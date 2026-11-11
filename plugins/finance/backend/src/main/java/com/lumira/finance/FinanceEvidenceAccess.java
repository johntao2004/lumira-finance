package com.lumira.finance;

import com.lumira.platform.common.AuthorizationContextProjectionPort;
import com.lumira.platform.identity.api.AuthenticationRequestContext;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * EV03-local scope/identity adapter. Does not alter existing financial workflows or their audit.
 */
abstract class FinanceEvidenceAccess {
    static final Set<String> SQL_CODES =
            Set.of(
                    "INVALID_INPUT",
                    "SCOPE_DENIED",
                    "SELF_APPROVAL_FORBIDDEN",
                    "VERSION_CONFLICT",
                    "IDEMPOTENCY_CONFLICT",
                    "EVIDENCE_HOME_SCOPE_UNSUPPORTED",
                    "EVIDENCE_IDENTITY_CONFLICT",
                    "EVIDENCE_BASIS_UNAVAILABLE",
                    "EVIDENCE_BASIS_REVIEW_PENDING",
                    "EVIDENCE_BASIS_STALE",
                    "EVIDENCE_ACTIVE_RESERVATIONS",
                    "EVIDENCE_CAPACITY_BELOW_COMMITTED",
                    "EVIDENCE_CAPACITY_EXCEEDED",
                    "EVIDENCE_SUBMISSION_EXPIRED",
                    "EVIDENCE_CONSUMER_STATE_CONFLICT",
                    "EVIDENCE_REVERSE_EXCEEDS_REMAINING",
                    "EVIDENCE_REVIEW_EXPIRED",
                    "AP_PARENT_IN_USE",
                    "AP_BASIS_STALE",
                    "JOURNAL_PERSON_MODE_REQUIRED",
                    "PERIOD_CLOSED",
                    "EVIDENCE_PAGE_STALE");
    final FinanceStore db;
    final FinanceService finance;
    final FinanceJournalPersonGate gate;
    final PlatformTransactionManager transactions;
    final AuthorizationContextProjectionPort authorization;

    FinanceEvidenceAccess(
            FinanceStore db,
            FinanceService finance,
            FinanceJournalPersonGate gate,
            PlatformTransactionManager transactions,
            AuthorizationContextProjectionPort authorization) {
        this.db = db;
        this.finance = finance;
        this.gate = gate;
        this.transactions = transactions;
        this.authorization = authorization;
    }

    static Map<String, Object> map(Object... pairs) {
        return FinanceJournalPersonGate.map(pairs);
    }

    static FinanceException error(String code, String message) {
        return new FinanceException(code, message);
    }

    Map<String, Object> json(Object value) {
        return gate.decode(value);
    }

    static String money8(Object value) {
        return new BigDecimal(value.toString()).setScale(8).toPlainString();
    }

    static boolean pending(Map<String, Object> row) {
        return "PENDING".equals(row.get("status"));
    }

    static boolean fresh(Map<String, Object> row) {
        return ((Timestamp) row.get("expires_at")).toInstant().isAfter(Instant.now());
    }

    static String count(Object value) {
        return value == null ? null : value.toString();
    }

    Map<String, Object> row(String table, Map<String, Object> b, UUID y, UUID id) {
        if (!Set.of(
                        "document",
                        "review",
                        "allocation",
                        "submission",
                        "usage",
                        "confirmation",
                        "reverse_request")
                .contains(table)) throw new IllegalArgumentException();
        return db.one(
                "SELECT * FROM plugin_finance_evidence_"
                        + table
                        + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                b.get("tenant_id"),
                b.get("id"),
                y,
                id);
    }

    boolean allows(Map<String, Object> b, String action) {
        var c = AuthenticationRequestContext.current();
        if (c == null
                || !Objects.equals(c.userId(), b.get("actor_id"))
                || c.authorization() == null
                || !c.authorization().effectiveAuthorities().contains("platform:finance:" + action))
            return false;
        String role = b.get("role").toString();
        return action.equals("read")
                || role.equals("OWNER")
                || action.equals("create") && role.equals("MAKER")
                || action.equals("approve") && role.equals("REVIEWER");
    }

    boolean samePerson(Map<String, Object> b, Map<String, Object> identity) {
        var current = actor(b);
        return current.get("personId") != null
                && current.get("personId").toString().equals(identity.get("personId").toString());
    }

    void recheckMaker(Map<String, Object> b, UUID year, Map<String, Object> maker, boolean manage) {
        try {
            UUID account = FinanceEvidenceInput.uuid(maker.get("accountId"));
            var member =
                    db.one(
                            "SELECT username FROM plugin_finance_member WHERE tenant_id=? AND"
                                    + " book_id=? AND user_id=? AND enabled",
                            b.get("tenant_id"),
                            b.get("id"),
                            account);
            var current =
                    finance.originalScope(
                            member.get("username").toString(), (UUID) b.get("id"), year, "create");
            finance.originalScope(
                    member.get("username").toString(), (UUID) b.get("id"), year, "read");
            if (!account.equals(current.get("actor_id"))) throw new IllegalArgumentException();
            if (manage)
                finance.originalScope(
                        member.get("username").toString(), (UUID) b.get("id"), year, "manage");
            if (!maker.equals(gate.identity((UUID) b.get("tenant_id"), account)))
                throw new IllegalArgumentException();
        } catch (RuntimeException stale) {
            throw error("EVIDENCE_BASIS_STALE", "原制备人当前权限或组织绑定失效，不能新增生效；可独立终结待审");
        }
    }

    void homeCollision(
            String actor, Map<String, Object> b, UUID year, UUID homeBook, UUID homeYear) {
        if (!Objects.equals(homeBook, b.get("id")) || !Objects.equals(homeYear, year)) {
            finance.originalScope(actor, homeBook, homeYear, "read");
            throw error("EVIDENCE_HOME_SCOPE_UNSUPPORTED", "该永久单据或事项已有原归属，首片不支持跨账套年度引用");
        }
    }

    Object tx(boolean write, Supplier<Object> work) {
        var tx = new TransactionTemplate(transactions);
        tx.setReadOnly(!write);
        tx.setIsolationLevel(
                write
                        ? TransactionDefinition.ISOLATION_READ_COMMITTED
                        : TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setTimeout(10);
        try {
            return tx.execute(status -> work.get());
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && "P0001".equals(sql.getSQLState())) {
                    String code =
                            sql.getMessage().replaceFirst("^ERROR: ", "").split("[\\r\\n]", 2)[0];
                    if (SQL_CODES.contains(code))
                        throw error(code, "单据依据、额度或当前状态不满足操作条件，请刷新核对（" + code + "）");
                }
            }
            throw failure;
        }
    }

    Map<String, Object> scope(String actor, UUID book, UUID year, String op) {
        var b = finance.originalScope(actor, book, year, "read");
        if (op == null) return b;
        String action =
                switch (op) {
                    case "register",
                            "review-create",
                            "review-cancel",
                            "reverse-create",
                            "reverse-cancel" ->
                            "manage";
                    case "review-approve",
                            "review-reject",
                            "confirm",
                            "reject",
                            "admin-terminate",
                            "reverse-approve",
                            "reverse-reject" ->
                            "approve";
                    default -> "create";
                };
        finance.originalScope(actor, book, year, action);
        if (action.equals("manage")) finance.originalScope(actor, book, year, "create");
        return b;
    }

    Map<String, Object> locked(String actor, UUID book, UUID year, String op, String key) {
        var b = scope(actor, book, year, op);
        db.list(
                "SELECT plugin_finance_evidence_prelock(?,?)",
                b.get("tenant_id"),
                b.get("legal_entity_id"));
        db.list(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                b.get("actor_id") + ":" + key);
        db.one(
                "SELECT id FROM plugin_finance_book WHERE tenant_id=? AND id=? FOR UPDATE",
                b.get("tenant_id"),
                book);
        b = scope(actor, book, year, op);
        finance.lockYearWrites(b, List.of(year));
        if (!gate.enabled(b, year))
            throw error("JOURNAL_PERSON_MODE_REQUIRED", "费用份额首片只支持已启用人员协议的V4年度");
        boolean open =
                Set.of(
                                "register",
                                "review-create",
                                "review-approve",
                                "create",
                                "edit",
                                "submit",
                                "confirm")
                        .contains(op);
        var y =
                db.one(
                        "SELECT * FROM plugin_finance_year WHERE tenant_id=? AND book_id=? AND"
                                + " id=?",
                        b.get("tenant_id"),
                        book,
                        year);
        if (!"V4".equals(y.get("precision_profile"))
                || Boolean.TRUE.equals(y.get("precision_review_required")))
            throw error("LEGACY_PRECISION_REVIEW_REQUIRED", "旧精度年度不能新增此协议数据");
        if (open && !"OPEN".equals(y.get("status"))) throw error("PERIOD_CLOSED", "新用须处于开放年度");
        db.list(
                "SELECT plugin_finance_evidence_scope(?,?,?,?,NULL::date)",
                b.get("tenant_id"),
                book,
                year,
                open);
        return b;
    }

    Map<String, Object> who(Map<String, Object> b, String op, HttpServletRequest request) {
        boolean recent =
                Set.of(
                                "register",
                                "review-create",
                                "review-approve",
                                "review-reject",
                                "review-cancel",
                                "confirm",
                                "reject",
                                "admin-terminate",
                                "reverse-create",
                                "reverse-approve",
                                "reverse-reject",
                                "reverse-cancel")
                        .contains(op);
        return gate.current((UUID) b.get("actor_id"), (UUID) b.get("tenant_id"), recent, request);
    }

    Map<String, Object> actor(Map<String, Object> b) {
        UUID account = (UUID) b.get("actor_id");
        var rows =
                db.list(
                        "SELECT x.person_id,x.status FROM plugin_finance_person_binding x JOIN"
                                + " plugin_finance_person p"
                                + " ON(p.tenant_id,p.id)=(x.tenant_id,x.person_id) JOIN"
                                + " plugin_finance_person_realm r ON r.tenant_id=x.tenant_id WHERE"
                                + " x.tenant_id=? AND x.account_id=?",
                        b.get("tenant_id"),
                        account);
        boolean configured =
                !db.list(
                                "SELECT tenant_id FROM plugin_finance_person_realm WHERE"
                                        + " tenant_id=?",
                                b.get("tenant_id"))
                        .isEmpty();
        String available =
                !configured
                        ? "UNCONFIGURED"
                        : rows.isEmpty()
                                ? "UNBOUND"
                                : "ACTIVE".equals(rows.getFirst().get("status"))
                                        ? "AVAILABLE"
                                        : "REVOKED";
        return map(
                "accountId",
                account,
                "personId",
                available.equals("AVAILABLE") ? rows.getFirst().get("person_id") : null,
                "identityAvailability",
                available);
    }

    String authorizationDigest(String principal, Map<String, Object> b) {
        var context =
                authorization
                        .current(principal)
                        .orElseThrow(() -> error("SCOPE_DENIED", "公开授权上下文不可用"));
        var grants =
                db.list(
                        "SELECT year_id FROM plugin_finance_member_year WHERE tenant_id=? AND"
                                + " book_id=? AND user_id=? ORDER BY year_id",
                        b.get("tenant_id"),
                        b.get("id"),
                        b.get("actor_id"));
        var realm =
                db.list(
                        "SELECT authority_id,manifest_sha256,generation FROM"
                                + " plugin_finance_person_realm WHERE tenant_id=?",
                        b.get("tenant_id"));
        var binding =
                db.list(
                        "SELECT id,person_id,status,version FROM plugin_finance_person_binding"
                                + " WHERE tenant_id=? AND account_id=?",
                        b.get("tenant_id"),
                        b.get("actor_id"));
        return FinanceService.hash(
                finance.encode(
                        finance.canonical(
                                map(
                                        "actor",
                                        b.get("actor_id"),
                                        "authorities",
                                        new TreeSet<>(context.effectiveAuthorities()),
                                        "globalAuthorities",
                                        new TreeSet<>(context.effectiveGlobalAuthorities()),
                                        "previewActive",
                                        context.previewActive(),
                                        "targetRole",
                                        context.targetRoleId(),
                                        "targetRoleVersion",
                                        context.targetRoleVersion(),
                                        "role",
                                        b.get("role"),
                                        "yearScope",
                                        b.get("year_scope"),
                                        "yearIds",
                                        grants,
                                        "realm",
                                        realm,
                                        "binding",
                                        binding))));
    }

    Map<String, Object> original(Map<String, Object> b, UUID year, Object id) {
        UUID original = FinanceEvidenceInput.uuid(id);
        var r =
                db.one(
                        "SELECT id,version,filename,sha256,created_by FROM plugin_finance_original"
                                + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        original);
        return map(
                "bookId",
                b.get("id"),
                "yearId",
                year,
                "originalId",
                r.get("id"),
                "version",
                r.get("version").toString(),
                "filename",
                r.get("filename"),
                "sha256",
                r.get("sha256"),
                "verificationStatus",
                "UNVERIFIED_ORIGINAL_BYTES",
                "createdBy",
                r.get("created_by"));
    }

    void monthOpen(Map<String, Object> b, UUID year, Object value) {
        LocalDate date = LocalDate.parse(value.toString());
        var y =
                db.one(
                        "SELECT year FROM plugin_finance_year WHERE tenant_id=? AND book_id=? AND"
                                + " id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year);
        if (date.getYear() != ((Number) y.get("year")).intValue())
            throw FinanceException.invalid("发生日期不属于当前年度");
        var p =
                db.one(
                        "SELECT status FROM plugin_finance_period WHERE tenant_id=? AND book_id=?"
                                + " AND year_id=? AND month=? FOR UPDATE",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        date.getMonthValue());
        if (!"OPEN".equals(p.get("status"))) throw error("PERIOD_CLOSED", "费用新用的发生月份须开放");
    }

    void independent(Map<String, Object> who, Map<String, Object> other) {
        if (Objects.equals(who.get("personId"), other.get("personId")))
            throw error("SELF_APPROVAL_FORBIDDEN", "同一自然人不得通过其他账号自审");
    }

    void sameAccount(Map<String, Object> who, Map<String, Object> maker) {
        if (!Objects.equals(who.get("accountId"), maker.get("accountId")))
            throw error("SCOPE_DENIED", "仅原制备账号可执行此操作");
    }

    static Map<String, String> query(HttpServletRequest request, Set<String> allowed) {
        var out = new LinkedHashMap<String, String>();
        if (request == null) return out;
        for (var entry : request.getParameterMap().entrySet()) {
            if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1)
                throw FinanceException.invalid("查询参数未知或重复");
            out.put(entry.getKey(), entry.getValue()[0]);
        }
        return out;
    }

    static int limit(Map<String, String> query) {
        String s = query.getOrDefault("limit", "20");
        if (!s.matches("[1-9][0-9]{0,2}")) throw FinanceException.invalid("分页大小无效");
        int n = Integer.parseInt(s);
        if (n > 100) throw FinanceException.invalid("分页最多100条");
        return n;
    }

    String token(Map<String, Object> value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        finance.encode(finance.canonical(value)).getBytes(StandardCharsets.UTF_8));
    }

    Map<String, Object> untoken(String value) {
        try {
            if (value.length() > 4096 || !value.matches("[A-Za-z0-9_-]+"))
                throw new IllegalArgumentException();
            return finance.decode(
                    new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw FinanceException.invalid("分页游标无效");
        }
    }

    String payloadHash(Map<String, Object> normalized) {
        var payload = new LinkedHashMap<>(normalized);
        payload.remove("idempotencyKey");
        return FinanceService.hash(finance.encode(finance.canonical(payload)));
    }

    Map<String, Object> cached(
            Map<String, Object> b, UUID year, String route, Map<String, Object> n) {
        var rows =
                db.list(
                        "SELECT * FROM plugin_finance_evidence_request WHERE actor_id=? AND"
                                + " request_key=?",
                        b.get("actor_id"),
                        n.get("idempotencyKey"));
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        if (!Objects.equals(row.get("tenant_id"), b.get("tenant_id"))
                || !Objects.equals(row.get("book_id"), b.get("id"))
                || !Objects.equals(row.get("year_id"), year)
                || !Objects.equals(row.get("route"), route)
                || !Objects.equals(row.get("payload_hash"), payloadHash(n)))
            throw error("IDEMPOTENCY_CONFLICT", "请求键已用于其他范围或内容");
        return map(
                "kind",
                row.get("result_kind"),
                "id",
                row.get("result_id").toString(),
                "documentId",
                row.get("document_id").toString(),
                "allocationId",
                row.get("allocation_id") == null ? null : row.get("allocation_id").toString());
    }

    void remember(
            Map<String, Object> b,
            UUID year,
            String route,
            Map<String, Object> n,
            Map<String, Object> result) {
        String kind = result.get("kind").toString();
        Object id = FinanceEvidenceInput.uuid(result.get("id"));
        db.update(
                "INSERT INTO"
                    + " plugin_finance_evidence_request(actor_id,request_key,tenant_id,legal_entity_id,book_id,year_id,route,payload_hash,result_kind,document_id,review_id,allocation_id,reverse_request_id)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                b.get("actor_id"),
                n.get("idempotencyKey"),
                b.get("tenant_id"),
                b.get("legal_entity_id"),
                b.get("id"),
                year,
                route,
                payloadHash(n),
                kind,
                FinanceEvidenceInput.uuid(result.get("documentId")),
                kind.equals("REVIEW") ? id : null,
                result.get("allocationId") == null
                        ? null
                        : FinanceEvidenceInput.uuid(result.get("allocationId")),
                kind.equals("REVERSE") ? id : null);
    }

    Map<String, Object> command(
            String function,
            Map<String, Object> b,
            UUID year,
            Map<String, Object> who,
            String op,
            UUID target,
            UUID child,
            Map<String, Object> normalized) {
        if (!Set.of("document", "allocation").contains(function))
            throw new IllegalArgumentException();
        return finance.decode(
                db.one(
                                "SELECT plugin_finance_evidence_"
                                        + function
                                        + "_command(?,?,?,?::jsonb,?,?,?,?::jsonb)::text result",
                                b.get("tenant_id"),
                                b.get("id"),
                                year,
                                finance.encode(who),
                                op,
                                target,
                                child,
                                finance.encode(normalized))
                        .get("result")
                        .toString());
    }

    Map<String, Object> page(
            String principal,
            Map<String, Object> b,
            UUID year,
            String resource,
            HttpServletRequest request,
            Function<Map<String, Object>, Map<String, Object>> projection) {
        boolean documents = resource.equals("documents");
        if (!documents && !resource.equals("allocations")) throw FinanceException.invalid("分页资源无效");
        var q =
                query(
                        request,
                        documents
                                ? Set.of("limit", "cursor")
                                : Set.of("limit", "cursor", "documentId"));
        int limit = limit(q);
        UUID filter =
                q.containsKey("documentId") ? FinanceEvidenceInput.uuid(q.get("documentId")) : null;
        var guard =
                db.one(
                        "SELECT generation FROM plugin_finance_evidence_home_guard WHERE"
                                + " tenant_id=? AND book_id=? AND year_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year);
        String generation = guard.get("generation").toString();
        var realm =
                db.list(
                        "SELECT generation FROM plugin_finance_person_realm WHERE tenant_id=?",
                        b.get("tenant_id"));
        var context =
                map(
                        "schemaVersion",
                        FinanceEvidenceInput.SCHEMA,
                        "resource",
                        resource,
                        "actorId",
                        b.get("actor_id").toString(),
                        "authorizationDigest",
                        authorizationDigest(principal, b),
                        "identityGeneration",
                        realm.isEmpty() ? null : realm.getFirst().get("generation").toString(),
                        "tenantId",
                        b.get("tenant_id").toString(),
                        "legalEntityId",
                        b.get("legal_entity_id").toString(),
                        "bookId",
                        b.get("id").toString(),
                        "yearId",
                        year.toString(),
                        "generation",
                        generation,
                        "documentId",
                        filter == null ? null : filter.toString(),
                        "limit",
                        limit);
        Timestamp anchorTime = null;
        UUID anchorId = null;
        if (q.containsKey("cursor")) {
            var cursor = untoken(q.get("cursor"));
            var expected = new HashSet<>(context.keySet());
            expected.add("anchorCreatedAt");
            expected.add("anchorId");
            if (!cursor.keySet().equals(expected)) throw FinanceException.invalid("分页游标结构无效");
            for (var entry : context.entrySet()) {
                if (Set.of("generation", "authorizationDigest", "identityGeneration")
                        .contains(entry.getKey())) continue;
                Object actual = cursor.get(entry.getKey());
                if (!Objects.equals(entry.getValue(), actual)) {
                    throw FinanceException.invalid("分页游标属于其他资源、调用者或筛选范围");
                }
            }
            for (String key : List.of("generation", "authorizationDigest", "identityGeneration"))
                if (!Objects.equals(context.get(key), cursor.get(key)))
                    throw error("EVIDENCE_PAGE_STALE", "目录、额度或授权上下文变化，请从首屏重新读取");
            try {
                anchorTime =
                        Timestamp.from(
                                Instant.parse(
                                        FinanceEvidenceInput.text(
                                                cursor.get("anchorCreatedAt"), 40, false)));
                anchorId = FinanceEvidenceInput.uuid(cursor.get("anchorId"));
            } catch (RuntimeException invalid) {
                throw FinanceException.invalid("分页位置无效");
            }
        }
        var args = new ArrayList<Object>(List.of(b.get("tenant_id"), b.get("id"), year));
        var sql =
                new StringBuilder("SELECT * FROM plugin_finance_evidence_")
                        .append(documents ? "document" : "allocation")
                        .append(" WHERE tenant_id=? AND book_id=? AND year_id=?");
        if (filter != null) {
            sql.append(" AND document_id=?");
            args.add(filter);
        }
        if (anchorTime != null) {
            sql.append(" AND(created_at,id)<(?,?)");
            args.add(anchorTime);
            args.add(anchorId);
        }
        sql.append(" ORDER BY created_at DESC,id DESC LIMIT ?");
        args.add(limit + 1);
        var rows = db.list(sql.toString(), args.toArray());
        boolean more = rows.size() > limit;
        var shown = rows.subList(0, Math.min(limit, rows.size()));
        String next = null;
        if (more) {
            var last = shown.getLast();
            var cursor = new LinkedHashMap<>(context);
            cursor.put(
                    "anchorCreatedAt", ((Timestamp) last.get("created_at")).toInstant().toString());
            cursor.put("anchorId", last.get("id").toString());
            next = token(cursor);
        }
        return map(
                "items",
                shown.stream().map(projection).toList(),
                "nextCursor",
                next,
                "generation",
                generation,
                "currentActor",
                actor(b));
    }

    Map<String, Object> basis(Map<String, Object> b, UUID y, Map<String, Object> review) {
        var content = json(review.get("content"));
        var doc = row("document", b, y, (UUID) review.get("document_id"));
        return map(
                "reviewId",
                review.get("id"),
                "basisVersion",
                count(review.get("published_basis_version")),
                "contentDigest",
                review.get("content_digest"),
                "documentType",
                content.get("documentType"),
                "issuerKey",
                content.get("issuerKey"),
                "externalIdentityKey",
                content.get("externalIdentityKey"),
                "currency",
                "CNY",
                "faceGross",
                money8(content.get("faceGross")),
                "expenseCapacity",
                money8(content.get("expenseCapacity")),
                "sourceOriginal",
                json(doc.get("source_snapshot")),
                "basisOriginal",
                json(review.get("basis_original_snapshot")),
                "reviewedBy",
                json(review.get("decided_by")),
                "reviewedAt",
                review.get("decided_at"),
                "assurance",
                "ORG_REVIEWED",
                "authenticity",
                "UNVERIFIED",
                "sourceType",
                content.get("sourceType"),
                "originalLocation",
                content.get("originalLocation"));
    }

    Map<String, Object> modeRead(Map<String, Object> b, UUID year) {
        return db.one(
                "SELECT mode FROM plugin_finance_journal_person_mode WHERE tenant_id=? AND"
                        + " book_id=? AND year_id=?",
                b.get("tenant_id"),
                b.get("id"),
                year);
    }

    boolean openRead(Map<String, Object> b, UUID year) {
        var y =
                db.one(
                        "SELECT status,precision_profile,precision_review_required FROM"
                                + " plugin_finance_year WHERE tenant_id=? AND book_id=? AND id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year);
        return "OPEN".equals(y.get("status"))
                && "V4".equals(y.get("precision_profile"))
                && !Boolean.TRUE.equals(y.get("precision_review_required"))
                && "JOURNAL_PERSON_SOD".equals(modeRead(b, year).get("mode"));
    }
}

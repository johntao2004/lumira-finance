package com.lumira.finance;

import com.lumira.platform.common.AuthorizationContextProjectionPort;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Independent organization declarations; not an accounting, bank, tax or government identity
 * service.
 */
@Service
public class FinanceCounterparties extends FinanceCounterpartyAccess {
    @Autowired
    public FinanceCounterparties(
            FinanceStore db,
            FinanceService finance,
            FinanceJournalPersonGate gate,
            PlatformTransactionManager tx,
            AuthorizationContextProjectionPort auth) {
        super(db, finance, gate, tx, auth);
    }

    static final String SELECT =
            "SELECT"
                + " p.tenant_id,p.legal_entity_id,p.id,p.home_book_id,p.namespace,p.jurisdiction,p.identifier,p.version,p.latest_revision_id,p.pending_revision_id,p.approved_revision_id,p.created_by,p.created_at,p.updated_at,a.id"
                + " app_id,a.revision_no app_revision,a.version app_version,a.status"
                + " app_status,a.desired_status app_desired,a.content_digest"
                + " app_digest,a.content->>'displayName' app_name FROM"
                + " plugin_finance_counterparty_registry p LEFT JOIN"
                + " plugin_finance_counterparty_revision a"
                + " ON(a.tenant_id,a.legal_entity_id,a.id)=(p.tenant_id,p.legal_entity_id,p.approved_revision_id)"
                + " ";

    static String uuidText(Object x) {
        return x == null ? null : x.toString();
    }

    Map<String, Object> identity(Map<String, Object> p) {
        return map(
                "namespace",
                p.get("namespace"),
                "jurisdiction",
                p.get("jurisdiction"),
                "identifier",
                p.get("identifier"),
                "verification",
                "ORGANIZATION_DECLARED_NOT_GOVERNMENT_VERIFIED");
    }

    Map<String, Object> ref(Map<String, Object> r) {
        return map(
                "id",
                r.get("id"),
                "revision",
                r.get("revision_no").toString(),
                "version",
                r.get("version").toString(),
                "status",
                r.get("status"),
                "desiredStatus",
                r.get("desired_status"),
                "contentDigest",
                r.get("content_digest"));
    }

    Map<String, Object> summary(Map<String, Object> p) {
        Object approved =
                p.get("app_id") == null
                        ? null
                        : map(
                                "id",
                                p.get("app_id"),
                                "revision",
                                p.get("app_revision").toString(),
                                "version",
                                p.get("app_version").toString(),
                                "status",
                                p.get("app_status"),
                                "desiredStatus",
                                p.get("app_desired"),
                                "contentDigest",
                                p.get("app_digest"));
        return map(
                "id",
                p.get("id"),
                "governanceHomeBookId",
                p.get("home_book_id"),
                "registryVersion",
                p.get("version").toString(),
                "identity",
                identity(p),
                "effectiveStatus",
                p.get("app_desired"),
                "effectiveDisplayName",
                p.get("app_name"),
                "effectiveRevision",
                approved,
                "pendingRevisionId",
                p.get("pending_revision_id"),
                "latestRevisionId",
                p.get("latest_revision_id"),
                "newUseEligible",
                "ACTIVE".equals(p.get("app_desired")) && p.get("pending_revision_id") == null,
                "createdBy",
                json(p.get("created_by")).get("accountId"),
                "createdAt",
                p.get("created_at"));
    }

    Map<String, Object> fullParty(Call call, Map<String, Object> b, UUID target) {
        return call.one(
                SELECT + " WHERE p.tenant_id=? AND p.legal_entity_id=? AND p.id=?",
                b.get("tenant_id"),
                b.get("legal_entity_id"),
                target);
    }

    Map<String, Object> publicScope(Map<String, Object> b, Map<String, Object> p) {
        return map(
                "tenantId",
                b.get("tenant_id"),
                "legalEntityId",
                b.get("legal_entity_id"),
                "currentBookId",
                b.get("id"),
                "governanceHomeBookId",
                p.get("home_book_id"),
                "bothBooksReadAuthorized",
                true);
    }

    Map<String, Object> decision(Call call, Map<String, Object> r) {
        if (r.get("decision_id") == null) return null;
        var d =
                call.one(
                        "SELECT * FROM plugin_finance_counterparty_decision WHERE tenant_id=? AND"
                                + " legal_entity_id=? AND id=?",
                        r.get("tenant_id"),
                        r.get("legal_entity_id"),
                        r.get("decision_id"));
        var actor = json(d.get("actor"));
        return map(
                "id",
                d.get("id"),
                "kind",
                d.get("kind"),
                "actorAccountId",
                actor.get("accountId"),
                "actorPersonId",
                actor.get("personId"),
                "reason",
                d.get("reason"),
                "decidedAt",
                d.get("created_at"));
    }

    Map<String, Object> actions(
            Call call,
            String principal,
            Map<String, Object> b,
            Map<String, Object> p,
            Map<String, Object> r,
            Map<String, Object> actor,
            HttpServletRequest request) {
        var allowed = new ArrayList<String>();
        var blockers = new ArrayList<Map<String, Object>>();
        boolean home = Objects.equals(b.get("id"), p.get("home_book_id"));
        boolean available = "AVAILABLE".equals(actor.get("identityAvailability"));
        if (!home)
            blockers.add(
                    map("code", "COUNTERPARTY_HOME_SCOPE_REQUIRED", "message", "请在治理账套提出或决定修订"));
        if (!available)
            blockers.add(
                    map(
                            "code",
                            "COUNTERPARTY_IDENTITY_UNAVAILABLE",
                            "message",
                            "写入需普通有效组织人员绑定，读取不要求该资格"));
        boolean recent = false;
        if (home && available) {
            try {
                who(call, b, request);
                recent = true;
            } catch (FinanceException f) {
                if (!Set.of("PERSON_AUTH_ASSURANCE_REQUIRED", "COUNTERPARTY_IDENTITY_UNAVAILABLE")
                        .contains(f.code)) throw f;
                blockers.add(map("code", f.code, "message", "写入需当前已验证近期TOTP登录会话；不代表逐动作step-up"));
            }
        }
        if (home && available && recent) {
            boolean owner =
                    b.get("role").equals("OWNER")
                            && allowed(b, "read")
                            && allowed(b, "manage")
                            && allowed(b, "create");
            boolean reviewer =
                    Set.of("OWNER", "REVIEWER").contains(b.get("role"))
                            && allowed(b, "read")
                            && allowed(b, "approve");
            if (owner
                    && p.get("pending_revision_id") == null
                    && !"INACTIVE".equals(p.get("app_desired"))) allowed.add("PROPOSE_REVISION");
            if (r != null && r.get("status").equals("PENDING")) {
                var maker = json(r.get("maker"));
                if (owner
                        && uuidText(actor.get("accountId"))
                                .equals(uuidText(maker.get("accountId")))) allowed.add("CANCEL");
                if (reviewer && !samePerson(actor, maker)) {
                    allowed.add("REJECT");
                    try {
                        recheckMaker(call, b, r);
                        allowed.add("APPROVE");
                    } catch (FinanceException stale) {
                        if (!stale.code.equals("COUNTERPARTY_APPROVAL_STALE")) throw stale;
                        blockers.add(map("code", stale.code, "message", "原制备人当前资格已失效；独立复核人仍可驳回"));
                    }
                }
            }
        }
        if ("INACTIVE".equals(p.get("app_desired")))
            blockers.add(
                    map(
                            "code",
                            "COUNTERPARTY_STATE_CONFLICT",
                            "message",
                            "已停用为终态，身份永久保留，不能恢复或新建替身"));
        return map("allowedActions", allowed, "blockers", blockers);
    }

    Map<String, Object> detail(
            Call call,
            String principal,
            Map<String, Object> b,
            UUID target,
            HttpServletRequest request) {
        var p = fullParty(call, b, target);
        homeRead(call, principal, b, p);
        var actor = actor(call, b);
        Map<String, Object> pending =
                p.get("pending_revision_id") == null
                        ? null
                        : revision(call, p, (UUID) p.get("pending_revision_id"));
        var actions = actions(call, principal, b, p, pending, actor, request);
        Object content =
                p.get("approved_revision_id") == null
                        ? null
                        : json(
                                revision(call, p, (UUID) p.get("approved_revision_id"))
                                        .get("content"));
        return map(
                "schemaVersion",
                FinanceCounterpartyInput.SCHEMA,
                "scope",
                publicScope(b, p),
                "currentActor",
                actor,
                "counterparty",
                summary(p),
                "effectiveContent",
                content,
                "allowedActions",
                actions.get("allowedActions"),
                "blockers",
                actions.get("blockers"),
                "consumerImplemented",
                false,
                "bankAccountSupported",
                false,
                "externalIdentityVerified",
                false);
    }

    Map<String, Object> revisionDetail(
            Call call,
            String principal,
            Map<String, Object> b,
            UUID target,
            UUID revisionId,
            HttpServletRequest request) {
        var p = fullParty(call, b, target);
        homeRead(call, principal, b, p);
        var r = revision(call, p, revisionId);
        var actor = actor(call, b);
        var actions = actions(call, principal, b, p, r, actor, request);
        var maker = json(r.get("maker"));
        return map(
                "schemaVersion",
                FinanceCounterpartyInput.SCHEMA,
                "scope",
                publicScope(b, p),
                "currentActor",
                actor,
                "counterpartyId",
                p.get("id"),
                "identity",
                identity(p),
                "revision",
                ref(r),
                "previousRevisionId",
                r.get("previous_revision_id"),
                "baseApprovedRevisionId",
                r.get("base_approved_revision_id"),
                "content",
                json(r.get("content")),
                "reason",
                r.get("reason"),
                "makerAccountId",
                maker.get("accountId"),
                "makerPersonId",
                maker.get("personId"),
                "createdAt",
                r.get("created_at"),
                "decision",
                decision(call, r),
                "allowedActions",
                actions.get("allowedActions"),
                "blockers",
                actions.get("blockers"),
                "counterparty",
                summary(p));
    }

    record Query(int limit, String cursor, String status) {}

    Query query(String op, HttpServletRequest request) {
        var q =
                FinanceEvidenceAccess.query(
                        request,
                        op.equals("list")
                                ? Set.of("limit", "cursor", "status")
                                : op.equals("revisions") ? Set.of("limit", "cursor") : Set.of());
        int limit = 20;
        if (q.containsKey("limit")) {
            String value = q.get("limit");
            if (!value.matches("[1-9][0-9]{0,2}") || (limit = Integer.parseInt(value)) > 100)
                throw FinanceException.invalid("分页数量须1..100");
        }
        String status = q.getOrDefault("status", "ALL");
        if (!Set.of("ALL", "ACTIVE", "FROZEN", "INACTIVE", "UNPUBLISHED", "PENDING")
                .contains(status)) throw FinanceException.invalid("目录筛选无效");
        String cursor = q.get("cursor");
        if (cursor != null && (cursor.isEmpty() || cursor.length() > 4096))
            throw FinanceException.invalid("分页token无效");
        return new Query(limit, cursor, status);
    }

    public Object read(
            String principal,
            UUID book,
            String op,
            UUID target,
            UUID revision,
            HttpServletRequest request) {
        if (!Set.of("list", "detail", "revisions", "revision").contains(op))
            throw FinanceException.invalid("操作无效");
        var q = query(op, request);
        return run(
                false,
                call -> {
                    var b = scope(call, principal, book, null);
                    if (op.equals("detail")) return detail(call, principal, b, target, request);
                    if (op.equals("revision"))
                        return revisionDetail(call, principal, b, target, revision, request);
                    return page(call, principal, b, op, target, q);
                });
    }

    Map<String, Object> page(
            Call call, String principal, Map<String, Object> b, String op, UUID target, Query q) {
        Map<String, Object> p = null;
        UUID home = (UUID) b.get("id");
        if (op.equals("revisions")) {
            p = party(call, b, target);
            homeRead(call, principal, b, p);
            home = (UUID) p.get("home_book_id");
        }
        var g = guard(call, b, home);
        var actor = actor(call, b);
        String auth = authorizationDigest(call, principal, b);
        if (p != null) {
            var h = scope(call, principal, home, null);
            auth = hash(map("current", auth, "home", authorizationDigest(call, principal, h)));
        }
        var expected =
                map(
                        "schema",
                        FinanceCounterpartyInput.SCHEMA,
                        "resource",
                        op,
                        "actor",
                        uuidText(b.get("actor_id")),
                        "tenant",
                        uuidText(b.get("tenant_id")),
                        "entity",
                        uuidText(b.get("legal_entity_id")),
                        "book",
                        uuidText(b.get("id")),
                        "home",
                        home.toString(),
                        "target",
                        target == null ? "" : target.toString(),
                        "status",
                        q.status,
                        "limit",
                        q.limit,
                        "epoch",
                        uuidText(g.get("epoch")),
                        "generation",
                        g.get("generation"),
                        "auth",
                        auth);
        Map<String, Object> cursor = null;
        if (q.cursor != null) {
            cursor = untoken(q.cursor);
            var keys = new HashSet<>(expected.keySet());
            keys.add("anchorId");
            keys.add("anchorValue");
            if (!cursor.keySet().equals(keys)) throw FinanceException.invalid("分页结构无效");
            for (String k : expected.keySet())
                if (!Set.of("epoch", "generation", "auth").contains(k)
                        && !Objects.equals(cursor.get(k), expected.get(k)))
                    throw FinanceException.invalid("分页资源、账号或筛选不匹配");
            for (String k : List.of("epoch", "generation", "auth"))
                if (!(cursor.get(k) instanceof String)
                        || !Objects.equals(cursor.get(k), expected.get(k)))
                    throw error("COUNTERPARTY_PAGE_STALE", "目录或授权上下文已改变，请重新读取首页");
            FinanceCounterpartyInput.uuid(cursor.get("anchorId"));
            if (!(cursor.get("anchorValue") instanceof String))
                throw FinanceException.invalid("分页位置无效");
        }
        var args = new ArrayList<Object>();
        args.add(b.get("tenant_id"));
        args.add(b.get("legal_entity_id"));
        List<Map<String, Object>> rows;
        String anchorId = null, anchorValue = null;
        if (op.equals("list")) {
            args.add(home);
            String filter =
                    switch (q.status) {
                        case "PENDING" -> " AND p.pending_revision_id IS NOT NULL";
                        case "UNPUBLISHED" -> " AND p.approved_revision_id IS NULL";
                        case "ALL" -> "";
                        default -> " AND a.desired_status='" + q.status + "'";
                    };
            String seek = "";
            if (cursor != null) {
                try {
                    Instant.parse(cursor.get("anchorValue").toString());
                } catch (RuntimeException bad) {
                    throw FinanceException.invalid("分页时间无效");
                }
                if (call.list(
                                SELECT
                                        + " WHERE p.tenant_id=? AND p.legal_entity_id=? AND"
                                        + " p.home_book_id=?"
                                        + filter
                                        + " AND p.created_at=?::timestamptz AND p.id=?::uuid",
                                b.get("tenant_id"),
                                b.get("legal_entity_id"),
                                home,
                                cursor.get("anchorValue"),
                                cursor.get("anchorId"))
                        .isEmpty()) throw FinanceException.invalid("分页锚点不属于当前目录筛选");
                seek = " AND(p.created_at,p.id)<(?::timestamptz,?::uuid)";
                args.add(cursor.get("anchorValue"));
                args.add(cursor.get("anchorId"));
            }
            args.add(q.limit + 1);
            rows =
                    call.list(
                            SELECT
                                    + " WHERE p.tenant_id=? AND p.legal_entity_id=? AND"
                                    + " p.home_book_id=?"
                                    + filter
                                    + seek
                                    + " ORDER BY p.created_at DESC,p.id DESC LIMIT ?",
                            args.toArray());
        } else {
            args.add(target);
            String seek = "";
            if (cursor != null) {
                FinanceCounterpartyInput.counter(cursor.get("anchorValue"));
                if (call.list(
                                "SELECT id FROM plugin_finance_counterparty_revision WHERE"
                                    + " tenant_id=? AND legal_entity_id=? AND counterparty_id=? AND"
                                    + " revision_no=?::bigint AND id=?::uuid",
                                b.get("tenant_id"),
                                b.get("legal_entity_id"),
                                target,
                                cursor.get("anchorValue"),
                                cursor.get("anchorId"))
                        .isEmpty()) throw FinanceException.invalid("分页锚点不属于当前版本历史");
                seek = " AND(revision_no,id)<(?::bigint,?::uuid)";
                args.add(cursor.get("anchorValue"));
                args.add(cursor.get("anchorId"));
            }
            args.add(q.limit + 1);
            rows =
                    call.list(
                            "SELECT id,revision_no,version,status,desired_status,content_digest"
                                + " FROM plugin_finance_counterparty_revision WHERE tenant_id=? AND"
                                + " legal_entity_id=? AND counterparty_id=?"
                                    + seek
                                    + " ORDER BY revision_no DESC,id DESC LIMIT ?",
                            args.toArray());
        }
        boolean more = rows.size() > q.limit;
        if (more) rows = new ArrayList<>(rows.subList(0, q.limit));
        var items = rows.stream().map(op.equals("list") ? this::summary : this::ref).toList();
        String next = null;
        if (more) {
            var last = rows.getLast();
            var n = new LinkedHashMap<>(expected);
            n.put("anchorId", uuidText(last.get("id")));
            n.put(
                    "anchorValue",
                    op.equals("list")
                            ? ((Timestamp) last.get("created_at")).toInstant().toString()
                            : last.get("revision_no").toString());
            next = token(n);
        }
        if (op.equals("list"))
            return map(
                    "schemaVersion",
                    FinanceCounterpartyInput.SCHEMA,
                    "currentActor",
                    actor,
                    "tenantId",
                    b.get("tenant_id"),
                    "legalEntityId",
                    b.get("legal_entity_id"),
                    "currentBookId",
                    b.get("id"),
                    "registryEpoch",
                    g.get("epoch"),
                    "generation",
                    g.get("generation"),
                    "limit",
                    q.limit,
                    "items",
                    items,
                    "nextCursor",
                    next,
                    "total",
                    null,
                    "totalStatus",
                    "NOT_COMPUTED");
        return map(
                "schemaVersion",
                FinanceCounterpartyInput.SCHEMA,
                "scope",
                publicScope(b, p),
                "currentActor",
                actor,
                "counterpartyId",
                target,
                "registryEpoch",
                g.get("epoch"),
                "generation",
                g.get("generation"),
                "limit",
                q.limit,
                "items",
                items,
                "nextCursor",
                next,
                "total",
                null,
                "totalStatus",
                "NOT_COMPUTED");
    }

    List<Map<String, Object>> duplicateScopes(
            Call call, String principal, Map<String, Object> b, Map<String, Object> input) {
        var identity = FinanceCounterpartyInput.object(input.get("identity"));
        var rows =
                call.list(
                        "SELECT * FROM plugin_finance_counterparty_registry WHERE tenant_id=? AND"
                                + " legal_entity_id=? AND(operation_id=? OR(namespace=? AND"
                                + " jurisdiction=? AND identifier=?))",
                        b.get("tenant_id"),
                        b.get("legal_entity_id"),
                        FinanceCounterpartyInput.uuid(input.get("operationId")),
                        identity.get("namespace"),
                        identity.get("jurisdiction"),
                        identity.get("identifier"));
        for (var p : rows) homeRead(call, principal, b, p);
        return rows;
    }

    public Object write(
            String principal,
            UUID book,
            String op,
            UUID target,
            UUID revisionId,
            Map<String, Object> input,
            HttpServletRequest request) {
        var n = FinanceCounterpartyInput.normalized(op, input);
        if (!request.getParameterMap().isEmpty()) throw FinanceException.invalid("写入不接受查询参数");
        return run(
                true,
                call -> {
                    var b = scope(call, principal, book, op);
                    who(call, b, request); // No identity-conflict disclosure before full caller
                    // qualification.
                    if (op.equals("create")) duplicateScopes(call, principal, b, n);
                    else {
                        var p = party(call, b, target);
                        homeRead(call, principal, b, p);
                        if (!Objects.equals(book, p.get("home_book_id")))
                            throw error("COUNTERPARTY_HOME_SCOPE_REQUIRED", "仅可在治理账套作出主数据变更");
                    }
                    if (call.list(
                                    "SELECT tenant_id FROM plugin_finance_person_realm WHERE"
                                            + " tenant_id=? FOR SHARE",
                                    b.get("tenant_id"))
                            .isEmpty())
                        throw error("COUNTERPARTY_IDENTITY_UNAVAILABLE", "组织人员目录尚未配置");
                    call.one(
                            "SELECT pg_advisory_xact_lock(hashtextextended(?,0)) value",
                            b.get("actor_id") + ":" + n.get("idempotencyKey"));
                    call.one(
                            "SELECT plugin_finance_counterparty_lock(?,?,?) value",
                            b.get("tenant_id"),
                            b.get("legal_entity_id"),
                            book);
                    b = scope(call, principal, book, op);
                    var who = who(call, b, request);
                    var authorizedDuplicates =
                            op.equals("create")
                                    ? duplicateScopes(call, principal, b, n)
                                    : List.<Map<String, Object>>of();
                    if (!op.equals("create") && !op.equals("propose"))
                        actionIdentity(
                                call,
                                b,
                                op,
                                revision(call, party(call, b, target), revisionId),
                                who);
                    String route = op + "/" + target + "/" + revisionId;
                    var payload = new LinkedHashMap<>(n);
                    payload.remove("idempotencyKey");
                    String hash = hash(payload);
                    var cached =
                            call.list(
                                    "SELECT * FROM plugin_finance_counterparty_request WHERE"
                                            + " actor_id=? AND request_key=?",
                                    b.get("actor_id"),
                                    n.get("idempotencyKey"));
                    UUID partyId, revision;
                    if (!cached.isEmpty()) {
                        var old = cached.getFirst();
                        if (!Objects.equals(old.get("tenant_id"), b.get("tenant_id"))
                                || !Objects.equals(
                                        old.get("legal_entity_id"), b.get("legal_entity_id"))
                                || !Objects.equals(old.get("home_book_id"), book)
                                || !Objects.equals(old.get("route"), route)
                                || !Objects.equals(old.get("payload_hash"), hash))
                            throw error("IDEMPOTENCY_CONFLICT", "请求键已永久绑定不同内容或资源");
                        partyId = (UUID) old.get("counterparty_id");
                        revision = (UUID) old.get("revision_id");
                    } else {
                        if (op.equals("create")) {
                            UUID operation = FinanceCounterpartyInput.uuid(n.get("operationId"));
                            boolean sameOperation =
                                    authorizedDuplicates.stream()
                                            .anyMatch(
                                                    p ->
                                                            Objects.equals(
                                                                    p.get("operation_id"),
                                                                    operation));
                            // Stable operation replay/conflict remains the SQL command's first
                            // decision.
                            // Only a fresh operation may report an already authorized identity
                            // reference.
                            if (!sameOperation && !authorizedDuplicates.isEmpty()) {
                                var existing = authorizedDuplicates.getFirst();
                                throw new FinanceException(
                                        "COUNTERPARTY_IDENTITY_EXISTS",
                                        "同法人已存在往来单位：counterpartyId="
                                                + existing.get("id")
                                                + "，governanceHomeBookId="
                                                + existing.get("home_book_id")
                                                + "；请查阅已有稳定身份，不得重复登记",
                                        List.of(
                                                map(
                                                        "row",
                                                        1,
                                                        "field",
                                                        "counterpartyId",
                                                        "reason",
                                                        existing.get("id").toString()),
                                                map(
                                                        "row",
                                                        1,
                                                        "field",
                                                        "governanceHomeBookId",
                                                        "reason",
                                                        existing.get("home_book_id").toString())));
                            }
                        }
                        var result =
                                json(
                                        call.one(
                                                        "SELECT"
                                                            + " plugin_finance_counterparty_command(?,?,?,?::jsonb,?,?,?,?::jsonb)::text"
                                                            + " value",
                                                        b.get("tenant_id"),
                                                        b.get("legal_entity_id"),
                                                        book,
                                                        finance.encode(who),
                                                        op.toUpperCase(Locale.ROOT),
                                                        target,
                                                        revisionId,
                                                        finance.encode(n))
                                                .get("value"));
                        partyId = id(result.get("counterpartyId"));
                        revision = id(result.get("revisionId"));
                        call.update(
                                "INSERT INTO"
                                    + " plugin_finance_counterparty_request(actor_id,request_key,tenant_id,legal_entity_id,home_book_id,route,payload_hash,counterparty_id,revision_id)"
                                    + " VALUES(?,?,?,?,?,?,?,?,?)",
                                b.get("actor_id"),
                                n.get("idempotencyKey"),
                                b.get("tenant_id"),
                                b.get("legal_entity_id"),
                                book,
                                route,
                                hash,
                                partyId,
                                revision);
                    }
                    return op.equals("create")
                            ? detail(call, principal, b, partyId, request)
                            : revisionDetail(call, principal, b, partyId, revision, request);
                });
    }
}

package com.lumira.finance;

import com.lumira.platform.identity.api.AuthenticationRequestContext;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

/**
 * Limited per-year protocol; no directory governance assignment is required for business actors.
 */
@Service
public class FinanceJournalPersonSod {
    static final String SCHEMA = "journal-person-sod/1";
    final FinanceStore db;
    final FinanceService finance;
    final FinanceJournalPersonGate gate;
    final PlatformTransactionManager transactions;

    @org.springframework.beans.factory.annotation.Autowired
    public FinanceJournalPersonSod(
            FinanceStore db,
            FinanceService finance,
            FinanceJournalPersonGate gate,
            PlatformTransactionManager transactions) {
        this.db = db;
        this.finance = finance;
        this.gate = gate;
        this.transactions = transactions;
    }

    static Map<String, Object> map(Object... a) {
        return FinanceJournalPersonGate.map(a);
    }

    static String counter(Object v) {
        return FinancePersonDirectory.counter(v);
    }

    static UUID uuid(Object v) {
        return FinancePersonDirectory.uuid(v);
    }

    static String reason(Object v) {
        if (!(v instanceof String s)
                || s.isBlank()
                || s.length() > 1000
                || s.chars()
                        .anyMatch(
                                c ->
                                        Character.isISOControl(c)
                                                && c != '\n'
                                                && c != '\r'
                                                && c != '\t'))
            throw FinanceException.invalid("原因无效");
        return s;
    }

    static Map<String, Object> normalized(String op, Map<String, Object> input) {
        Set<String> keys =
                switch (op) {
                    case "request" ->
                            Set.of(
                                    "schemaVersion",
                                    "idempotencyKey",
                                    "expectedModeGeneration",
                                    "reason");
                    case "approve", "reject", "cancel" ->
                            Set.of(
                                    "schemaVersion",
                                    "idempotencyKey",
                                    "expectedVersion",
                                    "requestDigest",
                                    "reason");
                    case "person-approve" ->
                            Set.of(
                                    "schemaVersion",
                                    "idempotencyKey",
                                    "expectedJournalVersion",
                                    "expectedContentVersion",
                                    "contentDigest",
                                    "requestedExpiresAt");
                    case "person-post" ->
                            Set.of(
                                    "schemaVersion",
                                    "idempotencyKey",
                                    "expectedJournalVersion",
                                    "expectedContentVersion",
                                    "contentDigest",
                                    "approvalId");
                    case "terminate" ->
                            Set.of(
                                    "schemaVersion",
                                    "idempotencyKey",
                                    "expectedJournalVersion",
                                    "expectedContentVersion",
                                    "contentDigest",
                                    "reason");
                    default -> throw FinanceException.invalid("命令无效");
                };
        if (input == null
                || !keys.equals(input.keySet())
                || !SCHEMA.equals(input.get("schemaVersion")))
            throw FinanceException.invalid("字段缺失或未知");
        var n = new LinkedHashMap<>(input);
        FinancePersonDirectory.text(n.get("idempotencyKey"), 128);
        for (String k : keys) {
            if (k.startsWith("expected")) {
                counter(n.get(k));
                if (!k.equals("expectedModeGeneration") && n.get(k).equals("0"))
                    throw FinanceException.invalid("对象版本必须为正");
            }
            if (k.endsWith("Digest")) FinancePersonDirectory.digest(n.get(k));
        }
        if (n.containsKey("reason")) reason(n.get("reason"));
        if (n.containsKey("approvalId")) uuid(n.get("approvalId"));
        if (n.containsKey("requestedExpiresAt")) {
            if (!(n.get("requestedExpiresAt") instanceof String s))
                throw FinanceException.invalid("批准有效期须为ISO时间");
            try {
                Instant.parse(s);
            } catch (RuntimeException e) {
                throw FinanceException.invalid("批准有效期无效");
            }
        }
        return n;
    }

    Object canonical(Object x) {
        if (x instanceof Map<?, ?> m) {
            var s = new TreeMap<String, Object>();
            m.forEach((k, v) -> s.put(k.toString(), canonical(v)));
            return s;
        }
        if (x instanceof List<?> l) return l.stream().map(this::canonical).toList();
        return x;
    }

    String hash(Object x) {
        return FinanceService.hash(gate.encode(canonical(x)));
    }

    Map<String, Object> modeRow(Map<String, Object> b, UUID y) {
        return db.one(
                "SELECT * FROM plugin_finance_journal_person_mode WHERE tenant_id=? AND book_id=?"
                        + " AND year_id=?",
                b.get("tenant_id"),
                b.get("id"),
                y);
    }

    Map<String, Object> projection(Map<String, Object> b, UUID actor) {
        var realm =
                db.list(
                        "SELECT tenant_id FROM plugin_finance_person_realm WHERE tenant_id=?",
                        b.get("tenant_id"));
        if (realm.isEmpty())
            return map(
                    "currentActorAccountId",
                    actor,
                    "currentActorPersonId",
                    null,
                    "identityAvailability",
                    "UNCONFIGURED");
        var bind =
                db.list(
                        "SELECT person_id,status FROM plugin_finance_person_binding WHERE"
                                + " tenant_id=? AND account_id=?",
                        b.get("tenant_id"),
                        actor);
        String status =
                bind.isEmpty()
                        ? "UNBOUND"
                        : bind.getFirst().get("status").equals("ACTIVE") ? "AVAILABLE" : "REVOKED";
        return map(
                "currentActorAccountId",
                actor,
                "currentActorPersonId",
                status.equals("AVAILABLE") ? bind.getFirst().get("person_id") : null,
                "identityAvailability",
                status);
    }

    boolean empty(Map<String, Object> b, UUID y) {
        return Boolean.TRUE.equals(
                db.one(
                                "SELECT plugin_finance_journal_person_empty(?,?,?) value",
                                b.get("tenant_id"),
                                b.get("id"),
                                y)
                        .get("value"));
    }

    Map<String, Object> mode(Map<String, Object> b, UUID y, UUID actor) {
        var m = modeRow(b, y);
        var who = projection(b, actor);
        var requests =
                db.list(
                        "SELECT id,status FROM plugin_finance_journal_person_activation WHERE"
                            + " tenant_id=? AND book_id=? AND year_id=? ORDER BY created_at DESC,id"
                            + " DESC LIMIT 1",
                        b.get("tenant_id"),
                        b.get("id"),
                        y);
        Object latest = requests.isEmpty() ? null : requests.getFirst().get("id"),
                pending =
                        requests.isEmpty() || !requests.getFirst().get("status").equals("PENDING")
                                ? null
                                : latest;
        var blockers = new ArrayList<Map<String, Object>>();
        if (!m.get("mode").equals("ACCOUNT_ONLY"))
            blockers.add(map("code", "MODE_ALREADY_ENABLED", "message", "年度人员协议已启用，不允许降级"));
        if (!empty(b, y))
            blockers.add(
                    map(
                            "code",
                            "YEAR_NOT_EMPTY_OR_UNAVAILABLE",
                            "message",
                            "须为V4/CNY完整OPEN空年度；不迁移旧凭证或期初"));
        if (!who.get("identityAvailability").equals("AVAILABLE"))
            blockers.add(map("code", "IDENTITY_UNAVAILABLE", "message", "当前账号须有组织核验的有效普通人员绑定"));
        var context = AuthenticationRequestContext.current();
        boolean manage =
                context != null
                        && context.userId().equals(actor)
                        && context.authorization() != null
                        && context.authorization()
                                .effectiveAuthorities()
                                .contains("platform:finance:manage")
                        && "OWNER".equals(b.get("role"));
        if (!manage)
            blockers.add(
                    map("code", "OWNER_MANAGE_REQUIRED", "message", "提出启用申请须当前OWNER及manage权限"));
        if (pending != null) blockers.add(map("code", "REQUEST_PENDING", "message", "已有待独立审签申请"));
        var out =
                map(
                        "schemaVersion",
                        SCHEMA,
                        "tenantId",
                        b.get("tenant_id"),
                        "bookId",
                        b.get("id"),
                        "yearId",
                        y,
                        "mode",
                        m.get("mode"),
                        "modeEpoch",
                        m.get("epoch"),
                        "modeGeneration",
                        m.get("generation").toString(),
                        "effectiveAt",
                        m.get("effective_at"),
                        "activationRequestId",
                        m.get("activation_id"),
                        "cashSodEnforcement",
                        "NOT_ENFORCED",
                        "yearClosePersonProtocol",
                        "UNSUPPORTED",
                        "pendingActivationRequestId",
                        pending,
                        "latestActivationRequestId",
                        latest,
                        "activationEligible",
                        blockers.isEmpty(),
                        "blockers",
                        blockers,
                        "capabilities",
                        map(
                                "manualNormalJournals",
                                true,
                                "sourceJournals",
                                false,
                                "reversals",
                                false,
                                "closingDrafts",
                                false,
                                "yearClose",
                                false,
                                "personReject",
                                true,
                                "legacyApprovePostInPersonMode",
                                false));
        out.putAll(who);
        return out;
    }

    Map<String, Object> activation(Map<String, Object> b, UUID y, UUID id) {
        var r =
                db.one(
                        "SELECT * FROM plugin_finance_journal_person_activation WHERE tenant_id=?"
                                + " AND book_id=? AND year_id=? AND id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        id);
        return map(
                "id",
                r.get("id"),
                "tenantId",
                b.get("tenant_id"),
                "bookId",
                b.get("id"),
                "yearId",
                y,
                "status",
                r.get("status"),
                "version",
                r.get("version").toString(),
                "requestDigest",
                r.get("request_digest"),
                "requestedBy",
                r.get("requester_account"),
                "requestedPersonId",
                r.get("requester_person"),
                "createdAt",
                r.get("created_at"),
                "expiresAt",
                r.get("expires_at"),
                "reason",
                r.get("reason"),
                "decidedBy",
                r.get("decider_account"),
                "decidedPersonId",
                r.get("decider_person"),
                "decidedAt",
                r.get("decided_at"),
                "decisionReason",
                r.get("decision_reason"),
                "resultModeEpoch",
                r.get("status").equals("APPROVED") ? r.get("epoch") : null);
    }

    Map<String, Object> approval(
            Map<String, Object> b, UUID y, UUID j, Map<String, Object> current, boolean posted) {
        var list =
                db.list(
                        "SELECT * FROM plugin_finance_journal_person_approval WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=? AND journal_id=? ORDER BY"
                                + " approved_journal_version DESC LIMIT 1",
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        j);
        if (list.isEmpty()) return null;
        var a = list.getFirst();
        String status =
                posted
                        ? "CONSUMED"
                        : Objects.equals(current.get("approval_id"), a.get("id"))
                                ? "ACTIVE"
                                : "SUPERSEDED";
        return map(
                "id",
                a.get("id"),
                "journalId",
                j,
                "contentVersion",
                a.get("content_version").toString(),
                "contentDigest",
                a.get("content_digest"),
                "approvedJournalVersion",
                a.get("approved_journal_version").toString(),
                "approvedBy",
                gate.decode(a.get("approver")),
                "approvedAt",
                a.get("approved_at"),
                "expiresAt",
                a.get("expires_at"),
                "status",
                status);
    }

    Map<String, Object> context(Map<String, Object> b, UUID y, UUID j, UUID actor) {
        var h = gate.rawJournal(b, y, j);
        var m = mode(b, y, actor);
        boolean enabled = m.get("mode").equals("JOURNAL_PERSON_SOD");
        var out =
                map(
                        "schemaVersion",
                        SCHEMA,
                        "tenantId",
                        b.get("tenant_id"),
                        "bookId",
                        b.get("id"),
                        "yearId",
                        y,
                        "journalId",
                        j,
                        "mode",
                        m,
                        "journalVersion",
                        h.get("version").toString(),
                        "contentVersion",
                        null,
                        "contentDigest",
                        null,
                        "snapshotFormatVersion",
                        null,
                        "maker",
                        null,
                        "lastEditor",
                        null,
                        "currentApproval",
                        null,
                        "postReceipt",
                        null,
                        "personProofStatus",
                        "ACCOUNT_ONLY_LEGACY",
                        "limitations",
                        List.of(
                                "仅年度人工NORMAL凭证协议；现金与年结自然人协议未实现",
                                "组织人工核验，不是政府KYC或action-bound step-up；历史旧年未回填"),
                        "journalStatus",
                        h.get("status"),
                        "contentSnapshot",
                        null,
                        "administrativeTermination",
                        null);
        out.putAll(projection(b, actor));
        if (!enabled) return out;
        var c = gate.content(b, y, j);
        var cur =
                db.one(
                        "SELECT * FROM plugin_finance_journal_person_current WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=? AND journal_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        j);
        var posts =
                db.list(
                        "SELECT * FROM plugin_finance_journal_person_post WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=? AND journal_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        j);
        boolean posted = !posts.isEmpty();
        out.put("contentVersion", c.get("content_version").toString());
        out.put("contentDigest", c.get("digest"));
        out.put("snapshotFormatVersion", c.get("format"));
        out.put("maker", gate.decode(c.get("maker")));
        out.put("lastEditor", gate.decode(c.get("editor")));
        out.put("contentSnapshot", gate.decode(c.get("snapshot")));
        out.put("currentApproval", approval(b, y, j, cur, posted));
        out.put(
                "personProofStatus",
                posted
                        ? "PERSON_POSTED"
                        : h.get("status").equals("APPROVED")
                                ? "PERSON_APPROVED"
                                : "PERSON_CONTENT_FROZEN");
        if (posted) {
            var p = posts.getFirst();
            out.put(
                    "postReceipt",
                    map(
                            "id",
                            p.get("id"),
                            "journalId",
                            j,
                            "approvalId",
                            p.get("approval_id"),
                            "contentVersion",
                            p.get("content_version").toString(),
                            "contentDigest",
                            p.get("content_digest"),
                            "postedBy",
                            gate.decode(p.get("poster")),
                            "postedAt",
                            p.get("posted_at"),
                            "postingSeq",
                            p.get("posting_seq").toString(),
                            "postingDigest",
                            p.get("posting_digest")));
        }
        var terms =
                db.list(
                        "SELECT * FROM plugin_finance_journal_person_termination WHERE tenant_id=?"
                                + " AND book_id=? AND year_id=? AND journal_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        j);
        if (!terms.isEmpty()) {
            var t = terms.getFirst();
            out.put(
                    "administrativeTermination",
                    map(
                            "id",
                            t.get("id"),
                            "actor",
                            gate.decode(t.get("actor")),
                            "reason",
                            t.get("reason"),
                            "decidedAt",
                            t.get("decided_at"),
                            "journalVersion",
                            t.get("journal_version").toString(),
                            "contentVersion",
                            t.get("content_version").toString()));
        }
        return out;
    }

    public Object read(
            String actor,
            UUID book,
            UUID year,
            String op,
            UUID target,
            HttpServletRequest request) {
        var tx = new TransactionTemplate(transactions);
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(
                status -> {
                    var b = finance.originalScope(actor, book, year, "read");
                    UUID a = (UUID) b.get("actor_id");
                    if (op.equals("context") && gate.payableProducer(b, year, target))
                        throw FinanceJournalPersonGate.error(
                                "JOURNAL_PERSON_MODE_REQUIRED", "应付业务凭证不使用人工NORMAL凭证的人员上下文");
                    if (op.equals("context") && gate.profitClosingProducer(b, year, target))
                        throw FinanceJournalPersonGate.error(
                                "JOURNAL_PERSON_MODE_REQUIRED", "该凭证须读取结转生产者的独立内容/审签上下文");
                    return switch (op) {
                        case "mode" -> mode(b, year, a);
                        case "activation" -> activation(b, year, target);
                        case "context" -> context(b, year, target, a);
                        default -> throw FinanceException.invalid("读取类型无效");
                    };
                });
    }

    Map<String, Object> lock(String actor, UUID book, UUID year, String permission, String key) {
        var b = finance.originalScope(actor, book, year, permission);
        gate.prelock((UUID) b.get("tenant_id"));
        db.list(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                b.get("actor_id") + ":" + key);
        db.one(
                "SELECT id FROM plugin_finance_book WHERE tenant_id=? AND id=? FOR UPDATE",
                b.get("tenant_id"),
                book);
        b = finance.originalScope(actor, book, year, permission);
        finance.lockYearWrites(b, List.of(year));
        gate.mode(b, year, false);
        return b;
    }

    void verifyInputContent(Map<String, Object> n, Map<String, Object> h, Map<String, Object> c) {
        if (!n.get("expectedJournalVersion").equals(h.get("version").toString())
                || !n.get("expectedContentVersion").equals(c.get("content_version").toString())
                || !n.get("contentDigest").equals(c.get("digest")))
            throw FinanceJournalPersonGate.error("VERSION_CONFLICT", "凭证、内容或依据版本已变化");
    }

    void recheckApproval(Map<String, Object> b, UUID y, Map<String, Object> a) {
        try {
            finance.recheckApprover(b, (UUID) a.get("approver_account"), y);
            var now = gate.identity((UUID) b.get("tenant_id"), (UUID) a.get("approver_account"));
            if (!now.equals(gate.decode(a.get("approver")))) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw FinanceJournalPersonGate.error(
                    "JOURNAL_PERSON_APPROVAL_STALE", "批准者当前权限或人员资格已变化");
        }
    }

    public Object write(
            String actor,
            UUID book,
            UUID year,
            String op,
            UUID target,
            Map<String, Object> input,
            HttpServletRequest request) {
        var n = normalized(op, input);
        String permission =
                switch (op) {
                    case "request", "cancel" -> "manage";
                    case "person-post" -> "post";
                    default -> "approve";
                };
        var tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx.execute(
                status -> {
                    var b = lock(actor, book, year, permission, n.get("idempotencyKey").toString());
                    UUID a = (UUID) b.get("actor_id");
                    var who = gate.current(a, (UUID) b.get("tenant_id"), true, request);
                    if (Set.of("request", "cancel").contains(op) && !"OWNER".equals(b.get("role")))
                        throw new FinanceException("SCOPE_DENIED", "须当前OWNER");
                    if (Set.of("person-approve", "terminate").contains(op))
                        finance.originalScope(actor, book, year, "read");
                    boolean activationOp =
                            Set.of("request", "approve", "reject", "cancel").contains(op);
                    Map<String, Object> r = null, c = null, h = null, ap = null;
                    if (activationOp && target != null) {
                        r =
                                db.one(
                                        "SELECT * FROM plugin_finance_journal_person_activation"
                                            + " WHERE tenant_id=? AND book_id=? AND year_id=? AND"
                                            + " id=?",
                                        b.get("tenant_id"),
                                        book,
                                        year,
                                        target);
                        if (op.equals("cancel")) {
                            if (!a.equals(r.get("requester_account")))
                                throw new FinanceException("SCOPE_DENIED", "仅原申请账号可取消");
                        } else if (who.get("personId").equals(r.get("requester_person").toString()))
                            throw new FinanceException("SELF_APPROVAL_FORBIDDEN", "同人多账号不得自审");
                        if (op.equals("approve")) {
                            var rb =
                                    finance.originalScope(
                                            r.get("requester_username").toString(),
                                            book,
                                            year,
                                            "manage");
                            if (!rb.get("actor_id").equals(r.get("requester_account"))
                                    || !"OWNER".equals(rb.get("role"))
                                    || !gate.identity(
                                                    (UUID) b.get("tenant_id"),
                                                    (UUID) r.get("requester_account"))
                                            .equals(gate.decode(r.get("requester"))))
                                throw FinanceJournalPersonGate.error(
                                        "JOURNAL_PERSON_APPROVAL_STALE", "原申请人当前权限或身份已变化");
                        }
                    }
                    if (!activationOp) {
                        if (gate.payableProducer(b, year, target))
                            throw FinanceJournalPersonGate.error(
                                    "JOURNAL_PERSON_MODE_REQUIRED", "应付业务凭证不接受普通NORMAL凭证的人员动作");
                        if (gate.profitClosingProducer(b, year, target))
                            throw FinanceJournalPersonGate.error(
                                    "JOURNAL_PERSON_MODE_REQUIRED", "系统结转不接受普通NORMAL凭证的人员动作");
                        if (!gate.enabled(b, year))
                            throw FinanceJournalPersonGate.error(
                                    "JOURNAL_PERSON_PROTOCOL_UNSUPPORTED", "旧年度不使用自然人凭证协议");
                        h = gate.rawJournal(b, year, target);
                        c = gate.content(b, year, target);
                        if (!op.equals("person-post")) gate.independent(who, c);
                        if (op.equals("person-post")) {
                            ap =
                                    db.one(
                                            "SELECT * FROM plugin_finance_journal_person_approval"
                                                + " WHERE tenant_id=? AND book_id=? AND year_id=?"
                                                + " AND journal_id=? AND id=?",
                                            b.get("tenant_id"),
                                            book,
                                            year,
                                            target,
                                            uuid(n.get("approvalId")));
                            recheckApproval(b, year, ap);
                        }
                    }
                    String route = book + "/" + year + "/" + op + "/" + target, hash = hash(n);
                    var cached =
                            db.list(
                                    "SELECT * FROM plugin_finance_journal_person_request WHERE"
                                            + " actor_id=? AND request_key=?",
                                    a,
                                    n.get("idempotencyKey"));
                    if (!cached.isEmpty()) {
                        var old = cached.getFirst();
                        if (!old.get("route").equals(route)
                                || !old.get("payload_hash").equals(hash))
                            throw new FinanceException("IDEMPOTENCY_CONFLICT", "请求键内容冲突");
                        return activationOp
                                ? activation(b, year, (UUID) old.get("result_id"))
                                : context(b, year, target, a);
                    }
                    UUID result = target;
                    if (op.equals("request")) {
                        var m = gate.mode(b, year, true);
                        if (!n.get("expectedModeGeneration").equals(m.get("generation").toString()))
                            throw FinanceJournalPersonGate.error(
                                    "JOURNAL_PERSON_MODE_STALE", "模式基线已变化");
                        if (!m.get("mode").equals("ACCOUNT_ONLY") || !empty(b, year))
                            throw FinanceJournalPersonGate.error(
                                    "JOURNAL_PERSON_YEAR_NOT_EMPTY", "只允许V4/CNY/全部月OPEN空年启用");
                        result = UUID.randomUUID();
                        db.update(
                                "INSERT INTO"
                                    + " plugin_finance_journal_person_activation(tenant_id,book_id,year_id,id,epoch,requester,requester_username,reason,request_digest,requested_generation)"
                                    + " VALUES(?,?,?,?,?,?::jsonb,?,?,?,?)",
                                b.get("tenant_id"),
                                book,
                                year,
                                result,
                                m.get("epoch"),
                                gate.encode(who),
                                actor,
                                n.get("reason"),
                                "0".repeat(64),
                                Long.parseLong(n.get("expectedModeGeneration").toString()));
                    } else if (activationOp) {
                        gate.mode(b, year, true);
                        if (!r.get("status").equals("PENDING")
                                || !n.get("expectedVersion").equals(r.get("version").toString())
                                || !n.get("requestDigest").equals(r.get("request_digest")))
                            throw new FinanceException("VERSION_CONFLICT", "申请状态或内容版本已变化");
                        if (op.equals("approve")
                                && (!empty(b, year)
                                        || !((java.sql.Timestamp) r.get("expires_at"))
                                                .toInstant()
                                                .isAfter(Instant.now())))
                            throw FinanceJournalPersonGate.error(
                                    "JOURNAL_PERSON_YEAR_NOT_EMPTY", "年度不再为空或申请已过期，请驳回/取消");
                        String next =
                                op.equals("approve")
                                        ? "APPROVED"
                                        : op.equals("reject") ? "REJECTED" : "CANCELLED";
                        db.update(
                                "UPDATE plugin_finance_journal_person_activation SET"
                                    + " status=?,version=version+1,decider=?::jsonb,decided_at=CURRENT_TIMESTAMP,decision_reason=?"
                                    + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                next,
                                gate.encode(who),
                                n.get("reason"),
                                b.get("tenant_id"),
                                book,
                                year,
                                target);
                    } else {
                        verifyInputContent(n, h, c);
                        if (op.equals("person-approve")) {
                            if (!h.get("status").equals("SUBMITTED"))
                                throw FinanceJournalPersonGate.error("VERSION_CONFLICT", "必须先提交");
                            Instant expires = Instant.parse(n.get("requestedExpiresAt").toString()),
                                    now = Instant.now();
                            if (!expires.isAfter(now)
                                    || expires.isAfter(now.plus(Duration.ofDays(7))))
                                throw FinanceException.invalid("批准有效期须在当前至7天内");
                            UUID approval = UUID.randomUUID();
                            db.update(
                                    "INSERT INTO"
                                        + " plugin_finance_journal_person_approval(tenant_id,book_id,year_id,journal_id,id,content_version,content_digest,approved_journal_version,approver,approver_username,expires_at)"
                                        + " VALUES(?,?,?,?,?,?,?,?,?::jsonb,?,?)",
                                    b.get("tenant_id"),
                                    book,
                                    year,
                                    target,
                                    approval,
                                    c.get("content_version"),
                                    c.get("digest"),
                                    ((Number) h.get("version")).longValue() + 1,
                                    gate.encode(who),
                                    actor,
                                    java.sql.Timestamp.from(expires));
                            finance.mutate(
                                    a,
                                    b,
                                    year,
                                    "approve",
                                    target,
                                    map("expectedVersion", h.get("version")));
                            gate.seal(b, year, target, who, "APPROVE", approval, null);
                        } else if (op.equals("person-post")) {
                            if (!h.get("status").equals("APPROVED")
                                    || !Objects.equals(h.get("approved_version"), h.get("version"))
                                    || !Objects.equals(
                                            ap.get("approved_journal_version"), h.get("version"))
                                    || !Objects.equals(
                                            ap.get("content_version"), c.get("content_version"))
                                    || !Objects.equals(ap.get("content_digest"), c.get("digest")))
                                throw FinanceJournalPersonGate.error(
                                        "JOURNAL_PERSON_APPROVAL_STALE", "批准与当前内容不一致");
                            if (!((java.sql.Timestamp) ap.get("expires_at"))
                                    .toInstant()
                                    .isAfter(Instant.now()))
                                throw FinanceJournalPersonGate.error(
                                        "JOURNAL_PERSON_APPROVAL_EXPIRED", "批准已过期，可由独立审核人退回");
                            finance.mutate(
                                    a,
                                    b,
                                    year,
                                    "post",
                                    target,
                                    map("expectedVersion", h.get("version")));
                            gate.seal(b, year, target, who, "POST", null, null);
                        } else {
                            if (!h.get("status").equals("DRAFT"))
                                throw FinanceJournalPersonGate.error(
                                        "VERSION_CONFLICT", "仅草稿可行政终结，请先独立退回已批准对象");
                            db.update(
                                    "UPDATE plugin_finance_journal SET"
                                        + " status='CANCELLED',version=version+1 WHERE tenant_id=?"
                                        + " AND book_id=? AND year_id=? AND id=?",
                                    b.get("tenant_id"),
                                    book,
                                    year,
                                    target);
                            gate.seal(
                                    b,
                                    year,
                                    target,
                                    who,
                                    "TERMINATE",
                                    null,
                                    n.get("reason").toString());
                            finance.originalAudit(
                                    a,
                                    b,
                                    year,
                                    "JOURNAL_ADMINISTRATIVE_TERMINATE",
                                    target,
                                    map(
                                            "reason",
                                            n.get("reason"),
                                            "actualActor",
                                            who,
                                            "originalMaker",
                                            gate.decode(c.get("maker")),
                                            "contentVersion",
                                            c.get("content_version").toString(),
                                            "contentDigest",
                                            c.get("digest")));
                        }
                    }
                    db.update(
                            "INSERT INTO"
                                + " plugin_finance_journal_person_request(actor_id,request_key,tenant_id,book_id,year_id,route,payload_hash,result_id)"
                                + " VALUES(?,?,?,?,?,?,?,?)",
                            a,
                            n.get("idempotencyKey"),
                            b.get("tenant_id"),
                            book,
                            year,
                            route,
                            hash,
                            result);
                    return activationOp ? activation(b, year, result) : context(b, year, target, a);
                });
    }
}

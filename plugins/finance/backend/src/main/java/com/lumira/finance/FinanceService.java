package com.lumira.finance;

import com.lumira.finance.domain.AccountingRules;
import com.lumira.finance.domain.PrecisionRules;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.identity.api.AccountOwnerPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

@Service
public class FinanceService {
    private final FinanceStore db;
    private final AdministrationPort guard;
    private final AccountOwnerPort owners;
    private final ObjectMapper json;

    final FinanceJournalPersonGate personGate;

    /**
     * Explicit historical-schema test adapter. Production always requires the five-argument gate.
     */
    FinanceService(
            FinanceStore db, AdministrationPort guard, AccountOwnerPort owners, ObjectMapper json) {
        this(
                db,
                guard,
                owners,
                json,
                FinanceJournalPersonGate.historicalFixture(db, guard, owners, json));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FinanceService(
            FinanceStore db,
            AdministrationPort guard,
            AccountOwnerPort owners,
            ObjectMapper json,
            FinanceJournalPersonGate personGate) {
        this.personGate = personGate;
        this.db = db;
        this.guard = guard;
        this.owners = owners;
        this.json = json;
    }

    /** Virtual accessor delegates through the Spring proxy to its initialized target. */
    public FinanceJournalPersonGate personGate() {
        return personGate;
    }

    static String s(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    static UUID id(Map<String, Object> m, String k) {
        try {
            return UUID.fromString(s(m, k));
        } catch (Exception e) {
            throw FinanceException.invalid("无效ID: " + k);
        }
    }

    static String text(Map<String, Object> m, String k, int max) {
        String v = s(m, k).strip();
        if (v.isEmpty() || v.length() > max) throw FinanceException.invalid("无效字段: " + k);
        return v;
    }

    static long version(Map<String, Object> m) {
        try {
            return Long.parseLong(s(m, "expectedVersion"));
        } catch (Exception e) {
            throw FinanceException.invalid("expectedVersion必填");
        }
    }

    static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    Map<String, Object> decode(String value) {
        try {
            return json.readValue(value, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID authorize(String actor, String action) {
        guard.require(actor, "platform:finance:access");
        guard.require(actor, "platform:finance:" + action);
        return owners.id(actor, false);
    }

    private Map<String, Object> scope(UUID actor, UUID book, String action) {
        var b =
                db.one(
                        "SELECT b.*,m.role,m.year_scope FROM plugin_finance_book b JOIN"
                                + " plugin_finance_member m ON m.tenant_id=b.tenant_id AND"
                                + " m.book_id=b.id WHERE b.id=? AND m.user_id=? AND m.enabled",
                        book,
                        actor);
        String r = s(b, "role");
        boolean allowed =
                action.equals("read")
                        || action.equals("export")
                        || r.equals("OWNER")
                        || action.equals("create") && r.equals("MAKER")
                        || action.equals("approve") && r.equals("REVIEWER")
                        || action.equals("post") && r.equals("POSTER");
        if (!allowed) throw new FinanceException("SCOPE_DENIED", "成员角色无此操作许可");
        b.put("actor_id", actor);
        return b;
    }

    private Map<String, Object> year(Map<String, Object> b, UUID y) {
        if (!yearAllowed(b, y)) throw new FinanceException("SCOPE_DENIED", "无目标年度授权");
        return db.one(
                "SELECT * FROM plugin_finance_year WHERE tenant_id=? AND book_id=? AND id=?",
                b.get("tenant_id"),
                b.get("id"),
                y);
    }

    private void audit(
            UUID actor, Map<String, Object> b, UUID y, String action, UUID target, Object payload) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("payload", payload);
        if (y != null
                && !db.list(
                                "SELECT id FROM plugin_finance_journal WHERE tenant_id=? AND"
                                        + " book_id=? AND year_id=? AND id=?",
                                b.get("tenant_id"),
                                b.get("id"),
                                y,
                                target)
                        .isEmpty()) evidence.put("journalSnapshot", journal(b, y, target));
        String serialized = encode(canonical(evidence));
        db.update(
                "INSERT INTO"
                    + " plugin_finance_audit(id,tenant_id,book_id,year_id,actor_id,action,target_id,payload_hash,payload,created_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?::jsonb,now())",
                UUID.randomUUID(),
                b.get("tenant_id"),
                b.get("id"),
                y,
                actor,
                action,
                target,
                hash(serialized),
                serialized);
        if (y != null
                && ((action.startsWith("JOURNAL_") && !action.equals("JOURNAL_POST"))
                        || Set.of("DOCUMENT_REGISTER", "CLOSING_APPROVAL_INVALIDATED")
                                .contains(action)))
            FinanceOutbox.enqueue(db, this, b, y, "JOURNAL_CHANGED", target, actor);
    }

    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Object read(
            String actor, UUID book, UUID y, String resource, UUID target, UUID account) {
        UUID a = authorize(actor, resource.equals("members") ? "manage" : "read");
        if (book == null)
            return db.list(
                    "SELECT b.id,b.tenant_id AS \"tenantId\",b.legal_entity_id AS"
                        + " \"legalEntityId\",b.name,b.standard,b.base_currency AS"
                        + " \"baseCurrency\",b.version,m.role AS \"currentRole\",e.name AS"
                        + " \"legalEntityName\",t.name AS \"tenantName\" FROM plugin_finance_book b"
                        + " JOIN plugin_finance_member m ON m.tenant_id=b.tenant_id AND"
                        + " m.book_id=b.id JOIN plugin_finance_entity e ON e.tenant_id=b.tenant_id"
                        + " AND e.id=b.legal_entity_id JOIN plugin_finance_tenant t ON"
                        + " t.id=b.tenant_id WHERE m.user_id=? AND m.enabled ORDER BY b.name LIMIT"
                        + " 200",
                    a);
        var b = scope(a, book, resource.equals("members") ? "manage" : "read");
        Object t = b.get("tenant_id");
        if (resource.equals("members")) return members(b);
        if (resource.equals("years"))
            return db
                    .list(
                            "SELECT id,year,status,version,currency_minor_unit AS"
                                    + " \"currencyMinorUnit\",precision_profile AS"
                                    + " \"precisionProfile\",precision_review_required AS"
                                    + " \"precisionReviewRequired\" FROM plugin_finance_year WHERE"
                                    + " tenant_id=? AND book_id=? ORDER BY year",
                            t,
                            book)
                    .stream()
                    .filter(row -> yearAllowed(b, (UUID) row.get("id")))
                    .toList();
        if (resource.equals("tax-profile")) {
            var rows =
                    db.list(
                            "SELECT vat_identity AS \"vatIdentity\",income_tax_method AS"
                                + " \"incomeTaxMethod\",region,notes,status,false AS"
                                + " \"externalSubmissionEnabled\" FROM plugin_finance_tax_profile"
                                + " WHERE tenant_id=? AND book_id=?",
                            t,
                            book);
            return rows.isEmpty()
                    ? Map.of("status", "UNREVIEWED", "externalSubmissionEnabled", false)
                    : rows.getFirst();
        }
        year(b, y);
        return switch (resource) {
            case "year-close-plans" -> target == null ? plans(b, y) : planForYear(b, y, target);
            case "periods" ->
                    db.list(
                            "SELECT id,month,status,version FROM plugin_finance_period WHERE"
                                    + " tenant_id=? AND book_id=? AND year_id=? ORDER BY month",
                            t,
                            book,
                            y);
            case "accounts" -> accounts(b, y);
            case "journals" ->
                    target == null
                            ? db.list(
                                    "SELECT id,date,summary,type,status,version,created_by AS"
                                        + " \"createdBy\",approved_by AS"
                                        + " \"approvedBy\",number,period_id AS \"periodId\" FROM"
                                        + " plugin_finance_journal WHERE tenant_id=? AND book_id=?"
                                        + " AND year_id=? ORDER BY date,id LIMIT 200",
                                    t,
                                    book,
                                    y)
                            : journal(b, y, target);
            case "documents" ->
                    db.list(
                            "SELECT id,original_id AS"
                                    + " \"originalId\",filename,sha256,source,original_location AS"
                                    + " \"originalLocation\" FROM plugin_finance_document WHERE"
                                    + " tenant_id=? AND book_id=? AND year_id=? AND journal_id=?",
                            t,
                            book,
                            y,
                            target);
            case "trial-balance" -> trial(b, y);
            case "ledger" ->
                    db.list(
                            "SELECT j.id AS \"journalId\",j.date,j.number,j.summary,CASE WHEN"
                                + " j.amount_format_version='LEGACY_6' THEN"
                                + " l.debit::numeric(24,6)::text ELSE l.debit::text END AS"
                                + " debit,CASE WHEN j.amount_format_version='LEGACY_6' THEN"
                                + " l.credit::numeric(24,6)::text ELSE l.credit::text END AS credit"
                                + " FROM plugin_finance_line l JOIN plugin_finance_journal j ON"
                                + " j.tenant_id=l.tenant_id AND j.book_id=l.book_id AND"
                                + " j.year_id=l.year_id AND j.id=l.journal_id WHERE l.tenant_id=?"
                                + " AND l.book_id=? AND l.year_id=? AND j.status='POSTED' AND"
                                + " (?::uuid IS NULL OR l.account_id=?::uuid) ORDER BY"
                                + " j.date,j.number,l.line_no LIMIT 200",
                            t,
                            book,
                            y,
                            account,
                            account);
            case "reports" ->
                    Map.of(
                            "status",
                            "RULE_VERSION_UNAVAILABLE",
                            "message",
                            "法定报表规则包未审定，禁止发布；请使用试算平衡表核对");
            default -> throw FinanceException.invalid("未知资源");
        };
    }

    List<Map<String, Object>> accounts(Map<String, Object> b, UUID y) {
        return db.list(
                "SELECT id,code,name,normal_side AS \"normalSide\",category,cash_flow_category AS"
                        + " \"cashFlowCategory\",enabled,purpose FROM plugin_finance_account WHERE"
                        + " tenant_id=? AND book_id=? AND year_id=? ORDER BY code",
                b.get("tenant_id"),
                b.get("id"),
                y);
    }

    Map<String, Object> journal(Map<String, Object> b, UUID y, UUID j) {
        var r =
                db.one(
                        "SELECT amount_format_version AS \"amountFormatVersion\",id,book_id AS"
                            + " \"bookId\",year_id AS \"yearId\",period_id AS"
                            + " \"periodId\",date,summary,type,status,version,created_by AS"
                            + " \"createdBy\",approved_by AS \"approvedBy\",number,closing_basis AS"
                            + " \"closingBasis\",reversal_of AS \"reversalOf\" FROM"
                            + " plugin_finance_journal WHERE tenant_id=? AND book_id=? AND"
                            + " year_id=? AND id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        j);
        r.put(
                "lines",
                db.list(
                        "SELECT account_id AS \"accountId\",debit::text AS debit,credit::text AS"
                                + " credit,memo FROM plugin_finance_line WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=? AND journal_id=? ORDER BY line_no",
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        j));
        if (s(r, "amountFormatVersion").equals("LEGACY_6"))
            for (var line : (List<Map<String, Object>>) r.get("lines"))
                for (String field : List.of("debit", "credit"))
                    line.put(
                            field,
                            new BigDecimal(s(line, field))
                                    .setScale(6, java.math.RoundingMode.UNNECESSARY)
                                    .toPlainString());
        return r;
    }

    @Transactional
    public Object write(
            String actor,
            UUID book,
            UUID y,
            String operation,
            UUID target,
            Map<String, Object> input) {
        String permission =
                switch (operation) {
                    case "book", "members", "years", "accounts", "tax-profile" -> "manage";
                    case "approve", "reject", "year-approve" -> "approve";
                    case "post" -> "post";
                    case "close-preview", "close", "year-plan", "year-execute" -> "close";
                    default -> "create";
                };
        validateInput(operation, input);
        UUID a = authorize(actor, permission);
        String key = text(input, "idempotencyKey", 100);
        if (key.length() < 8) throw FinanceException.invalid("幂等键至少8字符");
        String route = book + "/" + y + "/" + operation + "/" + target;
        String digest = hash(encode(canonical(input)));
        if (book != null && !personGate.historicalFixture) {
            var initial = scope(a, book, permission);
            personGate.prelock((UUID) initial.get("tenant_id"));
        }
        db.jdbc
                .sql("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")
                .param(a + ":" + key)
                .query((rs, n) -> 0)
                .list();
        if (book != null) {
            var checked = scope(a, book, permission);
            db.one(
                    "SELECT id FROM plugin_finance_book WHERE tenant_id=? AND id=? FOR UPDATE",
                    checked.get("tenant_id"),
                    book);
            checked = scope(a, book, permission);
            if (y != null) year(checked, y);
            if (operation.equals("year-plan")) year(checked, id(input, "targetYearId"));
            if (Set.of("year-approve", "year-execute").contains(operation)) {
                var crossYear =
                        db.one(
                                "SELECT target_year_id FROM plugin_finance_close_plan WHERE"
                                    + " tenant_id=? AND book_id=? AND source_year_id=? AND id=?",
                                checked.get("tenant_id"),
                                book,
                                y,
                                target);
                year(checked, (UUID) crossYear.get("target_year_id"));
            }
        }
        if (book != null && y != null) {
            var writeScope = scope(a, book, permission);
            List<UUID> affected = new ArrayList<>();
            affected.add(y);
            if (operation.equals("year-plan")) affected.add(id(input, "targetYearId"));
            if (Set.of("year-approve", "year-execute").contains(operation)) {
                var planYear =
                        db.one(
                                "SELECT target_year_id FROM plugin_finance_close_plan WHERE"
                                    + " tenant_id=? AND book_id=? AND source_year_id=? AND id=?",
                                writeScope.get("tenant_id"),
                                book,
                                y,
                                target);
                affected.add((UUID) planYear.get("target_year_id"));
            }
            lockYearWrites(writeScope, affected);
            personGate.lockModes(writeScope, affected);
            personGate.beforeCrossYear(writeScope, affected, operation);
            personGate.beforeLegacy(a, writeScope, y, operation, target, input);
        }
        if (Set.of("journal", "edit").contains(operation)) validateFormalInput(input);
        var previous =
                db.list(
                        "SELECT route,payload_hash,response FROM plugin_finance_idempotency WHERE"
                                + " actor_id=? AND key=?",
                        a,
                        key);
        if (!previous.isEmpty()) {
            var p = previous.getFirst();
            if (!route.equals(s(p, "route")) || !digest.equals(s(p, "payload_hash")))
                throw new FinanceException("IDEMPOTENCY_CONFLICT", "幂等键内容冲突");
            var replay = decode(s(p, "response"));
            if (book == null) scope(a, id(replay, "id"), "manage");
            return replay;
        }
        Object result;
        if (book == null) result = createBook(a, actor, input);
        else {
            var b = scope(a, book, permission);
            db.one(
                    "SELECT id FROM plugin_finance_book WHERE tenant_id=? AND id=? FOR UPDATE",
                    b.get("tenant_id"),
                    book);
            if (y != null) year(b, y);
            result = mutate(a, b, y, operation, target, input);
            personGate.finishLegacy(a, b, y, operation, target, input, result);
        }
        if (!operation.equals("close-preview"))
            db.update(
                    "INSERT INTO plugin_finance_idempotency VALUES(?,?,?,?,?)",
                    a,
                    key,
                    route,
                    digest,
                    encode(result));
        return result;
    }

    Map<String, Object> createBook(UUID actor, String username, Map<String, Object> in) {
        UUID t = s(in, "tenantId").isEmpty() ? UUID.randomUUID() : id(in, "tenantId");
        UUID e = s(in, "legalEntityId").isEmpty() ? UUID.randomUUID() : id(in, "legalEntityId");
        if (s(in, "tenantId").isEmpty())
            db.update(
                    "INSERT INTO plugin_finance_tenant VALUES(?,?,?)",
                    t,
                    text(in, "legalEntityName", 200),
                    actor);
        else
            db.one(
                    "SELECT id FROM plugin_finance_tenant WHERE id=? AND owner_id=? FOR UPDATE",
                    t,
                    actor);
        if (s(in, "legalEntityId").isEmpty())
            db.update(
                    "INSERT INTO plugin_finance_entity VALUES(?,?,?,?)",
                    t,
                    e,
                    text(in, "legalEntityName", 200),
                    text(in, "registrationId", 100));
        else
            db.one(
                    "SELECT e.id FROM plugin_finance_entity e JOIN plugin_finance_book b ON"
                        + " b.tenant_id=e.tenant_id AND b.legal_entity_id=e.id JOIN"
                        + " plugin_finance_member m ON m.tenant_id=b.tenant_id AND m.book_id=b.id"
                        + " WHERE e.tenant_id=? AND e.id=? AND m.user_id=? AND m.enabled AND"
                        + " m.role='OWNER' LIMIT 1",
                    t,
                    e,
                    actor);
        String standard = text(in, "standard", 10);
        if (!Set.of("CAS", "SMALL").contains(standard) || !s(in, "baseCurrency").equals("CNY"))
            throw FinanceException.invalid("仅支持CAS/SMALL和CNY");
        UUID book = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " plugin_finance_book(tenant_id,id,legal_entity_id,name,standard,base_currency)"
                    + " VALUES(?,?,?,?,?,'CNY')",
                t,
                book,
                e,
                text(in, "name", 200),
                standard);
        db.update(
                "INSERT INTO plugin_finance_member(tenant_id,book_id,user_id,username,role,enabled)"
                        + " VALUES(?,?,?,?,?,true)",
                t,
                book,
                actor,
                username,
                "OWNER");
        var b = db.one("SELECT * FROM plugin_finance_book WHERE id=?", book);
        createYear(b, Integer.parseInt(s(in, "year")));
        audit(actor, b, null, "BOOK_CREATE", book, in);
        return Map.of(
                "id",
                book,
                "tenantId",
                t,
                "legalEntityId",
                e,
                "name",
                s(in, "name"),
                "standard",
                standard,
                "baseCurrency",
                "CNY",
                "version",
                1);
    }

    Map<String, Object> createYear(Map<String, Object> b, int number) {
        if (number < 1900 || number > 2200) throw FinanceException.invalid("年度范围1900-2200");
        UUID y = UUID.randomUUID();
        db.update(
                "INSERT INTO plugin_finance_year(tenant_id,book_id,id,year) VALUES(?,?,?,?)",
                b.get("tenant_id"),
                b.get("id"),
                y,
                number);
        db.update(
                "INSERT INTO plugin_finance_year_guard(tenant_id,book_id,year_id) VALUES(?,?,?)",
                b.get("tenant_id"),
                b.get("id"),
                y);
        for (int month = 1; month <= 12; month++)
            db.update(
                    "INSERT INTO plugin_finance_period(tenant_id,book_id,year_id,id,month)"
                            + " VALUES(?,?,?,?,?)",
                    b.get("tenant_id"),
                    b.get("id"),
                    y,
                    UUID.randomUUID(),
                    month);
        return Map.of(
                "id",
                y,
                "year",
                number,
                "status",
                "OPEN",
                "version",
                1,
                "precisionProfile",
                "V4",
                "precisionReviewRequired",
                false,
                "currencyMinorUnit",
                2);
    }

    Object mutate(
            UUID actor,
            Map<String, Object> b,
            UUID y,
            String op,
            UUID target,
            Map<String, Object> in) {
        Object t = b.get("tenant_id"), book = b.get("id");
        if (op.equals("members")) {
            String role = text(in, "role", 20);
            if (!Set.of("OWNER", "MAKER", "REVIEWER", "POSTER", "READER").contains(role))
                throw FinanceException.invalid("无效成员角色");
            String username = text(in, "username", 100);
            UUID user = owners.id(username, false);
            boolean enabled = !Boolean.FALSE.equals(in.get("enabled"));
            if (user.equals(actor) && (!role.equals("OWNER") || !enabled))
                throw FinanceException.invalid("不能移除自己的所有者权限");
            String yearScope = s(in, "yearScope").isEmpty() ? "ALL" : s(in, "yearScope");
            if (!Set.of("ALL", "SELECTED").contains(yearScope))
                throw FinanceException.invalid("无效年度范围");
            if (yearScope.equals("ALL") && !s(b, "year_scope").equals("ALL"))
                throw new FinanceException("SCOPE_DENIED", "操作者不能扩大超出自身的年度范围");
            List<UUID> ids = new ArrayList<>();
            Object requested = in.get("yearIds");
            if (requested != null) {
                if (!(requested instanceof List<?> list) || list.size() > 301)
                    throw FinanceException.invalid("yearIds必须为年度ID数组");
                for (Object value : list) {
                    if (!(value instanceof String))
                        throw FinanceException.invalid("yearIds必须为UUID字符串");
                    try {
                        UUID selected = UUID.fromString(value.toString());
                        year(b, selected);
                        if (!ids.contains(selected)) ids.add(selected);
                    } catch (IllegalArgumentException invalid) {
                        throw FinanceException.invalid("无效年度ID");
                    }
                }
            }
            if (yearScope.equals("SELECTED") && ids.isEmpty())
                throw FinanceException.invalid("指定年度范围至少选择一个年度");
            if (yearScope.equals("ALL") && !ids.isEmpty())
                throw FinanceException.invalid("全部年度范围不接受yearIds");
            db.update(
                    "INSERT INTO"
                        + " plugin_finance_member(tenant_id,book_id,user_id,username,role,enabled,year_scope)"
                        + " VALUES(?,?,?,?,?,?,?) ON CONFLICT(tenant_id,book_id,user_id) DO UPDATE"
                        + " SET role=excluded.role,enabled=excluded.enabled,username=excluded.username,year_scope=excluded.year_scope",
                    t,
                    book,
                    user,
                    username,
                    role,
                    enabled,
                    yearScope);
            db.update(
                    "DELETE FROM plugin_finance_member_year WHERE tenant_id=? AND book_id=? AND"
                            + " user_id=?",
                    t,
                    book,
                    user);
            for (UUID selected : ids)
                db.update(
                        "INSERT INTO plugin_finance_member_year VALUES(?,?,?,?)",
                        t,
                        book,
                        user,
                        selected);
            audit(actor, b, null, "MEMBER_GRANT", user, in);
            return Map.of(
                    "userId",
                    user,
                    "username",
                    username,
                    "role",
                    role,
                    "enabled",
                    enabled,
                    "yearScope",
                    yearScope,
                    "yearIds",
                    ids);
        }
        if (op.equals("years")) {
            var result = createYear(b, Integer.parseInt(s(in, "year")));
            audit(actor, b, (UUID) result.get("id"), "YEAR_CREATE", (UUID) result.get("id"), in);
            return result;
        }
        if (op.equals("accounts")) {
            if (!s(year(b, y), "status").equals("OPEN"))
                throw new FinanceException("PERIOD_CLOSED", "关闭年度不可新增科目");
            UUID account = UUID.randomUUID();
            String side = text(in, "normalSide", 10),
                    category = text(in, "category", 20),
                    cash = s(in, "cashFlowCategory");
            if (!Set.of("DEBIT", "CREDIT").contains(side)
                    || !Set.of("ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE")
                            .contains(category)
                    || !cash.isEmpty()
                            && !Set.of("OPERATING", "INVESTING", "FINANCING").contains(cash))
                throw FinanceException.invalid("无效科目分类");
            String purpose = s(in, "purpose").isEmpty() ? "NONE" : s(in, "purpose");
            if (!Set.of("NONE", "CURRENT_YEAR_PROFIT", "RETAINED_EARNINGS").contains(purpose)
                    || !purpose.equals("NONE")
                            && (!category.equals("EQUITY") || !side.equals("CREDIT")))
                throw FinanceException.invalid("结转科目用途必须为贷向权益科目");
            db.update(
                    "INSERT INTO"
                        + " plugin_finance_account(tenant_id,book_id,year_id,id,code,name,normal_side,category,cash_flow_category,purpose)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                    t,
                    book,
                    y,
                    account,
                    text(in, "code", 40),
                    text(in, "name", 200),
                    side,
                    category,
                    cash.isEmpty() ? null : cash,
                    purpose);
            audit(actor, b, y, "ACCOUNT_CREATE", account, in);
            return accounts(b, y).stream()
                    .filter(r -> account.equals(r.get("id")))
                    .findFirst()
                    .orElseThrow();
        }
        if (op.equals("tax-profile")) {
            db.update(
                    "INSERT INTO plugin_finance_tax_profile VALUES(?,?,?,?,?,?,'UNREVIEWED') ON"
                        + " CONFLICT(tenant_id,book_id) DO UPDATE SET"
                        + " vat_identity=excluded.vat_identity,income_tax_method=excluded.income_tax_method,region=excluded.region,notes=excluded.notes,status='UNREVIEWED'",
                    t,
                    book,
                    text(in, "vatIdentity", 100),
                    text(in, "incomeTaxMethod", 100),
                    text(in, "region", 100),
                    s(in, "notes"));
            audit(actor, b, null, "TAX_PROFILE_DRAFT", (UUID) book, in);
            return Map.of("status", "UNREVIEWED", "externalSubmissionEnabled", false);
        }
        if (op.equals("year-plan") || op.equals("year-approve") || op.equals("year-execute"))
            return yearClose(actor, b, y, op, target, in);
        if (op.equals("close-preview") || op.equals("close")) {
            var p =
                    db.one(
                            "SELECT * FROM plugin_finance_period WHERE tenant_id=? AND book_id=?"
                                    + " AND year_id=? AND id=? FOR UPDATE",
                            t,
                            book,
                            y,
                            target);
            checkVersion(p, in);
            if (!s(p, "status").equals("OPEN"))
                throw new FinanceException("PERIOD_CLOSED", "期间已关闭");
            var blockers = closeBlockers(b, y, target);
            String digest = hash(encode(canonical(List.of(p, trial(b, y), blockers))));
            if (op.equals("close-preview"))
                return Map.of(
                        "id",
                        target,
                        "version",
                        p.get("version"),
                        "digest",
                        digest,
                        "canClose",
                        blockers.isEmpty(),
                        "blockers",
                        blockers);
            if (!digest.equals(s(in, "digest")))
                throw new FinanceException("VERSION_CONFLICT", "月结预检已过期");
            if (!blockers.isEmpty()) throw FinanceException.invalid(String.join("；", blockers));
            db.update(
                    "UPDATE plugin_finance_period SET status='CLOSED',version=version+1 WHERE"
                            + " tenant_id=? AND book_id=? AND year_id=? AND id=?",
                    t,
                    book,
                    y,
                    target);
            audit(actor, b, y, "PERIOD_CLOSE", target, in);
            return Map.of(
                    "id",
                    target,
                    "month",
                    p.get("month"),
                    "status",
                    "CLOSED",
                    "version",
                    ((Number) p.get("version")).longValue() + 1);
        }
        if (op.equals("closing-drafts")) return closingDraft(actor, b, y, in);
        if (op.equals("journal")) return createJournal(actor, b, y, in, null, "NORMAL");
        var old = journal(b, y, target);
        UUID period = (UUID) old.get("periodId");
        if (!op.equals("reversals")) lockPeriod(b, y, period);
        old = journal(b, y, target);
        checkVersion(old, in);
        String state = s(old, "status");
        long v = ((Number) old.get("version")).longValue();
        if (op.equals("reversals")) {
            if (!state.equals("POSTED")) throw FinanceException.invalid("仅可冲销已记账凭证");
            Map<String, Object> reversal = new LinkedHashMap<>(in);
            reversal.put("summary", "冲销: " + text(in, "reason", 500));
            List<Map<String, Object>> lines = new ArrayList<>();
            for (var line : (List<Map<String, Object>>) old.get("lines"))
                lines.add(
                        Map.of(
                                "accountId",
                                line.get("accountId"),
                                "debit",
                                line.get("credit"),
                                "credit",
                                line.get("debit"),
                                "memo",
                                s(line, "memo")));
            reversal.put("lines", lines);
            return createJournal(actor, b, y, reversal, target, "REVERSAL");
        }
        if (state.equals("POSTED")) throw new FinanceException("APPROVAL_STALE", "已记账凭证不可修改");
        if (Set.of("edit", "documents", "submit", "withdraw", "cancel").contains(op)
                && !actor.equals(old.get("createdBy")))
            throw new FinanceException("SCOPE_DENIED", "仅原制单人可编辑、关联依据或提交");
        switch (op) {
            case "edit" -> {
                if (Set.of("PROFIT_CLOSE", "YEAR_END").contains(s(old, "type")))
                    throw FinanceException.invalid("系统结转草稿不可编辑，取消后重新生成");
                if (!state.equals("DRAFT")) throw FinanceException.invalid("仅草稿可编辑");
                personGate.createKind(b, y, s(old, "type"));
                LocalDate date = date(in);
                var p = periodFor(b, y, date);
                lockPeriod(b, y, (UUID) p.get("id"));
                validateLines(b, y, in);
                db.update(
                        "DELETE FROM plugin_finance_line WHERE tenant_id=? AND book_id=? AND"
                                + " year_id=? AND journal_id=?",
                        t,
                        book,
                        y,
                        target);
                insertLines(b, y, target, in);
                db.update(
                        "UPDATE plugin_finance_journal SET"
                            + " date=?,period_id=?,summary=?,approved_by=NULL,approved_version=NULL,version=version+1"
                            + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        date,
                        p.get("id"),
                        text(in, "summary", 500),
                        t,
                        book,
                        y,
                        target);
            }
            case "documents" -> {
                if (!state.equals("DRAFT")) throw FinanceException.invalid("附件依据变更仅允许草稿");
                UUID original = id(in, "originalId");
                var originalRow =
                        db.one(
                                "SELECT filename,sha256 FROM plugin_finance_original WHERE"
                                        + " tenant_id=? AND book_id=? AND year_id=? AND id=? AND"
                                        + " created_by=?",
                                t,
                                book,
                                y,
                                original,
                                actor);
                String sha = s(originalRow, "sha256");
                UUID d = UUID.randomUUID();
                db.update(
                        "INSERT INTO"
                            + " plugin_finance_document(tenant_id,book_id,year_id,journal_id,id,filename,sha256,source,original_location,original_id)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                        t,
                        book,
                        y,
                        target,
                        d,
                        s(originalRow, "filename"),
                        sha,
                        text(in, "source", 100),
                        text(in, "originalLocation", 500),
                        original);
                db.update(
                        "UPDATE plugin_finance_journal SET version=version+1 WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=? AND id=?",
                        t,
                        book,
                        y,
                        target);
                audit(actor, b, y, "DOCUMENT_REGISTER", target, in);
                return Map.of(
                        "id",
                        d,
                        "filename",
                        s(originalRow, "filename"),
                        "originalId",
                        original,
                        "sha256",
                        sha,
                        "source",
                        s(in, "source"),
                        "originalLocation",
                        s(in, "originalLocation"),
                        "journalVersion",
                        v + 1,
                        "verificationStatus",
                        "UNVERIFIED_ORIGINAL");
            }
            case "withdraw" -> {
                if (!state.equals("SUBMITTED")) throw FinanceException.invalid("仅已提交可撤回");
                db.update(
                        "UPDATE plugin_finance_journal SET status='DRAFT',version=version+1 WHERE"
                                + " tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        t,
                        book,
                        y,
                        target);
            }
            case "cancel" -> {
                if (!state.equals("DRAFT")) throw FinanceException.invalid("仅草稿可取消");
                text(in, "reason", 500);
                db.update(
                        "UPDATE plugin_finance_journal SET status='CANCELLED',version=version+1"
                                + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        t,
                        book,
                        y,
                        target);
            }
            case "reject" -> {
                if (!(state.equals("SUBMITTED")
                                || (personGate.enabled(b, y) && state.equals("APPROVED")))
                        || actor.equals(old.get("createdBy")))
                    throw new FinanceException("SCOPE_DENIED", "退回须独立审核人");
                text(in, "reason", 500);
                db.update(
                        "UPDATE plugin_finance_journal SET"
                            + " status='DRAFT',approved_by=NULL,approved_version=NULL,version=version+1"
                            + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        t,
                        book,
                        y,
                        target);
            }
            case "submit" -> {
                if (!state.equals("DRAFT")) throw FinanceException.invalid("仅草稿可提交");
                validateLines(b, y, old);
                db.update(
                        "UPDATE plugin_finance_journal SET status='SUBMITTED',version=version+1"
                                + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        t,
                        book,
                        y,
                        target);
            }
            case "approve" -> {
                if (!state.equals("SUBMITTED")) throw FinanceException.invalid("必须先提交审核");
                if (actor.equals(old.get("createdBy")))
                    throw new FinanceException("SELF_APPROVAL_FORBIDDEN", "制单人不得自审");
                db.update(
                        "UPDATE plugin_finance_journal SET"
                            + " status='APPROVED',approved_by=?,approved_version=?,version=version+1"
                            + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        actor,
                        v + 1,
                        t,
                        book,
                        y,
                        target);
            }
            case "post" -> {
                if (!state.equals("APPROVED"))
                    throw new FinanceException("APPROVAL_STALE", "必须先独立批准");
                var full =
                        db.one(
                                "SELECT approved_version FROM plugin_finance_journal WHERE"
                                        + " tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                t,
                                book,
                                y,
                                target);
                if (((Number) full.get("approved_version")).longValue() != v)
                    throw new FinanceException("APPROVAL_STALE", "审批版本已过期");
                if (Set.of("PROFIT_CLOSE", "YEAR_END").contains(s(old, "type"))
                        && !s(old, "closingBasis")
                                .equals(balanceDigest(b, y, LocalDate.parse(s(old, "date")))))
                    throw new FinanceException("APPROVAL_STALE", "结转余额依据已变化，取消并重新生成");
                recheckApprover(b, (UUID) old.get("approvedBy"), y);
                validateLines(b, y, old);
                long number =
                        ((Number)
                                        db.one(
                                                        "SELECT coalesce(max(number),0)+1 AS n FROM"
                                                                + " plugin_finance_journal WHERE"
                                                                + " tenant_id=? AND book_id=? AND"
                                                                + " year_id=?",
                                                        t,
                                                        book,
                                                        y)
                                                .get("n"))
                                .longValue();
                long postingSeq =
                        ((Number)
                                        db.one(
                                                        "UPDATE plugin_finance_year_guard SET"
                                                            + " posting_seq=posting_seq+1 WHERE"
                                                            + " tenant_id=? AND book_id=? AND"
                                                            + " year_id=? RETURNING posting_seq",
                                                        t,
                                                        book,
                                                        y)
                                                .get("posting_seq"))
                                .longValue();
                db.update(
                        "INSERT INTO"
                            + " plugin_finance_posting(tenant_id,book_id,year_id,journal_id,journal_version,digest,posted_by,posting_seq)"
                            + " VALUES(?,?,?,?,?,?,?,?)",
                        t,
                        book,
                        y,
                        target,
                        v,
                        hash(encode(old)),
                        actor,
                        postingSeq);
                db.update(
                        "UPDATE plugin_finance_journal SET"
                            + " status='POSTED',number=?,version=version+1 WHERE tenant_id=? AND"
                            + " book_id=? AND year_id=? AND id=?",
                        number,
                        t,
                        book,
                        y,
                        target);
                invalidateClosing(actor, b, y, target);
                FinanceOutbox.enqueue(db, this, b, y, "JOURNAL_POSTED", target, actor);
            }
            default -> throw FinanceException.invalid("未知操作");
        }
        audit(
                actor,
                b,
                y,
                "JOURNAL_" + op.toUpperCase(Locale.ROOT),
                target,
                Map.of("request", in, "before", old, "after", journal(b, y, target)));
        return journal(b, y, target);
    }

    void checkVersion(Map<String, Object> row, Map<String, Object> in) {
        if (((Number) row.get("version")).longValue() != version(in))
            throw new FinanceException("VERSION_CONFLICT", "对象版本已变化");
    }

    LocalDate date(Map<String, Object> in) {
        try {
            return LocalDate.parse(s(in, "date"));
        } catch (Exception e) {
            throw FinanceException.invalid("日期必须YYYY-MM-DD");
        }
    }

    Map<String, Object> periodFor(Map<String, Object> b, UUID y, LocalDate date) {
        var yr = year(b, y);
        if (date.getYear() != ((Number) yr.get("year")).intValue())
            throw FinanceException.invalid("日期不属于所选年度");
        return db.one(
                "SELECT * FROM plugin_finance_period WHERE tenant_id=? AND book_id=? AND year_id=?"
                        + " AND month=?",
                b.get("tenant_id"),
                b.get("id"),
                y,
                date.getMonthValue());
    }

    void lockPeriod(Map<String, Object> b, UUID y, UUID period) {
        var p =
                db.one(
                        "SELECT * FROM plugin_finance_period WHERE tenant_id=? AND book_id=? AND"
                                + " year_id=? AND id=? FOR UPDATE",
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        period);
        if (!s(p, "status").equals("OPEN") || !s(year(b, y), "status").equals("OPEN"))
            throw new FinanceException("PERIOD_CLOSED", "年度或期间关闭");
    }

    Map<String, Object> createJournal(
            UUID actor,
            Map<String, Object> b,
            UUID y,
            Map<String, Object> in,
            UUID reversal,
            String type) {
        personGate.createKind(b, y, type);
        LocalDate date = date(in);
        var p = periodFor(b, y, date);
        lockPeriod(b, y, (UUID) p.get("id"));
        validateLines(b, y, in);
        UUID j = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " plugin_finance_journal(tenant_id,book_id,year_id,id,period_id,date,summary,type,created_by,reversal_of)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                b.get("tenant_id"),
                b.get("id"),
                y,
                j,
                p.get("id"),
                date,
                text(in, "summary", 500),
                type,
                actor,
                reversal);
        insertLines(b, y, j, in);
        audit(actor, b, y, "JOURNAL_CREATE", j, in);
        return journal(b, y, j);
    }

    Map<String, AccountingRules.Account> domainAccounts(Map<String, Object> b, UUID y) {
        Map<String, AccountingRules.Account> map = new LinkedHashMap<>();
        for (var a : accounts(b, y))
            map.put(
                    s(a, "id"),
                    new AccountingRules.Account(
                            s(a, "id"),
                            s(a, "category"),
                            s(a, "normalSide"),
                            Set.of("REVENUE", "EXPENSE").contains(s(a, "category")),
                            "",
                            AccountingRules.AccountPurpose.valueOf(s(a, "purpose"))));
        return map;
    }

    List<AccountingRules.Line> domainLines(Map<String, Object> in) {
        Object raw = in.get("lines");
        if (!(raw instanceof List<?> list) || list.size() < 2 || list.size() > 500)
            throw FinanceException.invalid("凭证需要2至500条分录");
        List<AccountingRules.Line> result = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?>)) throw FinanceException.invalid("无效分录");
            var l = (Map<String, Object>) o;
            if (!(l.get("debit") instanceof String) || !(l.get("credit") instanceof String))
                throw FinanceException.invalid("金额必须为JSON字符串");
            try {
                result.add(
                        new AccountingRules.Line(
                                id(l, "accountId").toString(),
                                AccountingRules.money(s(l, "debit")),
                                AccountingRules.money(s(l, "credit")),
                                false,
                                ""));
            } catch (IllegalArgumentException e) {
                throw FinanceException.invalid("内部金额必须为十进制字符串且最多8位小数");
            }
        }
        return result;
    }

    void validateLines(Map<String, Object> b, UUID y, Map<String, Object> in) {
        var lines = domainLines(in);
        var accountMap = domainAccounts(b, y);
        var errors = AccountingRules.validateJournal(lines, accountMap);
        if (!errors.isEmpty())
            throw new FinanceException("UNBALANCED_JOURNAL", String.join(";", errors));
    }

    void insertLines(Map<String, Object> b, UUID y, UUID j, Map<String, Object> in) {
        var lines = (List<Map<String, Object>>) in.get("lines");
        int n = 0;
        for (var l : lines)
            db.update(
                    "INSERT INTO plugin_finance_line VALUES(?,?,?,?,?,?,?,?,?)",
                    b.get("tenant_id"),
                    b.get("id"),
                    y,
                    j,
                    ++n,
                    id(l, "accountId"),
                    AccountingRules.money(s(l, "debit")),
                    AccountingRules.money(s(l, "credit")),
                    s(l, "memo"));
    }

    Map<String, Object> trial(Map<String, Object> b, UUID y) {
        return trial(b, y, null);
    }

    Map<String, Object> trial(Map<String, Object> b, UUID y, LocalDate cutoff) {
        var rows =
                db.list(
                        "SELECT a.id AS"
                            + " \"accountId\",a.code,a.name,a.category,a.purpose,a.normal_side AS"
                            + " \"normalSide\",coalesce(sum(l.debit) FILTER(WHERE"
                            + " j.status='POSTED'),0)::text AS debit,coalesce(sum(l.credit)"
                            + " FILTER(WHERE j.status='POSTED'),0)::text AS"
                            + " credit,coalesce(o.balance,0)::text AS opening FROM"
                            + " plugin_finance_account a LEFT JOIN plugin_finance_line l ON"
                            + " l.tenant_id=a.tenant_id AND l.book_id=a.book_id AND"
                            + " l.year_id=a.year_id AND l.account_id=a.id LEFT JOIN"
                            + " plugin_finance_journal j ON j.tenant_id=l.tenant_id AND"
                            + " j.book_id=l.book_id AND j.year_id=l.year_id AND j.id=l.journal_id"
                            + " AND (?::date IS NULL OR j.date<=?::date) LEFT JOIN"
                            + " plugin_finance_opening o ON o.tenant_id=a.tenant_id AND"
                            + " o.book_id=a.book_id AND o.year_id=a.year_id AND o.account_id=a.id"
                            + " WHERE a.tenant_id=? AND a.book_id=? AND a.year_id=? GROUP BY"
                            + " a.id,a.code,a.name,a.category,a.purpose,a.normal_side,o.balance"
                            + " ORDER BY a.code",
                        cutoff,
                        cutoff,
                        b.get("tenant_id"),
                        b.get("id"),
                        y);
        boolean legacy = Boolean.TRUE.equals(year(b, y).get("precision_review_required"));
        BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        for (var r : rows) {
            BigDecimal d = new BigDecimal(s(r, "debit")),
                    c = new BigDecimal(s(r, "credit")),
                    opening = new BigDecimal(s(r, "opening"));
            r.put(
                    "balance",
                    opening.add(d)
                            .subtract(c)
                            .setScale(legacy ? 6 : 8, java.math.RoundingMode.UNNECESSARY)
                            .toPlainString());
            if (legacy)
                for (String field : List.of("debit", "credit", "opening"))
                    r.put(
                            field,
                            new BigDecimal(s(r, field))
                                    .setScale(6, java.math.RoundingMode.UNNECESSARY)
                                    .toPlainString());
            debit = debit.add(d).add(opening.max(BigDecimal.ZERO));
            credit = credit.add(c).add(opening.negate().max(BigDecimal.ZERO));
        }
        return Map.of(
                "rows",
                rows,
                "debit",
                debit.setScale(legacy ? 6 : 8, java.math.RoundingMode.UNNECESSARY).toPlainString(),
                "credit",
                credit.setScale(legacy ? 6 : 8, java.math.RoundingMode.UNNECESSARY).toPlainString(),
                "balanced",
                debit.compareTo(credit) == 0,
                "watermark",
                watermark(b, y));
    }

    List<String> closeBlockers(Map<String, Object> b, UUID y, UUID p) {
        List<String> errors = new ArrayList<>();
        long pending =
                ((Number)
                                db.one(
                                                "SELECT count(*) AS n FROM plugin_finance_journal"
                                                    + " WHERE tenant_id=? AND book_id=? AND"
                                                    + " year_id=? AND period_id=? AND status NOT"
                                                    + " IN('POSTED','CANCELLED')",
                                                b.get("tenant_id"),
                                                b.get("id"),
                                                y,
                                                p)
                                        .get("n"))
                        .longValue();
        if (pending > 0) errors.add("存在未记账凭证");
        if (!Boolean.TRUE.equals(trial(b, y).get("balanced"))) errors.add("试算不平衡");
        return errors;
    }

    Object yearClose(
            UUID actor,
            Map<String, Object> b,
            UUID y,
            String op,
            UUID target,
            Map<String, Object> in) {
        Object t = b.get("tenant_id"), book = b.get("id");
        if (op.equals("year-plan")) {
            UUID ty = id(in, "targetYearId");
            var sy = year(b, y);
            var targetYear = year(b, ty);
            if (((Number) targetYear.get("year")).intValue()
                    != ((Number) sy.get("year")).intValue() + 1)
                throw FinanceException.invalid("目标必须为次年");
            var snapshot = yearSnapshot(b, y, ty);
            if (!((List<?>) snapshot.get("blockers")).isEmpty())
                return Map.of("status", "BLOCKED", "blockers", snapshot.get("blockers"));
            UUID plan = UUID.randomUUID();
            String payload = encode(canonical(snapshot)), digest = hash(payload);
            db.update(
                    "INSERT INTO"
                        + " plugin_finance_close_plan(tenant_id,book_id,id,source_year_id,target_year_id,digest,created_by,payload)"
                        + " VALUES(?,?,?,?,?,?,?,?)",
                    t,
                    book,
                    plan,
                    y,
                    ty,
                    digest,
                    actor,
                    payload);
            audit(actor, b, y, "YEAR_PREVIEW", plan, in);
            return plan(b, plan);
        }
        var p =
                db.one(
                        "SELECT * FROM plugin_finance_close_plan WHERE tenant_id=? AND book_id=?"
                                + " AND source_year_id=? AND id=? FOR UPDATE",
                        t,
                        book,
                        y,
                        target);
        checkVersion(p, in);
        UUID ty = (UUID) p.get("target_year_id");
        String digest = hash(encode(canonical(yearSnapshot(b, y, ty))));
        if (!s(p, "digest").equals(s(in, "digest")) || !digest.equals(s(p, "digest")))
            throw new FinanceException("APPROVAL_STALE", "年结来源或映射已变化");
        if (op.equals("year-approve")) {
            if (actor.equals(p.get("created_by")))
                throw new FinanceException("SELF_APPROVAL_FORBIDDEN", "年结需要独立批准");
            if (!s(p, "status").equals("PREVIEW"))
                throw new FinanceException("APPROVAL_STALE", "年结预览状态已变化");
            db.update(
                    "UPDATE plugin_finance_close_plan SET"
                        + " status='APPROVED',approved_by=?,version=version+1 WHERE tenant_id=? AND"
                        + " book_id=? AND id=?",
                    actor,
                    t,
                    book,
                    target);
        } else {
            if (!s(p, "status").equals("APPROVED"))
                throw new FinanceException("APPROVAL_STALE", "年结未批准");
            recheckApprover(b, (UUID) p.get("approved_by"), y, ty);
            var snap = yearSnapshot(b, y, ty);
            if (!((List<?>) snap.get("blockers")).isEmpty())
                throw FinanceException.invalid("年结校验失败");
            db.update(
                    "UPDATE plugin_finance_year SET status='CLOSING' WHERE tenant_id=? AND"
                            + " book_id=? AND id=?",
                    t,
                    book,
                    y);
            for (var r : (List<Map<String, Object>>) snap.get("balances")) {
                BigDecimal balance = new BigDecimal(s(r, "balance"));
                if (balance.signum() != 0)
                    db.update(
                            "INSERT INTO"
                                + " plugin_finance_opening(tenant_id,book_id,year_id,account_id,plan_id,balance)"
                                + " VALUES(?,?,?,?,?,?)",
                            t,
                            book,
                            ty,
                            r.get("targetAccountId"),
                            target,
                            balance);
            }
            db.update(
                    "UPDATE plugin_finance_year_guard SET opening_version=opening_version+1 WHERE"
                            + " tenant_id=? AND book_id=? AND year_id=?",
                    t,
                    book,
                    ty);
            db.update(
                    "UPDATE plugin_finance_year_guard SET"
                            + " source_close_version=source_close_version+1 WHERE tenant_id=? AND"
                            + " book_id=? AND year_id=?",
                    t,
                    book,
                    y);
            db.update(
                    "UPDATE plugin_finance_year SET"
                        + " status='CLOSED_PENDING_ARCHIVE',version=version+1 WHERE tenant_id=? AND"
                        + " book_id=? AND id=?",
                    t,
                    book,
                    y);
            db.update(
                    "UPDATE plugin_finance_close_plan SET status='EXECUTED',version=version+1 WHERE"
                            + " tenant_id=? AND book_id=? AND id=?",
                    t,
                    book,
                    target);
        }
        audit(actor, b, y, "YEAR_" + op, target, in);
        return plan(b, target);
    }

    Map<String, Object> plan(Map<String, Object> b, UUID plan) {
        var p =
                db.one(
                        "SELECT payload,id,source_year_id AS \"sourceYearId\",target_year_id AS"
                            + " \"targetYearId\",created_by AS \"createdBy\",approved_by AS"
                            + " \"approvedBy\",status,version,digest FROM plugin_finance_close_plan"
                            + " WHERE tenant_id=? AND book_id=? AND id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        plan);
        year(b, (UUID) p.get("sourceYearId"));
        year(b, (UUID) p.get("targetYearId"));
        p.put("preview", decode(s(p, "payload")));
        p.remove("payload");
        p.put("blockers", List.of());
        return p;
    }

    Map<String, Object> yearSnapshot(Map<String, Object> b, UUID y, UUID ty) {
        List<String> blockers = new ArrayList<>();
        Object t = b.get("tenant_id"), book = b.get("id");
        var source = year(b, y);
        var target = year(b, ty);
        if (!s(source, "status").equals("OPEN") || !s(target, "status").equals("OPEN"))
            blockers.add("源年度和目标年度必须开放");
        long open =
                ((Number)
                                db.one(
                                                "SELECT count(*) AS n FROM plugin_finance_period"
                                                        + " WHERE tenant_id=? AND book_id=? AND"
                                                        + " year_id=? AND status<>'CLOSED'",
                                                t,
                                                book,
                                                y)
                                        .get("n"))
                        .longValue();
        if (open > 0) blockers.add("源年度所有期间须先关闭");
        long pending =
                ((Number)
                                db.one(
                                                "SELECT count(*) AS n FROM plugin_finance_journal"
                                                        + " WHERE tenant_id=? AND book_id=? AND"
                                                        + " year_id=? AND status NOT"
                                                        + " IN('POSTED','CANCELLED')",
                                                t,
                                                book,
                                                y)
                                        .get("n"))
                        .longValue();
        if (pending > 0) blockers.add("源年度存在未记账凭证");
        if (!db.list(
                        "SELECT account_id FROM plugin_finance_opening WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=? LIMIT 1",
                        t,
                        book,
                        ty)
                .isEmpty()) blockers.add("目标已存在正式期初，不允许覆盖");
        Map<String, Map<String, Object>> targets = new HashMap<>();
        for (var a : accounts(b, ty)) targets.put(s(a, "code"), a);
        var trial = trial(b, y);
        List<Map<String, Object>> balances = new ArrayList<>();
        for (var row : (List<Map<String, Object>>) trial.get("rows")) {
            BigDecimal amount = new BigDecimal(s(row, "balance"));
            try {
                AccountingRules.money(amount.toPlainString());
            } catch (IllegalArgumentException overflow) {
                blockers.add("期初余额超出numeric(28,8)容量: " + s(row, "code"));
            }
            if ((Set.of("REVENUE", "EXPENSE").contains(s(row, "category"))
                            || s(row, "purpose").equals("CURRENT_YEAR_PROFIT"))
                    && amount.signum() != 0) blockers.add("损益科目未结清: " + s(row, "code"));
            if (amount.signum() == 0) continue;
            var destination = targets.get(s(row, "code"));
            if (destination == null
                    || !s(destination, "category").equals(s(row, "category"))
                    || !s(destination, "normalSide").equals(s(row, "normalSide"))
                    || !s(destination, "purpose").equals(s(row, "purpose")))
                blockers.add("目标科目映射缺失或语义不一致: " + s(row, "code"));
            else
                balances.add(
                        Map.of(
                                "code",
                                row.get("code"),
                                "targetAccountId",
                                destination.get("id"),
                                "balance",
                                amount.toPlainString()));
        }
        if (!Boolean.TRUE.equals(trial.get("balanced"))) blockers.add("期末试算不平衡");
        return Map.of(
                "balances",
                balances,
                "blockers",
                blockers,
                "sourceVersion",
                source.get("version"),
                "targetVersion",
                target.get("version"),
                "sourceWatermark",
                watermark(b, y),
                "targetWatermark",
                watermark(b, ty));
    }

    void validateInput(String op, Map<String, Object> in) {
        String fields =
                switch (op) {
                    case "book" ->
                            "tenantId legalEntityId name legalEntityName registrationId standard"
                                    + " baseCurrency year";
                    case "members" -> "username role enabled yearScope yearIds";
                    case "years" -> "year";
                    case "accounts" -> "code name normalSide category cashFlowCategory purpose";
                    case "journal", "edit" -> "date summary lines";
                    case "closing-drafts" -> "date stage";
                    case "reject", "cancel" -> "reason";
                    case "reversals" -> "date reason";
                    case "documents" -> "originalId source originalLocation";
                    case "tax-profile" -> "vatIdentity incomeTaxMethod region notes";
                    case "year-plan" -> "targetYearId";
                    case "close", "year-approve", "year-execute" -> "digest";
                    default -> "";
                };
        Set<String> allowed =
                new HashSet<>(
                        Arrays.asList((fields + " idempotencyKey expectedVersion").split(" ")));
        if (!allowed.containsAll(in.keySet())) throw FinanceException.invalid("请求包含不支持的字段");
        for (var entry : in.entrySet()) {
            if (!Set.of("year", "expectedVersion", "enabled", "lines", "yearIds")
                            .contains(entry.getKey())
                    && entry.getValue() != null
                    && !(entry.getValue() instanceof String))
                throw FinanceException.invalid("字段必须为字符串: " + entry.getKey());
        }
        if (in.containsKey("lines")) {
            if (!(in.get("lines") instanceof List<?> lines))
                throw FinanceException.invalid("lines必须数组");
            for (Object line : lines) {
                if (!(line instanceof Map<?, ?> m)
                        || !Set.of("accountId", "debit", "credit", "memo").containsAll(m.keySet()))
                    throw FinanceException.invalid("分录字段无效");
            }
        }
    }

    Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(k.toString(), canonical(v)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::canonical).toList();
        return value;
    }

    void recheckApprover(Map<String, Object> b, UUID user, UUID... years) {
        if (user == null) throw new FinanceException("APPROVAL_STALE", "审批人不存在");
        var rows =
                db.list(
                        "SELECT username FROM plugin_finance_member WHERE tenant_id=? AND book_id=?"
                                + " AND user_id=? AND enabled",
                        b.get("tenant_id"),
                        b.get("id"),
                        user);
        if (rows.isEmpty()) throw new FinanceException("APPROVAL_STALE", "审批人范围授权已失效");
        String username = s(rows.getFirst(), "username");
        try {
            UUID current = authorize(username, "approve");
            if (!current.equals(user)) throw new IllegalArgumentException();
            var approvedScope = scope(current, (UUID) b.get("id"), "approve");
            for (UUID selected : years) year(approvedScope, selected);
        } catch (Exception denied) {
            throw new FinanceException("APPROVAL_STALE", "审批人身份或操作权限已失效");
        }
    }

    Map<String, Object> planForYear(Map<String, Object> b, UUID y, UUID plan) {
        db.one(
                "SELECT id FROM plugin_finance_close_plan WHERE tenant_id=? AND book_id=? AND"
                        + " source_year_id=? AND id=?",
                b.get("tenant_id"),
                b.get("id"),
                y,
                plan);
        return plan(b, plan);
    }

    Map<String, Object> originalScope(String actor, UUID book, UUID y, String action) {
        UUID user = authorize(actor, action);
        var b = scope(user, book, action);
        year(b, y);
        return b;
    }

    /** Book master data has no fiscal-year context; this never authorizes year-scoped facts. */
    Map<String, Object> originalBookScope(String actor, UUID book, String action) {
        UUID user = authorize(actor, action);
        return scope(user, book, action);
    }

    void originalAudit(
            UUID actor, Map<String, Object> b, UUID y, String action, UUID target, Object payload) {
        audit(actor, b, y, action, target, payload);
    }

    String balanceDigest(Map<String, Object> b, UUID y, LocalDate date) {
        var balance = new LinkedHashMap<>(trial(b, y, date));
        balance.remove("watermark");
        return hash(encode(canonical(balance)));
    }

    Map<String, Object> closingDraft(
            UUID actor, Map<String, Object> b, UUID y, Map<String, Object> in) {
        String stage = text(in, "stage", 20);
        if (!Set.of("PROFIT_LOSS", "YEAR_END").contains(stage))
            throw FinanceException.invalid("无效结转阶段");
        LocalDate d = date(in);
        if (stage.equals("YEAR_END") && (d.getMonthValue() != 12 || d.getDayOfMonth() != 31)
                || stage.equals("PROFIT_LOSS") && d.getDayOfMonth() != d.lengthOfMonth())
            throw FinanceException.invalid("年度损益转存须使用12月31日");
        var balances = new LinkedHashMap<String, BigDecimal>();
        for (var row : (List<Map<String, Object>>) trial(b, y, d).get("rows"))
            balances.put(s(row, "accountId"), new BigDecimal(s(row, "balance")));
        var accounts = domainAccounts(b, y);
        if (stage.equals("YEAR_END")
                && accounts.values().stream()
                        .anyMatch(
                                a ->
                                        a.profitLoss()
                                                && balances.getOrDefault(a.id(), BigDecimal.ZERO)
                                                                .signum()
                                                        != 0))
            throw new FinanceException("APPROVAL_STALE", "请先将损益结转凭证正式记账");
        var standard =
                s(b, "standard").equals("CAS")
                        ? AccountingRules.AccountingStandard.CAS_2019
                        : AccountingRules.AccountingStandard.SBE_2011;
        AccountingRules.ClosingPlan plan;
        try {
            plan = AccountingRules.planProfitClosing(standard, balances, accounts);
        } catch (IllegalArgumentException error) {
            throw FinanceException.invalid("结转检查失败: " + error.getMessage());
        }
        var draft =
                plan.drafts().stream()
                        .filter(x -> x.closingType().name().equals(stage))
                        .findFirst()
                        .orElseThrow(() -> FinanceException.invalid("当前余额无此阶段待结转金额"));
        List<Map<String, Object>> lines =
                draft.lines().stream()
                        .map(
                                l ->
                                        Map.<String, Object>of(
                                                "accountId",
                                                l.accountId(),
                                                "debit",
                                                l.debit().toPlainString(),
                                                "credit",
                                                l.credit().toPlainString(),
                                                "memo",
                                                "系统结转"))
                        .toList();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("date", s(in, "date"));
        input.put("summary", stage.equals("PROFIT_LOSS") ? "损益结转" : "本年利润转入未分配利润");
        input.put("lines", lines);
        String basis = balanceDigest(b, y, d);
        var result =
                createJournal(
                        actor,
                        b,
                        y,
                        input,
                        null,
                        stage.equals("PROFIT_LOSS") ? "PROFIT_CLOSE" : "YEAR_END");
        db.update(
                "UPDATE plugin_finance_journal SET closing_basis=? WHERE tenant_id=? AND book_id=?"
                        + " AND year_id=? AND id=?",
                basis,
                b.get("tenant_id"),
                b.get("id"),
                y,
                result.get("id"));
        return journal(b, y, (UUID) result.get("id"));
    }

    void invalidateClosing(UUID actor, Map<String, Object> b, UUID y, UUID posted) {
        String typedExclusion =
                personGate.enabled(b, y)
                        ? " AND NOT EXISTS(SELECT 1 FROM plugin_finance_profit_closing_run r"
                              + " WHERE(r.tenant_id,r.book_id,r.year_id,r.journal_id)=(j.tenant_id,j.book_id,j.year_id,j.id))"
                        : "";
        var stale =
                db.list(
                        "SELECT j.id,j.date,j.closing_basis FROM plugin_finance_journal j WHERE"
                            + " j.tenant_id=? AND j.book_id=? AND j.year_id=? AND j.id<>? AND"
                            + " j.type IN('PROFIT_CLOSE','YEAR_END') AND j.status"
                            + " IN('DRAFT','SUBMITTED','APPROVED')"
                                + typedExclusion,
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        posted);
        for (var row : stale) {
            if (s(row, "closing_basis")
                    .equals(balanceDigest(b, y, LocalDate.parse(s(row, "date"))))) continue;
            db.update(
                    "UPDATE plugin_finance_journal SET"
                        + " status='DRAFT',approved_by=NULL,approved_version=NULL,version=version+1"
                        + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                    b.get("tenant_id"),
                    b.get("id"),
                    y,
                    row.get("id"));
            audit(
                    actor,
                    b,
                    y,
                    "CLOSING_APPROVAL_INVALIDATED",
                    (UUID) row.get("id"),
                    Map.of("postingId", posted));
        }
    }

    boolean yearAllowed(Map<String, Object> b, UUID y) {
        if (!b.containsKey("actor_id")) return true;
        if (s(b, "year_scope").equals("ALL")) return true;
        return !db.list(
                        "SELECT year_id FROM plugin_finance_member_year WHERE tenant_id=? AND"
                                + " book_id=? AND user_id=? AND year_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        b.get("actor_id"),
                        y)
                .isEmpty();
    }

    List<Map<String, Object>> members(Map<String, Object> b) {
        var rows =
                db.list(
                        "SELECT user_id AS \"userId\",username,role,enabled,year_scope AS"
                                + " \"yearScope\" FROM plugin_finance_member WHERE tenant_id=? AND"
                                + " book_id=? ORDER BY username LIMIT 200",
                        b.get("tenant_id"),
                        b.get("id"));
        for (var row : rows)
            row.put(
                    "yearIds",
                    db
                            .list(
                                    "SELECT year_id FROM plugin_finance_member_year WHERE"
                                            + " tenant_id=? AND book_id=? AND user_id=? ORDER BY"
                                            + " year_id",
                                    b.get("tenant_id"),
                                    b.get("id"),
                                    row.get("userId"))
                            .stream()
                            .map(r -> r.get("year_id"))
                            .toList());
        return rows;
    }

    List<Map<String, Object>> plans(Map<String, Object> b, UUID y) {
        return db
                .list(
                        "SELECT id,source_year_id AS \"sourceYearId\",target_year_id AS"
                            + " \"targetYearId\",created_by AS \"createdBy\",approved_by AS"
                            + " \"approvedBy\",status,version,digest FROM plugin_finance_close_plan"
                            + " WHERE tenant_id=? AND book_id=? AND source_year_id=? ORDER BY id"
                            + " LIMIT 200",
                        b.get("tenant_id"),
                        b.get("id"),
                        y)
                .stream()
                .filter(row -> yearAllowed(b, (UUID) row.get("targetYearId")))
                .toList();
    }

    void lockYearWrites(Map<String, Object> b, List<UUID> years) {
        for (UUID y :
                years.stream()
                        .distinct()
                        .sorted(
                                Comparator.<UUID>comparingInt(
                                                id -> ((Number) year(b, id).get("year")).intValue())
                                        .thenComparing(UUID::toString))
                        .toList()) {
            year(b, y);
            db.one(
                    "SELECT * FROM plugin_finance_year_guard WHERE tenant_id=? AND book_id=? AND"
                            + " year_id=? FOR UPDATE",
                    b.get("tenant_id"),
                    b.get("id"),
                    y);
            if (Boolean.TRUE.equals(year(b, y).get("precision_review_required")))
                throw new FinanceException(
                        "LEGACY_PRECISION_REVIEW_REQUIRED", "旧年度精度待专业复核，仅可读取历史证据");
        }
    }

    Map<String, Object> watermark(Map<String, Object> b, UUID y) {
        var mark =
                db.one(
                        "SELECT posting_seq AS \"postingSeq\",opening_version AS"
                            + " \"openingVersion\",baseline_version AS"
                            + " \"baselineVersion\",source_close_version AS \"sourceCloseVersion\""
                            + " FROM plugin_finance_year_guard WHERE tenant_id=? AND book_id=? AND"
                            + " year_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y);
        mark.put(
                "legacyUnsequencedFacts",
                Boolean.TRUE.equals(year(b, y).get("precision_review_required")));
        return mark;
    }

    void validateFormalInput(Map<String, Object> input) {
        if (!(input.get("lines") instanceof List<?> lines))
            throw FinanceException.invalid("lines必须为数组");
        for (Object raw : lines) {
            if (!(raw instanceof Map<?, ?> line)) throw FinanceException.invalid("无效分录");
            for (String field : List.of("debit", "credit")) {
                if (!(line.get(field) instanceof String literal))
                    throw FinanceException.invalid("金额须为十进制字符串");
                try {
                    PrecisionRules.formalCurrencyMoney(literal, 2);
                } catch (IllegalArgumentException invalid) {
                    if ("DECIMAL_OVERFLOW".equals(invalid.getMessage()))
                        throw new FinanceException(
                                "AMOUNT_OVERFLOW", "金额超出numeric(28,8)容量，整数部分最多20位");
                    throw new FinanceException("INVALID_CURRENCY_SCALE", "人民币正式借贷输入最多2位小数，禁止静默舍入");
                }
            }
        }
    }
}

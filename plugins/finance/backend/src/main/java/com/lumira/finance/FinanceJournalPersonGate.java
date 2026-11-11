package com.lumira.finance;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.identity.api.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import tools.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * No FinanceService/Directory bean dependency. Production never falls back on missing mode tables.
 */
@Service
public class FinanceJournalPersonGate {
    final FinanceStore db;
    final AdministrationPort permissions;
    final AccountOwnerPort owners;
    final ObjectMapper json;
    final boolean historicalFixture;

    @Autowired
    public FinanceJournalPersonGate(
            FinanceStore db,
            AdministrationPort permissions,
            AccountOwnerPort owners,
            ObjectMapper json) {
        this(db, permissions, owners, json, false);
    }

    private FinanceJournalPersonGate(
            FinanceStore db,
            AdministrationPort permissions,
            AccountOwnerPort owners,
            ObjectMapper json,
            boolean historicalFixture) {
        this.db = db;
        this.permissions = permissions;
        this.owners = owners;
        this.json = json;
        this.historicalFixture = historicalFixture;
    }

    /**
     * Package-only explicit old-schema test adapter; never selected by Spring production wiring.
     */
    static FinanceJournalPersonGate historicalFixture(
            FinanceStore db,
            AdministrationPort permissions,
            AccountOwnerPort owners,
            ObjectMapper json) {
        return new FinanceJournalPersonGate(db, permissions, owners, json, true);
    }

    static Map<String, Object> map(Object... args) {
        return FinancePersonDirectory.map(args);
    }

    String encode(Object x) {
        return json.writeValueAsString(x);
    }

    Map<String, Object> decode(Object x) {
        return json.readValue(x.toString(), Map.class);
    }

    static FinanceException error(String c, String m) {
        return new FinanceException(c, m);
    }

    void prelock(UUID tenant) {
        if (!historicalFixture)
            db.list(
                    "SELECT tenant_id FROM plugin_finance_person_realm WHERE tenant_id=? FOR SHARE",
                    tenant);
    }

    Map<String, Object> mode(Map<String, Object> b, UUID year, boolean exclusive) {
        if (historicalFixture) return map("mode", "ACCOUNT_ONLY");
        var list =
                db.list(
                        "SELECT * FROM plugin_finance_journal_person_mode WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=?"
                                + (exclusive ? " FOR UPDATE" : " FOR SHARE"),
                        b.get("tenant_id"),
                        b.get("id"),
                        year);
        if (list.isEmpty()) throw error("JOURNAL_PERSON_MODE_STALE", "年度人员协议基线缺失，不能自动降级");
        return list.getFirst();
    }

    boolean enabled(Map<String, Object> b, UUID y) {
        return y != null && "JOURNAL_PERSON_SOD".equals(mode(b, y, false).get("mode"));
    }

    void lockModes(Map<String, Object> b, List<UUID> years) {
        if (historicalFixture || years.isEmpty()) return;
        var rows =
                db.list(
                        "SELECT id FROM plugin_finance_year WHERE tenant_id=? AND book_id=? ORDER"
                                + " BY year,id",
                        b.get("tenant_id"),
                        b.get("id"));
        for (var row : rows)
            if (years.contains(row.get("id"))) mode(b, (UUID) row.get("id"), false);
    }

    Map<String, Object> identity(UUID tenant, UUID account) {
        var rows =
                db.list(
                        "SELECT status FROM plugin_finance_person_binding WHERE tenant_id=? AND"
                                + " account_id=?",
                        tenant,
                        account);
        if (rows.isEmpty() || !"ACTIVE".equals(rows.getFirst().get("status")))
            throw error("JOURNAL_PERSON_IDENTITY_UNAVAILABLE", "缺少有效组织人员账号绑定");
        return decode(
                db.one(
                                "SELECT plugin_finance_journal_person_identity(?, ?, true)::text"
                                        + " value",
                                tenant,
                                account)
                        .get("value"));
    }

    Map<String, Object> current(
            UUID actor, UUID tenant, boolean recent, HttpServletRequest request) {
        var c = AuthenticationRequestContext.current();
        if (c == null
                || !actor.equals(c.userId())
                || c.authorization() == null
                || !actor.equals(c.authorization().actorId())
                || !c.principalName().equals(c.authorization().principalName()))
            throw error("JOURNAL_PERSON_IDENTITY_UNAVAILABLE", "缺少宿主已验证的当前账号上下文");
        if (recent)
            FinancePersonDirectory.assurance(
                    c.principalName(), c, request, java.time.Instant.now());
        return identity(tenant, actor);
    }

    HttpServletRequest request() {
        var a = RequestContextHolder.getRequestAttributes();
        return a instanceof ServletRequestAttributes s ? s.getRequest() : null;
    }

    void independent(Map<String, Object> who, Map<String, Object> content) {
        var maker = decode(content.get("maker"));
        var editor = decode(content.get("editor"));
        if (who.get("personId").equals(maker.get("personId"))
                || who.get("personId").equals(editor.get("personId")))
            throw error("SELF_APPROVAL_FORBIDDEN", "同一自然人不能通过另一个账号自审");
    }

    Map<String, Object> content(Map<String, Object> b, UUID y, UUID j) {
        return db.one(
                "SELECT c.* FROM plugin_finance_journal_person_current p JOIN"
                    + " plugin_finance_journal_person_content c"
                    + " ON(c.tenant_id,c.book_id,c.year_id,c.journal_id,c.content_version)=(p.tenant_id,p.book_id,p.year_id,p.journal_id,p.content_version)"
                    + " WHERE p.tenant_id=? AND p.book_id=? AND p.year_id=? AND p.journal_id=?",
                b.get("tenant_id"),
                b.get("id"),
                y,
                j);
    }

    void unsupported(Map<String, Object> b, UUID y) {
        if (enabled(b, y))
            throw error(
                    "JOURNAL_PERSON_PROTOCOL_UNSUPPORTED", "本年度人员协议只支持人工NORMAL凭证，不支持来源、冲销或系统结转");
    }

    void beforeLegacy(
            UUID actor,
            Map<String, Object> b,
            UUID y,
            String op,
            UUID target,
            Map<String, Object> in) {
        if (y == null || !enabled(b, y)) return;
        if (target != null
                && Set.of(
                                "edit",
                                "documents",
                                "submit",
                                "withdraw",
                                "cancel",
                                "reject",
                                "approve",
                                "post",
                                "reversals")
                        .contains(op)
                && payableProducer(b, y, target))
            throw error("JOURNAL_PERSON_MODE_REQUIRED", "应付业务凭证须通过应付会计确认协议操作");
        if (target != null
                && Set.of(
                                "edit",
                                "documents",
                                "submit",
                                "withdraw",
                                "cancel",
                                "reject",
                                "approve",
                                "post")
                        .contains(op)
                && profitClosingProducer(b, y, target))
            throw error("JOURNAL_PERSON_MODE_REQUIRED", "系统结转须通过实际生产者专用协议操作，不能套用普通凭证动作");
        if (Set.of("year-plan", "year-approve", "year-execute").contains(op))
            throw error("JOURNAL_PERSON_YEAR_CLOSE_UNSUPPORTED", "人员协议年度暂未接入年结独立证明");
        if (Set.of("reversals", "closing-drafts").contains(op))
            throw error("JOURNAL_PERSON_PROTOCOL_UNSUPPORTED", "本年度只支持人工NORMAL凭证");
        if (Set.of("approve", "post").contains(op))
            throw error("JOURNAL_PERSON_MODE_REQUIRED", "请使用内容绑定的自然人批准/记账协议，旧请求键不能绕过");
        if (Set.of("journal", "edit", "documents", "submit", "withdraw", "cancel", "reject")
                .contains(op)) {
            boolean review = op.equals("reject");
            var who = current(actor, (UUID) b.get("tenant_id"), review, review ? request() : null);
            if (review) {
                permissions.require(
                        AuthenticationRequestContext.current().principalName(),
                        "platform:finance:read");
                independent(who, content(b, y, target));
            }
        }
    }

    /** Current schema only. Explicit historic fixtures do not select the new protocol. */
    boolean profitClosingProducer(Map<String, Object> b, UUID year, UUID journal) {
        if (historicalFixture) return false;
        return !db.list(
                        "SELECT id FROM plugin_finance_profit_closing_run WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=? AND journal_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        journal)
                .isEmpty();
    }

    /** Typed AP/AR/Asset raw types are reserved for their typed producers; ordinary journal actions never own them. */
    boolean payableProducer(Map<String, Object> b, UUID year, UUID journal) {
        if (historicalFixture) return false;
        return !db.list(
                        "SELECT id FROM plugin_finance_journal WHERE tenant_id=? AND book_id=? AND"
                            + " year_id=? AND id=? AND type IN("
                            + "'AP_EXPENSE','AP_EXPENSE_REVERSAL',"
                            + "'AR_REVENUE','AR_RECEIPT','AR_ALLOCATION','AR_REVERSAL',"
                            + "'ASSET_ACQUIRE','ASSET_DEPRECIATE','ASSET_DISPOSE','ASSET_REVERSE','ASSET_CORRECT_DEPRECIATION')",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        journal)
                .isEmpty();
    }

    void beforeCrossYear(Map<String, Object> b, List<UUID> years, String op) {
        if (!Set.of("year-plan", "year-approve", "year-execute").contains(op)) return;
        for (UUID year : years)
            if (enabled(b, year))
                throw error("JOURNAL_PERSON_YEAR_CLOSE_UNSUPPORTED", "年结源或目标为人员协议年度，当前未开放");
    }

    void createKind(Map<String, Object> b, UUID year, String type) {
        if (enabled(b, year) && !"NORMAL".equals(type))
            throw error("JOURNAL_PERSON_PROTOCOL_UNSUPPORTED", "本年度只支持人工NORMAL凭证");
    }

    void finishLegacy(
            UUID actor,
            Map<String, Object> b,
            UUID y,
            String op,
            UUID target,
            Map<String, Object> in,
            Object result) {
        if (y == null
                || !enabled(b, y)
                || !Set.of("journal", "edit", "documents", "submit", "withdraw", "cancel", "reject")
                        .contains(op)) return;
        UUID journal = target;
        if (op.equals("journal"))
            journal = UUID.fromString(((Map<?, ?>) result).get("id").toString());
        var who = current(actor, (UUID) b.get("tenant_id"), false, null);
        String action =
                switch (op) {
                    case "journal" -> "CREATE";
                    case "documents" -> "DOCUMENTS";
                    default -> op.toUpperCase(Locale.ROOT);
                };
        seal(b, y, journal, who, action, null, Objects.toString(in.get("reason"), null));
    }

    void seal(
            Map<String, Object> b,
            UUID y,
            UUID j,
            Map<String, Object> who,
            String action,
            UUID approval,
            String reason) {
        db.list(
                "SELECT plugin_finance_journal_person_seal(?,?,?,?,?::jsonb,?,?,?)",
                b.get("tenant_id"),
                b.get("id"),
                y,
                j,
                encode(who),
                action,
                approval,
                reason);
    }

    Map<String, Object> rawJournal(Map<String, Object> b, UUID y, UUID j) {
        return db.one(
                "SELECT * FROM plugin_finance_journal WHERE tenant_id=? AND book_id=? AND year_id=?"
                        + " AND id=?",
                b.get("tenant_id"),
                b.get("id"),
                y,
                j);
    }

    void audit(
            UUID actor, Map<String, Object> b, UUID y, String action, UUID target, Object payload) {
        db.update(
                "INSERT INTO"
                    + " plugin_finance_journal_person_audit(tenant_id,book_id,year_id,id,actor_id,action,target_id,payload)"
                    + " VALUES(?,?,?,?,?,?,?,?::jsonb)",
                b.get("tenant_id"),
                b.get("id"),
                y,
                UUID.randomUUID(),
                actor,
                action,
                target,
                encode(payload));
    }
}

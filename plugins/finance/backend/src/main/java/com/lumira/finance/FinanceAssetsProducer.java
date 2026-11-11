package com.lumira.finance;

import static com.lumira.finance.FinanceReceivablesRules.*;
import static com.lumira.finance.assets.AssetModel.*;

import com.lumira.finance.assets.AssetPorts;
import com.lumira.platform.common.AuthorizationContextProjectionPort;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Same-connection ACCOUNTING_CONFIRMATION source quota adapter and native ASSET_* journal producer.
 * Executes inside AssetService's single JDBC connection without opening nested connections or network I/O.
 */
@Service
public final class FinanceAssetsProducer extends FinanceProfitClosingAccess
        implements AssetPorts.Evidence, AssetPorts.Posting {
    private static final Set<String> CREATE_KEYS =
            Set.of("idempotencyKey", "originalId", "amount", "reason");
    private static final Set<String> APPROVE_KEYS =
            Set.of("idempotencyKey", "expectedVersion", "reason");

    public FinanceAssetsProducer(
            FinanceStore db,
            FinanceService finance,
            FinanceJournalPersonGate gate,
            PlatformTransactionManager tx,
            AuthorizationContextProjectionPort authorization) {
        super(db, finance, gate, tx, authorization);
    }

    private String hashValue(Object value) {
        return FinanceService.hash(finance.encode(finance.canonical(value)));
    }

    @Override
    public void reserve(Connection c, Context ctx, UUID sourceId, UUID plan, BigDecimal amount)
            throws Exception {
        UUID originalId;
        BigDecimal quotaAmount;
        String status;
        String state;
        try (var q =
                c.prepareStatement(
                        "SELECT status,state,amount,original_id FROM"
                            + " plugin_finance_asset_source_quota WHERE tenant_id=? AND book_id=?"
                            + " AND year_id=? AND id=? FOR UPDATE")) {
            q.setObject(1, ctx.scope().tenant());
            q.setObject(2, ctx.scope().book());
            q.setObject(3, ctx.scope().year());
            q.setObject(4, sourceId);
            try (var r = q.executeQuery()) {
                if (!r.next())
                    throw error("ASSET_QUOTA_UNAVAILABLE", "资产购置来源会计确认配额不存在");
                status = r.getString(1);
                state = r.getString(2);
                quotaAmount = r.getBigDecimal(3);
                originalId = (UUID) r.getObject(4);
            }
        }
        if (!"APPROVED".equals(status)
                || !"AVAILABLE".equals(state)
                || quotaAmount.compareTo(amount) != 0)
            throw error("ASSET_QUOTA_UNAVAILABLE", "资产购置来源配额尚未独立批准、已被占用或金额不符");
        assertNotConsumedByAp(c, ctx.scope().tenant(), ctx.scope().book(), ctx.scope().year(), originalId);
        try (var u =
                c.prepareStatement(
                        "UPDATE plugin_finance_asset_source_quota SET"
                            + " state='RESERVED',active_plan_id=?,version=version+1 WHERE"
                            + " tenant_id=? AND book_id=? AND year_id=? AND id=? AND"
                            + " state='AVAILABLE'")) {
            u.setObject(1, plan);
            u.setObject(2, ctx.scope().tenant());
            u.setObject(3, ctx.scope().book());
            u.setObject(4, ctx.scope().year());
            u.setObject(5, sourceId);
            if (u.executeUpdate() != 1)
                throw error("ASSET_QUOTA_STALE", "资产购置来源配额并发状态冲突");
        }
    }

    @Override
    public void consume(Connection c, Context ctx, UUID sourceId, UUID plan, BigDecimal amount)
            throws Exception {
        try (var u =
                c.prepareStatement(
                        "UPDATE plugin_finance_asset_source_quota SET"
                            + " state='CONSUMED',version=version+1 WHERE tenant_id=? AND"
                            + " book_id=? AND year_id=? AND id=? AND status='APPROVED' AND"
                            + " state='RESERVED' AND active_plan_id=? AND amount=?")) {
            u.setObject(1, ctx.scope().tenant());
            u.setObject(2, ctx.scope().book());
            u.setObject(3, ctx.scope().year());
            u.setObject(4, sourceId);
            u.setObject(5, plan);
            u.setBigDecimal(6, amount);
            if (u.executeUpdate() != 1)
                throw error("ASSET_QUOTA_STALE", "资产购置来源配额预占状态失效或金额不一致");
        }
    }

    @Override
    public void release(Connection c, Context ctx, UUID sourceId, UUID plan) throws Exception {
        try (var u =
                c.prepareStatement(
                        "UPDATE plugin_finance_asset_source_quota SET"
                            + " state='AVAILABLE',active_plan_id=NULL,version=version+1 WHERE"
                            + " tenant_id=? AND book_id=? AND year_id=? AND id=? AND"
                            + " state='RESERVED' AND active_plan_id=?")) {
            u.setObject(1, ctx.scope().tenant());
            u.setObject(2, ctx.scope().book());
            u.setObject(3, ctx.scope().year());
            u.setObject(4, sourceId);
            u.setObject(5, plan);
            if (u.executeUpdate() != 1)
                throw error("ASSET_QUOTA_STALE", "资产购置来源配额释放失败");
        }
    }

    @Override
    public void reverse(Connection c, Context ctx, UUID originalPlan, UUID reversalPlan)
            throws Exception {
        try (var u =
                c.prepareStatement(
                        "UPDATE plugin_finance_asset_source_quota SET"
                            + " state='AVAILABLE',active_plan_id=NULL,version=version+1 WHERE"
                            + " tenant_id=? AND book_id=? AND year_id=? AND state='CONSUMED' AND"
                            + " active_plan_id=?")) {
            u.setObject(1, ctx.scope().tenant());
            u.setObject(2, ctx.scope().book());
            u.setObject(3, ctx.scope().year());
            u.setObject(4, originalPlan);
            if (u.executeUpdate() != 1)
                throw error("ASSET_QUOTA_STALE", "资产购置来源配额冲销恢复失败");
        }
    }

    @Override
    public PostReceipt post(Connection c, Context ctx, StoredPlan approved) throws Exception {
        Plan plan = approved.plan();
        Scope s = plan.scope();
        String nativeType =
                switch (plan.kind()) {
                    case "ACQUIRE" -> "ASSET_ACQUIRE";
                    case "DEPRECIATE" -> "ASSET_DEPRECIATE";
                    case "DISPOSE" -> "ASSET_DISPOSE";
                    case "REVERSE" -> "ASSET_REVERSE";
                    case "CORRECT_DEPRECIATION" -> "ASSET_CORRECT_DEPRECIATION";
                    default -> throw error("ASSET_UNSUPPORTED_POSTING_KIND", "不支持记账的资产计划类型");
                };
        UUID reversesJournalId = null;
        if ("REVERSE".equals(plan.kind()) && plan.reverses() != null) {
            try (var q =
                    c.prepareStatement(
                            "SELECT journal_id FROM plugin_finance_asset_plan WHERE tenant_id=?"
                                    + " AND book_id=? AND id=?")) {
                q.setObject(1, s.tenant());
                q.setObject(2, s.book());
                q.setObject(3, plan.reverses());
                try (var r = q.executeQuery()) {
                    if (r.next()) reversesJournalId = (UUID) r.getObject(1);
                }
            }
        }
        UUID journalId = UUID.randomUUID();
        String summary = "ASSET:" + plan.businessKey();
        try (var q =
                c.prepareStatement(
                        "INSERT INTO"
                            + " plugin_finance_journal(tenant_id,book_id,year_id,id,period_id,date,summary,type,status,version,created_by,amount_format_version)"
                            + " VALUES(?,?,?,?,?,?,?,?,'DRAFT',1,?,'V4_8')")) {
            q.setObject(1, s.tenant());
            q.setObject(2, s.book());
            q.setObject(3, s.year());
            q.setObject(4, journalId);
            q.setObject(5, s.period());
            q.setObject(6, s.date());
            q.setString(7, summary);
            q.setString(8, nativeType);
            q.setObject(9, plan.maker().account());
            q.executeUpdate();
        }
        int lineNo = 1;
        for (Line line : plan.lines()) {
            try (var q =
                    c.prepareStatement(
                            "INSERT INTO"
                                + " plugin_finance_line(tenant_id,book_id,year_id,journal_id,line_no,account_id,debit,credit,memo)"
                                + " VALUES(?,?,?,?,?,?,?,?,?)")) {
                q.setObject(1, s.tenant());
                q.setObject(2, s.book());
                q.setObject(3, s.year());
                q.setObject(4, journalId);
                q.setInt(5, lineNo++);
                q.setObject(6, line.account());
                q.setBigDecimal(7, line.debit());
                q.setBigDecimal(8, line.credit());
                q.setString(9, plan.kind());
                q.executeUpdate();
            }
        }
        try (var q =
                c.prepareStatement(
                        "UPDATE plugin_finance_journal SET"
                            + " status='APPROVED',version=2,approved_by=?,approved_version=2"
                            + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?")) {
            q.setObject(1, approved.approver().account());
            q.setObject(2, s.tenant());
            q.setObject(3, s.book());
            q.setObject(4, s.year());
            q.setObject(5, journalId);
            q.executeUpdate();
        }
        long seq;
        try (var q =
                c.prepareStatement(
                        "UPDATE plugin_finance_year_guard SET posting_seq=posting_seq+1 WHERE"
                            + " tenant_id=? AND book_id=? AND year_id=? RETURNING posting_seq")) {
            q.setObject(1, s.tenant());
            q.setObject(2, s.book());
            q.setObject(3, s.year());
            try (var r = q.executeQuery()) {
                r.next();
                seq = r.getLong(1);
            }
        }
        long journalNo;
        try (var q =
                c.prepareStatement(
                        "SELECT COALESCE(MAX(number),0)+1 FROM plugin_finance_journal WHERE"
                                + " tenant_id=? AND book_id=? AND year_id=?")) {
            q.setObject(1, s.tenant());
            q.setObject(2, s.book());
            q.setObject(3, s.year());
            try (var r = q.executeQuery()) {
                r.next();
                journalNo = r.getLong(1);
            }
        }
        try (var q =
                c.prepareStatement(
                        "INSERT INTO"
                            + " plugin_finance_posting(tenant_id,book_id,year_id,journal_id,journal_version,posting_seq,digest,posted_by)"
                            + " VALUES(?,?,?,?,2,?,?,?)")) {
            q.setObject(1, s.tenant());
            q.setObject(2, s.book());
            q.setObject(3, s.year());
            q.setObject(4, journalId);
            q.setLong(5, seq);
            q.setString(6, approved.digest());
            q.setObject(7, ctx.actor().account());
            q.executeUpdate();
        }
        try (var q =
                c.prepareStatement(
                        "UPDATE plugin_finance_journal SET"
                            + " status='POSTED',version=3,number=?,reversal_of=? WHERE"
                            + " tenant_id=? AND book_id=? AND year_id=? AND id=?")) {
            q.setLong(1, journalNo);
            q.setObject(2, reversesJournalId);
            q.setObject(3, s.tenant());
            q.setObject(4, s.book());
            q.setObject(5, s.year());
            q.setObject(6, journalId);
            q.executeUpdate();
        }
        var outboxPayload =
                map(
                        "id",
                        journalId.toString(),
                        "journalId",
                        journalId.toString(),
                        "status",
                        "POSTED",
                        "date",
                        s.date().toString(),
                        "summary",
                        summary,
                        "number",
                        journalNo,
                        "sourceVersion",
                        3L,
                        "postingSeq",
                        seq);
        String encodedOutbox = finance.encode(finance.canonical(outboxPayload));
        try (var q =
                c.prepareStatement(
                        "INSERT INTO"
                            + " plugin_finance_outbox(tenant_id,book_id,year_id,id,event_type,aggregate_id,aggregate_version,payload,payload_digest,created_by)"
                            + " VALUES(?,?,?,?,?,?,3,?::jsonb,?,?)")) {
            q.setObject(1, s.tenant());
            q.setObject(2, s.book());
            q.setObject(3, s.year());
            q.setObject(4, UUID.randomUUID());
            q.setString(5, "JOURNAL_POSTED");
            q.setObject(6, journalId);
            q.setString(7, encodedOutbox);
            q.setString(8, FinanceService.hash(encodedOutbox));
            q.setObject(9, ctx.actor().account());
            q.executeUpdate();
        }
        return new PostReceipt(journalId);
    }

    private void assertNotConsumedByAp(
            Connection c, UUID tenantId, UUID bookId, UUID yearId, UUID originalId)
            throws Exception {
        try (var q =
                c.prepareStatement(
                        "SELECT 1 FROM plugin_finance_ap_basis b JOIN"
                            + " plugin_finance_evidence_document d ON"
                            + " (d.tenant_id,d.book_id,d.year_id,d.id)=(b.tenant_id,b.book_id,b.year_id,b.document_id)"
                            + " WHERE (d.tenant_id,d.book_id,d.year_id,d.source_original_id)=(?,?,?,?)"
                            + " AND b.status IN('PENDING','APPROVED') LIMIT 1")) {
            q.setObject(1, tenantId);
            q.setObject(2, bookId);
            q.setObject(3, yearId);
            q.setObject(4, originalId);
            try (var r = q.executeQuery()) {
                if (r.next())
                    throw error("ASSET_QUOTA_AP_CONFLICT", "该原件已被应付会计确认依据占用，不得重复用于资产购置");
            }
        }
    }

    private Map<String, Object> quotaView(Map<String, Object> r) {
        return map(
                "id",
                r.get("id").toString(),
                "originalId",
                r.get("original_id").toString(),
                "status",
                r.get("status"),
                "state",
                r.get("state"),
                "version",
                r.get("version").toString(),
                "amount",
                stored(new BigDecimal(r.get("amount").toString())),
                "activePlanId",
                r.get("active_plan_id") == null ? null : r.get("active_plan_id").toString());
    }

    public Object listQuotas(String principal, UUID book, UUID year) {
        return transaction(
                false,
                c -> {
                    var b = c.scope(principal, book, year, null);
                    var rows =
                            c.list(
                                    "SELECT * FROM plugin_finance_asset_source_quota WHERE"
                                            + " tenant_id=? AND book_id=? AND year_id=? ORDER BY"
                                            + " created_at DESC,id DESC LIMIT 100",
                                    b.get("tenant_id"),
                                    book,
                                    year);
                    return map("items", rows.stream().map(this::quotaView).toList());
                });
    }

    public Object createQuota(
            String principal,
            UUID book,
            UUID year,
            Map<String, Object> raw,
            HttpServletRequest request) {
        if (raw == null || !raw.keySet().equals(CREATE_KEYS))
            throw FinanceException.invalid("资产购置来源配额请求字段无效");
        String reqKey;
        UUID originalId;
        BigDecimal amount;
        String reason;
        try {
            reqKey = text(raw.get("idempotencyKey"), 128);
            originalId = uuid(raw.get("originalId"));
            amount = money(raw.get("amount"), false).setScale(2, RoundingMode.UNNECESSARY);
            reason = text(raw.get("reason"), 1000);
        } catch (Rejected ex) {
            throw error(ex.code, "资产购置来源配额参数或金额格式无效");
        }
        return transaction(
                true,
                c -> {
                    var b = c.locked(principal, book, year, "asset-quota-create", reqKey);
                    if (!allowed(b, "create"))
                        throw error("SCOPE_DENIED", "当前岗位无权创建资产购置来源配额");
                    UUID tenantId = id(b.get("tenant_id"));
                    var who = gate.current(id(b.get("actor_id")), tenantId, false, request);
                    var payload = new LinkedHashMap<>(raw);
                    payload.remove("idempotencyKey");
                    String payloadHash = hashValue(payload);
                    String route = "asset-quota-create/" + book + "/" + year;
                    var cached =
                            c.list(
                                    "SELECT * FROM plugin_finance_v4_governance_request WHERE"
                                            + " actor_id=? AND request_key=?",
                                    b.get("actor_id"),
                                    reqKey);
                    if (!cached.isEmpty()) {
                        var old = cached.getFirst();
                        if (!route.equals(old.get("route"))
                                || !payloadHash.equals(old.get("payload_hash")))
                            throw error("IDEMPOTENCY_CONFLICT", "同幂等键正文或路由不一致");
                        return json(old.get("response"));
                    }
                    var orig =
                            c.list(
                                    "SELECT id FROM plugin_finance_original WHERE tenant_id=? AND"
                                            + " book_id=? AND year_id=? AND id=? AND"
                                            + " sha256=encode(sha256(content),'hex')",
                                    tenantId,
                                    book,
                                    year,
                                    originalId);
                    if (orig.isEmpty())
                        throw error("ASSET_ORIGINAL_INVALID", "资产购置来源原件不存在或摘要不符");
                    var apUsed =
                            c.list(
                                    "SELECT 1 FROM plugin_finance_ap_basis ab JOIN"
                                        + " plugin_finance_evidence_document d ON"
                                        + " (d.tenant_id,d.book_id,d.year_id,d.id)=(ab.tenant_id,ab.book_id,ab.year_id,ab.document_id)"
                                        + " WHERE (d.tenant_id,d.book_id,d.year_id,d.source_original_id)=(?,?,?,?)"
                                        + " AND ab.status IN('PENDING','APPROVED') LIMIT 1",
                                    tenantId,
                                    book,
                                    year,
                                    originalId);
                    if (!apUsed.isEmpty())
                        throw error("ASSET_QUOTA_AP_CONFLICT", "该原件已被应付会计确认依据占用");
                    var dup =
                            c.list(
                                    "SELECT id FROM plugin_finance_asset_source_quota WHERE"
                                            + " tenant_id=? AND book_id=? AND year_id=? AND"
                                            + " original_id=?",
                                    tenantId,
                                    book,
                                    year,
                                    originalId);
                    if (!dup.isEmpty())
                        throw error("ASSET_QUOTA_DUPLICATE", "该原件已登记资产购置配额");

                    UUID quotaId = UUID.randomUUID();
                    c.update(
                            "INSERT INTO"
                                + " plugin_finance_asset_source_quota(tenant_id,book_id,year_id,id,original_id,amount,prepared_by,reason)"
                                + " VALUES(?,?,?,?,?,?,?::jsonb,?)",
                            tenantId,
                            book,
                            year,
                            quotaId,
                            originalId,
                            amount,
                            finance.encode(who),
                            reason);
                    var response =
                            quotaView(
                                    c.one(
                                            "SELECT * FROM plugin_finance_asset_source_quota WHERE"
                                                    + " tenant_id=? AND book_id=? AND year_id=?"
                                                    + " AND id=?",
                                            tenantId,
                                            book,
                                            year,
                                            quotaId));
                    c.update(
                            "INSERT INTO"
                                + " plugin_finance_v4_governance_request(actor_id,request_key,tenant_id,route,payload_hash,response)"
                                + " VALUES(?,?,?,?,?,?::jsonb)",
                            b.get("actor_id"),
                            reqKey,
                            tenantId,
                            route,
                            payloadHash,
                            finance.encode(response));
                    return response;
                });
    }

    public Object approveQuota(
            String principal,
            UUID book,
            UUID year,
            UUID quotaId,
            Map<String, Object> raw,
            HttpServletRequest request) {
        if (raw == null || !raw.keySet().equals(APPROVE_KEYS))
            throw FinanceException.invalid("资产购置来源配额审签请求字段无效");
        String reqKey;
        long expectedVersion;
        String reason;
        try {
            reqKey = text(raw.get("idempotencyKey"), 128);
            expectedVersion = version(raw.get("expectedVersion"));
            reason = text(raw.get("reason"), 1000);
        } catch (Rejected ex) {
            throw error(ex.code, "资产购置来源配额审签参数无效");
        }
        return transaction(
                true,
                c -> {
                    var b = c.locked(principal, book, year, "approve", reqKey);
                    if (!allowed(b, "approve"))
                        throw error("SCOPE_DENIED", "当前岗位无权审签资产购置来源配额");
                    UUID tenantId = id(b.get("tenant_id"));
                    var who = gate.current(id(b.get("actor_id")), tenantId, true, request);
                    var payload = new LinkedHashMap<>(raw);
                    payload.remove("idempotencyKey");
                    String payloadHash = hashValue(payload);
                    String route = "asset-quota-approve/" + book + "/" + year + "/" + quotaId;
                    var cached =
                            c.list(
                                    "SELECT * FROM plugin_finance_v4_governance_request WHERE"
                                            + " actor_id=? AND request_key=?",
                                    b.get("actor_id"),
                                    reqKey);
                    if (!cached.isEmpty()) {
                        var old = cached.getFirst();
                        if (!route.equals(old.get("route"))
                                || !payloadHash.equals(old.get("payload_hash")))
                            throw error("IDEMPOTENCY_CONFLICT", "同幂等键正文或路由不一致");
                        return json(old.get("response"));
                    }
                    var row =
                            c.one(
                                    "SELECT * FROM plugin_finance_asset_source_quota WHERE"
                                            + " tenant_id=? AND book_id=? AND year_id=? AND id=?"
                                            + " FOR UPDATE",
                                    tenantId,
                                    book,
                                    year,
                                    quotaId);
                    if (((Number) row.get("version")).longValue() != expectedVersion)
                        throw error("VERSION_CONFLICT", "资产购置来源配额版本不匹配");
                    if (!"PENDING".equals(row.get("status")))
                        throw error("ASSET_QUOTA_STATE", "资产购置来源配额已处于终态");
                    independent(who, json(row.get("prepared_by")));
                    c.update(
                            "UPDATE plugin_finance_asset_source_quota SET"
                                + " status='APPROVED',version=version+1,approved_by=?::jsonb,decision_reason=?,decided_at=CURRENT_TIMESTAMP"
                                + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                            finance.encode(who),
                            reason,
                            tenantId,
                            book,
                            year,
                            quotaId);
                    var response =
                            quotaView(
                                    c.one(
                                            "SELECT * FROM plugin_finance_asset_source_quota WHERE"
                                                    + " tenant_id=? AND book_id=? AND year_id=?"
                                                    + " AND id=?",
                                            tenantId,
                                            book,
                                            year,
                                            quotaId));
                    c.update(
                            "INSERT INTO"
                                + " plugin_finance_v4_governance_request(actor_id,request_key,tenant_id,route,payload_hash,response)"
                                + " VALUES(?,?,?,?,?,?::jsonb)",
                            b.get("actor_id"),
                            reqKey,
                            tenantId,
                            route,
                            payloadHash,
                            finance.encode(response));
                    return response;
                });
    }
}

package com.lumira.finance;

import com.lumira.platform.common.AuthorizationContextProjectionPort;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Bounded CNY payable settlement, payment instruction & manual bank slip verification (AP02 / EV03 / TR01 / TR02).
 * External bank payment channels remain NOT_VERIFIED; posts native AP_PAYMENT / AP_PAYMENT_REVERSAL journals.
 */
@Service
public final class FinancePayableSettlements extends FinanceProfitClosingAccess {

    public record CreateSettlementBasisInput(
            UUID originalId,
            UUID supplierId,
            String payeeAccountLabel,
            String capacity,
            String reason,
            String idempotencyKey) {}

    public record ApproveSettlementBasisInput(
            long expectedVersion, String reason, String idempotencyKey) {}

    public record CreateSettlementOrderInput(
            String kind,
            UUID openItemId,
            UUID settlementBasisId,
            UUID cashAccountId,
            UUID bankSlipOriginalId,
            String paymentInstructionRef,
            LocalDate accountingDate,
            String amount,
            String summary,
            UUID reversesId,
            String reason,
            String idempotencyKey) {}

    public record TransitionSettlementOrderInput(
            long expectedVersion, String reason, String idempotencyKey) {}

    public FinancePayableSettlements(
            FinanceStore db,
            FinanceService finance,
            FinanceJournalPersonGate gate,
            PlatformTransactionManager tx,
            AuthorizationContextProjectionPort authorization) {
        super(db, finance, gate, tx, authorization);
    }

    private static void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,128}"))
            throw FinanceException.invalid("幂等请求键格式无效");
    }

    private static BigDecimal parseMoney(String raw, String label) {
        if (raw == null || !raw.matches("(0|[1-9]\\d{0,17})(\\.\\d{1,2})?"))
            throw FinanceException.invalid(label + "金额格式无效");
        BigDecimal v = new BigDecimal(raw).setScale(2, RoundingMode.UNNECESSARY);
        if (v.signum() <= 0) throw FinanceException.invalid(label + "金额必须大于零");
        return v;
    }

    private static String fmt2(Object v) {
        return new BigDecimal(v.toString()).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private Map<String, Object> verifyActiveSupplier(
            Call c, String principal, Map<String, Object> b, UUID supplierId) {
        UUID tenant = id(b.get("tenant_id"));
        UUID legalEntity = id(b.get("legal_entity_id"));
        var rows =
                c.list(
                        "SELECT p.home_book_id,p.approved_revision_id,p.pending_revision_id,"
                            + "r.content,r.content_digest,r.desired_status,r.status "
                            + "FROM plugin_finance_counterparty_registry p "
                            + "JOIN plugin_finance_counterparty_revision r "
                            + "ON (r.tenant_id,r.legal_entity_id,r.counterparty_id,r.id)="
                            + "(p.tenant_id,p.legal_entity_id,p.id,p.approved_revision_id) "
                            + "WHERE p.tenant_id=? AND p.legal_entity_id=? AND p.id=?",
                        tenant,
                        legalEntity,
                        supplierId);
        if (rows.isEmpty()) throw error("AP_SUPPLIER_STALE", "供应商不存在或尚未完成独立批准");
        var r = rows.getFirst();
        c.timeout();
        finance.originalBookScope(principal, id(r.get("home_book_id")), "read");
        c.check();
        var content = json(r.get("content"));
        if (r.get("approved_revision_id") == null
                || r.get("pending_revision_id") != null
                || !"ACTIVE".equals(r.get("desired_status"))
                || !"APPROVED".equals(r.get("status"))
                || !(content.get("roles") instanceof List<?> roles)
                || !roles.contains("SUPPLIER"))
            throw error("AP_SUPPLIER_STALE", "供应商当前修订未激活、存在待审变更或不具备供应商角色");
        return map(
                "supplierId",
                supplierId.toString(),
                "supplierRevisionId",
                r.get("approved_revision_id").toString(),
                "contentDigest",
                r.get("content_digest"));
    }

    private String verifyOriginalSha(Call c, UUID tenant, UUID book, UUID year, UUID originalId) {
        var rows =
                c.list(
                        "SELECT sha256 FROM plugin_finance_original "
                            + "WHERE tenant_id=? AND book_id=? AND year_id=? AND id=? "
                            + "AND sha256=encode(sha256(content),'hex')",
                        tenant,
                        book,
                        year,
                        originalId);
        if (rows.isEmpty())
            throw error("AP_SETTLEMENT_ORIGINAL_INVALID", "结算依据或银行回单原件不存在或字节哈希不匹配");
        return rows.getFirst().get("sha256").toString();
    }

    @SuppressWarnings("unchecked")
    private UUID extractPayableAccountId(Map<String, Object> runRow) {
        var content = json(runRow.get("content"));
        var lines = (List<Map<String, Object>>) content.get("generatedLines");
        if (lines != null) {
            for (var line : lines) {
                if (new BigDecimal(line.get("credit").toString()).signum() > 0) {
                    var acc = (Map<String, Object>) line.get("account");
                    return id(acc.get("id"));
                }
            }
        }
        throw error("AP_OPEN_ITEM_UNAVAILABLE", "无法解析应付未结项对应的应付负债科目");
    }

    private static String agingBucket(
            LocalDate dueDate,
            LocalDate asOf,
            BigDecimal available,
            boolean reversed,
            BigDecimal settled,
            BigDecimal total) {
        if (reversed) return "REVERSED";
        if (settled.compareTo(total) == 0) return "SETTLED";
        if (available.signum() <= 0) return "RESERVED";
        if (dueDate == null || !asOf.isAfter(dueDate)) return "CURRENT";
        long days = ChronoUnit.DAYS.between(dueDate, asOf);
        if (days <= 30) return "OVERDUE_1_30";
        if (days <= 90) return "OVERDUE_31_90";
        return "OVERDUE_OVER_90";
    }

    public Object overview(String principal, UUID book, UUID year, LocalDate asOf) {
        LocalDate effectiveAsOf = asOf == null ? LocalDate.now() : asOf;
        return transaction(
                false,
                c -> {
                    var b = c.scope(principal, book, year, null);
                    UUID tenant = id(b.get("tenant_id"));
                    var rawItems =
                            c.list(
                                    "SELECT i.*, r.content FROM plugin_finance_ap_open_item i "
                                        + "JOIN plugin_finance_ap_run r ON (r.tenant_id,r.book_id,r.year_id,r.id)=(i.tenant_id,i.book_id,i.year_id,i.run_id) "
                                        + "WHERE i.tenant_id=? AND i.book_id=? AND i.year_id=? ORDER BY i.created_at DESC, i.id DESC",
                                    tenant,
                                    book,
                                    year);
                    List<Map<String, Object>> openItems = new ArrayList<>();
                    for (var r : rawItems) {
                        UUID itemId = id(r.get("id"));
                        BigDecimal total =
                                new BigDecimal(r.get("amount").toString())
                                        .setScale(2, RoundingMode.UNNECESSARY);
                        boolean reversed = r.get("reverse_post_id") != null;
                        var usage =
                                c.one(
                                        "SELECT COALESCE(SUM(amount) FILTER(WHERE state='POSTED' AND reversed_by_id IS NULL),0) AS settled, "
                                            + "COALESCE(SUM(amount) FILTER(WHERE state IN('DRAFT','SUBMITTED','APPROVED')),0) AS reserved "
                                            + "FROM plugin_finance_ap_settlement_order "
                                            + "WHERE tenant_id=? AND book_id=? AND year_id=? AND open_item_id=? AND kind='PAYMENT'",
                                        tenant,
                                        book,
                                        year,
                                        itemId);
                        BigDecimal settled =
                                new BigDecimal(usage.get("settled").toString())
                                        .setScale(2, RoundingMode.UNNECESSARY);
                        BigDecimal reserved =
                                new BigDecimal(usage.get("reserved").toString())
                                        .setScale(2, RoundingMode.UNNECESSARY);
                        BigDecimal available =
                                reversed
                                        ? BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY)
                                        : total.subtract(settled)
                                                .subtract(reserved)
                                                .max(BigDecimal.ZERO)
                                                .setScale(2, RoundingMode.UNNECESSARY);
                        LocalDate dueDate =
                                r.get("due_date") == null
                                        ? null
                                        : LocalDate.parse(r.get("due_date").toString());
                        UUID payableAccId = extractPayableAccountId(r);
                        openItems.add(
                                map(
                                        "id",
                                        itemId.toString(),
                                        "runId",
                                        r.get("run_id").toString(),
                                        "supplierId",
                                        r.get("supplier_id").toString(),
                                        "supplierRevisionId",
                                        r.get("supplier_revision_id").toString(),
                                        "payableAccountId",
                                        payableAccId.toString(),
                                        "amount",
                                        total.toPlainString(),
                                        "settledAmount",
                                        settled.toPlainString(),
                                        "reservedAmount",
                                        reserved.toPlainString(),
                                        "availableAmount",
                                        available.toPlainString(),
                                        "dueDate",
                                        dueDate == null ? null : dueDate.toString(),
                                        "reversed",
                                        reversed,
                                        "aging",
                                        agingBucket(
                                                dueDate,
                                                effectiveAsOf,
                                                available,
                                                reversed,
                                                settled,
                                                total)));
                    }

                    var rawBases =
                            c.list(
                                    "SELECT * FROM plugin_finance_ap_settlement_basis "
                                        + "WHERE tenant_id=? AND book_id=? AND year_id=? ORDER BY created_at DESC, id DESC",
                                    tenant,
                                    book,
                                    year);
                    List<Map<String, Object>> bases = new ArrayList<>();
                    for (var bs : rawBases) {
                        bases.add(basisView(c, tenant, book, year, bs));
                    }

                    var rawOrders =
                            c.list(
                                    "SELECT * FROM plugin_finance_ap_settlement_order "
                                        + "WHERE tenant_id=? AND book_id=? AND year_id=? ORDER BY created_at DESC, id DESC",
                                    tenant,
                                    book,
                                    year);
                    List<Map<String, Object>> orders = new ArrayList<>();
                    for (var ord : rawOrders) {
                        orders.add(orderView(ord));
                    }

                    return map(
                            "asOf",
                            effectiveAsOf.toString(),
                            "openItems",
                            openItems,
                            "bases",
                            bases,
                            "orders",
                            orders,
                            "bankChannel",
                            "NOT_VERIFIED",
                            "verificationMode",
                            "INTERNAL_MANUAL_PAYMENT_SLIP");
                });
    }

    public Object createBasis(
            String principal,
            UUID book,
            UUID year,
            CreateSettlementBasisInput in,
            HttpServletRequest request) {
        requireKey(in.idempotencyKey());
        if (in.originalId() == null
                || in.supplierId() == null
                || in.payeeAccountLabel() == null
                || in.payeeAccountLabel().isBlank()
                || in.payeeAccountLabel().length() > 128
                || in.reason() == null
                || in.reason().isBlank()
                || in.reason().length() > 1000)
            throw FinanceException.invalid("结算依据配额申请参数无效");
        BigDecimal capacity = parseMoney(in.capacity(), "结算依据配额");
        return transaction(
                true,
                c -> {
                    var b = c.scope(principal, book, year, "create");
                    if (!allowed(b, "create") && !allowed(b, "manage"))
                        throw error("SCOPE_DENIED", "需要制单或管理权限登记结算依据配额");
                    UUID tenant = id(b.get("tenant_id"));
                    UUID legalEntity = id(b.get("legal_entity_id"));
                    UUID actorId = id(b.get("actor_id"));
                    var who = gate.current(actorId, tenant, true, request);
                    String route = "ap-settlement-basis-create/" + book + "/" + year;
                    String pHash =
                            FinanceService.hash(
                                    in.originalId()
                                            + ":"
                                            + in.supplierId()
                                            + ":"
                                            + in.payeeAccountLabel()
                                            + ":"
                                            + capacity.toPlainString()
                                            + ":"
                                            + in.reason());
                    var cached = replay(c, actorId, in.idempotencyKey(), route, pHash);
                    if (cached != null) return cached;

                    var sup = verifyActiveSupplier(c, principal, b, in.supplierId());
                    UUID supRevId = id(sup.get("supplierRevisionId"));
                    String sha = verifyOriginalSha(c, tenant, book, year, in.originalId());

                    var dup =
                            c.list(
                                    "SELECT id FROM plugin_finance_ap_settlement_basis WHERE tenant_id=? AND book_id=? AND year_id=? AND original_id=? AND purpose='SETTLEMENT_EVIDENCE'",
                                    tenant,
                                    book,
                                    year,
                                    in.originalId());
                    if (!dup.isEmpty())
                        throw error("AP_SETTLEMENT_BASIS_EXISTS", "该原件已登记应付结算依据配额");

                    UUID basisId = UUID.randomUUID();
                    c.update(
                            "INSERT INTO plugin_finance_ap_settlement_basis("
                                + "tenant_id,legal_entity_id,book_id,year_id,id,original_id,original_sha256,"
                                + "purpose,supplier_id,supplier_revision_id,payee_account_label,bank_channel,"
                                + "capacity,status,version,prepared_by,reason) "
                                + "VALUES(?,?,?,?,?,?,?,'SETTLEMENT_EVIDENCE',?,?,?,'NOT_VERIFIED',?,'PENDING',1,?::jsonb,?)",
                            tenant,
                            legalEntity,
                            book,
                            year,
                            basisId,
                            in.originalId(),
                            sha,
                            in.supplierId(),
                            supRevId,
                            in.payeeAccountLabel().trim(),
                            capacity,
                            finance.encode(who),
                            in.reason().trim());
                    var res =
                            basisView(
                                    c,
                                    tenant,
                                    book,
                                    year,
                                    c.one(
                                            "SELECT * FROM plugin_finance_ap_settlement_basis WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                            tenant,
                                            book,
                                            year,
                                            basisId));
                    saveReplay(c, tenant, actorId, in.idempotencyKey(), route, pHash, res);
                    return res;
                });
    }

    public Object approveBasis(
            String principal,
            UUID book,
            UUID year,
            UUID basisId,
            ApproveSettlementBasisInput in,
            HttpServletRequest request) {
        requireKey(in.idempotencyKey());
        if (in.reason() == null || in.reason().isBlank() || in.reason().length() > 1000)
            throw FinanceException.invalid("结算依据审签原因无效");
        return transaction(
                true,
                c -> {
                    var b = c.scope(principal, book, year, "approve");
                    if (!allowed(b, "approve"))
                        throw error("SCOPE_DENIED", "需要独立审核权限批准结算依据配额");
                    UUID tenant = id(b.get("tenant_id"));
                    UUID actorId = id(b.get("actor_id"));
                    var who = gate.current(actorId, tenant, true, request);
                    String route = "ap-settlement-basis-approve/" + book + "/" + year + "/" + basisId;
                    String pHash =
                            FinanceService.hash(in.expectedVersion() + ":" + in.reason().trim());
                    var cached = replay(c, actorId, in.idempotencyKey(), route, pHash);
                    if (cached != null) return cached;

                    var row =
                            c.one(
                                    "SELECT * FROM plugin_finance_ap_settlement_basis WHERE tenant_id=? AND book_id=? AND year_id=? AND id=? FOR UPDATE",
                                    tenant,
                                    book,
                                    year,
                                    basisId);
                    if (((Number) row.get("version")).longValue() != in.expectedVersion())
                        throw error("VERSION_CONFLICT", "结算依据配额版本冲突");
                    if (!"PENDING".equals(row.get("status")))
                        throw error("AP_SETTLEMENT_STATE_CONFLICT", "结算依据配额非待审状态");
                    independent(who, json(row.get("prepared_by")));
                    verifyActiveSupplier(c, principal, b, id(row.get("supplier_id")));

                    c.update(
                            "UPDATE plugin_finance_ap_settlement_basis SET status='APPROVED',version=2,"
                                + "approved_by=?::jsonb,decision_reason=?,decided_at=CURRENT_TIMESTAMP "
                                + "WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                            finance.encode(who),
                            in.reason().trim(),
                            tenant,
                            book,
                            year,
                            basisId);
                    var res =
                            basisView(
                                    c,
                                    tenant,
                                    book,
                                    year,
                                    c.one(
                                            "SELECT * FROM plugin_finance_ap_settlement_basis WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                            tenant,
                                            book,
                                            year,
                                            basisId));
                    saveReplay(c, tenant, actorId, in.idempotencyKey(), route, pHash, res);
                    return res;
                });
    }

    public Object createOrder(
            String principal,
            UUID book,
            UUID year,
            CreateSettlementOrderInput in,
            HttpServletRequest request) {
        requireKey(in.idempotencyKey());
        if (in.kind() == null
                || !Set.of("PAYMENT", "REVERSAL").contains(in.kind())
                || in.openItemId() == null
                || in.settlementBasisId() == null
                || in.cashAccountId() == null
                || in.bankSlipOriginalId() == null
                || in.paymentInstructionRef() == null
                || in.paymentInstructionRef().isBlank()
                || in.paymentInstructionRef().length() > 128
                || in.accountingDate() == null
                || in.summary() == null
                || in.summary().isBlank()
                || in.summary().length() > 500
                || in.reason() == null
                || in.reason().isBlank()
                || in.reason().length() > 1000
                || ("REVERSAL".equals(in.kind()) != (in.reversesId() != null)))
            throw FinanceException.invalid("应付结算单创建参数无效");
        BigDecimal amount = parseMoney(in.amount(), "应付结算");
        return transaction(
                true,
                c -> {
                    var b = c.scope(principal, book, year, "create");
                    if (!allowed(b, "create"))
                        throw error("SCOPE_DENIED", "需要制单权限创建应付结算单");
                    UUID tenant = id(b.get("tenant_id"));
                    UUID legalEntity = id(b.get("legal_entity_id"));
                    UUID actorId = id(b.get("actor_id"));
                    var who = gate.current(actorId, tenant, true, request);
                    String route = "ap-settlement-order-create/" + book + "/" + year;
                    String pHash =
                            FinanceService.hash(
                                    in.kind()
                                            + ":"
                                            + in.openItemId()
                                            + ":"
                                            + in.settlementBasisId()
                                            + ":"
                                            + in.cashAccountId()
                                            + ":"
                                            + in.bankSlipOriginalId()
                                            + ":"
                                            + in.paymentInstructionRef().trim()
                                            + ":"
                                            + in.accountingDate()
                                            + ":"
                                            + amount.toPlainString()
                                            + ":"
                                            + in.reversesId());
                    var cached = replay(c, actorId, in.idempotencyKey(), route, pHash);
                    if (cached != null) return cached;

                    checkOpenPeriod(c, tenant, book, year, in.accountingDate());

                    var itemRows =
                            c.list(
                                    "SELECT * FROM plugin_finance_ap_open_item WHERE tenant_id=? AND book_id=? AND year_id=? AND id=? FOR UPDATE",
                                    tenant,
                                    book,
                                    year,
                                    in.openItemId());
                    if (itemRows.isEmpty())
                        throw error("AP_OPEN_ITEM_UNAVAILABLE", "应付未结项不存在");
                    var item = itemRows.getFirst();
                    if (item.get("reverse_post_id") != null
                            || ((Number) item.get("version")).longValue() != 1L)
                        throw error("AP_OPEN_ITEM_UNAVAILABLE", "应付未结项已被冲销，不得发起结算");
                    UUID runId = id(item.get("run_id"));
                    var runRow =
                            c.one(
                                    "SELECT * FROM plugin_finance_ap_run WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                    tenant,
                                    book,
                                    year,
                                    runId);
                    UUID payableAccountId = extractPayableAccountId(runRow);
                    verifyPayableAndCashAccounts(
                            c, tenant, book, year, payableAccountId, in.cashAccountId());

                    UUID supplierId = id(item.get("supplier_id"));
                    var sup = verifyActiveSupplier(c, principal, b, supplierId);
                    UUID supplierRevId = id(sup.get("supplierRevisionId"));
                    if (!supplierRevId.equals(id(item.get("supplier_revision_id"))))
                        throw error("AP_SUPPLIER_STALE", "供应商当前修订与应付未结项不一致");

                    var basisRows =
                            c.list(
                                    "SELECT * FROM plugin_finance_ap_settlement_basis WHERE tenant_id=? AND book_id=? AND year_id=? AND id=? FOR UPDATE",
                                    tenant,
                                    book,
                                    year,
                                    in.settlementBasisId());
                    if (basisRows.isEmpty()
                            || !"APPROVED".equals(basisRows.getFirst().get("status"))
                            || !supplierId.equals(id(basisRows.getFirst().get("supplier_id"))))
                        throw error("AP_SETTLEMENT_BASIS_UNAVAILABLE", "结算依据配额不存在、未独立批准或供应商不匹配");
                    var basis = basisRows.getFirst();

                    String slipSha =
                            verifyOriginalSha(c, tenant, book, year, in.bankSlipOriginalId());

                    var dupRef =
                            c.list(
                                    "SELECT id FROM plugin_finance_ap_settlement_order WHERE tenant_id=? AND book_id=? AND year_id=? AND payment_instruction_ref=?",
                                    tenant,
                                    book,
                                    year,
                                    in.paymentInstructionRef().trim());
                    if (!dupRef.isEmpty())
                        throw error("AP_PAYMENT_REF_EXISTS", "支付指令参考号在当前年度已存在");

                    if ("PAYMENT".equals(in.kind())) {
                        verifySettlementCapacity(
                                c, tenant, book, year, item, basis, amount, null);
                    } else {
                        var origRows =
                                c.list(
                                        "SELECT * FROM plugin_finance_ap_settlement_order WHERE tenant_id=? AND book_id=? AND year_id=? AND id=? FOR UPDATE",
                                        tenant,
                                        book,
                                        year,
                                        in.reversesId());
                        if (origRows.isEmpty())
                            throw error("AP_SETTLEMENT_SOURCE_UNAVAILABLE", "待冲销的原付款结算单不存在");
                        var orig = origRows.getFirst();
                        if (!"PAYMENT".equals(orig.get("kind"))
                                || !"POSTED".equals(orig.get("state"))
                                || orig.get("reversed_by_id") != null
                                || !in.openItemId().equals(id(orig.get("open_item_id")))
                                || !in.settlementBasisId().equals(id(orig.get("settlement_basis_id")))
                                || !in.cashAccountId().equals(id(orig.get("cash_account_id"))))
                            throw error("AP_SETTLEMENT_SOURCE_UNAVAILABLE", "原付款结算单非已入账有效状态或引用要素不一致");
                        BigDecimal origAmt =
                                new BigDecimal(orig.get("amount").toString())
                                        .setScale(2, RoundingMode.UNNECESSARY);
                        if (amount.compareTo(origAmt) != 0)
                            throw error("AP_FULL_REVERSAL_REQUIRED", "应付付款结算冲销必须为全额反向");
                    }

                    UUID orderId = UUID.randomUUID();
                    String contentDigest =
                            FinanceService.hash(
                                    finance.encode(
                                            finance.canonical(
                                                    map(
                                                            "kind",
                                                            in.kind(),
                                                            "openItemId",
                                                            in.openItemId().toString(),
                                                            "settlementBasisId",
                                                            in.settlementBasisId().toString(),
                                                            "supplierId",
                                                            supplierId.toString(),
                                                            "payableAccountId",
                                                            payableAccountId.toString(),
                                                            "cashAccountId",
                                                            in.cashAccountId().toString(),
                                                            "bankSlipSha256",
                                                            slipSha,
                                                            "paymentInstructionRef",
                                                            in.paymentInstructionRef().trim(),
                                                            "accountingDate",
                                                            in.accountingDate().toString(),
                                                            "amount",
                                                            amount.toPlainString(),
                                                            "reversesId",
                                                            in.reversesId() == null
                                                                    ? null
                                                                    : in.reversesId().toString()))));

                    c.update(
                            "INSERT INTO plugin_finance_ap_settlement_order("
                                + "tenant_id,legal_entity_id,book_id,year_id,id,kind,state,version,"
                                + "open_item_id,payable_run_id,settlement_basis_id,supplier_id,supplier_revision_id,"
                                + "payable_account_id,cash_account_id,bank_slip_original_id,bank_slip_sha256,"
                                + "payment_instruction_ref,verification_mode,bank_channel,accounting_date,"
                                + "amount,summary,reason,content_digest,prepared_by,reverses_id) "
                                + "VALUES(?,?,?,?,?,?,'DRAFT',1,?,?,?,?,?,?,?,?,?,?,'INTERNAL_MANUAL_PAYMENT_SLIP','NOT_VERIFIED',?,?,?,?,?,?::jsonb,?)",
                            tenant,
                            legalEntity,
                            book,
                            year,
                            orderId,
                            in.kind(),
                            in.openItemId(),
                            runId,
                            in.settlementBasisId(),
                            supplierId,
                            supplierRevId,
                            payableAccountId,
                            in.cashAccountId(),
                            in.bankSlipOriginalId(),
                            slipSha,
                            in.paymentInstructionRef().trim(),
                            in.accountingDate(),
                            amount,
                            in.summary().trim(),
                            in.reason().trim(),
                            contentDigest,
                            finance.encode(who),
                            in.reversesId());

                    var res =
                            orderView(
                                    c.one(
                                            "SELECT * FROM plugin_finance_ap_settlement_order WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                            tenant,
                                            book,
                                            year,
                                            orderId));
                    saveReplay(c, tenant, actorId, in.idempotencyKey(), route, pHash, res);
                    return res;
                });
    }

    public Object transitionOrder(
            String principal,
            UUID book,
            UUID year,
            UUID orderId,
            String action,
            TransitionSettlementOrderInput in,
            HttpServletRequest request) {
        requireKey(in.idempotencyKey());
        if (in.reason() == null || in.reason().isBlank() || in.reason().length() > 1000)
            throw FinanceException.invalid("应付结算流转原因无效");
        String perm =
                switch (action) {
                    case "approve" -> "approve";
                    case "post" -> "post";
                    case "submit", "cancel" -> "create";
                    default -> throw FinanceException.invalid("不支持的应付结算动作");
                };
        return transaction(
                true,
                c -> {
                    var b = c.scope(principal, book, year, perm);
                    if (!allowed(b, perm))
                        throw error("SCOPE_DENIED", "当前岗位无权执行该应付结算动作");
                    UUID tenant = id(b.get("tenant_id"));
                    UUID actorId = id(b.get("actor_id"));
                    var who = gate.current(actorId, tenant, true, request);
                    String route =
                            "ap-settlement-order-" + action + "/" + book + "/" + year + "/" + orderId;
                    String pHash =
                            FinanceService.hash(
                                    action + ":" + in.expectedVersion() + ":" + in.reason().trim());
                    var cached = replay(c, actorId, in.idempotencyKey(), route, pHash);
                    if (cached != null) return cached;

                    var row =
                            c.one(
                                    "SELECT * FROM plugin_finance_ap_settlement_order WHERE tenant_id=? AND book_id=? AND year_id=? AND id=? FOR UPDATE",
                                    tenant,
                                    book,
                                    year,
                                    orderId);
                    if (((Number) row.get("version")).longValue() != in.expectedVersion())
                        throw error("VERSION_CONFLICT", "应付结算单版本冲突");
                    String curState = row.get("state").toString();
                    String kind = row.get("kind").toString();
                    var maker = json(row.get("prepared_by"));
                    UUID openItemId = id(row.get("open_item_id"));
                    UUID basisId = id(row.get("settlement_basis_id"));
                    BigDecimal amount =
                            new BigDecimal(row.get("amount").toString())
                                    .setScale(2, RoundingMode.UNNECESSARY);
                    LocalDate accDate = LocalDate.parse(row.get("accounting_date").toString());

                    var item =
                            c.one(
                                    "SELECT * FROM plugin_finance_ap_open_item WHERE tenant_id=? AND book_id=? AND year_id=? AND id=? FOR UPDATE",
                                    tenant,
                                    book,
                                    year,
                                    openItemId);
                    var basis =
                            c.one(
                                    "SELECT * FROM plugin_finance_ap_settlement_basis WHERE tenant_id=? AND book_id=? AND year_id=? AND id=? FOR UPDATE",
                                    tenant,
                                    book,
                                    year,
                                    basisId);

                    switch (action) {
                        case "submit" -> {
                            if (!"DRAFT".equals(curState))
                                throw error("AP_SETTLEMENT_STATE_CONFLICT", "仅草稿状态可提交审核");
                            originalAccount(who, maker);
                            checkOpenPeriod(c, tenant, book, year, accDate);
                            verifyActiveSupplier(c, principal, b, id(row.get("supplier_id")));
                            if ("PAYMENT".equals(kind)) {
                                verifySettlementCapacity(
                                        c, tenant, book, year, item, basis, amount, orderId);
                            }
                            c.update(
                                    "UPDATE plugin_finance_ap_settlement_order SET state='SUBMITTED',version=version+1,updated_at=CURRENT_TIMESTAMP "
                                        + "WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                    tenant,
                                    book,
                                    year,
                                    orderId);
                        }
                        case "approve" -> {
                            if (!"SUBMITTED".equals(curState))
                                throw error("AP_SETTLEMENT_STATE_CONFLICT", "仅待审状态可独立批准");
                            independent(who, maker);
                            checkOpenPeriod(c, tenant, book, year, accDate);
                            verifyActiveSupplier(c, principal, b, id(row.get("supplier_id")));
                            if ("PAYMENT".equals(kind)) {
                                verifySettlementCapacity(
                                        c, tenant, book, year, item, basis, amount, orderId);
                            }
                            Instant exp = Instant.now().plus(24, ChronoUnit.HOURS);
                            c.update(
                                    "UPDATE plugin_finance_ap_settlement_order SET state='APPROVED',version=version+1,"
                                        + "approved_by=?::jsonb,approval_expires_at=?,updated_at=CURRENT_TIMESTAMP "
                                        + "WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                    finance.encode(who),
                                    Timestamp.from(exp),
                                    tenant,
                                    book,
                                    year,
                                    orderId);
                        }
                        case "post" -> {
                            if (!"APPROVED".equals(curState))
                                throw error("AP_SETTLEMENT_STATE_CONFLICT", "仅已批准的应付结算单可执行正式记账");
                            var approvedBy = json(row.get("approved_by"));
                            independent(approvedBy, maker);
                            Timestamp exp = (Timestamp) row.get("approval_expires_at");
                            if (exp == null || !exp.toInstant().isAfter(Instant.now()))
                                throw error("AP_SETTLEMENT_APPROVAL_EXPIRED", "应付结算批准已过期");
                            UUID periodId = checkOpenPeriod(c, tenant, book, year, accDate);
                            verifyActiveSupplier(c, principal, b, id(row.get("supplier_id")));
                            if (item.get("reverse_post_id") != null
                                    || ((Number) item.get("version")).longValue() != 1L)
                                throw error("AP_OPEN_ITEM_UNAVAILABLE", "应付未结项已失效");

                            UUID reversedJournalId = null;
                            if ("PAYMENT".equals(kind)) {
                                verifySettlementCapacity(
                                        c, tenant, book, year, item, basis, amount, orderId);
                            } else {
                                UUID origOrderId = id(row.get("reverses_id"));
                                var origOrder =
                                        c.one(
                                                "SELECT * FROM plugin_finance_ap_settlement_order WHERE tenant_id=? AND book_id=? AND year_id=? AND id=? FOR UPDATE",
                                                tenant,
                                                book,
                                                year,
                                                origOrderId);
                                if (!"POSTED".equals(origOrder.get("state"))
                                        || origOrder.get("reversed_by_id") != null)
                                    throw error("AP_SETTLEMENT_SOURCE_UNAVAILABLE", "原付款结算单已被冲销或状态无效");
                                reversedJournalId = id(origOrder.get("journal_id"));
                            }

                            String nativeType =
                                    "PAYMENT".equals(kind) ? "AP_PAYMENT" : "AP_PAYMENT_REVERSAL";
                            UUID journalId = UUID.randomUUID();
                            UUID payableAccId = id(row.get("payable_account_id"));
                            UUID cashAccId = id(row.get("cash_account_id"));

                            c.update(
                                    "INSERT INTO plugin_finance_journal("
                                        + "tenant_id,book_id,year_id,id,period_id,date,summary,type,status,version,created_by,amount_format_version) "
                                        + "VALUES(?,?,?,?,?,?,?,?,'DRAFT',1,?,'V4_8')",
                                    tenant,
                                    book,
                                    year,
                                    journalId,
                                    periodId,
                                    accDate,
                                    row.get("summary"),
                                    nativeType,
                                    id(maker.get("accountId")));

                            if ("PAYMENT".equals(kind)) {
                                c.update(
                                        "INSERT INTO plugin_finance_line(tenant_id,book_id,year_id,journal_id,line_no,account_id,debit,credit,memo) "
                                            + "VALUES(?,?,?,?,1,?,?,0,'AP_PAYABLE_SETTLEMENT')",
                                        tenant,
                                        book,
                                        year,
                                        journalId,
                                        payableAccId,
                                        amount);
                                c.update(
                                        "INSERT INTO plugin_finance_line(tenant_id,book_id,year_id,journal_id,line_no,account_id,debit,credit,memo) "
                                            + "VALUES(?,?,?,?,2,?,0,?,'AP_CASH_OUTFLOW')",
                                        tenant,
                                        book,
                                        year,
                                        journalId,
                                        cashAccId,
                                        amount);
                            } else {
                                c.update(
                                        "INSERT INTO plugin_finance_line(tenant_id,book_id,year_id,journal_id,line_no,account_id,debit,credit,memo) "
                                            + "VALUES(?,?,?,?,1,?,?,0,'AP_CASH_OUTFLOW_REVERSAL')",
                                        tenant,
                                        book,
                                        year,
                                        journalId,
                                        cashAccId,
                                        amount);
                                c.update(
                                        "INSERT INTO plugin_finance_line(tenant_id,book_id,year_id,journal_id,line_no,account_id,debit,credit,memo) "
                                            + "VALUES(?,?,?,?,2,?,0,?,'AP_PAYABLE_SETTLEMENT_REVERSAL')",
                                        tenant,
                                        book,
                                        year,
                                        journalId,
                                        payableAccId,
                                        amount);
                            }

                            c.update(
                                    "UPDATE plugin_finance_journal SET status='APPROVED',version=2,approved_by=?,approved_version=2 "
                                        + "WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                    id(approvedBy.get("accountId")),
                                    tenant,
                                    book,
                                    year,
                                    journalId);

                            long seq =
                                    ((Number)
                                                    c.one(
                                                                    "UPDATE plugin_finance_year_guard SET posting_seq=posting_seq+1 "
                                                                        + "WHERE tenant_id=? AND book_id=? AND year_id=? RETURNING posting_seq",
                                                                    tenant,
                                                                    book,
                                                                    year)
                                                            .get("posting_seq"))
                                            .longValue();
                            long journalNo =
                                    ((Number)
                                                    c.one(
                                                                    "SELECT COALESCE(MAX(number),0)+1 AS n FROM plugin_finance_journal "
                                                                        + "WHERE tenant_id=? AND book_id=? AND year_id=?",
                                                                    tenant,
                                                                    book,
                                                                    year)
                                                            .get("n"))
                                            .longValue();

                            c.update(
                                    "INSERT INTO plugin_finance_posting(tenant_id,book_id,year_id,journal_id,journal_version,posting_seq,digest,posted_by) "
                                        + "VALUES(?,?,?,?,2,?,?,?)",
                                    tenant,
                                    book,
                                    year,
                                    journalId,
                                    seq,
                                    row.get("content_digest"),
                                    actorId);

                            c.update(
                                    "UPDATE plugin_finance_journal SET status='POSTED',version=3,number=?,reversal_of=? "
                                        + "WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                    journalNo,
                                    reversedJournalId,
                                    tenant,
                                    book,
                                    year,
                                    journalId);

                            c.update(
                                    "UPDATE plugin_finance_ap_settlement_order SET state='POSTED',version=version+1,"
                                        + "posted_by=?::jsonb,journal_id=?,posting_seq=?,updated_at=CURRENT_TIMESTAMP "
                                        + "WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                    finance.encode(who),
                                    journalId,
                                    seq,
                                    tenant,
                                    book,
                                    year,
                                    orderId);

                            if ("REVERSAL".equals(kind)) {
                                c.update(
                                        "UPDATE plugin_finance_ap_settlement_order SET reversed_by_id=?,updated_at=CURRENT_TIMESTAMP "
                                            + "WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                        orderId,
                                        tenant,
                                        book,
                                        year,
                                        id(row.get("reverses_id")));
                            }

                            c.timeout();
                            FinanceOutbox.enqueue(
                                    db, finance, b, year, "JOURNAL_POSTED", journalId, actorId);
                            c.check();
                        }
                        case "cancel" -> {
                            if (!Set.of("DRAFT", "SUBMITTED", "APPROVED").contains(curState))
                                throw error("AP_SETTLEMENT_STATE_CONFLICT", "已记账或已作废的结算单不可作废");
                            originalAccount(who, maker);
                            c.update(
                                    "UPDATE plugin_finance_ap_settlement_order SET state='CANCELLED',version=version+1,updated_at=CURRENT_TIMESTAMP "
                                        + "WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                    tenant,
                                    book,
                                    year,
                                    orderId);
                        }
                        default -> throw FinanceException.invalid("不支持的应付结算动作");
                    }

                    var res =
                            orderView(
                                    c.one(
                                            "SELECT * FROM plugin_finance_ap_settlement_order WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                            tenant,
                                            book,
                                            year,
                                            orderId));
                    saveReplay(c, tenant, actorId, in.idempotencyKey(), route, pHash, res);
                    return res;
                });
    }

    private UUID checkOpenPeriod(Call c, UUID tenant, UUID book, UUID year, LocalDate date) {
        var rows =
                c.list(
                        "SELECT p.id FROM plugin_finance_period p "
                            + "JOIN plugin_finance_year y ON (y.tenant_id,y.book_id,y.id)=(p.tenant_id,p.book_id,p.year_id) "
                            + "WHERE p.tenant_id=? AND p.book_id=? AND p.year_id=? "
                            + "AND y.year=extract(year FROM ?::date) AND p.month=extract(month FROM ?::date) "
                            + "AND p.status='OPEN' AND y.status='OPEN' FOR UPDATE OF p",
                        tenant,
                        book,
                        year,
                        date,
                        date);
        if (rows.isEmpty()) throw error("PERIOD_CLOSED", "会计日期所属期间未开放或不属于当前年度");
        return id(rows.getFirst().get("id"));
    }

    private void verifyPayableAndCashAccounts(
            Call c, UUID tenant, UUID book, UUID year, UUID payableAccId, UUID cashAccId) {
        var pRows =
                c.list(
                        "SELECT category,normal_side,enabled FROM plugin_finance_account WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        tenant,
                        book,
                        year,
                        payableAccId);
        if (pRows.isEmpty()
                || !Boolean.TRUE.equals(pRows.getFirst().get("enabled"))
                || !"LIABILITY".equals(pRows.getFirst().get("category"))
                || !"CREDIT".equals(pRows.getFirst().get("normal_side")))
            throw error("AP_ACCOUNT_INVALID", "应付账款负债科目无效或已停用");
        var cRows =
                c.list(
                        "SELECT category,normal_side,enabled FROM plugin_finance_account WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        tenant,
                        book,
                        year,
                        cashAccId);
        if (cRows.isEmpty()
                || !Boolean.TRUE.equals(cRows.getFirst().get("enabled"))
                || !"ASSET".equals(cRows.getFirst().get("category"))
                || !"DEBIT".equals(cRows.getFirst().get("normal_side")))
            throw error("AP_ACCOUNT_INVALID", "银行存款/货币资金资产科目无效或已停用");
    }

    private void verifySettlementCapacity(
            Call c,
            UUID tenant,
            UUID book,
            UUID year,
            Map<String, Object> item,
            Map<String, Object> basis,
            BigDecimal currentAmount,
            UUID excludeOrderId) {
        UUID exclude = excludeOrderId == null ? new UUID(0, 0) : excludeOrderId;
        BigDecimal itemCapacity =
                new BigDecimal(item.get("amount").toString())
                        .setScale(2, RoundingMode.UNNECESSARY);
        var itemUsedRow =
                c.one(
                        "SELECT COALESCE(SUM(amount),0) AS u FROM plugin_finance_ap_settlement_order "
                            + "WHERE tenant_id=? AND book_id=? AND year_id=? AND open_item_id=? "
                            + "AND kind='PAYMENT' AND id<>? "
                            + "AND (state IN('DRAFT','SUBMITTED','APPROVED') OR (state='POSTED' AND reversed_by_id IS NULL))",
                        tenant,
                        book,
                        year,
                        id(item.get("id")),
                        exclude);
        BigDecimal itemUsed =
                new BigDecimal(itemUsedRow.get("u").toString())
                        .setScale(2, RoundingMode.UNNECESSARY);
        if (itemUsed.add(currentAmount).compareTo(itemCapacity) > 0)
            throw error("AP_SETTLEMENT_EXCEEDS_OPEN_ITEM", "应付结算金额超出未结项剩余可结算余额");

        BigDecimal basisCapacity =
                new BigDecimal(basis.get("capacity").toString())
                        .setScale(2, RoundingMode.UNNECESSARY);
        var basisUsedRow =
                c.one(
                        "SELECT COALESCE(SUM(amount),0) AS u FROM plugin_finance_ap_settlement_order "
                            + "WHERE tenant_id=? AND book_id=? AND year_id=? AND settlement_basis_id=? "
                            + "AND kind='PAYMENT' AND id<>? "
                            + "AND (state IN('DRAFT','SUBMITTED','APPROVED') OR (state='POSTED' AND reversed_by_id IS NULL))",
                        tenant,
                        book,
                        year,
                        id(basis.get("id")),
                        exclude);
        BigDecimal basisUsed =
                new BigDecimal(basisUsedRow.get("u").toString())
                        .setScale(2, RoundingMode.UNNECESSARY);
        if (basisUsed.add(currentAmount).compareTo(basisCapacity) > 0)
            throw error("AP_SETTLEMENT_BASIS_EXCEEDED", "应付结算金额超出结算依据配额剩余可用额度");
    }

    private Map<String, Object> basisView(
            Call c, UUID tenant, UUID book, UUID year, Map<String, Object> r) {
        UUID basisId = id(r.get("id"));
        BigDecimal cap =
                new BigDecimal(r.get("capacity").toString()).setScale(2, RoundingMode.UNNECESSARY);
        var usedRow =
                c.one(
                        "SELECT COALESCE(SUM(amount),0) AS u FROM plugin_finance_ap_settlement_order "
                            + "WHERE tenant_id=? AND book_id=? AND year_id=? AND settlement_basis_id=? "
                            + "AND kind='PAYMENT' AND (state IN('DRAFT','SUBMITTED','APPROVED') OR (state='POSTED' AND reversed_by_id IS NULL))",
                        tenant,
                        book,
                        year,
                        basisId);
        BigDecimal used =
                new BigDecimal(usedRow.get("u").toString()).setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal avail = cap.subtract(used).max(BigDecimal.ZERO).setScale(2, RoundingMode.UNNECESSARY);
        return map(
                "id",
                basisId.toString(),
                "originalId",
                r.get("original_id").toString(),
                "originalSha256",
                r.get("original_sha256"),
                "purpose",
                r.get("purpose"),
                "supplierId",
                r.get("supplier_id").toString(),
                "supplierRevisionId",
                r.get("supplier_revision_id").toString(),
                "payeeAccountLabel",
                r.get("payee_account_label"),
                "bankChannel",
                r.get("bank_channel"),
                "capacity",
                cap.toPlainString(),
                "usedAmount",
                used.toPlainString(),
                "availableAmount",
                avail.toPlainString(),
                "status",
                r.get("status"),
                "version",
                r.get("version").toString(),
                "reason",
                r.get("reason"));
    }

    private Map<String, Object> orderView(Map<String, Object> r) {
        return map(
                "id",
                r.get("id").toString(),
                "kind",
                r.get("kind"),
                "state",
                r.get("state"),
                "version",
                r.get("version").toString(),
                "openItemId",
                r.get("open_item_id").toString(),
                "payableRunId",
                r.get("payable_run_id").toString(),
                "settlementBasisId",
                r.get("settlement_basis_id").toString(),
                "supplierId",
                r.get("supplier_id").toString(),
                "payableAccountId",
                r.get("payable_account_id").toString(),
                "cashAccountId",
                r.get("cash_account_id").toString(),
                "bankSlipOriginalId",
                r.get("bank_slip_original_id").toString(),
                "paymentInstructionRef",
                r.get("payment_instruction_ref"),
                "verificationMode",
                r.get("verification_mode"),
                "bankChannel",
                r.get("bank_channel"),
                "accountingDate",
                r.get("accounting_date").toString(),
                "amount",
                fmt2(r.get("amount")),
                "summary",
                r.get("summary"),
                "journalId",
                r.get("journal_id") == null ? null : r.get("journal_id").toString(),
                "postingSeq",
                r.get("posting_seq") == null ? null : r.get("posting_seq").toString(),
                "reversesId",
                r.get("reverses_id") == null ? null : r.get("reverses_id").toString(),
                "reversedById",
                r.get("reversed_by_id") == null ? null : r.get("reversed_by_id").toString());
    }

    private Object replay(
            Call c, UUID actorId, String requestKey, String route, String payloadHash) {
        var rows =
                c.list(
                        "SELECT route,payload_hash,response FROM plugin_finance_v4_governance_request "
                            + "WHERE actor_id=? AND request_key=?",
                        actorId,
                        requestKey);
        if (rows.isEmpty()) return null;
        var old = rows.getFirst();
        if (!route.equals(old.get("route")) || !payloadHash.equals(old.get("payload_hash")))
            throw error("IDEMPOTENCY_CONFLICT", "应付结算幂等键绑定了不同请求范围或正文");
        return json(old.get("response"));
    }

    private void saveReplay(
            Call c,
            UUID tenant,
            UUID actorId,
            String requestKey,
            String route,
            String payloadHash,
            Object response) {
        c.update(
                "INSERT INTO plugin_finance_v4_governance_request("
                    + "actor_id,request_key,tenant_id,route,payload_hash,response) "
                    + "VALUES(?,?,?,?,?,?::jsonb)",
                actorId,
                requestKey,
                tenant,
                route,
                payloadHash,
                finance.encode(response));
    }
}

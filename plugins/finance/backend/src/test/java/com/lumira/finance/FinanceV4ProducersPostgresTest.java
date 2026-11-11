package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.finance.assets.AssetModel.Card;
import com.lumira.finance.assets.AssetModel.Receipt;
import com.lumira.finance.assets.AssetModel.StoredPlan;
import com.lumira.finance.assets.AssetService.Difference;
import com.lumira.finance.payroll.PayrollCalculation.DeductionMode;
import com.lumira.finance.payroll.PayrollController;
import com.lumira.finance.payroll.PayrollService.*;
import com.lumira.finance.payroll.PayrollView;
import com.lumira.platform.common.AuthorizationContextProjectionPort;

import org.junit.jupiter.api.*;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/**
 * End-to-end PostgreSQL 18 integration test over real V1-V24 migrations (zero trigger/constraint
 * bypass hacks) verifying FinanceReceivables + FinanceReceivablesProducer, FinanceAssets +
 * FinanceAssetsProducer, and FinancePayroll + V22/V23 RLS governance stores.
 */
class FinanceV4ProducersPostgresTest {
    static FinanceStore db;
    static FinanceCounterparties counterparties;
    static FinanceTaxEntities taxEntities;
    static FinanceReceivablesProducer arProducer;
    static FinanceReceivables receivables;
    static FinanceAssetsProducer assetProducer;
    static FinanceAssets assets;
    static FinancePayroll payroll;

    static final AuthorizationContextProjectionPort AUTH =
            p ->
                    Optional.of(
                            new AuthorizationContextProjectionPort.Snapshot(
                                    FinanceJournalPersonPostgresTest.ALL,
                                    FinanceJournalPersonPostgresTest.ALL,
                                    false,
                                    null,
                                    0));

    @BeforeAll
    static void setup() throws Exception {
        FinanceJournalPersonPostgresTest.setup(24);
        db = FinanceJournalPersonPostgresTest.db;
        FinanceEvidenceUsagePostgresTest.db = db;
        FinanceCounterpartyPostgresTest.db = db;
        FinanceTaxEntityPostgresTest.db = db;

        var txMgr = FinancePostgresTest.tx.getTransactionManager();
        var modern = FinanceJournalPersonPostgresTest.modern;
        var gate = FinanceJournalPersonPostgresTest.gate;

        counterparties = new FinanceCounterparties(db, modern, gate, txMgr, AUTH);
        FinanceCounterpartyPostgresTest.service = counterparties;

        taxEntities = new FinanceTaxEntities(db, modern, gate, txMgr, AUTH);
        FinanceTaxEntityPostgresTest.service = taxEntities;

        arProducer = new FinanceReceivablesProducer(db, modern, gate, txMgr, AUTH);
        receivables = new FinanceReceivables(db, modern, gate, txMgr, AUTH, Optional.of(arProducer));

        assetProducer = new FinanceAssetsProducer(db, modern, gate, txMgr, AUTH);
        assets =
                new FinanceAssets(
                        db,
                        modern,
                        gate,
                        txMgr,
                        AUTH,
                        Optional.of(FinancePostgresTest.source),
                        Optional.of(assetProducer),
                        Optional.of(assetProducer));

        payroll = new FinancePayroll(db, modern, gate, txMgr, AUTH, Optional.empty());
    }

    @AfterAll
    static void cleanup() {
        FinanceJournalPersonPostgresTest.cleanup();
    }

    @BeforeEach
    void reset() {
        FinanceJournalPersonPostgresTest.denied.clear();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object x) {
        return (Map<String, Object>) x;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object x) {
        return (List<Map<String, Object>>) x;
    }

    static Map<String, Object> map(Object... kv) {
        return FinanceJournalPersonGate.map(kv);
    }

    static UUID id(Object v) {
        return UUID.fromString(v.toString());
    }

    static UUID addAccount(UUID book, UUID year, String code, String name, String side, String cat) {
        var r =
                obj(
                        FinancePostgresTest.write(
                                "maker",
                                book,
                                year,
                                "accounts",
                                null,
                                map(
                                        "code",
                                        code,
                                        "name",
                                        name,
                                        "normalSide",
                                        side,
                                        "category",
                                        cat,
                                        "purpose",
                                        "NONE")));
        return id(r.get("id"));
    }

    static Map<String, UUID> createApprovedCustomer(UUID book) {
        var body =
                FinanceCounterpartyPostgresTest.create(
                        "CUST/" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT).substring(0, 8));
        obj(body.get("content")).put("roles", List.of("CUSTOMER"));
        var created = FinanceCounterpartyPostgresTest.write("maker", book, "create", null, null, body);
        var pending = FinanceCounterpartyPostgresTest.pending(book, created);
        var approved = FinanceCounterpartyPostgresTest.decide("reviewer", book, "approve", pending);
        var cp = obj(approved.get("counterparty"));
        return Map.of("id", id(cp.get("id")), "revisionId", id(obj(cp.get("effectiveRevision")).get("id")));
    }

    static Map<String, UUID> createApprovedTaxEntity(FinanceJournalPersonPostgresTest.Case c) {
        var tc = new FinanceTaxEntityPostgresTest.Case(c, FinanceEvidenceUsagePostgresTest.upload(c, "tax-reg"));
        var created =
                FinanceTaxEntityPostgresTest.current(
                        FinanceTaxEntityPostgresTest.write(
                                "maker",
                                tc.book(),
                                "create",
                                null,
                                null,
                                FinanceTaxEntityPostgresTest.create(tc)));
        var approved =
                FinanceTaxEntityPostgresTest.current(
                        FinanceTaxEntityPostgresTest.decide("reviewer", tc, "approve", created));
        var te = obj(approved.get("taxEntity"));
        return Map.of("id", id(te.get("id")), "revisionId", id(obj(te.get("approvedRevision")).get("id")));
    }

    @Test
    void receivablesProducerEndToEndChainAndSelfApprovalRejection() {
        var c = FinanceJournalPersonPostgresTest.fixture("CAS");
        FinanceJournalPersonPostgresTest.enable(c);
        UUID book = c.f().book(), year = c.f().year();

        UUID aRec = addAccount(book, year, "1122", "应收账款", "DEBIT", "ASSET");
        UUID aRev = addAccount(book, year, "6001", "主营业务收入", "CREDIT", "REVENUE");
        UUID aTax = addAccount(book, year, "2221", "应交税费-销项", "CREDIT", "LIABILITY");
        UUID aAdv = addAccount(book, year, "2203", "预收账款", "CREDIT", "LIABILITY");
        UUID aCash = c.f().cash();

        var cust = createApprovedCustomer(book);
        var taxEnt = createApprovedTaxEntity(c);
        UUID orig = FinanceEvidenceUsagePostgresTest.upload(c, "ar-contract-invoice");

        var initialPage =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () -> obj(receivables.read("maker", book, year, null, null, 20, LocalDate.of(2026, 1, 31))));
        assertEquals(Boolean.TRUE, initialPage.get("nativeProducerAvailable"));

        // Create & approve RECOGNITION decision (with self-approval check via alias)
        var recDec =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        arProducer.createDecision(
                                                "maker",
                                                book,
                                                year,
                                                map(
                                                        "idempotencyKey",
                                                        UUID.randomUUID().toString(),
                                                        "kind",
                                                        "RECOGNITION",
                                                        "policyVersion",
                                                        "CAS14-CNY-V4.1",
                                                        "accountingDate",
                                                        "2026-01-18",
                                                        "gross",
                                                        "1130.00",
                                                        "net",
                                                        "1000.00",
                                                        "taxAmount",
                                                        "130.00",
                                                        "taxEntityId",
                                                        taxEnt.get("id").toString(),
                                                        "taxTreatment",
                                                        "TAXABLE",
                                                        "taxRuleVersion",
                                                        "CN-VAT-13-V1",
                                                        "basisOriginalId",
                                                        orig.toString(),
                                                        "lines",
                                                        List.of(
                                                                map("accountId", aRec.toString(), "role", "RECEIVABLE", "debit", "1130.00", "credit", "0.00"),
                                                                map("accountId", aRev.toString(), "role", "REVENUE", "debit", "0.00", "credit", "1000.00"),
                                                                map("accountId", aTax.toString(), "role", "OUTPUT_TAX", "debit", "0.00", "credit", "130.00")),
                                                        "reason",
                                                        "合同阶段验收无条件收款权确认"),
                                                FinanceJournalPersonPostgresTest.request())));
        var sodErr =
                assertThrows(
                        FinanceException.class,
                        () ->
                                FinanceJournalPersonPostgresTest.as(
                                        "alias",
                                        () ->
                                                arProducer.approveDecision(
                                                        "alias",
                                                        book,
                                                        year,
                                                        id(recDec.get("id")),
                                                        map(
                                                                "idempotencyKey",
                                                                UUID.randomUUID().toString(),
                                                                "expectedVersion",
                                                                "1",
                                                                "reason",
                                                                "同自然人自审应阻断"),
                                                        FinanceJournalPersonPostgresTest.request())));
        assertEquals("SELF_APPROVAL_FORBIDDEN", sodErr.code);

        FinanceJournalPersonPostgresTest.as(
                "reviewer",
                () ->
                        arProducer.approveDecision(
                                "reviewer",
                                book,
                                year,
                                id(recDec.get("id")),
                                map("idempotencyKey", UUID.randomUUID().toString(), "expectedVersion", "1", "reason", "独立复核通过"),
                                FinanceJournalPersonPostgresTest.request()));

        UUID rcptDecId =
                createAndApproveSimpleArDecision(
                        book,
                        year,
                        orig,
                        "RECEIPT",
                        "2026-01-20",
                        "1130.00",
                        List.of(
                                map("accountId", aCash.toString(), "role", "CASH", "debit", "1130.00", "credit", "0.00"),
                                map("accountId", aAdv.toString(), "role", "CUSTOMER_ADVANCE", "debit", "0.00", "credit", "1130.00")));
        UUID allocDecId =
                createAndApproveSimpleArDecision(
                        book,
                        year,
                        orig,
                        "ALLOCATION",
                        "2026-01-22",
                        "600.00",
                        List.of(
                                map("accountId", aAdv.toString(), "role", "CUSTOMER_ADVANCE", "debit", "600.00", "credit", "0.00"),
                                map("accountId", aRec.toString(), "role", "RECEIVABLE", "debit", "0.00", "credit", "600.00")));
        UUID revDecId =
                createAndApproveSimpleArDecision(
                        book,
                        year,
                        orig,
                        "REVERSAL",
                        "2026-01-25",
                        "600.00",
                        List.of(
                                map("accountId", aAdv.toString(), "role", "CUSTOMER_ADVANCE", "debit", "0.00", "credit", "600.00"),
                                map("accountId", aRec.toString(), "role", "RECEIVABLE", "debit", "600.00", "credit", "0.00")));

        // 1. CONTRACT -> CONFIRMED
        UUID contractId =
                advanceAr(
                        book,
                        year,
                        false,
                        map(
                                "kind",
                                "CONTRACT",
                                "customerId",
                                cust.get("id").toString(),
                                "customerRevisionId",
                                cust.get("revisionId").toString(),
                                "originalId",
                                orig.toString(),
                                "reason",
                                "销售合同签署",
                                "businessKey",
                                "AR-CT-001",
                                "date",
                                "2026-01-10",
                                "amount",
                                "1130.00",
                                "terms",
                                "交付验收后30日内付清"));
        // 2. PERFORMANCE -> CONFIRMED
        UUID perfId =
                advanceAr(
                        book,
                        year,
                        false,
                        map(
                                "kind",
                                "PERFORMANCE",
                                "customerId",
                                cust.get("id").toString(),
                                "customerRevisionId",
                                cust.get("revisionId").toString(),
                                "originalId",
                                orig.toString(),
                                "reason",
                                "阶段交付客户验收单",
                                "businessKey",
                                "AR-PF-001",
                                "date",
                                "2026-01-15",
                                "amount",
                                "1130.00",
                                "contractId",
                                contractId.toString(),
                                "performanceRule",
                                "里程碑验收",
                                "acceptedOn",
                                "2026-01-15"));
        // 3. RECOGNITION -> POSTED
        UUID recogId =
                advanceAr(
                        book,
                        year,
                        true,
                        map(
                                "kind",
                                "RECOGNITION",
                                "customerId",
                                cust.get("id").toString(),
                                "customerRevisionId",
                                cust.get("revisionId").toString(),
                                "originalId",
                                orig.toString(),
                                "reason",
                                "确认主营业务收入及销项税",
                                "businessKey",
                                "AR-RC-001",
                                "date",
                                "2026-01-18",
                                "amount",
                                "1130.00",
                                "performanceId",
                                perfId.toString(),
                                "decisionId",
                                recDec.get("id").toString(),
                                "dueDate",
                                "2026-02-17"));
        // 4. RECEIPT -> POSTED
        UUID receiptId =
                advanceAr(
                        book,
                        year,
                        true,
                        map(
                                "kind",
                                "RECEIPT",
                                "customerId",
                                cust.get("id").toString(),
                                "customerRevisionId",
                                cust.get("revisionId").toString(),
                                "originalId",
                                orig.toString(),
                                "reason",
                                "登记银行收款回执",
                                "businessKey",
                                "AR-RP-001",
                                "date",
                                "2026-01-20",
                                "amount",
                                "1130.00",
                                "receiptDecisionId",
                                rcptDecId.toString(),
                                "bankAccountReference",
                                "CMB-CNY-001",
                                "receiptReference",
                                "BANK-SLIP-20260120"));
        // 5. Partial ALLOCATION (600.00) -> POSTED
        UUID allocId =
                advanceAr(
                        book,
                        year,
                        true,
                        map(
                                "kind",
                                "ALLOCATION",
                                "customerId",
                                cust.get("id").toString(),
                                "customerRevisionId",
                                cust.get("revisionId").toString(),
                                "originalId",
                                orig.toString(),
                                "reason",
                                "部分核销应收账款600元",
                                "businessKey",
                                "AR-AL-001",
                                "date",
                                "2026-01-22",
                                "amount",
                                "600.00",
                                "receiptId",
                                receiptId.toString(),
                                "allocationDecisionId",
                                allocDecId.toString(),
                                "items",
                                List.of(map("recognitionId", recogId.toString(), "amount", "600.00"))));

        var recogMid =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () -> obj(receivables.read("maker", book, year, recogId, null, 20, LocalDate.of(2026, 1, 23))));
        assertEquals("530.00000000", list(recogMid.get("balances")).getFirst().get("outstanding"));

        // 6. Full REVERSAL of ALLOCATION -> POSTED
        advanceAr(
                book,
                year,
                true,
                map(
                        "kind",
                        "REVERSAL",
                        "customerId",
                        cust.get("id").toString(),
                        "customerRevisionId",
                        cust.get("revisionId").toString(),
                        "originalId",
                        orig.toString(),
                        "reason",
                        "完整反向冲销核销记录",
                        "businessKey",
                        "AR-RV-001",
                        "date",
                        "2026-01-25",
                        "amount",
                        "600.00",
                        "originalObjectId",
                        allocId.toString(),
                        "correctionDecisionId",
                        revDecId.toString()));

        var recogAfter =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () -> obj(receivables.read("maker", book, year, recogId, null, 20, LocalDate.of(2026, 1, 26))));
        assertEquals("1130.00000000", list(recogAfter.get("balances")).getFirst().get("outstanding"));

        var tb = obj(FinanceJournalPersonPostgresTest.modern.read("maker", book, year, "trial-balance", null, null));
        assertEquals(Boolean.TRUE, tb.get("balanced"));
    }

    private static UUID createAndApproveSimpleArDecision(
            UUID book,
            UUID year,
            UUID orig,
            String kind,
            String date,
            String gross,
            List<Map<String, Object>> lines) {
        var created =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        arProducer.createDecision(
                                                "maker",
                                                book,
                                                year,
                                                map(
                                                        "idempotencyKey",
                                                        UUID.randomUUID().toString(),
                                                        "kind",
                                                        kind,
                                                        "policyVersion",
                                                        "CAS14-CNY-V4.1",
                                                        "accountingDate",
                                                        date,
                                                        "gross",
                                                        gross,
                                                        "net",
                                                        gross,
                                                        "taxAmount",
                                                        "0.00",
                                                        "taxEntityId",
                                                        null,
                                                        "taxTreatment",
                                                        null,
                                                        "taxRuleVersion",
                                                        null,
                                                        "basisOriginalId",
                                                        orig.toString(),
                                                        "lines",
                                                        lines,
                                                        "reason",
                                                        "标准应收科目核定"),
                                                FinanceJournalPersonPostgresTest.request())));
        FinanceJournalPersonPostgresTest.as(
                "reviewer",
                () ->
                        arProducer.approveDecision(
                                "reviewer",
                                book,
                                year,
                                id(created.get("id")),
                                map("idempotencyKey", UUID.randomUUID().toString(), "expectedVersion", "1", "reason", "独立批准"),
                                FinanceJournalPersonPostgresTest.request()));
        return id(created.get("id"));
    }

    private static UUID advanceAr(UUID book, UUID year, boolean financial, Map<String, Object> createBody) {
        var body = new LinkedHashMap<>(createBody);
        body.put("idempotencyKey", UUID.randomUUID().toString());
        body.put("operationId", UUID.randomUUID().toString());
        var created =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () -> obj(receivables.write("maker", book, year, "create", null, body, FinanceJournalPersonPostgresTest.request())));
        UUID target = id(created.get("id"));
        var submitted =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        receivables.write(
                                                "maker",
                                                book,
                                                year,
                                                "submit",
                                                target,
                                                map("idempotencyKey", UUID.randomUUID().toString(), "expectedVersion", "1", "reason", "提交复核"),
                                                FinanceJournalPersonPostgresTest.request())));
        assertEquals("SUBMITTED", submitted.get("state"));
        var approved =
                FinanceJournalPersonPostgresTest.as(
                        "reviewer",
                        () ->
                                obj(
                                        receivables.write(
                                                "reviewer",
                                                book,
                                                year,
                                                "approve",
                                                target,
                                                map("idempotencyKey", UUID.randomUUID().toString(), "expectedVersion", "2", "reason", "独立批准"),
                                                FinanceJournalPersonPostgresTest.request())));
        if (!financial) {
            assertEquals("CONFIRMED", approved.get("state"));
            return target;
        }
        assertEquals("APPROVED", approved.get("state"));
        var posted =
                FinanceJournalPersonPostgresTest.as(
                        "other",
                        () ->
                                obj(
                                        receivables.write(
                                                "other",
                                                book,
                                                year,
                                                "post",
                                                target,
                                                map("idempotencyKey", UUID.randomUUID().toString(), "expectedVersion", "3", "reason", "正式记账"),
                                                FinanceJournalPersonPostgresTest.request())));
        assertEquals("POSTED", posted.get("state"));
        assertNotNull(posted.get("journalId"));
        return target;
    }

    @Test
    void assetsProducerLifecyclePrecisionFixAndNonEmptyReconciliation() {
        var c = FinanceJournalPersonPostgresTest.fixture("CAS");
        FinanceJournalPersonPostgresTest.enable(c);
        UUID book = c.f().book(), year = c.f().year();

        UUID aCost = addAccount(book, year, "1601", "固定资产原值", "DEBIT", "ASSET");
        UUID aAcc = addAccount(book, year, "1602", "累计折旧", "CREDIT", "ASSET");
        UUID aExp = addAccount(book, year, "6602", "管理费用-折旧", "DEBIT", "EXPENSE");
        UUID aPay = addAccount(book, year, "2202", "应付账款-设备", "CREDIT", "LIABILITY");
        UUID aLoss = addAccount(book, year, "6711", "资产处置损失", "DEBIT", "EXPENSE");

        UUID acqOrig = FinanceEvidenceUsagePostgresTest.upload(c, "asset-invoice");
        UUID assessOrig1 = FinanceEvidenceUsagePostgresTest.upload(c, "asset-policy-1");
        UUID assessOrig2 = FinanceEvidenceUsagePostgresTest.upload(c, "asset-policy-2");
        UUID dispOrig = FinanceEvidenceUsagePostgresTest.upload(c, "asset-disposal-approval");

        var quota =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        assetProducer.createQuota(
                                                "maker",
                                                book,
                                                year,
                                                map(
                                                        "idempotencyKey",
                                                        UUID.randomUUID().toString(),
                                                        "originalId",
                                                        acqOrig.toString(),
                                                        "amount",
                                                        "12000.00",
                                                        "reason",
                                                        "服务器设备采购验收确认"),
                                                FinanceJournalPersonPostgresTest.request())));
        assertThrows(
                FinanceException.class,
                () ->
                        FinanceJournalPersonPostgresTest.as(
                                "alias",
                                () ->
                                        assetProducer.approveQuota(
                                                "alias",
                                                book,
                                                year,
                                                id(quota.get("id")),
                                                map("idempotencyKey", UUID.randomUUID().toString(), "expectedVersion", "1", "reason", "自批阻断"),
                                                FinanceJournalPersonPostgresTest.request())));
        FinanceJournalPersonPostgresTest.as(
                "reviewer",
                () ->
                        assetProducer.approveQuota(
                                "reviewer",
                                book,
                                year,
                                id(quota.get("id")),
                                map("idempotencyKey", UUID.randomUUID().toString(), "expectedVersion", "1", "reason", "独立批准资产购置配额"),
                                FinanceJournalPersonPostgresTest.request()));

        UUID custodianPerson =
                id(
                        FinanceJournalPersonPostgresTest.gate
                                .identity(c.tenant(), FinancePostgresTest.users.get("maker"))
                                .get("personId"));

        // 1. ACQUIRE -> POSTED
        Receipt acqReceipt =
                advanceAssetPlan(
                        book,
                        year,
                        LocalDate.of(2026, 1, 10),
                        () ->
                                assets.command(
                                        "maker",
                                        book,
                                        year,
                                        LocalDate.of(2026, 1, 10),
                                        "acquire",
                                        UUID.randomUUID().toString(),
                                        map(
                                                "sourceId",
                                                quota.get("id").toString(),
                                                "sourceAmount",
                                                "12000.00",
                                                "expensedAmount",
                                                "0.00",
                                                "expenseAccount",
                                                null,
                                                "creditAccount",
                                                aPay.toString(),
                                                "sourceKind",
                                                "PURCHASE",
                                                "reason",
                                                "机房核心服务器采购入账",
                                                "cards",
                                                List.of(
                                                        map(
                                                                "name",
                                                                "机房核心服务器",
                                                                "classification",
                                                                "ELECTRONIC_EQUIPMENT",
                                                                "acquired",
                                                                "2026-01-10",
                                                                "ready",
                                                                "2026-01-10",
                                                                "location",
                                                                "北京机房A01",
                                                                "custodian",
                                                                custodianPerson.toString(),
                                                                "cost",
                                                                "12000.00",
                                                                "accounts",
                                                                map(
                                                                        "cost",
                                                                        aCost.toString(),
                                                                        "accumulated",
                                                                        aAcc.toString(),
                                                                        "expense",
                                                                        aExp.toString()),
                                                                "policy",
                                                                map(
                                                                        "framework",
                                                                        "CAS",
                                                                        "kind",
                                                                        "FIXED",
                                                                        "method",
                                                                        "STRAIGHT_LINE",
                                                                        "firstPeriod",
                                                                        "2026-01",
                                                                        "months",
                                                                        12,
                                                                        "residual",
                                                                        "0.00",
                                                                        "assessmentId",
                                                                        assessOrig1.toString())))),
                                        FinanceJournalPersonPostgresTest.request()));
        assertNotNull(acqReceipt.journalId());

        List<Card> cards =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () -> assets.cards("maker", book, year, LocalDate.of(2026, 1, 20), null, 20));
        assertEquals(1, cards.size());
        Card card1 = cards.getFirst();

        // 2. DEPRECIATE (2026-01 -> 1000.00) -> POSTED
        Receipt depReceipt =
                advanceAssetPlan(
                        book,
                        year,
                        LocalDate.of(2026, 1, 28),
                        () ->
                                assets.command(
                                        "maker",
                                        book,
                                        year,
                                        LocalDate.of(2026, 1, 28),
                                        "depreciate",
                                        UUID.randomUUID().toString(),
                                        map(
                                                "assetId",
                                                card1.id().toString(),
                                                "expectedVersion",
                                                card1.version(),
                                                "reason",
                                                "1月直线法计提折旧"),
                                        FinanceJournalPersonPostgresTest.request()));
        assertNotNull(depReceipt.journalId());

        // Non-empty reconciliation must return 0.00000000 difference on both cost and accumulated accounts!
        List<Difference> diffs =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () -> assets.reconcile("maker", book, year, LocalDate.of(2026, 1, 31)));
        assertEquals(2, diffs.size());
        for (Difference d : diffs) {
            assertEquals(0, d.difference().compareTo(BigDecimal.ZERO), () -> "Non-zero diff on " + d);
        }

        // 3. ESTIMATE change in 2026-02 -> POSTED (journalId == null)
        Card afterJan =
                FinanceJournalPersonPostgresTest.as(
                                "maker",
                                () -> assets.cards("maker", book, year, LocalDate.of(2026, 2, 5), null, 20))
                        .getFirst();
        Receipt estReceipt =
                advanceAssetPlan(
                        book,
                        year,
                        LocalDate.of(2026, 2, 10),
                        () ->
                                assets.command(
                                        "maker",
                                        book,
                                        year,
                                        LocalDate.of(2026, 2, 10),
                                        "estimate",
                                        UUID.randomUUID().toString(),
                                        map(
                                                "assetId",
                                                afterJan.id().toString(),
                                                "expectedVersion",
                                                afterJan.version(),
                                                "remainingMonths",
                                                10,
                                                "residual",
                                                "0.00",
                                                "assessmentId",
                                                assessOrig2.toString(),
                                                "reason",
                                                "未来使用寿命重估为剩余10个月"),
                                        FinanceJournalPersonPostgresTest.request()));
        assertEquals("POSTED", estReceipt.status());
        assertNull(estReceipt.journalId());

        // 4. DISPOSE & 5. REVERSE of DISPOSE in 2026-02
        Card beforeDispose =
                FinanceJournalPersonPostgresTest.as(
                                "maker",
                                () -> assets.cards("maker", book, year, LocalDate.of(2026, 2, 15), null, 20))
                        .getFirst();
        Receipt dispReceipt =
                advanceAssetPlan(
                        book,
                        year,
                        LocalDate.of(2026, 2, 18),
                        () ->
                                assets.command(
                                        "maker",
                                        book,
                                        year,
                                        LocalDate.of(2026, 2, 18),
                                        "dispose",
                                        UUID.randomUUID().toString(),
                                        map(
                                                "assetId",
                                                beforeDispose.id().toString(),
                                                "expectedVersion",
                                                beforeDispose.version(),
                                                "proceeds",
                                                "0.00",
                                                "proceedsAccount",
                                                null,
                                                "lossAccount",
                                                aLoss.toString(),
                                                "gainAccount",
                                                null,
                                                "approvalEvidence",
                                                dispOrig.toString(),
                                                "reason",
                                                "报废处置入账"),
                                        FinanceJournalPersonPostgresTest.request()));
        assertNotNull(dispReceipt.journalId());

        StoredPlan dispStored =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () -> assets.detail("maker", book, year, LocalDate.of(2026, 2, 19), dispReceipt.planId()));
        Receipt revReceipt =
                advanceAssetPlan(
                        book,
                        year,
                        LocalDate.of(2026, 2, 20),
                        () ->
                                assets.command(
                                        "maker",
                                        book,
                                        year,
                                        LocalDate.of(2026, 2, 20),
                                        "reverse",
                                        UUID.randomUUID().toString(),
                                        map(
                                                "originalPlan",
                                                dispReceipt.planId().toString(),
                                                "expectedPlanVersion",
                                                dispStored.version(),
                                                "reason",
                                                "冲销误处置计划"),
                                        FinanceJournalPersonPostgresTest.request()));
        assertNotNull(revReceipt.journalId());

        List<Difference> finalDiffs =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () -> assets.reconcile("maker", book, year, LocalDate.of(2026, 2, 25)));
        assertEquals(2, finalDiffs.size());
        for (Difference d : finalDiffs) {
            assertEquals(0, d.difference().compareTo(BigDecimal.ZERO));
        }
    }

    private static Receipt advanceAssetPlan(
            UUID book, UUID year, LocalDate date, java.util.function.Supplier<Receipt> submitCall) {
        Receipt sub = FinanceJournalPersonPostgresTest.as("maker", submitCall::get);
        assertEquals("SUBMITTED", sub.status());
        Receipt app =
                FinanceJournalPersonPostgresTest.as(
                        "reviewer",
                        () ->
                                assets.command(
                                        "reviewer",
                                        book,
                                        year,
                                        date,
                                        "approve",
                                        UUID.randomUUID().toString(),
                                        map(
                                                "planId",
                                                sub.planId().toString(),
                                                "expectedVersion",
                                                sub.version(),
                                                "reason",
                                                "独立批准资产计划"),
                                        FinanceJournalPersonPostgresTest.request()));
        assertEquals("APPROVED", app.status());
        Receipt post =
                FinanceJournalPersonPostgresTest.as(
                        "other",
                        () ->
                                assets.command(
                                        "other",
                                        book,
                                        year,
                                        date,
                                        "post",
                                        UUID.randomUUID().toString(),
                                        map(
                                                "planId",
                                                sub.planId().toString(),
                                                "expectedVersion",
                                                app.version(),
                                                "reason",
                                                "正式记账执行"),
                                        FinanceJournalPersonPostgresTest.request()));
        assertEquals("POSTED", post.status());
        return post;
    }

    static UUID uploadAs(String actor, FinanceJournalPersonPostgresTest.Case c, String kind) {
        var originals =
                new FinanceOriginals(
                        FinanceJournalPersonPostgresTest.db,
                        FinanceJournalPersonPostgresTest.modern,
                        FinanceJournalPersonPostgresTest.owners);
        byte[] bytes =
                ("Synthetic V4 " + kind + " " + UUID.randomUUID())
                        .getBytes(StandardCharsets.UTF_8);
        return FinanceJournalPersonPostgresTest.as(
                actor,
                () ->
                        FinancePostgresTest.tx.execute(
                                status -> {
                                    try {
                                        return id(
                                                originals
                                                        .upload(
                                                                actor,
                                                                c.f().book(),
                                                                c.f().year(),
                                                                kind + ".txt",
                                                                UUID.randomUUID().toString(),
                                                                new ByteArrayInputStream(bytes),
                                                                bytes.length)
                                                        .get("id"));
                                    } catch (Exception e) {
                                        throw new IllegalStateException(e);
                                    }
                                }));
    }

    @Test
    void payrollProducerRulePackCumulativeWithholdingAndVerifiedFacts() {
        var c = FinanceJournalPersonPostgresTest.fixture("CAS");
        FinanceJournalPersonPostgresTest.enable(c);
        UUID book = c.f().book(), year = c.f().year();
        var taxEnt = createApprovedTaxEntity(c);
        UUID entityId = taxEnt.get("id");

        UUID empOrig = uploadAs("maker", c, "emp-contract");
        UUID ruleSrcOrig = uploadAs("maker", c, "iit-rule-source");
        UUID ruleAppOrig = uploadAs("other", c, "iit-rule-approval");
        UUID attJanOrig = uploadAs("maker", c, "attendance-2026-01");
        UUID attFebOrig = uploadAs("maker", c, "attendance-2026-02");
        UUID payOrig = uploadAs("maker", c, "bank-payroll-slip");
        UUID whtOrig = uploadAs("maker", c, "iit-withhold-confirm");
        UUID decOrig = uploadAs("maker", c, "iit-filing-receipt");

        var emp =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        payroll.registerEmployee(
                                                "maker",
                                                book,
                                                entityId,
                                                2026,
                                                map(
                                                        "idempotencyKey",
                                                        UUID.randomUUID().toString(),
                                                        "employeeNumber",
                                                        "EMP-2026-001",
                                                        "identitySecretRef",
                                                        UUID.randomUUID().toString(),
                                                        "startsOn",
                                                        "2026-01-01",
                                                        "endsOn",
                                                        null,
                                                        "residency",
                                                        "RESIDENT",
                                                        "evidenceOriginalId",
                                                        empOrig.toString()),
                                                FinanceJournalPersonPostgresTest.request())));
        UUID employeeId = id(emp.get("employeeId"));
        UUID employmentRevId = id(emp.get("employmentRevisionId"));

        List<Map<String, Object>> brackets =
                List.of(
                        map("upper", "36000.00", "rate", "0.03", "quick", "0.00"),
                        map("upper", "144000.00", "rate", "0.10", "quick", "2520.00"),
                        map("upper", "300000.00", "rate", "0.20", "quick", "16920.00"),
                        map("upper", "420000.00", "rate", "0.25", "quick", "31920.00"),
                        map("upper", "660000.00", "rate", "0.30", "quick", "52920.00"),
                        map("upper", "960000.00", "rate", "0.35", "quick", "85920.00"),
                        map("upper", null, "rate", "0.45", "quick", "181920.00"));

        var ruleBody =
                map(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "version",
                        "CN-IIT-RESIDENT-WAGE-2026.1",
                        "effectiveFrom",
                        "2026-01-01",
                        "effectiveThrough",
                        "2026-12-31",
                        "monthlyBasic",
                        "5000.00",
                        "brackets",
                        brackets,
                        "sourceEvidenceId",
                        ruleSrcOrig.toString(),
                        "approvalEvidenceId",
                        ruleAppOrig.toString());

        // Self-approval by alias (same natural person as maker who uploaded ruleSrcOrig) must fail
        var ruleSod =
                assertThrows(
                        FinanceException.class,
                        () ->
                                FinanceJournalPersonPostgresTest.as(
                                        "alias",
                                        () ->
                                                payroll.registerRulePack(
                                                        "alias",
                                                        book,
                                                        entityId,
                                                        2026,
                                                        ruleBody,
                                                        FinanceJournalPersonPostgresTest.request())));
        assertEquals("SELF_APPROVAL_FORBIDDEN", ruleSod.code);

        var rulePack =
                FinanceJournalPersonPostgresTest.as(
                        "reviewer",
                        () ->
                                obj(
                                        payroll.registerRulePack(
                                                "reviewer",
                                                book,
                                                entityId,
                                                2026,
                                                ruleBody,
                                                FinanceJournalPersonPostgresTest.request())));
        UUID rulePackId = id(rulePack.get("id"));

        // Month 1 (Jan 2026): gross 20,000, empSocial 2,000, employerSocial 4,000, special 1,000
        // Taxable = 20000 - 5000 - 2000 - 1000 = 12000 -> 3% = 360.00; netPay = 17640.00
        UUID batchJan = UUID.randomUUID();
        Draft draftJan =
                new Draft(
                        batchJan,
                        book,
                        LocalDate.of(2026, 1, 31),
                        Kind.NORMAL,
                        null,
                        "2026年1月正常发薪",
                        rulePackId,
                        List.of(
                                new Line(
                                        employeeId,
                                        employmentRevId,
                                        "20000.00",
                                        "0.00",
                                        "2000.00",
                                        "4000.00",
                                        "1000.00",
                                        "0.00",
                                        "0.00",
                                        "0.00",
                                        "0.00",
                                        1,
                                        DeductionMode.NORMAL,
                                        null,
                                        attJanOrig,
                                        LocalDate.of(2026, 1, 1),
                                        LocalDate.of(2026, 1, 31))));

        PayrollView createdJan =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                payroll.create(
                                        "maker",
                                        book,
                                        entityId,
                                        2026,
                                        new PayrollController.CreateRequest(
                                                new Command(UUID.randomUUID(), "test", 0), draftJan),
                                        FinanceJournalPersonPostgresTest.request()));
        assertEquals(State.DRAFT, createdJan.state());

        PayrollView calcJan =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                payroll.act(
                                        "maker",
                                        book,
                                        entityId,
                                        2026,
                                        batchJan,
                                        new PayrollController.ActionRequest(
                                                new Command(UUID.randomUUID(), "test", createdJan.version()),
                                                Action.CALCULATE),
                                        FinanceJournalPersonPostgresTest.request()));
        assertEquals(State.CALCULATED, calcJan.state());
        assertEquals("360.00", calcJan.computed().getFirst().tax().currentWithholding());
        assertEquals("17640.00", calcJan.computed().getFirst().netPay());

        // Self-review by maker (OWNER) must fail with SELF_APPROVAL_FORBIDDEN
        var selfRev =
                assertThrows(
                        FinanceException.class,
                        () ->
                                FinanceJournalPersonPostgresTest.as(
                                        "maker",
                                        () ->
                                                payroll.act(
                                                        "maker",
                                                        book,
                                                        entityId,
                                                        2026,
                                                        batchJan,
                                                        new PayrollController.ActionRequest(
                                                                new Command(UUID.randomUUID(), "test", calcJan.version()),
                                                                Action.REVIEW),
                                                        FinanceJournalPersonPostgresTest.request())));
        assertEquals("SELF_APPROVAL_FORBIDDEN", selfRev.code);

        PayrollView revJan =
                FinanceJournalPersonPostgresTest.as(
                        "reviewer",
                        () ->
                                payroll.act(
                                        "reviewer",
                                        book,
                                        entityId,
                                        2026,
                                        batchJan,
                                        new PayrollController.ActionRequest(
                                                new Command(UUID.randomUUID(), "test", calcJan.version()),
                                                Action.REVIEW),
                                        FinanceJournalPersonPostgresTest.request()));
        assertEquals(State.REVIEWED, revJan.state());

        // Alias shares person with maker (preparer) -> must also fail with SELF_APPROVAL_FORBIDDEN!
        var aliasApp =
                assertThrows(
                        FinanceException.class,
                        () ->
                                FinanceJournalPersonPostgresTest.as(
                                        "alias",
                                        () ->
                                                payroll.act(
                                                        "alias",
                                                        book,
                                                        entityId,
                                                        2026,
                                                        batchJan,
                                                        new PayrollController.ActionRequest(
                                                                new Command(UUID.randomUUID(), "test", revJan.version()),
                                                                Action.APPROVE),
                                                        FinanceJournalPersonPostgresTest.request())));
        assertEquals("SELF_APPROVAL_FORBIDDEN", aliasApp.code);

        PayrollView appJan =
                FinanceJournalPersonPostgresTest.as(
                        "other",
                        () ->
                                payroll.act(
                                        "other",
                                        book,
                                        entityId,
                                        2026,
                                        batchJan,
                                        new PayrollController.ActionRequest(
                                                new Command(UUID.randomUUID(), "test", revJan.version()),
                                                Action.APPROVE),
                                        FinanceJournalPersonPostgresTest.request()));
        assertEquals(State.APPROVED, appJan.state());

        PayrollView lockJan =
                FinanceJournalPersonPostgresTest.as(
                        "other",
                        () ->
                                payroll.act(
                                        "other",
                                        book,
                                        entityId,
                                        2026,
                                        batchJan,
                                        new PayrollController.ActionRequest(
                                                new Command(UUID.randomUUID(), "test", appJan.version()),
                                                Action.LOCK),
                                        FinanceJournalPersonPostgresTest.request()));
        assertEquals(State.LOCKED, lockJan.state());

        // Post a real NORMAL accrual journal (24000.00 = 20000 gross + 4000 employerSocial) & attach all 4 facts
        UUID accrualJournalId = postNormalAccrualJournal(c, "24000.00");
        PayrollView f1 =
                FinanceJournalPersonPostgresTest.as(
                        "other",
                        () ->
                                payroll.fact(
                                        "other",
                                        book,
                                        entityId,
                                        2026,
                                        batchJan,
                                        new PayrollController.FactRequest(
                                                new Command(UUID.randomUUID(), "test", lockJan.version()),
                                                FactKind.ACCRUAL_POSTED,
                                                accrualJournalId),
                                        FinanceJournalPersonPostgresTest.request()));
        UUID payEvId = createAndApprovePayrollFact(book, entityId, 2026, batchJan, "PAYMENT_VERIFIED", payOrig, "17640.00");
        UUID whtEvId = createAndApprovePayrollFact(book, entityId, 2026, batchJan, "WITHHOLDING_CONFIRMED", whtOrig, "360.00");
        UUID decEvId = createAndApprovePayrollFact(book, entityId, 2026, batchJan, "DECLARATION_ACCEPTED", decOrig, "360.00");

        PayrollView f2 = attachPayrollFact(book, entityId, 2026, batchJan, f1.version(), FactKind.PAYMENT_VERIFIED, payEvId);
        PayrollView f3 = attachPayrollFact(book, entityId, 2026, batchJan, f2.version(), FactKind.WITHHOLDING_CONFIRMED, whtEvId);
        PayrollView f4 = attachPayrollFact(book, entityId, 2026, batchJan, f3.version(), FactKind.DECLARATION_ACCEPTED, decEvId);
        assertEquals(4, f4.facts().size());

        // Month 2 (Feb 2026): gross 35,000, empSocial 2,000, employerSocial 4,000, special 1,000
        // Cumulative taxable = 12,000 + (35,000 - 5,000 - 2,000 - 1,000) = 39,000 -> 10% bracket:
        // cumulativeTax = 39000 * 0.10 - 2520 = 1380.00; currentWithholding = 1380.00 - 360.00 = 1020.00!
        UUID batchFeb = UUID.randomUUID();
        Draft draftFeb =
                new Draft(
                        batchFeb,
                        book,
                        LocalDate.of(2026, 2, 28),
                        Kind.NORMAL,
                        null,
                        "2026年2月累计预扣跨级距发薪",
                        rulePackId,
                        List.of(
                                new Line(
                                        employeeId,
                                        employmentRevId,
                                        "35000.00",
                                        "0.00",
                                        "2000.00",
                                        "4000.00",
                                        "1000.00",
                                        "0.00",
                                        "0.00",
                                        "0.00",
                                        "0.00",
                                        2,
                                        DeductionMode.NORMAL,
                                        null,
                                        attFebOrig,
                                        LocalDate.of(2026, 2, 1),
                                        LocalDate.of(2026, 2, 28))));
        PayrollView createdFeb =
                FinanceJournalPersonPostgresTest.as(
                        "plain",
                        () ->
                                payroll.create(
                                        "plain",
                                        book,
                                        entityId,
                                        2026,
                                        new PayrollController.CreateRequest(
                                                new Command(UUID.randomUUID(), "test", 0), draftFeb),
                                        FinanceJournalPersonPostgresTest.request()));
        PayrollView calcFeb =
                FinanceJournalPersonPostgresTest.as(
                        "plain",
                        () ->
                                payroll.act(
                                        "plain",
                                        book,
                                        entityId,
                                        2026,
                                        batchFeb,
                                        new PayrollController.ActionRequest(
                                                new Command(UUID.randomUUID(), "test", createdFeb.version()),
                                                Action.CALCULATE),
                                        FinanceJournalPersonPostgresTest.request()));
        assertEquals("1380.00", calcFeb.computed().getFirst().tax().cumulativeTax());
        assertEquals("1020.00", calcFeb.computed().getFirst().tax().currentWithholding());

        var setupView = obj(FinanceJournalPersonPostgresTest.as("maker", () -> payroll.listSetup("maker", book, entityId, 2026)));
        assertEquals(2, list(setupView.get("batches")).size());
    }

    private static UUID createAndApprovePayrollFact(
            UUID book, UUID entity, int year, UUID batchId, String kind, UUID origId, String amount) {
        var created =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        payroll.registerFactEvidence(
                                                "maker",
                                                book,
                                                entity,
                                                year,
                                                map(
                                                        "idempotencyKey",
                                                        UUID.randomUUID().toString(),
                                                        "batchId",
                                                        batchId.toString(),
                                                        "kind",
                                                        kind,
                                                        "originalId",
                                                        origId.toString(),
                                                        "amount",
                                                        amount,
                                                        "reason",
                                                        "登记人工核验回执"),
                                                FinanceJournalPersonPostgresTest.request())));
        UUID evId = id(created.get("id"));
        FinanceJournalPersonPostgresTest.as(
                "reviewer",
                () ->
                        payroll.approveFactEvidence(
                                "reviewer",
                                book,
                                entity,
                                year,
                                evId,
                                map("idempotencyKey", UUID.randomUUID().toString(), "expectedVersion", "1", "reason", "独立审签回执"),
                                FinanceJournalPersonPostgresTest.request()));
        return evId;
    }

    private static PayrollView attachPayrollFact(
            UUID book, UUID entity, int year, UUID batchId, long expectedVer, FactKind kind, UUID ref) {
        return FinanceJournalPersonPostgresTest.as(
                "other",
                () ->
                        payroll.fact(
                                "other",
                                book,
                                entity,
                                year,
                                batchId,
                                new PayrollController.FactRequest(
                                        new Command(UUID.randomUUID(), "test", expectedVer), kind, ref),
                                FinanceJournalPersonPostgresTest.request()));
    }

    static UUID postNormalAccrualJournal(FinanceJournalPersonPostgresTest.Case c, String amount) {
        UUID jid =
                (UUID)
                        FinanceJournalPersonPostgresTest.legacy(
                                        "maker",
                                        c,
                                        "journal",
                                        null,
                                        FinanceJournalPersonPostgresTest.journalInput(c, amount))
                                .get("id");
        FinanceJournalPersonPostgresTest.submit(c, jid);
        var approved = FinanceJournalPersonPostgresTest.approve(c, jid, java.time.Duration.ofMinutes(5));
        var post = FinanceJournalPersonPostgresTest.action(c, jid);
        post.put("approvalId", ((Map<?, ?>) approved.get("currentApproval")).get("id").toString());
        FinanceJournalPersonPostgresTest.cmd("other", c, "person-post", jid, post);
        return jid;
    }
}

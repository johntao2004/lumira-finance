package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.finance.domain.ReportSourceEngine.ContraType;
import com.lumira.finance.domain.ReportSourceEngine.Kind;
import com.lumira.finance.domain.ReportSourceEngine.Semantic;
import com.lumira.finance.payroll.PayrollCalculation.DeductionMode;
import com.lumira.finance.payroll.PayrollController;
import com.lumira.finance.payroll.PayrollService;
import com.lumira.finance.payroll.PayrollService.Action;
import com.lumira.finance.payroll.PayrollService.Command;
import com.lumira.finance.payroll.PayrollService.Draft;
import com.lumira.finance.payroll.PayrollService.FactKind;
import com.lumira.finance.payroll.PayrollService.Line;
import com.lumira.finance.payroll.PayrollService.State;
import com.lumira.finance.payroll.PayrollView;
import com.lumira.finance.tax.TaxObligationModel;
import com.lumira.platform.common.AuthorizationContextProjectionPort;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;

/**
 * End-to-end PostgreSQL 18 integration test for V25 (0.23.0):
 * 1. AP02/EV03/TR01/TR02 (FinancePayableSettlements): EV03 settlement basis -> independent approval ->
 *    partial AP02 payment order -> native AP_PAYMENT journal -> upstream AP01 reversal blocked by
 *    AP_DEPENDENCY_PRESENT -> AP02 REVERSAL order -> native AP_PAYMENT_REVERSAL journal -> upstream
 *    AP01 reversal unblocked.
 * 2. TAX02/TAX03/TAX04 (FinanceTaxObligations worksheets): VAT_SMALL, VAT_GENERAL_SURCHARGE, and
 *    CIT_ADJUSTMENT_AND_PREPAY calculation worksheets + natural-person SoD + obligation build binding.
 * 3. PAY02 (FinancePayroll restatement plans): cross-batch restatement plan preview on locked batch
 *    with accepted declaration -> requiresPriorAmendment=true -> natural-person SoD -> forced RLS check.
 * 4. RPT02/RPT05 (FinanceReportDrafts CONTRA mapping): accumulated depreciation contra mapping nets
 *    against fixed asset row with MATCHED_CONTRA trace.
 */
class FinanceV4Runtime023PostgresTest {
    static FinanceStore db;
    static FinanceCounterparties counterparties;
    static FinancePayablesAp01 payablesAp01;
    static FinancePayableSettlements payableSettlements;
    static FinanceTaxEntities taxEntities;
    static FinanceTaxObligations taxObligations;
    static FinancePayroll payroll;
    static FinanceReportDrafts reportDrafts;

    static final AuthorizationContextProjectionPort AUTH =
            p ->
                    Optional.of(
                            new AuthorizationContextProjectionPort.Snapshot(
                                    FinanceV4Runtime022PostgresTest.ALL_WITH_EXPORT,
                                    FinanceV4Runtime022PostgresTest.ALL_WITH_EXPORT,
                                    false,
                                    null,
                                    0));

    @BeforeAll
    static void setup() throws Exception {
        FinanceJournalPersonPostgresTest.setup(25);
        db = FinanceJournalPersonPostgresTest.db;
        FinanceEvidenceUsagePostgresTest.db = db;
        FinanceCounterpartyPostgresTest.db = db;
        FinanceTaxEntityPostgresTest.db = db;

        var txMgr = FinancePostgresTest.tx.getTransactionManager();
        var modern = FinanceJournalPersonPostgresTest.modern;
        var gate = FinanceJournalPersonPostgresTest.gate;

        FinanceEvidenceUsagePostgresTest.documents =
                new FinanceEvidenceDocuments(db, modern, gate, txMgr, AUTH);
        FinanceEvidenceUsagePostgresTest.allocations =
                new FinanceExpenseAllocations(db, modern, gate, txMgr, AUTH);

        counterparties = new FinanceCounterparties(db, modern, gate, txMgr, AUTH);
        FinanceCounterpartyPostgresTest.service = counterparties;

        payablesAp01 = new FinancePayablesAp01(db, modern, gate, txMgr, AUTH);
        FinancePayablesAp01PostgresTest.db = db;
        FinancePayablesAp01PostgresTest.service = payablesAp01;

        payableSettlements = new FinancePayableSettlements(db, modern, gate, txMgr, AUTH);

        taxEntities = new FinanceTaxEntities(db, modern, gate, txMgr, AUTH);
        FinanceTaxEntityPostgresTest.service = taxEntities;

        taxObligations = new FinanceTaxObligations(db, modern, gate, txMgr, AUTH);
        payroll = new FinancePayroll(db, modern, gate, txMgr, AUTH, Optional.empty());
        reportDrafts = new FinanceReportDrafts(db, modern, gate, txMgr, AUTH);
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

    static UUID id(Object x) {
        return UUID.fromString(x.toString());
    }

    @Test
    void ap02PayableSettlementLifecycleNativeJournalUpstreamReversalGuardAndReversal() {
        var c = FinancePayablesAp01PostgresTest.fixture("CAS");
        UUID book = c.book(), year = c.year();
        UUID cashAcc = c.expense().base().f().cash();

        // 1. Create, submit, approve, and post an AP01 payable confirmation (600.00 CNY)
        var apCreated = FinancePayablesAp01PostgresTest.create(c);
        var apSubmitted = FinancePayablesAp01PostgresTest.step("maker", c, "submit", apCreated);
        var apApproved = FinancePayablesAp01PostgresTest.step("reviewer", c, "approve", apSubmitted);
        var apPosted = FinancePayablesAp01PostgresTest.step("other", c, "post", apApproved);
        UUID openItemId = id(obj(apPosted.get("openItem")).get("id"));

        Map<String, Object> ov0 =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        payableSettlements.overview(
                                                "maker", book, year, LocalDate.of(2026, 1, 31))));
        assertEquals("NOT_VERIFIED", ov0.get("bankChannel"));
        var oi0 =
                list(ov0.get("openItems")).stream()
                        .filter(i -> openItemId.toString().equals(i.get("id")))
                        .findFirst()
                        .orElseThrow();
        assertEquals("600.00", oi0.get("amount"));
        assertEquals("0.00", oi0.get("settledAmount"));
        assertEquals("600.00", oi0.get("availableAmount"));

        // 2. Propose EV03 SETTLEMENT_EVIDENCE basis (500.00 CNY) and verify natural-person SoD
        UUID ev03Orig =
                FinanceEvidenceUsagePostgresTest.upload(
                        c.expense().base(), "ev03-settlement-agreement");
        UUID bankSlipOrig =
                FinanceEvidenceUsagePostgresTest.upload(
                        c.expense().base(), "tr02-offline-bank-receipt");
        UUID revSlipOrig =
                FinanceEvidenceUsagePostgresTest.upload(
                        c.expense().base(), "tr02-offline-bank-reversal-slip");

        Map<String, Object> basis =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        payableSettlements.createBasis(
                                                "maker",
                                                book,
                                                year,
                                                new FinancePayableSettlements.CreateSettlementBasisInput(
                                                        ev03Orig,
                                                        c.supplier(),
                                                        "招商银行上海分行-供应商基本户(离线台账标签)",
                                                        "500.00",
                                                        "2026年1月供应商分期结算协议核定额",
                                                        "ev03-create-" + UUID.randomUUID()),
                                                FinanceJournalPersonPostgresTest.request())));
        UUID basisId = id(basis.get("id"));
        assertEquals("PENDING", basis.get("status"));
        assertEquals("NOT_VERIFIED", basis.get("bankChannel"));

        var selfBasisErr =
                assertThrows(
                        FinanceException.class,
                        () ->
                                FinanceJournalPersonPostgresTest.as(
                                        "alias",
                                        () ->
                                                payableSettlements.approveBasis(
                                                        "alias",
                                                        book,
                                                        year,
                                                        basisId,
                                                        new FinancePayableSettlements.ApproveSettlementBasisInput(
                                                                1L,
                                                                "同自然人自审应阻断",
                                                                "ev03-self-" + UUID.randomUUID()),
                                                        FinanceJournalPersonPostgresTest.request())));
        assertEquals("SELF_APPROVAL_FORBIDDEN", selfBasisErr.code);

        Map<String, Object> approvedBasis =
                FinanceJournalPersonPostgresTest.as(
                        "reviewer",
                        () ->
                                obj(
                                        payableSettlements.approveBasis(
                                                "reviewer",
                                                book,
                                                year,
                                                basisId,
                                                new FinancePayableSettlements.ApproveSettlementBasisInput(
                                                        1L,
                                                        "独立自然人审签结算依据配额",
                                                        "ev03-app-" + UUID.randomUUID()),
                                                FinanceJournalPersonPostgresTest.request())));
        assertEquals("APPROVED", approvedBasis.get("status"));
        assertEquals("500.00", approvedBasis.get("availableAmount"));

        // 3. Create partial AP02 PAYMENT order (300.00 CNY) -> submit -> approve -> post
        Map<String, Object> payOrder =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        payableSettlements.createOrder(
                                                "maker",
                                                book,
                                                year,
                                                new FinancePayableSettlements.CreateSettlementOrderInput(
                                                        "PAYMENT",
                                                        openItemId,
                                                        basisId,
                                                        cashAcc,
                                                        bankSlipOrig,
                                                        "BANK-SLIP-20260120-01",
                                                        LocalDate.of(2026, 1, 20),
                                                        "300.00",
                                                        "应付账款部分清偿300元",
                                                        null,
                                                        "依据已批准结算协议支付首期款",
                                                        "ap02-create-" + UUID.randomUUID()),
                                                FinanceJournalPersonPostgresTest.request())));
        UUID payOrderId = id(payOrder.get("id"));
        assertEquals("DRAFT", payOrder.get("state"));

        FinanceJournalPersonPostgresTest.as(
                "maker",
                () ->
                        payableSettlements.transitionOrder(
                                "maker",
                                book,
                                year,
                                payOrderId,
                                "submit",
                                new FinancePayableSettlements.TransitionSettlementOrderInput(
                                        1L, "提交结算单", "ap02-sub-" + UUID.randomUUID()),
                                FinanceJournalPersonPostgresTest.request()));

        var selfOrderErr =
                assertThrows(
                        FinanceException.class,
                        () ->
                                FinanceJournalPersonPostgresTest.as(
                                        "alias",
                                        () ->
                                                payableSettlements.transitionOrder(
                                                        "alias",
                                                        book,
                                                        year,
                                                        payOrderId,
                                                        "approve",
                                                        new FinancePayableSettlements.TransitionSettlementOrderInput(
                                                                2L,
                                                                "同自然人自审应阻断",
                                                                "ap02-self-" + UUID.randomUUID()),
                                                        FinanceJournalPersonPostgresTest.request())));
        assertEquals("SELF_APPROVAL_FORBIDDEN", selfOrderErr.code);

        FinanceJournalPersonPostgresTest.as(
                "reviewer",
                () ->
                        payableSettlements.transitionOrder(
                                "reviewer",
                                book,
                                year,
                                payOrderId,
                                "approve",
                                new FinancePayableSettlements.TransitionSettlementOrderInput(
                                        2L, "独立审签结算指令", "ap02-app-" + UUID.randomUUID()),
                                FinanceJournalPersonPostgresTest.request()));

        Map<String, Object> postedPayOrder =
                FinanceJournalPersonPostgresTest.as(
                        "other",
                        () ->
                                obj(
                                        payableSettlements.transitionOrder(
                                                "other",
                                                book,
                                                year,
                                                payOrderId,
                                                "post",
                                                new FinancePayableSettlements.TransitionSettlementOrderInput(
                                                        3L,
                                                        "过账生成原生AP_PAYMENT凭证",
                                                        "ap02-post-" + UUID.randomUUID()),
                                                FinanceJournalPersonPostgresTest.request())));
        assertEquals("POSTED", postedPayOrder.get("state"));
        assertNotNull(postedPayOrder.get("journalId"));
        UUID payJournalId = id(postedPayOrder.get("journalId"));
        var jRow =
                db.one(
                        "SELECT type,status FROM plugin_finance_journal WHERE book_id=? AND year_id=? AND id=?",
                        book,
                        year,
                        payJournalId);
        assertEquals("AP_PAYMENT", jRow.get("type"));
        assertEquals("POSTED", jRow.get("status"));

        Map<String, Object> ovAfterPay =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        payableSettlements.overview(
                                                "maker", book, year, LocalDate.of(2026, 1, 31))));
        var oiAfterPay =
                list(ovAfterPay.get("openItems")).stream()
                        .filter(i -> openItemId.toString().equals(i.get("id")))
                        .findFirst()
                        .orElseThrow();
        assertEquals("300.00", oiAfterPay.get("settledAmount"));
        assertEquals("300.00", oiAfterPay.get("availableAmount"));

        // 4. Attempting upstream AP01 reversal while open item has active settlement MUST fail with AP_DEPENDENCY_PRESENT!
        var revDraft = FinancePayablesAp01PostgresTest.reverse(c, apPosted);
        var revSubmitted = FinancePayablesAp01PostgresTest.step("maker", c, "submit", revDraft);
        var revApproved = FinancePayablesAp01PostgresTest.step("reviewer", c, "approve", revSubmitted);
        var depErr =
                assertThrows(
                        FinanceException.class,
                        () -> FinancePayablesAp01PostgresTest.step("other", c, "post", revApproved));
        assertEquals("AP_DEPENDENCY_PRESENT", depErr.code);

        // 5. Create & post AP02 REVERSAL order (-300.00 CNY) -> releases open item & EV03 basis
        Map<String, Object> revOrder =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        payableSettlements.createOrder(
                                                "maker",
                                                book,
                                                year,
                                                new FinancePayableSettlements.CreateSettlementOrderInput(
                                                        "REVERSAL",
                                                        openItemId,
                                                        basisId,
                                                        cashAcc,
                                                        revSlipOrig,
                                                        "BANK-SLIP-20260122-REV",
                                                        LocalDate.of(2026, 1, 22),
                                                        "300.00",
                                                        "应付清偿退回冲销300元",
                                                        payOrderId,
                                                        "退回原应付清偿款项并恢复未清项",
                                                        "ap02-rev-create-" + UUID.randomUUID()),
                                                FinanceJournalPersonPostgresTest.request())));
        UUID revOrderId = id(revOrder.get("id"));

        FinanceJournalPersonPostgresTest.as(
                "maker",
                () ->
                        payableSettlements.transitionOrder(
                                "maker",
                                book,
                                year,
                                revOrderId,
                                "submit",
                                new FinancePayableSettlements.TransitionSettlementOrderInput(
                                        1L, "提交结算冲回单", "ap02-rev-sub-" + UUID.randomUUID()),
                                FinanceJournalPersonPostgresTest.request()));
        FinanceJournalPersonPostgresTest.as(
                "reviewer",
                () ->
                        payableSettlements.transitionOrder(
                                "reviewer",
                                book,
                                year,
                                revOrderId,
                                "approve",
                                new FinancePayableSettlements.TransitionSettlementOrderInput(
                                        2L, "批准结算冲回单", "ap02-rev-app-" + UUID.randomUUID()),
                                FinanceJournalPersonPostgresTest.request()));
        Map<String, Object> postedRevOrder =
                FinanceJournalPersonPostgresTest.as(
                        "other",
                        () ->
                                obj(
                                        payableSettlements.transitionOrder(
                                                "other",
                                                book,
                                                year,
                                                revOrderId,
                                                "post",
                                                new FinancePayableSettlements.TransitionSettlementOrderInput(
                                                        3L,
                                                        "过账生成原生AP_PAYMENT_REVERSAL凭证",
                                                        "ap02-rev-post-" + UUID.randomUUID()),
                                                FinanceJournalPersonPostgresTest.request())));
        assertEquals("POSTED", postedRevOrder.get("state"));
        var revJRow =
                db.one(
                        "SELECT type,status,reversal_of FROM plugin_finance_journal WHERE book_id=? AND year_id=? AND id=?",
                        book,
                        year,
                        id(postedRevOrder.get("journalId")));
        assertEquals("AP_PAYMENT_REVERSAL", revJRow.get("type"));
        assertEquals("POSTED", revJRow.get("status"));
        assertEquals(payJournalId, id(revJRow.get("reversal_of")));

        // Verify open item is now completely unsettled (settledAmount = 0.00, availableAmount = 600.00)
        Map<String, Object> ovAfterRev =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        payableSettlements.overview(
                                                "maker", book, year, LocalDate.of(2026, 1, 31))));
        var oiAfterRev =
                list(ovAfterRev.get("openItems")).stream()
                        .filter(i -> openItemId.toString().equals(i.get("id")))
                        .findFirst()
                        .orElseThrow();
        assertEquals("0.00", oiAfterRev.get("settledAmount"));
        assertEquals("600.00", oiAfterRev.get("availableAmount"));

        // And now upstream AP01 reversal succeeds!
        var apRevPosted = FinancePayablesAp01PostgresTest.step("other", c, "post", revApproved);
        FinancePayablesAp01PostgresTest.assertReceiptUsesActualTuple(c, apRevPosted);
    }

    @Test
    void taxWorksheetsVatSmallGeneralSurchargeCitAndObligationBinding() {
        var c = FinanceJournalPersonPostgresTest.fixture("CAS");
        FinanceJournalPersonPostgresTest.enable(c);
        UUID book = c.f().book(), year = c.f().year();
        var taxEnt = FinanceV4ProducersPostgresTest.createApprovedTaxEntity(c);
        UUID taxEntityId = taxEnt.get("id");
        UUID wsOrig = FinanceV4ProducersPostgresTest.uploadAs("maker", c, "tax-ws-basis");

        // Bind book to taxEntity first
        FinanceJournalPersonPostgresTest.as(
                "maker",
                () ->
                        taxObligations.proposeBinding(
                                "maker",
                                book,
                                new FinanceTaxObligations.BookBindingInput(
                                        taxEntityId,
                                        "STATUTORY_SOURCE",
                                        "TAX-MAP-2026.1",
                                        LocalDate.of(2026, 1, 1),
                                        "bind-ws-" + UUID.randomUUID()),
                                FinanceJournalPersonPostgresTest.request()));
        FinanceJournalPersonPostgresTest.as(
                "reviewer",
                () ->
                        taxObligations.approveBinding(
                                "reviewer",
                                book,
                                taxEntityId,
                                new FinanceTaxObligations.ApproveBindingInput(
                                        1L, "bind-ws-app-" + UUID.randomUUID()),
                                FinanceJournalPersonPostgresTest.request()));

        // 1. Create VAT_GENERAL_SURCHARGE worksheet
        Map<String, Object> vatGenWs =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        taxObligations.createWorksheet(
                                                "maker",
                                                book,
                                                new FinanceTaxObligations.CreateWorksheetInput(
                                                        year,
                                                        taxEntityId,
                                                        "WS-VAT-GEN-202601",
                                                        "VAT_GENERAL_SURCHARGE",
                                                        LocalDate.of(2026, 1, 1),
                                                        LocalDate.of(2026, 1, 31),
                                                        "CN-VAT-GEN-2026.1",
                                                        wsOrig,
                                                        map(
                                                                "outputTax",
                                                                "1300.00",
                                                                "inputTax",
                                                                "500.00",
                                                                "priorCredit",
                                                                "100.00",
                                                                "inputTaxTransferOut",
                                                                "50.00",
                                                                "cityTaxRate",
                                                                "0.07",
                                                                "smallMicroReliefAttested",
                                                                false),
                                                        "2026年1月一般纳税人增值税及附加底稿",
                                                        "ws-gen-" + UUID.randomUUID()),
                                                FinanceJournalPersonPostgresTest.request())));
        UUID vatGenWsId = id(vatGenWs.get("id"));
        var calcOut = obj(vatGenWs.get("outputs"));
        assertEquals("550.00", calcOut.get("deductibleInputTotal"));
        assertEquals("750.00", calcOut.get("vatPayable"));
        assertEquals("90.00", calcOut.get("totalSurchargePayable"));

        // Self-approval via alias must fail
        var selfWsErr =
                assertThrows(
                        FinanceException.class,
                        () ->
                                FinanceJournalPersonPostgresTest.as(
                                        "alias",
                                        () ->
                                                taxObligations.approveWorksheet(
                                                        "alias",
                                                        book,
                                                        vatGenWsId,
                                                        new FinanceTaxObligations.ApproveWorksheetInput(
                                                                year,
                                                                1L,
                                                                "同自然人自审应阻断",
                                                                "ws-self-" + UUID.randomUUID()),
                                                        FinanceJournalPersonPostgresTest.request())));
        assertEquals("SELF_APPROVAL_FORBIDDEN", selfWsErr.code);

        Map<String, Object> approvedWs =
                FinanceJournalPersonPostgresTest.as(
                        "reviewer",
                        () ->
                                obj(
                                        taxObligations.approveWorksheet(
                                                "reviewer",
                                                book,
                                                vatGenWsId,
                                                new FinanceTaxObligations.ApproveWorksheetInput(
                                                        year,
                                                        1L,
                                                        "独立审签增值税及附加计算底稿",
                                                        "ws-app-" + UUID.randomUUID()),
                                                FinanceJournalPersonPostgresTest.request())));
        assertEquals("APPROVED", approvedWs.get("status"));
        String wsDigest = approvedWs.get("worksheetDigest").toString();

        // 2. Create CIT_ADJUSTMENT_AND_PREPAY worksheet
        Map<String, Object> citWs =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        taxObligations.createWorksheet(
                                                "maker",
                                                book,
                                                new FinanceTaxObligations.CreateWorksheetInput(
                                                        year,
                                                        taxEntityId,
                                                        "WS-CIT-2026Q1",
                                                        "CIT_ADJUSTMENT_AND_PREPAY",
                                                        LocalDate.of(2026, 1, 1),
                                                        LocalDate.of(2026, 3, 31),
                                                        "CN-CIT-2026.1",
                                                        wsOrig,
                                                        map(
                                                                "revenue",
                                                                "2000000.00",
                                                                "accountingProfit",
                                                                "500000.00",
                                                                "wageTotal",
                                                                "400000.00",
                                                                "entertainmentExpense",
                                                                "20000.00",
                                                                "welfareExpense",
                                                                "50000.00",
                                                                "unionExpense",
                                                                "5000.00",
                                                                "educationExpense",
                                                                "20000.00",
                                                                "priorEducationCarry",
                                                                "0.00",
                                                                "advertisingExpense",
                                                                "100000.00",
                                                                "advertisingCapRate",
                                                                "0.15",
                                                                "priorAdvertisingCarry",
                                                                "0.00",
                                                                "donationExpense",
                                                                "10000.00",
                                                                "priorDonationCarry",
                                                                "0.00",
                                                                "priorLossCarry",
                                                                "50000.00",
                                                                "smallMicroQualifiedAttested",
                                                                false),
                                                        "2026年一季度企业所得税纳税调整及预缴底稿",
                                                        "ws-cit-" + UUID.randomUUID()),
                                                FinanceJournalPersonPostgresTest.request())));
        var citOut = obj(citWs.get("outputs"));
        assertEquals("10000.00", citOut.get("entertainmentAddBack"));
        assertEquals("460000.00", citOut.get("taxableIncome"));
        assertEquals("115000.00", citOut.get("citPayable"));

        // 3. Build TAX05 obligation bound to the approved worksheetDigest
        Map<String, Object> built =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        taxObligations.build(
                                                "maker",
                                                book,
                                                new FinanceTaxObligations.BuildObligationInput(
                                                        taxEntityId,
                                                        "CN_STA",
                                                        "VAT_GENERAL",
                                                        TaxObligationModel.Role.SELF,
                                                        LocalDate.of(2026, 1, 1),
                                                        LocalDate.of(2026, 1, 31),
                                                        "PERIODIC",
                                                        "CN_VAT_MONTHLY",
                                                        TaxObligationModel.Kind.INITIAL,
                                                        null,
                                                        UUID.randomUUID(),
                                                        List.of(wsOrig),
                                                        List.of(),
                                                        wsDigest,
                                                        List.of(
                                                                new FinanceTaxObligations.SourceRefInput(
                                                                        book, year)),
                                                        "绑定已批准增值税底稿摘要生成申报义务",
                                                        "ob-ws-" + UUID.randomUUID()),
                                                FinanceJournalPersonPostgresTest.request())));
        assertNotNull(obj(built.get("declaration")).get("dataDigest"));
    }

    @Test
    void payrollRestatementPlanPreviewOnLockedBatchAndRlsIsolation() {
        var c = FinanceJournalPersonPostgresTest.fixture("CAS");
        FinanceJournalPersonPostgresTest.enable(c);
        UUID book = c.f().book();
        var taxEnt = FinanceV4ProducersPostgresTest.createApprovedTaxEntity(c);
        UUID entityId = taxEnt.get("id");

        UUID empOrig = FinanceV4ProducersPostgresTest.uploadAs("maker", c, "emp-restatement");
        UUID ruleSrcOrig = FinanceV4ProducersPostgresTest.uploadAs("maker", c, "rule-src-rest");
        UUID ruleAppOrig = FinanceV4ProducersPostgresTest.uploadAs("other", c, "rule-app-rest");
        UUID attOrig = FinanceV4ProducersPostgresTest.uploadAs("maker", c, "att-rest-202601");
        UUID decOrig = FinanceV4ProducersPostgresTest.uploadAs("maker", c, "dec-rest-202601");

        Map<String, Object> emp =
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
                                                        "EMP-REST-001",
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
        Map<String, Object> rulePack =
                FinanceJournalPersonPostgresTest.as(
                        "reviewer",
                        () ->
                                obj(
                                        payroll.registerRulePack(
                                                "reviewer",
                                                book,
                                                entityId,
                                                2026,
                                                map(
                                                        "idempotencyKey",
                                                        UUID.randomUUID().toString(),
                                                        "version",
                                                        "CN-IIT-REST-2026.1",
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
                                                        ruleAppOrig.toString()),
                                                FinanceJournalPersonPostgresTest.request())));
        UUID rulePackId = id(rulePack.get("id"));

        UUID batchId = UUID.randomUUID();
        Draft draft =
                new Draft(
                        batchId,
                        book,
                        LocalDate.of(2026, 1, 31),
                        PayrollService.Kind.NORMAL,
                        null,
                        "2026年1月发薪批次",
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
                                        attOrig,
                                        LocalDate.of(2026, 1, 1),
                                        LocalDate.of(2026, 1, 31))));
        PayrollView b0 =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                payroll.create(
                                        "maker",
                                        book,
                                        entityId,
                                        2026,
                                        new PayrollController.CreateRequest(
                                                new Command(UUID.randomUUID(), "test", 0), draft),
                                        FinanceJournalPersonPostgresTest.request()));
        PayrollView b1 =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                payroll.act(
                                        "maker",
                                        book,
                                        entityId,
                                        2026,
                                        batchId,
                                        new PayrollController.ActionRequest(
                                                new Command(UUID.randomUUID(), "test", b0.version()),
                                                Action.CALCULATE),
                                        FinanceJournalPersonPostgresTest.request()));
        PayrollView b2 =
                FinanceJournalPersonPostgresTest.as(
                        "reviewer",
                        () ->
                                payroll.act(
                                        "reviewer",
                                        book,
                                        entityId,
                                        2026,
                                        batchId,
                                        new PayrollController.ActionRequest(
                                                new Command(UUID.randomUUID(), "test", b1.version()),
                                                Action.REVIEW),
                                        FinanceJournalPersonPostgresTest.request()));
        PayrollView b3 =
                FinanceJournalPersonPostgresTest.as(
                        "other",
                        () ->
                                payroll.act(
                                        "other",
                                        book,
                                        entityId,
                                        2026,
                                        batchId,
                                        new PayrollController.ActionRequest(
                                                new Command(UUID.randomUUID(), "test", b2.version()),
                                                Action.APPROVE),
                                        FinanceJournalPersonPostgresTest.request()));
        PayrollView bLocked =
                FinanceJournalPersonPostgresTest.as(
                        "other",
                        () ->
                                payroll.act(
                                        "other",
                                        book,
                                        entityId,
                                        2026,
                                        batchId,
                                        new PayrollController.ActionRequest(
                                                new Command(UUID.randomUUID(), "test", b3.version()),
                                                Action.LOCK),
                                        FinanceJournalPersonPostgresTest.request()));
        assertEquals(State.LOCKED, bLocked.state());

        // Register & approve DECLARATION_ACCEPTED fact on the locked batch
        Map<String, Object> factEv =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        payroll.registerFactEvidence(
                                                "maker",
                                                book,
                                                entityId,
                                                2026,
                                                map(
                                                        "idempotencyKey",
                                                        UUID.randomUUID().toString(),
                                                        "batchId",
                                                        batchId.toString(),
                                                        "kind",
                                                        "DECLARATION_ACCEPTED",
                                                        "originalId",
                                                        decOrig.toString(),
                                                        "amount",
                                                        "360.00",
                                                        "reason",
                                                        "登记个税申报受理回执"),
                                                FinanceJournalPersonPostgresTest.request())));
        UUID factEvId = id(factEv.get("id"));
        FinanceJournalPersonPostgresTest.as(
                "reviewer",
                () ->
                        payroll.approveFactEvidence(
                                "reviewer",
                                book,
                                entityId,
                                2026,
                                factEvId,
                                map(
                                        "idempotencyKey",
                                        UUID.randomUUID().toString(),
                                        "expectedVersion",
                                        "1",
                                        "reason",
                                        "批准个税申报受理回执"),
                                FinanceJournalPersonPostgresTest.request()));
        FinanceJournalPersonPostgresTest.as(
                "other",
                () ->
                        payroll.fact(
                                "other",
                                book,
                                entityId,
                                2026,
                                batchId,
                                new PayrollController.FactRequest(
                                        new Command(UUID.randomUUID(), "test", bLocked.version()),
                                        FactKind.DECLARATION_ACCEPTED,
                                        factEvId),
                                FinanceJournalPersonPostgresTest.request()));

        // Create PAY02 cross-batch restatement plan (cumulativeIncome revised from 20000.00 to 25000.00 -> tax 510.00, delta +150.00)
        Map<String, Object> plan =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        payroll.createRestatementPlan(
                                                "maker",
                                                book,
                                                entityId,
                                                2026,
                                                map(
                                                        "idempotencyKey",
                                                        UUID.randomUUID().toString(),
                                                        "employeeId",
                                                        employeeId.toString(),
                                                        "rulePackId",
                                                        rulePackId.toString(),
                                                        "periods",
                                                        List.of(
                                                                map(
                                                                        "batchId",
                                                                        batchId.toString(),
                                                                        "cumulativeIncome",
                                                                        "25000.00",
                                                                        "cumulativeExempt",
                                                                        "0.00",
                                                                        "cumulativeSocial",
                                                                        "2000.00",
                                                                        "cumulativeSpecial",
                                                                        "1000.00",
                                                                        "cumulativeOther",
                                                                        "0.00",
                                                                        "cumulativeDonation",
                                                                        "0.00",
                                                                        "cumulativeReduction",
                                                                        "0.00",
                                                                        "alreadyWithheld",
                                                                        "0.00")),
                                                        "reason",
                                                        "补录1月绩效差额5000元生成下游预扣差额方案"),
                                                FinanceJournalPersonPostgresTest.request())));
        UUID planId = id(plan.get("id"));
        assertEquals("PENDING", plan.get("status"));
        assertEquals("150.00", plan.get("totalTaxDelta"));
        assertEquals("0.00", plan.get("totalOverwithheld"));
        assertEquals(Boolean.TRUE, plan.get("requiresPriorAmendment"));

        // Self-approval via alias must fail with SELF_APPROVAL_FORBIDDEN
        var selfPlanErr =
                assertThrows(
                        FinanceException.class,
                        () ->
                                FinanceJournalPersonPostgresTest.as(
                                        "alias",
                                        () ->
                                                payroll.approveRestatementPlan(
                                                        "alias",
                                                        book,
                                                        entityId,
                                                        2026,
                                                        planId,
                                                        map(
                                                                "idempotencyKey",
                                                                UUID.randomUUID().toString(),
                                                                "expectedVersion",
                                                                "1",
                                                                "reason",
                                                                "同自然人自审应阻断"),
                                                        FinanceJournalPersonPostgresTest.request())));
        assertEquals("SELF_APPROVAL_FORBIDDEN", selfPlanErr.code);

        Map<String, Object> approvedPlan =
                FinanceJournalPersonPostgresTest.as(
                        "reviewer",
                        () ->
                                obj(
                                        payroll.approveRestatementPlan(
                                                "reviewer",
                                                book,
                                                entityId,
                                                2026,
                                                planId,
                                                map(
                                                        "idempotencyKey",
                                                        UUID.randomUUID().toString(),
                                                        "expectedVersion",
                                                        "1",
                                                        "reason",
                                                        "独立审签跨期重算差额方案"),
                                                FinanceJournalPersonPostgresTest.request())));
        assertEquals("APPROVED", approvedPlan.get("status"));
        assertEquals("2", approvedPlan.get("version"));
    }

    private static void postNormalJournal(
            FinanceJournalPersonPostgresTest.Case c,
            String date,
            String summary,
            List<Map<String, Object>> lines) {
        Map<String, Object> input = FinanceJournalPersonPostgresTest.journalInput(c, "100.00");
        input.put("date", date);
        input.put("summary", summary);
        input.put("lines", lines);
        UUID jid =
                (UUID)
                        FinanceJournalPersonPostgresTest.legacy("maker", c, "journal", null, input)
                                .get("id");
        FinanceJournalPersonPostgresTest.submit(c, jid);
        var approved = FinanceJournalPersonPostgresTest.approve(c, jid, Duration.ofMinutes(5));
        var post = FinanceJournalPersonPostgresTest.action(c, jid);
        post.put("approvalId", ((Map<?, ?>) approved.get("currentApproval")).get("id").toString());
        FinanceJournalPersonPostgresTest.cmd("other", c, "person-post", jid, post);
    }

    @Test
    void reportDraftsContraMappingExtractsAccumulatedDepreciationAndNetsAssetRow() {
        var c = FinanceJournalPersonPostgresTest.fixture("CAS");
        FinanceJournalPersonPostgresTest.enable(c);
        UUID book = c.f().book(), year = c.f().year();

        UUID faAcc =
                FinanceV4ProducersPostgresTest.addAccount(
                        book, year, "1601", "固定资产", "DEBIT", "ASSET");
        UUID depAcc =
                FinanceV4ProducersPostgresTest.addAccount(
                        book, year, "1602", "累计折旧", "CREDIT", "ASSET");
        UUID eqAcc =
                FinanceV4ProducersPostgresTest.addAccount(
                        book, year, "4002", "实收资本-专项", "CREDIT", "EQUITY");
        UUID expAcc =
                FinanceV4ProducersPostgresTest.addAccount(
                        book, year, "6602", "管理费用", "DEBIT", "EXPENSE");

        // Post acquisition: Debit 1601 12000.00 / Credit 4001 12000.00
        postNormalJournal(
                c,
                "2026-01-10",
                "购入研发服务器",
                List.of(
                        map("accountId", faAcc.toString(), "debit", "12000.00", "credit", "0.00"),
                        map("accountId", eqAcc.toString(), "debit", "0.00", "credit", "12000.00")));

        // Post depreciation: Debit 6602 1000.00 / Credit 1602 1000.00
        postNormalJournal(
                c,
                "2026-01-31",
                "计提1月折旧",
                List.of(
                        map("accountId", expAcc.toString(), "debit", "1000.00", "credit", "0.00"),
                        map("accountId", depAcc.toString(), "debit", "0.00", "credit", "1000.00")));

        // Create report mapping with CONTRA for 1602 -> 1601
        Map<String, Object> mp =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        reportDrafts.createMapping(
                                                "maker",
                                                book,
                                                year,
                                                new FinanceReportDrafts.CreateMappingInput(
                                                        "CAS-CONTRA-2026.1",
                                                        List.of(
                                                                new FinanceReportDrafts.AccountMappingItem(
                                                                        faAcc,
                                                                        "BS",
                                                                        Semantic.FIXED_ASSET),
                                                                new FinanceReportDrafts.AccountMappingItem(
                                                                        depAcc,
                                                                        "CONTRA",
                                                                        Semantic.FIXED_ASSET,
                                                                        ContraType.DEPRECIATION,
                                                                        faAcc),
                                                                new FinanceReportDrafts.AccountMappingItem(
                                                                        eqAcc,
                                                                        "BS",
                                                                        Semantic.CAPITAL),
                                                                new FinanceReportDrafts.AccountMappingItem(
                                                                        expAcc,
                                                                        "PL",
                                                                        Semantic.MANAGEMENT)),
                                                        List.of(
                                                                new FinanceReportDrafts.CertifiedRowItem(
                                                                        Kind.BS, "A22"),
                                                                new FinanceReportDrafts.CertifiedRowItem(
                                                                        Kind.BS, "E01"),
                                                                new FinanceReportDrafts.CertifiedRowItem(
                                                                        Kind.PL, "P05")),
                                                        "映射固定资产与累计折旧备抵科目"),
                                                FinanceJournalPersonPostgresTest.request())));
        UUID mappingId = id(mp.get("id"));
        FinanceJournalPersonPostgresTest.as(
                "reviewer",
                () ->
                        reportDrafts.approveMapping(
                                "reviewer",
                                book,
                                year,
                                mappingId,
                                new FinanceReportDrafts.ApproveMappingInput(1L, "独立批准含备抵映射"),
                                FinanceJournalPersonPostgresTest.request()));

        Map<String, Object> draft =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        reportDrafts.evaluate(
                                                "maker",
                                                book,
                                                year,
                                                new FinanceReportDrafts.EvaluateDraftInput(
                                                        mappingId,
                                                        LocalDate.of(2026, 1, 1),
                                                        LocalDate.of(2026, 1, 31)))));
        assertEquals("BLOCKED_DRAFT", draft.get("status"));
        var bsSheet =
                list(draft.get("sheets")).stream()
                        .filter(s -> "CAS-BS-2019-A2".equals(s.get("template")))
                        .findFirst()
                        .orElseThrow();
        var a22 =
                list(bsSheet.get("cells")).stream()
                        .filter(cell -> "A22".equals(cell.get("rowId")))
                        .findFirst()
                        .orElseThrow();
        assertEquals("DRAFT", a22.get("status"));
        assertEquals("11000", a22.get("rawValue"));
        assertTrue(
                list(a22.get("trace")).stream()
                        .anyMatch(t -> "MATCHED_CONTRA".equals(t.get("operation")) && "-1000".equals(t.get("rawValue"))));
    }
}

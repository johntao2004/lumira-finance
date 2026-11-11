package com.lumira.finance.yearend;

import static com.lumira.finance.yearend.YearendModel.*;
import static com.lumira.finance.yearend.YearendRules.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public class YearendRulesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void testCutoverModeMutualExclusivity() {
        assertDoesNotThrow(() -> validateCutoverMode("FULL_HISTORY"));
        assertDoesNotThrow(() -> validateCutoverMode("OPENING_PLUS_YTD"));
        assertThrows(IllegalArgumentException.class, () -> validateCutoverMode("HYBRID"));
        assertThrows(IllegalArgumentException.class, () -> validateCutoverMode(""));
    }

    @Test
    public void testCutoverTieOutEvaluation() {
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        UUID a3 = UUID.randomUUID();
        UUID a4 = UUID.randomUUID();

        // Balanced opening balances with Fixed Assets 1601 and Accumulated Depreciation 1602 (contra-asset)
        List<OpeningBalanceItem> balances = List.of(
                new OpeningBalanceItem(UUID.randomUUID(), a1, "1001", "Cash", "CNY", "DEBIT", new BigDecimal("5000.00000000"), Map.of()),
                new OpeningBalanceItem(UUID.randomUUID(), a2, "1122", "Accounts Receivable", "CNY", "DEBIT", new BigDecimal("10000.00000000"), Map.of("customerId", "CUST_001")),
                new OpeningBalanceItem(UUID.randomUUID(), a3, "1601", "Fixed Assets Gross", "CNY", "DEBIT", new BigDecimal("25000.00000000"), Map.of()),
                new OpeningBalanceItem(UUID.randomUUID(), UUID.randomUUID(), "1602", "Accumulated Depreciation", "CNY", "CREDIT", new BigDecimal("5000.00000000"), Map.of()),
                new OpeningBalanceItem(UUID.randomUUID(), a4, "2202", "Accounts Payable", "CNY", "CREDIT", new BigDecimal("35000.00000000"), Map.of("vendorId", "VEND_001"))
        );

        List<BaselineItem> baselines = List.of(
                new BaselineItem(UUID.randomUUID(), "CASHFLOW", 6, "SALES_INFLOW", "Cash from Sales", new BigDecimal("5000.00000000"), Map.of())
        );

        // Fixed asset net book value: 25000 - 5000 = 20000
        Map<String, BigDecimal> subledgerTotals = Map.of(
                "AR", new BigDecimal("10000.00000000"),
                "AP", new BigDecimal("35000.00000000"),
                "ASSET", new BigDecimal("20000.00000000"),
                "PAYROLL", BigDecimal.ZERO
        );

        List<TieOutResult> results = evaluateCutoverTieOut(balances, baselines, subledgerTotals);
        assertEquals(6, results.size());
        assertTrue(areAllTieOutsPassed(results));

        // Mismatched subledger causes failure
        Map<String, BigDecimal> badSubledger = new HashMap<>(subledgerTotals);
        badSubledger.put("AR", new BigDecimal("9999.00000000"));
        List<TieOutResult> badResults = evaluateCutoverTieOut(balances, baselines, badSubledger);
        assertFalse(areAllTieOutsPassed(badResults));
    }

    @Test
    public void testAutoRuleAndReviewPolicyEvaluation() {
        UUID bookId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        PersonActor maker = new PersonActor(UUID.randomUUID(), UUID.randomUUID(), "rule-maker");
        PersonActor approver = new PersonActor(UUID.randomUUID(), UUID.randomUUID(), "rule-approver");

        AutoRuleTemplate tmpl = new AutoRuleTemplate(
                UUID.randomUUID(), tenantId, bookId, "RULE_AR_SALES", "Sales Auto Rule", "AR_SALES",
                1, "APPROVED", sha256Hex("tmpl"),
                List.of(new LineRule(UUID.randomUUID(), "1122", "DEBIT", "amount", Map.of(), "Sales")),
                maker, approver, java.time.Instant.now(), java.time.Instant.now()
        );

        AutoReviewPolicy policy = new AutoReviewPolicy(
                UUID.randomUUID(), tenantId, bookId, "POL_AUTO_REVIEW_SALES", "Sales Policy",
                1, "APPROVED", sha256Hex("pol"),
                List.of("AR_SALES"),
                List.of("1122", "6001", "2221"),
                new BigDecimal("50000.00000000"),
                true,
                maker, approver, java.time.Instant.now(), java.time.Instant.now()
        );

        UUID yearId = UUID.randomUUID();
        // Case A: Within bounds and zero exceptions -> Auto Approved
        AutoRuleExecution execPass = evaluateAutoRuleAndReview(
                tmpl, policy, yearId, UUID.randomUUID(), "EVENT_001", "ERP_SALES",
                new BigDecimal("12000.00000000"), "AR_SALES", List.of("1122", "6001"),
                new PersonActor(UUID.randomUUID(), UUID.randomUUID(), "operator")
        );
        assertTrue(execPass.autoReviewed());
        assertEquals(0, execPass.exceptionCount());
        assertEquals("APPROVED", execPass.auditEvidence().get("autoReviewVerdict"));

        // Case B: Amount exceeds ceiling -> Requires Manual Review
        AutoRuleExecution execExceed = evaluateAutoRuleAndReview(
                tmpl, policy, yearId, UUID.randomUUID(), "EVENT_002", "ERP_SALES",
                new BigDecimal("60000.00000000"), "AR_SALES", List.of("1122", "6001"),
                new PersonActor(UUID.randomUUID(), UUID.randomUUID(), "operator")
        );
        assertFalse(execExceed.autoReviewed());
        assertTrue(execExceed.exceptionCount() > 0);
        assertEquals("MANUAL_REVIEW_REQUIRED", execExceed.auditEvidence().get("autoReviewVerdict"));

        // Case C: Unapproved template throws IllegalStateException
        AutoRuleTemplate draftTmpl = new AutoRuleTemplate(
                UUID.randomUUID(), tenantId, bookId, "DRAFT_TMPL", "Draft", "AR_SALES",
                1, "DRAFT", sha256Hex("d"), List.of(), maker, null, null, java.time.Instant.now()
        );
        assertThrows(IllegalStateException.class, () -> evaluateAutoRuleAndReview(
                draftTmpl, policy, yearId, UUID.randomUUID(), "E3", "S", BigDecimal.TEN, "AR_SALES", List.of("1122"), maker
        ));
    }

    @Test
    public void testJournalCorrectionRoutingAndWatermarkCeilings() {
        Map<Integer, BigDecimal> watermarks = Map.of(
                1, new BigDecimal("1000.00000000"),
                2, new BigDecimal("1000.00000000")
        );

        List<CorrectionAllocation> validAllocations = List.of(
                new CorrectionAllocation(1, new BigDecimal("500.00000000"), UUID.randomUUID()),
                new CorrectionAllocation(2, new BigDecimal("500.00000000"), UUID.randomUUID())
        );

        // CAS with account 6901 passes
        assertDoesNotThrow(() -> validateCorrectionRequest(
                "CAS", "PRIOR_PERIOD_ADJUSTMENT", "6901", validAllocations, watermarks
        ));

        // CAS with non-6901 account for prior period adjustment fails
        assertThrows(IllegalArgumentException.class, () -> validateCorrectionRequest(
                "CAS", "PRIOR_PERIOD_ADJUSTMENT", "6001", validAllocations, watermarks
        ));

        // SMALL standard with 6901 strictly fails
        assertThrows(IllegalArgumentException.class, () -> validateCorrectionRequest(
                "SMALL", "PRIOR_PERIOD_ADJUSTMENT", "6901", validAllocations, watermarks
        ));

        // SMALL standard with non-6901 passes
        assertDoesNotThrow(() -> validateCorrectionRequest(
                "SMALL", "PRIOR_PERIOD_ADJUSTMENT", "3103", validAllocations, watermarks
        ));

        // Allocation exceeding line watermark fails
        List<CorrectionAllocation> overAllocations = List.of(
                new CorrectionAllocation(1, new BigDecimal("1500.00000000"), UUID.randomUUID())
        );
        assertThrows(IllegalArgumentException.class, () -> validateCorrectionRequest(
                "CAS", "PARTIAL_REVERSAL", null, overAllocations, watermarks
        ));
    }

    @Test
    public void testPeriodCloseChecklistEvaluation() {
        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        UUID yearId = UUID.randomUUID();
        UUID periodId = UUID.randomUUID();
        PersonActor maker = new PersonActor(UUID.randomUUID(), UUID.randomUUID(), "close-maker");

        Map<String, BigDecimal> glBalances = Map.of(
                "AR", new BigDecimal("5000.00000000"),
                "AP", new BigDecimal("4000.00000000"),
                "ASSET", new BigDecimal("20000.00000000"),
                "INVENTORY", new BigDecimal("1000.00000000"),
                "PAYROLL", new BigDecimal("3000.00000000")
        );

        Map<String, BigDecimal> subledgerBalances = new HashMap<>(glBalances);

        // All gates passed
        PeriodCloseCheckPlan passedPlan = evaluatePeriodClose(
                tenantId, bookId, yearId, periodId,
                0, new BigDecimal("33000.00000000"), new BigDecimal("33000.00000000"),
                true, glBalances, subledgerBalances, true, true, maker
        );
        assertTrue(passedPlan.allChecksPassed());
        assertTrue(passedPlan.trialBalanceBalanced());
        assertEquals(10, passedPlan.items().size());

        // Failed due to unposted journals
        PeriodCloseCheckPlan unpostedFailed = evaluatePeriodClose(
                tenantId, bookId, yearId, periodId,
                2, new BigDecimal("33000.00000000"), new BigDecimal("33000.00000000"),
                true, glBalances, subledgerBalances, true, true, maker
        );
        assertFalse(unpostedFailed.allChecksPassed());
        assertFalse(unpostedFailed.items().get(0).passed());

        // Failed due to unbalanced trial balance
        PeriodCloseCheckPlan trialFailed = evaluatePeriodClose(
                tenantId, bookId, yearId, periodId,
                0, new BigDecimal("33000.00000000"), new BigDecimal("33001.00000000"),
                true, glBalances, subledgerBalances, true, true, maker
        );
        assertFalse(trialFailed.allChecksPassed());
        assertFalse(trialFailed.trialBalanceBalanced());
    }

    @Test
    public void testCarryBatchAndRestatementImpact() {
        List<CarryBalanceItem> balances = List.of(
                new CarryBalanceItem(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "1122", "1122", "CNY", "DEBIT",
                        new BigDecimal("10000.00000000"), new BigDecimal("2000.00000000"), new BigDecimal("12000.00000000"), Map.of()),
                new CarryBalanceItem(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "6001", "6001", "CNY", "CREDIT",
                        new BigDecimal("5000.00000000"), new BigDecimal("1000.00000000"), new BigDecimal("6000.00000000"), Map.of())
        );

        Map<String, BigDecimal> preReport = Map.of(
                "BALANCE_SHEET_TOTAL", new BigDecimal("100000.00000000"),
                "INCOME_STATEMENT_TOTAL", new BigDecimal("50000.00000000"),
                "CIT_TAXABLE_INCOME", new BigDecimal("45000.00000000")
        );

        List<RestatementManifestItem> restatements = calculateRestatementImpact(balances, preReport);
        assertEquals(3, restatements.size());

        RestatementManifestItem bs = restatements.get(0);
        assertEquals("BALANCE_SHEET_COMPARATIVE", bs.restatementScope());
        assertEquals(new BigDecimal("2000.00000000"), bs.deltaAmount());
        assertEquals(new BigDecimal("102000.00000000"), bs.restatedAmount());

        RestatementManifestItem pl = restatements.get(1);
        assertEquals("INCOME_STATEMENT_COMPARATIVE", pl.restatementScope());
        assertEquals(new BigDecimal("1000.00000000"), pl.deltaAmount());
        assertEquals(new BigDecimal("51000.00000000"), pl.restatedAmount());

        RestatementManifestItem cit = restatements.get(2);
        assertEquals("CIT_ANNUAL_ADJUSTMENT", cit.restatementScope());
        assertEquals(new BigDecimal("46000.00000000"), cit.restatedAmount());
    }

    @Test
    public void testJackson3RoundTripAndBigDecimalPlainString() throws Exception {
        TieOutResult item = new TieOutResult(
                "AR_SUBLEDGER", true,
                new BigDecimal("1234567.89000000"),
                new BigDecimal("1234567.89000000"),
                BigDecimal.ZERO,
                Map.of("note", "tied out perfectly")
        );

        String json = mapper.writeValueAsString(item);
        assertTrue(json.contains("\"1234567.89000000\""), "Monetary amount must serialize as string");
        assertFalse(json.contains("1.234567"), "Must not use scientific notation");

        TieOutResult read = mapper.readValue(json, TieOutResult.class);
        assertEquals(item.checkItem(), read.checkItem());
        assertEquals(item.glAmount(), read.glAmount());
        assertTrue(read.passed());
    }

    @Test
    public void testNullLineWatermarksSafeHandling() {
        List<CorrectionAllocation> allocations = List.of(
                new CorrectionAllocation(1, new BigDecimal("100.00000000"), UUID.randomUUID())
        );
        // Null lineWatermarks should throw IllegalArgumentException for watermark exceeded (remaining=0), NOT NullPointerException
        assertThrows(IllegalArgumentException.class, () -> validateCorrectionRequest(
                "CAS", "PARTIAL_REVERSAL", null, allocations, null
        ));
    }

    @Test
    public void testConsecutiveYearsAndMultiYearReadScope() {
        // Consecutive years pass
        assertDoesNotThrow(() -> validateConsecutiveYears(2026, 2027));
        // Non-consecutive years fail
        assertThrows(IllegalArgumentException.class, () -> validateConsecutiveYears(2026, 2028));
        assertThrows(IllegalArgumentException.class, () -> validateConsecutiveYears(2027, 2026));

        UUID y2025 = UUID.randomUUID();
        UUID y2026 = UUID.randomUUID();
        UUID y2027 = UUID.randomUUID();
        Set<UUID> authorized = Set.of(y2025, y2026);

        // Requested authorized years pass
        assertDoesNotThrow(() -> validateMultiYearReadScope(List.of(y2025, y2026), authorized));
        // Requested unauthorized year fails
        assertThrows(IllegalArgumentException.class, () -> validateMultiYearReadScope(List.of(y2026, y2027), authorized));
        // Empty requested years fail
        assertThrows(IllegalArgumentException.class, () -> validateMultiYearReadScope(List.of(), authorized));
    }

    @Test
    public void testChartOfAccountsMappingValidation() {
        List<CarryBalanceItem> validItems = List.of(
                new CarryBalanceItem(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "1122", "1122", "CNY", "DEBIT",
                        new BigDecimal("1000.00000000"), BigDecimal.ZERO, new BigDecimal("1000.00000000"), Map.of())
        );
        assertDoesNotThrow(() -> validateChartOfAccountsMapping("MAP_V1", validItems));

        // Blank mapping version fails
        assertThrows(IllegalArgumentException.class, () -> validateChartOfAccountsMapping("", validItems));
        assertThrows(IllegalArgumentException.class, () -> validateChartOfAccountsMapping(null, validItems));

        // Empty items fail
        assertThrows(IllegalArgumentException.class, () -> validateChartOfAccountsMapping("MAP_V1", List.of()));

        // Missing target account code fails
        List<CarryBalanceItem> badItems = List.of(
                new CarryBalanceItem(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "1122", "", "CNY", "DEBIT",
                        new BigDecimal("1000.00000000"), BigDecimal.ZERO, new BigDecimal("1000.00000000"), Map.of())
        );
        assertThrows(IllegalArgumentException.class, () -> validateChartOfAccountsMapping("MAP_V1", badItems));
    }
}

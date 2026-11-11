package com.lumira.finance.statutory;

import static com.lumira.finance.statutory.StatutoryModels.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public class StatutoryReportsRulesTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID entityId = UUID.randomUUID();
    private final UUID bookId = UUID.randomUUID();
    private final UUID yearId = UUID.randomUUID();
    private final Actor preparer = new Actor(UUID.randomUUID(), "Alice Preparer", "ACCOUNTANT");
    private final Actor reviewer = new Actor(UUID.randomUUID(), "Bob Reviewer", "REVIEWER");
    private final Actor approver = new Actor(UUID.randomUUID(), "Charlie Controller", "FINANCIAL_CONTROLLER");

    @Test
    @DisplayName("RPT01: 6 canonical tech vouchers must balance and adhere to contra-account rules for CAS and SMALL")
    void testCanonicalVouchersAndTeachingGuide() {
        for (Standard std : List.of(Standard.CAS_2019, Standard.SBE_2011)) {
            List<CanonicalVoucher> vouchers = TeachingGuideEngine.getCanonicalVouchers(std);
            assertEquals(6, vouchers.size(), "Must produce exactly 6 canonical vouchers");

            for (CanonicalVoucher v : vouchers) {
                assertNotNull(v.code());
                assertNotNull(v.title());
                assertTrue(v.lines().size() >= 2, "Voucher must have at least 2 lines");
                assertEquals(v.totalDebit(), v.totalCredit(), "Voucher must balance down to cent: " + v.code());

                BigDecimal sumDebit = BigDecimal.ZERO;
                BigDecimal sumCredit = BigDecimal.ZERO;
                for (VoucherLine l : v.lines()) {
                    assertTrue(l.amount().signum() > 0, "Line amount must be positive");
                    if ("DEBIT".equalsIgnoreCase(l.direction())) {
                        sumDebit = sumDebit.add(l.amount());
                    } else if ("CREDIT".equalsIgnoreCase(l.direction())) {
                        sumCredit = sumCredit.add(l.amount());
                    } else {
                        fail("Invalid direction: " + l.direction());
                    }
                }
                assertEquals(sumDebit, sumCredit, "Sum of debit lines must equal credit lines for " + v.code());
            }

            List<TeachingGuide> guides = TeachingGuideEngine.getAllTeachingGuides(std);
            assertEquals(3, guides.size(), "Must produce 3 teaching guides (Double-entry, Contra accounts, Walkthroughs)");
            assertTrue(guides.stream().anyMatch(g -> g.topic().equals("CONTRA_ACCOUNTS")));
        }
    }

    @Test
    @DisplayName("RPT02 & RP01: Balance sheet reclassifications, contra netting, maturity, and articulation")
    void testBalanceSheetCalculations() {
        Scope scope = new Scope(tenantId, entityId, bookId, yearId, Standard.CAS_2019, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "CNY");

        // Prepare balances:
        // Cash: 1,225.00
        // AR: Customer A debit 1,000, Customer B credit 200 (reclassified to contract liability)
        // AP: Vendor X credit 500, Vendor Y debit 100 (reclassified to prepayment)
        // Fixed Asset: Gross 120,000, Accumulated Depreciation contra credit 10,000 -> Net 110,000
        // Long-term borrowing: 300,000, with 120,000 due within 1 year reclassified to current liability
        // Equity: Share capital 800,000, Capital reserve 200,000, Retained earnings (net profit)
        List<AccountBalance> balances = List.of(
                new AccountBalance("1002", "银行存款", "BANK-01", "OBJ-CASH", "CASH", new BigDecimal("1225.00"), BigDecimal.ZERO),
                new AccountBalance("1122", "应收账款-A", "CUST-A", "OBJ-CUST-A", "RECEIVABLE", new BigDecimal("1000.00"), BigDecimal.ZERO),
                new AccountBalance("1122", "应收账款-B", "CUST-B", "OBJ-CUST-B", "RECEIVABLE", BigDecimal.ZERO, new BigDecimal("200.00")),
                new AccountBalance("2202", "应付账款-X", "VEND-X", "OBJ-VEND-X", "PAYABLE", BigDecimal.ZERO, new BigDecimal("500.00")),
                new AccountBalance("2202", "应付账款-Y", "VEND-Y", "OBJ-VEND-Y", "PAYABLE", new BigDecimal("100.00"), BigDecimal.ZERO),
                new AccountBalance("1601", "固定资产", "FA-01", "OBJ-FA", "FIXED_ASSET", new BigDecimal("120000.00"), BigDecimal.ZERO),
                new AccountBalance("2501", "长期借款", "LOAN-01", "OBJ-LOAN", "LONG_BORROWING", BigDecimal.ZERO, new BigDecimal("300000.00")),
                new AccountBalance("4001", "实收资本", "CAP-01", "OBJ-CAP", "SHARE_CAPITAL", BigDecimal.ZERO, new BigDecimal("800000.00")),
                new AccountBalance("4002", "资本公积", "CAP-RES", "OBJ-CAP-RES", "CAPITAL_RESERVE", BigDecimal.ZERO, new BigDecimal("200000.00")),
                new AccountBalance("4104", "未分配利润", "PROFIT", "OBJ-PROFIT", "RETAINED_EARNINGS", new BigDecimal("1188475.00"), BigDecimal.ZERO) // Balancing loss
        );

        List<ContraBalance> contras = List.of(
                new ContraBalance("1602", "1601", "FA-01", "DEPRECIATION", new BigDecimal("10000.00")),
                new ContraBalance("1231", "1122", "CUST-A", "IMPAIRMENT", new BigDecimal("100.00")) // 100 bad debt on customer A
        );

        Map<String, BigDecimal> maturity = Map.of("LONG_LOAN_WITHIN_1YR", new BigDecimal("120000.00"));

        List<ReportTrace> traces = new ArrayList<>();
        StatementSheet bs = StatutoryReportEngine.buildBalanceSheet(scope, balances, contras, maturity, traces);

        assertEquals("PASS", bs.articulationStatus(), "Balance sheet must articulate: Assets = Liabilities + Equity");
        assertEquals("1225.00", bs.cells().get("A01").rawValue());
        assertEquals("900.00", bs.cells().get("A05").rawValue(), "AR must be 1000 debit minus 100 bad debt = 900");
        assertEquals("100.00", bs.cells().get("A07").rawValue(), "AP debit must be reclassified to prepayments 100");
        assertEquals("200.00", bs.cells().get("L07").rawValue(), "AR credit must be reclassified to contract liabilities 200");
        assertEquals("500.00", bs.cells().get("L05").rawValue(), "AP credit must be 500");
        assertEquals("110000.00", bs.cells().get("A22").rawValue(), "Net fixed assets must be 120000 - 10000 = 110000");
        assertEquals("120000.00", bs.cells().get("L12").rawValue(), "Maturity within 1 year reclassified to L12");
        assertEquals("180000.00", bs.cells().get("L15").rawValue(), "Remaining long-term borrowing must be 180000");
    }

    @Test
    @DisplayName("RPT03 & RP01: Comparative income statement excludes profit-closing entries and computes net profit")
    void testIncomeStatementClosingExclusion() {
        Scope scope = new Scope(tenantId, entityId, bookId, yearId, Standard.CAS_2019, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "CNY");

        List<MovementRecord> movements = List.of(
                new MovementRecord("6001", "REVENUE", LocalDate.of(2026, 3, 1), BigDecimal.ZERO, new BigDecimal("200000.00"), "NONE"),
                new MovementRecord("6401", "COST", LocalDate.of(2026, 3, 1), new BigDecimal("60000.00"), BigDecimal.ZERO, "NONE"),
                new MovementRecord("6602", "MANAGEMENT", LocalDate.of(2026, 3, 1), new BigDecimal("30000.00"), BigDecimal.ZERO, "NONE"),
                new MovementRecord("6603", "FINANCE", LocalDate.of(2026, 3, 1), new BigDecimal("5000.00"), BigDecimal.ZERO, "NONE"),
                new MovementRecord("6701", "CREDIT_LOSS", LocalDate.of(2026, 3, 1), new BigDecimal("5000.00"), BigDecimal.ZERO, "NONE"),
                new MovementRecord("6801", "INCOME_TAX", LocalDate.of(2026, 3, 1), new BigDecimal("10000.00"), BigDecimal.ZERO, "NONE"),
                // Nominal annual profit transfer entry (closing entry to zero out accounts):
                new MovementRecord("6001", "REVENUE", LocalDate.of(2026, 12, 31), new BigDecimal("200000.00"), BigDecimal.ZERO, "ANNUAL_PROFIT_TRANSFER"),
                new MovementRecord("6401", "COST", LocalDate.of(2026, 12, 31), BigDecimal.ZERO, new BigDecimal("60000.00"), "ANNUAL_PROFIT_TRANSFER")
        );

        Map<String, BigDecimal> priorYear = Map.of("P01", new BigDecimal("150000.00"), "P20", new BigDecimal("70000.00"));

        List<ReportTrace> traces = new ArrayList<>();
        StatementSheet pl = StatutoryReportEngine.buildIncomeStatement(scope, movements, priorYear, traces);

        assertEquals("PASS", pl.articulationStatus());
        assertEquals("200000.00", pl.cells().get("P01").rawValue(), "Revenue must exclude closing transfer and reflect 200,000.00");
        assertEquals("60000.00", pl.cells().get("P02").rawValue(), "Cost must exclude closing transfer and reflect 60,000.00");
        assertEquals("-5000.00", pl.cells().get("P11").rawValue(), "Credit loss must be reported on P11 line");
        // 200,000 - 60,000 - 30,000 - 5,000 - 5,000 = 100,000 Operating profit
        assertEquals("100000.00", pl.cells().get("P15").rawValue());
        // 100,000 - 10,000 tax = 90,000 Net profit
        assertEquals("90000.00", pl.cells().get("P20").rawValue());
    }

    @Test
    @DisplayName("RPT04 & RP01: Cash flow statement direct method and indirect profit reconciliation")
    void testCashFlowDirectAndIndirect() {
        Scope scope = new Scope(tenantId, entityId, bookId, yearId, Standard.CAS_2019, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "CNY");

        // Direct method cashflow items
        Map<String, BigDecimal> cfItems = Map.of(
                "C01", new BigDecimal("150.00"), // Sales receipt
                "C05", new BigDecimal("80.00"),  // Purchases
                "C06", new BigDecimal("20.00"),  // Payroll
                "C17", new BigDecimal("120.00"), // Capex
                "C23", new BigDecimal("1000.00"),// Equity
                "C24", new BigDecimal("300.00"), // Debt
                "C28", new BigDecimal("5.00")    // Interest
        );
        // Net Operating: 150 - 80 - 20 = 50.00
        // Net Investing: -120.00
        // Net Financing: 1000 + 300 - 5 = 1295.00
        // Net Cash Flow: 50 - 120 + 1295 = 1225.00
        BigDecimal opening = BigDecimal.ZERO;
        BigDecimal closing = new BigDecimal("1225.00");

        // Indirect reconciliation parameters:
        // Net profit: 95.00
        // Non-cash depreciation: 10.00
        // Working capital changes:
        // Inventory increase 40.00 (reduces cash by 40)
        // Receivables increase 76.00 (reduces cash by 76)
        // Payables increase 61.00 (increases cash by 61)
        // Indirect Operating Cash = 95 + 10 - 40 - 76 + 61 = 50.00! Matches C10 (50.00)
        BigDecimal netProfit = new BigDecimal("95.00");
        BigDecimal depr = new BigDecimal("10.00");
        BigDecimal deltaInventory = new BigDecimal("-40.00"); // negative for inventory increase
        BigDecimal deltaAR = new BigDecimal("76.00");        // positive for AR increase (subtracts)
        BigDecimal deltaAP = new BigDecimal("61.00");        // positive for AP increase (adds)

        List<ReportTrace> traces = new ArrayList<>();
        StatementSheet cf = StatutoryReportEngine.buildCashFlowStatement(
                scope, opening, closing, cfItems,
                netProfit, depr, BigDecimal.ZERO, deltaAR, deltaAP, deltaInventory, traces
        );

        assertEquals("PASS", cf.articulationStatus(), "Direct and indirect cash flows must articulate");
        assertEquals("50.00", cf.cells().get("C10").rawValue());
        assertEquals("-120.00", cf.cells().get("C22").rawValue());
        assertEquals("1295.00", cf.cells().get("C31").rawValue());
        assertEquals("1225.00", cf.cells().get("C33").rawValue());
        assertEquals("1225.00", cf.cells().get("C35").rawValue());
    }

    @Test
    @DisplayName("RPT06: THREE_STATEMENT_PACKAGE vs FULL_STATUTORY_REPORT_PACKAGE and mandatory blocking rules")
    void testPackageCompositionAndBlockingRules() {
        Scope scope = new Scope(tenantId, entityId, bookId, yearId, Standard.CAS_2019, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "CNY");

        StatementSheet bs = new StatementSheet(StatementKind.BS, "CAS-BS-2019-A2", Map.of(), "PASS", Map.of(), List.of());
        StatementSheet pl = new StatementSheet(StatementKind.PL, "CAS-PL-2019-A2", Map.of(), "PASS", Map.of(), List.of());
        StatementSheet cf = new StatementSheet(StatementKind.CF, "CAS-CF-2019-A2", Map.of(), "PASS", Map.of(), List.of());
        StatementSheet sce = new StatementSheet(StatementKind.SCE, "CAS-SCE-2019-A2", Map.of(), "PASS", Map.of(), List.of());

        // 1. Three statement package without equity statement or notes -> Should be DRAFT (not blocked)
        ReportPackage threePkg = StatutoryReportEngine.assembleAndEvaluatePackage(
                UUID.randomUUID(), scope, PackageType.THREE_STATEMENT_PACKAGE, FilingTarget.TAX_FILING,
                bs, pl, cf, null, null, null, preparer, null, null
        );
        assertEquals(PackageStatus.DRAFT, threePkg.status());
        assertTrue(threePkg.publicationGaps().isEmpty());

        // 2. Full package without SCE or Notes -> Must be BLOCKED
        ReportPackage fullPkgBlocked = StatutoryReportEngine.assembleAndEvaluatePackage(
                UUID.randomUUID(), scope, PackageType.FULL_STATUTORY_REPORT_PACKAGE, FilingTarget.TAX_FILING,
                bs, pl, cf, null, null, null, preparer, null, null
        );
        assertEquals(PackageStatus.BLOCKED, fullPkgBlocked.status(), "Full package without SCE and Notes must be BLOCKED");
        assertTrue(fullPkgBlocked.publicationGaps().stream().anyMatch(g -> g.contains("CAS_EQUITY_CHANGES_STATEMENT_MANDATORY")));
        assertTrue(fullPkgBlocked.publicationGaps().stream().anyMatch(g -> g.contains("MANDATORY_NOTES_INCOMPLETE")));

        // 3. Full package with complete SCE and all mandatory Notes sections -> DRAFT / APPROVABLE
        Map<String, String> completeSections = Map.of(
                "ENTITY_PROFILE", "北京科技有限公司，统一社会信用代码91110000MA00000000，成立于2020年。",
                "BASIS_OF_PREPARATION", "本财务报表以持续经营假设为基础，按照财政部颁布的企业会计准则编制。",
                "STATEMENT_OF_COMPLIANCE", "本公司编制的财务报表符合企业会计准则的要求，真实、完整地反映了本公司财务状况。",
                "SIGNIFICANT_POLICIES", "会计期间为公历1月1日至12月31日，以人民币为记账本位币，固定资产采用直线法折旧。",
                "TAXATION", "主要税种包括增值税13%、企业所得税15%（高新技术企业优惠税率）。",
                "DISCLOSURE_LINE_ITEMS", "货币资金期末余额1,225.00元，无受限资金；应收账款净额900.00元。"
        );
        StructuredNotes validNotes = StatutoryReportEngine.evaluateNotesCompleteness(completeSections);
        assertTrue(validNotes.mandatoryComplete());

        ReportPackage fullPkgComplete = StatutoryReportEngine.assembleAndEvaluatePackage(
                UUID.randomUUID(), scope, PackageType.FULL_STATUTORY_REPORT_PACKAGE, FilingTarget.TAX_FILING,
                bs, pl, cf, sce, validNotes, null, preparer, reviewer, approver
        );
        assertEquals(PackageStatus.APPROVED, fullPkgComplete.status());
        assertTrue(fullPkgComplete.publicationGaps().isEmpty());
    }

    @Test
    @DisplayName("RP02: Tech-enterprise operating KPIs and rolling cash calendar alert")
    void testKpisAndCashCalendar() {
        // KPI test:
        // Cash: 2,400,000
        // Op Outflow: 300,000, Op Inflow: 100,000, Capex: 40,000 in 12 months -> Burn Rate = (300k + 40k - 100k) / 12 = 20,000 / month
        // Runway = 2,400,000 / 20,000 = 120.0 months
        // Revenue: 1,000,000, COGS: 400,000 -> Gross Margin = 60.00%
        // RD Expense: 250,000 -> RD Intensity = 25.00%
        // Avg Receivables: 100,000 -> DSO = (100k / 1M) * 365 = 36.5 days
        // Avg Payables: 80,000 -> DPO = (80k / 400k) * 365 = 73.0 days
        KpiMetrics kpi = KpiAndCalendarEngine.computeKpis(
                new BigDecimal("2400000.00"),
                new BigDecimal("300000.00"),
                new BigDecimal("100000.00"),
                new BigDecimal("40000.00"),
                12,
                new BigDecimal("1000000.00"),
                new BigDecimal("400000.00"),
                new BigDecimal("250000.00"),
                new BigDecimal("100000.00"),
                new BigDecimal("80000.00"),
                preparer,
                approver
        );

        assertEquals(new BigDecimal("20000.00"), kpi.burnRate());
        assertEquals(new BigDecimal("120.0"), kpi.runwayMonths());
        assertEquals(new BigDecimal("25.0000"), kpi.rdIntensity());
        assertEquals(new BigDecimal("60.0000"), kpi.grossMargin());
        assertEquals(new BigDecimal("36.5"), kpi.dsoDays());
        assertEquals(new BigDecimal("73.0"), kpi.dpoDays());
        assertEquals("APPROVED", kpi.status());

        // Cash calendar test:
        // Opening cash: 50,000. Safe buffer: 30,000.
        // Day 5 outflow: 40,000 -> Balance becomes 10,000 (< 30,000 buffer) -> should flag bufferBreached = true
        Scope scope = new Scope(tenantId, entityId, bookId, yearId, Standard.CAS_2019, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "CNY");
        LocalDate projDate = LocalDate.of(2026, 1, 1);
        Map<LocalDate, BigDecimal> inflows = Map.of(projDate.plusDays(10), new BigDecimal("50000.00"));
        Map<LocalDate, BigDecimal> outflows = Map.of(projDate.plusDays(5), new BigDecimal("40000.00"));

        CashCalendar cal = KpiAndCalendarEngine.generateCashCalendar(
                UUID.randomUUID(), scope, projDate, 30,
                new BigDecimal("50000.00"),
                new BigDecimal("30000.00"),
                inflows, outflows, approver
        );

        assertEquals(30, cal.dailyForecasts().size());
        assertTrue(cal.bufferBreached(), "Buffer should be breached on Day 5 when balance drops to 10,000");
        assertEquals(new BigDecimal("10000.00"), cal.minProjectedBalance());
        assertEquals("PUBLISHED", cal.status());
    }

    @Test
    @DisplayName("RP03: Multi-entity management rollup and consolidation worksheet with 5 elimination entries")
    void testConsolidationAndMinorityInterest() {
        UUID parentId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();

        ConsolidationMember subMember = new ConsolidationMember(
                subId,
                "子公司科技开发有限公司",
                new BigDecimal("80.00"), // 80% parent, 20% minority
                new BigDecimal("80.00"),
                ConsolidationMethod.FULL_CONSOLIDATION
        );

        ConsolidationScope scope = new ConsolidationScope(
                UUID.randomUUID(),
                "GROUP_TECH_2026",
                parentId,
                "科技控股集团合并范围",
                List.of(subMember),
                "ACTIVE"
        );

        // Parent sheet: Assets 1,000,000, Liabilities 240,000, Equity 760,000, Revenue 500,000, Profit 100,000
        StatementSheet parentBs = new StatementSheet(StatementKind.BS, "CAS-BS-2019-A2", Map.of(
                "A34", new ReportCell("A34", "资产总计", "1000000.00", "1000000.00", "EXACT", List.of()),
                "L24", new ReportCell("L24", "负债合计", "240000.00", "240000.00", "EXACT", List.of()),
                "E09", new ReportCell("E09", "所有者权益合计", "760000.00", "760000.00", "EXACT", List.of())
        ), "PASS", Map.of(), List.of());
        StatementSheet parentPl = new StatementSheet(StatementKind.PL, "CAS-PL-2019-A2", Map.of("P01", new ReportCell("P01", "营业收入", "500000.00", "500000.00", "EXACT", List.of()), "P20", new ReportCell("P20", "净利润", "100000.00", "100000.00", "EXACT", List.of())), "PASS", Map.of(), List.of());
        StatementSheet parentCf = new StatementSheet(StatementKind.CF, "CAS-CF-2019-A2", Map.of(), "PASS", Map.of(), List.of());

        // Subsidiary sheet: Assets 400,000, Liabilities 100,000, Equity 300,000, Revenue 200,000, Net profit 50,000
        StatementSheet subBs = new StatementSheet(StatementKind.BS, "CAS-BS-2019-A2", Map.of(
                "A34", new ReportCell("A34", "资产总计", "400000.00", "400000.00", "EXACT", List.of()),
                "L24", new ReportCell("L24", "负债合计", "100000.00", "100000.00", "EXACT", List.of()),
                "E09", new ReportCell("E09", "所有者权益合计", "300000.00", "300000.00", "EXACT", List.of())
        ), "PASS", Map.of(), List.of());
        StatementSheet subPl = new StatementSheet(StatementKind.PL, "CAS-PL-2019-A2", Map.of(
                "P01", new ReportCell("P01", "营业收入", "200000.00", "200000.00", "EXACT", List.of()),
                "P20", new ReportCell("P20", "净利润", "50000.00", "50000.00", "EXACT", List.of())
        ), "PASS", Map.of(), List.of());

        // 1. Management Rollup
        ConsolidationWorksheet mgmtRollup = ConsolidationEngine.generateConsolidationWorksheet(
                UUID.randomUUID(), scope, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                true, parentBs, parentPl, parentCf, Map.of(subId, subBs), Map.of(subId, subPl), Map.of(),
                null, null, null, null, null, preparer, approver
        );
        assertTrue(mgmtRollup.isManagementRollup());
        assertEquals("1400000.00", mgmtRollup.rollupData().get("rollupTotalAssets"));
        assertEquals("700000.00", mgmtRollup.rollupData().get("rollupTotalRevenue"));
        assertTrue(mgmtRollup.eliminations().isEmpty(), "Management rollup has no elimination entries");

        // 2. Statutory Consolidation with eliminations
        ConsolidationWorksheet statConsol = ConsolidationEngine.generateConsolidationWorksheet(
                UUID.randomUUID(), scope, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                false, parentBs, parentPl, parentCf, Map.of(subId, subBs), Map.of(subId, subPl), Map.of(),
                new BigDecimal("50000.00"), // Intercompany AR 50,000
                new BigDecimal("2500.00"),  // Bad debt 2,500
                new BigDecimal("80000.00"), // Internal sales 80,000
                new BigDecimal("60000.00"), // Internal cost 60,000
                new BigDecimal("5000.00"),  // Unrealized profit in ending inventory 5,000
                preparer, approver
        );

        assertFalse(statConsol.isManagementRollup());
        assertEquals(5, statConsol.eliminations().size(), "Must have all 5 canonical elimination types including investment income vs profit distribution");
        assertTrue(statConsol.eliminations().stream().anyMatch(e -> e.entryType().startsWith("ELIM_INVESTMENT_INCOME_PROFIT_DISTRIBUTION_")));
        assertEquals(1, statConsol.minorityInterests().size());

        MinorityInterest mi = statConsol.minorityInterests().get(0);
        assertEquals(new BigDecimal("20.0000"), mi.minorityRatio());
        // Minority equity = 300,000 * 20% = 60,000
        assertEquals(new BigDecimal("60000.00"), mi.minorityEquity());
        // Minority profit = 50,000 * 20% = 10,000
        assertEquals(new BigDecimal("10000.00"), mi.minorityProfit());

        // Verify genuine consolidated Balance Sheet & articulation
        StatementSheet consolBs = statConsol.consolidatedSheets().get("CONSOLIDATED_BS");
        assertNotNull(consolBs);
        assertEquals("PASS", consolBs.articulationStatus(), "Consolidated Balance Sheet must articulate: Assets = Liab + Equity");
        assertEquals("60000.00", consolBs.cells().get("4999").rawValue(), "Minority interest line must be 60,000.00");

        // Verify genuine consolidated Income Statement
        StatementSheet consolPl = statConsol.consolidatedSheets().get("CONSOLIDATED_PL");
        assertNotNull(consolPl);
        assertEquals("PASS", consolPl.articulationStatus());
        // Consolidated Revenue = 500,000 + 200,000 - 80,000 internal sales = 620,000
        assertEquals("620000.00", consolPl.cells().get("P01").rawValue());
        // Minority profit = 10,000
        assertEquals("10000.00", consolPl.cells().get("P22").rawValue());
    }
}

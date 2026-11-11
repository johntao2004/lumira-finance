package com.lumira.finance.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static com.lumira.finance.domain.ReportSourceEngine.*;

/** Independent candidate assertions. Not executed/accepted by the reports lane. */
public final class ReportSourceEngineTest {
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 1, 31);
    private static Scope scope(Standard standard) {
        return new Scope("tenant", "entity", "book", "year", standard, "INDIVIDUAL", "CNY", START, END,
                "ledger-v9", "mapping-v2", "mapping-approval-v2", "snapshot-v9");
    }
    private static Input input(Standard standard, List<Balance> bs, List<Contra> cs,
                               List<Movement> ms, List<Coverage> coverage) {
        return new Input(scope(standard), "FULL_HISTORY", bs, cs, ms, coverage);
    }
    private static Balance balance(String id, Semantic sem, String value) {
        return new Balance(id, "snapshot-v9", "account-" + sem, "detail-" + id, "object-" + id, sem, value);
    }
    private static Contra contra(String id, String target, ContraType type, String value) {
        return new Contra(id, "snapshot-v9", "detail-" + target, "object-" + target, type, value);
    }
    private static Movement move(String id, Semantic semantic, String debit, String credit, Closing closing) {
        return new Movement(id, "snapshot-v9", "account-" + semantic, semantic, END,
                debit, credit, closing, null);
    }
    private static List<Coverage> complete(Kind kind, String... rows) {
        return Arrays.stream(rows).map(row -> new Coverage(kind, row, "snapshot-v9", "evidence-" + row)).toList();
    }
    private static Cell cell(Result result, Kind kind, String id) {
        return result.sheets().stream().filter(s -> s.template().contains("-" + kind + "-"))
                .findFirst().orElseThrow().cells().stream().filter(c -> c.rowId().equals(id)).findFirst().orElseThrow();
    }
    private static void eq(String expected, Cell actual) {
        check(actual.rawValue() != null && new BigDecimal(expected).compareTo(new BigDecimal(actual.rawValue())) == 0,
                actual.rowId() + " expected " + expected + " got " + actual.rawValue());
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void rejected(String code, Runnable action) {
        try { action.run(); } catch (IllegalArgumentException ex) {
            check(ex.getMessage().equals(code), "Expected " + code + " got " + ex.getMessage()); return;
        }
        throw new AssertionError("Expected " + code);
    }
    private static void bilateralReceivablesAndPayables() {
        var bs = List.of(balance("a", Semantic.RECEIVABLE, "1000"),
                balance("b", Semantic.RECEIVABLE_CONTRACT_ADVANCE, "-200"),
                balance("c", Semantic.PAYABLE, "-500"), balance("d", Semantic.PAYABLE, "100"));
        var out = calculate(input(Standard.CAS_2019, bs,
                List.of(contra("provision-a", "a", ContraType.IMPAIRMENT, "100")), List.of(),
                complete(Kind.BS, "A05", "L07", "A07", "L05")));
        eq("900", cell(out, Kind.BS, "A05")); eq("200", cell(out, Kind.BS, "L07"));
        eq("100", cell(out, Kind.BS, "A07")); eq("500", cell(out, Kind.BS, "L05"));
        check(cell(out, Kind.BS, "L07").trace().stream().anyMatch(t -> "RECLASSIFY".equals(t.operation())
                && "A05".equals(t.fromRow()) && "detail-b".equals(t.detailId())), "lost route lineage");
        check(cell(out, Kind.BS, "A05").trace().stream().anyMatch(t -> "MATCHED_CONTRA".equals(t.operation())
                && "object-a".equals(t.objectId())), "lost matched provision");
    }
    private static void smallEnterpriseDoesNotUseCasImpairment() {
        var bs = List.of(balance("a", Semantic.RECEIVABLE, "1000"), balance("b", Semantic.RECEIVABLE, "-200"));
        var out = calculate(input(Standard.SBE_2011, bs, List.of(), List.of(), complete(Kind.BS, "4", "34")));
        eq("1000", cell(out, Kind.BS, "4")); eq("200", cell(out, Kind.BS, "34"));
        rejected("CONTRA_NOT_APPLICABLE", () -> calculate(input(Standard.SBE_2011, bs,
                List.of(contra("p", "a", ContraType.IMPAIRMENT, "100")), List.of(), List.of())));
    }
    private static void ambiguousCreditIsNotGuessed() {
        rejected("CAS_ADVANCE_NATURE_REQUIRED", () -> calculate(input(Standard.CAS_2019,
                List.of(balance("b", Semantic.RECEIVABLE, "-200")), List.of(), List.of(), List.of())));
    }
    private static void orphanWrongObjectAndOverdeduction() {
        var bs = List.of(balance("a", Semantic.FIXED_ASSET, "100"));
        rejected("ORPHAN_OR_WRONG_OBJECT_CONTRA", () -> calculate(input(Standard.CAS_2019, bs,
                List.of(contra("p", "absent", ContraType.DEPRECIATION, "10")), List.of(), List.of())));
        rejected("ORPHAN_OR_WRONG_OBJECT_CONTRA", () -> calculate(input(Standard.CAS_2019, bs,
                List.of(new Contra("p", "snapshot-v9", "detail-a", "another-asset", ContraType.DEPRECIATION, "10")),
                List.of(), List.of())));
        rejected("CONTRA_EXCEEDS_MATCHED_ASSET", () -> calculate(input(Standard.CAS_2019, bs,
                List.of(contra("p", "a", ContraType.DEPRECIATION, "60"),
                        contra("q", "a", ContraType.IMPAIRMENT, "50")), List.of(), List.of())));
    }
    private static void fixedAssetsDifferByRegime() {
        var bs = List.of(balance("a", Semantic.FIXED_ASSET, "100"), balance("clear", Semantic.FIXED_CLEARING, "-5"));
        var cs = List.of(contra("depreciation", "a", ContraType.DEPRECIATION, "20"));
        var cas = calculate(input(Standard.CAS_2019, bs, cs, List.of(), complete(Kind.BS, "A22")));
        var sbe = calculate(input(Standard.SBE_2011, bs, cs, List.of(), complete(Kind.BS, "18", "19", "23")));
        eq("75", cell(cas, Kind.BS, "A22")); eq("100", cell(sbe, Kind.BS, "18"));
        eq("20", cell(sbe, Kind.BS, "19")); eq("80", cell(sbe, Kind.BS, "20")); eq("-5", cell(sbe, Kind.BS, "23"));
        check(cell(sbe, Kind.BS, "20").trace().stream().anyMatch(t -> "19".equals(t.fromRow())
                && new BigDecimal(t.rawValue()).signum() < 0), "formula sign missing");
    }
    private static void closingExcludedAndOrdinaryReversalCountedOnce() {
        var ms = List.of(move("sale", Semantic.REVENUE, "0", "100", Closing.NONE),
                move("return", Semantic.REVENUE, "20", "0", Closing.NONE),
                move("transfer", Semantic.REVENUE, "80", "0", Closing.MONTH_PROFIT_TRANSFER),
                new Movement("transfer-reversal", "snapshot-v9", "revenue", Semantic.REVENUE, END,
                        "0", "80", Closing.TRANSFER_REVERSAL, Closing.MONTH_PROFIT_TRANSFER));
        var out = calculate(input(Standard.CAS_2019, List.of(), List.of(), ms, complete(Kind.PL, "P01")));
        eq("80", cell(out, Kind.PL, "P01"));
        check(cell(out, Kind.PL, "P01").trace().stream().filter(t -> t.operation().equals("MOVEMENT")).count() == 2,
                "closing polluted revenue trace");
        rejected("REVERSAL_ORIGIN_REQUIRED", () -> calculate(input(Standard.CAS_2019, List.of(), List.of(),
                List.of(move("r", Semantic.REVENUE, "0", "80", Closing.TRANSFER_REVERSAL)), List.of())));
    }
    private static void researchAndInterestDifferences() {
        var ms = List.of(move("admin", Semantic.MANAGEMENT, "10", "0", Closing.NONE),
                move("research", Semantic.RESEARCH, "30", "0", Closing.NONE),
                move("interest", Semantic.INTEREST_EXPENSE, "8", "0", Closing.NONE),
                move("deposit", Semantic.INTEREST_INCOME, "0", "3", Closing.NONE));
        var cas = calculate(input(Standard.CAS_2019, List.of(), List.of(), ms,
                complete(Kind.PL, "P05", "P06", "P07", "P07a", "P07b")));
        eq("10", cell(cas, Kind.PL, "P05")); eq("30", cell(cas, Kind.PL, "P06"));
        eq("5", cell(cas, Kind.PL, "P07")); eq("8", cell(cas, Kind.PL, "P07a")); eq("3", cell(cas, Kind.PL, "P07b"));
        var sbe = calculate(input(Standard.SBE_2011, List.of(), List.of(), ms, complete(Kind.PL, "14", "17", "18", "19")));
        eq("40", cell(sbe, Kind.PL, "14")); eq("30", cell(sbe, Kind.PL, "17"));
        eq("5", cell(sbe, Kind.PL, "18")); eq("5", cell(sbe, Kind.PL, "19"));
    }
    private static void actualProfitFormulaUsesAmounts() {
        var ms = List.of(move("sale", Semantic.REVENUE, "0", "200", Closing.NONE),
                move("cost", Semantic.COST, "60", "0", Closing.NONE),
                move("admin", Semantic.MANAGEMENT, "25", "0", Closing.NONE),
                move("credit-loss", Semantic.CREDIT_LOSS, "10", "0", Closing.NONE),
                move("tax", Semantic.INCOME_TAX, "10", "0", Closing.NONE));
        var out = calculate(input(Standard.CAS_2019, List.of(), List.of(), ms,
                complete(Kind.PL, "P01", "P02", "P03", "P04", "P05", "P06", "P07", "P08", "P09", "P10", "P11",
                        "P12", "P13", "P14", "P16", "P17", "P19")));
        eq("-10", cell(out, Kind.PL, "P12")); eq("105", cell(out, Kind.PL, "P15")); eq("95", cell(out, Kind.PL, "P20"));
        check(cell(out, Kind.PL, "P22").rawValue() == null, "unknown OCI fabricated");
    }
    private static void unclosedProfitNotYtdAddedAgain() {
        var bs = List.of(balance("retained", Semantic.RETAINED, "-60"),
                balance("current", Semantic.CURRENT_PROFIT, "-35"),
                balance("remaining", Semantic.REMAINING_PROFIT, "-5"));
        var out = calculate(input(Standard.CAS_2019, bs, List.of(),
                List.of(move("annual-revenue", Semantic.REVENUE, "0", "1000", Closing.NONE)),
                complete(Kind.BS, "E08")));
        eq("100", cell(out, Kind.BS, "E08"));
    }
    private static void noImplicitZeroOrPartialAsComplete() {
        var out = calculate(input(Standard.CAS_2019,
                List.of(balance("cash", Semantic.CASH, "12")), List.of(), List.of(), List.of()));
        var cash = cell(out, Kind.BS, "A01");
        check(cash.rawValue() == null && !cash.trace().isEmpty(), "partial source must remain unknown");
        var zero = calculate(input(Standard.CAS_2019, List.of(), List.of(), List.of(), complete(Kind.BS, "A01")));
        check(cell(zero, Kind.BS, "A01").status().equals("ZERO"), "complete empty population must be explicit zero");
        rejected("UNSUPPORTED_ROW_CANNOT_BE_CERTIFIED", () -> calculate(input(Standard.CAS_2019,
                List.of(), List.of(), List.of(), complete(Kind.PL, "P23a"))));
    }
    private static void duplicatesScopePrecisionAndMode() {
        var a = balance("a", Semantic.CASH, "1");
        rejected("DUPLICATE_OR_MISSING_SOURCE", () -> calculate(input(Standard.CAS_2019, List.of(a, a), List.of(), List.of(), List.of())));
        rejected("MIXED_SNAPSHOT", () -> calculate(input(Standard.CAS_2019,
                List.of(new Balance("a", "stale", "acct", "detail", "object", Semantic.CASH, "1")), List.of(), List.of(), List.of())));
        rejected("POSTED_CNY_PRECISION", () -> calculate(input(Standard.CAS_2019, List.of(), List.of(),
                List.of(move("m", Semantic.COST, "1.005", "0", Closing.NONE)), List.of())));
        rejected("AMOUNT_OVERFLOW", () -> calculate(input(Standard.CAS_2019,
                List.of(balance("a", Semantic.CASH, "100000000000000000000")), List.of(), List.of(), List.of())));
        rejected("MIGRATION_BASELINE_NOT_SUPPORTED", () -> calculate(new Input(scope(Standard.CAS_2019),
                "OPENING_PLUS_YTD", List.of(), List.of(), List.of(), List.of())));
    }
    private static void cashAndFullReportGapsAreEnumerated() {
        for (Standard standard : Standard.values()) {
            var in = input(standard, List.of(), List.of(), List.of(), List.of());
            check(in.equals(input(standard, List.of(), List.of(), List.of(), List.of())), "input record equality");
            var out = calculate(in);
            check(out.equals(calculate(in)), "result record equality");
            var cash = out.sheets().get(2);
            check(cash.cells().size() == (standard == Standard.CAS_2019 ? 35 : 22), "cash row census");
            check(cash.cells().stream().allMatch(c -> c.rawValue() == null && !c.gaps().isEmpty()), "cash gaps absent");
            check(out.publicationGaps().contains("NOTES_REQUIRED"), "notes absent");
            check(out.publicationGaps().contains("EQUITY_CHANGES_STATEMENT_REQUIRED") == (standard == Standard.CAS_2019), "regime completeness");
            check(out.status().equals("BLOCKED_DRAFT"), "unauthorized publication status");
        }
    }
    public static void main(String[] args) {
        bilateralReceivablesAndPayables(); smallEnterpriseDoesNotUseCasImpairment();
        ambiguousCreditIsNotGuessed(); orphanWrongObjectAndOverdeduction(); fixedAssetsDifferByRegime();
        closingExcludedAndOrdinaryReversalCountedOnce(); researchAndInterestDifferences();
        actualProfitFormulaUsesAmounts(); unclosedProfitNotYtdAddedAgain(); noImplicitZeroOrPartialAsComplete();
        duplicatesScopePrecisionAndMode(); cashAndFullReportGapsAreEnumerated();
        System.out.println("ReportSourceEngineTest: 12 independent scenario groups passed");
    }
}

package com.lumira.finance.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public final class ReportCalculatorTest {
    private static BigDecimal m(String value) {
        return AccountingRules.money(value);
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }

    private static void rejected(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected rejection");
    }

    private static ReportCalculator.SourceMapping mapping(
            String source, String row, String amount, boolean subset) {
        return new ReportCalculator.SourceMapping(source, row, m(amount), subset);
    }

    private static void disclosureMappings() {
        // A caller cannot designate a second ordinary asset row as a disclosure escape hatch.
        rejected(
                () ->
                        ReportCalculator.mappedInputs(
                                "CAS-BS-2019-A2",
                                List.of(
                                        mapping("cash", "A01", "100", false),
                                        mapping("cash", "A02", "999", true)),
                                Map.of("cash", m("100"))));
        rejected(
                () ->
                        ReportCalculator.mappedInputs(
                                "SBE-BS-2011",
                                List.of(
                                        mapping("stock", "9", "100", false),
                                        mapping("stock", "10", "999", true)),
                                Map.of("stock", m("100"))));
        var allowed =
                ReportCalculator.mappedInputs(
                        "SBE-BS-2011",
                        List.of(
                                mapping("stock", "10", "30", true),
                                mapping("stock", "9", "100", false),
                                mapping("stock", "11", "70", true)),
                        Map.of("stock", m("100")));
        check(
                allowed.get("9").compareTo(m("100")) == 0
                        && allowed.get("10").compareTo(m("30")) == 0);
        // Distinct disjoint inventory subsets share the source's finite capacity.
        rejected(
                () ->
                        ReportCalculator.mappedInputs(
                                "SBE-BS-2011",
                                List.of(
                                        mapping("stock", "9", "100", false),
                                        mapping("stock", "10", "60", true),
                                        mapping("stock", "11", "60", true)),
                                Map.of("stock", m("100"))));
        rejected(
                () ->
                        ReportCalculator.mappedInputs(
                                "SBE-BS-2011",
                                List.of(
                                        mapping("stock", "9", "100", false),
                                        mapping("stock", "10", "20", true),
                                        mapping("stock", "10", "20", true)),
                                Map.of("stock", m("100"))));
        rejected(
                () ->
                        ReportCalculator.mappedInputs(
                                "SBE-BS-2011",
                                List.of(
                                        mapping("cash", "1", "100", false),
                                        mapping("cash", "10", "20", true)),
                                Map.of("cash", m("100"))));
        rejected(
                () ->
                        ReportCalculator.mappedInputs(
                                "SBE-BS-2011",
                                List.of(mapping("stock", "10", "100", false)),
                                Map.of("stock", m("100"))));
        rejected(
                () ->
                        ReportCalculator.mappedInputs(
                                "SBE-BS-2011",
                                List.of(mapping("stock", "10", "0", true)),
                                Map.of("stock", m("0"))));
        rejected(
                () ->
                        ReportCalculator.mappedInputs(
                                "SBE-BS-2011",
                                List.of(
                                        mapping("stock", "9", "100", false),
                                        mapping("stock", "10", "-20", true)),
                                Map.of("stock", m("100"))));
        // Signed losses/corrections stay signed, rather than being converted to absolute income.
        var loss =
                ReportCalculator.mappedInputs(
                        "CAS-PL-2019-A2",
                        List.of(
                                mapping("loss", "P09", "-100", false),
                                mapping("loss", "P09b", "-30", true)),
                        Map.of("loss", m("-100")));
        check(loss.get("P09b").compareTo(m("-30")) == 0);
        var sbeInterest =
                ReportCalculator.mappedInputs(
                        "SBE-PL-2011",
                        List.of(
                                mapping("interest", "18", "-30", false),
                                mapping("interest", "19", "-30", true)),
                        Map.of("interest", m("-30")));
        check(sbeInterest.get("19").compareTo(m("-30")) == 0);
        // CAS income is a negative granular finance-cost source, displayed positively in P07b.
        var casInterest =
                ReportCalculator.mappedInputs(
                        "CAS-PL-2019-A2",
                        List.of(
                                mapping("expense", "P07", "100", false),
                                        mapping("expense", "P07a", "100", true),
                                mapping("income", "P07", "-80", false),
                                        mapping("income", "P07b", "80", true)),
                        Map.of("expense", m("100"), "income", m("-80")));
        check(casInterest.get("P07").compareTo(m("20")) == 0);
        check(casInterest.get("P07b").compareTo(m("80")) == 0); // Can exceed the net parent row 20.
        rejected(
                () ->
                        ReportCalculator.mappedInputs(
                                "CAS-PL-2019-A2",
                                List.of(
                                        mapping("expense", "P07", "100", false),
                                        mapping("expense", "P07b", "30", true)),
                                Map.of("expense", m("100"))));
        rejected(
                () ->
                        ReportCalculator.mappedInputs(
                                "CAS-PL-2019-A2",
                                List.of(
                                        mapping("income", "P07", "-100", false),
                                        mapping("income", "P07b", "-30", true)),
                                Map.of("income", m("-100"))));
        var zero =
                ReportCalculator.mappedInputs(
                        "SBE-BS-2011",
                        List.of(
                                mapping("stock", "9", "0", false),
                                mapping("stock", "10", "0", true)),
                        Map.of("stock", m("0")));
        check(zero.get("10").signum() == 0);
    }

    public static void main(String[] args) {
        disclosureMappings();
        var counts =
                Map.of(
                        "CAS-BS-2019-A2",
                        72,
                        "CAS-PL-2019-A2",
                        42,
                        "CAS-CF-2019-A2",
                        35,
                        "SBE-BS-2011",
                        53,
                        "SBE-PL-2011",
                        32,
                        "SBE-CF-2011",
                        22);
        for (var template : counts.entrySet()) {
            var rows =
                    ReportDictionary.rows().stream()
                            .filter(r -> r.template().equals(template.getKey()))
                            .toList();
            check(rows.size() == template.getValue());
            check(rows.stream().map(ReportDictionary.Row::id).distinct().count() == rows.size());
            boolean sbe = template.getKey().startsWith("SBE");
            var formulas =
                    template.getKey().contains("-BS-")
                            ? ReportCalculator.balanceSheetFormulas(sbe)
                            : template.getKey().contains("-PL-")
                                    ? ReportCalculator.profitFormulas(sbe)
                                    : ReportCalculator.cashflowFormulas(sbe);
            ReportCalculator.validateDependencies(template.getKey(), Map.of(), formulas);
            check(
                    ReportCalculator.draft(template.getKey(), Map.of(), formulas).stream()
                            .allMatch(c -> c.status().equals("MISSING")));
            check(
                    !ReportDictionary.publicationErrors(
                                    template.getKey(),
                                    ReportCalculator.draft(template.getKey(), Map.of(), formulas))
                            .isEmpty());
        }
        var sbe =
                ReportCalculator.draft(
                        "SBE-BS-2011",
                        Map.of("18", m("100"), "19", m("20"), "10", m("999")),
                        ReportCalculator.balanceSheetFormulas(true));
        check(
                sbe.stream()
                                .filter(c -> c.rowId().equals("20"))
                                .findFirst()
                                .orElseThrow()
                                .value()
                                .compareTo(m("80"))
                        == 0);
        check(
                sbe.stream()
                        .filter(c -> c.rowId().equals("15"))
                        .findFirst()
                        .orElseThrow()
                        .status()
                        .equals("MISSING"));
        for (boolean small : List.of(true, false)) {
            String template = small ? "SBE-BS-2011" : "CAS-BS-2019-A2";
            var formulas = ReportCalculator.balanceSheetFormulas(small);
            Set<String> computed = new HashSet<>();
            for (var formula : formulas) computed.add(formula.rowId());
            Map<String, BigDecimal> explicit = new HashMap<>();
            for (var row : ReportDictionary.rows())
                if (row.template().equals(template) && !computed.contains(row.id()))
                    explicit.put(row.id(), m("0"));
            if (small) {
                explicit.put("1", m("100"));
                explicit.put("5", m("100"));
                explicit.put("33", m("500"));
                explicit.put("48", m("-300"));
                explicit.put("9", m("0"));
                explicit.put("10", m("999"));
                explicit.put("11", m("888"));
            } else {
                explicit.put("A01", m("100"));
                explicit.put("L01", m("20"));
                explicit.put("E01", m("90"));
                explicit.put("E04", m("10"));
                explicit.put("E02a", m("999"));
                explicit.put("L16a", m("888"));
            }
            var calculated = ReportCalculator.calculate(explicit, formulas);
            check(calculated.get(small ? "30" : "A34").compareTo(m(small ? "200" : "100")) == 0);
            check(calculated.get(small ? "53" : "E10").compareTo(m(small ? "200" : "100")) == 0);
        }
        var payableDetails =
                AccountingRules.splitDetails(Map.of("supplierA", m("-500"), "supplierB", m("100")));
        check(
                payableDetails.credit().compareTo(m("500")) == 0
                        && payableDetails.debit().compareTo(m("100")) == 0);
        rejected(() -> ReportCalculator.draft("SBE-BS-2011", Map.of("UNKNOWN", m("1")), List.of()));
        rejected(
                () ->
                        ReportCalculator.draft(
                                "SBE-BS-2011",
                                Map.of(),
                                List.of(
                                        new ReportCalculator.Formula(
                                                "1", List.of(new ReportCalculator.Term("2", 1))),
                                        new ReportCalculator.Formula(
                                                "2", List.of(new ReportCalculator.Term("1", 1))))));
        rejected(
                () ->
                        ReportCalculator.mappedInputs(
                                "SBE-BS-2011",
                                List.of(
                                        new ReportCalculator.SourceMapping(
                                                "inventory", "9", m("100"), false),
                                        new ReportCalculator.SourceMapping(
                                                "inventory", "10", m("100"), false)),
                                Map.of("inventory", m("100"))));
        rejected(
                () ->
                        ReportCalculator.mappedInputs(
                                "SBE-BS-2011", List.of(), Map.of("inventory", m("100"))));
        var inputs =
                ReportCalculator.mappedInputs(
                        "SBE-BS-2011",
                        List.of(
                                new ReportCalculator.SourceMapping(
                                        "inventory", "9", m("100"), false),
                                new ReportCalculator.SourceMapping(
                                        "inventory", "10", m("30"), true)),
                        Map.of("inventory", m("100")));
        check(inputs.size() == 2);
        var period = ReportCalculator.profitPeriod(2026, 2, ReportCalculator.ProfitColumn.MONTH);
        check(period.through().equals(LocalDate.of(2026, 2, 28)));
        check(
                ReportCalculator.profitPeriod(2026, 2, ReportCalculator.ProfitColumn.ANNUAL)
                        .through()
                        .equals(LocalDate.of(2026, 12, 31)));
        var acc = new AccountingRules.Account("income", "INCOME", "CREDIT", true, "P01");
        var lines = List.of(new AccountingRules.Line("income", m("0"), m("100"), false, null));
        var journals =
                List.of(
                        new ReportCalculator.DatedJournal(
                                LocalDate.of(2026, 1, 15), AccountingRules.ClosingType.NONE, lines),
                        new ReportCalculator.DatedJournal(
                                LocalDate.of(2026, 2, 15), AccountingRules.ClosingType.NONE, lines),
                        new ReportCalculator.DatedJournal(
                                LocalDate.of(2026, 2, 28),
                                AccountingRules.ClosingType.PROFIT_LOSS,
                                lines));
        check(
                ReportCalculator.profitForPeriod(Map.of("income", acc), journals, period)
                                .compareTo(m("100"))
                        == 0);
        check(
                ReportCalculator.profitForPeriod(
                                        Map.of("income", acc),
                                        journals,
                                        ReportCalculator.profitPeriod(
                                                2026,
                                                2,
                                                ReportCalculator.ProfitColumn.YEAR_TO_DATE))
                                .compareTo(m("200"))
                        == 0);
        for (boolean small : List.of(true, false)) {
            var bs =
                    small
                            ? Map.of("30", m("1451"), "53", m("1451"), "1", m("1225"))
                            : Map.of("A34", m("1451"), "E10", m("1451"), "A01", m("1225"));
            var pl = Map.of(small ? "32" : "P20", m("95"));
            var cf =
                    small
                            ? Map.of("7", m("50"), "21", m("0"), "22", m("1225"))
                            : Map.of("C10", m("50"), "C34", m("0"), "C35", m("1225"));
            check(
                    ReportCalculator.reconcile(
                                    small, bs, pl, cf, m("95"), m("1225"), m("0"), m("0"), m("-45"),
                                    m("95"))
                            .stream()
                            .allMatch(r -> r.status().equals("PASS")));
            check(
                    ReportCalculator.reconcile(
                                    small, bs, pl, cf, null, null, null, null, null, null)
                            .stream()
                            .anyMatch(r -> r.status().equals("MISSING")));
        }
        System.out.println("ReportCalculator acceptance assertions passed");
    }
}

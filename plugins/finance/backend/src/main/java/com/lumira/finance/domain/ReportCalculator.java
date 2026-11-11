package com.lumira.finance.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Closed arithmetic graph: references and signed sums only; never scripts, SQL or eval. */
public final class ReportCalculator {
    private ReportCalculator() {}

    public record Term(String rowId, int sign) {
        public Term {
            if (sign != 1 && sign != -1) throw new IllegalArgumentException("INVALID_SIGN");
        }
    }

    public record Formula(String rowId, List<Term> terms) {
        public Formula {
            terms = List.copyOf(terms);
        }
    }

    public record SourceMapping(
            String sourceId, String rowId, BigDecimal amount, boolean disclosureSubset) {}

    /** Dictionary-defined subset relation. A negative sign presents interest income positively. */
    private record Disclosure(String parentRow, int sourceSign) {}

    private static Map<String, Disclosure> disclosures(String template) {
        Map<String, Disclosure> rules = new LinkedHashMap<>();
        switch (template) {
            case "CAS-BS-2019-A2" -> {
                subsets(rules, "L16", "L16a", "L16b");
                subsets(rules, "E02", "E02a", "E02b");
            }
            case "CAS-PL-2019-A2" -> {
                subsets(rules, "P07", "P07a");
                rules.put("P07b", new Disclosure("P07", -1));
                subsets(rules, "P09", "P09a", "P09b");
            }
            case "SBE-BS-2011" -> subsets(rules, "9", "10", "11", "12", "13");
            case "SBE-PL-2011" -> {
                subsets(rules, "3", "4", "5", "6", "7", "8", "9", "10");
                subsets(rules, "11", "12", "13");
                subsets(rules, "14", "15", "16", "17");
                subsets(rules, "18", "19");
                subsets(rules, "22", "23");
                subsets(rules, "24", "25", "26", "27", "28", "29");
            }
            default -> {
                /* Cashflow templates have no disclosure subset rows. */
            }
        }
        return Map.copyOf(rules);
    }

    private static void subsets(Map<String, Disclosure> rules, String parent, String... children) {
        for (String child : children) rules.put(child, new Disclosure(parent, 1));
    }

    public record DatedJournal(
            LocalDate accountingDate,
            AccountingRules.ClosingType closingType,
            List<AccountingRules.Line> lines) {}

    public enum ProfitColumn {
        MONTH,
        QUARTER,
        YEAR_TO_DATE,
        ANNUAL,
        PREVIOUS_YEAR
    }

    public record Period(LocalDate from, LocalDate through) {}

    public record Reconciliation(String code, BigDecimal difference, String status) {}

    public static Period profitPeriod(int year, int month, ProfitColumn column) {
        LocalDate end =
                LocalDate.of(year, month, 1)
                        .withDayOfMonth(LocalDate.of(year, month, 1).lengthOfMonth());
        return switch (column) {
            case MONTH -> new Period(end.withDayOfMonth(1), end);
            case QUARTER -> new Period(LocalDate.of(year, ((month - 1) / 3) * 3 + 1, 1), end);
            case YEAR_TO_DATE -> new Period(LocalDate.of(year, 1, 1), end);
            case ANNUAL -> new Period(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
            case PREVIOUS_YEAR ->
                    new Period(LocalDate.of(year - 1, 1, 1), LocalDate.of(year - 1, 12, 31));
        };
    }

    public static BigDecimal profitForPeriod(
            Map<String, AccountingRules.Account> accounts,
            List<DatedJournal> posted,
            Period period) {
        return AccountingRules.profit(
                accounts,
                posted.stream()
                        .filter(j -> j.closingType() == AccountingRules.ClosingType.NONE)
                        .filter(
                                j ->
                                        !j.accountingDate().isBefore(period.from())
                                                && !j.accountingDate().isAfter(period.through()))
                        .flatMap(j -> j.lines().stream())
                        .toList());
    }

    /**
     * One granular signed source feeds one primary row and dictionary-defined disjoint subsets.
     * Sources must be split before netting: an interest-income source is negative in P07 and
     * positive in P07b, while SBE row 19 retains that negative sign. A disclosure flag cannot
     * create a new report route or bypass the source amount. This validates arithmetic lineage, not
     * professional approval of the source's accounting classification.
     */
    public static Map<String, BigDecimal> mappedInputs(
            String template,
            List<SourceMapping> mappings,
            Map<String, BigDecimal> expectedSourceAmounts) {
        Set<String> allowed = templateRows(template), primarySources = new HashSet<>();
        Map<String, Disclosure> disclosureRules = disclosures(template);
        Map<String, SourceMapping> primary = new LinkedHashMap<>();
        Set<List<String>> disclosureIdentities = new HashSet<>();
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (SourceMapping mapping : mappings) {
            if (!allowed.contains(mapping.rowId()))
                throw new IllegalArgumentException("UNKNOWN_REPORT_ROW:" + mapping.rowId());
            if (mapping.amount() == null || mapping.sourceId() == null)
                throw new IllegalArgumentException("MISSING_SOURCE");
            if (!expectedSourceAmounts.containsKey(mapping.sourceId()))
                throw new IllegalArgumentException("UNKNOWN_SOURCE:" + mapping.sourceId());
            if (!mapping.disclosureSubset()) {
                if (disclosureRules.containsKey(mapping.rowId()))
                    throw new IllegalArgumentException(
                            "DISCLOSURE_REQUIRES_PARENT:" + mapping.rowId());
                if (!primarySources.add(mapping.sourceId()))
                    throw new IllegalArgumentException(
                            "DUPLICATE_PRIMARY_MAPPING:" + mapping.sourceId());
                if (mapping.amount().compareTo(expectedSourceAmounts.get(mapping.sourceId())) != 0)
                    throw new IllegalArgumentException(
                            "SOURCE_MAPPING_DIFFERENCE:" + mapping.sourceId());
                primary.put(mapping.sourceId(), mapping);
            } else {
                if (!disclosureRules.containsKey(mapping.rowId()))
                    throw new IllegalArgumentException("INVALID_DISCLOSURE_ROW:" + mapping.rowId());
                if (!disclosureIdentities.add(List.of(mapping.sourceId(), mapping.rowId())))
                    throw new IllegalArgumentException(
                            "DUPLICATE_DISCLOSURE_MAPPING:" + mapping.sourceId());
            }
            result.merge(mapping.rowId(), mapping.amount(), BigDecimal::add);
        }
        for (var source : expectedSourceAmounts.entrySet())
            if (source.getValue().signum() != 0 && !primarySources.contains(source.getKey()))
                throw new IllegalArgumentException("UNMAPPED_NONZERO:" + source.getKey());
        Map<String, BigDecimal> disclosed = new LinkedHashMap<>();
        for (SourceMapping mapping : mappings) {
            if (!mapping.disclosureSubset()) continue;
            Disclosure rule = disclosureRules.get(mapping.rowId());
            SourceMapping parent = primary.get(mapping.sourceId());
            if (parent == null || !rule.parentRow().equals(parent.rowId()))
                throw new IllegalArgumentException("DISCLOSURE_PARENT_MISMATCH:" + mapping.rowId());
            BigDecimal signedSourcePart =
                    mapping.amount().multiply(BigDecimal.valueOf(rule.sourceSign()));
            if (signedSourcePart.signum() != 0
                    && signedSourcePart.signum() != parent.amount().signum())
                throw new IllegalArgumentException("DISCLOSURE_SIGN_MISMATCH:" + mapping.rowId());
            // CAS interest income is displayed positive; a positive expense source is not income.
            if (rule.sourceSign() == -1 && mapping.amount().signum() < 0)
                throw new IllegalArgumentException("DISCLOSURE_SIGN_MISMATCH:" + mapping.rowId());
            BigDecimal total =
                    disclosed.merge(mapping.sourceId(), signedSourcePart.abs(), BigDecimal::add);
            if (total.compareTo(parent.amount().abs()) > 0)
                throw new IllegalArgumentException(
                        "DISCLOSURE_EXCEEDS_SOURCE:" + mapping.sourceId());
        }
        return Map.copyOf(result);
    }

    public static List<ReportDictionary.Cell> draft(
            String template, Map<String, BigDecimal> inputs, List<Formula> formulas) {
        validateDependencies(template, inputs, formulas);
        Map<String, BigDecimal> values = new LinkedHashMap<>(inputs);
        Set<String> pending = new HashSet<>();
        for (Formula formula : formulas) pending.add(formula.rowId());
        boolean changed;
        do {
            changed = false;
            for (Formula formula : formulas)
                if (pending.contains(formula.rowId())
                        && formula.terms().stream().allMatch(t -> values.containsKey(t.rowId()))) {
                    BigDecimal amount = BigDecimal.ZERO;
                    for (Term term : formula.terms())
                        amount =
                                amount.add(
                                        values.get(term.rowId())
                                                .multiply(BigDecimal.valueOf(term.sign())));
                    values.put(formula.rowId(), amount);
                    pending.remove(formula.rowId());
                    changed = true;
                }
        } while (changed);
        return ReportDictionary.preview(template, values);
    }

    public static void validateDependencies(
            String template, Map<String, BigDecimal> inputs, List<Formula> formulas) {
        Set<String> allowed = templateRows(template);
        Map<String, Formula> definitions = new LinkedHashMap<>();
        for (var input : inputs.entrySet())
            if (!allowed.contains(input.getKey()) || input.getValue() == null)
                throw new IllegalArgumentException(
                        "UNKNOWN_OR_MISSING_REPORT_ROW:" + input.getKey());
        for (Formula formula : formulas) {
            if (!allowed.contains(formula.rowId()))
                throw new IllegalArgumentException("UNKNOWN_REPORT_ROW:" + formula.rowId());
            if (formula.terms().isEmpty()) throw new IllegalArgumentException("EMPTY_FORMULA");
            if (definitions.putIfAbsent(formula.rowId(), formula) != null
                    || inputs.containsKey(formula.rowId()))
                throw new IllegalArgumentException("DUPLICATE_ROW:" + formula.rowId());
            Set<String> terms = new HashSet<>();
            for (Term term : formula.terms()) {
                if (!allowed.contains(term.rowId()))
                    throw new IllegalArgumentException("UNKNOWN_REPORT_ROW:" + term.rowId());
                if (!terms.add(term.rowId()))
                    throw new IllegalArgumentException("DUPLICATE_FORMULA_TERM:" + term.rowId());
            }
        }
        for (String row : definitions.keySet())
            checkCycle(row, definitions, new HashSet<>(), new HashSet<>());
    }

    private static void checkCycle(
            String row,
            Map<String, Formula> definitions,
            Set<String> visiting,
            Set<String> complete) {
        if (complete.contains(row) || !definitions.containsKey(row)) return;
        if (!visiting.add(row)) throw new IllegalArgumentException("FORMULA_CYCLE:" + row);
        for (Term term : definitions.get(row).terms())
            checkCycle(term.rowId(), definitions, visiting, complete);
        visiting.remove(row);
        complete.add(row);
    }

    private static Set<String> templateRows(String template) {
        Set<String> allowed = new HashSet<>();
        for (ReportDictionary.Row row : ReportDictionary.rows())
            if (row.template().equals(template)) allowed.add(row.id());
        if (allowed.isEmpty()) throw new IllegalArgumentException("RULE_VERSION_UNAVAILABLE");
        return allowed;
    }

    public static Map<String, BigDecimal> calculate(
            Map<String, BigDecimal> inputs, List<Formula> formulas) {
        Map<String, Formula> definitions = new LinkedHashMap<>();
        for (Formula formula : formulas) {
            if (definitions.putIfAbsent(formula.rowId(), formula) != null
                    || inputs.containsKey(formula.rowId()))
                throw new IllegalArgumentException("DUPLICATE_ROW:" + formula.rowId());
        }
        Map<String, BigDecimal> values = new HashMap<>(inputs);
        Set<String> visiting = new HashSet<>(), completed = new HashSet<>();
        for (String row : definitions.keySet())
            resolve(row, definitions, values, visiting, completed);
        return Map.copyOf(values);
    }

    private static BigDecimal resolve(
            String row,
            Map<String, Formula> definitions,
            Map<String, BigDecimal> values,
            Set<String> visiting,
            Set<String> completed) {
        if (values.containsKey(row)) return values.get(row);
        if (!visiting.add(row)) throw new IllegalArgumentException("FORMULA_CYCLE:" + row);
        Formula formula = definitions.get(row);
        if (formula == null) throw new IllegalArgumentException("MISSING_SOURCE:" + row);
        BigDecimal result = BigDecimal.ZERO;
        for (Term term : formula.terms()) {
            BigDecimal value = resolve(term.rowId(), definitions, values, visiting, completed);
            result = term.sign() == 1 ? result.add(value) : result.subtract(value);
        }
        visiting.remove(row);
        completed.add(row);
        values.put(row, result);
        return result;
    }

    public static List<Formula> cashflowFormulas(boolean smallEnterprise) {
        if (smallEnterprise)
            return List.of(
                    sum("7", "1 2 -3 -4 -5 -6"),
                    sum("13", "8 9 10 -11 -12"),
                    sum("19", "14 15 -16 -17 -18"),
                    sum("20", "7 13 19"),
                    sum("22", "20 21"));
        return List.of(
                sum("C04", "C01 C02 C03"),
                sum("C09", "C05 C06 C07 C08"),
                sum("C10", "C04 -C09"),
                sum("C16", "C11 C12 C13 C14 C15"),
                sum("C21", "C17 C18 C19 C20"),
                sum("C22", "C16 -C21"),
                sum("C26", "C23 C24 C25"),
                sum("C30", "C27 C28 C29"),
                sum("C31", "C26 -C30"),
                sum("C33", "C10 C22 C31 C32"),
                sum("C35", "C33 C34"));
    }

    public static List<Formula> profitFormulas(boolean smallEnterprise) {
        if (smallEnterprise)
            return List.of(
                    sum("21", "1 -2 -3 -11 -14 -18 20"),
                    sum("30", "21 22 -24"),
                    sum("32", "30 -31"));
        return List.of(
                sum("P15", "P01 -P02 -P03 -P04 -P05 -P06 -P07 P08 P09 P10 P11 P12 P13 P14"),
                sum("P18", "P15 P16 -P17"),
                sum("P20", "P18 -P19"),
                sum("P21a", "P21a1 P21a2 P21a3 P21a4"),
                sum("P21b", "P21b1 P21b2 P21b3 P21b4 P21b5 P21b6"),
                sum("P21", "P21a P21b"),
                sum("P22", "P20 P21"));
    }

    public static List<Formula> balanceSheetFormulas(boolean smallEnterprise) {
        if (smallEnterprise)
            return List.of(
                    sum("15", "1 2 3 4 5 6 7 8 9 14"),
                    sum("20", "18 -19"),
                    sum("29", "16 17 20 21 22 23 24 25 26 27 28"),
                    sum("30", "15 29"),
                    sum("41", "31 32 33 34 35 36 37 38 39 40"),
                    sum("46", "42 43 44 45"),
                    sum("47", "41 46"),
                    sum("52", "48 49 50 51"),
                    sum("53", "47 52"));
        return List.of(
                sum("A14", "A01 A02 A03 A04 A05 A06 A07 A08 A09 A10 A11 A12 A13"),
                sum(
                        "A33",
                        "A15 A16 A17 A18 A19 A20 A21 A22 A23 A24 A25 A26 A27 A28 A29 A30 A31 A32"),
                sum("A34", "A14 A33"),
                sum("L14", "L01 L02 L03 L04 L05 L06 L07 L08 L09 L10 L11 L12 L13"),
                sum("L23", "L15 L16 L17 L18 L19 L20 L21 L22"),
                sum("L24", "L14 L23"),
                sum("E09", "E01 E02 E03 -E04 E05 E06 E07 E08"),
                sum("E10", "L24 E09"));
    }

    /**
     * Evidence inputs must use the same scope, period, currency and ledger watermark. Null means
     * MISSING.
     */
    public static List<Reconciliation> reconcile(
            boolean smallEnterprise,
            Map<String, BigDecimal> balanceSheet,
            Map<String, BigDecimal> profit,
            Map<String, BigDecimal> cashflow,
            BigDecimal ledgerNetProfit,
            BigDecimal cashScopeClosing,
            BigDecimal cashScopeOpening,
            BigDecimal cashScopeDifference,
            BigDecimal profitToOperatingAdjustments,
            BigDecimal equityProfitBridge) {
        String assets = smallEnterprise ? "30" : "A34",
                liabilitiesEquity = smallEnterprise ? "53" : "E10";
        String netProfit = smallEnterprise ? "32" : "P20",
                operating = smallEnterprise ? "7" : "C10";
        String cashEnd = smallEnterprise ? "22" : "C35", cashStart = smallEnterprise ? "21" : "C34";
        String money = smallEnterprise ? "1" : "A01";
        List<Reconciliation> result = new ArrayList<>();
        result.add(
                compare(
                        "BALANCE_SHEET_EQUATION",
                        balanceSheet.get(assets),
                        balanceSheet.get(liabilitiesEquity)));
        result.add(compare("PROFIT_TO_LEDGER", profit.get(netProfit), ledgerNetProfit));
        result.add(compare("CASH_CLOSING_TO_SCOPE", cashflow.get(cashEnd), cashScopeClosing));
        result.add(compare("CASH_OPENING_TO_SCOPE", cashflow.get(cashStart), cashScopeOpening));
        result.add(
                compare(
                        "MONETARY_FUNDS_SCOPE_BRIDGE",
                        balanceSheet.get(money),
                        add(cashScopeClosing, cashScopeDifference)));
        result.add(
                compare(
                        "PROFIT_TO_OPERATING_CASH",
                        cashflow.get(operating),
                        add(profit.get(netProfit), profitToOperatingAdjustments)));
        result.add(compare("EQUITY_PROFIT_BRIDGE", profit.get(netProfit), equityProfitBridge));
        return List.copyOf(result);
    }

    private static BigDecimal add(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? null : a.add(b);
    }

    private static Reconciliation compare(String code, BigDecimal left, BigDecimal right) {
        if (left == null || right == null) return new Reconciliation(code, null, "MISSING");
        BigDecimal delta = left.subtract(right);
        return new Reconciliation(code, delta, delta.signum() == 0 ? "PASS" : "DIFFERENCE");
    }

    private static Formula sum(String row, String expression) {
        List<Term> terms = new ArrayList<>();
        for (String token : expression.split(" "))
            terms.add(
                    new Term(
                            token.startsWith("-") ? token.substring(1) : token,
                            token.startsWith("-") ? -1 : 1));
        return new Formula(row, terms);
    }
}

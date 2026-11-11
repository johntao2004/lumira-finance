package com.lumira.finance.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Dependency-free executable acceptance assertions; run with java -ea. */
public final class AccountingRulesTest {
    private static BigDecimal m(String amount) {
        return AccountingRules.money(amount);
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }

    private static void rejected(Runnable call) {
        try {
            call.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected rejection");
    }

    public static void main(String[] args) {
        var cash = new AccountingRules.Account("cash", "ASSET", "DEBIT", false, "A01");
        var sales = new AccountingRules.Account("sales", "INCOME", "CREDIT", true, "P01");
        var accounts = Map.of("cash", cash, "sales", sales);
        check(m("0.000001").compareTo(new BigDecimal("0.000001")) == 0);
        check(m("0.00000001").scale() == 8);
        rejected(() -> m("0.000000001"));
        rejected(() -> m("100000000000000000000"));
        rejected(() -> m("1e2"));
        rejected(() -> m("NaN"));
        rejected(() -> m("Infinity"));
        rejected(() -> PrecisionRules.formalCurrencyMoney("1.001", 2));
        rejected(() -> PrecisionRules.formalCurrencyMoney("1.005", 2));
        rejected(() -> PrecisionRules.formalCurrencyMoney("1.000", 2));
        rejected(() -> PrecisionRules.formalCurrencyMoney("1.00000000", 2));
        rejected(() -> PrecisionRules.formalCurrencyMoney("-1.00", 2));
        check(PrecisionRules.formalCurrencyMoney("1.00", 2).scale() == 8);
        check(PrecisionRules.isFormalCurrencyMoney(new BigDecimal("1.00000000"), 2));
        check(!PrecisionRules.isFormalCurrencyMoney(new BigDecimal("1.00500000"), 2));
        check(m("99999999999999999999.99999999").precision() == 28);
        check(PrecisionRules.exchangeRate("9999999999999999.999999999999").precision() == 28);
        rejected(() -> PrecisionRules.exchangeRate("10000000000000000"));
        rejected(() -> PrecisionRules.exchangeRate("0"));
        rejected(() -> PrecisionRules.exchangeRate("1.0000000000001"));
        check(PrecisionRules.ratio("999999.999999999999").precision() == 18);
        rejected(() -> PrecisionRules.ratio("1000000"));
        rejected(() -> PrecisionRules.ratio("-0.1"));
        check(PrecisionRules.ratio("1.25").compareTo(new BigDecimal("1.25")) == 0);
        BigDecimal unrounded =
                new BigDecimal("100.00").multiply(PrecisionRules.exchangeRate("7.123456789012"));
        check(unrounded.compareTo(new BigDecimal("712.34567890120000")) == 0);
        var trace =
                PrecisionRules.roundForPosting(
                        unrounded, 2, java.math.RoundingMode.HALF_UP, "fx-post-v4");
        check(trace.sourceUnrounded().equals(unrounded.toPlainString()));
        check(
                trace.result().compareTo(m("712.35")) == 0
                        && trace.ruleVersion().equals("fx-post-v4"));
        rejected(
                () ->
                        PrecisionRules.roundForPosting(
                                unrounded, 2, java.math.RoundingMode.HALF_UP, ""));
        check(
                PrecisionRules.divide(
                                        BigDecimal.ONE,
                                        new BigDecimal("3"),
                                        40,
                                        java.math.RoundingMode.HALF_EVEN)
                                .result()
                                .length()
                        == 42);
        rejected(
                () ->
                        PrecisionRules.divide(
                                BigDecimal.ONE,
                                new BigDecimal("3"),
                                39,
                                java.math.RoundingMode.HALF_EVEN));
        var normal =
                List.of(
                        new AccountingRules.Line("cash", m("100"), m("0"), false, "A"),
                        new AccountingRules.Line("sales", m("0"), m("100"), false, "A"));
        check(AccountingRules.validateJournal(normal, accounts).isEmpty());
        for (String tooPrecise : List.of("1.001", "1.005")) {
            var invalidFormal =
                    List.of(
                            new AccountingRules.Line("cash", m(tooPrecise), m("0"), false, "A"),
                            new AccountingRules.Line("sales", m("0"), m(tooPrecise), false, "A"));
            check(!AccountingRules.validateJournal(invalidFormal, accounts).isEmpty());
        }
        var unbalanced =
                List.of(
                        normal.get(0),
                        new AccountingRules.Line("sales", m("0"), m("99.99"), false, "A"));
        check(
                AccountingRules.validateJournal(unbalanced, accounts)
                        .contains("UNBALANCED_JOURNAL:0.01000000"));
        var reversal =
                List.of(
                        new AccountingRules.Line("cash", m("0"), m("100"), true, "A"),
                        new AccountingRules.Line("sales", m("100"), m("0"), true, "A"));
        var balance =
                AccountingRules.trialBalance(
                        AccountingRules.trialBalance(Map.of(), normal), reversal);
        check(balance.values().stream().allMatch(v -> v.signum() == 0));
        check(AccountingRules.profit(accounts, normal).compareTo(m("100")) == 0);
        var split =
                AccountingRules.splitDetails(
                        Map.of("customerA", m("1000"), "customerB", m("-200")));
        check(split.debit().compareTo(m("1000")) == 0 && split.credit().compareTo(m("200")) == 0);
        check(
                AccountingRules.retainedEarnings(m("0"), m("40"), Map.of("expense", m("5")))
                                .compareTo(m("35"))
                        == 0);
        var incomplete =
                new AccountingRules.CashMovement(
                        "pay",
                        m("100"),
                        false,
                        false,
                        List.of(
                                new AccountingRules.CashAllocation("OP_PURCHASE", m("60"), true),
                                new AccountingRules.CashAllocation("OP_OTHER_OUT", m("30"), true)),
                        AccountingRules.CashDirection.OUT);
        check(
                AccountingRules.cashPrecheck("CAS-CF-2019-A2", List.of(incomplete))
                        .get(0)
                        .code()
                        .equals("CASH_ALLOCATION_DIFFERENCE"));
        for (String code : List.of("NOT_A_REPORT_CODE", "C04", "SBE_OP_SALES")) {
            var invalid =
                    new AccountingRules.CashMovement(
                            "bad",
                            m("100"),
                            false,
                            false,
                            List.of(new AccountingRules.CashAllocation(code, m("100"), true)),
                            AccountingRules.CashDirection.IN);
            check(
                    AccountingRules.cashPrecheck("CAS-CF-2019-A2", List.of(invalid)).stream()
                            .anyMatch(e -> e.code().equals("INVALID_CASHFLOW_LEAF")));
        }
        var refund =
                new AccountingRules.CashMovement(
                        "refund",
                        m("10"),
                        false,
                        false,
                        List.of(
                                new AccountingRules.CashAllocation(
                                        "OP_SALES", m("-10"), true, true, "original-receipt")),
                        AccountingRules.CashDirection.OUT);
        check(AccountingRules.cashPrecheck("CAS-CF-2019-A2", List.of(refund)).isEmpty());
        var arbitraryNegative =
                new AccountingRules.CashMovement(
                        "bad-negative",
                        m("10"),
                        false,
                        false,
                        List.of(new AccountingRules.CashAllocation("OP_SALES", m("-10"), true)),
                        AccountingRules.CashDirection.OUT);
        check(
                !AccountingRules.cashPrecheck("CAS-CF-2019-A2", List.of(arbitraryNegative))
                        .isEmpty());
        BigDecimal overflow = m("99999999999999999999.99999999").add(m("0.00000001"));
        check(!AccountingRules.isStorableAmount(overflow));
        check(
                AccountingRules.yearClosePrecheck(Map.of("cash", overflow), accounts, 0).stream()
                        .anyMatch(e -> e.startsWith("OPENING_AMOUNT_OVERFLOW")));
        rejected(
                () ->
                        AccountingRules.carryForward(
                                List.of(new AccountingRules.Opening("cash", "A", "CNY", overflow)),
                                Map.of("cash", "cash"),
                                accounts));
        rejected(
                () ->
                        AccountingRules.carryForward(
                                List.of(
                                        new AccountingRules.Opening(
                                                "cash",
                                                "A",
                                                "CNY",
                                                m("99999999999999999999.99999999")),
                                        new AccountingRules.Opening(
                                                "cash", "A", "CNY", m("0.00000001"))),
                                Map.of("cash", "cash"),
                                accounts));
        check(
                AccountingRules.cashBridge(
                                m("0"), m("50"), m("-120"), m("1295"), m("0"), m("1225"), false)
                        .isEmpty());
        check(!AccountingRules.monthClosePrecheck(1, m("0"), true, true, true).isEmpty());
        check(!AccountingRules.yearClosePrecheck(Map.of("sales", m("100")), accounts, 0).isEmpty());
        rejected(
                () ->
                        AccountingRules.carryForward(
                                List.of(new AccountingRules.Opening("sales", "A", "CNY", m("100"))),
                                Map.of("sales", "sales"),
                                accounts));
        check(!AccountingRules.transition("POSTED", "edit", "maker", "admin").isEmpty());
        check(!AccountingRules.transition("SUBMITTED", "approve", "maker", "maker").isEmpty());
        check(ReportDictionary.rows().size() == 256);
        check(ReportDictionary.preview("SBE-CF-2011", Map.of()).size() == 22);
        check(!ReportDictionary.publicationErrors("SBE-CF-2011", List.of()).isEmpty());
        var sbeProfit =
                ReportCalculator.calculate(
                        Map.ofEntries(
                                Map.entry("1", m("200")),
                                Map.entry("2", m("60")),
                                Map.entry("3", m("0")),
                                Map.entry("11", m("0")),
                                Map.entry("14", m("30")),
                                Map.entry("18", m("5")),
                                Map.entry("20", m("0")),
                                Map.entry("22", m("0")),
                                Map.entry("24", m("0")),
                                Map.entry("31", m("10"))),
                        ReportCalculator.profitFormulas(true));
        check(sbeProfit.get("32").compareTo(m("95")) == 0);
        rejected(
                () ->
                        ReportCalculator.calculate(
                                Map.of(),
                                List.of(
                                        new ReportCalculator.Formula(
                                                "A", List.of(new ReportCalculator.Term("B", 1))),
                                        new ReportCalculator.Formula(
                                                "B", List.of(new ReportCalculator.Term("A", 1))))));
        rejected(() -> ReportCalculator.calculate(Map.of(), ReportCalculator.profitFormulas(true)));
        for (var standard : AccountingRules.AccountingStandard.values()) {
            var closingAccounts = new java.util.HashMap<>(accounts);
            closingAccounts.put(
                    "expense",
                    new AccountingRules.Account("expense", "EXPENSE", "DEBIT", true, "P02"));
            closingAccounts.put(
                    "profit",
                    new AccountingRules.Account(
                            "profit",
                            "EQUITY",
                            "CREDIT",
                            false,
                            "E08",
                            AccountingRules.AccountPurpose.CURRENT_YEAR_PROFIT));
            closingAccounts.put(
                    "retained",
                    new AccountingRules.Account(
                            "retained",
                            "EQUITY",
                            "CREDIT",
                            false,
                            "E08",
                            AccountingRules.AccountPurpose.RETAINED_EARNINGS));
            var plan =
                    AccountingRules.planProfitClosing(
                            standard,
                            Map.of("sales", m("-100"), "expense", m("60"), "cash", m("40")),
                            closingAccounts);
            check(plan.drafts().size() == 2);
            check(plan.drafts().get(0).closingType() == AccountingRules.ClosingType.PROFIT_LOSS);
            check(plan.drafts().get(1).closingType() == AccountingRules.ClosingType.YEAR_END);
            check(plan.resultingBalances().get("retained").compareTo(m("-40")) == 0);
            check(
                    AccountingRules.yearClosePrecheck(plan.resultingBalances(), closingAccounts, 0)
                            .isEmpty());
            check(
                    AccountingRules.planProfitClosing(
                                    standard, plan.resultingBalances(), closingAccounts)
                            .drafts()
                            .isEmpty());
            var delta = new java.util.HashMap<>(plan.resultingBalances());
            delta.put("expense", m("5"));
            delta.put("cash", m("35"));
            var corrected = AccountingRules.planProfitClosing(standard, delta, closingAccounts);
            check(corrected.resultingBalances().get("retained").compareTo(m("-35")) == 0);
            var loss =
                    AccountingRules.planProfitClosing(
                            standard,
                            Map.of("sales", m("-20"), "expense", m("60"), "cash", m("-40")),
                            closingAccounts);
            check(loss.resultingBalances().get("retained").compareTo(m("40")) == 0);
            var existingProfit =
                    AccountingRules.planProfitClosing(
                            standard, Map.of("profit", m("-40"), "cash", m("40")), closingAccounts);
            check(
                    existingProfit.drafts().size() == 1
                            && existingProfit.drafts().get(0).closingType()
                                    == AccountingRules.ClosingType.YEAR_END);
            var journalSources = new java.util.ArrayList<>(plan.drafts());
            journalSources.add(
                    new AccountingRules.ClosingDraft(AccountingRules.ClosingType.NONE, normal));
            check(
                    AccountingRules.profitFromJournals(closingAccounts, journalSources)
                                    .compareTo(m("100"))
                            == 0);
            rejected(
                    () ->
                            AccountingRules.planProfitClosing(
                                    standard, Map.of("cash", m("1")), closingAccounts));
            rejected(() -> AccountingRules.planProfitClosing(standard, Map.of(), accounts));
        }
        System.out.println("AccountingRules acceptance assertions passed");
    }
}

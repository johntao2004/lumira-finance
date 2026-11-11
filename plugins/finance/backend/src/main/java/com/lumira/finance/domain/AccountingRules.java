package com.lumira.finance.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact CNY accounting primitives. Reversal lines already have exchanged debit/credit. */
public final class AccountingRules {
    private AccountingRules() {}

    public enum ClosingType {
        NONE,
        PROFIT_LOSS,
        YEAR_END
    }

    public enum AccountingStandard {
        CAS_2019,
        SBE_2011
    }

    public enum AccountPurpose {
        NONE,
        CURRENT_YEAR_PROFIT,
        RETAINED_EARNINGS
    }

    public record Account(
            String id,
            String category,
            String normalDirection,
            boolean profitLoss,
            String reportRow,
            AccountPurpose purpose) {
        public Account(
                String id,
                String category,
                String normalDirection,
                boolean profitLoss,
                String reportRow) {
            this(id, category, normalDirection, profitLoss, reportRow, AccountPurpose.NONE);
        }

        public Account {
            if (purpose == null) throw new IllegalArgumentException("ACCOUNT_PURPOSE_REQUIRED");
        }
    }

    public record ClosingDraft(ClosingType closingType, List<Line> lines) {
        public ClosingDraft {
            lines = List.copyOf(lines);
        }
    }

    public record ClosingPlan(
            AccountingStandard standard,
            List<ClosingDraft> drafts,
            Map<String, BigDecimal> resultingBalances) {
        public ClosingPlan {
            drafts = List.copyOf(drafts);
            resultingBalances = Map.copyOf(resultingBalances);
        }
    }

    public record Line(
            String accountId,
            BigDecimal debit,
            BigDecimal credit,
            boolean reversal,
            String dimensionKey) {}

    public record DetailSplit(BigDecimal debit, BigDecimal credit) {}

    public enum CashDirection {
        IN,
        OUT,
        UNSPECIFIED
    }

    public record CashAllocation(
            String code,
            BigDecimal amount,
            boolean confirmed,
            boolean refund,
            String originalMovementId) {
        public CashAllocation(String code, BigDecimal amount, boolean confirmed) {
            this(code, amount, confirmed, false, null);
        }
    }

    public record CashMovement(
            String id,
            BigDecimal amount,
            boolean internalTransfer,
            boolean nonCash,
            List<CashAllocation> allocations,
            CashDirection direction) {
        public CashMovement(
                String id,
                BigDecimal amount,
                boolean internalTransfer,
                boolean nonCash,
                List<CashAllocation> allocations) {
            this(id, amount, internalTransfer, nonCash, allocations, CashDirection.UNSPECIFIED);
        }
    }

    public record Check(String code, String message) {}

    public record Opening(
            String accountId, String dimensionKey, String currency, BigDecimal balance) {}

    public static BigDecimal money(String value) {
        return PrecisionRules.money(value);
    }

    private static boolean validAmount(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) return false;
        try {
            money(amount.toPlainString());
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public static boolean isStorableAmount(BigDecimal amount) {
        if (amount == null) return false;
        try {
            money(amount.toPlainString());
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public static List<String> validateJournal(List<Line> lines, Map<String, Account> accounts) {
        List<String> errors = new ArrayList<>();
        if (lines == null || lines.size() < 2) return List.of("AT_LEAST_TWO_LINES_REQUIRED");
        BigDecimal total = BigDecimal.ZERO;
        for (Line line : lines) {
            if (line == null) {
                errors.add("INVALID_LINE");
                continue;
            }
            if (!accounts.containsKey(line.accountId()))
                errors.add("UNKNOWN_ACCOUNT:" + line.accountId());
            if (!PrecisionRules.isFormalCurrencyMoney(line.debit(), 2)
                    || !PrecisionRules.isFormalCurrencyMoney(line.credit(), 2)) {
                errors.add("INVALID_AMOUNT:" + line.accountId());
                continue;
            }
            if ((line.debit().signum() == 0) == (line.credit().signum() == 0))
                errors.add("EXACTLY_ONE_SIDE_REQUIRED:" + line.accountId());
            total = total.add(line.debit()).subtract(line.credit());
        }
        if (total.signum() != 0) errors.add("UNBALANCED_JOURNAL:" + total.toPlainString());
        return List.copyOf(errors);
    }

    public static Map<String, BigDecimal> trialBalance(
            Map<String, BigDecimal> openings, List<Line> posted) {
        Map<String, BigDecimal> balances = new LinkedHashMap<>(openings);
        for (Line line : posted)
            balances.merge(line.accountId(), line.debit().subtract(line.credit()), BigDecimal::add);
        return Map.copyOf(balances);
    }

    /** Caller supplies only normal business/adjustment lines, excluding BOTH closing types. */
    public static BigDecimal profit(Map<String, Account> accounts, List<Line> normalBusinessLines) {
        BigDecimal result = BigDecimal.ZERO;
        for (Line line : normalBusinessLines) {
            Account account = accounts.get(line.accountId());
            if (account == null) throw new IllegalArgumentException("UNKNOWN_ACCOUNT");
            if (account.profitLoss()) result = result.add(line.credit()).subtract(line.debit());
        }
        return result;
    }

    public static DetailSplit splitDetails(Map<String, BigDecimal> detailBalances) {
        BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        for (BigDecimal balance : detailBalances.values()) {
            if (balance.signum() > 0) debit = debit.add(balance);
            else credit = credit.subtract(balance);
        }
        return new DetailSplit(debit, credit);
    }

    public static Map<String, BigDecimal> reportRows(
            Map<String, BigDecimal> balances, Map<String, Account> accounts) {
        Map<String, BigDecimal> rows = new LinkedHashMap<>();
        for (var balance : balances.entrySet()) {
            Account account = accounts.get(balance.getKey());
            if (account == null || account.reportRow() == null || account.reportRow().isBlank()) {
                if (balance.getValue().signum() != 0)
                    throw new IllegalArgumentException("UNMAPPED_NONZERO:" + balance.getKey());
                continue;
            }
            if (!List.of("DEBIT", "CREDIT").contains(account.normalDirection()))
                throw new IllegalArgumentException("INVALID_NORMAL_DIRECTION");
            rows.merge(
                    account.reportRow(),
                    "CREDIT".equals(account.normalDirection())
                            ? balance.getValue().negate()
                            : balance.getValue(),
                    BigDecimal::add);
        }
        return Map.copyOf(rows);
    }

    public static List<String> yearClosePrecheck(
            Map<String, BigDecimal> balances, Map<String, Account> accounts, int unpostedCount) {
        List<String> errors = new ArrayList<>();
        if (unpostedCount != 0) errors.add("UNPOSTED_JOURNALS:" + unpostedCount);
        BigDecimal total = BigDecimal.ZERO;
        for (var entry : balances.entrySet()) {
            if (!isStorableAmount(entry.getValue()))
                errors.add("OPENING_AMOUNT_OVERFLOW:" + entry.getKey());
            total = total.add(entry.getValue());
            Account account = accounts.get(entry.getKey());
            if (account == null) errors.add("UNKNOWN_ACCOUNT:" + entry.getKey());
            else if ((account.profitLoss()
                            || account.purpose() == AccountPurpose.CURRENT_YEAR_PROFIT
                            || "CURRENT_YEAR_PROFIT".equals(account.category()))
                    && entry.getValue().signum() != 0)
                errors.add("UNCLOSED_PROFIT:" + entry.getKey());
        }
        if (total.signum() != 0) errors.add("UNBALANCED_TRIAL:" + total.toPlainString());
        return List.copyOf(errors);
    }

    public static List<Check> cashPrecheck(List<CashMovement> movements) {
        return List.of(
                new Check(
                        "CASH_TEMPLATE_REQUIRED",
                        "Select an explicit CAS/SBE cashflow template version"));
    }

    public static List<Check> cashPrecheck(String templateId, List<CashMovement> movements) {
        List<Check> errors = new ArrayList<>();
        Map<String, CashDirection> codes = cashLeafCodes(templateId);
        for (CashMovement movement : movements) {
            if (!validAmount(movement.amount())) {
                errors.add(new Check("INVALID_CASH_AMOUNT", movement.id()));
                continue;
            }
            BigDecimal allocated = BigDecimal.ZERO;
            if (!movement.internalTransfer()
                    && !movement.nonCash()
                    && (movement.direction() == null
                            || movement.direction() == CashDirection.UNSPECIFIED))
                errors.add(new Check("CASH_DIRECTION_REQUIRED", movement.id()));
            for (CashAllocation allocation : movement.allocations()) {
                CashDirection naturalDirection = codes.get(allocation.code());
                if (naturalDirection == null) {
                    errors.add(
                            new Check("INVALID_CASHFLOW_LEAF", String.valueOf(allocation.code())));
                    continue;
                }
                boolean validRefund =
                        allocation.refund()
                                && allocation.originalMovementId() != null
                                && !allocation.originalMovementId().isBlank()
                                && !allocation.originalMovementId().equals(movement.id());
                if (!isStorableAmount(allocation.amount())
                        || allocation.amount().signum() == 0
                        || (allocation.amount().signum() < 0 ? !validRefund : allocation.refund())
                        || !allocation.confirmed()) {
                    errors.add(new Check("INVALID_CASHFLOW_REFUND_OR_AMOUNT", movement.id()));
                    continue;
                }
                allocated =
                        allocated.add(
                                naturalDirection == CashDirection.IN
                                        ? allocation.amount()
                                        : allocation.amount().negate());
            }
            if (movement.internalTransfer() || movement.nonCash()) {
                if (!movement.allocations().isEmpty())
                    errors.add(new Check("EXCLUDED_CASHFLOW_ALLOCATED", movement.id()));
            } else {
                BigDecimal expected =
                        movement.direction() == CashDirection.OUT
                                ? movement.amount().negate()
                                : movement.amount();
                if (allocated.compareTo(expected) != 0)
                    errors.add(
                            new Check(
                                    "CASH_ALLOCATION_DIFFERENCE",
                                    movement.id() + ":" + expected.subtract(allocated)));
            }
        }
        return List.copyOf(errors);
    }

    private static Map<String, CashDirection> cashLeafCodes(String templateId) {
        Map<String, CashDirection> codes = new LinkedHashMap<>();
        if ("CAS-CF-2019-A2".equals(templateId)) {
            for (String code :
                    List.of(
                            "OP_SALES",
                            "OP_TAX_REFUND",
                            "OP_OTHER_IN",
                            "INV_PRINCIPAL_IN",
                            "INV_RETURN_IN",
                            "INV_ASSET_DISPOSAL_IN",
                            "INV_BUSINESS_DISPOSAL_IN",
                            "INV_OTHER_IN",
                            "FIN_EQUITY_IN",
                            "FIN_DEBT_IN",
                            "FIN_OTHER_IN")) codes.put(code, CashDirection.IN);
            for (String code :
                    List.of(
                            "OP_PURCHASE",
                            "OP_PAYROLL",
                            "OP_TAX",
                            "OP_OTHER_OUT",
                            "INV_CAPEX",
                            "INV_INVEST_OUT",
                            "INV_BUSINESS_ACQUISITION_OUT",
                            "INV_OTHER_OUT",
                            "FIN_PRINCIPAL_OUT",
                            "FIN_DIV_INT_OUT",
                            "FIN_OTHER_OUT")) codes.put(code, CashDirection.OUT);
        } else if ("SBE-CF-2011".equals(templateId)) {
            for (String code :
                    List.of(
                            "SBE_OP_SALES",
                            "SBE_OP_OTHER_IN",
                            "SBE_INV_PRINCIPAL_IN",
                            "SBE_INV_RETURN_IN",
                            "SBE_INV_ASSET_DISPOSAL_IN",
                            "SBE_FIN_DEBT_IN",
                            "SBE_FIN_EQUITY_IN")) codes.put(code, CashDirection.IN);
            for (String code :
                    List.of(
                            "SBE_OP_PURCHASE",
                            "SBE_OP_PAYROLL",
                            "SBE_OP_TAX",
                            "SBE_OP_OTHER_OUT",
                            "SBE_INV_INVEST_OUT",
                            "SBE_INV_CAPEX",
                            "SBE_FIN_PRINCIPAL_OUT",
                            "SBE_FIN_INTEREST_OUT",
                            "SBE_FIN_DIVIDEND_OUT")) codes.put(code, CashDirection.OUT);
        } else throw new IllegalArgumentException("RULE_VERSION_UNAVAILABLE");
        return Map.copyOf(codes);
    }

    public static List<Opening> carryForward(
            List<Opening> source,
            Map<String, String> mapping,
            Map<String, Account> targetAccounts) {
        List<Opening> result = new ArrayList<>();
        for (Opening opening : source) {
            if (!isStorableAmount(opening.balance()))
                throw new IllegalArgumentException("OPENING_AMOUNT_OVERFLOW");
            if (!"CNY".equals(opening.currency()))
                throw new IllegalArgumentException("FOREIGN_CURRENCY_UNSUPPORTED");
            String target = mapping.get(opening.accountId());
            Account account = targetAccounts.get(target);
            if (account == null)
                throw new IllegalArgumentException("YEAR_MAPPING_MISSING:" + opening.accountId());
            if ((account.profitLoss()
                            || account.purpose() == AccountPurpose.CURRENT_YEAR_PROFIT
                            || "CURRENT_YEAR_PROFIT".equals(account.category()))
                    && opening.balance().signum() != 0)
                throw new IllegalArgumentException("PROFIT_OPENING_NOT_ZERO");
            result.add(
                    new Opening(
                            target, opening.dimensionKey(), opening.currency(), opening.balance()));
        }
        Map<List<String>, BigDecimal> merged = new LinkedHashMap<>();
        for (Opening opening : result) {
            List<String> key =
                    java.util.Arrays.asList(
                            opening.accountId(), opening.dimensionKey(), opening.currency());
            merged.merge(key, opening.balance(), BigDecimal::add);
            if (!isStorableAmount(merged.get(key)))
                throw new IllegalArgumentException("OPENING_AMOUNT_OVERFLOW");
        }
        return List.copyOf(result);
    }

    public static List<String> transition(
            String current, String action, String maker, String actor) {
        if ("POSTED".equals(current)) return List.of("POSTED_IMMUTABLE");
        if ("approve".equals(action) && java.util.Objects.equals(maker, actor))
            return List.of("SELF_APPROVAL_DENIED");
        boolean allowed =
                switch (action) {
                    case "edit", "submit" -> "DRAFT".equals(current);
                    case "approve", "return" -> "SUBMITTED".equals(current);
                    case "post" -> "APPROVED".equals(current);
                    case "void" -> List.of("DRAFT", "SUBMITTED", "APPROVED").contains(current);
                    default -> false;
                };
        return allowed ? List.of() : List.of("INVALID_TRANSITION");
    }

    /**
     * Remaining ledger profit balances only: previously transferred profit must not be added twice.
     */
    public static BigDecimal retainedEarnings(
            BigDecimal distributionCredit,
            BigDecimal currentProfitCredit,
            Map<String, BigDecimal> remainingProfitDebitBalances) {
        BigDecimal result = distributionCredit.add(currentProfitCredit);
        for (BigDecimal balance : remainingProfitDebitBalances.values())
            result = result.subtract(balance);
        return result;
    }

    public static List<Check> cashBridge(
            BigDecimal opening,
            BigDecimal operating,
            BigDecimal investing,
            BigDecimal financing,
            BigDecimal exchangeEffect,
            BigDecimal closing,
            boolean smallEnterprise) {
        List<Check> errors = new ArrayList<>();
        if (smallEnterprise && exchangeEffect.signum() != 0)
            errors.add(
                    new Check(
                            "SBE_FOREIGN_ADJUSTMENT_REQUIRED",
                            "Standard 22-row template has no exchange-effect row"));
        BigDecimal difference =
                closing.subtract(opening)
                        .subtract(operating)
                        .subtract(investing)
                        .subtract(financing)
                        .subtract(exchangeEffect);
        if (difference.signum() != 0)
            errors.add(new Check("CASH_BRIDGE_DIFFERENCE", difference.toPlainString()));
        return List.copyOf(errors);
    }

    public static List<String> monthClosePrecheck(
            int unpostedCount,
            BigDecimal trialDifference,
            boolean subledgersReconciled,
            boolean cashClassified,
            boolean reportsValidated) {
        List<String> errors = new ArrayList<>();
        if (unpostedCount != 0) errors.add("UNPOSTED_JOURNALS:" + unpostedCount);
        if (trialDifference.signum() != 0) errors.add("UNBALANCED_TRIAL");
        if (!subledgersReconciled) errors.add("SUBLEDGER_DIFFERENCE");
        if (!cashClassified) errors.add("CASHFLOW_INCOMPLETE");
        if (!reportsValidated) errors.add("REPORT_VALIDATION_INCOMPLETE");
        return List.copyOf(errors);
    }

    /**
     * Plans only; the caller must bind scope, source version, period and approval before posting.
     */
    public static ClosingPlan planProfitClosing(
            AccountingStandard standard,
            Map<String, BigDecimal> signedDebitBalances,
            Map<String, Account> accounts) {
        if (standard == null) throw new IllegalArgumentException("ACCOUNTING_STANDARD_REQUIRED");
        String current = purposeAccount(accounts, AccountPurpose.CURRENT_YEAR_PROFIT);
        String retained = purposeAccount(accounts, AccountPurpose.RETAINED_EARNINGS);
        BigDecimal trial = BigDecimal.ZERO;
        List<Line> profitLines = new ArrayList<>();
        BigDecimal remainingProfitDebit = BigDecimal.ZERO;
        for (var entry : signedDebitBalances.entrySet()) {
            if (!isStorableAmount(entry.getValue()))
                throw new IllegalArgumentException("BALANCE_AMOUNT_OVERFLOW");
            Account account = accounts.get(entry.getKey());
            if (account == null)
                throw new IllegalArgumentException("UNKNOWN_ACCOUNT:" + entry.getKey());
            trial = trial.add(entry.getValue());
            if (account.profitLoss() && entry.getValue().signum() != 0) {
                profitLines.add(signedLine(entry.getKey(), entry.getValue().negate()));
                remainingProfitDebit = remainingProfitDebit.add(entry.getValue());
            }
        }
        if (trial.signum() != 0) throw new IllegalArgumentException("UNBALANCED_TRIAL");
        if (remainingProfitDebit.signum() != 0)
            profitLines.add(signedLine(current, remainingProfitDebit));
        List<ClosingDraft> drafts = new ArrayList<>();
        Map<String, BigDecimal> balances = new LinkedHashMap<>(signedDebitBalances);
        if (!profitLines.isEmpty()) {
            if (!validateJournal(profitLines, accounts).isEmpty())
                throw new IllegalArgumentException("INVALID_PROFIT_CLOSING");
            drafts.add(new ClosingDraft(ClosingType.PROFIT_LOSS, profitLines));
            balances = new LinkedHashMap<>(trialBalance(balances, profitLines));
        }
        BigDecimal currentBalance = balances.getOrDefault(current, BigDecimal.ZERO);
        if (!isStorableAmount(currentBalance))
            throw new IllegalArgumentException("BALANCE_AMOUNT_OVERFLOW");
        if (currentBalance.signum() != 0) {
            List<Line> yearLines =
                    List.of(
                            signedLine(current, currentBalance.negate()),
                            signedLine(retained, currentBalance));
            if (!validateJournal(yearLines, accounts).isEmpty())
                throw new IllegalArgumentException("INVALID_YEAR_CLOSING");
            drafts.add(new ClosingDraft(ClosingType.YEAR_END, yearLines));
            balances = new LinkedHashMap<>(trialBalance(balances, yearLines));
        }
        if (!yearClosePrecheck(balances, accounts, 0).isEmpty())
            throw new IllegalArgumentException("INVALID_CLOSING_RESULT");
        return new ClosingPlan(standard, drafts, balances);
    }

    private static String purposeAccount(Map<String, Account> accounts, AccountPurpose purpose) {
        List<Account> matches =
                accounts.values().stream().filter(a -> a.purpose() == purpose).toList();
        if (matches.size() != 1)
            throw new IllegalArgumentException("PURPOSE_ACCOUNT_REQUIRED:" + purpose);
        Account account = matches.get(0);
        if (account.profitLoss()
                || !"CREDIT".equals(account.normalDirection())
                || !"EQUITY".equals(account.category())
                || !account.equals(accounts.get(account.id())))
            throw new IllegalArgumentException("INVALID_PURPOSE_ACCOUNT:" + purpose);
        return account.id();
    }

    private static Line signedLine(String accountId, BigDecimal signedDebit) {
        money(signedDebit.toPlainString());
        return signedDebit.signum() > 0
                ? new Line(accountId, signedDebit, BigDecimal.ZERO, false, null)
                : new Line(accountId, BigDecimal.ZERO, signedDebit.negate(), false, null);
    }

    /** Typed source prevents either closing batch from erasing the ordinary profit movement. */
    public static BigDecimal profitFromJournals(
            Map<String, Account> accounts, List<ClosingDraft> journals) {
        return profit(
                accounts,
                journals.stream()
                        .filter(j -> j.closingType() == ClosingType.NONE)
                        .flatMap(j -> j.lines().stream())
                        .toList());
    }
}

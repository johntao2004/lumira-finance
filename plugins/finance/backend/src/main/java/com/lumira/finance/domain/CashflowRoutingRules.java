package com.lumira.finance.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

/** Executes an explicitly approved transaction rule; does not select or approve accounting policy. */
public final class CashflowRoutingRules {
    private CashflowRoutingRules() {}
    public enum ReportScope { INDIVIDUAL, CONSOLIDATED }
    public enum TransactionForm { SHARE_CONTROL, ABSORPTION_OR_BUSINESS }
    public enum Action { ACQUIRE, DISPOSE }
    public record Context(String reportingEntity, Set<String> reportingEntitySet, ReportScope scope,
                          String cashScopeVersion, String templateVersion, LocalDate date) {
        public Context {
            if (reportingEntity == null || reportingEntity.isBlank() || reportingEntitySet == null
                    || reportingEntitySet.isEmpty()
                    || reportingEntitySet.stream().anyMatch(id -> id == null || id.isBlank()))
                throw new IllegalArgumentException("MISSING_REPORT_SCOPE");
            reportingEntitySet = Set.copyOf(reportingEntitySet);
        }
    }
    public record Rule(String id, String version, String approvalStatus, String approvedBy, String sourceReference,
                       Context applicability, TransactionForm form, LocalDate effectiveFrom,
                       LocalDate effectiveThrough, String acquireRow, String disposeRow,
                       boolean disposalCostsAllowed) {}
    public record Transaction(Action action, TransactionForm form, BigDecimal cashPrice,
                              BigDecimal targetCash, BigDecimal disposalCosts,
                              boolean commonControl, boolean controlEvidenceVerified) {}
    public record DraftResult(String row, BigDecimal amount, String ruleId, String ruleVersion,
                              BigDecimal grossPrice, BigDecimal cashRemovedFromScope,
                              BigDecimal disposalCosts, String status) {}

    private static boolean text(String value) { return value != null && !value.isBlank(); }
    private static void require(boolean condition, String error) {
        if (!condition) throw new IllegalArgumentException(error);
    }
    private static void context(Context c) {
        require(c != null && text(c.reportingEntity()) && c.scope() != null && c.date() != null
                && text(c.cashScopeVersion()) && "CAS-CF-2019-A2".equals(c.templateVersion())
                && c.reportingEntitySet().contains(c.reportingEntity()), "MISSING_REPORT_SCOPE");
        require(c.scope() != ReportScope.INDIVIDUAL || c.reportingEntitySet().equals(Set.of(c.reportingEntity())),
                "INDIVIDUAL_ENTITY_SET_MISMATCH");
    }
    public static DraftResult route(Context context, Rule rule, Transaction transaction) {
        context(context);
        require(rule != null && text(rule.id()) && text(rule.version()) && "APPROVED".equals(rule.approvalStatus()) && text(rule.approvedBy())
                && text(rule.sourceReference()), "APPROVED_MAPPING_REQUIRED");
        Context bound = rule.applicability();
        context(bound);
        require(bound.reportingEntity().equals(context.reportingEntity())
                && bound.reportingEntitySet().equals(context.reportingEntitySet())
                && bound.scope() == context.scope()
                && bound.cashScopeVersion().equals(context.cashScopeVersion())
                && bound.templateVersion().equals(context.templateVersion()), "MAPPING_SCOPE_MISMATCH");
        require(rule.effectiveFrom() != null && rule.effectiveThrough() != null
                && !rule.effectiveThrough().isBefore(rule.effectiveFrom())
                && !context.date().isBefore(rule.effectiveFrom())
                && !context.date().isAfter(rule.effectiveThrough()), "MAPPING_OUTSIDE_EFFECTIVE_PERIOD");
        require(transaction != null && transaction.action() != null && transaction.form() != null
                && transaction.form() == rule.form(), "COMBINATION_FORM_MISMATCH");
        require(!transaction.commonControl(), "SPECIAL_COMBINATION_RULE_REQUIRED");
        require(transaction.controlEvidenceVerified(), "CONTROL_EVIDENCE_REQUIRED");
        boolean controlRows = "C19".equals(rule.acquireRow()) && "C14".equals(rule.disposeRow());
        boolean investmentRows = "C18".equals(rule.acquireRow()) && "C11".equals(rule.disposeRow());
        require(controlRows || investmentRows, "MAPPING_CODES_UNSUPPORTED");
        require(!investmentRows || context.scope() == ReportScope.INDIVIDUAL
                && transaction.form() == TransactionForm.SHARE_CONTROL, "MAPPING_SCOPE_MISMATCH");
        BigDecimal price = nonnegative(transaction.cashPrice());
        BigDecimal targetCash = nonnegative(transaction.targetCash());
        BigDecimal costs = nonnegative(transaction.disposalCosts());
        require(transaction.action() != Action.ACQUIRE || costs.signum() == 0, "ACQUISITION_COST_RULE_REQUIRED");
        require(costs.signum() == 0 || controlRows && rule.disposalCostsAllowed(), "SPECIAL_DISPOSAL_COST_RULE_REQUIRED");
        boolean deductTarget = context.scope() == ReportScope.CONSOLIDATED
                || transaction.form() == TransactionForm.ABSORPTION_OR_BUSINESS;
        BigDecimal removed = deductTarget ? targetCash : BigDecimal.ZERO;
        BigDecimal net = price.subtract(removed).subtract(costs);
        require(AccountingRules.isStorableAmount(net), "CASH_RESULT_OVERFLOW");
        String row = transaction.action() == Action.ACQUIRE ? rule.acquireRow() : rule.disposeRow();
        if (net.signum() < 0) row = transaction.action() == Action.ACQUIRE ? "C15" : "C20";
        return new DraftResult(row, net.abs(), rule.id(), rule.version(), price, removed, costs, "DRAFT");
    }
    private static BigDecimal nonnegative(BigDecimal value) {
        require(AccountingRules.isStorableAmount(value) && value.signum() >= 0, "INVALID_CASH_AMOUNT");
        return value;
    }

    /**
     * Call only for one frozen cash range. This compatibility signature cannot establish that the
     * cashflow input is complete/confirmed, so callers must use the explicit confirmation overload.
     */
    public static BigDecimal exchangeEffect(Context context, BigDecimal openingUnits, BigDecimal openingRate,
            BigDecimal closingUnits, BigDecimal closingRate, BigDecimal cashflowsBook,
            boolean unresolvedNonFxChange) {
        throw new IllegalArgumentException("CASHFLOWS_CONFIRMATION_REQUIRED");
    }

    /**
     * Returns an exact internal working value, potentially with more than eight fractional digits.
     * No storage/posting quantization is implied; apply an explicit approved rounding node later.
     * Scope changes and incomplete/unconfirmed cashflows forbid residual attribution to FX.
     */
    public static BigDecimal exchangeEffect(Context context, BigDecimal openingUnits, BigDecimal openingRate,
            BigDecimal closingUnits, BigDecimal closingRate, BigDecimal cashflowsBook,
            boolean unresolvedNonFxChange, boolean cashflowsCompleteAndConfirmed) {
        context(context);
        require(!unresolvedNonFxChange, "CASH_SCOPE_RECONCILIATION_REQUIRED");
        require(cashflowsCompleteAndConfirmed, "CASHFLOWS_CONFIRMATION_REQUIRED");
        BigDecimal open = nonnegative(openingUnits), close = nonnegative(closingUnits);
        require(AccountingRules.isStorableAmount(cashflowsBook), "INVALID_CASHFLOWS_AMOUNT");
        BigDecimal startRate = PrecisionRules.exchangeRate(openingRate == null ? null : openingRate.toPlainString());
        BigDecimal endRate = PrecisionRules.exchangeRate(closingRate == null ? null : closingRate.toPlainString());
        return close.multiply(endRate).subtract(open.multiply(startRate)).subtract(cashflowsBook);
    }
}

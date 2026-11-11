package com.lumira.finance.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/** Applies a reviewed entity/detail-specific presentation decision; never resolves legal ambiguity. */
public final class SbeTaxPresentationRules {
    private SbeTaxPresentationRules() {}
    public enum TaxKind { ORDINARY_NON_VAT, VAT }
    public enum Treatment { SBE_GENERAL_NEGATIVE, REVIEWED_VAT_ASSET }
    public record Context(String entityId, String taxDetailId, TaxKind taxKind,
                          String templateVersion, LocalDate date) {}
    public record Rule(String id, String version, String approvalStatus, String approvedBy, String legalSource,
                       String entityId, String taxDetailId, TaxKind taxKind, String templateVersion,
                       LocalDate effectiveFrom, LocalDate effectiveThrough, Treatment treatment) {}
    public record DraftResult(Map<String,BigDecimal> exclusiveRowAmounts, String ruleId,
                              String ruleVersion, String status) {
        public DraftResult { exclusiveRowAmounts = Map.copyOf(exclusiveRowAmounts); }
    }
    private static boolean text(String s) { return s != null && !s.isBlank(); }
    private static void require(boolean value, String code) { if (!value) throw new IllegalArgumentException(code); }
    public static DraftResult present(Context context, BigDecimal debitBalance, Rule rule) {
        require(context != null && text(context.entityId()) && text(context.taxDetailId())
                && context.taxKind() != null && context.date() != null
                && "SBE-BS-2011".equals(context.templateVersion()), "MISSING_TAX_PRESENTATION_SCOPE");
        require(AccountingRules.isStorableAmount(debitBalance) && debitBalance.signum() >= 0, "INVALID_TAX_DEBIT_BALANCE");
        require(rule != null && text(rule.id()) && text(rule.version()) && "APPROVED".equals(rule.approvalStatus()) && text(rule.approvedBy())
                && text(rule.legalSource()) && rule.treatment() != null, "TAX_PRESENTATION_RULE_REQUIRED");
        require(context.entityId().equals(rule.entityId()) && context.taxDetailId().equals(rule.taxDetailId())
                && context.taxKind() == rule.taxKind() && context.templateVersion().equals(rule.templateVersion()),
                "TAX_PRESENTATION_RULE_SCOPE_MISMATCH");
        require(rule.effectiveFrom() != null && rule.effectiveThrough() != null
                && !rule.effectiveThrough().isBefore(rule.effectiveFrom())
                && !context.date().isBefore(rule.effectiveFrom()) && !context.date().isAfter(rule.effectiveThrough()),
                "TAX_PRESENTATION_RULE_EXPIRED");
        boolean ordinary = context.taxKind() == TaxKind.ORDINARY_NON_VAT
                && rule.treatment() == Treatment.SBE_GENERAL_NEGATIVE;
        boolean vat = context.taxKind() == TaxKind.VAT && rule.treatment() == Treatment.REVIEWED_VAT_ASSET;
        require(ordinary || vat, "TAX_PRESENTATION_RULE_SCOPE_MISMATCH");
        // Both routes are supplied together: the same detail cannot also appear as negative tax liability.
        Map<String,BigDecimal> rows = ordinary ? Map.of("36",debitBalance.negate(),"14",BigDecimal.ZERO)
                : Map.of("36",BigDecimal.ZERO,"14",debitBalance);
        return new DraftResult(rows,rule.id(),rule.version(),"DRAFT");
    }
}

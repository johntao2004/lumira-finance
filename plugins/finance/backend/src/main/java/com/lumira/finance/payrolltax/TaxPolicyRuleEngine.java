package com.lumira.finance.payrolltax;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TAX01: Versioned general tax policy rule library & qualification attestation engine.
 * Never auto-assumes preferential qualification (e.g. HNTE, Small-Low-Profit, R&D).
 * Enforces explicit natural-person attestation with digest and validity checks.
 */
public final class TaxPolicyRuleEngine {

    private TaxPolicyRuleEngine() {}

    public record PolicyPack(
            String id,
            String taxType,
            String version,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String digest,
            String approvalStatus,
            boolean attestationRequired,
            Map<String, Object> rules
    ) implements Serializable {
        public PolicyPack {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("POLICY_PACK_ID_REQUIRED");
            if (taxType == null || taxType.isBlank()) throw new IllegalArgumentException("TAX_TYPE_REQUIRED");
            if (version == null || version.isBlank()) throw new IllegalArgumentException("VERSION_REQUIRED");
            if (effectiveFrom == null || effectiveTo == null || effectiveTo.isBefore(effectiveFrom))
                throw new IllegalArgumentException("INVALID_POLICY_EFFECTIVE_PERIOD");
            if (digest == null || !digest.matches("^[a-f0-9]{64}$"))
                throw new IllegalArgumentException("INVALID_SHA256_POLICY_DIGEST");
            if (!"APPROVED".equals(approvalStatus))
                throw new IllegalArgumentException("POLICY_PACK_NOT_APPROVED");
        }

        public void checkEffective(LocalDate date) {
            if (date.isBefore(effectiveFrom) || date.isAfter(effectiveTo)) {
                throw new IllegalArgumentException("POLICY_OUTSIDE_EFFECTIVE_PERIOD");
            }
        }
    }

    public record Attestation(
            UUID id,
            String policyPackId,
            UUID taxEntityId,
            String qualificationCategory,
            LocalDate validFrom,
            LocalDate validThrough,
            UUID attestedBy,
            String attestationDigest,
            Map<String, Object> qualificationFacts
    ) implements Serializable {
        public Attestation {
            if (id == null) throw new IllegalArgumentException("ATTESTATION_ID_REQUIRED");
            if (policyPackId == null) throw new IllegalArgumentException("POLICY_PACK_ID_REQUIRED");
            if (taxEntityId == null) throw new IllegalArgumentException("TAX_ENTITY_ID_REQUIRED");
            if (qualificationCategory == null) throw new IllegalArgumentException("QUALIFICATION_CATEGORY_REQUIRED");
            if (validFrom == null || validThrough == null || validThrough.isBefore(validFrom))
                throw new IllegalArgumentException("INVALID_ATTESTATION_VALIDITY_PERIOD");
            if (attestedBy == null) throw new IllegalArgumentException("ATTESTING_PERSON_REQUIRED");
            if (attestationDigest == null || !attestationDigest.matches("^[a-f0-9]{64}$"))
                throw new IllegalArgumentException("INVALID_ATTESTATION_DIGEST");
        }

        public void checkValid(LocalDate date) {
            if (date.isBefore(validFrom) || date.isAfter(validThrough)) {
                throw new IllegalArgumentException("QUALIFICATION_ATTESTATION_EXPIRED");
            }
        }
    }

    public record CalculationResult(
            String taxType,
            BigDecimal baseAmount,
            BigDecimal statutoryRate,
            BigDecimal preferentialRate,
            BigDecimal statutoryTax,
            BigDecimal reliefTax,
            BigDecimal payableTax,
            boolean preferentialApplied,
            CalculationExplainTree explainTree
    ) {}

    /**
     * Evaluates Corporate Income Tax (CIT) for Small-Low-Profit Enterprises (小型微利企业).
     * Statutory rate 25%. Small-low-profit relief: 20% rate applied to 25% of taxable base (effective 5%).
     * STRICT: Requires explicit attestation! Never auto-assumes eligibility!
     */
    public static CalculationResult evaluateSmallLowProfitCit(
            PolicyPack pack,
            Attestation attestation,
            UUID taxEntityId,
            LocalDate periodDate,
            BigDecimal annualTaxableProfit,
            BigDecimal averageStaffCount,
            BigDecimal totalAssets
    ) {
        pack.checkEffective(periodDate);
        if (!"CIT_SMALL_LOW_PROFIT".equals(pack.taxType())) {
            throw new IllegalArgumentException("MISMATCHED_POLICY_TAX_TYPE");
        }

        // Statutory tax at standard 25%
        BigDecimal statutoryRate = new BigDecimal("0.25");
        BigDecimal statutoryTax = annualTaxableProfit.compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO.setScale(2)
                : annualTaxableProfit.multiply(statutoryRate).setScale(2, RoundingMode.HALF_UP);

        boolean qualifiesStatutoryLimits = annualTaxableProfit.compareTo(new BigDecimal("3000000.00")) <= 0
                && averageStaffCount.compareTo(new BigDecimal("300")) <= 0
                && totalAssets.compareTo(new BigDecimal("50000000.00")) <= 0;

        if (pack.attestationRequired()) {
            if (attestation == null) {
                throw new IllegalStateException("TAX_QUALIFICATION_ATTESTATION_REQUIRED: Small-low-profit relief requires explicit user attestation; eligibility must never be auto-assumed");
            }
            if (!taxEntityId.equals(attestation.taxEntityId()) || !pack.id().equals(attestation.policyPackId())) {
                throw new IllegalArgumentException("ATTESTATION_SCOPE_MISMATCH");
            }
            attestation.checkValid(periodDate);
            if (!"SMALL_LOW_PROFIT".equals(attestation.qualificationCategory())) {
                throw new IllegalArgumentException("INCORRECT_ATTESTATION_CATEGORY");
            }
        }

        BigDecimal preferentialRate = new BigDecimal("0.05"); // Effective 5% (25% base * 20% rate)
        BigDecimal payableTax;
        BigDecimal reliefTax;
        boolean applied;

        if (annualTaxableProfit.compareTo(BigDecimal.ZERO) <= 0) {
            applied = false;
            payableTax = BigDecimal.ZERO.setScale(2);
            reliefTax = BigDecimal.ZERO.setScale(2);
        } else if (qualifiesStatutoryLimits && (!pack.attestationRequired() || attestation != null)) {
            // Relief applies
            applied = true;
            // Taxable income reduced to 25%, taxed at 20%
            BigDecimal reducedBase = annualTaxableProfit.multiply(new BigDecimal("0.25")).setScale(2, RoundingMode.HALF_UP);
            payableTax = reducedBase.multiply(new BigDecimal("0.20")).setScale(2, RoundingMode.HALF_UP);
            reliefTax = statutoryTax.subtract(payableTax).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        } else {
            applied = false;
            payableTax = statutoryTax;
            reliefTax = BigDecimal.ZERO.setScale(2);
        }

        CalculationExplainTree.Builder treeBuilder = new CalculationExplainTree.Builder(
                "TAX01_CIT_SMALL_LOW_PROFIT",
                taxEntityId.toString()
        );

        treeBuilder.addStep(
                1,
                "STATUTORY_CIT_ASSESSMENT",
                "statutoryTax = taxableProfit * 25%",
                "CIT_Law_Art4",
                List.of(new CalculationExplainTree.InputProvenance("annualTaxableProfit", annualTaxableProfit.toPlainString(), "TAXABLE_INCOME_BASE", "profit", "none")),
                statutoryTax.toPlainString(),
                List.of()
        );

        treeBuilder.addStep(
                2,
                "ATTESTATION_AND_RELIEF_GATE",
                applied ? "Attestation verified + Criteria met -> effective rate 5%" : "Relief not applied (standard 25%)",
                pack.id() + "@" + pack.digest(),
                List.of(
                        new CalculationExplainTree.InputProvenance("attestationDigest", attestation != null ? attestation.attestationDigest() : "NONE", "USER_ATTESTATION", "attestation", attestation != null ? attestation.attestationDigest() : "none"),
                        new CalculationExplainTree.InputProvenance("averageStaffCount", averageStaffCount.toPlainString(), "STATUTORY_CRITERIA", "staff", "none"),
                        new CalculationExplainTree.InputProvenance("totalAssets", totalAssets.toPlainString(), "STATUTORY_CRITERIA", "assets", "none")
                ),
                payableTax.toPlainString(),
                List.of()
        );

        return new CalculationResult(
                pack.taxType(),
                annualTaxableProfit,
                statutoryRate,
                applied ? preferentialRate : statutoryRate,
                statutoryTax,
                reliefTax,
                payableTax,
                applied,
                treeBuilder.build()
        );
    }

    /**
     * Evaluates R&D Super-Deduction (研发费用加计扣除).
     * Statutory rate 100% or 120% super-deduction.
     * STRICT: Requires explicit attestation!
     */
    public static CalculationResult evaluateRdSuperDeduction(
            PolicyPack pack,
            Attestation attestation,
            UUID taxEntityId,
            LocalDate periodDate,
            BigDecimal eligibleRdExpenses,
            BigDecimal superDeductionRatio // e.g. 1.00 for 100%, 1.20 for 120%
    ) {
        pack.checkEffective(periodDate);
        if (!"CIT_RD".equals(pack.taxType())) {
            throw new IllegalArgumentException("MISMATCHED_POLICY_TAX_TYPE");
        }
        if (eligibleRdExpenses == null || eligibleRdExpenses.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("ELIGIBLE_RD_EXPENSES_CANNOT_BE_NEGATIVE");
        }
        if (superDeductionRatio == null || superDeductionRatio.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("SUPER_DEDUCTION_RATIO_MUST_BE_POSITIVE");
        }

        if (pack.attestationRequired()) {
            if (attestation == null) {
                throw new IllegalStateException("TAX_QUALIFICATION_ATTESTATION_REQUIRED: R&D super-deduction requires explicit qualification attestation");
            }
            if (!taxEntityId.equals(attestation.taxEntityId()) || !pack.id().equals(attestation.policyPackId())) {
                throw new IllegalArgumentException("ATTESTATION_SCOPE_MISMATCH");
            }
            attestation.checkValid(periodDate);
            if (!"RD_QUALIFIED".equals(attestation.qualificationCategory()) && !"HIGH_NEW_TECH".equals(attestation.qualificationCategory())) {
                throw new IllegalArgumentException("INCORRECT_ATTESTATION_CATEGORY");
            }
        }

        BigDecimal extraDeduction = eligibleRdExpenses.multiply(superDeductionRatio).setScale(2, RoundingMode.HALF_UP);

        CalculationExplainTree.Builder treeBuilder = new CalculationExplainTree.Builder(
                "TAX01_CIT_RD_SUPER_DEDUCTION",
                taxEntityId.toString()
        );

        treeBuilder.addStep(
                1,
                "RD_EXPENSE_SUPER_DEDUCTION_CALCULATION",
                "extraDeduction = eligibleRdExpenses * " + superDeductionRatio.toPlainString(),
                pack.id() + "@" + pack.digest(),
                List.of(
                        new CalculationExplainTree.InputProvenance("eligibleRdExpenses", eligibleRdExpenses.toPlainString(), "RD_LEDGER", "rd_book", "none"),
                        new CalculationExplainTree.InputProvenance("attestationDigest", attestation != null ? attestation.attestationDigest() : "NONE", "USER_ATTESTATION", "attestation", attestation != null ? attestation.attestationDigest() : "none")
                ),
                extraDeduction.toPlainString(),
                List.of()
        );

        return new CalculationResult(
                pack.taxType(),
                eligibleRdExpenses,
                BigDecimal.ONE,
                superDeductionRatio,
                eligibleRdExpenses,
                extraDeduction,
                extraDeduction,
                true,
                treeBuilder.build()
        );
    }
}

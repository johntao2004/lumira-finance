package com.lumira.finance.payrolltax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * PAY04: Natural-person external service remuneration (劳务报酬/稿酬/特许权使用费)
 * & small-amount VAT / surtax withholding engine.
 * Implements IIT Law, Regulations Art. 14, Announcement 2018 No. 61,
 * and natural person VAT withholding rules.
 */
public final class LaborRemunerationEngine {

    private LaborRemunerationEngine() {}

    public enum RemunerationType {
        LABOR_SERVICE,
        AUTHOR_REMUNERATION,
        ROYALTY
    }

    public record LaborInput(
            UUID naturalPersonId,
            RemunerationType type,
            BigDecimal grossPayment,
            boolean relief1Pct,
            BigDecimal cityRate,
            boolean sixTaxesHalf,
            boolean educationExempt,
            boolean proxyInvoiceCompleted,
            String proxyInvoiceRef
    ) {
        public LaborInput {
            if (naturalPersonId == null) throw new IllegalArgumentException("NATURAL_PERSON_ID_REQUIRED");
            if (type == null) throw new IllegalArgumentException("REMUNERATION_TYPE_REQUIRED");
            if (grossPayment == null || grossPayment.compareTo(BigDecimal.ZERO) <= 0)
                throw new IllegalArgumentException("GROSS_PAYMENT_MUST_BE_POSITIVE");
            if (cityRate == null) {
                cityRate = new BigDecimal("0.07");
            } else if (cityRate.compareTo(new BigDecimal("0.07")) != 0
                    && cityRate.compareTo(new BigDecimal("0.05")) != 0
                    && cityRate.compareTo(new BigDecimal("0.01")) != 0) {
                throw new IllegalArgumentException("INVALID_CITY_SURTAX_RATE");
            }
            if (proxyInvoiceCompleted && (proxyInvoiceRef == null || proxyInvoiceRef.isBlank())) {
                throw new IllegalArgumentException("PROXY_INVOICE_REF_REQUIRED_WHEN_COMPLETED");
            }
        }
    }

    public record LaborResult(
            UUID naturalPersonId,
            RemunerationType type,
            BigDecimal grossPayment,
            BigDecimal deductionAmount,
            BigDecimal taxableIncome,
            BigDecimal iitRate,
            BigDecimal iitQuickDeduction,
            BigDecimal iitWithholding,
            BigDecimal vatSales,
            boolean vatExempt,
            BigDecimal vatRate,
            BigDecimal vatReduction,
            BigDecimal vatWithheld,
            BigDecimal citySurtax,
            BigDecimal educationSurcharge,
            BigDecimal localEducationSurcharge,
            BigDecimal totalSurchargeWithheld,
            BigDecimal netPayable,
            boolean proxyInvoiceCompleted,
            String proxyInvoiceRef,
            CalculationExplainTree explainTree
    ) {}

    private static final BigDecimal THRESHOLD_4000 = new BigDecimal("4000.00");
    private static final BigDecimal DEDUCTION_800 = new BigDecimal("800.00");
    private static final BigDecimal RATE_20_PCT = new BigDecimal("0.20");
    private static final BigDecimal FACTOR_80_PCT = new BigDecimal("0.80");
    private static final BigDecimal FACTOR_70_PCT = new BigDecimal("0.70"); // 30% reduction for author
    private static final BigDecimal VAT_SMALL_THRESHOLD = new BigDecimal("1000.00");

    public static LaborResult calculate(LaborInput input) {
        BigDecimal gross = input.grossPayment().setScale(2, RoundingMode.HALF_UP);

        // --- 1. Statutory IIT Deduction ---
        BigDecimal deduction;
        BigDecimal preliminaryTaxable;
        if (gross.compareTo(THRESHOLD_4000) <= 0) {
            deduction = gross.min(DEDUCTION_800);
            preliminaryTaxable = gross.subtract(deduction).max(BigDecimal.ZERO);
        } else {
            deduction = gross.multiply(RATE_20_PCT).setScale(2, RoundingMode.HALF_UP);
            preliminaryTaxable = gross.subtract(deduction).max(BigDecimal.ZERO);
        }

        BigDecimal taxableIncome;
        BigDecimal iitRate;
        BigDecimal iitQuick;
        BigDecimal iitWithholding;

        switch (input.type()) {
            case AUTHOR_REMUNERATION -> {
                // 30% statutory reduction for author remuneration
                taxableIncome = preliminaryTaxable.multiply(FACTOR_70_PCT).setScale(2, RoundingMode.HALF_UP);
                iitRate = RATE_20_PCT;
                iitQuick = BigDecimal.ZERO.setScale(2);
                iitWithholding = taxableIncome.multiply(iitRate).setScale(2, RoundingMode.HALF_UP);
            }
            case ROYALTY -> {
                taxableIncome = preliminaryTaxable.setScale(2, RoundingMode.HALF_UP);
                iitRate = RATE_20_PCT;
                iitQuick = BigDecimal.ZERO.setScale(2);
                iitWithholding = taxableIncome.multiply(iitRate).setScale(2, RoundingMode.HALF_UP);
            }
            case LABOR_SERVICE -> {
                taxableIncome = preliminaryTaxable.setScale(2, RoundingMode.HALF_UP);
                // Progressive withholding table for labor services:
                // <= 20,000: 20%, quick 0
                // 20,000 - 50,000: 30%, quick 2,000
                // > 50,000: 40%, quick 7,000
                if (taxableIncome.compareTo(new BigDecimal("20000.00")) <= 0) {
                    iitRate = new BigDecimal("0.20");
                    iitQuick = BigDecimal.ZERO.setScale(2);
                } else if (taxableIncome.compareTo(new BigDecimal("50000.00")) <= 0) {
                    iitRate = new BigDecimal("0.30");
                    iitQuick = new BigDecimal("2000.00");
                } else {
                    iitRate = new BigDecimal("0.40");
                    iitQuick = new BigDecimal("7000.00");
                }
                iitWithholding = taxableIncome.multiply(iitRate)
                        .subtract(iitQuick)
                        .max(BigDecimal.ZERO)
                        .setScale(2, RoundingMode.HALF_UP);
            }
            default -> throw new IllegalStateException("UNREACHABLE");
        }

        // --- 2. Natural-Person Small-Amount VAT & Surtaxes Withholding ---
        BigDecimal vatSales = gross;
        boolean vatExempt = false;
        BigDecimal vatRate = BigDecimal.ZERO.setScale(4);
        BigDecimal vatReduction = BigDecimal.ZERO.setScale(2);
        BigDecimal vatWithheld = BigDecimal.ZERO.setScale(2);
        BigDecimal citySurtax = BigDecimal.ZERO.setScale(2);
        BigDecimal eduSurcharge = BigDecimal.ZERO.setScale(2);
        BigDecimal localEduSurcharge = BigDecimal.ZERO.setScale(2);
        BigDecimal totalSurchargeWithheld = BigDecimal.ZERO.setScale(2);

        if (!input.proxyInvoiceCompleted()) {
            if (gross.compareTo(VAT_SMALL_THRESHOLD) < 0) {
                // Below 1,000 CNY daily/transaction threshold: Exempt
                vatExempt = true;
                vatRate = new BigDecimal("0.0300");
                vatReduction = gross.multiply(new BigDecimal("0.03")).setScale(2, RoundingMode.HALF_UP);
                vatWithheld = BigDecimal.ZERO.setScale(2);
            } else {
                vatExempt = false;
                vatRate = new BigDecimal("0.0300");
                BigDecimal grossVat = gross.multiply(vatRate).setScale(2, RoundingMode.HALF_UP);
                if (input.relief1Pct()) {
                    // 1% preferential policy (2% reduction)
                    vatReduction = gross.multiply(new BigDecimal("0.02")).setScale(2, RoundingMode.HALF_UP);
                    vatWithheld = grossVat.subtract(vatReduction).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
                } else {
                    vatReduction = BigDecimal.ZERO.setScale(2);
                    vatWithheld = grossVat;
                }

                // Surcharges on VAT
                BigDecimal halfMultiplier = input.sixTaxesHalf() ? new BigDecimal("0.50") : BigDecimal.ONE;

                citySurtax = vatWithheld.multiply(input.cityRate()).multiply(halfMultiplier).setScale(2, RoundingMode.HALF_UP);

                if (input.educationExempt() || gross.compareTo(new BigDecimal("100000.00")) <= 0) {
                    eduSurcharge = BigDecimal.ZERO.setScale(2);
                    localEduSurcharge = BigDecimal.ZERO.setScale(2);
                } else {
                    eduSurcharge = vatWithheld.multiply(new BigDecimal("0.03")).multiply(halfMultiplier).setScale(2, RoundingMode.HALF_UP);
                    localEduSurcharge = vatWithheld.multiply(new BigDecimal("0.02")).multiply(halfMultiplier).setScale(2, RoundingMode.HALF_UP);
                }
                totalSurchargeWithheld = citySurtax.add(eduSurcharge).add(localEduSurcharge).setScale(2, RoundingMode.HALF_UP);
            }
        }

        BigDecimal netPayable = gross.subtract(iitWithholding)
                .subtract(vatWithheld)
                .subtract(totalSurchargeWithheld)
                .setScale(2, RoundingMode.HALF_UP);

        // --- 3. Build Deterministic Explain Tree ---
        CalculationExplainTree.Builder treeBuilder = new CalculationExplainTree.Builder(
                "PAY04_EXTERNAL_SERVICE_REMUNERATION",
                input.naturalPersonId().toString()
        );

        treeBuilder.addStep(
                1,
                "STATUTORY_EXPENSE_DEDUCTION",
                gross.compareTo(THRESHOLD_4000) <= 0 ? "deduction = min(gross, 800)" : "deduction = gross * 20%",
                "IIT_Law_Art6 / Announcement_2018_61",
                List.of(new CalculationExplainTree.InputProvenance("grossPayment", gross.toPlainString(), "USER_INPUT", "remuneration", "none")),
                deduction.toPlainString(),
                List.of()
        );

        treeBuilder.addStep(
                2,
                "IIT_PROGRESSIVE_WITHHOLDING",
                "taxable = " + taxableIncome.toPlainString() + ", rate = " + iitRate.toPlainString() + ", quick = " + iitQuick.toPlainString(),
                "IIT_Announcement_2018_61_LaborServiceSchedule",
                List.of(
                        new CalculationExplainTree.InputProvenance("preliminaryTaxable", preliminaryTaxable.toPlainString(), "STEP_1_OUTPUT", "step_1", "none"),
                        new CalculationExplainTree.InputProvenance("remunerationType", input.type().name(), "TYPE_DISPATCH", "type", "none")
                ),
                iitWithholding.toPlainString(),
                List.of()
        );

        treeBuilder.addStep(
                3,
                "VAT_AND_SURTAX_WITHHOLDING",
                vatExempt ? "sales < 1000 -> EXEMPT" : (input.proxyInvoiceCompleted() ? "PROXY_INVOICE_COMPLETED -> NO_WITHHOLDING" : "vatWithheld = " + vatWithheld.toPlainString() + ", surcharges = " + totalSurchargeWithheld.toPlainString()),
                "VAT_Regulations / STA_NaturalPersonWithholding_Baseline",
                List.of(
                        new CalculationExplainTree.InputProvenance("grossSales", gross.toPlainString(), "REVENUE_BASE", "gross", "none"),
                        new CalculationExplainTree.InputProvenance("cityRate", input.cityRate().toPlainString(), "LOCAL_SURTAX_RATE", "city", "none")
                ),
                vatWithheld.add(totalSurchargeWithheld).toPlainString(),
                List.of()
        );

        return new LaborResult(
                input.naturalPersonId(),
                input.type(),
                gross,
                deduction,
                taxableIncome,
                iitRate,
                iitQuick,
                iitWithholding,
                vatSales,
                vatExempt,
                vatRate,
                vatReduction,
                vatWithheld,
                citySurtax,
                eduSurcharge,
                localEduSurcharge,
                totalSurchargeWithheld,
                netPayable,
                input.proxyInvoiceCompleted(),
                input.proxyInvoiceRef(),
                treeBuilder.build()
        );
    }
}

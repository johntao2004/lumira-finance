package com.lumira.finance.payrolltax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * PAY03: Annual one-time bonus (全年一次性奖金) calculation & election engine.
 * Implements Cai Shui [2018] No. 164 and MOF/STA Announcement 2023 No. 30.
 * Supports SEPARATE_MONTHLY_QUOTIENT vs COMBINED_COMPREHENSIVE_INCOME,
 * along with annual IIT reconciliation synergy preview.
 */
public final class BonusCalculationEngine {

    private BonusCalculationEngine() {}

    public enum BonusMode {
        SEPARATE_MONTHLY_QUOTIENT,
        COMBINED_COMPREHENSIVE_INCOME
    }

    public record BonusBracket(BigDecimal upper, BigDecimal rate, BigDecimal quickDeduction) {}

    // Monthly Comprehensive IIT Brackets for Separate Monthly Quotient Method
    private static final List<BonusBracket> MONTHLY_BRACKETS = List.of(
            new BonusBracket(new BigDecimal("3000.00"), new BigDecimal("0.03"), BigDecimal.ZERO.setScale(2)),
            new BonusBracket(new BigDecimal("12000.00"), new BigDecimal("0.10"), new BigDecimal("210.00")),
            new BonusBracket(new BigDecimal("25000.00"), new BigDecimal("0.20"), new BigDecimal("1410.00")),
            new BonusBracket(new BigDecimal("35000.00"), new BigDecimal("0.25"), new BigDecimal("2660.00")),
            new BonusBracket(new BigDecimal("55000.00"), new BigDecimal("0.30"), new BigDecimal("4410.00")),
            new BonusBracket(new BigDecimal("80000.00"), new BigDecimal("0.35"), new BigDecimal("7160.00")),
            new BonusBracket(null, new BigDecimal("0.45"), new BigDecimal("15160.00"))
    );

    // Annual Comprehensive IIT Brackets for Combined Reconciliation Method
    private static final List<BonusBracket> ANNUAL_BRACKETS = List.of(
            new BonusBracket(new BigDecimal("36000.00"), new BigDecimal("0.03"), BigDecimal.ZERO.setScale(2)),
            new BonusBracket(new BigDecimal("144000.00"), new BigDecimal("0.10"), new BigDecimal("2520.00")),
            new BonusBracket(new BigDecimal("300000.00"), new BigDecimal("0.20"), new BigDecimal("16920.00")),
            new BonusBracket(new BigDecimal("420000.00"), new BigDecimal("0.25"), new BigDecimal("31920.00")),
            new BonusBracket(new BigDecimal("660000.00"), new BigDecimal("0.30"), new BigDecimal("52920.00")),
            new BonusBracket(new BigDecimal("960000.00"), new BigDecimal("0.35"), new BigDecimal("85920.00")),
            new BonusBracket(null, new BigDecimal("0.45"), new BigDecimal("181920.00"))
    );

    public record BonusInput(
            UUID employeeId,
            BigDecimal bonusAmount,
            BigDecimal annualTaxableSalary,
            BonusMode requestedMode
    ) {
        public BonusInput {
            if (employeeId == null) throw new IllegalArgumentException("EMPLOYEE_ID_REQUIRED");
            if (bonusAmount == null || bonusAmount.compareTo(BigDecimal.ZERO) <= 0)
                throw new IllegalArgumentException("BONUS_AMOUNT_MUST_BE_POSITIVE");
            if (annualTaxableSalary == null)
                annualTaxableSalary = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            if (requestedMode == null)
                requestedMode = BonusMode.SEPARATE_MONTHLY_QUOTIENT;
        }
    }

    public record BonusCalculationResult(
            UUID employeeId,
            BigDecimal bonusAmount,
            BonusMode electedMode,
            BigDecimal monthlyQuotient,
            BigDecimal applicableRate,
            BigDecimal quickDeduction,
            BigDecimal withholdingTax,
            BigDecimal netAmount,
            BonusMode recommendedMode,
            BigDecimal taxSavings,
            String comparisonSummary,
            CalculationExplainTree explainTree
    ) {}

    public static BonusCalculationResult calculate(BonusInput input) {
        BigDecimal bonus = input.bonusAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal annualSalary = input.annualTaxableSalary().setScale(2, RoundingMode.HALF_UP);

        // --- Option A: Separate Monthly Quotient Method ---
        BigDecimal quotient = bonus.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
        BonusBracket separateBracket = MONTHLY_BRACKETS.stream()
                .filter(b -> b.upper() == null || quotient.compareTo(b.upper()) <= 0)
                .findFirst().orElseThrow();
        BigDecimal separateTax = bonus.multiply(separateBracket.rate())
                .subtract(separateBracket.quickDeduction())
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        // Annual salary tax standalone (under annual brackets)
        BigDecimal salaryTax;
        if (annualSalary.compareTo(BigDecimal.ZERO) <= 0) {
            salaryTax = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        } else {
            BonusBracket salaryBracket = ANNUAL_BRACKETS.stream()
                    .filter(b -> b.upper() == null || annualSalary.compareTo(b.upper()) <= 0)
                    .findFirst().orElseThrow();
            salaryTax = annualSalary.multiply(salaryBracket.rate())
                    .subtract(salaryBracket.quickDeduction())
                    .max(BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal totalTaxOptionA = salaryTax.add(separateTax);

        // --- Option B: Combined Comprehensive Method ---
        BigDecimal combinedTaxable = annualSalary.add(bonus).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BonusBracket combinedBracket = ANNUAL_BRACKETS.stream()
                .filter(b -> b.upper() == null || combinedTaxable.compareTo(b.upper()) <= 0)
                .findFirst().orElseThrow();
        BigDecimal totalTaxOptionB = combinedTaxable.compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : combinedTaxable.multiply(combinedBracket.rate())
                    .subtract(combinedBracket.quickDeduction())
                    .max(BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP);

        BigDecimal combinedMarginalBonusTax = totalTaxOptionB.subtract(salaryTax).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        // Synergy comparison
        BonusMode recommended;
        BigDecimal savings;
        if (totalTaxOptionA.compareTo(totalTaxOptionB) < 0) {
            recommended = BonusMode.SEPARATE_MONTHLY_QUOTIENT;
            savings = totalTaxOptionB.subtract(totalTaxOptionA);
        } else if (totalTaxOptionB.compareTo(totalTaxOptionA) < 0) {
            recommended = BonusMode.COMBINED_COMPREHENSIVE_INCOME;
            savings = totalTaxOptionA.subtract(totalTaxOptionB);
        } else {
            recommended = BonusMode.SEPARATE_MONTHLY_QUOTIENT;
            savings = BigDecimal.ZERO.setScale(2);
        }

        String summary = String.format(
                "Option A (Separate): Bonus Tax %s + Salary Tax %s = Total %s. Option B (Combined): Total %s. Recommended: %s (Savings: %s)",
                separateTax.toPlainString(),
                salaryTax.toPlainString(),
                totalTaxOptionA.toPlainString(),
                totalTaxOptionB.toPlainString(),
                recommended.name(),
                savings.toPlainString()
        );

        // Elected Mode determination
        BigDecimal finalRate;
        BigDecimal finalQuick;
        BigDecimal finalWithholdingTax;
        if (input.requestedMode() == BonusMode.SEPARATE_MONTHLY_QUOTIENT) {
            finalRate = separateBracket.rate();
            finalQuick = separateBracket.quickDeduction();
            finalWithholdingTax = separateTax;
        } else {
            finalRate = combinedBracket.rate();
            finalQuick = combinedBracket.quickDeduction();
            finalWithholdingTax = combinedMarginalBonusTax;
        }
        BigDecimal netAmount = bonus.subtract(finalWithholdingTax).setScale(2, RoundingMode.HALF_UP);

        // Build Explanation Tree
        CalculationExplainTree.Builder treeBuilder = new CalculationExplainTree.Builder(
                "PAY03_ANNUAL_ONE_TIME_BONUS",
                input.employeeId().toString()
        );

        treeBuilder.addStep(
                1,
                "MONTHLY_QUOTIENT_CALCULATION",
                "monthlyQuotient = bonusAmount / 12",
                "CaiShui_2018_164_Art1 / Announcement_2023_30",
                List.of(
                        new CalculationExplainTree.InputProvenance("bonusAmount", bonus.toPlainString(), "USER_PAYROLL_INPUT", "bonus_entry", "sha256_placeholder"),
                        new CalculationExplainTree.InputProvenance("monthsInYear", "12", "STATUTORY_CONSTANT", "circular_164", "none")
                ),
                quotient.toPlainString(),
                List.of()
        );

        treeBuilder.addStep(
                2,
                "SEPARATE_BRACKET_MATCHING",
                "rate = " + separateBracket.rate().toPlainString() + ", quick = " + separateBracket.quickDeduction().toPlainString(),
                "IIT_Law_2018_Monthly_Bracket_Table_1",
                List.of(new CalculationExplainTree.InputProvenance("monthlyQuotient", quotient.toPlainString(), "STEP_1_OUTPUT", "step_1", "none")),
                separateTax.toPlainString(),
                List.of()
        );

        treeBuilder.addStep(
                3,
                "ANNUAL_RECONCILIATION_SYNERGY_EVALUATION",
                summary,
                "IIT_Law_Comprehensive_Reconciliation_Synergy",
                List.of(
                        new CalculationExplainTree.InputProvenance("annualTaxableSalary", annualSalary.toPlainString(), "SOURCE_WAGE_LEDGER", "wage_ytd", "none"),
                        new CalculationExplainTree.InputProvenance("totalTaxOptionA", totalTaxOptionA.toPlainString(), "SEPARATE_PATH", "opt_a", "none"),
                        new CalculationExplainTree.InputProvenance("totalTaxOptionB", totalTaxOptionB.toPlainString(), "COMBINED_PATH", "opt_b", "none")
                ),
                savings.toPlainString(),
                List.of()
        );

        return new BonusCalculationResult(
                input.employeeId(),
                bonus,
                input.requestedMode(),
                quotient,
                finalRate,
                finalQuick,
                finalWithholdingTax,
                netAmount,
                recommended,
                savings,
                summary,
                treeBuilder.build()
        );
    }
}

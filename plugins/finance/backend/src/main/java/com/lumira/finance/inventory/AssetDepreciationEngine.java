package com.lumira.finance.inventory;

import static com.lumira.finance.inventory.InventoryModel.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Accelerated Depreciation & CAS 8 Long-term Asset Impairment Engine:
 * - Double-Declining Balance (DDB) with final 2-year straight-line switchover
 * - Sum-of-the-Years'-Digits (SYD)
 * - Straight-Line (SL) with final-month absorption
 * - CAS 8 Strictly Non-Reversible Asset Impairment (blocked on SMALL)
 */
public final class AssetDepreciationEngine {
    private AssetDepreciationEngine() {}

    /**
     * Generates complete lifetime monthly depreciation schedule for an asset card.
     */
    public static List<DepreciationScheduleRow> generateSchedule(
            DepreciationMethod method,
            String startPeriod,
            int totalMonths,
            BigDecimal originalCost,
            BigDecimal residualValue) {
        if (method == null) {
            throw new IllegalArgumentException("DEPRECIATION_METHOD_REQUIRED");
        }
        if (startPeriod == null || !startPeriod.matches("^[0-9]{4}-(?:0[1-9]|1[0-2])$")) {
            throw new IllegalArgumentException("INVALID_START_PERIOD_FORMAT");
        }
        if (totalMonths <= 0) {
            throw new IllegalArgumentException("TOTAL_MONTHS_MUST_BE_POSITIVE");
        }
        if (originalCost == null || originalCost.signum() <= 0) {
            throw new IllegalArgumentException("COST_MUST_BE_POSITIVE");
        }
        if (residualValue == null || residualValue.signum() < 0) {
            throw new IllegalArgumentException("NON_NEGATIVE_RESIDUAL_REQUIRED");
        }
        if (residualValue.compareTo(originalCost) >= 0) {
            throw new IllegalArgumentException("RESIDUAL_MUST_BE_LESS_THAN_COST");
        }

        return switch (method) {
            case STRAIGHT_LINE -> generateStraightLine(startPeriod, totalMonths, originalCost, residualValue);
            case DOUBLE_DECLINING_BALANCE -> generateDoubleDeclining(startPeriod, totalMonths, originalCost, residualValue);
            case SUM_OF_THE_YEARS_DIGITS -> generateSumOfTheYearsDigits(startPeriod, totalMonths, originalCost, residualValue);
        };
    }

    private static List<DepreciationScheduleRow> generateStraightLine(
            String startPeriod, int totalMonths, BigDecimal cost, BigDecimal residual) {
        List<DepreciationScheduleRow> rows = new ArrayList<>(totalMonths);
        BigDecimal totalDepreciable = roundCents(cost.subtract(residual));
        BigDecimal monthlyStandard = roundCents(totalDepreciable.divide(BigDecimal.valueOf(totalMonths), 8, RoundingMode.HALF_UP));

        YearMonth ym = YearMonth.parse(startPeriod);
        BigDecimal accumulated = roundCents(BigDecimal.ZERO);
        BigDecimal carrying = roundCents(cost);

        for (int m = 1; m <= totalMonths; m++) {
            BigDecimal monthly;
            if (m == totalMonths) {
                // Final month absorbs remainder
                monthly = roundCents(totalDepreciable.subtract(accumulated));
            } else {
                monthly = monthlyStandard;
            }

            accumulated = roundCents(accumulated.add(monthly));
            carrying = roundCents(carrying.subtract(monthly));

            rows.add(new DepreciationScheduleRow(ym.toString(), m, monthly, carrying));
            ym = ym.plusMonths(1);
        }

        return rows;
    }

    private static List<DepreciationScheduleRow> generateDoubleDeclining(
            String startPeriod, int totalMonths, BigDecimal cost, BigDecimal residual) {
        List<DepreciationScheduleRow> rows = new ArrayList<>(totalMonths);
        int years = (int) Math.ceil(totalMonths / 12.0);
        BigDecimal totalDepreciable = roundCents(cost.subtract(residual));

        YearMonth ym = YearMonth.parse(startPeriod);
        BigDecimal carrying = roundCents(cost);
        BigDecimal accumulated = roundCents(BigDecimal.ZERO);

        BigDecimal annualRate = BigDecimal.valueOf(2.0 / years);

        BigDecimal currentYearAnnual = roundCents(BigDecimal.ZERO);
        BigDecimal currentYearMonthlyStd = roundCents(BigDecimal.ZERO);
        BigDecimal currentYearDepreciated = roundCents(BigDecimal.ZERO);
        int monthsInThisYear = 12;

        for (int m = 1; m <= totalMonths; m++) {
            int monthInYear = (m - 1) % 12;
            int currentYear = (m - 1) / 12 + 1;
            int remainingYears = years - currentYear + 1;

            if (monthInYear == 0) {
                monthsInThisYear = Math.min(12, totalMonths - (currentYear - 1) * 12);
                if (remainingYears <= 2) {
                    // Final 2 years (or useful life <= 2 years): switch to straight-line on remaining depreciable
                    BigDecimal remainingDepreciable = roundCents(carrying.subtract(residual));
                    currentYearAnnual = roundCents(remainingDepreciable.divide(BigDecimal.valueOf(remainingYears), 8, RoundingMode.HALF_UP));
                } else {
                    BigDecimal rawAnnual = roundCents(carrying.multiply(annualRate));
                    BigDecimal maxAllowed = roundCents(carrying.subtract(residual));
                    currentYearAnnual = rawAnnual.min(maxAllowed);
                }
                currentYearMonthlyStd = roundCents(currentYearAnnual.divide(BigDecimal.valueOf(monthsInThisYear), 8, RoundingMode.HALF_UP));
                currentYearDepreciated = roundCents(BigDecimal.ZERO);
            }

            BigDecimal monthly;
            if (m == totalMonths) {
                // Final month absorbs lifetime remainder
                monthly = roundCents(totalDepreciable.subtract(accumulated));
            } else if (monthInYear == monthsInThisYear - 1) {
                // Last month of current year absorbs year's rounding difference
                monthly = roundCents(currentYearAnnual.subtract(currentYearDepreciated));
            } else {
                monthly = currentYearMonthlyStd;
            }

            currentYearDepreciated = roundCents(currentYearDepreciated.add(monthly));
            accumulated = roundCents(accumulated.add(monthly));
            carrying = roundCents(carrying.subtract(monthly));

            rows.add(new DepreciationScheduleRow(ym.toString(), m, monthly, carrying));
            ym = ym.plusMonths(1);
        }

        return rows;
    }

    private static List<DepreciationScheduleRow> generateSumOfTheYearsDigits(
            String startPeriod, int totalMonths, BigDecimal cost, BigDecimal residual) {
        List<DepreciationScheduleRow> rows = new ArrayList<>(totalMonths);
        int years = (int) Math.ceil(totalMonths / 12.0);
        long sumOfYears = (long) years * (years + 1) / 2;
        BigDecimal totalDepreciable = roundCents(cost.subtract(residual));

        YearMonth ym = YearMonth.parse(startPeriod);
        BigDecimal carrying = roundCents(cost);
        BigDecimal accumulated = roundCents(BigDecimal.ZERO);

        BigDecimal currentYearAnnual = roundCents(BigDecimal.ZERO);
        BigDecimal currentYearMonthlyStd = roundCents(BigDecimal.ZERO);
        BigDecimal currentYearDepreciated = roundCents(BigDecimal.ZERO);

        for (int m = 1; m <= totalMonths; m++) {
            int monthInYear = (m - 1) % 12;
            int currentYear = (m - 1) / 12 + 1;

            if (monthInYear == 0) {
                // Beginning of a year: calculate this year's annual fraction and annual amount
                long remainingYears = years - currentYear + 1;
                BigDecimal annualFraction = BigDecimal.valueOf(remainingYears)
                        .divide(BigDecimal.valueOf(sumOfYears), 8, RoundingMode.HALF_UP);
                currentYearAnnual = roundCents(totalDepreciable.multiply(annualFraction));
                currentYearMonthlyStd = roundCents(currentYearAnnual.divide(BigDecimal.valueOf(12), 8, RoundingMode.HALF_UP));
                currentYearDepreciated = roundCents(BigDecimal.ZERO);
            }

            BigDecimal monthly;
            if (m == totalMonths) {
                // Very final month absorbs lifetime remainder
                monthly = roundCents(totalDepreciable.subtract(accumulated));
            } else if (monthInYear == 11) {
                // Month 12 absorbs year's rounding difference
                monthly = roundCents(currentYearAnnual.subtract(currentYearDepreciated));
            } else {
                monthly = currentYearMonthlyStd;
            }

            currentYearDepreciated = roundCents(currentYearDepreciated.add(monthly));
            accumulated = roundCents(accumulated.add(monthly));
            carrying = roundCents(carrying.subtract(monthly));

            rows.add(new DepreciationScheduleRow(ym.toString(), m, monthly, carrying));
            ym = ym.plusMonths(1);
        }

        return rows;
    }

    /**
     * CAS 8 Asset Impairment Evaluation:
     * - SMALL: Blocked completely (Small enterprise standards do not allow long-term asset impairment provisions).
     * - CAS 8: If recoverableAmount < carryingAmount, recognize impairment provision.
     *   CRUCIAL STATUTORY INVARIANT: Long-term asset impairment under CAS 8 is STRICTLY NON-REVERSIBLE.
     */
    public static AssetImpairmentEvaluation evaluateAssetImpairment(
            String regime,
            UUID assetCardId,
            String period,
            BigDecimal carryingAmount,
            BigDecimal recoverableAmount) {
        if (!"CAS".equals(regime) && !"SMALL".equals(regime)) {
            throw new IllegalArgumentException("INVALID_ACCOUNTING_REGIME");
        }

        if ("SMALL".equals(regime)) {
            throw new IllegalArgumentException("SMALL_IMPAIRMENT_BLOCKED");
        }

        if (carryingAmount == null || carryingAmount.signum() < 0) {
            throw new IllegalArgumentException("NON_NEGATIVE_CARRYING_AMOUNT_REQUIRED");
        }

        if (recoverableAmount == null || recoverableAmount.signum() < 0) {
            throw new IllegalArgumentException("INVALID_RECOVERABLE_AMOUNT");
        }

        BigDecimal impairment;
        if (recoverableAmount.compareTo(carryingAmount) < 0) {
            impairment = roundCents(carryingAmount.subtract(recoverableAmount));
        } else {
            impairment = ZERO_8;
        }

        // isReversible is hardcoded false per CAS 8
        return new AssetImpairmentEvaluation(
                UUID.randomUUID(), assetCardId, regime, period,
                carryingAmount, recoverableAmount, impairment, false, "POSTED"
        );
    }

    /**
     * Statutory check: Reversing CAS 8 asset impairment is strictly forbidden by standard.
     */
    public static void assertAssetImpairmentNotReversible(AssetImpairmentEvaluation prior) {
        if ("CAS".equals(prior.regime()) && prior.impairmentAmount().signum() > 0) {
            throw new IllegalStateException("CAS8_ASSET_IMPAIRMENT_NON_REVERSIBLE");
        }
    }
}

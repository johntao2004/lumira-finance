package com.lumira.finance.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Domain rules and exact precision invariants for Dimensions and Budget Control (Lane W03).
 * Enforces numeric(28,8) monetary amounts, numeric(18,12) ratios, SoD segregation of duties,
 * and exact remainder allocation with zero penny or fractional leakage.
 */
public final class FinanceBudgetDimensionsRules {

    public static final int MONEY_SCALE = 8;
    public static final int PENNY_SCALE = 2;
    public static final int RATIO_SCALE = 12;
    public static final BigDecimal ONE_RATIO = new BigDecimal("1.000000000000");

    private FinanceBudgetDimensionsRules() {}

    public record TargetRatio(UUID targetId, BigDecimal ratio, int priority) {}
    public record AllocatedTarget(UUID targetId, BigDecimal ratio, BigDecimal allocatedAmount) {}

    public static void validateAmount(BigDecimal amount, String fieldName) {
        if (amount == null) {
            throw new IllegalArgumentException(fieldName + " cannot be null");
        }
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(fieldName + " cannot be negative: " + amount.toPlainString());
        }
        if (amount.scale() > MONEY_SCALE) {
            throw new IllegalArgumentException(fieldName + " scale exceeds " + MONEY_SCALE + ": " + amount.toPlainString());
        }
    }

    public static void validateRatio(BigDecimal ratio, String fieldName) {
        if (ratio == null) {
            throw new IllegalArgumentException(fieldName + " cannot be null");
        }
        if (ratio.compareTo(BigDecimal.ZERO) <= 0 || ratio.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(fieldName + " must be in range (0, 1]: " + ratio.toPlainString());
        }
        if (ratio.scale() > RATIO_SCALE) {
            throw new IllegalArgumentException(fieldName + " scale exceeds " + RATIO_SCALE + ": " + ratio.toPlainString());
        }
    }

    private static final UUID NIL_UUID = new UUID(0L, 0L);

    public static void validateSoD(UUID makerPerson, UUID reviewerPerson) {
        if (makerPerson == null) {
            throw new IllegalArgumentException("maker_person is required");
        }
        if (reviewerPerson == null) {
            throw new IllegalArgumentException("reviewer_person is required");
        }
        if (NIL_UUID.equals(makerPerson) || NIL_UUID.equals(reviewerPerson)) {
            throw new IllegalArgumentException("Nil UUID is not a valid natural person");
        }
        if (makerPerson.equals(reviewerPerson)) {
            throw new IllegalStateException("SELF_APPROVAL_FORBIDDEN: maker and reviewer natural persons must be distinct");
        }
    }

    /**
     * Exact multi-target allocation with zero-leakage remainder assignment.
     * Guaranteed invariant: sum(allocated targets) == totalAmount exactly.
     */
    public static List<AllocatedTarget> allocate(BigDecimal totalAmount, List<TargetRatio> targets, int scale) {
        validateAmount(totalAmount, "totalAmount");
        if (scale < 0 || scale > MONEY_SCALE) {
            throw new IllegalArgumentException("Scale must be between 0 and " + MONEY_SCALE + ", got: " + scale);
        }
        if (totalAmount.stripTrailingZeros().scale() > scale) {
            throw new IllegalArgumentException("totalAmount fractional digits exceed allocation scale " + scale + ": " + totalAmount.toPlainString());
        }
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("Allocation targets list cannot be empty");
        }

        BigDecimal sumRatio = BigDecimal.ZERO;
        for (TargetRatio tr : targets) {
            validateRatio(tr.ratio(), "target ratio");
            sumRatio = sumRatio.add(tr.ratio());
        }

        if (sumRatio.compareTo(ONE_RATIO) != 0) {
            throw new IllegalStateException("Sum of allocation ratios must equal 1.000000000000, got: " + sumRatio.toPlainString());
        }

        if (totalAmount.compareTo(BigDecimal.ZERO) == 0) {
            List<AllocatedTarget> zeroList = new ArrayList<>(targets.size());
            for (TargetRatio tr : targets) {
                zeroList.add(new AllocatedTarget(tr.targetId(), tr.ratio(), BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY)));
            }
            return zeroList;
        }

        record InterimTarget(int index, UUID targetId, BigDecimal ratio, int priority, BigDecimal exact, BigDecimal base, BigDecimal fraction) {}

        List<InterimTarget> interims = new ArrayList<>(targets.size());
        BigDecimal sumBase = BigDecimal.ZERO;

        for (int i = 0; i < targets.size(); i++) {
            TargetRatio tr = targets.get(i);
            BigDecimal exact = totalAmount.multiply(tr.ratio());
            BigDecimal base = exact.setScale(scale, RoundingMode.DOWN);
            BigDecimal fraction = exact.subtract(base);
            sumBase = sumBase.add(base);
            interims.add(new InterimTarget(i, tr.targetId(), tr.ratio(), tr.priority(), exact, base, fraction));
        }

        BigDecimal remainder = totalAmount.subtract(sumBase);
        BigDecimal unit = BigDecimal.ONE.movePointLeft(scale);

        int unitsCount = remainder.divide(unit, 0, RoundingMode.UNNECESSARY).intValueExact();
        if (unitsCount < 0) {
            throw new IllegalStateException("Negative remainder in allocation: " + remainder.toPlainString());
        }
        if (unitsCount > targets.size()) {
            throw new IllegalStateException("Allocation remainder units " + unitsCount + " exceeds target count " + targets.size());
        }

        // Sort by fractional remainder descending, then priority ascending, then original index ascending
        List<InterimTarget> sorted = new ArrayList<>(interims);
        sorted.sort((a, b) -> {
            int cmp = b.fraction().compareTo(a.fraction());
            if (cmp != 0) return cmp;
            int pCmp = Integer.compare(a.priority(), b.priority());
            if (pCmp != 0) return pCmp;
            return Integer.compare(a.index(), b.index());
        });

        Set<Integer> awardedIndices = new HashSet<>();
        for (int k = 0; k < unitsCount && k < sorted.size(); k++) {
            awardedIndices.add(sorted.get(k).index());
        }

        List<AllocatedTarget> results = new ArrayList<>(targets.size());
        BigDecimal runningTotal = BigDecimal.ZERO;

        for (int i = 0; i < interims.size(); i++) {
            InterimTarget it = interims.get(i);
            BigDecimal alloc = it.base();
            if (awardedIndices.contains(i)) {
                alloc = alloc.add(unit);
            }
            alloc = alloc.setScale(scale, RoundingMode.UNNECESSARY);
            runningTotal = runningTotal.add(alloc);
            results.add(new AllocatedTarget(it.targetId(), it.ratio(), alloc));
        }

        // Strict invariant check: zero leakage
        if (runningTotal.compareTo(totalAmount) != 0) {
            throw new IllegalStateException("Allocation integrity failure: sum " + runningTotal.toPlainString()
                    + " != expected " + totalAmount.toPlainString());
        }

        return Collections.unmodifiableList(results);
    }

    /**
     * Invariant check on budget control ledger:
     * budgetAmount >= reservedAmount + consumedAmount
     */
    public static void validateControlLedgerInvariant(BigDecimal budgetAmount, BigDecimal reservedAmount, BigDecimal consumedAmount) {
        validateAmount(budgetAmount, "budgetAmount");
        validateAmount(reservedAmount, "reservedAmount");
        validateAmount(consumedAmount, "consumedAmount");

        BigDecimal totalCommitted = reservedAmount.add(consumedAmount);
        if (budgetAmount.compareTo(totalCommitted) < 0) {
            throw new IllegalStateException("BUDGET_EXCEEDED: budget " + budgetAmount.toPlainString()
                    + " is less than committed " + totalCommitted.toPlainString());
        }
    }

    /**
     * Invariant check on budget consumption rollback:
     * rollbackAmount <= consumedAmount
     */
    public static void validateRollbackConsume(BigDecimal consumedAmount, BigDecimal rollbackAmount) {
        validateAmount(consumedAmount, "consumedAmount");
        validateAmount(rollbackAmount, "rollbackAmount");
        if (rollbackAmount.compareTo(consumedAmount) > 0) {
            throw new IllegalStateException("BUDGET_ROLLBACK_EXCEEDS_CONSUMED: rollback " + rollbackAmount.toPlainString()
                    + " exceeds consumed " + consumedAmount.toPlainString());
        }
    }

    /**
     * Compute available budget = budgetAmount - reservedAmount - consumedAmount.
     */
    public static BigDecimal computeAvailable(BigDecimal budgetAmount, BigDecimal reservedAmount, BigDecimal consumedAmount) {
        validateControlLedgerInvariant(budgetAmount, reservedAmount, consumedAmount);
        return budgetAmount.subtract(reservedAmount.add(consumedAmount));
    }

    /**
     * Compute forecast total = ytdConsumed + forecastedRemaining
     * and projected variance = forecastTotal - baselineBudget
     */
    public static BigDecimal computeForecastTotal(BigDecimal ytdConsumed, BigDecimal forecastedRemaining) {
        validateAmount(ytdConsumed, "ytdConsumed");
        validateAmount(forecastedRemaining, "forecastedRemaining");
        return ytdConsumed.add(forecastedRemaining);
    }

    public static BigDecimal computeProjectedVariance(BigDecimal forecastTotal, BigDecimal baselineBudget) {
        validateAmount(forecastTotal, "forecastTotal");
        validateAmount(baselineBudget, "baselineBudget");
        return forecastTotal.subtract(baselineBudget);
    }

    /**
     * Compute SHA-256 snapshot hash for rolling forecast.
     */
    public static String computeForecastSnapshotHash(
            UUID tenantId, UUID bookId, UUID yearId, int asOfPeriod,
            UUID baselineVersionId, BigDecimal ytdConsumed,
            BigDecimal forecastedRemaining, BigDecimal forecastTotal, BigDecimal projectedVariance) {
        String canonical = tenantId + ":" + bookId + ":" + yearId + ":" + asOfPeriod + ":"
                + baselineVersionId + ":" + ytdConsumed.toPlainString() + ":"
                + forecastedRemaining.toPlainString() + ":" + forecastTotal.toPlainString() + ":"
                + projectedVariance.toPlainString();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm missing", e);
        }
    }
}

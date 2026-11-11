package com.lumira.finance.budget;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FinanceBudgetDimensionsRules (Lane W03).
 */
public class FinanceBudgetDimensionsRulesTest {

    @Test
    void testExactPennyAllocationThreeWay() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID t3 = UUID.randomUUID();

        // 1/3 each with scale 12 summing to 1.000000000000
        BigDecimal r1 = new BigDecimal("0.333333333333");
        BigDecimal r2 = new BigDecimal("0.333333333333");
        BigDecimal r3 = new BigDecimal("0.333333333334");

        List<FinanceBudgetDimensionsRules.TargetRatio> targets = List.of(
                new FinanceBudgetDimensionsRules.TargetRatio(t1, r1, 1),
                new FinanceBudgetDimensionsRules.TargetRatio(t2, r2, 2),
                new FinanceBudgetDimensionsRules.TargetRatio(t3, r3, 3)
        );

        BigDecimal total = new BigDecimal("100.00");
        List<FinanceBudgetDimensionsRules.AllocatedTarget> result =
                FinanceBudgetDimensionsRules.allocate(total, targets, 2);

        assertEquals(3, result.size());
        BigDecimal sum = BigDecimal.ZERO;
        for (FinanceBudgetDimensionsRules.AllocatedTarget at : result) {
            sum = sum.add(at.allocatedAmount());
        }
        assertEquals(new BigDecimal("100.00"), sum);

        // Highest remainder target gets the penny
        // Target 3 has ratio 0.333333333334 -> 33.3333333334 base 33.33 fraction .0033333334
        // Target 1 & 2 have fraction .0033333333
        // So target 3 gets 33.34, targets 1 and 2 get 33.33
        assertEquals(new BigDecimal("33.33"), result.get(0).allocatedAmount());
        assertEquals(new BigDecimal("33.33"), result.get(1).allocatedAmount());
        assertEquals(new BigDecimal("33.34"), result.get(2).allocatedAmount());
    }

    @Test
    void testEightDecimalRemainderAllocation() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();

        BigDecimal r1 = new BigDecimal("0.700000000000");
        BigDecimal r2 = new BigDecimal("0.300000000000");

        List<FinanceBudgetDimensionsRules.TargetRatio> targets = List.of(
                new FinanceBudgetDimensionsRules.TargetRatio(t1, r1, 1),
                new FinanceBudgetDimensionsRules.TargetRatio(t2, r2, 2)
        );

        BigDecimal total = new BigDecimal("123.45678901");
        List<FinanceBudgetDimensionsRules.AllocatedTarget> result =
                FinanceBudgetDimensionsRules.allocate(total, targets, 8);

        assertEquals(2, result.size());
        BigDecimal sum = result.get(0).allocatedAmount().add(result.get(1).allocatedAmount());
        assertEquals(total, sum);
    }

    @Test
    void testZeroAmountAllocation() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();

        List<FinanceBudgetDimensionsRules.TargetRatio> targets = List.of(
                new FinanceBudgetDimensionsRules.TargetRatio(t1, new BigDecimal("0.500000000000"), 1),
                new FinanceBudgetDimensionsRules.TargetRatio(t2, new BigDecimal("0.500000000000"), 2)
        );

        List<FinanceBudgetDimensionsRules.AllocatedTarget> result =
                FinanceBudgetDimensionsRules.allocate(BigDecimal.ZERO, targets, 2);

        assertEquals(new BigDecimal("0.00"), result.get(0).allocatedAmount());
        assertEquals(new BigDecimal("0.00"), result.get(1).allocatedAmount());
    }

    @Test
    void testInvalidRatioSumRejection() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();

        List<FinanceBudgetDimensionsRules.TargetRatio> targets = List.of(
                new FinanceBudgetDimensionsRules.TargetRatio(t1, new BigDecimal("0.500000000000"), 1),
                new FinanceBudgetDimensionsRules.TargetRatio(t2, new BigDecimal("0.400000000000"), 2)
        );

        assertThrows(IllegalStateException.class, () ->
                FinanceBudgetDimensionsRules.allocate(new BigDecimal("100.00"), targets, 2));
    }

    @Test
    void testNegativeAmountRejection() {
        UUID t1 = UUID.randomUUID();
        List<FinanceBudgetDimensionsRules.TargetRatio> targets = List.of(
                new FinanceBudgetDimensionsRules.TargetRatio(t1, new BigDecimal("1.000000000000"), 1)
        );

        assertThrows(IllegalArgumentException.class, () ->
                FinanceBudgetDimensionsRules.allocate(new BigDecimal("-10.00"), targets, 2));
    }

    @Test
    void testSoDEnforcement() {
        UUID maker = UUID.randomUUID();
        UUID reviewer = UUID.randomUUID();

        assertDoesNotThrow(() -> FinanceBudgetDimensionsRules.validateSoD(maker, reviewer));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                FinanceBudgetDimensionsRules.validateSoD(maker, maker));
        assertTrue(ex.getMessage().contains("SELF_APPROVAL_FORBIDDEN"));
    }

    @Test
    void testControlLedgerInvariants() {
        BigDecimal budget = new BigDecimal("1000.00000000");
        BigDecimal reserved = new BigDecimal("400.00000000");
        BigDecimal consumed = new BigDecimal("500.00000000");

        assertEquals(new BigDecimal("100.00000000"),
                FinanceBudgetDimensionsRules.computeAvailable(budget, reserved, consumed));

        // Exceeding budget throws
        BigDecimal overConsumed = new BigDecimal("700.00000000");
        assertThrows(IllegalStateException.class, () ->
                FinanceBudgetDimensionsRules.validateControlLedgerInvariant(budget, reserved, overConsumed));
    }

    @Test
    void testForecastCalculationsAndHash() {
        BigDecimal ytd = new BigDecimal("450.00000000");
        BigDecimal remaining = new BigDecimal("600.00000000");
        BigDecimal baseline = new BigDecimal("1000.00000000");

        BigDecimal total = FinanceBudgetDimensionsRules.computeForecastTotal(ytd, remaining);
        assertEquals(new BigDecimal("1050.00000000"), total);

        BigDecimal variance = FinanceBudgetDimensionsRules.computeProjectedVariance(total, baseline);
        assertEquals(new BigDecimal("50.00000000"), variance);

        UUID t = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID y = UUID.randomUUID();
        UUID v = UUID.randomUUID();

        String hash = FinanceBudgetDimensionsRules.computeForecastSnapshotHash(t, b, y, 6, v, ytd, remaining, total, variance);
        assertNotNull(hash);
        assertEquals(64, hash.length());
        assertTrue(hash.matches("^[a-f0-9]{64}$"));
    }

    @Test
    void testFractionalScaleRejection() {
        UUID t1 = UUID.randomUUID();
        List<FinanceBudgetDimensionsRules.TargetRatio> targets = List.of(
                new FinanceBudgetDimensionsRules.TargetRatio(t1, new BigDecimal("1.000000000000"), 1)
        );

        // 100.123 has 3 decimal places, exceeding scale = 2
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                FinanceBudgetDimensionsRules.allocate(new BigDecimal("100.123"), targets, 2));
        assertTrue(ex.getMessage().contains("fractional digits exceed allocation scale"));
    }

    @Test
    void testNilUuidSoDRejection() {
        UUID nilUuid = new UUID(0L, 0L);
        UUID normal = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () ->
                FinanceBudgetDimensionsRules.validateSoD(nilUuid, normal));
        assertThrows(IllegalArgumentException.class, () ->
                FinanceBudgetDimensionsRules.validateSoD(normal, nilUuid));
    }

    @Test
    void testRollbackConsumeValidation() {
        BigDecimal consumed = new BigDecimal("500.00000000");
        BigDecimal validRollback = new BigDecimal("200.00000000");
        BigDecimal excessiveRollback = new BigDecimal("600.00000000");

        assertDoesNotThrow(() -> FinanceBudgetDimensionsRules.validateRollbackConsume(consumed, validRollback));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                FinanceBudgetDimensionsRules.validateRollbackConsume(consumed, excessiveRollback));
        assertTrue(ex.getMessage().contains("BUDGET_ROLLBACK_EXCEEDS_CONSUMED"));
    }
}

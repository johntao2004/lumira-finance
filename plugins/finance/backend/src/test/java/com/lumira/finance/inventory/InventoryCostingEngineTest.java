package com.lumira.finance.inventory;

import static com.lumira.finance.inventory.InventoryModel.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

class InventoryCostingEngineTest {

    @Test
    void fifoBasicConsumption() {
        UUID b1 = UUID.randomUUID();
        UUID b2 = UUID.randomUUID();
        List<FifoLayer> layers = List.of(
                new FifoLayer(b1, LocalDate.of(2026, 1, 5), new BigDecimal("100.00000000"), new BigDecimal("10.00000000")),
                new FifoLayer(b2, LocalDate.of(2026, 1, 10), new BigDecimal("200.00000000"), new BigDecimal("12.00000000"))
        );

        // Consume 150 units: 100 from b1 @ 10, 50 from b2 @ 12
        FifoResult res = InventoryCostingEngine.calculateFifoOutbound(layers, new BigDecimal("150.00000000"));
        assertEquals(new BigDecimal("150.00000000"), res.totalConsumedQuantity());
        // 100*10 + 50*12 = 1000 + 600 = 1600.00000000
        assertEquals(new BigDecimal("1600.00000000"), res.totalConsumedCost());
        assertEquals(2, res.allocations().size());
        assertEquals(b1, res.allocations().get(0).batchId());
        assertEquals(new BigDecimal("100.00000000"), res.allocations().get(0).quantityConsumed());
        assertEquals(b2, res.allocations().get(1).batchId());
        assertEquals(new BigDecimal("50.00000000"), res.allocations().get(1).quantityConsumed());

        // Updated layers: b2 remaining 150
        assertEquals(1, res.updatedLayers().size());
        assertEquals(b2, res.updatedLayers().get(0).batchId());
        assertEquals(new BigDecimal("150.00000000"), res.updatedLayers().get(0).remainingQuantity());
    }

    @Test
    void fifoInsufficientStockThrows() {
        List<FifoLayer> layers = List.of(
                new FifoLayer(UUID.randomUUID(), LocalDate.of(2026, 1, 5), new BigDecimal("10.00000000"), new BigDecimal("10.00000000"))
        );
        assertThrows(IllegalArgumentException.class, () ->
                InventoryCostingEngine.calculateFifoOutbound(layers, new BigDecimal("20.00000000")));
    }

    @Test
    void movingWeightedAverageContinuous() {
        // Current stock: 100 @ 10 = 1000
        // Inbound: 100 @ 14 = 1400
        // New unit cost = (1000 + 1400) / 200 = 2400 / 200 = 12.00000000
        BigDecimal unitCost = InventoryCostingEngine.calculateMovingWeightedAverage(
                new BigDecimal("100.00000000"), new BigDecimal("1000.00000000"),
                new BigDecimal("100.00000000"), new BigDecimal("1400.00000000")
        );
        assertEquals(new BigDecimal("12.00000000"), unitCost);
    }

    @Test
    void monthEndWeightedAverage() {
        // Opening: 100 @ 10 = 1000
        // Inbound 1: 50 @ 11 = 550
        // Inbound 2: 50 @ 13 = 650
        // Total Qty = 200, Total Cost = 2200, Unit Cost = 11.00000000
        StockMovement m1 = new StockMovement(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "IN-1", MovementType.PURCHASE_IN, UUID.randomUUID(), null, UUID.randomUUID(), null,
                new BigDecimal("50.00000000"), new BigDecimal("11.00000000"), new BigDecimal("550.00000000"),
                UUID.randomUUID(), LocalDate.of(2026, 1, 5), "POSTED", null, UUID.randomUUID(), null
        );
        StockMovement m2 = new StockMovement(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "IN-2", MovementType.PURCHASE_IN, UUID.randomUUID(), null, UUID.randomUUID(), null,
                new BigDecimal("50.00000000"), new BigDecimal("13.00000000"), new BigDecimal("650.00000000"),
                UUID.randomUUID(), LocalDate.of(2026, 1, 15), "POSTED", null, UUID.randomUUID(), null
        );

        BigDecimal unitCost = InventoryCostingEngine.calculateMonthEndWeightedAverage(
                new BigDecimal("100.00000000"), new BigDecimal("1000.00000000"),
                List.of(m1, m2)
        );
        assertEquals(new BigDecimal("11.00000000"), unitCost);
    }

    @Test
    void landedCostAllocationWithPennyAbsorption() {
        // Inbound 1: 100 units, cost 1000 (1/3 of 3000)
        // Inbound 2: 200 units, cost 2000 (2/3 of 3000)
        // Landed cost: 100.00000000
        StockMovement m1 = new StockMovement(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "IN-1", MovementType.PURCHASE_IN, UUID.randomUUID(), null, UUID.randomUUID(), null,
                new BigDecimal("100.00000000"), new BigDecimal("10.00000000"), new BigDecimal("1000.00000000"),
                UUID.randomUUID(), LocalDate.of(2026, 1, 5), "POSTED", null, UUID.randomUUID(), null
        );
        StockMovement m2 = new StockMovement(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "IN-2", MovementType.PURCHASE_IN, UUID.randomUUID(), null, UUID.randomUUID(), null,
                new BigDecimal("200.00000000"), new BigDecimal("10.00000000"), new BigDecimal("2000.00000000"),
                UUID.randomUUID(), LocalDate.of(2026, 1, 15), "POSTED", null, UUID.randomUUID(), null
        );

        BigDecimal landed = new BigDecimal("100.00000000");
        List<LandedCostAllocation> allocs = InventoryCostingEngine.allocateLandedCost(List.of(m1, m2), landed);
        assertEquals(2, allocs.size());

        BigDecimal sumAllocated = allocs.get(0).allocatedAmount().add(allocs.get(1).allocatedAmount());
        // Absorption ensures exact sum matches landed cost
        assertEquals(landed, sumAllocated);
    }

    @Test
    void cogsPlanBalanced() {
        UUID cogsAcc = UUID.randomUUID();
        UUID invAcc = UUID.randomUUID();
        StockMovement sale1 = new StockMovement(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "OUT-1", MovementType.SALES_OUT, UUID.randomUUID(), UUID.randomUUID(), null, null,
                new BigDecimal("10.00000000"), new BigDecimal("50.00000000"), new BigDecimal("500.00000000"),
                null, LocalDate.of(2026, 1, 20), "POSTED", null, UUID.randomUUID(), null
        );
        CogsPlan plan = InventoryCostingEngine.generateCogsPlan(
                UUID.randomUUID(), "2026-01", cogsAcc, invAcc, List.of(sale1)
        );

        assertEquals(1, plan.movementCount());
        assertEquals(new BigDecimal("500.00"), plan.totalCogsAmount());
        assertEquals(2, plan.lines().size());
        assertEquals(cogsAcc, plan.lines().get(0).accountId());
        assertEquals(new BigDecimal("500.00"), plan.lines().get(0).debit());
        assertEquals(invAcc, plan.lines().get(1).accountId());
        assertEquals(new BigDecimal("500.00"), plan.lines().get(1).credit());
    }

    @Test
    void cas1InventoryImpairmentAndReversal() {
        UUID item = UUID.randomUUID();
        BigDecimal bookCost = new BigDecimal("1000.00000000");
        BigDecimal nrvLow = new BigDecimal("800.00000000");

        // Period 1: NRV drops to 800 -> provision 200
        InventoryImpairmentEvaluation p1 = InventoryCostingEngine.evaluateInventoryImpairment(
                "CAS", "2026-01", item, bookCost, nrvLow, ZERO_8
        );
        assertEquals("PROVISIONED", p1.status());
        assertEquals(new BigDecimal("200.00000000"), p1.impairmentAdjustment());
        assertEquals(new BigDecimal("200.00000000"), p1.newImpairment());

        // Period 2: NRV recovers to 950 -> provision should be 50, so reverse 150
        BigDecimal nrvRec = new BigDecimal("950.00000000");
        InventoryImpairmentEvaluation p2 = InventoryCostingEngine.evaluateInventoryImpairment(
                "CAS", "2026-02", item, bookCost, nrvRec, p1.newImpairment()
        );
        assertEquals("REVERSED", p2.status());
        assertEquals(new BigDecimal("-150.00000000"), p2.impairmentAdjustment());
        assertEquals(new BigDecimal("50.00000000"), p2.newImpairment());

        // Period 3: NRV recovers to 1100 -> provision should be 0, reverse remaining 50
        BigDecimal nrvHigh = new BigDecimal("1100.00000000");
        InventoryImpairmentEvaluation p3 = InventoryCostingEngine.evaluateInventoryImpairment(
                "CAS", "2026-03", item, bookCost, nrvHigh, p2.newImpairment()
        );
        assertEquals("REVERSED", p3.status());
        assertEquals(new BigDecimal("-50.00000000"), p3.impairmentAdjustment());
        assertEquals(ZERO_8, p3.newImpairment());
    }

    @Test
    void smallInventoryImpairmentBlocked() {
        UUID item = UUID.randomUUID();
        BigDecimal bookCost = new BigDecimal("1000.00000000");
        BigDecimal nrvLow = new BigDecimal("800.00000000");

        InventoryImpairmentEvaluation eval = InventoryCostingEngine.evaluateInventoryImpairment(
                "SMALL", "2026-01", item, bookCost, nrvLow, ZERO_8
        );
        assertEquals("BLOCKED", eval.status());
        assertEquals(ZERO_8, eval.newImpairment());
    }

    @Test
    void acceleratedDepreciationDoubleDecliningBalance() {
        // 5-year asset (60 months), Cost 50000, Residual 2000
        // Total depreciable = 48000
        List<DepreciationScheduleRow> schedule = AssetDepreciationEngine.generateSchedule(
                DepreciationMethod.DOUBLE_DECLINING_BALANCE, "2026-01", 60,
                new BigDecimal("50000.00"), new BigDecimal("2000.00")
        );
        assertEquals(60, schedule.size());

        BigDecimal totalDepreciated = ZERO_8;
        for (DepreciationScheduleRow r : schedule) {
            totalDepreciated = totalDepreciated.add(r.monthlyDepreciation());
        }
        // Exact remainder absorption guarantees total depreciated equals Cost - Residual
        assertEquals(0, new BigDecimal("48000.00").compareTo(totalDepreciated));
        assertEquals(0, new BigDecimal("2000.00").compareTo(schedule.get(59).remainingCarryingAmount()));

        // Statutory Chinese CAS double declining balance annual amounts:
        // Y1: 50000 * 40% = 20000.00
        BigDecimal y1 = ZERO_8;
        for (int i = 0; i < 12; i++) y1 = y1.add(schedule.get(i).monthlyDepreciation());
        assertEquals(money("20000.00"), y1);

        // Y2: 30000 * 40% = 12000.00
        BigDecimal y2 = ZERO_8;
        for (int i = 12; i < 24; i++) y2 = y2.add(schedule.get(i).monthlyDepreciation());
        assertEquals(money("12000.00"), y2);

        // Y3: 18000 * 40% = 7200.00
        BigDecimal y3 = ZERO_8;
        for (int i = 24; i < 36; i++) y3 = y3.add(schedule.get(i).monthlyDepreciation());
        assertEquals(money("7200.00"), y3);

        // Y4 & Y5: Switch to straight-line on remaining (10800 - 2000) / 2 = 4400.00 each
        BigDecimal y4 = ZERO_8;
        for (int i = 36; i < 48; i++) y4 = y4.add(schedule.get(i).monthlyDepreciation());
        assertEquals(money("4400.00"), y4);

        BigDecimal y5 = ZERO_8;
        for (int i = 48; i < 60; i++) y5 = y5.add(schedule.get(i).monthlyDepreciation());
        assertEquals(money("4400.00"), y5);
    }

    @Test
    void acceleratedDepreciationSumOfTheYearsDigits() {
        // 3-year asset (36 months), Cost 30000, Residual 1200
        // Total depreciable = 28800
        List<DepreciationScheduleRow> schedule = AssetDepreciationEngine.generateSchedule(
                DepreciationMethod.SUM_OF_THE_YEARS_DIGITS, "2026-01", 36,
                new BigDecimal("30000.00"), new BigDecimal("1200.00")
        );
        assertEquals(36, schedule.size());

        BigDecimal totalDepreciated = ZERO_8;
        for (DepreciationScheduleRow r : schedule) {
            totalDepreciated = totalDepreciated.add(r.monthlyDepreciation());
        }
        assertEquals(0, new BigDecimal("28800.00").compareTo(totalDepreciated));
        assertEquals(0, new BigDecimal("1200.00").compareTo(schedule.get(35).remainingCarryingAmount()));

        // Statutory annual amounts:
        // Y1: 28800 * 3/6 = 14400.00
        BigDecimal y1 = ZERO_8;
        for (int i = 0; i < 12; i++) y1 = y1.add(schedule.get(i).monthlyDepreciation());
        assertEquals(money("14400.00"), y1);

        // Y2: 28800 * 2/6 = 9600.00
        BigDecimal y2 = ZERO_8;
        for (int i = 12; i < 24; i++) y2 = y2.add(schedule.get(i).monthlyDepreciation());
        assertEquals(money("9600.00"), y2);

        // Y3: 28800 * 1/6 = 4800.00
        BigDecimal y3 = ZERO_8;
        for (int i = 24; i < 36; i++) y3 = y3.add(schedule.get(i).monthlyDepreciation());
        assertEquals(money("4800.00"), y3);
    }

    @Test
    void doubleDecliningUnderTwoYearsDefaultsToStraightLine() {
        // 12 months asset: 12000 cost, 1200 residual -> 10800 depreciable = 900 / month
        List<DepreciationScheduleRow> schedule = AssetDepreciationEngine.generateSchedule(
                DepreciationMethod.DOUBLE_DECLINING_BALANCE, "2026-01", 12,
                new BigDecimal("12000.00"), new BigDecimal("1200.00")
        );
        assertEquals(12, schedule.size());
        assertEquals(new BigDecimal("900.00"), schedule.get(0).monthlyDepreciation());
        assertEquals(new BigDecimal("900.00"), schedule.get(11).monthlyDepreciation());
        assertEquals(new BigDecimal("1200.00"), schedule.get(11).remainingCarryingAmount());
    }

    @Test
    void depreciationParameterValidation() {
        assertThrows(IllegalArgumentException.class, () ->
                AssetDepreciationEngine.generateSchedule(null, "2026-01", 12, new BigDecimal("100"), new BigDecimal("10")));
        assertThrows(IllegalArgumentException.class, () ->
                AssetDepreciationEngine.generateSchedule(DepreciationMethod.STRAIGHT_LINE, "bad-period", 12, new BigDecimal("100"), new BigDecimal("10")));
        assertThrows(IllegalArgumentException.class, () ->
                AssetDepreciationEngine.generateSchedule(DepreciationMethod.STRAIGHT_LINE, "2026-01", 0, new BigDecimal("100"), new BigDecimal("10")));
        assertThrows(IllegalArgumentException.class, () ->
                AssetDepreciationEngine.generateSchedule(DepreciationMethod.STRAIGHT_LINE, "2026-01", 12, new BigDecimal("100"), new BigDecimal("-5")));
        assertThrows(IllegalArgumentException.class, () ->
                AssetDepreciationEngine.generateSchedule(DepreciationMethod.STRAIGHT_LINE, "2026-01", 12, new BigDecimal("100"), new BigDecimal("100")));
    }

    @Test
    void costingEngineBoundaryGuards() {
        // FIFO null layers
        assertThrows(IllegalArgumentException.class, () ->
                InventoryCostingEngine.calculateFifoOutbound(null, new BigDecimal("10")));

        // Moving WA negative cost or invalid inbound
        assertThrows(IllegalArgumentException.class, () ->
                InventoryCostingEngine.calculateMovingWeightedAverage(new BigDecimal("10"), new BigDecimal("-100"), new BigDecimal("10"), new BigDecimal("10")));
        assertThrows(IllegalArgumentException.class, () ->
                InventoryCostingEngine.calculateMovingWeightedAverage(new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0"), new BigDecimal("10")));

        // Landed cost empty or invalid
        assertThrows(IllegalArgumentException.class, () ->
                InventoryCostingEngine.allocateLandedCost(List.of(), new BigDecimal("100")));
        assertThrows(IllegalArgumentException.class, () ->
                InventoryCostingEngine.allocateLandedCost(null, new BigDecimal("100")));

        // Impairment regime and bounds
        assertThrows(IllegalArgumentException.class, () ->
                InventoryCostingEngine.evaluateInventoryImpairment("UNKNOWN", "2026-01", UUID.randomUUID(), new BigDecimal("100"), new BigDecimal("80"), ZERO_8));
        assertThrows(IllegalArgumentException.class, () ->
                InventoryCostingEngine.evaluateInventoryImpairment("CAS", "invalid-period", UUID.randomUUID(), new BigDecimal("100"), new BigDecimal("80"), ZERO_8));
    }

    @Test
    void cas8AssetImpairmentNonReversible() {
        UUID card = UUID.randomUUID();
        BigDecimal carrying = new BigDecimal("100000.00");
        BigDecimal recoverable = new BigDecimal("85000.00");

        // CAS 8 Impairment recognized: 15000.00
        AssetImpairmentEvaluation eval = AssetDepreciationEngine.evaluateAssetImpairment(
                "CAS", card, "2026-06", carrying, recoverable
        );
        assertEquals(new BigDecimal("15000.00"), eval.impairmentAmount());
        assertFalse(eval.isReversible());

        // Reversal assertion throws
        assertThrows(IllegalStateException.class, () ->
                AssetDepreciationEngine.assertAssetImpairmentNotReversible(eval));

        // SMALL throws immediately
        assertThrows(IllegalArgumentException.class, () ->
                AssetDepreciationEngine.evaluateAssetImpairment("SMALL", card, "2026-06", carrying, recoverable));
    }
}

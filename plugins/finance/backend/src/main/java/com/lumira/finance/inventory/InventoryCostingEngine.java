package com.lumira.finance.inventory;

import static com.lumira.finance.inventory.InventoryModel.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pure mathematical costing engine for inventory valuation:
 * - FIFO (First-In, First-Out with layer tracking)
 * - Moving Weighted Average
 * - Month-End Weighted Average
 * - Landed Cost Allocation with penny absorption
 * - CAS 1 NRV Impairment & Reversal vs SMALL Direct Recognition
 * - COGS Balanced Journal Entry Generation
 */
public final class InventoryCostingEngine {
    private InventoryCostingEngine() {}

    /**
     * Consumes stock according to First-In, First-Out (FIFO) layer order.
     */
    public static FifoResult calculateFifoOutbound(List<FifoLayer> layers, BigDecimal quantityToConsume) {
        if (layers == null) {
            throw new IllegalArgumentException("LAYERS_CANNOT_BE_NULL");
        }
        if (quantityToConsume == null || quantityToConsume.signum() <= 0) {
            throw new IllegalArgumentException("POSITIVE_QUANTITY_REQUIRED");
        }
        BigDecimal needed = quantityToConsume;
        BigDecimal totalCost = ZERO_8;
        List<FifoAllocation> allocations = new ArrayList<>();
        List<FifoLayer> updatedLayers = new ArrayList<>();

        for (FifoLayer layer : layers) {
            if (layer == null || layer.remainingQuantity() == null || layer.remainingQuantity().signum() <= 0) {
                continue;
            }
            if (needed.signum() == 0) {
                updatedLayers.add(layer);
                continue;
            }

            BigDecimal take = layer.remainingQuantity().min(needed);
            BigDecimal cost = take.multiply(layer.unitCost()).setScale(8, RoundingMode.HALF_UP);
            allocations.add(new FifoAllocation(layer.batchId(), take, layer.unitCost(), cost));
            totalCost = totalCost.add(cost);
            needed = needed.subtract(take);

            BigDecimal remaining = layer.remainingQuantity().subtract(take);
            if (remaining.signum() > 0) {
                updatedLayers.add(new FifoLayer(layer.batchId(), layer.inboundDate(), remaining, layer.unitCost()));
            }
        }

        if (needed.signum() > 0) {
            throw new IllegalArgumentException("INSUFFICIENT_STOCK");
        }

        return new FifoResult(quantityToConsume, totalCost, allocations, updatedLayers);
    }

    /**
     * Continuous moving weighted average recalculation upon new inbound shipment.
     */
    public static BigDecimal calculateMovingWeightedAverage(
            BigDecimal currentQty, BigDecimal currentTotalCost,
            BigDecimal inboundQty, BigDecimal inboundCost) {
        if (inboundQty == null || inboundQty.signum() <= 0) {
            throw new IllegalArgumentException("POSITIVE_INBOUND_QTY_REQUIRED");
        }
        BigDecimal cQty = currentQty != null ? currentQty : ZERO_8;
        BigDecimal cCost = currentTotalCost != null ? currentTotalCost : ZERO_8;
        BigDecimal inCost = inboundCost != null ? inboundCost : ZERO_8;

        if (cQty.signum() < 0) {
            throw new IllegalArgumentException("NON_NEGATIVE_CURRENT_QTY_REQUIRED");
        }
        if (cCost.signum() < 0 || inCost.signum() < 0) {
            throw new IllegalArgumentException("NON_NEGATIVE_COST_REQUIRED");
        }

        BigDecimal totalQty = cQty.add(inboundQty);
        BigDecimal totalCost = cCost.add(inCost);
        if (totalQty.signum() <= 0) {
            return ZERO_8;
        }
        return totalCost.divide(totalQty, 8, RoundingMode.HALF_UP);
    }

    /**
     * Month-end weighted average calculation across opening balance and monthly additions.
     */
    public static BigDecimal calculateMonthEndWeightedAverage(
            BigDecimal openingQty, BigDecimal openingCost,
            List<StockMovement> monthInbounds) {
        BigDecimal totalQty = openingQty != null ? openingQty : ZERO_8;
        BigDecimal totalCost = openingCost != null ? openingCost : ZERO_8;

        if (totalQty.signum() < 0 || totalCost.signum() < 0) {
            throw new IllegalArgumentException("NON_NEGATIVE_OPENING_BALANCE_REQUIRED");
        }

        if (monthInbounds != null) {
            for (StockMovement m : monthInbounds) {
                if (m != null) {
                    if (m.quantity() != null && m.quantity().signum() > 0) {
                        totalQty = totalQty.add(m.quantity());
                    }
                    if (m.totalCost() != null && m.totalCost().signum() >= 0) {
                        totalCost = totalCost.add(m.totalCost());
                    }
                }
            }
        }

        if (totalQty.signum() <= 0) {
            return ZERO_8;
        }
        return totalCost.divide(totalQty, 8, RoundingMode.HALF_UP);
    }

    /**
     * Proportional Landed Cost allocation (freight, duties, handling) with remainder absorption.
     */
    public static List<LandedCostAllocation> allocateLandedCost(
            List<StockMovement> inbounds, BigDecimal landedCostAmount) {
        if (landedCostAmount == null || landedCostAmount.signum() <= 0) {
            throw new IllegalArgumentException("POSITIVE_LANDED_COST_REQUIRED");
        }
        if (inbounds == null || inbounds.isEmpty()) {
            throw new IllegalArgumentException("INBOUNDS_EMPTY");
        }

        BigDecimal totalPurchaseValue = ZERO_8;
        for (StockMovement m : inbounds) {
            if (m == null) {
                throw new IllegalArgumentException("INBOUND_MOVEMENT_CANNOT_BE_NULL");
            }
            if (m.quantity() == null || m.quantity().signum() <= 0) {
                throw new IllegalArgumentException("POSITIVE_QUANTITY_REQUIRED");
            }
            if (m.totalCost() == null || m.totalCost().signum() < 0) {
                throw new IllegalArgumentException("NON_NEGATIVE_TOTAL_COST_REQUIRED");
            }
            totalPurchaseValue = totalPurchaseValue.add(m.totalCost());
        }

        if (totalPurchaseValue.signum() == 0) {
            throw new IllegalArgumentException("ZERO_PURCHASE_VALUE_CANNOT_ALLOCATE");
        }

        List<LandedCostAllocation> result = new ArrayList<>();
        BigDecimal allocatedSum = ZERO_8;

        for (int i = 0; i < inbounds.size(); i++) {
            StockMovement m = inbounds.get(i);
            BigDecimal share;
            if (i == inbounds.size() - 1) {
                // Final item absorbs any fraction/penny rounding remainder
                share = landedCostAmount.subtract(allocatedSum);
            } else {
                share = landedCostAmount.multiply(m.totalCost())
                        .divide(totalPurchaseValue, 8, RoundingMode.HALF_UP);
                allocatedSum = allocatedSum.add(share);
            }

            BigDecimal finalTotalCost = m.totalCost().add(share);
            BigDecimal finalUnitCost = finalTotalCost.divide(m.quantity(), 8, RoundingMode.HALF_UP);

            result.add(new LandedCostAllocation(
                    m.id(), m.totalCost(), share, finalTotalCost, finalUnitCost
            ));
        }

        return result;
    }

    /**
     * Regime-aware valuation: CAS 1 NRV Impairment & Reversal vs SMALL Direct Recognition.
     */
    public static InventoryImpairmentEvaluation evaluateInventoryImpairment(
            String regime, String period, UUID itemId,
            BigDecimal bookCost, BigDecimal nrv, BigDecimal priorImpairment) {
        if (!"CAS".equals(regime) && !"SMALL".equals(regime)) {
            throw new IllegalArgumentException("INVALID_ACCOUNTING_REGIME");
        }
        if (period == null || !period.matches("^[0-9]{4}-(?:0[1-9]|1[0-2])$")) {
            throw new IllegalArgumentException("INVALID_PERIOD_FORMAT");
        }
        if (itemId == null) {
            throw new IllegalArgumentException("ITEM_ID_REQUIRED");
        }
        if (bookCost == null || bookCost.signum() < 0) {
            throw new IllegalArgumentException("NON_NEGATIVE_BOOK_COST_REQUIRED");
        }
        if (nrv == null || nrv.signum() < 0) {
            throw new IllegalArgumentException("NON_NEGATIVE_NRV_REQUIRED");
        }
        if (priorImpairment != null && priorImpairment.signum() < 0) {
            throw new IllegalArgumentException("NON_NEGATIVE_PRIOR_IMPAIRMENT_REQUIRED");
        }

        if ("SMALL".equals(regime)) {
            // Under Small Enterprise Accounting Standards, inventory impairment provision is explicitly blocked
            return new InventoryImpairmentEvaluation(
                    UUID.randomUUID(), itemId, regime, period,
                    bookCost, nrv, ZERO_8, ZERO_8, ZERO_8, "BLOCKED"
            );
        }

        // CAS 1 Lower of Cost and Net Realizable Value (NRV)
        BigDecimal targetProvision;
        if (nrv.compareTo(bookCost) < 0) {
            targetProvision = bookCost.subtract(nrv);
        } else {
            targetProvision = ZERO_8;
        }

        BigDecimal currentPrior = priorImpairment != null ? priorImpairment : ZERO_8;
        BigDecimal adjustment = targetProvision.subtract(currentPrior);

        String status;
        if (adjustment.signum() > 0) {
            status = "PROVISIONED";
        } else if (adjustment.signum() < 0) {
            status = "REVERSED";
        } else {
            status = "NONE";
        }

        return new InventoryImpairmentEvaluation(
                UUID.randomUUID(), itemId, regime, period,
                bookCost, nrv, currentPrior, adjustment, targetProvision, status
        );
    }

    /**
     * Periodical COGS (Cost of Goods Sold) calculation and balanced journal line generation.
     */
    public static CogsPlan generateCogsPlan(
            UUID planId, String period, UUID cogsAccount, UUID inventoryAccount,
            List<StockMovement> salesMovements) {
        if (planId == null) {
            throw new IllegalArgumentException("PLAN_ID_REQUIRED");
        }
        if (period == null || !period.matches("^[0-9]{4}-(?:0[1-9]|1[0-2])$")) {
            throw new IllegalArgumentException("INVALID_PERIOD_FORMAT");
        }
        if (cogsAccount == null || inventoryAccount == null || cogsAccount.equals(inventoryAccount)) {
            throw new IllegalArgumentException("DISTINCT_ACCOUNT_MAPPING_REQUIRED");
        }
        if (salesMovements == null) {
            throw new IllegalArgumentException("SALES_MOVEMENTS_CANNOT_BE_NULL");
        }

        BigDecimal totalCost = ZERO_8;
        int count = 0;
        for (StockMovement m : salesMovements) {
            if (m != null && m.movementType() == MovementType.SALES_OUT) {
                totalCost = totalCost.add(m.totalCost());
                count++;
            }
        }

        BigDecimal roundedCogs = roundCents(totalCost);
        List<JournalLine> lines = List.of(
                new JournalLine(cogsAccount, roundedCogs, ZERO_8),
                new JournalLine(inventoryAccount, ZERO_8, roundedCogs)
        );

        return new CogsPlan(planId, period, count, roundedCogs, lines);
    }
}

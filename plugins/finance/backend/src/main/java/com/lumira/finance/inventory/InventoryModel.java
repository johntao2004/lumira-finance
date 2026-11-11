package com.lumira.finance.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Domain model for Inventory (INV01, INV02, INV03) and Asset extensions (AS01, AS02, AS03).
 * Immutable records with exact numeric(28,8) and CNY financial rules.
 */
public final class InventoryModel {
    private InventoryModel() {}

    public static final BigDecimal ZERO_8 = new BigDecimal("0.00000000");

    public static BigDecimal money(String raw) {
        if (raw == null || !raw.matches("(?:0|[1-9][0-9]{0,19})(?:\\.[0-9]{1,2})?")) {
            throw new IllegalArgumentException("CNY_TWO_DECIMALS_REQUIRED");
        }
        return new BigDecimal(raw).setScale(8, RoundingMode.UNNECESSARY);
    }

    public static BigDecimal scale8(BigDecimal val) {
        if (val == null || val.signum() < 0) {
            throw new IllegalArgumentException("NON_NEGATIVE_AMOUNT_REQUIRED");
        }
        return val.setScale(8, RoundingMode.HALF_UP);
    }

    public static BigDecimal roundCents(BigDecimal val) {
        if (val == null) return BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        return val.setScale(2, RoundingMode.HALF_UP);
    }

    public enum ValuationMethod {
        FIFO,
        MOVING_WEIGHTED_AVERAGE,
        MONTH_END_WEIGHTED_AVERAGE,
        SPECIFIC_BATCH
    }

    public enum MovementType {
        PURCHASE_IN,
        PRODUCTION_ISSUE,
        PRODUCTION_IN,
        SALES_OUT,
        TRANSFER
    }

    public enum DepreciationMethod {
        STRAIGHT_LINE,
        DOUBLE_DECLINING_BALANCE,
        SUM_OF_THE_YEARS_DIGITS
    }

    public record Scope(UUID tenantId, UUID bookId, UUID yearId) {}
    public record Actor(UUID accountId, UUID personId) {}

    public record Warehouse(
        UUID id,
        String code,
        String name,
        String location,
        boolean isActive
    ) {}

    public record InventoryItem(
        UUID id,
        String code,
        String name,
        String spec,
        String unit,
        ValuationMethod valuationMethod,
        UUID inventoryAccountId,
        UUID cogsAccountId,
        UUID lossGainAccountId,
        UUID impairmentAccountId,
        UUID impairmentExpenseAccountId,
        boolean isActive
    ) {}

    public record Batch(
        UUID id,
        UUID itemId,
        String batchNo,
        LocalDate inboundDate,
        BigDecimal initialCost,
        BigDecimal remainingQuantity
    ) {}

    public record StockMovement(
        UUID id,
        UUID tenantId,
        UUID bookId,
        UUID yearId,
        String movementNo,
        MovementType movementType,
        UUID itemId,
        UUID sourceWarehouseId,
        UUID destWarehouseId,
        UUID batchId,
        BigDecimal quantity,
        BigDecimal unitCost,
        BigDecimal totalCost,
        UUID sourceEvidenceId,
        LocalDate businessDate,
        String status,
        UUID journalId,
        UUID createdBy,
        UUID approvedBy
    ) {}

    public record FifoLayer(
        UUID batchId,
        LocalDate inboundDate,
        BigDecimal remainingQuantity,
        BigDecimal unitCost
    ) {}

    public record FifoAllocation(
        UUID batchId,
        BigDecimal quantityConsumed,
        BigDecimal unitCost,
        BigDecimal costConsumed
    ) {}

    public record FifoResult(
        BigDecimal totalConsumedQuantity,
        BigDecimal totalConsumedCost,
        List<FifoAllocation> allocations,
        List<FifoLayer> updatedLayers
    ) {}

    public record LandedCostAllocation(
        UUID movementId,
        BigDecimal purchaseValue,
        BigDecimal allocatedAmount,
        BigDecimal finalTotalCost,
        BigDecimal finalUnitCost
    ) {}

    public record JournalLine(
        UUID accountId,
        BigDecimal debit,
        BigDecimal credit
    ) {}

    public record CogsPlan(
        UUID planId,
        String period,
        int movementCount,
        BigDecimal totalCogsAmount,
        List<JournalLine> lines
    ) {}

    public record StocktakeSheet(
        UUID id,
        UUID warehouseId,
        LocalDate cutoffDate,
        String sheetNo,
        String status,
        UUID makerId,
        UUID approverId
    ) {}

    public record StocktakeLine(
        UUID id,
        UUID stocktakeId,
        UUID itemId,
        UUID batchId,
        BigDecimal bookQuantity,
        BigDecimal bookUnitCost,
        BigDecimal countedQuantity,
        BigDecimal varianceQuantity,
        BigDecimal varianceAmount,
        String varianceType,
        String disposition
    ) {}

    public record InventoryImpairmentEvaluation(
        UUID id,
        UUID itemId,
        String regime,
        String period,
        BigDecimal bookCost,
        BigDecimal nrv,
        BigDecimal priorImpairment,
        BigDecimal impairmentAdjustment,
        BigDecimal newImpairment,
        String status
    ) {}

    public record CipProject(
        UUID id,
        String projectCode,
        String projectName,
        BigDecimal accumulatedCost,
        String status
    ) {}

    public record CipCapitalizationCard(
        String name,
        String classification,
        String location,
        UUID custodian,
        BigDecimal cost,
        String firstPeriod,
        int months,
        BigDecimal residual
    ) {}

    public record CipCapitalizationPlan(
        UUID id,
        UUID projectId,
        BigDecimal capitalizedCost,
        List<CipCapitalizationCard> targetCards,
        UUID makerId,
        UUID approverId,
        String status
    ) {}

    public record RdProject(
        UUID id,
        String projectCode,
        String projectName,
        String stage,
        BigDecimal expensedAmount,
        BigDecimal capitalizedAmount,
        String status
    ) {}

    public record RdCapitalizationPlan(
        UUID id,
        UUID projectId,
        String intangibleAssetName,
        BigDecimal capitalizedAmount,
        UUID sourceEvidenceId,
        UUID makerId,
        UUID approverId,
        String status
    ) {}

    public record DepreciationScheduleRow(
        String period,
        int monthIndex,
        BigDecimal monthlyDepreciation,
        BigDecimal remainingCarryingAmount
    ) {}

    public record AssetImpairmentEvaluation(
        UUID id,
        UUID assetCardId,
        String regime,
        String period,
        BigDecimal carryingAmount,
        BigDecimal recoverableAmount,
        BigDecimal impairmentAmount,
        boolean isReversible,
        String status
    ) {}

    public record AssetStocktakeSheet(
        UUID id,
        String sheetNo,
        LocalDate stocktakeDate,
        String status,
        UUID makerId,
        UUID approverId
    ) {}

    public record AssetStocktakeLine(
        UUID id,
        UUID stocktakeId,
        UUID assetCardId,
        String tagRfid,
        String bookLocation,
        String actualLocation,
        String varianceType,
        String disposition
    ) {}
}

package com.lumira.finance.subledgerext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.lumira.finance.subledgerext.SubledgerExtRules.*;

/**
 * Domain records for AP01, AR01, and AR02 subledger extensions.
 */
public final class SubledgerExtModels {
    private SubledgerExtModels() {}

    public record PoLine(
            UUID id,
            UUID poId,
            int lineNo,
            String itemCode,
            String itemName,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal netAmount,
            BigDecimal taxRate,
            BigDecimal taxAmount,
            BigDecimal grossAmount,
            BigDecimal receivedQuantity,
            BigDecimal invoicedQuantity
    ) {}

    public record PoOrder(
            UUID id,
            UUID tenantId,
            UUID bookId,
            UUID yearId,
            String poNumber,
            UUID supplierId,
            UUID supplierRevisionId,
            LocalDate orderDate,
            String currency,
            BigDecimal totalNetAmount,
            BigDecimal totalTaxAmount,
            BigDecimal totalGrossAmount,
            String terms,
            PoStatus status,
            UUID originalId,
            UUID createdBy,
            UUID approvedBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            long version,
            List<PoLine> lines
    ) {}

    public record AcceptanceLine(
            UUID id,
            UUID acceptanceId,
            UUID poLineId,
            int lineNo,
            BigDecimal acceptedQuantity,
            BigDecimal unitCostEstimate,
            BigDecimal estimatedNetAmount,
            BigDecimal invoicedQuantity
    ) {}

    public record PoAcceptance(
            UUID id,
            UUID tenantId,
            UUID bookId,
            UUID yearId,
            UUID poId,
            String acceptanceNumber,
            LocalDate acceptedDate,
            UUID acceptedBy,
            String acceptanceNote,
            AcceptanceStatus status,
            InvoiceStatus invoiceStatus,
            ProvisionalStatus provisionalStatus,
            UUID originalId,
            OffsetDateTime createdAt,
            long version,
            List<AcceptanceLine> lines
    ) {}

    public record ProvisionalAccrual(
            UUID id,
            UUID tenantId,
            UUID bookId,
            UUID yearId,
            UUID acceptanceId,
            UUID poId,
            int accrualPeriod,
            LocalDate accrualDate,
            BigDecimal estimatedNetAmount,
            ProvisionalStatus status,
            UUID journalId,
            Integer reversalPeriod,
            LocalDate reversalDate,
            UUID reversalJournalId,
            String reason,
            UUID createdBy,
            UUID approvedBy,
            OffsetDateTime createdAt,
            long version
    ) {}

    public record ProvisionalSettlement(
            UUID id,
            UUID tenantId,
            UUID bookId,
            UUID yearId,
            UUID provisionalId,
            String invoiceNumber,
            LocalDate invoiceDate,
            BigDecimal actualNetAmount,
            BigDecimal actualTaxAmount,
            BigDecimal actualGrossAmount,
            BigDecimal priceVariance,
            BigDecimal quantityVariance,
            BigDecimal totalVariance,
            String status,
            UUID journalId,
            UUID createdBy,
            UUID approvedBy,
            OffsetDateTime createdAt,
            long version
    ) {}

    public record ContractAsset(
            UUID id,
            UUID tenantId,
            UUID bookId,
            UUID yearId,
            UUID contractId,
            UUID customerId,
            String stageName,
            LocalDate milestoneDate,
            BigDecimal assetAmount,
            String conditionDescription,
            ConditionStatus conditionStatus,
            LocalDate reclassifiedDate,
            UUID reclassifiedArId,
            ContractAssetStatus status,
            UUID journalId,
            UUID createdBy,
            UUID approvedBy,
            OffsetDateTime createdAt,
            long version
    ) {}

    public record VariableConsideration(
            UUID id,
            UUID tenantId,
            UUID bookId,
            UUID yearId,
            UUID contractId,
            VariableKind variableKind,
            BigDecimal estimatedAmount,
            BigDecimal confidenceScore,
            BigDecimal constrainedCapAmount,
            String capRationale,
            VariableStatus status,
            UUID preparerId,
            UUID approvedBy,
            OffsetDateTime approvedAt,
            String approvalDigest,
            OffsetDateTime createdAt,
            long version
    ) {}

    public record MultiYearContract(
            UUID id,
            UUID tenantId,
            UUID bookId,
            UUID contractId,
            int startYear,
            int endYear,
            BigDecimal totalContractValue,
            BigDecimal cumulativeProgressPct,
            BigDecimal cumulativeRevenueRecognized,
            BigDecimal currentYearRevenue,
            BigDecimal unrecognizedBalance,
            int lastSyncYear,
            MultiYearStatus status,
            OffsetDateTime updatedAt,
            long version
    ) {}

    public record EclRecord(
            UUID id,
            UUID tenantId,
            UUID bookId,
            UUID yearId,
            int period,
            EclAssessmentType assessmentType,
            UUID customerId,
            BigDecimal receivableAmount,
            AgingBucket agingBucket,
            BigDecimal lossRate,
            BigDecimal provisionAmount,
            BigDecimal priorAllowance,
            BigDecimal adjustmentAmount,
            EclOperationKind operationKind,
            EclStatus status,
            UUID preparerId,
            UUID approverId,
            String approvalDigest,
            UUID evidenceId,
            UUID journalId,
            OffsetDateTime createdAt,
            long version
    ) {}

    public record AllowanceAdjustment(
            UUID id,
            UUID tenantId,
            UUID bookId,
            UUID yearId,
            UUID customerId,
            UUID contractId,
            UUID receivableId,
            AllowanceType adjustmentType,
            String creditNoteNumber,
            LocalDate adjustmentDate,
            BigDecimal originalReceivableAmount,
            BigDecimal netAdjustmentAmount,
            BigDecimal taxAdjustmentAmount,
            BigDecimal grossAdjustmentAmount,
            String vatRedInvoiceInfo,
            String reason,
            AllowanceStatus status,
            UUID preparerId,
            UUID approverId,
            String approvalDigest,
            UUID journalId,
            OffsetDateTime createdAt,
            long version
    ) {}
}

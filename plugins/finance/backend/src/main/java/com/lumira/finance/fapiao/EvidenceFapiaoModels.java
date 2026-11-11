package com.lumira.finance.fapiao;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Domain records and models for W04 Evidence & Fapiao candidates.
 */
public final class EvidenceFapiaoModels {

    private EvidenceFapiaoModels() {}

    public static BigDecimal roundMoney(BigDecimal val) {
        if (val == null) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return val.setScale(2, RoundingMode.HALF_UP);
    }

    public record CompletenessRecord(
            UUID id,
            UUID tenantId,
            UUID legalEntityId,
            UUID sourceOriginalId,
            EvidenceClassification classification,
            EvidenceCompletenessState completenessState,
            boolean isComplete,
            List<String> missingReasons,
            String paperStorageLocation,
            Map<String, Object> metadataIndex,
            long version,
            UUID predecessorId,
            Map<String, Object> createdBy,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record VerificationRecord(
            UUID id,
            UUID tenantId,
            UUID legalEntityId,
            UUID evidenceId,
            String verificationMethod,
            String structuralDigest,
            String ocrDigest,
            String comparisonStatus,
            Map<String, Object> fieldDiscrepancies,
            String taxBureauVerification, // Always "NOT_VERIFIED"
            String verifier,
            Instant verifiedAt,
            String reviewNotes
    ) {}

    public record ExtendedQuotaRecord(
            UUID id,
            UUID tenantId,
            UUID legalEntityId,
            UUID evidenceId,
            ExtendedQuotaBucket bucketType,
            String currency,
            BigDecimal capacity,
            BigDecimal reservedAmount,
            BigDecimal committedAmount,
            BigDecimal releasedAmount,
            BigDecimal availableAmount,
            Instant createdAt,
            Instant updatedAt
    ) {
        public ExtendedQuotaRecord {
            capacity = roundMoney(capacity);
            reservedAmount = roundMoney(reservedAmount);
            committedAmount = roundMoney(committedAmount);
            releasedAmount = roundMoney(releasedAmount);
            availableAmount = capacity.subtract(reservedAmount).subtract(committedAmount);
        }
    }

    public record ExtendedQuotaLogRecord(
            UUID id,
            UUID tenantId,
            UUID legalEntityId,
            UUID quotaId,
            String operationType,
            BigDecimal amount,
            UUID businessChainId,
            Map<String, Object> operator,
            Instant createdAt
    ) {
        public ExtendedQuotaLogRecord {
            amount = roundMoney(amount);
        }
    }

    public record GraphNodeRecord(
            UUID id,
            UUID tenantId,
            UUID legalEntityId,
            GraphNodeType nodeType,
            String businessRefId,
            String nodeLabel,
            Map<String, Object> metadata,
            Instant createdAt
    ) {}

    public record GraphEdgeRecord(
            UUID id,
            UUID tenantId,
            UUID legalEntityId,
            UUID sourceNodeId,
            UUID targetNodeId,
            GraphEdgeType relationshipType,
            BigDecimal amount,
            Map<String, Object> metadata,
            Instant createdAt
    ) {
        public GraphEdgeRecord {
            if (amount != null) amount = roundMoney(amount);
        }
    }

    public record EvidenceExceptionRecord(
            UUID id,
            UUID tenantId,
            UUID legalEntityId,
            UUID businessChainId,
            EvidenceExceptionType exceptionType,
            String description,
            BigDecimal provisionalAmount,
            String responsiblePersonId,
            String responsiblePersonName,
            LocalDate dueDate,
            EvidenceExceptionStatus status,
            String approvalReason,
            Map<String, Object> approvedBy,
            Instant approvedAt,
            Map<String, Object> createdBy,
            Instant createdAt,
            Instant updatedAt
    ) {
        public EvidenceExceptionRecord {
            provisionalAmount = roundMoney(provisionalAmount);
        }
    }

    public record FapiaoPoolRecord(
            UUID id,
            UUID tenantId,
            UUID legalEntityId,
            FapiaoDirection direction,
            String invoiceCode,
            String fapiaoNumber,
            FapiaoType fapiaoType,
            LocalDate issueDate,
            String buyerTaxNo,
            String buyerName,
            String sellerTaxNo,
            String sellerName,
            BigDecimal untaxedAmount,
            BigDecimal taxRate,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            UUID originalDocumentId,
            String taxBureauVerification, // Always "NOT_VERIFIED"
            FapiaoLifecycleStatus lifecycleStatus,
            UUID ledgerJournalId,
            UUID taxWorksheetId,
            String reconciliationStatus,
            Instant createdAt,
            Instant updatedAt
    ) {
        public FapiaoPoolRecord {
            untaxedAmount = roundMoney(untaxedAmount);
            taxAmount = roundMoney(taxAmount);
            totalAmount = roundMoney(totalAmount);
        }
    }

    public record IssuanceAppRecord(
            UUID id,
            UUID tenantId,
            UUID legalEntityId,
            String applicationNumber,
            Map<String, Object> applicant,
            LocalDate applicationDate,
            String contractId,
            String customerId,
            String buyerTaxNo,
            String buyerName,
            String buyerAddressPhone,
            String buyerBankAccount,
            BuyerQualificationStatus buyerQualificationStatus,
            String qualificationNotes,
            Map<String, Object> reviewer,
            Instant reviewedAt,
            BigDecimal untaxedAmount,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            List<Map<String, Object>> items,
            IssuanceAppStatus status,
            String externalTaxChannel, // Always "NOT_VERIFIED"
            UUID issuedFapiaoId,
            String manualConfirmationNotes,
            Map<String, Object> confirmedBy,
            Instant confirmedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        public IssuanceAppRecord {
            untaxedAmount = roundMoney(untaxedAmount);
            taxAmount = roundMoney(taxAmount);
            totalAmount = roundMoney(totalAmount);
        }
    }

    public record RedNoticeRecord(
            UUID id,
            UUID tenantId,
            UUID legalEntityId,
            String redNoticeNumber,
            UUID originalFapiaoId,
            String direction,
            String initiator,
            String reason,
            BigDecimal redUntaxedAmount,
            BigDecimal redTaxAmount,
            BigDecimal redTotalAmount,
            RedNoticeStatus status,
            Instant registeredAt,
            Instant expiresAt,
            Instant confirmedAt,
            UUID creditFapiaoId,
            BigDecimal accountingRevenueReversalAmount,
            BigDecimal vatAdjustmentAmount,
            UUID revenueReversalJournalId,
            UUID vatAdjustmentJournalId,
            Instant createdAt,
            Instant updatedAt
    ) {
        public RedNoticeRecord {
            redUntaxedAmount = roundMoney(redUntaxedAmount);
            redTaxAmount = roundMoney(redTaxAmount);
            redTotalAmount = roundMoney(redTotalAmount);
            accountingRevenueReversalAmount = roundMoney(accountingRevenueReversalAmount);
            vatAdjustmentAmount = roundMoney(vatAdjustmentAmount);
        }
    }

    public record ExportIndexRecord(
            UUID evidenceId,
            String catalogIndexCode,
            String filename,
            String contentType,
            Long byteSize,
            String sha256,
            EvidenceClassification classification,
            String paperStorageLocation,
            Instant collectedAt,
            EvidenceCompletenessState completenessStatus,
            boolean isComplete,
            long version
    ) {}

    public record EvidencePathRecord(
            UUID startNodeId,
            List<GraphNodeRecord> upstreamNodes,
            List<GraphNodeRecord> downstreamNodes,
            List<GraphEdgeRecord> paths
    ) {}

    public record CumulativeRedNoticeInfo(
            BigDecimal cumulativeUntaxedAmount,
            BigDecimal cumulativeTaxAmount,
            BigDecimal cumulativeTotalAmount,
            BigDecimal remainingUntaxedAmount,
            BigDecimal remainingTaxAmount,
            BigDecimal remainingTotalAmount
    ) {}
}

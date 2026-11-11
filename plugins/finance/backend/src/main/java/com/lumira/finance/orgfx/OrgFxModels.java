package com.lumira.finance.orgfx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Domain models and DTOs for Lane W01 (org-fx).
 * Covers ORG01, ORG02, ORG03, ORG04, and ORG05.
 */
public final class OrgFxModels {
    private OrgFxModels() {}

    public record Actor(UUID accountId, UUID personId, UUID bindingId) {
        public static Actor of(UUID accountId, UUID personId) {
            return new Actor(accountId, personId, UUID.randomUUID());
        }
    }

    // --- ORG01: Tax Bureau, Branch & Entity Lifecycle ---

    public record TaxBureau(
            String bureauCode,
            String bureauName,
            String provinceDistrictCode,
            String authorityLevel,
            boolean isActive
    ) {}

    public record Branch(
            UUID id,
            UUID tenantId,
            UUID legalEntityId,
            String branchCode,
            String branchName,
            String branchType,
            String taxFilingMode,
            String taxBureauCode,
            String taxBureauName,
            String address,
            String status,
            long version,
            OffsetDateTime createdAt
    ) {}

    public enum EntityStatus {
        PREPARING,
        PENDING_CONFIRMATION,
        ACTIVE,
        SUSPENDED,
        TERMINATED
    }

    public record EntityLifecycleRecord(
            UUID tenantId,
            UUID legalEntityId,
            EntityStatus currentStatus,
            long version,
            OffsetDateTime updatedAt
    ) {}

    public record EntityLifecycleEvent(
            UUID id,
            UUID tenantId,
            UUID legalEntityId,
            EntityStatus fromStatus,
            EntityStatus toStatus,
            String reason,
            Actor maker,
            Actor reviewer,
            OffsetDateTime createdAt
    ) {}

    // --- ORG02: Standards Eligibility, Policy Versions & Conversion Bridge ---

    public enum AccountingStandard {
        CAS,
        SMALL
    }

    public record StandardEligibilityAssessment(
            UUID id,
            UUID tenantId,
            UUID legalEntityId,
            boolean isPubliclyTraded,
            boolean isFinancialInstitution,
            boolean isGroupSubsidiaryOfPublic,
            int employeeCount,
            BigDecimal annualRevenue,
            BigDecimal totalAssets,
            boolean eligibleForSmall,
            AccountingStandard evaluatedStandard,
            List<String> disqualificationReasons,
            Actor assessor,
            String evaluationNotes,
            OffsetDateTime assessedAt
    ) {}

    public enum PolicyStatus {
        DRAFT,
        APPROVED,
        SUPERSEDED
    }

    public record PolicyVersion(
            UUID id,
            UUID tenantId,
            UUID bookId,
            int policyVersionNo,
            AccountingStandard accountingStandard,
            String depreciationMethod,
            String inventoryCostingMethod,
            boolean impairmentAllowed,
            boolean badDebtProvisionAllowed,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            PolicyStatus status,
            Actor maker,
            Actor reviewer,
            String approvalDigest,
            long version,
            OffsetDateTime createdAt
    ) {}

    public record ConversionBridge(
            UUID id,
            UUID tenantId,
            UUID bookId,
            AccountingStandard fromStandard,
            AccountingStandard toStandard,
            int effectiveFiscalYear,
            String status,
            Actor maker,
            Actor reviewer,
            BigDecimal totalAdjustmentDebit,
            BigDecimal totalAdjustmentCredit,
            String justification,
            List<ConversionBridgeItem> items,
            long version,
            OffsetDateTime createdAt
    ) {}

    public record ConversionBridgeItem(
            UUID id,
            UUID bridgeId,
            String accountCode,
            String accountName,
            BigDecimal originalBalance,
            BigDecimal adjustmentDebit,
            BigDecimal adjustmentCredit,
            BigDecimal adjustedBalance,
            String adjustmentReason,
            String affectedOpeningBalanceType
    ) {}

    // --- ORG03: Multi-Date Semantics & Period Reopen ---

    public record MultiDateContext(
            LocalDate businessDate,
            LocalDate invoiceDate,
            LocalDate accountingDate,
            LocalDate taxObligationDate,
            String periodStatus,
            String justification
    ) {}

    public record MultiDateValidationResult(
            boolean isValid,
            List<String> validationErrors,
            boolean accountingPeriodOpen,
            boolean requiresExplanation,
            int dateSpanDays
    ) {}

    public enum ReopenStatus {
        REQUESTED,
        APPROVED,
        REJECTED,
        CLOSED_AGAIN
    }

    public record PeriodReopenRecord(
            UUID id,
            UUID tenantId,
            UUID bookId,
            UUID yearId,
            UUID periodId,
            int periodMonth,
            String reopenReason,
            ReopenStatus status,
            Actor requestedBy,
            Actor approvedBy,
            String decisionReason,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    public record PeriodAuditTrailEntry(
            UUID id,
            UUID tenantId,
            UUID bookId,
            UUID yearId,
            UUID periodId,
            String action,
            Actor actor,
            String detailsJson,
            OffsetDateTime recordedAt
    ) {}

    // --- ORG04: Currencies, FX Snapshot, Revaluation & Tax Worksheet ---

    public record FxCurrency(
            String currencyCode,
            String currencyName,
            String symbol,
            int standardScale,
            boolean isBase,
            boolean isActive
    ) {}

    public enum FxRateType {
        CENTRAL_PARITY,
        SPOT_BUY,
        SPOT_SELL,
        CUSTOM_APPROVED
    }

    public record FxRateSnapshot(
            UUID id,
            UUID tenantId,
            String sourceCurrency,
            String targetCurrency,
            LocalDate rateDate,
            FxRateType rateType,
            BigDecimal rateValue,
            String quoteSource,
            Actor maker,
            Actor reviewer,
            boolean isActive,
            OffsetDateTime createdAt
    ) {}

    public record FxLineTranslationResult(
            String originalCurrency,
            BigDecimal originalAmount,
            BigDecimal rateValue,
            BigDecimal functionalCnyExact,
            BigDecimal functionalCnyQuantized,
            BigDecimal roundingDifference
    ) {}

    public record FxRevaluationPlan(
            UUID id,
            UUID tenantId,
            UUID bookId,
            UUID yearId,
            UUID periodId,
            LocalDate revaluationDate,
            UUID closingRateSnapshotId,
            String status,
            BigDecimal totalGainLoss,
            Actor maker,
            Actor reviewer,
            UUID journalEntryId,
            List<FxRevaluationItem> items,
            long version,
            OffsetDateTime createdAt
    ) {}

    public record FxRevaluationItem(
            UUID id,
            UUID planId,
            String accountCode,
            String accountName,
            String currencyCode,
            BigDecimal foreignBalance,
            BigDecimal carryingCnyBalance,
            BigDecimal closingRate,
            BigDecimal restatedCnyBalance,
            BigDecimal gainLossAmount,
            String exchangeGainLossAccount
    ) {}

    public record FxTaxWorksheetItem(
            UUID id,
            UUID tenantId,
            UUID bookId,
            UUID periodId,
            String transactionRef,
            String originalCurrency,
            BigDecimal originalAmount,
            BigDecimal accountingFxRate,
            BigDecimal accountingCnyAmount,
            String taxFxRateType,
            BigDecimal taxFxRate,
            BigDecimal taxCnyAmount,
            BigDecimal taxAccountingDiff,
            String explanation,
            OffsetDateTime createdAt
    ) {}

    // --- ORG05: Regional Tax Calendar & Obligation Catalog ---

    public record TaxCalendarTemplate(
            UUID id,
            String regionCode,
            int taxYear,
            int periodMonth,
            String taxCategory,
            LocalDate statutoryDeadline,
            LocalDate extendedDeadline,
            String extensionReason,
            String officialAnnouncementRef
    ) {}

    public record TaxCatalogRule(
            String obligationCode,
            String taxCategory,
            String taxTypeName,
            String filingFrequency,
            String defaultRole,
            String statutoryFilingUnit,
            String applicableTaxpayerType,
            String applicableCitMode,
            boolean isActive
    ) {}

    public record ResolvedTaxObligation(
            String obligationCode,
            String taxCategory,
            String taxTypeName,
            LocalDate deadline,
            String filingRole,
            boolean isExtended,
            boolean smallMicroCitApplicable
    ) {}
}

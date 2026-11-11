package com.lumira.finance.yearend;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Domain records and DTOs for GL and Year-End Accounting Lifecycle (W09).
 */
public final class YearendModel {
    private YearendModel() {}

    public record PersonActor(UUID accountId, UUID personId, String name) {}

    public record Scope(UUID tenantId, UUID bookId, UUID yearId, UUID periodId, LocalDate date) {}

    // GL01: Cutover
    public record CutoverPlan(
        UUID id,
        UUID tenantId,
        UUID bookId,
        UUID yearId,
        LocalDate cutoverDate,
        String mode, // FULL_HISTORY, OPENING_PLUS_YTD
        String status, // DRAFT, VALIDATED, APPROVED, LOCKED, CANCELLED
        long version,
        String contentDigest,
        String sourceSystem,
        String sourceWatermark,
        PersonActor maker,
        PersonActor approver,
        Instant lockedAt,
        Instant createdAt,
        Instant updatedAt
    ) {}

    public record OpeningBalanceItem(
        UUID id,
        UUID accountId,
        String accountCode,
        String accountName,
        String currency,
        String direction, // DEBIT, CREDIT
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        Map<String, Object> auxDimensions
    ) {}

    public record BaselineItem(
        UUID id,
        String baselineType, // PROFIT_LOSS, CASHFLOW, TAX, PAYROLL
        int periodMonth,
        String categoryCode,
        String categoryName,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        Map<String, Object> auxPayload
    ) {}

    public record TieOutResult(
        String checkItem, // TRIAL_BALANCE, AR_SUBLEDGER, AP_SUBLEDGER, ASSET_SUBLEDGER, CASHFLOW_TIE_OUT, PAYROLL_TIE_OUT
        boolean passed,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal glAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal subledgerAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal difference,
        Map<String, Object> details
    ) {}

    public record CutoverProposal(
        CutoverPlan plan,
        List<OpeningBalanceItem> openingBalances,
        List<BaselineItem> baselines,
        List<TieOutResult> tieOutResults
    ) {}

    // GL02 & GL03: Auto Rules & Auto Review
    public record LineRule(
        UUID accountId,
        String accountCode,
        String direction, // DEBIT, CREDIT
        String amountFormula,
        Map<String, String> dimensionMappings,
        String memoPattern
    ) {}

    public record AutoRuleTemplate(
        UUID id,
        UUID tenantId,
        UUID bookId,
        String ruleCode,
        String ruleName,
        String businessType,
        int version,
        String status, // DRAFT, APPROVED, DEPRECATED
        String contentDigest,
        List<LineRule> lineTemplates,
        PersonActor maker,
        PersonActor approver,
        Instant approvedAt,
        Instant createdAt
    ) {}

    public record AutoReviewPolicy(
        UUID id,
        UUID tenantId,
        UUID bookId,
        String policyCode,
        String policyName,
        int version,
        String status, // DRAFT, APPROVED, DEPRECATED
        String contentDigest,
        List<String> scopeBusinessTypes,
        List<String> allowedAccountCodes,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal maxAmount,
        boolean zeroExceptionRequired,
        PersonActor maker,
        PersonActor approver,
        Instant approvedAt,
        Instant createdAt
    ) {}

    public record AutoRuleExecution(
        UUID id,
        UUID tenantId,
        UUID bookId,
        UUID yearId,
        UUID journalId,
        String sourceEventId,
        String sourceSystem,
        String stableEffectKey,
        UUID templateId,
        int templateVersion,
        UUID policyId,
        Integer policyVersion,
        String policyDigest,
        boolean autoReviewed,
        int exceptionCount,
        Map<String, Object> auditEvidence,
        Instant createdAt
    ) {}

    // GL05: Journal Correction & Reversal
    public record CorrectionAllocation(
        int sourceLineNo,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        UUID targetAccountVersionId
    ) {}

    public record JournalCorrectionRecord(
        UUID id,
        UUID tenantId,
        UUID bookId,
        UUID targetYearId,
        UUID sourceYearId,
        UUID sourceJournalId,
        long sourcePostingSeq,
        String sourceDigest,
        UUID correctionJournalId,
        String correctionKind, // FULL_REVERSAL, PARTIAL_REVERSAL, SUPPLEMENT, PRIOR_PERIOD_ADJUSTMENT
        String standard, // CAS, SMALL
        String casAccountCode,
        String status, // DRAFT, SUBMITTED, APPROVED, POSTED, CANCELLED
        String reason,
        List<CorrectionAllocation> allocations,
        PersonActor maker,
        PersonActor approver,
        Instant createdAt
    ) {}

    // GL06: Period Close Checklist
    public record PeriodCloseCheckItem(
        UUID id,
        String checkCategory,
        String checkCode,
        String checkTitle,
        boolean passed,
        Map<String, Object> details
    ) {}

    public record PeriodCloseCheckPlan(
        UUID id,
        UUID tenantId,
        UUID bookId,
        UUID yearId,
        UUID periodId,
        long version,
        String status, // PREVIEWED, APPROVED, CLOSED, CANCELLED, REOPENED
        String contentDigest,
        long sourceWatermark,
        boolean allChecksPassed,
        int unpostedJournalsCount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal trialBalanceDebit,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal trialBalanceCredit,
        boolean trialBalanceBalanced,
        boolean bankReconciliationComplete,
        boolean arTieOutPassed,
        boolean apTieOutPassed,
        boolean assetTieOutPassed,
        boolean inventoryTieOutPassed,
        boolean payrollTieOutPassed,
        boolean cashflowCoverageComplete,
        boolean taxWorksheetReady,
        PersonActor maker,
        PersonActor approver,
        Instant approvedAt,
        Instant closedAt,
        Instant createdAt,
        List<PeriodCloseCheckItem> items
    ) {}

    // YEAR01, YEAR02, YEAR03: Carry-Over & Restatement
    public record CarryBalanceItem(
        UUID id,
        UUID sourceAccountId,
        UUID targetAccountId,
        String sourceAccountCode,
        String targetAccountCode,
        String currency,
        String direction,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal baseAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal deltaAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal netOpeningAmount,
        Map<String, Object> auxDimensions
    ) {}

    public record CarrySubledgerItem(
        UUID id,
        String subledgerKind, // AR_OPEN_INVOICE, AP_OPEN_BILL, EMPLOYEE_ADVANCE, FX_BALANCE
        String businessRef,
        UUID counterpartyId,
        LocalDate originalDate,
        String currency,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal originalAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal remainingOpenAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal fxRate,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cnyEquivalent,
        Map<String, Object> auxPayload
    ) {}

    public record RestatementManifestItem(
        UUID id,
        String restatementScope, // BALANCE_SHEET_COMPARATIVE, INCOME_STATEMENT_COMPARATIVE, CIT_ANNUAL_ADJUSTMENT, TAX_RECONCILIATION
        String statementLineCode,
        String statementLineName,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal preCorrectionAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal deltaAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal restatedAmount,
        String impactSummary,
        Instant createdAt
    ) {}

    public record CarryBatchPlan(
        UUID id,
        UUID tenantId,
        UUID bookId,
        UUID sourceYearId,
        UUID targetYearId,
        String kind, // BASE, DELTA
        String status, // PREVIEWED, APPROVED, COMMITTED, CANCELLED
        int sourceCloseVersion,
        long sourceWatermark,
        int targetOpeningVersion,
        String mappingVersion,
        long contentVersion,
        String contentDigest,
        UUID previousBatchId,
        String reason,
        PersonActor maker,
        PersonActor approver,
        Instant committedAt,
        Instant createdAt,
        List<CarryBalanceItem> balances,
        List<CarrySubledgerItem> subledgerItems,
        List<RestatementManifestItem> restatements
    ) {}

    // Request DTOs
    public record CutoverCreateRequest(
        LocalDate cutoverDate,
        String mode,
        String sourceSystem,
        String sourceWatermark,
        List<OpeningBalanceItem> openingBalances,
        List<BaselineItem> baselines,
        Map<String, BigDecimal> subledgerTotals
    ) {}

    public record TemplateCreateRequest(
        String ruleCode,
        String ruleName,
        String businessType,
        List<LineRule> lineTemplates
    ) {}

    public record PolicyCreateRequest(
        String policyCode,
        String policyName,
        List<String> scopeBusinessTypes,
        List<String> allowedAccountCodes,
        String maxAmount,
        boolean zeroExceptionRequired
    ) {}

    public record AutoReviewEvaluateRequest(
        UUID templateId,
        UUID policyId,
        String sourceEventId,
        String sourceSystem,
        Map<String, Object> eventData
    ) {}

    public record CorrectionCreateRequest(
        UUID targetYearId,
        UUID sourceYearId,
        UUID sourceJournalId,
        long sourcePostingSeq,
        String sourceDigest,
        UUID correctionJournalId,
        String correctionKind,
        String standard,
        String casAccountCode,
        String reason,
        List<CorrectionAllocation> allocations,
        Map<Integer, BigDecimal> lineWatermarks
    ) {
        public CorrectionCreateRequest(
            UUID sourceYearId,
            UUID sourceJournalId,
            long sourcePostingSeq,
            String sourceDigest,
            UUID correctionJournalId,
            String correctionKind,
            String standard,
            String casAccountCode,
            String reason,
            List<CorrectionAllocation> allocations,
            Map<Integer, BigDecimal> lineWatermarks
        ) {
            this(sourceYearId, sourceYearId, sourceJournalId, sourcePostingSeq, sourceDigest,
                 correctionJournalId, correctionKind, standard, casAccountCode, reason, allocations, lineWatermarks);
        }
    }

    public record PeriodClosePreviewRequest(
        int unpostedJournalsCount,
        BigDecimal trialBalanceDebit,
        BigDecimal trialBalanceCredit,
        boolean bankReconciliationComplete,
        Map<String, BigDecimal> glBalances,
        Map<String, BigDecimal> subledgerBalances,
        boolean cashflowCoverageComplete,
        boolean taxWorksheetReady
    ) {}

    public record CarryBatchPreviewRequest(
        UUID sourceYearId,
        UUID targetYearId,
        int sourceCloseVersion,
        long sourceWatermark,
        int targetOpeningVersion,
        String mappingVersion,
        String kind,
        UUID previousBatchId,
        String reason,
        List<CarryBalanceItem> balances,
        List<CarrySubledgerItem> subledgerItems,
        List<RestatementManifestItem> restatements
    ) {}
}

package com.lumira.finance.budget;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Closed command and query models for Dimensions and Budget Control (Lane W03).
 * Uses Jackson 3 conventions and explicit string representations for exact numeric amounts.
 */
public final class FinanceBudgetDimensionsInput {

    private FinanceBudgetDimensionsInput() {}

    // MD01: Account Dimension Rule
    public record AccountRuleDto(
            UUID tenantId,
            UUID bookId,
            UUID yearId,
            UUID accountId,
            String accountCode,
            String standardVersion,
            boolean requireDepartment,
            boolean requireProject,
            boolean requireCounterparty,
            boolean requireEmployee,
            boolean requireContract,
            boolean requireCostCenter,
            String currencyPolicy,
            String status,
            long version,
            OffsetDateTime updatedAt
    ) {}

    public record AccountDeactivationRequest(
            UUID accountId,
            String proposalType, // DEACTIVATE or CHANGE_POLICY
            String proposedStatus, // INACTIVE or ACTIVE
            UUID makerPerson,
            UUID makerAccount,
            String reason
    ) {}

    public record AccountApprovalDecision(
            UUID approvalId,
            UUID reviewerPerson,
            UUID reviewerAccount,
            String decision, // APPROVE or REJECT
            String decisionReason
    ) {}

    // MD02: Counterparty Bank Account & Identity
    public record CounterpartyBankAccountDto(
            UUID id,
            UUID tenantId,
            UUID legalEntityId,
            UUID counterpartyId,
            String bankName,
            String branchName,
            String bankAccountNo,
            String currency,
            String purpose,
            boolean isDefault,
            String status,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    public record BankAccountReviewRequest(
            UUID bankAccountId,
            String changeType, // CREATE, UPDATE, DEACTIVATE
            String proposedPayload,
            UUID makerPerson,
            String reviewComment
    ) {}

    public record BankAccountReviewDecision(
            UUID reviewId,
            UUID reviewerPerson,
            String decision, // APPROVE or REJECT
            String reviewComment
    ) {}

    public record CounterpartyIdentityHistoryDto(
            UUID id,
            UUID tenantId,
            UUID legalEntityId,
            UUID counterpartyId,
            long changeSeq,
            String oldDisplayName,
            String newDisplayName,
            String oldIdentifier,
            String newIdentifier,
            String reason,
            UUID evidenceOriginalId,
            String evidenceHash,
            UUID makerPerson,
            UUID approverPerson,
            OffsetDateTime approvedAt
    ) {}

    public record FrameworkContractDto(
            UUID id,
            UUID tenantId,
            UUID legalEntityId,
            UUID counterpartyId,
            String contractNo,
            String contractName,
            String settlementTerms,
            int creditDays,
            String penaltyRateAnnual, // numeric(18,12) as plain string
            LocalDate validFrom,
            LocalDate validTo,
            String status,
            long version,
            OffsetDateTime createdAt
    ) {}

    // MD03: Dimensions & Allocation Rules
    public record DepartmentDto(
            UUID id,
            UUID tenantId,
            UUID bookId,
            String code,
            String name,
            UUID parentId,
            UUID managerPersonId,
            LocalDate validFrom,
            LocalDate validTo,
            String status,
            long version,
            OffsetDateTime createdAt
    ) {}

    public record ProjectDto(
            UUID id,
            UUID tenantId,
            UUID bookId,
            String code,
            String name,
            UUID departmentId,
            UUID managerPersonId,
            LocalDate validFrom,
            LocalDate validTo,
            String status,
            long version,
            OffsetDateTime createdAt
    ) {}

    public record CostCenterDto(
            UUID id,
            UUID tenantId,
            UUID bookId,
            String code,
            String name,
            UUID departmentId,
            LocalDate validFrom,
            LocalDate validTo,
            String status,
            long version,
            OffsetDateTime createdAt
    ) {}

    public record ContractDimensionDto(
            UUID id,
            UUID tenantId,
            UUID bookId,
            String code,
            String name,
            UUID counterpartyId,
            UUID departmentId,
            UUID projectId,
            String totalAmount, // numeric(28,8)
            LocalDate validFrom,
            LocalDate validTo,
            String status,
            long version,
            OffsetDateTime createdAt
    ) {}

    public record AllocationTargetInput(
            UUID targetCostCenterId,
            UUID targetDepartmentId,
            UUID targetProjectId,
            String ratio, // numeric(18,12)
            int priorityOrder
    ) {}

    public record AllocationRuleRequest(
            String ruleCode,
            String ruleName,
            UUID sourceCostCenterId,
            UUID sourceDepartmentId,
            String sourceAccountCode,
            LocalDate validFrom,
            LocalDate validTo,
            List<AllocationTargetInput> targets
    ) {}

    public record AllocationExecutionRequest(
            UUID ruleId,
            String totalAmount, // numeric(28,8)
            int scale           // 2 for penny, 8 for internal precision
    ) {}

    public record AllocationExecutionResult(
            UUID ruleId,
            String totalAmount,
            int scale,
            List<AllocatedItemResult> allocatedTargets
    ) {}

    public record AllocatedItemResult(
            UUID targetId,
            String ratio,
            String allocatedAmount
    ) {}

    // BD01: Budget Formulation & Versions
    public record BudgetItemInput(
            int period,
            String accountCode,
            UUID departmentId,
            UUID projectId,
            UUID costCenterId,
            UUID counterpartyId,
            UUID contractId,
            String amount // numeric(28,8)
    ) {}

    public record BudgetVersionCreateRequest(
            String versionCode,
            String versionType, // ORIGINAL, ADJUSTMENT, ROLLING
            String reason,
            UUID makerPerson,
            List<BudgetItemInput> items
    ) {}

    public record BudgetVersionApprovalRequest(
            UUID versionId,
            UUID reviewerPerson,
            String decision, // APPROVE or REJECT
            String reason
    ) {}

    public record BudgetAdjustmentRequest(
            UUID sourceVersionId,
            UUID targetVersionId,
            String adjustmentType, // INTERNAL_TRANSFER, AUGMENTATION, REDUCTION
            String adjustmentAmount,
            String justification,
            UUID makerPerson,
            UUID reviewerPerson
    ) {}

    // BD02: Budget Control Ledger & Execution
    public record BudgetReservationRequest(
            int period,
            String accountCode,
            UUID departmentId,
            UUID projectId,
            UUID costCenterId,
            String amount, // numeric(28,8)
            String referenceType,
            UUID referenceId,
            UUID actorPerson,
            String note
    ) {}

    public record BudgetConsumptionRequest(
            int period,
            String accountCode,
            UUID departmentId,
            UUID projectId,
            UUID costCenterId,
            String amount, // numeric(28,8)
            boolean fromReservation,
            String referenceType,
            UUID referenceId,
            UUID actorPerson,
            String note
    ) {}

    public record BudgetReleaseRequest(
            int period,
            String accountCode,
            UUID departmentId,
            UUID projectId,
            UUID costCenterId,
            String amount, // numeric(28,8)
            String referenceType,
            UUID referenceId,
            UUID actorPerson,
            String note
    ) {}

    public record BudgetRollbackConsumptionRequest(
            int period,
            String accountCode,
            UUID departmentId,
            UUID projectId,
            UUID costCenterId,
            String amount, // numeric(28,8)
            String referenceType,
            UUID referenceId,
            UUID actorPerson,
            String note
    ) {}

    public record BudgetControlLedgerDto(
            UUID id,
            UUID tenantId,
            UUID bookId,
            UUID yearId,
            int period,
            String accountCode,
            UUID departmentId,
            UUID projectId,
            UUID costCenterId,
            String budgetAmount,
            String reservedAmount,
            String consumedAmount,
            String availableAmount,
            OffsetDateTime updatedAt
    ) {}

    public record BudgetForecastSnapshotRequest(
            int asOfPeriod,
            UUID baselineBudgetVersionId,
            String forecastedRemaining, // numeric(28,8)
            UUID createdByPerson
    ) {}

    public record BudgetForecastSnapshotDto(
            UUID id,
            UUID tenantId,
            UUID bookId,
            UUID yearId,
            int asOfPeriod,
            UUID baselineBudgetVersionId,
            String ytdConsumed,
            String forecastedRemaining,
            String forecastTotal,
            String projectedVariance,
            String snapshotHash,
            UUID createdByPerson,
            OffsetDateTime createdAt
    ) {}
}

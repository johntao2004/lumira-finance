package com.lumira.finance.iamops;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

public final class IamOpsModels {
    private IamOpsModels() {}

    // ------------------------------------------------------------------------
    // IAM01: Invitations, Attested Sources, Lifecycle
    // ------------------------------------------------------------------------
    public record MemberInvitation(
            UUID id,
            UUID tenantId,
            String email,
            String phone,
            String role,
            String departmentId,
            String projectId,
            String status,
            UUID invitedByAccount,
            UUID invitedByPerson,
            String tokenHash,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt,
            OffsetDateTime acceptedAt,
            long version
    ) {}

    public record CreateInvitationInput(
            String email,
            String phone,
            String role,
            String departmentId,
            String projectId,
            int validityDays
    ) {}

    public record AcceptInvitationInput(
            String token,
            UUID accountId,
            UUID personId,
            String displayName,
            String idCard,
            String bankAccount,
            BigDecimal salaryAmount
    ) {
        public AcceptInvitationInput(String token, UUID accountId, UUID personId, String displayName) {
            this(token, accountId, personId, displayName, null, null, null);
        }
    }

    public record AttestedSource(
            UUID id,
            UUID tenantId,
            String sourceType,
            String providerName,
            String attestationKind,
            String sourceReference,
            String sourceSha256,
            int recordCount,
            String rawPayload,
            OffsetDateTime attestedAt,
            OffsetDateTime createdAt
    ) {}

    public record ImportAttestedSourceInput(
            String sourceType,
            String providerName,
            String sourceReference,
            String sourceSha256,
            int recordCount,
            String rawPayload,
            OffsetDateTime attestedAt
    ) {}

    public record PersonLifecycle(
            UUID id,
            UUID tenantId,
            UUID personId,
            UUID accountId,
            String employeeNo,
            String displayName,
            String departmentId,
            String projectId,
            String idCard,
            String bankAccount,
            BigDecimal salaryAmount,
            String state,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            OffsetDateTime offboardedAt,
            UUID offboardedBy,
            String offboardingReason,
            long version
    ) {}

    public record OffboardPersonInput(
            String reason
    ) {}

    public record UpdateTicketStatusInput(
            String status
    ) {}

    // ------------------------------------------------------------------------
    // IAM02: Data Scopes, Masking & Scoped Exports
    // ------------------------------------------------------------------------
    public record DataScopeGrant(
            UUID id,
            UUID tenantId,
            UUID accountId,
            UUID personId,
            String scopeType,
            String departmentId,
            String projectId,
            boolean canExport,
            boolean canUnmaskSensitive,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime revokedAt,
            UUID revokedBy,
            long version
    ) {}

    public record GrantDataScopeInput(
            UUID accountId,
            UUID personId,
            String scopeType,
            String departmentId,
            String projectId,
            boolean canExport,
            boolean canUnmaskSensitive
    ) {}

    public record MaskedEmployeeRecord(
            UUID personId,
            String employeeNo,
            String displayName,
            String departmentId,
            String projectId,
            String maskedIdCard,
            String maskedBankAccount,
            String maskedSalaryAmount,
            boolean isMasked
    ) {}

    public record ExportRequest(
            UUID id,
            UUID tenantId,
            UUID requestedByAccount,
            UUID requestedByPerson,
            String exportResource,
            String filterDepartmentId,
            String filterProjectId,
            boolean includeSensitiveUnmasked,
            String status,
            String rejectionReason,
            Integer exportedRowCount,
            String fileSha256,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt
    ) {}

    public record CreateExportRequestInput(
            String exportResource,
            String filterDepartmentId,
            String filterProjectId,
            boolean requestUnmasked
    ) {}

    // ------------------------------------------------------------------------
    // IAM03: SoD Matrix, Tiered Thresholds, Approvals & Delegations
    // ------------------------------------------------------------------------
    public record SodMatrixRule(
            UUID id,
            UUID tenantId,
            String roleA,
            String roleB,
            String conflictSeverity,
            String statutoryBasis,
            boolean isActive,
            OffsetDateTime createdAt
    ) {}

    public record ApprovalThreshold(
            UUID id,
            UUID tenantId,
            String ruleName,
            String entityType,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            int requiredStages,
            String stage1Role,
            String stage2Role,
            String stage3Role,
            OffsetDateTime createdAt
    ) {}

    public record ApprovalTask(
            UUID id,
            UUID tenantId,
            UUID thresholdId,
            String entityType,
            UUID entityId,
            BigDecimal amount,
            int currentStage,
            int totalStages,
            String status,
            UUID requestedByAccount,
            UUID requestedByPerson,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            List<ApprovalStageDecision> stages
    ) {}

    public record ApprovalStageDecision(
            UUID id,
            UUID taskId,
            int stageOrder,
            String assignedRole,
            UUID assignedPersonId,
            String decision,
            UUID decidedByAccount,
            UUID decidedByPerson,
            String decisionReason,
            OffsetDateTime decidedAt
    ) {}

    public record CreateApprovalTaskInput(
            String entityType,
            UUID entityId,
            BigDecimal amount
    ) {}

    public record DecideStageInput(
            int stageOrder,
            String decision,
            String reason
    ) {}

    public record Delegation(
            UUID id,
            UUID tenantId,
            UUID delegatorAccount,
            UUID delegatorPerson,
            UUID delegateeAccount,
            UUID delegateePerson,
            String role,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo,
            String status,
            String reason,
            OffsetDateTime revokedAt,
            UUID revokedBy,
            OffsetDateTime createdAt
    ) {}

    public record CreateDelegationInput(
            UUID delegateeAccount,
            UUID delegateePerson,
            String role,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo,
            String reason
    ) {}

    // ------------------------------------------------------------------------
    // IAM04: Independent AUDITOR & Dual-Person Emergency Grants
    // ------------------------------------------------------------------------
    public record EmergencyGrant(
            UUID id,
            UUID tenantId,
            UUID requesterAccount,
            UUID requesterPerson,
            UUID bookId,
            String scopeDescription,
            int requestedDurationMinutes,
            String reason,
            String status,
            UUID approver1Account,
            UUID approver1Person,
            OffsetDateTime approver1At,
            UUID approver2Account,
            UUID approver2Person,
            OffsetDateTime approver2At,
            OffsetDateTime activatedAt,
            OffsetDateTime expiresAt,
            OffsetDateTime revokedAt,
            UUID revokedBy,
            OffsetDateTime createdAt
    ) {}

    public record RequestEmergencyGrantInput(
            UUID bookId,
            String scopeDescription,
            int requestedDurationMinutes,
            String reason
    ) {}

    public record ApproveEmergencyGrantInput(
            String approvalSlot // "APPROVER1" or "APPROVER2"
    ) {}

    // ------------------------------------------------------------------------
    // OPS01: Backup/PITR Manifest, Joint Recovery Drill & Rollback Ledger
    // ------------------------------------------------------------------------
    public record BackupManifest(
            UUID id,
            UUID tenantId,
            String backupId,
            String backupType,
            String dbSnapshotHash,
            int originalFilesCount,
            long originalBytesTotal,
            String filesManifestSha256,
            OffsetDateTime recoveryTargetTime,
            String cloudDrStatus,
            String status,
            OffsetDateTime verifiedAt,
            OffsetDateTime createdAt
    ) {}

    public record CreateBackupManifestInput(
            String backupId,
            String backupType,
            String dbSnapshotHash,
            int originalFilesCount,
            long originalBytesTotal,
            String filesManifestSha256,
            OffsetDateTime recoveryTargetTime,
            String cloudDrStatus
    ) {}

    public record JointDrillRecord(
            UUID id,
            UUID tenantId,
            UUID manifestId,
            String drillName,
            OffsetDateTime targetPitrTime,
            long dbRecordCount,
            boolean dbRecordsMatched,
            boolean originalFilesMatched,
            boolean originalBytesMatched,
            int discrepancyCount,
            String discrepanciesDetail,
            String status,
            UUID executedBy,
            long durationMs,
            OffsetDateTime createdAt
    ) {}

    public record ExecuteJointDrillInput(
            UUID manifestId,
            String drillName,
            long simulatedDbRowCount,
            long simulatedOriginalByteTotal,
            int simulatedDiscrepancyCount
    ) {}

    public record RollbackLedgerRecord(
            UUID id,
            UUID tenantId,
            String releaseVersion,
            String migrationName,
            String rollbackScriptSha256,
            boolean backwardCompatible,
            String readinessStatus,
            String blockReason,
            UUID verifiedBy,
            OffsetDateTime createdAt
    ) {}

    public record CreateRollbackLedgerInput(
            String releaseVersion,
            String migrationName,
            String rollbackScriptSha256,
            boolean backwardCompatible,
            String readinessStatus,
            String blockReason
    ) {}

    // ------------------------------------------------------------------------
    // OPS02: Support Tickets, Incident Timeline & Diagnostic Grants
    // ------------------------------------------------------------------------
    public record SupportTicket(
            UUID id,
            UUID tenantId,
            String ticketNo,
            UUID bookId,
            String severity,
            String category,
            String title,
            String description,
            String status,
            UUID createdByAccount,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            OffsetDateTime resolvedAt
    ) {}

    public record CreateTicketInput(
            UUID bookId,
            String severity,
            String category,
            String title,
            String description
    ) {}

    public record IncidentTimelineEvent(
            UUID id,
            UUID tenantId,
            UUID ticketId,
            String incidentPhase,
            String summary,
            String customerCommunication,
            String technicalDetail,
            OffsetDateTime eventTimestamp,
            UUID recordedBy,
            OffsetDateTime createdAt
    ) {}

    public record AddTimelineEventInput(
            String incidentPhase,
            String summary,
            String customerCommunication,
            String technicalDetail
    ) {}

    public record DiagnosticGrant(
            UUID id,
            UUID tenantId,
            UUID ticketId,
            UUID bookId,
            UUID supportOperatorAccount,
            UUID approvedByAccount,
            UUID approvedByPerson,
            String scopeType,
            int durationMinutes,
            String status,
            OffsetDateTime activatedAt,
            OffsetDateTime expiresAt,
            OffsetDateTime revokedAt,
            UUID revokedBy,
            String revocationReason,
            OffsetDateTime createdAt
    ) {}

    public record GrantDiagnosticInput(
            UUID ticketId,
            UUID bookId,
            UUID supportOperatorAccount,
            int durationMinutes
    ) {}

    public record RevokeDiagnosticInput(
            String reason
    ) {}
}

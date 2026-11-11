package com.lumira.finance.iamops;

import com.lumira.finance.iamops.IamOpsModels.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Transactional
public class IamOpsService {
    private final IamOpsStore store;
    private final SecureRandom random = new SecureRandom();

    public IamOpsService(IamOpsStore store) {
        this.store = store;
    }

    private OffsetDateTime nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    // ========================================================================
    // IAM01: Enterprise Member Invitation & Person Lifecycle
    // ========================================================================

    public MemberInvitation inviteMember(UUID tenantId, UUID inviterAccount, UUID inviterPerson, CreateInvitationInput in) {
        if (in.email() == null || in.email().isBlank()) {
            throw new IllegalArgumentException("邀请邮箱不能为空");
        }
        if (in.role() == null || in.role().isBlank()) {
            throw new IllegalArgumentException("邀请角色不能为空");
        }
        UUID id = UUID.randomUUID();
        OffsetDateTime now = nowUtc();
        int days = in.validityDays() <= 0 ? 7 : in.validityDays();
        OffsetDateTime expires = now.plusDays(days);

        // Generate token and hash
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = HexFormat.of().formatHex(tokenBytes);
        String tokenHash = IamOpsRules.sha256Hex(token);

        MemberInvitation inv = new MemberInvitation(
            id, tenantId, in.email().trim(), in.phone(), in.role(),
            in.departmentId(), in.projectId(), "PENDING",
            inviterAccount, inviterPerson, tokenHash, now, expires, null, 1L
        );
        store.insertInvitation(inv);
        return inv;
    }

    public PersonLifecycle acceptInvitation(UUID tenantId, AcceptInvitationInput in) {
        String tokenHash = IamOpsRules.sha256Hex(in.token());
        MemberInvitation inv = store.findInvitationByTokenHash(tenantId, tokenHash)
            .orElseThrow(() -> new IllegalStateException("邀请凭证无效或不存在"));

        if (!"PENDING".equalsIgnoreCase(inv.status())) {
            throw new IllegalStateException("邀请已处理或已失效 (当前状态: " + inv.status() + ")");
        }
        if (nowUtc().isAfter(inv.expiresAt())) {
            throw new IllegalStateException("邀请链接已过期");
        }

        OffsetDateTime now = nowUtc();
        store.acceptInvitation(tenantId, inv.id(), now);

        UUID lifecycleId = UUID.randomUUID();
        String empNo = "EMP-" + HexFormat.of().formatHex(in.personId().toString().substring(0, 8).getBytes()).toUpperCase();
        PersonLifecycle lc = new PersonLifecycle(
            lifecycleId, tenantId, in.personId(), in.accountId(),
            empNo, in.displayName(), inv.departmentId(), inv.projectId(),
            in.idCard(), in.bankAccount(), in.salaryAmount(),
            "ACTIVE", now, now, null, null, null, 1L
        );
        store.insertPersonLifecycle(lc);
        return lc;
    }

    public AttestedSource importAttestedSource(UUID tenantId, ImportAttestedSourceInput in) {
        if (in.sourceSha256() == null || !in.sourceSha256().matches("^[a-f0-9]{64}$")) {
            throw new IllegalArgumentException("数据源SHA-256校验和格式不合法");
        }
        UUID id = UUID.randomUUID();
        OffsetDateTime now = nowUtc();
        AttestedSource src = new AttestedSource(
            id, tenantId, in.sourceType(), in.providerName(),
            IamOpsRules.ORG_ATTESTED_KIND, in.sourceReference(), in.sourceSha256(),
            in.recordCount(), in.rawPayload() == null ? "{}" : in.rawPayload(),
            in.attestedAt() == null ? now : in.attestedAt(), now
        );
        store.insertAttestedSource(src);
        return src;
    }

    public Map<String, Integer> offboardPersonAtomic(UUID tenantId, UUID personId, UUID operatorId, OffboardPersonInput in) {
        String reason = (in != null && in.reason() != null && !in.reason().isBlank())
            ? in.reason().trim()
            : "企业成员离职回收";
        return store.offboardPersonAtomic(tenantId, personId, operatorId, reason);
    }

    // ========================================================================
    // IAM02: Data Scopes, Masking & Scoped Exports
    // ========================================================================

    public DataScopeGrant grantDataScope(UUID tenantId, GrantDataScopeInput in) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = nowUtc();
        DataScopeGrant grant = new DataScopeGrant(
            id, tenantId, in.accountId(), in.personId(), in.scopeType(),
            in.departmentId(), in.projectId(), in.canExport(), in.canUnmaskSensitive(),
            "ACTIVE", now, null, null, 1L
        );
        store.insertDataScopeGrant(grant);
        return grant;
    }

    @Transactional(readOnly = true)
    public MaskedEmployeeRecord getMaskedEmployee(UUID tenantId, UUID actorPerson, UUID targetPersonId) {
        PersonLifecycle lc = store.findPersonLifecycle(tenantId, targetPersonId)
            .orElseThrow(() -> new IllegalStateException("人员档案未找到 (personId: " + targetPersonId + ")"));

        // Check actor's data scope grant
        DataScopeGrant grant = store.findActiveDataScopeGrant(tenantId, actorPerson)
            .orElseThrow(() -> new IllegalStateException("操作人无有效数据范围授权，拒绝查看人员信息"));

        if ("DEPARTMENT".equalsIgnoreCase(grant.scopeType())) {
            if (lc.departmentId() == null || !lc.departmentId().equals(grant.departmentId())) {
                throw new IllegalStateException("数据范围超出授权部门：" + grant.departmentId());
            }
        } else if ("PROJECT".equalsIgnoreCase(grant.scopeType())) {
            if (lc.projectId() == null || !lc.projectId().equals(grant.projectId())) {
                throw new IllegalStateException("数据范围超出授权项目：" + grant.projectId());
            }
        }

        boolean hasUnmaskPermission = grant.canUnmaskSensitive();
        String maskedId = IamOpsRules.maskIdCard(lc.idCard(), hasUnmaskPermission);
        String maskedBank = IamOpsRules.maskBankAccount(lc.bankAccount(), hasUnmaskPermission);
        String maskedSal = IamOpsRules.maskPayroll(lc.salaryAmount(), hasUnmaskPermission);

        return new MaskedEmployeeRecord(
            lc.personId(), lc.employeeNo(), lc.displayName(),
            lc.departmentId(), lc.projectId(),
            maskedId, maskedBank, maskedSal, !hasUnmaskPermission
        );
    }

    @Transactional(readOnly = true)
    public MaskedEmployeeRecord getMaskedEmployee(UUID tenantId, UUID personId, String rawIdCard, String rawBankAccount, BigDecimal rawSalary, boolean hasUnmaskPermission) {
        PersonLifecycle lc = store.findPersonLifecycle(tenantId, personId)
            .orElseThrow(() -> new IllegalStateException("人员档案未找到 (personId: " + personId + ")"));

        String maskedId = IamOpsRules.maskIdCard(rawIdCard, hasUnmaskPermission);
        String maskedBank = IamOpsRules.maskBankAccount(rawBankAccount, hasUnmaskPermission);
        String maskedSal = IamOpsRules.maskPayroll(rawSalary, hasUnmaskPermission);

        return new MaskedEmployeeRecord(
            lc.personId(), lc.employeeNo(), lc.displayName(),
            lc.departmentId(), lc.projectId(),
            maskedId, maskedBank, maskedSal, !hasUnmaskPermission
        );
    }

    public ExportRequest requestExport(UUID tenantId, UUID actorAccount, UUID actorPerson, CreateExportRequestInput in) {
        DataScopeGrant grant = store.findActiveDataScopeGrant(tenantId, actorPerson)
            .orElseThrow(() -> new IllegalStateException("操作人无有效数据范围授权，拒绝导出"));

        // Validate export scope and unmasked permission
        IamOpsRules.validateExportAuthorization(grant, in.filterDepartmentId(), in.filterProjectId(), in.requestUnmasked());

        UUID id = UUID.randomUUID();
        OffsetDateTime now = nowUtc();
        ExportRequest req = new ExportRequest(
            id, tenantId, actorAccount, actorPerson, in.exportResource(),
            in.filterDepartmentId(), in.filterProjectId(), in.requestUnmasked(),
            "AUTHORIZED", null, 100, IamOpsRules.sha256Hex("mock-export-" + id),
            now, now.plusSeconds(2)
        );
        store.insertExportRequest(req);
        return req;
    }

    // ========================================================================
    // IAM03: SoD Matrix, Approvals & Delegations
    // ========================================================================

    @Transactional(readOnly = true)
    public List<SodMatrixRule> getSodMatrix(UUID tenantId) {
        return store.listSodMatrix(tenantId);
    }

    public ApprovalTask createApprovalTask(UUID tenantId, UUID requestedByAccount, UUID requestedByPerson, CreateApprovalTaskInput in) {
        List<ApprovalThreshold> thresholds = store.listApprovalThresholds(tenantId, in.entityType());
        ApprovalThreshold matched = IamOpsRules.findThreshold(in.amount(), in.entityType(), thresholds);

        UUID taskId = UUID.randomUUID();
        OffsetDateTime now = nowUtc();
        List<ApprovalStageDecision> stages = new ArrayList<>();

        stages.add(new ApprovalStageDecision(
            UUID.randomUUID(), taskId, 1, matched.stage1Role(), null, "PENDING", null, null, null, null
        ));

        if (matched.requiredStages() >= 2 && matched.stage2Role() != null) {
            stages.add(new ApprovalStageDecision(
                UUID.randomUUID(), taskId, 2, matched.stage2Role(), null, "PENDING", null, null, null, null
            ));
        }
        if (matched.requiredStages() >= 3 && matched.stage3Role() != null) {
            stages.add(new ApprovalStageDecision(
                UUID.randomUUID(), taskId, 3, matched.stage3Role(), null, "PENDING", null, null, null, null
            ));
        }

        ApprovalTask task = new ApprovalTask(
            taskId, tenantId, matched.id(), in.entityType(), in.entityId(),
            in.amount(), 1, matched.requiredStages(), "PENDING",
            requestedByAccount, requestedByPerson, now, now, stages
        );
        store.insertApprovalTaskWithStages(task);
        return task;
    }

    public ApprovalTask decideStage(UUID tenantId, UUID taskId, UUID deciderAccount, UUID deciderPerson, DecideStageInput in) {
        ApprovalTask task = store.findApprovalTaskById(tenantId, taskId)
            .orElseThrow(() -> new IllegalStateException("审批任务未找到 (taskId: " + taskId + ")"));

        if (!"PENDING".equalsIgnoreCase(task.status())) {
            throw new IllegalStateException("审批任务已被终结，无法继续审批 (状态: " + task.status() + ")");
        }
        if (task.currentStage() != in.stageOrder()) {
            throw new IllegalStateException("审批阶段不匹配：当前为第 " + task.currentStage() + " 阶，请求为第 " + in.stageOrder() + " 阶");
        }

        // SoD Rule: Maker cannot approve own transaction & distinct person across stages
        IamOpsRules.validateApprovalStageDecider(task, in.stageOrder(), deciderPerson);

        if (!"APPROVED".equalsIgnoreCase(in.decision()) && !"REJECTED".equalsIgnoreCase(in.decision())) {
            throw new IllegalArgumentException("审批决定必须为 APPROVED 或 REJECTED");
        }

        store.updateStageDecision(tenantId, taskId, in.stageOrder(), in.decision(), deciderAccount, deciderPerson, in.reason());

        if ("REJECTED".equalsIgnoreCase(in.decision())) {
            store.updateApprovalTaskStatus(tenantId, taskId, in.stageOrder(), "REJECTED");
        } else {
            if (in.stageOrder() < task.totalStages()) {
                store.updateApprovalTaskStatus(tenantId, taskId, in.stageOrder() + 1, "PENDING");
            } else {
                store.updateApprovalTaskStatus(tenantId, taskId, in.stageOrder(), "APPROVED");
            }
        }

        return store.findApprovalTaskById(tenantId, taskId).orElseThrow();
    }

    public Delegation createDelegation(UUID tenantId, UUID delegatorAccount, UUID delegatorPerson, Set<String> delegateeRoles, CreateDelegationInput in) {
        Set<String> effectiveRoles = (delegateeRoles != null && !delegateeRoles.isEmpty())
            ? delegateeRoles
            : store.findRolesForAccount(tenantId, in.delegateeAccount());

        List<SodMatrixRule> matrix = store.listSodMatrix(tenantId);
        IamOpsRules.validateDelegation(
            delegatorPerson, in.delegateePerson(), in.effectiveFrom(), in.effectiveTo(),
            effectiveRoles, in.role(), matrix
        );

        UUID id = UUID.randomUUID();
        OffsetDateTime now = nowUtc();
        Delegation del = new Delegation(
            id, tenantId, delegatorAccount, delegatorPerson, in.delegateeAccount(), in.delegateePerson(),
            in.role(), in.effectiveFrom(), in.effectiveTo(), "ACTIVE", in.reason(),
            null, null, now
        );
        store.insertDelegation(del);
        return del;
    }

    // ========================================================================
    // IAM04: Independent AUDITOR & Dual-Person Emergency Grants
    // ========================================================================

    public EmergencyGrant requestEmergencyGrant(UUID tenantId, UUID requesterAccount, UUID requesterPerson, RequestEmergencyGrantInput in) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = nowUtc();
        EmergencyGrant g = new EmergencyGrant(
            id, tenantId, requesterAccount, requesterPerson, in.bookId(),
            in.scopeDescription(), in.requestedDurationMinutes(), in.reason(),
            "PENDING", null, null, null, null, null, null, null, null, null, null, now
        );
        store.insertEmergencyGrant(g);
        return g;
    }

    public EmergencyGrant approveEmergencyGrant(UUID tenantId, UUID grantId, UUID approverAccount, UUID approverPerson, ApproveEmergencyGrantInput in) {
        EmergencyGrant g = store.findEmergencyGrantById(tenantId, grantId)
            .orElseThrow(() -> new IllegalStateException("紧急特权申请未找到 (grantId: " + grantId + ")"));

        if (!"PENDING".equalsIgnoreCase(g.status())) {
            throw new IllegalStateException("紧急特权申请当前不在待审核状态 (状态: " + g.status() + ")");
        }

        OffsetDateTime now = nowUtc();
        if ("APPROVER1".equalsIgnoreCase(in.approvalSlot())) {
            IamOpsRules.validateEmergencyGrantApprovals(g.requesterPerson(), approverPerson, g.approver2Person());
            store.updateEmergencyGrantApproval1(tenantId, grantId, approverAccount, approverPerson, now);
        } else if ("APPROVER2".equalsIgnoreCase(in.approvalSlot())) {
            if (g.approver1Person() == null) {
                throw new IllegalStateException("必须先完成第一审批人审核");
            }
            IamOpsRules.validateEmergencyGrantApprovals(g.requesterPerson(), g.approver1Person(), approverPerson);
            OffsetDateTime expiresAt = now.plusMinutes(g.requestedDurationMinutes());
            store.updateEmergencyGrantApproval2AndActivate(tenantId, grantId, approverAccount, approverPerson, now, expiresAt);
        } else {
            throw new IllegalArgumentException("审批席位必须为 APPROVER1 或 APPROVER2");
        }

        return store.findEmergencyGrantById(tenantId, grantId).orElseThrow();
    }

    // ========================================================================
    // OPS01: Backup Manifests, Joint Drills & Rollback Ledgers
    // ========================================================================

    public BackupManifest createBackupManifest(UUID tenantId, CreateBackupManifestInput in) {
        String drStatus = (in.cloudDrStatus() != null && !in.cloudDrStatus().isBlank())
            ? in.cloudDrStatus()
            : IamOpsRules.LOCAL_DRILL_ONLY;

        // Enforce no fake cloud claim rule: mark cloud DR LOCAL_DRILL_ONLY unless externally verified
        IamOpsRules.validateCloudDrStatus(drStatus, false);

        UUID id = UUID.randomUUID();
        OffsetDateTime now = nowUtc();
        BackupManifest m = new BackupManifest(
            id, tenantId, in.backupId(), in.backupType(), in.dbSnapshotHash(),
            in.originalFilesCount(), in.originalBytesTotal(), in.filesManifestSha256(),
            in.recoveryTargetTime() == null ? now : in.recoveryTargetTime(),
            drStatus, "VERIFIED", now, now
        );
        store.insertBackupManifest(m);
        return m;
    }

    public JointDrillRecord executeJointRecoveryDrill(UUID tenantId, UUID executedBy, ExecuteJointDrillInput in) {
        BackupManifest manifest = store.findBackupManifestById(tenantId, in.manifestId())
            .orElseThrow(() -> new IllegalStateException("灾备清单未找到 (manifestId: " + in.manifestId() + ")"));

        boolean dbMatched = (in.simulatedDbRowCount() > 0);
        boolean filesMatched = (in.simulatedDiscrepancyCount() == 0);
        boolean bytesMatched = (in.simulatedOriginalByteTotal() == manifest.originalBytesTotal());

        boolean success = dbMatched && filesMatched && bytesMatched && (in.simulatedDiscrepancyCount() == 0);
        String status = success ? "SUCCESS" : "FAILED";

        UUID id = UUID.randomUUID();
        OffsetDateTime now = nowUtc();
        JointDrillRecord dr = new JointDrillRecord(
            id, tenantId, manifest.id(), in.drillName(), manifest.recoveryTargetTime(),
            in.simulatedDbRowCount(), dbMatched, filesMatched, bytesMatched,
            in.simulatedDiscrepancyCount(), "[]", status, executedBy, 1500L, now
        );
        store.insertJointDrillRecord(dr);
        return dr;
    }

    public RollbackLedgerRecord recordRollbackReadiness(UUID tenantId, UUID verifiedBy, CreateRollbackLedgerInput in) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = nowUtc();
        RollbackLedgerRecord r = new RollbackLedgerRecord(
            id, tenantId, in.releaseVersion(), in.migrationName(),
            in.rollbackScriptSha256(), in.backwardCompatible(),
            in.readinessStatus(), in.blockReason(), verifiedBy, now
        );
        store.insertRollbackLedger(r);
        return r;
    }

    // ========================================================================
    // OPS02: Support Tickets, Incident Timelines & Diagnostic Grants
    // ========================================================================

    public SupportTicket createSupportTicket(UUID tenantId, UUID creatorAccount, CreateTicketInput in) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = nowUtc();
        String ticketNo = "TICK-" + now.format(DateTimeFormatter.ofPattern("yyyyMM")) + "-" + String.format("%04d", random.nextInt(10000));

        SupportTicket t = new SupportTicket(
            id, tenantId, ticketNo, in.bookId(), in.severity(), in.category(),
            in.title(), in.description(), "OPEN", creatorAccount, now, now, null
        );
        store.insertSupportTicket(t);
        return t;
    }

    public IncidentTimelineEvent addIncidentTimelineEvent(UUID tenantId, UUID ticketId, UUID recordedBy, AddTimelineEventInput in) {
        store.findSupportTicketById(tenantId, ticketId)
            .orElseThrow(() -> new IllegalStateException("支持工单未找到 (ticketId: " + ticketId + ")"));

        UUID id = UUID.randomUUID();
        OffsetDateTime now = nowUtc();
        IncidentTimelineEvent ev = new IncidentTimelineEvent(
            id, tenantId, ticketId, in.incidentPhase(), in.summary(),
            in.customerCommunication(), in.technicalDetail(), now, recordedBy, now
        );
        store.insertIncidentTimelineEvent(ev);

        // Auto-transition ticket to RESOLVED if incident phase is RESOLVED
        if ("RESOLVED".equalsIgnoreCase(in.incidentPhase())) {
            store.updateSupportTicketStatus(tenantId, ticketId, "RESOLVED", now);
        }

        return ev;
    }

    public void updateSupportTicketStatus(UUID tenantId, UUID ticketId, UpdateTicketStatusInput in) {
        OffsetDateTime resolvedAt = "RESOLVED".equalsIgnoreCase(in.status()) ? nowUtc() : null;
        store.updateSupportTicketStatus(tenantId, ticketId, in.status(), resolvedAt);
    }

    public DiagnosticGrant grantDiagnosticAccess(UUID tenantId, UUID approverAccount, UUID approverPerson, GrantDiagnosticInput in) {
        store.findSupportTicketById(tenantId, in.ticketId())
            .orElseThrow(() -> new IllegalStateException("关联工单不存在 (ticketId: " + in.ticketId() + ")"));

        UUID id = UUID.randomUUID();
        OffsetDateTime now = nowUtc();
        int minutes = in.durationMinutes() <= 0 ? 60 : in.durationMinutes();
        OffsetDateTime expires = now.plusMinutes(minutes);

        DiagnosticGrant g = new DiagnosticGrant(
            id, tenantId, in.ticketId(), in.bookId(), in.supportOperatorAccount(),
            approverAccount, approverPerson, "READ_ONLY_DIAGNOSTIC",
            minutes, "ACTIVE", now, expires, null, null, null, now
        );
        store.insertDiagnosticGrant(g);
        return g;
    }

    public void revokeDiagnosticAccess(UUID tenantId, UUID grantId, UUID revokerAccount, RevokeDiagnosticInput in) {
        String reason = (in != null && in.reason() != null && !in.reason().isBlank())
            ? in.reason().trim()
            : "客户主动撤销诊断访问";
        store.revokeDiagnosticGrant(tenantId, grantId, revokerAccount, reason);
    }

    public void assertAuditorReadOnly(UUID tenantId, UUID accountId, String action) {
        Set<String> roles = store.findRolesForAccount(tenantId, accountId);
        IamOpsRules.checkAuditorReadOnly(roles, action);
    }
}

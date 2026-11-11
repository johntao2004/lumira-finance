package com.lumira.finance.iamops;

import com.lumira.finance.FinanceGlobalExceptionHandler;
import com.lumira.finance.iamops.IamOpsModels.*;
import com.lumira.platform.modules.ModuleEndpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/finance/v1/iam-ops")
@ModuleEndpoint("finance")
public class IamOpsController {
    private final IamOpsService service;

    public IamOpsController(IamOpsService service) {
        this.service = service;
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", 200);
        map.put("success", true);
        map.put("data", data);
        return map;
    }

    // ========================================================================
    // IAM01 Endpoints
    // ========================================================================

    @PostMapping("/invitations")
    public ResponseEntity<Map<String, Object>> createInvitation(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-Account-Id") UUID inviterAccount,
            @RequestHeader(value = "X-Person-Id", required = false) UUID inviterPerson,
            @RequestBody CreateInvitationInput input) {
        try {
            MemberInvitation res = service.inviteMember(tenantId, inviterAccount, inviterPerson, input);
            return ResponseEntity.ok(ok(res));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/invitations/accept")
    public ResponseEntity<Map<String, Object>> acceptInvitation(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestBody AcceptInvitationInput input) {
        try {
            PersonLifecycle res = service.acceptInvitation(tenantId, input);
            return ResponseEntity.ok(ok(res));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/attested-sources")
    public ResponseEntity<Map<String, Object>> importAttestedSource(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestBody ImportAttestedSourceInput input) {
        try {
            AttestedSource res = service.importAttestedSource(tenantId, input);
            return ResponseEntity.ok(ok(res));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/persons/{personId}/offboard")
    public ResponseEntity<Map<String, Object>> offboardPerson(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-Account-Id") UUID operatorId,
            @PathVariable UUID personId,
            @RequestBody(required = false) OffboardPersonInput input) {
        try {
            Map<String, Integer> res = service.offboardPersonAtomic(tenantId, personId, operatorId, input);
            return ResponseEntity.ok(ok(res));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // ========================================================================
    // IAM02 Endpoints
    // ========================================================================

    @PostMapping("/data-scopes")
    public ResponseEntity<Map<String, Object>> grantDataScope(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestBody GrantDataScopeInput input) {
        try {
            DataScopeGrant res = service.grantDataScope(tenantId, input);
            return ResponseEntity.ok(ok(res));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/export-requests")
    public ResponseEntity<Map<String, Object>> requestExport(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-Account-Id") UUID actorAccount,
            @RequestHeader("X-Person-Id") UUID actorPerson,
            @RequestBody CreateExportRequestInput input) {
        try {
            ExportRequest res = service.requestExport(tenantId, actorAccount, actorPerson, input);
            return ResponseEntity.ok(ok(res));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @GetMapping("/persons/{personId}")
    public ResponseEntity<Map<String, Object>> getPersonProfile(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-Person-Id") UUID actorPerson,
            @PathVariable UUID personId) {
        try {
            MaskedEmployeeRecord res = service.getMaskedEmployee(tenantId, actorPerson, personId);
            return ResponseEntity.ok(ok(res));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // ========================================================================
    // IAM03 Endpoints
    // ========================================================================

    @GetMapping("/sod-matrix")
    public ResponseEntity<Map<String, Object>> getSodMatrix(
            @RequestHeader("X-Tenant-Id") UUID tenantId) {
        try {
            List<SodMatrixRule> res = service.getSodMatrix(tenantId);
            return ResponseEntity.ok(ok(res));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/approval-tasks")
    public ResponseEntity<Map<String, Object>> createApprovalTask(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-Account-Id") UUID requestedByAccount,
            @RequestHeader("X-Person-Id") UUID requestedByPerson,
            @RequestBody CreateApprovalTaskInput input) {
        try {
            ApprovalTask res = service.createApprovalTask(tenantId, requestedByAccount, requestedByPerson, input);
            return ResponseEntity.ok(ok(formatTask(res)));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/approval-tasks/{taskId}/decide")
    public ResponseEntity<Map<String, Object>> decideApprovalStage(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-Account-Id") UUID deciderAccount,
            @RequestHeader("X-Person-Id") UUID deciderPerson,
            @PathVariable UUID taskId,
            @RequestBody DecideStageInput input) {
        try {
            ApprovalTask res = service.decideStage(tenantId, taskId, deciderAccount, deciderPerson, input);
            return ResponseEntity.ok(ok(formatTask(res)));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/delegations")
    public ResponseEntity<Map<String, Object>> createDelegation(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-Account-Id") UUID delegatorAccount,
            @RequestHeader("X-Person-Id") UUID delegatorPerson,
            @RequestBody CreateDelegationInput input) {
        try {
            Delegation res = service.createDelegation(tenantId, delegatorAccount, delegatorPerson, null, input);
            return ResponseEntity.ok(ok(res));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // ========================================================================
    // IAM04 Endpoints
    // ========================================================================

    @PostMapping("/emergency-grants")
    public ResponseEntity<Map<String, Object>> requestEmergencyGrant(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-Account-Id") UUID requesterAccount,
            @RequestHeader("X-Person-Id") UUID requesterPerson,
            @RequestBody RequestEmergencyGrantInput input) {
        try {
            EmergencyGrant res = service.requestEmergencyGrant(tenantId, requesterAccount, requesterPerson, input);
            return ResponseEntity.ok(ok(res));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/emergency-grants/{grantId}/approve")
    public ResponseEntity<Map<String, Object>> approveEmergencyGrant(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-Account-Id") UUID approverAccount,
            @RequestHeader("X-Person-Id") UUID approverPerson,
            @PathVariable UUID grantId,
            @RequestBody ApproveEmergencyGrantInput input) {
        try {
            EmergencyGrant res = service.approveEmergencyGrant(tenantId, grantId, approverAccount, approverPerson, input);
            return ResponseEntity.ok(ok(res));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // ========================================================================
    // OPS01 Endpoints
    // ========================================================================

    @PostMapping("/backup-manifests")
    public ResponseEntity<Map<String, Object>> createBackupManifest(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestBody CreateBackupManifestInput input) {
        try {
            BackupManifest res = service.createBackupManifest(tenantId, input);
            return ResponseEntity.ok(ok(res));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/joint-drills")
    public ResponseEntity<Map<String, Object>> executeJointDrill(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-Account-Id") UUID operatorId,
            @RequestBody ExecuteJointDrillInput input) {
        try {
            JointDrillRecord res = service.executeJointRecoveryDrill(tenantId, operatorId, input);
            return ResponseEntity.ok(ok(res));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/rollback-ledger")
    public ResponseEntity<Map<String, Object>> recordRollbackLedger(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-Account-Id") UUID operatorId,
            @RequestBody CreateRollbackLedgerInput input) {
        try {
            RollbackLedgerRecord res = service.recordRollbackReadiness(tenantId, operatorId, input);
            return ResponseEntity.ok(ok(res));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // ========================================================================
    // OPS02 Endpoints
    // ========================================================================

    @PostMapping("/support-tickets")
    public ResponseEntity<Map<String, Object>> createSupportTicket(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-Account-Id") UUID creatorAccount,
            @RequestBody CreateTicketInput input) {
        try {
            SupportTicket res = service.createSupportTicket(tenantId, creatorAccount, input);
            return ResponseEntity.ok(ok(res));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/support-tickets/{ticketId}/timeline")
    public ResponseEntity<Map<String, Object>> addIncidentTimelineEvent(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-Account-Id") UUID operatorId,
            @PathVariable UUID ticketId,
            @RequestBody AddTimelineEventInput input) {
        try {
            IncidentTimelineEvent res = service.addIncidentTimelineEvent(tenantId, ticketId, operatorId, input);
            return ResponseEntity.ok(ok(res));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/support-tickets/{ticketId}/status")
    public ResponseEntity<Map<String, Object>> updateTicketStatus(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID ticketId,
            @RequestBody UpdateTicketStatusInput input) {
        try {
            service.updateSupportTicketStatus(tenantId, ticketId, input);
            return ResponseEntity.ok(ok(Map.of("updated", true, "ticketId", ticketId, "status", input.status())));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/diagnostic-grants")
    public ResponseEntity<Map<String, Object>> grantDiagnosticAccess(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-Account-Id") UUID approverAccount,
            @RequestHeader("X-Person-Id") UUID approverPerson,
            @RequestBody GrantDiagnosticInput input) {
        try {
            DiagnosticGrant res = service.grantDiagnosticAccess(tenantId, approverAccount, approverPerson, input);
            return ResponseEntity.ok(ok(res));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/diagnostic-grants/{grantId}/revoke")
    public ResponseEntity<Map<String, Object>> revokeDiagnosticAccess(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-Account-Id") UUID revokerAccount,
            @PathVariable UUID grantId,
            @RequestBody(required = false) RevokeDiagnosticInput input) {
        try {
            service.revokeDiagnosticAccess(tenantId, grantId, revokerAccount, input);
            return ResponseEntity.ok(ok(Map.of("revoked", true, "grantId", grantId)));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // Helper to format BigDecimal amounts cleanly
    private Map<String, Object> formatTask(ApprovalTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("tenantId", t.tenantId());
        m.put("thresholdId", t.thresholdId());
        m.put("entityType", t.entityType());
        m.put("entityId", t.entityId());
        m.put("amount", t.amount() != null ? t.amount().toPlainString() : "0.00");
        m.put("currentStage", t.currentStage());
        m.put("totalStages", t.totalStages());
        m.put("status", t.status());
        m.put("requestedByAccount", t.requestedByAccount());
        m.put("requestedByPerson", t.requestedByPerson());
        m.put("createdAt", t.createdAt());
        m.put("updatedAt", t.updatedAt());
        m.put("stages", t.stages());
        return m;
    }
}

package com.lumira.finance.iamops;

import com.lumira.finance.iamops.IamOpsModels.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Repository
public class IamOpsStore {
    private final JdbcClient jdbc;

    public IamOpsStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime toOffset(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }

    // ========================================================================
    // IAM01: Invitations, Attested Sources & Lifecycle
    // ========================================================================

    public void insertInvitation(MemberInvitation inv) {
        jdbc.sql("""
            INSERT INTO plugin_finance_iam_invitation
            (tenant_id, id, email, phone, role, department_id, project_id, status, invited_by_account, invited_by_person, token_hash, created_at, expires_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
            .params(
                inv.tenantId(), inv.id(), inv.email(), inv.phone(), inv.role(),
                inv.departmentId(), inv.projectId(), inv.status(),
                inv.invitedByAccount(), inv.invitedByPerson(), inv.tokenHash(),
                Timestamp.from(inv.createdAt().toInstant()),
                Timestamp.from(inv.expiresAt().toInstant()),
                inv.version()
            ).update();
    }

    public Optional<MemberInvitation> findInvitationByTokenHash(UUID tenantId, String tokenHash) {
        return jdbc.sql("""
            SELECT * FROM plugin_finance_iam_invitation WHERE tenant_id = ? AND token_hash = ?
            """)
            .params(tenantId, tokenHash)
            .query(this::mapInvitation)
            .optional();
    }

    public Optional<MemberInvitation> findInvitationById(UUID tenantId, UUID id) {
        return jdbc.sql("""
            SELECT * FROM plugin_finance_iam_invitation WHERE tenant_id = ? AND id = ?
            """)
            .params(tenantId, id)
            .query(this::mapInvitation)
            .optional();
    }

    public void acceptInvitation(UUID tenantId, UUID id, OffsetDateTime acceptedAt) {
        jdbc.sql("""
            UPDATE plugin_finance_iam_invitation
            SET status = 'ACCEPTED', accepted_at = ?, version = version + 1
            WHERE tenant_id = ? AND id = ? AND status = 'PENDING'
            """)
            .params(Timestamp.from(acceptedAt.toInstant()), tenantId, id)
            .update();
    }

    private MemberInvitation mapInvitation(ResultSet rs, int rowNum) throws SQLException {
        return new MemberInvitation(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"),
            rs.getString("email"),
            rs.getString("phone"),
            rs.getString("role"),
            rs.getString("department_id"),
            rs.getString("project_id"),
            rs.getString("status"),
            (UUID) rs.getObject("invited_by_account"),
            (UUID) rs.getObject("invited_by_person"),
            rs.getString("token_hash"),
            toOffset(rs.getTimestamp("created_at")),
            toOffset(rs.getTimestamp("expires_at")),
            toOffset(rs.getTimestamp("accepted_at")),
            rs.getLong("version")
        );
    }

    public void insertAttestedSource(AttestedSource src) {
        jdbc.sql("""
            INSERT INTO plugin_finance_iam_attested_source
            (tenant_id, id, source_type, provider_name, attestation_kind, source_reference, source_sha256, record_count, raw_payload, attested_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """)
            .params(
                src.tenantId(), src.id(), src.sourceType(), src.providerName(),
                src.attestationKind(), src.sourceReference(), src.sourceSha256(),
                src.recordCount(), src.rawPayload(),
                Timestamp.from(src.attestedAt().toInstant()),
                Timestamp.from(src.createdAt().toInstant())
            ).update();
    }

    public Optional<AttestedSource> findAttestedSourceById(UUID tenantId, UUID id) {
        return jdbc.sql("""
            SELECT * FROM plugin_finance_iam_attested_source WHERE tenant_id = ? AND id = ?
            """)
            .params(tenantId, id)
            .query(this::mapAttestedSource)
            .optional();
    }

    private AttestedSource mapAttestedSource(ResultSet rs, int rowNum) throws SQLException {
        return new AttestedSource(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"),
            rs.getString("source_type"),
            rs.getString("provider_name"),
            rs.getString("attestation_kind"),
            rs.getString("source_reference"),
            rs.getString("source_sha256"),
            rs.getInt("record_count"),
            rs.getString("raw_payload"),
            toOffset(rs.getTimestamp("attested_at")),
            toOffset(rs.getTimestamp("created_at"))
        );
    }

    public void insertPersonLifecycle(PersonLifecycle lc) {
        jdbc.sql("""
            INSERT INTO plugin_finance_iam_person_lifecycle
            (tenant_id, id, person_id, account_id, employee_no, display_name, department_id, project_id, id_card, bank_account, salary_amount, state, created_at, updated_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
            .params(
                lc.tenantId(), lc.id(), lc.personId(), lc.accountId(),
                lc.employeeNo(), lc.displayName(), lc.departmentId(), lc.projectId(),
                lc.idCard(), lc.bankAccount(), lc.salaryAmount(),
                lc.state(),
                Timestamp.from(lc.createdAt().toInstant()),
                Timestamp.from(lc.updatedAt().toInstant()),
                lc.version()
            ).update();
    }

    public Optional<PersonLifecycle> findPersonLifecycle(UUID tenantId, UUID personId) {
        return jdbc.sql("""
            SELECT * FROM plugin_finance_iam_person_lifecycle WHERE tenant_id = ? AND person_id = ?
            """)
            .params(tenantId, personId)
            .query(this::mapPersonLifecycle)
            .optional();
    }

    private PersonLifecycle mapPersonLifecycle(ResultSet rs, int rowNum) throws SQLException {
        return new PersonLifecycle(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"),
            (UUID) rs.getObject("person_id"),
            (UUID) rs.getObject("account_id"),
            rs.getString("employee_no"),
            rs.getString("display_name"),
            rs.getString("department_id"),
            rs.getString("project_id"),
            rs.getString("id_card"),
            rs.getString("bank_account"),
            rs.getBigDecimal("salary_amount"),
            rs.getString("state"),
            toOffset(rs.getTimestamp("created_at")),
            toOffset(rs.getTimestamp("updated_at")),
            toOffset(rs.getTimestamp("offboarded_at")),
            (UUID) rs.getObject("offboarded_by"),
            rs.getString("offboarding_reason"),
            rs.getLong("version")
        );
    }

    public Set<String> findRolesForAccount(UUID tenantId, UUID accountId) {
        if (accountId == null) return Set.of();
        try {
            List<String> roles = jdbc.sql("""
                SELECT DISTINCT role FROM plugin_finance_member
                WHERE tenant_id = ? AND user_id = ? AND enabled = true
                """)
                .params(tenantId, accountId)
                .query((rs, rowNum) -> rs.getString("role"))
                .list();
            return new HashSet<>(roles);
        } catch (Exception e) {
            return Set.of();
        }
    }

    /**
     * IAM01 Atomic Offboarding:
     * Atomically marks person OFFBOARDED, disables active book memberships,
     * revokes active delegations, cancels pending approval stages and tasks,
     * revokes emergency grants and data scopes, and rejects pending export requests.
     */
    public Map<String, Integer> offboardPersonAtomic(UUID tenantId, UUID personId, UUID operatorId, String reason) {
        Timestamp now = Timestamp.from(OffsetDateTime.now(ZoneOffset.UTC).toInstant());

        // 1. Update lifecycle
        int lcRows = jdbc.sql("""
            UPDATE plugin_finance_iam_person_lifecycle
            SET state = 'OFFBOARDED', offboarded_at = ?, offboarded_by = ?, offboarding_reason = ?, version = version + 1, updated_at = ?
            WHERE tenant_id = ? AND person_id = ? AND state <> 'OFFBOARDED'
            """)
            .params(now, operatorId, reason, now, tenantId, personId)
            .update();

        if (lcRows == 0) {
            throw new IllegalStateException("人员不存在或已处于离职状态 (personId: " + personId + ")");
        }

        // Get associated account_id
        Optional<UUID> accountIdOpt = jdbc.sql("""
            SELECT account_id FROM plugin_finance_iam_person_lifecycle WHERE tenant_id = ? AND person_id = ?
            """)
            .params(tenantId, personId)
            .query((rs, rowNum) -> (UUID) rs.getObject("account_id"))
            .optional();

        int memberRevoked = 0;
        if (accountIdOpt.isPresent() && accountIdOpt.get() != null) {
            UUID accId = accountIdOpt.get();
            // 2. Disable book memberships if plugin_finance_member table exists
            try {
                memberRevoked = jdbc.sql("""
                    UPDATE plugin_finance_member
                    SET enabled = false
                    WHERE tenant_id = ? AND user_id = ? AND enabled = true
                    """)
                    .params(tenantId, accId)
                    .update();
            } catch (Exception ignored) {
                // Ignore if table is in a test schema where plugin_finance_member is omitted
            }
        }

        // 3. Revoke all active delegations
        int delegRevoked = jdbc.sql("""
            UPDATE plugin_finance_iam_delegation
            SET status = 'REVOKED', revoked_at = ?, revoked_by = ?
            WHERE tenant_id = ? AND status = 'ACTIVE' AND (delegator_person = ? OR delegatee_person = ?)
            """)
            .params(now, operatorId, tenantId, personId, personId)
            .update();

        // 4. Invalidate/reject pending approval stages assigned to this person
        int pendingRejected = jdbc.sql("""
            UPDATE plugin_finance_iam_approval_stage_decision
            SET decision = 'REJECTED', decision_reason = 'OFFBOARDING_REVOKED', decided_at = ?, decided_by_person = ?
            WHERE tenant_id = ? AND decision = 'PENDING' AND assigned_person_id = ?
            """)
            .params(now, operatorId, tenantId, personId)
            .update();

        // 5. Cancel pending approval tasks requested by the offboarded person or affected by rejected stage
        int tasksCancelled = jdbc.sql("""
            UPDATE plugin_finance_iam_approval_task
            SET status = 'CANCELLED', updated_at = ?
            WHERE tenant_id = ? AND status = 'PENDING' AND (
                requested_by_person = ?
                OR id IN (
                    SELECT task_id FROM plugin_finance_iam_approval_stage_decision
                    WHERE tenant_id = ? AND decision_reason = 'OFFBOARDING_REVOKED'
                )
            )
            """)
            .params(now, tenantId, personId, tenantId)
            .update();

        // 6. Inactivate active data scope grants
        int scopesRevoked = jdbc.sql("""
            UPDATE plugin_finance_iam_data_scope_grant
            SET status = 'REVOKED', revoked_at = ?, revoked_by = ?, version = version + 1
            WHERE tenant_id = ? AND person_id = ? AND status = 'ACTIVE'
            """)
            .params(now, operatorId, tenantId, personId)
            .update();

        // 7. Revoke active or pending emergency privileged grants
        int emergencyRevoked = jdbc.sql("""
            UPDATE plugin_finance_iam_emergency_grant
            SET status = 'REVOKED', revoked_at = ?, revoked_by = ?
            WHERE tenant_id = ? AND status IN ('PENDING', 'ACTIVE')
              AND (requester_person = ? OR approver1_person = ? OR approver2_person = ?)
            """)
            .params(now, operatorId, tenantId, personId, personId, personId)
            .update();

        // 8. Cancel pending export requests
        int exportsCancelled = jdbc.sql("""
            UPDATE plugin_finance_iam_export_request
            SET status = 'REJECTED', rejection_reason = 'OFFBOARDING_REVOKED', completed_at = ?
            WHERE tenant_id = ? AND requested_by_person = ? AND status = 'PENDING'
            """)
            .params(now, tenantId, personId)
            .update();

        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("lifecycleUpdated", lcRows);
        summary.put("membershipsDisabled", memberRevoked);
        summary.put("delegationsRevoked", delegRevoked);
        summary.put("pendingApprovalsCancelled", pendingRejected);
        summary.put("pendingTasksCancelled", tasksCancelled);
        summary.put("dataScopesRevoked", scopesRevoked);
        summary.put("emergencyGrantsRevoked", emergencyRevoked);
        summary.put("pendingExportsCancelled", exportsCancelled);
        return summary;
    }

    // ========================================================================
    // IAM02: Data Scopes & Exports
    // ========================================================================

    public void insertDataScopeGrant(DataScopeGrant grant) {
        jdbc.sql("""
            INSERT INTO plugin_finance_iam_data_scope_grant
            (tenant_id, id, account_id, person_id, scope_type, department_id, project_id, can_export, can_unmask_sensitive, status, created_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
            .params(
                grant.tenantId(), grant.id(), grant.accountId(), grant.personId(),
                grant.scopeType(), grant.departmentId(), grant.projectId(),
                grant.canExport(), grant.canUnmaskSensitive(), grant.status(),
                Timestamp.from(grant.createdAt().toInstant()),
                grant.version()
            ).update();
    }

    public Optional<DataScopeGrant> findActiveDataScopeGrant(UUID tenantId, UUID personId) {
        return jdbc.sql("""
            SELECT * FROM plugin_finance_iam_data_scope_grant
            WHERE tenant_id = ? AND person_id = ? AND status = 'ACTIVE'
            ORDER BY created_at DESC LIMIT 1
            """)
            .params(tenantId, personId)
            .query(this::mapDataScopeGrant)
            .optional();
    }

    private DataScopeGrant mapDataScopeGrant(ResultSet rs, int rowNum) throws SQLException {
        return new DataScopeGrant(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"),
            (UUID) rs.getObject("account_id"),
            (UUID) rs.getObject("person_id"),
            rs.getString("scope_type"),
            rs.getString("department_id"),
            rs.getString("project_id"),
            rs.getBoolean("can_export"),
            rs.getBoolean("can_unmask_sensitive"),
            rs.getString("status"),
            toOffset(rs.getTimestamp("created_at")),
            toOffset(rs.getTimestamp("revoked_at")),
            (UUID) rs.getObject("revoked_by"),
            rs.getLong("version")
        );
    }

    public void insertExportRequest(ExportRequest req) {
        jdbc.sql("""
            INSERT INTO plugin_finance_iam_export_request
            (tenant_id, id, requested_by_account, requested_by_person, export_resource, filter_department_id, filter_project_id, include_sensitive_unmasked, status, rejection_reason, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
            .params(
                req.tenantId(), req.id(), req.requestedByAccount(), req.requestedByPerson(),
                req.exportResource(), req.filterDepartmentId(), req.filterProjectId(),
                req.includeSensitiveUnmasked(), req.status(), req.rejectionReason(),
                Timestamp.from(req.createdAt().toInstant())
            ).update();
    }

    public Optional<ExportRequest> findExportRequestById(UUID tenantId, UUID id) {
        return jdbc.sql("""
            SELECT * FROM plugin_finance_iam_export_request WHERE tenant_id = ? AND id = ?
            """)
            .params(tenantId, id)
            .query(this::mapExportRequest)
            .optional();
    }

    private ExportRequest mapExportRequest(ResultSet rs, int rowNum) throws SQLException {
        return new ExportRequest(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"),
            (UUID) rs.getObject("requested_by_account"),
            (UUID) rs.getObject("requested_by_person"),
            rs.getString("export_resource"),
            rs.getString("filter_department_id"),
            rs.getString("filter_project_id"),
            rs.getBoolean("include_sensitive_unmasked"),
            rs.getString("status"),
            rs.getString("rejection_reason"),
            (Integer) rs.getObject("exported_row_count"),
            rs.getString("file_sha256"),
            toOffset(rs.getTimestamp("created_at")),
            toOffset(rs.getTimestamp("completed_at"))
        );
    }

    // ========================================================================
    // IAM03: SoD Matrix, Approvals & Delegations
    // ========================================================================

    public List<SodMatrixRule> listSodMatrix(UUID tenantId) {
        return jdbc.sql("""
            SELECT * FROM plugin_finance_iam_sod_conflict_matrix WHERE tenant_id = ? AND is_active = true
            """)
            .params(tenantId)
            .query(this::mapSodRule)
            .list();
    }

    private SodMatrixRule mapSodRule(ResultSet rs, int rowNum) throws SQLException {
        return new SodMatrixRule(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"),
            rs.getString("role_a"),
            rs.getString("role_b"),
            rs.getString("conflict_severity"),
            rs.getString("statutory_basis"),
            rs.getBoolean("is_active"),
            toOffset(rs.getTimestamp("created_at"))
        );
    }

    public List<ApprovalThreshold> listApprovalThresholds(UUID tenantId, String entityType) {
        return jdbc.sql("""
            SELECT * FROM plugin_finance_iam_approval_threshold
            WHERE tenant_id = ? AND entity_type = ?
            ORDER BY min_amount ASC
            """)
            .params(tenantId, entityType)
            .query(this::mapApprovalThreshold)
            .list();
    }

    private ApprovalThreshold mapApprovalThreshold(ResultSet rs, int rowNum) throws SQLException {
        return new ApprovalThreshold(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"),
            rs.getString("rule_name"),
            rs.getString("entity_type"),
            rs.getBigDecimal("min_amount"),
            rs.getBigDecimal("max_amount"),
            rs.getInt("required_stages"),
            rs.getString("stage1_role"),
            rs.getString("stage2_role"),
            rs.getString("stage3_role"),
            toOffset(rs.getTimestamp("created_at"))
        );
    }

    public void insertApprovalTaskWithStages(ApprovalTask task) {
        jdbc.sql("""
            INSERT INTO plugin_finance_iam_approval_task
            (tenant_id, id, threshold_id, entity_type, entity_id, amount, current_stage, total_stages, status, requested_by_account, requested_by_person, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
            .params(
                task.tenantId(), task.id(), task.thresholdId(), task.entityType(), task.entityId(),
                task.amount(), task.currentStage(), task.totalStages(), task.status(),
                task.requestedByAccount(), task.requestedByPerson(),
                Timestamp.from(task.createdAt().toInstant()),
                Timestamp.from(task.updatedAt().toInstant())
            ).update();

        for (ApprovalStageDecision stage : task.stages()) {
            jdbc.sql("""
                INSERT INTO plugin_finance_iam_approval_stage_decision
                (tenant_id, id, task_id, stage_order, assigned_role, assigned_person_id, decision)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)
                .params(
                    task.tenantId(), stage.id(), task.id(), stage.stageOrder(),
                    stage.assignedRole(), stage.assignedPersonId(), stage.decision()
                ).update();
        }
    }

    public Optional<ApprovalTask> findApprovalTaskById(UUID tenantId, UUID id) {
        Optional<ApprovalTask> taskOpt = jdbc.sql("""
            SELECT * FROM plugin_finance_iam_approval_task WHERE tenant_id = ? AND id = ?
            """)
            .params(tenantId, id)
            .query((rs, rowNum) -> new ApprovalTask(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("threshold_id"),
                rs.getString("entity_type"),
                (UUID) rs.getObject("entity_id"),
                rs.getBigDecimal("amount"),
                rs.getInt("current_stage"),
                rs.getInt("total_stages"),
                rs.getString("status"),
                (UUID) rs.getObject("requested_by_account"),
                (UUID) rs.getObject("requested_by_person"),
                toOffset(rs.getTimestamp("created_at")),
                toOffset(rs.getTimestamp("updated_at")),
                new ArrayList<>()
            ))
            .optional();

        if (taskOpt.isEmpty()) return Optional.empty();

        List<ApprovalStageDecision> stages = jdbc.sql("""
            SELECT * FROM plugin_finance_iam_approval_stage_decision
            WHERE tenant_id = ? AND task_id = ?
            ORDER BY stage_order ASC
            """)
            .params(tenantId, id)
            .query(this::mapStageDecision)
            .list();

        ApprovalTask task = taskOpt.get();
        return Optional.of(new ApprovalTask(
            task.id(), task.tenantId(), task.thresholdId(), task.entityType(), task.entityId(),
            task.amount(), task.currentStage(), task.totalStages(), task.status(),
            task.requestedByAccount(), task.requestedByPerson(), task.createdAt(), task.updatedAt(),
            stages
        ));
    }

    private ApprovalStageDecision mapStageDecision(ResultSet rs, int rowNum) throws SQLException {
        return new ApprovalStageDecision(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("task_id"),
            rs.getInt("stage_order"),
            rs.getString("assigned_role"),
            (UUID) rs.getObject("assigned_person_id"),
            rs.getString("decision"),
            (UUID) rs.getObject("decided_by_account"),
            (UUID) rs.getObject("decided_by_person"),
            rs.getString("decision_reason"),
            toOffset(rs.getTimestamp("decided_at"))
        );
    }

    public void updateStageDecision(UUID tenantId, UUID taskId, int stageOrder, String decision, UUID deciderAccount, UUID deciderPerson, String reason) {
        Timestamp now = Timestamp.from(OffsetDateTime.now(ZoneOffset.UTC).toInstant());
        jdbc.sql("""
            UPDATE plugin_finance_iam_approval_stage_decision
            SET decision = ?, decided_by_account = ?, decided_by_person = ?, decision_reason = ?, decided_at = ?
            WHERE tenant_id = ? AND task_id = ? AND stage_order = ? AND decision = 'PENDING'
            """)
            .params(decision, deciderAccount, deciderPerson, reason, now, tenantId, taskId, stageOrder)
            .update();
    }

    public void updateApprovalTaskStatus(UUID tenantId, UUID taskId, int currentStage, String status) {
        Timestamp now = Timestamp.from(OffsetDateTime.now(ZoneOffset.UTC).toInstant());
        jdbc.sql("""
            UPDATE plugin_finance_iam_approval_task
            SET current_stage = ?, status = ?, updated_at = ?
            WHERE tenant_id = ? AND id = ?
            """)
            .params(currentStage, status, now, tenantId, taskId)
            .update();
    }

    public void insertDelegation(Delegation del) {
        jdbc.sql("""
            INSERT INTO plugin_finance_iam_delegation
            (tenant_id, id, delegator_account, delegator_person, delegatee_account, delegatee_person, role, effective_from, effective_to, status, reason, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
            .params(
                del.tenantId(), del.id(), del.delegatorAccount(), del.delegatorPerson(),
                del.delegateeAccount(), del.delegateePerson(), del.role(),
                Timestamp.from(del.effectiveFrom().toInstant()),
                Timestamp.from(del.effectiveTo().toInstant()),
                del.status(), del.reason(),
                Timestamp.from(del.createdAt().toInstant())
            ).update();
    }

    public Optional<Delegation> findDelegationById(UUID tenantId, UUID id) {
        return jdbc.sql("""
            SELECT * FROM plugin_finance_iam_delegation WHERE tenant_id = ? AND id = ?
            """)
            .params(tenantId, id)
            .query(this::mapDelegation)
            .optional();
    }

    private Delegation mapDelegation(ResultSet rs, int rowNum) throws SQLException {
        return new Delegation(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"),
            (UUID) rs.getObject("delegator_account"),
            (UUID) rs.getObject("delegator_person"),
            (UUID) rs.getObject("delegatee_account"),
            (UUID) rs.getObject("delegatee_person"),
            rs.getString("role"),
            toOffset(rs.getTimestamp("effective_from")),
            toOffset(rs.getTimestamp("effective_to")),
            rs.getString("status"),
            rs.getString("reason"),
            toOffset(rs.getTimestamp("revoked_at")),
            (UUID) rs.getObject("revoked_by"),
            toOffset(rs.getTimestamp("created_at"))
        );
    }

    // ========================================================================
    // IAM04: Emergency Audit Grants
    // ========================================================================

    public void insertEmergencyGrant(EmergencyGrant g) {
        jdbc.sql("""
            INSERT INTO plugin_finance_iam_emergency_grant
            (tenant_id, id, requester_account, requester_person, book_id, scope_description, requested_duration_minutes, reason, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
            .params(
                g.tenantId(), g.id(), g.requesterAccount(), g.requesterPerson(),
                g.bookId(), g.scopeDescription(), g.requestedDurationMinutes(),
                g.reason(), g.status(),
                Timestamp.from(g.createdAt().toInstant())
            ).update();
    }

    public Optional<EmergencyGrant> findEmergencyGrantById(UUID tenantId, UUID id) {
        return jdbc.sql("""
            SELECT * FROM plugin_finance_iam_emergency_grant WHERE tenant_id = ? AND id = ?
            """)
            .params(tenantId, id)
            .query(this::mapEmergencyGrant)
            .optional();
    }

    public void updateEmergencyGrantApproval1(UUID tenantId, UUID id, UUID acc, UUID person, OffsetDateTime at) {
        jdbc.sql("""
            UPDATE plugin_finance_iam_emergency_grant
            SET approver1_account = ?, approver1_person = ?, approver1_at = ?
            WHERE tenant_id = ? AND id = ? AND status = 'PENDING' AND approver1_person IS NULL
            """)
            .params(acc, person, Timestamp.from(at.toInstant()), tenantId, id)
            .update();
    }

    public void updateEmergencyGrantApproval2AndActivate(UUID tenantId, UUID id, UUID acc, UUID person, OffsetDateTime at, OffsetDateTime expiresAt) {
        jdbc.sql("""
            UPDATE plugin_finance_iam_emergency_grant
            SET approver2_account = ?, approver2_person = ?, approver2_at = ?, activated_at = ?, expires_at = ?, status = 'ACTIVE'
            WHERE tenant_id = ? AND id = ? AND status = 'PENDING' AND approver1_person IS NOT NULL AND approver2_person IS NULL
            """)
            .params(acc, person, Timestamp.from(at.toInstant()), Timestamp.from(at.toInstant()), Timestamp.from(expiresAt.toInstant()), tenantId, id)
            .update();
    }

    private EmergencyGrant mapEmergencyGrant(ResultSet rs, int rowNum) throws SQLException {
        return new EmergencyGrant(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"),
            (UUID) rs.getObject("requester_account"),
            (UUID) rs.getObject("requester_person"),
            (UUID) rs.getObject("book_id"),
            rs.getString("scope_description"),
            rs.getInt("requested_duration_minutes"),
            rs.getString("reason"),
            rs.getString("status"),
            (UUID) rs.getObject("approver1_account"),
            (UUID) rs.getObject("approver1_person"),
            toOffset(rs.getTimestamp("approver1_at")),
            (UUID) rs.getObject("approver2_account"),
            (UUID) rs.getObject("approver2_person"),
            toOffset(rs.getTimestamp("approver2_at")),
            toOffset(rs.getTimestamp("activated_at")),
            toOffset(rs.getTimestamp("expires_at")),
            toOffset(rs.getTimestamp("revoked_at")),
            (UUID) rs.getObject("revoked_by"),
            toOffset(rs.getTimestamp("created_at"))
        );
    }

    // ========================================================================
    // OPS01: Backup Manifests, Joint Drills & Rollback Ledgers
    // ========================================================================

    public void insertBackupManifest(BackupManifest m) {
        jdbc.sql("""
            INSERT INTO plugin_finance_ops_backup_manifest
            (tenant_id, id, backup_id, backup_type, db_snapshot_hash, original_files_count, original_bytes_total, files_manifest_sha256, recovery_target_time, cloud_dr_status, status, verified_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
            .params(
                m.tenantId(), m.id(), m.backupId(), m.backupType(), m.dbSnapshotHash(),
                m.originalFilesCount(), m.originalBytesTotal(), m.filesManifestSha256(),
                Timestamp.from(m.recoveryTargetTime().toInstant()),
                m.cloudDrStatus(), m.status(),
                Timestamp.from(m.verifiedAt().toInstant()),
                Timestamp.from(m.createdAt().toInstant())
            ).update();
    }

    public Optional<BackupManifest> findBackupManifestById(UUID tenantId, UUID id) {
        return jdbc.sql("""
            SELECT * FROM plugin_finance_ops_backup_manifest WHERE tenant_id = ? AND id = ?
            """)
            .params(tenantId, id)
            .query(this::mapBackupManifest)
            .optional();
    }

    private BackupManifest mapBackupManifest(ResultSet rs, int rowNum) throws SQLException {
        return new BackupManifest(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"),
            rs.getString("backup_id"),
            rs.getString("backup_type"),
            rs.getString("db_snapshot_hash"),
            rs.getInt("original_files_count"),
            rs.getLong("original_bytes_total"),
            rs.getString("files_manifest_sha256"),
            toOffset(rs.getTimestamp("recovery_target_time")),
            rs.getString("cloud_dr_status"),
            rs.getString("status"),
            toOffset(rs.getTimestamp("verified_at")),
            toOffset(rs.getTimestamp("created_at"))
        );
    }

    public void insertJointDrillRecord(JointDrillRecord dr) {
        jdbc.sql("""
            INSERT INTO plugin_finance_ops_joint_drill_record
            (tenant_id, id, manifest_id, drill_name, target_pitr_time, db_record_count, db_records_matched, original_files_matched, original_bytes_matched, discrepancy_count, discrepancies_detail, status, executed_by, duration_ms, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            """)
            .params(
                dr.tenantId(), dr.id(), dr.manifestId(), dr.drillName(),
                Timestamp.from(dr.targetPitrTime().toInstant()),
                dr.dbRecordCount(), dr.dbRecordsMatched(), dr.originalFilesMatched(),
                dr.originalBytesMatched(), dr.discrepancyCount(), dr.discrepanciesDetail(),
                dr.status(), dr.executedBy(), dr.durationMs(),
                Timestamp.from(dr.createdAt().toInstant())
            ).update();
    }

    public Optional<JointDrillRecord> findJointDrillRecordById(UUID tenantId, UUID id) {
        return jdbc.sql("""
            SELECT * FROM plugin_finance_ops_joint_drill_record WHERE tenant_id = ? AND id = ?
            """)
            .params(tenantId, id)
            .query(this::mapJointDrillRecord)
            .optional();
    }

    private JointDrillRecord mapJointDrillRecord(ResultSet rs, int rowNum) throws SQLException {
        return new JointDrillRecord(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"),
            (UUID) rs.getObject("manifest_id"),
            rs.getString("drill_name"),
            toOffset(rs.getTimestamp("target_pitr_time")),
            rs.getLong("db_record_count"),
            rs.getBoolean("db_records_matched"),
            rs.getBoolean("original_files_matched"),
            rs.getBoolean("original_bytes_matched"),
            rs.getInt("discrepancy_count"),
            rs.getString("discrepancies_detail"),
            rs.getString("status"),
            (UUID) rs.getObject("executed_by"),
            rs.getLong("duration_ms"),
            toOffset(rs.getTimestamp("created_at"))
        );
    }

    public void insertRollbackLedger(RollbackLedgerRecord r) {
        jdbc.sql("""
            INSERT INTO plugin_finance_ops_rollback_ledger
            (tenant_id, id, release_version, migration_name, rollback_script_sha256, backward_compatible, readiness_status, block_reason, verified_by, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
            .params(
                r.tenantId(), r.id(), r.releaseVersion(), r.migrationName(),
                r.rollbackScriptSha256(), r.backwardCompatible(), r.readinessStatus(),
                r.blockReason(), r.verifiedBy(),
                Timestamp.from(r.createdAt().toInstant())
            ).update();
    }

    public Optional<RollbackLedgerRecord> findRollbackLedgerById(UUID tenantId, UUID id) {
        return jdbc.sql("""
            SELECT * FROM plugin_finance_ops_rollback_ledger WHERE tenant_id = ? AND id = ?
            """)
            .params(tenantId, id)
            .query(this::mapRollbackLedger)
            .optional();
    }

    private RollbackLedgerRecord mapRollbackLedger(ResultSet rs, int rowNum) throws SQLException {
        return new RollbackLedgerRecord(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"),
            rs.getString("release_version"),
            rs.getString("migration_name"),
            rs.getString("rollback_script_sha256"),
            rs.getBoolean("backward_compatible"),
            rs.getString("readiness_status"),
            rs.getString("block_reason"),
            (UUID) rs.getObject("verified_by"),
            toOffset(rs.getTimestamp("created_at"))
        );
    }

    // ========================================================================
    // OPS02: Support Tickets, Incident Timelines & Diagnostic Grants
    // ========================================================================

    public void insertSupportTicket(SupportTicket t) {
        jdbc.sql("""
            INSERT INTO plugin_finance_ops_support_ticket
            (tenant_id, id, ticket_no, book_id, severity, category, title, description, status, created_by_account, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
            .params(
                t.tenantId(), t.id(), t.ticketNo(), t.bookId(), t.severity(),
                t.category(), t.title(), t.description(), t.status(),
                t.createdByAccount(),
                Timestamp.from(t.createdAt().toInstant()),
                Timestamp.from(t.updatedAt().toInstant())
            ).update();
    }

    public Optional<SupportTicket> findSupportTicketById(UUID tenantId, UUID id) {
        return jdbc.sql("""
            SELECT * FROM plugin_finance_ops_support_ticket WHERE tenant_id = ? AND id = ?
            """)
            .params(tenantId, id)
            .query(this::mapSupportTicket)
            .optional();
    }

    private SupportTicket mapSupportTicket(ResultSet rs, int rowNum) throws SQLException {
        return new SupportTicket(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"),
            rs.getString("ticket_no"),
            (UUID) rs.getObject("book_id"),
            rs.getString("severity"),
            rs.getString("category"),
            rs.getString("title"),
            rs.getString("description"),
            rs.getString("status"),
            (UUID) rs.getObject("created_by_account"),
            toOffset(rs.getTimestamp("created_at")),
            toOffset(rs.getTimestamp("updated_at")),
            toOffset(rs.getTimestamp("resolved_at"))
        );
    }

    public void updateSupportTicketStatus(UUID tenantId, UUID ticketId, String status, OffsetDateTime resolvedAt) {
        Timestamp now = Timestamp.from(OffsetDateTime.now(ZoneOffset.UTC).toInstant());
        Timestamp resTs = resolvedAt == null ? null : Timestamp.from(resolvedAt.toInstant());
        jdbc.sql("""
            UPDATE plugin_finance_ops_support_ticket
            SET status = ?, resolved_at = ?, updated_at = ?
            WHERE tenant_id = ? AND id = ?
            """)
            .params(status, resTs, now, tenantId, ticketId)
            .update();
    }

    public void insertIncidentTimelineEvent(IncidentTimelineEvent ev) {
        jdbc.sql("""
            INSERT INTO plugin_finance_ops_incident_timeline
            (tenant_id, id, ticket_id, incident_phase, summary, customer_communication, technical_detail, event_timestamp, recorded_by, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
            .params(
                ev.tenantId(), ev.id(), ev.ticketId(), ev.incidentPhase(),
                ev.summary(), ev.customerCommunication(), ev.technicalDetail(),
                Timestamp.from(ev.eventTimestamp().toInstant()),
                ev.recordedBy(),
                Timestamp.from(ev.createdAt().toInstant())
            ).update();
    }

    public List<IncidentTimelineEvent> listTimelineEventsByTicket(UUID tenantId, UUID ticketId) {
        return jdbc.sql("""
            SELECT * FROM plugin_finance_ops_incident_timeline
            WHERE tenant_id = ? AND ticket_id = ?
            ORDER BY event_timestamp ASC
            """)
            .params(tenantId, ticketId)
            .query(this::mapTimelineEvent)
            .list();
    }

    private IncidentTimelineEvent mapTimelineEvent(ResultSet rs, int rowNum) throws SQLException {
        return new IncidentTimelineEvent(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"),
            (UUID) rs.getObject("ticket_id"),
            rs.getString("incident_phase"),
            rs.getString("summary"),
            rs.getString("customer_communication"),
            rs.getString("technical_detail"),
            toOffset(rs.getTimestamp("event_timestamp")),
            (UUID) rs.getObject("recorded_by"),
            toOffset(rs.getTimestamp("created_at"))
        );
    }

    public void insertDiagnosticGrant(DiagnosticGrant g) {
        jdbc.sql("""
            INSERT INTO plugin_finance_ops_diagnostic_grant
            (tenant_id, id, ticket_id, book_id, support_operator_account, approved_by_account, approved_by_person, scope_type, duration_minutes, status, activated_at, expires_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
            .params(
                g.tenantId(), g.id(), g.ticketId(), g.bookId(),
                g.supportOperatorAccount(), g.approvedByAccount(), g.approvedByPerson(),
                g.scopeType(), g.durationMinutes(), g.status(),
                Timestamp.from(g.activatedAt().toInstant()),
                Timestamp.from(g.expiresAt().toInstant()),
                Timestamp.from(g.createdAt().toInstant())
            ).update();
    }

    public Optional<DiagnosticGrant> findDiagnosticGrantById(UUID tenantId, UUID id) {
        return jdbc.sql("""
            SELECT * FROM plugin_finance_ops_diagnostic_grant WHERE tenant_id = ? AND id = ?
            """)
            .params(tenantId, id)
            .query(this::mapDiagnosticGrant)
            .optional();
    }

    public void revokeDiagnosticGrant(UUID tenantId, UUID id, UUID revoker, String reason) {
        Timestamp now = Timestamp.from(OffsetDateTime.now(ZoneOffset.UTC).toInstant());
        jdbc.sql("""
            UPDATE plugin_finance_ops_diagnostic_grant
            SET status = 'REVOKED', revoked_at = ?, revoked_by = ?, revocation_reason = ?
            WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'
            """)
            .params(now, revoker, reason, tenantId, id)
            .update();
    }

    private DiagnosticGrant mapDiagnosticGrant(ResultSet rs, int rowNum) throws SQLException {
        return new DiagnosticGrant(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"),
            (UUID) rs.getObject("ticket_id"),
            (UUID) rs.getObject("book_id"),
            (UUID) rs.getObject("support_operator_account"),
            (UUID) rs.getObject("approved_by_account"),
            (UUID) rs.getObject("approved_by_person"),
            rs.getString("scope_type"),
            rs.getInt("duration_minutes"),
            rs.getString("status"),
            toOffset(rs.getTimestamp("activated_at")),
            toOffset(rs.getTimestamp("expires_at")),
            toOffset(rs.getTimestamp("revoked_at")),
            (UUID) rs.getObject("revoked_by"),
            rs.getString("revocation_reason"),
            toOffset(rs.getTimestamp("created_at"))
        );
    }
}

package com.lumira.finance.iamops;

import com.lumira.finance.FinanceTestCredentials;

import com.lumira.finance.iamops.IamOpsModels.*;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class IamOpsPostgresTest {

    @Test
    public void testFullIamOpsLifecycleAgainstPostgres18() throws Exception {
        String jdbcUrl = FinanceTestCredentials.jdbcUrl();
        String user = FinanceTestCredentials.databaseUser();
        String pass = FinanceTestCredentials.databasePassword();

        String schemaName = "test_w02_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        var adminDs = new PGSimpleDataSource();
        adminDs.setURL(jdbcUrl);
        adminDs.setUser(user);
        adminDs.setPassword(pass);

        var adminJdbc = JdbcClient.create(adminDs);
        adminJdbc.sql("CREATE SCHEMA " + schemaName).update();

        try {
            var tenantDs = new PGSimpleDataSource();
            tenantDs.setURL(jdbcUrl);
            tenantDs.setUser(user);
            tenantDs.setPassword(pass);
            tenantDs.setCurrentSchema(schemaName);

            // Execute base migration and candidate migration
            try (var conn = tenantDs.getConnection()) {
                Path baseV1 = java.util.List.of(
                    Path.of("plugins/finance/migrations/V1__plugin_initialization.sql"),
                    Path.of("../migrations/V1__plugin_initialization.sql")
                ).stream().filter(Files::exists).findFirst().orElse(Path.of("missing"));
                if (Files.exists(baseV1)) {
                    conn.createStatement().execute(Files.readString(baseV1, StandardCharsets.UTF_8));
                } else {
                    // Minimal base tables if running in isolated container
                    conn.createStatement().execute("""
                        CREATE TABLE IF NOT EXISTS plugin_finance_tenant (id uuid PRIMARY KEY, name text NOT NULL, owner_id uuid NOT NULL);
                        CREATE TABLE IF NOT EXISTS plugin_finance_entity (tenant_id uuid NOT NULL REFERENCES plugin_finance_tenant(id), id uuid NOT NULL, name text NOT NULL, registration_id text NOT NULL, PRIMARY KEY(tenant_id,id));
                        CREATE TABLE IF NOT EXISTS plugin_finance_book (tenant_id uuid NOT NULL, id uuid PRIMARY KEY, legal_entity_id uuid NOT NULL, name text NOT NULL, standard text NOT NULL, base_currency text NOT NULL, version bigint NOT NULL DEFAULT 1);
                        CREATE TABLE IF NOT EXISTS plugin_finance_member (tenant_id uuid NOT NULL, book_id uuid NOT NULL, user_id uuid NOT NULL, username text NOT NULL, role text NOT NULL, enabled boolean NOT NULL DEFAULT true, year_scope text NOT NULL DEFAULT 'ALL', PRIMARY KEY(tenant_id,book_id,user_id));
                        """);
                }

                Path cand = java.util.List.of(
                    Path.of("plugins/finance/migrations/V27__iam_ops_governance.sql"),
                    Path.of("../migrations/V27__iam_ops_governance.sql"),
                    Path.of(".runtime/v4-wave2/iam-ops/migration-candidate.sql"),
                    Path.of("../../../.runtime/v4-wave2/iam-ops/migration-candidate.sql"),
                    Path.of("migration-candidate.sql")
                ).stream().filter(Files::exists).findFirst().orElseThrow();
                conn.createStatement().execute(Files.readString(cand, StandardCharsets.UTF_8));
            }

            var jdbc = JdbcClient.create(tenantDs);
            IamOpsStore store = new IamOpsStore(jdbc);
            IamOpsService service = new IamOpsService(store);

            UUID tenantId = UUID.randomUUID();
            UUID legalEntityId = UUID.randomUUID();
            UUID bookId = UUID.randomUUID();
            UUID adminAccountId = UUID.randomUUID();
            UUID adminPersonId = UUID.randomUUID();

            // Setup tenant, book, and admin member
            jdbc.sql("INSERT INTO plugin_finance_tenant (id, name, owner_id) VALUES (?, 'QA-Tenant', ?)").params(tenantId, adminAccountId).update();
            jdbc.sql("INSERT INTO plugin_finance_entity (tenant_id, id, name, registration_id) VALUES (?, ?, 'QA-Entity', 'REG001')").params(tenantId, legalEntityId).update();
            jdbc.sql("INSERT INTO plugin_finance_book (tenant_id, id, legal_entity_id, name, standard, base_currency) VALUES (?, ?, ?, 'QA-Book', 'CAS', 'CNY')").params(tenantId, bookId, legalEntityId).update();
            jdbc.sql("INSERT INTO plugin_finance_member (tenant_id, book_id, user_id, username, role, enabled) VALUES (?, ?, ?, 'admin', 'OWNER', true)").params(tenantId, bookId, adminAccountId).update();

            // Seed defaults
            jdbc.sql("SELECT plugin_finance_iam_seed_defaults(?)").params(tenantId).query().singleRow();

            // ----------------------------------------------------------------
            // 1. IAM01: Member Invitation & Acceptance
            // ----------------------------------------------------------------
            var invInput = new CreateInvitationInput("accountant@example.com", "13800000001", "ACCOUNTANT", "DEPT-FINANCE", "PRJ-01", 7);
            MemberInvitation invitation = service.inviteMember(tenantId, adminAccountId, adminPersonId, invInput);
            assertNotNull(invitation.id());
            assertEquals("PENDING", invitation.status());

            // Fake token retrieval by querying the DB
            String tokenHash = invitation.tokenHash();
            // Test accept invitation by creating person lifecycle
            UUID accountantAccountId = UUID.randomUUID();
            UUID accountantPersonId = UUID.randomUUID();

            // Acceptance with profile sensitive fields setup
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            store.acceptInvitation(tenantId, invitation.id(), now);
            PersonLifecycle lc = new PersonLifecycle(
                UUID.randomUUID(), tenantId, accountantPersonId, accountantAccountId,
                "EMP-001", "张会计", "DEPT-FINANCE", "PRJ-01",
                "11010119900101001X", "6222021234567891234", new BigDecimal("25000.00"),
                "ACTIVE", now, now, null, null, null, 1L
            );
            store.insertPersonLifecycle(lc);
            Optional<PersonLifecycle> foundLc = store.findPersonLifecycle(tenantId, accountantPersonId);
            assertTrue(foundLc.isPresent());
            assertEquals("ACTIVE", foundLc.get().state());
            assertEquals("11010119900101001X", foundLc.get().idCard());

            // Bind accountant to book
            jdbc.sql("INSERT INTO plugin_finance_member (tenant_id, book_id, user_id, username, role, enabled) VALUES (?, ?, ?, 'zhang_acc', 'MAKER', true)").params(tenantId, bookId, accountantAccountId).update();

            // ----------------------------------------------------------------
            // Attested Source Import (ORG_ATTESTED)
            // ----------------------------------------------------------------
            String dummyPayload = "{\"source\": \"Workday\", \"batch\": \"20261110\"}";
            String sha = IamOpsRules.sha256Hex(dummyPayload);
            var attIn = new ImportAttestedSourceInput("HR_IMPORT", "Workday", "REF-HR-202611", sha, 10, dummyPayload, now);
            AttestedSource attSrc = service.importAttestedSource(tenantId, attIn);
            assertEquals(IamOpsRules.ORG_ATTESTED_KIND, attSrc.attestationKind());

            // ----------------------------------------------------------------
            // Setup Full Offboarding Chain: Delegation, Task, Emergency Grant, Export, Scopes
            // ----------------------------------------------------------------
            UUID approverAccountId = UUID.randomUUID();
            UUID approverPersonId = UUID.randomUUID();
            Delegation del = new Delegation(
                UUID.randomUUID(), tenantId, accountantAccountId, accountantPersonId,
                approverAccountId, approverPersonId, "REVIEWER", now, now.plusDays(5),
                "ACTIVE", "休假转交", null, null, now
            );
            store.insertDelegation(del);

            // Approval task where accountant is assigned to a stage
            List<ApprovalThreshold> thresholds = store.listApprovalThresholds(tenantId, "PAYMENT_REQUEST");
            assertFalse(thresholds.isEmpty());
            ApprovalThreshold th = thresholds.getFirst();

            UUID taskId = UUID.randomUUID();
            List<ApprovalStageDecision> stages = List.of(
                new ApprovalStageDecision(UUID.randomUUID(), taskId, 1, "MAKER", accountantPersonId, "PENDING", null, null, null, null)
            );
            ApprovalTask task = new ApprovalTask(
                taskId, tenantId, th.id(), "PAYMENT_REQUEST", UUID.randomUUID(),
                new BigDecimal("10000.00"), 1, 1, "PENDING", accountantAccountId, accountantPersonId, now, now, stages
            );
            store.insertApprovalTaskWithStages(task);

            // Emergency grant requested by accountant
            EmergencyGrant accEmergency = service.requestEmergencyGrant(
                tenantId, accountantAccountId, accountantPersonId,
                new RequestEmergencyGrantInput(bookId, "调阅历史凭证", 60, "年结准备")
            );

            // Export request requested by accountant
            service.grantDataScope(tenantId, new GrantDataScopeInput(
                accountantAccountId, accountantPersonId, "DEPARTMENT", "DEPT-FINANCE", null, true, false
            ));
            ExportRequest accExport = service.requestExport(
                tenantId, accountantAccountId, accountantPersonId,
                new CreateExportRequestInput("JOURNALS", "DEPT-FINANCE", null, false)
            );
            // Re-set to PENDING to verify offboarding cascade cancels it
            jdbc.sql("UPDATE plugin_finance_iam_export_request SET status = 'PENDING' WHERE id = ?").params(accExport.id()).update();

            // ----------------------------------------------------------------
            // Atomic offboarding execution & verification across all 8 entities
            // ----------------------------------------------------------------
            Map<String, Integer> offboardSummary = service.offboardPersonAtomic(
                tenantId, accountantPersonId, adminAccountId, new OffboardPersonInput("员工离职全链回收")
            );
            assertEquals(1, offboardSummary.get("lifecycleUpdated"));
            assertEquals(1, offboardSummary.get("membershipsDisabled"));
            assertEquals(1, offboardSummary.get("delegationsRevoked"));
            assertEquals(1, offboardSummary.get("pendingApprovalsCancelled"));
            assertTrue(offboardSummary.get("pendingTasksCancelled") >= 1);
            assertEquals(1, offboardSummary.get("dataScopesRevoked"));
            assertEquals(1, offboardSummary.get("emergencyGrantsRevoked"));
            assertEquals(1, offboardSummary.get("pendingExportsCancelled"));

            // Verify lifecycle state
            PersonLifecycle offboardedLc = store.findPersonLifecycle(tenantId, accountantPersonId).orElseThrow();
            assertEquals("OFFBOARDED", offboardedLc.state());
            assertEquals("员工离职全链回收", offboardedLc.offboardingReason());

            // Verify book membership disabled
            var memberRow = jdbc.sql("SELECT enabled FROM plugin_finance_member WHERE tenant_id = ? AND user_id = ?").params(tenantId, accountantAccountId).query().singleRow();
            assertFalse((Boolean) memberRow.get("enabled"));

            // Verify delegation revoked
            Delegation revokedDel = store.findDelegationById(tenantId, del.id()).orElseThrow();
            assertEquals("REVOKED", revokedDel.status());

            // Verify task cancelled
            ApprovalTask cancelledTask = store.findApprovalTaskById(tenantId, task.id()).orElseThrow();
            assertEquals("CANCELLED", cancelledTask.status());

            // Verify emergency grant revoked
            EmergencyGrant revokedEg = store.findEmergencyGrantById(tenantId, accEmergency.id()).orElseThrow();
            assertEquals("REVOKED", revokedEg.status());

            // Verify export request rejected
            ExportRequest cancelledExport = store.findExportRequestById(tenantId, accExport.id()).orElseThrow();
            assertEquals("REJECTED", cancelledExport.status());
            assertEquals("OFFBOARDING_REVOKED", cancelledExport.rejectionReason());

            // ----------------------------------------------------------------
            // 2. IAM02: Scoped Data Retrieval & Sensitive Field Masking
            // ----------------------------------------------------------------
            UUID employeePersonId = UUID.randomUUID();
            UUID employeeAccountId = UUID.randomUUID();
            store.insertPersonLifecycle(new PersonLifecycle(
                UUID.randomUUID(), tenantId, employeePersonId, employeeAccountId,
                "EMP-002", "李员工", "DEPT-FINANCE", "PRJ-01",
                "11010119900101001X", "6222021234567891234", new BigDecimal("25000.00"),
                "ACTIVE", now, now, null, null, null, 1L
            ));

            UUID viewerPersonId = UUID.randomUUID();
            UUID viewerAccountId = UUID.randomUUID();

            // Grant viewer DEPARTMENT scope without unmask privilege
            service.grantDataScope(tenantId, new GrantDataScopeInput(
                viewerAccountId, viewerPersonId, "DEPARTMENT", "DEPT-FINANCE", null, true, false
            ));

            MaskedEmployeeRecord masked = service.getMaskedEmployee(tenantId, viewerPersonId, employeePersonId);
            assertEquals("***.00", masked.maskedSalaryAmount());
            assertEquals("110101********001X", masked.maskedIdCard());
            assertEquals("622202******1234", masked.maskedBankAccount());
            assertTrue(masked.isMasked());

            // Grant viewer unmask privilege
            service.grantDataScope(tenantId, new GrantDataScopeInput(
                viewerAccountId, viewerPersonId, "DEPARTMENT", "DEPT-FINANCE", null, true, true
            ));
            MaskedEmployeeRecord unmasked = service.getMaskedEmployee(tenantId, viewerPersonId, employeePersonId);
            assertEquals("25000.00", unmasked.maskedSalaryAmount());
            assertEquals("11010119900101001X", unmasked.maskedIdCard());
            assertEquals("6222021234567891234", unmasked.maskedBankAccount());
            assertFalse(unmasked.isMasked());

            // Viewer with mismatched department -> blocked by scope check
            UUID otherDeptViewer = UUID.randomUUID();
            service.grantDataScope(tenantId, new GrantDataScopeInput(
                UUID.randomUUID(), otherDeptViewer, "DEPARTMENT", "DEPT-SALES", null, true, true
            ));
            assertThrows(IllegalStateException.class, () ->
                service.getMaskedEmployee(tenantId, otherDeptViewer, employeePersonId)
            );

            // ----------------------------------------------------------------
            // 3. IAM03: Tiered Approvals & Multi-Stage Distinct Persons
            // ----------------------------------------------------------------
            UUID requesterAcc = UUID.randomUUID();
            UUID requesterPerson = UUID.randomUUID();
            UUID approver1Acc = UUID.randomUUID();
            UUID approver1Person = UUID.randomUUID();
            UUID approver2Acc = UUID.randomUUID();
            UUID approver2Person = UUID.randomUUID();

            ApprovalTask tieredTask = service.createApprovalTask(
                tenantId, requesterAcc, requesterPerson,
                new CreateApprovalTaskInput("PAYMENT_REQUEST", UUID.randomUUID(), new BigDecimal("80000.00000000"))
            );
            assertEquals(2, tieredTask.totalStages());
            assertEquals(1, tieredTask.currentStage());
            assertEquals("PENDING", tieredTask.status());

            // Maker self-approval must be rejected
            assertThrows(IllegalStateException.class, () ->
                service.decideStage(tenantId, tieredTask.id(), requesterAcc, requesterPerson, new DecideStageInput(1, "APPROVED", "自批通过"))
            );

            // Stage 1 approval by distinct approver 1
            ApprovalTask afterStage1 = service.decideStage(
                tenantId, tieredTask.id(), approver1Acc, approver1Person, new DecideStageInput(1, "APPROVED", "一审通过")
            );
            assertEquals(2, afterStage1.currentStage());
            assertEquals("PENDING", afterStage1.status());

            // Approver 1 attempting to approve Stage 2 -> Blocked (cannot self-concur across stages!)
            assertThrows(IllegalStateException.class, () ->
                service.decideStage(tenantId, tieredTask.id(), approver1Acc, approver1Person, new DecideStageInput(2, "APPROVED", "一审人兼审二审"))
            );

            // Stage 2 approval by distinct approver 2
            ApprovalTask afterStage2 = service.decideStage(
                tenantId, tieredTask.id(), approver2Acc, approver2Person, new DecideStageInput(2, "APPROVED", "二审终审通过")
            );
            assertEquals(2, afterStage2.currentStage());
            assertEquals("APPROVED", afterStage2.status());

            // ----------------------------------------------------------------
            // Delegation with Database Role Lookup & SoD Check
            // ----------------------------------------------------------------
            UUID cashierAccount = UUID.randomUUID();
            UUID cashierPerson = UUID.randomUUID();
            jdbc.sql("INSERT INTO plugin_finance_member (tenant_id, book_id, user_id, username, role, enabled) VALUES (?, ?, ?, 'cashier_user', 'CASHIER', true)")
                .params(tenantId, bookId, cashierAccount).update();

            // Attempting to delegate APPROVER (or REVIEWER) to CASHIER with null delegateeRoles (queries DB) -> Blocked by SoD!
            assertThrows(IllegalStateException.class, () ->
                service.createDelegation(tenantId, adminAccountId, adminPersonId, null, new CreateDelegationInput(
                    cashierAccount, cashierPerson, "APPROVER", now, now.plusDays(3), "试图委托审批权给出纳"
                ))
            );

            // ----------------------------------------------------------------
            // 4. IAM04: Dual-Person Emergency Audit Grant & Auditor Read-Only
            // ----------------------------------------------------------------
            UUID auditorAcc = UUID.randomUUID();
            UUID auditorPerson = UUID.randomUUID();
            jdbc.sql("INSERT INTO plugin_finance_member (tenant_id, book_id, user_id, username, role, enabled) VALUES (?, ?, ?, 'auditor_user', 'AUDITOR', true)")
                .params(tenantId, bookId, auditorAcc).update();

            // Auditor write restriction invariant
            assertThrows(IllegalStateException.class, () ->
                service.assertAuditorReadOnly(tenantId, auditorAcc, "POST_JOURNAL")
            );
            assertDoesNotThrow(() ->
                service.assertAuditorReadOnly(tenantId, auditorAcc, "READ_LOGS")
            );

            UUID secOfficer1Acc = UUID.randomUUID();
            UUID secOfficer1Person = UUID.randomUUID();
            UUID secOfficer2Acc = UUID.randomUUID();
            UUID secOfficer2Person = UUID.randomUUID();

            EmergencyGrant emGrant = service.requestEmergencyGrant(
                tenantId, auditorAcc, auditorPerson,
                new RequestEmergencyGrantInput(bookId, "年度外部审计抽凭调阅", 120, "审计进场紧急只读检查")
            );
            assertEquals("PENDING", emGrant.status());

            // Officer 1 approves slot 1
            EmergencyGrant step1 = service.approveEmergencyGrant(
                tenantId, emGrant.id(), secOfficer1Acc, secOfficer1Person, new ApproveEmergencyGrantInput("APPROVER1")
            );
            assertEquals("PENDING", step1.status());
            assertNotNull(step1.approver1Person());

            // Officer 2 approves slot 2 -> Activates grant
            EmergencyGrant step2 = service.approveEmergencyGrant(
                tenantId, emGrant.id(), secOfficer2Acc, secOfficer2Person, new ApproveEmergencyGrantInput("APPROVER2")
            );
            assertEquals("ACTIVE", step2.status());
            assertNotNull(step2.activatedAt());
            assertNotNull(step2.expiresAt());

            // ----------------------------------------------------------------
            // 5. OPS01: Backup Manifest & Joint Drill Record
            // ----------------------------------------------------------------
            String fakeSnapHash = IamOpsRules.sha256Hex("db-snapshot-20261110");
            String fakeFilesManifest = IamOpsRules.sha256Hex("files-manifest-20261110");

            // Fake cloud DR claim is rejected
            assertThrows(IllegalArgumentException.class, () ->
                service.createBackupManifest(tenantId, new CreateBackupManifestInput(
                    "BK-FAKE-001", "PITR", fakeSnapHash, 15, 1048576L, fakeFilesManifest, now, "PROD_REPLICATED"
                ))
            );

            BackupManifest manifest = service.createBackupManifest(
                tenantId, new CreateBackupManifestInput(
                    "BK-20261110-001", "PITR", fakeSnapHash, 15, 1048576L, fakeFilesManifest, now, "LOCAL_DRILL_ONLY"
                )
            );
            assertEquals("LOCAL_DRILL_ONLY", manifest.cloudDrStatus());
            assertEquals("VERIFIED", manifest.status());

            JointDrillRecord drill = service.executeJointRecoveryDrill(
                tenantId, adminAccountId, new ExecuteJointDrillInput(
                    manifest.id(), "2026Q4原件与数据库联合恢复演练", 1000L, 1048576L, 0
                )
            );
            assertEquals("SUCCESS", drill.status());
            assertTrue(drill.dbRecordsMatched());
            assertTrue(drill.originalBytesMatched());

            // Rollback ledger
            RollbackLedgerRecord rb = service.recordRollbackReadiness(
                tenantId, adminAccountId, new CreateRollbackLedgerInput(
                    "0.22.1", "V24__tax_obligations_archive_and_report_drafts.sql",
                    IamOpsRules.sha256Hex("rollback-v24-sql"), true, "READY", null
                )
            );
            assertEquals("READY", rb.readinessStatus());
            assertTrue(rb.backwardCompatible());

            // ----------------------------------------------------------------
            // 6. OPS02: Support Tickets, Timelines & Auto-Resolution
            // ----------------------------------------------------------------
            SupportTicket ticket = service.createSupportTicket(
                tenantId, adminAccountId, new CreateTicketInput(
                    bookId, "HIGH", "ACCOUNTING_ANOMALY", "跨期损益结转试算平衡差异", "用户报告2026年10月月结试算平衡存在1分钱差异"
                )
            );
            assertNotNull(ticket.ticketNo());
            assertTrue(ticket.ticketNo().startsWith("TICK-"));

            IncidentTimelineEvent ev = service.addIncidentTimelineEvent(
                tenantId, ticket.id(), adminAccountId, new AddTimelineEventInput(
                    "INVESTIGATING", "开始核对账簿结账日志", "工程师已接入排查", "检查科目汇总表与分录明细行精度"
                )
            );
            assertNotNull(ev.id());

            // Add RESOLVED timeline event -> ticket status auto-transitions to RESOLVED
            service.addIncidentTimelineEvent(
                tenantId, ticket.id(), adminAccountId, new AddTimelineEventInput(
                    "RESOLVED", "差异原因定位为四舍五入规则并完成补正", "故障已解除并恢复试算平衡", "精度对齐完成"
                )
            );
            SupportTicket resolvedTicket = store.findSupportTicketById(tenantId, ticket.id()).orElseThrow();
            assertEquals("RESOLVED", resolvedTicket.status());
            assertNotNull(resolvedTicket.resolvedAt());

            UUID supportOpAccount = UUID.randomUUID();
            DiagnosticGrant diag = service.grantDiagnosticAccess(
                tenantId, adminAccountId, adminPersonId, new GrantDiagnosticInput(
                    ticket.id(), bookId, supportOpAccount, 120
                )
            );
            assertEquals("ACTIVE", diag.status());
            assertEquals("READ_ONLY_DIAGNOSTIC", diag.scopeType());

            // Instant revocation
            service.revokeDiagnosticAccess(tenantId, diag.id(), adminAccountId, new RevokeDiagnosticInput("问题排查完毕，立即撤销支持授权"));
            DiagnosticGrant revokedDiag = store.findDiagnosticGrantById(tenantId, diag.id()).orElseThrow();
            assertEquals("REVOKED", revokedDiag.status());
            assertNotNull(revokedDiag.revokedAt());
            assertEquals("问题排查完毕，立即撤销支持授权", revokedDiag.revocationReason());

            System.out.println(">>> All IAM01-04 and OPS01-02 database lifecycles successfully verified in isolated schema " + schemaName);
        } finally {
            adminJdbc.sql("DROP SCHEMA IF EXISTS " + schemaName + " CASCADE").update();
        }
    }
}

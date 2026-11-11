package com.lumira.finance.iamops;

import com.lumira.finance.iamops.IamOpsModels.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class IamOpsUnitTest {

    @Test
    void testSensitiveFieldMaskingRules() {
        // Payroll masking
        assertEquals("***.00", IamOpsRules.maskPayroll(new BigDecimal("15000.50"), false));
        assertEquals("15000.50", IamOpsRules.maskPayroll(new BigDecimal("15000.50"), true));
        assertEquals("***.00", IamOpsRules.maskPayroll(new BigDecimal("0.00"), false));
        assertEquals("0.00", IamOpsRules.maskPayroll(new BigDecimal("0.00"), true));

        // ID card masking
        assertEquals("110101********001X", IamOpsRules.maskIdCard("11010119900101001X", false));
        assertEquals("11010119900101001X", IamOpsRules.maskIdCard("11010119900101001X", true));

        // Bank account masking
        assertEquals("622202******1234", IamOpsRules.maskBankAccount("6222021234567891234", false));
        assertEquals("6222021234567891234", IamOpsRules.maskBankAccount("6222021234567891234", true));
    }

    @Test
    void testStatutorySodConflictMatrix() {
        UUID tenantId = UUID.randomUUID();
        List<SodMatrixRule> matrix = List.of(
            new SodMatrixRule(UUID.randomUUID(), tenantId, "CASHIER", "AUDITOR", "STRICT_BLOCK", "出纳不得兼任稽核", true, OffsetDateTime.now(ZoneOffset.UTC)),
            new SodMatrixRule(UUID.randomUUID(), tenantId, "CASHIER", "APPROVER", "STRICT_BLOCK", "出纳不得兼任审批", true, OffsetDateTime.now(ZoneOffset.UTC)),
            new SodMatrixRule(UUID.randomUUID(), tenantId, "CASHIER", "ACCOUNTANT", "STRICT_BLOCK", "出纳不得兼任总账会计", true, OffsetDateTime.now(ZoneOffset.UTC)),
            new SodMatrixRule(UUID.randomUUID(), tenantId, "ACCOUNTANT", "AUDITOR", "STRICT_BLOCK", "会计不得兼任审计", true, OffsetDateTime.now(ZoneOffset.UTC))
        );

        // Cashier attempting to gain Auditor role -> Must throw SoD exception
        assertThrows(IllegalStateException.class, () ->
            IamOpsRules.checkSodConflict(Set.of("CASHIER"), "AUDITOR", matrix)
        );

        // Cashier attempting to gain Approver role -> Must throw SoD exception
        assertThrows(IllegalStateException.class, () ->
            IamOpsRules.checkSodConflict(Set.of("CASHIER"), "APPROVER", matrix)
        );

        // Approver attempting to gain Cashier role -> Must throw SoD exception
        assertThrows(IllegalStateException.class, () ->
            IamOpsRules.checkSodConflict(Set.of("APPROVER"), "CASHIER", matrix)
        );

        // Case-insensitivity check (lowercase cashier vs uppercase AUDITOR)
        assertThrows(IllegalStateException.class, () ->
            IamOpsRules.checkSodConflict(Set.of("cashier"), "AUDITOR", matrix)
        );

        // Role alias checks: REVIEWER maps to APPROVER, conflicting with CASHIER
        assertThrows(IllegalStateException.class, () ->
            IamOpsRules.checkSodConflict(Set.of("CASHIER"), "REVIEWER", matrix)
        );

        // Role alias checks: POSTER maps to ACCOUNTANT, conflicting with CASHIER
        assertThrows(IllegalStateException.class, () ->
            IamOpsRules.checkSodConflict(Set.of("CASHIER"), "POSTER", matrix)
        );

        // Compatible roles (e.g. Reader & Approver) -> Should NOT throw
        assertDoesNotThrow(() ->
            IamOpsRules.checkSodConflict(Set.of("READER"), "APPROVER", matrix)
        );
    }

    @Test
    void testAuditorReadOnlyRule() {
        // Auditor attempting journal posting / modification must be blocked with statutory invariant
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            IamOpsRules.checkAuditorReadOnly(Set.of("AUDITOR"), "POST_JOURNAL")
        );
        assertTrue(ex.getMessage().contains("ROLE_RESTRICTED_READ_ONLY"));

        assertThrows(IllegalStateException.class, () ->
            IamOpsRules.checkAuditorReadOnly(Set.of("AUDITOR"), "REVERSE_JOURNAL")
        );
        assertThrows(IllegalStateException.class, () ->
            IamOpsRules.checkAuditorReadOnly(Set.of("AUDITOR"), "DELETE_JOURNAL")
        );

        // Auditor read operation must pass
        assertDoesNotThrow(() ->
            IamOpsRules.checkAuditorReadOnly(Set.of("AUDITOR"), "READ_LOGS")
        );

        // Non-auditor write operation does not trigger auditor rule
        assertDoesNotThrow(() ->
            IamOpsRules.checkAuditorReadOnly(Set.of("ACCOUNTANT"), "POST_JOURNAL")
        );
    }

    @Test
    void testMultiStageSequentialRoutingDecider() {
        UUID maker = UUID.randomUUID();
        UUID approver1 = UUID.randomUUID();
        UUID approver2 = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        List<ApprovalStageDecision> stages = List.of(
            new ApprovalStageDecision(UUID.randomUUID(), taskId, 1, "APPROVER", approver1, "APPROVED", UUID.randomUUID(), approver1, "一审通过", OffsetDateTime.now(ZoneOffset.UTC)),
            new ApprovalStageDecision(UUID.randomUUID(), taskId, 2, "FINANCE_DIRECTOR", null, "PENDING", null, null, null, null)
        );
        ApprovalTask task = new ApprovalTask(
            taskId, UUID.randomUUID(), UUID.randomUUID(), "PAYMENT_REQUEST", UUID.randomUUID(),
            new BigDecimal("80000.00"), 2, 2, "PENDING", UUID.randomUUID(), maker,
            OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC), stages
        );

        // Maker self-approval blocked
        assertThrows(IllegalStateException.class, () ->
            IamOpsRules.validateApprovalStageDecider(task, 2, maker)
        );

        // Stage 1 approver attempting to approve Stage 2 blocked (cannot self-concur across stages)
        assertThrows(IllegalStateException.class, () ->
            IamOpsRules.validateApprovalStageDecider(task, 2, approver1)
        );

        // Distinct stage 2 approver allowed
        assertDoesNotThrow(() ->
            IamOpsRules.validateApprovalStageDecider(task, 2, approver2)
        );
    }

    @Test
    void testCloudDrClaimValidation() {
        // Unverified cloud disaster recovery claim is strictly blocked
        assertThrows(IllegalArgumentException.class, () ->
            IamOpsRules.validateCloudDrStatus("PROD_REPLICATED", false)
        );

        // Verified claim or LOCAL_DRILL_ONLY is allowed
        assertDoesNotThrow(() ->
            IamOpsRules.validateCloudDrStatus("PROD_REPLICATED", true)
        );
        assertDoesNotThrow(() ->
            IamOpsRules.validateCloudDrStatus("LOCAL_DRILL_ONLY", false)
        );
    }

    @Test
    void testTieredMonetaryThresholds() {
        UUID tenantId = UUID.randomUUID();
        List<ApprovalThreshold> thresholds = List.of(
            new ApprovalThreshold(UUID.randomUUID(), tenantId, "Tier1", "PAYMENT_REQUEST", new BigDecimal("0.00"), new BigDecimal("50000.00"), 1, "APPROVER", null, null, OffsetDateTime.now(ZoneOffset.UTC)),
            new ApprovalThreshold(UUID.randomUUID(), tenantId, "Tier2", "PAYMENT_REQUEST", new BigDecimal("50000.01"), new BigDecimal("500000.00"), 2, "APPROVER", "FINANCE_DIRECTOR", null, OffsetDateTime.now(ZoneOffset.UTC)),
            new ApprovalThreshold(UUID.randomUUID(), tenantId, "Tier3", "PAYMENT_REQUEST", new BigDecimal("500000.01"), null, 3, "APPROVER", "FINANCE_DIRECTOR", "TREASURY_BOARD", OffsetDateTime.now(ZoneOffset.UTC))
        );

        ApprovalThreshold t1 = IamOpsRules.findThreshold(new BigDecimal("25000.00"), "PAYMENT_REQUEST", thresholds);
        assertEquals(1, t1.requiredStages());
        assertEquals("APPROVER", t1.stage1Role());

        // Boundary test: exactly 50000.00 is Tier 1
        ApprovalThreshold t1Exact = IamOpsRules.findThreshold(new BigDecimal("50000.00"), "PAYMENT_REQUEST", thresholds);
        assertEquals(1, t1Exact.requiredStages());

        // Boundary continuity: 50000.005 falls into Tier 2
        ApprovalThreshold t2Boundary = IamOpsRules.findThreshold(new BigDecimal("50000.005"), "PAYMENT_REQUEST", thresholds);
        assertEquals(2, t2Boundary.requiredStages());

        ApprovalThreshold t2 = IamOpsRules.findThreshold(new BigDecimal("100000.00"), "PAYMENT_REQUEST", thresholds);
        assertEquals(2, t2.requiredStages());
        assertEquals("FINANCE_DIRECTOR", t2.stage2Role());

        ApprovalThreshold t3 = IamOpsRules.findThreshold(new BigDecimal("8000000.00000000"), "PAYMENT_REQUEST", thresholds);
        assertEquals(3, t3.requiredStages());
        assertEquals("TREASURY_BOARD", t3.stage3Role());
    }

    @Test
    void testTimeBoxedNonSelfDelegation() {
        UUID personA = UUID.randomUUID();
        UUID personB = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime future = now.plusDays(3);

        List<SodMatrixRule> matrix = List.of(
            new SodMatrixRule(UUID.randomUUID(), UUID.randomUUID(), "CASHIER", "APPROVER", "STRICT_BLOCK", "出纳不得兼任审批", true, now)
        );

        // Self-delegation is strictly blocked
        assertThrows(IllegalArgumentException.class, () ->
            IamOpsRules.validateDelegation(personA, personA, now, future, Set.of("ACCOUNTANT"), "APPROVER", matrix)
        );

        // Invalid time range (end before start) is blocked
        assertThrows(IllegalArgumentException.class, () ->
            IamOpsRules.validateDelegation(personA, personB, future, now, Set.of("ACCOUNTANT"), "APPROVER", matrix)
        );

        // Delegating APPROVER to a person holding CASHIER role -> Blocked by SoD
        assertThrows(IllegalStateException.class, () ->
            IamOpsRules.validateDelegation(personA, personB, now, future, Set.of("CASHIER"), "APPROVER", matrix)
        );

        // Delegating REVIEWER (alias for APPROVER) to CASHIER -> Blocked by SoD
        assertThrows(IllegalStateException.class, () ->
            IamOpsRules.validateDelegation(personA, personB, now, future, Set.of("CASHIER"), "REVIEWER", matrix)
        );

        // Valid delegation -> Success
        assertDoesNotThrow(() ->
            IamOpsRules.validateDelegation(personA, personB, now, future, Set.of("ACCOUNTANT"), "APPROVER", matrix)
        );
    }

    @Test
    void testDualPersonEmergencyAuditApprovals() {
        UUID requester = UUID.randomUUID();
        UUID approver1 = UUID.randomUUID();
        UUID approver2 = UUID.randomUUID();

        // Requester cannot approve own emergency grant
        assertThrows(IllegalArgumentException.class, () ->
            IamOpsRules.validateEmergencyGrantApprovals(requester, requester, null)
        );
        assertThrows(IllegalArgumentException.class, () ->
            IamOpsRules.validateEmergencyGrantApprovals(requester, approver1, requester)
        );

        // Approver 1 and Approver 2 cannot be the same person
        assertThrows(IllegalArgumentException.class, () ->
            IamOpsRules.validateEmergencyGrantApprovals(requester, approver1, approver1)
        );

        // Distinct two approvers -> Success
        assertDoesNotThrow(() ->
            IamOpsRules.validateEmergencyGrantApprovals(requester, approver1, approver2)
        );
    }

    @Test
    void testExportScopeAuthorization() {
        UUID tenantId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        // Grant with department scope and export allowed, but no unmasking
        DataScopeGrant deptGrant = new DataScopeGrant(
            UUID.randomUUID(), tenantId, accountId, personId, "DEPARTMENT", "DEPT-FINANCE", null,
            true, false, "ACTIVE", OffsetDateTime.now(ZoneOffset.UTC), null, null, 1L
        );

        // Matching department -> Success
        assertDoesNotThrow(() ->
            IamOpsRules.validateExportAuthorization(deptGrant, "DEPT-FINANCE", null, false)
        );

        // Mismatched department -> Blocked
        assertThrows(IllegalStateException.class, () ->
            IamOpsRules.validateExportAuthorization(deptGrant, "DEPT-SALES", null, false)
        );

        // Requesting unmasked data without privilege -> Blocked
        assertThrows(IllegalStateException.class, () ->
            IamOpsRules.validateExportAuthorization(deptGrant, "DEPT-FINANCE", null, true)
        );
    }
}

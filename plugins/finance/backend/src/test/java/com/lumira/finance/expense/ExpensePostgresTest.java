package com.lumira.finance.expense;

import com.lumira.finance.FinanceTestCredentials;

import com.lumira.finance.expense.ExpenseDomain.*;
import org.junit.jupiter.api.*;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolated PostgreSQL 18 schema test for Candidate Lane W05 (Expense).
 * Runs on 127.0.0.1:15489/lumira_qa_finance_kernel using a unique test_w05_<uuid> schema
 * that is dropped in finally / @AfterAll.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ExpensePostgresTest {

    private String testSchema;
    private Connection connection;
    private ExpenseJdbcStore store;
    private ExpenseService service;

    @BeforeEach
    void beforeEach() throws Exception {
        if (connection != null && !connection.isClosed()) {
            connection.rollback();
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET search_path TO " + testSchema + ", public;");
            }
        }
    }

    @BeforeAll
    void initDatabase() throws Exception {
        String jdbcUrl = FinanceTestCredentials.jdbcUrl();
        String user = FinanceTestCredentials.databaseUser();
        String pass = FinanceTestCredentials.databasePassword();

        if (jdbcUrl.contains("lumira_qa_finance_web") || jdbcUrl.contains("lumira_next")) {
            throw new IllegalStateException("Forbidden production/web target database: " + jdbcUrl);
        }

        testSchema = "test_w05_" + UUID.randomUUID().toString().replace("-", "");

        connection = DriverManager.getConnection(jdbcUrl, user, pass);
        connection.setAutoCommit(false);

        // 1. Create isolated schema
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE SCHEMA " + testSchema + ";");
            stmt.execute("SET search_path TO " + testSchema + ", public;");
        }
        connection.commit();

        Path candidatePath = java.util.List.of(
            Paths.get("plugins/finance/migrations/V30__expense_advance_reimbursement.sql"),
            Paths.get("../migrations/V30__expense_advance_reimbursement.sql"),
            Paths.get(".runtime/v4-wave2/expense/migration-candidate.sql"),
            Paths.get("../../../.runtime/v4-wave2/expense/migration-candidate.sql"),
            Paths.get("migration-candidate.sql")
        ).stream().filter(Files::exists).findFirst().orElseThrow();
        String sql = Files.readString(candidatePath, StandardCharsets.UTF_8);

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("SET search_path TO " + testSchema + ", public;\n" + sql);
        }
        connection.commit();

        store = new ExpenseJdbcStore();
        service = new ExpenseService(
                store,
                new TravelPolicyEngine(),
                new ExpenseRulesEngine(),
                new ExpenseAccountingAdapter()
        );
    }

    @AfterAll
    void tearDown() {
        if (connection != null) {
            try {
                connection.rollback();
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("DROP SCHEMA IF EXISTS " + testSchema + " CASCADE;");
                }
                connection.commit();
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    @Test
    void testTravelPolicyAndExceptionDatabaseLifecycle() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        UUID applicantId = UUID.randomUUID();
        UUID approverId = UUID.randomUUID();

        // Create policy with cap
        TravelPolicyCap cap = new TravelPolicyCap(
                null, null, CityTier.TIER_1, EmployeeRank.STAFF,
                ExpenseCategory.HOTEL, ExpenseDomain.cny("500.00", false), ExpenseDomain.CURRENCY_CNY
        );

        TravelPolicy policy = service.createTravelPolicy(
                connection, tenantId, "POL-2026-T1", 1, "Travel Policy 2026",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), creatorId, List.of(cap)
        );
        connection.commit();

        assertNotNull(policy.id());
        assertEquals(PolicyStatus.DRAFT, policy.status());

        // Activate policy
        TravelPolicy activated = service.activateTravelPolicy(connection, policy.id());
        connection.commit();
        assertEquals(PolicyStatus.ACTIVE, activated.status());

        // Query caps from PG
        List<TravelPolicyCap> loadedCaps = store.listCapsByPolicyId(connection, policy.id());
        assertEquals(1, loadedCaps.size());
        assertEquals(CityTier.TIER_1, loadedCaps.get(0).cityTier());
        assertEquals("500.00", ExpenseDomain.toCny2String(loadedCaps.get(0).dailyCapAmount()));

        // Evaluate cap in engine: 700 CNY exceeds 500 CNY
        TravelPolicyEngine.CapEvaluationResult eval = service.evaluateTravelExpense(
                connection, policy.id(), LocalDate.of(2026, 4, 15), CityTier.TIER_1, EmployeeRank.STAFF,
                ExpenseCategory.HOTEL, ExpenseDomain.cny("700.00", false)
        );
        assertEquals(TravelPolicyEngine.EvaluationStatus.OVER_CAP, eval.status());
        assertEquals("200.00", ExpenseDomain.toCny2String(eval.excessAmount()));

        // Request exception
        TravelException ex = service.requestTravelException(
                connection, tenantId, null, 1, loadedCaps.get(0).id(),
                ExpenseDomain.cny("700.00", false), loadedCaps.get(0).dailyCapAmount(),
                applicantId, "Hotel booked during Canton Fair"
        );
        connection.commit();
        assertEquals(TravelExceptionStatus.PENDING, ex.status());

        // Verify SoD in PostgreSQL check constraint: applicant cannot be approver
        TravelException selfApproved = new TravelException(
                UUID.randomUUID(), tenantId, null, 2, loadedCaps.get(0).id(),
                ExpenseDomain.cny("600.00", false), loadedCaps.get(0).dailyCapAmount(),
                ExpenseDomain.cny("100.00", false), applicantId, applicantId,
                TravelExceptionStatus.APPROVED, "Self approval attempt", null, null, OffsetDateTime.now()
        );
        Savepoint sp = connection.setSavepoint();
        assertThrows(SQLException.class, () -> {
            try {
                store.insertTravelException(connection, selfApproved);
            } finally {
                connection.rollback(sp);
            }
        });

        // Independent approval passes
        TravelException decided = service.decideTravelException(connection, ex.id(), approverId, true, "Approved fair pricing");
        connection.commit();
        assertEquals(TravelExceptionStatus.APPROVED, decided.status());
        assertEquals(approverId, decided.approverPersonId());
    }

    @Test
    void testExpenseRequisitionBudgetPreEncumbrance() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        UUID applicantId = UUID.randomUUID();
        UUID approverId = UUID.randomUUID();

        ExpenseRequisition req = service.createRequisition(
                connection, tenantId, bookId, "REQ-W05-" + UUID.randomUUID(), applicantId,
                UUID.randomUUID(), null, "Q3 Marketing Campaigns", ExpenseDomain.cny("20000.00", false),
                LocalDate.of(2026, 12, 31)
        );
        connection.commit();

        assertEquals(RequisitionStatus.DRAFT, req.status());
        assertEquals("20000.00", ExpenseDomain.toCny2String(req.totalBudgetAmount()));

        // Submit
        ExpenseRequisition submitted = service.submitRequisition(connection, req.id());
        connection.commit();
        assertEquals(RequisitionStatus.SUBMITTED, submitted.status());
        assertEquals("20000.00", ExpenseDomain.toCny2String(submitted.reservedAmount()));

        // Approve
        ExpenseRequisition approved = service.approveRequisition(connection, req.id(), approverId);
        connection.commit();
        assertEquals(RequisitionStatus.APPROVED, approved.status());

        // Cancel / Release unutilized budget
        ExpenseRequisition cancelled = service.cancelRequisition(connection, req.id());
        connection.commit();
        assertEquals(RequisitionStatus.CANCELLED, cancelled.status());
        assertEquals("0.00", ExpenseDomain.toCny2String(cancelled.reservedAmount()));
    }

    @Test
    void testCashAdvanceBorrow1000Offset800Refund200BalanceZero() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID approverId = UUID.randomUUID();
        UUID cashierId = UUID.randomUUID();

        // 1. Borrow 1000 CNY
        ExpenseAdvance adv = service.createAdvance(
                connection, tenantId, bookId, "ADV-W05-" + UUID.randomUUID(), employeeId,
                null, "Business trip travel advance", ExpenseDomain.cny("1000.00", false), employeeId
        );
        connection.commit();
        assertEquals(AdvanceStatus.DRAFT, adv.status());

        // 2. Submit & Approve
        service.submitAdvance(connection, adv.id());
        connection.commit();
        service.approveAdvance(connection, adv.id(), approverId);
        connection.commit();

        // 3. Payout by Cashier
        service.payoutAdvance(connection, adv.id(), cashierId, "BANK-TX-W05-001");
        connection.commit();

        ExpenseAdvance paid = store.findAdvanceById(connection, adv.id()).orElseThrow();
        assertEquals(AdvanceStatus.PAID, paid.status());
        assertEquals("1000.00", ExpenseDomain.toCny2String(paid.remainingBalance()));

        // 4. Reimbursement offset 800 CNY
        ExpenseAdvance offsetAdv = service.rulesEngine().offsetAdvance(paid, ExpenseDomain.cny("800.00", false));
        store.updateAdvance(connection, offsetAdv);
        connection.commit();

        ExpenseAdvance loadedAfterOffset = store.findAdvanceById(connection, adv.id()).orElseThrow();
        assertEquals(AdvanceStatus.PARTIALLY_OFFSET, loadedAfterOffset.status());
        assertEquals("800.00", ExpenseDomain.toCny2String(loadedAfterOffset.offsetAmount()));
        assertEquals("200.00", ExpenseDomain.toCny2String(loadedAfterOffset.remainingBalance()));

        // 5. Cash refund of remaining 200 CNY
        ExpenseAdvanceRefund refund = service.refundAdvanceCash(
                connection, adv.id(), "REF-W05-" + UUID.randomUUID(), ExpenseDomain.cny("200.00", false),
                LocalDate.now(), "BANK_TRANSFER", "RETURN-TX-001", cashierId
        );
        connection.commit();

        assertNotNull(refund.id());
        ExpenseAdvance settled = store.findAdvanceById(connection, adv.id()).orElseThrow();
        assertEquals(AdvanceStatus.SETTLED, settled.status());
        assertEquals("0.00", ExpenseDomain.toCny2String(settled.remainingBalance()));
        assertEquals("800.00", ExpenseDomain.toCny2String(settled.offsetAmount()));
        assertEquals("200.00", ExpenseDomain.toCny2String(settled.refundedAmount()));
        assertEquals("1000.00", ExpenseDomain.toCny2String(settled.principalAmount()));
    }

    @Test
    void testExpenseClaimMultiLinePartialRecognitionPayableAndReversal() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID submitterId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        UUID approverId = UUID.randomUUID();
        UUID cashierId = UUID.randomUUID();
        UUID deptId = UUID.randomUUID();

        // 1. Prepare an advance to offset against (borrow 800 CNY)
        ExpenseAdvance adv = service.createAdvance(
                connection, tenantId, bookId, "ADV-CLM-" + UUID.randomUUID(), employeeId,
                null, "Pre-trip advance", ExpenseDomain.cny("800.00", false), employeeId
        );
        service.submitAdvance(connection, adv.id());
        service.approveAdvance(connection, adv.id(), approverId);
        service.payoutAdvance(connection, adv.id(), cashierId, "BANK-TX-PRE");
        connection.commit();

        // 2. Prepare multi-line claim:
        // Line 1: 1200 submitted, 1000 approved, 200 personal disallowed, 6% VAT input split
        ExpenseClaimLine l1 = new ExpenseClaimLine(
                null, null, 1, ExpenseCategory.HOTEL, deptId, null,
                UUID.randomUUID(), "INV-H01", "10001", "c".repeat(64),
                ExpenseDomain.cny("1200.00", false), ExpenseDomain.cny("1000.00", false),
                ExpenseDomain.cny("200.00", false), "Personal luxury upgrade excluded",
                new BigDecimal("0.060000000000"), BigDecimal.ZERO, BigDecimal.ZERO, null, false
        );

        // Line 2: 300 submitted, 300 approved, 0 disallowed, 0 VAT
        ExpenseClaimLine l2 = new ExpenseClaimLine(
                null, null, 2, ExpenseCategory.TRANSPORT, deptId, null,
                UUID.randomUUID(), "INV-T01", "10002", "d".repeat(64),
                ExpenseDomain.cny("300.00", false), ExpenseDomain.cny("300.00", false),
                BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, false
        );

        // Link 800 CNY advance offset
        Map<UUID, BigDecimal> offsets = Map.of(adv.id(), ExpenseDomain.cny("800.00", false));

        var buildResult = service.createClaim(
                connection, tenantId, bookId, "CLM-W05-" + UUID.randomUUID(), employeeId,
                null, "Business trip claim with partial recognition", LocalDate.of(2026, 5, 20),
                submitterId, List.of(l1, l2), offsets
        );
        connection.commit();

        ExpenseClaim claim = buildResult.claim();
        assertEquals("1500.00", ExpenseDomain.toCny2String(claim.submittedAmount()));
        assertEquals("1300.00", ExpenseDomain.toCny2String(claim.approvedAmount()));
        assertEquals("200.00", ExpenseDomain.toCny2String(claim.disallowedAmount()));
        assertEquals("800.00", ExpenseDomain.toCny2String(claim.offsetAdvanceAmount()));
        assertEquals("500.00", ExpenseDomain.toCny2String(claim.payableAmount())); // 1300 - 800 = 500

        // 3. Workflow transitions: submit -> review -> approve
        service.submitClaim(connection, claim.id());
        connection.commit();

        service.reviewClaim(connection, claim.id(), reviewerId);
        connection.commit();

        service.approveClaim(connection, claim.id(), approverId);
        connection.commit();

        // Verify payable ledger record generated in PG
        ExpensePayableLedger payable = store.findPayableByClaimId(connection, claim.id()).orElseThrow();
        assertEquals("500.00", ExpenseDomain.toCny2String(payable.payableAmount()));
        assertEquals("500.00", ExpenseDomain.toCny2String(payable.outstandingBalance()));
        assertEquals(SettlementStatus.UNPAID, payable.settlementStatus());

        // 4. Post journal
        ExpenseJournalPlan claimJournal = service.postClaimJournal(connection, claim.id());
        connection.commit();
        assertEquals("EXPENSE_CLAIM", claimJournal.journalType());
        assertEquals("1300.00", ExpenseDomain.toCny2String(claimJournal.totalDebit()));
        assertEquals("1300.00", ExpenseDomain.toCny2String(claimJournal.totalCredit()));

        // 5. Disburse payment of 500 CNY -> Settled
        ExpenseRulesEngine.DisburseResult disburseRes = service.disbursePayment(
                connection, claim.id(), "PAY-W05-01", ExpenseDomain.cny("500.00", false),
                LocalDate.now(), "BANK_TRANSFER", "BANK-DISBURSE-001", cashierId
        );
        connection.commit();
        assertEquals(SettlementStatus.SETTLED, disburseRes.updatedLedger().settlementStatus());
        assertEquals("0.00", ExpenseDomain.toCny2String(disburseRes.updatedLedger().outstandingBalance()));
        assertEquals(ClaimStatus.SETTLED, disburseRes.updatedClaim().status());
    }

    @Test
    void testRowLevelSecurityEnforcement() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID empA = UUID.randomUUID();
        UUID empB = UUID.randomUUID();

        // Insert claim for Tenant A
        ExpenseClaim claimA = new ExpenseClaim(
                UUID.randomUUID(), tenantA, UUID.randomUUID(), "CLM-RLS-A", empA, null,
                "Tenant A Claim", LocalDate.now(), ExpenseDomain.CURRENCY_CNY,
                ExpenseDomain.cny("100.00", false), ExpenseDomain.cny("100.00", false),
                BigDecimal.ZERO, BigDecimal.ZERO, ExpenseDomain.cny("100.00", false),
                BigDecimal.ZERO, ClaimStatus.DRAFT, empA, null, null, null, null, null, null, OffsetDateTime.now(), 1
        );
        store.insertClaim(connection, claimA, List.of(), List.of());

        // Insert claim for Tenant B
        ExpenseClaim claimB = new ExpenseClaim(
                UUID.randomUUID(), tenantB, UUID.randomUUID(), "CLM-RLS-B", empB, null,
                "Tenant B Claim", LocalDate.now(), ExpenseDomain.CURRENCY_CNY,
                ExpenseDomain.cny("200.00", false), ExpenseDomain.cny("200.00", false),
                BigDecimal.ZERO, BigDecimal.ZERO, ExpenseDomain.cny("200.00", false),
                BigDecimal.ZERO, ClaimStatus.DRAFT, empB, null, null, null, null, null, null, OffsetDateTime.now(), 1
        );
        store.insertClaim(connection, claimB, List.of(), List.of());
        connection.commit();

        String rlsRole = "rls_user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE ROLE " + rlsRole + " NOLOGIN NOSUPERUSER NOBYPASSRLS;");
            stmt.execute("GRANT USAGE ON SCHEMA " + testSchema + " TO " + rlsRole + ";");
            stmt.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA " + testSchema + " TO " + rlsRole + ";");
        }
        connection.commit();

        try {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET ROLE " + rlsRole + ";");
            }

            // Under Tenant A + Employee A context
            store.setSessionContext(connection, tenantA, empA, false);
            Optional<ExpenseClaim> foundA = store.findClaimById(connection, claimA.id());
            Optional<ExpenseClaim> foundB = store.findClaimById(connection, claimB.id());

            assertTrue(foundA.isPresent(), "Tenant A should see claim A");
            assertFalse(foundB.isPresent(), "Tenant A should NOT see Tenant B claim under RLS");

            // Under Tenant B + Employee B context
            store.setSessionContext(connection, tenantB, empB, false);
            Optional<ExpenseClaim> foundA_fromB = store.findClaimById(connection, claimA.id());
            Optional<ExpenseClaim> foundB_fromB = store.findClaimById(connection, claimB.id());

            assertFalse(foundA_fromB.isPresent(), "Tenant B should NOT see Tenant A claim under RLS");
            assertTrue(foundB_fromB.isPresent(), "Tenant B should see claim B");

            // Strict RLS: When app.tenant_id is UNSET or EMPTY, zero rows must be returned (no leak!)
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("RESET app.tenant_id; RESET app.employee_id;");
            }
            Optional<ExpenseClaim> leakCheckA = store.findClaimById(connection, claimA.id());
            Optional<ExpenseClaim> leakCheckB = store.findClaimById(connection, claimB.id());
            assertFalse(leakCheckA.isPresent(), "Unset tenant context must NOT leak Tenant A claim");
            assertFalse(leakCheckB.isPresent(), "Unset tenant context must NOT leak Tenant B claim");
        } finally {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("RESET ROLE;");
                stmt.execute("DROP OWNED BY " + rlsRole + ";");
                stmt.execute("DROP ROLE IF EXISTS " + rlsRole + ";");
            }
            connection.commit();
        }
    }

    @Test
    void testFullClaimReversalWithAdvanceRestorationAndPayableCancellation() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID submitterId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        UUID approverId = UUID.randomUUID();
        UUID cashierId = UUID.randomUUID();
        UUID deptId = UUID.randomUUID();

        // 1. Requisition for 5000 CNY
        ExpenseRequisition req = service.createRequisition(
                connection, tenantId, bookId, "REQ-REV-" + UUID.randomUUID(), employeeId,
                deptId, null, "Project travel and logistics", ExpenseDomain.cny("5000.00", false),
                LocalDate.of(2026, 12, 31)
        );
        service.submitRequisition(connection, req.id());
        service.approveRequisition(connection, req.id(), approverId);
        connection.commit();

        // 2. Advance of 1000 CNY linked to requisition
        ExpenseAdvance adv = service.createAdvance(
                connection, tenantId, bookId, "ADV-REV-" + UUID.randomUUID(), employeeId,
                req.id(), "Initial advance", ExpenseDomain.cny("1000.00", false), employeeId
        );
        service.submitAdvance(connection, adv.id());
        service.approveAdvance(connection, adv.id(), approverId);
        service.payoutAdvance(connection, adv.id(), cashierId, "BANK-TX-REV-01");
        connection.commit();

        // Check requisition encumbrance after advance: 1000 utilized, 4000 reserved
        ExpenseRequisition reqAfterAdv = store.findRequisitionById(connection, req.id()).orElseThrow();
        assertEquals("1000.00", ExpenseDomain.toCny2String(reqAfterAdv.utilizedAmount()));
        assertEquals("4000.00", ExpenseDomain.toCny2String(reqAfterAdv.reservedAmount()));

        // 3. Claim of 2500 CNY, offset 1000 CNY advance -> net payable 1500 CNY
        ExpenseClaimLine l1 = new ExpenseClaimLine(
                null, null, 1, ExpenseCategory.HOTEL, deptId, null,
                null, null, null, null,
                ExpenseDomain.cny("2500.00", false), ExpenseDomain.cny("2500.00", false),
                BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, ExpenseDomain.cny("2500.00", false), null, false
        );
        Map<UUID, BigDecimal> offsets = Map.of(adv.id(), ExpenseDomain.cny("1000.00", false));

        var buildRes = service.createClaim(
                connection, tenantId, bookId, "CLM-REV-" + UUID.randomUUID(), employeeId,
                req.id(), "Travel claim with advance offset", LocalDate.now(),
                submitterId, List.of(l1), offsets
        );
        connection.commit();
        ExpenseClaim claim = buildRes.claim();

        service.submitClaim(connection, claim.id());
        service.reviewClaim(connection, claim.id(), reviewerId);
        service.approveClaim(connection, claim.id(), approverId);
        connection.commit();

        // Requisition utilized should be 1000 (advance) + 1500 (claim net) = 2500 CNY (NO DOUBLE COUNTING!)
        ExpenseRequisition reqAfterClaim = store.findRequisitionById(connection, req.id()).orElseThrow();
        assertEquals("2500.00", ExpenseDomain.toCny2String(reqAfterClaim.utilizedAmount()));
        assertEquals("2500.00", ExpenseDomain.toCny2String(reqAfterClaim.reservedAmount()));

        // Advance should be settled (offset 1000, remaining 0)
        ExpenseAdvance advAfterClaim = store.findAdvanceById(connection, adv.id()).orElseThrow();
        assertEquals(AdvanceStatus.SETTLED, advAfterClaim.status());
        assertEquals("0.00", ExpenseDomain.toCny2String(advAfterClaim.remainingBalance()));

        // Payable ledger should be 1500 UNPAID
        ExpensePayableLedger payable = store.findPayableByClaimId(connection, claim.id()).orElseThrow();
        assertEquals(SettlementStatus.UNPAID, payable.settlementStatus());
        assertEquals("1500.00", ExpenseDomain.toCny2String(payable.payableAmount()));
        assertEquals("1500.00", ExpenseDomain.toCny2String(payable.outstandingBalance()));

        // Post claim journal
        service.postClaimJournal(connection, claim.id());
        connection.commit();

        // 4. EXECUTE FULL REVERSAL
        ExpenseJournalPlan revJournal = service.reverseClaim(connection, claim.id(), approverId, "Trip cancelled post-facto");
        connection.commit();

        assertNotNull(revJournal);
        ExpenseClaim reversedClaim = store.findClaimById(connection, claim.id()).orElseThrow();
        assertEquals(ClaimStatus.REVERSED, reversedClaim.status());
        assertNotNull(reversedClaim.reversalJournalId());

        // Verify Advance is restored to PAID with remaining balance 1000 CNY
        ExpenseAdvance advRestored = store.findAdvanceById(connection, adv.id()).orElseThrow();
        assertEquals(AdvanceStatus.PAID, advRestored.status());
        assertEquals("1000.00", ExpenseDomain.toCny2String(advRestored.remainingBalance()));
        assertEquals("0.00", ExpenseDomain.toCny2String(advRestored.offsetAmount()));

        // Verify Requisition encumbrance is restored (1500 net consumed returned -> utilized back to 1000, reserved back to 4000)
        ExpenseRequisition reqRestored = store.findRequisitionById(connection, req.id()).orElseThrow();
        assertEquals("1000.00", ExpenseDomain.toCny2String(reqRestored.utilizedAmount()));
        assertEquals("4000.00", ExpenseDomain.toCny2String(reqRestored.reservedAmount()));

        // Verify Payable Ledger is CANCELLED with 0.00 outstanding balance
        ExpensePayableLedger payableCancelled = store.findPayableByClaimId(connection, claim.id()).orElseThrow();
        assertEquals(SettlementStatus.CANCELLED, payableCancelled.settlementStatus());
        assertEquals("0.00", ExpenseDomain.toCny2String(payableCancelled.outstandingBalance()));
    }

    @Test
    void testPostgresBeneficiarySodCheckConstraints() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();

        // Check 1: Claim employee_id == approver_person_id violates ck_claim_sod_employee_approver
        ExpenseClaim invalidClaim = new ExpenseClaim(
                UUID.randomUUID(), tenantId, UUID.randomUUID(), "CLM-SOD-ERR", employeeId, null,
                "Self approved claim", LocalDate.now(), ExpenseDomain.CURRENCY_CNY,
                ExpenseDomain.cny("500.00", false), ExpenseDomain.cny("500.00", false),
                BigDecimal.ZERO, BigDecimal.ZERO, ExpenseDomain.cny("500.00", false),
                BigDecimal.ZERO, ClaimStatus.APPROVED, UUID.randomUUID(), null, employeeId, // approver is employee!
                null, OffsetDateTime.now(), null, null, OffsetDateTime.now(), 1
        );

        Savepoint sp1 = connection.setSavepoint();
        assertThrows(SQLException.class, () -> {
            try {
                store.insertClaim(connection, invalidClaim, List.of(), List.of());
            } finally {
                connection.rollback(sp1);
            }
        });

        // Check 2: Advance employee_id == approver_person_id violates ck_advance_approver_employee_sod
        ExpenseAdvance invalidAdvance = new ExpenseAdvance(
                UUID.randomUUID(), tenantId, UUID.randomUUID(), "ADV-SOD-ERR", employeeId, null,
                "Self approved advance", ExpenseDomain.CURRENCY_CNY,
                ExpenseDomain.cny("1000.00", false), BigDecimal.ZERO, BigDecimal.ZERO, ExpenseDomain.cny("1000.00", false),
                AdvanceStatus.APPROVED, UUID.randomUUID(), employeeId, null, null,
                null, OffsetDateTime.now(), 1L
        );

        Savepoint sp2 = connection.setSavepoint();
        assertThrows(SQLException.class, () -> {
            try {
                store.insertAdvance(connection, invalidAdvance);
            } finally {
                connection.rollback(sp2);
            }
        });
    }

    @Test
    void testLeapYearPolicyEvaluation() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();

        TravelPolicyCap cap = new TravelPolicyCap(
                null, null, CityTier.TIER_2, EmployeeRank.MANAGER,
                ExpenseCategory.MEAL, ExpenseDomain.cny("200.00", false), ExpenseDomain.CURRENCY_CNY
        );

        // Leap year 2024 policy
        TravelPolicy policy = service.createTravelPolicy(
                connection, tenantId, "POL-2024-LEAP", 1, "Leap Year Travel Policy",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), creatorId, List.of(cap)
        );
        service.activateTravelPolicy(connection, policy.id());
        connection.commit();

        // Evaluate on leap day 2024-02-29
        TravelPolicyEngine.CapEvaluationResult eval = service.evaluateTravelExpense(
                connection, policy.id(), LocalDate.of(2024, 2, 29),
                CityTier.TIER_2, EmployeeRank.MANAGER, ExpenseCategory.MEAL, ExpenseDomain.cny("180.00", false)
        );
        assertEquals(TravelPolicyEngine.EvaluationStatus.COMPLIANT, eval.status());
        assertEquals("0.00", ExpenseDomain.toCny2String(eval.excessAmount()));
    }
}

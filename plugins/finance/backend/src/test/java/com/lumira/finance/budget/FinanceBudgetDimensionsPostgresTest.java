package com.lumira.finance.budget;

import com.lumira.finance.FinanceTestCredentials;

import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolated PostgreSQL 18 integration test for Dimensions & Budget Control (Lane W03).
 * Connects to lumira_qa_finance_kernel at 127.0.0.1:15489 using a unique test_w03_<uuid> schema.
 * All DDL and test data reside in this schema and are cleanly dropped in finally.
 */
public class FinanceBudgetDimensionsPostgresTest {

    @Test
    void testDimensionsAndBudgetLifecycle() throws Exception {
        String url = FinanceTestCredentials.jdbcUrl();
        String user = FinanceTestCredentials.databaseUser();
        String pass = FinanceTestCredentials.databasePassword();

        String schemaName = "test_w03_" + UUID.randomUUID().toString().replace("-", "");

        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(url);
        ds.setUser(user);
        ds.setPassword(pass);

        FinanceBudgetDimensionsStore store = new FinanceBudgetDimensionsStore();
        FinanceBudgetDimensionsService service = new FinanceBudgetDimensionsService(ds, store);

        Path candidateSqlPath = java.util.List.of(
            Path.of("plugins/finance/migrations/V28__dimensions_budget_control.sql"),
            Path.of("../migrations/V28__dimensions_budget_control.sql"),
            Path.of(".runtime/v4-wave2/dimensions-budget/migration-candidate.sql"),
            Path.of("../../../.runtime/v4-wave2/dimensions-budget/migration-candidate.sql"),
            Path.of("migration-candidate.sql")
        ).stream().filter(Files::exists).findFirst().orElseThrow();
        String ddl = Files.readString(candidateSqlPath, StandardCharsets.UTF_8);

        try (Connection initConn = ds.getConnection()) {
            initConn.setAutoCommit(true);
            try (Statement st = initConn.createStatement()) {
                st.execute("CREATE SCHEMA " + schemaName);
            }
        }

        // Configure datasource to use the isolated schema
        ds.setCurrentSchema(schemaName);

        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(true);
            store.initSchema(conn, ddl);

            UUID tenantId = UUID.randomUUID();
            UUID legalEntityId = UUID.randomUUID();
            UUID bookId = UUID.randomUUID();
            UUID yearId = UUID.randomUUID();

            UUID makerPerson = UUID.randomUUID();
            UUID makerAccount = UUID.randomUUID();
            UUID reviewerPerson = UUID.randomUUID();
            UUID reviewerAccount = UUID.randomUUID();

            // -----------------------------------------------------------------
            // MD01: Account auxiliary dimensions & deactivation approval
            // -----------------------------------------------------------------
            UUID accountId = UUID.randomUUID();
            FinanceBudgetDimensionsInput.AccountRuleDto rule = new FinanceBudgetDimensionsInput.AccountRuleDto(
                    tenantId, bookId, yearId, accountId, "6602", "CAS_2006",
                    true, true, false, false, false, true, "CNY_ONLY", "ACTIVE", 1, null
            );
            service.configureAccountRule(rule);

            Optional<FinanceBudgetDimensionsInput.AccountRuleDto> fetchedRule =
                    service.findAccountRule(tenantId, bookId, yearId, accountId);
            assertTrue(fetchedRule.isPresent());
            assertEquals("6602", fetchedRule.get().accountCode());
            assertTrue(fetchedRule.get().requireDepartment());
            assertTrue(fetchedRule.get().requireProject());
            assertTrue(fetchedRule.get().requireCostCenter());
            assertEquals("ACTIVE", fetchedRule.get().status());

            // Propose deactivation
            FinanceBudgetDimensionsInput.AccountDeactivationRequest deactReq =
                    new FinanceBudgetDimensionsInput.AccountDeactivationRequest(
                            accountId, "DEACTIVATE", "INACTIVE", makerPerson, makerAccount, "Account deprecated"
                    );
            UUID approvalId = service.submitAccountDeactivation(tenantId, bookId, yearId, deactReq);

            // Verify status transitioned to DEACTIVATION_PENDING
            Optional<FinanceBudgetDimensionsInput.AccountRuleDto> pendingRule =
                    service.findAccountRule(tenantId, bookId, yearId, accountId);
            assertTrue(pendingRule.isPresent());
            assertEquals("DEACTIVATION_PENDING", pendingRule.get().status());

            // Attempt self-approval by maker -> MUST FAIL with SoD check
            FinanceBudgetDimensionsInput.AccountApprovalDecision selfDecision =
                    new FinanceBudgetDimensionsInput.AccountApprovalDecision(
                            approvalId, makerPerson, makerAccount, "APPROVE", "Self approved"
                    );
            assertThrows(Exception.class, () -> service.reviewAccountApproval(selfDecision));

            // Test rejection restoring to ACTIVE
            service.reviewAccountApproval(new FinanceBudgetDimensionsInput.AccountApprovalDecision(
                    approvalId, reviewerPerson, reviewerAccount, "REJECT", "Not ready to deprecate"
            ));
            Optional<FinanceBudgetDimensionsInput.AccountRuleDto> rejectedRule =
                    service.findAccountRule(tenantId, bookId, yearId, accountId);
            assertTrue(rejectedRule.isPresent());
            assertEquals("ACTIVE", rejectedRule.get().status());

            // Submit deactivation again and approve -> INACTIVE
            UUID approvalId2 = service.submitAccountDeactivation(tenantId, bookId, yearId, deactReq);
            service.reviewAccountApproval(new FinanceBudgetDimensionsInput.AccountApprovalDecision(
                    approvalId2, reviewerPerson, reviewerAccount, "APPROVE", "Approved by finance director"
            ));

            // Verify account status updated to INACTIVE
            Optional<FinanceBudgetDimensionsInput.AccountRuleDto> deactivatedRule =
                    service.findAccountRule(tenantId, bookId, yearId, accountId);
            assertTrue(deactivatedRule.isPresent());
            assertEquals("INACTIVE", deactivatedRule.get().status());

            // -----------------------------------------------------------------
            // MD02: Counterparty bank account, review, identity history & contract
            // -----------------------------------------------------------------
            UUID counterpartyId = UUID.randomUUID();
            UUID bankAccId = UUID.randomUUID();
            FinanceBudgetDimensionsInput.CounterpartyBankAccountDto bankDto =
                    new FinanceBudgetDimensionsInput.CounterpartyBankAccountDto(
                            bankAccId, tenantId, legalEntityId, counterpartyId,
                            "China Merchants Bank", "Shenzhen Branch", "6225880123456789",
                            "CNY", "SETTLEMENT", true, "ACTIVE", 1, null, null
                    );
            service.registerCounterpartyBankAccount(bankDto);

            // Bank account review (e.g. deactivation proposal)
            FinanceBudgetDimensionsInput.BankAccountReviewRequest reviewReq =
                    new FinanceBudgetDimensionsInput.BankAccountReviewRequest(
                            bankAccId, "DEACTIVATE", "{\"reason\":\"Account closed\"}", makerPerson, "Deactivate bank account"
                    );
            UUID reviewId = service.submitBankAccountReview(tenantId, legalEntityId, counterpartyId, reviewReq);

            // Review by reviewerPerson
            FinanceBudgetDimensionsInput.BankAccountReviewDecision bankDecision =
                    new FinanceBudgetDimensionsInput.BankAccountReviewDecision(
                            reviewId, reviewerPerson, "APPROVED", "Confirmed closed"
                    );
            service.reviewBankAccount(bankDecision);

            // New bank account created with PENDING_REVIEW -> approved to ACTIVE
            UUID bankAcc2Id = UUID.randomUUID();
            FinanceBudgetDimensionsInput.CounterpartyBankAccountDto pendingBankDto =
                    new FinanceBudgetDimensionsInput.CounterpartyBankAccountDto(
                            bankAcc2Id, tenantId, legalEntityId, counterpartyId,
                            "Industrial and Commercial Bank of China", "Beijing Branch", "9558801234567890",
                            "CNY", "OTHER", false, "PENDING_REVIEW", 1, null, null
                    );
            service.registerCounterpartyBankAccount(pendingBankDto);

            UUID createReviewId = service.submitBankAccountReview(tenantId, legalEntityId, counterpartyId,
                    new FinanceBudgetDimensionsInput.BankAccountReviewRequest(
                            bankAcc2Id, "CREATE", "{\"reason\":\"New supplier account\"}", makerPerson, "Approve new account"
                    ));
            service.reviewBankAccount(new FinanceBudgetDimensionsInput.BankAccountReviewDecision(
                    createReviewId, reviewerPerson, "APPROVED", "KYC verified"
            ));

            // Counterparty Identity History (Legal name change)
            String evidenceHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
            FinanceBudgetDimensionsInput.CounterpartyIdentityHistoryDto history =
                    new FinanceBudgetDimensionsInput.CounterpartyIdentityHistoryDto(
                            UUID.randomUUID(), tenantId, legalEntityId, counterpartyId, 1,
                            "Tech Corp Ltd", "Tech Global Holdings Ltd",
                            "91440300MA5XXXXXX1", "91440300MA5XXXXXX1",
                            "Legal corporate reorganization", UUID.randomUUID(), evidenceHash,
                            makerPerson, reviewerPerson, null
                    );
            service.appendIdentityHistory(history);

            // Framework Contract
            UUID frameworkContractId = UUID.randomUUID();
            FinanceBudgetDimensionsInput.FrameworkContractDto fc =
                    new FinanceBudgetDimensionsInput.FrameworkContractDto(
                            frameworkContractId, tenantId, legalEntityId, counterpartyId,
                            "FC-2026-001", "IT Services Master Agreement",
                            "NET30", 30, "0.050000000000",
                            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                            "ACTIVE", 1, null
                    );
            service.registerFrameworkContract(fc);

            // -----------------------------------------------------------------
            // MD03: Master data dimensions & Multi-target Cost Allocation
            // -----------------------------------------------------------------
            UUID deptId = UUID.randomUUID();
            service.registerDepartment(new FinanceBudgetDimensionsInput.DepartmentDto(
                    deptId, tenantId, bookId, "D01", "Engineering", null, makerPerson,
                    LocalDate.of(2026, 1, 1), null, "ACTIVE", 1, null
            ));

            UUID projId = UUID.randomUUID();
            service.registerProject(new FinanceBudgetDimensionsInput.ProjectDto(
                    projId, tenantId, bookId, "P01", "Cloud Migration", deptId, reviewerPerson,
                    LocalDate.of(2026, 1, 1), null, "ACTIVE", 1, null
            ));

            UUID ccId = UUID.randomUUID();
            service.registerCostCenter(new FinanceBudgetDimensionsInput.CostCenterDto(
                    ccId, tenantId, bookId, "CC01", "Platform Operations", deptId,
                    LocalDate.of(2026, 1, 1), null, "ACTIVE", 1, null
            ));

            UUID contractDimId = UUID.randomUUID();
            service.registerContractDimension(new FinanceBudgetDimensionsInput.ContractDimensionDto(
                    contractDimId, tenantId, bookId, "CT-2026-888", "Annual Server Hosting",
                    counterpartyId, deptId, projId, "500000.00000000",
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "ACTIVE", 1, null
            ));

            // Create Cost Allocation Rule: 3 targets with numeric(18,12) ratios
            FinanceBudgetDimensionsInput.AllocationRuleRequest allocRuleReq =
                    new FinanceBudgetDimensionsInput.AllocationRuleRequest(
                            "ALLOC-SHARED-IT", "IT Cost Pool Allocation", ccId, deptId, "6602",
                            LocalDate.of(2026, 1, 1), null,
                            List.of(
                                    new FinanceBudgetDimensionsInput.AllocationTargetInput(ccId, deptId, projId, "0.500000000000", 1),
                                    new FinanceBudgetDimensionsInput.AllocationTargetInput(ccId, deptId, projId, "0.300000000000", 2),
                                    new FinanceBudgetDimensionsInput.AllocationTargetInput(ccId, deptId, projId, "0.200000000000", 3)
                            )
                    );
            UUID allocRuleId = service.createAllocationRule(tenantId, bookId, allocRuleReq);

            // Execute penny allocation (scale 2): total 100.00
            FinanceBudgetDimensionsInput.AllocationExecutionResult pennyRes =
                    service.executeAllocation(new FinanceBudgetDimensionsInput.AllocationExecutionRequest(allocRuleId, "100.00", 2));
            assertEquals("100.00", pennyRes.totalAmount());
            assertEquals(3, pennyRes.allocatedTargets().size());
            BigDecimal pennySum = BigDecimal.ZERO;
            for (FinanceBudgetDimensionsInput.AllocatedItemResult item : pennyRes.allocatedTargets()) {
                pennySum = pennySum.add(new BigDecimal(item.allocatedAmount()));
            }
            assertEquals(new BigDecimal("100.00"), pennySum);

            // Execute exact 8-decimal allocation: total 98765.43210987
            FinanceBudgetDimensionsInput.AllocationExecutionResult dec8Res =
                    service.executeAllocation(new FinanceBudgetDimensionsInput.AllocationExecutionRequest(allocRuleId, "98765.43210987", 8));
            BigDecimal dec8Sum = BigDecimal.ZERO;
            for (FinanceBudgetDimensionsInput.AllocatedItemResult item : dec8Res.allocatedTargets()) {
                dec8Sum = dec8Sum.add(new BigDecimal(item.allocatedAmount()));
            }
            assertEquals(new BigDecimal("98765.43210987"), dec8Sum);

            // -----------------------------------------------------------------
            // BD01: Comprehensive Budget Formulation, Approval & Adjustment
            // -----------------------------------------------------------------
            FinanceBudgetDimensionsInput.BudgetVersionCreateRequest budgetReq =
                    new FinanceBudgetDimensionsInput.BudgetVersionCreateRequest(
                            "BUDGET-2026-V1", "ORIGINAL", "Annual Operating Plan", makerPerson,
                            List.of(
                                    new FinanceBudgetDimensionsInput.BudgetItemInput(1, "6602", deptId, projId, ccId, counterpartyId, contractDimId, "100000.00000000"),
                                    new FinanceBudgetDimensionsInput.BudgetItemInput(2, "6602", deptId, projId, ccId, counterpartyId, contractDimId, "120000.00000000")
                            )
                    );
            UUID budgetVersionId = service.createBudgetVersion(tenantId, legalEntityId, bookId, yearId, budgetReq);

            // Approval SoD check: maker cannot approve own budget
            assertThrows(Exception.class, () ->
                    service.approveBudgetVersion(new FinanceBudgetDimensionsInput.BudgetVersionApprovalRequest(
                            budgetVersionId, makerPerson, "APPROVED", "Self approval attempt"
                    )));

            // Reviewer approves budget
            service.approveBudgetVersion(new FinanceBudgetDimensionsInput.BudgetVersionApprovalRequest(
                    budgetVersionId, reviewerPerson, "APPROVED", "Board approved"
            ));

            // Budget adjustment
            UUID budgetV2Id = service.createBudgetVersion(tenantId, legalEntityId, bookId, yearId,
                    new FinanceBudgetDimensionsInput.BudgetVersionCreateRequest(
                            "BUDGET-2026-V2", "ADJUSTMENT", "Q1 Adjustment", makerPerson,
                            List.of(
                                    new FinanceBudgetDimensionsInput.BudgetItemInput(1, "6602", deptId, projId, ccId, counterpartyId, contractDimId, "110000.00000000")
                            )
                    ));
            UUID adjId = service.createBudgetAdjustment(tenantId, bookId, yearId,
                    new FinanceBudgetDimensionsInput.BudgetAdjustmentRequest(
                            budgetVersionId, budgetV2Id, "AUGMENTATION", "10000.00000000",
                            "Emergency server capacity expansion", makerPerson, reviewerPerson
                    ));
            assertNotNull(adjId);

            // -----------------------------------------------------------------
            // BD02: Real-time Budget Control Ledger, Reservation, Consumption & Forecast
            // -----------------------------------------------------------------
            // Control ledger was populated upon approving BUDGET-2026-V1 (Period 1 has 100000.00000000)
            FinanceBudgetDimensionsInput.BudgetReservationRequest reserveReq =
                    new FinanceBudgetDimensionsInput.BudgetReservationRequest(
                            1, "6602", deptId, projId, ccId, "30000.00000000",
                            "EXPENSE_REQUEST", UUID.randomUUID(), makerPerson, "Reserve for cloud compute"
                    );
            UUID resTxId = service.reserveBudget(tenantId, bookId, yearId, reserveReq);
            assertNotNull(resTxId);

            // Over-reservation should fail
            FinanceBudgetDimensionsInput.BudgetReservationRequest overReserve =
                    new FinanceBudgetDimensionsInput.BudgetReservationRequest(
                            1, "6602", deptId, projId, ccId, "90000.00000000", // 30k + 90k = 120k > 100k
                            "EXPENSE_REQUEST", UUID.randomUUID(), makerPerson, "Exceed budget"
                    );
            assertThrows(Exception.class, () -> service.reserveBudget(tenantId, bookId, yearId, overReserve));

            // Consume from reservation (20000.00000000 out of 30000 reserved)
            FinanceBudgetDimensionsInput.BudgetConsumptionRequest consumeReq =
                    new FinanceBudgetDimensionsInput.BudgetConsumptionRequest(
                            1, "6602", deptId, projId, ccId, "20000.00000000",
                            true, "PURCHASE_INVOICE", UUID.randomUUID(), makerPerson, "Compute invoice Jan"
                    );
            UUID conTxId = service.consumeBudget(tenantId, bookId, yearId, consumeReq);
            assertNotNull(conTxId);

            // Release remaining 10000 reservation
            FinanceBudgetDimensionsInput.BudgetReleaseRequest releaseReq =
                    new FinanceBudgetDimensionsInput.BudgetReleaseRequest(
                            1, "6602", deptId, projId, ccId, "10000.00000000",
                            "EXPENSE_CANCEL", UUID.randomUUID(), makerPerson, "Release unspent quota"
                    );
            UUID relTxId = service.releaseBudget(tenantId, bookId, yearId, releaseReq);
            assertNotNull(relTxId);

            // Forecast Snapshot for Period 1
            FinanceBudgetDimensionsInput.BudgetForecastSnapshotRequest forecastReq =
                    new FinanceBudgetDimensionsInput.BudgetForecastSnapshotRequest(
                            1, budgetVersionId, "180000.00000000", reviewerPerson
                    );
            UUID snapshotId = service.captureForecastSnapshot(tenantId, bookId, yearId, forecastReq);
            assertNotNull(snapshotId);

            // -----------------------------------------------------------------
            // Verification of Control Ledger & Rollback Consumption
            // -----------------------------------------------------------------
            // Control ledger after reserve 30k, consume 20k, release 10k:
            // budget = 100k, reserved = 0, consumed = 20k, available = 80k
            Optional<FinanceBudgetDimensionsInput.BudgetControlLedgerDto> ledgerOpt =
                    service.findControlLedger(tenantId, bookId, yearId, 1, "6602", deptId, projId, ccId);
            assertTrue(ledgerOpt.isPresent());
            assertEquals(0, new BigDecimal("100000.00000000").compareTo(new BigDecimal(ledgerOpt.get().budgetAmount())));
            assertEquals(0, new BigDecimal("0.00000000").compareTo(new BigDecimal(ledgerOpt.get().reservedAmount())));
            assertEquals(0, new BigDecimal("20000.00000000").compareTo(new BigDecimal(ledgerOpt.get().consumedAmount())));
            assertEquals(0, new BigDecimal("80000.00000000").compareTo(new BigDecimal(ledgerOpt.get().availableAmount())));

            // Rollback consumption by 5000 (e.g. accounting voucher reversal)
            FinanceBudgetDimensionsInput.BudgetRollbackConsumptionRequest rollbackReq =
                    new FinanceBudgetDimensionsInput.BudgetRollbackConsumptionRequest(
                            1, "6602", deptId, projId, ccId, "5000.00000000",
                            "ACCOUNTING_REVERSAL", UUID.randomUUID(), makerPerson, "Rollback partial invoice"
                    );
            UUID rollbackTxId = service.rollbackConsumeBudget(tenantId, bookId, yearId, rollbackReq);
            assertNotNull(rollbackTxId);

            // Ledger now has consumed = 15000, available = 85000
            Optional<FinanceBudgetDimensionsInput.BudgetControlLedgerDto> updatedLedger =
                    service.findControlLedger(tenantId, bookId, yearId, 1, "6602", deptId, projId, ccId);
            assertTrue(updatedLedger.isPresent());
            assertEquals(0, new BigDecimal("15000.00000000").compareTo(new BigDecimal(updatedLedger.get().consumedAmount())));
            assertEquals(0, new BigDecimal("85000.00000000").compareTo(new BigDecimal(updatedLedger.get().availableAmount())));

            // Over-rollback (e.g. 20000 > 15000) should fail
            FinanceBudgetDimensionsInput.BudgetRollbackConsumptionRequest overRollback =
                    new FinanceBudgetDimensionsInput.BudgetRollbackConsumptionRequest(
                            1, "6602", deptId, projId, ccId, "20000.00000000",
                            "ACCOUNTING_REVERSAL", UUID.randomUUID(), makerPerson, "Excessive rollback"
                    );
            assertThrows(Exception.class, () -> service.rollbackConsumeBudget(tenantId, bookId, yearId, overRollback));

            // Direct consumption without prior reservation (e.g. 5000 direct consume)
            FinanceBudgetDimensionsInput.BudgetConsumptionRequest directConsumeReq =
                    new FinanceBudgetDimensionsInput.BudgetConsumptionRequest(
                            1, "6602", deptId, projId, ccId, "5000.00000000",
                            false, "DIRECT_PAYMENT", UUID.randomUUID(), makerPerson, "Direct utility expense"
                    );
            UUID dirTxId = service.consumeBudget(tenantId, bookId, yearId, directConsumeReq);
            assertNotNull(dirTxId);

            // Ledger now has consumed = 20000, available = 80000
            Optional<FinanceBudgetDimensionsInput.BudgetControlLedgerDto> postDirectLedger =
                    service.findControlLedger(tenantId, bookId, yearId, 1, "6602", deptId, projId, ccId);
            assertEquals(0, new BigDecimal("20000.00000000").compareTo(new BigDecimal(postDirectLedger.get().consumedAmount())));
            assertEquals(0, new BigDecimal("80000.00000000").compareTo(new BigDecimal(postDirectLedger.get().availableAmount())));

            // -----------------------------------------------------------------
            // Concurrency Test: Row-level lock (FOR UPDATE) under thread contention
            // Available is 80,000. Thread 1 attempts 50,000, Thread 2 attempts 50,000.
            // Exactly one must succeed, and one must fail with BUDGET_EXCEEDED.
            // -----------------------------------------------------------------
            int threadCount = 2;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);

            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                futures.add(executor.submit(() -> {
                    try {
                        startLatch.await();
                        service.reserveBudget(tenantId, bookId, yearId,
                                new FinanceBudgetDimensionsInput.BudgetReservationRequest(
                                        1, "6602", deptId, projId, ccId, "50000.00000000",
                                        "CONCURRENT_TEST", UUID.randomUUID(), makerPerson, "Thread " + idx
                                ));
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    }
                }));
            }
            startLatch.countDown();
            for (Future<?> f : futures) f.get();
            executor.shutdown();

            assertEquals(1, successCount.get(), "Exactly one concurrent reservation should succeed");
            assertEquals(1, failureCount.get(), "Competing reservation exceeding available quota must fail");

            // Available is now 80,000 - 50,000 = 30,000
            Optional<FinanceBudgetDimensionsInput.BudgetControlLedgerDto> postConcurrentLedger =
                    service.findControlLedger(tenantId, bookId, yearId, 1, "6602", deptId, projId, ccId);
            assertEquals(0, new BigDecimal("30000.00000000").compareTo(new BigDecimal(postConcurrentLedger.get().availableAmount())));

            // -----------------------------------------------------------------
            // Inverted Date Range DB Constraint Test
            // -----------------------------------------------------------------
            assertThrows(Exception.class, () -> {
                try (Connection c = ds.getConnection()) {
                    try (Statement st = c.createStatement()) {
                        st.execute("INSERT INTO plugin_finance_dim_framework_contract (id, tenant_id, legal_entity_id, counterparty_id, contract_no, contract_name, settlement_terms, valid_from, valid_to) VALUES ('"
                                + UUID.randomUUID() + "', '" + tenantId + "', '" + legalEntityId + "', '" + counterpartyId + "', 'INV-DATE-1', 'Invalid Date Contract', 'NET30', '2026-12-31', '2026-01-01')");
                    }
                }
            });
        } finally {
            // Drop isolated schema in finally
            try (Connection cleanConn = ds.getConnection()) {
                cleanConn.setAutoCommit(true);
                try (Statement st = cleanConn.createStatement()) {
                    st.execute("DROP SCHEMA IF EXISTS " + schemaName + " CASCADE");
                }
            }
        }
    }
}

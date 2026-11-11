package com.lumira.finance.treasury;

import com.lumira.finance.FinanceTestCredentials;

import com.lumira.finance.treasury.TreasuryModel.*;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolated PostgreSQL 18 schema integration test on lumira_qa_finance_kernel.
 * Creates an isolated schema test_w07_<uuid>, runs migration-candidate.sql,
 * exercises MD04, TR01, TR02, TR03, TR04, and drops the schema in @AfterAll.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinanceTreasuryPostgresTest {
    private static final String JDBC_URL = FinanceTestCredentials.jdbcUrl();
    private static final String DB_USER = FinanceTestCredentials.databaseUser();
    private static final String DB_PASS = FinanceTestCredentials.databasePassword();

    private PGSimpleDataSource dataSource;
    private String schemaName;
    private TreasuryRepository repo;
    private TreasuryService service;
    private ObjectMapper mapper;

    private UUID tenantId;
    private UUID bookId;
    private UUID periodId;
    private Actor maker;
    private Actor reviewer;
    private Scope scope;

    @BeforeAll
    void setupAll() throws Exception {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(JDBC_URL);
        dataSource.setUser(DB_USER);
        dataSource.setPassword(DB_PASS);

        schemaName = "test_w07_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        mapper = new ObjectMapper();
        repo = new TreasuryRepository(mapper);
        service = new TreasuryService(dataSource, repo, mapper);

        tenantId = UUID.randomUUID();
        bookId = UUID.randomUUID();
        periodId = UUID.randomUUID();
        maker = new Actor(UUID.randomUUID(), UUID.randomUUID());
        reviewer = new Actor(UUID.randomUUID(), UUID.randomUUID());
        scope = new Scope(tenantId, bookId);

        // Read migration SQL
        String migrationSql = findMigrationSql();

        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schemaName);
            s.execute("SET search_path TO " + schemaName + ", public");
            s.execute(migrationSql);
        }

        // Configure connection datasource default search_path
        dataSource.setCurrentSchema(schemaName);
    }

    private String findMigrationSql() throws Exception {
        Path p = java.util.List.of(
            Path.of("plugins/finance/migrations/V32__treasury_cash_bank_management.sql"),
            Path.of("../migrations/V32__treasury_cash_bank_management.sql"),
            Path.of(".runtime/v4-wave2/treasury/migration-candidate.sql"),
            Path.of("../../../.runtime/v4-wave2/treasury/migration-candidate.sql"),
            Path.of("migration-candidate.sql")
        ).stream().filter(Files::exists).findFirst().orElseThrow();
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @AfterAll
    void tearDownAll() {
        if (schemaName != null && dataSource != null) {
            try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + schemaName + " CASCADE");
            } catch (Exception e) {
                System.err.println("Failed to clean up schema " + schemaName + ": " + e.getMessage());
            }
        }
    }

    @Test
    @DisplayName("MD04: Bank Account Lifecycle (Create -> Activate -> Freeze -> Unfreeze -> Close)")
    void testBankAccountLifecycleInPostgres() throws Exception {
        CreateAccountInput in = new CreateAccountInput(
                "6222001001", "Operating Account 1", "China Merchants Bank", "Shenzhen Bay Branch",
                "BASIC", UUID.randomUUID()
        );
        BankAccount acct = service.openAccount(scope, maker, in);
        assertEquals(BankAccountStatus.PENDING_REVIEW, acct.status());

        // Self-activation rejected by SoD
        assertThrows(IllegalStateException.class, () ->
                service.reviewAccount(scope, maker, acct.id(), new ReviewAccountInput("ACTIVATE", "Self activation")));

        // Independent activation
        BankAccount active = service.reviewAccount(scope, reviewer, acct.id(), new ReviewAccountInput("ACTIVATE", "Reviewed by Finance Director"));
        assertEquals(BankAccountStatus.ACTIVE, active.status());

        // Freeze
        BankAccount frozen = service.reviewAccount(scope, reviewer, acct.id(), new ReviewAccountInput("FREEZE", "Suspicious activity hold"));
        assertEquals(BankAccountStatus.FROZEN, frozen.status());
        assertEquals("Suspicious activity hold", frozen.frozenReason());

        // Unfreeze
        BankAccount unfrozen = service.reviewAccount(scope, reviewer, acct.id(), new ReviewAccountInput("UNFREEZE", "Hold lifted"));
        assertEquals(BankAccountStatus.ACTIVE, unfrozen.status());

        // Close
        BankAccount closed = service.reviewAccount(scope, reviewer, acct.id(), new ReviewAccountInput("CLOSE", "Account terminated"));
        assertEquals(BankAccountStatus.CLOSED, closed.status());
    }

    @Test
    @DisplayName("MD04: Period-Approved Numbering Policy creation and monotonic sequence")
    void testNumberingPolicyInPostgres() throws Exception {
        ConfigureNumberingInput in = new ConfigureNumberingInput(
                periodId, 11, "PAYMENT", "PAY-", "202611"
        );
        NumberingPolicy policy = service.configureNumberingPolicy(scope, maker, in);
        assertNotNull(policy.id());
        assertFalse(policy.isApproved());

        // Self-approval rejected by SoD
        assertThrows(IllegalStateException.class, () ->
                service.approveNumberingPolicy(scope, maker, policy.id()));

        // Independent approval by reviewer
        NumberingPolicy approvedPolicy = service.approveNumberingPolicy(scope, reviewer, policy.id());
        assertTrue(approvedPolicy.isApproved());
        assertEquals(reviewer.personId(), approvedPolicy.reviewerPersonId());

        // Sequence increment
        try (Connection c = dataSource.getConnection()) {
            int seq1 = repo.incrementNumberingSequence(c, policy.id());
            int seq2 = repo.incrementNumberingSequence(c, policy.id());
            assertEquals(1, seq1);
            assertEquals(2, seq2);
        }
    }

    @Test
    @DisplayName("TR01: Treasury Orders and Internal Bank-to-Bank Transfer Pairing with In-Transit state")
    void testInternalTransferPairingInPostgres() throws Exception {
        BankAccount acctA = service.openAccount(scope, maker, new CreateAccountInput(
                "ACC-TRF-A", "Treasury Account A", "ICBC", "Main Branch", "GENERAL", UUID.randomUUID()));
        service.reviewAccount(scope, reviewer, acctA.id(), new ReviewAccountInput("ACTIVATE", "OK"));

        BankAccount acctB = service.openAccount(scope, maker, new CreateAccountInput(
                "ACC-TRF-B", "Treasury Account B", "BOC", "Sub Branch", "GENERAL", UUID.randomUUID()));
        service.reviewAccount(scope, reviewer, acctB.id(), new ReviewAccountInput("ACTIVATE", "OK"));

        CreateOrderInput transferIn = new CreateOrderInput(
                "TRANSFER_OUT", acctA.id(), null, acctB.id(), LocalDate.parse("2026-11-10"),
                "100000.00", "25.00", UUID.randomUUID(), "Liquidity rebalancing"
        );
        TreasuryOrder outOrder = service.createTreasuryOrder(scope, maker, transferIn);
        assertNotNull(outOrder.transferPairId());
        assertEquals(OrderStatus.DRAFT, outOrder.status());

        // Approve transfer-out: moves to IN_TRANSIT
        TreasuryOrder approvedOut = service.approveTreasuryOrder(scope, reviewer, outOrder.id());
        assertEquals(OrderStatus.IN_TRANSIT, approvedOut.status());

        // Verify paired order also exists and is IN_TRANSIT
        try (Connection c = dataSource.getConnection()) {
            List<TreasuryOrder> orders = repo.listTreasuryOrders(c, tenantId, bookId, acctB.id(), null);
            Optional<TreasuryOrder> paired = orders.stream()
                    .filter(o -> outOrder.transferPairId().equals(o.transferPairId()))
                    .findFirst();
            assertTrue(paired.isPresent());
            assertEquals(OrderType.TRANSFER_IN, paired.get().orderType());
            assertEquals(OrderStatus.IN_TRANSIT, paired.get().status());
            assertEquals(new BigDecimal("100000.00000000"), paired.get().amount());
        }

        // Complete settlement of transfer instruction: verifies paired TRANSFER_IN also becomes EXECUTED!
        PaymentInstruction transferInst = service.createPaymentInstruction(scope, maker, new CreatePaymentInstructionInput(
                outOrder.id(), acctA.id(), acctB.accountNo(), "Treasury Account B", "BOC", "100000.00"
        ));
        service.approvePaymentInstruction(scope, reviewer, transferInst.id());
        service.dispatchPaymentInstruction(scope, maker, transferInst.id());
        service.settlePaymentInstruction(scope, reviewer, transferInst.id(),
                new VerifySettlementReceiptInput("BANK-TRF-001", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));

        try (Connection c = dataSource.getConnection()) {
            TreasuryOrder settledOut = repo.findTreasuryOrderById(c, outOrder.id()).orElseThrow();
            assertEquals(OrderStatus.EXECUTED, settledOut.status());

            List<TreasuryOrder> orders = repo.listTreasuryOrders(c, tenantId, bookId, acctB.id(), null);
            Optional<TreasuryOrder> paired = orders.stream()
                    .filter(o -> outOrder.transferPairId().equals(o.transferPairId()))
                    .findFirst();
            assertTrue(paired.isPresent());
            assertEquals(OrderType.TRANSFER_IN, paired.get().orderType());
            assertEquals(OrderStatus.EXECUTED, paired.get().status());
        }
    }

    @Test
    @DisplayName("TR02: Payment Instruction UNKNOWN hold, SHA-256 settlement, and failed return lineage")
    void testPaymentInstructionInPostgres() throws Exception {
        BankAccount payerAcct = service.openAccount(scope, maker, new CreateAccountInput(
                "ACC-PAYER", "Payer Account", "CCB", "Branch A", "BASIC", UUID.randomUUID()));
        service.reviewAccount(scope, reviewer, payerAcct.id(), new ReviewAccountInput("ACTIVATE", "OK"));

        TreasuryOrder payOrder = service.createTreasuryOrder(scope, maker, new CreateOrderInput(
                "PAYMENT", payerAcct.id(), UUID.randomUUID(), null, LocalDate.parse("2026-11-10"),
                "50000.00", "0.00", null, "Supplier settlement"
        ));
        service.approveTreasuryOrder(scope, reviewer, payOrder.id());

        // Create instruction
        PaymentInstruction inst = service.createPaymentInstruction(scope, maker, new CreatePaymentInstructionInput(
                payOrder.id(), payerAcct.id(), "6217009988", "Tech Vendor Ltd", "ABC Bank", "50000.00"
        ));
        assertEquals(InstructionStatus.DRAFT, inst.status());

        // Approve instruction
        PaymentInstruction approved = service.approvePaymentInstruction(scope, reviewer, inst.id());
        assertEquals(InstructionStatus.APPROVED, approved.status());

        // Dispatch instruction -> DISPATCHED_UNKNOWN (Strict Hold)
        PaymentInstruction dispatched = service.dispatchPaymentInstruction(scope, maker, inst.id());
        assertEquals(InstructionStatus.DISPATCHED_UNKNOWN, dispatched.status());
        assertEquals("NOT_VERIFIED", dispatched.bankDirectChannel());

        // Settle instruction with genuine SHA-256 receipt
        String sha256 = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9";
        PaymentInstruction settled = service.settlePaymentInstruction(scope, reviewer, inst.id(),
                new VerifySettlementReceiptInput("BANK-REF-9988", sha256));
        assertEquals(InstructionStatus.SETTLED, settled.status());
        assertEquals(sha256, settled.receiptOriginalSha256());
        assertEquals("BANK-REF-9988", settled.receiptProviderRef());

        // Verify payOrder status was correctly updated to EXECUTED (verifying fix for version conflict bug)
        try (Connection c = dataSource.getConnection()) {
            TreasuryOrder updatedOrder = repo.findTreasuryOrderById(c, payOrder.id()).orElseThrow();
            assertEquals(OrderStatus.EXECUTED, updatedOrder.status());
        }

        // Create second instruction to test FAILED_RETURNED
        TreasuryOrder payOrder2 = service.createTreasuryOrder(scope, maker, new CreateOrderInput(
                "PAYMENT", payerAcct.id(), UUID.randomUUID(), null, LocalDate.parse("2026-11-10"),
                "12000.00", "0.00", null, "Rent payment"
        ));
        service.approveTreasuryOrder(scope, reviewer, payOrder2.id());

        PaymentInstruction inst2 = service.createPaymentInstruction(scope, maker, new CreatePaymentInstructionInput(
                payOrder2.id(), payerAcct.id(), "6217001122", "Landlord Corp", "ABC Bank", "12000.00"
        ));
        service.approvePaymentInstruction(scope, reviewer, inst2.id());
        service.dispatchPaymentInstruction(scope, maker, inst2.id());

        UUID adjRefId = UUID.randomUUID();
        PaymentInstruction failed = service.failPaymentInstruction(
                scope, reviewer, inst2.id(), "Account frozen at recipient bank", adjRefId);
        assertEquals(InstructionStatus.FAILED_RETURNED, failed.status());
        assertEquals(adjRefId, failed.adjustmentRefId());
        assertEquals("Account frozen at recipient bank", failed.failureReason());

        // Verify payOrder2 status was updated to FAILED
        try (Connection c = dataSource.getConnection()) {
            TreasuryOrder updatedOrder2 = repo.findTreasuryOrderById(c, payOrder2.id()).orElseThrow();
            assertEquals(OrderStatus.FAILED, updatedOrder2.status());
        }
    }

    @Test
    @DisplayName("TR03: Bank Statement Import, batch deduplication, line hash deduplication, candidate matching, and SoD review")
    void testStatementImportAndMatchingInPostgres() throws Exception {
        BankAccount stmtAcct = service.openAccount(scope, maker, new CreateAccountInput(
                "ACC-STMT", "Statement Target Acct", "ICBC", "Branch C", "GENERAL", UUID.randomUUID()));
        service.reviewAccount(scope, reviewer, stmtAcct.id(), new ReviewAccountInput("ACTIVATE", "OK"));

        // Create an executed order that matches line 1 to verify candidateOrderId resolution
        TreasuryOrder receiptOrder = service.createTreasuryOrder(scope, maker, new CreateOrderInput(
                "RECEIPT", stmtAcct.id(), null, null, LocalDate.parse("2026-11-05"),
                "20000.00", "0.00", null, "Client Alpha payment"
        ));
        service.approveTreasuryOrder(scope, reviewer, receiptOrder.id());
        try (Connection c = dataSource.getConnection()) {
            repo.updateTreasuryOrderStatus(c, receiptOrder.id(), OrderStatus.EXECUTED, reviewer.personId(), null, 2);
        }

        String csv = """
            transactionDate,valueDate,direction,amount,balanceAfter,counterpartyAccountNo,counterpartyName,transactionNo,summary
            2026-11-05,2026-11-05,CREDIT,20000.00,120000.00,622201,Client Alpha,TXN1001,Consulting Fee
            2026-11-06,2026-11-06,DEBIT,8000.00,112000.00,622202,Vendor Beta,TXN1002,Equipment Purchase
            """;

        ImportStatementInput in = new ImportStatementInput(
                stmtAcct.id(), "CSV", csv, LocalDate.parse("2026-11-06"),
                "100000.00", "112000.00", "20000.00", "8000.00", 2
        );

        BankStatementBatch batch = service.importBankStatement(scope, maker, in);
        assertNotNull(batch.id());
        assertEquals(2, batch.recordCount());

        // Duplicate batch import throws IllegalStateException
        assertThrows(IllegalStateException.class, () -> service.importBankStatement(scope, maker, in));

        // Verify lines stored and candidate order ID matched
        try (Connection c = dataSource.getConnection()) {
            List<BankStatementLine> lines = repo.listStatementLines(c, batch.id());
            assertEquals(2, lines.size());

            BankStatementLine l1 = lines.get(0);
            assertEquals(StatementMatchStatus.PROPOSED, l1.matchStatus());
            assertEquals(receiptOrder.id(), l1.matchedOrderId());

            // Review match SoD: maker (importer) cannot review match
            assertThrows(IllegalStateException.class, () ->
                    service.reviewMatch(scope, maker, new MatchReviewInput(l1.id(), receiptOrder.id(), null, "CONFIRM")));

            // Independent reviewer confirms match
            boolean matchOk = service.reviewMatch(scope, reviewer, new MatchReviewInput(
                    l1.id(), receiptOrder.id(), null, "CONFIRM"));
            assertTrue(matchOk);
        }
    }

    @Test
    @DisplayName("TR04: Bank Balance Reconciliation Sheet with unreached item aging, forecast, and SoD confirmation")
    void testReconciliationAndForecastInPostgres() throws Exception {
        BankAccount recAcct = service.openAccount(scope, maker, new CreateAccountInput(
                "ACC-REC", "Recon Account", "BOC", "Branch D", "GENERAL", UUID.randomUUID()));
        service.reviewAccount(scope, reviewer, recAcct.id(), new ReviewAccountInput("ACTIVATE", "OK"));

        LocalDate cutoff = LocalDate.parse("2026-11-30");
        UUID suppEvidence = UUID.randomUUID();

        CreateReconciliationInput recIn = new CreateReconciliationInput(
                recAcct.id(), periodId, 11, cutoff, "100000.00", "95000.00",
                List.of(
                        new UnreachedItemInput("ENTERPRISE_RECORDED_BANK_UNRECORDED", LocalDate.parse("2026-11-28"), "20000.00", "Deposit in transit", null),
                        new UnreachedItemInput("ENTERPRISE_PAID_BANK_UNPAID", LocalDate.parse("2026-11-29"), "10000.00", "Outstanding check", null),
                        new UnreachedItemInput("BANK_CREDITED_ENTERPRISE_UNRECORDED", LocalDate.parse("2026-11-15"), "18000.00", "Bank direct collection", suppEvidence),
                        new UnreachedItemInput("BANK_DEBITED_ENTERPRISE_UNRECORDED", LocalDate.parse("2026-10-10"), "3000.00", "Service fee", null) // >30 days aging!
                )
        );

        BankReconciliation rec = service.createBankReconciliation(scope, maker, recIn);
        assertTrue(rec.isBalanced());
        assertEquals(new BigDecimal("110000.00000000"), rec.adjustedBankBalance());
        assertEquals(new BigDecimal("110000.00000000"), rec.adjustedLedgerBalance());
        assertEquals(ReconciliationStatus.DRAFT, rec.status());

        // SoD confirmation check
        assertThrows(IllegalStateException.class, () ->
                service.confirmBankReconciliation(scope, maker, rec.id()));

        // Independent confirmation
        BankReconciliation confirmed = service.confirmBankReconciliation(scope, reviewer, rec.id());
        assertEquals(ReconciliationStatus.CONFIRMED, confirmed.status());
        assertEquals(reviewer.personId(), confirmed.reviewerPersonId());

        try (Connection c = dataSource.getConnection()) {
            List<UnreachedItem> items = repo.listUnreachedItems(c, rec.id());
            assertEquals(4, items.size());

            // Check overdue warning triggered on the item from 2026-10-10 (51 days old)
            Optional<UnreachedItem> overdue = items.stream().filter(UnreachedItem::isOverdueWarning).findFirst();
            assertTrue(overdue.isPresent());
            assertTrue(overdue.get().agingDays() > 30);
        }

        // Test Cash Forecast
        GenerateForecastInput fcIn = new GenerateForecastInput(
                recAcct.id(), 30, LocalDate.parse("2026-11-10"), "100000.00",
                List.of(
                        new ForecastItemInput("CONFIRMED", "40000.00", "10000.00", "Confirmed orders"),
                        new ForecastItemInput("COMMITTED", "25000.00", "15000.00", "Committed contracts"),
                        new ForecastItemInput("PLANNED", "60000.00", "20000.00", "Planned budget")
                )
        );
        List<CashForecast> forecasts = service.generateCashForecast(scope, maker, fcIn);
        assertEquals(3, forecasts.size());
        for (CashForecast f : forecasts) {
            assertTrue(f.isReadOnlyForecast()); // Strictly read-only
        }
    }

    @Test
    @DisplayName("MD04: Query history preserved for frozen and closed accounts")
    void testDisabledAndClosedAccountQueryHistory() throws Exception {
        BankAccount acct = service.openAccount(scope, maker, new CreateAccountInput(
                "ACC-HIST-01", "History Acct", "ABC", "Sub Branch", "GENERAL", UUID.randomUUID()));
        service.reviewAccount(scope, reviewer, acct.id(), new ReviewAccountInput("ACTIVATE", "OK"));

        // Create an order while active
        TreasuryOrder order = service.createTreasuryOrder(scope, maker, new CreateOrderInput(
                "RECEIPT", acct.id(), null, null, LocalDate.parse("2026-11-10"),
                "1500.00", "0.00", null, "Initial deposit"
        ));
        assertNotNull(order.id());

        // Freeze account
        service.reviewAccount(scope, reviewer, acct.id(), new ReviewAccountInput("FREEZE", "Audit freeze"));

        // Creating order on frozen account must fail
        assertThrows(IllegalStateException.class, () ->
                service.createTreasuryOrder(scope, maker, new CreateOrderInput(
                        "RECEIPT", acct.id(), null, null, LocalDate.parse("2026-11-10"),
                        "200.00", "0.00", null, "Blocked deposit"
                )));

        // Historical query on frozen account succeeds
        try (Connection c = dataSource.getConnection()) {
            List<TreasuryOrder> frozenOrders = repo.listTreasuryOrders(c, tenantId, bookId, acct.id(), null);
            assertEquals(1, frozenOrders.size());
            assertEquals(order.id(), frozenOrders.get(0).id());
        }

        // Zero balance account can be closed and history still queried
        BankAccount zeroAcct = service.openAccount(scope, maker, new CreateAccountInput(
                "ACC-HIST-02", "Zero Balance Acct", "ABC", "Sub Branch", "GENERAL", UUID.randomUUID()));
        service.reviewAccount(scope, reviewer, zeroAcct.id(), new ReviewAccountInput("ACTIVATE", "OK"));
        service.reviewAccount(scope, reviewer, zeroAcct.id(), new ReviewAccountInput("CLOSE", "Closed directly"));

        // Querying closed account succeeds
        try (Connection c = dataSource.getConnection()) {
            Optional<BankAccount> closedAcct = repo.findBankAccountById(c, zeroAcct.id());
            assertTrue(closedAcct.isPresent());
            assertEquals(BankAccountStatus.CLOSED, closedAcct.get().status());

            List<TreasuryOrder> closedOrders = repo.listTreasuryOrders(c, tenantId, bookId, zeroAcct.id(), null);
            assertTrue(closedOrders.isEmpty());
        }
    }

    @Test
    @DisplayName("Security: Immutable Event Trigger prevents tampering in PostgreSQL 18")
    void testImmutableEventTriggerInPostgres() throws Exception {
        UUID eventId = UUID.randomUUID();
        try (Connection c = dataSource.getConnection()) {
            repo.recordTreasuryEvent(c, new TreasuryEvent(
                    eventId, tenantId, bookId, "SECURITY_TEST", UUID.randomUUID(), "TEST_ACTION",
                    maker.personId(), "{\"test\":true}", java.time.Instant.now()
            ));

            // Attempting update on plugin_finance_treasury_event must raise exception
            try (Statement s = c.createStatement()) {
                SQLException ex = assertThrows(SQLException.class,
                        () -> s.executeUpdate("UPDATE plugin_finance_treasury_event SET action = 'HACKED' WHERE id = '" + eventId + "'"));
                assertTrue(ex.getMessage().contains("plugin_finance_treasury_event rows are immutable"));
            }
        }
    }
}

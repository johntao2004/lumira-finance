package com.lumira.finance.treasury;

import com.lumira.finance.treasury.TreasuryModel.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TreasuryRulesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("MD04: CNY amount validation and scale")
    void testCnyAmountValidation() {
        assertDoesNotThrow(() -> TreasuryRules.validateCnyAmount("100.50", "CNY"));
        assertDoesNotThrow(() -> TreasuryRules.validateCnyAmount("0.01", "CNY"));
        assertDoesNotThrow(() -> TreasuryRules.validateCnyAmount("5000", "CNY"));

        // Reject more than 2 decimals
        assertThrows(IllegalArgumentException.class, () -> TreasuryRules.validateCnyAmount("100.555", "CNY"));
        assertThrows(IllegalArgumentException.class, () -> TreasuryRules.validateCnyAmount("abc", "CNY"));

        BigDecimal parsed = TreasuryRules.parsePositiveCny("1250.80", "Amount");
        assertEquals(new BigDecimal("1250.80000000"), parsed);
    }

    @Test
    @DisplayName("MD04: Natural person separation (SoD) prevents self-review and null reviewer")
    void testNaturalPersonSeparation() {
        UUID personA = UUID.randomUUID();
        UUID personB = UUID.randomUUID();

        assertDoesNotThrow(() -> TreasuryRules.requireIndependentPerson(personA, personB, "复核"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> TreasuryRules.requireIndependentPerson(personA, personA, "复核"));
        assertTrue(ex.getMessage().contains("职责分离失败"));

        // Null reviewer is strictly rejected
        IllegalArgumentException nullEx = assertThrows(IllegalArgumentException.class,
                () -> TreasuryRules.requireIndependentPerson(personA, null, "审批"));
        assertTrue(nullEx.getMessage().contains("必须指定独立的复核/审批人"));
    }

    @Test
    @DisplayName("MD04: Numbering policy approval SoD and idempotency")
    void testNumberingPolicyApproval() {
        UUID maker = UUID.randomUUID();
        UUID reviewer = UUID.randomUUID();
        NumberingPolicy draft = new NumberingPolicy(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                11, "PAYMENT", "PAY-", "202611", 0, false, maker, null, 1, Instant.now()
        );

        // Self-approval fails SoD
        assertThrows(IllegalStateException.class, () -> TreasuryRules.evaluateNumberingPolicyApproval(draft, maker));

        // Independent approval succeeds
        NumberingPolicy approved = TreasuryRules.evaluateNumberingPolicyApproval(draft, reviewer);
        assertTrue(approved.isApproved());
        assertEquals(reviewer, approved.reviewerPersonId());

        // Repeated approval fails
        assertThrows(IllegalStateException.class, () -> TreasuryRules.evaluateNumberingPolicyApproval(approved, reviewer));
    }

    @Test
    @DisplayName("MD04: Bank account state transitions and zero-balance close requirement")
    void testBankAccountTransitions() {
        UUID maker = UUID.randomUUID();
        UUID reviewer = UUID.randomUUID();
        UUID acctId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();

        BankAccount acct = new BankAccount(
                acctId, tenantId, bookId, "1234567890", "Main Ops", "ICBC", "Branch 1",
                BankAccountType.BASIC, "CNY", UUID.randomUUID(), BankAccountStatus.PENDING_REVIEW,
                maker, null, null, null, 1, Instant.now(), Instant.now()
        );

        // ACTIVATE
        BankAccountStatus s1 = TreasuryRules.evaluateAccountTransition(acct, "ACTIVATE", reviewer, BigDecimal.ZERO);
        assertEquals(BankAccountStatus.ACTIVE, s1);

        // FREEZE
        BankAccount activeAcct = new BankAccount(
                acctId, tenantId, bookId, "1234567890", "Main Ops", "ICBC", "Branch 1",
                BankAccountType.BASIC, "CNY", UUID.randomUUID(), BankAccountStatus.ACTIVE,
                maker, reviewer, null, null, 2, Instant.now(), Instant.now()
        );
        BankAccountStatus s2 = TreasuryRules.evaluateAccountTransition(activeAcct, "FREEZE", reviewer, BigDecimal.ZERO);
        assertEquals(BankAccountStatus.FROZEN, s2);

        // CLOSE with non-zero balance MUST FAIL
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> TreasuryRules.evaluateAccountTransition(activeAcct, "CLOSE", reviewer, new BigDecimal("100.00")));
        assertTrue(ex.getMessage().contains("余额不为零"));

        // CLOSE with zero balance SUCCEEDS
        BankAccountStatus s3 = TreasuryRules.evaluateAccountTransition(activeAcct, "CLOSE", reviewer, BigDecimal.ZERO);
        assertEquals(BankAccountStatus.CLOSED, s3);
    }

    @Test
    @DisplayName("MD04: Numbering policy formats sequential numbers monotonically")
    void testNumberingFormatting() {
        String no1 = TreasuryRules.formatTreasuryOrderNo("PAY-", "202611", 1);
        assertEquals("PAY-202611-0001", no1);

        String no2 = TreasuryRules.formatTreasuryOrderNo("REC-", "202611", 42);
        assertEquals("REC-202611-0042", no2);

        assertThrows(IllegalArgumentException.class, () -> TreasuryRules.formatTreasuryOrderNo("PAY-", "202611", 0));
    }

    @Test
    @DisplayName("TR01: Internal transfer pairing and cross-book prevention")
    void testTransferPairing() {
        UUID tenantA = UUID.randomUUID();
        UUID bookA = UUID.randomUUID();
        UUID bookB = UUID.randomUUID();
        UUID maker = UUID.randomUUID();

        BankAccount acct1 = new BankAccount(
                UUID.randomUUID(), tenantA, bookA, "ACC1", "Acc 1", "Bank A", "Branch",
                BankAccountType.BASIC, "CNY", UUID.randomUUID(), BankAccountStatus.ACTIVE,
                maker, null, null, null, 1, Instant.now(), Instant.now()
        );
        BankAccount acct2 = new BankAccount(
                UUID.randomUUID(), tenantA, bookA, "ACC2", "Acc 2", "Bank A", "Branch",
                BankAccountType.GENERAL, "CNY", UUID.randomUUID(), BankAccountStatus.ACTIVE,
                maker, null, null, null, 1, Instant.now(), Instant.now()
        );
        BankAccount acctCrossBook = new BankAccount(
                UUID.randomUUID(), tenantA, bookB, "ACC3", "Acc 3", "Bank A", "Branch",
                BankAccountType.GENERAL, "CNY", UUID.randomUUID(), BankAccountStatus.ACTIVE,
                maker, null, null, null, 1, Instant.now(), Instant.now()
        );

        // Valid pairing
        assertDoesNotThrow(() -> TreasuryRules.validateTransferPairing(acct1, acct2, new BigDecimal("5000.00"), new BigDecimal("15.00")));

        // Same account rejected
        assertThrows(IllegalArgumentException.class,
                () -> TreasuryRules.validateTransferPairing(acct1, acct1, new BigDecimal("5000.00"), BigDecimal.ZERO));

        // Cross-book rejected
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> TreasuryRules.validateTransferPairing(acct1, acctCrossBook, new BigDecimal("5000.00"), BigDecimal.ZERO));
        assertTrue(ex.getMessage().contains("跨法人不能使用同账套内部转账捷径"));
    }

    @Test
    @DisplayName("TR02: Payment instruction lifecycle with strict UNKNOWN hold and SHA-256 receipt check")
    void testPaymentInstructionLifecycle() {
        UUID maker = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        UUID verifier = UUID.randomUUID();
        UUID instId = UUID.randomUUID();

        PaymentInstruction inst = new PaymentInstruction(
                instId, UUID.randomUUID(), UUID.randomUUID(), "PI-1001", UUID.randomUUID(), UUID.randomUUID(),
                "6222021000", "Supplier A", "ICBC", new BigDecimal("10000.00000000"),
                InstructionStatus.DRAFT, "NOT_VERIFIED", maker, null, null, null, null, null, null, null, null,
                1, Instant.now(), Instant.now()
        );

        // Approver cannot be maker
        assertThrows(IllegalStateException.class, () -> TreasuryRules.validateInstructionApproval(inst, maker));
        assertDoesNotThrow(() -> TreasuryRules.validateInstructionApproval(inst, approver));

        // Approve and dispatch to DISPATCHED_UNKNOWN
        PaymentInstruction approved = new PaymentInstruction(
                inst.id(), inst.tenantId(), inst.bookId(), inst.instructionNo(), inst.treasuryOrderId(),
                inst.payerAccountId(), inst.payeeAccountNo(), inst.payeeAccountName(), inst.payeeBankName(),
                inst.amount(), InstructionStatus.APPROVED, "NOT_VERIFIED", maker, approver, null,
                null, null, null, null, null, null, 2, Instant.now(), Instant.now()
        );
        PaymentInstruction dispatched = TreasuryRules.transitionToDispatched(approved);
        assertEquals(InstructionStatus.DISPATCHED_UNKNOWN, dispatched.status());
        assertEquals("NOT_VERIFIED", dispatched.bankDirectChannel());

        // Settling requires valid 64-hex SHA-256 receipt
        assertThrows(IllegalArgumentException.class,
                () -> TreasuryRules.transitionToSettled(dispatched, "TXN-999", "invalid-sha", verifier));

        String validSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        PaymentInstruction settled = TreasuryRules.transitionToSettled(dispatched, "TXN-999", validSha256, verifier);
        assertEquals(InstructionStatus.SETTLED, settled.status());
        assertEquals(validSha256, settled.receiptOriginalSha256());
        assertEquals("TXN-999", settled.receiptProviderRef());
        assertEquals(verifier, settled.receiptVerifierPersonId());

        // Failed returned preserves failure reason and lineage
        UUID adjId = UUID.randomUUID();
        PaymentInstruction failed = TreasuryRules.transitionToFailedReturned(
                dispatched, "银行账户不存在原路退回", adjId, verifier);
        assertEquals(InstructionStatus.FAILED_RETURNED, failed.status());
        assertEquals("银行账户不存在原路退回", failed.failureReason());
        assertEquals(adjId, failed.adjustmentRefId());
    }

    @Test
    @DisplayName("TR03: CSV statement parsing, line hashing, and source totals reconciliation")
    void testStatementCsvParsingAndTotals() {
        String csv = """
            transactionDate,valueDate,direction,amount,balanceAfter,counterpartyAccountNo,counterpartyName,transactionNo,summary
            2026-11-01,2026-11-01,CREDIT,15000.00,65000.00,6221001,Client A,TX001,Payment for services
            2026-11-02,2026-11-02,DEBIT,5000.00,60000.00,6221002,Supplier B,TX002,Office rent
            """;

        List<RawStatementLine> lines = TreasuryRules.parseCsvStatement(csv);
        assertEquals(2, lines.size());
        assertEquals(new BigDecimal("15000.00000000"), lines.get(0).amount());
        assertEquals(new BigDecimal("5000.00000000"), lines.get(1).amount());

        // Reconcile source totals: opening 50000 + 15000 - 5000 = 60000
        BigDecimal openBal = new BigDecimal("50000.00000000");
        BigDecimal closeBal = new BigDecimal("60000.00000000");
        BigDecimal totalCredit = new BigDecimal("15000.00000000");
        BigDecimal totalDebit = new BigDecimal("5000.00000000");

        assertDoesNotThrow(() -> TreasuryRules.reconcileSourceTotals(openBal, closeBal, totalCredit, totalDebit, 2, lines));

        // Mismatched closing balance throws
        assertThrows(IllegalArgumentException.class,
                () -> TreasuryRules.reconcileSourceTotals(openBal, new BigDecimal("65000.00"), totalCredit, totalDebit, 2, lines));

        // Line hash calculation
        UUID acctId = UUID.randomUUID();
        String hash1 = TreasuryRules.computeLineHash(
                acctId, lines.get(0).transactionDate(), lines.get(0).direction(),
                lines.get(0).amount(), lines.get(0).balanceAfter(), lines.get(0).transactionNo(), lines.get(0).counterpartyAccountNo());
        assertEquals(64, hash1.length());
    }

    @Test
    @DisplayName("TR03: Robust CSV parsing with quoted commas and escaped quotes")
    void testQuotedCsvParsing() {
        String csv = """
            transactionDate,valueDate,direction,amount,balanceAfter,counterpartyAccountNo,counterpartyName,transactionNo,summary
            2026-11-03,2026-11-03,DEBIT,3500.00,56500.00,6221003,"Shenzhen Tech, Ltd",TX003,"Software, license ""V4\"""
            """;
        List<RawStatementLine> lines = TreasuryRules.parseCsvStatement(csv);
        assertEquals(1, lines.size());
        assertEquals("Shenzhen Tech, Ltd", lines.get(0).counterpartyName());
        assertEquals("Software, license \"V4\"", lines.get(0).summary());
    }

    @Test
    @DisplayName("TR04: Bank reconciliation confirmation SoD and balance check")
    void testReconciliationConfirmation() {
        UUID maker = UUID.randomUUID();
        UUID reviewer = UUID.randomUUID();
        BankReconciliation balanced = new BankReconciliation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 11, LocalDate.parse("2026-11-30"),
                new BigDecimal("100000.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100000.00"),
                new BigDecimal("100000.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100000.00"),
                true, ReconciliationStatus.DRAFT, maker, null, 1, Instant.now(), Instant.now()
        );

        // Self confirmation fails SoD
        assertThrows(IllegalStateException.class, () -> TreasuryRules.evaluateReconciliationConfirmation(balanced, maker));

        // Independent confirmation succeeds
        BankReconciliation confirmed = TreasuryRules.evaluateReconciliationConfirmation(balanced, reviewer);
        assertEquals(ReconciliationStatus.CONFIRMED, confirmed.status());
        assertEquals(reviewer, confirmed.reviewerPersonId());

        // Unbalanced sheet cannot be confirmed
        BankReconciliation unbalanced = new BankReconciliation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 11, LocalDate.parse("2026-11-30"),
                new BigDecimal("100000.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100000.00"),
                new BigDecimal("95000.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("95000.00"),
                false, ReconciliationStatus.DRAFT, maker, null, 1, Instant.now(), Instant.now()
        );
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> TreasuryRules.evaluateReconciliationConfirmation(unbalanced, reviewer));
        assertTrue(ex.getMessage().contains("未平衡，禁止确认"));
    }

    @Test
    @DisplayName("TR03: Matching proposal turns multiple candidate matches into AMBIGUOUS_MULTI_CANDIDATE")
    void testMatchingProposalAmbiguity() {
        assertEquals(StatementMatchStatus.UNMATCHED, TreasuryRules.evaluateMatchProposal(0));
        assertEquals(StatementMatchStatus.PROPOSED, TreasuryRules.evaluateMatchProposal(1));
        assertEquals(StatementMatchStatus.AMBIGUOUS_MULTI_CANDIDATE, TreasuryRules.evaluateMatchProposal(2));
        assertEquals(StatementMatchStatus.AMBIGUOUS_MULTI_CANDIDATE, TreasuryRules.evaluateMatchProposal(5));
    }

    @Test
    @DisplayName("TR04: Bank Balance Reconciliation 4-item formula and aging overdue warning")
    void testBankReconciliationFormula() {
        UUID recId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        UUID acctId = UUID.randomUUID();
        UUID prep = UUID.randomUUID();
        UUID suppEvidence = UUID.randomUUID();
        LocalDate cutoff = LocalDate.parse("2026-11-30");

        // Bank statement balance: 100,000.00
        // Enterprise recorded, bank unrecorded (收): 20,000.00
        // Enterprise paid, bank unpaid (付): 10,000.00
        // Adjusted Bank Balance = 100,000 + 20,000 - 10,000 = 110,000.00

        // Enterprise ledger balance: 95,000.00
        // Bank credited, enterprise unrecorded (收): 18,000.00
        // Bank debited, enterprise unrecorded (付): 3,000.00
        // Adjusted Ledger Balance = 95,000 + 18,000 - 3,000 = 110,000.00

        List<UnreachedItemInput> items = List.of(
                new UnreachedItemInput("ENTERPRISE_RECORDED_BANK_UNRECORDED", LocalDate.parse("2026-11-28"), "20000.00", "In-transit customer deposit", null),
                new UnreachedItemInput("ENTERPRISE_PAID_BANK_UNPAID", LocalDate.parse("2026-11-29"), "10000.00", "Outstanding check #101", null),
                new UnreachedItemInput("BANK_CREDITED_ENTERPRISE_UNRECORDED", LocalDate.parse("2026-11-15"), "18000.00", "Direct collection by bank", suppEvidence),
                new UnreachedItemInput("BANK_DEBITED_ENTERPRISE_UNRECORDED", LocalDate.parse("2026-10-15"), "3000.00", "Bank fee charges", null)
        );

        BankReconciliation rec = TreasuryRules.computeBankReconciliation(
                recId, tenantId, bookId, acctId, UUID.randomUUID(), 11, cutoff,
                new BigDecimal("100000.00"), new BigDecimal("95000.00"), items, prep);

        assertEquals(new BigDecimal("110000.00000000"), rec.adjustedBankBalance());
        assertEquals(new BigDecimal("110000.00000000"), rec.adjustedLedgerBalance());
        assertTrue(rec.isBalanced());

        // Aging check
        // 2026-10-15 to 2026-11-30 is 46 days -> OVERDUE WARNING
        int agingOld = TreasuryRules.calculateAgingDays(LocalDate.parse("2026-10-15"), cutoff);
        assertEquals(46, agingOld);
        assertTrue(TreasuryRules.isOverdueWarning(agingOld));

        int agingNew = TreasuryRules.calculateAgingDays(LocalDate.parse("2026-11-28"), cutoff);
        assertEquals(2, agingNew);
        assertFalse(TreasuryRules.isOverdueWarning(agingNew));
    }

    @Test
    @DisplayName("TR04: Cash forecast tiered calculations and read-only invariant")
    void testCashForecastTiered() {
        UUID fId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        UUID acctId = UUID.randomUUID();
        LocalDate date = LocalDate.parse("2026-11-10");

        List<ForecastItemInput> items = List.of(
                new ForecastItemInput("CONFIRMED", "50000.00", "10000.00", "Settled order TR-001"),
                new ForecastItemInput("COMMITTED", "20000.00", "15000.00", "Approved AP batch"),
                new ForecastItemInput("PLANNED", "80000.00", "30000.00", "Budget projection")
        );

        CashForecast fConf = TreasuryRules.computeForecast(
                fId, tenantId, bookId, acctId, 30, date, new BigDecimal("100000.00"), ForecastTier.CONFIRMED, items);
        assertEquals(new BigDecimal("100000.00000000"), fConf.openingCash());
        assertEquals(new BigDecimal("50000.00000000"), fConf.projectedInflow());
        assertEquals(new BigDecimal("10000.00000000"), fConf.projectedOutflow());
        assertEquals(new BigDecimal("140000.00000000"), fConf.closingCash());
        assertTrue(fConf.isReadOnlyForecast()); // Strictly read-only

        CashForecast fComm = TreasuryRules.computeForecast(
                fId, tenantId, bookId, acctId, 30, date, new BigDecimal("100000.00"), ForecastTier.COMMITTED, items);
        assertEquals(new BigDecimal("105000.00000000"), fComm.closingCash());

        CashForecast fPlan = TreasuryRules.computeForecast(
                fId, tenantId, bookId, acctId, 30, date, new BigDecimal("100000.00"), ForecastTier.PLANNED, items);
        assertEquals(new BigDecimal("150000.00000000"), fPlan.closingCash());
    }

    @Test
    @DisplayName("Jackson 3 serialization round-trip test")
    void testJacksonRoundTrip() throws Exception {
        UUID id = UUID.randomUUID();
        BankAccount acct = new BankAccount(
                id, UUID.randomUUID(), UUID.randomUUID(), "6221008899", "Tech Operations", "CMB", "Hi-Tech Branch",
                BankAccountType.GENERAL, "CNY", UUID.randomUUID(), BankAccountStatus.ACTIVE,
                UUID.randomUUID(), UUID.randomUUID(), null, null, 1, Instant.now(), Instant.now()
        );

        String json = mapper.writeValueAsString(acct);
        assertNotNull(json);
        assertTrue(json.contains("6221008899"));
        assertTrue(json.contains("Tech Operations"));

        BankAccount deserialized = mapper.readValue(json, BankAccount.class);
        assertEquals(acct.id(), deserialized.id());
        assertEquals(acct.accountNo(), deserialized.accountNo());
    }
}

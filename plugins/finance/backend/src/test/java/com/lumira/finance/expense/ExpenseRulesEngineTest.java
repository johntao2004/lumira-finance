package com.lumira.finance.expense;

import com.lumira.finance.expense.ExpenseDomain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class ExpenseRulesEngineTest {

    private TravelPolicyEngine travelEngine;
    private ExpenseRulesEngine rulesEngine;
    private ExpenseAccountingAdapter accountingAdapter;

    private UUID tenantId;
    private UUID bookId;
    private UUID applicantId;
    private UUID reviewerId;
    private UUID approverId;
    private UUID cashierId;

    @BeforeEach
    void setup() {
        travelEngine = new TravelPolicyEngine();
        rulesEngine = new ExpenseRulesEngine();
        accountingAdapter = new ExpenseAccountingAdapter();

        tenantId = UUID.randomUUID();
        bookId = UUID.randomUUID();
        applicantId = UUID.randomUUID();
        reviewerId = UUID.randomUUID();
        approverId = UUID.randomUUID();
        cashierId = UUID.randomUUID();
    }

    // --- EXP04 Travel & Expense Standards ---

    @Test
    void testTravelPolicyEvaluationAndExceptionWorkflow() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 12, 31);

        TravelPolicy draft = travelEngine.createDraftPolicy(
                tenantId, "POL-2026-01", 1, "2026 Domestic Travel Standard", from, to, approverId
        );
        assertEquals(PolicyStatus.DRAFT, draft.status());

        TravelPolicy active = travelEngine.activatePolicy(draft);
        assertEquals(PolicyStatus.ACTIVE, active.status());

        TravelPolicyCap hotelCap = new TravelPolicyCap(
                UUID.randomUUID(), active.id(), CityTier.TIER_1, EmployeeRank.STAFF,
                ExpenseCategory.HOTEL, ExpenseDomain.cny("500.00", false), ExpenseDomain.CURRENCY_CNY
        );
        List<TravelPolicyCap> caps = List.of(hotelCap);

        // Case 1: Within cap (450 CNY) -> COMPLIANT
        LocalDate travelDate = LocalDate.of(2026, 5, 10);
        TravelPolicyEngine.CapEvaluationResult res1 = travelEngine.evaluateCap(
                active, caps, travelDate, CityTier.TIER_1, EmployeeRank.STAFF,
                ExpenseCategory.HOTEL, ExpenseDomain.cny("450.00", false)
        );
        assertEquals(TravelPolicyEngine.EvaluationStatus.COMPLIANT, res1.status());
        assertEquals("0.00", ExpenseDomain.toCny2String(res1.excessAmount()));

        // Case 2: Exceeds cap (700 CNY) -> OVER_CAP (excess 200 CNY)
        TravelPolicyEngine.CapEvaluationResult res2 = travelEngine.evaluateCap(
                active, caps, travelDate, CityTier.TIER_1, EmployeeRank.STAFF,
                ExpenseCategory.HOTEL, ExpenseDomain.cny("700.00", false)
        );
        assertEquals(TravelPolicyEngine.EvaluationStatus.OVER_CAP, res2.status());
        assertEquals("200.00", ExpenseDomain.toCny2String(res2.excessAmount()));

        // Case 3: Request exception for excess
        TravelException ex = travelEngine.createExceptionRequest(
                tenantId, null, 1, hotelCap.id(), ExpenseDomain.cny("700.00", false),
                hotelCap.dailyCapAmount(), applicantId, "Exhibition peak season hotel surge"
        );
        assertEquals(TravelExceptionStatus.PENDING, ex.status());
        assertEquals("200.00", ExpenseDomain.toCny2String(ex.excessAmount()));

        // SoD check: Applicant cannot approve their own exception
        assertThrows(ValidationException.class, () -> travelEngine.decideException(ex, applicantId, true, "Self approval"));

        // Independent approval
        TravelException approvedEx = travelEngine.decideException(ex, approverId, true, "Approved for exhibition");
        assertEquals(TravelExceptionStatus.APPROVED, approvedEx.status());
        assertEquals(approverId, approvedEx.approverPersonId());
    }

    // --- EXP01 Expense Pre-Approval Requisition ---

    @Test
    void testExpenseRequisitionLifecycleAndEncumbranceRelease() {
        BigDecimal budget = ExpenseDomain.cny("10000.00", false);
        LocalDate validUntil = LocalDate.of(2026, 12, 31);

        ExpenseRequisition req = rulesEngine.createDraftRequisition(
                tenantId, bookId, "REQ-2026-001", applicantId, UUID.randomUUID(), null,
                "Annual client roadshow", budget, validUntil
        );
        assertEquals(RequisitionStatus.DRAFT, req.status());
        assertEquals("10000.00", ExpenseDomain.toCny2String(req.availableAmount()));

        ExpenseRequisition submitted = rulesEngine.submitRequisition(req);
        assertEquals(RequisitionStatus.SUBMITTED, submitted.status());
        assertEquals("10000.00", ExpenseDomain.toCny2String(submitted.reservedAmount()));

        // SoD: Applicant cannot self-approve
        assertThrows(ValidationException.class, () -> rulesEngine.approveRequisition(submitted, applicantId));

        ExpenseRequisition approved = rulesEngine.approveRequisition(submitted, approverId);
        assertEquals(RequisitionStatus.APPROVED, approved.status());

        // Consume 3000.00 CNY
        ExpenseRequisition consumed = rulesEngine.consumeEncumbrance(approved, ExpenseDomain.cny("3000.00", false));
        assertEquals("3000.00", ExpenseDomain.toCny2String(consumed.utilizedAmount()));
        assertEquals("7000.00", ExpenseDomain.toCny2String(consumed.reservedAmount()));

        // Cancel / Close remaining unutilized 7000.00 CNY
        ExpenseRequisition closed = rulesEngine.cancelOrCloseRequisition(consumed);
        assertEquals(RequisitionStatus.CLOSED, closed.status());
        assertEquals("0.00", ExpenseDomain.toCny2String(closed.reservedAmount())); // released!
        assertEquals("3000.00", ExpenseDomain.toCny2String(closed.utilizedAmount())); // preserved!
    }

    // --- EXP02 Employee Cash Advance ---

    @Test
    void testAdvanceBorrow1000Offset800Refund200Settles() {
        BigDecimal principal = ExpenseDomain.cny("1000.00", false);

        ExpenseAdvance adv = rulesEngine.createDraftAdvance(
                tenantId, bookId, "ADV-2026-001", applicantId, null,
                "Field investigation cash advance", principal, applicantId
        );
        assertEquals(AdvanceStatus.DRAFT, adv.status());
        assertEquals("1000.00", ExpenseDomain.toCny2String(adv.remainingBalance()));

        ExpenseAdvance submitted = rulesEngine.submitAdvance(adv);

        // SoD check: Approver cannot be applicant
        assertThrows(ValidationException.class, () -> rulesEngine.approveAdvance(submitted, applicantId));

        ExpenseAdvance approved = rulesEngine.approveAdvance(submitted, approverId);
        assertEquals(AdvanceStatus.APPROVED, approved.status());

        // SoD check: Cashier cannot be applicant or approver
        assertThrows(ValidationException.class, () -> rulesEngine.payoutAdvance(approved, applicantId, "PAY-REF-1"));
        assertThrows(ValidationException.class, () -> rulesEngine.payoutAdvance(approved, approverId, "PAY-REF-1"));

        ExpenseAdvance paid = rulesEngine.payoutAdvance(approved, cashierId, "BANK-TX-998822");
        assertEquals(AdvanceStatus.PAID, paid.status());
        assertEquals("BANK-TX-998822", paid.payoutRef());

        // Reimbursement offset 800.00 CNY
        ExpenseAdvance offsetAdv = rulesEngine.offsetAdvance(paid, ExpenseDomain.cny("800.00", false));
        assertEquals(AdvanceStatus.PARTIALLY_OFFSET, offsetAdv.status());
        assertEquals("800.00", ExpenseDomain.toCny2String(offsetAdv.offsetAmount()));
        assertEquals("200.00", ExpenseDomain.toCny2String(offsetAdv.remainingBalance()));

        // Refund remaining 200.00 CNY cash
        ExpenseRulesEngine.RefundResult refundRes = rulesEngine.refundAdvanceCash(
                offsetAdv, "REFUND-2026-01", ExpenseDomain.cny("200.00", false),
                LocalDate.now(), "CASH", "CASH-RECEIPT-01", cashierId
        );

        ExpenseAdvance settled = refundRes.updatedAdvance();
        assertEquals(AdvanceStatus.SETTLED, settled.status());
        assertEquals("0.00", ExpenseDomain.toCny2String(settled.remainingBalance()));
        assertEquals("800.00", ExpenseDomain.toCny2String(settled.offsetAmount()));
        assertEquals("200.00", ExpenseDomain.toCny2String(settled.refundedAmount()));
        assertEquals("1000.00", ExpenseDomain.toCny2String(settled.principalAmount()));
        assertEquals("REFUND-2026-01", refundRes.refundSlip().refundNo());
    }

    // --- EXP03 Reimbursement Claim, Partial Recognition, VAT & Payables ---

    @Test
    void testClaimPartialRecognitionAndVatInputSplit() {
        // Line 1: Submitted 1200, Disallowed personal 200 (personal entertainment) -> Reimbursable 1000.
        // VAT rate 6% (0.060000000000) -> Net expense 943.40, VAT input 56.60
        UUID dept1 = UUID.randomUUID();
        ExpenseClaimLine l1 = new ExpenseClaimLine(
                UUID.randomUUID(), null, 1, ExpenseCategory.MEAL, dept1, null,
                UUID.randomUUID(), "INV-001", "12345678", "a".repeat(64),
                ExpenseDomain.cny("1200.00", false), ExpenseDomain.cny("1000.00", false),
                ExpenseDomain.cny("200.00", false), "Personal dinner excluded",
                new BigDecimal("0.060000000000"), BigDecimal.ZERO, BigDecimal.ZERO, null, false
        );

        // Line 2: Submitted 300, Approved 300 (transportation, no VAT)
        UUID dept2 = UUID.randomUUID();
        ExpenseClaimLine l2 = new ExpenseClaimLine(
                UUID.randomUUID(), null, 2, ExpenseCategory.TRANSPORT, dept2, null,
                UUID.randomUUID(), "INV-002", "87654321", "b".repeat(64),
                ExpenseDomain.cny("300.00", false), ExpenseDomain.cny("300.00", false),
                BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, false
        );

        // Offset against active advance: 800 CNY
        UUID advId = UUID.randomUUID();
        Map<UUID, BigDecimal> offsets = Map.of(advId, ExpenseDomain.cny("800.00", false));

        ExpenseRulesEngine.ClaimBuildResult res = rulesEngine.buildClaim(
                tenantId, bookId, "CLM-2026-001", applicantId, null,
                "Quarterly business review travel", LocalDate.of(2026, 6, 1),
                applicantId, List.of(l1, l2), offsets
        );

        ExpenseClaim claim = res.claim();
        assertEquals(ClaimStatus.DRAFT, claim.status());
        assertEquals("1500.00", ExpenseDomain.toCny2String(claim.submittedAmount())); // 1200 + 300
        assertEquals("1300.00", ExpenseDomain.toCny2String(claim.approvedAmount()));  // 1000 + 300
        assertEquals("200.00", ExpenseDomain.toCny2String(claim.disallowedAmount())); // 200 disallowed
        assertEquals("800.00", ExpenseDomain.toCny2String(claim.offsetAdvanceAmount())); // 800 offset
        assertEquals("500.00", ExpenseDomain.toCny2String(claim.payableAmount())); // 1300 - 800 = 500 net payable

        // Verify Line 1 calculated VAT split
        ExpenseClaimLine builtL1 = res.lines().get(0);
        assertEquals("943.40", ExpenseDomain.toCny2String(builtL1.netExpenseAmount()));
        assertEquals("56.60", ExpenseDomain.toCny2String(builtL1.vatInputAmount()));
        assertEquals("1000.00", ExpenseDomain.toCny2String(builtL1.approvedReimbursableAmount()));

        // Verification of Segregation of Duties (submitter != reviewer != approver)
        ExpenseClaim submitted = rulesEngine.submitClaim(claim);

        // Submitter cannot review
        assertThrows(ValidationException.class, () -> rulesEngine.reviewClaim(submitted, applicantId));
        ExpenseClaim reviewed = rulesEngine.reviewClaim(submitted, reviewerId);
        assertEquals(ClaimStatus.REVIEWED, reviewed.status());

        // Submitter cannot approve
        assertThrows(ValidationException.class, () -> rulesEngine.approveClaim(reviewed, applicantId));
        // Reviewer cannot approve
        assertThrows(ValidationException.class, () -> rulesEngine.approveClaim(reviewed, reviewerId));

        ExpenseClaim approved = rulesEngine.approveClaim(reviewed, approverId);
        assertEquals(ClaimStatus.APPROVED, approved.status());

        // Payable ledger generation
        ExpensePayableLedger payable = rulesEngine.generatePayableLedger(approved);
        assertEquals("500.00", ExpenseDomain.toCny2String(payable.payableAmount()));
        assertEquals("500.00", ExpenseDomain.toCny2String(payable.outstandingBalance()));
        assertEquals(SettlementStatus.UNPAID, payable.settlementStatus());

        // Partial payment: 300 CNY
        ExpenseRulesEngine.DisburseResult disburseRes1 = rulesEngine.disbursePayment(
                payable, approved, "PAY-01", ExpenseDomain.cny("300.00", false),
                LocalDate.now(), "BANK", "TX-001", cashierId, UUID.randomUUID()
        );
        assertEquals(SettlementStatus.PARTIALLY_PAID, disburseRes1.updatedLedger().settlementStatus());
        assertEquals("200.00", ExpenseDomain.toCny2String(disburseRes1.updatedLedger().outstandingBalance()));
        assertEquals("300.00", ExpenseDomain.toCny2String(disburseRes1.updatedClaim().paidAmount()));
        assertEquals(ClaimStatus.PAID, disburseRes1.updatedClaim().status());

        // Final payment: 200 CNY -> SETTLED
        ExpenseRulesEngine.DisburseResult disburseRes2 = rulesEngine.disbursePayment(
                disburseRes1.updatedLedger(), disburseRes1.updatedClaim(), "PAY-02", ExpenseDomain.cny("200.00", false),
                LocalDate.now(), "BANK", "TX-002", cashierId, UUID.randomUUID()
        );
        assertEquals(SettlementStatus.SETTLED, disburseRes2.updatedLedger().settlementStatus());
        assertEquals("0.00", ExpenseDomain.toCny2String(disburseRes2.updatedLedger().outstandingBalance()));
        assertEquals("500.00", ExpenseDomain.toCny2String(disburseRes2.updatedClaim().paidAmount()));
        assertEquals(ClaimStatus.SETTLED, disburseRes2.updatedClaim().status());
    }

    // --- Accounting Adapter Journal Generation & Full Reversal ---

    @Test
    void testClaimJournalPlanAndFullReversal() {
        UUID dept = UUID.randomUUID();
        ExpenseClaimLine l1 = new ExpenseClaimLine(
                UUID.randomUUID(), null, 1, ExpenseCategory.OFFICE, dept, null,
                null, null, null, null,
                ExpenseDomain.cny("1060.00", false), ExpenseDomain.cny("1060.00", false),
                BigDecimal.ZERO, null, new BigDecimal("0.060000000000"),
                ExpenseDomain.cny("60.00", false), ExpenseDomain.cny("1000.00", false), null, false
        );

        ExpenseClaim claim = new ExpenseClaim(
                UUID.randomUUID(), tenantId, bookId, "CLM-JRNL-01", applicantId, null,
                "Office supplies reimbursement", LocalDate.of(2026, 4, 1), ExpenseDomain.CURRENCY_CNY,
                ExpenseDomain.cny("1060.00", false), ExpenseDomain.cny("1060.00", false),
                BigDecimal.ZERO, ExpenseDomain.cny("260.00", false), // 260 advance offset
                ExpenseDomain.cny("800.00", false), BigDecimal.ZERO, ClaimStatus.APPROVED,
                applicantId, reviewerId, approverId, null, null, null, null, null, 1
        );

        ExpenseJournalPlan plan = accountingAdapter.generateClaimJournal(claim, List.of(l1), bookId);
        assertEquals("EXPENSE_CLAIM", plan.journalType());
        assertEquals("1060.00", ExpenseDomain.toCny2String(plan.totalDebit()));
        assertEquals("1060.00", ExpenseDomain.toCny2String(plan.totalCredit()));

        // Full Reversal plan
        ExpenseJournalPlan revPlan = accountingAdapter.generateReversalJournal(claim, plan);
        assertEquals("EXPENSE_REVERSAL", revPlan.journalType());
        assertEquals("1060.00", ExpenseDomain.toCny2String(revPlan.totalDebit()));
        assertEquals("1060.00", ExpenseDomain.toCny2String(revPlan.totalCredit()));
        assertEquals(plan.lines().size(), revPlan.lines().size());
    }

    @Test
    void testRestoreEncumbranceMathAndBounds() {
        BigDecimal budget = ExpenseDomain.cny("10000.00", false);
        LocalDate validUntil = LocalDate.of(2026, 12, 31);

        ExpenseRequisition req = rulesEngine.createDraftRequisition(
                tenantId, bookId, "REQ-REV-01", applicantId, UUID.randomUUID(), null,
                "Budget reservation", budget, validUntil
        );
        ExpenseRequisition approved = rulesEngine.approveRequisition(rulesEngine.submitRequisition(req), approverId);

        // Consume 4000
        ExpenseRequisition consumed = rulesEngine.consumeEncumbrance(approved, ExpenseDomain.cny("4000.00", false));
        assertEquals("4000.00", ExpenseDomain.toCny2String(consumed.utilizedAmount()));
        assertEquals("6000.00", ExpenseDomain.toCny2String(consumed.reservedAmount()));

        // Restore 1500
        ExpenseRequisition restored = rulesEngine.restoreEncumbrance(consumed, ExpenseDomain.cny("1500.00", false));
        assertEquals("2500.00", ExpenseDomain.toCny2String(restored.utilizedAmount()));
        assertEquals("7500.00", ExpenseDomain.toCny2String(restored.reservedAmount()));

        // Cannot restore more than utilized
        assertThrows(ValidationException.class, () ->
                rulesEngine.restoreEncumbrance(restored, ExpenseDomain.cny("3000.00", false))
        );

        // Cannot restore negative
        assertThrows(ValidationException.class, () ->
                rulesEngine.restoreEncumbrance(restored, new BigDecimal("-100.00"))
        );
    }

    @Test
    void testBeneficiarySodEnforcement() {
        UUID employeeId = UUID.randomUUID();
        UUID proxySubmitterId = UUID.randomUUID();

        // 1. Advance: Employee beneficiary cannot be approver
        ExpenseAdvance adv = rulesEngine.createDraftAdvance(
                tenantId, bookId, "ADV-SOD-01", proxySubmitterId, null,
                "Employee travel advance", ExpenseDomain.cny("1000.00", false), employeeId
        );
        ExpenseAdvance submittedAdv = rulesEngine.submitAdvance(adv);

        // Approver is employee beneficiary -> MUST FAIL
        assertThrows(ValidationException.class, () -> rulesEngine.approveAdvance(submittedAdv, employeeId));

        // Approver is independent -> passes
        ExpenseAdvance approvedAdv = rulesEngine.approveAdvance(submittedAdv, approverId);

        // Cashier is employee beneficiary -> MUST FAIL
        assertThrows(ValidationException.class, () -> rulesEngine.payoutAdvance(approvedAdv, employeeId, "REF-1"));

        // 2. Claim: Employee beneficiary cannot be reviewer or approver
        ExpenseClaimLine line = new ExpenseClaimLine(
                UUID.randomUUID(), null, 1, ExpenseCategory.MEAL, UUID.randomUUID(), null,
                null, null, null, null,
                ExpenseDomain.cny("200.00", false), ExpenseDomain.cny("200.00", false),
                BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, ExpenseDomain.cny("200.00", false), null, false
        );

        var buildRes = rulesEngine.buildClaim(
                tenantId, bookId, "CLM-SOD-01", employeeId, null,
                "Team dinner", LocalDate.now(), proxySubmitterId, List.of(line), Map.of()
        );
        ExpenseClaim submittedClaim = rulesEngine.submitClaim(buildRes.claim());

        // Reviewer is employee beneficiary -> MUST FAIL
        assertThrows(ValidationException.class, () -> rulesEngine.reviewClaim(submittedClaim, employeeId));

        ExpenseClaim reviewedClaim = rulesEngine.reviewClaim(submittedClaim, reviewerId);

        // Approver is employee beneficiary -> MUST FAIL
        assertThrows(ValidationException.class, () -> rulesEngine.approveClaim(reviewedClaim, employeeId));
    }

    @Test
    void testCentLevelVatSplittingExhaustive() {
        String[][] testCases = {
                {"100.00", "0.00", "0.130000000000"},
                {"55.55", "5.55", "0.090000000000"},
                {"1234.56", "34.56", "0.060000000000"},
                {"999.99", "99.99", "0.030000000000"},
                {"0.05", "0.01", "0.060000000000"},
                {"1000000.00", "0.00", "0.130000000000"}
        };

        for (String[] tc : testCases) {
            BigDecimal submitted = ExpenseDomain.cny(tc[0], false);
            BigDecimal disallowed = ExpenseDomain.cny(tc[1], true);
            BigDecimal rate = new BigDecimal(tc[2]);

            ExpenseClaimLine line = new ExpenseClaimLine(
                    UUID.randomUUID(), null, 1, ExpenseCategory.HOTEL, UUID.randomUUID(), null,
                    null, null, null, null,
                    submitted, submitted.subtract(disallowed), disallowed,
                    disallowed.signum() > 0 ? "Personal portion excluded" : null,
                    rate, BigDecimal.ZERO, BigDecimal.ZERO, null, false
            );

            var buildRes = rulesEngine.buildClaim(
                    tenantId, bookId, "CLM-VAT-TEST", applicantId, null,
                    "VAT test", LocalDate.now(), applicantId, List.of(line), Map.of()
            );

            ExpenseClaimLine split = buildRes.lines().get(0);
            BigDecimal reimbursable = split.approvedReimbursableAmount();
            BigDecimal net = split.netExpenseAmount();
            BigDecimal vat = split.vatInputAmount();

            assertEquals(reimbursable, net.add(vat),
                    "Net (" + net + ") + VAT (" + vat + ") must equal Reimbursable (" + reimbursable + ")");
            assertEquals(ExpenseDomain.MONEY_SCALE, net.scale(), "Net scale must match MONEY_SCALE (8)");
            assertEquals(ExpenseDomain.MONEY_SCALE, vat.scale(), "VAT scale must match MONEY_SCALE (8)");
            assertEquals("0.00", ExpenseDomain.toCny2String(reimbursable.subtract(net).subtract(vat)));
        }
    }

    @Test
    void testCancelAdvanceAndRejectClaimTransitions() {
        // Advance cancel
        ExpenseAdvance adv = rulesEngine.createDraftAdvance(
                tenantId, bookId, "ADV-CAN-01", applicantId, null,
                "Advance to cancel", ExpenseDomain.cny("500.00", false), applicantId
        );
        ExpenseAdvance cancelled = rulesEngine.cancelAdvance(adv);
        assertEquals(AdvanceStatus.CANCELLED, cancelled.status());

        // Claim reject
        ExpenseClaimLine line = new ExpenseClaimLine(
                UUID.randomUUID(), null, 1, ExpenseCategory.MEAL, UUID.randomUUID(), null,
                null, null, null, null,
                ExpenseDomain.cny("300.00", false), ExpenseDomain.cny("300.00", false),
                BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, ExpenseDomain.cny("300.00", false), null, false
        );
        var buildRes = rulesEngine.buildClaim(
                tenantId, bookId, "CLM-REJ-01", applicantId, null,
                "Claim to reject", LocalDate.now(), applicantId, List.of(line), Map.of()
        );
        ExpenseClaim submitted = rulesEngine.submitClaim(buildRes.claim());
        ExpenseClaim reviewed = rulesEngine.reviewClaim(submitted, reviewerId);

        ExpenseClaim rejected = rulesEngine.rejectClaim(reviewed);
        assertEquals(ClaimStatus.REJECTED, rejected.status());
    }
}

package com.lumira.finance.expense;

import com.lumira.finance.expense.ExpenseDomain.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

/**
 * High-level orchestration service for EXP01-EXP04 business processes.
 */
@Service
public final class ExpenseService {

    private final ExpenseJdbcStore store;
    private final TravelPolicyEngine travelEngine;
    private final ExpenseRulesEngine rulesEngine;
    private final ExpenseAccountingAdapter accountingAdapter;

    public ExpenseService() {
        this(new ExpenseJdbcStore(), new TravelPolicyEngine(), new ExpenseRulesEngine(), new ExpenseAccountingAdapter());
    }

    public ExpenseService(
            ExpenseJdbcStore store,
            TravelPolicyEngine travelEngine,
            ExpenseRulesEngine rulesEngine,
            ExpenseAccountingAdapter accountingAdapter
    ) {
        this.store = store;
        this.travelEngine = travelEngine;
        this.rulesEngine = rulesEngine;
        this.accountingAdapter = accountingAdapter;
    }

    public TravelPolicyEngine travelEngine() { return travelEngine; }
    public ExpenseRulesEngine rulesEngine() { return rulesEngine; }
    public ExpenseAccountingAdapter accountingAdapter() { return accountingAdapter; }
    public ExpenseJdbcStore store() { return store; }

    // --- EXP04 Travel Policy Workflows ---

    public TravelPolicy createTravelPolicy(
            Connection conn, UUID tenantId, String policyCode, int versionInt, String name,
            LocalDate effectiveFrom, LocalDate effectiveTo, UUID createdBy, List<TravelPolicyCap> caps
    ) throws SQLException {
        TravelPolicy policy = travelEngine.createDraftPolicy(tenantId, policyCode, versionInt, name, effectiveFrom, effectiveTo, createdBy);
        store.insertTravelPolicy(conn, policy);
        if (caps != null) {
            for (TravelPolicyCap cap : caps) {
                TravelPolicyCap c = new TravelPolicyCap(
                        UUID.randomUUID(), policy.id(), cap.cityTier(), cap.employeeRank(),
                        cap.expenseCategory(), cap.dailyCapAmount(), ExpenseDomain.CURRENCY_CNY
                );
                store.insertTravelPolicyCap(conn, c);
            }
        }
        return policy;
    }

    public TravelPolicy activateTravelPolicy(Connection conn, UUID policyId) throws SQLException {
        TravelPolicy policy = store.findTravelPolicyById(conn, policyId)
                .orElseThrow(() -> new ValidationException("POLICY_NOT_FOUND", "Policy not found: " + policyId));
        TravelPolicy activated = travelEngine.activatePolicy(policy);
        store.updateTravelPolicyStatus(conn, policyId, PolicyStatus.ACTIVE);
        return activated;
    }

    public TravelPolicyEngine.CapEvaluationResult evaluateTravelExpense(
            Connection conn, UUID policyId, LocalDate date, CityTier tier, EmployeeRank rank,
            ExpenseCategory category, BigDecimal submittedAmount
    ) throws SQLException {
        TravelPolicy policy = store.findTravelPolicyById(conn, policyId)
                .orElseThrow(() -> new ValidationException("POLICY_NOT_FOUND", "Policy not found: " + policyId));
        List<TravelPolicyCap> caps = store.listCapsByPolicyId(conn, policyId);
        return travelEngine.evaluateCap(policy, caps, date, tier, rank, category, submittedAmount);
    }

    public TravelException requestTravelException(
            Connection conn, UUID tenantId, UUID claimId, int lineNo, UUID policyCapId,
            BigDecimal requestedAmount, BigDecimal standardCapAmount, UUID applicantId, String justification
    ) throws SQLException {
        TravelException ex = travelEngine.createExceptionRequest(
                tenantId, claimId, lineNo, policyCapId, requestedAmount, standardCapAmount, applicantId, justification
        );
        store.insertTravelException(conn, ex);
        return ex;
    }

    public TravelException decideTravelException(
            Connection conn, UUID exceptionId, UUID approverId, boolean approve, String decisionNotes
    ) throws SQLException {
        TravelException ex = store.findTravelExceptionById(conn, exceptionId)
                .orElseThrow(() -> new ValidationException("EXCEPTION_NOT_FOUND", "Exception not found: " + exceptionId));
        TravelException decided = travelEngine.decideException(ex, approverId, approve, decisionNotes);
        store.updateTravelExceptionDecision(conn, decided);
        return decided;
    }

    // --- EXP01 Expense Requisition Workflows ---

    public ExpenseRequisition createRequisition(
            Connection conn, UUID tenantId, UUID bookId, String requisitionNo, UUID applicantId,
            UUID departmentId, UUID projectId, String purpose, BigDecimal totalBudget, LocalDate effectiveUntil
    ) throws SQLException {
        ExpenseRequisition req = rulesEngine.createDraftRequisition(
                tenantId, bookId, requisitionNo, applicantId, departmentId, projectId, purpose, totalBudget, effectiveUntil
        );
        store.insertRequisition(conn, req);
        return req;
    }

    public ExpenseRequisition submitRequisition(Connection conn, UUID requisitionId) throws SQLException {
        ExpenseRequisition req = store.findRequisitionById(conn, requisitionId)
                .orElseThrow(() -> new ValidationException("REQUISITION_NOT_FOUND", "Requisition not found: " + requisitionId));
        ExpenseRequisition submitted = rulesEngine.submitRequisition(req);
        store.updateRequisition(conn, submitted);
        return submitted;
    }

    public ExpenseRequisition approveRequisition(Connection conn, UUID requisitionId, UUID approverId) throws SQLException {
        ExpenseRequisition req = store.findRequisitionById(conn, requisitionId)
                .orElseThrow(() -> new ValidationException("REQUISITION_NOT_FOUND", "Requisition not found: " + requisitionId));
        ExpenseRequisition approved = rulesEngine.approveRequisition(req, approverId);
        store.updateRequisition(conn, approved);
        return approved;
    }

    public ExpenseRequisition cancelRequisition(Connection conn, UUID requisitionId) throws SQLException {
        ExpenseRequisition req = store.findRequisitionById(conn, requisitionId)
                .orElseThrow(() -> new ValidationException("REQUISITION_NOT_FOUND", "Requisition not found: " + requisitionId));
        ExpenseRequisition cancelled = rulesEngine.cancelOrCloseRequisition(req);
        store.updateRequisition(conn, cancelled);
        return cancelled;
    }

    // --- EXP02 Employee Cash Advance Workflows ---

    public ExpenseAdvance createAdvance(
            Connection conn, UUID tenantId, UUID bookId, String advanceNo, UUID employeeId,
            UUID requisitionId, String purpose, BigDecimal principalAmount, UUID applicantId
    ) throws SQLException {
        // If linked to requisition, check available capacity
        if (requisitionId != null) {
            ExpenseRequisition req = store.findRequisitionById(conn, requisitionId)
                    .orElseThrow(() -> new ValidationException("REQUISITION_NOT_FOUND", "Requisition not found: " + requisitionId));
            if (req.status() != RequisitionStatus.APPROVED) {
                throw new ValidationException("INVALID_REQUISITION_STATUS", "Linked requisition must be APPROVED");
            }
            if (req.availableAmount().compareTo(principalAmount) < 0) {
                throw new ValidationException("REQUISITION_CAP_EXCEEDED", "Advance exceeds available requisition budget cap");
            }
            ExpenseRequisition updatedReq = rulesEngine.consumeEncumbrance(req, principalAmount);
            store.updateRequisition(conn, updatedReq);
        }

        ExpenseAdvance adv = rulesEngine.createDraftAdvance(
                tenantId, bookId, advanceNo, employeeId, requisitionId, purpose, principalAmount, applicantId
        );
        store.insertAdvance(conn, adv);
        return adv;
    }

    public ExpenseAdvance cancelAdvance(Connection conn, UUID advanceId, UUID actorId) throws SQLException {
        ExpenseAdvance adv = store.findAdvanceById(conn, advanceId)
                .orElseThrow(() -> new ValidationException("ADVANCE_NOT_FOUND", "Advance not found: " + advanceId));
        if (adv.requisitionId() != null) {
            ExpenseRequisition req = store.findRequisitionById(conn, adv.requisitionId())
                    .orElseThrow(() -> new ValidationException("REQUISITION_NOT_FOUND", "Requisition not found: " + adv.requisitionId()));
            ExpenseRequisition restored = rulesEngine.restoreEncumbrance(req, adv.principalAmount());
            store.updateRequisition(conn, restored);
        }
        ExpenseAdvance cancelled = rulesEngine.cancelAdvance(adv);
        store.updateAdvance(conn, cancelled);
        return cancelled;
    }

    public ExpenseAdvance submitAdvance(Connection conn, UUID advanceId) throws SQLException {
        ExpenseAdvance adv = store.findAdvanceById(conn, advanceId)
                .orElseThrow(() -> new ValidationException("ADVANCE_NOT_FOUND", "Advance not found: " + advanceId));
        ExpenseAdvance submitted = rulesEngine.submitAdvance(adv);
        store.updateAdvance(conn, submitted);
        return submitted;
    }

    public ExpenseAdvance approveAdvance(Connection conn, UUID advanceId, UUID approverId) throws SQLException {
        ExpenseAdvance adv = store.findAdvanceById(conn, advanceId)
                .orElseThrow(() -> new ValidationException("ADVANCE_NOT_FOUND", "Advance not found: " + advanceId));
        ExpenseAdvance approved = rulesEngine.approveAdvance(adv, approverId);
        store.updateAdvance(conn, approved);
        return approved;
    }

    public ExpenseAdvance payoutAdvance(Connection conn, UUID advanceId, UUID cashierId, String payoutRef) throws SQLException {
        ExpenseAdvance adv = store.findAdvanceById(conn, advanceId)
                .orElseThrow(() -> new ValidationException("ADVANCE_NOT_FOUND", "Advance not found: " + advanceId));
        ExpenseAdvance paid = rulesEngine.payoutAdvance(adv, cashierId, payoutRef);
        store.updateAdvance(conn, paid);
        return paid;
    }

    public ExpenseAdvanceRefund refundAdvanceCash(
            Connection conn, UUID advanceId, String refundNo, BigDecimal refundAmount,
            LocalDate refundDate, String receiptMethod, String receiptRef, UUID cashierPersonId
    ) throws SQLException {
        ExpenseAdvance adv = store.findAdvanceById(conn, advanceId)
                .orElseThrow(() -> new ValidationException("ADVANCE_NOT_FOUND", "Advance not found: " + advanceId));
        ExpenseRulesEngine.RefundResult res = rulesEngine.refundAdvanceCash(
                adv, refundNo, refundAmount, refundDate, receiptMethod, receiptRef, cashierPersonId
        );
        store.updateAdvance(conn, res.updatedAdvance());
        store.insertAdvanceRefund(conn, res.refundSlip());
        return res.refundSlip();
    }

    // --- EXP03 Expense Reimbursement Claim Workflows ---

    public ExpenseRulesEngine.ClaimBuildResult createClaim(
            Connection conn, UUID tenantId, UUID bookId, String claimNo, UUID employeeId, UUID requisitionId,
            String title, LocalDate claimDate, UUID submitterId, List<ExpenseClaimLine> lines,
            Map<UUID, BigDecimal> advanceOffsets
    ) throws SQLException {
        // Validate each advance offset exists, belongs to employee, and has enough balance
        if (advanceOffsets != null) {
            for (Map.Entry<UUID, BigDecimal> entry : advanceOffsets.entrySet()) {
                ExpenseAdvance adv = store.findAdvanceById(conn, entry.getKey())
                        .orElseThrow(() -> new ValidationException("ADVANCE_NOT_FOUND", "Advance not found: " + entry.getKey()));
                if (!adv.employeeId().equals(employeeId)) {
                    throw new ValidationException("ADVANCE_EMPLOYEE_MISMATCH", "Advance does not belong to claim employee");
                }
                if (adv.remainingBalance().compareTo(entry.getValue()) < 0) {
                    throw new ValidationException("ADVANCE_BALANCE_EXCEEDED",
                            "Offset amount exceeds remaining balance for advance " + adv.advanceNo());
                }
            }
        }

        // Validate requisition budget cap if linked
        if (requisitionId != null) {
            ExpenseRequisition req = store.findRequisitionById(conn, requisitionId)
                    .orElseThrow(() -> new ValidationException("REQUISITION_NOT_FOUND", "Requisition not found: " + requisitionId));
            if (req.status() != RequisitionStatus.APPROVED) {
                throw new ValidationException("INVALID_REQUISITION_STATUS", "Linked requisition must be APPROVED");
            }
        }

        ExpenseRulesEngine.ClaimBuildResult res = rulesEngine.buildClaim(
                tenantId, bookId, claimNo, employeeId, requisitionId, title, claimDate, submitterId, lines, advanceOffsets
        );
        store.insertClaim(conn, res.claim(), res.lines(), res.offsets());
        return res;
    }

    public ExpenseClaim submitClaim(Connection conn, UUID claimId) throws SQLException {
        ExpenseClaim claim = store.findClaimById(conn, claimId)
                .orElseThrow(() -> new ValidationException("CLAIM_NOT_FOUND", "Claim not found: " + claimId));
        ExpenseClaim submitted = rulesEngine.submitClaim(claim);
        store.updateClaim(conn, submitted);
        return submitted;
    }

    public ExpenseClaim reviewClaim(Connection conn, UUID claimId, UUID reviewerId) throws SQLException {
        ExpenseClaim claim = store.findClaimById(conn, claimId)
                .orElseThrow(() -> new ValidationException("CLAIM_NOT_FOUND", "Claim not found: " + claimId));
        ExpenseClaim reviewed = rulesEngine.reviewClaim(claim, reviewerId);
        store.updateClaim(conn, reviewed);
        return reviewed;
    }

    public ExpenseClaim approveClaim(Connection conn, UUID claimId, UUID approverId) throws SQLException {
        ExpenseClaim claim = store.findClaimById(conn, claimId)
                .orElseThrow(() -> new ValidationException("CLAIM_NOT_FOUND", "Claim not found: " + claimId));
        ExpenseClaim approved = rulesEngine.approveClaim(claim, approverId);

        // Apply advance offsets upon approval
        List<ExpenseClaimAdvanceOffset> offsets = store.listClaimOffsets(conn, claimId);
        BigDecimal alreadyEncumberedByAdvance = BigDecimal.ZERO;
        for (ExpenseClaimAdvanceOffset off : offsets) {
            ExpenseAdvance adv = store.findAdvanceById(conn, off.advanceId())
                    .orElseThrow(() -> new ValidationException("ADVANCE_NOT_FOUND", "Advance not found: " + off.advanceId()));
            if (claim.requisitionId() != null && adv.requisitionId() != null && adv.requisitionId().equals(claim.requisitionId())) {
                alreadyEncumberedByAdvance = alreadyEncumberedByAdvance.add(off.offsetAmount());
            }
            ExpenseAdvance updatedAdv = rulesEngine.offsetAdvance(adv, off.offsetAmount());
            store.updateAdvance(conn, updatedAdv);
        }

        // Consume requisition budget if linked (excluding advance offsets that already encumbered this requisition)
        if (claim.requisitionId() != null) {
            BigDecimal netToConsume = claim.approvedAmount().subtract(alreadyEncumberedByAdvance);
            if (netToConsume.signum() > 0) {
                ExpenseRequisition req = store.findRequisitionById(conn, claim.requisitionId())
                        .orElseThrow(() -> new ValidationException("REQUISITION_NOT_FOUND", "Requisition not found"));
                ExpenseRequisition updatedReq = rulesEngine.consumeEncumbrance(req, netToConsume);
                store.updateRequisition(conn, updatedReq);
            }
        }

        store.updateClaim(conn, approved);

        // Generate employee payable ledger
        ExpensePayableLedger ledger = rulesEngine.generatePayableLedger(approved);
        store.insertPayableLedger(conn, ledger);

        return approved;
    }

    public ExpenseClaim rejectClaim(Connection conn, UUID claimId, UUID reviewerOrApproverId, String reason) throws SQLException {
        ExpenseClaim claim = store.findClaimById(conn, claimId)
                .orElseThrow(() -> new ValidationException("CLAIM_NOT_FOUND", "Claim not found: " + claimId));
        ExpenseDomain.checkBeneficiarySod(claim.employeeId(), reviewerOrApproverId, "Expense claim reject");
        ExpenseClaim rejected = rulesEngine.rejectClaim(claim);
        store.updateClaim(conn, rejected);
        return rejected;
    }

    public ExpenseClaim cancelClaim(Connection conn, UUID claimId, UUID actorId) throws SQLException {
        ExpenseClaim claim = store.findClaimById(conn, claimId)
                .orElseThrow(() -> new ValidationException("CLAIM_NOT_FOUND", "Claim not found: " + claimId));
        ExpenseClaim cancelled = rulesEngine.cancelClaim(claim);
        store.updateClaim(conn, cancelled);
        return cancelled;
    }

    public ExpenseJournalPlan postClaimJournal(Connection conn, UUID claimId) throws SQLException {
        ExpenseClaim claim = store.findClaimById(conn, claimId)
                .orElseThrow(() -> new ValidationException("CLAIM_NOT_FOUND", "Claim not found: " + claimId));
        if (claim.status() != ClaimStatus.APPROVED) {
            throw new ValidationException("INVALID_STATUS", "Only APPROVED claim can be journal-posted");
        }
        if (claim.approvedAmount().signum() <= 0) {
            throw new ValidationException("NO_EXPENSE_TO_POST", "Claim with zero approved amount cannot generate journal");
        }
        List<ExpenseClaimLine> lines = store.listClaimLines(conn, claimId);

        ExpenseJournalPlan plan = accountingAdapter.generateClaimJournal(claim, lines, claim.bookId());

        ExpenseClaim posted = new ExpenseClaim(
                claim.id(), claim.tenantId(), claim.bookId(), claim.claimNo(), claim.employeeId(),
                claim.requisitionId(), claim.title(), claim.claimDate(), claim.currency(),
                claim.submittedAmount(), claim.approvedAmount(), claim.disallowedAmount(),
                claim.offsetAdvanceAmount(), claim.payableAmount(), claim.paidAmount(),
                ClaimStatus.POSTED, claim.submitterId(), claim.reviewerId(), claim.approverId(),
                claim.reviewedAt(), claim.approvedAt(), plan.journalId(), null,
                claim.createdAt(), claim.version() + 1
        );
        store.updateClaim(conn, posted);
        return plan;
    }

    public ExpenseRulesEngine.DisburseResult disbursePayment(
            Connection conn, UUID claimId, String paymentNo, BigDecimal amount,
            LocalDate paymentDate, String paymentMethod, String paymentRef, UUID cashierId
    ) throws SQLException {
        ExpenseClaim claim = store.findClaimById(conn, claimId)
                .orElseThrow(() -> new ValidationException("CLAIM_NOT_FOUND", "Claim not found: " + claimId));
        ExpensePayableLedger ledger = store.findPayableByClaimId(conn, claimId)
                .orElseThrow(() -> new ValidationException("PAYABLE_NOT_FOUND", "Payable ledger not found for claim: " + claimId));

        UUID paymentId = UUID.randomUUID();
        ExpensePayment paymentSlip = new ExpensePayment(
                paymentId, ledger.tenantId(), ledger.id(), paymentNo, amount,
                paymentDate, paymentMethod, paymentRef, cashierId, null, null
        );
        ExpenseJournalPlan paymentJournal = accountingAdapter.generatePaymentJournal(paymentSlip, ledger, claim.bookId());

        ExpenseRulesEngine.DisburseResult res = rulesEngine.disbursePayment(
                paymentId, ledger, claim, paymentNo, amount, paymentDate, paymentMethod, paymentRef, cashierId, paymentJournal.journalId()
        );

        store.updatePayableLedger(conn, res.updatedLedger());
        store.insertPayment(conn, res.paymentSlip());
        store.updateClaim(conn, res.updatedClaim());

        return res;
    }

    public ExpenseJournalPlan reverseClaim(Connection conn, UUID claimId, UUID actorId, String reason) throws SQLException {
        ExpenseClaim claim = store.findClaimById(conn, claimId)
                .orElseThrow(() -> new ValidationException("CLAIM_NOT_FOUND", "Claim not found: " + claimId));
        if (claim.status() != ClaimStatus.POSTED && claim.status() != ClaimStatus.APPROVED) {
            throw new ValidationException("INVALID_STATUS", "Only APPROVED or POSTED claim can be reversed");
        }
        if (claim.paidAmount().signum() > 0) {
            throw new ValidationException("CANNOT_REVERSE_PAID", "Cannot reverse a claim with disbursed payments");
        }

        List<ExpenseClaimLine> lines = store.listClaimLines(conn, claimId);
        ExpenseJournalPlan origJournal = claim.status() == ClaimStatus.POSTED && claim.approvedAmount().signum() > 0
                ? accountingAdapter.generateClaimJournal(claim, lines, claim.bookId())
                : null;
        ExpenseJournalPlan revJournal = origJournal != null
                ? accountingAdapter.generateReversalJournal(claim, origJournal)
                : null;

        // Restore advance balances
        List<ExpenseClaimAdvanceOffset> offsets = store.listClaimOffsets(conn, claimId);
        BigDecimal alreadyEncumberedByAdvance = BigDecimal.ZERO;
        for (ExpenseClaimAdvanceOffset off : offsets) {
            ExpenseAdvance adv = store.findAdvanceById(conn, off.advanceId())
                    .orElseThrow(() -> new ValidationException("ADVANCE_NOT_FOUND", "Advance not found: " + off.advanceId()));
            if (claim.requisitionId() != null && adv.requisitionId() != null && adv.requisitionId().equals(claim.requisitionId())) {
                alreadyEncumberedByAdvance = alreadyEncumberedByAdvance.add(off.offsetAmount());
            }
            BigDecimal newOffset = adv.offsetAmount().subtract(off.offsetAmount());
            BigDecimal newRemaining = adv.remainingBalance().add(off.offsetAmount());
            AdvanceStatus newStatus = newOffset.signum() > 0 ? AdvanceStatus.PARTIALLY_OFFSET : AdvanceStatus.PAID;
            ExpenseAdvance restored = new ExpenseAdvance(
                    adv.id(), adv.tenantId(), adv.bookId(), adv.advanceNo(), adv.employeeId(),
                    adv.requisitionId(), adv.purpose(), adv.currency(), adv.principalAmount(),
                    newOffset, adv.refundedAmount(), newRemaining, newStatus,
                    adv.applicantId(), adv.approverId(), adv.cashierId(), adv.payoutRef(),
                    adv.payoutAt(), adv.createdAt(), adv.version() + 1
            );
            store.updateAdvance(conn, restored);
        }

        // Restore requisition encumbrance if linked
        if (claim.requisitionId() != null) {
            BigDecimal netConsumed = claim.approvedAmount().subtract(alreadyEncumberedByAdvance);
            if (netConsumed.signum() > 0) {
                ExpenseRequisition req = store.findRequisitionById(conn, claim.requisitionId())
                        .orElseThrow(() -> new ValidationException("REQUISITION_NOT_FOUND", "Requisition not found"));
                ExpenseRequisition restoredReq = rulesEngine.restoreEncumbrance(req, netConsumed);
                store.updateRequisition(conn, restoredReq);
            }
        }

        // Cancel employee payable ledger
        Optional<ExpensePayableLedger> payableOpt = store.findPayableByClaimId(conn, claimId);
        if (payableOpt.isPresent()) {
            ExpensePayableLedger p = payableOpt.get();
            ExpensePayableLedger cancelledPayable = new ExpensePayableLedger(
                    p.id(), p.tenantId(), p.bookId(), p.claimId(), p.employeeId(),
                    p.payableAmount(), p.paidAmount(), BigDecimal.ZERO.setScale(8, RoundingMode.UNNECESSARY),
                    SettlementStatus.CANCELLED, p.createdAt(), p.version() + 1
            );
            store.updatePayableLedger(conn, cancelledPayable);
        }

        ExpenseClaim reversed = new ExpenseClaim(
                claim.id(), claim.tenantId(), claim.bookId(), claim.claimNo(), claim.employeeId(),
                claim.requisitionId(), claim.title(), claim.claimDate(), claim.currency(),
                claim.submittedAmount(), claim.approvedAmount(), claim.disallowedAmount(),
                claim.offsetAdvanceAmount(), claim.payableAmount(), claim.paidAmount(),
                ClaimStatus.REVERSED, claim.submitterId(), claim.reviewerId(), claim.approverId(),
                claim.reviewedAt(), claim.approvedAt(), claim.journalId(), revJournal != null ? revJournal.journalId() : null,
                claim.createdAt(), claim.version() + 1
        );
        store.updateClaim(conn, reversed);
        return revJournal;
    }

    public ExpenseJournalPlan reverseClaim(Connection conn, UUID claimId, ExpenseJournalPlan originalJournal) throws SQLException {
        return reverseClaim(conn, claimId, null, "Manual reversal with plan");
    }

    // --- Query / Listing methods ---

    public List<TravelPolicy> listPolicies(Connection conn, UUID tenantId) throws SQLException {
        return store.listPolicies(conn, tenantId);
    }

    public List<ExpenseRequisition> listRequisitions(Connection conn, UUID tenantId, UUID employeeId, boolean isReviewer) throws SQLException {
        return store.listRequisitions(conn, tenantId, employeeId, isReviewer);
    }

    public List<ExpenseAdvance> listAdvances(Connection conn, UUID tenantId, UUID employeeId, boolean isReviewer) throws SQLException {
        return store.listAdvances(conn, tenantId, employeeId, isReviewer);
    }

    public List<ExpenseClaim> listClaims(Connection conn, UUID tenantId, UUID employeeId, boolean isReviewer) throws SQLException {
        return store.listClaims(conn, tenantId, employeeId, isReviewer);
    }

    public List<ExpensePayableLedger> listPayables(Connection conn, UUID tenantId, UUID employeeId, boolean isReviewer) throws SQLException {
        return store.listPayables(conn, tenantId, employeeId, isReviewer);
    }
}

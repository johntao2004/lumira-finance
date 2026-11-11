package com.lumira.finance.expense;

import com.lumira.finance.expense.ExpenseDomain.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Rules and invariants engine for EXP01, EXP02, and EXP03.
 */
@Component
public final class ExpenseRulesEngine {

    // =========================================================================
    // EXP01: Expense Requisition & Pre-Encumbrance
    // =========================================================================

    public ExpenseRequisition createDraftRequisition(
            UUID tenantId, UUID bookId, String requisitionNo, UUID applicantId,
            UUID departmentId, UUID projectId, String purpose, BigDecimal totalBudget, LocalDate effectiveUntil) {
        if (totalBudget.signum() <= 0) {
            throw new ValidationException("INVALID_BUDGET", "Requisition total budget must be strictly positive");
        }
        return new ExpenseRequisition(
                UUID.randomUUID(), tenantId, bookId, requisitionNo, applicantId,
                departmentId, projectId, purpose, ExpenseDomain.CURRENCY_CNY,
                totalBudget, BigDecimal.ZERO.setScale(8, RoundingMode.UNNECESSARY),
                BigDecimal.ZERO.setScale(8, RoundingMode.UNNECESSARY),
                RequisitionStatus.DRAFT, null, null, effectiveUntil,
                OffsetDateTime.now(), 1
        );
    }

    public ExpenseRequisition submitRequisition(ExpenseRequisition req) {
        if (req.status() != RequisitionStatus.DRAFT) {
            throw new ValidationException("INVALID_STATUS", "Only DRAFT requisition can be submitted");
        }
        return new ExpenseRequisition(
                req.id(), req.tenantId(), req.bookId(), req.requisitionNo(), req.applicantId(),
                req.departmentId(), req.projectId(), req.businessPurpose(), req.currency(),
                req.totalBudgetAmount(), req.totalBudgetAmount(), // reserve full budget upon submission
                req.utilizedAmount(), RequisitionStatus.SUBMITTED, req.approverId(), req.approvedAt(),
                req.effectiveUntil(), req.createdAt(), req.version() + 1
        );
    }

    public ExpenseRequisition approveRequisition(ExpenseRequisition req, UUID approverId) {
        if (req.status() != RequisitionStatus.SUBMITTED) {
            throw new ValidationException("INVALID_STATUS", "Only SUBMITTED requisition can be approved");
        }
        ExpenseDomain.checkSod(req.applicantId(), approverId, "Expense requisition approval");

        return new ExpenseRequisition(
                req.id(), req.tenantId(), req.bookId(), req.requisitionNo(), req.applicantId(),
                req.departmentId(), req.projectId(), req.businessPurpose(), req.currency(),
                req.totalBudgetAmount(), req.reservedAmount(), req.utilizedAmount(),
                RequisitionStatus.APPROVED, approverId, OffsetDateTime.now(),
                req.effectiveUntil(), req.createdAt(), req.version() + 1
        );
    }

    public ExpenseRequisition consumeEncumbrance(ExpenseRequisition req, BigDecimal amount) {
        if (req.status() != RequisitionStatus.APPROVED) {
            throw new ValidationException("INVALID_STATUS", "Cannot consume unapproved requisition");
        }
        if (amount.signum() <= 0) {
            throw new ValidationException("INVALID_AMOUNT", "Consumed amount must be strictly positive");
        }
        BigDecimal currentReserved = req.reservedAmount();
        BigDecimal newUtilized = req.utilizedAmount().add(amount);

        // Deduct from reserved if available, otherwise check total budget
        BigDecimal newReserved = currentReserved.compareTo(amount) >= 0 ? currentReserved.subtract(amount) : BigDecimal.ZERO.setScale(8, RoundingMode.UNNECESSARY);
        if (newUtilized.add(newReserved).compareTo(req.totalBudgetAmount()) > 0) {
            throw new ValidationException("ENCUMBRANCE_EXCEEDED", "Consumption exceeds requisition total budget cap");
        }

        return new ExpenseRequisition(
                req.id(), req.tenantId(), req.bookId(), req.requisitionNo(), req.applicantId(),
                req.departmentId(), req.projectId(), req.businessPurpose(), req.currency(),
                req.totalBudgetAmount(), newReserved, newUtilized, req.status(),
                req.approverId(), req.approvedAt(), req.effectiveUntil(), req.createdAt(), req.version() + 1
        );
    }

    public ExpenseRequisition restoreEncumbrance(ExpenseRequisition req, BigDecimal amount) {
        if (req.status() != RequisitionStatus.APPROVED && req.status() != RequisitionStatus.CLOSED) {
            throw new ValidationException("INVALID_STATUS", "Cannot restore encumbrance on status: " + req.status());
        }
        if (amount.signum() <= 0) {
            throw new ValidationException("INVALID_AMOUNT", "Restored amount must be strictly positive");
        }
        if (amount.compareTo(req.utilizedAmount()) > 0) {
            throw new ValidationException("EXCEEDS_UTILIZED",
                    "Cannot restore amount " + ExpenseDomain.toCny2String(amount) +
                    " greater than currently utilized encumbrance " + ExpenseDomain.toCny2String(req.utilizedAmount()));
        }
        BigDecimal newUtilized = req.utilizedAmount().subtract(amount);
        BigDecimal newReserved = req.reservedAmount().add(amount);
        if (newUtilized.add(newReserved).compareTo(req.totalBudgetAmount()) > 0) {
            newReserved = req.totalBudgetAmount().subtract(newUtilized);
        }
        return new ExpenseRequisition(
                req.id(), req.tenantId(), req.bookId(), req.requisitionNo(), req.applicantId(),
                req.departmentId(), req.projectId(), req.businessPurpose(), req.currency(),
                req.totalBudgetAmount(), newReserved, newUtilized, req.status(),
                req.approverId(), req.approvedAt(), req.effectiveUntil(), req.createdAt(), req.version() + 1
        );
    }

    public ExpenseRequisition cancelOrCloseRequisition(ExpenseRequisition req) {
        if (req.status() == RequisitionStatus.CLOSED || req.status() == RequisitionStatus.CANCELLED) {
            throw new ValidationException("ALREADY_TERMINAL", "Requisition is already terminal: " + req.status());
        }
        // If utilized > 0, we close the requisition and release unutilized reserved amounts.
        // If utilized == 0, we can cancel it completely.
        RequisitionStatus targetStatus = req.utilizedAmount().signum() > 0 ? RequisitionStatus.CLOSED : RequisitionStatus.CANCELLED;
        return new ExpenseRequisition(
                req.id(), req.tenantId(), req.bookId(), req.requisitionNo(), req.applicantId(),
                req.departmentId(), req.projectId(), req.businessPurpose(), req.currency(),
                req.totalBudgetAmount(), BigDecimal.ZERO.setScale(8, RoundingMode.UNNECESSARY), // released reserved capacity
                req.utilizedAmount(), targetStatus, req.approverId(), req.approvedAt(),
                req.effectiveUntil(), req.createdAt(), req.version() + 1
        );
    }

    // =========================================================================
    // EXP02: Employee Cash Advance & Offset & Refund
    // =========================================================================

    public ExpenseAdvance createDraftAdvance(
            UUID tenantId, UUID bookId, String advanceNo, UUID employeeId, UUID requisitionId,
            String purpose, BigDecimal principalAmount, UUID applicantId) {
        if (principalAmount.signum() <= 0) {
            throw new ValidationException("INVALID_AMOUNT", "Advance principal must be strictly positive");
        }
        return new ExpenseAdvance(
                UUID.randomUUID(), tenantId, bookId, advanceNo, employeeId, requisitionId,
                purpose, ExpenseDomain.CURRENCY_CNY, principalAmount,
                BigDecimal.ZERO.setScale(8, RoundingMode.UNNECESSARY),
                BigDecimal.ZERO.setScale(8, RoundingMode.UNNECESSARY),
                principalAmount, AdvanceStatus.DRAFT, applicantId, null, null, null, null,
                OffsetDateTime.now(), 1
        );
    }

    public ExpenseAdvance submitAdvance(ExpenseAdvance advance) {
        if (advance.status() != AdvanceStatus.DRAFT) {
            throw new ValidationException("INVALID_STATUS", "Only DRAFT advance can be submitted");
        }
        return new ExpenseAdvance(
                advance.id(), advance.tenantId(), advance.bookId(), advance.advanceNo(),
                advance.employeeId(), advance.requisitionId(), advance.purpose(), advance.currency(),
                advance.principalAmount(), advance.offsetAmount(), advance.refundedAmount(),
                advance.remainingBalance(), AdvanceStatus.SUBMITTED, advance.applicantId(),
                advance.approverId(), advance.cashierId(), advance.payoutRef(), advance.payoutAt(),
                advance.createdAt(), advance.version() + 1
        );
    }

    public ExpenseAdvance approveAdvance(ExpenseAdvance advance, UUID approverId) {
        if (advance.status() != AdvanceStatus.SUBMITTED) {
            throw new ValidationException("INVALID_STATUS", "Only SUBMITTED advance can be approved");
        }
        ExpenseDomain.checkSod(advance.applicantId(), approverId, "Advance approval");
        ExpenseDomain.checkBeneficiarySod(advance.employeeId(), approverId, "Advance approval");

        return new ExpenseAdvance(
                advance.id(), advance.tenantId(), advance.bookId(), advance.advanceNo(),
                advance.employeeId(), advance.requisitionId(), advance.purpose(), advance.currency(),
                advance.principalAmount(), advance.offsetAmount(), advance.refundedAmount(),
                advance.remainingBalance(), AdvanceStatus.APPROVED, advance.applicantId(),
                approverId, advance.cashierId(), advance.payoutRef(), advance.payoutAt(),
                advance.createdAt(), advance.version() + 1
        );
    }

    public ExpenseAdvance payoutAdvance(ExpenseAdvance advance, UUID cashierId, String payoutRef) {
        if (advance.status() != AdvanceStatus.APPROVED) {
            throw new ValidationException("INVALID_STATUS", "Only APPROVED advance can be paid out");
        }
        if (payoutRef == null || payoutRef.isBlank()) {
            throw new ValidationException("PAYOUT_REF_REQUIRED", "Payout slip reference is mandatory");
        }
        ExpenseDomain.checkSod(advance.applicantId(), cashierId, "Advance payout cashier");
        ExpenseDomain.checkBeneficiarySod(advance.employeeId(), cashierId, "Advance payout cashier");
        ExpenseDomain.checkSod(advance.approverId(), cashierId, "Advance payout cashier vs approver");

        return new ExpenseAdvance(
                advance.id(), advance.tenantId(), advance.bookId(), advance.advanceNo(),
                advance.employeeId(), advance.requisitionId(), advance.purpose(), advance.currency(),
                advance.principalAmount(), advance.offsetAmount(), advance.refundedAmount(),
                advance.remainingBalance(), AdvanceStatus.PAID, advance.applicantId(),
                advance.approverId(), cashierId, payoutRef, OffsetDateTime.now(),
                advance.createdAt(), advance.version() + 1
        );
    }

    public ExpenseAdvance cancelAdvance(ExpenseAdvance advance) {
        if (advance.status() == AdvanceStatus.PAID || advance.status() == AdvanceStatus.PARTIALLY_OFFSET || advance.status() == AdvanceStatus.SETTLED) {
            throw new ValidationException("CANNOT_CANCEL_PAID", "Cannot cancel paid or settled advance");
        }
        return new ExpenseAdvance(
                advance.id(), advance.tenantId(), advance.bookId(), advance.advanceNo(),
                advance.employeeId(), advance.requisitionId(), advance.purpose(), advance.currency(),
                advance.principalAmount(), advance.offsetAmount(), advance.refundedAmount(),
                advance.remainingBalance(), AdvanceStatus.CANCELLED, advance.applicantId(),
                advance.approverId(), advance.cashierId(), advance.payoutRef(), advance.payoutAt(),
                advance.createdAt(), advance.version() + 1
        );
    }

    public ExpenseAdvance offsetAdvance(ExpenseAdvance advance, BigDecimal offsetAmount) {
        if (advance.status() != AdvanceStatus.PAID && advance.status() != AdvanceStatus.PARTIALLY_OFFSET) {
            throw new ValidationException("INVALID_STATUS", "Advance not in offsettable status: " + advance.status());
        }
        if (offsetAmount.signum() <= 0) {
            throw new ValidationException("INVALID_AMOUNT", "Offset amount must be positive");
        }
        if (offsetAmount.compareTo(advance.remainingBalance()) > 0) {
            throw new ValidationException("OFFSET_EXCEEDS_BALANCE",
                    "Offset amount " + ExpenseDomain.toCny2String(offsetAmount) +
                            " exceeds remaining advance balance " + ExpenseDomain.toCny2String(advance.remainingBalance()));
        }

        BigDecimal newOffset = advance.offsetAmount().add(offsetAmount);
        BigDecimal newRemaining = advance.remainingBalance().subtract(offsetAmount);
        AdvanceStatus newStatus = newRemaining.signum() == 0 ? AdvanceStatus.SETTLED : AdvanceStatus.PARTIALLY_OFFSET;

        return new ExpenseAdvance(
                advance.id(), advance.tenantId(), advance.bookId(), advance.advanceNo(),
                advance.employeeId(), advance.requisitionId(), advance.purpose(), advance.currency(),
                advance.principalAmount(), newOffset, advance.refundedAmount(), newRemaining,
                newStatus, advance.applicantId(), advance.approverId(), advance.cashierId(),
                advance.payoutRef(), advance.payoutAt(), advance.createdAt(), advance.version() + 1
        );
    }

    public record RefundResult(ExpenseAdvance updatedAdvance, ExpenseAdvanceRefund refundSlip) {}

    public RefundResult refundAdvanceCash(ExpenseAdvance advance, String refundNo, BigDecimal refundAmount,
                                         LocalDate refundDate, String receiptMethod, String receiptRef, UUID cashierPersonId) {
        if (advance.status() != AdvanceStatus.PAID && advance.status() != AdvanceStatus.PARTIALLY_OFFSET) {
            throw new ValidationException("INVALID_STATUS", "Advance not in refundable status: " + advance.status());
        }
        if (refundAmount.signum() <= 0) {
            throw new ValidationException("INVALID_AMOUNT", "Refund amount must be positive");
        }
        if (refundAmount.compareTo(advance.remainingBalance()) > 0) {
            throw new ValidationException("REFUND_EXCEEDS_BALANCE",
                    "Refund amount " + ExpenseDomain.toCny2String(refundAmount) +
                            " exceeds remaining advance balance " + ExpenseDomain.toCny2String(advance.remainingBalance()));
        }
        ExpenseDomain.checkSod(advance.applicantId(), cashierPersonId, "Advance cash refund cashier");
        ExpenseDomain.checkBeneficiarySod(advance.employeeId(), cashierPersonId, "Advance cash refund cashier");

        BigDecimal newRefunded = advance.refundedAmount().add(refundAmount);
        BigDecimal newRemaining = advance.remainingBalance().subtract(refundAmount);
        AdvanceStatus newStatus = newRemaining.signum() == 0 ? AdvanceStatus.SETTLED : AdvanceStatus.PARTIALLY_OFFSET;

        ExpenseAdvance updated = new ExpenseAdvance(
                advance.id(), advance.tenantId(), advance.bookId(), advance.advanceNo(),
                advance.employeeId(), advance.requisitionId(), advance.purpose(), advance.currency(),
                advance.principalAmount(), advance.offsetAmount(), newRefunded, newRemaining,
                newStatus, advance.applicantId(), advance.approverId(), advance.cashierId(),
                advance.payoutRef(), advance.payoutAt(), advance.createdAt(), advance.version() + 1
        );

        ExpenseAdvanceRefund slip = new ExpenseAdvanceRefund(
                UUID.randomUUID(), advance.tenantId(), advance.id(), refundNo,
                refundAmount, refundDate, receiptMethod, receiptRef, cashierPersonId, OffsetDateTime.now()
        );

        return new RefundResult(updated, slip);
    }

    // =========================================================================
    // EXP03: Expense Reimbursement Claim, Splits, Partial Recognition & Payables
    // =========================================================================

    public record ClaimLineValidation(
            BigDecimal submittedAmount,
            BigDecimal approvedReimbursableAmount,
            BigDecimal disallowedAmount,
            BigDecimal vatInputAmount,
            BigDecimal netExpenseAmount
    ) {}

    public ClaimLineValidation calculateLineRecognition(
            BigDecimal submittedAmount,
            BigDecimal disallowedAmount,
            BigDecimal vatRate
    ) {
        if (submittedAmount == null || submittedAmount.signum() <= 0) {
            throw new ValidationException("INVALID_LINE_AMOUNT", "Line submitted amount must be positive");
        }
        BigDecimal dis = disallowedAmount != null ? disallowedAmount : BigDecimal.ZERO.setScale(8, RoundingMode.UNNECESSARY);
        if (dis.signum() < 0 || dis.compareTo(submittedAmount) > 0) {
            throw new ValidationException("INVALID_DISALLOWED_AMOUNT", "Disallowed amount cannot exceed submitted amount");
        }

        BigDecimal reimbursable = submittedAmount.subtract(dis);
        BigDecimal rate = vatRate != null ? vatRate : BigDecimal.ZERO.setScale(12, RoundingMode.UNNECESSARY);

        // VAT input split: net = reimbursable / (1 + vatRate) rounded to 2 decimals, vatInput = reimbursable - net
        BigDecimal net;
        BigDecimal vatInput;
        if (rate.signum() > 0) {
            BigDecimal divisor = BigDecimal.ONE.add(rate);
            net = reimbursable.divide(divisor, 2, RoundingMode.HALF_UP).setScale(8, RoundingMode.UNNECESSARY);
            vatInput = reimbursable.subtract(net);
        } else {
            net = reimbursable;
            vatInput = BigDecimal.ZERO.setScale(8, RoundingMode.UNNECESSARY);
        }

        return new ClaimLineValidation(submittedAmount, reimbursable, dis, vatInput, net);
    }

    public record ClaimBuildResult(
            ExpenseClaim claim,
            List<ExpenseClaimLine> lines,
            List<ExpenseClaimAdvanceOffset> offsets
    ) {}

    public ClaimBuildResult buildClaim(
            UUID tenantId, UUID bookId, String claimNo, UUID employeeId, UUID requisitionId,
            String title, LocalDate claimDate, UUID submitterId,
            List<ExpenseClaimLine> inputLines,
            Map<UUID, BigDecimal> advanceOffsets
    ) {
        if (inputLines == null || inputLines.isEmpty()) {
            throw new ValidationException("LINES_REQUIRED", "At least one claim line is required");
        }

        BigDecimal totalSubmitted = BigDecimal.ZERO.setScale(8, RoundingMode.UNNECESSARY);
        BigDecimal totalApproved = BigDecimal.ZERO.setScale(8, RoundingMode.UNNECESSARY);
        BigDecimal totalDisallowed = BigDecimal.ZERO.setScale(8, RoundingMode.UNNECESSARY);

        List<ExpenseClaimLine> finalizedLines = new ArrayList<>();
        UUID claimId = UUID.randomUUID();

        for (int i = 0; i < inputLines.size(); i++) {
            ExpenseClaimLine raw = inputLines.get(i);
            ClaimLineValidation val = calculateLineRecognition(raw.submittedAmount(), raw.disallowedAmount(), raw.vatRate());

            if (val.disallowedAmount().signum() > 0 && (raw.disallowanceReason() == null || raw.disallowanceReason().isBlank())) {
                throw new ValidationException("DISALLOWANCE_REASON_REQUIRED",
                        "Line " + (i + 1) + " has disallowed amount but missing disallowance reason");
            }

            finalizedLines.add(new ExpenseClaimLine(
                    UUID.randomUUID(), claimId, i + 1, raw.expenseCategory(),
                    raw.departmentId(), raw.projectId(), raw.invoiceId(), raw.invoiceCode(),
                    raw.invoiceNumber(), raw.invoiceDigest(), val.submittedAmount(),
                    val.approvedReimbursableAmount(), val.disallowedAmount(), raw.disallowanceReason(),
                    raw.vatRate() != null ? raw.vatRate() : BigDecimal.ZERO.setScale(12, RoundingMode.UNNECESSARY),
                    val.vatInputAmount(), val.netExpenseAmount(), raw.travelPolicyCapId(), raw.isExceptionApproved()
            ));

            totalSubmitted = totalSubmitted.add(val.submittedAmount());
            totalApproved = totalApproved.add(val.approvedReimbursableAmount());
            totalDisallowed = totalDisallowed.add(val.disallowedAmount());
        }

        BigDecimal totalOffset = BigDecimal.ZERO.setScale(8, RoundingMode.UNNECESSARY);
        List<ExpenseClaimAdvanceOffset> offsetList = new ArrayList<>();
        if (advanceOffsets != null) {
            for (Map.Entry<UUID, BigDecimal> entry : advanceOffsets.entrySet()) {
                BigDecimal amt = entry.getValue();
                if (amt == null || amt.signum() <= 0) {
                    throw new ValidationException("INVALID_OFFSET_AMOUNT", "Advance offset amount must be strictly positive");
                }
                offsetList.add(new ExpenseClaimAdvanceOffset(UUID.randomUUID(), claimId, entry.getKey(), amt, OffsetDateTime.now()));
                totalOffset = totalOffset.add(amt);
            }
        }

        if (totalOffset.compareTo(totalApproved) > 0) {
            throw new ValidationException("OFFSET_EXCEEDS_APPROVED",
                    "Total advance offset " + ExpenseDomain.toCny2String(totalOffset) +
                            " cannot exceed approved reimbursable amount " + ExpenseDomain.toCny2String(totalApproved));
        }

        BigDecimal netPayable = totalApproved.subtract(totalOffset);

        ExpenseClaim claim = new ExpenseClaim(
                claimId, tenantId, bookId, claimNo, employeeId, requisitionId, title, claimDate,
                ExpenseDomain.CURRENCY_CNY, totalSubmitted, totalApproved, totalDisallowed,
                totalOffset, netPayable, BigDecimal.ZERO.setScale(8, RoundingMode.UNNECESSARY),
                ClaimStatus.DRAFT, submitterId, null, null, null, null, null, null,
                OffsetDateTime.now(), 1
        );

        return new ClaimBuildResult(claim, finalizedLines, offsetList);
    }

    public ExpenseClaim submitClaim(ExpenseClaim claim) {
        if (claim.status() != ClaimStatus.DRAFT) {
            throw new ValidationException("INVALID_STATUS", "Only DRAFT claim can be submitted");
        }
        return new ExpenseClaim(
                claim.id(), claim.tenantId(), claim.bookId(), claim.claimNo(), claim.employeeId(),
                claim.requisitionId(), claim.title(), claim.claimDate(), claim.currency(),
                claim.submittedAmount(), claim.approvedAmount(), claim.disallowedAmount(),
                claim.offsetAdvanceAmount(), claim.payableAmount(), claim.paidAmount(),
                ClaimStatus.SUBMITTED, claim.submitterId(), null, null, null, null,
                claim.journalId(), claim.reversalJournalId(), claim.createdAt(), claim.version() + 1
        );
    }

    public ExpenseClaim reviewClaim(ExpenseClaim claim, UUID reviewerId) {
        if (claim.status() != ClaimStatus.SUBMITTED) {
            throw new ValidationException("INVALID_STATUS", "Only SUBMITTED claim can be reviewed");
        }
        ExpenseDomain.checkSod(claim.submitterId(), reviewerId, "Expense claim department review");
        ExpenseDomain.checkBeneficiarySod(claim.employeeId(), reviewerId, "Expense claim department review");

        return new ExpenseClaim(
                claim.id(), claim.tenantId(), claim.bookId(), claim.claimNo(), claim.employeeId(),
                claim.requisitionId(), claim.title(), claim.claimDate(), claim.currency(),
                claim.submittedAmount(), claim.approvedAmount(), claim.disallowedAmount(),
                claim.offsetAdvanceAmount(), claim.payableAmount(), claim.paidAmount(),
                ClaimStatus.REVIEWED, claim.submitterId(), reviewerId, null,
                OffsetDateTime.now(), null, claim.journalId(), claim.reversalJournalId(),
                claim.createdAt(), claim.version() + 1
        );
    }

    public ExpenseClaim approveClaim(ExpenseClaim claim, UUID approverId) {
        if (claim.status() != ClaimStatus.REVIEWED) {
            throw new ValidationException("INVALID_STATUS", "Only REVIEWED claim can be approved");
        }
        ExpenseDomain.checkSod(claim.submitterId(), approverId, "Expense claim finance approval");
        ExpenseDomain.checkBeneficiarySod(claim.employeeId(), approverId, "Expense claim finance approval");
        ExpenseDomain.checkSod(claim.reviewerId(), approverId, "Expense claim finance approver vs reviewer");

        return new ExpenseClaim(
                claim.id(), claim.tenantId(), claim.bookId(), claim.claimNo(), claim.employeeId(),
                claim.requisitionId(), claim.title(), claim.claimDate(), claim.currency(),
                claim.submittedAmount(), claim.approvedAmount(), claim.disallowedAmount(),
                claim.offsetAdvanceAmount(), claim.payableAmount(), claim.paidAmount(),
                ClaimStatus.APPROVED, claim.submitterId(), claim.reviewerId(), approverId,
                claim.reviewedAt(), OffsetDateTime.now(), claim.journalId(), claim.reversalJournalId(),
                claim.createdAt(), claim.version() + 1
        );
    }

    public ExpenseClaim rejectClaim(ExpenseClaim claim) {
        if (claim.status() != ClaimStatus.SUBMITTED && claim.status() != ClaimStatus.REVIEWED) {
            throw new ValidationException("INVALID_STATUS", "Only SUBMITTED or REVIEWED claim can be rejected");
        }
        return new ExpenseClaim(
                claim.id(), claim.tenantId(), claim.bookId(), claim.claimNo(), claim.employeeId(),
                claim.requisitionId(), claim.title(), claim.claimDate(), claim.currency(),
                claim.submittedAmount(), claim.approvedAmount(), claim.disallowedAmount(),
                claim.offsetAdvanceAmount(), claim.payableAmount(), claim.paidAmount(),
                ClaimStatus.REJECTED, claim.submitterId(), claim.reviewerId(), claim.approverId(),
                claim.reviewedAt(), claim.approvedAt(), claim.journalId(), claim.reversalJournalId(),
                claim.createdAt(), claim.version() + 1
        );
    }

    public ExpenseClaim cancelClaim(ExpenseClaim claim) {
        if (claim.status() == ClaimStatus.POSTED || claim.status() == ClaimStatus.PAID || claim.status() == ClaimStatus.SETTLED || claim.status() == ClaimStatus.REVERSED) {
            throw new ValidationException("INVALID_STATUS", "Cannot cancel claim in status: " + claim.status());
        }
        return new ExpenseClaim(
                claim.id(), claim.tenantId(), claim.bookId(), claim.claimNo(), claim.employeeId(),
                claim.requisitionId(), claim.title(), claim.claimDate(), claim.currency(),
                claim.submittedAmount(), claim.approvedAmount(), claim.disallowedAmount(),
                claim.offsetAdvanceAmount(), claim.payableAmount(), claim.paidAmount(),
                ClaimStatus.CANCELLED, claim.submitterId(), claim.reviewerId(), claim.approverId(),
                claim.reviewedAt(), claim.approvedAt(), claim.journalId(), claim.reversalJournalId(),
                claim.createdAt(), claim.version() + 1
        );
    }

    public ExpensePayableLedger generatePayableLedger(ExpenseClaim claim) {
        if (claim.status() != ClaimStatus.APPROVED && claim.status() != ClaimStatus.POSTED) {
            throw new ValidationException("INVALID_STATUS", "Cannot generate payable ledger for unapproved claim");
        }
        SettlementStatus settlement = claim.payableAmount().signum() == 0 ? SettlementStatus.SETTLED : SettlementStatus.UNPAID;
        return new ExpensePayableLedger(
                UUID.randomUUID(), claim.tenantId(), claim.bookId(), claim.id(), claim.employeeId(),
                claim.payableAmount(), BigDecimal.ZERO.setScale(8, RoundingMode.UNNECESSARY),
                claim.payableAmount(), settlement, OffsetDateTime.now(), 1
        );
    }

    public record DisburseResult(
            ExpensePayableLedger updatedLedger,
            ExpensePayment paymentSlip,
            ExpenseClaim updatedClaim
    ) {}

    public DisburseResult disbursePayment(
            ExpensePayableLedger ledger,
            ExpenseClaim claim,
            String paymentNo,
            BigDecimal amount,
            LocalDate paymentDate,
            String paymentMethod,
            String paymentRef,
            UUID cashierId,
            UUID journalId
    ) {
        return disbursePayment(UUID.randomUUID(), ledger, claim, paymentNo, amount, paymentDate, paymentMethod, paymentRef, cashierId, journalId);
    }

    public DisburseResult disbursePayment(
            UUID paymentId,
            ExpensePayableLedger ledger,
            ExpenseClaim claim,
            String paymentNo,
            BigDecimal amount,
            LocalDate paymentDate,
            String paymentMethod,
            String paymentRef,
            UUID cashierId,
            UUID journalId
    ) {
        if (ledger.settlementStatus() == SettlementStatus.SETTLED || ledger.settlementStatus() == SettlementStatus.CANCELLED) {
            throw new ValidationException("ALREADY_SETTLED", "Payable ledger is already settled or cancelled: " + ledger.settlementStatus());
        }
        if (amount.signum() <= 0) {
            throw new ValidationException("INVALID_AMOUNT", "Payment amount must be positive");
        }
        if (amount.compareTo(ledger.outstandingBalance()) > 0) {
            throw new ValidationException("PAYMENT_EXCEEDS_OUTSTANDING",
                    "Payment amount " + ExpenseDomain.toCny2String(amount) +
                            " exceeds outstanding payable balance " + ExpenseDomain.toCny2String(ledger.outstandingBalance()));
        }

        BigDecimal newPaid = ledger.paidAmount().add(amount);
        BigDecimal newOutstanding = ledger.outstandingBalance().subtract(amount);
        SettlementStatus newStatus = newOutstanding.signum() == 0 ? SettlementStatus.SETTLED : SettlementStatus.PARTIALLY_PAID;

        ExpensePayableLedger updatedLedger = new ExpensePayableLedger(
                ledger.id(), ledger.tenantId(), ledger.bookId(), ledger.claimId(), ledger.employeeId(),
                ledger.payableAmount(), newPaid, newOutstanding, newStatus,
                ledger.createdAt(), ledger.version() + 1
        );

        ExpensePayment slip = new ExpensePayment(
                paymentId != null ? paymentId : UUID.randomUUID(), ledger.tenantId(), ledger.id(), paymentNo, amount,
                paymentDate, paymentMethod, paymentRef, cashierId, journalId, OffsetDateTime.now()
        );

        BigDecimal claimNewPaid = claim.paidAmount().add(amount);
        ClaimStatus claimNewStatus = newStatus == SettlementStatus.SETTLED ? ClaimStatus.SETTLED : ClaimStatus.PAID;
        ExpenseClaim updatedClaim = new ExpenseClaim(
                claim.id(), claim.tenantId(), claim.bookId(), claim.claimNo(), claim.employeeId(),
                claim.requisitionId(), claim.title(), claim.claimDate(), claim.currency(),
                claim.submittedAmount(), claim.approvedAmount(), claim.disallowedAmount(),
                claim.offsetAdvanceAmount(), claim.payableAmount(), claimNewPaid,
                claimNewStatus, claim.submitterId(), claim.reviewerId(), claim.approverId(),
                claim.reviewedAt(), claim.approvedAt(), claim.journalId(), claim.reversalJournalId(),
                claim.createdAt(), claim.version() + 1
        );

        return new DisburseResult(updatedLedger, slip, updatedClaim);
    }
}

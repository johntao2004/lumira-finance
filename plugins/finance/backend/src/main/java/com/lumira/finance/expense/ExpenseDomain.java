package com.lumira.finance.expense;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Domain types and invariants for EXP01-EXP04 Expense Management & Travel Standards.
 * Enforces numeric(28,8) money scale, strict 2-decimal CNY representation, and natural-person SoD.
 */
public final class ExpenseDomain {
    private ExpenseDomain() {}

    public static final String CURRENCY_CNY = "CNY";
    public static final int MONEY_SCALE = 8;
    public static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(8, RoundingMode.UNNECESSARY);

    // --- Enums ---
    public enum CityTier { TIER_1, TIER_2, TIER_3, SPECIAL }
    public enum EmployeeRank { STAFF, MANAGER, EXECUTIVE }
    public enum ExpenseCategory { HOTEL, MEAL, TRANSPORT, DAILY_ALLOWANCE, INCIDENTAL, OFFICE, TRAINING, OTHER }
    public enum PolicyStatus { DRAFT, ACTIVE, RETIRED }
    public enum TravelExceptionStatus { PENDING, APPROVED, REJECTED }
    public enum RequisitionStatus { DRAFT, SUBMITTED, APPROVED, CLOSED, CANCELLED }
    public enum AdvanceStatus { DRAFT, SUBMITTED, APPROVED, PAID, PARTIALLY_OFFSET, SETTLED, CANCELLED }
    public enum ClaimStatus { DRAFT, SUBMITTED, REVIEWED, APPROVED, POSTED, PAID, SETTLED, REJECTED, CANCELLED, REVERSED }
    public enum SettlementStatus { UNPAID, PARTIALLY_PAID, SETTLED, CANCELLED }

    public static class ValidationException extends RuntimeException {
        private final String errorCode;
        public ValidationException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }
        public String getErrorCode() { return errorCode; }
    }

    // --- Math & Validation Helpers ---
    public static BigDecimal money(String raw) {
        if (raw == null) throw new ValidationException("INVALID_AMOUNT", "Amount cannot be null");
        String s = raw.strip();
        if (!s.matches("^(?:0|[1-9]\\d{0,19})(?:\\.\\d{1,8})?$")) {
            throw new ValidationException("INVALID_AMOUNT_FORMAT", "Invalid amount string: " + raw);
        }
        return new BigDecimal(s).setScale(8, RoundingMode.HALF_UP);
    }

    public static BigDecimal cny(String raw, boolean allowZero) {
        if (raw == null) throw new ValidationException("INVALID_AMOUNT", "Amount cannot be null");
        String s = raw.strip();
        if (!s.matches("^(?:0|[1-9]\\d{0,19})(?:\\.\\d{1,2})?$")) {
            throw new ValidationException("INVALID_CURRENCY_SCALE", "CNY requires at most 2 decimal places: " + raw);
        }
        BigDecimal bd = new BigDecimal(s).setScale(8, RoundingMode.HALF_UP);
        if (!allowZero && bd.signum() <= 0) {
            throw new ValidationException("INVALID_AMOUNT", "Amount must be strictly positive: " + raw);
        }
        if (allowZero && bd.signum() < 0) {
            throw new ValidationException("INVALID_AMOUNT", "Amount cannot be negative: " + raw);
        }
        return bd;
    }

    public static String toCny2String(BigDecimal amount) {
        if (amount == null) return "0.00";
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public static String toStoredPlainString(BigDecimal amount) {
        if (amount == null) return "0.00000000";
        return amount.setScale(8, RoundingMode.HALF_UP).toPlainString();
    }

    public static void checkSod(UUID person1, UUID person2, String message) {
        if (person1 != null && person2 != null && person1.equals(person2)) {
            throw new ValidationException("SOD_VIOLATION", message + ": Person " + person1 + " cannot approve or review their own action.");
        }
    }

    public static void checkBeneficiarySod(UUID beneficiary, UUID reviewerOrApprover, String message) {
        if (beneficiary != null && reviewerOrApprover != null && beneficiary.equals(reviewerOrApprover)) {
            throw new ValidationException("SOD_VIOLATION", message + ": Beneficiary employee " + beneficiary + " cannot review or approve their own claim or advance.");
        }
    }

    // --- Records ---
    public record TravelPolicy(
            UUID id,
            UUID tenantId,
            String policyCode,
            int versionInt,
            String name,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            PolicyStatus status,
            OffsetDateTime createdAt,
            UUID createdBy
    ) {}

    public record TravelPolicyCap(
            UUID id,
            UUID policyId,
            CityTier cityTier,
            EmployeeRank employeeRank,
            ExpenseCategory expenseCategory,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal dailyCapAmount,
            String currency
    ) {}

    public record TravelException(
            UUID id,
            UUID tenantId,
            UUID claimId,
            int lineNo,
            UUID policyCapId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal requestedAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal standardCapAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal excessAmount,
            UUID applicantPersonId,
            UUID approverPersonId,
            TravelExceptionStatus status,
            String justification,
            String decisionNotes,
            OffsetDateTime decidedAt,
            OffsetDateTime createdAt
    ) {}

    public record ExpenseRequisition(
            UUID id,
            UUID tenantId,
            UUID bookId,
            String requisitionNo,
            UUID applicantId,
            UUID departmentId,
            UUID projectId,
            String businessPurpose,
            String currency,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalBudgetAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal reservedAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal utilizedAmount,
            RequisitionStatus status,
            UUID approverId,
            OffsetDateTime approvedAt,
            LocalDate effectiveUntil,
            OffsetDateTime createdAt,
            long version
    ) {
        public BigDecimal availableAmount() {
            if (status == RequisitionStatus.CLOSED || status == RequisitionStatus.CANCELLED) {
                return ZERO_MONEY;
            }
            if (status == RequisitionStatus.DRAFT) {
                return totalBudgetAmount;
            }
            return reservedAmount;
        }
    }

    public record ExpenseAdvance(
            UUID id,
            UUID tenantId,
            UUID bookId,
            String advanceNo,
            UUID employeeId,
            UUID requisitionId,
            String purpose,
            String currency,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal principalAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal offsetAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal refundedAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal remainingBalance,
            AdvanceStatus status,
            UUID applicantId,
            UUID approverId,
            UUID cashierId,
            String payoutRef,
            OffsetDateTime payoutAt,
            OffsetDateTime createdAt,
            long version
    ) {}

    public record ExpenseAdvanceRefund(
            UUID id,
            UUID tenantId,
            UUID advanceId,
            String refundNo,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal refundAmount,
            LocalDate refundDate,
            String receiptMethod,
            String receiptRef,
            UUID cashierPersonId,
            OffsetDateTime createdAt
    ) {}

    public record ExpenseClaim(
            UUID id,
            UUID tenantId,
            UUID bookId,
            String claimNo,
            UUID employeeId,
            UUID requisitionId,
            String title,
            LocalDate claimDate,
            String currency,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal submittedAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal approvedAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal disallowedAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal offsetAdvanceAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal payableAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal paidAmount,
            ClaimStatus status,
            UUID submitterId,
            UUID reviewerId,
            UUID approverId,
            OffsetDateTime reviewedAt,
            OffsetDateTime approvedAt,
            UUID journalId,
            UUID reversalJournalId,
            OffsetDateTime createdAt,
            long version
    ) {}

    public record ExpenseClaimLine(
            UUID id,
            UUID claimId,
            int lineNo,
            ExpenseCategory expenseCategory,
            UUID departmentId,
            UUID projectId,
            UUID invoiceId,
            String invoiceCode,
            String invoiceNumber,
            String invoiceDigest,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal submittedAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal approvedReimbursableAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal disallowedAmount,
            String disallowanceReason,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal vatRate,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal vatInputAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal netExpenseAmount,
            UUID travelPolicyCapId,
            boolean isExceptionApproved
    ) {}

    public record ExpenseClaimAdvanceOffset(
            UUID id,
            UUID claimId,
            UUID advanceId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal offsetAmount,
            OffsetDateTime createdAt
    ) {}

    public record ExpensePayableLedger(
            UUID id,
            UUID tenantId,
            UUID bookId,
            UUID claimId,
            UUID employeeId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal payableAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal paidAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal outstandingBalance,
            SettlementStatus settlementStatus,
            OffsetDateTime createdAt,
            long version
    ) {}

    public record ExpensePayment(
            UUID id,
            UUID tenantId,
            UUID payableId,
            String paymentNo,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
            LocalDate paymentDate,
            String paymentMethod,
            String paymentRef,
            UUID cashierId,
            UUID journalId,
            OffsetDateTime createdAt
    ) {}

    public record JournalLine(
            int lineNumber,
            String accountCode,
            String accountName,
            String direction, // "DEBIT" or "CREDIT"
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
            UUID departmentId,
            UUID projectId,
            String summary
    ) {}

    public record ExpenseJournalPlan(
            UUID journalId,
            UUID tenantId,
            UUID bookId,
            String journalType, // EXPENSE_CLAIM, EXPENSE_PAYMENT, EXPENSE_REVERSAL
            LocalDate journalDate,
            String description,
            UUID referenceId,
            List<JournalLine> lines,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalDebit,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalCredit
    ) {}
}

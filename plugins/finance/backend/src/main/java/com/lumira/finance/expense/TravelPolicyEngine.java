package com.lumira.finance.expense;

import com.lumira.finance.expense.ExpenseDomain.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Engine for EXP04 Travel & Expense Standards.
 * Handles policy versioning, cap evaluations, version freezing, and independent exception approvals.
 */
@Component
public final class TravelPolicyEngine {

    public enum EvaluationStatus { COMPLIANT, OVER_CAP, NO_POLICY_FOUND }

    public record CapEvaluationResult(
            EvaluationStatus status,
            TravelPolicy policy,
            TravelPolicyCap cap,
            BigDecimal submittedAmount,
            BigDecimal standardCapAmount,
            BigDecimal excessAmount,
            String message
    ) {}

    public TravelPolicy createDraftPolicy(UUID tenantId, String policyCode, int versionInt, String name,
                                          LocalDate effectiveFrom, LocalDate effectiveTo, UUID createdBy) {
        if (policyCode == null || policyCode.isBlank()) {
            throw new ValidationException("POLICY_CODE_REQUIRED", "policyCode is mandatory");
        }
        if (versionInt <= 0) {
            throw new ValidationException("INVALID_VERSION", "versionInt must be strictly positive");
        }
        if (effectiveFrom == null) {
            throw new ValidationException("EFFECTIVE_FROM_REQUIRED", "effectiveFrom date is mandatory");
        }
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new ValidationException("INVALID_POLICY_DATES", "effectiveTo cannot be before effectiveFrom");
        }
        return new TravelPolicy(
                UUID.randomUUID(),
                tenantId,
                policyCode,
                versionInt,
                name,
                effectiveFrom,
                effectiveTo,
                PolicyStatus.DRAFT,
                OffsetDateTime.now(),
                createdBy
        );
    }

    public TravelPolicy activatePolicy(TravelPolicy policy) {
        if (policy.status() != PolicyStatus.DRAFT) {
            throw new ValidationException("INVALID_STATUS", "Only DRAFT policy can be activated");
        }
        return new TravelPolicy(
                policy.id(),
                policy.tenantId(),
                policy.policyCode(),
                policy.versionInt(),
                policy.name(),
                policy.effectiveFrom(),
                policy.effectiveTo(),
                PolicyStatus.ACTIVE,
                policy.createdAt(),
                policy.createdBy()
        );
    }

    public CapEvaluationResult evaluateCap(
            TravelPolicy policy,
            List<TravelPolicyCap> caps,
            LocalDate date,
            CityTier tier,
            EmployeeRank rank,
            ExpenseCategory category,
            BigDecimal submittedAmount
    ) {
        if (date == null) {
            throw new ValidationException("DATE_REQUIRED", "Expense date is mandatory for policy evaluation");
        }
        if (submittedAmount == null || submittedAmount.signum() < 0) {
            throw new ValidationException("INVALID_AMOUNT", "Submitted amount cannot be null or negative");
        }
        if (policy == null || policy.status() != PolicyStatus.ACTIVE) {
            return new CapEvaluationResult(EvaluationStatus.NO_POLICY_FOUND, null, null, submittedAmount,
                    BigDecimal.ZERO, BigDecimal.ZERO, "No active policy found");
        }

        // Effective date check
        if (date.isBefore(policy.effectiveFrom()) || (policy.effectiveTo() != null && date.isAfter(policy.effectiveTo()))) {
            return new CapEvaluationResult(EvaluationStatus.NO_POLICY_FOUND, policy, null, submittedAmount,
                    BigDecimal.ZERO, BigDecimal.ZERO, "Expense date outside policy effective window");
        }

        // Match cap by tier, rank, category
        Optional<TravelPolicyCap> match = caps.stream()
                .filter(c -> c.cityTier() == tier && c.employeeRank() == rank && c.expenseCategory() == category)
                .findFirst();

        if (match.isEmpty()) {
            return new CapEvaluationResult(EvaluationStatus.NO_POLICY_FOUND, policy, null, submittedAmount,
                    BigDecimal.ZERO, BigDecimal.ZERO, "No matching cap rule for criteria");
        }

        TravelPolicyCap cap = match.get();
        BigDecimal dailyCap = cap.dailyCapAmount();
        if (submittedAmount.compareTo(dailyCap) <= 0) {
            return new CapEvaluationResult(EvaluationStatus.COMPLIANT, policy, cap, submittedAmount, dailyCap,
                    BigDecimal.ZERO, "Within travel policy standard cap");
        } else {
            BigDecimal excess = submittedAmount.subtract(dailyCap);
            return new CapEvaluationResult(EvaluationStatus.OVER_CAP, policy, cap, submittedAmount, dailyCap,
                    excess, "Exceeds daily cap by " + ExpenseDomain.toCny2String(excess) + " CNY. Exception approval required.");
        }
    }

    public TravelException createExceptionRequest(
            UUID tenantId,
            UUID claimId,
            int lineNo,
            UUID policyCapId,
            BigDecimal requestedAmount,
            BigDecimal standardCapAmount,
            UUID applicantPersonId,
            String justification
    ) {
        if (justification == null || justification.isBlank()) {
            throw new ValidationException("JUSTIFICATION_REQUIRED", "Exception justification is mandatory");
        }
        BigDecimal excess = requestedAmount.subtract(standardCapAmount);
        if (excess.signum() <= 0) {
            throw new ValidationException("NO_EXCESS", "Requested amount does not exceed standard cap");
        }

        return new TravelException(
                UUID.randomUUID(),
                tenantId,
                claimId,
                lineNo,
                policyCapId,
                requestedAmount,
                standardCapAmount,
                excess,
                applicantPersonId,
                null,
                TravelExceptionStatus.PENDING,
                justification,
                null,
                null,
                OffsetDateTime.now()
        );
    }

    public TravelException decideException(
            TravelException exception,
            UUID approverPersonId,
            boolean approve,
            String decisionNotes
    ) {
        if (exception.status() != TravelExceptionStatus.PENDING) {
            throw new ValidationException("ALREADY_DECIDED", "Exception has already been decided: " + exception.status());
        }
        ExpenseDomain.checkSod(exception.applicantPersonId(), approverPersonId, "Travel exception approval");

        return new TravelException(
                exception.id(),
                exception.tenantId(),
                exception.claimId(),
                exception.lineNo(),
                exception.policyCapId(),
                exception.requestedAmount(),
                exception.standardCapAmount(),
                exception.excessAmount(),
                exception.applicantPersonId(),
                approverPersonId,
                approve ? TravelExceptionStatus.APPROVED : TravelExceptionStatus.REJECTED,
                exception.justification(),
                decisionNotes,
                OffsetDateTime.now(),
                exception.createdAt()
        );
    }
}

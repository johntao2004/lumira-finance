package com.lumira.finance.payrolltax;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Data transfer models, view representations, and command records for
 * Payroll and Tax Extensions (PAY03, PAY04, TAX01, TAX05).
 * All monetary amounts in views are formatted as decimal strings via BigDecimal.toPlainString().
 */
public final class PayrollTaxExtModel {

    private PayrollTaxExtModel() {}

    public enum BatchState {
        DRAFT,
        CALCULATED,
        REVIEWED,
        APPROVED,
        LOCKED,
        CANCELLED
    }

    // --- Bonus Batch Models (PAY03) ---

    public record CreateBonusBatchCommand(
            UUID withholdingEntityId,
            int taxYear,
            String bonusName,
            LocalDate payDate,
            List<BonusItemEntry> entries
    ) implements Serializable {}

    public record ReviewBatchCommand(long expectedVersion) implements Serializable {}
    public record ApproveBatchCommand(long expectedVersion) implements Serializable {}
    public record LockBatchCommand(long expectedVersion) implements Serializable {}

    public record BonusItemEntry(
            UUID employeeId,
            String bonusAmount,
            String annualTaxableSalary,
            BonusCalculationEngine.BonusMode requestedMode
    ) implements Serializable {}

    public record BonusBatchView(
            String id,
            String withholdingEntityId,
            int taxYear,
            String bonusName,
            String payDate,
            String state,
            String preparerId,
            String reviewerId,
            String approverId,
            String lockedAt,
            String externalTaxChannel,
            long version,
            String totalBonusAmount,
            String totalWithholdingTax,
            String totalNetAmount,
            List<BonusItemView> items
    ) implements Serializable {}

    public record BonusItemView(
            String id,
            String employeeId,
            String bonusAmount,
            String calculationMode,
            String monthlyQuotient,
            String applicableRate,
            String quickDeduction,
            String withholdingTax,
            String netAmount,
            String comparisonRecommendedMode,
            String comparisonTaxSavings,
            CalculationExplainTree explainTree
    ) implements Serializable {}

    // --- Labor Service Remuneration Models (PAY04) ---

    public record CreateLaborBatchCommand(
            UUID withholdingEntityId,
            String period,
            String batchName,
            LocalDate payDate,
            List<LaborItemEntry> entries
    ) implements Serializable {}

    public record LaborItemEntry(
            UUID naturalPersonId,
            LaborRemunerationEngine.RemunerationType type,
            String grossPayment,
            boolean relief1Pct,
            String cityRate,
            boolean sixTaxesHalf,
            boolean educationExempt,
            boolean proxyInvoiceCompleted,
            String proxyInvoiceRef
    ) implements Serializable {}

    public record LaborBatchView(
            String id,
            String withholdingEntityId,
            String period,
            String batchName,
            String payDate,
            String state,
            String preparerId,
            String reviewerId,
            String approverId,
            String lockedAt,
            String externalTaxChannel,
            long version,
            String totalGrossPayment,
            String totalIitWithholding,
            String totalVatWithheld,
            String totalSurchargesWithheld,
            String totalNetPayable,
            List<LaborItemView> items
    ) implements Serializable {}

    public record LaborItemView(
            String id,
            String naturalPersonId,
            String serviceCategory,
            String grossPayment,
            String deductionAmount,
            String taxableIncome,
            String iitRate,
            String iitQuickDeduction,
            String iitWithholding,
            String vatSales,
            boolean vatExempt,
            String vatRate,
            String vatReduction,
            String vatWithheld,
            String citySurtax,
            String educationSurcharge,
            String localEducationSurcharge,
            String totalSurchargeWithheld,
            String netPayable,
            boolean proxyInvoiceCompleted,
            String proxyInvoiceRef,
            CalculationExplainTree explainTree
    ) implements Serializable {}

    // --- Tax Policy & Qualification Models (TAX01) ---

    public record CreatePolicyPackCommand(
            String id,
            String taxType,
            String version,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String digest,
            String approvalStatus,
            Boolean attestationRequired,
            Map<String, Object> rules
    ) implements Serializable {}

    public record PolicyPackView(
            String id,
            String taxType,
            String version,
            String effectiveFrom,
            String effectiveTo,
            String digest,
            String approvalStatus,
            boolean attestationRequired,
            Map<String, Object> rules
    ) implements Serializable {}

    public record EvaluateCitCommand(
            String policyPackId,
            UUID taxEntityId,
            LocalDate periodDate,
            String annualTaxableProfit,
            String averageStaffCount,
            String totalAssets,
            UUID attestationId
    ) implements Serializable {}

    public record EvaluateCitResultView(
            String taxType,
            String baseAmount,
            String statutoryRate,
            String appliedRate,
            String statutoryTax,
            String reliefTax,
            String payableTax,
            boolean preferentialApplied,
            CalculationExplainTree explainTree
    ) implements Serializable {}

    // --- Calendar Models (TAX05) ---

    public record GenerateCalendarCommand(
            String taxType,
            int year,
            int month,
            String region,
            String calendarPackVersion
    ) implements Serializable {}

    public record CalendarDeadlineView(
            String taxType,
            int year,
            int month,
            String baseDeadline,
            String adjustedDeadline,
            boolean isDeferred,
            String deferralReason,
            String calendarPackVersion
    ) implements Serializable {}

    // --- Form Lock Models (TAX05) ---

    public record LockFormCommand(
            UUID taxObligationId,
            String formCode,
            int versionNumber,
            String contentDigest,
            Map<String, String> cellValues,
            Set<String> mandatoryAttachments
    ) implements Serializable {}

    public record FormLockView(
            String id,
            String taxObligationId,
            String formCode,
            int versionNumber,
            boolean isLocked,
            String lockedAt,
            String lockedByNaturalPerson,
            String contentDigest
    ) implements Serializable {}

    // --- Schedule Check Models (TAX05) ---

    public record CheckScheduleCommand(
            String formType,
            Map<String, String> figures,
            Set<String> attachedScheduleCodes
    ) implements Serializable {}

    public record ScheduleCheckResultView(
            boolean passed,
            List<MandatoryScheduleChecker.ScheduleRequirement> requiredSchedules,
            List<MandatoryScheduleChecker.ScheduleRequirement> missingSchedules
    ) implements Serializable {}
}

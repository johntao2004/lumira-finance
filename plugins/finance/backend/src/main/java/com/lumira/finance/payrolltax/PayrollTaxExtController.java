package com.lumira.finance.payrolltax;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import com.lumira.finance.FinanceGlobalExceptionHandler;
import com.lumira.platform.modules.ModuleEndpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller for Candidate Lane W10 (payroll-tax-ext).
 * Exposes PAY03 (Bonus lifecycle: create, get, review, approve, lock),
 * PAY04 (Labor service lifecycle: create, get, review, approve, lock & VAT withholding),
 * TAX01 (Policy library & calculation explainer), and
 * TAX05 (Filing calendar with tenant calendar pack lookup, form lock, mandatory schedules).
 * Formats all monetary values as decimal strings via BigDecimal.toPlainString().
 */
@RestController
@RequestMapping("/api/finance/v1/books/{book}")
@ModuleEndpoint("finance")
public class PayrollTaxExtController {

    private final PayrollTaxExtService service;

    public PayrollTaxExtController(PayrollTaxExtService service) {
        this.service = Objects.requireNonNull(service, "SERVICE_REQUIRED");
    }

    // --- PAY03: Bonus Batch Endpoints ---

    @PostMapping("/payroll-bonus/batches")
    public ResponseEntity<?> createBonusBatch(
            @PathVariable("book") String book,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-Actor-Person-Id") UUID preparerId,
            @RequestBody PayrollTaxExtModel.CreateBonusBatchCommand command
    ) {
        try {
            PayrollTaxExtService.BonusBatchAggregate batch = service.createAndCalculateBonusBatch(tenantId, preparerId, command);
            return ResponseEntity.ok(toBonusBatchView(batch));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @GetMapping("/payroll-bonus/batches/{id}")
    public ResponseEntity<?> getBonusBatch(
            @PathVariable("book") String book,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable("id") UUID id
    ) {
        try {
            PayrollTaxExtService.BonusBatchAggregate batch = service.getBonusBatch(tenantId, id);
            return ResponseEntity.ok(toBonusBatchView(batch));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/payroll-bonus/batches/{id}/review")
    public ResponseEntity<?> reviewBonusBatch(
            @PathVariable("book") String book,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-Actor-Person-Id") UUID reviewerId,
            @PathVariable("id") UUID id,
            @RequestBody PayrollTaxExtModel.ReviewBatchCommand command
    ) {
        try {
            service.reviewBonusBatch(tenantId, id, reviewerId, command.expectedVersion());
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/payroll-bonus/batches/{id}/approve")
    public ResponseEntity<?> approveBonusBatch(
            @PathVariable("book") String book,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-Actor-Person-Id") UUID approverId,
            @PathVariable("id") UUID id,
            @RequestBody PayrollTaxExtModel.ApproveBatchCommand command
    ) {
        try {
            service.approveBonusBatch(tenantId, id, approverId, command.expectedVersion());
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/payroll-bonus/batches/{id}/lock")
    public ResponseEntity<?> lockBonusBatch(
            @PathVariable("book") String book,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable("id") UUID id,
            @RequestBody PayrollTaxExtModel.LockBatchCommand command
    ) {
        try {
            service.lockBonusBatch(tenantId, id, command.expectedVersion());
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // --- PAY04: Labor Service Endpoints ---

    @PostMapping("/payroll-labor/batches")
    public ResponseEntity<?> createLaborBatch(
            @PathVariable("book") String book,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-Actor-Person-Id") UUID preparerId,
            @RequestBody PayrollTaxExtModel.CreateLaborBatchCommand command
    ) {
        try {
            PayrollTaxExtService.LaborBatchAggregate batch = service.createAndCalculateLaborBatch(tenantId, preparerId, command);
            return ResponseEntity.ok(toLaborBatchView(batch));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @GetMapping("/payroll-labor/batches/{id}")
    public ResponseEntity<?> getLaborBatch(
            @PathVariable("book") String book,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable("id") UUID id
    ) {
        try {
            PayrollTaxExtService.LaborBatchAggregate batch = service.getLaborBatch(tenantId, id);
            return ResponseEntity.ok(toLaborBatchView(batch));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/payroll-labor/batches/{id}/review")
    public ResponseEntity<?> reviewLaborBatch(
            @PathVariable("book") String book,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-Actor-Person-Id") UUID reviewerId,
            @PathVariable("id") UUID id,
            @RequestBody PayrollTaxExtModel.ReviewBatchCommand command
    ) {
        try {
            service.reviewLaborBatch(tenantId, id, reviewerId, command.expectedVersion());
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/payroll-labor/batches/{id}/approve")
    public ResponseEntity<?> approveLaborBatch(
            @PathVariable("book") String book,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-Actor-Person-Id") UUID approverId,
            @PathVariable("id") UUID id,
            @RequestBody PayrollTaxExtModel.ApproveBatchCommand command
    ) {
        try {
            service.approveLaborBatch(tenantId, id, approverId, command.expectedVersion());
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/payroll-labor/batches/{id}/lock")
    public ResponseEntity<?> lockLaborBatch(
            @PathVariable("book") String book,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable("id") UUID id,
            @RequestBody PayrollTaxExtModel.LockBatchCommand command
    ) {
        try {
            service.lockLaborBatch(tenantId, id, command.expectedVersion());
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // --- TAX01: Policy Evaluation Endpoint ---

    @PostMapping("/tax-policy/packs")
    public ResponseEntity<?> createPolicyPack(
            @PathVariable("book") String book,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestBody PayrollTaxExtModel.CreatePolicyPackCommand command
    ) {
        try {
            TaxPolicyRuleEngine.PolicyPack pack = service.createPolicyPack(tenantId, command);
            return ResponseEntity.ok(toPolicyPackView(pack));
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @GetMapping("/tax-policy/packs/{id}")
    public ResponseEntity<?> getPolicyPack(
            @PathVariable("book") String book,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable("id") String id
    ) {
        try {
            return service.findPolicyPack(tenantId, id)
                    .map(pack -> ResponseEntity.ok(toPolicyPackView(pack)))
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/tax-policy/evaluate-cit")
    public ResponseEntity<?> evaluateCitPolicy(
            @PathVariable("book") String book,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestBody PayrollTaxExtModel.EvaluateCitCommand command
    ) {
        try {
            TaxPolicyRuleEngine.CalculationResult result = service.evaluateCitPolicy(tenantId, command);
            PayrollTaxExtModel.EvaluateCitResultView view = new PayrollTaxExtModel.EvaluateCitResultView(
                    result.taxType(),
                    result.baseAmount().toPlainString(),
                    result.statutoryRate().toPlainString(),
                    result.preferentialRate().toPlainString(),
                    result.statutoryTax().toPlainString(),
                    result.reliefTax().toPlainString(),
                    result.payableTax().toPlainString(),
                    result.preferentialApplied(),
                    result.explainTree()
            );
            return ResponseEntity.ok(view);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // --- TAX05: Filing Calendar Endpoint ---

    @PostMapping("/tax-calendar/deadlines")
    public ResponseEntity<?> generateCalendarDeadline(
            @PathVariable("book") String book,
            @RequestHeader(value = "X-Tenant-Id", required = false) UUID tenantId,
            @RequestBody PayrollTaxExtModel.GenerateCalendarCommand command
    ) {
        try {
            TaxFilingCalendarGenerator.FilingDeadline deadline = service.generateFilingCalendar(tenantId, command, Map.of());
            PayrollTaxExtModel.CalendarDeadlineView view = new PayrollTaxExtModel.CalendarDeadlineView(
                    deadline.taxType(),
                    deadline.year(),
                    deadline.month(),
                    deadline.baseDeadline().toString(),
                    deadline.adjustedDeadline().toString(),
                    deadline.isDeferred(),
                    deadline.deferralReason(),
                    deadline.calendarPackVersion()
            );
            return ResponseEntity.ok(view);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // --- TAX05: Form Cell Lock & Schedule Check Endpoints ---

    @PostMapping("/tax-forms/check-schedules")
    public ResponseEntity<?> checkSchedules(
            @PathVariable("book") String book,
            @RequestBody PayrollTaxExtModel.CheckScheduleCommand command
    ) {
        try {
            MandatoryScheduleChecker.ValidationResult result = service.validateSchedules(command);
            PayrollTaxExtModel.ScheduleCheckResultView view = new PayrollTaxExtModel.ScheduleCheckResultView(
                    result.passed(),
                    result.requiredSchedules(),
                    result.missingSchedules()
            );
            return ResponseEntity.ok(view);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    @PostMapping("/tax-forms/locks")
    public ResponseEntity<?> lockForm(
            @PathVariable("book") String book,
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-Actor-Person-Id") UUID lockingPersonId,
            @RequestBody PayrollTaxExtModel.LockFormCommand command
    ) {
        try {
            UUID formId = service.lockFormVersion(tenantId, lockingPersonId, command);
            PayrollTaxExtModel.FormLockView view = new PayrollTaxExtModel.FormLockView(
                    formId.toString(),
                    command.taxObligationId().toString(),
                    command.formCode(),
                    command.versionNumber(),
                    true,
                    java.time.Instant.now().toString(),
                    lockingPersonId.toString(),
                    command.contentDigest()
            );
            return ResponseEntity.ok(view);
        } catch (Exception e) {
            return FinanceGlobalExceptionHandler.handleException(e);
        }
    }

    // --- Helper Methods ---

    private PayrollTaxExtModel.BonusBatchView toBonusBatchView(PayrollTaxExtService.BonusBatchAggregate batch) {
        BigDecimal totalBonus = BigDecimal.ZERO;
        BigDecimal totalTax = BigDecimal.ZERO;
        BigDecimal totalNet = BigDecimal.ZERO;
        List<PayrollTaxExtModel.BonusItemView> itemViews = new ArrayList<>();

        for (BonusCalculationEngine.BonusCalculationResult item : batch.items()) {
            totalBonus = totalBonus.add(item.bonusAmount());
            totalTax = totalTax.add(item.withholdingTax());
            totalNet = totalNet.add(item.netAmount());

            itemViews.add(new PayrollTaxExtModel.BonusItemView(
                    UUID.randomUUID().toString(),
                    item.employeeId().toString(),
                    item.bonusAmount().toPlainString(),
                    item.electedMode().name(),
                    item.monthlyQuotient().toPlainString(),
                    item.applicableRate().toPlainString(),
                    item.quickDeduction().toPlainString(),
                    item.withholdingTax().toPlainString(),
                    item.netAmount().toPlainString(),
                    item.recommendedMode().name(),
                    item.taxSavings().toPlainString(),
                    item.explainTree()
            ));
        }

        return new PayrollTaxExtModel.BonusBatchView(
                batch.id().toString(),
                batch.withholdingEntityId().toString(),
                batch.taxYear(),
                batch.bonusName(),
                batch.payDate().toString(),
                batch.state().name(),
                batch.preparerId().toString(),
                batch.reviewerId() == null ? null : batch.reviewerId().toString(),
                batch.approverId() == null ? null : batch.approverId().toString(),
                batch.lockedAt() == null ? null : batch.lockedAt().toString(),
                "NOT_VERIFIED",
                batch.version(),
                totalBonus.toPlainString(),
                totalTax.toPlainString(),
                totalNet.toPlainString(),
                itemViews
        );
    }

    private PayrollTaxExtModel.LaborBatchView toLaborBatchView(PayrollTaxExtService.LaborBatchAggregate batch) {
        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalIit = BigDecimal.ZERO;
        BigDecimal totalVat = BigDecimal.ZERO;
        BigDecimal totalSurcharges = BigDecimal.ZERO;
        BigDecimal totalNet = BigDecimal.ZERO;
        List<PayrollTaxExtModel.LaborItemView> itemViews = new ArrayList<>();

        for (LaborRemunerationEngine.LaborResult item : batch.items()) {
            totalGross = totalGross.add(item.grossPayment());
            totalIit = totalIit.add(item.iitWithholding());
            totalVat = totalVat.add(item.vatWithheld());
            totalSurcharges = totalSurcharges.add(item.totalSurchargeWithheld());
            totalNet = totalNet.add(item.netPayable());

            itemViews.add(new PayrollTaxExtModel.LaborItemView(
                    UUID.randomUUID().toString(),
                    item.naturalPersonId().toString(),
                    item.type().name(),
                    item.grossPayment().toPlainString(),
                    item.deductionAmount().toPlainString(),
                    item.taxableIncome().toPlainString(),
                    item.iitRate().toPlainString(),
                    item.iitQuickDeduction().toPlainString(),
                    item.iitWithholding().toPlainString(),
                    item.vatSales().toPlainString(),
                    item.vatExempt(),
                    item.vatRate().toPlainString(),
                    item.vatReduction().toPlainString(),
                    item.vatWithheld().toPlainString(),
                    item.citySurtax().toPlainString(),
                    item.educationSurcharge().toPlainString(),
                    item.localEducationSurcharge().toPlainString(),
                    item.totalSurchargeWithheld().toPlainString(),
                    item.netPayable().toPlainString(),
                    item.proxyInvoiceCompleted(),
                    item.proxyInvoiceRef(),
                    item.explainTree()
            ));
        }

        return new PayrollTaxExtModel.LaborBatchView(
                batch.id().toString(),
                batch.withholdingEntityId().toString(),
                batch.period(),
                batch.batchName(),
                batch.payDate().toString(),
                batch.state().name(),
                batch.preparerId().toString(),
                batch.reviewerId() == null ? null : batch.reviewerId().toString(),
                batch.approverId() == null ? null : batch.approverId().toString(),
                batch.lockedAt() == null ? null : batch.lockedAt().toString(),
                "NOT_VERIFIED",
                batch.version(),
                totalGross.toPlainString(),
                totalIit.toPlainString(),
                totalVat.toPlainString(),
                totalSurcharges.toPlainString(),
                totalNet.toPlainString(),
                itemViews
        );
    }

    private static PayrollTaxExtModel.PolicyPackView toPolicyPackView(TaxPolicyRuleEngine.PolicyPack pack) {
        return new PayrollTaxExtModel.PolicyPackView(
                pack.id(),
                pack.taxType(),
                pack.version(),
                pack.effectiveFrom().toString(),
                pack.effectiveTo().toString(),
                pack.digest(),
                pack.approvalStatus(),
                pack.attestationRequired(),
                pack.rules()
        );
    }
}

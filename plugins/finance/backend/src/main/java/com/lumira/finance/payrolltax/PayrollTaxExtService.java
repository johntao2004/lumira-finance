package com.lumira.finance.payrolltax;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * High-integrity service layer for PAY03, PAY04, TAX01, and TAX05.
 * Strictly enforces Separation of Duties (preparer != reviewer != approver),
 * once-per-tax-year-per-person bonus election guard, mandatory schedule validation before lock,
 * and tenant RLS isolation.
 */
@Service
public class PayrollTaxExtService {

    private final PayrollTaxExtJdbcStore store;

    public PayrollTaxExtService(PayrollTaxExtJdbcStore store) {
        this.store = Objects.requireNonNull(store, "STORE_REQUIRED");
    }

    // --- PAY03: Bonus Batch Lifecycle ---

    public record BonusBatchAggregate(
            UUID id,
            UUID withholdingEntityId,
            int taxYear,
            String bonusName,
            LocalDate payDate,
            PayrollTaxExtModel.BatchState state,
            UUID preparerId,
            UUID reviewerId,
            UUID approverId,
            Instant lockedAt,
            long version,
            List<BonusCalculationEngine.BonusCalculationResult> items
    ) {}

    public BonusBatchAggregate createAndCalculateBonusBatch(
            UUID tenantId,
            UUID preparerId,
            PayrollTaxExtModel.CreateBonusBatchCommand command
    ) {
        if (tenantId == null || preparerId == null) throw new IllegalArgumentException("TENANT_AND_PREPARER_REQUIRED");
        if (command.entries() == null || command.entries().isEmpty())
            throw new IllegalArgumentException("ENTRIES_REQUIRED");

        // Validate all entries and check once-per-tax-year-per-person separate election guard BEFORE creating batch
        for (PayrollTaxExtModel.BonusItemEntry entry : command.entries()) {
            if (entry.requestedMode() == BonusCalculationEngine.BonusMode.SEPARATE_MONTHLY_QUOTIENT) {
                if (store.hasUsedSeparateBonusElection(tenantId, command.withholdingEntityId(), entry.employeeId(), command.taxYear())) {
                    throw new IllegalStateException("BONUS_SEPARATE_CALCULATION_ALREADY_USED: Employee "
                            + entry.employeeId() + " has already used separate bonus calculation in tax year " + command.taxYear());
                }
            }
        }

        UUID batchId = UUID.randomUUID();
        store.insertBonusBatch(
                tenantId,
                batchId,
                command.withholdingEntityId(),
                command.taxYear(),
                command.bonusName(),
                command.payDate(),
                PayrollTaxExtModel.BatchState.CALCULATED,
                preparerId
        );

        List<BonusCalculationEngine.BonusCalculationResult> results = new ArrayList<>();
        for (PayrollTaxExtModel.BonusItemEntry entry : command.entries()) {
            BigDecimal bonusAmt = new BigDecimal(entry.bonusAmount());
            BigDecimal annualSalary = entry.annualTaxableSalary() == null ? BigDecimal.ZERO : new BigDecimal(entry.annualTaxableSalary());
            BonusCalculationEngine.BonusInput input = new BonusCalculationEngine.BonusInput(
                    entry.employeeId(),
                    bonusAmt,
                    annualSalary,
                    entry.requestedMode()
            );

            BonusCalculationEngine.BonusCalculationResult calc = BonusCalculationEngine.calculate(input);
            UUID itemId = UUID.randomUUID();
            store.insertBonusItem(tenantId, itemId, batchId, entry.employeeId(), calc);
            results.add(calc);
        }

        return new BonusBatchAggregate(
                batchId,
                command.withholdingEntityId(),
                command.taxYear(),
                command.bonusName(),
                command.payDate(),
                PayrollTaxExtModel.BatchState.CALCULATED,
                preparerId,
                null,
                null,
                null,
                0L,
                results
        );
    }

    public void reviewBonusBatch(
            UUID tenantId,
            UUID batchId,
            UUID reviewerId,
            long expectedVersion
    ) {
        PayrollTaxExtJdbcStore.BonusBatchRecord batch = store.findBonusBatch(tenantId, batchId)
                .orElseThrow(() -> new IllegalArgumentException("BATCH_NOT_FOUND: " + batchId));
        if (batch.state() != PayrollTaxExtModel.BatchState.CALCULATED) {
            throw new IllegalStateException("ILLEGAL_STATE_TRANSITION: Batch is in state " + batch.state() + ", expected CALCULATED");
        }
        if (reviewerId.equals(batch.preparerId())) {
            throw new IllegalStateException("SELF_APPROVAL_FORBIDDEN: Reviewer cannot be the same natural person as the preparer");
        }
        store.updateBonusBatchState(
                tenantId,
                batchId,
                PayrollTaxExtModel.BatchState.REVIEWED,
                reviewerId,
                null,
                null,
                expectedVersion
        );
    }

    public void reviewBonusBatch(
            UUID tenantId,
            UUID batchId,
            UUID reviewerId,
            UUID preparerId,
            long expectedVersion
    ) {
        if (reviewerId.equals(preparerId)) {
            throw new IllegalStateException("SELF_APPROVAL_FORBIDDEN: Reviewer cannot be the same natural person as the preparer");
        }
        reviewBonusBatch(tenantId, batchId, reviewerId, expectedVersion);
    }

    public void approveBonusBatch(
            UUID tenantId,
            UUID batchId,
            UUID approverId,
            long expectedVersion
    ) {
        PayrollTaxExtJdbcStore.BonusBatchRecord batch = store.findBonusBatch(tenantId, batchId)
                .orElseThrow(() -> new IllegalArgumentException("BATCH_NOT_FOUND: " + batchId));
        if (batch.state() != PayrollTaxExtModel.BatchState.REVIEWED) {
            throw new IllegalStateException("ILLEGAL_STATE_TRANSITION: Batch is in state " + batch.state() + ", expected REVIEWED");
        }
        if (approverId.equals(batch.preparerId())) {
            throw new IllegalStateException("SELF_APPROVAL_FORBIDDEN: Approver cannot be the same natural person as the preparer");
        }
        if (batch.reviewerId() != null && approverId.equals(batch.reviewerId())) {
            throw new IllegalStateException("SELF_APPROVAL_FORBIDDEN: Approver cannot be the same natural person as the reviewer");
        }
        store.updateBonusBatchState(
                tenantId,
                batchId,
                PayrollTaxExtModel.BatchState.APPROVED,
                null,
                approverId,
                null,
                expectedVersion
        );
    }

    public void approveBonusBatch(
            UUID tenantId,
            UUID batchId,
            UUID approverId,
            UUID preparerId,
            UUID reviewerId,
            long expectedVersion
    ) {
        if (approverId.equals(preparerId)) {
            throw new IllegalStateException("SELF_APPROVAL_FORBIDDEN: Approver cannot be the same natural person as the preparer");
        }
        if (reviewerId != null && approverId.equals(reviewerId)) {
            throw new IllegalStateException("SELF_APPROVAL_FORBIDDEN: Approver cannot be the same natural person as the reviewer");
        }
        approveBonusBatch(tenantId, batchId, approverId, expectedVersion);
    }

    public void lockBonusBatch(
            UUID tenantId,
            UUID batchId,
            long expectedVersion
    ) {
        PayrollTaxExtJdbcStore.BonusBatchRecord batch = store.findBonusBatch(tenantId, batchId)
                .orElseThrow(() -> new IllegalArgumentException("BATCH_NOT_FOUND: " + batchId));
        if (batch.state() != PayrollTaxExtModel.BatchState.APPROVED) {
            throw new IllegalStateException("ILLEGAL_STATE_TRANSITION: Batch is in state " + batch.state() + ", expected APPROVED");
        }
        List<PayrollTaxExtJdbcStore.BonusItemRecord> items = store.findBonusItems(tenantId, batchId);
        for (var item : items) {
            if (item.calculationMode() == BonusCalculationEngine.BonusMode.SEPARATE_MONTHLY_QUOTIENT) {
                store.recordSeparateBonusElection(
                        tenantId,
                        batch.withholdingEntityId(),
                        item.employeeId(),
                        batch.taxYear(),
                        batchId
                );
            }
        }
        store.updateBonusBatchState(
                tenantId,
                batchId,
                PayrollTaxExtModel.BatchState.LOCKED,
                null,
                null,
                Instant.now(),
                expectedVersion
        );
    }

    public void lockBonusBatch(
            UUID tenantId,
            UUID batchId,
            UUID withholdingEntityId,
            int taxYear,
            List<BonusCalculationEngine.BonusCalculationResult> items,
            long expectedVersion
    ) {
        lockBonusBatch(tenantId, batchId, expectedVersion);
    }

    public BonusBatchAggregate getBonusBatch(UUID tenantId, UUID batchId) {
        PayrollTaxExtJdbcStore.BonusBatchRecord batch = store.findBonusBatch(tenantId, batchId)
                .orElseThrow(() -> new IllegalArgumentException("BATCH_NOT_FOUND: " + batchId));
        List<PayrollTaxExtJdbcStore.BonusItemRecord> itemRecords = store.findBonusItems(tenantId, batchId);
        List<BonusCalculationEngine.BonusCalculationResult> results = new ArrayList<>();
        for (var it : itemRecords) {
            results.add(new BonusCalculationEngine.BonusCalculationResult(
                    it.employeeId(),
                    it.bonusAmount(),
                    it.calculationMode(),
                    it.monthlyQuotient(),
                    it.applicableRate(),
                    it.quickDeduction(),
                    it.withholdingTax(),
                    it.netAmount(),
                    it.comparisonRecommendedMode(),
                    it.comparisonTaxSavings(),
                    "Stored item calculation",
                    it.explainTree()
            ));
        }
        return new BonusBatchAggregate(
                batch.id(),
                batch.withholdingEntityId(),
                batch.taxYear(),
                batch.bonusName(),
                batch.payDate(),
                batch.state(),
                batch.preparerId(),
                batch.reviewerId(),
                batch.approverId(),
                batch.lockedAt(),
                batch.version(),
                results
        );
    }

    // --- PAY04: Labor Service Batch Lifecycle ---

    public record LaborBatchAggregate(
            UUID id,
            UUID withholdingEntityId,
            String period,
            String batchName,
            LocalDate payDate,
            PayrollTaxExtModel.BatchState state,
            UUID preparerId,
            UUID reviewerId,
            UUID approverId,
            Instant lockedAt,
            long version,
            List<LaborRemunerationEngine.LaborResult> items
    ) {}

    public LaborBatchAggregate createAndCalculateLaborBatch(
            UUID tenantId,
            UUID preparerId,
            PayrollTaxExtModel.CreateLaborBatchCommand command
    ) {
        if (tenantId == null || preparerId == null) throw new IllegalArgumentException("TENANT_AND_PREPARER_REQUIRED");
        if (command.entries() == null || command.entries().isEmpty())
            throw new IllegalArgumentException("ENTRIES_REQUIRED");

        UUID batchId = UUID.randomUUID();
        store.insertLaborBatch(
                tenantId,
                batchId,
                command.withholdingEntityId(),
                command.period(),
                command.batchName(),
                command.payDate(),
                PayrollTaxExtModel.BatchState.CALCULATED,
                preparerId
        );

        List<LaborRemunerationEngine.LaborResult> results = new ArrayList<>();
        for (PayrollTaxExtModel.LaborItemEntry entry : command.entries()) {
            LaborRemunerationEngine.LaborInput input = new LaborRemunerationEngine.LaborInput(
                    entry.naturalPersonId(),
                    entry.type(),
                    new BigDecimal(entry.grossPayment()),
                    entry.relief1Pct(),
                    entry.cityRate() == null ? null : new BigDecimal(entry.cityRate()),
                    entry.sixTaxesHalf(),
                    entry.educationExempt(),
                    entry.proxyInvoiceCompleted(),
                    entry.proxyInvoiceRef()
            );

            LaborRemunerationEngine.LaborResult calc = LaborRemunerationEngine.calculate(input);
            UUID itemId = UUID.randomUUID();
            store.insertLaborItem(tenantId, itemId, batchId, entry.naturalPersonId(), calc);
            results.add(calc);
        }

        return new LaborBatchAggregate(
                batchId,
                command.withholdingEntityId(),
                command.period(),
                command.batchName(),
                command.payDate(),
                PayrollTaxExtModel.BatchState.CALCULATED,
                preparerId,
                null,
                null,
                null,
                0L,
                results
        );
    }

    public void reviewLaborBatch(
            UUID tenantId,
            UUID batchId,
            UUID reviewerId,
            long expectedVersion
    ) {
        PayrollTaxExtJdbcStore.LaborBatchRecord batch = store.findLaborBatch(tenantId, batchId)
                .orElseThrow(() -> new IllegalArgumentException("BATCH_NOT_FOUND: " + batchId));
        if (batch.state() != PayrollTaxExtModel.BatchState.CALCULATED) {
            throw new IllegalStateException("ILLEGAL_STATE_TRANSITION: Batch is in state " + batch.state() + ", expected CALCULATED");
        }
        if (reviewerId.equals(batch.preparerId())) {
            throw new IllegalStateException("SELF_APPROVAL_FORBIDDEN: Reviewer cannot be the same natural person as the preparer");
        }
        store.updateLaborBatchState(
                tenantId,
                batchId,
                PayrollTaxExtModel.BatchState.REVIEWED,
                reviewerId,
                null,
                null,
                expectedVersion
        );
    }

    public void reviewLaborBatch(
            UUID tenantId,
            UUID batchId,
            UUID reviewerId,
            UUID preparerId,
            long expectedVersion
    ) {
        if (reviewerId.equals(preparerId)) {
            throw new IllegalStateException("SELF_APPROVAL_FORBIDDEN: Reviewer cannot be the same natural person as the preparer");
        }
        reviewLaborBatch(tenantId, batchId, reviewerId, expectedVersion);
    }

    public void approveLaborBatch(
            UUID tenantId,
            UUID batchId,
            UUID approverId,
            long expectedVersion
    ) {
        PayrollTaxExtJdbcStore.LaborBatchRecord batch = store.findLaborBatch(tenantId, batchId)
                .orElseThrow(() -> new IllegalArgumentException("BATCH_NOT_FOUND: " + batchId));
        if (batch.state() != PayrollTaxExtModel.BatchState.REVIEWED) {
            throw new IllegalStateException("ILLEGAL_STATE_TRANSITION: Batch is in state " + batch.state() + ", expected REVIEWED");
        }
        if (approverId.equals(batch.preparerId())) {
            throw new IllegalStateException("SELF_APPROVAL_FORBIDDEN: Approver cannot be the same natural person as the preparer");
        }
        if (batch.reviewerId() != null && approverId.equals(batch.reviewerId())) {
            throw new IllegalStateException("SELF_APPROVAL_FORBIDDEN: Approver cannot be the same natural person as the reviewer");
        }
        store.updateLaborBatchState(
                tenantId,
                batchId,
                PayrollTaxExtModel.BatchState.APPROVED,
                null,
                approverId,
                null,
                expectedVersion
        );
    }

    public void approveLaborBatch(
            UUID tenantId,
            UUID batchId,
            UUID approverId,
            UUID preparerId,
            UUID reviewerId,
            long expectedVersion
    ) {
        if (approverId.equals(preparerId)) {
            throw new IllegalStateException("SELF_APPROVAL_FORBIDDEN: Approver cannot be the same natural person as the preparer");
        }
        if (reviewerId != null && approverId.equals(reviewerId)) {
            throw new IllegalStateException("SELF_APPROVAL_FORBIDDEN: Approver cannot be the same natural person as the reviewer");
        }
        approveLaborBatch(tenantId, batchId, approverId, expectedVersion);
    }

    public void lockLaborBatch(
            UUID tenantId,
            UUID batchId,
            long expectedVersion
    ) {
        PayrollTaxExtJdbcStore.LaborBatchRecord batch = store.findLaborBatch(tenantId, batchId)
                .orElseThrow(() -> new IllegalArgumentException("BATCH_NOT_FOUND: " + batchId));
        if (batch.state() != PayrollTaxExtModel.BatchState.APPROVED) {
            throw new IllegalStateException("ILLEGAL_STATE_TRANSITION: Batch is in state " + batch.state() + ", expected APPROVED");
        }
        store.updateLaborBatchState(
                tenantId,
                batchId,
                PayrollTaxExtModel.BatchState.LOCKED,
                null,
                null,
                Instant.now(),
                expectedVersion
        );
    }

    public LaborBatchAggregate getLaborBatch(UUID tenantId, UUID batchId) {
        PayrollTaxExtJdbcStore.LaborBatchRecord batch = store.findLaborBatch(tenantId, batchId)
                .orElseThrow(() -> new IllegalArgumentException("BATCH_NOT_FOUND: " + batchId));
        List<PayrollTaxExtJdbcStore.LaborItemRecord> itemRecords = store.findLaborItems(tenantId, batchId);
        List<LaborRemunerationEngine.LaborResult> results = new ArrayList<>();
        for (var it : itemRecords) {
            results.add(new LaborRemunerationEngine.LaborResult(
                    it.naturalPersonId(),
                    it.serviceCategory(),
                    it.grossPayment(),
                    it.deductionAmount(),
                    it.taxableIncome(),
                    it.iitRate(),
                    it.iitQuickDeduction(),
                    it.iitWithholding(),
                    it.vatSales(),
                    it.vatExempt(),
                    it.vatRate(),
                    it.vatReduction(),
                    it.vatWithheld(),
                    it.citySurtax(),
                    it.educationSurcharge(),
                    it.localEducationSurcharge(),
                    it.totalSurchargeWithheld(),
                    it.netPayable(),
                    it.proxyInvoiceCompleted(),
                    it.proxyInvoiceRef(),
                    it.explainTree()
            ));
        }
        return new LaborBatchAggregate(
                batch.id(),
                batch.withholdingEntityId(),
                batch.period(),
                batch.batchName(),
                batch.payDate(),
                batch.state(),
                batch.preparerId(),
                batch.reviewerId(),
                batch.approverId(),
                batch.lockedAt(),
                batch.version(),
                results
        );
    }

    // --- TAX01: Policy Pack Evaluation ---

    @Transactional
    public TaxPolicyRuleEngine.PolicyPack createPolicyPack(
            UUID tenantId,
            PayrollTaxExtModel.CreatePolicyPackCommand command
    ) {
        String id = command.id() != null && !command.id().isBlank()
                ? command.id().trim()
                : "PACK_" + UUID.randomUUID().toString().replace("-", "");
        String taxType = command.taxType() != null && !command.taxType().isBlank()
                ? command.taxType().trim()
                : "CIT_SMALL_LOW_PROFIT";
        String version = command.version() != null && !command.version().isBlank()
                ? command.version().trim()
                : "1.0.0";
        LocalDate effectiveFrom = command.effectiveFrom() != null
                ? command.effectiveFrom()
                : LocalDate.of(2020, 1, 1);
        LocalDate effectiveTo = command.effectiveTo() != null
                ? command.effectiveTo()
                : LocalDate.of(2099, 12, 31);
        String approvalStatus = command.approvalStatus() != null && !command.approvalStatus().isBlank()
                ? command.approvalStatus().trim()
                : "APPROVED";
        boolean attestationRequired = Boolean.TRUE.equals(command.attestationRequired());
        Map<String, Object> rules = command.rules() != null ? command.rules() : Map.of();

        String digest = command.digest();
        if (digest == null || !digest.matches("^[a-f0-9]{64}$")) {
            digest = computeSha256(id + ":" + taxType + ":" + version + ":" + rules);
        }

        TaxPolicyRuleEngine.PolicyPack pack = new TaxPolicyRuleEngine.PolicyPack(
                id,
                taxType,
                version,
                effectiveFrom,
                effectiveTo,
                digest,
                approvalStatus,
                attestationRequired,
                rules
        );
        store.insertPolicyPack(tenantId, pack);
        return pack;
    }

    public Optional<TaxPolicyRuleEngine.PolicyPack> findPolicyPack(UUID tenantId, String id) {
        return store.findPolicyPack(tenantId, id);
    }

    private static String computeSha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public TaxPolicyRuleEngine.CalculationResult evaluateCitPolicy(
            UUID tenantId,
            PayrollTaxExtModel.EvaluateCitCommand command
    ) {
        TaxPolicyRuleEngine.PolicyPack pack = store.findPolicyPack(tenantId, command.policyPackId())
                .orElseThrow(() -> new IllegalArgumentException("POLICY_PACK_NOT_FOUND: " + command.policyPackId()));

        TaxPolicyRuleEngine.Attestation attestation = null;
        if (command.attestationId() != null) {
            attestation = store.findAttestation(tenantId, command.attestationId())
                    .orElseThrow(() -> new IllegalArgumentException("ATTESTATION_NOT_FOUND: " + command.attestationId()));
        }

        return TaxPolicyRuleEngine.evaluateSmallLowProfitCit(
                pack,
                attestation,
                command.taxEntityId(),
                command.periodDate(),
                new BigDecimal(command.annualTaxableProfit()),
                new BigDecimal(command.averageStaffCount()),
                new BigDecimal(command.totalAssets())
        );
    }

    // --- TAX05: Filing Calendar, Form Lock, Mandatory Schedule Check ---

    public TaxFilingCalendarGenerator.FilingDeadline generateFilingCalendar(
            UUID tenantId,
            PayrollTaxExtModel.GenerateCalendarCommand command,
            Map<LocalDate, String> customHolidayOverrides
    ) {
        Map<LocalDate, String> holidayOverrides = new java.util.HashMap<>();
        if (customHolidayOverrides != null) {
            holidayOverrides.putAll(customHolidayOverrides);
        }
        if (tenantId != null && command.calendarPackVersion() != null) {
            store.findCalendarPack(tenantId, command.calendarPackVersion()).ifPresent(pack -> {
                for (var entry : pack.scheduleEntries().entrySet()) {
                    try {
                        holidayOverrides.put(LocalDate.parse(entry.getKey()), entry.getValue());
                    } catch (Exception ignored) {}
                }
            });
        }
        return TaxFilingCalendarGenerator.generate(
                command.taxType(),
                command.year(),
                command.month(),
                holidayOverrides,
                command.calendarPackVersion()
        );
    }

    public TaxFilingCalendarGenerator.FilingDeadline generateFilingCalendar(
            PayrollTaxExtModel.GenerateCalendarCommand command,
            Map<LocalDate, String> holidayOverrides
    ) {
        return generateFilingCalendar(null, command, holidayOverrides);
    }

    public MandatoryScheduleChecker.ValidationResult validateSchedules(
            PayrollTaxExtModel.CheckScheduleCommand command
    ) {
        Map<String, BigDecimal> numericFigures = command.figures() == null ? Map.of() :
                command.figures().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                e -> new BigDecimal(e.getValue())
                        ));
        return MandatoryScheduleChecker.validateAttachments(
                command.formType(),
                numericFigures,
                command.attachedScheduleCodes()
        );
    }

    public UUID lockFormVersion(
            UUID tenantId,
            UUID lockingPersonId,
            PayrollTaxExtModel.LockFormCommand command
    ) {
        // First check mandatory attachments! If missing, block lock!
        Map<String, BigDecimal> numericFigures = command.cellValues() == null ? Map.of() :
                command.cellValues().entrySet().stream()
                        .filter(e -> e.getValue().matches("^-?[0-9]+(?:\\.[0-9]+)?$"))
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                e -> new BigDecimal(e.getValue())
                        ));

        MandatoryScheduleChecker.ValidationResult validation = MandatoryScheduleChecker.validateAttachments(
                command.formCode(),
                numericFigures,
                command.mandatoryAttachments()
        );
        validation.assertAllPresent();

        UUID formId = UUID.randomUUID();
        store.insertFormLock(
                tenantId,
                formId,
                command.taxObligationId(),
                command.formCode(),
                command.versionNumber(),
                command.contentDigest(),
                command.cellValues(),
                command.mandatoryAttachments()
        );

        store.lockForm(tenantId, formId, lockingPersonId, command.contentDigest());
        return formId;
    }
}

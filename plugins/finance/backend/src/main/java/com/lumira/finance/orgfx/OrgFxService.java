package com.lumira.finance.orgfx;

import com.lumira.finance.orgfx.OrgFxModels.*;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Domain service coordinator for Lane W01 (org-fx).
 * Coordinates business rules, validation, SoD enforcement, and persistence.
 */
@Service
public final class OrgFxService {
    private final OrgFxJdbcStore store;

    public OrgFxService(OrgFxJdbcStore store) {
        this.store = store;
    }

    // --- ORG01: Branches & Entity Lifecycle ---

    public Branch createBranch(
            UUID tenantId,
            UUID legalEntityId,
            String branchCode,
            String branchName,
            String branchType,
            String taxFilingMode,
            String taxBureauCode,
            String taxBureauName,
            String address
    ) {
        Branch branch = new Branch(
                UUID.randomUUID(),
                tenantId,
                legalEntityId,
                branchCode,
                branchName,
                branchType,
                taxFilingMode,
                taxBureauCode,
                taxBureauName,
                address,
                "ACTIVE",
                1L,
                OffsetDateTime.now()
        );
        store.inTransaction(tenantId, "branch:" + legalEntityId, s -> {
            s.insertBranch(branch);
            return null;
        });
        return branch;
    }

    public List<Branch> listBranches(UUID tenantId, UUID legalEntityId) {
        return store.listBranches(tenantId, legalEntityId);
    }

    public EntityLifecycleRecord transitionEntityLifecycle(
            UUID tenantId,
            UUID legalEntityId,
            EntityStatus targetStatus,
            String reason,
            Actor maker,
            Actor reviewer
    ) {
        return store.inTransaction(tenantId, "lifecycle:" + legalEntityId, s -> {
            Optional<EntityLifecycleRecord> existing = s.findLifecycleRecord(tenantId, legalEntityId);
            EntityStatus current = existing.map(EntityLifecycleRecord::currentStatus).orElse(EntityStatus.PREPARING);
            long nextVersion = existing.map(e -> e.version() + 1).orElse(1L);

            OrgFxRules.validateLifecycleTransition(current, targetStatus, maker, reviewer);

            EntityLifecycleRecord updated = new EntityLifecycleRecord(
                    tenantId,
                    legalEntityId,
                    targetStatus,
                    nextVersion,
                    OffsetDateTime.now()
            );
            s.upsertLifecycleRecord(updated);

            EntityLifecycleEvent event = new EntityLifecycleEvent(
                    UUID.randomUUID(),
                    tenantId,
                    legalEntityId,
                    current,
                    targetStatus,
                    reason,
                    maker,
                    reviewer,
                    OffsetDateTime.now()
            );
            s.insertLifecycleEvent(event);
            return updated;
        });
    }


    public Optional<EntityLifecycleRecord> getLifecycle(UUID tenantId, UUID legalEntityId) {
        return store.findLifecycleRecord(tenantId, legalEntityId);
    }

    public List<TaxBureau> listTaxBureaus() {
        return store.listTaxBureaus();
    }

    // --- ORG02: Standards Eligibility, Policies & Conversion Bridge ---

    public StandardEligibilityAssessment assessStandardEligibility(
            UUID tenantId,
            UUID legalEntityId,
            boolean isPubliclyTraded,
            boolean isFinancialInstitution,
            boolean isGroupSubsidiaryOfPublic,
            int employeeCount,
            BigDecimal annualRevenue,
            BigDecimal totalAssets,
            Actor assessor,
            String notes
    ) {
        StandardEligibilityAssessment assessment = OrgFxRules.assessStandardEligibility(
                UUID.randomUUID(),
                tenantId,
                legalEntityId,
                isPubliclyTraded,
                isFinancialInstitution,
                isGroupSubsidiaryOfPublic,
                employeeCount,
                annualRevenue,
                totalAssets,
                assessor,
                notes
        );
        store.inTransaction(tenantId, "standard:" + legalEntityId, s -> {
            s.insertStandardEligibility(assessment);
            return null;
        });
        return assessment;
    }

    public PolicyVersion createPolicyVersion(
            UUID tenantId,
            UUID bookId,
            int policyVersionNo,
            AccountingStandard standard,
            String depreciationMethod,
            String inventoryCostingMethod,
            boolean impairmentAllowed,
            boolean badDebtProvisionAllowed,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Actor maker
    ) {
        if (maker == null || maker.personId() == null) {
            throw new IllegalArgumentException("MAKER_REQUIRED: Valid maker natural person required.");
        }
        if (standard == AccountingStandard.SMALL && impairmentAllowed) {
            throw new IllegalArgumentException("SMALL_STANDARD_IMPAIRMENT_PROHIBITED: Small enterprise standard does not permit asset impairment.");
        }
        int versionNo = policyVersionNo > 0 ? policyVersionNo : (store.findLatestPolicyVersionNo(tenantId, bookId) + 1);

        PolicyVersion draft = new PolicyVersion(
                UUID.randomUUID(),
                tenantId,
                bookId,
                versionNo,
                standard,
                depreciationMethod,
                inventoryCostingMethod,
                impairmentAllowed,
                badDebtProvisionAllowed,
                effectiveFrom,
                effectiveTo,
                PolicyStatus.DRAFT,
                maker,
                null,
                null,
                1L,
                OffsetDateTime.now()
        );
        store.inTransaction(tenantId, "policy:" + bookId, s -> {
            s.insertPolicyVersion(draft);
            return null;
        });
        return draft;
    }

    public PolicyVersion approvePolicyVersion(
            UUID tenantId,
            UUID bookId,
            UUID policyVersionId,
            Actor reviewer,
            String approvalDigest
    ) {
        return store.inTransaction(tenantId, "policy:" + bookId, s -> {
            PolicyVersion draft = s.findPolicyVersion(tenantId, bookId, policyVersionId)
                    .orElseThrow(() -> new NoSuchElementException("POLICY_NOT_FOUND: Policy version " + policyVersionId + " not found."));
            if (draft.status() != PolicyStatus.DRAFT) {
                throw new IllegalStateException("INVALID_STATUS: Cannot approve policy version in status " + draft.status());
            }
            OrgFxRules.verifySod(draft.maker(), reviewer);

            PolicyVersion approved = new PolicyVersion(
                    draft.id(),
                    draft.tenantId(),
                    draft.bookId(),
                    draft.policyVersionNo(),
                    draft.accountingStandard(),
                    draft.depreciationMethod(),
                    draft.inventoryCostingMethod(),
                    draft.impairmentAllowed(),
                    draft.badDebtProvisionAllowed(),
                    draft.effectiveFrom(),
                    draft.effectiveTo(),
                    PolicyStatus.APPROVED,
                    draft.maker(),
                    reviewer,
                    approvalDigest,
                    draft.version() + 1,
                    draft.createdAt()
            );
            s.updatePolicyVersionApproval(tenantId, bookId, policyVersionId, reviewer, approvalDigest);
            return approved;
        });
    }

    public PolicyVersion approvePolicyVersion(
            PolicyVersion draft,
            Actor reviewer,
            String approvalDigest
    ) {
        return approvePolicyVersion(draft.tenantId(), draft.bookId(), draft.id(), reviewer, approvalDigest);
    }

    public ConversionBridge createConversionBridge(
            UUID tenantId,
            UUID bookId,
            AccountingStandard fromStandard,
            AccountingStandard toStandard,
            int effectiveFiscalYear,
            boolean eligibleForSmall,
            Actor maker,
            Actor reviewer,
            String justification,
            List<ConversionBridgeItem> rawItems
    ) {
        ConversionBridge bridge = OrgFxRules.calculateConversionBridge(
                UUID.randomUUID(),
                tenantId,
                bookId,
                fromStandard,
                toStandard,
                effectiveFiscalYear,
                eligibleForSmall,
                maker,
                reviewer,
                justification,
                rawItems
        );
        store.inTransaction(tenantId, "bridge:" + bookId, s -> {
            s.insertConversionBridge(bridge);
            return null;
        });
        return bridge;
    }

    public Optional<ConversionBridge> findConversionBridge(UUID tenantId, UUID bookId, UUID id) {
        return store.findConversionBridge(tenantId, bookId, id);
    }

    public List<ConversionBridge> listConversionBridges(UUID tenantId, UUID bookId) {
        return store.listConversionBridges(tenantId, bookId);
    }

    // --- ORG03: Multi-Date Semantics & Period Reopen ---

    public MultiDateValidationResult validateMultiDates(MultiDateContext ctx) {
        return OrgFxRules.validateMultiDates(ctx);
    }

    public PeriodReopenRecord requestPeriodReopen(
            UUID tenantId,
            UUID bookId,
            UUID yearId,
            UUID periodId,
            int periodMonth,
            String reason,
            Actor requester
    ) {
        PeriodReopenRecord record = new PeriodReopenRecord(
                UUID.randomUUID(),
                tenantId,
                bookId,
                yearId,
                periodId,
                periodMonth,
                reason,
                ReopenStatus.REQUESTED,
                requester,
                null,
                null,
                1L,
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
        store.inTransaction(tenantId, "period:" + periodId, s -> {
            s.insertPeriodReopen(record);
            s.insertPeriodAuditTrail(new PeriodAuditTrailEntry(
                    UUID.randomUUID(),
                    tenantId,
                    bookId,
                    yearId,
                    periodId,
                    "REOPEN_REQUEST",
                    requester,
                    "{\"reason\":\"" + reason + "\"}",
                    OffsetDateTime.now()
            ));
            return null;
        });
        return record;
    }

    public PeriodReopenRecord approvePeriodReopen(
            UUID tenantId,
            UUID bookId,
            UUID periodId,
            Actor approver,
            String decisionReason
    ) {
        return store.inTransaction(tenantId, "period:" + periodId, s -> {
            PeriodReopenRecord pending = s.findPendingPeriodReopen(tenantId, bookId, periodId)
                    .orElseThrow(() -> new NoSuchElementException("NO_PENDING_REOPEN: No pending reopen request found for period " + periodId));

            OrgFxRules.verifySod(pending.requestedBy(), approver);

            s.updatePeriodReopenStatus(
                    tenantId,
                    bookId,
                    pending.id(),
                    ReopenStatus.APPROVED,
                    approver,
                    decisionReason
            );
            s.insertPeriodAuditTrail(new PeriodAuditTrailEntry(
                    UUID.randomUUID(),
                    tenantId,
                    bookId,
                    pending.yearId(),
                    periodId,
                    "REOPEN_APPROVE",
                    approver,
                    "{\"decisionReason\":\"" + decisionReason + "\",\"immutableVouchersPreserved\":true}",
                    OffsetDateTime.now()
            ));

            return new PeriodReopenRecord(
                    pending.id(),
                    tenantId,
                    bookId,
                    pending.yearId(),
                    periodId,
                    pending.periodMonth(),
                    pending.reopenReason(),
                    ReopenStatus.APPROVED,
                    pending.requestedBy(),
                    approver,
                    decisionReason,
                    pending.version() + 1,
                    pending.createdAt(),
                    OffsetDateTime.now()
            );
        });
    }

    public PeriodReopenRecord approvePeriodReopen(
            PeriodReopenRecord requested,
            Actor approver,
            String decisionReason
    ) {
        return approvePeriodReopen(requested.tenantId(), requested.bookId(), requested.periodId(), approver, decisionReason);
    }

    public PeriodReopenRecord rejectPeriodReopen(
            UUID tenantId,
            UUID bookId,
            UUID periodId,
            Actor reviewer,
            String decisionReason
    ) {
        return store.inTransaction(tenantId, "period:" + periodId, s -> {
            PeriodReopenRecord pending = s.findPendingPeriodReopen(tenantId, bookId, periodId)
                    .orElseThrow(() -> new NoSuchElementException("NO_PENDING_REOPEN: No pending reopen request found for period " + periodId));

            OrgFxRules.verifySod(pending.requestedBy(), reviewer);

            s.updatePeriodReopenStatus(
                    tenantId,
                    bookId,
                    pending.id(),
                    ReopenStatus.REJECTED,
                    reviewer,
                    decisionReason
            );
            s.insertPeriodAuditTrail(new PeriodAuditTrailEntry(
                    UUID.randomUUID(),
                    tenantId,
                    bookId,
                    pending.yearId(),
                    periodId,
                    "REOPEN_REJECT",
                    reviewer,
                    "{\"decisionReason\":\"" + decisionReason + "\"}",
                    OffsetDateTime.now()
            ));

            return new PeriodReopenRecord(
                    pending.id(),
                    tenantId,
                    bookId,
                    pending.yearId(),
                    periodId,
                    pending.periodMonth(),
                    pending.reopenReason(),
                    ReopenStatus.REJECTED,
                    pending.requestedBy(),
                    reviewer,
                    decisionReason,
                    pending.version() + 1,
                    pending.createdAt(),
                    OffsetDateTime.now()
            );
        });
    }

    public PeriodReopenRecord reclosePeriod(
            UUID tenantId,
            UUID bookId,
            UUID periodId,
            Actor actor,
            String reason
    ) {
        return store.inTransaction(tenantId, "period:" + periodId, s -> {
            PeriodReopenRecord latest = s.findLatestPeriodReopen(tenantId, bookId, periodId)
                    .orElseThrow(() -> new NoSuchElementException("NO_REOPEN_FOUND: No reopen record found for period " + periodId));

            if (latest.status() != ReopenStatus.APPROVED) {
                throw new IllegalStateException("CANNOT_RECLOSE: Period reopen is in status " + latest.status() + ", cannot reclose.");
            }

            s.updatePeriodReopenStatus(
                    tenantId,
                    bookId,
                    latest.id(),
                    ReopenStatus.CLOSED_AGAIN,
                    actor,
                    reason
            );
            s.insertPeriodAuditTrail(new PeriodAuditTrailEntry(
                    UUID.randomUUID(),
                    tenantId,
                    bookId,
                    latest.yearId(),
                    periodId,
                    "RECLOSE",
                    actor,
                    "{\"reason\":\"" + reason + "\"}",
                    OffsetDateTime.now()
            ));

            return new PeriodReopenRecord(
                    latest.id(),
                    tenantId,
                    bookId,
                    latest.yearId(),
                    periodId,
                    latest.periodMonth(),
                    latest.reopenReason(),
                    ReopenStatus.CLOSED_AGAIN,
                    latest.requestedBy(),
                    actor,
                    reason,
                    latest.version() + 1,
                    latest.createdAt(),
                    OffsetDateTime.now()
            );
        });
    }

    public Map<String, Object> handlePeriodReopen(
            UUID tenantId,
            UUID bookId,
            UUID periodId,
            String action,
            String reason,
            Actor actor,
            String decisionReason
    ) {
        if ("REQUEST".equalsIgnoreCase(action)) {
            PeriodReopenRecord rec = requestPeriodReopen(
                    tenantId,
                    bookId,
                    UUID.randomUUID(),
                    periodId,
                    1,
                    reason,
                    actor
            );
            return Map.of(
                    "id", rec.id().toString(),
                    "periodId", periodId.toString(),
                    "status", rec.status().name(),
                    "version", rec.version(),
                    "immutableVouchersPreserved", true
            );
        } else if ("APPROVE".equalsIgnoreCase(action)) {
            PeriodReopenRecord approved = approvePeriodReopen(tenantId, bookId, periodId, actor, decisionReason);
            return Map.of(
                    "id", approved.id().toString(),
                    "periodId", periodId.toString(),
                    "status", approved.status().name(),
                    "version", approved.version(),
                    "immutableVouchersPreserved", true
            );
        } else if ("REJECT".equalsIgnoreCase(action)) {
            PeriodReopenRecord rejected = rejectPeriodReopen(tenantId, bookId, periodId, actor, decisionReason);
            return Map.of(
                    "id", rejected.id().toString(),
                    "periodId", periodId.toString(),
                    "status", rejected.status().name(),
                    "version", rejected.version(),
                    "immutableVouchersPreserved", true
            );
        } else if ("RECLOSE".equalsIgnoreCase(action)) {
            PeriodReopenRecord reclosed = reclosePeriod(tenantId, bookId, periodId, actor, reason);
            return Map.of(
                    "id", reclosed.id().toString(),
                    "periodId", periodId.toString(),
                    "status", reclosed.status().name(),
                    "version", reclosed.version(),
                    "immutableVouchersPreserved", true
            );
        } else {
            throw new IllegalArgumentException("UNSUPPORTED_ACTION: " + action);
        }
    }

    // --- ORG04: Foreign Currency, FX Rate Registry, Revaluation ---

    public FxRateSnapshot registerFxRateSnapshot(
            UUID tenantId,
            String sourceCurrency,
            LocalDate rateDate,
            FxRateType rateType,
            BigDecimal rateValue,
            String quoteSource,
            Actor maker,
            Actor reviewer
    ) {
        if (rateType == FxRateType.CUSTOM_APPROVED) {
            OrgFxRules.verifySod(maker, reviewer);
        }
        FxRateSnapshot snapshot = new FxRateSnapshot(
                UUID.randomUUID(),
                tenantId,
                sourceCurrency,
                "CNY",
                rateDate,
                rateType,
                rateValue,
                quoteSource,
                maker,
                reviewer,
                true,
                OffsetDateTime.now()
        );
        store.inTransaction(tenantId, "fx:" + sourceCurrency, s -> {
            s.insertFxRateSnapshot(snapshot);
            return null;
        });
        return snapshot;
    }

    public FxLineTranslationResult translateLine(
            String originalCurrency,
            BigDecimal originalAmount,
            BigDecimal fxRate
    ) {
        return OrgFxRules.translateFxLine(originalCurrency, originalAmount, fxRate);
    }

    public FxRevaluationPlan createRevaluationPlan(
            UUID tenantId,
            UUID bookId,
            UUID yearId,
            UUID periodId,
            LocalDate revaluationDate,
            UUID closingRateSnapshotId,
            Actor maker,
            List<FxRevaluationItem> rawItems
    ) {
        UUID planId = UUID.randomUUID();
        BigDecimal totalGainLoss = BigDecimal.ZERO;
        List<FxRevaluationItem> calculated = new ArrayList<>();

        for (FxRevaluationItem raw : rawItems) {
            FxRevaluationItem item = OrgFxRules.calculateRevaluationItem(
                    UUID.randomUUID(),
                    planId,
                    raw.accountCode(),
                    raw.accountName(),
                    raw.currencyCode(),
                    raw.foreignBalance(),
                    raw.carryingCnyBalance(),
                    raw.closingRate(),
                    raw.exchangeGainLossAccount()
            );
            totalGainLoss = totalGainLoss.add(item.gainLossAmount());
            calculated.add(item);
        }

        FxRevaluationPlan plan = new FxRevaluationPlan(
                planId,
                tenantId,
                bookId,
                yearId,
                periodId,
                revaluationDate,
                closingRateSnapshotId,
                "DRAFT",
                totalGainLoss,
                maker,
                null,
                null,
                List.copyOf(calculated),
                1L,
                OffsetDateTime.now()
        );

        store.inTransaction(tenantId, "reval:" + periodId, s -> {
            s.insertFxRevaluationPlan(plan);
            return null;
        });
        return plan;
    }

    public FxTaxWorksheetItem recordTaxWorksheet(
            UUID tenantId,
            UUID bookId,
            UUID periodId,
            String transactionRef,
            String originalCurrency,
            BigDecimal originalAmount,
            BigDecimal accountingFxRate,
            String taxFxRateType,
            BigDecimal taxFxRate,
            String explanation
    ) {
        FxTaxWorksheetItem item = OrgFxRules.calculateTaxFxDifference(
                UUID.randomUUID(),
                tenantId,
                bookId,
                periodId,
                transactionRef,
                originalCurrency,
                originalAmount,
                accountingFxRate,
                taxFxRateType,
                taxFxRate,
                explanation
        );
        store.inTransaction(tenantId, "taxws:" + periodId, s -> {
            s.insertTaxWorksheetItem(item);
            return null;
        });
        return item;
    }

    // --- ORG05: Regional Tax Calendar & Catalog Rules ---

    public List<ResolvedTaxObligation> resolveTaxObligations(
            String regionCode,
            int taxYear,
            int periodMonth,
            String taxpayerType,
            String citMode,
            boolean isSmallMicro
    ) {
        List<TaxCalendarTemplate> calendars = store.listTaxCalendarTemplates(regionCode, taxYear);
        List<TaxCatalogRule> rules = store.listTaxCatalogRules();
        return OrgFxRules.resolveTaxObligations(
                rules,
                calendars,
                taxpayerType,
                citMode,
                isSmallMicro,
                taxYear,
                periodMonth
        );
    }

    public List<FxCurrency> listCurrencies() {
        return store.listCurrencies();
    }
}

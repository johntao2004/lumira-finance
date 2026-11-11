package com.lumira.finance.yearend;

import static com.lumira.finance.yearend.YearendModel.*;
import static com.lumira.finance.yearend.YearendRules.*;

import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Transactional orchestrator and domain service for Lane W09.
 */
@Service
public class YearendService {
    private final DataSource dataSource;
    private final YearendStore store;

    public YearendService(DataSource dataSource, YearendStore store) {
        this.dataSource = dataSource;
        this.store = store;
    }

    // --- GL01 Cutover Service ---
    public CutoverProposal proposeCutover(
            UUID tenantId, UUID bookId, UUID yearId, LocalDate cutoverDate,
            String mode, String sourceSystem, String sourceWatermark,
            List<OpeningBalanceItem> balances, List<BaselineItem> baselines,
            Map<String, BigDecimal> subledgerTotals, PersonActor maker) throws SQLException {

        validateCutoverMode(mode);
        if (maker == null) throw new IllegalArgumentException("MAKER_REQUIRED");

        // Mutual exclusivity checks
        if ("FULL_HISTORY".equals(mode)) {
            // Cannot provide duplicate monthly baselines in FULL_HISTORY mode
            if (baselines != null && !baselines.isEmpty()) {
                throw new IllegalArgumentException("FULL_HISTORY_MODE_CANNOT_CONTAIN_MONTHLY_BASELINES");
            }
        } else if ("OPENING_PLUS_YTD".equals(mode)) {
            if (baselines == null || baselines.isEmpty()) {
                throw new IllegalArgumentException("OPENING_PLUS_YTD_MODE_REQUIRES_YTD_BASELINES");
            }
        }

        List<TieOutResult> tieOuts = evaluateCutoverTieOut(balances, baselines == null ? List.of() : baselines, subledgerTotals);
        boolean passed = areAllTieOutsPassed(tieOuts);

        String digestPayload = tenantId + ":" + bookId + ":" + yearId + ":" + cutoverDate + ":" + mode + ":" + balances.size();
        String contentDigest = sha256Hex(digestPayload);

        UUID planId = UUID.randomUUID();
        CutoverPlan plan = new CutoverPlan(
                planId, tenantId, bookId, yearId, cutoverDate, mode,
                passed ? "VALIDATED" : "DRAFT",
                1L, contentDigest, sourceSystem, sourceWatermark,
                maker, null, null, Instant.now(), Instant.now()
        );

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                store.insertCutoverPlan(c, plan);
                store.insertOpeningBalances(c, tenantId, bookId, planId, balances);
                if (baselines != null && !baselines.isEmpty()) {
                    store.insertBaselines(c, tenantId, bookId, planId, baselines);
                }
                store.insertTieOuts(c, tenantId, bookId, planId, tieOuts);
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }

        return new CutoverProposal(plan, balances, baselines, tieOuts);
    }

    public void approveCutover(UUID tenantId, UUID bookId, UUID planId, PersonActor approver) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                CutoverPlan plan = store.findCutoverPlan(c, tenantId, bookId, planId)
                        .orElseThrow(() -> new IllegalArgumentException("CUTOVER_PLAN_NOT_FOUND"));

                validateActorSod(plan.maker(), approver);
                if (!"VALIDATED".equals(plan.status())) {
                    throw new IllegalStateException("CANNOT_APPROVE_IN_STATUS: " + plan.status() + " (only VALIDATED plans can be approved)");
                }
                if (store.hasUnpassedTieOuts(c, tenantId, bookId, planId)) {
                    throw new IllegalStateException("CANNOT_APPROVE_CUTOVER_TIE_OUT_FAILED");
                }

                store.updateCutoverStatus(c, tenantId, bookId, planId, "APPROVED", approver, null);
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    public void lockCutover(UUID tenantId, UUID bookId, UUID planId, PersonActor locker) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                CutoverPlan plan = store.findCutoverPlan(c, tenantId, bookId, planId)
                        .orElseThrow(() -> new IllegalArgumentException("CUTOVER_PLAN_NOT_FOUND"));

                if (!"APPROVED".equals(plan.status())) {
                    throw new IllegalStateException("CUTOVER_PLAN_MUST_BE_APPROVED_BEFORE_LOCK");
                }
                if (locker != null) {
                    validateActorSod(plan.maker(), locker);
                }
                if (store.hasUnpassedTieOuts(c, tenantId, bookId, planId)) {
                    throw new IllegalStateException("CANNOT_LOCK_CUTOVER_TIE_OUT_FAILED");
                }

                store.updateCutoverStatus(c, tenantId, bookId, planId, "LOCKED", plan.approver(), Instant.now());
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    // --- GL02 & GL03 Auto Rules & Review ---
    public AutoRuleTemplate createRuleTemplate(
            UUID tenantId, UUID bookId, String ruleCode, String ruleName, String businessType,
            List<LineRule> lineTemplates, PersonActor maker) throws SQLException {

        validateActorSod(maker, null);
        String digest = sha256Hex(ruleCode + ":" + businessType + ":" + lineTemplates.size());
        AutoRuleTemplate tmpl = new AutoRuleTemplate(
                UUID.randomUUID(), tenantId, bookId, ruleCode, ruleName, businessType,
                1, "DRAFT", digest, lineTemplates, maker, null, null, Instant.now()
        );

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                store.insertRuleTemplate(c, tmpl);
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
        return tmpl;
    }

    public AutoReviewPolicy createReviewPolicy(
            UUID tenantId, UUID bookId, String policyCode, String policyName,
            List<String> scopeBusinessTypes, List<String> allowedAccountCodes,
            BigDecimal maxAmount, boolean zeroExceptionRequired, PersonActor maker) throws SQLException {

        validateActorSod(maker, null);
        if (maxAmount == null || maxAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("MAX_AMOUNT_MUST_BE_POSITIVE");
        }
        String digest = sha256Hex(policyCode + ":" + maxAmount.toPlainString() + ":" + zeroExceptionRequired);
        AutoReviewPolicy policy = new AutoReviewPolicy(
                UUID.randomUUID(), tenantId, bookId, policyCode, policyName,
                1, "DRAFT", digest, scopeBusinessTypes, allowedAccountCodes,
                maxAmount, zeroExceptionRequired, maker, null, null, Instant.now()
        );

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                store.insertReviewPolicy(c, policy);
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
        return policy;
    }

    public void approveRuleTemplate(UUID tenantId, UUID bookId, UUID templateId, PersonActor approver) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                AutoRuleTemplate tmpl = store.findRuleTemplate(c, tenantId, bookId, templateId)
                        .orElseThrow(() -> new IllegalArgumentException("RULE_TEMPLATE_NOT_FOUND"));
                validateActorSod(tmpl.maker(), approver);
                if (!"DRAFT".equals(tmpl.status())) {
                    throw new IllegalStateException("CANNOT_APPROVE_RULE_TEMPLATE_IN_STATUS: " + tmpl.status());
                }
                store.updateRuleTemplateStatus(c, tenantId, bookId, templateId, "APPROVED", approver);
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    public void approveReviewPolicy(UUID tenantId, UUID bookId, UUID policyId, PersonActor approver) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                AutoReviewPolicy policy = store.findReviewPolicy(c, tenantId, bookId, policyId)
                        .orElseThrow(() -> new IllegalArgumentException("REVIEW_POLICY_NOT_FOUND"));
                validateActorSod(policy.maker(), approver);
                if (!"DRAFT".equals(policy.status())) {
                    throw new IllegalStateException("CANNOT_APPROVE_REVIEW_POLICY_IN_STATUS: " + policy.status());
                }
                store.updateReviewPolicyStatus(c, tenantId, bookId, policyId, "APPROVED", approver);
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    public AutoRuleExecution executeAutoDraftAndReview(
            UUID yearId, AutoRuleTemplate template, AutoReviewPolicy policy, UUID journalId,
            String sourceEventId, String sourceSystem, BigDecimal transactionAmount,
            String businessType, List<String> affectedAccounts, PersonActor transactionMaker) throws SQLException {

        AutoRuleExecution exec = evaluateAutoRuleAndReview(
                template, policy, yearId, journalId, sourceEventId, sourceSystem,
                transactionAmount, businessType, affectedAccounts, transactionMaker
        );

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                store.insertRuleExecution(c, exec);
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
        return exec;
    }

    // --- GL05 Journal Corrections ---
    public JournalCorrectionRecord createJournalCorrection(
            UUID tenantId, UUID bookId, UUID targetYearId, UUID sourceYearId,
            UUID sourceJournalId, long sourcePostingSeq, String sourceDigest,
            UUID correctionJournalId, String correctionKind, String standard,
            String casAccountCode, String reason, List<CorrectionAllocation> allocations,
            Map<Integer, BigDecimal> lineWatermarks, PersonActor maker) throws SQLException {

        validateActorSod(maker, null);
        validateCorrectionRequest(standard, correctionKind, casAccountCode, allocations, lineWatermarks);

        JournalCorrectionRecord rec = new JournalCorrectionRecord(
                UUID.randomUUID(), tenantId, bookId, targetYearId, sourceYearId,
                sourceJournalId, sourcePostingSeq, sourceDigest, correctionJournalId,
                correctionKind, standard, casAccountCode, "DRAFT", reason,
                allocations, maker, null, Instant.now()
        );

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                store.insertCorrectionRecord(c, rec);
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
        return rec;
    }

    // --- GL06 Period Close Checklist ---
    public PeriodCloseCheckPlan previewPeriodClose(
            UUID tenantId, UUID bookId, UUID yearId, UUID periodId,
            int unpostedJournalsCount, BigDecimal trialDebit, BigDecimal trialCredit,
            boolean bankReconciliationComplete, Map<String, BigDecimal> glBalances,
            Map<String, BigDecimal> subledgerBalances, boolean cashflowCoverageComplete,
            boolean taxWorksheetReady, PersonActor maker) throws SQLException {

        validateActorSod(maker, null);
        PeriodCloseCheckPlan plan = evaluatePeriodClose(
                tenantId, bookId, yearId, periodId, unpostedJournalsCount,
                trialDebit, trialCredit, bankReconciliationComplete,
                glBalances, subledgerBalances, cashflowCoverageComplete,
                taxWorksheetReady, maker
        );

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                store.insertPeriodClosePlan(c, plan);
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
        return plan;
    }

    public void executePeriodClose(UUID tenantId, UUID bookId, UUID planId, PersonActor approver) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                PeriodCloseCheckPlan plan = store.findPeriodClosePlan(c, tenantId, bookId, planId)
                        .orElseThrow(() -> new IllegalArgumentException("PERIOD_CLOSE_PLAN_NOT_FOUND"));

                validateActorSod(plan.maker(), approver);

                if (!"PREVIEWED".equals(plan.status()) && !"APPROVED".equals(plan.status())) {
                    throw new IllegalStateException("CANNOT_CLOSE_PERIOD_IN_STATUS: " + plan.status());
                }

                if (!plan.allChecksPassed()) {
                    throw new IllegalStateException("PERIOD_CLOSE_GATE_FAILED: checklist has failing items");
                }

                store.updatePeriodCloseStatus(c, tenantId, bookId, planId, "CLOSED", approver, Instant.now());
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    // --- YEAR01, YEAR02, YEAR03 Carry Batch Plan & Restatement ---
    public CarryBatchPlan previewCarryBatch(
            UUID tenantId, UUID bookId, UUID sourceYearId, UUID targetYearId,
            int sourceCloseVersion, long sourceWatermark, int targetOpeningVersion,
            String mappingVersion, String kind, UUID previousBatchId, String reason,
            List<CarryBalanceItem> balances, List<CarrySubledgerItem> subledgerItems,
            Map<String, BigDecimal> preCorrectionReportAmounts, PersonActor maker) throws SQLException {

        validateActorSod(maker, null);
        validateChartOfAccountsMapping(mappingVersion, balances);

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                // Verify consecutive years if fiscal years exist in DB
                Optional<Integer> srcYear = store.getFiscalYear(c, tenantId, bookId, sourceYearId);
                Optional<Integer> tgtYear = store.getFiscalYear(c, tenantId, bookId, targetYearId);
                if (srcYear.isPresent() && tgtYear.isPresent()) {
                    validateConsecutiveYears(srcYear.get(), tgtYear.get());
                }

                if ("DELTA".equals(kind)) {
                    if (previousBatchId == null) {
                        throw new IllegalArgumentException("DELTA_CARRY_BATCH_REQUIRES_PREVIOUS_BATCH_ID");
                    }
                    CarryBatchPlan prev = store.findCarryBatchPlan(c, tenantId, bookId, previousBatchId)
                            .orElseThrow(() -> new IllegalArgumentException("PREVIOUS_BATCH_NOT_FOUND: " + previousBatchId));

                    if (!prev.sourceYearId().equals(sourceYearId) || !prev.targetYearId().equals(targetYearId)) {
                        throw new IllegalArgumentException("PREVIOUS_BATCH_YEAR_MISMATCH");
                    }
                    if (!"COMMITTED".equals(prev.status())) {
                        throw new IllegalStateException("PREVIOUS_BATCH_MUST_BE_COMMITTED: " + prev.status());
                    }
                    if (sourceCloseVersion <= prev.sourceCloseVersion()) {
                        throw new IllegalArgumentException("SOURCE_CLOSE_VERSION_MUST_BE_STRICTLY_GREATER_THAN_PREVIOUS_BATCH: current=" + sourceCloseVersion + ", prev=" + prev.sourceCloseVersion());
                    }
                }

                List<RestatementManifestItem> restatements = calculateRestatementImpact(balances, preCorrectionReportAmounts);

                String digestPayload = tenantId + ":" + bookId + ":" + sourceYearId + ":" + targetYearId + ":" + kind + ":" + balances.size();
                String contentDigest = sha256Hex(digestPayload);

                UUID batchId = UUID.randomUUID();
                CarryBatchPlan plan = new CarryBatchPlan(
                        batchId, tenantId, bookId, sourceYearId, targetYearId, kind, "PREVIEWED",
                        sourceCloseVersion, sourceWatermark, targetOpeningVersion, mappingVersion,
                        1L, contentDigest, previousBatchId, reason, maker, null, null, Instant.now(),
                        balances, subledgerItems, restatements
                );

                store.insertCarryBatchPlan(c, plan);
                c.commit();
                return plan;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    public void commitCarryBatch(UUID tenantId, UUID bookId, UUID batchId, PersonActor approver) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                CarryBatchPlan plan = store.findCarryBatchPlan(c, tenantId, bookId, batchId)
                        .orElseThrow(() -> new IllegalArgumentException("CARRY_BATCH_NOT_FOUND"));

                validateActorSod(plan.maker(), approver);

                if ("COMMITTED".equals(plan.status())) {
                    throw new IllegalStateException("CARRY_BATCH_ALREADY_COMMITTED");
                }
                if ("CANCELLED".equals(plan.status())) {
                    throw new IllegalStateException("CANNOT_COMMIT_CANCELLED_CARRY_BATCH");
                }

                store.updateCarryBatchStatus(c, tenantId, bookId, batchId, "COMMITTED", approver, Instant.now());
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }
}

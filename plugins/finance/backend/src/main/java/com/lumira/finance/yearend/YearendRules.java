package com.lumira.finance.yearend;

import static com.lumira.finance.yearend.YearendModel.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Domain rules and business logic for Lane W09 (GL and Year-End).
 */
public final class YearendRules {
    private YearendRules() {}

    public static final String CAS_PRIOR_PERIOD_ACCOUNT = "6901";
    public static final String SMALL_PROHIBITED_ACCOUNT = "6901";

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static void validateActorSod(PersonActor maker, PersonActor approver) {
        if (maker == null) {
            throw new IllegalArgumentException("MAKER_REQUIRED");
        }
        if (approver != null) {
            if (maker.accountId().equals(approver.accountId()) || 
                (maker.personId() != null && maker.personId().equals(approver.personId()))) {
                throw new IllegalArgumentException("SOD_VIOLATION_MAKER_CANNOT_APPROVE");
            }
        }
    }

    // --- GL01 Cutover Validation ---
    public static void validateCutoverMode(String mode) {
        if (!"FULL_HISTORY".equals(mode) && !"OPENING_PLUS_YTD".equals(mode)) {
            throw new IllegalArgumentException("INVALID_CUTOVER_MODE: " + mode);
        }
    }

    public static List<TieOutResult> evaluateCutoverTieOut(
            List<OpeningBalanceItem> balances,
            List<BaselineItem> baselines,
            Map<String, BigDecimal> subledgerTotals) {
        
        List<TieOutResult> results = new ArrayList<>();

        // 1. Trial Balance Check: Debit vs Credit
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        BigDecimal glAr = BigDecimal.ZERO;
        BigDecimal glAp = BigDecimal.ZERO;
        BigDecimal glAsset = BigDecimal.ZERO;
        BigDecimal glCash = BigDecimal.ZERO;
        BigDecimal glPayroll = BigDecimal.ZERO;

        for (OpeningBalanceItem b : balances) {
            if ("DEBIT".equalsIgnoreCase(b.direction())) {
                totalDebit = totalDebit.add(b.amount());
            } else if ("CREDIT".equalsIgnoreCase(b.direction())) {
                totalCredit = totalCredit.add(b.amount());
            }
            if (b.accountCode().startsWith("1122") || b.accountCode().startsWith("1121")) { // AR
                glAr = glAr.add(b.amount());
            } else if (b.accountCode().startsWith("2202") || b.accountCode().startsWith("2201")) { // AP
                glAp = glAp.add(b.amount());
            } else if (b.accountCode().startsWith("1601")) { // Fixed Assets (Gross)
                glAsset = glAsset.add("DEBIT".equalsIgnoreCase(b.direction()) ? b.amount() : b.amount().negate());
            } else if (b.accountCode().startsWith("1602")) { // Accumulated Depreciation (Contra-Asset reduces net book value)
                glAsset = glAsset.subtract("CREDIT".equalsIgnoreCase(b.direction()) ? b.amount() : b.amount().negate());
            } else if (b.accountCode().startsWith("1001") || b.accountCode().startsWith("1002")) { // Cash/Bank
                glCash = glCash.add(b.amount());
            } else if (b.accountCode().startsWith("2211")) { // Payroll Payable
                glPayroll = glPayroll.add(b.amount());
            }
        }

        BigDecimal trialDiff = totalDebit.subtract(totalCredit);
        results.add(new TieOutResult("TRIAL_BALANCE", trialDiff.compareTo(BigDecimal.ZERO) == 0,
                totalDebit, totalCredit, trialDiff, Map.of("debit", totalDebit.toPlainString(), "credit", totalCredit.toPlainString())));

        // 2. AR Subledger
        BigDecimal subAr = subledgerTotals.getOrDefault("AR", BigDecimal.ZERO);
        BigDecimal arDiff = glAr.subtract(subAr);
        results.add(new TieOutResult("AR_SUBLEDGER", arDiff.compareTo(BigDecimal.ZERO) == 0,
                glAr, subAr, arDiff, Map.of("glAr", glAr.toPlainString(), "subledgerAr", subAr.toPlainString())));

        // 3. AP Subledger
        BigDecimal subAp = subledgerTotals.getOrDefault("AP", BigDecimal.ZERO);
        BigDecimal apDiff = glAp.subtract(subAp);
        results.add(new TieOutResult("AP_SUBLEDGER", apDiff.compareTo(BigDecimal.ZERO) == 0,
                glAp, subAp, apDiff, Map.of("glAp", glAp.toPlainString(), "subledgerAp", subAp.toPlainString())));

        // 4. Asset Subledger
        BigDecimal subAsset = subledgerTotals.getOrDefault("ASSET", BigDecimal.ZERO);
        BigDecimal assetDiff = glAsset.subtract(subAsset);
        results.add(new TieOutResult("ASSET_SUBLEDGER", assetDiff.compareTo(BigDecimal.ZERO) == 0,
                glAsset, subAsset, assetDiff, Map.of("glAsset", glAsset.toPlainString(), "subledgerAsset", subAsset.toPlainString())));

        // 5. Cashflow Baseline
        BigDecimal baseCash = BigDecimal.ZERO;
        for (BaselineItem item : baselines) {
            if ("CASHFLOW".equalsIgnoreCase(item.baselineType())) {
                baseCash = baseCash.add(item.amount());
            }
        }
        BigDecimal cashDiff = glCash.subtract(baseCash);
        results.add(new TieOutResult("CASHFLOW_TIE_OUT", cashDiff.compareTo(BigDecimal.ZERO) == 0,
                glCash, baseCash, cashDiff, Map.of("glCash", glCash.toPlainString(), "baselineCash", baseCash.toPlainString())));

        // 6. Payroll Subledger
        BigDecimal subPayroll = subledgerTotals.getOrDefault("PAYROLL", BigDecimal.ZERO);
        BigDecimal payrollDiff = glPayroll.subtract(subPayroll);
        results.add(new TieOutResult("PAYROLL_TIE_OUT", payrollDiff.compareTo(BigDecimal.ZERO) == 0,
                glPayroll, subPayroll, payrollDiff, Map.of("glPayroll", glPayroll.toPlainString(), "subledgerPayroll", subPayroll.toPlainString())));

        return results;
    }

    public static boolean areAllTieOutsPassed(List<TieOutResult> results) {
        for (TieOutResult r : results) {
            if (!r.passed()) return false;
        }
        return true;
    }

    // --- GL02 & GL03 Auto Rule & Auto Review Evaluation ---
    public static AutoRuleExecution evaluateAutoRuleAndReview(
            AutoRuleTemplate template,
            AutoReviewPolicy policy,
            UUID yearId,
            UUID journalId,
            String sourceEventId,
            String sourceSystem,
            BigDecimal transactionAmount,
            String businessType,
            List<String> affectedAccounts,
            PersonActor transactionMaker) {

        if (!"APPROVED".equals(template.status())) {
            throw new IllegalStateException("RULE_TEMPLATE_NOT_APPROVED");
        }

        int exceptions = 0;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("templateCode", template.ruleCode());
        evidence.put("templateVersion", template.version());
        evidence.put("transactionAmount", transactionAmount.toPlainString());

        boolean autoApproved = false;

        if (policy != null) {
            if (!"APPROVED".equals(policy.status())) {
                throw new IllegalStateException("REVIEW_POLICY_NOT_APPROVED");
            }
            evidence.put("policyCode", policy.policyCode());
            evidence.put("policyVersion", policy.version());
            evidence.put("policyDigest", policy.contentDigest());
            evidence.put("policyMaxAmount", policy.maxAmount().toPlainString());

            // 1. Amount ceiling check
            if (transactionAmount.compareTo(policy.maxAmount()) > 0) {
                exceptions++;
                evidence.put("amountCeilingExceeded", true);
            }

            // 2. Business type scope check
            if (!policy.scopeBusinessTypes().contains(businessType)) {
                exceptions++;
                evidence.put("businessTypeOutOfScope", businessType);
            }

            // 3. Allowed account codes check
            for (String acc : affectedAccounts) {
                if (!policy.allowedAccountCodes().contains(acc)) {
                    exceptions++;
                    evidence.put("accountOutOfScope", acc);
                }
            }

            // 4. Zero Exception requirement
            if (policy.zeroExceptionRequired() && exceptions == 0) {
                autoApproved = true;
                evidence.put("autoReviewVerdict", "APPROVED");
            } else {
                evidence.put("autoReviewVerdict", "MANUAL_REVIEW_REQUIRED");
            }
        }

        String effectPayload = template.bookId() + ":" + sourceSystem + ":" + sourceEventId + ":" + template.id();
        String stableEffectKey = sha256Hex(effectPayload);

        return new AutoRuleExecution(
                UUID.randomUUID(),
                template.tenantId(),
                template.bookId(),
                yearId,
                journalId,
                sourceEventId,
                sourceSystem,
                stableEffectKey,
                template.id(),
                template.version(),
                policy == null ? null : policy.id(),
                policy == null ? null : policy.version(),
                policy == null ? null : policy.contentDigest(),
                autoApproved,
                exceptions,
                evidence,
                java.time.Instant.now()
        );
    }

    // --- GL05 Journal Correction & CAS vs SMALL ---
    public static void validateCorrectionRequest(
            String standard,
            String correctionKind,
            String casAccountCode,
            List<CorrectionAllocation> allocations,
            Map<Integer, BigDecimal> lineWatermarks) {

        if ("SMALL".equalsIgnoreCase(standard)) {
            if (SMALL_PROHIBITED_ACCOUNT.equals(casAccountCode)) {
                throw new IllegalArgumentException("SMALL_STANDARD_DOES_NOT_PERMIT_ACCOUNT_6901");
            }
        } else if ("CAS".equalsIgnoreCase(standard)) {
            if ("PRIOR_PERIOD_ADJUSTMENT".equalsIgnoreCase(correctionKind)) {
                if (!CAS_PRIOR_PERIOD_ACCOUNT.equals(casAccountCode)) {
                    throw new IllegalArgumentException("CAS_PRIOR_PERIOD_ADJUSTMENT_REQUIRES_ACCOUNT_6901");
                }
            }
        }

        // Bounded allocation checks
        if ("FULL_REVERSAL".equalsIgnoreCase(correctionKind) || "PARTIAL_REVERSAL".equalsIgnoreCase(correctionKind)) {
            if (allocations == null || allocations.isEmpty()) {
                throw new IllegalArgumentException("REVERSAL_ALLOCATIONS_REQUIRED");
            }
            for (CorrectionAllocation alloc : allocations) {
                if (alloc.amount().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("REVERSAL_AMOUNT_MUST_BE_POSITIVE");
                }
                BigDecimal remainingWatermark = (lineWatermarks != null)
                        ? lineWatermarks.getOrDefault(alloc.sourceLineNo(), BigDecimal.ZERO)
                        : BigDecimal.ZERO;
                if (alloc.amount().compareTo(remainingWatermark) > 0) {
                    throw new IllegalArgumentException("ALLOCATION_EXCEEDS_REMAINING_WATERMARK_FOR_LINE_" + alloc.sourceLineNo());
                }
            }
        }
    }

    // --- GL06 Period Close Checklist Evaluation ---
    public static PeriodCloseCheckPlan evaluatePeriodClose(
            UUID tenantId,
            UUID bookId,
            UUID yearId,
            UUID periodId,
            int unpostedJournalsCount,
            BigDecimal trialDebit,
            BigDecimal trialCredit,
            boolean bankReconciliationComplete,
            Map<String, BigDecimal> glBalances,
            Map<String, BigDecimal> subledgerBalances,
            boolean cashflowCoverageComplete,
            boolean taxWorksheetReady,
            PersonActor maker) {

        List<PeriodCloseCheckItem> items = new ArrayList<>();

        // Gate 1: Unposted journals
        boolean unpostedPass = (unpostedJournalsCount == 0);
        items.add(new PeriodCloseCheckItem(UUID.randomUUID(), "UNPOSTED_JOURNALS", "CHECK_UNPOSTED",
                "Unposted Journals Check", unpostedPass, Map.of("count", unpostedJournalsCount)));

        // Gate 2: Trial Balance
        boolean trialPass = (trialDebit != null && trialCredit != null && trialDebit.compareTo(trialCredit) == 0);
        items.add(new PeriodCloseCheckItem(UUID.randomUUID(), "TRIAL_BALANCE", "CHECK_TB",
                "Trial Balance Equality", trialPass, Map.of("debit", trialDebit == null ? "0" : trialDebit.toPlainString(),
                        "credit", trialCredit == null ? "0" : trialCredit.toPlainString())));

        // Gate 3: Bank Reconciliation
        items.add(new PeriodCloseCheckItem(UUID.randomUUID(), "BANK_RECONCILIATION", "CHECK_BANK_REC",
                "Bank Reconciliation Status", bankReconciliationComplete, Map.of("complete", bankReconciliationComplete)));

        // Gate 4: Subledger Tie-outs
        BigDecimal glAr = glBalances.getOrDefault("AR", BigDecimal.ZERO);
        BigDecimal subAr = subledgerBalances.getOrDefault("AR", BigDecimal.ZERO);
        boolean arPass = (glAr.compareTo(subAr) == 0);
        items.add(new PeriodCloseCheckItem(UUID.randomUUID(), "AR_SUBLEDGER", "CHECK_AR_TIE_OUT",
                "AR Subledger Tie-Out", arPass, Map.of("gl", glAr.toPlainString(), "subledger", subAr.toPlainString())));

        BigDecimal glAp = glBalances.getOrDefault("AP", BigDecimal.ZERO);
        BigDecimal subAp = subledgerBalances.getOrDefault("AP", BigDecimal.ZERO);
        boolean apPass = (glAp.compareTo(subAp) == 0);
        items.add(new PeriodCloseCheckItem(UUID.randomUUID(), "AP_SUBLEDGER", "CHECK_AP_TIE_OUT",
                "AP Subledger Tie-Out", apPass, Map.of("gl", glAp.toPlainString(), "subledger", subAp.toPlainString())));

        BigDecimal glAsset = glBalances.getOrDefault("ASSET", BigDecimal.ZERO);
        BigDecimal subAsset = subledgerBalances.getOrDefault("ASSET", BigDecimal.ZERO);
        boolean assetPass = (glAsset.compareTo(subAsset) == 0);
        items.add(new PeriodCloseCheckItem(UUID.randomUUID(), "ASSET_SUBLEDGER", "CHECK_ASSET_TIE_OUT",
                "Asset Subledger Tie-Out", assetPass, Map.of("gl", glAsset.toPlainString(), "subledger", subAsset.toPlainString())));

        BigDecimal glInv = glBalances.getOrDefault("INVENTORY", BigDecimal.ZERO);
        BigDecimal subInv = subledgerBalances.getOrDefault("INVENTORY", BigDecimal.ZERO);
        boolean invPass = (glInv.compareTo(subInv) == 0);
        items.add(new PeriodCloseCheckItem(UUID.randomUUID(), "INVENTORY_SUBLEDGER", "CHECK_INV_TIE_OUT",
                "Inventory Subledger Tie-Out", invPass, Map.of("gl", glInv.toPlainString(), "subledger", subInv.toPlainString())));

        BigDecimal glPay = glBalances.getOrDefault("PAYROLL", BigDecimal.ZERO);
        BigDecimal subPay = subledgerBalances.getOrDefault("PAYROLL", BigDecimal.ZERO);
        boolean payPass = (glPay.compareTo(subPay) == 0);
        items.add(new PeriodCloseCheckItem(UUID.randomUUID(), "PAYROLL_SUBLEDGER", "CHECK_PAYROLL_TIE_OUT",
                "Payroll Subledger Tie-Out", payPass, Map.of("gl", glPay.toPlainString(), "subledger", subPay.toPlainString())));

        // Gate 5: Cashflow Coverage
        items.add(new PeriodCloseCheckItem(UUID.randomUUID(), "CASHFLOW_COVERAGE", "CHECK_CASHFLOW",
                "Cashflow Allocation Coverage", cashflowCoverageComplete, Map.of("coverageComplete", cashflowCoverageComplete)));

        // Gate 6: Tax Worksheet
        items.add(new PeriodCloseCheckItem(UUID.randomUUID(), "TAX_WORKSHEET", "CHECK_TAX_WORKSHEET",
                "Tax Worksheet Readiness", taxWorksheetReady, Map.of("taxReady", taxWorksheetReady)));

        boolean allPass = unpostedPass && trialPass && bankReconciliationComplete && arPass && apPass && assetPass && invPass && payPass && cashflowCoverageComplete && taxWorksheetReady;

        String digestPayload = tenantId + ":" + bookId + ":" + yearId + ":" + periodId + ":" + allPass;
        String contentDigest = sha256Hex(digestPayload);

        return new PeriodCloseCheckPlan(
                UUID.randomUUID(),
                tenantId,
                bookId,
                yearId,
                periodId,
                1L,
                "PREVIEWED",
                contentDigest,
                100L,
                allPass,
                unpostedJournalsCount,
                trialDebit == null ? BigDecimal.ZERO : trialDebit,
                trialCredit == null ? BigDecimal.ZERO : trialCredit,
                trialPass,
                bankReconciliationComplete,
                arPass,
                apPass,
                assetPass,
                invPass,
                payPass,
                cashflowCoverageComplete,
                taxWorksheetReady,
                maker,
                null,
                null,
                null,
                java.time.Instant.now(),
                items
        );
    }

    // --- YEAR01, YEAR02, YEAR03 Carry Batch & Restatement ---
    public static List<RestatementManifestItem> calculateRestatementImpact(
            List<CarryBalanceItem> balances,
            Map<String, BigDecimal> preCorrectionReportAmounts) {

        List<RestatementManifestItem> restatements = new ArrayList<>();
        BigDecimal totalDeltaBs = BigDecimal.ZERO;
        BigDecimal totalDeltaPl = BigDecimal.ZERO;

        for (CarryBalanceItem b : balances) {
            if (b.targetAccountCode().startsWith("1") || b.targetAccountCode().startsWith("2") || b.targetAccountCode().startsWith("3")) {
                totalDeltaBs = totalDeltaBs.add(b.deltaAmount());
            } else if (b.targetAccountCode().startsWith("6") || b.targetAccountCode().startsWith("5")) {
                totalDeltaPl = totalDeltaPl.add(b.deltaAmount());
            }
        }

        // 1. Balance Sheet Comparative Column
        BigDecimal preBs = preCorrectionReportAmounts.getOrDefault("BALANCE_SHEET_TOTAL", BigDecimal.ZERO);
        BigDecimal restatedBs = preBs.add(totalDeltaBs);
        restatements.add(new RestatementManifestItem(
                UUID.randomUUID(),
                "BALANCE_SHEET_COMPARATIVE",
                "BS_NET_ASSETS",
                "Net Assets Prior-Year Comparative Restatement",
                preBs,
                totalDeltaBs,
                restatedBs,
                "Restated prior-year comparative closing balance by " + totalDeltaBs.toPlainString(),
                java.time.Instant.now()
        ));

        // 2. Income Statement Comparative Column
        BigDecimal prePl = preCorrectionReportAmounts.getOrDefault("INCOME_STATEMENT_TOTAL", BigDecimal.ZERO);
        BigDecimal restatedPl = prePl.add(totalDeltaPl);
        restatements.add(new RestatementManifestItem(
                UUID.randomUUID(),
                "INCOME_STATEMENT_COMPARATIVE",
                "PL_NET_PROFIT",
                "Net Profit Prior-Period Comparative Restatement",
                prePl,
                totalDeltaPl,
                restatedPl,
                "Restated prior-period comparative net profit by " + totalDeltaPl.toPlainString(),
                java.time.Instant.now()
        ));

        // 3. CIT Annual Adjustment
        BigDecimal preCit = preCorrectionReportAmounts.getOrDefault("CIT_TAXABLE_INCOME", BigDecimal.ZERO);
        BigDecimal restatedCit = preCit.add(totalDeltaPl);
        restatements.add(new RestatementManifestItem(
                UUID.randomUUID(),
                "CIT_ANNUAL_ADJUSTMENT",
                "CIT_ANNUAL_DIFF",
                "Corporate Income Tax Annual Reconciliation Difference",
                preCit,
                totalDeltaPl,
                restatedCit,
                "CIT annual taxable income difference adjusted by " + totalDeltaPl.toPlainString(),
                java.time.Instant.now()
        ));

        return restatements;
    }

    // --- YEAR01 Multi-Year Scope & Chart of Accounts Mapping ---
    public static void validateConsecutiveYears(int sourceYear, int targetYear) {
        if (targetYear != sourceYear + 1) {
            throw new IllegalArgumentException("TARGET_YEAR_MUST_BE_CONSECUTIVE_NEXT_YEAR: source=" + sourceYear + ", target=" + targetYear);
        }
    }

    public static void validateMultiYearReadScope(Collection<UUID> requestedYearIds, Collection<UUID> authorizedYearIds) {
        if (requestedYearIds == null || requestedYearIds.isEmpty()) {
            throw new IllegalArgumentException("REQUESTED_YEARS_EMPTY");
        }
        if (authorizedYearIds == null || !authorizedYearIds.containsAll(requestedYearIds)) {
            throw new IllegalArgumentException("MULTI_YEAR_READ_UNAUTHORIZED: requested=" + requestedYearIds + ", authorized=" + authorizedYearIds);
        }
    }

    public static void validateChartOfAccountsMapping(String mappingVersion, List<CarryBalanceItem> balances) {
        if (mappingVersion == null || mappingVersion.isBlank()) {
            throw new IllegalArgumentException("MAPPING_VERSION_REQUIRED");
        }
        if (balances == null || balances.isEmpty()) {
            throw new IllegalArgumentException("CARRY_BALANCES_REQUIRED");
        }
        for (CarryBalanceItem item : balances) {
            if (item.sourceAccountCode() == null || item.sourceAccountCode().isBlank()) {
                throw new IllegalArgumentException("SOURCE_ACCOUNT_CODE_REQUIRED");
            }
            if (item.targetAccountCode() == null || item.targetAccountCode().isBlank()) {
                throw new IllegalArgumentException("TARGET_ACCOUNT_CODE_REQUIRED_FOR_MAPPING");
            }
            if (item.netOpeningAmount() == null || item.netOpeningAmount().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("NET_OPENING_AMOUNT_MUST_BE_NON_NEGATIVE");
            }
        }
    }
}

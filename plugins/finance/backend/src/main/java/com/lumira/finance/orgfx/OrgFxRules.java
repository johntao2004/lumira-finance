package com.lumira.finance.orgfx;

import com.lumira.finance.orgfx.OrgFxModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Domain rules and calculation logic for Lane W01 (org-fx).
 * Strict adherence to 28,8 money, 28,12 rates, multi-date semantics, SoD checks, and tax catalog rules.
 */
public final class OrgFxRules {
    public static final BigDecimal MAX_SME_REVENUE = new BigDecimal("100000000.00000000");
    public static final BigDecimal MAX_SME_ASSETS = new BigDecimal("100000000.00000000");
    public static final int MAX_SME_EMPLOYEES = 300;

    private OrgFxRules() {}

    /**
     * Segregation of Duties (SoD) verification.
     * Enforces natural-person independence between maker and reviewer.
     */
    public static void verifySod(Actor maker, Actor reviewer) {
        if (maker == null || reviewer == null) {
            throw new IllegalArgumentException("SOD_INCOMPLETE: Maker and reviewer are both required.");
        }
        if (maker.personId() == null || reviewer.personId() == null) {
            throw new IllegalArgumentException("SOD_INCOMPLETE: Valid natural-person IDs required.");
        }
        if (maker.personId().equals(reviewer.personId())) {
            throw new IllegalStateException("SOD_VIOLATION: Maker and reviewer natural persons must differ (personId=" + maker.personId() + ").");
        }
    }

    /**
     * ORG01: Enforces the legal entity lifecycle state machine and dual natural-person SoD.
     * Transitions:
     * PREPARING -> PENDING_CONFIRMATION
     * PENDING_CONFIRMATION -> ACTIVE, PREPARING
     * ACTIVE -> SUSPENDED, TERMINATED
     * SUSPENDED -> ACTIVE, TERMINATED
     * TERMINATED is terminal (no outward transitions permitted).
     */
    public static void validateLifecycleTransition(
            EntityStatus fromStatus,
            EntityStatus toStatus,
            Actor maker,
            Actor reviewer
    ) {
        verifySod(maker, reviewer);
        if (fromStatus == null || toStatus == null) {
            throw new IllegalArgumentException("STATUS_REQUIRED: fromStatus and toStatus are required.");
        }
        if (fromStatus == toStatus) {
            throw new IllegalStateException("SAME_STATUS: Entity is already in status " + toStatus + ".");
        }
        if (fromStatus == EntityStatus.TERMINATED) {
            throw new IllegalStateException("TERMINATED_IS_TERMINAL: Entity has been terminated and cannot transition to any other status.");
        }

        boolean allowed = switch (fromStatus) {
            case PREPARING -> toStatus == EntityStatus.PENDING_CONFIRMATION;
            case PENDING_CONFIRMATION -> toStatus == EntityStatus.ACTIVE || toStatus == EntityStatus.PREPARING;
            case ACTIVE -> toStatus == EntityStatus.SUSPENDED || toStatus == EntityStatus.TERMINATED;
            case SUSPENDED -> toStatus == EntityStatus.ACTIVE || toStatus == EntityStatus.TERMINATED;
            case TERMINATED -> false;
        };

        if (!allowed) {
            throw new IllegalStateException("ILLEGAL_LIFECYCLE_TRANSITION: Cannot transition from " + fromStatus + " to " + toStatus + ".");
        }
    }

    /**
     * ORG02: Assesses eligibility for SMALL accounting standard.
     * Decoupled from VAT taxpayer status.
     */
    public static StandardEligibilityAssessment assessStandardEligibility(
            UUID id,
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
        if (assessor == null || assessor.personId() == null) {
            throw new IllegalArgumentException("ASSESSOR_REQUIRED: Valid assessor natural person required.");
        }
        if (employeeCount < 0) {
            throw new IllegalArgumentException("INVALID_EMPLOYEE_COUNT: Employee count cannot be negative.");
        }
        if (annualRevenue == null || annualRevenue.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("INVALID_ANNUAL_REVENUE: Annual revenue cannot be null or negative.");
        }
        if (totalAssets == null || totalAssets.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("INVALID_TOTAL_ASSETS: Total assets cannot be null or negative.");
        }

        List<String> disqualifications = new ArrayList<>();
        if (isPubliclyTraded) {
            disqualifications.add("PUBLICLY_TRADED: Listed companies must adopt CAS.");
        }
        if (isFinancialInstitution) {
            disqualifications.add("FINANCIAL_INSTITUTION: Financial and insurance entities must adopt CAS.");
        }
        if (isGroupSubsidiaryOfPublic) {
            disqualifications.add("PUBLIC_GROUP_SUBSIDIARY: Entities with public reporting parent/subsidiary must adopt CAS.");
        }
        if (employeeCount > MAX_SME_EMPLOYEES) {
            disqualifications.add("EMPLOYEE_COUNT_EXCEEDED: Employee count (" + employeeCount + ") exceeds SME ceiling (300).");
        }
        if (annualRevenue.compareTo(MAX_SME_REVENUE) > 0) {
            disqualifications.add("ANNUAL_REVENUE_EXCEEDED: Annual revenue (" + annualRevenue.toPlainString() + ") exceeds SME ceiling (100M).");
        }
        if (totalAssets.compareTo(MAX_SME_ASSETS) > 0) {
            disqualifications.add("TOTAL_ASSETS_EXCEEDED: Total assets (" + totalAssets.toPlainString() + ") exceeds SME ceiling (100M).");
        }

        boolean eligibleForSmall = disqualifications.isEmpty();
        AccountingStandard evaluated = eligibleForSmall ? AccountingStandard.SMALL : AccountingStandard.CAS;

        return new StandardEligibilityAssessment(
                id,
                tenantId,
                legalEntityId,
                isPubliclyTraded,
                isFinancialInstitution,
                isGroupSubsidiaryOfPublic,
                employeeCount,
                annualRevenue.setScale(8, RoundingMode.HALF_UP),
                totalAssets.setScale(8, RoundingMode.HALF_UP),
                eligibleForSmall,
                evaluated,
                List.copyOf(disqualifications),
                assessor,
                notes,
                java.time.OffsetDateTime.now()
        );
    }

    /**
     * ORG02: Validates standard transition rules (prohibiting arbitrary CAS to SMALL downgrade).
     */
    public static void validateStandardTransition(
            AccountingStandard current,
            AccountingStandard target,
            boolean eligibleForSmall,
            Actor maker,
            Actor reviewer
    ) {
        if (current == target) {
            throw new IllegalArgumentException("SAME_STANDARD: Current and target standards are identical (" + current + ").");
        }
        if (current == AccountingStandard.CAS && target == AccountingStandard.SMALL) {
            if (!eligibleForSmall) {
                throw new IllegalStateException("STANDARD_DOWNGRADE_PROHIBITED: Entity is not legally eligible for SMALL standard.");
            }
        }
        verifySod(maker, reviewer);
    }

    /**
     * ORG02: Evaluates and balances a standard conversion bridge worksheet between CAS and SMALL.
     */
    public static ConversionBridge calculateConversionBridge(
            UUID id,
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
        validateStandardTransition(fromStandard, toStandard, eligibleForSmall, maker, reviewer);

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        List<ConversionBridgeItem> calculatedItems = new ArrayList<>();

        if (rawItems != null) {
            for (ConversionBridgeItem raw : rawItems) {
                BigDecimal orig = raw.originalBalance().setScale(8, RoundingMode.HALF_UP);
                BigDecimal deb = raw.adjustmentDebit().setScale(8, RoundingMode.HALF_UP);
                BigDecimal cred = raw.adjustmentCredit().setScale(8, RoundingMode.HALF_UP);
                BigDecimal adj = orig.add(deb).subtract(cred).setScale(8, RoundingMode.HALF_UP);

                totalDebit = totalDebit.add(deb);
                totalCredit = totalCredit.add(cred);

                calculatedItems.add(new ConversionBridgeItem(
                        raw.id() != null ? raw.id() : UUID.randomUUID(),
                        id,
                        raw.accountCode(),
                        raw.accountName(),
                        orig,
                        deb,
                        cred,
                        adj,
                        raw.adjustmentReason(),
                        raw.affectedOpeningBalanceType()
                ));
            }
        }

        return new ConversionBridge(
                id,
                tenantId,
                bookId,
                fromStandard,
                toStandard,
                effectiveFiscalYear,
                reviewer != null ? "APPROVED" : "DRAFT",
                maker,
                reviewer,
                totalDebit,
                totalCredit,
                justification,
                List.copyOf(calculatedItems),
                1L,
                java.time.OffsetDateTime.now()
        );
    }

    /**
     * ORG03: Validates multi-date semantics.
     * Quad-date model: businessDate, invoiceDate, accountingDate, taxObligationDate.
     */
    public static MultiDateValidationResult validateMultiDates(MultiDateContext ctx) {
        List<String> errors = new ArrayList<>();
        if (ctx.businessDate() == null) errors.add("MISSING_BUSINESS_DATE: Business event date is required.");
        if (ctx.invoiceDate() == null) errors.add("MISSING_INVOICE_DATE: Invoice date is required.");
        if (ctx.accountingDate() == null) errors.add("MISSING_ACCOUNTING_DATE: Accounting date is required.");
        if (ctx.taxObligationDate() == null) errors.add("MISSING_TAX_OBLIGATION_DATE: Tax obligation date is required.");

        if (!errors.isEmpty()) {
            return new MultiDateValidationResult(false, List.copyOf(errors), false, false, 0);
        }

        LocalDate today = LocalDate.now();
        LocalDate maxFuture = today.plusDays(366);
        LocalDate minBoundary = LocalDate.of(1900, 1, 1);

        if (ctx.businessDate().isAfter(maxFuture)) errors.add("BUSINESS_DATE_HORIZON_EXCEEDED: Business event date cannot exceed 1 year in future.");
        if (ctx.invoiceDate().isAfter(maxFuture)) errors.add("INVOICE_DATE_HORIZON_EXCEEDED: Invoice date cannot exceed 1 year in future.");
        if (ctx.accountingDate().isAfter(maxFuture)) errors.add("ACCOUNTING_DATE_HORIZON_EXCEEDED: Accounting date cannot exceed 1 year in future.");
        if (ctx.taxObligationDate().isAfter(maxFuture)) errors.add("TAX_OBLIGATION_DATE_HORIZON_EXCEEDED: Tax obligation date cannot exceed 1 year in future.");

        if (ctx.businessDate().isBefore(minBoundary) || ctx.invoiceDate().isBefore(minBoundary) ||
            ctx.accountingDate().isBefore(minBoundary) || ctx.taxObligationDate().isBefore(minBoundary)) {
            errors.add("DATE_HISTORICAL_BOUNDARY_EXCEEDED: Dates cannot precede year 1900.");
        }

        boolean periodOpen = "OPEN".equalsIgnoreCase(ctx.periodStatus());
        if (!periodOpen) {
            errors.add("ACCOUNTING_PERIOD_CLOSED: Cannot post to closed accounting period.");
        }

        int spanDays = (int) Math.abs(ChronoUnit.DAYS.between(ctx.businessDate(), ctx.accountingDate()));
        boolean requiresExplanation = spanDays > 90;
        if (requiresExplanation && (ctx.justification() == null || ctx.justification().trim().isEmpty())) {
            errors.add("EXPLANATION_REQUIRED: Date discrepancy between business date and accounting date exceeds 90 days (" + spanDays + " days).");
        }

        boolean isValid = errors.isEmpty();
        return new MultiDateValidationResult(isValid, List.copyOf(errors), periodOpen, requiresExplanation, spanDays);
    }

    /**
     * ORG04: Translates original-currency line to functional CNY.
     * Retains 28,8 precision internally while generating 2-decimal quantized output.
     */
    public static FxLineTranslationResult translateFxLine(
            String originalCurrency,
            BigDecimal originalAmount,
            BigDecimal fxRate
    ) {
        if (originalCurrency == null || !originalCurrency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("INVALID_CURRENCY_CODE: Original currency must be 3 uppercase letters.");
        }
        if (originalAmount == null || fxRate == null) {
            throw new IllegalArgumentException("AMOUNTS_REQUIRED: Original amount and FX rate are required.");
        }
        if (fxRate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("INVALID_FX_RATE: FX rate must be strictly positive.");
        }

        BigDecimal orig = originalAmount.setScale(8, RoundingMode.HALF_UP);
        BigDecimal rate = fxRate.setScale(12, RoundingMode.HALF_UP);

        if ("CNY".equalsIgnoreCase(originalCurrency) && rate.compareTo(new BigDecimal("1.000000000000")) != 0) {
            throw new IllegalArgumentException("BASE_CURRENCY_RATE_MUST_BE_ONE: Functional currency CNY must have exchange rate 1.0.");
        }

        BigDecimal exact = orig.multiply(rate).setScale(8, RoundingMode.HALF_UP);
        BigDecimal quantized = exact.setScale(2, RoundingMode.HALF_UP);
        BigDecimal diff = exact.subtract(quantized).setScale(8, RoundingMode.HALF_UP);

        return new FxLineTranslationResult(
                originalCurrency,
                orig,
                rate,
                exact,
                quantized,
                diff
        );
    }

    /**
     * ORG04: Calculates month-end monetary foreign currency revaluation.
     * Restated = ForeignBalance * ClosingRate
     * GainLoss = Restated - CarryingCny
     */
    public static FxRevaluationItem calculateRevaluationItem(
            UUID id,
            UUID planId,
            String accountCode,
            String accountName,
            String currencyCode,
            BigDecimal foreignBalance,
            BigDecimal carryingCnyBalance,
            BigDecimal closingRate,
            String exchangeGainLossAccount
    ) {
        if (currencyCode == null || !currencyCode.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("INVALID_CURRENCY_CODE: Currency code must be 3 uppercase letters.");
        }
        if ("CNY".equalsIgnoreCase(currencyCode)) {
            throw new IllegalArgumentException("CANNOT_REVALUE_BASE_CURRENCY: Base currency CNY cannot be revalued as foreign monetary item.");
        }
        if (closingRate == null || closingRate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("INVALID_CLOSING_RATE: Closing rate must be positive.");
        }
        BigDecimal fb = foreignBalance.setScale(8, RoundingMode.HALF_UP);
        BigDecimal cny = carryingCnyBalance.setScale(8, RoundingMode.HALF_UP);
        BigDecimal rate = closingRate.setScale(12, RoundingMode.HALF_UP);

        BigDecimal restated = fb.multiply(rate).setScale(8, RoundingMode.HALF_UP);
        BigDecimal gainLoss = restated.subtract(cny).setScale(8, RoundingMode.HALF_UP);

        return new FxRevaluationItem(
                id,
                planId,
                accountCode,
                accountName,
                currencyCode,
                fb,
                cny,
                rate,
                restated,
                gainLoss,
                exchangeGainLossAccount
        );
    }

    /**
     * ORG04: Calculates tax FX difference worksheet item.
     * Tracks divergence between accounting exchange rate and statutory tax exchange rate.
     */
    public static FxTaxWorksheetItem calculateTaxFxDifference(
            UUID id,
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
        if (originalAmount == null || accountingFxRate == null || taxFxRate == null) {
            throw new IllegalArgumentException("AMOUNTS_REQUIRED: Original amount and FX rates are required.");
        }
        if (accountingFxRate.compareTo(BigDecimal.ZERO) <= 0 || taxFxRate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("INVALID_FX_RATE: FX rates must be positive.");
        }

        BigDecimal orig = originalAmount.setScale(8, RoundingMode.HALF_UP);
        BigDecimal accRate = accountingFxRate.setScale(12, RoundingMode.HALF_UP);
        BigDecimal txRate = taxFxRate.setScale(12, RoundingMode.HALF_UP);

        BigDecimal accCny = orig.multiply(accRate).setScale(8, RoundingMode.HALF_UP);
        BigDecimal txCny = orig.multiply(txRate).setScale(8, RoundingMode.HALF_UP);
        BigDecimal diff = txCny.subtract(accCny).setScale(8, RoundingMode.HALF_UP);

        return new FxTaxWorksheetItem(
                id,
                tenantId,
                bookId,
                periodId,
                transactionRef,
                originalCurrency,
                orig,
                accRate,
                accCny,
                taxFxRateType,
                txRate,
                txCny,
                diff,
                explanation,
                java.time.OffsetDateTime.now()
        );
    }

    /**
     * ORG05: Resolves tax obligations for an entity given taxpayer type, CIT mode, and calendar templates.
     * Enforces the critical rule: SMALL_SCALE VAT status does NOT automatically confer SMALL_MICRO_PROFIT CIT 5% rate!
     */
    public static List<ResolvedTaxObligation> resolveTaxObligations(
            List<TaxCatalogRule> rules,
            List<TaxCalendarTemplate> calendars,
            String taxpayerType,
            String citMode,
            boolean isSmallMicroEnterprise,
            int taxYear,
            int periodMonth
    ) {
        List<ResolvedTaxObligation> resolved = new ArrayList<>();
        Map<String, TaxCalendarTemplate> calendarMap = new HashMap<>();
        for (TaxCalendarTemplate cal : calendars) {
            if (cal.taxYear() == taxYear && cal.periodMonth() == periodMonth) {
                calendarMap.put(cal.taxCategory(), cal);
            }
        }

        for (TaxCatalogRule rule : rules) {
            if (!rule.isActive()) continue;

            // Match taxpayer type
            if (!"ALL".equals(rule.applicableTaxpayerType()) && !rule.applicableTaxpayerType().equals(taxpayerType)) {
                continue;
            }
            // Match CIT mode
            if (!"NOT_APPLICABLE".equals(rule.applicableCitMode()) && !rule.applicableCitMode().equals(citMode)) {
                continue;
            }

            // Frequency check: monthly vs quarterly vs annual
            boolean frequencyMatch = false;
            if ("MONTHLY".equals(rule.filingFrequency()) || "TRANSACTIONAL".equals(rule.filingFrequency())) {
                frequencyMatch = true;
            } else if ("QUARTERLY".equals(rule.filingFrequency())) {
                // Quarterly filings usually in months 4, 7, 10, 1 (or periods 3, 6, 9, 12)
                frequencyMatch = (periodMonth % 3 == 0);
            } else if ("ANNUALLY".equals(rule.filingFrequency())) {
                // CIT Annual settlement occurs by May 31 (month 5)
                frequencyMatch = (periodMonth == 5);
            }

            if (!frequencyMatch) continue;

            TaxCalendarTemplate cal = calendarMap.get(rule.taxCategory());
            LocalDate deadline;
            if (cal != null) {
                deadline = cal.extendedDeadline();
            } else if ("ANNUALLY".equals(rule.filingFrequency())) {
                deadline = LocalDate.of(taxYear + 1, 5, 31);
            } else {
                int nextMonth = (periodMonth == 12) ? 1 : periodMonth + 1;
                int nextYear = (periodMonth == 12) ? taxYear + 1 : taxYear;
                deadline = LocalDate.of(nextYear, nextMonth, 15);
            }
            boolean isExtended = (cal != null) && !cal.extendedDeadline().equals(cal.statutoryDeadline());

            // Decoupling rule: smallMicroCitApplicable is only true if explicitly certified as small-micro
            boolean smallMicroApplicable = isSmallMicroEnterprise && "CIT_PREPAY".equals(rule.taxCategory());

            resolved.add(new ResolvedTaxObligation(
                    rule.obligationCode(),
                    rule.taxCategory(),
                    rule.taxTypeName(),
                    deadline,
                    rule.defaultRole(),
                    isExtended,
                    smallMicroApplicable
            ));
        }

        return List.copyOf(resolved);
    }
}

package com.lumira.finance.orgfx;

import com.lumira.finance.orgfx.OrgFxModels.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Standalone unit tests for Lane W01 (org-fx) domain rules and calculations.
 * Covers ORG01, ORG02, ORG03, ORG04, and ORG05.
 */
class OrgFxRulesTest {

    @Test
    void testSoDEnforcement() {
        UUID personA = UUID.randomUUID();
        UUID personB = UUID.randomUUID();
        Actor maker = Actor.of(UUID.randomUUID(), personA);
        Actor reviewerDiff = Actor.of(UUID.randomUUID(), personB);
        Actor reviewerSame = Actor.of(UUID.randomUUID(), personA);

        // Different natural persons succeed
        assertDoesNotThrow(() -> OrgFxRules.verifySod(maker, reviewerDiff));

        // Same natural person throws IllegalStateException
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> OrgFxRules.verifySod(maker, reviewerSame));
        assertTrue(ex.getMessage().contains("SOD_VIOLATION"));
    }

    @Test
    void testStandardEligibility_SmallEnterprise() {
        UUID tenantId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        Actor assessor = Actor.of(UUID.randomUUID(), UUID.randomUUID());

        StandardEligibilityAssessment assessment = OrgFxRules.assessStandardEligibility(
                UUID.randomUUID(),
                tenantId,
                entityId,
                false, // not public
                false, // not financial
                false, // not group subsidiary
                150,   // employees <= 300
                new BigDecimal("50000000.00"), // 50M <= 100M
                new BigDecimal("40000000.00"), // 40M <= 100M
                assessor,
                "Qualifies for SMALL accounting standard"
        );

        assertTrue(assessment.eligibleForSmall());
        assertEquals(AccountingStandard.SMALL, assessment.evaluatedStandard());
        assertTrue(assessment.disqualificationReasons().isEmpty());
    }

    @Test
    void testStandardEligibility_DisqualificationReasons() {
        UUID tenantId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        Actor assessor = Actor.of(UUID.randomUUID(), UUID.randomUUID());

        // Listed company
        StandardEligibilityAssessment a1 = OrgFxRules.assessStandardEligibility(
                UUID.randomUUID(), tenantId, entityId,
                true, false, false, 100, new BigDecimal("10000000"), new BigDecimal("10000000"),
                assessor, null
        );
        assertFalse(a1.eligibleForSmall());
        assertEquals(AccountingStandard.CAS, a1.evaluatedStandard());
        assertTrue(a1.disqualificationReasons().stream().anyMatch(s -> s.contains("PUBLICLY_TRADED")));

        // Exceeded revenue & assets & staff
        StandardEligibilityAssessment a2 = OrgFxRules.assessStandardEligibility(
                UUID.randomUUID(), tenantId, entityId,
                false, false, false, 500, new BigDecimal("150000000"), new BigDecimal("200000000"),
                assessor, null
        );
        assertFalse(a2.eligibleForSmall());
        assertEquals(AccountingStandard.CAS, a2.evaluatedStandard());
        assertEquals(3, a2.disqualificationReasons().size());
    }

    @Test
    void testStandardTransition_DowngradeProhibited() {
        Actor maker = Actor.of(UUID.randomUUID(), UUID.randomUUID());
        Actor reviewer = Actor.of(UUID.randomUUID(), UUID.randomUUID());

        // Attempting to downgrade from CAS to SMALL when ineligible must fail
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                OrgFxRules.validateStandardTransition(
                        AccountingStandard.CAS,
                        AccountingStandard.SMALL,
                        false, // not eligible
                        maker,
                        reviewer
                )
        );
        assertTrue(ex.getMessage().contains("STANDARD_DOWNGRADE_PROHIBITED"));

        // Valid downgrade when eligible with SoD
        assertDoesNotThrow(() ->
                OrgFxRules.validateStandardTransition(
                        AccountingStandard.CAS,
                        AccountingStandard.SMALL,
                        true, // eligible
                        maker,
                        reviewer
                )
        );
    }

    @Test
    void testMultiDateValidation_SuccessAndSpanExplanation() {
        LocalDate now = LocalDate.now();
        // Valid case within 30 days
        MultiDateContext ctx1 = new MultiDateContext(
                now.minusDays(10),
                now.minusDays(5),
                now,
                now.minusDays(5),
                "OPEN",
                null
        );
        MultiDateValidationResult res1 = OrgFxRules.validateMultiDates(ctx1);
        assertTrue(res1.isValid());
        assertTrue(res1.accountingPeriodOpen());
        assertFalse(res1.requiresExplanation());
        assertEquals(10, res1.dateSpanDays());

        // Span > 90 days without justification fails
        MultiDateContext ctx2 = new MultiDateContext(
                now.minusDays(100),
                now.minusDays(10),
                now,
                now.minusDays(10),
                "OPEN",
                null
        );
        MultiDateValidationResult res2 = OrgFxRules.validateMultiDates(ctx2);
        assertFalse(res2.isValid());
        assertTrue(res2.requiresExplanation());
        assertTrue(res2.validationErrors().stream().anyMatch(e -> e.contains("EXPLANATION_REQUIRED")));

        // Span > 90 days with justification succeeds
        MultiDateContext ctx3 = new MultiDateContext(
                now.minusDays(100),
                now.minusDays(10),
                now,
                now.minusDays(10),
                "OPEN",
                "Contract milestones verified after multi-month acceptance testing"
        );
        MultiDateValidationResult res3 = OrgFxRules.validateMultiDates(ctx3);
        assertTrue(res3.isValid());
    }

    @Test
    void testMultiDateValidation_ClosedPeriodRejected() {
        LocalDate now = LocalDate.now();
        MultiDateContext ctx = new MultiDateContext(
                now.minusDays(10),
                now.minusDays(5),
                now,
                now.minusDays(5),
                "CLOSED",
                null
        );
        MultiDateValidationResult res = OrgFxRules.validateMultiDates(ctx);
        assertFalse(res.isValid());
        assertFalse(res.accountingPeriodOpen());
        assertTrue(res.validationErrors().stream().anyMatch(e -> e.contains("ACCOUNTING_PERIOD_CLOSED")));
    }

    @Test
    void testFxLineTranslationPrecision() {
        // Original: USD 1,234.56780000
        // Rate: 7.123456789012 (scale 12)
        BigDecimal orig = new BigDecimal("1234.56780000");
        BigDecimal rate = new BigDecimal("7.123456789012");

        FxLineTranslationResult result = OrgFxRules.translateFxLine("USD", orig, rate);
        assertEquals("USD", result.originalCurrency());
        assertEquals(orig, result.originalAmount());
        assertEquals(rate, result.rateValue());

        // exact: 1234.56780000 * 7.123456789012 = 8794.39037641 (scale 8)
        assertEquals(new BigDecimal("8794.39037641"), result.functionalCnyExact());
        // quantized: 8794.39
        assertEquals(new BigDecimal("8794.39"), result.functionalCnyQuantized());
        // rounding difference: 0.00037641
        assertEquals(new BigDecimal("0.00037641"), result.roundingDifference());

        // exact equals quantized + difference
        assertEquals(result.functionalCnyExact(), result.functionalCnyQuantized().add(result.roundingDifference()));
    }

    @Test
    void testFxRevaluationCalculation() {
        UUID planId = UUID.randomUUID();
        // 50,000.00 USD carrying 355,000.00 CNY. Closing rate = 7.200000000000
        BigDecimal foreignBalance = new BigDecimal("50000.00000000");
        BigDecimal carryingCny = new BigDecimal("355000.00000000");
        BigDecimal closingRate = new BigDecimal("7.200000000000");

        FxRevaluationItem item = OrgFxRules.calculateRevaluationItem(
                UUID.randomUUID(),
                planId,
                "1002.02",
                "Bank Deposit USD",
                "USD",
                foreignBalance,
                carryingCny,
                closingRate,
                "6603" // 财务费用-汇兑损益
        );

        // Restated = 50000 * 7.2 = 360,000.00000000
        assertEquals(new BigDecimal("360000.00000000"), item.restatedCnyBalance());
        // Gain/Loss = 360,000 - 355,000 = +5,000.00000000
        assertEquals(new BigDecimal("5000.00000000"), item.gainLossAmount());
    }

    @Test
    void testTaxFxDifferenceWorksheet() {
        // Transaction 10,000 USD
        // Accounting spot rate: 7.220000000000 -> 72,200.00 CNY
        // Tax statutory midpoint rate (first day of month): 7.180000000000 -> 71,800.00 CNY
        // Difference = 71,800 - 72,200 = -400.00 CNY
        FxTaxWorksheetItem item = OrgFxRules.calculateTaxFxDifference(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "INV-2026-001",
                "USD",
                new BigDecimal("10000.00000000"),
                new BigDecimal("7.220000000000"),
                "MONTH_FIRST_DAY_MID",
                new BigDecimal("7.180000000000"),
                "Monthly midpoint rate elected under CIT regulation"
        );

        assertEquals(new BigDecimal("72200.00000000"), item.accountingCnyAmount());
        assertEquals(new BigDecimal("71800.00000000"), item.taxCnyAmount());
        assertEquals(new BigDecimal("-400.00000000"), item.taxAccountingDiff());
    }

    @Test
    void testTaxObligationResolutionAndDecoupling() {
        List<TaxCatalogRule> rules = List.of(
                new TaxCatalogRule("VAT_GENERAL_MONTHLY", "VAT", "增值税一般纳税人申报", "MONTHLY", "SELF", "VAT_GEN", "GENERAL_TAXPAYER", "NOT_APPLICABLE", true),
                new TaxCatalogRule("VAT_SMALL_QUARTERLY", "VAT", "增值税小规模纳税人申报", "QUARTERLY", "SELF", "VAT_SML", "SMALL_SCALE", "NOT_APPLICABLE", true),
                new TaxCatalogRule("CIT_PREPAY_QUARTERLY", "CIT_PREPAY", "企业所得税预缴", "QUARTERLY", "SELF", "CIT_PRE", "ALL", "AUDIT_COLLECTION", true),
                new TaxCatalogRule("IIT_MONTHLY", "IIT", "个人所得税扣缴", "MONTHLY", "WITHHOLDING", "IIT_WITH", "ALL", "NOT_APPLICABLE", true)
        );

        List<TaxCalendarTemplate> calendars = List.of(
                new TaxCalendarTemplate(UUID.randomUUID(), "CN_NATIONAL", 2026, 3, "VAT", LocalDate.of(2026, 4, 15), LocalDate.of(2026, 4, 18), "HOLIDAY", "REF-01"),
                new TaxCalendarTemplate(UUID.randomUUID(), "CN_NATIONAL", 2026, 3, "CIT_PREPAY", LocalDate.of(2026, 4, 15), LocalDate.of(2026, 4, 18), "HOLIDAY", "REF-01"),
                new TaxCalendarTemplate(UUID.randomUUID(), "CN_NATIONAL", 2026, 3, "IIT", LocalDate.of(2026, 4, 15), LocalDate.of(2026, 4, 15), null, "REF-01")
        );

        // Case 1: Small-scale taxpayer, NOT small-micro CIT
        List<ResolvedTaxObligation> res1 = OrgFxRules.resolveTaxObligations(
                rules, calendars, "SMALL_SCALE", "AUDIT_COLLECTION", false, 2026, 3
        );

        assertTrue(res1.stream().anyMatch(r -> "VAT_SMALL_QUARTERLY".equals(r.obligationCode())));
        assertFalse(res1.stream().anyMatch(r -> "VAT_GENERAL_MONTHLY".equals(r.obligationCode())));
        ResolvedTaxObligation cit = res1.stream().filter(r -> "CIT_PREPAY_QUARTERLY".equals(r.obligationCode())).findFirst().orElseThrow();
        assertFalse(cit.smallMicroCitApplicable(), "SMALL_SCALE VAT must NOT imply small-micro CIT rate!");
        assertTrue(cit.isExtended());
        assertEquals(LocalDate.of(2026, 4, 18), cit.deadline());

        // Case 2: General taxpayer, with explicit small-micro CIT certification
        List<ResolvedTaxObligation> res2 = OrgFxRules.resolveTaxObligations(
                rules, calendars, "GENERAL_TAXPAYER", "AUDIT_COLLECTION", true, 2026, 3
        );
        assertTrue(res2.stream().anyMatch(r -> "VAT_GENERAL_MONTHLY".equals(r.obligationCode())));
        ResolvedTaxObligation cit2 = res2.stream().filter(r -> "CIT_PREPAY_QUARTERLY".equals(r.obligationCode())).findFirst().orElseThrow();
        assertTrue(cit2.smallMicroCitApplicable(), "Explicit small-micro status enables CIT concession rate.");
    }

    @Test
    void testLifecycleStateMachineTransitions() {
        Actor maker = Actor.of(UUID.randomUUID(), UUID.randomUUID());
        Actor reviewer = Actor.of(UUID.randomUUID(), UUID.randomUUID());

        // 1. Valid transitions
        assertDoesNotThrow(() -> OrgFxRules.validateLifecycleTransition(
                EntityStatus.PREPARING, EntityStatus.PENDING_CONFIRMATION, maker, reviewer));
        assertDoesNotThrow(() -> OrgFxRules.validateLifecycleTransition(
                EntityStatus.PENDING_CONFIRMATION, EntityStatus.ACTIVE, maker, reviewer));
        assertDoesNotThrow(() -> OrgFxRules.validateLifecycleTransition(
                EntityStatus.PENDING_CONFIRMATION, EntityStatus.PREPARING, maker, reviewer));
        assertDoesNotThrow(() -> OrgFxRules.validateLifecycleTransition(
                EntityStatus.ACTIVE, EntityStatus.SUSPENDED, maker, reviewer));
        assertDoesNotThrow(() -> OrgFxRules.validateLifecycleTransition(
                EntityStatus.SUSPENDED, EntityStatus.ACTIVE, maker, reviewer));
        assertDoesNotThrow(() -> OrgFxRules.validateLifecycleTransition(
                EntityStatus.ACTIVE, EntityStatus.TERMINATED, maker, reviewer));
        assertDoesNotThrow(() -> OrgFxRules.validateLifecycleTransition(
                EntityStatus.SUSPENDED, EntityStatus.TERMINATED, maker, reviewer));

        // 2. Self-transition prohibited
        IllegalStateException sameEx = assertThrows(IllegalStateException.class, () ->
                OrgFxRules.validateLifecycleTransition(EntityStatus.ACTIVE, EntityStatus.ACTIVE, maker, reviewer));
        assertTrue(sameEx.getMessage().contains("SAME_STATUS"));

        // 3. Terminal state cannot transition anywhere
        IllegalStateException termEx = assertThrows(IllegalStateException.class, () ->
                OrgFxRules.validateLifecycleTransition(EntityStatus.TERMINATED, EntityStatus.ACTIVE, maker, reviewer));
        assertTrue(termEx.getMessage().contains("TERMINATED_IS_TERMINAL"));

        // 4. Illegal jumps
        IllegalStateException jumpEx = assertThrows(IllegalStateException.class, () ->
                OrgFxRules.validateLifecycleTransition(EntityStatus.PREPARING, EntityStatus.ACTIVE, maker, reviewer));
        assertTrue(jumpEx.getMessage().contains("ILLEGAL_LIFECYCLE_TRANSITION"));

        // 5. SoD violation in lifecycle transition
        Actor samePerson = Actor.of(UUID.randomUUID(), maker.personId());
        IllegalStateException sodEx = assertThrows(IllegalStateException.class, () ->
                OrgFxRules.validateLifecycleTransition(EntityStatus.ACTIVE, EntityStatus.SUSPENDED, maker, samePerson));
        assertTrue(sodEx.getMessage().contains("SOD_VIOLATION"));
    }

    @Test
    void testEligibilityNegativeAndNullValidations() {
        UUID tenantId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        Actor assessor = Actor.of(UUID.randomUUID(), UUID.randomUUID());

        // Negative employees
        assertThrows(IllegalArgumentException.class, () ->
                OrgFxRules.assessStandardEligibility(UUID.randomUUID(), tenantId, entityId,
                        false, false, false, -5, new BigDecimal("100"), new BigDecimal("100"), assessor, null));

        // Negative revenue
        assertThrows(IllegalArgumentException.class, () ->
                OrgFxRules.assessStandardEligibility(UUID.randomUUID(), tenantId, entityId,
                        false, false, false, 50, new BigDecimal("-100"), new BigDecimal("100"), assessor, null));

        // Negative assets
        assertThrows(IllegalArgumentException.class, () ->
                OrgFxRules.assessStandardEligibility(UUID.randomUUID(), tenantId, entityId,
                        false, false, false, 50, new BigDecimal("100"), new BigDecimal("-100"), assessor, null));

        // Null assessor
        assertThrows(IllegalArgumentException.class, () ->
                OrgFxRules.assessStandardEligibility(UUID.randomUUID(), tenantId, entityId,
                        false, false, false, 50, new BigDecimal("100"), new BigDecimal("100"), null, null));
    }

    @Test
    void testMultiDateFutureAndHistoricalBoundaries() {
        LocalDate today = LocalDate.now();
        // Far future date (> 366 days)
        MultiDateContext futureCtx = new MultiDateContext(
                today,
                today,
                today.plusDays(400),
                today,
                "OPEN",
                null
        );
        MultiDateValidationResult resFuture = OrgFxRules.validateMultiDates(futureCtx);
        assertFalse(resFuture.isValid());
        assertTrue(resFuture.validationErrors().stream().anyMatch(e -> e.contains("HORIZON_EXCEEDED")));

        // Pre-1900 date
        MultiDateContext pastCtx = new MultiDateContext(
                LocalDate.of(1899, 12, 31),
                today,
                today,
                today,
                "OPEN",
                null
        );
        MultiDateValidationResult resPast = OrgFxRules.validateMultiDates(pastCtx);
        assertFalse(resPast.isValid());
        assertTrue(resPast.validationErrors().stream().anyMatch(e -> e.contains("DATE_HISTORICAL_BOUNDARY_EXCEEDED")));
    }

    @Test
    void testFxTranslationBaseCurrencyAndInvalidRates() {
        // Rate <= 0 throws
        assertThrows(IllegalArgumentException.class, () ->
                OrgFxRules.translateFxLine("USD", new BigDecimal("100"), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () ->
                OrgFxRules.translateFxLine("USD", new BigDecimal("100"), new BigDecimal("-1.5")));

        // Invalid currency code
        assertThrows(IllegalArgumentException.class, () ->
                OrgFxRules.translateFxLine("US", new BigDecimal("100"), new BigDecimal("7.0")));
        assertThrows(IllegalArgumentException.class, () ->
                OrgFxRules.translateFxLine("USDT", new BigDecimal("100"), new BigDecimal("7.0")));

        // CNY with rate != 1.0 throws
        IllegalArgumentException cnyEx = assertThrows(IllegalArgumentException.class, () ->
                OrgFxRules.translateFxLine("CNY", new BigDecimal("100"), new BigDecimal("1.05")));
        assertTrue(cnyEx.getMessage().contains("BASE_CURRENCY_RATE_MUST_BE_ONE"));

        // CNY with rate == 1.0 succeeds
        FxLineTranslationResult cnyRes = OrgFxRules.translateFxLine("CNY", new BigDecimal("500.50"), BigDecimal.ONE);
        assertEquals(new BigDecimal("500.50000000"), cnyRes.functionalCnyExact());
        assertEquals(new BigDecimal("500.50"), cnyRes.functionalCnyQuantized());
        assertEquals(new BigDecimal("0.00000000"), cnyRes.roundingDifference());
    }

    @Test
    void testFxRevaluationRejectsBaseCurrencyAndInvalidRates() {
        UUID planId = UUID.randomUUID();
        // CNY revaluation rejected
        IllegalArgumentException cnyRevalEx = assertThrows(IllegalArgumentException.class, () ->
                OrgFxRules.calculateRevaluationItem(
                        UUID.randomUUID(), planId, "1001", "Cash", "CNY",
                        new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("1.0"), "6603"
                ));
        assertTrue(cnyRevalEx.getMessage().contains("CANNOT_REVALUE_BASE_CURRENCY"));

        // Zero / negative closing rate rejected
        assertThrows(IllegalArgumentException.class, () ->
                OrgFxRules.calculateRevaluationItem(
                        UUID.randomUUID(), planId, "1002", "Bank", "USD",
                        new BigDecimal("100"), new BigDecimal("700"), BigDecimal.ZERO, "6603"
                ));
    }

    @Test
    void testConversionBridgeCalculationAndValidation() {
        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        Actor maker = Actor.of(UUID.randomUUID(), UUID.randomUUID());
        Actor reviewer = Actor.of(UUID.randomUUID(), UUID.randomUUID());

        List<ConversionBridgeItem> items = List.of(
                new ConversionBridgeItem(
                        UUID.randomUUID(), null, "1501", "Fixed Assets",
                        new BigDecimal("1000000.00000000"),
                        new BigDecimal("50000.00000000"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "Component depreciation adjustment under CAS",
                        "OPENING_RETAINED_EARNINGS"
                ),
                new ConversionBridgeItem(
                        UUID.randomUUID(), null, "4001", "Retained Earnings",
                        new BigDecimal("500000.00000000"),
                        BigDecimal.ZERO,
                        new BigDecimal("50000.00000000"),
                        BigDecimal.ZERO,
                        "Corresponding retained earnings adjustment",
                        "OPENING_RETAINED_EARNINGS"
                )
        );

        ConversionBridge bridge = OrgFxRules.calculateConversionBridge(
                UUID.randomUUID(),
                tenantId,
                bookId,
                AccountingStandard.SMALL,
                AccountingStandard.CAS,
                2026,
                false,
                maker,
                reviewer,
                "Company scale surpassed SME threshold; migrating to CAS",
                items
        );

        assertEquals("APPROVED", bridge.status());
        assertEquals(new BigDecimal("50000.00000000"), bridge.totalAdjustmentDebit());
        assertEquals(new BigDecimal("50000.00000000"), bridge.totalAdjustmentCredit());
        assertEquals(2, bridge.items().size());
        assertEquals(new BigDecimal("1050000.00000000"), bridge.items().get(0).adjustedBalance());
        assertEquals(new BigDecimal("450000.00000000"), bridge.items().get(1).adjustedBalance());
    }
}


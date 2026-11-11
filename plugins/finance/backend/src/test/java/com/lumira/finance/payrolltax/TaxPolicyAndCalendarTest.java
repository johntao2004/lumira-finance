package com.lumira.finance.payrolltax;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TaxPolicyAndCalendarTest {

    private final UUID entityId = UUID.randomUUID();
    private final UUID personId = UUID.randomUUID();

    @Test
    @DisplayName("TAX01: Small-low-profit CIT relief throws when attestation is missing")
    void testCitSmallLowProfitRequiresAttestation() {
        var pack = new TaxPolicyRuleEngine.PolicyPack(
                "CIT_SMALL_LOW_PROFIT_2026_V1",
                "CIT_SMALL_LOW_PROFIT",
                "1.0.0",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "APPROVED",
                true, // attestationRequired = true
                Map.of("effective_rate", "0.05")
        );

        // Without attestation -> Throws IllegalStateException
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                TaxPolicyRuleEngine.evaluateSmallLowProfitCit(
                        pack,
                        null, // missing attestation!
                        entityId,
                        LocalDate.of(2026, 5, 10),
                        new BigDecimal("1000000.00"),
                        new BigDecimal("150"),
                        new BigDecimal("20000000.00")
                )
        );
        assertTrue(ex.getMessage().contains("TAX_QUALIFICATION_ATTESTATION_REQUIRED"));
    }

    @Test
    @DisplayName("TAX01: Small-low-profit CIT relief succeeds when valid attestation provided")
    void testCitSmallLowProfitWithValidAttestation() {
        var pack = new TaxPolicyRuleEngine.PolicyPack(
                "CIT_SMALL_LOW_PROFIT_2026_V1",
                "CIT_SMALL_LOW_PROFIT",
                "1.0.0",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "APPROVED",
                true,
                Map.of("effective_rate", "0.05")
        );

        var attestation = new TaxPolicyRuleEngine.Attestation(
                UUID.randomUUID(),
                "CIT_SMALL_LOW_PROFIT_2026_V1",
                entityId,
                "SMALL_LOW_PROFIT",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                personId,
                "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90",
                Map.of("certified", true)
        );

        // Taxable profit 2,000,000 -> standard 25% is 500,000 -> effective 5% (20% on 25% base) = 100,000
        var res = TaxPolicyRuleEngine.evaluateSmallLowProfitCit(
                pack,
                attestation,
                entityId,
                LocalDate.of(2026, 6, 15),
                new BigDecimal("2000000.00"),
                new BigDecimal("200"),
                new BigDecimal("30000000.00")
        );

        assertTrue(res.preferentialApplied());
        assertEquals(new BigDecimal("500000.00"), res.statutoryTax());
        assertEquals(new BigDecimal("100000.00"), res.payableTax());
        assertEquals(new BigDecimal("400000.00"), res.reliefTax());
        assertNotNull(res.explainTree());
    }

    @Test
    @DisplayName("TAX05: Filing calendar weekend deferral (Saturday -> Monday)")
    void testCalendarWeekendDeferral() {
        // 2026-08-15 is a Saturday -> Adjusted deadline should be Monday 2026-08-17
        var deadline = TaxFilingCalendarGenerator.generate(
                "VAT",
                2026,
                8,
                Map.of(),
                "CAL_CN_2026_V1"
        );
        assertEquals(LocalDate.of(2026, 8, 15), deadline.baseDeadline());
        assertEquals(LocalDate.of(2026, 8, 17), deadline.adjustedDeadline());
        assertTrue(deadline.isDeferred());
        assertTrue(deadline.deferralReason().contains("SATURDAY_WEEKEND_DEFERRAL"));
    }

    @Test
    @DisplayName("TAX05: Filing calendar holiday pack deferral (e.g. Spring Festival)")
    void testCalendarHolidayOverrideDeferral() {
        // February 15th 2026 is Sunday, and also in Spring Festival holiday
        Map<LocalDate, String> holidays = Map.of(
                LocalDate.of(2026, 2, 16), "SPRING_FESTIVAL_HOLIDAY",
                LocalDate.of(2026, 2, 17), "SPRING_FESTIVAL_HOLIDAY",
                LocalDate.of(2026, 2, 18), "SPRING_FESTIVAL_HOLIDAY",
                LocalDate.of(2026, 2, 19), "SPRING_FESTIVAL_HOLIDAY",
                LocalDate.of(2026, 2, 20), "SPRING_FESTIVAL_HOLIDAY",
                LocalDate.of(2026, 2, 21), "SPRING_FESTIVAL_HOLIDAY",
                LocalDate.of(2026, 2, 22), "SPRING_FESTIVAL_HOLIDAY",
                LocalDate.of(2026, 2, 23), "SPRING_FESTIVAL_DEFERRAL"
        );
        var deadline = TaxFilingCalendarGenerator.generate(
                "VAT",
                2026,
                2,
                holidays,
                "CAL_CN_2026_V1"
        );
        assertEquals(LocalDate.of(2026, 2, 15), deadline.baseDeadline());
        assertEquals(LocalDate.of(2026, 2, 24), deadline.adjustedDeadline());
        assertTrue(deadline.isDeferred());
    }

    @Test
    @DisplayName("TAX05: Form cell lock prevents mutation after locking")
    void testFormCellLockImmutability() {
        var lockState = new FormVersionCellLock.FormLockState(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "VAT_GENERAL",
                1,
                false,
                null,
                null,
                "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90",
                Map.of("col1", "100.00")
        );

        // Initial state is editable
        assertDoesNotThrow(lockState::assertEditable);

        // Lock form
        var lockedState = lockState.lock(personId, "b1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90");
        assertTrue(lockedState.isLocked());

        // Subsequent edit throws FORM_VERSION_LOCKED
        IllegalStateException ex = assertThrows(IllegalStateException.class, lockedState::assertEditable);
        assertTrue(ex.getMessage().contains("FORM_VERSION_LOCKED"));
    }

    @Test
    @DisplayName("TAX05: Mandatory schedule attachment checker blocks missing schedules")
    void testMandatoryScheduleChecker() {
        // VAT general declaration with export sales > 0 and input tax deduction > 0
        Map<String, BigDecimal> figures = Map.of(
                "exportSales", new BigDecimal("50000.00"),
                "inputDeduction", new BigDecimal("12000.00"),
                "taxReduction", BigDecimal.ZERO
        );

        // Attached only Schedule 1 (Export), but missing Schedule 2 (Input deduction)
        Set<String> attached = Set.of("ATTACHMENT_VAT_EXPORT");

        var result = MandatoryScheduleChecker.validateAttachments("VAT_GENERAL", figures, attached);
        assertFalse(result.passed());
        assertEquals(2, result.requiredSchedules().size());
        assertEquals(1, result.missingSchedules().size());
        assertEquals("ATTACHMENT_VAT_INPUT_DEDUCTION", result.missingSchedules().get(0).scheduleCode());

        // assertAllPresent throws
        IllegalStateException ex = assertThrows(IllegalStateException.class, result::assertAllPresent);
        assertTrue(ex.getMessage().contains("MISSING_MANDATORY_SCHEDULE_ATTACHMENT"));
    }

    @Test
    @DisplayName("TAX01: Small-low-profit CIT relief with zero or loss taxable profit clamps tax to 0")
    void testCitSmallLowProfitZeroOrLossProfit() {
        var pack = new TaxPolicyRuleEngine.PolicyPack(
                "CIT_SMALL_LOW_PROFIT_2026_V1",
                "CIT_SMALL_LOW_PROFIT",
                "1.0.0",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "APPROVED",
                true,
                Map.of("effective_rate", "0.05")
        );

        var attestation = new TaxPolicyRuleEngine.Attestation(
                UUID.randomUUID(),
                "CIT_SMALL_LOW_PROFIT_2026_V1",
                entityId,
                "SMALL_LOW_PROFIT",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                personId,
                "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90",
                Map.of("certified", true)
        );

        // Loss of -50,000 -> payable tax should be 0.00, statutory tax 0.00
        var res = TaxPolicyRuleEngine.evaluateSmallLowProfitCit(
                pack,
                attestation,
                entityId,
                LocalDate.of(2026, 6, 15),
                new BigDecimal("-50000.00"),
                new BigDecimal("50"),
                new BigDecimal("10000000.00")
        );

        assertFalse(res.preferentialApplied());
        assertEquals(new BigDecimal("0.00"), res.statutoryTax());
        assertEquals(new BigDecimal("0.00"), res.payableTax());
        assertEquals(new BigDecimal("0.00"), res.reliefTax());
    }

    @Test
    @DisplayName("TAX01: R&D super-deduction requires attestation and validates non-negative expenses")
    void testRdSuperDeductionAttestationAndValidation() {
        var pack = new TaxPolicyRuleEngine.PolicyPack(
                "CIT_RD_SUPER_DEDUCTION_2026_V1",
                "CIT_RD",
                "1.0.0",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                "f3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "APPROVED",
                true,
                Map.of("super_deduction_rate", "1.00", "applicable_cit_rate", "0.25")
        );

        // Missing attestation -> throws
        assertThrows(IllegalStateException.class, () ->
                TaxPolicyRuleEngine.evaluateRdSuperDeduction(
                        pack,
                        null,
                        entityId,
                        LocalDate.of(2026, 10, 15),
                        new BigDecimal("2000000.00"),
                        new BigDecimal("1.00")
                )
        );

        var attestation = new TaxPolicyRuleEngine.Attestation(
                UUID.randomUUID(),
                "CIT_RD_SUPER_DEDUCTION_2026_V1",
                entityId,
                "RD_QUALIFIED",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                personId,
                "c1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90",
                Map.of("auxiliary_ledger_kept", true)
        );

        // Negative expense -> throws
        assertThrows(IllegalArgumentException.class, () ->
                TaxPolicyRuleEngine.evaluateRdSuperDeduction(
                        pack,
                        attestation,
                        entityId,
                        LocalDate.of(2026, 10, 15),
                        new BigDecimal("-1000.00"),
                        new BigDecimal("1.00")
                )
        );

        // Valid 2,000,000 eligible R&D expense -> 100% additional deduction = 2,000,000 -> tax relief = 500,000
        var res = TaxPolicyRuleEngine.evaluateRdSuperDeduction(
                pack,
                attestation,
                entityId,
                LocalDate.of(2026, 10, 15),
                new BigDecimal("2000000.00"),
                new BigDecimal("1.00")
        );
        assertTrue(res.preferentialApplied());
        assertEquals(new BigDecimal("2000000.00"), res.reliefTax());
        assertNotNull(res.explainTree());
        assertFalse(res.explainTree().steps().isEmpty());
    }
}

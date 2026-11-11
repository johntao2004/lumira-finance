package com.lumira.finance.payrolltax;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class LaborRemunerationEngineTest {

    private final UUID personId = UUID.randomUUID();

    @Test
    @DisplayName("PAY04: Labor service progressive withholding with 800 deduction (gross <= 4000)")
    void testLaborServiceBelow4000() {
        // Gross 3,000 -> deduction 800 -> taxable 2,200 -> 20% -> tax 440.00
        var input = new LaborRemunerationEngine.LaborInput(
                personId,
                LaborRemunerationEngine.RemunerationType.LABOR_SERVICE,
                new BigDecimal("3000.00"),
                true,
                new BigDecimal("0.07"),
                false,
                false,
                false,
                null
        );
        var res = LaborRemunerationEngine.calculate(input);
        assertEquals(new BigDecimal("800.00"), res.deductionAmount());
        assertEquals(new BigDecimal("2200.00"), res.taxableIncome());
        assertEquals(new BigDecimal("0.20"), res.iitRate());
        assertEquals(new BigDecimal("440.00"), res.iitWithholding());
        // VAT: 3,000 >= 1,000 -> 1% relief -> VAT = 30.00
        assertEquals(new BigDecimal("30.00"), res.vatWithheld());
        // City surtax: 30 * 0.07 = 2.10
        assertEquals(new BigDecimal("2.10"), res.citySurtax());
        // Net payable = 3,000 - 440 - 30 - 2.10 = 2,527.90
        assertEquals(new BigDecimal("2527.90"), res.netPayable());
    }

    @Test
    @DisplayName("PAY04: Labor service progressive withholding with 20% deduction (gross > 4000)")
    void testLaborServiceAbove4000() {
        // Gross 30,000 -> deduction 20% = 6,000 -> taxable 24,000 -> 30% (quick 2000) -> tax 24000*0.3 - 2000 = 5,200.00
        var input = new LaborRemunerationEngine.LaborInput(
                personId,
                LaborRemunerationEngine.RemunerationType.LABOR_SERVICE,
                new BigDecimal("30000.00"),
                true,
                new BigDecimal("0.07"),
                false,
                false,
                false,
                null
        );
        var res = LaborRemunerationEngine.calculate(input);
        assertEquals(new BigDecimal("6000.00"), res.deductionAmount());
        assertEquals(new BigDecimal("24000.00"), res.taxableIncome());
        assertEquals(new BigDecimal("0.30"), res.iitRate());
        assertEquals(new BigDecimal("2000.00"), res.iitQuickDeduction());
        assertEquals(new BigDecimal("5200.00"), res.iitWithholding());
    }

    @Test
    @DisplayName("PAY04: Author remuneration statutory 30% reduction (taxable * 70%)")
    void testAuthorRemuneration() {
        // Gross 10,000 -> deduction 20% = 2,000 -> preliminary 8,000 -> author 30% reduction -> taxable 8000 * 0.70 = 5,600
        // Flat 20% rate -> tax = 5,600 * 0.20 = 1,120.00
        var input = new LaborRemunerationEngine.LaborInput(
                personId,
                LaborRemunerationEngine.RemunerationType.AUTHOR_REMUNERATION,
                new BigDecimal("10000.00"),
                true,
                new BigDecimal("0.07"),
                false,
                false,
                false,
                null
        );
        var res = LaborRemunerationEngine.calculate(input);
        assertEquals(new BigDecimal("2000.00"), res.deductionAmount());
        assertEquals(new BigDecimal("5600.00"), res.taxableIncome());
        assertEquals(new BigDecimal("0.20"), res.iitRate());
        assertEquals(new BigDecimal("1120.00"), res.iitWithholding());
    }

    @Test
    @DisplayName("PAY04: Royalty remuneration flat 20%")
    void testRoyaltyRemuneration() {
        // Gross 10,000 -> deduction 2,000 -> taxable 8,000 -> 20% flat -> tax 1,600.00
        var input = new LaborRemunerationEngine.LaborInput(
                personId,
                LaborRemunerationEngine.RemunerationType.ROYALTY,
                new BigDecimal("10000.00"),
                true,
                new BigDecimal("0.07"),
                false,
                false,
                false,
                null
        );
        var res = LaborRemunerationEngine.calculate(input);
        assertEquals(new BigDecimal("2000.00"), res.deductionAmount());
        assertEquals(new BigDecimal("8000.00"), res.taxableIncome());
        assertEquals(new BigDecimal("0.20"), res.iitRate());
        assertEquals(new BigDecimal("1600.00"), res.iitWithholding());
    }

    @Test
    @DisplayName("PAY04: Small-amount VAT exemption when sales < 1,000 CNY")
    void testVatSmallAmountExemption() {
        // Gross 800 -> IIT taxable = 800 - 800 = 0 -> IIT = 0
        // VAT: 800 < 1,000 -> vatExempt = true, vatWithheld = 0
        var input = new LaborRemunerationEngine.LaborInput(
                personId,
                LaborRemunerationEngine.RemunerationType.LABOR_SERVICE,
                new BigDecimal("800.00"),
                true,
                new BigDecimal("0.07"),
                false,
                false,
                false,
                null
        );
        var res = LaborRemunerationEngine.calculate(input);
        assertTrue(res.vatExempt());
        assertEquals(new BigDecimal("0.00"), res.vatWithheld());
        assertEquals(new BigDecimal("0.00"), res.totalSurchargeWithheld());
        assertEquals(new BigDecimal("800.00"), res.netPayable());
    }

    @Test
    @DisplayName("PAY04: Proxy invoice completed suppresses VAT & surtax withholding")
    void testProxyInvoiceSuppressesVatWithholding() {
        var input = new LaborRemunerationEngine.LaborInput(
                personId,
                LaborRemunerationEngine.RemunerationType.LABOR_SERVICE,
                new BigDecimal("5000.00"),
                true,
                new BigDecimal("0.07"),
                false,
                false,
                true,
                "INV-PROXY-20261109-001"
        );
        var res = LaborRemunerationEngine.calculate(input);
        assertTrue(res.proxyInvoiceCompleted());
        assertEquals(new BigDecimal("0.00"), res.vatWithheld());
        assertEquals(new BigDecimal("0.00"), res.totalSurchargeWithheld());
        // Only IIT withheld: 5,000 * 0.8 = 4,000 taxable * 20% = 800.00
        assertEquals(new BigDecimal("800.00"), res.iitWithholding());
        assertEquals(new BigDecimal("4200.00"), res.netPayable());
    }

    @Test
    @DisplayName("PAY04: Gross payment below 800 CNY caps deduction to gross and results in 0 taxable income")
    void testGrossBelow800() {
        var input = new LaborRemunerationEngine.LaborInput(
                personId,
                LaborRemunerationEngine.RemunerationType.LABOR_SERVICE,
                new BigDecimal("500.00"),
                true,
                new BigDecimal("0.07"),
                false,
                false,
                false,
                null
        );
        var res = LaborRemunerationEngine.calculate(input);
        assertEquals(new BigDecimal("500.00"), res.deductionAmount());
        assertEquals(new BigDecimal("0.00"), res.taxableIncome());
        assertEquals(new BigDecimal("0.00"), res.iitWithholding());
        assertTrue(res.vatExempt());
        assertEquals(new BigDecimal("500.00"), res.netPayable());
    }

    @Test
    @DisplayName("PAY04: Proxy invoice without reference throws IllegalArgumentException")
    void testProxyInvoiceWithoutRefThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new LaborRemunerationEngine.LaborInput(
                        personId,
                        LaborRemunerationEngine.RemunerationType.LABOR_SERVICE,
                        new BigDecimal("5000.00"),
                        true,
                        new BigDecimal("0.07"),
                        false,
                        false,
                        true, // proxy completed
                        "   "  // blank ref!
                )
        );
        assertTrue(ex.getMessage().contains("PROXY_INVOICE_REF_REQUIRED"));
    }

    @Test
    @DisplayName("PAY04: Labor service top bracket 40% withholding (> 50,000 taxable)")
    void testLaborServiceTopBracket40() {
        // Gross 80,000 -> deduction 20% = 16,000 -> taxable 64,000 -> 40% (quick 7,000) -> 64000*0.4 - 7000 = 18,600.00
        var input = new LaborRemunerationEngine.LaborInput(
                personId,
                LaborRemunerationEngine.RemunerationType.LABOR_SERVICE,
                new BigDecimal("80000.00"),
                true,
                new BigDecimal("0.07"),
                false,
                false,
                false,
                null
        );
        var res = LaborRemunerationEngine.calculate(input);
        assertEquals(new BigDecimal("16000.00"), res.deductionAmount());
        assertEquals(new BigDecimal("64000.00"), res.taxableIncome());
        assertEquals(new BigDecimal("0.40"), res.iitRate());
        assertEquals(new BigDecimal("7000.00"), res.iitQuickDeduction());
        assertEquals(new BigDecimal("18600.00"), res.iitWithholding());
    }
}

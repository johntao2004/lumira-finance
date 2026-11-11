package com.lumira.finance.payrolltax;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class BonusCalculationEngineTest {

    private final UUID empId = UUID.randomUUID();

    @Test
    @DisplayName("PAY03: Separate monthly quotient across multiple tax brackets")
    void testSeparateMonthlyQuotientBrackets() {
        // Bracket 1: 36,000 CNY -> quotient 3,000 -> 3%, quick 0 -> tax 1,080.00
        var input1 = new BonusCalculationEngine.BonusInput(
                empId,
                new BigDecimal("36000.00"),
                new BigDecimal("100000.00"),
                BonusCalculationEngine.BonusMode.SEPARATE_MONTHLY_QUOTIENT
        );
        var res1 = BonusCalculationEngine.calculate(input1);
        assertEquals(new BigDecimal("3000.00"), res1.monthlyQuotient());
        assertEquals(new BigDecimal("0.03"), res1.applicableRate());
        assertEquals(new BigDecimal("0.00"), res1.quickDeduction());
        assertEquals(new BigDecimal("1080.00"), res1.withholdingTax());
        assertEquals(new BigDecimal("34920.00"), res1.netAmount());

        // Bracket 2: 120,000 CNY -> quotient 10,000 -> 10%, quick 210 -> tax 120000 * 0.10 - 210 = 11,790.00
        var input2 = new BonusCalculationEngine.BonusInput(
                empId,
                new BigDecimal("120000.00"),
                new BigDecimal("100000.00"),
                BonusCalculationEngine.BonusMode.SEPARATE_MONTHLY_QUOTIENT
        );
        var res2 = BonusCalculationEngine.calculate(input2);
        assertEquals(new BigDecimal("10000.00"), res2.monthlyQuotient());
        assertEquals(new BigDecimal("0.10"), res2.applicableRate());
        assertEquals(new BigDecimal("210.00"), res2.quickDeduction());
        assertEquals(new BigDecimal("11790.00"), res2.withholdingTax());
        assertEquals(new BigDecimal("108210.00"), res2.netAmount());

        // Bracket 3: 300,000 CNY -> quotient 25,000 -> 20%, quick 1,410 -> tax 300000 * 0.20 - 1410 = 58,590.00
        var input3 = new BonusCalculationEngine.BonusInput(
                empId,
                new BigDecimal("300000.00"),
                new BigDecimal("100000.00"),
                BonusCalculationEngine.BonusMode.SEPARATE_MONTHLY_QUOTIENT
        );
        var res3 = BonusCalculationEngine.calculate(input3);
        assertEquals(new BigDecimal("25000.00"), res3.monthlyQuotient());
        assertEquals(new BigDecimal("0.20"), res3.applicableRate());
        assertEquals(new BigDecimal("1410.00"), res3.quickDeduction());
        assertEquals(new BigDecimal("58590.00"), res3.withholdingTax());
        assertEquals(new BigDecimal("241410.00"), res3.netAmount());
    }

    @Test
    @DisplayName("PAY03: Synergy preview where Separate Calculation is strictly better")
    void testSynergyPreviewSeparateBetter() {
        // High annual salary 200,000 (taxable), bonus 60,000
        // Salary tax on 200,000: 200,000 * 0.20 - 16,920 = 23,080.00
        // Separate Bonus on 60,000: quotient 5,000 -> 10% (quick 210) -> tax 60,000 * 0.10 - 210 = 5,790.00
        // Option A Total = 23,080 + 5,790 = 28,870.00
        // Option B Combined: 260,000 * 0.20 - 16,920 = 35,080.00
        // Difference = 35,080 - 28,870 = 6,210.00 in favor of Separate!
        var input = new BonusCalculationEngine.BonusInput(
                empId,
                new BigDecimal("60000.00"),
                new BigDecimal("200000.00"),
                BonusCalculationEngine.BonusMode.SEPARATE_MONTHLY_QUOTIENT
        );
        var res = BonusCalculationEngine.calculate(input);
        assertEquals(BonusCalculationEngine.BonusMode.SEPARATE_MONTHLY_QUOTIENT, res.recommendedMode());
        assertEquals(new BigDecimal("6210.00"), res.taxSavings());
        assertNotNull(res.explainTree());
        assertEquals(3, res.explainTree().steps().size());
    }

    @Test
    @DisplayName("PAY03: Synergy preview where Combined Income is strictly better (e.g. unabsorbed deduction / low base salary)")
    void testSynergyPreviewCombinedBetter() {
        // Employee has annual salary 40,000 minus 60,000 basic deduction = -20,000 unabsorbed deduction
        // Bonus 30,000 CNY
        // Option A (Separate): Bonus quotient 2,500 -> 3% -> bonus tax = 900.00; salary tax = 0 -> Total A = 900.00
        // Option B (Combined): Combined taxable = -20,000 + 30,000 = 10,000 -> 3% -> Total B = 300.00
        // Difference = 900.00 - 300.00 = 600.00 savings in favor of Combined!
        var input = new BonusCalculationEngine.BonusInput(
                empId,
                new BigDecimal("30000.00"),
                new BigDecimal("-20000.00"),
                BonusCalculationEngine.BonusMode.COMBINED_COMPREHENSIVE_INCOME
        );
        var res = BonusCalculationEngine.calculate(input);
        assertEquals(BonusCalculationEngine.BonusMode.COMBINED_COMPREHENSIVE_INCOME, res.recommendedMode());
        assertEquals(new BigDecimal("600.00"), res.taxSavings());
        assertEquals(new BigDecimal("300.00"), res.withholdingTax());
        assertEquals(new BigDecimal("29700.00"), res.netAmount());
        assertEquals(new BigDecimal("0.03"), res.applicableRate());
    }

    @Test
    @DisplayName("PAY03: Edge case - Terminated employee receiving bonus in subsequent year with zero employment months")
    void testTerminatedEmployeeSubsequentYearBonus() {
        // Former employee left prior year, 0 employment months in current company
        // Full 60,000 annual basic deduction is unabsorbed (-60,000)
        // Bonus 36,000 CNY
        // Separate option: quotient 3,000 -> 3% -> tax = 1,080.00
        // Combined option: -60,000 + 36,000 = -24,000 <= 0 -> tax = 0.00!
        // Savings = 1,080.00 in favor of Combined!
        var input = new BonusCalculationEngine.BonusInput(
                empId,
                new BigDecimal("36000.00"),
                new BigDecimal("-60000.00"),
                BonusCalculationEngine.BonusMode.SEPARATE_MONTHLY_QUOTIENT
        );
        var res = BonusCalculationEngine.calculate(input);
        assertEquals(BonusCalculationEngine.BonusMode.COMBINED_COMPREHENSIVE_INCOME, res.recommendedMode());
        assertEquals(new BigDecimal("1080.00"), res.taxSavings());
        // Because requestedMode was SEPARATE, separate withholding tax 1080 is applied unless user changes election
        assertEquals(new BigDecimal("1080.00"), res.withholdingTax());
        assertEquals(new BigDecimal("34920.00"), res.netAmount());
    }
}

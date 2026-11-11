package com.lumira.finance.domain;

import static com.lumira.finance.domain.TaxCalculationRules.*;

/**
 * Pure JDK domain verification for TAX01/TAX02/TAX03/TAX04 TaxCalculationRules.
 */
public final class TaxCalculationRulesTest {

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void rejected(String expectedPrefix, Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException ex) {
            check(
                    ex.getMessage() != null && ex.getMessage().startsWith(expectedPrefix),
                    "Expected " + expectedPrefix + " but got " + ex.getMessage());
            return;
        }
        throw new AssertionError("Expected rejection " + expectedPrefix);
    }

    private static void verifySplitGrossAndSmallVat() {
        var split = splitGross("113.00", "0.13");
        check("100.00".equals(split.net()), "net 100.00");
        check("13.00".equals(split.tax()), "tax 13.00");

        var exemptMonth =
                calculateSmallVat(new SmallVatInput("98000.00", "MONTHLY", "0.01", null));
        check(exemptMonth.exemptUnderThreshold(), "monthly <= 100000 exempt");
        check("98000.00".equals(exemptMonth.exemptSales()), "exemptSales");
        check("0.00".equals(exemptMonth.vatPayable()), "vatPayable 0.00");
        check("980.00".equals(exemptMonth.reliefTax()), "reliefTax 980.00");
        check(exemptMonth.worksheetDigest().matches("[a-f0-9]{64}"), "digest 64 hex");

        var taxableQuarter =
                calculateSmallVat(new SmallVatInput("320000.00", "QUARTERLY", "0.03", null));
        check(!taxableQuarter.exemptUnderThreshold(), "quarterly > 300000 taxable");
        check("0.00".equals(taxableQuarter.exemptSales()), "exemptSales 0");
        check("320000.00".equals(taxableQuarter.taxableSales()), "taxableSales");
        check("9600.00".equals(taxableQuarter.vatPayable()), "vatPayable 9600.00");
        check("0.00".equals(taxableQuarter.reliefTax()), "reliefTax 0.00");

        rejected(
                "UNSUPPORTED_SMALL_VAT_RATE",
                () -> calculateSmallVat(new SmallVatInput("50000.00", "MONTHLY", "0.06", null)));
    }

    private static void verifyVatGeneralAndSurcharges() {
        var payable =
                calculateVatGeneralAndSurcharge(
                        new VatGeneralSurchargeInput(
                                "50000.00", "30000.00", "2000.00", "5000.00", "0.07", true));
        // netInput = 25000, deductible = 27000, vatPayable = 23000
        check("25000.00".equals(payable.netInputTax()), "netInput 25000");
        check("27000.00".equals(payable.deductibleInputTotal()), "deductible 27000");
        check("23000.00".equals(payable.vatPayable()), "vatPayable 23000");
        check("0.00".equals(payable.endingCredit()), "endingCredit 0");
        // cityTax = 23000 * 0.07 * 0.5 = 805.00; edu = 23000 * 0.03 * 0.5 = 345.00; localEdu = 23000 * 0.02 * 0.5 = 230.00
        check("805.00".equals(payable.cityTaxPayable()), "cityTax 805.00");
        check("345.00".equals(payable.educationSurchargePayable()), "edu 345.00");
        check("230.00".equals(payable.localEducationSurchargePayable()), "localEdu 230.00");
        check("1380.00".equals(payable.totalSurchargePayable()), "totalSurcharge 1380.00");
        check("24380.00".equals(payable.totalTaxAndSurchargePayable()), "grandTotal 24380.00");

        var credit =
                calculateVatGeneralAndSurcharge(
                        new VatGeneralSurchargeInput(
                                "10000.00", "15000.00", "1000.00", "0.00", "0.07", false));
        check("0.00".equals(credit.vatPayable()), "vatPayable 0");
        check("6000.00".equals(credit.endingCredit()), "endingCredit 6000");
        check("0.00".equals(credit.totalSurchargePayable()), "surcharge 0");

        rejected(
                "INPUT_TRANSFER_OUT_EXCEEDS_INPUT_TAX",
                () ->
                        calculateVatGeneralAndSurcharge(
                                new VatGeneralSurchargeInput(
                                        "10000.00", "1000.00", "0.00", "1500.00", "0.07", false)));
    }

    private static void verifyCitAdjustmentsAndSmallMicroGuard() {
        var cit =
                calculateCitAdjustmentAndPrepay(
                        new CitAdjustmentInput(
                                "2000000.00", // revenue
                                "300000.00", // accounting profit
                                "500000.00", // wage
                                "20000.00", // entertainment: 60%=12000, 0.5% rev=10000 -> deductible 10000, addBack 10000
                                "80000.00", // welfare: cap 14% wage = 70000 -> addBack 10000
                                "12000.00", // union: cap 2% wage = 10000 -> addBack 2000
                                "35000.00", // education: cap 8% wage = 40000 -> addBack 0, room 5000 absorbs prior 3000
                                "3000.00", // priorEducationCarry
                                "350000.00", // advertising: cap 15% rev = 300000 -> addBack 50000, nextCarry 60000
                                "0.15",
                                "10000.00", // priorAdvertisingCarry
                                "40000.00", // donation: cap 12% profit = 36000 -> addBack 4000, nextCarry 4000
                                "0.00",
                                "20000.00", // priorLossCarry
                                true));
        check("10000.00".equals(cit.entertainmentAddBack()), "ent addBack 10000");
        check("10000.00".equals(cit.welfareAddBack()), "welfare addBack 10000");
        check("2000.00".equals(cit.unionAddBack()), "union addBack 2000");
        check("0.00".equals(cit.educationAddBack()), "edu addBack 0");
        check("3000.00".equals(cit.educationPriorAbsorbed()), "edu prior absorbed 3000");
        check("0.00".equals(cit.educationNextCarry()), "edu next carry 0");
        check("50000.00".equals(cit.advertisingAddBack()), "adv addBack 50000");
        check("60000.00".equals(cit.advertisingNextCarry()), "adv next carry 60000");
        check("4000.00".equals(cit.donationAddBack()), "don addBack 4000");
        check("76000.00".equals(cit.totalUpwardAdjustment()), "totalUpward 76000");
        check("3000.00".equals(cit.totalPriorCarryAbsorbed()), "totalPriorAbsorbed 3000");
        check("20000.00".equals(cit.lossAbsorbed()), "lossAbsorbed 20000");
        // taxableIncome = 300000 + 76000 - 3000 - 20000 = 353000.00
        check("353000.00".equals(cit.taxableIncome()), "taxableIncome 353000");
        check(cit.smallMicroApplied(), "smallMicroApplied");
        check("17650.00".equals(cit.citPayable()), "citPayable 5% = 17650.00");

        rejected(
                "SMALL_MICRO_EXCEEDS_TAXABLE_INCOME_CAP",
                () ->
                        calculateCitAdjustmentAndPrepay(
                                new CitAdjustmentInput(
                                        "10000000.00",
                                        "3500000.00",
                                        "500000.00",
                                        "0.00",
                                        "0.00",
                                        "0.00",
                                        "0.00",
                                        "0.00",
                                        "0.00",
                                        "0.15",
                                        "0.00",
                                        "0.00",
                                        "0.00",
                                        "0.00",
                                        true)));
    }

    public static void main(String[] args) {
        verifySplitGrossAndSmallVat();
        verifyVatGeneralAndSurcharges();
        verifyCitAdjustmentsAndSmallMicroGuard();
        System.out.println("TaxCalculationRulesTest: 3 suites passed.");
    }
}

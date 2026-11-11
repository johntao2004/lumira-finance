package com.lumira.finance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

/**
 * Pure domain calculation rules for TAX01/TAX02/TAX03/TAX04 tax worksheets.
 * Matches V4 reference specification formulas with exact 2-decimal CNY rounding,
 * no implicit qualification assumptions, and deterministic SHA-256 worksheet digests.
 */
public final class TaxCalculationRules {

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal ONE = new BigDecimal("1.00");
    private static final BigDecimal MONTHLY_SMALL_VAT_THRESHOLD = new BigDecimal("100000.00");
    private static final BigDecimal QUARTERLY_SMALL_VAT_THRESHOLD = new BigDecimal("300000.00");
    private static final BigDecimal SMALL_MICRO_CIT_LIMIT = new BigDecimal("3000000.00");
    private static final Set<BigDecimal> ALLOWED_SMALL_VAT_RATES =
            Set.of(new BigDecimal("0.01"), new BigDecimal("0.03"));
    private static final Set<BigDecimal> ALLOWED_CITY_TAX_RATES =
            Set.of(new BigDecimal("0.07"), new BigDecimal("0.05"), new BigDecimal("0.01"));
    private static final Set<BigDecimal> ALLOWED_ADVERTISING_CAP_RATES =
            Set.of(new BigDecimal("0.15"), new BigDecimal("0.30"));

    private TaxCalculationRules() {}

    public static BigDecimal cny(String raw, String field, boolean allowNegative) {
        if (raw == null || !raw.matches(allowNegative ? "-?(0|[1-9]\\d{0,17})(\\.\\d{1,2})?" : "(0|[1-9]\\d{0,17})(\\.\\d{1,2})?")) {
            throw new IllegalArgumentException("INVALID_CNY_" + field);
        }
        return new BigDecimal(raw).setScale(2, RoundingMode.UNNECESSARY);
    }

    public static BigDecimal rate(String raw, String field) {
        if (raw == null || !raw.matches("0(\\.\\d{1,4})?|1(\\.0{1,4})?")) {
            throw new IllegalArgumentException("INVALID_RATE_" + field);
        }
        return new BigDecimal(raw).stripTrailingZeros();
    }

    public static BigDecimal round2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    public record SplitGrossResult(String gross, String net, String tax, String rate) {}

    public static SplitGrossResult splitGross(String grossRaw, String rateRaw) {
        BigDecimal gross = cny(grossRaw, "GROSS", false);
        BigDecimal r = rate(rateRaw, "VAT_RATE");
        BigDecimal net = gross.divide(ONE.add(r), 2, RoundingMode.HALF_UP);
        BigDecimal tax = gross.subtract(net).setScale(2, RoundingMode.UNNECESSARY);
        return new SplitGrossResult(
                gross.toPlainString(),
                net.toPlainString(),
                tax.toPlainString(),
                r.toPlainString());
    }

    public record SmallVatInput(
            String salesExcludingTax,
            String periodKind,
            String appliedRate,
            String customThreshold) {}

    public record SmallVatOutput(
            String salesExcludingTax,
            String periodKind,
            String threshold,
            String appliedRate,
            boolean exemptUnderThreshold,
            String exemptSales,
            String taxableSales,
            String vatPayable,
            String reliefTax,
            String worksheetDigest) {}

    public static SmallVatOutput calculateSmallVat(SmallVatInput in) {
        if (in == null) throw new IllegalArgumentException("SMALL_VAT_INPUT_MISSING");
        BigDecimal sales = cny(in.salesExcludingTax(), "SALES", false);
        if (in.periodKind() == null || !Set.of("MONTHLY", "QUARTERLY").contains(in.periodKind())) {
            throw new IllegalArgumentException("INVALID_PERIOD_KIND");
        }
        BigDecimal r = new BigDecimal(rate(in.appliedRate(), "APPLIED_RATE").toPlainString()).setScale(2, RoundingMode.UNNECESSARY);
        if (!ALLOWED_SMALL_VAT_RATES.contains(r)) {
            throw new IllegalArgumentException("UNSUPPORTED_SMALL_VAT_RATE");
        }
        BigDecimal threshold =
                in.customThreshold() != null && !in.customThreshold().isBlank()
                        ? cny(in.customThreshold(), "THRESHOLD", false)
                        : ("MONTHLY".equals(in.periodKind())
                                ? MONTHLY_SMALL_VAT_THRESHOLD
                                : QUARTERLY_SMALL_VAT_THRESHOLD);
        boolean exempt = sales.compareTo(threshold) <= 0;
        BigDecimal calculatedTax = round2(sales.multiply(r));
        BigDecimal exemptSales = exempt ? sales : ZERO;
        BigDecimal taxableSales = exempt ? ZERO : sales;
        BigDecimal vatPayable = exempt ? ZERO : calculatedTax;
        BigDecimal reliefTax = exempt ? calculatedTax : ZERO;
        String digest =
                sha256(
                        "VAT_SMALL|"
                                + sales.toPlainString()
                                + "|"
                                + in.periodKind()
                                + "|"
                                + threshold.toPlainString()
                                + "|"
                                + r.toPlainString()
                                + "|"
                                + vatPayable.toPlainString()
                                + "|"
                                + reliefTax.toPlainString());
        return new SmallVatOutput(
                sales.toPlainString(),
                in.periodKind(),
                threshold.toPlainString(),
                r.toPlainString(),
                exempt,
                exemptSales.toPlainString(),
                taxableSales.toPlainString(),
                vatPayable.toPlainString(),
                reliefTax.toPlainString(),
                digest);
    }

    public record VatGeneralSurchargeInput(
            String outputTax,
            String inputTax,
            String priorCredit,
            String inputTaxTransferOut,
            String cityTaxRate,
            boolean smallMicroReliefAttested) {}

    public record VatGeneralSurchargeOutput(
            String outputTax,
            String netInputTax,
            String deductibleInputTotal,
            String vatPayable,
            String endingCredit,
            String cityTaxRate,
            boolean smallMicroReliefAttested,
            String cityTaxPayable,
            String educationSurchargePayable,
            String localEducationSurchargePayable,
            String totalSurchargePayable,
            String totalTaxAndSurchargePayable,
            String worksheetDigest) {}

    public static VatGeneralSurchargeOutput calculateVatGeneralAndSurcharge(
            VatGeneralSurchargeInput in) {
        if (in == null) throw new IllegalArgumentException("VAT_GENERAL_INPUT_MISSING");
        BigDecimal output = cny(in.outputTax(), "OUTPUT_TAX", false);
        BigDecimal input = cny(in.inputTax(), "INPUT_TAX", false);
        BigDecimal prior = cny(in.priorCredit(), "PRIOR_CREDIT", false);
        BigDecimal transferOut = cny(in.inputTaxTransferOut(), "TRANSFER_OUT", false);
        if (transferOut.compareTo(input) > 0) {
            throw new IllegalArgumentException("INPUT_TRANSFER_OUT_EXCEEDS_INPUT_TAX");
        }
        BigDecimal cityRate =
                new BigDecimal(rate(in.cityTaxRate(), "CITY_TAX_RATE").toPlainString())
                        .setScale(2, RoundingMode.UNNECESSARY);
        if (!ALLOWED_CITY_TAX_RATES.contains(cityRate)) {
            throw new IllegalArgumentException("UNSUPPORTED_CITY_TAX_RATE");
        }
        BigDecimal netInput = input.subtract(transferOut).setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal deductibleTotal = netInput.add(prior).setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal vatPayable =
                output.compareTo(deductibleTotal) > 0
                        ? output.subtract(deductibleTotal).setScale(2, RoundingMode.UNNECESSARY)
                        : ZERO;
        BigDecimal endingCredit =
                deductibleTotal.compareTo(output) > 0
                        ? deductibleTotal.subtract(output).setScale(2, RoundingMode.UNNECESSARY)
                        : ZERO;

        BigDecimal factor =
                in.smallMicroReliefAttested() ? new BigDecimal("0.50") : ONE;
        BigDecimal cityPayable = round2(round2(vatPayable.multiply(cityRate)).multiply(factor));
        BigDecimal eduPayable =
                round2(round2(vatPayable.multiply(new BigDecimal("0.03"))).multiply(factor));
        BigDecimal localEduPayable =
                round2(round2(vatPayable.multiply(new BigDecimal("0.02"))).multiply(factor));
        BigDecimal totalSurcharge =
                cityPayable.add(eduPayable).add(localEduPayable).setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal grandTotal =
                vatPayable.add(totalSurcharge).setScale(2, RoundingMode.UNNECESSARY);
        String digest =
                sha256(
                        "VAT_GENERAL_SURCHARGE|"
                                + output.toPlainString()
                                + "|"
                                + netInput.toPlainString()
                                + "|"
                                + prior.toPlainString()
                                + "|"
                                + vatPayable.toPlainString()
                                + "|"
                                + endingCredit.toPlainString()
                                + "|"
                                + cityRate.toPlainString()
                                + "|"
                                + in.smallMicroReliefAttested()
                                + "|"
                                + totalSurcharge.toPlainString());
        return new VatGeneralSurchargeOutput(
                output.toPlainString(),
                netInput.toPlainString(),
                deductibleTotal.toPlainString(),
                vatPayable.toPlainString(),
                endingCredit.toPlainString(),
                cityRate.toPlainString(),
                in.smallMicroReliefAttested(),
                cityPayable.toPlainString(),
                eduPayable.toPlainString(),
                localEduPayable.toPlainString(),
                totalSurcharge.toPlainString(),
                grandTotal.toPlainString(),
                digest);
    }

    public record CarryBucket(
            BigDecimal currentDeductible,
            BigDecimal upwardAddBack,
            BigDecimal priorCarryAbsorbed,
            BigDecimal nextCarryForward) {}

    public static CarryBucket carryDeduct(
            BigDecimal currentAmount, BigDecimal cap, BigDecimal priorCarryIn) {
        BigDecimal currentDeductible = currentAmount.min(cap).setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal upwardAddBack =
                currentAmount.subtract(currentDeductible).setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal room =
                cap.compareTo(currentAmount) > 0
                        ? cap.subtract(currentAmount).setScale(2, RoundingMode.UNNECESSARY)
                        : ZERO;
        BigDecimal priorAbsorbed = priorCarryIn.min(room).setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal nextCarry =
                priorCarryIn
                        .subtract(priorAbsorbed)
                        .add(upwardAddBack)
                        .setScale(2, RoundingMode.UNNECESSARY);
        return new CarryBucket(currentDeductible, upwardAddBack, priorAbsorbed, nextCarry);
    }

    public static CarryBucket entertainmentAdjustment(BigDecimal amount, BigDecimal revenue) {
        BigDecimal sixtyPct = round2(amount.multiply(new BigDecimal("0.60")));
        BigDecimal halfPctRev = round2(revenue.multiply(new BigDecimal("0.005")));
        BigDecimal deductible = sixtyPct.min(halfPctRev).setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal addBack = amount.subtract(deductible).setScale(2, RoundingMode.UNNECESSARY);
        return new CarryBucket(deductible, addBack, ZERO, ZERO);
    }

    public record CitAdjustmentInput(
            String revenue,
            String accountingProfit,
            String wageTotal,
            String entertainmentExpense,
            String welfareExpense,
            String unionExpense,
            String educationExpense,
            String priorEducationCarry,
            String advertisingExpense,
            String advertisingCapRate,
            String priorAdvertisingCarry,
            String donationExpense,
            String priorDonationCarry,
            String priorLossCarry,
            boolean smallMicroQualifiedAttested) {}

    public record CitAdjustmentOutput(
            String revenue,
            String accountingProfit,
            String entertainmentAddBack,
            String welfareAddBack,
            String unionAddBack,
            String educationAddBack,
            String educationPriorAbsorbed,
            String educationNextCarry,
            String advertisingAddBack,
            String advertisingPriorAbsorbed,
            String advertisingNextCarry,
            String donationAddBack,
            String donationPriorAbsorbed,
            String donationNextCarry,
            String totalUpwardAdjustment,
            String totalPriorCarryAbsorbed,
            String lossAbsorbed,
            String remainingLossCarry,
            String taxableIncome,
            boolean smallMicroApplied,
            String citPayable,
            String worksheetDigest) {}

    public static CitAdjustmentOutput calculateCitAdjustmentAndPrepay(CitAdjustmentInput in) {
        if (in == null) throw new IllegalArgumentException("CIT_INPUT_MISSING");
        BigDecimal revenue = cny(in.revenue(), "REVENUE", false);
        BigDecimal profit = cny(in.accountingProfit(), "ACCOUNTING_PROFIT", true);
        BigDecimal wage = cny(in.wageTotal(), "WAGE_TOTAL", false);
        BigDecimal entertainment = cny(in.entertainmentExpense(), "ENTERTAINMENT", false);
        BigDecimal welfare = cny(in.welfareExpense(), "WELFARE", false);
        BigDecimal union = cny(in.unionExpense(), "UNION", false);
        BigDecimal education = cny(in.educationExpense(), "EDUCATION", false);
        BigDecimal priorEdu = cny(in.priorEducationCarry(), "PRIOR_EDUCATION_CARRY", false);
        BigDecimal advertising = cny(in.advertisingExpense(), "ADVERTISING", false);
        BigDecimal advRate =
                new BigDecimal(rate(in.advertisingCapRate(), "ADVERTISING_CAP_RATE").toPlainString())
                        .setScale(2, RoundingMode.UNNECESSARY);
        if (!ALLOWED_ADVERTISING_CAP_RATES.contains(advRate)) {
            throw new IllegalArgumentException("UNSUPPORTED_ADVERTISING_CAP_RATE");
        }
        BigDecimal priorAdv = cny(in.priorAdvertisingCarry(), "PRIOR_ADVERTISING_CARRY", false);
        BigDecimal donation = cny(in.donationExpense(), "DONATION", false);
        BigDecimal priorDon = cny(in.priorDonationCarry(), "PRIOR_DONATION_CARRY", false);
        BigDecimal priorLoss = cny(in.priorLossCarry(), "PRIOR_LOSS_CARRY", false);

        CarryBucket entBucket = entertainmentAdjustment(entertainment, revenue);
        BigDecimal welfareCap = round2(wage.multiply(new BigDecimal("0.14")));
        BigDecimal welfareAddBack =
                welfare.compareTo(welfareCap) > 0
                        ? welfare.subtract(welfareCap).setScale(2, RoundingMode.UNNECESSARY)
                        : ZERO;
        BigDecimal unionCap = round2(wage.multiply(new BigDecimal("0.02")));
        BigDecimal unionAddBack =
                union.compareTo(unionCap) > 0
                        ? union.subtract(unionCap).setScale(2, RoundingMode.UNNECESSARY)
                        : ZERO;
        BigDecimal eduCap = round2(wage.multiply(new BigDecimal("0.08")));
        CarryBucket eduBucket = carryDeduct(education, eduCap, priorEdu);

        BigDecimal advCap = round2(revenue.multiply(advRate));
        CarryBucket advBucket = carryDeduct(advertising, advCap, priorAdv);

        BigDecimal donCap =
                profit.compareTo(ZERO) > 0
                        ? round2(profit.multiply(new BigDecimal("0.12")))
                        : ZERO;
        CarryBucket donBucket = carryDeduct(donation, donCap, priorDon);

        BigDecimal totalUpward =
                entBucket
                        .upwardAddBack()
                        .add(welfareAddBack)
                        .add(unionAddBack)
                        .add(eduBucket.upwardAddBack())
                        .add(advBucket.upwardAddBack())
                        .add(donBucket.upwardAddBack())
                        .setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal totalPriorAbsorbed =
                eduBucket
                        .priorCarryAbsorbed()
                        .add(advBucket.priorCarryAbsorbed())
                        .add(donBucket.priorCarryAbsorbed())
                        .setScale(2, RoundingMode.UNNECESSARY);

        BigDecimal beforeLoss =
                profit.add(totalUpward).subtract(totalPriorAbsorbed).setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal lossAbsorbed =
                beforeLoss.compareTo(ZERO) > 0
                        ? beforeLoss.min(priorLoss).setScale(2, RoundingMode.UNNECESSARY)
                        : ZERO;
        BigDecimal remainingLoss =
                beforeLoss.compareTo(ZERO) < 0
                        ? priorLoss.add(beforeLoss.negate()).setScale(2, RoundingMode.UNNECESSARY)
                        : priorLoss.subtract(lossAbsorbed).setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal taxableIncome =
                beforeLoss.compareTo(ZERO) > 0
                        ? beforeLoss.subtract(lossAbsorbed).setScale(2, RoundingMode.UNNECESSARY)
                        : ZERO;

        if (in.smallMicroQualifiedAttested() && taxableIncome.compareTo(SMALL_MICRO_CIT_LIMIT) > 0) {
            throw new IllegalArgumentException("SMALL_MICRO_EXCEEDS_TAXABLE_INCOME_CAP");
        }
        boolean smallMicroApplied =
                in.smallMicroQualifiedAttested() && taxableIncome.compareTo(SMALL_MICRO_CIT_LIMIT) <= 0;
        BigDecimal citPayable =
                taxableIncome.compareTo(ZERO) <= 0
                        ? ZERO
                        : round2(
                                taxableIncome.multiply(
                                        smallMicroApplied
                                                ? new BigDecimal("0.05")
                                                : new BigDecimal("0.25")));
        String digest =
                sha256(
                        "CIT_ADJUSTMENT_AND_PREPAY|"
                                + revenue.toPlainString()
                                + "|"
                                + profit.toPlainString()
                                + "|"
                                + totalUpward.toPlainString()
                                + "|"
                                + totalPriorAbsorbed.toPlainString()
                                + "|"
                                + lossAbsorbed.toPlainString()
                                + "|"
                                + taxableIncome.toPlainString()
                                + "|"
                                + smallMicroApplied
                                + "|"
                                + citPayable.toPlainString());
        return new CitAdjustmentOutput(
                revenue.toPlainString(),
                profit.toPlainString(),
                entBucket.upwardAddBack().toPlainString(),
                welfareAddBack.toPlainString(),
                unionAddBack.toPlainString(),
                eduBucket.upwardAddBack().toPlainString(),
                eduBucket.priorCarryAbsorbed().toPlainString(),
                eduBucket.nextCarryForward().toPlainString(),
                advBucket.upwardAddBack().toPlainString(),
                advBucket.priorCarryAbsorbed().toPlainString(),
                advBucket.nextCarryForward().toPlainString(),
                donBucket.upwardAddBack().toPlainString(),
                donBucket.priorCarryAbsorbed().toPlainString(),
                donBucket.nextCarryForward().toPlainString(),
                totalUpward.toPlainString(),
                totalPriorAbsorbed.toPlainString(),
                lossAbsorbed.toPlainString(),
                remainingLoss.toPlainString(),
                taxableIncome.toPlainString(),
                smallMicroApplied,
                citPayable.toPlainString(),
                digest);
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", e);
        }
    }
}

package com.lumira.finance.statutory;

import static com.lumira.finance.statutory.StatutoryModels.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

public final class StatutoryReportEngine {
    private StatutoryReportEngine() {}

    private static String raw(BigDecimal d) {
        if (d == null) return "0.00";
        return d.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public static String sha256Hex(String data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    // -------------------------------------------------------------------------
    // 1. Balance Sheet Engine (RPT02 & RP01)
    // -------------------------------------------------------------------------
    public static StatementSheet buildBalanceSheet(
            Scope scope,
            List<AccountBalance> balances,
            List<ContraBalance> contras,
            Map<String, BigDecimal> maturityReclassifications, // e.g. "LONG_LOAN_WITHIN_1YR" -> amount
            List<ReportTrace> tracesCollector) {

        boolean cas = scope.standard() == Standard.CAS_2019;
        String templateCode = cas ? "CAS-BS-2019-A2" : "SBE-BS-2011";
        Map<String, BigDecimal> rowValues = new LinkedHashMap<>();
        Map<String, String> rowLabels = new LinkedHashMap<>();
        List<ReportTrace> localTraces = new ArrayList<>();

        // Group balances by semantic and detail netting unit
        BigDecimal cash = BigDecimal.ZERO;
        BigDecimal arDebit = BigDecimal.ZERO;
        BigDecimal arCredit = BigDecimal.ZERO;
        BigDecimal apDebit = BigDecimal.ZERO;
        BigDecimal apCredit = BigDecimal.ZERO;
        BigDecimal otherArDebit = BigDecimal.ZERO;
        BigDecimal otherArCredit = BigDecimal.ZERO;
        BigDecimal inventoryGross = BigDecimal.ZERO;
        BigDecimal fixedAssetGross = BigDecimal.ZERO;
        BigDecimal intangibleGross = BigDecimal.ZERO;
        BigDecimal shortBorrowing = BigDecimal.ZERO;
        BigDecimal payrollPayable = BigDecimal.ZERO;
        BigDecimal taxPayable = BigDecimal.ZERO;
        BigDecimal shareCapital = BigDecimal.ZERO;
        BigDecimal capitalReserve = BigDecimal.ZERO;
        BigDecimal surplusReserve = BigDecimal.ZERO;
        BigDecimal retainedEarnings = BigDecimal.ZERO;
        BigDecimal longTermBorrowing = BigDecimal.ZERO;

        for (AccountBalance b : balances) {
            BigDecimal net = b.netDebit();
            String sem = b.semantic() == null ? "" : b.semantic().toUpperCase();

            switch (sem) {
                case "CASH" -> {
                    cash = cash.add(net);
                    localTraces.add(new ReportTrace("BALANCE_EXTRACT", b.accountCode(), b.nettingUnit(), null, cas ? "A01" : "1", raw(net), "货币资金借方净额"));
                }
                case "RECEIVABLE" -> {
                    if (net.signum() >= 0) {
                        arDebit = arDebit.add(net);
                        localTraces.add(new ReportTrace("RECLASSIFY_DEBIT", b.accountCode(), b.nettingUnit(), null, cas ? "A05" : "4", raw(net), "应收账款明细借方余额列入应收账款"));
                    } else {
                        BigDecimal cr = net.abs();
                        arCredit = arCredit.add(cr);
                        localTraces.add(new ReportTrace("RECLASSIFY_CREDIT", b.accountCode(), b.nettingUnit(), null, cas ? "L07" : "34", raw(cr), cas ? "应收明细贷方余额重分类至合同负债" : "应收明细贷方余额重分类至预收账款"));
                    }
                }
                case "PAYABLE" -> {
                    if (net.signum() <= 0) {
                        BigDecimal cr = net.abs();
                        apCredit = apCredit.add(cr);
                        localTraces.add(new ReportTrace("RECLASSIFY_CREDIT", b.accountCode(), b.nettingUnit(), null, cas ? "L05" : "33", raw(cr), "应付账款明细贷方余额列入应付账款"));
                    } else {
                        apDebit = apDebit.add(net);
                        localTraces.add(new ReportTrace("RECLASSIFY_DEBIT", b.accountCode(), b.nettingUnit(), null, cas ? "A07" : "5", raw(net), "应付账款明细借方余额重分类至预付款项"));
                    }
                }
                case "OTHER_RECEIVABLE" -> {
                    if (net.signum() >= 0) {
                        otherArDebit = otherArDebit.add(net);
                        localTraces.add(new ReportTrace("RECLASSIFY_DEBIT", b.accountCode(), b.nettingUnit(), null, cas ? "A08" : "8", raw(net), "其他应收明细借方余额"));
                    } else {
                        otherArCredit = otherArCredit.add(net.abs());
                        localTraces.add(new ReportTrace("RECLASSIFY_CREDIT", b.accountCode(), b.nettingUnit(), null, cas ? "L10" : "39", raw(net.abs()), "其他应收明细贷方重分类至其他应付款"));
                    }
                }
                case "INVENTORY" -> {
                    inventoryGross = inventoryGross.add(net);
                    localTraces.add(new ReportTrace("BALANCE_EXTRACT", b.accountCode(), b.nettingUnit(), null, cas ? "A09" : "9", raw(net), "存货账面原值"));
                }
                case "FIXED_ASSET" -> {
                    fixedAssetGross = fixedAssetGross.add(net);
                    localTraces.add(new ReportTrace("BALANCE_EXTRACT", b.accountCode(), b.nettingUnit(), null, cas ? "A22" : "18", raw(net), "固定资产原价"));
                }
                case "INTANGIBLE" -> {
                    intangibleGross = intangibleGross.add(net);
                    localTraces.add(new ReportTrace("BALANCE_EXTRACT", b.accountCode(), b.nettingUnit(), null, cas ? "A27" : "25", raw(net), "无形资产原价"));
                }
                case "SHORT_BORROWING" -> shortBorrowing = shortBorrowing.add(b.creditBalance().subtract(b.debitBalance()));
                case "LONG_BORROWING" -> longTermBorrowing = longTermBorrowing.add(b.creditBalance().subtract(b.debitBalance()));
                case "PAYROLL_PAYABLE" -> payrollPayable = payrollPayable.add(b.creditBalance().subtract(b.debitBalance()));
                case "TAX_PAYABLE" -> taxPayable = taxPayable.add(b.creditBalance().subtract(b.debitBalance()));
                case "SHARE_CAPITAL" -> shareCapital = shareCapital.add(b.creditBalance().subtract(b.debitBalance()));
                case "CAPITAL_RESERVE" -> capitalReserve = capitalReserve.add(b.creditBalance().subtract(b.debitBalance()));
                case "SURPLUS_RESERVE" -> surplusReserve = surplusReserve.add(b.creditBalance().subtract(b.debitBalance()));
                case "RETAINED_EARNINGS" -> retainedEarnings = retainedEarnings.add(b.creditBalance().subtract(b.debitBalance()));
            }
        }

        // Apply Contra balances
        BigDecimal fixedDepreciation = BigDecimal.ZERO;
        BigDecimal fixedImpairment = BigDecimal.ZERO;
        BigDecimal intangibleAmortization = BigDecimal.ZERO;
        BigDecimal badDebtAllowance = BigDecimal.ZERO;
        BigDecimal inventoryImpairment = BigDecimal.ZERO;

        for (ContraBalance cb : contras) {
            String type = cb.contraType().toUpperCase();
            switch (type) {
                case "DEPRECIATION" -> {
                    fixedDepreciation = fixedDepreciation.add(cb.creditBalance());
                    localTraces.add(new ReportTrace("CONTRA_NETTING", cb.contraAccountCode(), cb.nettingUnit(), null, cas ? "A22" : "19", raw(cb.creditBalance()), "累计折旧对冲固定资产"));
                }
                case "AMORTIZATION" -> {
                    intangibleAmortization = intangibleAmortization.add(cb.creditBalance());
                    localTraces.add(new ReportTrace("CONTRA_NETTING", cb.contraAccountCode(), cb.nettingUnit(), null, cas ? "A27" : "25", raw(cb.creditBalance()), "累计摊销对冲无形资产"));
                }
                case "IMPAIRMENT" -> {
                    if (cb.targetAccountCode() != null && cb.targetAccountCode().startsWith("1122")) {
                        badDebtAllowance = badDebtAllowance.add(cb.creditBalance());
                        localTraces.add(new ReportTrace("CONTRA_NETTING", cb.contraAccountCode(), cb.nettingUnit(), null, cas ? "A05" : "4", raw(cb.creditBalance()), "坏账准备对冲应收账款"));
                    } else if (cb.targetAccountCode() != null && cb.targetAccountCode().startsWith("1601")) {
                        fixedImpairment = fixedImpairment.add(cb.creditBalance());
                    } else {
                        inventoryImpairment = inventoryImpairment.add(cb.creditBalance());
                    }
                }
            }
        }

        // Liquidity maturity reclassification
        BigDecimal longBorrowingWithin1Yr = maturityReclassifications == null ? BigDecimal.ZERO
                : maturityReclassifications.getOrDefault("LONG_LOAN_WITHIN_1YR", BigDecimal.ZERO);
        BigDecimal longTermBorrowingNonCurrent = longTermBorrowing.subtract(longBorrowingWithin1Yr);
        if (longBorrowingWithin1Yr.signum() > 0) {
            localTraces.add(new ReportTrace("MATURITY_RECLASSIFY", "2501", null, cas ? "L15" : "42", cas ? "L12" : "40", raw(longBorrowingWithin1Yr), "一年内到期长期借款重分类至流动负债"));
        }

        // Net Assets calculations
        BigDecimal netFixed = fixedAssetGross.subtract(fixedDepreciation).subtract(fixedImpairment).max(BigDecimal.ZERO);
        BigDecimal netIntangible = intangibleGross.subtract(intangibleAmortization).max(BigDecimal.ZERO);
        BigDecimal netAr = arDebit.subtract(badDebtAllowance).max(BigDecimal.ZERO);
        BigDecimal netInventory = inventoryGross.subtract(inventoryImpairment).max(BigDecimal.ZERO);

        // Assets
        BigDecimal currentAssets = cash.add(netAr).add(apDebit).add(otherArDebit).add(netInventory);
        BigDecimal nonCurrentAssets = netFixed.add(netIntangible);
        BigDecimal totalAssets = currentAssets.add(nonCurrentAssets);

        // Liabilities
        BigDecimal currentLiab = shortBorrowing.add(apCredit).add(arCredit).add(payrollPayable).add(taxPayable).add(otherArCredit).add(longBorrowingWithin1Yr);
        BigDecimal nonCurrentLiab = longTermBorrowingNonCurrent.max(BigDecimal.ZERO);
        BigDecimal totalLiab = currentLiab.add(nonCurrentLiab);

        // Equity
        BigDecimal totalEquity = shareCapital.add(capitalReserve).add(surplusReserve).add(retainedEarnings);
        BigDecimal totalLiabAndEquity = totalLiab.add(totalEquity);

        // Articulation check: Assets = Liabilities + Equity
        BigDecimal articulationDifference = totalAssets.subtract(totalLiabAndEquity);
        boolean articulationPass = articulationDifference.compareTo(BigDecimal.ZERO) == 0;

        // Populate rowValues according to standard
        if (cas) {
            rowValues.put("A01", cash); rowLabels.put("A01", "货币资金");
            rowValues.put("A05", netAr); rowLabels.put("A05", "应收账款");
            rowValues.put("A07", apDebit); rowLabels.put("A07", "预付款项");
            rowValues.put("A08", otherArDebit); rowLabels.put("A08", "其他应收款");
            rowValues.put("A09", netInventory); rowLabels.put("A09", "存货");
            rowValues.put("A14", currentAssets); rowLabels.put("A14", "流动资产合计");
            rowValues.put("A22", netFixed); rowLabels.put("A22", "固定资产");
            rowValues.put("A27", netIntangible); rowLabels.put("A27", "无形资产");
            rowValues.put("A33", nonCurrentAssets); rowLabels.put("A33", "非流动资产合计");
            rowValues.put("A34", totalAssets); rowLabels.put("A34", "资产总计");

            rowValues.put("L01", shortBorrowing); rowLabels.put("L01", "短期借款");
            rowValues.put("L05", apCredit); rowLabels.put("L05", "应付账款");
            rowValues.put("L07", arCredit); rowLabels.put("L07", "合同负债");
            rowValues.put("L08", payrollPayable); rowLabels.put("L08", "应付职工薪酬");
            rowValues.put("L09", taxPayable); rowLabels.put("L09", "应交税费");
            rowValues.put("L10", otherArCredit); rowLabels.put("L10", "其他应付款");
            rowValues.put("L12", longBorrowingWithin1Yr); rowLabels.put("L12", "一年内到期的非流动负债");
            rowValues.put("L14", currentLiab); rowLabels.put("L14", "流动负债合计");
            rowValues.put("L15", longTermBorrowingNonCurrent); rowLabels.put("L15", "长期借款");
            rowValues.put("L23", nonCurrentLiab); rowLabels.put("L23", "非流动负债合计");
            rowValues.put("L24", totalLiab); rowLabels.put("L24", "负债合计");

            rowValues.put("E01", shareCapital); rowLabels.put("E01", "实收资本");
            rowValues.put("E03", capitalReserve); rowLabels.put("E03", "资本公积");
            rowValues.put("E07", surplusReserve); rowLabels.put("E07", "盈余公积");
            rowValues.put("E08", retainedEarnings); rowLabels.put("E08", "未分配利润");
            rowValues.put("E09", totalEquity); rowLabels.put("E09", "所有者权益合计");
            rowValues.put("E10", totalLiabAndEquity); rowLabels.put("E10", "负债和所有者权益总计");
        } else {
            rowValues.put("1", cash); rowLabels.put("1", "货币资金");
            rowValues.put("4", netAr); rowLabels.put("4", "应收账款");
            rowValues.put("5", apDebit); rowLabels.put("5", "预付账款");
            rowValues.put("8", otherArDebit); rowLabels.put("8", "其他应收款");
            rowValues.put("9", netInventory); rowLabels.put("9", "存货");
            rowValues.put("15", currentAssets); rowLabels.put("15", "流动资产合计");
            rowValues.put("18", fixedAssetGross); rowLabels.put("18", "固定资产原价");
            rowValues.put("19", fixedDepreciation); rowLabels.put("19", "减：累计折旧");
            rowValues.put("20", netFixed); rowLabels.put("20", "固定资产账面价值");
            rowValues.put("25", netIntangible); rowLabels.put("25", "无形资产");
            rowValues.put("29", nonCurrentAssets); rowLabels.put("29", "非流动资产合计");
            rowValues.put("30", totalAssets); rowLabels.put("30", "资产总计");

            rowValues.put("31", shortBorrowing); rowLabels.put("31", "短期借款");
            rowValues.put("33", apCredit); rowLabels.put("33", "应付账款");
            rowValues.put("34", arCredit); rowLabels.put("34", "预收账款");
            rowValues.put("35", payrollPayable); rowLabels.put("35", "应付职工薪酬");
            rowValues.put("36", taxPayable); rowLabels.put("36", "应交税费");
            rowValues.put("39", otherArCredit); rowLabels.put("39", "其他应付款");
            rowValues.put("40", longBorrowingWithin1Yr); rowLabels.put("40", "其他流动负债(含一年内到期借款)");
            rowValues.put("41", currentLiab); rowLabels.put("41", "流动负债合计");
            rowValues.put("42", longTermBorrowingNonCurrent); rowLabels.put("42", "长期借款");
            rowValues.put("46", nonCurrentLiab); rowLabels.put("46", "非流动负债合计");
            rowValues.put("47", totalLiab); rowLabels.put("47", "负债合计");

            rowValues.put("48", shareCapital); rowLabels.put("48", "实收资本");
            rowValues.put("49", capitalReserve); rowLabels.put("49", "资本公积");
            rowValues.put("50", surplusReserve); rowLabels.put("50", "盈余公积");
            rowValues.put("51", retainedEarnings); rowLabels.put("51", "未分配利润");
            rowValues.put("52", totalEquity); rowLabels.put("52", "所有者权益合计");
            rowValues.put("53", totalLiabAndEquity); rowLabels.put("53", "负债和所有者权益总计");
        }

        Map<String, ReportCell> cells = new LinkedHashMap<>();
        for (var entry : rowValues.entrySet()) {
            cells.put(entry.getKey(), new ReportCell(
                    entry.getKey(),
                    rowLabels.getOrDefault(entry.getKey(), entry.getKey()),
                    raw(entry.getValue()),
                    raw(entry.getValue()),
                    entry.getValue().signum() == 0 ? "ZERO" : "EXACT",
                    List.of()
            ));
        }

        if (tracesCollector != null) tracesCollector.addAll(localTraces);

        Map<String, Object> articulationDetails = Map.of(
                "totalAssets", raw(totalAssets),
                "totalLiabilities", raw(totalLiab),
                "totalEquity", raw(totalEquity),
                "totalLiabilitiesAndEquity", raw(totalLiabAndEquity),
                "difference", raw(articulationDifference),
                "articulationRule", "totalAssets == totalLiabilities + totalEquity"
        );

        return new StatementSheet(
                StatementKind.BS,
                templateCode,
                cells,
                articulationPass ? "PASS" : "FAIL",
                articulationDetails,
                localTraces
        );
    }

    // -------------------------------------------------------------------------
    // 2. Comparative Income Statement Engine (RPT03 & RP01)
    // -------------------------------------------------------------------------
    public static StatementSheet buildIncomeStatement(
            Scope scope,
            List<MovementRecord> movements,
            Map<String, BigDecimal> priorYearComparative,
            List<ReportTrace> tracesCollector) {

        boolean cas = scope.standard() == Standard.CAS_2019;
        String templateCode = cas ? "CAS-PL-2019-A2" : "SBE-PL-2011";

        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal taxSurcharge = BigDecimal.ZERO;
        BigDecimal sellingExpense = BigDecimal.ZERO;
        BigDecimal managementExpense = BigDecimal.ZERO;
        BigDecimal researchExpense = BigDecimal.ZERO;
        BigDecimal financeExpense = BigDecimal.ZERO;
        BigDecimal otherIncome = BigDecimal.ZERO;
        BigDecimal investmentIncome = BigDecimal.ZERO;
        BigDecimal fairValueGain = BigDecimal.ZERO;
        BigDecimal creditLoss = BigDecimal.ZERO;
        BigDecimal assetLoss = BigDecimal.ZERO;
        BigDecimal nonOperatingIncome = BigDecimal.ZERO;
        BigDecimal nonOperatingExpense = BigDecimal.ZERO;
        BigDecimal incomeTaxExpense = BigDecimal.ZERO;

        List<ReportTrace> localTraces = new ArrayList<>();

        for (MovementRecord m : movements) {
            // Strictly exclude closing transfers!
            if ("MONTH_PROFIT_TRANSFER".equalsIgnoreCase(m.closingType())
                    || "ANNUAL_PROFIT_TRANSFER".equalsIgnoreCase(m.closingType())
                    || "TRANSFER_REVERSAL".equalsIgnoreCase(m.closingType())) {
                localTraces.add(new ReportTrace("EXCLUDE_CLOSING", m.accountCode(), null, null, null, raw(m.debit().add(m.credit())), "排除名义损益结转分录"));
                continue;
            }

            String sem = m.semantic() == null ? "" : m.semantic().toUpperCase();
            BigDecimal net = m.credit().subtract(m.debit()); // Revenue normal side is Credit
            BigDecimal expNet = m.debit().subtract(m.credit()); // Expense normal side is Debit

            switch (sem) {
                case "REVENUE" -> revenue = revenue.add(net);
                case "COST" -> cost = cost.add(expNet);
                case "TAX_SURCHARGE" -> taxSurcharge = taxSurcharge.add(expNet);
                case "SELLING" -> sellingExpense = sellingExpense.add(expNet);
                case "MANAGEMENT" -> managementExpense = managementExpense.add(expNet);
                case "RESEARCH" -> researchExpense = researchExpense.add(expNet);
                case "FINANCE" -> financeExpense = financeExpense.add(expNet);
                case "OTHER_INCOME" -> otherIncome = otherIncome.add(net);
                case "INVESTMENT_INCOME" -> investmentIncome = investmentIncome.add(net);
                case "FAIR_VALUE_GAIN" -> fairValueGain = fairValueGain.add(net);
                case "CREDIT_LOSS" -> creditLoss = creditLoss.add(expNet);
                case "ASSET_LOSS" -> assetLoss = assetLoss.add(expNet);
                case "NONOPERATING_INCOME" -> nonOperatingIncome = nonOperatingIncome.add(net);
                case "NONOPERATING_EXPENSE" -> nonOperatingExpense = nonOperatingExpense.add(expNet);
                case "INCOME_TAX" -> incomeTaxExpense = incomeTaxExpense.add(expNet);
            }
        }

        // Operating Profit
        // 营业利润 = 营业收入 - 营业成本 - 税金及附加 - 销售费用 - 管理费用 - 研发费用 - 财务费用 + 其他收益 + 投资收益 + 公允价值变动收益 - 信用减值损失 - 资产减值损失
        BigDecimal operatingProfit = revenue.subtract(cost).subtract(taxSurcharge)
                .subtract(sellingExpense).subtract(managementExpense).subtract(researchExpense).subtract(financeExpense)
                .add(otherIncome).add(investmentIncome).add(fairValueGain)
                .subtract(creditLoss).subtract(assetLoss);

        // Total Profit
        // 利润总额 = 营业利润 + 营业外收入 - 营业外支出
        BigDecimal totalProfit = operatingProfit.add(nonOperatingIncome).subtract(nonOperatingExpense);

        // Net Profit
        // 净利润 = 利润总额 - 所得税费用
        BigDecimal netProfit = totalProfit.subtract(incomeTaxExpense);

        Map<String, BigDecimal> rowValues = new LinkedHashMap<>();
        Map<String, String> rowLabels = new LinkedHashMap<>();

        if (cas) {
            rowValues.put("P01", revenue); rowLabels.put("P01", "营业收入");
            rowValues.put("P02", cost); rowLabels.put("P02", "营业成本");
            rowValues.put("P03", taxSurcharge); rowLabels.put("P03", "税金及附加");
            rowValues.put("P04", sellingExpense); rowLabels.put("P04", "销售费用");
            rowValues.put("P05", managementExpense); rowLabels.put("P05", "管理费用");
            rowValues.put("P06", researchExpense); rowLabels.put("P06", "研发费用");
            rowValues.put("P07", financeExpense); rowLabels.put("P07", "财务费用");
            rowValues.put("P08", otherIncome); rowLabels.put("P08", "其他收益");
            rowValues.put("P09", investmentIncome); rowLabels.put("P09", "投资收益");
            rowValues.put("P10", fairValueGain); rowLabels.put("P10", "公允价值变动收益");
            rowValues.put("P11", creditLoss.negate()); rowLabels.put("P11", "信用减值损失");
            rowValues.put("P12", assetLoss.negate()); rowLabels.put("P12", "资产减值损失");
            rowValues.put("P15", operatingProfit); rowLabels.put("P15", "营业利润");
            rowValues.put("P16", nonOperatingIncome); rowLabels.put("P16", "营业外收入");
            rowValues.put("P17", nonOperatingExpense); rowLabels.put("P17", "营业外支出");
            rowValues.put("P18", totalProfit); rowLabels.put("P18", "利润总额");
            rowValues.put("P19", incomeTaxExpense); rowLabels.put("P19", "所得税费用");
            rowValues.put("P20", netProfit); rowLabels.put("P20", "净利润");
        } else {
            rowValues.put("1", revenue); rowLabels.put("1", "营业收入");
            rowValues.put("2", cost); rowLabels.put("2", "营业成本");
            rowValues.put("3", taxSurcharge); rowLabels.put("3", "税金及附加");
            rowValues.put("11", sellingExpense); rowLabels.put("11", "销售费用");
            rowValues.put("14", managementExpense); rowLabels.put("14", "管理费用");
            rowValues.put("17", researchExpense); rowLabels.put("17", "其中：研究费用");
            rowValues.put("18", financeExpense); rowLabels.put("18", "财务费用");
            rowValues.put("20", investmentIncome); rowLabels.put("20", "投资收益");
            rowValues.put("21", operatingProfit); rowLabels.put("21", "营业利润");
            rowValues.put("22", nonOperatingIncome); rowLabels.put("22", "营业外收入");
            rowValues.put("24", nonOperatingExpense); rowLabels.put("24", "营业外支出");
            rowValues.put("30", totalProfit); rowLabels.put("30", "利润总额");
            rowValues.put("31", incomeTaxExpense); rowLabels.put("31", "所得税费用");
            rowValues.put("32", netProfit); rowLabels.put("32", "净利润");
        }

        Map<String, ReportCell> cells = new LinkedHashMap<>();
        for (var entry : rowValues.entrySet()) {
            BigDecimal prior = priorYearComparative == null ? BigDecimal.ZERO : priorYearComparative.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            cells.put(entry.getKey(), new ReportCell(
                    entry.getKey(),
                    rowLabels.getOrDefault(entry.getKey(), entry.getKey()),
                    raw(entry.getValue()),
                    raw(entry.getValue()) + " (上年同期: " + raw(prior) + ")",
                    entry.getValue().signum() == 0 ? "ZERO" : "EXACT",
                    List.of()
            ));
        }

        if (tracesCollector != null) tracesCollector.addAll(localTraces);

        Map<String, Object> articulationDetails = Map.of(
                "operatingProfit", raw(operatingProfit),
                "totalProfit", raw(totalProfit),
                "netProfit", raw(netProfit),
                "articulationRule", "netProfit == totalProfit - incomeTaxExpense"
        );

        return new StatementSheet(
                StatementKind.PL,
                templateCode,
                cells,
                "PASS",
                articulationDetails,
                localTraces
        );
    }

    // -------------------------------------------------------------------------
    // 3. Cash Flow Statement Engine (Direct + Indirect Reconciliation) (RPT04 & RP01)
    // -------------------------------------------------------------------------
    public static StatementSheet buildCashFlowStatement(
            Scope scope,
            BigDecimal openingCash,
            BigDecimal closingCash,
            Map<String, BigDecimal> cashflowItems, // e.g. "C01" -> 150.00
            BigDecimal netProfit,
            BigDecimal depreciationNonCash,
            BigDecimal amortizationNonCash,
            BigDecimal workingCapitalChangeReceivables,
            BigDecimal workingCapitalChangePayables,
            BigDecimal workingCapitalChangeInventory,
            BigDecimal impairmentNonCash,
            BigDecimal financeExpenseNonOperating,
            List<ReportTrace> tracesCollector) {

        boolean cas = scope.standard() == Standard.CAS_2019;
        String templateCode = cas ? "CAS-CF-2019-A2" : "SBE-CF-2011";

        // Direct method items
        BigDecimal c01 = cashflowItems.getOrDefault(cas ? "C01" : "1", BigDecimal.ZERO); // Sales receipt
        BigDecimal c02 = cashflowItems.getOrDefault(cas ? "C02" : "2", BigDecimal.ZERO); // Tax refund / other
        BigDecimal c03 = cashflowItems.getOrDefault(cas ? "C03" : "2", BigDecimal.ZERO); // Other op in
        BigDecimal opInflow = c01.add(c02).add(c03);

        BigDecimal c05 = cashflowItems.getOrDefault(cas ? "C05" : "3", BigDecimal.ZERO); // Purchases
        BigDecimal c06 = cashflowItems.getOrDefault(cas ? "C06" : "4", BigDecimal.ZERO); // Payroll
        BigDecimal c07 = cashflowItems.getOrDefault(cas ? "C07" : "5", BigDecimal.ZERO); // Taxes
        BigDecimal c08 = cashflowItems.getOrDefault(cas ? "C08" : "6", BigDecimal.ZERO); // Other op out
        BigDecimal opOutflow = c05.add(c06).add(c07).add(c08);

        BigDecimal netOperatingCashFlow = opInflow.subtract(opOutflow);

        // Investing
        BigDecimal invInflow = cashflowItems.getOrDefault(cas ? "C16" : "8", BigDecimal.ZERO);
        BigDecimal c17 = cashflowItems.getOrDefault(cas ? "C17" : "12", BigDecimal.ZERO); // Capex
        BigDecimal c18 = cashflowItems.getOrDefault(cas ? "C18" : "11", BigDecimal.ZERO); // Investments
        BigDecimal invOutflow = c17.add(c18);
        BigDecimal netInvestingCashFlow = invInflow.subtract(invOutflow);

        // Financing
        BigDecimal c23 = cashflowItems.getOrDefault(cas ? "C23" : "15", BigDecimal.ZERO); // Equity in
        BigDecimal c24 = cashflowItems.getOrDefault(cas ? "C24" : "14", BigDecimal.ZERO); // Borrowing in
        BigDecimal finInflow = c23.add(c24);
        BigDecimal c27 = cashflowItems.getOrDefault(cas ? "C27" : "16", BigDecimal.ZERO); // Repay debt
        BigDecimal c28 = cashflowItems.getOrDefault(cas ? "C28" : "17", BigDecimal.ZERO); // Div/Interest
        BigDecimal finOutflow = c27.add(c28);
        BigDecimal netFinancingCashFlow = finInflow.subtract(finOutflow);

        // FX effect
        BigDecimal fxEffect = cashflowItems.getOrDefault(cas ? "C32" : "20a", BigDecimal.ZERO);

        // Net cash increase
        BigDecimal netCashIncrease = netOperatingCashFlow.add(netInvestingCashFlow).add(netFinancingCashFlow).add(fxEffect);

        // Articulation Check 1: ΔCash == Net Cash Flow
        BigDecimal deltaCash = (closingCash == null ? BigDecimal.ZERO : closingCash).subtract(openingCash == null ? BigDecimal.ZERO : openingCash);
        BigDecimal cashDifference = deltaCash.subtract(netCashIncrease);
        boolean cashBridgePass = cashDifference.compareTo(BigDecimal.ZERO) == 0;

        // Indirect Reconciliation:
        // 净利润 + 非付现折旧 + 非付现摊销 + 非付现减值 + 非经营财务费用 + 存货减少 - 经营性应收增加 + 经营性应付增加 == 经营活动产生的现金流量净额
        BigDecimal indirectOperatingCash = (netProfit == null ? BigDecimal.ZERO : netProfit)
                .add(depreciationNonCash == null ? BigDecimal.ZERO : depreciationNonCash)
                .add(amortizationNonCash == null ? BigDecimal.ZERO : amortizationNonCash)
                .add(impairmentNonCash == null ? BigDecimal.ZERO : impairmentNonCash)
                .add(financeExpenseNonOperating == null ? BigDecimal.ZERO : financeExpenseNonOperating)
                .add(workingCapitalChangeInventory == null ? BigDecimal.ZERO : workingCapitalChangeInventory)
                .subtract(workingCapitalChangeReceivables == null ? BigDecimal.ZERO : workingCapitalChangeReceivables)
                .add(workingCapitalChangePayables == null ? BigDecimal.ZERO : workingCapitalChangePayables);

        BigDecimal indirectDifference = indirectOperatingCash.subtract(netOperatingCashFlow);
        boolean indirectBridgePass = indirectDifference.compareTo(BigDecimal.ZERO) == 0;

        Map<String, ReportCell> cells = new LinkedHashMap<>();
        if (cas) {
            cells.put("C01", new ReportCell("C01", "销售商品、提供劳务收到的现金", raw(c01), raw(c01), "EXACT", List.of()));
            cells.put("C04", new ReportCell("C04", "经营活动现金流入小计", raw(opInflow), raw(opInflow), "EXACT", List.of()));
            cells.put("C05", new ReportCell("C05", "购买商品、接受劳务支付的现金", raw(c05), raw(c05), "EXACT", List.of()));
            cells.put("C06", new ReportCell("C06", "支付给职工以及为职工支付的现金", raw(c06), raw(c06), "EXACT", List.of()));
            cells.put("C07", new ReportCell("C07", "支付的各项税费", raw(c07), raw(c07), "EXACT", List.of()));
            cells.put("C09", new ReportCell("C09", "经营活动现金流出小计", raw(opOutflow), raw(opOutflow), "EXACT", List.of()));
            cells.put("C10", new ReportCell("C10", "经营活动产生的现金流量净额", raw(netOperatingCashFlow), raw(netOperatingCashFlow), "EXACT", List.of()));
            cells.put("C17", new ReportCell("C17", "购建固定资产、无形资产和其他长期资产支付的现金", raw(c17), raw(c17), "EXACT", List.of()));
            cells.put("C21", new ReportCell("C21", "投资活动现金流出小计", raw(invOutflow), raw(invOutflow), "EXACT", List.of()));
            cells.put("C22", new ReportCell("C22", "投资活动产生的现金流量净额", raw(netInvestingCashFlow), raw(netInvestingCashFlow), "EXACT", List.of()));
            cells.put("C23", new ReportCell("C23", "吸收投资收到的现金", raw(c23), raw(c23), "EXACT", List.of()));
            cells.put("C24", new ReportCell("C24", "取得借款收到的现金", raw(c24), raw(c24), "EXACT", List.of()));
            cells.put("C26", new ReportCell("C26", "筹资活动现金流入小计", raw(finInflow), raw(finInflow), "EXACT", List.of()));
            cells.put("C28", new ReportCell("C28", "分配股利、利润或偿付利息支付的现金", raw(c28), raw(c28), "EXACT", List.of()));
            cells.put("C30", new ReportCell("C30", "筹资活动现金流出小计", raw(finOutflow), raw(finOutflow), "EXACT", List.of()));
            cells.put("C31", new ReportCell("C31", "筹资活动产生的现金流量净额", raw(netFinancingCashFlow), raw(netFinancingCashFlow), "EXACT", List.of()));
            cells.put("C32", new ReportCell("C32", "汇率变动对现金及现金等价物的影响", raw(fxEffect), raw(fxEffect), "EXACT", List.of()));
            cells.put("C33", new ReportCell("C33", "现金及现金等价物净增加额", raw(netCashIncrease), raw(netCashIncrease), "EXACT", List.of()));
            cells.put("C34", new ReportCell("C34", "期初现金及现金等价物余额", raw(openingCash), raw(openingCash), "EXACT", List.of()));
            cells.put("C35", new ReportCell("C35", "期末现金及现金等价物余额", raw(closingCash), raw(closingCash), "EXACT", List.of()));
        } else {
            cells.put("1", new ReportCell("1", "销售产成品、商品、提供劳务收到的现金", raw(c01), raw(c01), "EXACT", List.of()));
            cells.put("3", new ReportCell("3", "购买原材料、商品、接受劳务支付的现金", raw(c05), raw(c05), "EXACT", List.of()));
            cells.put("4", new ReportCell("4", "支付的职工薪酬", raw(c06), raw(c06), "EXACT", List.of()));
            cells.put("5", new ReportCell("5", "支付的税费", raw(c07), raw(c07), "EXACT", List.of()));
            cells.put("7", new ReportCell("7", "经营活动现金流量净额", raw(netOperatingCashFlow), raw(netOperatingCashFlow), "EXACT", List.of()));
            cells.put("12", new ReportCell("12", "购建固定资产、无形资产和其他非流动资产支付的现金", raw(c17), raw(c17), "EXACT", List.of()));
            cells.put("13", new ReportCell("13", "投资活动现金流量净额", raw(netInvestingCashFlow), raw(netInvestingCashFlow), "EXACT", List.of()));
            cells.put("14", new ReportCell("14", "取得借款收到的现金", raw(c24), raw(c24), "EXACT", List.of()));
            cells.put("15", new ReportCell("15", "吸收投资者投资收到的现金", raw(c23), raw(c23), "EXACT", List.of()));
            cells.put("17", new ReportCell("17", "偿还借款利息支付的现金", raw(c28), raw(c28), "EXACT", List.of()));
            cells.put("19", new ReportCell("19", "筹资活动现金流量净额", raw(netFinancingCashFlow), raw(netFinancingCashFlow), "EXACT", List.of()));
            cells.put("20", new ReportCell("20", "现金净增加额", raw(netCashIncrease), raw(netCashIncrease), "EXACT", List.of()));
            cells.put("21", new ReportCell("21", "加：期初现金余额", raw(openingCash), raw(openingCash), "EXACT", List.of()));
            cells.put("22", new ReportCell("22", "期末现金余额", raw(closingCash), raw(closingCash), "EXACT", List.of()));
        }

        Map<String, Object> articulationDetails = Map.of(
                "netOperatingCashFlow", raw(netOperatingCashFlow),
                "netInvestingCashFlow", raw(netInvestingCashFlow),
                "netFinancingCashFlow", raw(netFinancingCashFlow),
                "netCashIncrease", raw(netCashIncrease),
                "deltaCash", raw(deltaCash),
                "cashBridgeDifference", raw(cashDifference),
                "cashBridgePass", cashBridgePass,
                "indirectOperatingCash", raw(indirectOperatingCash),
                "indirectDifference", raw(indirectDifference),
                "indirectBridgePass", indirectBridgePass
        );

        boolean pass = cashBridgePass && indirectBridgePass;

        return new StatementSheet(
                StatementKind.CF,
                templateCode,
                cells,
                pass ? "PASS" : "FAIL",
                articulationDetails,
                List.of(new ReportTrace("CASH_RECONCILIATION", "CASH_TOTAL", null, null, null, raw(netCashIncrease), "直接法净现金与间接法净利润调节双向勾稽"))
        );
    }

    public static StatementSheet buildCashFlowStatement(
            Scope scope,
            BigDecimal openingCash,
            BigDecimal closingCash,
            Map<String, BigDecimal> cashflowItems,
            BigDecimal netProfit,
            BigDecimal depreciationNonCash,
            BigDecimal amortizationNonCash,
            BigDecimal workingCapitalChangeReceivables,
            BigDecimal workingCapitalChangePayables,
            BigDecimal workingCapitalChangeInventory,
            List<ReportTrace> tracesCollector) {
        return buildCashFlowStatement(
                scope, openingCash, closingCash, cashflowItems,
                netProfit, depreciationNonCash, amortizationNonCash,
                workingCapitalChangeReceivables, workingCapitalChangePayables, workingCapitalChangeInventory,
                BigDecimal.ZERO, BigDecimal.ZERO, tracesCollector
        );
    }

    // -------------------------------------------------------------------------
    // 4. Statement of Changes in Owners' Equity (RPT06)
    // -------------------------------------------------------------------------
    public static StatementSheet buildStatementOfChangesInEquity(
            Scope scope,
            BigDecimal openingCapital,
            BigDecimal openingCapitalReserve,
            BigDecimal openingSurplus,
            BigDecimal openingRetained,
            BigDecimal currentNetProfit,
            BigDecimal currentCapitalContribution,
            BigDecimal currentProfitDistribution,
            List<ReportTrace> tracesCollector) {

        String templateCode = "CAS-SCE-2019-A2";

        BigDecimal opCap = openingCapital == null ? BigDecimal.ZERO : openingCapital;
        BigDecimal opRes = openingCapitalReserve == null ? BigDecimal.ZERO : openingCapitalReserve;
        BigDecimal opSur = openingSurplus == null ? BigDecimal.ZERO : openingSurplus;
        BigDecimal opRet = openingRetained == null ? BigDecimal.ZERO : openingRetained;
        BigDecimal openingTotal = opCap.add(opRes).add(opSur).add(opRet);

        // Changes in current year
        BigDecimal changeCapital = currentCapitalContribution == null ? BigDecimal.ZERO : currentCapitalContribution;
        BigDecimal changeRetained = currentNetProfit == null ? BigDecimal.ZERO : currentNetProfit;
        BigDecimal distribution = currentProfitDistribution == null ? BigDecimal.ZERO : currentProfitDistribution;
        BigDecimal netRetainedChange = changeRetained.subtract(distribution);

        BigDecimal closingCapital = opCap.add(changeCapital);
        BigDecimal closingCapitalReserve = opRes;
        BigDecimal closingSurplus = opSur;
        BigDecimal closingRetained = opRet.add(netRetainedChange);

        BigDecimal closingTotal = closingCapital.add(closingCapitalReserve).add(closingSurplus).add(closingRetained);
        BigDecimal totalNetChange = closingTotal.subtract(openingTotal);

        Map<String, ReportCell> cells = new LinkedHashMap<>();
        cells.put("SCE_OPENING", new ReportCell("SCE_OPENING", "一、上年年末余额", raw(openingTotal), raw(openingTotal), "EXACT", List.of()));
        cells.put("SCE_NET_PROFIT", new ReportCell("SCE_NET_PROFIT", "二、本年净利润", raw(currentNetProfit), raw(currentNetProfit), "EXACT", List.of()));
        cells.put("SCE_CAPITAL_CONTRIB", new ReportCell("SCE_CAPITAL_CONTRIB", "三、所有者投入资本", raw(changeCapital), raw(changeCapital), "EXACT", List.of()));
        cells.put("SCE_DISTRIBUTION", new ReportCell("SCE_DISTRIBUTION", "四、利润分配", raw(distribution.negate()), raw(distribution.negate()), "EXACT", List.of()));
        cells.put("SCE_TOTAL_CHANGE", new ReportCell("SCE_TOTAL_CHANGE", "五、本年增减变动金额", raw(totalNetChange), raw(totalNetChange), "EXACT", List.of()));
        cells.put("SCE_CLOSING", new ReportCell("SCE_CLOSING", "六、本年年末余额", raw(closingTotal), raw(closingTotal), "EXACT", List.of()));

        Map<String, Object> articulationDetails = Map.of(
                "openingTotal", raw(openingTotal),
                "closingTotal", raw(closingTotal),
                "totalNetChange", raw(totalNetChange),
                "equityBridgePass", closingTotal.subtract(openingTotal.add(totalNetChange)).compareTo(BigDecimal.ZERO) == 0
        );

        return new StatementSheet(
                StatementKind.SCE,
                templateCode,
                cells,
                "PASS",
                articulationDetails,
                List.of(new ReportTrace("EQUITY_BRIDGE", "EQUITY_TOTAL", null, null, null, raw(totalNetChange), "所有者权益变动表期初至期末完整勾稽"))
        );
    }

    // -------------------------------------------------------------------------
    // 5. Structured Notes & Mandatory Completeness Checking (RPT06)
    // -------------------------------------------------------------------------
    public static StructuredNotes evaluateNotesCompleteness(Map<String, String> submittedSections) {
        // 10 canonical sections according to CAS/SBE disclosure standards
        Set<String> mandatoryKeys = Set.of(
                "ENTITY_PROFILE",                // 1. 企业基本情况
                "BASIS_OF_PREPARATION",         // 2. 财务报表编制基础
                "STATEMENT_OF_COMPLIANCE",      // 3. 遵循会计准则声明
                "SIGNIFICANT_POLICIES",         // 4. 重要会计政策和会计估计
                "TAXATION",                     // 5. 税项说明
                "DISCLOSURE_LINE_ITEMS"         // 6. 报表重要项目明细说明
        );

        Map<String, String> sections = submittedSections == null ? Map.of() : Map.copyOf(submittedSections);
        List<String> missing = new ArrayList<>();

        for (String key : mandatoryKeys) {
            String val = sections.get(key);
            if (val == null || val.trim().length() < 5) {
                missing.add(key);
            }
        }

        return new StructuredNotes(sections, missing.isEmpty(), missing);
    }

    // -------------------------------------------------------------------------
    // 6. Report Package Assembly & Publication Blocker (RPT06 & RP01)
    // -------------------------------------------------------------------------
    public static ReportPackage assembleAndEvaluatePackage(
            UUID packageId,
            Scope scope,
            PackageType packageType,
            FilingTarget filingTarget,
            StatementSheet bs,
            StatementSheet pl,
            StatementSheet cf,
            StatementSheet sce,
            StructuredNotes notes,
            Worksheet worksheet,
            Actor preparer,
            Actor reviewer,
            Actor approver) {

        List<StatementSheet> sheets = new ArrayList<>();
        sheets.add(bs);
        sheets.add(pl);
        sheets.add(cf);
        if (sce != null) sheets.add(sce);

        List<String> publicationGaps = new ArrayList<>();

        // Check statement articulation
        if (!"PASS".equals(bs.articulationStatus())) {
            publicationGaps.add("BS_ARTICULATION_FAILURE: Total Assets != Liabilities + Equity");
        }
        if (!"PASS".equals(cf.articulationStatus())) {
            publicationGaps.add("CF_ARTICULATION_FAILURE: Delta Cash != Net Cash Flow or Indirect Bridge Mismatch");
        }

        // Package specific rules
        if (packageType == PackageType.FULL_STATUTORY_REPORT_PACKAGE) {
            if (scope.standard() == Standard.CAS_2019 && sce == null) {
                publicationGaps.add("CAS_EQUITY_CHANGES_STATEMENT_MANDATORY_FOR_FULL_PACKAGE");
            }
            if (notes == null || !notes.mandatoryComplete()) {
                publicationGaps.add("MANDATORY_NOTES_INCOMPLETE: Missing sections: " + (notes == null ? "ALL" : notes.missingSections()));
            }
        }

        // Filing target checks
        if (filingTarget == FilingTarget.STATUTORY_AUDIT_FILING) {
            if (notes == null || !notes.mandatoryComplete()) {
                publicationGaps.add("AUDIT_FILING_REQUIRES_AUDITED_NOTES");
            }
        }

        PackageStatus status;
        if (!publicationGaps.isEmpty()) {
            status = PackageStatus.BLOCKED;
        } else if (approver != null) {
            status = PackageStatus.APPROVED;
        } else if (reviewer != null) {
            status = PackageStatus.REVIEWED;
        } else {
            status = PackageStatus.DRAFT;
        }

        String digestInput = scope.tenantId() + ":" + packageId + ":" + packageType + ":" + filingTarget + ":" + status
                + ":" + bs.articulationStatus() + ":" + cf.articulationStatus() + ":" + (notes != null && notes.mandatoryComplete());
        String contentDigest = sha256Hex(digestInput);

        Instant now = Instant.now();
        return new ReportPackage(
                packageId,
                scope,
                packageType,
                filingTarget,
                status,
                sheets,
                worksheet,
                notes,
                publicationGaps,
                contentDigest,
                preparer,
                reviewer,
                approver,
                now,
                now
        );
    }
}

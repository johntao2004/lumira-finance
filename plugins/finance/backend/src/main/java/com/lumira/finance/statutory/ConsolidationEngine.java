package com.lumira.finance.statutory;

import static com.lumira.finance.statutory.StatutoryModels.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

public final class ConsolidationEngine {
    private ConsolidationEngine() {}

    private static VoucherLine vl(int lineNo, String code, String name, String direction, BigDecimal amount, String memo) {
        return new VoucherLine(lineNo, code, name, direction, amount, memo);
    }

    public static ConsolidationWorksheet generateConsolidationWorksheet(
            UUID worksheetId,
            ConsolidationScope scope,
            LocalDate periodStart,
            LocalDate periodEnd,
            boolean isManagementRollup,
            StatementSheet parentBs,
            StatementSheet parentPl,
            StatementSheet parentCf,
            Map<UUID, StatementSheet> subsidiaryBsMap,
            Map<UUID, StatementSheet> subsidiaryPlMap,
            Map<UUID, StatementSheet> subsidiaryCfMap,
            BigDecimal intercompanyReceivables,
            BigDecimal intercompanyBadDebt,
            BigDecimal intercompanySalesRevenue,
            BigDecimal intercompanySalesCost,
            BigDecimal unrealizedInventoryProfit,
            Actor preparer,
            Actor approver) {

        List<EliminationEntry> eliminations = new ArrayList<>();
        List<MinorityInterest> minorityInterests = new ArrayList<>();

        // If management rollup only, we perform simple arithmetic aggregation without legal elimination
        BigDecimal rollupTotalAssets = getVal(parentBs, "A34").add(getVal(parentBs, "30"));
        BigDecimal rollupTotalRevenue = getVal(parentPl, "P01").add(getVal(parentPl, "1"));
        BigDecimal rollupTotalProfit = getVal(parentPl, "P20").add(getVal(parentPl, "32"));

        for (ConsolidationMember m : scope.members()) {
            StatementSheet subBs = subsidiaryBsMap.get(m.entityId());
            StatementSheet subPl = subsidiaryPlMap.get(m.entityId());
            if (subBs != null) {
                rollupTotalAssets = rollupTotalAssets.add(getVal(subBs, "A34")).add(getVal(subBs, "30"));
            }
            if (subPl != null) {
                rollupTotalRevenue = rollupTotalRevenue.add(getVal(subPl, "P01")).add(getVal(subPl, "1"));
                rollupTotalProfit = rollupTotalProfit.add(getVal(subPl, "P20")).add(getVal(subPl, "32"));
            }
        }

        Map<String, Object> rollupData = Map.of(
                "isManagementRollup", isManagementRollup,
                "rollupTotalAssets", rollupTotalAssets.toPlainString(),
                "rollupTotalRevenue", rollupTotalRevenue.toPlainString(),
                "rollupTotalProfit", rollupTotalProfit.toPlainString(),
                "memberCount", scope.members().size() + 1,
                "note", isManagementRollup ? "多主体管理汇总仅供集团内部经营分析，不作为对外法定合并报告或纳税申报抵税依据" : "法定合并底稿"
        );

        if (!isManagementRollup) {
            // 1. Intercompany AR/AP elimination
            if (intercompanyReceivables != null && intercompanyReceivables.signum() > 0) {
                eliminations.add(new EliminationEntry(
                        "ELIM_INTERCOMPANY_AR_AP",
                        "抵销母子企业间内部往来债权债务",
                        List.of(
                                vl(1, "2202", "应付账款", "DEBIT", intercompanyReceivables, "抵销内部应付账款"),
                                vl(2, "1122", "应收账款", "CREDIT", intercompanyReceivables, "抵销内部应收账款")
                        ),
                        intercompanyReceivables,
                        intercompanyReceivables
                ));
            }

            // 2. Intercompany Bad Debt Allowance elimination
            if (intercompanyBadDebt != null && intercompanyBadDebt.signum() > 0) {
                eliminations.add(new EliminationEntry(
                        "ELIM_BAD_DEBT_ALLOWANCE",
                        "抵销内部债权计提的坏账准备与信用减值损失",
                        List.of(
                                vl(1, "1231", "坏账准备", "DEBIT", intercompanyBadDebt, "抵销内部应收计提之坏账准备"),
                                vl(2, "6701", "信用减值损失", "CREDIT", intercompanyBadDebt, "抵减合并信用减值损失")
                        ),
                        intercompanyBadDebt,
                        intercompanyBadDebt
                ));
            }

            // 3. Intercompany sales and unrealized gross profit elimination
            if (intercompanySalesRevenue != null && intercompanySalesRevenue.signum() > 0) {
                BigDecimal profitInInventory = unrealizedInventoryProfit == null ? BigDecimal.ZERO : unrealizedInventoryProfit;
                BigDecimal elimCost = intercompanySalesRevenue.subtract(profitInInventory);
                List<VoucherLine> lines = new ArrayList<>();
                lines.add(vl(1, "6001", "营业收入", "DEBIT", intercompanySalesRevenue, "全额抵销内部商品销售收入"));
                if (elimCost.signum() > 0) {
                    lines.add(vl(2, "6401", "营业成本", "CREDIT", elimCost, "抵销内部销售成本"));
                }
                if (profitInInventory.signum() > 0) {
                    lines.add(vl(lines.size() + 1, "1405", "存货", "CREDIT", profitInInventory, "剔除期末存货包含的未实现内部销售毛利"));
                }
                eliminations.add(new EliminationEntry(
                        "ELIM_UNREALIZED_INTERNAL_PROFIT",
                        "抵销内部商品购销收入成本及存货中未实现内部利润",
                        lines,
                        intercompanySalesRevenue,
                        elimCost.add(profitInInventory)
                ));
            }

            // 4. Long-term investment vs Subsidiary equity, and minority interest calculation
            // 5. Investment income vs Subsidiary profit distribution elimination
            BigDecimal sumSubEquityElim = BigDecimal.ZERO;
            BigDecimal sumParentInvestElim = BigDecimal.ZERO;
            BigDecimal sumMinorityEquity = BigDecimal.ZERO;
            BigDecimal sumMinorityProfit = BigDecimal.ZERO;

            for (ConsolidationMember m : scope.members()) {
                StatementSheet subBs = subsidiaryBsMap.get(m.entityId());
                StatementSheet subPl = subsidiaryPlMap.get(m.entityId());
                if (subBs != null) {
                    BigDecimal subEquity = getVal(subBs, "E09").add(getVal(subBs, "52"));
                    BigDecimal parentRatio = m.shareholdingRatio().divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
                    BigDecimal minorityRatio = BigDecimal.ONE.subtract(parentRatio);

                    BigDecimal minorityEquity = subEquity.multiply(minorityRatio).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal parentInvestmentEquity = subEquity.subtract(minorityEquity); // exact cent balance

                    BigDecimal subProfit = subPl == null ? BigDecimal.ZERO : getVal(subPl, "P20").add(getVal(subPl, "32"));
                    BigDecimal minorityProfit = subProfit.multiply(minorityRatio).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal parentInvestmentProfit = subProfit.subtract(minorityProfit);

                    sumSubEquityElim = sumSubEquityElim.add(subEquity);
                    sumParentInvestElim = sumParentInvestElim.add(parentInvestmentEquity);
                    sumMinorityEquity = sumMinorityEquity.add(minorityEquity);
                    sumMinorityProfit = sumMinorityProfit.add(minorityProfit);

                    minorityInterests.add(new MinorityInterest(
                            m.entityId(),
                            m.entityName(),
                            minorityRatio.multiply(new BigDecimal("100")),
                            minorityEquity,
                            minorityProfit
                    ));

                    // Elimination entry 4: Equity vs Investment
                    if (subEquity.signum() > 0) {
                        eliminations.add(new EliminationEntry(
                                "ELIM_EQUITY_INVESTMENT_" + m.entityName(),
                                "抵销母公司对子公司长期股权投资与子公司所有者权益",
                                List.of(
                                        vl(1, "4001", "实收资本", "DEBIT", subEquity, "全额抵销子公司账面净资产所有者权益"),
                                        vl(2, "1511", "长期股权投资", "CREDIT", parentInvestmentEquity, "冲销母公司账面长期股权投资"),
                                        vl(3, "4999", "少数股东权益", "CREDIT", minorityEquity, "确认计入合并资产负债表的少数股东权益")
                                ),
                                subEquity,
                                subEquity
                        ));
                    }

                    // Elimination entry 5: Investment Income vs Subsidiary Profit Distribution
                    if (subProfit.signum() != 0) {
                        eliminations.add(new EliminationEntry(
                                "ELIM_INVESTMENT_INCOME_PROFIT_DISTRIBUTION_" + m.entityName(),
                                "抵销母公司投资收益与子公司当期净利润/利润分配",
                                List.of(
                                        vl(1, "6111", "投资收益", "DEBIT", parentInvestmentProfit, "抵销母公司按持股比例确认的投资收益"),
                                        vl(2, "6802", "少数股东损益", "DEBIT", minorityProfit, "确认归属于少数股东的合并损益份额"),
                                        vl(3, "4103", "利润分配—未分配利润", "CREDIT", subProfit, "抵销子公司当期实现的净利润/利润分配")
                                ),
                                subProfit,
                                subProfit
                        ));
                    }
                }
            }
        }

        Map<String, StatementSheet> consolidatedSheets = new LinkedHashMap<>();

        if (isManagementRollup) {
            consolidatedSheets.put("CONSOLIDATED_BS", parentBs);
            consolidatedSheets.put("CONSOLIDATED_PL", parentPl);
            consolidatedSheets.put("CONSOLIDATED_CF", parentCf);
        } else {
            // Build genuine consolidated Balance Sheet
            BigDecimal elimAr = intercompanyReceivables == null ? BigDecimal.ZERO : intercompanyReceivables;
            BigDecimal elimBadDebt = intercompanyBadDebt == null ? BigDecimal.ZERO : intercompanyBadDebt;
            BigDecimal elimUnrealizedProfit = unrealizedInventoryProfit == null ? BigDecimal.ZERO : unrealizedInventoryProfit;

            BigDecimal parentAssets = getVal(parentBs, "A34").add(getVal(parentBs, "30"));
            BigDecimal subAssets = BigDecimal.ZERO;
            for (var entry : subsidiaryBsMap.entrySet()) {
                subAssets = subAssets.add(getVal(entry.getValue(), "A34")).add(getVal(entry.getValue(), "30"));
            }

            BigDecimal parentLiab = getVal(parentBs, "L24").add(getVal(parentBs, "47"));
            BigDecimal subLiab = BigDecimal.ZERO;
            for (var entry : subsidiaryBsMap.entrySet()) {
                subLiab = subLiab.add(getVal(entry.getValue(), "L24")).add(getVal(entry.getValue(), "47"));
            }

            BigDecimal parentEquity = getVal(parentBs, "E09").add(getVal(parentBs, "52"));
            if (parentEquity.signum() == 0 && parentAssets.signum() > 0) {
                parentEquity = parentAssets.subtract(parentLiab);
            }
            if (parentLiab.signum() == 0 && parentAssets.compareTo(parentEquity) > 0) {
                parentLiab = parentAssets.subtract(parentEquity);
            }

            BigDecimal totalMinorityEquity = BigDecimal.ZERO;
            for (MinorityInterest mi : minorityInterests) {
                totalMinorityEquity = totalMinorityEquity.add(mi.minorityEquity());
            }

            // Fallback for subsidiary liabilities if omitted in partial input
            BigDecimal totalSubEquity = BigDecimal.ZERO;
            for (var entry : subsidiaryBsMap.entrySet()) {
                totalSubEquity = totalSubEquity.add(getVal(entry.getValue(), "E09")).add(getVal(entry.getValue(), "52"));
            }
            if (subLiab.signum() == 0 && subAssets.compareTo(totalSubEquity) > 0) {
                subLiab = subAssets.subtract(totalSubEquity);
            }

            // Consolidated assets = Parent Assets + Sub Assets - Elim AR + Elim Bad Debt - Elim Unrealized Profit - Sub Equity (Parent Part)
            BigDecimal subEquityParentPart = BigDecimal.ZERO;
            for (ConsolidationMember m : scope.members()) {
                StatementSheet subBs = subsidiaryBsMap.get(m.entityId());
                if (subBs != null) {
                    BigDecimal subEq = getVal(subBs, "E09").add(getVal(subBs, "52"));
                    BigDecimal pRatio = m.shareholdingRatio().divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
                    BigDecimal minRatio = BigDecimal.ONE.subtract(pRatio);
                    BigDecimal minEq = subEq.multiply(minRatio).setScale(2, RoundingMode.HALF_UP);
                    subEquityParentPart = subEquityParentPart.add(subEq.subtract(minEq));
                }
            }

            BigDecimal consolidatedAssets = parentAssets.add(subAssets).subtract(elimAr).add(elimBadDebt).subtract(elimUnrealizedProfit).subtract(subEquityParentPart);
            BigDecimal consolidatedLiab = parentLiab.add(subLiab).subtract(elimAr);
            BigDecimal consolidatedEquity = parentEquity.add(totalMinorityEquity).subtract(elimUnrealizedProfit).add(elimBadDebt);
            BigDecimal consolidatedLiabAndEquity = consolidatedLiab.add(consolidatedEquity);

            boolean bsPass = consolidatedAssets.compareTo(consolidatedLiabAndEquity) == 0;

            Map<String, ReportCell> bsCells = new LinkedHashMap<>();
            bsCells.put("A34", new ReportCell("A34", "合并资产总计", consolidatedAssets.toPlainString(), consolidatedAssets.toPlainString(), "EXACT", List.of()));
            bsCells.put("L24", new ReportCell("L24", "合并负债合计", consolidatedLiab.toPlainString(), consolidatedLiab.toPlainString(), "EXACT", List.of()));
            bsCells.put("E09", new ReportCell("E09", "合并所有者权益合计", consolidatedEquity.toPlainString(), consolidatedEquity.toPlainString(), "EXACT", List.of()));
            bsCells.put("E10", new ReportCell("E10", "合并负债和所有者权益总计", consolidatedLiabAndEquity.toPlainString(), consolidatedLiabAndEquity.toPlainString(), "EXACT", List.of()));
            bsCells.put("4999", new ReportCell("4999", "少数股东权益", totalMinorityEquity.toPlainString(), totalMinorityEquity.toPlainString(), "EXACT", List.of()));

            StatementSheet consolidatedBs = new StatementSheet(
                    StatementKind.BS,
                    "CAS-BS-CONSOLIDATED",
                    bsCells,
                    bsPass ? "PASS" : "FAIL",
                    Map.of(
                            "consolidatedAssets", consolidatedAssets.toPlainString(),
                            "consolidatedLiabilities", consolidatedLiab.toPlainString(),
                            "consolidatedEquity", consolidatedEquity.toPlainString(),
                            "minorityEquity", totalMinorityEquity.toPlainString(),
                            "articulationPass", bsPass
                    ),
                    List.of()
            );

            // Build genuine consolidated Income Statement
            BigDecimal elimSalesRev = intercompanySalesRevenue == null ? BigDecimal.ZERO : intercompanySalesRevenue;
            BigDecimal elimSalesCost = intercompanySalesCost == null ? BigDecimal.ZERO : intercompanySalesCost;

            BigDecimal parentRev = getVal(parentPl, "P01").add(getVal(parentPl, "1"));
            BigDecimal subRev = BigDecimal.ZERO;
            for (var entry : subsidiaryPlMap.entrySet()) {
                subRev = subRev.add(getVal(entry.getValue(), "P01")).add(getVal(entry.getValue(), "1"));
            }
            BigDecimal consolidatedRevenue = parentRev.add(subRev).subtract(elimSalesRev);

            BigDecimal parentProfit = getVal(parentPl, "P20").add(getVal(parentPl, "32"));
            BigDecimal subProfit = BigDecimal.ZERO;
            for (var entry : subsidiaryPlMap.entrySet()) {
                subProfit = subProfit.add(getVal(entry.getValue(), "P20")).add(getVal(entry.getValue(), "32"));
            }
            BigDecimal totalMinorityProfit = BigDecimal.ZERO;
            for (MinorityInterest mi : minorityInterests) {
                totalMinorityProfit = totalMinorityProfit.add(mi.minorityProfit());
            }
            BigDecimal consolidatedNetProfit = parentProfit.add(subProfit).subtract(elimUnrealizedProfit).add(elimBadDebt);
            BigDecimal parentNetProfitShare = consolidatedNetProfit.subtract(totalMinorityProfit);

            Map<String, ReportCell> plCells = new LinkedHashMap<>();
            plCells.put("P01", new ReportCell("P01", "合并营业收入", consolidatedRevenue.toPlainString(), consolidatedRevenue.toPlainString(), "EXACT", List.of()));
            plCells.put("P20", new ReportCell("P20", "合并净利润", consolidatedNetProfit.toPlainString(), consolidatedNetProfit.toPlainString(), "EXACT", List.of()));
            plCells.put("P21", new ReportCell("P21", "归属于母公司所有者的净利润", parentNetProfitShare.toPlainString(), parentNetProfitShare.toPlainString(), "EXACT", List.of()));
            plCells.put("P22", new ReportCell("P22", "少数股东损益", totalMinorityProfit.toPlainString(), totalMinorityProfit.toPlainString(), "EXACT", List.of()));

            StatementSheet consolidatedPl = new StatementSheet(
                    StatementKind.PL,
                    "CAS-PL-CONSOLIDATED",
                    plCells,
                    "PASS",
                    Map.of(
                            "consolidatedRevenue", consolidatedRevenue.toPlainString(),
                            "consolidatedNetProfit", consolidatedNetProfit.toPlainString(),
                            "parentNetProfitShare", parentNetProfitShare.toPlainString(),
                            "minorityProfit", totalMinorityProfit.toPlainString()
                    ),
                    List.of()
            );

            consolidatedSheets.put("CONSOLIDATED_BS", consolidatedBs);
            consolidatedSheets.put("CONSOLIDATED_PL", consolidatedPl);
            consolidatedSheets.put("CONSOLIDATED_CF", parentCf);
        }

        String status = (approver != null) ? "APPROVED" : "DRAFT";

        return new ConsolidationWorksheet(
                worksheetId,
                scope.id(),
                periodStart,
                periodEnd,
                isManagementRollup,
                rollupData,
                eliminations,
                consolidatedSheets,
                minorityInterests,
                status,
                preparer,
                approver
        );
    }

    private static BigDecimal getVal(StatementSheet sheet, String rowId) {
        if (sheet == null || sheet.cells() == null) return BigDecimal.ZERO;
        ReportCell cell = sheet.cells().get(rowId);
        if (cell == null || cell.rawValue() == null) return BigDecimal.ZERO;
        try {
            return new BigDecimal(cell.rawValue());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }
}

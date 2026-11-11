package com.lumira.finance.payrolltax;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TAX05: Conditional schedule attachment checker (必要附表缺失阻断).
 * Evaluates declaration triggers on main form figures and determines whether
 * statutory attachments/worksheets are required. Blocks completion if missing.
 */
public final class MandatoryScheduleChecker {

    private MandatoryScheduleChecker() {}

    public record ScheduleRequirement(
            String scheduleCode,
            String scheduleName,
            String triggerCondition,
            String statutoryCitation
    ) implements Serializable {}

    public record ValidationResult(
            boolean passed,
            List<ScheduleRequirement> requiredSchedules,
            List<ScheduleRequirement> missingSchedules
    ) implements Serializable {
        public ValidationResult {
            requiredSchedules = Collections.unmodifiableList(requiredSchedules);
            missingSchedules = Collections.unmodifiableList(missingSchedules);
        }

        public void assertAllPresent() {
            if (!passed) {
                List<String> codes = missingSchedules.stream().map(ScheduleRequirement::scheduleCode).toList();
                throw new IllegalStateException("MISSING_MANDATORY_SCHEDULE_ATTACHMENT: Missing mandatory schedules: " + codes);
            }
        }
    }

    /**
     * Checks conditional attachments for VAT or CIT declarations.
     *
     * @param formType "VAT_GENERAL" or "CIT_ANNUAL"
     * @param figures Map of field amounts in the main return
     * @param attachedScheduleCodes Set of schedule codes actually uploaded/attached
     */
    public static ValidationResult validateAttachments(
            String formType,
            Map<String, BigDecimal> figures,
            Set<String> attachedScheduleCodes
    ) {
        List<ScheduleRequirement> required = new ArrayList<>();

        if ("VAT_GENERAL".equalsIgnoreCase(formType)) {
            // Trigger 1: Export sales > 0 -> Schedule 1 Export Tax Rebate Worksheet
            BigDecimal exportSales = figures.getOrDefault("exportSales", BigDecimal.ZERO);
            if (exportSales.compareTo(BigDecimal.ZERO) > 0) {
                required.add(new ScheduleRequirement(
                        "ATTACHMENT_VAT_EXPORT",
                        "增值税纳税申报表附列资料（一）（本期免税、退税及出口销售额明细）",
                        "exportSales > 0",
                        "VAT_Declaration_Rules_Sched1"
                ));
            }

            // Trigger 2: Input tax deduction / transfer out > 0 -> Schedule 2 Input Tax Worksheet
            BigDecimal inputDeduction = figures.getOrDefault("inputDeduction", BigDecimal.ZERO);
            BigDecimal inputTransferOut = figures.getOrDefault("inputTransferOut", BigDecimal.ZERO);
            if (inputDeduction.compareTo(BigDecimal.ZERO) > 0 || inputTransferOut.compareTo(BigDecimal.ZERO) > 0) {
                required.add(new ScheduleRequirement(
                        "ATTACHMENT_VAT_INPUT_DEDUCTION",
                        "增值税纳税申报表附列资料（二）（本期进项税额明细）",
                        "inputDeduction > 0 || inputTransferOut > 0",
                        "VAT_Declaration_Rules_Sched2"
                ));
            }

            // Trigger 3: VAT tax reduction / relief claimed > 0 -> Schedule 3 Tax Relief Worksheet
            BigDecimal taxReduction = figures.getOrDefault("taxReduction", BigDecimal.ZERO);
            if (taxReduction.compareTo(BigDecimal.ZERO) > 0) {
                required.add(new ScheduleRequirement(
                        "ATTACHMENT_VAT_REDUCTION",
                        "增值税减免税申报明细表",
                        "taxReduction > 0",
                        "VAT_Declaration_Rules_ReliefSched"
                ));
            }
        } else if ("CIT_ANNUAL".equalsIgnoreCase(formType)) {
            // Trigger 1: R&D expense super-deduction claimed > 0 -> Schedule A107012
            BigDecimal rdSuperDeduction = figures.getOrDefault("rdSuperDeduction", BigDecimal.ZERO);
            if (rdSuperDeduction.compareTo(BigDecimal.ZERO) > 0) {
                required.add(new ScheduleRequirement(
                        "ATTACHMENT_CIT_A107012_RD",
                        "A107012 研发费用加计扣除优惠明细表",
                        "rdSuperDeduction > 0",
                        "CIT_Declaration_Rules_A107012"
                ));
            }

            // Trigger 2: Advertising / business publicity expenses > 0 -> Schedule A105060
            BigDecimal advertisingExpense = figures.getOrDefault("advertisingExpense", BigDecimal.ZERO);
            if (advertisingExpense.compareTo(BigDecimal.ZERO) > 0) {
                required.add(new ScheduleRequirement(
                        "ATTACHMENT_CIT_A105060_ADVERTISING",
                        "A105060 广告费和业务宣传费等跨年度纳税调整明细表",
                        "advertisingExpense > 0",
                        "CIT_Declaration_Rules_A105060"
                ));
            }

            // Trigger 3: Charitable donations > 0 -> Schedule A105070
            BigDecimal donationExpense = figures.getOrDefault("donationExpense", BigDecimal.ZERO);
            if (donationExpense.compareTo(BigDecimal.ZERO) > 0) {
                required.add(new ScheduleRequirement(
                        "ATTACHMENT_CIT_A105070_DONATION",
                        "A105070 公益性捐赠支出纳税调整明细表",
                        "donationExpense > 0",
                        "CIT_Declaration_Rules_A105070"
                ));
            }

            // Trigger 4: Small-low-profit relief claimed -> Schedule A100000 micro-enterprise qualification
            BigDecimal smallLowProfitRelief = figures.getOrDefault("smallLowProfitRelief", BigDecimal.ZERO);
            if (smallLowProfitRelief.compareTo(BigDecimal.ZERO) > 0) {
                required.add(new ScheduleRequirement(
                        "ATTACHMENT_CIT_A100000_QUALIFICATION",
                        "A100000 中华人民共和国企业所得税年度纳税申报表（A类）符合小型微利企业条件信息",
                        "smallLowProfitRelief > 0",
                        "CIT_Declaration_Rules_A100000"
                ));
            }
        }

        List<ScheduleRequirement> missing = required.stream()
                .filter(req -> attachedScheduleCodes == null || !attachedScheduleCodes.contains(req.scheduleCode()))
                .toList();

        return new ValidationResult(missing.isEmpty(), required, missing);
    }
}

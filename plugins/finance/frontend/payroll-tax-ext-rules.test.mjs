import test from "node:test";
import assert from "node:assert/strict";
import {
  computeBonusSeparate,
  computeBonusSynergy,
  computeLaborRemuneration,
  computeFilingDeadline,
  checkMandatorySchedules,
} from "./payroll-tax-ext-rules.ts";

test("PAY03: 全年一次性奖金按月换算商数单独计税正确覆盖多个速算级距", () => {
  // 36,000元 -> 商数3,000 -> 3% -> 个税 1,080.00
  const r1 = computeBonusSeparate("36000.00");
  assert.equal(r1.monthlyQuotient, "3000.00");
  assert.equal(r1.applicableRate, "0.03");
  assert.equal(r1.quickDeduction, "0.00");
  assert.equal(r1.withholdingTax, "1080.00");
  assert.equal(r1.netAmount, "34920.00");

  // 120,000元 -> 商数10,000 -> 10% (速算扣除数210) -> 个税 120,000*0.1 - 210 = 11,790.00
  const r2 = computeBonusSeparate("120000.00");
  assert.equal(r2.monthlyQuotient, "10000.00");
  assert.equal(r2.applicableRate, "0.10");
  assert.equal(r2.quickDeduction, "210.00");
  assert.equal(r2.withholdingTax, "11790.00");
  assert.equal(r2.netAmount, "108210.00");

  // 300,000元 -> 商数25,000 -> 20% (速算扣除数1410) -> 个税 300,000*0.2 - 1410 = 58,590.00
  const r3 = computeBonusSeparate("300000.00");
  assert.equal(r3.monthlyQuotient, "25000.00");
  assert.equal(r3.applicableRate, "0.20");
  assert.equal(r3.quickDeduction, "1410.00");
  assert.equal(r3.withholdingTax, "58590.00");
});

test("PAY03: 年度汇算清缴协同对比预览能够精准计算两方案总税负及节税推荐", () => {
  // 年应税工资 200,000元，年终奖 60,000元
  // 方案A(单独计税): 工资税 23,080 + 奖金税 5,790 = 28,870
  // 方案B(并入综合): 260,000 * 0.2 - 16,920 = 35,080
  // 差额 6,210元，推荐单独计税
  const syn = computeBonusSynergy("60000.00", "200000.00");
  assert.equal(syn.recommendedMode, "SEPARATE_MONTHLY_QUOTIENT");
  assert.equal(syn.taxSavings, "6210.00");
  assert.ok(syn.summary.includes("节税 6210.00元"));
});

test("PAY04: 自然人劳务报酬分别验证≤4000扣800与>4000扣20%以及20%-30%-40%累进税率", () => {
  // 3,000元 -> 扣800 -> 应税 2,200 -> 20% -> 个税 440.00
  const l1 = computeLaborRemuneration("3000.00", "LABOR_SERVICE", {
    relief1Pct: true,
    cityRate: "0.07",
  });
  assert.equal(l1.deductionAmount, "800.00");
  assert.equal(l1.taxableIncome, "2200.00");
  assert.equal(l1.iitWithholding, "440.00");
  // 增值税：3,000 >= 1,000 -> 1%减征 -> 增值税 30.00
  assert.equal(l1.vatWithheld, "30.00");
  // 城建税：30 * 0.07 = 2.10
  assert.equal(l1.citySurtax, "2.10");

  // 30,000元 -> 扣20% (6,000) -> 应税 24,000 -> 30% (速算2000) -> 24000*0.3 - 2000 = 5,200.00
  const l2 = computeLaborRemuneration("30000.00", "LABOR_SERVICE", {
    relief1Pct: true,
  });
  assert.equal(l2.deductionAmount, "6000.00");
  assert.equal(l2.taxableIncome, "24000.00");
  assert.equal(l2.iitRate, "0.30");
  assert.equal(l2.iitWithholding, "5200.00");
});

test("PAY04: 稿酬所得享受法定减计30%与特许权使用费平税20%", () => {
  // 稿酬 10,000元 -> 扣20% (2,000) -> 初步应税 8,000 -> 减计30% (70%) -> 最终应税 5,600 -> 20% -> 个税 1,120.00
  const author = computeLaborRemuneration("10000.00", "AUTHOR_REMUNERATION");
  assert.equal(author.taxableIncome, "5600.00");
  assert.equal(author.iitWithholding, "1120.00");

  // 特许权 10,000元 -> 扣20% (2,000) -> 应税 8,000 -> 20% -> 个税 1,600.00
  const royalty = computeLaborRemuneration("10000.00", "ROYALTY");
  assert.equal(royalty.taxableIncome, "8000.00");
  assert.equal(royalty.iitWithholding, "1600.00");
});

test("PAY04: 自然人小额1000元增值税起征点及已完成税局代开抑制扣缴", () => {
  // 800元 < 1000元起征点 -> 增值税免税 (vatExempt = true, vatWithheld = 0.00)
  const small = computeLaborRemuneration("800.00", "LABOR_SERVICE");
  assert.equal(small.vatExempt, true);
  assert.equal(small.vatWithheld, "0.00");
  assert.equal(small.totalSurchargeWithheld, "0.00");

  // 5,000元 且已录入代开凭证 -> 增值税及附加税不扣缴
  const proxy = computeLaborRemuneration("5000.00", "LABOR_SERVICE", {
    proxyInvoiceCompleted: true,
    proxyInvoiceRef: "INV-PROXY-TEST-001",
  });
  assert.equal(proxy.vatWithheld, "0.00");
  assert.equal(proxy.totalSurchargeWithheld, "0.00");
  assert.equal(proxy.iitWithholding, "800.00");
  assert.equal(proxy.netPayable, "4200.00");

  // 缺少代开凭证号时必须抛出异常
  assert.throws(
    () =>
      computeLaborRemuneration("5000.00", "LABOR_SERVICE", {
        proxyInvoiceCompleted: true,
      }),
    /已完成税局代开必须提供代开发票凭证号/
  );

  // 报酬低于800元（例如500元）扣除额截断为500，应税额与个税均为0
  const sub800 = computeLaborRemuneration("500.00", "LABOR_SERVICE");
  assert.equal(sub800.deductionAmount, "500.00");
  assert.equal(sub800.taxableIncome, "0.00");
  assert.equal(sub800.iitWithholding, "0.00");
  assert.equal(sub800.netPayable, "500.00");
});

test("PAY03: 年度汇算清缴协同对比在低薪/减除费用未扣除尽时智能推荐并入综合所得", () => {
  // 年应税工资 -20,000元 (说明60000基本减除费用尚余20000未被工资消化)
  // 年终奖 30,000元
  // 方案A (单独计税): 工资税 0 + 奖金税 (30,000*0.03 = 900) = 900.00
  // 方案B (并入综合): 综合所得应税 = 30,000 - 20,000 = 10,000 -> 3% -> 300.00
  // 差额 600元，方案B更优，智能推荐 COMBINED_COMPREHENSIVE_INCOME
  const syn = computeBonusSynergy("30000.00", "-20000.00");
  assert.equal(syn.recommendedMode, "COMBINED_COMPREHENSIVE_INCOME");
  assert.equal(syn.taxSavings, "600.00");
  assert.ok(syn.summary.includes("推荐方案: COMBINED_COMPREHENSIVE_INCOME(节税 600.00元)"));
});

test("TAX05: 地区法定征期日历准确处理周六周日及法定假期顺延", () => {
  // 2026年8月15日为周六 -> 顺延至8月17日(周一)
  const d1 = computeFilingDeadline("VAT", 2026, 8);
  assert.equal(d1.baseDeadline, "2026-08-15");
  assert.equal(d1.adjustedDeadline, "2026-08-17");
  assert.equal(d1.isDeferred, true);
  assert.ok(d1.deferralReason.includes("SATURDAY_WEEKEND_DEFERRAL"));

  // 节假日顺延
  const d2 = computeFilingDeadline("VAT", 2026, 2, {
    "2026-02-15": "春节假日",
    "2026-02-16": "春节假日",
    "2026-02-17": "春节顺延",
  });
  assert.equal(d2.baseDeadline, "2026-02-15");
  assert.equal(d2.adjustedDeadline, "2026-02-18");
  assert.equal(d2.isDeferred, true);
});

test("TAX05: 必要附表缺失阻断检查可准确识别漏报附表", () => {
  // 增值税一般纳税人有出口销售额和进项抵扣
  const figures = { exportSales: 10000, inputDeduction: 5000 };
  const incomplete = checkMandatorySchedules("VAT_GENERAL", figures, [
    "ATTACHMENT_VAT_EXPORT",
  ]);
  assert.equal(incomplete.passed, false);
  assert.deepEqual(incomplete.missingSchedules, ["ATTACHMENT_VAT_INPUT_DEDUCTION"]);

  const complete = checkMandatorySchedules("VAT_GENERAL", figures, [
    "ATTACHMENT_VAT_EXPORT",
    "ATTACHMENT_VAT_INPUT_DEDUCTION",
  ]);
  assert.equal(complete.passed, true);
  assert.equal(complete.missingSchedules.length, 0);
});

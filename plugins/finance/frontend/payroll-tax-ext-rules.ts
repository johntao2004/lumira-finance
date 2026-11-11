/**
 * Business rules, calculations, and validation logic for Candidate Lane W10 (payroll-tax-ext).
 * Covers PAY03, PAY04, TAX01, and TAX05.
 */

export type BonusCalculationMode =
  | "SEPARATE_MONTHLY_QUOTIENT"
  | "COMBINED_COMPREHENSIVE_INCOME";

export type RemunerationType =
  | "LABOR_SERVICE"
  | "AUTHOR_REMUNERATION"
  | "ROYALTY";

export interface BonusCalculationResult {
  bonusAmount: string;
  electedMode: BonusCalculationMode;
  monthlyQuotient: string;
  applicableRate: string;
  quickDeduction: string;
  withholdingTax: string;
  netAmount: string;
  recommendedMode: BonusCalculationMode;
  taxSavings: string;
  summary: string;
}

export interface LaborCalculationResult {
  grossPayment: string;
  type: RemunerationType;
  deductionAmount: string;
  taxableIncome: string;
  iitRate: string;
  iitQuickDeduction: string;
  iitWithholding: string;
  vatSales: string;
  vatExempt: boolean;
  vatRate: string;
  vatReduction: string;
  vatWithheld: string;
  citySurtax: string;
  educationSurcharge: string;
  localEducationSurcharge: string;
  totalSurchargeWithheld: string;
  netPayable: string;
  proxyInvoiceCompleted: boolean;
}

export interface CalendarDeadlineResult {
  taxType: string;
  year: number;
  month: number;
  baseDeadline: string;
  adjustedDeadline: string;
  isDeferred: boolean;
  deferralReason: string;
}

export interface ScheduleCheckResult {
  passed: boolean;
  requiredSchedules: string[];
  missingSchedules: string[];
}

/**
 * PAY03: Calculates Annual One-time Bonus IIT under separate monthly quotient method.
 * Implements Cai Shui [2018] No. 164 & Announcement 2023 No. 30.
 */
export function computeBonusSeparate(bonusStr: string): {
  monthlyQuotient: string;
  applicableRate: string;
  quickDeduction: string;
  withholdingTax: string;
  netAmount: string;
} {
  const bonus = Number.parseFloat(bonusStr);
  if (Number.isNaN(bonus) || bonus <= 0) {
    throw new Error("奖金金额必须为大于0的有效数值");
  }

  const quotient = bonus / 12;
  let rate = 0.03;
  let quick = 0;

  if (quotient <= 3000) {
    rate = 0.03;
    quick = 0;
  } else if (quotient <= 12000) {
    rate = 0.10;
    quick = 210;
  } else if (quotient <= 25000) {
    rate = 0.20;
    quick = 1410;
  } else if (quotient <= 35000) {
    rate = 0.25;
    quick = 2660;
  } else if (quotient <= 55000) {
    rate = 0.30;
    quick = 4410;
  } else if (quotient <= 80000) {
    rate = 0.35;
    quick = 7160;
  } else {
    rate = 0.45;
    quick = 15160;
  }

  const tax = Math.max(0, Math.round((bonus * rate - quick) * 100) / 100);
  const net = Math.round((bonus - tax) * 100) / 100;

  return {
    monthlyQuotient: quotient.toFixed(2),
    applicableRate: rate.toFixed(2),
    quickDeduction: quick.toFixed(2),
    withholdingTax: tax.toFixed(2),
    netAmount: net.toFixed(2),
  };
}

/**
 * PAY03: Annual IIT reconciliation synergy comparison preview (汇算清缴对比预览).
 */
export function computeBonusSynergy(
  bonusStr: string,
  annualSalaryStr: string,
  requestedMode: BonusCalculationMode = "SEPARATE_MONTHLY_QUOTIENT"
): BonusCalculationResult {
  const bonus = Number.parseFloat(bonusStr);
  const salary = Number.isNaN(Number.parseFloat(annualSalaryStr)) ? 0 : Number.parseFloat(annualSalaryStr);
  if (Number.isNaN(bonus) || bonus <= 0) {
    throw new Error("奖金金额必须为大于0的有效数值");
  }

  // Option A: Separate Bonus + Standalone Salary
  const sep = computeBonusSeparate(bonusStr);
  const sepBonusTax = Number.parseFloat(sep.withholdingTax);

  const calcAnnualSalaryTax = (taxable: number): number => {
    if (taxable <= 0) return 0;
    if (taxable <= 36000) return taxable * 0.03;
    if (taxable <= 144000) return taxable * 0.10 - 2520;
    if (taxable <= 300000) return taxable * 0.20 - 16920;
    if (taxable <= 420000) return taxable * 0.25 - 31920;
    if (taxable <= 660000) return taxable * 0.30 - 52920;
    if (taxable <= 960000) return taxable * 0.35 - 85920;
    return taxable * 0.45 - 181920;
  };

  const salaryTax = salary <= 0 ? 0 : Math.round(calcAnnualSalaryTax(salary) * 100) / 100;
  const totalTaxOptionA = Math.round((salaryTax + sepBonusTax) * 100) / 100;

  // Option B: Combined Comprehensive
  const combinedTaxable = Math.max(0, salary + bonus);
  const totalTaxOptionB = Math.round(calcAnnualSalaryTax(combinedTaxable) * 100) / 100;
  const combinedMarginalBonusTax = Math.max(0, Math.round((totalTaxOptionB - salaryTax) * 100) / 100);

  let recommendedMode: BonusCalculationMode;
  let taxSavings: number;

  if (totalTaxOptionA < totalTaxOptionB) {
    recommendedMode = "SEPARATE_MONTHLY_QUOTIENT";
    taxSavings = Math.round((totalTaxOptionB - totalTaxOptionA) * 100) / 100;
  } else if (totalTaxOptionB < totalTaxOptionA) {
    recommendedMode = "COMBINED_COMPREHENSIVE_INCOME";
    taxSavings = Math.round((totalTaxOptionA - totalTaxOptionB) * 100) / 100;
  } else {
    recommendedMode = "SEPARATE_MONTHLY_QUOTIENT";
    taxSavings = 0;
  }

  const finalTax =
    requestedMode === "SEPARATE_MONTHLY_QUOTIENT"
      ? sepBonusTax
      : combinedMarginalBonusTax;
  const netAmount = Math.round((bonus - finalTax) * 100) / 100;

  const summary = `方案A(单独计税): 奖金税 ${sepBonusTax.toFixed(2)} + 工资税 ${salaryTax.toFixed(2)} = 合计 ${totalTaxOptionA.toFixed(2)}; 方案B(并入综合): 合计 ${totalTaxOptionB.toFixed(2)}; 推荐方案: ${recommendedMode}(节税 ${taxSavings.toFixed(2)}元)`;

  return {
    bonusAmount: bonus.toFixed(2),
    electedMode: requestedMode,
    monthlyQuotient: sep.monthlyQuotient,
    applicableRate: sep.applicableRate,
    quickDeduction: sep.quickDeduction,
    withholdingTax: finalTax.toFixed(2),
    netAmount: netAmount.toFixed(2),
    recommendedMode,
    taxSavings: taxSavings.toFixed(2),
    summary,
  };
}

/**
 * PAY04: Calculates Natural-Person External Service Remuneration & Small-Amount VAT Withholding.
 */
export function computeLaborRemuneration(
  grossStr: string,
  type: RemunerationType,
  options: {
    relief1Pct?: boolean;
    cityRate?: string;
    sixTaxesHalf?: boolean;
    educationExempt?: boolean;
    proxyInvoiceCompleted?: boolean;
    proxyInvoiceRef?: string;
  } = {}
): LaborCalculationResult {
  const gross = Number.parseFloat(grossStr);
  if (Number.isNaN(gross) || gross <= 0) {
    throw new Error("报酬金额必须为大于0的有效数值");
  }

  const relief1Pct = options.relief1Pct ?? true;
  const cityRate = Number.parseFloat(options.cityRate ?? "0.07");
  const sixTaxesHalf = options.sixTaxesHalf ?? false;
  const educationExempt = options.educationExempt ?? false;
  const proxyInvoiceCompleted = options.proxyInvoiceCompleted ?? false;
  if (proxyInvoiceCompleted && (!options.proxyInvoiceRef || options.proxyInvoiceRef.trim() === "")) {
    throw new Error("已完成税局代开必须提供代开发票凭证号");
  }

  // 1. Statutory IIT Deduction
  let deduction = 0;
  let preliminaryTaxable = 0;
  if (gross <= 4000) {
    deduction = Math.min(gross, 800);
    preliminaryTaxable = Math.max(0, gross - deduction);
  } else {
    deduction = Math.round(gross * 0.20 * 100) / 100;
    preliminaryTaxable = Math.max(0, gross - deduction);
  }

  let taxable = preliminaryTaxable;
  let rate = 0.20;
  let quick = 0;
  let iitWithholding = 0;

  if (type === "AUTHOR_REMUNERATION") {
    // 30% statutory reduction
    taxable = Math.round(preliminaryTaxable * 0.70 * 100) / 100;
    rate = 0.20;
    quick = 0;
    iitWithholding = Math.round(taxable * rate * 100) / 100;
  } else if (type === "ROYALTY") {
    taxable = preliminaryTaxable;
    rate = 0.20;
    quick = 0;
    iitWithholding = Math.round(taxable * rate * 100) / 100;
  } else {
    // LABOR_SERVICE: progressive 20%-30%-40%
    taxable = preliminaryTaxable;
    if (taxable <= 20000) {
      rate = 0.20;
      quick = 0;
    } else if (taxable <= 50000) {
      rate = 0.30;
      quick = 2000;
    } else {
      rate = 0.40;
      quick = 7000;
    }
    iitWithholding = Math.max(0, Math.round((taxable * rate - quick) * 100) / 100);
  }

  // 2. Small-Amount VAT & Surtaxes
  let vatExempt = false;
  let vatRate = 0;
  let vatReduction = 0;
  let vatWithheld = 0;
  let citySurtax = 0;
  let eduSurcharge = 0;
  let localEduSurcharge = 0;
  let totalSurchargeWithheld = 0;

  if (!proxyInvoiceCompleted) {
    if (gross < 1000) {
      vatExempt = true;
      vatRate = 0.03;
      vatReduction = Math.round(gross * 0.03 * 100) / 100;
      vatWithheld = 0;
    } else {
      vatExempt = false;
      vatRate = 0.03;
      const baseVat = Math.round(gross * 0.03 * 100) / 100;
      if (relief1Pct) {
        vatReduction = Math.round(gross * 0.02 * 100) / 100;
        vatWithheld = Math.max(0, Math.round((baseVat - vatReduction) * 100) / 100);
      } else {
        vatReduction = 0;
        vatWithheld = baseVat;
      }

      const half = sixTaxesHalf ? 0.5 : 1.0;
      citySurtax = Math.round(vatWithheld * cityRate * half * 100) / 100;

      if (educationExempt || gross <= 100000) {
        eduSurcharge = 0;
        localEduSurcharge = 0;
      } else {
        eduSurcharge = Math.round(vatWithheld * 0.03 * half * 100) / 100;
        localEduSurcharge = Math.round(vatWithheld * 0.02 * half * 100) / 100;
      }

      totalSurchargeWithheld = Math.round((citySurtax + eduSurcharge + localEduSurcharge) * 100) / 100;
    }
  }

  const netPayable = Math.round((gross - iitWithholding - vatWithheld - totalSurchargeWithheld) * 100) / 100;

  return {
    grossPayment: gross.toFixed(2),
    type,
    deductionAmount: deduction.toFixed(2),
    taxableIncome: taxable.toFixed(2),
    iitRate: rate.toFixed(2),
    iitQuickDeduction: quick.toFixed(2),
    iitWithholding: iitWithholding.toFixed(2),
    vatSales: gross.toFixed(2),
    vatExempt,
    vatRate: vatRate.toFixed(4),
    vatReduction: vatReduction.toFixed(2),
    vatWithheld: vatWithheld.toFixed(2),
    citySurtax: citySurtax.toFixed(2),
    educationSurcharge: eduSurcharge.toFixed(2),
    localEducationSurcharge: localEduSurcharge.toFixed(2),
    totalSurchargeWithheld: totalSurchargeWithheld.toFixed(2),
    netPayable: netPayable.toFixed(2),
    proxyInvoiceCompleted,
  };
}

/**
 * TAX05: Regional Statutory Filing Calendar Generator.
 */
export function computeFilingDeadline(
  taxType: string,
  year: number,
  month: number,
  holidays: Record<string, string> = {}
): CalendarDeadlineResult {
  const pad = (n: number) => n.toString().padStart(2, "0");
  let current = new Date(Date.UTC(year, month - 1, 15));
  const baseDeadline = `${year}-${pad(month)}-15`;
  let isDeferred = false;
  const reasons: string[] = [];

  while (true) {
    let shifted = false;
    const isoDate = current.toISOString().slice(0, 10);

    if (holidays[isoDate]) {
      reasons.push(holidays[isoDate]);
      current.setUTCDate(current.getUTCDate() + 1);
      isDeferred = true;
      shifted = true;
    }

    const day = current.getUTCDay();
    if (day === 6) {
      // Saturday
      reasons.push("SATURDAY_WEEKEND_DEFERRAL");
      current.setUTCDate(current.getUTCDate() + 2);
      isDeferred = true;
      shifted = true;
    } else if (day === 0) {
      // Sunday
      reasons.push("SUNDAY_WEEKEND_DEFERRAL");
      current.setUTCDate(current.getUTCDate() + 1);
      isDeferred = true;
      shifted = true;
    }

    if (!shifted) break;
  }

  const adjustedDeadline = current.toISOString().slice(0, 10);
  return {
    taxType,
    year,
    month,
    baseDeadline,
    adjustedDeadline,
    isDeferred,
    deferralReason: isDeferred ? reasons.join("; ") : "REGULAR_STATUTORY_DEADLINE",
  };
}

/**
 * TAX05: Conditional schedule attachment checker (必要附表缺失阻断).
 */
export function checkMandatorySchedules(
  formType: "VAT_GENERAL" | "CIT_ANNUAL",
  figures: Record<string, number>,
  attachedCodes: string[]
): ScheduleCheckResult {
  const required: string[] = [];

  if (formType === "VAT_GENERAL") {
    if ((figures.exportSales ?? 0) > 0) {
      required.push("ATTACHMENT_VAT_EXPORT");
    }
    if ((figures.inputDeduction ?? 0) > 0 || (figures.inputTransferOut ?? 0) > 0) {
      required.push("ATTACHMENT_VAT_INPUT_DEDUCTION");
    }
    if ((figures.taxReduction ?? 0) > 0) {
      required.push("ATTACHMENT_VAT_REDUCTION");
    }
  } else if (formType === "CIT_ANNUAL") {
    if ((figures.rdSuperDeduction ?? 0) > 0) {
      required.push("ATTACHMENT_CIT_A107012_RD");
    }
    if ((figures.advertisingExpense ?? 0) > 0) {
      required.push("ATTACHMENT_CIT_A105060_ADVERTISING");
    }
    if ((figures.donationExpense ?? 0) > 0) {
      required.push("ATTACHMENT_CIT_A105070_DONATION");
    }
    if ((figures.smallLowProfitRelief ?? 0) > 0) {
      required.push("ATTACHMENT_CIT_A100000_QUALIFICATION");
    }
  }

  const attachedSet = new Set(attachedCodes);
  const missing = required.filter((code) => !attachedSet.has(code));

  return {
    passed: missing.length === 0,
    requiredSchedules: required,
    missingSchedules: missing,
  };
}

/**
 * Pure domain rules and client-side validation for Lane W09 (GL and Year-End Lifecycle).
 */

export type CutoverMode = "FULL_HISTORY" | "OPENING_PLUS_YTD";
export type CorrectionKind = "FULL_REVERSAL" | "PARTIAL_REVERSAL" | "SUPPLEMENT" | "PRIOR_PERIOD_ADJUSTMENT";
export type Standard = "CAS" | "SMALL";
export type PeriodCloseStatus = "PREVIEWED" | "APPROVED" | "CLOSED" | "CANCELLED" | "REOPENED";
export type CarryBatchKind = "BASE" | "DELTA";

export interface OpeningBalanceInput {
  accountId: string;
  accountCode: string;
  accountName: string;
  direction: "DEBIT" | "CREDIT";
  amount: string;
  auxDimensions?: Record<string, string>;
}

export interface BaselineItemInput {
  baselineType: "PROFIT_LOSS" | "CASHFLOW" | "TAX" | "PAYROLL";
  periodMonth: number;
  categoryCode: string;
  categoryName: string;
  amount: string;
  auxPayload?: Record<string, unknown>;
}

export interface TieOutCheckResult {
  checkItem: string;
  passed: boolean;
  glAmount: string;
  subledgerAmount: string;
  difference: string;
}

export interface AutoReviewPolicyConfig {
  policyCode: string;
  maxAmount: string;
  scopeBusinessTypes: string[];
  allowedAccountCodes: string[];
  zeroExceptionRequired: boolean;
}

export interface CorrectionAllocationInput {
  sourceLineNo: number;
  amount: string;
  targetAccountVersionId: string;
}

export interface PeriodCloseGateSummary {
  allChecksPassed: boolean;
  unpostedCount: number;
  trialBalanceBalanced: boolean;
  bankReconciliationComplete: boolean;
  subledgerTieOutPassed: boolean;
  cashflowCoverageComplete: boolean;
  taxWorksheetReady: boolean;
}

export const GL_YEAREND_LABELS = {
  FULL_HISTORY: "全历史凭证迁入",
  OPENING_PLUS_YTD: "时点余额+本年累计迁入",
  CAS: "企业会计准则 (CAS)",
  SMALL: "小企业会计准则 (SMALL)",
  FULL_REVERSAL: "红字全额冲销",
  PARTIAL_REVERSAL: "差额部分更正",
  SUPPLEMENT: "补充凭证",
  PRIOR_PERIOD_ADJUSTMENT: "以前年度损益调整",
  BASE: "正式期初承接批次 (BASE)",
  DELTA: "跨年更正调整批次 (DELTA)",
} as const;

/** Validate and normalize CNY 2-decimal monetary string */
export function glCny(value: unknown, allowZero = false): string {
  if (typeof value !== "string" || !/^(0|[1-9]\d{0,19})(\.\d{1,2})?$/.test(value)) {
    throw new Error("人民币金额须为最多两位小数的非负规范字符串");
  }
  const [whole, decimals = ""] = value.split(".");
  const cents = BigInt(whole) * 100n + BigInt(decimals.padEnd(2, "0"));
  if (!allowZero && cents === 0n) {
    throw new Error("金额必须严格大于零");
  }
  return `${whole}.${decimals.padEnd(2, "0")}`;
}

export function glCnyUnits(value: string): bigint {
  return BigInt(glCny(value, true).replace(".", ""));
}

/** Canonical JSON stringifier with deterministic key ordering */
export function glCanonical(value: unknown): string {
  function sorted(v: unknown): unknown {
    if (Array.isArray(v)) return v.map(sorted);
    if (v !== null && typeof v === "object") {
      return Object.fromEntries(
        Object.entries(v)
          .sort(([a], [b]) => a.localeCompare(b))
          .map(([k, x]) => [k, sorted(x)])
      );
    }
    return v;
  }
  return JSON.stringify(sorted(value));
}

/** Validate Mid-Year Cutover mode mutual exclusivity and tie-outs */
export function validateCutoverInput(
  mode: CutoverMode,
  balances: OpeningBalanceInput[],
  baselines: BaselineItemInput[],
  subledgerTotals: Record<string, string>
): { passed: boolean; tieOuts: TieOutCheckResult[] } {
  if (mode !== "FULL_HISTORY" && mode !== "OPENING_PLUS_YTD") {
    throw new Error("迁入模式必须为 FULL_HISTORY 或 OPENING_PLUS_YTD 之一");
  }

  if (mode === "FULL_HISTORY" && baselines.length > 0) {
    throw new Error("FULL_HISTORY 模式严禁提供分月累计基线 (baselines must be empty)");
  }

  if (mode === "OPENING_PLUS_YTD" && baselines.length === 0) {
    throw new Error("OPENING_PLUS_YTD 模式必须提供分月累计基线");
  }

  let totalDebit = 0n;
  let totalCredit = 0n;
  let glAr = 0n;
  let glAp = 0n;
  let glAsset = 0n;
  let glCash = 0n;
  let glPayroll = 0n;

  for (const b of balances) {
    const amt = glCnyUnits(b.amount);
    if (b.direction === "DEBIT") totalDebit += amt;
    else if (b.direction === "CREDIT") totalCredit += amt;

    if (b.accountCode.startsWith("1122") || b.accountCode.startsWith("1121")) glAr += amt;
    else if (b.accountCode.startsWith("2202") || b.accountCode.startsWith("2201")) glAp += amt;
    else if (b.accountCode.startsWith("1601")) glAsset += b.direction === "DEBIT" ? amt : -amt;
    else if (b.accountCode.startsWith("1602")) glAsset -= b.direction === "CREDIT" ? amt : -amt;
    else if (b.accountCode.startsWith("1001") || b.accountCode.startsWith("1002")) glCash += amt;
    else if (b.accountCode.startsWith("2211")) glPayroll += amt;
  }

  const tieOuts: TieOutCheckResult[] = [];

  // 1. Trial balance
  const tbDiff = totalDebit - totalCredit;
  tieOuts.push({
    checkItem: "TRIAL_BALANCE",
    passed: tbDiff === 0n,
    glAmount: (Number(totalDebit) / 100).toFixed(2),
    subledgerAmount: (Number(totalCredit) / 100).toFixed(2),
    difference: (Number(tbDiff) / 100).toFixed(2),
  });

  // 2. AR Subledger
  const subAr = glCnyUnits(subledgerTotals["AR"] || "0.00");
  const arDiff = glAr - subAr;
  tieOuts.push({
    checkItem: "AR_SUBLEDGER",
    passed: arDiff === 0n,
    glAmount: (Number(glAr) / 100).toFixed(2),
    subledgerAmount: (Number(subAr) / 100).toFixed(2),
    difference: (Number(arDiff) / 100).toFixed(2),
  });

  // 3. AP Subledger
  const subAp = glCnyUnits(subledgerTotals["AP"] || "0.00");
  const apDiff = glAp - subAp;
  tieOuts.push({
    checkItem: "AP_SUBLEDGER",
    passed: apDiff === 0n,
    glAmount: (Number(glAp) / 100).toFixed(2),
    subledgerAmount: (Number(subAp) / 100).toFixed(2),
    difference: (Number(apDiff) / 100).toFixed(2),
  });

  // 4. Asset Subledger
  const subAsset = glCnyUnits(subledgerTotals["ASSET"] || "0.00");
  const assetDiff = glAsset - subAsset;
  tieOuts.push({
    checkItem: "ASSET_SUBLEDGER",
    passed: assetDiff === 0n,
    glAmount: (Number(glAsset) / 100).toFixed(2),
    subledgerAmount: (Number(subAsset) / 100).toFixed(2),
    difference: (Number(assetDiff) / 100).toFixed(2),
  });

  // 5. Cashflow baseline
  let baseCash = 0n;
  for (const bl of baselines) {
    if (bl.baselineType === "CASHFLOW") {
      baseCash += glCnyUnits(bl.amount);
    }
  }
  const cashDiff = glCash - baseCash;
  tieOuts.push({
    checkItem: "CASHFLOW_TIE_OUT",
    passed: cashDiff === 0n,
    glAmount: (Number(glCash) / 100).toFixed(2),
    subledgerAmount: (Number(baseCash) / 100).toFixed(2),
    difference: (Number(cashDiff) / 100).toFixed(2),
  });

  // 6. Payroll
  const subPayroll = glCnyUnits(subledgerTotals["PAYROLL"] || "0.00");
  const payDiff = glPayroll - subPayroll;
  tieOuts.push({
    checkItem: "PAYROLL_TIE_OUT",
    passed: payDiff === 0n,
    glAmount: (Number(glPayroll) / 100).toFixed(2),
    subledgerAmount: (Number(subPayroll) / 100).toFixed(2),
    difference: (Number(payDiff) / 100).toFixed(2),
  });

  const passed = tieOuts.every((t) => t.passed);
  return { passed, tieOuts };
}

/** Evaluate auto-review policy against draft parameters */
export function evaluateAutoReviewPolicy(
  policy: AutoReviewPolicyConfig,
  transactionAmount: string,
  businessType: string,
  affectedAccounts: string[]
): { autoApproved: boolean; exceptions: string[] } {
  const exceptions: string[] = [];
  const txUnits = glCnyUnits(transactionAmount);
  const maxUnits = glCnyUnits(policy.maxAmount);

  if (txUnits > maxUnits) {
    exceptions.push(`交易金额 ${transactionAmount} 超过自动审签上限 ${policy.maxAmount}`);
  }

  if (!policy.scopeBusinessTypes.includes(businessType)) {
    exceptions.push(`业务类型 ${businessType} 超出自动审签适用范围`);
  }

  for (const acc of affectedAccounts) {
    if (!policy.allowedAccountCodes.includes(acc)) {
      exceptions.push(`科目 ${acc} 不在自动审签白名单中`);
    }
  }

  const autoApproved = policy.zeroExceptionRequired ? exceptions.length === 0 : exceptions.length === 0;
  return { autoApproved, exceptions };
}

/** Validate CAS vs SMALL routing for prior-period error adjustments */
export function validateCorrectionStandard(
  standard: Standard,
  correctionKind: CorrectionKind,
  casAccountCode?: string
): void {
  if (standard === "SMALL") {
    if (casAccountCode === "6901") {
      throw new Error("小企业会计准则 (SMALL) 不设置'6901 以前年度损益调整'科目，前期差错应计入当期损益或利润分配");
    }
  } else if (standard === "CAS") {
    if (correctionKind === "PRIOR_PERIOD_ADJUSTMENT" && casAccountCode !== "6901") {
      throw new Error("企业会计准则 (CAS) 重大前期差错更正必须通过'6901 以前年度损益调整'科目核算");
    }
  }
}

/** Check line allocation ceilings against watermarks */
export function validateCorrectionAllocations(
  allocations: CorrectionAllocationInput[],
  watermarks: Record<number, string>
): void {
  if (allocations.length === 0) {
    throw new Error("更正/冲销必须至少指定一条分录分配");
  }
  for (const alloc of allocations) {
    const allocUnits = glCnyUnits(alloc.amount);
    const watermarkStr = watermarks[alloc.sourceLineNo] || "0.00";
    const watermarkUnits = glCnyUnits(watermarkStr);
    if (allocUnits > watermarkUnits) {
      throw new Error(`行号 ${alloc.sourceLineNo} 更正金额 ${alloc.amount} 超过原分录剩余可冲销额度 ${watermarkStr}`);
    }
  }
}

/** Verify Month-End Close 6-Gate checklist */
export function evaluatePeriodCloseGates(
  unpostedCount: number,
  trialBalanceDebit: string,
  trialBalanceCredit: string,
  bankReconciliationComplete: boolean,
  subledgerPassed: boolean,
  cashflowComplete: boolean,
  taxReady: boolean
): PeriodCloseGateSummary {
  const tbBalanced = glCnyUnits(trialBalanceDebit) === glCnyUnits(trialBalanceCredit);
  const unpostedZero = unpostedCount === 0;

  const allChecksPassed =
    unpostedZero &&
    tbBalanced &&
    bankReconciliationComplete &&
    subledgerPassed &&
    cashflowComplete &&
    taxReady;

  return {
    allChecksPassed,
    unpostedCount,
    trialBalanceBalanced: tbBalanced,
    bankReconciliationComplete,
    subledgerTieOutPassed: subledgerPassed,
    cashflowCoverageComplete: cashflowComplete,
    taxWorksheetReady: taxReady,
  };
}

/** Session storage retry key caching for UI idempotency */
export async function glYearendRetryIdentity(
  scopeIdentity: string,
  route: string,
  body: unknown,
  storage: Pick<Storage, "getItem" | "setItem">,
  uuidGen: () => string = () => crypto.randomUUID()
): Promise<{ operationId: string; idempotencyKey: string }> {
  const payloadStr = glCanonical([scopeIdentity, route, body]);
  const encoded = new TextEncoder().encode(payloadStr);
  const digestBuf = await crypto.subtle.digest("SHA-256", encoded);
  const digest = Array.from(new Uint8Array(digestBuf), (b) => b.toString(16).padStart(2, "0")).join("");
  const key = `finance:gl_yearend:v1:retry:${digest}`;

  const prior = storage.getItem(key);
  if (prior) {
    try {
      const parsed = JSON.parse(prior);
      if (parsed && typeof parsed.operationId === "string" && typeof parsed.idempotencyKey === "string") {
        return { operationId: parsed.operationId, idempotencyKey: parsed.idempotencyKey };
      }
    } catch {}
  }

  const result = { operationId: uuidGen(), idempotencyKey: uuidGen() };
  storage.setItem(key, JSON.stringify(result));
  return result;
}

/** Verify consecutive years for cross-year carry (YEAR01) */
export function validateConsecutiveYears(sourceYear: number, targetYear: number): void {
  if (targetYear !== sourceYear + 1) {
    throw new Error(`目标年度必须为源年度严格连续次年 (source=${sourceYear}, target=${targetYear})`);
  }
}

/** Verify multi-year summary read scope authorization (YEAR01) */
export function validateMultiYearReadScope(authorizedYears: number[], requestedYears: number[]): void {
  if (requestedYears.length === 0) {
    throw new Error("请求年度范围不可为空");
  }
  const authSet = new Set(authorizedYears);
  for (const yr of requestedYears) {
    if (!authSet.has(yr)) {
      throw new Error(`跨年汇总查询未授权年度: ${yr}`);
    }
  }
}

/** Validate Chart of Accounts mapping across consecutive years (YEAR01) */
export function validateChartOfAccountsMapping(
  mappingVersion: string,
  mappings: Array<{ sourceCode: string; targetCode: string; netOpeningAmount: string }>
): void {
  if (!mappingVersion || mappingVersion.trim().length === 0) {
    throw new Error("科目映射版本号 (mappingVersion) 必填");
  }
  if (!mappings || mappings.length === 0) {
    throw new Error("期初承接科目列表不可为空");
  }
  for (const m of mappings) {
    if (!m.sourceCode || !m.sourceCode.trim()) {
      throw new Error("源科目代码必填");
    }
    if (!m.targetCode || !m.targetCode.trim()) {
      throw new Error("目标映射科目代码必填");
    }
    glCny(m.netOpeningAmount, true);
  }
}


import type {
  Actor,
  AccountPage,
  Check,
  Scope,
  SignedAmount,
  UnsignedAmount,
  Summary,
} from "./carry-readiness-contract";
export type CarryFilter = AccountPage["filter"];
export type CarrySelection = {
  tenantId: string;
  legalEntityId: string;
  bookId: string;
  sourceYearId: string;
  sourceYear: number;
  targetYearId: string;
  targetYear: number;
  authHint: string;
  actorHint: Actor | null;
  filter: CarryFilter;
  limit: number;
  refreshToken: number;
  yearHints: string;
};
export const carryCheckLabels: Record<Check["id"], string> = {
  SOURCE_DATA: "源年度账务数据",
  TARGET_DATA: "目标年度账务数据",
  SOURCE_PERSON_MODE: "源年度人员模式",
  TARGET_PERSON_MODE: "目标年度人员模式",
  SOURCE_PERIODS_CLOSED: "源年度12期实际状态",
  SOURCE_PENDING_JOURNALS: "源年度未记账凭证",
  SOURCE_PENDING_CLOSING: "源年度未完成结转运行",
  SOURCE_TRIAL_BALANCED: "源年度借贷平衡",
  SOURCE_PROFIT_LOSS_ZERO: "源年度损益实际清零",
  SOURCE_CURRENT_PROFIT_ZERO: "源年度本年利润实际清零",
  TARGET_OPENING_FACTS: "目标已有期初余额行",
  TARGET_POSTED_YEAR_MOVEMENTS: "目标全年已记账发生",
  CANDIDATE_ACCOUNT_MAPPING: "科目候选对照",
  APPROVED_ACCOUNT_MAPPING: "已批准科目映射模型",
  FORMAL_OPENING_BATCH: "正式期初承接批次",
  YTD_BASELINE_MODEL: "迁移累计基线模型",
  SUBLEDGER_OPEN_ITEMS: "子账及未核销往来",
  BANK_RECONCILIATION: "银行对账",
  EXTERNAL_CASH_UNIVERSE: "外部现金完整总体",
  ASSET_CONTINUITY: "资产连续性",
  INVENTORY_QUANTITY: "存货及数量连续性",
  TAX_CONTINUITY: "税项连续性",
  FORMAL_REPORT_APPROVAL: "正式报告批准",
  ARCHIVE: "档案归档",
};
export const carryKnowledgeLabels: Record<SignedAmount["state"], string> = {
  KNOWN: "已取得",
  MISSING_ACCOUNT: "无对应科目",
  UNSUPPORTED_PRECISION: "旧版精度尚未支持",
  UNSUPPORTED_BASELINE: "累计基线尚未支持",
  UNKNOWN_INVALID_VALUE: "数据值尚不可确认",
  NOT_COMPARABLE: "当前不可比较",
  NOT_IMPLEMENTED: "能力尚未实现",
};
export const carryCandidateLabels: Record<
  AccountPage["items"][number]["candidateStatus"],
  string
> = {
  CANDIDATE_COMPATIBLE: "语义相容候选（未批准）",
  MISSING_TARGET: "没有目标候选",
  TARGET_ONLY: "仅目标存在的科目",
  SEMANTIC_CONFLICT: "候选语义冲突",
  DISABLED_ACCOUNT: "存在停用科目",
};
export const carryDifferenceLabels = {
  CATEGORY: "类别不同",
  NORMAL_SIDE: "正常方向不同",
  PURPOSE: "明确用途不同",
  SOURCE_DISABLED: "源科目停用",
  TARGET_DISABLED: "目标科目停用",
};
export function carryMoney(fact: SignedAmount | UnsignedAmount) {
  return fact.state === "KNOWN" &&
    fact.amount !== null &&
    /^-?(0|[1-9][0-9]{0,19})\.[0-9]{8}$/.test(fact.amount)
    ? fact.amount
    : `未知（${fact.state === "KNOWN" ? "数据值尚不可确认" : (carryKnowledgeLabels[fact.state] ?? "数据尚不可确认")}）`;
}
export function carryCheckMoney(check: Check) {
  return check.amountKnowledge === "KNOWN" &&
    check.amount !== null &&
    /^-?(0|[1-9][0-9]{0,19})\.[0-9]{8}$/.test(check.amount)
    ? check.amount
    : "未知 / 本项未测量金额";
}
export function carryActorEqual(a: Actor, b: Actor) {
  return (
    a.accountId === b.accountId &&
    a.personId === b.personId &&
    a.identityAvailability === b.identityAvailability
  );
}
export function carrySelectionKey(s: CarrySelection) {
  return JSON.stringify([
    s.tenantId,
    s.legalEntityId,
    s.bookId,
    s.sourceYearId,
    s.sourceYear,
    s.targetYearId,
    s.targetYear,
    s.authHint,
    s.actorHint?.accountId ?? null,
    s.actorHint?.personId ?? null,
    s.actorHint?.identityAvailability ?? null,
    s.filter,
    s.limit,
    s.refreshToken,
    s.yearHints,
  ]);
}
export function carryAllowedTargets<T extends { id: string; year: number }>(
  years: T[],
  sourceYearId: string,
) {
  const source = years.find((y) => y.id === sourceYearId);
  return source
    ? years.filter((y) => y.id !== sourceYearId && y.year === source.year + 1)
    : [];
}
export function carryScopeMatches(scope: Scope, s: CarrySelection) {
  return (
    scope.tenantId === s.tenantId &&
    scope.legalEntityId === s.legalEntityId &&
    scope.bookId === s.bookId &&
    scope.sourceYearId === s.sourceYearId &&
    scope.targetYearId === s.targetYearId &&
    scope.sourceYear === s.sourceYear &&
    scope.targetYear === s.targetYear &&
    scope.targetYear === scope.sourceYear + 1 &&
    scope.bothYearsReadAuthorized === true
  );
}
export function carrySummaryMatches(summary: Summary, s: CarrySelection) {
  return (
    summary.schemaVersion === "carry-readiness/1" &&
    carryScopeMatches(summary.scope, s) &&
    summary.source.yearId === s.sourceYearId &&
    summary.source.yearNumber === s.sourceYear &&
    summary.source.rangeStart === `${s.sourceYear}-01-01` &&
    summary.source.rangeEnd === `${s.sourceYear}-12-31` &&
    summary.target.rangeStart === `${s.targetYear}-01-01` &&
    summary.target.rangeEnd === `${s.targetYear}-12-31` &&
    summary.target.yearNumber === s.targetYear &&
    summary.observation.source.yearNumber === s.sourceYear &&
    summary.observation.target.yearNumber === s.targetYear &&
    summary.target.yearId === s.targetYearId &&
    summary.observation.source.yearId === s.sourceYearId &&
    summary.observation.target.yearId === s.targetYearId &&
    summary.canExecuteBase === false &&
    summary.yearCloseEnabled === false &&
    summary.formalReportEnabled === false &&
    summary.persistentSnapshotCreated === false &&
    summary.checks.length === 24 &&
    new Set(summary.checks.map((c) => c.id)).size === 24 &&
    summary.checks.every((c) =>
      Object.prototype.hasOwnProperty.call(carryCheckLabels, c.id),
    )
  );
}
export function carryPageMatches(
  page: AccountPage,
  summary: Summary,
  s: CarrySelection,
) {
  return (
    page.schemaVersion === "carry-readiness/1" &&
    carryScopeMatches(page.scope, s) &&
    page.observationToken === summary.observation.token &&
    page.contextDigest === summary.observation.contextDigest &&
    carryActorEqual(page.currentActor, summary.currentActor) &&
    page.filter === s.filter &&
    page.limit === s.limit &&
    page.items.length <= s.limit &&
    new Set(page.items.map((row) => row.rowKey)).size === page.items.length &&
    page.populationRows === summary.mappingTotals.populationRows &&
    page.filteredRows <= page.populationRows &&
    page.canExecuteBase === false &&
    page.yearCloseEnabled === false
  );
}
export function carrySummaryPath(s: CarrySelection) {
  return `/finance/v1/books/${s.bookId}/years/${s.sourceYearId}/carry-readiness?${new URLSearchParams({ targetYearId: s.targetYearId })}`;
}
export function carryAccountsPath(
  s: CarrySelection,
  token: string,
  cursor: string | null,
) {
  const params = new URLSearchParams({
    targetYearId: s.targetYearId,
    observationToken: token,
    filter: s.filter,
    limit: String(s.limit),
  });
  if (cursor) params.set("cursor", cursor);
  return `/finance/v1/books/${s.bookId}/years/${s.sourceYearId}/carry-readiness/accounts?${params}`;
}

export function carryGroupVisible(
  storedKey: string,
  currentKey: string,
  active: boolean,
  phase: string,
) {
  return active && storedKey === currentKey && phase === "READY";
}

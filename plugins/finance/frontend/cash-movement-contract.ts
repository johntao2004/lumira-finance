import { cnyUnits, units } from "./types.ts";
export const movementSchema = "cash-movement-registration/1";
export type MovementCatalog = {
  catalogVersion: string;
  sourceSystems: {
    id: string;
    evidenceKind: string;
    instrumentKinds: string[];
    label: string;
  }[];
  transactionKinds: { id: string; direction: string }[];
};
export type CashLeg = {
  journalId: string;
  lineNo: number;
  accountId: string;
  date: string;
  direction: string;
  normalizedAmount: string;
  journalVersion: number;
  postingSeq: number;
  postingDigest: string;
  number: number | null;
  summary: string;
  activeRegistrationId: string | null;
  activeClaimId?: string | null;
  activeClaimKind?: string | null;
  activeCorrectionId?: string | null;
};
export type ApprovedMovementScope = {
  id: string;
  status: string;
  scopeVersion: number | null;
  contentDigest: string;
  periodStart: string;
  periodEnd: string;
  templateId: string;
  items: {
    accountId: string;
    instrumentKind: string;
    qualification: string;
    singleCashInstrument: boolean;
    accountSnapshot?: { code?: string; name?: string };
  }[];
};
export type MovementSummary = {
  id: string;
  sourceSystem: string;
  sourceMovementKey: string;
  sourceDocumentId: string;
  sourceRowReference: string;
  occurredDate: string;
  accountId: string;
  direction: string;
  normalizedAmount: string;
  status: string;
  verificationStatus: string;
  classificationStatus: string;
  reportContributionEnabled: boolean;
  version: number;
  contentVersion: number;
  contentDigest: string;
  preparedBy: string;
  voidReason: string | null;
};
export type MovementDetail = MovementSummary & {
  description: string;
  reason: string;
  evidenceKind: string;
  originalSnapshot: { id: string; filename: string; sha256: string };
  scopeItemSnapshot: unknown;
  accountSnapshot: unknown;
  matchedLegSnapshot: CashLeg;
  asOfWatermark: unknown;
  approvedScopeVersion: number;
  scopeContentDigest: string;
};
export type MovementForm = {
  sourceSystem: string;
  sourceMovementKey: string;
  sourceDocumentId: string;
  sourceDocumentVersion: string;
  evidenceOriginalId: string;
  sourceRowReference: string;
  occurredDate: string;
  direction: string;
  amount: string;
  transactionKind: string;
  description: string;
  reason: string;
  evidenceSource: "ELECTRONIC_ORIGINAL" | "PAPER_SCAN";
  paperLocation?: string;
};
export function movementPayload(
  values: MovementForm,
  scope: ApprovedMovementScope,
  accountId: string,
  leg: CashLeg,
  catalog: MovementCatalog,
) {
  if (
    scope.status !== "APPROVED" ||
    !Number.isSafeInteger(scope.scopeVersion) ||
    !scope.scopeVersion
  )
    throw new Error("必须选择有自身批准水位的已批准月度范围");
  const item = scope.items.find(
    (item) =>
      item.accountId === accountId &&
      item.qualification === "INCLUDED" &&
      item.singleCashInstrument === true,
  );
  if (!item) throw new Error("科目必须是本月已批准范围中的纳入单一工具科目");
  const source = catalog.sourceSystems.find(
    (source) =>
      source.id === values.sourceSystem &&
      source.instrumentKinds.includes(item.instrumentKind),
  );
  if (!source) throw new Error("请选择与现金工具相符的服务端来源目录");
  const kind = catalog.transactionKinds.find(
    (kind) => kind.id === values.transactionKind,
  );
  if (!kind || kind.direction !== values.direction)
    throw new Error("普通交易类型必须来自目录并与真实方向一致");
  const magnitude = cnyUnits(values.amount);
  if (magnitude <= 0n) throw new Error("实际来源金额须大于零");
  if (leg.activeClaimId || leg.activeRegistrationId)
    throw new Error("该现金腿已有有效登记，不能重复占用");
  if (
    leg.accountId !== accountId ||
    leg.direction !== values.direction ||
    leg.date !== values.occurredDate ||
    units(leg.normalizedAmount) !== magnitude
  )
    throw new Error(
      "实际来源日期、方向、科目及全额金额必须与所选已记账现金腿完全一致，不支持拆分或净额",
    );
  if (
    values.occurredDate < scope.periodStart ||
    values.occurredDate > scope.periodEnd
  )
    throw new Error("来源日期须属于选定范围的完整自然月");
  if (
    !Number.isSafeInteger(leg.lineNo) ||
    leg.lineNo < 1 ||
    !Number.isSafeInteger(leg.journalVersion) ||
    leg.journalVersion < 1 ||
    !Number.isSafeInteger(leg.postingSeq) ||
    leg.postingSeq < 1
  )
    throw new Error("现金腿权威行号、版本或提交水位缺失，请刷新");
  if (
    !/^[1-9]\d{0,9}$/.test(values.sourceDocumentVersion) ||
    Number(values.sourceDocumentVersion) > 2147483647
  )
    throw new Error("来源单据版本须为正32位整数");
  if (!values.sourceMovementKey?.trim() || !values.sourceRowReference?.trim())
    throw new Error(
      "稳定来源键和全文件唯一行定位必须由实际来源提供，不能使用随机请求键",
    );
  if (
    values.evidenceSource !== "ELECTRONIC_ORIGINAL" &&
    values.evidenceSource !== "PAPER_SCAN"
  )
    throw new Error("请选择证据来源类型");
  if (values.evidenceSource === "PAPER_SCAN" && !values.paperLocation?.trim())
    throw new Error("纸质扫描件须登记实体原件位置");
  const reason = `${values.reason}\n证据来源：${values.evidenceSource === "PAPER_SCAN" ? `纸质扫描件；原件保管位置：${values.paperLocation}` : "电子原件"}`;
  if (reason.length > 1000)
    throw new Error("登记理由与证据位置说明合计不能超过1000字");
  return {
    schemaVersion: movementSchema,
    sourceSystem: source.id,
    sourceMovementKey: values.sourceMovementKey,
    sourceDocumentId: values.sourceDocumentId,
    sourceDocumentVersion: Number(values.sourceDocumentVersion),
    evidenceKind: source.evidenceKind,
    evidenceOriginalId: values.evidenceOriginalId,
    sourceRowReference: values.sourceRowReference,
    cashScopeRevisionId: scope.id,
    expectedScopeVersion: scope.scopeVersion,
    scopeContentDigest: scope.contentDigest,
    accountId,
    occurredDate: values.occurredDate,
    direction: values.direction,
    amount: values.amount,
    currency: "CNY",
    transactionKind: kind.id,
    description: values.description,
    journalId: leg.journalId,
    lineNo: leg.lineNo,
    expectedJournalVersion: leg.journalVersion,
    expectedPostingSeq: leg.postingSeq,
    reason,
  };
}
export function movementVoidPayload(detail: MovementSummary, reason: string) {
  return {
    schemaVersion: movementSchema,
    expectedVersion: detail.version,
    targetContentVersion: detail.contentVersion,
    contentDigest: detail.contentDigest,
    reason,
  };
}

export function movementDirectionLabel(direction: string) {
  return direction === "IN"
    ? "流入"
    : direction === "OUT"
      ? "流出"
      : "未知方向";
}

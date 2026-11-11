export const cashSchema = "cashflow-working-paper/1";
export type CashScopeSummary = {
  id: string;
  schemaVersion: string;
  standard: string;
  reportScope: string;
  currency: string;
  templateId: string;
  periodStart: string;
  periodEnd: string;
  status: string;
  version: number;
  contentVersion: number;
  contentDigest: string;
  preparedBy: string;
  lastEditedBy: string;
  approvedBy: string | null;
  scopeVersion: number | null;
  createdAt: string;
};
export type CashScopeItem = {
  accountId: string;
  singleCashInstrument: boolean;
  instrumentKind: string;
  qualification: string;
  restrictionKind: string;
  availableOnDemand: boolean;
  eligibilityBasis: string;
  evidenceOriginalId: string;
  evidenceSource?: "ELECTRONIC_ORIGINAL" | "PAPER_SCAN";
  paperLocation?: string;
  accountSnapshot?: unknown;
  accountVersionDigest?: string;
  originalSha256?: string;
  originalFilename?: string;
};
export type CashScopeDetail = CashScopeSummary & {
  reason: string;
  decisionReason?: string;
  items: CashScopeItem[];
  asOfWatermark: unknown;
};
export function cashMonth(year: number, month: number) {
  if (
    !Number.isInteger(year) ||
    year < 1900 ||
    year > 2200 ||
    !Number.isInteger(month) ||
    month < 1 ||
    month > 12
  )
    throw new Error("请选择当前年度的有效月份");
  const prefix = `${year}-${String(month).padStart(2, "0")}`;
  return {
    periodStart: `${prefix}-01`,
    periodEnd: `${prefix}-${new Date(Date.UTC(year, month, 0)).getUTCDate()}`,
  };
}
export function cashTemplate(standard: string) {
  if (standard === "CAS") return "CAS-CF-2019-A2";
  if (standard === "SMALL") return "SBE-CF-2011";
  throw new Error("当前制度无可用现金范围模板");
}
export function cashCreatePayload(
  standard: string,
  year: number,
  values: { month: number; reason: string; items: CashScopeItem[] },
) {
  if (values.items.length < 1 || values.items.length > 20)
    throw new Error("每次须登记1至20项现金范围");
  if (
    new Set(values.items.map((item) => item.accountId)).size !==
    values.items.length
  )
    throw new Error("同一范围不能重复选择科目");
  return {
    schemaVersion: cashSchema,
    reportScope: "SEPARATE",
    currency: "CNY",
    templateId: cashTemplate(standard),
    ...cashMonth(year, values.month),
    reason: values.reason,
    items: values.items.map((item) => {
      if (item.singleCashInstrument !== true)
        throw new Error(
          "须主动确认该年度科目仅对应一个独立现金工具或账户，混合科目不支持",
        );
      if (
        item.qualification === "INCLUDED" &&
        (item.restrictionKind !== "NONE" || item.availableOnDemand !== true)
      )
        throw new Error("纳入现金范围须无使用限制并可随时支取");
      if (item.evidenceSource === "PAPER_SCAN" && !item.paperLocation?.trim())
        throw new Error("纸质扫描件须登记实体原件保管位置");
      const basisLength =
        item.eligibilityBasis.length + (item.paperLocation?.length ?? 0) + 60;
      if (basisLength > 2000) throw new Error("资格依据和证据保管说明合计过长");
      return {
        accountId: item.accountId,
        singleCashInstrument: item.singleCashInstrument,
        instrumentKind: item.instrumentKind,
        qualification: item.qualification,
        restrictionKind: item.restrictionKind,
        availableOnDemand: item.availableOnDemand,
        eligibilityBasis: item.evidenceSource
          ? `${item.eligibilityBasis}
证据来源：${item.evidenceSource === "PAPER_SCAN" ? "纸质凭证扫描件" : "电子原件"}${item.evidenceSource === "PAPER_SCAN" ? `；纸质原件保管位置：${item.paperLocation}` : ""}`
          : item.eligibilityBasis,
        evidenceOriginalId: item.evidenceOriginalId,
      };
    }),
  };
}
export function cashDecisionPayload(detail: CashScopeSummary, reason: string) {
  return {
    schemaVersion: cashSchema,
    expectedVersion: detail.version,
    targetContentVersion: detail.contentVersion,
    contentDigest: detail.contentDigest,
    reason,
  };
}

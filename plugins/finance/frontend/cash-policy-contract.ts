export const policySchema = "cash-classification-policy/1";
export const policyAssertions = [
  ["ordinaryGrossOnly", "仅适用于有真实依据的普通全额现金业务"],
  ["singleLegalEntity", "仅本账套单一法人范围，不用于合并或跨主体处理"],
  ["cnyOnly", "仅人民币，不含外币或汇兑"],
  [
    "purposeEvidenceRequiredForEachFutureUse",
    "未来每笔使用仍须独立核对具体业务用途证据",
  ],
  [
    "noAutomaticAccountInference",
    "不根据科目、供应商、工资或税款名称自动推断用途",
  ],
  [
    "noInternalTransferRefundFxControlOrNetting",
    "不涵盖内部划转、退款、外币、控制交易或净额结算",
  ],
  [
    "noUnsupportedCapitalizedPayrollInterestOrTaxComponents",
    "不涵盖未支持的资本化工资、利息或性质不明税款组成",
  ],
] as const;
export type PolicyTuple = {
  transactionKind: string;
  businessPurpose: string;
  direction: string;
  semanticLeaf: string;
  rowId: string;
};
export type PolicyCatalog = {
  standard: string;
  templateId: string;
  dictionaryVersion: string;
  items: PolicyTuple[];
};
export const purposeLabels: Record<string, string> = {
  SALES_GOODS_SERVICES_GROSS: "销售商品及服务的实际全额现金收取",
  OPERATING_GOODS_SERVICES_GROSS:
    "经营性商品和服务的全额付款（非任意供应商付款）",
  OPERATING_EMPLOYEE_PAY_GROSS:
    "普通经营性职工全额付款（排除建设/开发资本化工资）",
  ORDINARY_OPERATING_TAX_ACTUAL: "性质明确的普通经营税款实际付款（非全部税款）",
  BORROWING_PRINCIPAL_CASH: "实际借款本金现金收取",
  BORROWING_PRINCIPAL_ONLY: "仅借款本金偿还（不含利息）",
  EQUITY_CAPITAL_CASH_GROSS: "实际权益资本全额现金投入",
};
export type PolicyEvidence = {
  originalId: string;
  purpose: string;
  description: string;
  originalVersion?: number;
  sha256?: string;
  filename?: string;
};
export type PolicySummary = {
  id: string;
  policyCode: string;
  revision: number;
  supersedesPolicyId: string | null;
  status: string;
  version: number;
  contentVersion: number;
  contentDigest: string;
  transactionKind: string;
  businessPurpose: string;
  direction: string;
  effectiveFrom: string;
  effectiveTo: string;
  preparedBy: string;
  submittedAt: string | null;
  submissionExpiresAt: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  decisionReason: string | null;
  retiredBy: string | null;
  retiredAt: string | null;
  activeForNewUse: boolean;
  formalReportContributionEnabled: false;
};
export type PolicyDetail = PolicySummary & {
  standard: string;
  templateId: string;
  dictionaryVersion: string;
  semanticLeaf: string;
  rowId: string;
  policyBasis: string;
  applicabilityStatement: string;
  assertions: Record<string, boolean>;
  evidence: PolicyEvidence[];
  pendingRetirementId: string | null;
  policyWatermark: number;
  limitations: string[];
  reason: string;
};
export type PolicyRetirement = {
  id: string;
  policyId: string;
  expectedPolicyVersion: number;
  policyContentDigest: string;
  status: string;
  version: number;
  contentDigest: string;
  reason: string;
  requestedBy: string;
  requestedAt: string;
  reviewExpiresAt: string;
  reviewedBy: string | null;
  reviewedAt: string | null;
  decisionReason: string | null;
};
export type PolicyForm = {
  policyCode: string;
  catalogKind: string;
  effectiveFrom: string;
  effectiveTo: string;
  policyBasis: string;
  applicabilityStatement: string;
  assertions: Record<string, boolean>;
  evidence: PolicyEvidence[];
  reason: string;
};
function validDate(value: string, year: number) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || Number(value.slice(0, 4)) !== year)
    return false;
  const date = new Date(`${value}T00:00:00Z`);
  return (
    Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value
  );
}
export function policyCreatePayload(
  values: PolicyForm,
  catalog: PolicyCatalog,
  year: number,
  predecessor: PolicySummary | null,
) {
  const tuple = catalog.items.find(
    (tuple) => tuple.transactionKind === values.catalogKind,
  );
  if (!tuple)
    throw new Error("必须从服务端普通用途目录选择明确的业务种类与用途组合");
  if (!/^[A-Z][A-Z0-9_]{2,49}$/.test(values.policyCode))
    throw new Error("政策编码须为3至50位大写字母、数字或下划线，且字母开头");
  if (
    !validDate(values.effectiveFrom, year) ||
    !validDate(values.effectiveTo, year) ||
    values.effectiveFrom > values.effectiveTo
  )
    throw new Error("现金日期适用区间须为当前年度内真实日期，起日不得晚于止日");
  if (
    predecessor &&
    (predecessor.policyCode !== values.policyCode ||
      predecessor.transactionKind !== tuple.transactionKind ||
      predecessor.businessPurpose !== tuple.businessPurpose)
  )
    throw new Error(
      "继任须保持同编码与分类维度，前序必须是服务端最新任意状态修订",
    );
  const assertions = Object.fromEntries(
    policyAssertions.map(([key]) => {
      if (values.assertions?.[key] !== true)
        throw new Error("七项边界声明须由用户逐项主动勾选");
      return [key, values.assertions[key]];
    }),
  );
  if (
    !Array.isArray(values.evidence) ||
    values.evidence.length < 2 ||
    values.evidence.length > 10 ||
    new Set(values.evidence.map((e) => e.originalId)).size !==
      values.evidence.length
  )
    throw new Error(
      "须选择2至10份不同原件，不能重复使用同一原件ID充当不同角色",
    );
  const roles = new Set(values.evidence.map((e) => e.purpose));
  if (
    !roles.has("POLICY_BASIS") ||
    !roles.has("APPLICABILITY_SUPPORT") ||
    [...roles].some(
      (role) => !["POLICY_BASIS", "APPLICABILITY_SUPPORT"].includes(role),
    )
  )
    throw new Error("必须同时有政策依据与适用性支持两类真实证据");
  return {
    schemaVersion: policySchema,
    policyCode: values.policyCode,
    supersedesPolicyId: predecessor?.id ?? null,
    transactionKind: tuple.transactionKind,
    businessPurpose: tuple.businessPurpose,
    reportScope: "SEPARATE",
    currency: "CNY",
    transactionForm: "ORDINARY_GROSS_CASH",
    effectiveFrom: values.effectiveFrom,
    effectiveTo: values.effectiveTo,
    policyBasis: values.policyBasis,
    applicabilityStatement: values.applicabilityStatement,
    assertions,
    evidence: values.evidence.map((e) => ({
      originalId: e.originalId,
      purpose: e.purpose,
      description: e.description,
    })),
    reason: values.reason,
  };
}
export function policyActionPayload(
  record: { version: number; contentDigest: string },
  reason: string,
) {
  return {
    schemaVersion: policySchema,
    expectedVersion: record.version,
    contentDigest: record.contentDigest,
    reason,
  };
}
export function policyPublicationLabel(
  policy: Pick<PolicySummary, "status" | "activeForNewUse">,
) {
  if (policy.status === "APPROVED")
    return policy.activeForNewUse
      ? "已发布，具后续逐笔评估资格（不是已使用）"
      : "已发布，未来新用冻结（检查退役请求）";
  return policy.status === "RETIRED"
    ? "已退役，历史保留，不可新用"
    : "尚无发布资格";
}

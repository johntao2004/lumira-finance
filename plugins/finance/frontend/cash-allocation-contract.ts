import { cnyUnits, units } from "./types.ts";
import type { PolicySummary } from "./cash-policy-contract";
export const allocationSchema = "cash-allocation/1";
export type AllocationExpectedParent = {
  reviewId: string;
  expectedReviewVersion: number;
  reviewContentDigest: string;
  activeReviewUsageId: string;
  claimId: string;
  claimGeneration: number;
  claimContentDigest: string;
  sourceMatchGeneration: number;
};
export type AllocationEvidenceInput = {
  originalId: string;
  purpose: "BUSINESS_PURPOSE_SUPPORT" | "FACT_CORROBORATION";
  description: string;
};
export type AllocationEvidenceSnapshot = {
  originalId: string;
  purpose: "BUSINESS_PURPOSE_SUPPORT" | "FACT_CORROBORATION";
  description: string;
  originalVersion: number;
  sha256: string;
  filename: string;
};
export type AllocationOriginalSnapshot = {
  originalId: string;
  version: number;
  sha256: string;
  filename: string;
};
export type AllocationYearWatermark = {
  postingSeq: number;
  openingVersion: number;
  baselineVersion: number;
  sourceCloseVersion: number;
  legacyUnsequencedFacts: boolean;
};
export type AllocationLineInput = {
  componentKey: string;
  amount: string;
  policyId: string;
  expectedPolicyVersion: number;
  policyContentDigest: string;
  businessPurpose:
    | "SALES_GOODS_SERVICES_GROSS"
    | "OPERATING_GOODS_SERVICES_GROSS"
    | "OPERATING_EMPLOYEE_PAY_GROSS"
    | "ORDINARY_OPERATING_TAX_ACTUAL"
    | "BORROWING_PRINCIPAL_CASH"
    | "BORROWING_PRINCIPAL_ONLY"
    | "EQUITY_CAPITAL_CASH_GROSS";
  purposeEvidenceOriginalId: string;
  description: string;
  ordinaryPurposeConfirmed: true;
};
export type AllocationCreate = {
  schemaVersion: "cash-allocation/1";
  idempotencyKey?: string;
  expectedParent: AllocationExpectedParent;
  supersedesAllocationId: string | null;
  expectedSupersededVersion: number | null;
  supersededContentDigest: string | null;
  lines: Array<AllocationLineInput>;
  reason: string;
};
export type AllocationAction = {
  schemaVersion: "cash-allocation/1";
  idempotencyKey?: string;
  expectedVersion?: number;
  contentDigest: string;
  expectedParent: AllocationExpectedParent;
  reason: string;
};
export type AllocationReleaseCreate = {
  schemaVersion: "cash-allocation/1";
  idempotencyKey?: string;
  expectedVersion?: number;
  contentDigest: string;
  activeDependencyId: string;
  reason: string;
};
export type AllocationDecision = {
  schemaVersion: "cash-allocation/1";
  idempotencyKey?: string;
  expectedVersion?: number;
  contentDigest: string;
  reason: string;
};
export type AllocationReplacementCreate = {
  schemaVersion: "cash-allocation/1";
  idempotencyKey?: string;
  oldAllocationId: string;
  expectedOldVersion: number;
  oldContentDigest: string;
  oldDependencyId: string;
  newAllocationId: string;
  expectedNewVersion: number;
  newContentDigest: string;
  expectedParent: AllocationExpectedParent;
  reason: string;
};
export type AllocationPolicySnapshot = {
  policyId: string;
  policyCode: string;
  revision: number;
  policyVersion: number;
  contentDigest: string;
  dictionaryVersion: "ordinary-cash-policy-dictionary/1";
  standard: "CAS" | "SMALL";
  templateId: "CAS-CF-2019-A2" | "SBE-CF-2011";
  transactionKind:
    | "ORDINARY_CUSTOMER_RECEIPT"
    | "ORDINARY_SUPPLIER_PAYMENT"
    | "ORDINARY_PAYROLL"
    | "ORDINARY_TAX_PAYMENT"
    | "ORDINARY_BORROWING_RECEIPT"
    | "ORDINARY_PRINCIPAL_REPAYMENT"
    | "ORDINARY_CAPITAL_RECEIPT";
  businessPurpose:
    | "SALES_GOODS_SERVICES_GROSS"
    | "OPERATING_GOODS_SERVICES_GROSS"
    | "OPERATING_EMPLOYEE_PAY_GROSS"
    | "ORDINARY_OPERATING_TAX_ACTUAL"
    | "BORROWING_PRINCIPAL_CASH"
    | "BORROWING_PRINCIPAL_ONLY"
    | "EQUITY_CAPITAL_CASH_GROSS";
  direction: "IN" | "OUT";
  semanticLeaf: string;
  rowId: string;
  reportScope: "SEPARATE";
  currency: "CNY";
  transactionForm: "ORDINARY_GROSS_CASH";
  effectiveFrom: string;
  effectiveTo: string;
  approvedBy: string;
  approvedAt: string;
};
export type AllocationLine = {
  lineNo: number;
  componentKey: string;
  normalizedAmount: string;
  policySnapshot: AllocationPolicySnapshot;
  businessPurpose: string;
  purposeEvidence: AllocationOriginalSnapshot;
  description: string;
  ordinaryPurposeConfirmed: true;
};
export type AllocationParentSnapshot = {
  reviewId: string;
  expectedReviewVersion: number;
  reviewContentDigest: string;
  activeReviewUsageId: string;
  claimId: string;
  claimGeneration: number;
  claimContentDigest: string;
  sourceMatchGeneration: number;
  rawRegistrationId: string;
  rawContentDigest: string;
  claimKind: "RAW_REGISTRATION" | "MATCH_CORRECTION";
  correctionId: string | null;
  journalId: string;
  lineNo: number;
  journalVersion: number;
  postingSeq: number;
  postingDigest: string;
  accountId: string;
  scopeRevisionId: string;
  scopeVersion: number;
  scopeDigest: string;
  occurredDate: string;
  direction: "IN" | "OUT";
  currency: "CNY";
  grossAmount: string;
  transactionKind:
    | "ORDINARY_CUSTOMER_RECEIPT"
    | "ORDINARY_SUPPLIER_PAYMENT"
    | "ORDINARY_PAYROLL"
    | "ORDINARY_TAX_PAYMENT"
    | "ORDINARY_BORROWING_RECEIPT"
    | "ORDINARY_PRINCIPAL_REPAYMENT"
    | "ORDINARY_CAPITAL_RECEIPT";
};
export type AllocationRequest = {
  id: string;
  allocationId: string;
  dependencyId: string;
  allocationContentDigest: string;
  expectedAllocationVersion: number;
  status: "REQUESTED" | "APPROVED" | "REJECTED" | "CANCELLED";
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
export type AllocationReplacement = {
  id: string;
  oldAllocationId: string;
  oldDependencyId: string;
  expectedOldVersion: number;
  oldContentDigest: string;
  newAllocationId: string;
  expectedNewVersion: number;
  newContentDigest: string;
  parentReviewId: string;
  claimId: string;
  status: "REQUESTED" | "APPROVED" | "REJECTED" | "CANCELLED";
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
export type AllocationSummary = {
  id: string;
  reviewId: string;
  claimId: string;
  claimGeneration: number;
  status:
    | "DRAFT"
    | "SUBMITTED"
    | "APPROVED"
    | "REJECTED"
    | "CANCELLED"
    | "RELEASED"
    | "SUPERSEDED";
  version: number;
  contentVersion: 1;
  contentDigest: string;
  preparedBy: string;
  createdAt: string;
  submittedAt: string | null;
  submissionExpiresAt: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  decisionBy: string | null;
  decisionAt: string | null;
  decisionReason: string | null;
  activeDependencyId: string | null;
  currentEffective: boolean;
  supersedesAllocationId: string | null;
  supersededByAllocationId: string | null;
  releasedBy: string | null;
  releasedAt: string | null;
  formalReportContributionEnabled: false;
  coverageVerified: false;
};
export type AllocationWatermark = {
  allocationVersion: number;
  sourceReviewVersion: number;
  yearMatchVersion: number;
  sourceMatchGeneration: number;
  policyPublicationVersion: number;
  year: AllocationYearWatermark;
};
export type AllocationDetail = {
  id: string;
  reviewId: string;
  claimId: string;
  claimGeneration: number;
  status:
    | "DRAFT"
    | "SUBMITTED"
    | "APPROVED"
    | "REJECTED"
    | "CANCELLED"
    | "RELEASED"
    | "SUPERSEDED";
  version: number;
  contentVersion: 1;
  contentDigest: string;
  preparedBy: string;
  createdAt: string;
  submittedAt: string | null;
  submissionExpiresAt: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  decisionBy: string | null;
  decisionAt: string | null;
  decisionReason: string | null;
  activeDependencyId: string | null;
  currentEffective: boolean;
  supersedesAllocationId: string | null;
  supersededByAllocationId: string | null;
  releasedBy: string | null;
  releasedAt: string | null;
  formalReportContributionEnabled: false;
  coverageVerified: false;
  tenantId: string;
  bookId: string;
  yearId: string;
  legalEntityId: string;
  parentSnapshot: AllocationParentSnapshot;
  lines: Array<AllocationLine>;
  normalizedTotal: string;
  reason: string;
  pendingRelease: AllocationRequest | null;
  pendingReplacement: AllocationReplacement | null;
  watermark: AllocationWatermark;
  limitations: Array<string>;
};
export type AllocationList = {
  items: Array<AllocationSummary>;
  nextCursor: string | null;
};
export type AllocationReleaseList = {
  items: Array<AllocationRequest>;
  nextCursor: string | null;
};
export type AllocationReplacementList = {
  items: Array<AllocationReplacement>;
  nextCursor: string | null;
};
export type AllocationEligible = {
  items: Array<AllocationParentSnapshot>;
  nextCursor: string | null;
};
export type AllocationFormLine = {
  componentKey: string;
  amount: string;
  policyId: string;
  purposeEvidenceOriginalId: string;
  description: string;
  ordinaryPurposeConfirmed: boolean;
};
export type AllocationForm = { lines: AllocationFormLine[]; reason: string };
export function expectedAllocationParent(
  parent: AllocationExpectedParent,
): AllocationExpectedParent {
  if (
    !parent.reviewId ||
    !parent.activeReviewUsageId ||
    !parent.claimId ||
    !/^[a-f0-9]{64}$/.test(parent.reviewContentDigest) ||
    !/^[a-f0-9]{64}$/.test(parent.claimContentDigest) ||
    ![
      parent.expectedReviewVersion,
      parent.claimGeneration,
      parent.sourceMatchGeneration,
    ].every((n) => Number.isSafeInteger(n) && n > 0)
  )
    throw new Error("权威父级人工复核、使用关系或匹配版本缺失，请刷新");
  return {
    reviewId: parent.reviewId,
    expectedReviewVersion: parent.expectedReviewVersion,
    reviewContentDigest: parent.reviewContentDigest,
    activeReviewUsageId: parent.activeReviewUsageId,
    claimId: parent.claimId,
    claimGeneration: parent.claimGeneration,
    claimContentDigest: parent.claimContentDigest,
    sourceMatchGeneration: parent.sourceMatchGeneration,
  };
}
export function sameAllocationParent(
  a: AllocationExpectedParent,
  b: AllocationExpectedParent,
) {
  return (
    JSON.stringify(expectedAllocationParent(a)) ===
    JSON.stringify(expectedAllocationParent(b))
  );
}
export function allocationPolicyEligible(
  policy: PolicySummary,
  parent: AllocationParentSnapshot,
) {
  return (
    policy.status === "APPROVED" &&
    policy.activeForNewUse === true &&
    policy.transactionKind === parent.transactionKind &&
    policy.direction === parent.direction &&
    policy.effectiveFrom <= parent.occurredDate &&
    parent.occurredDate <= policy.effectiveTo
  );
}
function reasonText(reason: string) {
  if (!reason?.trim() || reason.length > 1000)
    throw new Error("请填写不超过1000字的业务理由");
  return reason.trim();
}
export function allocationCreatePayload(
  form: AllocationForm,
  parent: AllocationParentSnapshot,
  policies: PolicySummary[],
  old: AllocationDetail | null,
): Omit<AllocationCreate, "idempotencyKey"> {
  if (
    parent.currency !== "CNY" ||
    !Array.isArray(form.lines) ||
    form.lines.length < 1 ||
    form.lines.length > 20
  )
    throw new Error("仅支持1至20项普通人民币全额组成部分");
  const seen = new Set<string>();
  let total = 0n;
  const lines = form.lines.map((line) => {
    if (
      !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,79}$/.test(line.componentKey) ||
      seen.has(line.componentKey)
    )
      throw new Error(
        "每个真实组成部分须有唯一的1至80位英文数字组件键，可用点、下划线、冒号或短横线",
      );
    seen.add(line.componentKey);
    const magnitude = cnyUnits(line.amount);
    if (magnitude <= 0n)
      throw new Error(
        "每个组成部分须为正数人民币金额，不得负数、零值、净额或残差",
      );
    total += magnitude;
    const policy = policies.find((p) => p.id === line.policyId);
    if (!policy || !allocationPolicyEligible(policy, parent))
      throw new Error(
        "须选择服务端当前发布且适用于真实现金日期、方向和普通业务种类的政策",
      );
    if (
      !Number.isSafeInteger(policy.version) ||
      policy.version < 1 ||
      !/^[a-f0-9]{64}$/.test(policy.contentDigest)
    )
      throw new Error("政策权威版本或摘要缺失，请刷新");
    if (line.ordinaryPurposeConfirmed !== true)
      throw new Error(
        "每个组件须由用户主动确认其具体普通用途，不可按科目名推断",
      );
    if (
      !line.purposeEvidenceOriginalId ||
      !line.description?.trim() ||
      line.description.length > 1000
    )
      throw new Error("每项须选择实际用途原件并填写不超过1000字的业务依据");
    return {
      componentKey: line.componentKey,
      amount: line.amount,
      policyId: policy.id,
      expectedPolicyVersion: policy.version,
      policyContentDigest: policy.contentDigest,
      businessPurpose:
        policy.businessPurpose as AllocationLineInput["businessPurpose"],
      purposeEvidenceOriginalId: line.purposeEvidenceOriginalId,
      description: line.description.trim(),
      ordinaryPurposeConfirmed: true as const,
    };
  });
  if (total !== units(parent.grossAmount))
    throw new Error(
      "组件金额合计必须精确等于父级来源全额；不舍入、不自动填残差",
    );
  if (
    old &&
    (old.status !== "APPROVED" ||
      !old.currentEffective ||
      !old.activeDependencyId ||
      old.pendingRelease ||
      old.pendingReplacement ||
      !sameAllocationParent(parent, old.parentSnapshot))
  )
    throw new Error(
      "替换草稿须绑定同一父级的当前有效分配，且不能有待审释放或替换",
    );
  return {
    schemaVersion: allocationSchema,
    expectedParent: expectedAllocationParent(parent),
    supersedesAllocationId: old?.id ?? null,
    expectedSupersededVersion: old?.version ?? null,
    supersededContentDigest: old?.contentDigest ?? null,
    lines,
    reason: reasonText(form.reason),
  };
}
export function allocationActionPayload(
  allocation: AllocationDetail,
  reason: string,
): AllocationAction {
  return {
    schemaVersion: allocationSchema,
    expectedVersion: allocation.version,
    contentDigest: allocation.contentDigest,
    expectedParent: expectedAllocationParent(allocation.parentSnapshot),
    reason: reasonText(reason),
  };
}
export function allocationReleasePayload(
  allocation: AllocationDetail,
  reason: string,
): AllocationReleaseCreate {
  if (!allocation.activeDependencyId)
    throw new Error("当前没有可释放的实际分配依赖，请刷新");
  return {
    schemaVersion: allocationSchema,
    expectedVersion: allocation.version,
    contentDigest: allocation.contentDigest,
    activeDependencyId: allocation.activeDependencyId,
    reason: reasonText(reason),
  };
}
export function allocationDecisionPayload(
  request: { version: number; contentDigest: string },
  reason: string,
): AllocationDecision {
  return {
    schemaVersion: allocationSchema,
    expectedVersion: request.version,
    contentDigest: request.contentDigest,
    reason: reasonText(reason),
  };
}
export function allocationReplacementPayload(
  old: AllocationDetail,
  next: AllocationDetail,
  reason: string,
): AllocationReplacementCreate {
  if (
    old.id === next.id ||
    old.status !== "APPROVED" ||
    !old.currentEffective ||
    !old.activeDependencyId ||
    old.pendingRelease ||
    old.pendingReplacement ||
    next.pendingReplacement ||
    next.status !== "SUBMITTED" ||
    next.supersedesAllocationId !== old.id ||
    !sameAllocationParent(old.parentSnapshot, next.parentSnapshot)
  )
    throw new Error(
      "原子替换须绑定当前有效旧分配和同父级已提交的新候选，无待审释放/替换；不能普通批准替换草稿",
    );
  return {
    schemaVersion: allocationSchema,
    oldAllocationId: old.id,
    expectedOldVersion: old.version,
    oldContentDigest: old.contentDigest,
    oldDependencyId: old.activeDependencyId,
    newAllocationId: next.id,
    expectedNewVersion: next.version,
    newContentDigest: next.contentDigest,
    expectedParent: expectedAllocationParent(next.parentSnapshot),
    reason: reasonText(reason),
  };
}
export function allocationIndependent(
  actor: string | undefined,
  rawCreator: string | undefined,
  ...excluded: (string | null | undefined)[]
) {
  return (
    !!actor && !!rawCreator && actor !== rawCreator && !excluded.includes(actor)
  );
}
export function allocationEffectLabel(
  allocation: Pick<
    AllocationDetail,
    "status" | "currentEffective" | "pendingRelease" | "pendingReplacement"
  >,
) {
  if (allocation.status === "SUPERSEDED")
    return "历史分配已被原子替换；查看继任记录，不据其后续状态抹除本次替换历史";
  if (allocation.status === "RELEASED")
    return "分配依赖已释放，历史保留；父级人工复核使用关系与匹配声明未释放";
  if (
    allocation.currentEffective &&
    (allocation.pendingRelease || allocation.pendingReplacement)
  )
    return "当前分配仍有效并占用依赖，待审释放/替换冻结后续新依赖";
  if (allocation.currentEffective)
    return "当前普通组成分配有效；不代表全量覆盖、工作底稿或正式报表";
  return "当前无有效分配；草稿与历史批准不等于当前效果";
}

export const sourceReviewSchema = "cash-source-review/1";
export const sourceReviewChecklist = [
  ["sourceRowInspected", "已逐项检查真实来源文件及全文件唯一行位置"],
  ["actualEvidenceBytesInspected", "已下载检查实际保留的原件字节"],
  [
    "scopeInstrumentAndAccountInspected",
    "已核对本月现金范围、独立现金工具及科目",
  ],
  ["dateDirectionAndFullAmountMatched", "已核对真实现金日期、方向和全额金额"],
  ["effectivePostedCashLegMatched", "已核对当前有效匹配及完整已记账现金行"],
  ["businessPurposeEvidenceInspected", "已核对具体业务用途及其真实支持证据"],
  [
    "noUnsupportedSpecialOrDuplicateFactsKnown",
    "未发现退款、内部划转、外币、净额等未支持特殊事实或重复来源",
  ],
  [
    "humanReviewNotBankAuthenticity",
    "理解这是人工文件复核，不代表银行验真、法律或税务结论",
  ],
] as const;
export type ExpectedReviewClaim = {
  rawRegistrationId: string;
  rawContentDigest: string;
  claimId: string;
  claimKind: "RAW_REGISTRATION" | "MATCH_CORRECTION";
  claimGeneration: number;
  claimContentDigest: string;
  sourceMatchGeneration: number;
};
export type ReviewOriginal = {
  originalId: string;
  version: number;
  sha256: string;
  filename: string;
};
export type ReviewClaim = ExpectedReviewClaim & {
  correctionId: string | null;
  journalId: string;
  lineNo: number;
  journalVersion: number;
  postingSeq: number;
  postingDigest: string;
  accountId: string;
  cashScopeRevisionId: string;
  cashScopeVersion: number;
  cashScopeDigest: string;
  accountSnapshotDigest: string;
  occurredDate: string;
  direction: string;
  currency: string;
  normalizedAmount: string;
  sourceOriginal: ReviewOriginal;
  scopeOriginal: ReviewOriginal;
  rawVersion: number;
  sourceIdentity: {
    sourceSystem: string;
    sourceMovementKey: string;
    sourceDocumentId: string;
    sourceDocumentVersion: number;
    evidenceKind: string;
    evidenceOriginalId: string;
    sourceRowReference: string;
    transactionKind: string;
    description: string;
    preparedBy: string;
  };
};
export type ReviewEvidence = {
  originalId: string;
  purpose: string;
  description: string;
  filename?: string;
  sha256?: string;
  originalVersion?: number;
};
export type SourceReviewSummary = {
  id: string;
  rawRegistrationId: string;
  claimId: string;
  claimKind: string;
  claimGeneration: number;
  contentDigest: string;
  contentVersion: number;
  version: number;
  status: string;
  preparedBy: string;
  createdAt: string;
  submittedAt: string | null;
  submissionExpiresAt: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  decisionBy: string | null;
  decisionAt: string | null;
  decisionReason: string | null;
  activeUsageId: string | null;
  releasedBy: string | null;
  releasedAt: string | null;
  currentEffective: boolean;
  formalReportContributionEnabled: false;
  bankAuthenticityVerified: false;
};
export type SourceReviewRelease = {
  id: string;
  reviewId: string;
  usageId: string;
  expectedReviewVersion: number;
  reviewContentDigest: string;
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
export type SourceReviewDetail = SourceReviewSummary & {
  claimSnapshot: ReviewClaim;
  businessPurposeStatement: string;
  factReviewStatement: string;
  checklist: Record<string, boolean>;
  evidence: ReviewEvidence[];
  conclusion: string;
  method: string;
  reason: string;
  pendingRelease: SourceReviewRelease | null;
  watermark: unknown;
  limitations: string[];
};
export type SourceReviewForm = {
  businessPurposeStatement: string;
  factReviewStatement: string;
  checklist: Record<string, boolean>;
  evidence: ReviewEvidence[];
  reason: string;
};
export function expectedReviewClaim(
  claim: ExpectedReviewClaim,
): ExpectedReviewClaim {
  if (
    !claim.rawRegistrationId ||
    !claim.claimId ||
    !["RAW_REGISTRATION", "MATCH_CORRECTION"].includes(claim.claimKind) ||
    !/^[a-f0-9]{64}$/.test(claim.rawContentDigest) ||
    !/^[a-f0-9]{64}$/.test(claim.claimContentDigest) ||
    !Number.isSafeInteger(claim.claimGeneration) ||
    claim.claimGeneration < 1 ||
    !Number.isSafeInteger(claim.sourceMatchGeneration) ||
    claim.sourceMatchGeneration < 1
  )
    throw new Error("来源声明权威身份、代次或摘要缺失，请刷新后重新选择");
  return {
    rawRegistrationId: claim.rawRegistrationId,
    rawContentDigest: claim.rawContentDigest,
    claimId: claim.claimId,
    claimKind: claim.claimKind,
    claimGeneration: claim.claimGeneration,
    claimContentDigest: claim.claimContentDigest,
    sourceMatchGeneration: claim.sourceMatchGeneration,
  };
}
export function sameReviewClaim(
  a: ExpectedReviewClaim,
  b: ExpectedReviewClaim,
) {
  return (
    JSON.stringify(expectedReviewClaim(a)) ===
    JSON.stringify(expectedReviewClaim(b))
  );
}
function reasonText(reason: string) {
  if (!reason?.trim() || reason.length > 1000)
    throw new Error("请填写不超过1000字的业务理由");
  return reason.trim();
}
export function sourceReviewCreatePayload(
  form: SourceReviewForm,
  claim: ReviewClaim,
) {
  if (
    !form.businessPurposeStatement?.trim() ||
    form.businessPurposeStatement.length > 2000 ||
    !form.factReviewStatement?.trim() ||
    form.factReviewStatement.length > 4000
  )
    throw new Error(
      "请填写具体业务用途（最多2000字）及人工事实复核说明（最多4000字）",
    );
  const checklist = Object.fromEntries(
    sourceReviewChecklist.map(([key]) => {
      if (form.checklist?.[key] !== true)
        throw new Error("每项事实检查均须由用户主动确认，不能预先替用户勾选");
      return [key, true];
    }),
  );
  if (
    !Array.isArray(form.evidence) ||
    form.evidence.length < 1 ||
    form.evidence.length > 10 ||
    new Set(form.evidence.map((e) => e.originalId)).size !==
      form.evidence.length ||
    !form.evidence.some((e) => e.purpose === "BUSINESS_PURPOSE_SUPPORT")
  )
    throw new Error(
      "请选择1至10份不重复的真实原件，至少一份具体业务用途支持证据",
    );
  const evidence = form.evidence.map((e) => {
    if (
      !e.originalId ||
      !["BUSINESS_PURPOSE_SUPPORT", "FACT_CORROBORATION"].includes(e.purpose) ||
      !e.description?.trim() ||
      e.description.length > 500
    )
      throw new Error("每份证据须有真实原件、明确角色及不超过500字说明");
    return {
      originalId: e.originalId,
      purpose: e.purpose,
      description: e.description.trim(),
    };
  });
  return {
    schemaVersion: sourceReviewSchema,
    expectedClaim: expectedReviewClaim(claim),
    businessPurposeStatement: form.businessPurposeStatement.trim(),
    factReviewStatement: form.factReviewStatement.trim(),
    checklist,
    evidence,
    conclusion: "SUPPORTED_FOR_PREPARATION",
    reason: reasonText(form.reason),
  };
}
export function sourceReviewActionPayload(
  review: SourceReviewDetail,
  reason: string,
) {
  return {
    schemaVersion: sourceReviewSchema,
    expectedVersion: review.version,
    contentDigest: review.contentDigest,
    expectedClaim: expectedReviewClaim(review.claimSnapshot),
    reason: reasonText(reason),
  };
}
export function sourceReviewReleasePayload(
  review: SourceReviewDetail,
  reason: string,
) {
  if (!review.activeUsageId)
    throw new Error("当前没有可释放的人工复核使用关系，请刷新");
  return {
    ...sourceReviewActionPayload(review, reason),
    activeUsageId: review.activeUsageId,
  };
}
export function sourceReviewReleaseAction(
  request: SourceReviewRelease,
  reason: string,
) {
  return {
    schemaVersion: sourceReviewSchema,
    expectedVersion: request.version,
    contentDigest: request.contentDigest,
    reason: reasonText(reason),
  };
}
export function sourceReviewIndependent(
  actor: string | undefined,
  review: SourceReviewDetail,
  requester?: string,
) {
  return (
    !!actor &&
    actor !== review.preparedBy &&
    actor !== review.claimSnapshot.sourceIdentity.preparedBy &&
    actor !== requester
  );
}
export function sourceReviewUseLabel(
  review: Pick<
    SourceReviewDetail,
    "status" | "currentEffective" | "pendingRelease"
  >,
) {
  if (review.status === "RELEASED")
    return "使用关系已释放，历史保留；现金匹配声明未释放";
  if (review.status === "APPROVED" && review.pendingRelease)
    return "待独立释放：仍占用使用关系，冻结后续新依赖";
  if (review.status === "APPROVED" && review.currentEffective)
    return "人工依据支持后续编制，使用关系有效；不是银行验真或报表";
  if (review.status === "APPROVED")
    return "曾批准，当前有效资格请以服务端快照为准";
  return "尚无已批准使用关系，不参与报表";
}

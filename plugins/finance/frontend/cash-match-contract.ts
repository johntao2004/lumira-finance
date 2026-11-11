import type { CashLeg, MovementDetail } from "./cash-movement-contract";
export const correctionSchema = "cash-match-correction/1";
export type CashClaim = {
  id: string;
  kind: string;
  rawRegistrationId: string;
  correctionId: string | null;
  generation: number;
  journalId: string;
  lineNo: number;
  accountId: string;
  journalVersion: number;
  postingSeq: number;
  postingDigest: string;
  state: string;
};
export type MatchEffective = {
  originalRegistrationId: string;
  rawStatus: string;
  rawVersion: number;
  rawContentDigest: string;
  effectiveClaim: CashClaim | null;
  currentSourceMatchGeneration: number;
  asOfWatermark: unknown;
};
export type MatchCorrection = {
  id: string;
  originalRegistrationId: string;
  rawVersionAtProposal: number;
  rawContentDigest: string;
  status: string;
  version: number;
  contentVersion: number;
  contentDigest: string;
  expectedSourceMatchGeneration: number;
  preparedBy: string;
  lastEditedBy: string;
  approvedBy: string | null;
  approvedAt: string | null;
  approvedClaimId: string | null;
  approvedMatchGeneration: number | null;
  decisionReason: string | null;
  targetJournalId: string;
  targetLineNo: number;
  targetJournalVersion: number;
  targetPostingSeq: number;
  reason: string;
  rawSnapshot: Record<string, unknown>;
  originalMatchSnapshot: CashLeg;
  targetLegSnapshot: CashLeg;
  sourceIdentityDigest: string;
  effectiveClaim: CashClaim | null;
  currentSourceMatchGeneration: number;
  asOfWatermark: unknown;
  pendingWithdrawal: WithdrawalRequest | null;
  approvalEnabled?: boolean;
  rawStatus: string;
};
export type WithdrawalRequest = {
  id: string;
  correctionId: string;
  originalRegistrationId: string;
  activeClaimId: string;
  expectedCorrectionVersion: number;
  expectedSourceMatchGeneration: number;
  status: string;
  version: number;
  contentDigest: string;
  reason: string;
  requestedBy: string;
  requestedAt: string;
  reviewedBy: string | null;
  reviewedAt: string | null;
  decisionReason: string | null;
};
export function correctionProposal(
  raw: MovementDetail,
  effective: MatchEffective,
  target: CashLeg,
  reason: string,
) {
  if (
    raw.status !== "VOIDED" ||
    effective.rawStatus !== "VOIDED" ||
    raw.id !== effective.originalRegistrationId
  )
    throw new Error("只能更正已作废来源的匹配，不能复活原登记");
  if (
    raw.version !== effective.rawVersion ||
    raw.contentDigest !== effective.rawContentDigest
  )
    throw new Error("原登记版本或摘要已变化，请刷新");
  if (effective.effectiveClaim)
    throw new Error("当前已有有效匹配，须先受控撤回，不能覆盖");
  if (target.activeClaimId || target.activeRegistrationId)
    throw new Error("目标现金腿已被有效声明占用");
  if (
    target.journalId === raw.matchedLegSnapshot.journalId &&
    target.lineNo === raw.matchedLegSnapshot.lineNo
  )
    throw new Error("新目标必须不同于原错误匹配");
  if (
    target.accountId !== raw.accountId ||
    target.date !== raw.occurredDate ||
    target.direction !== raw.direction ||
    target.normalizedAmount !== raw.normalizedAmount
  )
    throw new Error("更正不能改变来源科目、日期、方向或全额金额");
  if (
    !Number.isSafeInteger(effective.currentSourceMatchGeneration) ||
    effective.currentSourceMatchGeneration < 0 ||
    !Number.isSafeInteger(target.lineNo) ||
    target.lineNo < 1 ||
    !Number.isSafeInteger(target.journalVersion) ||
    target.journalVersion < 1 ||
    !Number.isSafeInteger(target.postingSeq) ||
    target.postingSeq < 1
  )
    throw new Error("目标权威版本或匹配代次缺失，请刷新");
  return {
    schemaVersion: correctionSchema,
    originalRegistrationId: raw.id,
    expectedRawVersion: raw.version,
    rawContentDigest: raw.contentDigest,
    expectedSourceMatchGeneration: effective.currentSourceMatchGeneration,
    targetJournalId: target.journalId,
    targetLineNo: target.lineNo,
    expectedTargetJournalVersion: target.journalVersion,
    expectedTargetPostingSeq: target.postingSeq,
    reason,
  };
}
export function correctionAction(detail: MatchCorrection, reason: string) {
  return {
    schemaVersion: correctionSchema,
    expectedVersion: detail.version,
    targetContentVersion: detail.contentVersion,
    contentDigest: detail.contentDigest,
    expectedSourceMatchGeneration: detail.currentSourceMatchGeneration,
    reason,
  };
}
export function correctionWithdrawal(detail: MatchCorrection, reason: string) {
  if (
    !detail.effectiveClaim ||
    detail.effectiveClaim.correctionId !== detail.id ||
    detail.effectiveClaim.state !== "ACTIVE"
  )
    throw new Error("该历史批准当前没有属于本更正的有效声明，不能撤回");
  if (detail.pendingWithdrawal)
    throw new Error("已有待复核撤回申请，当前占用仍保留");
  return {
    schemaVersion: correctionSchema,
    expectedVersion: detail.version,
    contentDigest: detail.contentDigest,
    activeClaimId: detail.effectiveClaim.id,
    expectedSourceMatchGeneration: detail.currentSourceMatchGeneration,
    reason,
  };
}
export function withdrawalDecision(
  request: WithdrawalRequest,
  generation: number,
  reason: string,
) {
  return {
    schemaVersion: correctionSchema,
    expectedVersion: request.version,
    contentDigest: request.contentDigest,
    expectedSourceMatchGeneration: generation,
    reason,
  };
}
export function claimOccupancy(
  leg: Pick<
    CashLeg,
    | "activeRegistrationId"
    | "activeClaimId"
    | "activeClaimKind"
    | "activeCorrectionId"
  >,
) {
  return leg.activeClaimKind === "MATCH_CORRECTION"
    ? `已被更正声明占用（更正 ${leg.activeCorrectionId ?? "待查看"}；原登记仍作废）`
    : leg.activeClaimId || leg.activeRegistrationId
      ? "已被来源有效匹配占用"
      : "未占用";
}

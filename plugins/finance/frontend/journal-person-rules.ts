// Business rules only: governance-directory assignments never grant these actions.
export type JournalPersonMode = "ACCOUNT_ONLY" | "JOURNAL_PERSON_SOD";
export class JournalPersonWriteGate {
  private owner: symbol | null = null;
  acquire() {
    if (this.owner) return null;
    this.owner = Symbol("journal-person-command");
    return this.owner;
  }
  release(token: symbol) {
    if (this.owner === token) this.owner = null;
  }
}
export type JournalPersonAction =
  | "create"
  | "edit"
  | "documents"
  | "submit"
  | "withdraw"
  | "cancel"
  | "reject"
  | "approve"
  | "post"
  | "terminate-draft"
  | "source"
  | "reversal"
  | "closing"
  | "year-plan"
  | "year-approve"
  | "year-execute";
export type JournalBusinessIdentity = {
  accountId: string;
  personId: string | null;
};
export function journalPersonRoute(
  mode: JournalPersonMode | null,
  action: JournalPersonAction,
  rejectProtocolReady = false,
): "LEGACY" | "PERSON" | "BLOCKED" {
  if (!mode) return "BLOCKED";
  if (mode === "ACCOUNT_ONLY")
    return action === "terminate-draft" ? "BLOCKED" : "LEGACY";
  if (
    [
      "source",
      "reversal",
      "closing",
      "year-plan",
      "year-approve",
      "year-execute",
    ].includes(action)
  )
    return "BLOCKED";
  if (action === "reject" && !rejectProtocolReady) return "BLOCKED";
  return ["approve", "post", "terminate-draft"].includes(action)
    ? "PERSON"
    : "LEGACY";
}
export function journalPersonIndependent(
  actor: JournalBusinessIdentity | null,
  maker: JournalBusinessIdentity | null,
  editor: JournalBusinessIdentity | null,
) {
  return (
    !!actor?.personId &&
    !!maker?.personId &&
    !!editor?.personId &&
    actor.personId !== maker.personId &&
    actor.personId !== editor.personId
  );
}
export function journalCreatorAccount(
  actor: JournalBusinessIdentity | null,
  createdBy: string,
) {
  return !!actor && actor.accountId === createdBy;
}
export function journalPersonRejectable(
  mode: JournalPersonMode,
  status: string,
) {
  return (
    status === "SUBMITTED" ||
    (mode === "JOURNAL_PERSON_SOD" && status === "APPROVED")
  );
}
export function journalPersonCounter(value: string) {
  if (
    !/^(0|[1-9][0-9]{0,18})$/.test(value) ||
    BigInt(value) > 9223372036854775807n
  )
    throw new Error("凭证版本不可读取，请重新读取当前状态。");
  return value;
}
type ApprovalCandidate = {
  id: string;
  status: string;
  contentVersion: string;
  contentDigest: string;
  approvedJournalVersion: string;
  expiresAt: string;
};
export function journalPersonPostCandidate(input: {
  businessPostAllowed: boolean;
  journalStatus: string;
  journalVersion: string;
  contentVersion: string | null;
  contentDigest: string | null;
  approval: ApprovalCandidate | null;
  now: number;
}) {
  const approval = input.approval;
  if (
    !input.businessPostAllowed ||
    input.journalStatus !== "APPROVED" ||
    !approval ||
    approval.status !== "ACTIVE" ||
    !input.contentVersion ||
    !input.contentDigest
  )
    return false;
  const expiry = Date.parse(approval.expiresAt);
  return (
    !!approval.id &&
    Number.isFinite(expiry) &&
    expiry > input.now &&
    approval.contentVersion === input.contentVersion &&
    approval.contentDigest === input.contentDigest &&
    approval.approvedJournalVersion === input.journalVersion
  );
}
export const journalPersonBlockedReasons: Partial<
  Record<JournalPersonAction, string>
> = {
  source:
    "该年度已启用凭证人员审签，本片仅支持人工普通凭证，业务来源自动生成草稿尚未接入。",
  reversal:
    "该年度的冲销凭证尚未接入人员内容及审批证明，暂不能创建；原凭证和历史仍可查阅。",
  closing: "该年度的系统结转草稿尚未接入人员内容及审批证明，暂不能生成。",
  "year-plan":
    "来源或目标年度已启用凭证人员审签，年结的人员审批协议尚未支持，不能新建年结计划。",
  "year-approve":
    "来源或目标年度已启用凭证人员审签，不能用原账号协议批准年结。",
  "year-execute":
    "来源或目标年度已启用凭证人员审签，年结人员审批协议尚未支持，不能执行结转。",
  reject: "人员模式退回协议尚未确认可用，不能通过原账号协议绕过独立审签。",
};

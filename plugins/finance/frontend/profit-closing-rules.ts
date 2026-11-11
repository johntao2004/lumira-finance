import type {
  Actor,
  Preview,
  RunDetail,
  RunSummary,
  CreateRun,
  Action,
} from "./profit-closing-contract";
import { units, amount } from "./types.ts";
export const closingKinds = {
  PROFIT_LOSS: "损益转入本年利润",
  YEAR_END: "本年利润转入未分配利润",
  TRANSFER_REVERSAL: "一次全额反向已记账结转",
};
export const closingStates = {
  DRAFT: "受控草稿",
  SUBMITTED: "待独立审核",
  APPROVED: "已批准待记账",
  POSTED: "已记账",
  CANCELLED: "已终结",
};
export function closingNormalLane(type: string | undefined) {
  return type === "NORMAL";
}
export function closingIndependent(
  actor: Actor,
  run: Pick<RunSummary, "preparedBy" | "lastEditor">,
) {
  return (
    actor.identityAvailability === "AVAILABLE" &&
    !!actor.personId &&
    actor.personId !== run.preparedBy.personId &&
    actor.personId !== run.lastEditor.personId
  );
}
export function closingSameRun(a: RunDetail, b: RunDetail) {
  return (
    a.id === b.id &&
    a.version === b.version &&
    a.journalVersion === b.journalVersion &&
    a.contentVersion === b.contentVersion &&
    a.contentDigest === b.contentDigest &&
    a.sourceDigest === b.sourceDigest
  );
}
export function closingTotals(lines: { debit: string; credit: string }[]) {
  let debit = 0n,
    credit = 0n;
  for (const line of lines) {
    const d = units(line.debit),
      c = units(line.credit);
    if (d < 0n || c < 0n) throw new Error("生成行金额不能为负。");
    debit += d;
    credit += c;
  }
  return {
    debit: amount(debit),
    credit: amount(credit),
    balanced: debit === credit,
  };
}
export function closingDate(cutoff: string, kind: Preview["producerKind"]) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(cutoff)) return false;
  const d = new Date(cutoff + "T00:00:00Z");
  if (!Number.isFinite(d.getTime()) || d.toISOString().slice(0, 10) !== cutoff)
    return false;
  if (kind === "YEAR_END") return cutoff.endsWith("-12-31");
  const next = new Date(d.getTime() + 86400000);
  return next.getUTCDate() === 1;
}
export function closingActionPayload(
  run: RunDetail,
  reason: unknown,
): Omit<Action, "idempotencyKey"> {
  if (typeof reason !== "string" || !reason.trim() || reason.length > 1000)
    throw new Error("请填写操作理由（最多1000字）。");
  return {
    schemaVersion: "profit-closing/1",
    expectedVersion: run.version,
    expectedContentVersion: run.contentVersion,
    contentDigest: run.contentDigest,
    reason,
  };
}
export function closingCreatePayload(
  preview: Preview,
  values: { operationId: string; reason: string },
  cancelled: RunSummary | null,
): Omit<CreateRun, "idempotencyKey"> {
  if (
    preview.outcome !== "PRODUCIBLE" ||
    !preview.source ||
    !preview.generatedDigest ||
    !preview.generatedLines?.length ||
    !preview.allowedActions.includes("create-run")
  )
    throw new Error("预览尚不可生成，请成功读取当前来源与完整分录。");
  if (
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
      values.operationId,
    )
  )
    throw new Error("操作标识须为小写UUID；未知结果重试必须保留原标识。");
  if (!values.reason.trim() || values.reason.length > 1000)
    throw new Error("请填写生成理由（最多1000字）。");
  if (
    cancelled &&
    (cancelled.status !== "CANCELLED" ||
      cancelled.cutoff !== preview.cutoff ||
      cancelled.producerKind !== preview.producerKind ||
      cancelled.reversesRunId !== preview.reversesRunId)
  )
    throw new Error("重建前驱必须是相同阶段、截止日与反向目标的已终结尝试。");
  return {
    schemaVersion: "profit-closing/1",
    operationId: values.operationId,
    producerKind: preview.producerKind,
    cutoff: preview.cutoff,
    expectedSource: preview.source.basis,
    expectedSourceDigest: preview.source.sourceDigest,
    expectedGeneratedDigest: preview.generatedDigest,
    predecessorPostedRunId: preview.source.latestPostedRunId,
    supersedesCancelledAttemptId: cancelled?.id ?? null,
    expectedSupersededVersion: cancelled?.version ?? null,
    reversesRunId: preview.reversesRunId,
    reason: values.reason,
  };
}

export type ClosingCommand =
  | "submit"
  | "approve"
  | "post"
  | "reject"
  | "withdraw"
  | "cancel"
  | "admin-terminate";
export function closingCommandAllowed(
  action: ClosingCommand,
  run: RunDetail | null,
  permission: {
    active: boolean;
    newUseAllowed: boolean;
    canMake: boolean;
    canApprove: boolean;
    canPost: boolean;
  },
  now: number,
) {
  if (
    !permission.active ||
    !run ||
    run.currentActor.identityAvailability !== "AVAILABLE" ||
    !run.currentActor.personId ||
    !run.allowedActions.includes(action)
  )
    return false;
  const states = {
    submit: ["DRAFT"],
    approve: ["SUBMITTED"],
    post: ["APPROVED"],
    reject: ["SUBMITTED", "APPROVED"],
    withdraw: ["SUBMITTED"],
    cancel: ["DRAFT"],
    "admin-terminate": ["DRAFT"],
  };
  if (!states[action].includes(run.status)) return false;
  if (
    action === "approve" ||
    action === "reject" ||
    action === "admin-terminate"
  )
    return (
      permission.canApprove &&
      closingIndependent(run.currentActor, run) &&
      (action !== "approve" || (permission.newUseAllowed && run.sourceFresh))
    );
  if (action === "post")
    return (
      permission.canPost &&
      permission.newUseAllowed &&
      run.sourceFresh &&
      run.currentApproval?.eligibilityForNewPost === "FRESH" &&
      Date.parse(run.currentApproval.expiresAt) > now
    );
  return (
    permission.canMake &&
    run.currentActor.accountId === run.preparedBy.accountId &&
    (action !== "submit" || (permission.newUseAllowed && run.sourceFresh))
  );
}

export function closingResourceMatches(
  stored: {
    path: string;
    revision: number;
    identity: string;
    available: boolean;
  },
  path: string,
  revision: number,
  identity: string,
  available: boolean,
) {
  return (
    stored.path === path &&
    stored.revision === revision &&
    stored.identity === identity &&
    stored.available === available
  );
}
export function closingWriteScopeDenied(status: number, code: string) {
  return status === 403 && code === "SCOPE_DENIED";
}
export function closingDraftKey(
  identity: string | undefined,
  available: boolean | undefined,
  key: string,
) {
  return JSON.stringify([identity, available, key]);
}

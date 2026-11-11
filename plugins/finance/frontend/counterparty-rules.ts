import type {
  Action,
  Actor,
  Content,
  CounterpartySummary,
  Create,
  ProposeRevision,
  RevisionDetail,
} from "./counterparty-contract";
export const counterpartyStatuses = {
  ACTIVE: "已发布启用",
  FROZEN: "已冻结",
  INACTIVE: "已终态停用",
  PENDING: "待独立确认",
  APPROVED: "已批准修订",
  REJECTED: "已驳回",
  CANCELLED: "已取消",
};
export function partyStatus(value: string | null) {
  return value === null
    ? "尚未发布"
    : (counterpartyStatuses[value as keyof typeof counterpartyStatuses] ??
        value);
}
export function partyIdentity(
  scope: string,
  auth: string,
  actor: Actor | null,
) {
  return JSON.stringify([
    scope,
    auth,
    actor?.accountId ?? null,
    actor?.personId ?? null,
    actor?.identityAvailability ?? null,
  ]);
}
export function partyActorEqual(a: Actor, b: Actor) {
  return (
    a.accountId === b.accountId &&
    a.personId === b.personId &&
    a.identityAvailability === b.identityAvailability
  );
}
export function partyCanDecide(
  actor: Actor,
  revision: RevisionDetail,
  role: string,
  approve: boolean,
) {
  return (
    approve &&
    ["OWNER", "REVIEWER"].includes(role) &&
    actor.identityAvailability === "AVAILABLE" &&
    !!actor.personId &&
    actor.personId !== revision.makerPersonId &&
    actor.accountId !== revision.makerAccountId
  );
}
export function partyContent(values: Record<string, unknown>): Content {
  const text = (key: string, required = false, max = 1000) => {
    const raw = values[key];
    const value = typeof raw === "string" ? raw.normalize("NFC") : "";
    if (
      (required && !value.trim()) ||
      value.length > max ||
      (key === "displayName"
        ? /[\u0000-\u001f\u007f-\u009f]/u
        : /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/u
      ).test(value)
    )
      throw new Error("请填写完整且不含控制字符的组织说明。");
    return value.trim() ? value : null;
  };
  const roles = Array.isArray(values.roles)
    ? values.roles.filter(
        (v): v is "CUSTOMER" | "SUPPLIER" =>
          v === "CUSTOMER" || v === "SUPPLIER",
      )
    : [];
  if (
    !roles.length ||
    new Set(roles).size !== roles.length ||
    roles.length !== (values.roles as unknown[]).length
  )
    throw new Error("请选择客户、供应商中的至少一种角色。");
  const relatedPartyStatus = values.relatedPartyStatus;
  if (
    relatedPartyStatus !== "UNKNOWN" &&
    relatedPartyStatus !== "RELATED" &&
    relatedPartyStatus !== "NOT_RELATED"
  )
    throw new Error("请选择关联方状态。");
  return {
    displayName: text("displayName", true, 300)!,
    roles,
    settlementTerms: text("settlementTerms"),
    contractDescription: text("contractDescription"),
    registrationBasis: text("registrationBasis", true)!,
    relatedPartyStatus,
    relatedPartyBasis:
      relatedPartyStatus === "UNKNOWN" ? null : text("relatedPartyBasis", true),
  };
}
export function partyCreate(
  values: Record<string, unknown>,
  operationId: string,
): Omit<Create, "idempotencyKey"> {
  if (
    values.organizationDeclarationConfirmed !== true ||
    values.identityNotPlaceholderConfirmed !== true
  )
    throw new Error("请主动确认两项组织声明。");
  const normalize = (value: unknown) => {
    if (typeof value !== "string" || /[^\x20-\x7e]/u.test(value))
      throw new Error(
        "登记身份只允许真实ASCII字符，不接受控制符或非ASCII替代。",
      );
    return value.replace(/^ +| +$/g, "").toUpperCase();
  };
  const jurisdiction = normalize(values.jurisdiction),
    identifier = normalize(values.identifier);
  if (
    !/^[A-Z]{2}$/.test(jurisdiction) ||
    !/^[A-Z0-9._/-]{1,64}$/.test(identifier)
  )
    throw new Error(
      "登记辖区需两位英文字母；登记标识仅接受英文字母、数字及 . _ / -，不得使用占位身份。",
    );
  return {
    schemaVersion: "counterparty/1",
    operationId,
    reason: partyReason(values.reason),
    identity: {
      namespace: "ORGANIZATION_DECLARED_REGISTRATION",
      jurisdiction,
      identifier,
      verification: "ORGANIZATION_DECLARED_NOT_GOVERNMENT_VERIFIED",
    },
    content: partyContent(values),
    organizationDeclarationConfirmed: true,
    identityNotPlaceholderConfirmed: true,
  };
}
export function partyReason(value: unknown) {
  if (typeof value !== "string" || !value.trim() || value.length > 1000)
    throw new Error("请填写1至1000字的操作理由。");
  if (/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/u.test(value))
    throw new Error("操作理由不能包含控制字符（允许换行及制表）。");
  return value;
}
export function partyProposal(
  values: Record<string, unknown>,
  current: CounterpartySummary,
): Omit<ProposeRevision, "idempotencyKey"> {
  const desiredStatus = values.desiredStatus;
  if (
    desiredStatus !== "ACTIVE" &&
    desiredStatus !== "FROZEN" &&
    desiredStatus !== "INACTIVE"
  )
    throw new Error("请选择拟发布状态。");
  if (current.effectiveStatus === null && desiredStatus !== "ACTIVE")
    throw new Error("尚未发布的组织仅能提出启用修订。");
  if (current.effectiveStatus === "INACTIVE")
    throw new Error("终态停用的组织不能重新启用或续提。");
  return {
    schemaVersion: "counterparty/1",
    reason: partyReason(values.reason),
    expectedRegistryVersion: current.registryVersion,
    baseApprovedRevisionId: current.effectiveRevision?.id ?? null,
    baseApprovedContentDigest: current.effectiveRevision?.contentDigest ?? null,
    previousRevisionId: current.latestRevisionId,
    desiredStatus,
    content: partyContent(values),
  };
}
export function partyAction(
  reason: unknown,
  revision: RevisionDetail,
): Omit<Action, "idempotencyKey"> {
  return {
    schemaVersion: "counterparty/1",
    reason: partyReason(reason),
    expectedRegistryVersion: revision.counterparty.registryVersion,
    expectedRevisionVersion: revision.revision.version,
    contentDigest: revision.revision.contentDigest,
  };
}
export function boundedPartyDraft<T>(
  drafts: Map<string, T>,
  key: string,
  create: () => T,
): T {
  const old = drafts.get(key);
  if (old !== undefined) {
    drafts.delete(key);
    drafts.set(key, old);
    return old;
  }
  const value = create();
  drafts.set(key, value);
  while (drafts.size > 20) drafts.delete(drafts.keys().next().value!);
  return value;
}
export function partyTabIntent(
  active: string,
  hasBook: boolean,
  hasYear: boolean,
  directory: boolean,
  canRead: boolean,
) {
  if (active === "counterparties")
    return hasBook && canRead ? active : "journals";
  if (!hasYear && hasBook && canRead && !directory) return "counterparties";
  return null;
}
/** Only the two contract-defined, already-authorized issue projections may expose navigation references. */
export function partyConflictReferences(
  status: number,
  code: string,
  issues: unknown,
): { counterpartyId: string; governanceHomeBookId: string } | null {
  if (
    status !== 409 ||
    code !== "COUNTERPARTY_IDENTITY_EXISTS" ||
    !Array.isArray(issues) ||
    issues.length !== 2
  )
    return null;
  const fields = ["counterpartyId", "governanceHomeBookId"];
  const reasons: string[] = [];
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
  for (let i = 0; i < 2; i++) {
    const issue: unknown = issues[i];
    if (!issue || typeof issue !== "object" || Array.isArray(issue))
      return null;
    const record = issue as Record<string, unknown>;
    if (
      Object.keys(record).sort().join(",") !== "field,reason,row" ||
      record.row !== 1 ||
      record.field !== fields[i] ||
      typeof record.reason !== "string" ||
      !uuid.test(record.reason)
    )
      return null;
    reasons.push(record.reason);
  }
  return {
    counterpartyId: reasons[0]!,
    governanceHomeBookId: reasons[1]!,
  };
}

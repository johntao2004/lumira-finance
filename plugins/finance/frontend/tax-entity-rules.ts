import type {
  Actor,
  ProfileInput,
  RevisionDetail,
  CreateRequest,
  RevisionRequest,
  ActionRequest,
  OriginalReference,
} from "./tax-entity-contract";
export const taxUuid =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export function taxIdentity(book: string, auth: string, actor: Actor | null) {
  return JSON.stringify([
    book,
    auth,
    actor?.accountId ?? null,
    actor?.personId ?? null,
    actor?.identityAvailability ?? null,
  ]);
}
export function taxActorEqual(a: Actor, b: Actor) {
  return (
    a.accountId === b.accountId &&
    a.personId === b.personId &&
    a.identityAvailability === b.identityAvailability
  );
}
export function taxText(
  value: unknown,
  max: number,
  required = true,
  multiline = true,
): string {
  if (typeof value !== "string") {
    if (!required && value == null) return "";
    throw new Error("请填写完整的组织登记说明。");
  }
  const s = value.normalize("NFC");
  if (
    (required && !s.trim()) ||
    s.length > max ||
    (multiline
      ? /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/u
      : /[\u0000-\u001f\u007f-\u009f]/u
    ).test(s)
  )
    throw new Error("说明不能为空或含不支持的控制字符，请检查长度。");
  return s;
}
export function taxNumber(value: unknown) {
  if (typeof value !== "string" || /[^\x20-\x7e]/.test(value))
    throw new Error(
      "登记号仅支持ASCII字母、数字及 . _ / -，请核对真实组织记录。",
    );
  const result = value.replace(/^ +| +$/g, "").toUpperCase();
  if (!/^[A-Z0-9][A-Z0-9._/-]{0,63}$/.test(result))
    throw new Error("请填写最多64位的真实登记号。");
  return result;
}
export function taxOriginalVersion(value: unknown) {
  if (typeof value === "string" && /^(0|[1-9][0-9]*)$/.test(value))
    return value;
  if (typeof value === "number" && Number.isSafeInteger(value) && value >= 0)
    return String(value);
  throw new Error("原件版本未知，请重新读取实际原件。");
}
function date(value: unknown): string | null {
  if (value == null || value === "") return null;
  if (
    typeof value !== "string" ||
    !/^\d{4}-\d{2}-\d{2}$/.test(value) ||
    !Number.isFinite(Date.parse(value)) ||
    new Date(value).toISOString().slice(0, 10) !== value
  )
    throw new Error("日期须为真实YYYY-MM-DD日期。");
  return value;
}
export function taxContent(
  v: Record<string, unknown>,
  evidence: OriginalReference,
): ProfileInput {
  const entityKind = v.entityKind,
    residencyDeclaration = v.residencyDeclaration;
  if (
    entityKind !== "LEGAL_ENTITY" &&
    entityKind !== "BRANCH" &&
    entityKind !== "OTHER_REGISTERED_TAXPAYER"
  )
    throw new Error("请选择纳税主体组织类型。");
  if (
    residencyDeclaration !== "RESIDENT" &&
    residencyDeclaration !== "NON_RESIDENT" &&
    residencyDeclaration !== "UNKNOWN"
  )
    throw new Error("请选择组织声明的居民属性，无法判断时选待核验。");
  if (
    v.singleTaxpayerConfirmed !== true ||
    v.allKnownRegistrationIdentifiersDeclared !== true ||
    v.organizationReviewedNotGovernmentVerified !== true
  )
    throw new Error("请主动完成三项组织登记声明。");
  if (
    !taxUuid.test(evidence.yearId) ||
    !taxUuid.test(evidence.originalId) ||
    !/^[a-f0-9]{64}$/.test(evidence.expectedSha256)
  )
    throw new Error("请选择本账套授权年度的真实已上传原件。");
  taxOriginalVersion(evidence.expectedVersion);
  const sourceType = v.sourceType;
  if (sourceType !== "ELECTRONIC_ORIGINAL" && sourceType !== "PAPER_SCAN")
    throw new Error("请选择原件来源。");
  const location = taxText(
    v.originalLocation,
    500,
    sourceType === "PAPER_SCAN",
  );
  return {
    displayName: taxText(v.displayName, 200, true, false),
    entityKind,
    registrationTypeDeclaration: taxText(
      v.registrationTypeDeclaration,
      100,
      true,
      false,
    ),
    residencyDeclaration,
    establishedOn: date(v.establishedOn),
    cancelledOn: date(v.cancelledOn),
    authorityCodeDeclaration:
      taxText(v.authorityCodeDeclaration, 100, false, false) || null,
    administrativeDivisionDeclaration:
      taxText(v.administrativeDivisionDeclaration, 100, false, false) || null,
    organizationVerificationBasis: taxText(
      v.organizationVerificationBasis,
      1000,
    ),
    registrationEvidence: {
      yearId: evidence.yearId,
      originalId: evidence.originalId,
      expectedVersion: evidence.expectedVersion,
      expectedSha256: evidence.expectedSha256,
      sourceType,
      originalLocation: location || null,
    },
    singleTaxpayerConfirmed: true,
    allKnownRegistrationIdentifiersDeclared: true,
    organizationReviewedNotGovernmentVerified: true,
  };
}
export function taxCreate(
  v: Record<string, unknown>,
  operationId: string,
  evidence: OriginalReference,
): Omit<CreateRequest, "idempotencyKey"> {
  if (!taxUuid.test(operationId))
    throw new Error("登记操作标识无效，请重新准备。");
  return {
    schemaVersion: "tax-entity/1",
    reason: taxText(v.reason, 1000),
    operationId,
    identity: {
      country: "CN",
      canonicalTaxRegistrationNo: taxNumber(v.taxNumber),
      verification: "ORGANIZATION_DECLARED_NOT_GOVERNMENT_VERIFIED",
    },
    content: taxContent(v, evidence),
  };
}
export function taxProposal(
  v: Record<string, unknown>,
  latest: RevisionDetail,
  evidence: OriginalReference,
): Omit<RevisionRequest, "idempotencyKey"> {
  const party = latest.taxEntity,
    desiredStatus = v.desiredStatus;
  if (latest.revision.id !== party.latestRevisionId)
    throw new Error("请先读取权威最新修订，再准备续提。");
  if (
    (desiredStatus !== "ACTIVE" && desiredStatus !== "INACTIVE") ||
    (party.effectiveStatus === "UNPUBLISHED" && desiredStatus !== "ACTIVE")
  )
    throw new Error("未发布主体只能提请启用；本地停用后可独立修订恢复。");
  return {
    schemaVersion: "tax-entity/1",
    reason: taxText(v.reason, 1000),
    expectedEntityVersion: party.version,
    expectedLatestRevisionId: latest.revision.id,
    expectedLatestRevisionVersion: latest.revision.version,
    expectedApprovedRevisionId: party.approvedRevision?.id ?? null,
    expectedApprovedContentDigest:
      party.approvedRevision?.contentDigest ?? null,
    desiredStatus,
    content: taxContent(v, evidence),
  };
}
export function taxAction(
  v: Record<string, unknown>,
  revision: RevisionDetail,
): Omit<ActionRequest, "idempotencyKey"> {
  return {
    schemaVersion: "tax-entity/1",
    reason: taxText(v.reason, 1000),
    expectedEntityVersion: revision.taxEntity.version,
    expectedVersion: revision.revision.version,
    contentDigest: revision.revision.contentDigest,
  };
}
export function taxCanDecide(
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
    actor.personId !== revision.revision.preparedBy.personId &&
    actor.accountId !== revision.revision.preparedBy.accountId
  );
}
export function taxConflictReferences(
  status: number,
  code: string,
  issues: unknown,
): { existingTaxEntityId: string; governanceHomeBookId: string } | null {
  if (
    status !== 409 ||
    code !== "TAX_ENTITY_IDENTITY_EXISTS" ||
    !Array.isArray(issues) ||
    issues.length !== 2
  )
    return null;
  const fields = ["existingTaxEntityId", "governanceHomeBookId"];
  for (let i = 0; i < 2; i++) {
    const issue: unknown = issues[i];
    if (
      !issue ||
      typeof issue !== "object" ||
      !("row" in issue) ||
      issue.row !== 1 ||
      !("field" in issue) ||
      issue.field !== fields[i] ||
      !("reason" in issue) ||
      typeof issue.reason !== "string" ||
      !taxUuid.test(issue.reason) ||
      Object.keys(issue).sort().join(",") !== "field,reason,row"
    )
      return null;
  }
  return {
    existingTaxEntityId: issues[0].reason,
    governanceHomeBookId: issues[1].reason,
  };
}
export function taxStatus(value: string) {
  const labels: Record<string, string> = {
    UNPUBLISHED: "尚未发布",
    ACTIVE: "本地档案已启用",
    INACTIVE: "本地停用（非官方注销）",
    PENDING: "待独立复核",
    APPROVED: "已批准修订",
    REJECTED: "已驳回",
    CANCELLED: "已取消",
  };
  return labels[value] ?? value;
}
export function boundedTaxDraft<T>(map: Map<string, T>, key: string, value: T) {
  map.delete(key);
  map.set(key, value);
  while (map.size > 20) map.delete(map.keys().next().value!);
}

import type {
  ActionRequest,
  Actor,
  ApproveRequest,
  BasisActionRequest,
  BasisDetail,
  BasisCreateRequest,
  CreateRequest,
  EligibleParent,
  FullReversalRequest,
  PayableDetail,
  PostRequest,
  Producer,
  SubmitRequest,
} from "./payables-ap01-contract";
export function apRequestIdentity(scope: string, auth: string, actor: Actor) {
  return JSON.stringify([
    scope,
    auth,
    actor.accountId,
    actor.personId,
    actor.identityAvailability,
  ]);
}
export function apDate(value: unknown) {
  if (
    typeof value !== "string" ||
    !/^\d{4}-\d{2}-\d{2}$/.test(value) ||
    Number.isNaN(Date.parse(value + "T00:00:00Z")) ||
    new Date(value + "T00:00:00Z").toISOString().slice(0, 10) !== value
  )
    throw new Error("请填写真实有效的自然日日期。");
  return value;
}
export const apRaw = (type: string | undefined) =>
  type === "AP_EXPENSE" || type === "AP_EXPENSE_REVERSAL";
export function apProducerMatches(
  link: Producer | null,
  journalId: string,
  rawType: string | undefined,
) {
  return (
    !!link &&
    link.journalId === journalId &&
    link.resolution === "FOUND" &&
    !!link.payableId &&
    apRaw(rawType) &&
    link.rawType === rawType &&
    link.producerKind === rawType
  );
}
export function apIndependent(
  actor: Actor,
  maker: { accountId: string; personId: string },
) {
  return (
    actor.identityAvailability === "AVAILABLE" &&
    !!actor.personId &&
    actor.accountId !== maker.accountId &&
    actor.personId !== maker.personId
  );
}
export function apActorMatches(a: Actor, b: Actor) {
  return (
    a.accountId === b.accountId &&
    a.personId === b.personId &&
    a.identityAvailability === b.identityAvailability
  );
}
export function apMoney(value: string | null) {
  return value === null ? "未知（未取得依据）" : value;
}
export function apReason(v: unknown, max = 1000) {
  if (
    typeof v !== "string" ||
    !v.trim() ||
    v.length > max ||
    /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/u.test(v)
  )
    throw new Error(`请填写1至${max}字理由，保留正常换行，不含其他控制字符。`);
  return v;
}
export function apUuid(v: unknown) {
  if (
    typeof v !== "string" ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v)
  )
    throw new Error("请选择真实已读取对象，不可填写临时或占位ID。");
  return v;
}
export function apCny(v: unknown, allowZero = false) {
  if (typeof v !== "string" || !/^(0|[1-9][0-9]{0,19})(\.[0-9]{1,2})?$/.test(v))
    throw new Error("人民币额度最多两位小数，不能舍入或使用浮点数。");
  if (!allowZero && BigInt(v.replace(".", "").replace(/^0+/, "") || "0") === 0n)
    throw new Error("金额须为正数。");
  return v;
}
export function apRequireChecks(v: Record<string, unknown>) {
  for (const key of [
    "ordinaryServiceExpenseConfirmed",
    "supplierMatchesDocumentConfirmed",
    "serviceAcceptedConfirmed",
    "noTaxAssetInventoryPayrollConfirmed",
    "payableAccountUseConfirmed",
  ])
    if (v[key] !== true)
      throw new Error("请主动确认全部业务与应付科目用途声明。");
}
export function apCreate(
  v: Record<string, unknown>,
  parent: EligibleParent,
  supplier: CreateRequest["expectedSupplier"],
  operationId: string,
): Omit<CreateRequest, "idempotencyKey"> {
  apRequireChecks(v);
  if (
    !parent.eligibleForPreparation ||
    !parent.capacity.currentBasis ||
    parent.capacity.qualification !== "ORG_REVIEWED" ||
    parent.capacity.capacity === null ||
    parent.capacity.available === null
  )
    throw new Error("当前父费用份额或会计额度不允许准备，请重新读取。");
  return {
    schemaVersion: "payables-ap01/1",
    reason: apReason(v.reason),
    operationId,
    expectedPredecessorId: parent.sourceSlot.latestAttemptId,
    expectedPredecessorVersion: parent.sourceSlot.latestAttemptVersion,
    expectedParent: parent.parent,
    expectedSupplier: supplier,
    expectedAccountingBasis: parent.capacity.currentBasis!,
    accountingDate: apDate(v.accountingDate),
    dueDate:
      typeof v.dueDate === "string" && v.dueDate ? apDate(v.dueDate) : null,
    expenseAccountId: apUuid(v.expenseAccountId),
    payableAccountId: apUuid(v.payableAccountId),
    serviceAcceptanceOriginalId: apUuid(v.serviceAcceptanceOriginalId),
    recognitionOriginalId: apUuid(v.recognitionOriginalId),
    recognitionReason: apReason(v.recognitionReason),
    summary: apReason(v.summary, 500),
    ordinaryServiceExpenseConfirmed: true,
    supplierMatchesDocumentConfirmed: true,
    serviceAcceptedConfirmed: true,
    noTaxAssetInventoryPayrollConfirmed: true,
    payableAccountUseConfirmed: true,
  };
}
export function apBasisCreate(
  v: Record<string, unknown>,
  parent: EligibleParent,
): Omit<BasisCreateRequest, "idempotencyKey"> {
  if (v.organizationReviewedNotTaxVerified !== true)
    throw new Error("请主动确认组织复核不等于税务或政府验真。");
  return {
    schemaVersion: "payables-ap01/1",
    reason: apReason(v.reason),
    documentExpectation: parent.parent.documentExpectation,
    expectedCurrentBasisId: parent.capacity.currentBasis?.basisId ?? null,
    expectedCurrentBasisVersion:
      parent.capacity.currentBasis?.basisVersion ?? null,
    accountingCapacity: apCny(v.accountingCapacity, true),
    basisOriginalId: apUuid(v.basisOriginalId),
    recognitionEligibilityReason: apReason(v.recognitionEligibilityReason),
    organizationReviewedNotTaxVerified: true,
  };
}
export function apAction(
  v: Record<string, unknown>,
  d: PayableDetail,
): Omit<ActionRequest, "idempotencyKey"> {
  return {
    schemaVersion: "payables-ap01/1",
    reason: apReason(v.reason),
    expectedVersion: d.payable.version,
    expectedContentVersion: d.payable.contentVersion,
    contentDigest: d.payable.contentDigest,
    sourceDigest: d.payable.sourceDigest,
  };
}
export function apBasisAction(
  v: Record<string, unknown>,
  d: BasisDetail,
): Omit<BasisActionRequest, "idempotencyKey"> {
  return {
    schemaVersion: "payables-ap01/1",
    reason: apReason(v.reason),
    expectedVersion: d.basis.version,
    contentDigest: d.basis.contentDigest,
  };
}
export function apExpiry(v: unknown) {
  if (v === null || v === undefined || v === "") return null;
  if (
    typeof v !== "string" ||
    !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(v) ||
    Number.isNaN(Date.parse(v))
  )
    throw new Error("审签期限请使用有效ISO UTC时间，或留空由服务端受控默认。");
  return v;
}
export function apSubmit(
  v: Record<string, unknown>,
  d: PayableDetail,
): Omit<SubmitRequest, "idempotencyKey"> {
  return {
    ...apAction(v, d),
    requestedExpiresAt: apExpiry(v.requestedExpiresAt),
  };
}
export function apApprove(
  v: Record<string, unknown>,
  d: PayableDetail,
): Omit<ApproveRequest, "idempotencyKey"> {
  if (!d.currentSubmission) throw new Error("缺少真实提交收据，不能批准。");
  return {
    ...apAction(v, d),
    submissionId: d.currentSubmission.id,
    requestedExpiresAt: apExpiry(v.requestedExpiresAt),
  };
}
export function apPost(
  v: Record<string, unknown>,
  d: PayableDetail,
): Omit<PostRequest, "idempotencyKey"> {
  if (!d.currentSubmission || !d.currentApproval)
    throw new Error("缺少真实提交或批准收据，不能记账。");
  return {
    ...apAction(v, d),
    submissionId: d.currentSubmission.id,
    approvalId: d.currentApproval.id,
  };
}
export function apReversal(
  v: Record<string, unknown>,
  d: PayableDetail,
  operationId: string,
): Omit<FullReversalRequest, "idempotencyKey"> {
  if (
    !d.postReceipt ||
    !d.openItem ||
    !d.currentSourceSlot.latestAttemptId ||
    !d.currentSourceSlot.latestAttemptVersion
  )
    throw new Error("原记账收据、未结项或前驱链缺失，不可反向。");
  return {
    schemaVersion: "payables-ap01/1",
    reason: apReason(v.reason),
    operationId,
    expectedPredecessorId: d.currentSourceSlot.latestAttemptId,
    expectedPredecessorVersion: d.currentSourceSlot.latestAttemptVersion,
    expectedOriginalVersion: d.payable.version,
    expectedOriginalContentDigest: d.payable.contentDigest,
    originalPostReceiptId: d.postReceipt.id,
    originalOpenItemId: d.openItem.id,
    expectedOpenItemVersion: d.openItem.version,
    accountingReasonOriginalId: apUuid(v.accountingReasonOriginalId),
  };
}
export function apDraft<T>(map: Map<string, T>, key: string, create: () => T) {
  const old = map.get(key);
  if (old !== undefined) {
    map.delete(key);
    map.set(key, old);
    return old;
  }
  const value = create();
  map.set(key, value);
  while (map.size > 20) map.delete(map.keys().next().value!);
  return value;
}

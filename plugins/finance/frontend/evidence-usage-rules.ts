import { cnyUnits, units, amount } from "./types.ts";
import type { ReviewFields } from "./evidence-usage-contract";
export function evidenceAllocationMatches(
  selectedId: string,
  filterDocumentId: string,
  allocation: { id: string; documentId: string } | null | undefined,
) {
  return (
    !!allocation &&
    allocation.id === selectedId &&
    (!filterDocumentId || allocation.documentId === filterDocumentId)
  );
}
export function evidenceReadMatches(
  path: string,
  revision: number,
  stored: { path: string; revision: number } | null,
) {
  return !!stored && stored.path === path && stored.revision === revision;
}
export function evidenceText(
  values: Record<string, unknown>,
  name: string,
  maximum: number,
) {
  const value = values[name];
  if (typeof value !== "string" || !value.trim() || value.length > maximum)
    throw new Error("请核对必填信息及长度。");
  return value;
}
export function evidenceReviewFields(
  values: Record<string, unknown>,
): ReviewFields {
  if (
    values.singleFileSingleDocumentConfirmed !== true ||
    values.allKnownDocumentIdentifiersDeclared !== true ||
    values.sameDocumentConfirmed !== true
  )
    throw new Error("请主动确认单文件单单据、全部已知标识及同一单据声明。");
  return {
    documentType: "ORDINARY_EXPENSE_INVOICE",
    issuerKey: evidenceText(values, "issuerKey", 128),
    externalIdentityKey: evidenceText(values, "externalIdentityKey", 200),
    ...evidenceBasisAmounts(values.faceGross, values.expenseCapacity),
    basisOriginalId: evidenceText(values, "basisOriginalId", 36),
    reason: evidenceText(values, "reason", 1000),
    singleFileSingleDocumentConfirmed: true,
    allKnownDocumentIdentifiersDeclared: true,
    sameDocumentConfirmed: true,
    ...evidenceSourceLocation(values.sourceType, values.originalLocation),
  };
}
export function evidenceMoney(value: string | null | undefined) {
  return value == null ? "未知" : value;
}
export function evidenceCnyEditable(value: string) {
  const match = /^([0-9]+)[.]([0-9]{2})0{6}$/.exec(value);
  return match ? `${match[1]}.${match[2]}` : value;
}
export function evidencePositive(value: unknown, label = "金额") {
  if (typeof value !== "string" || cnyUnits(value) <= 0n)
    throw new Error(`${label}须为正人民币金额，最多两位小数。`);
  return value;
}
export function evidenceBasisAmounts(face: unknown, capacity: unknown) {
  const faceGross = evidencePositive(face, "单据面额");
  if (
    typeof capacity !== "string" ||
    cnyUnits(capacity) < 0n ||
    cnyUnits(capacity) > cnyUnits(faceGross)
  )
    throw new Error("费用归属上限须为非负人民币金额且不超过单据面额。");
  return { faceGross, expenseCapacity: capacity };
}
export function evidencePreview(available: string | null, proposed: unknown) {
  if (available === null || typeof proposed !== "string")
    return "未知，不能估算";
  try {
    return amount(units(available) - cnyUnits(evidencePositive(proposed)));
  } catch {
    return "请核对正人民币两位金额";
  }
}
export function evidenceSourceLocation(
  source: unknown,
  location: unknown,
): Pick<ReviewFields, "sourceType" | "originalLocation"> {
  if (source !== "ELECTRONIC_ORIGINAL" && source !== "PAPER_SCAN")
    throw new Error("请选择实际电子原件或纸质扫描来源。");
  if (
    location != null &&
    (typeof location !== "string" || location.length > 500)
  )
    throw new Error("原件位置最多500字。");
  if (
    source === "PAPER_SCAN" &&
    (typeof location !== "string" || !location.trim())
  )
    throw new Error("纸质扫描件必须登记实际原件保管位置。");
  return {
    sourceType: source,
    originalLocation:
      typeof location === "string" && location.trim() ? location : null,
  };
}
export function evidenceIndependent(
  actor: { personId: string | null },
  prepared: { personId: string },
) {
  return !!actor.personId && actor.personId !== prepared.personId;
}
export function evidenceOwns(
  actor: { accountId: string },
  prepared: { accountId: string },
) {
  return actor.accountId === prepared.accountId;
}
export function evidenceIsNewUse(action: string) {
  return [
    "create-document",
    "create-review",
    "approve-review",
    "create-allocation",
    "edit-allocation",
    "submit",
    "confirm",
  ].includes(action);
}
export function evidenceActionAllowed(
  actions: readonly string[],
  action: string,
  newUseAllowed: boolean,
  businessPermission: boolean,
) {
  return (
    businessPermission &&
    actions.includes(action) &&
    (!evidenceIsNewUse(action) || newUseAllowed)
  );
}

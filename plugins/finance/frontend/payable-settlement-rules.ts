const CNY_TWO_DECIMAL = /^(?:0|[1-9]\d{0,17})(?:\.\d{1,2})?$/;

export type PayableSettlementOrderState =
  | "DRAFT"
  | "SUBMITTED"
  | "APPROVED"
  | "POSTED"
  | "CANCELLED";

export type PayableSettlementOrderAction =
  | "submit"
  | "approve"
  | "post"
  | "cancel"
  | "reverse";

export function isPayableSettlementJournalType(
  type: string | null | undefined,
): boolean {
  return type === "AP_PAYMENT" || type === "AP_PAYMENT_REVERSAL";
}

export const isApSettlementJournalType = isPayableSettlementJournalType;

export function parsePositiveCnyCents(raw: string): bigint | null {
  if (typeof raw !== "string" || !CNY_TWO_DECIMAL.test(raw)) return null;
  const [whole, frac = ""] = raw.split(".");
  const padded = (frac + "00").slice(0, 2);
  const cents = BigInt(whole) * 100n + BigInt(padded);
  return cents > 0n ? cents : null;
}

export function parseNonNegativeCnyCents(raw: string): bigint | null {
  if (typeof raw !== "string" || !CNY_TWO_DECIMAL.test(raw)) return null;
  const [whole, frac = ""] = raw.split(".");
  const padded = (frac + "00").slice(0, 2);
  return BigInt(whole) * 100n + BigInt(padded);
}

export function validatePayableSettlementAmount(
  amountRaw: string,
  availableOpenItemRaw: string,
  availableBasisRaw: string,
): { ok: true; cents: bigint } | { ok: false; code: string } {
  const amountCents = parsePositiveCnyCents(amountRaw);
  if (amountCents === null) {
    return { ok: false, code: "CNY_PRECISION_OR_RANGE" };
  }
  const openAvail = parseNonNegativeCnyCents(availableOpenItemRaw);
  const basisAvail = parseNonNegativeCnyCents(availableBasisRaw);
  if (openAvail === null || basisAvail === null) {
    return { ok: false, code: "INVALID_AVAILABLE_CAPACITY" };
  }
  if (amountCents > openAvail) {
    return { ok: false, code: "AP_OPEN_ITEM_EXCEEDED" };
  }
  if (amountCents > basisAvail) {
    return { ok: false, code: "AP_SETTLEMENT_BASIS_EXCEEDED" };
  }
  return { ok: true, cents: amountCents };
}

export function payableSettlementNextActions(
  kind: string,
  state: string,
  reversedById: string | null,
): readonly PayableSettlementOrderAction[] {
  switch (state) {
    case "DRAFT":
      return ["submit", "cancel"];
    case "SUBMITTED":
      return ["approve", "cancel"];
    case "APPROVED":
      return ["post", "cancel"];
    case "POSTED":
      return kind === "PAYMENT" && !reversedById ? ["reverse"] : [];
    default:
      return [];
  }
}

export function formatTaxWorksheetKindLabel(kind: string): string {
  switch (kind) {
    case "VAT_SMALL":
      return "小规模纳税人增值税（TAX02）";
    case "VAT_GENERAL_SURCHARGE":
      return "一般纳税人增值税及附加税费（TAX02 / TAX03）";
    case "CIT_ADJUSTMENT_AND_PREPAY":
      return "企业所得税纳税调整与预缴底稿（TAX04）";
    default:
      return kind;
  }
}

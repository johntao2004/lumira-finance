export type AccountingStandard = "CAS" | "SMALL";

export type PoStatus = "DRAFT" | "SUBMITTED" | "APPROVED" | "CANCELLED" | "CLOSED";
export type AcceptanceStatus = "SUBMITTED" | "ACCEPTED" | "CANCELLED";
export type ProvisionalStatus = "NONE" | "PROVISIONALLY_ACCRUED" | "REVERSED" | "SETTLED";

export type ConditionStatus = "CONDITIONAL" | "FULFILLED" | "WRITTEN_OFF";
export type ContractAssetStatus = "DRAFT" | "SUBMITTED" | "APPROVED" | "POSTED" | "RECLASSIFIED";

export type VariableKind = "BONUS" | "REBATE" | "PENALTY" | "CONCESSION";
export type VariableStatus = "PENDING_APPROVAL" | "APPROVED" | "REVISED" | "REJECTED";

export type AgingBucket = "NOT_DUE" | "DAYS_1_30" | "DAYS_31_60" | "DAYS_61_90" | "DAYS_91_180" | "DAYS_181_PLUS";
export type EclAssessmentType = "AGING_BUCKET" | "INDIVIDUAL_ASSESSMENT";
export type EclOperationKind = "PROVISION" | "REVERSAL" | "WRITE_OFF" | "WRITE_OFF_RECOVERY";
export type EclStatus = "DRAFT" | "SUBMITTED" | "APPROVED" | "POSTED" | "REJECTED";

export type AllowanceType = "COMMERCIAL_DISCOUNT" | "SALES_ALLOWANCE" | "SALES_RETURN";
export type AllowanceStatus = "DRAFT" | "SUBMITTED" | "APPROVED" | "POSTED" | "REJECTED";

export const agingBucketLossRates: Record<AgingBucket, string> = {
  NOT_DUE: "0.0050",
  DAYS_1_30: "0.0200",
  DAYS_31_60: "0.0500",
  DAYS_61_90: "0.1000",
  DAYS_91_180: "0.3000",
  DAYS_181_PLUS: "0.5000",
};

export const agingBucketLabels: Record<AgingBucket, string> = {
  NOT_DUE: "未逾期",
  DAYS_1_30: "逾期1-30天",
  DAYS_31_60: "逾期31-60天",
  DAYS_61_90: "逾期61-90天",
  DAYS_91_180: "逾期91-180天",
  DAYS_181_PLUS: "逾期181天以上",
};

export function cny(value: unknown, allowZero = false): string {
  if (typeof value !== "string" || !/^(0|[1-9]\d{0,19})(\.\d{1,2})?$/.test(value)) {
    throw new Error("人民币金额须为最多两位小数的非负数字符串");
  }
  const [whole, decimals = ""] = value.split(".");
  const padded = decimals.padEnd(2, "0");
  const units = BigInt(whole) * 100n + BigInt(padded);
  if (!allowZero && units === 0n) {
    throw new Error("金额必须大于零");
  }
  return `${whole}.${padded}`;
}

export function toBigIntUnits(value: string): bigint {
  return BigInt(cny(value, true).replace(".", ""));
}

export function fromBigIntUnits(units: bigint): string {
  const sign = units < 0n ? "-" : "";
  const abs = units < 0n ? -units : units;
  const whole = (abs / 100n).toString();
  const rem = (abs % 100n).toString().padStart(2, "0");
  return `${sign}${whole}.${rem}`;
}

export function calculatePriceVariance(estimatedPrice: string, actualPrice: string, invoicedQuantity: string): string {
  const estUnits = toBigIntUnits(estimatedPrice);
  const actUnits = toBigIntUnits(actualPrice);
  const qtyUnits = toBigIntUnits(invoicedQuantity); // quantity with 2 decimals
  // Variance = (actUnits - estUnits) * qtyUnits / 100
  const diffUnits = actUnits - estUnits;
  const varUnits = (diffUnits * qtyUnits) / 100n;
  return fromBigIntUnits(varUnits);
}

export function calculateQuantityVariance(acceptedQuantity: string, invoicedQuantity: string, estimatedPrice: string): string {
  const accUnits = toBigIntUnits(acceptedQuantity);
  const invUnits = toBigIntUnits(invoicedQuantity);
  const estPriceUnits = toBigIntUnits(estimatedPrice);
  const diffQty = invUnits - accUnits;
  const varUnits = (diffQty * estPriceUnits) / 100n;
  return fromBigIntUnits(varUnits);
}

export function calculateTotalVariance(provisionalNet: string, actualNet: string): string {
  const provUnits = toBigIntUnits(provisionalNet);
  const actUnits = toBigIntUnits(actualNet);
  return fromBigIntUnits(actUnits - provUnits);
}

export function calculateEclProvision(receivableAmount: string, lossRateStr: string): string {
  const recUnits = toBigIntUnits(receivableAmount);
  // loss rate e.g. "0.0500" -> 500 / 10000
  const rateNum = Math.round(parseFloat(lossRateStr) * 10000);
  const provUnits = (recUnits * BigInt(rateNum)) / 10000n;
  return fromBigIntUnits(provUnits);
}

export function calculateEclAdjustment(targetProvision: string, priorAllowance: string): string {
  const targetUnits = toBigIntUnits(targetProvision);
  const priorUnits = toBigIntUnits(priorAllowance);
  return fromBigIntUnits(targetUnits - priorUnits);
}

export function calculateConstrainedCap(estimatedAmount: string, confidenceScoreStr: string): string {
  const estUnits = toBigIntUnits(estimatedAmount);
  const confNum = Math.round(parseFloat(confidenceScoreStr) * 10000);
  if (confNum < 0 || confNum > 10000) {
    throw new Error("置信度须在0与1之间");
  }
  const capUnits = (estUnits * BigInt(confNum)) / 10000n;
  return fromBigIntUnits(capUnits);
}

export function validateStandardForContractAsset(standard: AccountingStandard): void {
  if (standard === "SMALL") {
    throw new Error("SMALL_DOES_NOT_SUPPORT_CONTRACT_ASSET");
  }
}

export function validateStandardForEclProvision(standard: AccountingStandard, opKind: EclOperationKind): void {
  if (standard === "SMALL" && (opKind === "PROVISION" || opKind === "REVERSAL")) {
    throw new Error("SMALL_DOES_NOT_SUPPORT_BAD_DEBT_PROVISION");
  }
}

export function validateSegregationOfDuties(preparerId: string, approverId: string): void {
  if (!preparerId || !approverId) {
    throw new Error("INVALID_INPUT");
  }
  if (preparerId.toLowerCase() === approverId.toLowerCase()) {
    throw new Error("SELF_APPROVAL_FORBIDDEN");
  }
}

export function canonicalJson(value: unknown): string {
  function sorted(v: unknown): unknown {
    if (Array.isArray(v)) return v.map(sorted);
    if (v !== null && typeof v === "object") {
      return Object.fromEntries(
        Object.entries(v)
          .sort(([a], [b]) => a.localeCompare(b))
          .map(([k, x]) => [k, sorted(x)])
      );
    }
    return v;
  }
  return JSON.stringify(sorted(value));
}

export interface StorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

export async function retryIdentity(
  scopeIdentity: string,
  route: string,
  body: unknown,
  storage: StorageLike,
  uuidGenerator: () => string = () => crypto.randomUUID()
): Promise<{ operationId: string; idempotencyKey: string }> {
  const encoded = new TextEncoder().encode(canonicalJson([scopeIdentity, route, body]));
  const digestBuffer = await crypto.subtle.digest("SHA-256", encoded);
  const digest = Array.from(new Uint8Array(digestBuffer), (b) => b.toString(16).padStart(2, "0")).join("");
  const key = `finance:subledger-ext:retry:${digest}`;
  const existing = storage.getItem(key);
  if (existing) {
    try {
      const parsed = JSON.parse(existing);
      if (parsed && typeof parsed.operationId === "string" && typeof parsed.idempotencyKey === "string") {
        return { operationId: parsed.operationId, idempotencyKey: parsed.idempotencyKey };
      }
    } catch {}
  }
  const result = { operationId: uuidGenerator(), idempotencyKey: uuidGenerator() };
  storage.setItem(key, JSON.stringify(result));
  return result;
}

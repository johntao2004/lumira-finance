export type ArKind = "CONTRACT" | "PERFORMANCE" | "RECOGNITION" | "RECEIPT" | "ALLOCATION" | "REVERSAL";
export type ArState = "DRAFT" | "SUBMITTED" | "APPROVED" | "CONFIRMED" | "POSTED" | "CANCELLED";
export type ArObject = {
  id: string; kind: ArKind; state: ArState; version: string; amount: string;
  customerId: string; businessChainId: string; journalId: string | null; reversedBy: string | null;
  balances?: { purpose: string; outstanding: string; available: string; reserved: string; aging?: string }[];
};
export const AR_PRODUCER_JOURNAL_TYPES = [
  "AR_REVENUE",
  "AR_RECEIPT",
  "AR_ALLOCATION",
  "AR_REVERSAL",
] as const;
export type ArProducerJournalType = (typeof AR_PRODUCER_JOURNAL_TYPES)[number];
export function isArProducerJournalType(
  type: string | undefined,
): type is ArProducerJournalType {
  return (
    typeof type === "string" &&
    (AR_PRODUCER_JOURNAL_TYPES as readonly string[]).includes(type)
  );
}
export const arLabels: Record<ArKind, string> = {
  CONTRACT: "合同", PERFORMANCE: "履约阶段", RECOGNITION: "收入确认",
  RECEIPT: "人工收款回执", ALLOCATION: "收款核销", REVERSAL: "完整反向更正",
};
export function arCny(value: unknown, allowZero = false): string {
  if (typeof value !== "string" || !/^(0|[1-9]\d{0,19})(\.\d{1,2})?$/.test(value))
    throw new Error("人民币金额须为最多两位小数的非负字符串");
  const [whole, decimals = ""] = value.split(".");
  const units = BigInt(whole) * 100n + BigInt(decimals.padEnd(2, "0"));
  if (!allowZero && units === 0n) throw new Error("金额必须大于零");
  return `${whole}.${decimals.padEnd(2, "0")}`;
}
export function arUnits(value: string): bigint {
  return BigInt(arCny(value, true).replace(".", ""));
}
export function arAllocation(items: { recognitionId: string; amount: string }[], total: string) {
  if (items.length < 1 || items.length > 50) throw new Error("每次核销须有1至50条应收");
  const seen = new Set<string>(); let sum = 0n;
  const rows = items.map((item) => {
    if (!/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(item.recognitionId) || seen.has(item.recognitionId.toLowerCase()))
      throw new Error("应收标识无效或重复");
    seen.add(item.recognitionId.toLowerCase()); sum += arUnits(item.amount);
    return { recognitionId: item.recognitionId.toLowerCase(), amount: arCny(item.amount) };
  });
  if (sum !== arUnits(total)) throw new Error("核销明细合计必须等于本次核销金额");
  return rows.sort((a, b) => a.recognitionId.localeCompare(b.recognitionId));
}
export function arActions(state: ArState): string[] {
  return state === "DRAFT" ? ["submit", "cancel"] : state === "SUBMITTED"
    ? ["approve", "reject", "withdraw", "cancel"] : state === "APPROVED"
      ? ["post", "reject", "withdraw", "cancel"] : [];
}
export function arCanonical(value: unknown): string {
  function sorted(v: unknown): unknown {
    if (Array.isArray(v)) return v.map(sorted);
    if (v !== null && typeof v === "object") return Object.fromEntries(Object.entries(v).sort(([a], [b]) => a.localeCompare(b)).map(([k, x]) => [k, sorted(x)]));
    return v;
  }
  return JSON.stringify(sorted(value));
}
/** Retain identifiers on ambiguous failures and refresh; persist no bank/customer/content data. */
export async function arRetryIdentity(scopeIdentity: string, route: string, body: unknown,
  storage: Pick<Storage, "getItem" | "setItem">, uuid: () => string = () => crypto.randomUUID()) {
  const encoded = new TextEncoder().encode(arCanonical([scopeIdentity, route, body]));
  const digest = Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", encoded)), (b) => b.toString(16).padStart(2, "0")).join("");
  const key = `finance:ar:v1:retry:${digest}`;
  const prior = storage.getItem(key);
  if (prior) {
    const parsed: unknown = JSON.parse(prior);
    if (parsed && typeof parsed === "object" && "operationId" in parsed && "idempotencyKey" in parsed
      && typeof parsed.operationId === "string" && typeof parsed.idempotencyKey === "string")
      return { operationId: parsed.operationId, idempotencyKey: parsed.idempotencyKey };
    throw new Error("重试身份损坏，请保留现场并查询原操作结果");
  }
  const result = { operationId: uuid(), idempotencyKey: uuid() };
  storage.setItem(key, JSON.stringify(result));
  return result;
}

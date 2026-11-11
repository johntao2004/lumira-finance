export const ASSET_PRODUCER_JOURNAL_TYPES = [
  "ASSET_ACQUIRE",
  "ASSET_DEPRECIATE",
  "ASSET_DISPOSE",
  "ASSET_REVERSE",
  "ASSET_CORRECT_DEPRECIATION",
] as const;

export type AssetProducerJournalType =
  (typeof ASSET_PRODUCER_JOURNAL_TYPES)[number];

export type AssetCommandAction =
  | "depreciate"
  | "estimate"
  | "approve"
  | "post"
  | "cancel";

export type AssetPendingCommand = {
  action: AssetCommandAction;
  body: Record<string, unknown>;
  key: string;
};

const ASSET_ACTIONS: ReadonlySet<string> = new Set([
  "depreciate",
  "estimate",
  "approve",
  "post",
  "cancel",
]);

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function isAssetProducerJournalType(
  type: string | undefined,
): type is AssetProducerJournalType {
  return (
    typeof type === "string" &&
    (ASSET_PRODUCER_JOURNAL_TYPES as readonly string[]).includes(type)
  );
}

export function assetCnyAmount(value: unknown, allowZero = false): string {
  if (
    typeof value !== "string" ||
    !/^(0|[1-9]\d{0,19})(\.\d{1,2})?$/.test(value)
  ) {
    throw new Error("人民币资产金额须为最多两位小数的非负十进制字符串");
  }
  const [whole, fraction = ""] = value.split(".");
  const cents = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, "0"));
  if (!allowZero && cents === 0n) {
    throw new Error("人民币资产金额必须大于零");
  }
  return `${whole}.${fraction.padEnd(2, "0")}`;
}

export function assetZeroDifference(
  rows: readonly { difference: string }[],
): boolean {
  return rows.every((row) => {
    if (
      typeof row.difference !== "string" ||
      !/^-?(0|[1-9]\d{0,19})(\.\d{1,8})?$/.test(row.difference)
    ) {
      return false;
    }
    const clean = row.difference.startsWith("-")
      ? row.difference.slice(1)
      : row.difference;
    const [whole, fraction = ""] = clean.split(".");
    return BigInt(whole) === 0n && BigInt(fraction.padEnd(8, "0")) === 0n;
  });
}

export function validateAssetPendingCommand(raw: string): AssetPendingCommand {
  const parsed: unknown = JSON.parse(raw);
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error("无法读取上次操作，请核查服务器结果");
  }
  const candidate = parsed as Record<string, unknown>;
  if (
    typeof candidate.action !== "string" ||
    !ASSET_ACTIONS.has(candidate.action) ||
    typeof candidate.key !== "string" ||
    !UUID_PATTERN.test(candidate.key) ||
    !candidate.body ||
    typeof candidate.body !== "object" ||
    Array.isArray(candidate.body)
  ) {
    throw new Error("无法读取上次操作，请核查服务器结果");
  }
  return {
    action: candidate.action as AssetCommandAction,
    body: candidate.body as Record<string, unknown>,
    key: candidate.key,
  };
}

export function isDefinitiveAssetRejection(error: unknown): boolean {
  if (!error || typeof error !== "object") return false;
  const status = (error as { status?: unknown }).status;
  return (
    typeof status === "number" &&
    Number.isInteger(status) &&
    status >= 400 &&
    status < 500 &&
    status !== 408
  );
}


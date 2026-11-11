export type Book = {
  id: string;
  tenantId: string;
  tenantName?: string;
  legalEntityId: string;
  legalEntityName?: string;
  name: string;
  currentRole?: string;
  standard: string;
  baseCurrency: string;
  version: number;
};
export type Year = {
  currencyMinorUnit?: number;
  precisionProfile?: string;
  precisionReviewRequired?: boolean;
  watermarks?: unknown;
  id: string;
  year: number;
  status: string;
  version: number;
};
export type Period = {
  id: string;
  month: number;
  status: string;
  version: number;
};
export type Account = {
  id: string;
  code: string;
  name: string;
  purpose?: "NONE" | "CURRENT_YEAR_PROFIT" | "RETAINED_EARNINGS";
  normalSide: string;
  category: string;
  enabled: boolean;
};
export type Line = {
  accountId: string;
  debit: string;
  credit: string;
  memo: string;
};
export type Journal = {
  type?: string;
  id: string;
  date: string;
  summary: string;
  status: string;
  version: number;
  number: string | null;
  lines: Line[];
  createdBy: string;
  approvedBy: string | null;
};
export type Plan = {
  createdBy?: string;
  approvedBy?: string;
  preview?: {
    balances: { code: string; targetAccountId: string; balance: string }[];
    blockers: string[];
    sourceVersion: number;
    targetVersion: number;
  };
  payload?: unknown;
  id: string;
  sourceYearId: string;
  targetYearId: string;
  status: string;
  version: number;
  digest: string;
  blockers: string[];
};
export const moneyPattern = /^(0|[1-9]\d{0,19})(\.\d{1,8})?$/;
export function units(value: string) {
  if (!moneyPattern.test(value))
    throw new Error("金额须为非负十进制字符串，内部金额最多20位整数及8位小数");
  const [integer, fraction = ""] = value.split(".");
  return BigInt(integer) * 100000000n + BigInt(fraction.padEnd(8, "0"));
}
export function amount(value: bigint) {
  const negative = value < 0n;
  const n = negative ? -value : value;
  return `${negative ? "-" : ""}${n / 100000000n}.${(n % 100000000n).toString().padStart(8, "0")}`;
}
export const blankLine = (): Line => ({
  accountId: "",
  debit: "0",
  credit: "0",
  memo: "",
});

export function cnyUnits(value: string) {
  if (!/^(0|[1-9]\d{0,19})(\.\d{1,2})?$/.test(value))
    throw new Error("人民币提交金额最多20位整数、2位小数，不自动舍入");
  return units(value);
}

export type AssetMoney = string;
export type AssetPolicy = {framework: "CAS" | "SMALL"; kind: "FIXED" | "INTANGIBLE_FINITE"; method: "STRAIGHT_LINE"; firstPeriod: string; months: number; residual: AssetMoney; assessmentId: string; basisAccumulated: AssetMoney; basisElapsed: number};
export type AssetCard = {id: string; name: string; classification: string; acquired: string; ready: string; location: string; custodian: string; policy: AssetPolicy; accounts: {cost: string; accumulated: string; expense: string}; cost: AssetMoney; accumulated: AssetMoney; impairment: AssetMoney; elapsed: number; lastPeriod: string | null; version: number; status: "IN_USE" | "DISPOSED" | "REVERSED"};
export type AssetTrace = {base: AssetMoney; unrounded: AssetMoney; recognized: AssetMoney; roundingAdjustment: AssetMoney; remaining: AssetMoney; remainingMonths: number};
export type AssetPlan = {plan: {id: string; kind: string; reason: string; maker: {account: string; person: string}; changes: {before: AssetCard | null; after: AssetCard; trace: AssetTrace | null}[]; lines: {account: string; debit: AssetMoney; credit: AssetMoney}[]; estimate: {oldSchedule: {period: string; amount: AssetMoney}[]; newSchedule: {period: string; amount: AssetMoney}[]} | null}; digest: string; status: "SUBMITTED" | "APPROVED" | "POSTED" | "CANCELLED"; version: number; journal: string | null};
export type AssetReceipt = {planId: string; status: string; journalId: string | null; version: number};
export type AssetDifference = {account: string; subledgerDebitNet: AssetMoney; ledgerDebitNet: AssetMoney; difference: AssetMoney; watermark: string};
// Integration supplies authenticated same-origin HTTP, CSRF, strict schemas and server scope.
export interface AssetApi {
  cards(after: string | null, signal: AbortSignal): Promise<AssetCard[]>;
  detail(id: string, signal: AbortSignal): Promise<AssetPlan>;
  reconciliation(signal: AbortSignal): Promise<AssetDifference[]>;
  command(action: "depreciate" | "estimate" | "approve" | "post" | "cancel", body: Record<string, unknown>, key: string): Promise<AssetReceipt>;
}

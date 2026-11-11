/**
 * Pure business rules, math validators, and type definitions for EXP01-EXP04 Expense Management.
 * Follows strict 2-decimal CNY string standards and zero-coercion validation.
 */

export type CityTier = "TIER_1" | "TIER_2" | "TIER_3" | "SPECIAL";
export type EmployeeRank = "STAFF" | "MANAGER" | "EXECUTIVE";
export type ExpenseCategory =
  | "HOTEL"
  | "MEAL"
  | "TRANSPORT"
  | "DAILY_ALLOWANCE"
  | "INCIDENTAL"
  | "OFFICE"
  | "TRAINING"
  | "OTHER";

export type PolicyStatus = "DRAFT" | "ACTIVE" | "RETIRED";
export type TravelExceptionStatus = "PENDING" | "APPROVED" | "REJECTED";
export type RequisitionStatus = "DRAFT" | "SUBMITTED" | "APPROVED" | "CLOSED" | "CANCELLED";
export type AdvanceStatus =
  | "DRAFT"
  | "SUBMITTED"
  | "APPROVED"
  | "PAID"
  | "PARTIALLY_OFFSET"
  | "SETTLED"
  | "CANCELLED";
export type ClaimStatus =
  | "DRAFT"
  | "SUBMITTED"
  | "REVIEWED"
  | "APPROVED"
  | "POSTED"
  | "PAID"
  | "SETTLED"
  | "REJECTED"
  | "CANCELLED"
  | "REVERSED";
export type SettlementStatus = "UNPAID" | "PARTIALLY_PAID" | "SETTLED" | "CANCELLED";

export interface TravelPolicy {
  id: string;
  tenantId: string;
  policyCode: string;
  versionInt: number;
  name: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  status: PolicyStatus;
}

export interface TravelPolicyCap {
  id: string;
  policyId: string;
  cityTier: CityTier;
  employeeRank: EmployeeRank;
  expenseCategory: ExpenseCategory;
  dailyCapAmount: string;
  currency: string;
}

export interface TravelException {
  id: string;
  tenantId: string;
  claimId: string | null;
  lineNo: number;
  policyCapId: string;
  requestedAmount: string;
  standardCapAmount: string;
  excessAmount: string;
  applicantPersonId: string;
  approverPersonId: string | null;
  status: TravelExceptionStatus;
  justification: string;
  decisionNotes: string | null;
}

export interface ExpenseRequisition {
  id: string;
  tenantId: string;
  bookId: string;
  requisitionNo: string;
  applicantId: string;
  departmentId: string;
  projectId: string | null;
  businessPurpose: string;
  currency: string;
  totalBudgetAmount: string;
  reservedAmount: string;
  utilizedAmount: string;
  availableAmount: string;
  status: RequisitionStatus;
  effectiveUntil: string;
}

export interface ExpenseAdvance {
  id: string;
  tenantId: string;
  bookId: string;
  advanceNo: string;
  employeeId: string;
  requisitionId: string | null;
  purpose: string;
  currency: string;
  principalAmount: string;
  offsetAmount: string;
  refundedAmount: string;
  remainingBalance: string;
  status: AdvanceStatus;
  applicantId: string;
  approverId: string | null;
  cashierId: string | null;
  payoutRef: string | null;
}

export interface ExpenseClaimLine {
  id?: string;
  lineNo: number;
  expenseCategory: ExpenseCategory;
  departmentId: string;
  projectId: string | null;
  invoiceId: string | null;
  invoiceCode?: string;
  invoiceNumber?: string;
  invoiceDigest?: string;
  submittedAmount: string;
  approvedReimbursableAmount: string;
  disallowedAmount: string;
  disallowanceReason?: string;
  vatRate: string;
  vatInputAmount: string;
  netExpenseAmount: string;
  travelPolicyCapId?: string | null;
  isExceptionApproved?: boolean;
}

export interface ExpenseClaim {
  id: string;
  tenantId: string;
  bookId: string;
  claimNo: string;
  employeeId: string;
  requisitionId: string | null;
  title: string;
  claimDate: string;
  currency: string;
  submittedAmount: string;
  approvedAmount: string;
  disallowedAmount: string;
  offsetAdvanceAmount: string;
  payableAmount: string;
  paidAmount: string;
  status: ClaimStatus;
  submitterId: string;
  reviewerId: string | null;
  approverId: string | null;
  lines: ExpenseClaimLine[];
}

export interface ExpensePayableLedger {
  id: string;
  tenantId: string;
  bookId: string;
  claimId: string;
  employeeId: string;
  payableAmount: string;
  paidAmount: string;
  outstandingBalance: string;
  settlementStatus: SettlementStatus;
}

// --- Strict CNY Format & Precision Helpers ---

export function expenseCny(value: unknown, allowZero = false): string {
  if (typeof value !== "string" || !/^(0|[1-9]\d{0,19})(\.\d{1,2})?$/.test(value)) {
    throw new Error("人民币金额须为最多两位小数的非负字符串");
  }
  const [whole, decimals = ""] = value.split(".");
  const units = BigInt(whole) * 100n + BigInt(decimals.padEnd(2, "0"));
  if (!allowZero && units === 0n) {
    throw new Error("金额必须大于零");
  }
  return `${whole}.${decimals.padEnd(2, "0")}`;
}

export function expenseUnits(value: string): bigint {
  return BigInt(expenseCny(value, true).replace(".", ""));
}

export function fromUnits(units: bigint): string {
  const isNegative = units < 0n;
  const abs = isNegative ? -units : units;
  const whole = abs / 100n;
  const frac = abs % 100n;
  const s = `${whole}.${frac.toString().padStart(2, "0")}`;
  return isNegative ? `-${s}` : s;
}

// --- EXP04 Travel Standard Evaluation ---

export function evaluateTravelCap(
  cap: TravelPolicyCap,
  amountStr: string
): { status: "COMPLIANT" | "OVER_CAP"; excessAmount: string } {
  const submittedUnits = expenseUnits(amountStr);
  const capUnits = expenseUnits(cap.dailyCapAmount);
  if (submittedUnits <= capUnits) {
    return { status: "COMPLIANT", excessAmount: "0.00" };
  }
  return { status: "OVER_CAP", excessAmount: fromUnits(submittedUnits - capUnits) };
}

// --- EXP03 Partial Recognition & VAT Input Split ---

export function calculateLineSplit(
  submittedStr: string,
  disallowedStr: string = "0.00",
  vatRateStr: string = "0"
): { reimbursable: string; disallowed: string; vatInput: string; netExpense: string } {
  const subUnits = expenseUnits(submittedStr);
  const disUnits = expenseUnits(disallowedStr);
  if (disUnits > subUnits) {
    throw new Error("核减个人金额不能大于提交金额");
  }

  const reimbursableUnits = subUnits - disUnits;
  const reimbursable = fromUnits(reimbursableUnits);
  const disallowed = fromUnits(disUnits);

  const rate = parseFloat(vatRateStr);
  if (isNaN(rate) || rate < 0) {
    throw new Error("增值税税率必须为非负数值");
  }

  if (rate === 0 || reimbursableUnits === 0n) {
    return {
      reimbursable,
      disallowed,
      vatInput: "0.00",
      netExpense: reimbursable,
    };
  }

  // Exact division: net = reimbursable / (1 + rate)
  const reimbursableNum = Number(reimbursableUnits) / 100;
  const netNum = reimbursableNum / (1 + rate);
  const netUnits = BigInt(Math.round(netNum * 100));
  const vatUnits = reimbursableUnits - netUnits;

  return {
    reimbursable,
    disallowed,
    vatInput: fromUnits(vatUnits),
    netExpense: fromUnits(netUnits),
  };
}

export function calculateClaimTotals(
  lines: Array<{ submitted: string; disallowed?: string; vatRate?: string }>,
  advanceOffsets: Array<{ advanceId: string; amount: string }> = []
): {
  totalSubmitted: string;
  totalApproved: string;
  totalDisallowed: string;
  totalOffset: string;
  netPayable: string;
} {
  let subTotal = 0n;
  let disTotal = 0n;
  let appTotal = 0n;

  for (const line of lines) {
    const s = expenseUnits(line.submitted);
    const d = expenseUnits(line.disallowed || "0.00");
    if (d > s) throw new Error("核减金额不能大于行提交金额");
    subTotal += s;
    disTotal += d;
    appTotal += s - d;
  }

  let offsetTotal = 0n;
  for (const off of advanceOffsets) {
    offsetTotal += expenseUnits(off.amount);
  }

  if (offsetTotal > appTotal) {
    throw new Error("冲借款总额不能大于经认可报销金额");
  }

  const netPayableUnits = appTotal - offsetTotal;

  return {
    totalSubmitted: fromUnits(subTotal),
    totalApproved: fromUnits(appTotal),
    totalDisallowed: fromUnits(disTotal),
    totalOffset: fromUnits(offsetTotal),
    netPayable: fromUnits(netPayableUnits),
  };
}

// --- EXP02 Cash Advance Math ---

export function calculateAdvanceBalances(
  principalStr: string,
  offsetStr: string = "0.00",
  refundStr: string = "0.00"
): { remainingBalance: string; isSettled: boolean } {
  const p = expenseUnits(principalStr);
  const o = expenseUnits(offsetStr);
  const r = expenseUnits(refundStr);
  if (o + r > p) {
    throw new Error("借款冲销及退回金额不能大于借款本金");
  }
  const rem = p - o - r;
  return {
    remainingBalance: fromUnits(rem),
    isSettled: rem === 0n,
  };
}

// --- Segregation of Duties (SoD) ---

export function validateSod(
  submitterId: string,
  reviewerId?: string | null,
  approverId?: string | null,
  employeeId?: string | null
): { valid: boolean; violationReason?: string } {
  if (reviewerId && submitterId.toLowerCase() === reviewerId.toLowerCase()) {
    return { valid: false, violationReason: "申请人不能作为审核人" };
  }
  if (approverId && submitterId.toLowerCase() === approverId.toLowerCase()) {
    return { valid: false, violationReason: "申请人不能作为审批人" };
  }
  if (reviewerId && approverId && reviewerId.toLowerCase() === approverId.toLowerCase()) {
    return { valid: false, violationReason: "审核人与审批人必须为不同自然人" };
  }
  if (employeeId && reviewerId && employeeId.toLowerCase() === reviewerId.toLowerCase()) {
    return { valid: false, violationReason: "报销/借款员工本人不能作为审核人" };
  }
  if (employeeId && approverId && employeeId.toLowerCase() === approverId.toLowerCase()) {
    return { valid: false, violationReason: "报销/借款员工本人不能作为审批人" };
  }
  return { valid: true };
}

// --- Allowed State Actions ---

export function requisitionActions(status: RequisitionStatus): string[] {
  return status === "DRAFT"
    ? ["submit", "cancel"]
    : status === "SUBMITTED"
    ? ["approve", "cancel"]
    : status === "APPROVED"
    ? ["cancel"]
    : [];
}

export function advanceActions(status: AdvanceStatus): string[] {
  return status === "DRAFT"
    ? ["submit", "cancel"]
    : status === "SUBMITTED"
    ? ["approve", "cancel"]
    : status === "APPROVED"
    ? ["payout", "cancel"]
    : status === "PAID" || status === "PARTIALLY_OFFSET"
    ? ["offset", "refund"]
    : [];
}

export function claimActions(status: ClaimStatus): string[] {
  return status === "DRAFT"
    ? ["submit", "cancel"]
    : status === "SUBMITTED"
    ? ["review", "reject", "cancel"]
    : status === "REVIEWED"
    ? ["approve", "reject", "cancel"]
    : status === "APPROVED"
    ? ["post", "reverse", "cancel"]
    : status === "POSTED"
    ? ["disburse", "reverse"]
    : [];
}

// --- Canonical JSON & Retriable Idempotency Identity ---

export function expenseCanonical(value: unknown): string {
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

export async function expenseRetryIdentity(
  scopeIdentity: string,
  route: string,
  body: unknown,
  storage: Pick<Storage, "getItem" | "setItem">,
  uuid: () => string = () => crypto.randomUUID()
): Promise<{ operationId: string; idempotencyKey: string }> {
  const encoded = new TextEncoder().encode(expenseCanonical([scopeIdentity, route, body]));
  const digestBuffer = await crypto.subtle.digest("SHA-256", encoded);
  const digest = Array.from(new Uint8Array(digestBuffer), (b) =>
    b.toString(16).padStart(2, "0")
  ).join("");
  const key = `finance:expense:v1:retry:${digest}`;
  const prior = storage.getItem(key);
  if (prior) {
    const parsed: unknown = JSON.parse(prior);
    if (
      parsed &&
      typeof parsed === "object" &&
      "operationId" in parsed &&
      "idempotencyKey" in parsed &&
      typeof (parsed as { operationId: string }).operationId === "string" &&
      typeof (parsed as { idempotencyKey: string }).idempotencyKey === "string"
    ) {
      return {
        operationId: (parsed as { operationId: string }).operationId,
        idempotencyKey: (parsed as { idempotencyKey: string }).idempotencyKey,
      };
    }
  }
  const created = { operationId: uuid(), idempotencyKey: uuid() };
  storage.setItem(key, JSON.stringify(created));
  return created;
}

/**
 * Resolves the canonical expense endpoint base path (/finance/v1/books/{bookId}/expense).
 * Normalizes scope if passed as /books/{bookId}/years/{yearId} or /finance/v1/books/{bookId}/years/{yearId}.
 */
export function expenseScope(scope: string): string {
  if (!scope) return "";
  let clean = scope.trim();
  const queryIndex = clean.indexOf("?");
  if (queryIndex !== -1) {
    clean = clean.slice(0, queryIndex);
  }
  clean = clean.replace(/\/+$/, "");
  if (clean.startsWith("/api/")) {
    clean = clean.slice(4);
  }
  if (clean.endsWith("/expense")) {
    return clean;
  }
  if (clean.includes("/years/")) {
    return clean.replace(/\/years\/[^/]+$/, "/expense");
  }
  return `${clean}/expense`;
}


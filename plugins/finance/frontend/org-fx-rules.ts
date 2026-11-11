/**
 * Domain rules and BigInt fixed-point calculations for Lane W01 (org-fx).
 * Money precision: 8 decimal places (10^8).
 * FX rate precision: 12 decimal places (10^12).
 */

const SCALE_8 = 100000000n; // 10^8
const SCALE_12 = 1000000000000n; // 10^12

/**
 * Divides num by den with RoundingMode.HALF_UP (half rounded away from zero).
 */
export function divideRoundHalfUp(num: bigint, den: bigint): bigint {
  if (den === 0n) throw new Error("Division by zero");
  if (num === 0n) return 0n;
  const isNeg = (num < 0n) !== (den < 0n);
  const absNum = num < 0n ? -num : num;
  const absDen = den < 0n ? -den : den;
  const quotient = (absNum + (absDen / 2n)) / absDen;
  return isNeg ? -quotient : quotient;
}

export function parseFixedPoint(val: string, decimals: number): bigint {
  const trimmed = val.trim();
  const isNeg = trimmed.startsWith('-');
  const clean = trimmed.replace(/^[-+]/, '');
  const parts = clean.split('.');
  const intPart = BigInt(parts[0] || '0');
  const fracStr = (parts[1] || '').padEnd(decimals, '0').slice(0, decimals);
  const fracPart = BigInt(fracStr || '0');
  const div = 10n ** BigInt(decimals);
  const total = intPart * div + fracPart;
  return isNeg ? -total : total;
}

export function formatFixedPoint(val: bigint, decimals: number): string {
  const isNeg = val < 0n;
  const abs = isNeg ? -val : val;
  const div = 10n ** BigInt(decimals);
  const intPart = abs / div;
  const fracPart = (abs % div).toString().padStart(decimals, '0');
  return (isNeg ? '-' : '') + intPart.toString() + '.' + fracPart;
}

export function parseMoney(val: string): bigint {
  return parseFixedPoint(val, 8);
}

export function formatMoney(val: bigint): string {
  return formatFixedPoint(val, 8);
}

export function parseRate(val: string): bigint {
  return parseFixedPoint(val, 12);
}

export function formatRate(val: bigint): string {
  return formatFixedPoint(val, 12);
}

/**
 * Translates original-currency amount to functional CNY using BigInt fixed-point arithmetic.
 * Output: exact (scale 8), quantized (scale 2, RoundingMode.HALF_UP), and explicit rounding difference.
 */
export function translateFx(originalAmountStr: string, rateStr: string) {
  const orig = parseMoney(originalAmountStr);
  const rate = parseRate(rateStr);

  // Exact functional CNY in 8 decimals: (orig * rate) / 10^12 rounded HALF_UP
  const exact = divideRoundHalfUp(orig * rate, SCALE_12);

  // Quantized to 2 decimals: divide by 1,000,000 with HALF_UP, then scale back to 10^8
  const q2 = divideRoundHalfUp(exact, 1000000n);
  const quantized = q2 * 1000000n;
  const diff = exact - quantized;

  return {
    exact: formatMoney(exact),
    quantized: formatFixedPoint(q2, 2),
    difference: formatMoney(diff),
  };
}

/**
 * Validates legal entity lifecycle transitions per state machine rules:
 * PREPARING -> PENDING_CONFIRMATION
 * PENDING_CONFIRMATION -> ACTIVE, PREPARING
 * ACTIVE -> SUSPENDED, TERMINATED
 * SUSPENDED -> ACTIVE, TERMINATED
 * TERMINATED is terminal.
 */
export function validateLifecycleTransition(
  fromStatus: string,
  toStatus: string,
  makerPersonId: string,
  reviewerPersonId: string
) {
  if (!makerPersonId || !reviewerPersonId) {
    return { isValid: false, error: "SOD_INCOMPLETE: Maker and reviewer are required." };
  }
  if (makerPersonId === reviewerPersonId) {
    return { isValid: false, error: "SOD_VIOLATION: Maker and reviewer natural persons must differ." };
  }
  if (fromStatus === toStatus) {
    return { isValid: false, error: "SAME_STATUS: Entity is already in status " + toStatus + "." };
  }
  if (fromStatus === "TERMINATED") {
    return { isValid: false, error: "TERMINATED_IS_TERMINAL: Terminated entities cannot transition." };
  }

  const allowedTransitions: Record<string, string[]> = {
    PREPARING: ["PENDING_CONFIRMATION"],
    PENDING_CONFIRMATION: ["ACTIVE", "PREPARING"],
    ACTIVE: ["SUSPENDED", "TERMINATED"],
    SUSPENDED: ["ACTIVE", "TERMINATED"],
  };

  const targets = allowedTransitions[fromStatus] || [];
  if (!targets.includes(toStatus)) {
    return { isValid: false, error: "ILLEGAL_LIFECYCLE_TRANSITION: Cannot transition from " + fromStatus + " to " + toStatus + "." };
  }

  return { isValid: true, error: null };
}

/**
 * Evaluates CAS vs SMALL standard eligibility based on enterprise criteria.
 */
export function assessStandard(params: {
  isPubliclyTraded: boolean;
  isFinancialInstitution: boolean;
  isGroupSubsidiaryOfPublic: boolean;
  employeeCount: number;
  annualRevenueStr: string;
  totalAssetsStr: string;
}) {
  const disqualifications: string[] = [];
  if (params.isPubliclyTraded) disqualifications.push('PUBLICLY_TRADED');
  if (params.isFinancialInstitution) disqualifications.push('FINANCIAL_INSTITUTION');
  if (params.isGroupSubsidiaryOfPublic) disqualifications.push('PUBLIC_GROUP_SUBSIDIARY');
  if (params.employeeCount > 300) disqualifications.push('EMPLOYEE_COUNT_EXCEEDED');

  const rev = parseMoney(params.annualRevenueStr);
  const assets = parseMoney(params.totalAssetsStr);
  const maxSme = parseMoney('100000000.00000000');

  if (rev > maxSme) disqualifications.push('ANNUAL_REVENUE_EXCEEDED');
  if (assets > maxSme) disqualifications.push('TOTAL_ASSETS_EXCEEDED');

  const eligibleForSmall = disqualifications.length === 0;
  return {
    eligibleForSmall,
    standard: eligibleForSmall ? 'SMALL' : 'CAS',
    disqualifications,
  };
}

/**
 * Validates quad-date semantics and period status.
 */
export function validateMultiDate(
  businessDateStr: string,
  invoiceDateStr: string,
  accountingDateStr: string,
  taxObligationDateStr: string,
  periodStatus: string,
  justification?: string
) {
  const errors: string[] = [];
  if (!businessDateStr) errors.push('MISSING_BUSINESS_DATE');
  if (!invoiceDateStr) errors.push('MISSING_INVOICE_DATE');
  if (!accountingDateStr) errors.push('MISSING_ACCOUNTING_DATE');
  if (!taxObligationDateStr) errors.push('MISSING_TAX_OBLIGATION_DATE');

  if (errors.length > 0) {
    return { isValid: false, errors, dateSpanDays: 0, requiresExplanation: false };
  }

  const now = new Date();
  const maxFuture = new Date(now.getTime() + 366 * 24 * 60 * 60 * 1000);
  const minBoundary = new Date("1900-01-01T00:00:00Z");

  const bDate = new Date(businessDateStr);
  const iDate = new Date(invoiceDateStr);
  const aDate = new Date(accountingDateStr);
  const tDate = new Date(taxObligationDateStr);

  if (bDate > maxFuture) errors.push('BUSINESS_DATE_HORIZON_EXCEEDED');
  if (iDate > maxFuture) errors.push('INVOICE_DATE_HORIZON_EXCEEDED');
  if (aDate > maxFuture) errors.push('ACCOUNTING_DATE_HORIZON_EXCEEDED');
  if (tDate > maxFuture) errors.push('TAX_OBLIGATION_DATE_HORIZON_EXCEEDED');

  if (bDate < minBoundary || iDate < minBoundary || aDate < minBoundary || tDate < minBoundary) {
    errors.push('DATE_HISTORICAL_BOUNDARY_EXCEEDED');
  }

  if (periodStatus !== 'OPEN') {
    errors.push('ACCOUNTING_PERIOD_CLOSED');
  }

  const diffTime = Math.abs(aDate.getTime() - bDate.getTime());
  const dateSpanDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24));

  const requiresExplanation = dateSpanDays > 90;
  if (requiresExplanation && (!justification || justification.trim().length === 0)) {
    errors.push('EXPLANATION_REQUIRED');
  }

  return {
    isValid: errors.length === 0,
    errors,
    dateSpanDays,
    requiresExplanation,
  };
}

/**
 * Calculates month-end foreign exchange revaluation.
 */
export function calculateRevaluation(
  foreignBalanceStr: string,
  carryingCnyStr: string,
  closingRateStr: string
) {
  const fb = parseMoney(foreignBalanceStr);
  const cny = parseMoney(carryingCnyStr);
  const rate = parseRate(closingRateStr);

  const restated = divideRoundHalfUp(fb * rate, SCALE_12);
  const gainLoss = restated - cny;

  return {
    restatedCny: formatMoney(restated),
    gainLoss: formatMoney(gainLoss),
  };
}

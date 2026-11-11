/**
 * Frontend business rules and precision math for Dimensions and Budget Control (Lane W03).
 * Zero-leakage penny and 8-decimal remainder allocation, SoD enforcement, and budget control.
 */

export interface TargetRatio {
  targetId: string;
  ratio: string; // numeric(18,12)
  priority: number;
}

export interface AllocatedTargetResult {
  targetId: string;
  ratio: string;
  allocatedAmount: string;
}

export interface AccountRule {
  tenantId: string;
  bookId: string;
  yearId: string;
  accountId: string;
  accountCode: string;
  standardVersion: 'CAS_2006' | 'SMALL_2013';
  requireDepartment: boolean;
  requireProject: boolean;
  requireCounterparty: boolean;
  requireEmployee: boolean;
  requireContract: boolean;
  requireCostCenter: boolean;
  currencyPolicy: 'CNY_ONLY' | 'MULTI_CURRENCY';
  status: 'ACTIVE' | 'DEACTIVATION_PENDING' | 'INACTIVE';
  version: number;
}

export interface CounterpartyBankAccount {
  id: string;
  tenantId: string;
  legalEntityId: string;
  counterpartyId: string;
  bankName: string;
  branchName?: string;
  bankAccountNo: string;
  currency: 'CNY';
  purpose: 'SETTLEMENT' | 'TAX' | 'PAYROLL' | 'OTHER';
  isDefault: boolean;
  status: 'PENDING_REVIEW' | 'ACTIVE' | 'INACTIVE';
  version: number;
}

export interface BudgetControlStatus {
  budgetAmount: string;
  reservedAmount: string;
  consumedAmount: string;
  availableAmount: string;
}

export interface ForecastProjection {
  ytdConsumed: string;
  forecastedRemaining: string;
  forecastTotal: string;
  projectedVariance: string;
}

const NIL_UUID = '00000000-0000-0000-0000-000000000000';

/**
 * Enforces Segregation of Duties (SoD): maker natural person != reviewer natural person.
 */
export function validateSoD(makerPerson: string, reviewerPerson: string): void {
  if (!makerPerson || !reviewerPerson) {
    throw new Error('Maker and reviewer person IDs are required');
  }
  if (makerPerson.trim() === NIL_UUID || reviewerPerson.trim() === NIL_UUID) {
    throw new Error('Nil UUID is not a valid natural person');
  }
  if (makerPerson.trim() === reviewerPerson.trim()) {
    throw new Error('SELF_APPROVAL_FORBIDDEN: Maker and reviewer natural persons must be distinct');
  }
}

/**
 * Parses a decimal string into BigInt units scaled by 10^scale.
 */
function parseToScaledBigInt(valStr: string, scale: number): bigint {
  const trimmed = valStr.trim();
  const parts = trimmed.split('.');
  const intPart = parts[0] || '0';
  let fracPart = parts[1] || '';
  if (fracPart.length > scale) {
    throw new Error(`Scale exceeds ${scale}: ${valStr}`);
  }
  while (fracPart.length < scale) {
    fracPart += '0';
  }
  return BigInt(intPart) * (10n ** BigInt(scale)) + BigInt(fracPart);
}

/**
 * Formats a scaled BigInt back to a fixed-scale decimal string.
 */
function formatScaledBigInt(val: bigint, scale: number): string {
  if (val < 0n) {
    throw new Error(`Negative value cannot be formatted: ${val}`);
  }
  const divisor = 10n ** BigInt(scale);
  const intPart = val / divisor;
  let fracPart = (val % divisor).toString();
  while (fracPart.length < scale) {
    fracPart = '0' + fracPart;
  }
  return scale === 0 ? intPart.toString() : `${intPart}.${fracPart}`;
}

/**
 * Multi-target allocation with zero-leakage remainder assignment.
 * Guaranteed invariant: sum(allocated targets) == totalAmount exactly.
 */
export function allocateCost(
  totalAmountStr: string,
  targets: TargetRatio[],
  scale: number
): AllocatedTargetResult[] {
  if (!targets || targets.length === 0) {
    throw new Error('Allocation targets list cannot be empty');
  }

  // Parse total amount at given scale
  const totalScaled = parseToScaledBigInt(totalAmountStr, scale);

  // Validate sum of ratios = 1.000000000000
  let sumRatio = 0n;
  const targetRatiosScaled = targets.map((t) => {
    const rScaled = parseToScaledBigInt(t.ratio, 12);
    sumRatio += rScaled;
    return { target: t, ratioScaled: rScaled };
  });

  const oneScaled = 1_000_000_000_000n;
  if (sumRatio !== oneScaled) {
    throw new Error(`Sum of ratios must equal 1.000000000000, got: ${sumRatio}`);
  }

  if (totalScaled === 0n) {
    return targets.map((t) => ({
      targetId: t.targetId,
      ratio: t.ratio,
      allocatedAmount: formatScaledBigInt(0n, scale),
    }));
  }

  // Compute exact product: totalScaled * ratioScaled (scale is scale + 12)
  // Base allocation: (totalScaled * ratioScaled) / 10^12
  // Remainder fraction: (totalScaled * ratioScaled) % 10^12
  let sumBase = 0n;
  const interims = targetRatiosScaled.map((item, index) => {
    const product = totalScaled * item.ratioScaled;
    const base = product / oneScaled;
    const frac = product % oneScaled;
    sumBase += base;
    return {
      index,
      targetId: item.target.targetId,
      ratio: item.target.ratio,
      priority: item.target.priority,
      base,
      frac,
    };
  });

  const remainderUnits = Number(totalScaled - sumBase);
  if (remainderUnits < 0) {
    throw new Error(`Negative remainder in allocation: ${remainderUnits}`);
  }

  // Sort by fraction descending, then priority ascending, then index ascending
  const sorted = [...interims].sort((a, b) => {
    if (b.frac > a.frac) return 1;
    if (b.frac < a.frac) return -1;
    if (a.priority !== b.priority) return a.priority - b.priority;
    return a.index - b.index;
  });

  const awardedIndices = new Set<number>();
  for (let k = 0; k < remainderUnits && k < sorted.length; k++) {
    awardedIndices.add(sorted[k].index);
  }

  let runningSum = 0n;
  const results: AllocatedTargetResult[] = interims.map((item, idx) => {
    let alloc = item.base;
    if (awardedIndices.has(idx)) {
      alloc += 1n;
    }
    runningSum += alloc;
    return {
      targetId: item.targetId,
      ratio: item.ratio,
      allocatedAmount: formatScaledBigInt(alloc, scale),
    };
  });

  if (runningSum !== totalScaled) {
    throw new Error(
      `Allocation integrity failure: sum ${runningSum} != expected ${totalScaled}`
    );
  }

  return results;
}

/**
 * Compute budget available = budget - reserved - consumed.
 * Throws if reserved + consumed exceeds budget.
 */
export function computeBudgetAvailable(
  budgetStr: string,
  reservedStr: string,
  consumedStr: string
): BudgetControlStatus {
  const scale = 8;
  const budget = parseToScaledBigInt(budgetStr, scale);
  const reserved = parseToScaledBigInt(reservedStr, scale);
  const consumed = parseToScaledBigInt(consumedStr, scale);

  const committed = reserved + consumed;
  if (budget < committed) {
    throw new Error(`BUDGET_EXCEEDED: Committed ${formatScaledBigInt(committed, scale)} exceeds budget ${formatScaledBigInt(budget, scale)}`);
  }

  const available = budget - committed;
  return {
    budgetAmount: formatScaledBigInt(budget, scale),
    reservedAmount: formatScaledBigInt(reserved, scale),
    consumedAmount: formatScaledBigInt(consumed, scale),
    availableAmount: formatScaledBigInt(available, scale),
  };
}

/**
 * Rollback previously consumed budget upon accounting reversal.
 * Returns new consumed amount.
 */
export function rollbackBudgetConsumption(
  consumedStr: string,
  rollbackAmountStr: string
): string {
  const scale = 8;
  const consumed = parseToScaledBigInt(consumedStr, scale);
  const rollback = parseToScaledBigInt(rollbackAmountStr, scale);
  if (rollback > consumed) {
    throw new Error(
      `BUDGET_ROLLBACK_EXCEEDS_CONSUMED: Rollback ${formatScaledBigInt(rollback, scale)} exceeds consumed ${formatScaledBigInt(consumed, scale)}`
    );
  }
  return formatScaledBigInt(consumed - rollback, scale);
}

/**
 * Forecast run-rate total and projected variance.
 */
export function computeForecastProjection(
  ytdConsumedStr: string,
  forecastedRemainingStr: string,
  baselineBudgetStr: string
): ForecastProjection {
  const scale = 8;
  const ytd = parseToScaledBigInt(ytdConsumedStr, scale);
  const rem = parseToScaledBigInt(forecastedRemainingStr, scale);
  const baseline = parseToScaledBigInt(baselineBudgetStr, scale);

  const forecastTotal = ytd + rem;
  const variance = forecastTotal - baseline;

  const divisor = 10n ** BigInt(scale);
  const absVariance = variance < 0n ? -variance : variance;
  const intPart = absVariance / divisor;
  let fracPart = (absVariance % divisor).toString();
  while (fracPart.length < scale) {
    fracPart = '0' + fracPart;
  }
  const varianceStr = (variance < 0n ? '-' : '') + `${intPart}.${fracPart}`;

  return {
    ytdConsumed: formatScaledBigInt(ytd, scale),
    forecastedRemaining: formatScaledBigInt(rem, scale),
    forecastTotal: formatScaledBigInt(forecastTotal, scale),
    projectedVariance: varianceStr,
  };
}

/**
 * Pure TypeScript domain and validation rules for Inventory & Asset Extensions (W08: INV01-03, AS01-03).
 * Decimal arithmetic avoids IEEE-754 binary float errors for exact financial reporting.
 */

export type InventoryValuationMethod =
  | "FIFO"
  | "MOVING_WEIGHTED_AVERAGE"
  | "MONTH_END_WEIGHTED_AVERAGE"
  | "SPECIFIC_BATCH";

export type StockMovementType =
  | "PURCHASE_IN"
  | "PRODUCTION_ISSUE"
  | "PRODUCTION_IN"
  | "SALES_OUT"
  | "TRANSFER";

export type AccountingRegime = "CAS" | "SMALL";

export type DepreciationMethod =
  | "STRAIGHT_LINE"
  | "DOUBLE_DECLINING_BALANCE"
  | "SUM_OF_THE_YEARS_DIGITS";

export interface FifoLayer {
  batchId: string;
  inboundDate: string;
  remainingQuantity: string;
  unitCost: string;
}

export interface FifoAllocation {
  batchId: string;
  quantityConsumed: string;
  unitCost: string;
  costConsumed: string;
}

export interface FifoResult {
  totalConsumedQuantity: string;
  totalConsumedCost: string;
  allocations: FifoAllocation[];
  updatedLayers: FifoLayer[];
}

export interface LandedCostItem {
  movementId: string;
  purchaseValue: string;
  allocatedAmount: string;
  finalTotalCost: string;
  finalUnitCost: string;
}

export interface DepreciationScheduleRow {
  period: string;
  monthIndex: number;
  monthlyDepreciation: string;
  remainingCarryingAmount: string;
}

export function validateMoneyCny(raw: string): boolean {
  if (typeof raw !== "string") return false;
  return /^(?:0|[1-9][0-9]{0,19})(?:\.[0-9]{1,2})?$/.test(raw.trim());
}

export function parseFixed8(val: string): bigint {
  const trimmed = val.trim();
  const isNeg = trimmed.startsWith("-");
  const clean = isNeg ? trimmed.slice(1) : trimmed;
  const parts = clean.split(".");
  const intPart = BigInt(parts[0] || "0");
  const fracStr = (parts[1] || "").padEnd(8, "0").slice(0, 8);
  const fracPart = BigInt(fracStr);
  const absVal = intPart * 100000000n + fracPart;
  return isNeg ? -absVal : absVal;
}

export function formatFixed8(val: bigint): string {
  if (val === 0n) return "0.00000000";
  const sign = val < 0n ? "-" : "";
  const abs = val < 0n ? -val : val;
  const intPart = abs / 100000000n;
  const fracPart = abs % 100000000n;
  return `${sign}${intPart}.${fracPart.toString().padStart(8, "0")}`;
}

export function roundCents(val8: bigint): bigint {
  // Round from 8 decimals to 2 decimals (1,000,000 units is 0.01) with half-away-from-zero
  const isNeg = val8 < 0n;
  const abs = isNeg ? -val8 : val8;
  const remainder = abs % 1000000n;
  let rounded = abs - remainder;
  if (remainder >= 500000n) {
    rounded += 1000000n;
  }
  return isNeg ? -rounded : rounded;
}

export function calculateFifo(layers: FifoLayer[], neededQtyStr: string): FifoResult {
  let needed = parseFixed8(neededQtyStr);
  if (needed <= 0n) {
    throw new Error("POSITIVE_QUANTITY_REQUIRED");
  }

  let totalCost = 0n;
  const allocations: FifoAllocation[] = [];
  const updatedLayers: FifoLayer[] = [];

  for (const layer of layers) {
    const layerRem = parseFixed8(layer.remainingQuantity);
    if (layerRem <= 0n) continue;

    if (needed === 0n) {
      updatedLayers.push(layer);
      continue;
    }

    const take = layerRem < needed ? layerRem : needed;
    const unitCost = parseFixed8(layer.unitCost);
    // take * unitCost / 10^8
    const cost = (take * unitCost + 50000000n) / 100000000n;

    allocations.push({
      batchId: layer.batchId,
      quantityConsumed: formatFixed8(take),
      unitCost: formatFixed8(unitCost),
      costConsumed: formatFixed8(cost),
    });

    totalCost += cost;
    needed -= take;

    const remaining = layerRem - take;
    if (remaining > 0n) {
      updatedLayers.push({
        ...layer,
        remainingQuantity: formatFixed8(remaining),
      });
    }
  }

  if (needed > 0n) {
    throw new Error("INSUFFICIENT_STOCK");
  }

  return {
    totalConsumedQuantity: neededQtyStr,
    totalConsumedCost: formatFixed8(totalCost),
    allocations,
    updatedLayers,
  };
}

export function calculateMovingWeightedAverage(
  currentQtyStr: string,
  currentCostStr: string,
  inQtyStr: string,
  inCostStr: string
): string {
  const inQty = parseFixed8(inQtyStr);
  if (inQty <= 0n) {
    throw new Error("POSITIVE_INBOUND_QTY_REQUIRED");
  }
  const curQty = parseFixed8(currentQtyStr);
  const curCost = parseFixed8(currentCostStr);
  const inCost = parseFixed8(inCostStr);

  const totalQty = curQty + inQty;
  const totalCost = curCost + inCost;

  if (totalQty <= 0n) return "0.00000000";
  // (totalCost * 10^8) / totalQty
  const unitCost = (totalCost * 100000000n + totalQty / 2n) / totalQty;
  return formatFixed8(unitCost);
}

export function allocateLandedCosts(
  inbounds: { id: string; totalCost: string; quantity: string }[],
  landedCostStr: string
): LandedCostItem[] {
  const landed = parseFixed8(landedCostStr);
  if (landed <= 0n) throw new Error("POSITIVE_LANDED_COST_REQUIRED");
  if (inbounds.length === 0) throw new Error("INBOUNDS_EMPTY");

  let totalPurchase = 0n;
  for (const m of inbounds) {
    const q = parseFixed8(m.quantity);
    if (q <= 0n) throw new Error("POSITIVE_QUANTITY_REQUIRED");
    totalPurchase += parseFixed8(m.totalCost);
  }
  if (totalPurchase === 0n) throw new Error("ZERO_PURCHASE_VALUE_CANNOT_ALLOCATE");

  const results: LandedCostItem[] = [];
  let allocatedSum = 0n;

  for (let i = 0; i < inbounds.length; i++) {
    const m = inbounds[i];
    const mCost = parseFixed8(m.totalCost);
    let share: bigint;
    if (i === inbounds.length - 1) {
      share = landed - allocatedSum;
    } else {
      share = (landed * mCost + totalPurchase / 2n) / totalPurchase;
      allocatedSum += share;
    }

    const finalTotal = mCost + share;
    const qty = parseFixed8(m.quantity);
    const finalUnit = (finalTotal * 100000000n + qty / 2n) / qty;

    results.push({
      movementId: m.id,
      purchaseValue: m.totalCost,
      allocatedAmount: formatFixed8(share),
      finalTotalCost: formatFixed8(finalTotal),
      finalUnitCost: formatFixed8(finalUnit),
    });
  }

  return results;
}

export function evaluateInventoryImpairment(
  regime: AccountingRegime,
  bookCostStr: string,
  nrvStr: string,
  priorStr: string
): { status: string; adjustment: string; newImpairment: string } {
  if (regime === "SMALL") {
    return {
      status: "BLOCKED",
      adjustment: "0.00000000",
      newImpairment: "0.00000000",
    };
  }

  const bookCost = parseFixed8(bookCostStr);
  const nrv = parseFixed8(nrvStr);
  const prior = parseFixed8(priorStr || "0.00000000");

  let target = 0n;
  if (nrv < bookCost) {
    target = bookCost - nrv;
  }

  const adj = target - prior;
  let status = "NONE";
  if (adj > 0n) status = "PROVISIONED";
  else if (adj < 0n) status = "REVERSED";

  return {
    status,
    adjustment: formatFixed8(adj),
    newImpairment: formatFixed8(target),
  };
}

export function evaluateAssetImpairment(
  regime: AccountingRegime,
  carryingStr: string,
  recoverableStr: string
): { status: string; impairment: string; isReversible: boolean } {
  if (regime === "SMALL") {
    throw new Error("SMALL_IMPAIRMENT_BLOCKED");
  }

  const carrying = parseFixed8(carryingStr);
  const recoverable = parseFixed8(recoverableStr);

  let impairment = 0n;
  if (recoverable < carrying) {
    impairment = roundCents(carrying - recoverable);
  }

  // CAS 8 mandates: asset impairment is strictly non-reversible once posted
  return {
    status: "POSTED",
    impairment: formatFixed8(impairment),
    isReversible: false,
  };
}

export function validateTransferMovement(
  sourceWh: string,
  destWh: string,
  qtyStr: string,
  sourceStockStr: string
): { valid: boolean; error?: string } {
  if (!sourceWh || !destWh) {
    return { valid: false, error: "WAREHOUSES_REQUIRED" };
  }
  if (sourceWh === destWh) {
    return { valid: false, error: "DISTINCT_TRANSFER_WAREHOUSES_REQUIRED" };
  }
  const qty = parseFixed8(qtyStr);
  const stock = parseFixed8(sourceStockStr);
  if (qty <= 0n) {
    return { valid: false, error: "POSITIVE_QUANTITY_REQUIRED" };
  }
  if (stock < qty) {
    return { valid: false, error: "INSUFFICIENT_STOCK" };
  }
  return { valid: true };
}

export function validateCipCapitalization(
  capitalizedCostStr: string,
  cardCosts: string[]
): { valid: boolean; error?: string } {
  const cap = parseFixed8(capitalizedCostStr);
  if (cap <= 0n) return { valid: false, error: "POSITIVE_CAPITALIZED_COST_REQUIRED" };
  if (cardCosts.length === 0) return { valid: false, error: "TARGET_ASSET_CARDS_EMPTY" };

  let sum = 0n;
  for (const c of cardCosts) {
    sum += parseFixed8(c);
  }
  if (sum !== cap) {
    return { valid: false, error: "CIP_CAPITALIZATION_CARDS_MISMATCH" };
  }
  return { valid: true };
}

export function validateRdCapitalization(
  stage: string,
  evidenceId: string | null,
  capitalizedAmountStr: string,
  projectBalanceStr: string
): { valid: boolean; error?: string } {
  if (stage !== "DEVELOPMENT") {
    return { valid: false, error: "RESEARCH_STAGE_CANNOT_CAPITALIZE" };
  }
  if (!evidenceId) {
    return { valid: false, error: "STATUTORY_EVIDENCE_REQUIRED" };
  }
  const amount = parseFixed8(capitalizedAmountStr);
  const bal = parseFixed8(projectBalanceStr);
  if (amount <= 0n) {
    return { valid: false, error: "POSITIVE_AMOUNT_REQUIRED" };
  }
  if (bal < amount) {
    return { valid: false, error: "INSUFFICIENT_RD_CAPITALIZED_BALANCE" };
  }
  return { valid: true };
}

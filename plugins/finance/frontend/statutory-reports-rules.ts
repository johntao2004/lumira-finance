/**
 * Statutory Reports Rules & Contracts (Lane W11)
 * Covers RPT01-RPT06, RP01, RP02, RP03
 */

export type Standard = 'CAS_2019' | 'SBE_2011';
export type StatementKind = 'BS' | 'PL' | 'CF' | 'SCE' | 'NOTES';
export type PackageType = 'THREE_STATEMENT_PACKAGE' | 'FULL_STATUTORY_REPORT_PACKAGE';
export type PackageStatus = 'DRAFT' | 'REVIEWED' | 'APPROVED' | 'PUBLISHED' | 'BLOCKED';
export type FilingTarget = 'TAX_FILING' | 'MARKET_SUPERVISION_FILING' | 'STATUTORY_AUDIT_FILING';

export interface VoucherLine {
  lineNo: number;
  accountCode: string;
  accountName: string;
  direction: 'DEBIT' | 'CREDIT';
  amount: string; // Plain decimal string
  memo: string;
}

export interface CanonicalVoucher {
  code: string;
  title: string;
  standard: Standard;
  businessDescription: string;
  lines: VoucherLine[];
  totalDebit: string;
  totalCredit: string;
}

export interface ReportCell {
  rowId: string;
  label: string;
  rawValue: string;
  formattedValue: string;
  status: 'EXACT' | 'DRAFT' | 'MISSING' | 'ZERO';
  gaps?: string[];
}

export interface StatementSheet {
  kind: StatementKind;
  templateCode: string;
  cells: Record<string, ReportCell>;
  articulationStatus: 'PASS' | 'FAIL' | 'BLOCKED';
  articulationDetails: Record<string, any>;
}

export interface StructuredNotes {
  sections: Record<string, string>;
  mandatoryComplete: boolean;
  missingSections: string[];
}

export interface ReportPackage {
  id: string;
  standard: Standard;
  packageType: PackageType;
  filingTarget: FilingTarget;
  status: PackageStatus;
  sheets: StatementSheet[];
  notes?: StructuredNotes;
  publicationGaps: string[];
  contentDigest: string;
}

export interface KpiMetrics {
  burnRate: string;
  runwayMonths: string;
  rdIntensity: string;
  grossMargin: string;
  dsoDays: string;
  dpoDays: string;
  details: Record<string, any>;
  status: string;
}

export interface DailyCashForecast {
  date: string;
  scheduledInflow: string;
  scheduledOutflow: string;
  netDailyChange: string;
  projectedBalance: string;
  belowBuffer: boolean;
}

export interface CashCalendar {
  id: string;
  projectionDate: string;
  horizonDays: number;
  openingBalance: string;
  safeBufferAmount: string;
  minProjectedBalance: string;
  bufferBreached: boolean;
  dailyForecasts: DailyCashForecast[];
  status: string;
}

export interface MinorityInterest {
  subsidiaryId: string;
  subsidiaryName: string;
  minorityRatio: string;
  minorityEquity: string;
  minorityProfit: string;
}

export interface EliminationEntry {
  entryType: string;
  description: string;
  lines: VoucherLine[];
  totalDebit: string;
  totalCredit: string;
}

export interface ConsolidationWorksheet {
  id: string;
  scopeId: string;
  periodStart: string;
  periodEnd: string;
  isManagementRollup: boolean;
  rollupData: Record<string, any>;
  eliminations: EliminationEntry[];
  minorityInterests: MinorityInterest[];
  status: string;
}

// -----------------------------------------------------------------------------
// Pure Calculation & Validation Logic
// -----------------------------------------------------------------------------

export function validateVoucherBalance(voucher: CanonicalVoucher): boolean {
  let sumDebit = 0;
  let sumCredit = 0;
  for (const line of voucher.lines) {
    const val = parseFloat(line.amount);
    if (isNaN(val) || val <= 0) return false;
    if (line.direction === 'DEBIT') sumDebit += val;
    else if (line.direction === 'CREDIT') sumCredit += val;
    else return false;
  }
  return Math.abs(sumDebit - sumCredit) < 0.0001 && Math.abs(sumDebit - parseFloat(voucher.totalDebit)) < 0.0001;
}

export function validateBalanceSheetArticulation(totalAssets: number, totalLiabilities: number, totalEquity: number): { pass: boolean; diff: number } {
  const diff = Math.round((totalAssets - (totalLiabilities + totalEquity)) * 100) / 100;
  return { pass: Math.abs(diff) < 0.0001, diff };
}

export function validateCashFlowBridge(
  openingCash: number,
  closingCash: number,
  netCashIncrease: number,
  netOperatingCash: number,
  indirectOperatingCash: number
): { pass: boolean; cashDiff: number; indirectDiff: number } {
  const cashDiff = Math.round((closingCash - openingCash - netCashIncrease) * 100) / 100;
  const indirectDiff = Math.round((indirectOperatingCash - netOperatingCash) * 100) / 100;
  return {
    pass: Math.abs(cashDiff) < 0.0001 && Math.abs(indirectDiff) < 0.0001,
    cashDiff,
    indirectDiff
  };
}

export function evaluatePackagePublication(
  packageType: PackageType,
  standard: Standard,
  bsPass: boolean,
  cfPass: boolean,
  hasSce: boolean,
  notesComplete: boolean
): { status: PackageStatus; gaps: string[] } {
  const gaps: string[] = [];
  if (!bsPass) gaps.push('BS_ARTICULATION_FAILURE');
  if (!cfPass) gaps.push('CF_ARTICULATION_FAILURE');

  if (packageType === 'FULL_STATUTORY_REPORT_PACKAGE') {
    if (standard === 'CAS_2019' && !hasSce) {
      gaps.push('CAS_EQUITY_CHANGES_STATEMENT_MANDATORY');
    }
    if (!notesComplete) {
      gaps.push('MANDATORY_NOTES_INCOMPLETE');
    }
  }

  return {
    status: gaps.length > 0 ? 'BLOCKED' : 'APPROVED',
    gaps
  };
}

export function computeKpis(
  availableCash: number,
  opOutflow: number,
  opInflow: number,
  capexOutflow: number,
  months: number,
  revenue: number,
  cogs: number,
  rdExpense: number,
  avgReceivables: number,
  avgPayables: number
): KpiMetrics {
  const validMonths = months > 0 ? months : 12;
  const netDrain = Math.max(0, opOutflow + capexOutflow - opInflow);
  const burnRate = Math.round((netDrain / validMonths) * 100) / 100;
  const runwayMonths = burnRate > 0 && availableCash > 0 ? Math.round((availableCash / burnRate) * 10) / 10 : availableCash > 0 ? 999 : 0;
  const rdIntensity = revenue > 0 ? Math.round((rdExpense / revenue) * 1000000) / 10000 : 0;
  const grossMargin = revenue > 0 ? Math.round(((revenue - cogs) / revenue) * 1000000) / 10000 : 0;
  const dsoDays = revenue > 0 ? Math.round(((avgReceivables * 365) / revenue) * 10) / 10 : 0;
  const dpoDays = cogs > 0 ? Math.round(((avgPayables * 365) / cogs) * 10) / 10 : 0;

  return {
    burnRate: burnRate.toFixed(2),
    runwayMonths: runwayMonths.toFixed(1),
    rdIntensity: rdIntensity.toFixed(4),
    grossMargin: grossMargin.toFixed(4),
    dsoDays: dsoDays.toFixed(1),
    dpoDays: dpoDays.toFixed(1),
    details: { availableCash: availableCash.toFixed(2), netDrain: netDrain.toFixed(2), months: validMonths },
    status: 'COMPUTED'
  };
}

export function projectCashCalendar(
  openingCash: number,
  safeBuffer: number,
  horizonDays: number,
  inflows: Record<string, number>,
  outflows: Record<string, number>,
  startDateStr: string
): { bufferBreached: boolean; minBalance: number; forecasts: DailyCashForecast[] } {
  let balance = openingCash;
  let minBalance = balance;
  let bufferBreached = false;
  const forecasts: DailyCashForecast[] = [];
  const start = new Date(startDateStr);

  for (let i = 1; i <= horizonDays; i++) {
    const d = new Date(start);
    d.setDate(d.getDate() + i);
    const dateStr = d.toISOString().split('T')[0];
    const inf = inflows[dateStr] || 0;
    const out = outflows[dateStr] || 0;
    const net = inf - out;
    balance += net;

    if (balance < minBalance) minBalance = balance;
    const below = balance < safeBuffer;
    if (below) bufferBreached = true;

    forecasts.push({
      date: dateStr,
      scheduledInflow: inf.toFixed(2),
      scheduledOutflow: out.toFixed(2),
      netDailyChange: net.toFixed(2),
      projectedBalance: balance.toFixed(2),
      belowBuffer: below
    });
  }

  return { bufferBreached, minBalance, forecasts };
}

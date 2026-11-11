/**
 * Frontend domain rules and validation utilities for Lane W12 (archive-int-ai).
 * Verified with Node 24 ESM test runner.
 */

export interface FieldMapping {
  sourceColumn: string;
  targetField: string;
  dataType: 'STRING' | 'DECIMAL' | 'DATE' | 'UUID' | 'INTEGER';
  required: boolean;
}

export interface ImportRowError {
  lineNo: number;
  column: string;
  errorCode: string;
  message: string;
  sampleValue: string;
}

export interface DryRunResult {
  totalRows: number;
  validRows: number;
  errorRows: number;
  controlTotalDebit: string;
  controlTotalCredit: string;
  passed: boolean;
  errors: ImportRowError[];
}

export interface ArchiveRecord {
  id: string;
  category: string;
  title: string;
  statutoryYears: number;
  retentionUntilDate: string;
  legalHold: boolean;
  status: string;
}

export const STATUTORY_RETENTION_YEARS: Record<string, { years: number; desc: string }> = {
  VOUCHER_GENERAL: { years: 30, desc: '原始凭证、记账凭证' },
  LEDGER_BOOK: { years: 30, desc: '总账、明细账、日记账' },
  STATEMENT_ANNUAL: { years: 999, desc: '年度财务会计报告（决算）' },
  STATEMENT_PERIODIC: { years: 10, desc: '月度、季度、半年度财务报告' },
  BANK_RECONCILIATION: { years: 10, desc: '银行存款余额调节表、银行对账单' },
  TAX_DECLARATION: { years: 10, desc: '纳税申报表及附列资料' },
  CUSTODY_TRANSFER: { years: 999, desc: '会计档案移交清册' },
  DESTRUCTION_MANIFEST: { years: 999, desc: '会计档案销毁清册及鉴定意见书' },
};

export const CRM_PROHIBITED_FIELDS = [
  'lead_id',
  'opportunity_id',
  'pipeline_stage',
  'crm_contact',
  'sales_funnel',
  'deal_stage',
  'campaign_id',
];

export const MAX_OFFLINE_LEASE_SECONDS = 28800; // 8 hours

export function calculateRetentionUntilDate(archivedYear: number, statutoryYears: number): string {
  if (statutoryYears >= 999) {
    return '9999-12-31';
  }
  return `${archivedYear + statutoryYears}-12-31`;
}

export function validateBorrowingSoD(borrowerId: string, inspectorId?: string | null): void {
  if (inspectorId && inspectorId === borrowerId) {
    throw new Error('SOD_VIOLATION: Borrower cannot inspect their own return');
  }
}

export function validateBorrowReturnSoD(borrowerId: string, inspectorId: string): void {
  if (!borrowerId || !inspectorId) {
    throw new Error('MISSING_ACTOR: Borrower and return inspector IDs are required');
  }
  if (inspectorId === borrowerId) {
    throw new Error('SOD_VIOLATION: Borrower cannot inspect their own return');
  }
}

export function validateCustodyTransferSoD(transferor: string, recipient: string, supervisor: string): void {
  if (transferor === recipient) throw new Error('SOD_VIOLATION: Transferor cannot be recipient');
  if (transferor === supervisor) throw new Error('SOD_VIOLATION: Transferor cannot be supervisor');
  if (recipient === supervisor) throw new Error('SOD_VIOLATION: Recipient cannot be supervisor');
}

export function validateDestructionAppraisal(
  records: ArchiveRecord[],
  appraiser1: string,
  appraiser2: string,
  supervisor1: string,
  supervisor2: string,
  currentDateIso: string
): void {
  if (!records || records.length === 0) {
    throw new Error('NO_RECORDS: Destruction appraisal manifest must contain at least one archive record');
  }
  if (appraiser1 === appraiser2) {
    throw new Error('SOD_VIOLATION: Dual appraisers must be two distinct persons');
  }
  if (supervisor1 === supervisor2) {
    throw new Error('SOD_VIOLATION: Dual supervisors must be two distinct persons');
  }
  const appraisers = new Set([appraiser1, appraiser2]);
  if (appraisers.has(supervisor1) || appraisers.has(supervisor2)) {
    throw new Error('SOD_VIOLATION: Supervisors must be completely distinct from appraisers');
  }

  for (const r of records) {
    if (r.legalHold) {
      throw new Error(`LEGAL_HOLD_ACTIVE: Record ${r.id} is under legal hold or ongoing tax audit; destruction is frozen`);
    }
    if (r.statutoryYears >= 999) {
      throw new Error(`PERMANENT_RECORD: Record ${r.id} is classified as permanent retention and cannot be destroyed`);
    }
    if (r.retentionUntilDate > currentDateIso) {
      throw new Error(`RETENTION_NOT_EXPIRED: Record ${r.id} retention expires on ${r.retentionUntilDate}, cannot destroy before expiry`);
    }
  }
}

export function verifyArchiveCompleteness(
  statementsPublished: boolean,
  fiscalYearClosed: boolean,
  originalsVerified: boolean,
  auditLogsComplete: boolean
): { completenessSatisfied: boolean; legalArchiveComplete: boolean; blockers: string[] } {
  const blockers: string[] = [];
  if (!statementsPublished) blockers.push('STATUTORY_STATEMENTS_NOT_PUBLISHED');
  if (!fiscalYearClosed) blockers.push('FISCAL_YEAR_NOT_CLOSED');
  if (!originalsVerified) blockers.push('ORIGINALS_NOT_VERIFIED');
  if (!auditLogsComplete) blockers.push('AUDIT_LOGS_INCOMPLETE');

  const passed = blockers.length === 0;
  return {
    completenessSatisfied: passed,
    legalArchiveComplete: passed,
    blockers,
  };
}

export function parseCsvLine(line: string, delimiter: string = ','): string[] {
  if (line.startsWith('\uFEFF')) {
    line = line.slice(1);
  }
  const cells: string[] = [];
  let current = '';
  let inQuotes = false;
  for (let i = 0; i < line.length; i++) {
    const char = line[i];
    if (char === '"') {
      if (inQuotes && i + 1 < line.length && line[i + 1] === '"') {
        current += '"';
        i++;
      } else {
        inQuotes = !inQuotes;
      }
    } else if (char === delimiter && !inQuotes) {
      cells.push(current.trim());
      current = '';
    } else {
      current += char;
    }
  }
  cells.push(current.trim());
  return cells;
}

export function stripSurroundingQuotes(val: string): string {
  val = (val || '').trim();
  if (val.length >= 2 && val.startsWith('"') && val.endsWith('"')) {
    return val.slice(1, -1).replace(/""/g, '"').trim();
  }
  return val;
}

export function dryRunCsv(mappings: FieldMapping[], delimiter: string, csvContent: string): DryRunResult {
  if (csvContent.startsWith('\uFEFF')) {
    csvContent = csvContent.slice(1);
  }
  const lines = csvContent.split(/\r?\n/).filter(l => l.trim().length > 0);
  if (lines.length === 0) {
    return {
      totalRows: 0,
      validRows: 0,
      errorRows: 0,
      controlTotalDebit: '0.00000000',
      controlTotalCredit: '0.00000000',
      passed: false,
      errors: [{ lineNo: 1, column: 'FILE', errorCode: 'EMPTY_FILE', message: 'CSV content is empty', sampleValue: '' }],
    };
  }

  const headers = parseCsvLine(lines[0], delimiter).map(h => stripSurroundingQuotes(h).trim());
  const headerMap = new Map<string, number>();
  headers.forEach((h, idx) => headerMap.set(h, idx));

  const errors: ImportRowError[] = [];
  for (const fm of mappings) {
    if (fm.required && !headerMap.has(fm.sourceColumn)) {
      errors.push({
        lineNo: 1,
        column: fm.sourceColumn,
        errorCode: 'MISSING_HEADER',
        message: `Required column missing from CSV header: ${fm.sourceColumn}`,
        sampleValue: '',
      });
    }
  }
  if (errors.length > 0) {
    return {
      totalRows: lines.length - 1,
      validRows: 0,
      errorRows: lines.length - 1,
      controlTotalDebit: '0.00000000',
      controlTotalCredit: '0.00000000',
      passed: false,
      errors,
    };
  }

  let validCount = 0;
  let debitSum = 0n;
  let creditSum = 0n;
  const SCALE = 100000000n; // 10^8

  for (let r = 1; r < lines.length; r++) {
    const cells = parseCsvLine(lines[r], delimiter);
    let rowValid = true;
    let rowDebit = 0n;
    let rowCredit = 0n;

    for (const fm of mappings) {
      const idx = headerMap.get(fm.sourceColumn);
      const val = (idx !== undefined && idx < cells.length) ? stripSurroundingQuotes(cells[idx].trim()) : '';

      if (fm.required && val.length === 0) {
        errors.push({
          lineNo: r + 1,
          column: fm.sourceColumn,
          errorCode: 'REQUIRED_FIELD_EMPTY',
          message: 'Required field is empty',
          sampleValue: val,
        });
        rowValid = false;
        continue;
      }

      if (val.length > 0) {
        if (fm.dataType === 'DECIMAL') {
          if (!/^(0|[1-9]\d*)(\.\d{1,8})?$/.test(val)) {
            errors.push({
              lineNo: r + 1,
              column: fm.sourceColumn,
              errorCode: 'INVALID_DECIMAL',
              message: 'Invalid decimal format or exceeds 8 decimal places',
              sampleValue: val,
            });
            rowValid = false;
          } else {
            const parts = val.split('.');
            const whole = BigInt(parts[0]) * SCALE;
            let frac = 0n;
            if (parts[1]) {
              const padded = parts[1].padEnd(8, '0').slice(0, 8);
              frac = BigInt(padded);
            }
            const scaledVal = whole + frac;
            if (fm.targetField.toLowerCase() === 'debit') {
              rowDebit += scaledVal;
            } else if (fm.targetField.toLowerCase() === 'credit') {
              rowCredit += scaledVal;
            }
          }
        } else if (fm.dataType === 'DATE') {
          if (!/^\d{4}-\d{2}-\d{2}$/.test(val)) {
            errors.push({
              lineNo: r + 1,
              column: fm.sourceColumn,
              errorCode: 'INVALID_DATE_FORMAT',
              message: 'Date must be formatted as YYYY-MM-DD',
              sampleValue: val,
            });
            rowValid = false;
          }
        }
      }
    }

    if (rowValid) {
      validCount++;
      debitSum += rowDebit;
      creditSum += rowCredit;
    }
  }

  const formatScaled = (val: bigint) => {
    const w = (val / SCALE).toString();
    const f = (val % SCALE).toString().padStart(8, '0');
    return `${w}.${f}`;
  };

  return {
    totalRows: lines.length - 1,
    validRows: validCount,
    errorRows: errors.length,
    controlTotalDebit: formatScaled(debitSum),
    controlTotalCredit: formatScaled(creditSum),
    passed: errors.length === 0,
    errors,
  };
}

export function reconcileControlTotals(
  expDebit: string,
  expCredit: string,
  actDebit: string,
  actCredit: string,
  expRows: number,
  actRows: number
): { matched: boolean; status: 'RECONCILED' | 'MISMATCH_DETECTED' } {
  const norm = (s: string) => {
    const n = Number(s);
    return isNaN(n) ? s : n.toFixed(8);
  };
  const debitMatch = norm(expDebit) === norm(actDebit);
  const creditMatch = norm(expCredit) === norm(actCredit);
  const rowsMatch = expRows === actRows;
  const matched = debitMatch && creditMatch && rowsMatch;
  return {
    matched,
    status: matched ? 'RECONCILED' : 'MISMATCH_DETECTED',
  };
}

const NORMALIZED_CRM_KEYS = new Set([
  'leadid',
  'opportunityid',
  'pipelinestage',
  'crmcontact',
  'salesfunnel',
  'dealstage',
  'campaignid',
]);

export function checkAntiCrmGuard(payloadJsonOrObj: unknown): void {
  const text = typeof payloadJsonOrObj === 'string' ? payloadJsonOrObj : JSON.stringify(payloadJsonOrObj);

  // 1. Check extracted JSON keys with normalization (stripping underscores/hyphens and lowercasing)
  const keyRegex = /["']([a-zA-Z0-9_\-]+)["']\s*:/g;
  let match: RegExpExecArray | null;
  while ((match = keyRegex.exec(text)) !== null) {
    const rawKey = match[1];
    const normalizedKey = rawKey.toLowerCase().replace(/_/g, '').replace(/-/g, '');
    if (NORMALIZED_CRM_KEYS.has(normalizedKey)) {
      throw new Error(`CRM_PAYLOAD_REJECTED: Anti-CRM guard detected non-accounting field '${rawKey}'`);
    }
  }

  // 2. Substring check fallback
  const lower = text.toLowerCase();
  for (const crmField of CRM_PROHIBITED_FIELDS) {
    const lowerField = crmField.toLowerCase();
    if (lower.includes(`"${lowerField}"`) || lower.includes(`'${lowerField}'`)) {
      throw new Error(`CRM_PAYLOAD_REJECTED: Anti-CRM guard detected non-accounting field '${crmField}'`);
    }
  }
}

export function calculateMonthEndDate(year: number, month: number, leapYearRule = 'LAST_DAY_OF_MONTH'): string {
  if (month < 1 || month > 12) throw new Error(`Invalid month: ${month}`);
  if (month === 2) {
    const isLeap = (year % 4 === 0 && year % 100 !== 0) || (year % 400 === 0);
    if (leapYearRule === 'EXACT_28_ALWAYS') {
      return `${year}-02-28`;
    }
    return `${year}-02-${isLeap ? '29' : '28'}`;
  }
  const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate();
  return `${year}-${String(month).padStart(2, '0')}-${String(lastDay).padStart(2, '0')}`;
}

export function calculateScheduleExecutionDate(
  sched: { scheduleType: string; dayOfMonth?: number; leapYearFebRule?: string },
  year: number,
  month: number
): string {
  if (sched.scheduleType === 'MONTH_END' || sched.scheduleType === 'PERIOD_END') {
    return calculateMonthEndDate(year, month, sched.leapYearFebRule || 'LAST_DAY_OF_MONTH');
  }
  if (sched.scheduleType === 'DAY_OF_MONTH') {
    const dom = sched.dayOfMonth && sched.dayOfMonth > 0 ? sched.dayOfMonth : 1;
    let lastDayOfMonth = 31;
    if (month === 2) {
      const isLeap = (year % 4 === 0 && year % 100 !== 0) || (year % 400 === 0);
      lastDayOfMonth = sched.leapYearFebRule === 'EXACT_28_ALWAYS' ? 28 : (isLeap ? 29 : 28);
    } else {
      lastDayOfMonth = new Date(Date.UTC(year, month, 0)).getUTCDate();
    }
    const actualDay = Math.min(dom, lastDayOfMonth);
    return `${year}-${String(month).padStart(2, '0')}-${String(actualDay).padStart(2, '0')}`;
  }
  return calculateMonthEndDate(year, month, sched.leapYearFebRule || 'LAST_DAY_OF_MONTH');
}

export function detectMissedScheduleWindows(
  periods: Array<{ periodId: string; periodNumber: number; year: number }>,
  executedPeriodIds: Set<string>,
  currentPeriodNumber: number,
  leapYearRule = 'LAST_DAY_OF_MONTH'
): Array<{ periodId: string; periodNumber: number; expectedDate: string; executionType: string }> {
  const missed: Array<{ periodId: string; periodNumber: number; expectedDate: string; executionType: string }> = [];
  for (const p of periods) {
    if (p.periodNumber < currentPeriodNumber && !executedPeriodIds.has(p.periodId)) {
      missed.push({
        periodId: p.periodId,
        periodNumber: p.periodNumber,
        expectedDate: calculateMonthEndDate(p.year, p.periodNumber, leapYearRule),
        executionType: 'CATCH_UP',
      });
    }
  }
  return missed;
}

export function suggestAccountFromOcr(ocrText: string): {
  suggestedAccountCode: string;
  accountName: string;
  confidence: string;
  externalModelProvider: string;
  mandatoryHumanReview: boolean;
} {
  const lower = (ocrText || '').toLowerCase();
  if (lower.includes('机票') || lower.includes('差旅') || lower.includes('住宿') || lower.includes('酒店')) {
    return {
      suggestedAccountCode: '660201',
      accountName: '管理费用-差旅费',
      confidence: '0.9500',
      externalModelProvider: 'NOT_CONFIGURED',
      mandatoryHumanReview: true,
    };
  }
  if (lower.includes('办公') || lower.includes('文具') || lower.includes('打印')) {
    return {
      suggestedAccountCode: '660202',
      accountName: '管理费用-办公费',
      confidence: '0.9200',
      externalModelProvider: 'NOT_CONFIGURED',
      mandatoryHumanReview: true,
    };
  }
  if (lower.includes('销售') || lower.includes('货款') || lower.includes('收入')) {
    return {
      suggestedAccountCode: '600101',
      accountName: '主营业务收入',
      confidence: '0.9400',
      externalModelProvider: 'NOT_CONFIGURED',
      mandatoryHumanReview: true,
    };
  }
  return {
    suggestedAccountCode: '660299',
    accountName: '管理费用-其他',
    confidence: '0.5000',
    externalModelProvider: 'NOT_CONFIGURED',
    mandatoryHumanReview: true,
  };
}

export function validateActionPlanExecution(actions: Array<{ actionId: string; command: string; risk: string }>): void {
  for (const a of actions) {
    const cmd = (a.command || '').toUpperCase();
    const isMajor = cmd.startsWith('TAX_') || cmd.startsWith('INVOICE_') || cmd.includes('PAYMENT') || cmd.includes('DISBURSEMENT');
    if (a.risk === 'R4' || isMajor) {
      throw new Error(
        `AI_MAJOR_ACTION_BLOCKED: Action ${a.actionId} (${a.command}) is risk tier R4 or targets external major operations. AI execution of external tax filing, invoice issuing/invalidation, tax payment, or bank disbursement is strictly prohibited.`
      );
    }
  }
}

export function validateOfflineLease(requestedSeconds: number): void {
  if (requestedSeconds <= 0 || requestedSeconds > MAX_OFFLINE_LEASE_SECONDS) {
    throw new Error(
      `LEASE_DURATION_EXCEEDED: Requested lease duration ${requestedSeconds}s exceeds maximum allowed 8 hours (${MAX_OFFLINE_LEASE_SECONDS}s)`
    );
  }
}

export function validateReconnection(lease: { status: string; expiresAt: string }): void {
  if (lease.status === 'REVOKED') {
    throw new Error('LEASE_REVOKED: Offline lease was revoked by administrator');
  }
  if (lease.status === 'EXPIRED' || new Date(lease.expiresAt).getTime() < Date.now()) {
    throw new Error('LEASE_EXPIRED: Offline lease has expired; client must re-authenticate online');
  }
}

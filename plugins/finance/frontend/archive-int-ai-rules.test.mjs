import test from 'node:test';
import assert from 'node:assert/strict';
import {
  STATUTORY_RETENTION_YEARS,
  calculateRetentionUntilDate,
  validateBorrowingSoD,
  validateBorrowReturnSoD,
  validateCustodyTransferSoD,
  validateDestructionAppraisal,
  verifyArchiveCompleteness,
  dryRunCsv,
  reconcileControlTotals,
  checkAntiCrmGuard,
  calculateMonthEndDate,
  calculateScheduleExecutionDate,
  detectMissedScheduleWindows,
  suggestAccountFromOcr,
  validateActionPlanExecution,
  validateOfflineLease,
  validateReconnection,
} from './archive-int-ai-rules.ts';

// 1. Statutory retention rules & SoD
test('1. Statutory retention rules & SoD', () => {
  assert.equal(STATUTORY_RETENTION_YEARS.VOUCHER_GENERAL.years, 30);
  assert.equal(STATUTORY_RETENTION_YEARS.STATEMENT_ANNUAL.years, 999);
  assert.equal(STATUTORY_RETENTION_YEARS.BANK_RECONCILIATION.years, 10);
  assert.equal(calculateRetentionUntilDate(2026, 30), '2056-12-31');
  assert.equal(calculateRetentionUntilDate(2026, 999), '9999-12-31');
  assert.equal(calculateRetentionUntilDate(2026, 10), '2036-12-31');

  assert.throws(() => validateBorrowingSoD('person-1', 'person-1'), /SOD_VIOLATION/);
  assert.doesNotThrow(() => validateBorrowingSoD('person-1', 'person-2'));

  assert.throws(() => validateBorrowReturnSoD('borrower-1', 'borrower-1'), /SOD_VIOLATION/);
  assert.doesNotThrow(() => validateBorrowReturnSoD('borrower-1', 'inspector-2'));

  assert.throws(() => validateCustodyTransferSoD('p1', 'p1', 'p3'), /SOD_VIOLATION/);
  assert.throws(() => validateCustodyTransferSoD('p1', 'p2', 'p1'), /SOD_VIOLATION/);
  assert.throws(() => validateCustodyTransferSoD('p1', 'p2', 'p2'), /SOD_VIOLATION/);
  assert.doesNotThrow(() => validateCustodyTransferSoD('p1', 'p2', 'p3'));

  // Legal hold blocks destruction
  const holdRecord = {
    id: 'rec-001',
    category: 'VOUCHER_GENERAL',
    title: '涉案凭证',
    statutoryYears: 30,
    retentionUntilDate: '2020-12-31',
    legalHold: true,
    status: 'IN_CUSTODY',
  };
  assert.throws(
    () => validateDestructionAppraisal([holdRecord], 'app1', 'app2', 'sup1', 'sup2', '2026-11-10'),
    /LEGAL_HOLD_ACTIVE/
  );

  // Permanent record cannot be destroyed
  const permRecord = {
    id: 'rec-002',
    category: 'STATEMENT_ANNUAL',
    title: '年度决算报告',
    statutoryYears: 999,
    retentionUntilDate: '9999-12-31',
    legalHold: false,
    status: 'IN_CUSTODY',
  };
  assert.throws(
    () => validateDestructionAppraisal([permRecord], 'app1', 'app2', 'sup1', 'sup2', '2026-11-10'),
    /PERMANENT_RECORD/
  );

  // Valid appraisal with dual appraisers and distinct supervisors
  const expiredRecord = {
    id: 'rec-003',
    category: 'STATEMENT_PERIODIC',
    title: '2010年第1季度报表',
    statutoryYears: 10,
    retentionUntilDate: '2020-12-31',
    legalHold: false,
    status: 'IN_CUSTODY',
  };
  assert.doesNotThrow(() =>
    validateDestructionAppraisal([expiredRecord], 'app1', 'app2', 'sup1', 'sup2', '2026-11-10')
  );

  // Non-empty records requirement for destruction appraisal
  assert.throws(
    () => validateDestructionAppraisal([], 'app1', 'app2', 'sup1', 'sup2', '2026-11-10'),
    /NO_RECORDS/
  );
});

// 2. Archive completeness verification
test('2. Archive completeness verification', () => {
  const completeRes = verifyArchiveCompleteness(true, true, true, true);
  assert.equal(completeRes.completenessSatisfied, true);
  assert.equal(completeRes.legalArchiveComplete, true);
  assert.equal(completeRes.blockers.length, 0);

  const blockedRes = verifyArchiveCompleteness(false, false, true, true);
  assert.equal(blockedRes.completenessSatisfied, false);
  assert.equal(blockedRes.legalArchiveComplete, false);
  assert.ok(blockedRes.blockers.includes('STATUTORY_STATEMENTS_NOT_PUBLISHED'));
  assert.ok(blockedRes.blockers.includes('FISCAL_YEAR_NOT_CLOSED'));
});

// 3. CSV dry-run and control total reconciliation
test('3. CSV dry-run and control total reconciliation', () => {
  const mappings = [
    { sourceColumn: 'voucher_no', targetField: 'voucher_no', dataType: 'STRING', required: true },
    { sourceColumn: 'account_code', targetField: 'account_code', dataType: 'STRING', required: true },
    { sourceColumn: 'debit', targetField: 'debit', dataType: 'DECIMAL', required: true },
    { sourceColumn: 'credit', targetField: 'credit', dataType: 'DECIMAL', required: true },
    { sourceColumn: 'accounting_date', targetField: 'accounting_date', dataType: 'DATE', required: true },
  ];

  const validCsv = `voucher_no,account_code,debit,credit,accounting_date
V001,100201,1000.50,0.00,2026-10-15
V001,600101,0.00,1000.50,2026-10-15`;

  const dryRunSuccess = dryRunCsv(mappings, ',', validCsv);
  assert.equal(dryRunSuccess.passed, true);
  assert.equal(dryRunSuccess.validRows, 2);
  assert.equal(dryRunSuccess.controlTotalDebit, '1000.50000000');
  assert.equal(dryRunSuccess.controlTotalCredit, '1000.50000000');

  // RFC 4180 CSV with quotes, embedded comma, and UTF-8 BOM
  const rfcCsv = `\uFEFFvoucher_no,account_code,debit,credit,accounting_date
"V002","100201","880.00","0.00","2026-10-16"
"V002","660201","0.00","880.00","2026-10-16"`;
  const rfcRes = dryRunCsv(mappings, ',', rfcCsv);
  assert.equal(rfcRes.passed, true);
  assert.equal(rfcRes.validRows, 2);
  assert.equal(rfcRes.controlTotalDebit, '880.00000000');
  assert.equal(rfcRes.controlTotalCredit, '880.00000000');

  const invalidCsv = `voucher_no,account_code,debit,credit,accounting_date
,100201,invalid,0.00,2026-99-99`;
  const dryRunFail = dryRunCsv(mappings, ',', invalidCsv);
  assert.equal(dryRunFail.passed, false);
  assert.ok(dryRunFail.errors.some(e => e.errorCode === 'REQUIRED_FIELD_EMPTY'));
  assert.ok(dryRunFail.errors.some(e => e.errorCode === 'INVALID_DECIMAL'));

  // Control total reconciliation
  const recPass = reconcileControlTotals('1000.50', '1000.50', '1000.50', '1000.50', 2, 2);
  assert.equal(recPass.matched, true);
  assert.equal(recPass.status, 'RECONCILED');

  const recFail = reconcileControlTotals('1000.50', '1000.50', '900.00', '1000.50', 2, 2);
  assert.equal(recFail.matched, false);
  assert.equal(recFail.status, 'MISMATCH_DETECTED');
});

// 4. Anti-CRM Guard (snake_case, camelCase, PascalCase, kebab-case)
test('4. Anti-CRM Guard (snake_case, camelCase, PascalCase, kebab-case)', () => {
  assert.doesNotThrow(() => checkAntiCrmGuard({ source_system: 'SAP', net_amount: '500.00' }));
  assert.throws(() => checkAntiCrmGuard({ source_system: 'SAP', lead_id: 'CRM-123' }), /CRM_PAYLOAD_REJECTED/);
  assert.throws(() => checkAntiCrmGuard({ source_system: 'SAP', leadId: 'CRM-123' }), /CRM_PAYLOAD_REJECTED/);
  assert.throws(() => checkAntiCrmGuard({ source_system: 'SAP', OpportunityId: 'OPP-456' }), /CRM_PAYLOAD_REJECTED/);
  assert.throws(() => checkAntiCrmGuard({ source_system: 'SAP', 'pipeline-stage': 'STAGE_2' }), /CRM_PAYLOAD_REJECTED/);
});

// 5. Month-end & Leap-year calendar rules and missed-window detection
test('5. Month-end & Leap-year calendar rules and missed-window detection', () => {
  assert.equal(calculateMonthEndDate(2024, 2, 'LAST_DAY_OF_MONTH'), '2024-02-29'); // Leap year
  assert.equal(calculateMonthEndDate(2025, 2, 'LAST_DAY_OF_MONTH'), '2025-02-28'); // Normal year
  assert.equal(calculateMonthEndDate(2028, 2, 'LAST_DAY_OF_MONTH'), '2028-02-29'); // Leap year
  assert.equal(calculateMonthEndDate(2026, 4, 'LAST_DAY_OF_MONTH'), '2026-04-30'); // 30-day month
  assert.equal(calculateMonthEndDate(2026, 3, 'LAST_DAY_OF_MONTH'), '2026-03-31'); // 31-day month

  // Day 31 clamping across months
  const schedDay31 = { scheduleType: 'DAY_OF_MONTH', dayOfMonth: 31 };
  assert.equal(calculateScheduleExecutionDate(schedDay31, 2026, 4), '2026-04-30'); // April has 30 days
  assert.equal(calculateScheduleExecutionDate(schedDay31, 2024, 2), '2024-02-29'); // Feb 2024 leap year
  assert.equal(calculateScheduleExecutionDate(schedDay31, 2025, 2), '2025-02-28'); // Feb 2025 normal year

  // Missed-window detection
  const periods = [
    { periodId: 'p1', periodNumber: 1, year: 2026 },
    { periodId: 'p2', periodNumber: 2, year: 2026 },
    { periodId: 'p3', periodNumber: 3, year: 2026 },
    { periodId: 'p4', periodNumber: 4, year: 2026 },
  ];
  const missed = detectMissedScheduleWindows(periods, new Set(['p1']), 4);
  assert.equal(missed.length, 2);
  assert.equal(missed[0].periodNumber, 2);
  assert.equal(missed[0].executionType, 'CATCH_UP');
});

// 6. AI OCR Suggestion
test('6. AI OCR Suggestion', () => {
  const flightSugg = suggestAccountFromOcr('机票 差旅住宿 报销 600元');
  assert.equal(flightSugg.suggestedAccountCode, '660201');
  assert.equal(flightSugg.externalModelProvider, 'NOT_CONFIGURED');
  assert.equal(flightSugg.mandatoryHumanReview, true);
});

// 7. ActionPlan Risk & Tiered Major Block (Tax, Invoice, Payment)
test('7. ActionPlan Risk & Tiered Major Block (Tax, Invoice, Payment)', () => {
  assert.doesNotThrow(() =>
    validateActionPlanExecution([
      { actionId: 'act-1', command: 'JOURNAL_CREATE_DRAFT', risk: 'R1' },
      { actionId: 'act-2', command: 'JOURNAL_POST', risk: 'R3' },
    ])
  );

  assert.throws(
    () =>
      validateActionPlanExecution([
        { actionId: 'act-3', command: 'TAX_SUBMIT', risk: 'R4' },
      ]),
    /AI_MAJOR_ACTION_BLOCKED/
  );
  assert.throws(
    () =>
      validateActionPlanExecution([
        { actionId: 'act-4', command: 'INVOICE_ISSUE', risk: 'R3' },
      ]),
    /AI_MAJOR_ACTION_BLOCKED/
  );
  assert.throws(
    () =>
      validateActionPlanExecution([
        { actionId: 'act-5', command: 'BANK_PAYMENT_DISBURSEMENT', risk: 'R3' },
      ]),
    /AI_MAJOR_ACTION_BLOCKED/
  );
});

// 8. Offline Lease Duration & Revocation / Expiry
test('8. Offline Lease Duration & Revocation / Expiry', () => {
  assert.doesNotThrow(() => validateOfflineLease(28800)); // 8h
  assert.throws(() => validateOfflineLease(36000), /LEASE_DURATION_EXCEEDED/); // 10h

  assert.doesNotThrow(() =>
    validateReconnection({ status: 'ACTIVE', expiresAt: new Date(Date.now() + 60000).toISOString() })
  );
  assert.throws(
    () => validateReconnection({ status: 'REVOKED', expiresAt: new Date(Date.now() + 60000).toISOString() }),
    /LEASE_REVOKED/
  );
  assert.throws(
    () => validateReconnection({ status: 'EXPIRED', expiresAt: new Date(Date.now() - 60000).toISOString() }),
    /LEASE_EXPIRED/
  );
});

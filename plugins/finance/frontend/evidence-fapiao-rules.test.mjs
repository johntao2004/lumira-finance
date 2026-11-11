import test from 'node:test';
import assert from 'node:assert/strict';
import {
  EvidenceClassification,
  EvidenceCompletenessState,
  ExtendedQuotaBucket,
  GraphNodeType,
  GraphEdgeType,
  EvidenceExceptionType,
  EvidenceExceptionStatus,
  FapiaoDirection,
  FapiaoLifecycleStatus,
  FapiaoType,
  BuyerQualificationStatus,
  IssuanceAppStatus,
  RedNoticeStatus,
  parseCents,
  formatCents,
  formatMoney,
  evaluateCompleteness,
  verifyInvoiceMath,
  compareOcrInvoice,
  calculateAvailableQuota,
  checkExceptionSod,
  isValidFapiaoNumber,
  isValidTaxNumber,
  isRedNoticeExpired,
  validateRedNoticeLimits,
  detectGraphCycle,
  evaluateReconciliation,
} from './evidence-fapiao-rules.ts';

test('EV01: evaluateCompleteness handles standard XML, screenshot only and paper scan storage locations', () => {
  // Standard XML original -> complete & usable
  const res1 = evaluateCompleteness(EvidenceClassification.EINVOICE_XML, false, null);
  assert.equal(res1.isComplete, true);
  assert.equal(res1.state, EvidenceCompletenessState.USABLE);
  assert.equal(res1.missingReasons.length, 0);

  // Mobile screenshot only -> incomplete & pending completion
  const res2 = evaluateCompleteness(EvidenceClassification.EINVOICE_PDF, true, null);
  assert.equal(res2.isComplete, false);
  assert.equal(res2.state, EvidenceCompletenessState.PENDING_COMPLETION);
  assert.ok(res2.missingReasons.includes('SCREENSHOT_ONLY_MISSING_ORIGINAL_XML_OR_OFD'));

  // Paper scan missing physical storage location -> incomplete
  const res3 = evaluateCompleteness(EvidenceClassification.OTHER_PAPER_SCAN, false, '');
  assert.equal(res3.isComplete, false);
  assert.equal(res3.state, EvidenceCompletenessState.PENDING_COMPLETION);
  assert.ok(res3.missingReasons.includes('MISSING_PHYSICAL_PAPER_STORAGE_LOCATION'));
});

test('EV02: verifyInvoiceMath strictly tests 2-decimal arithmetic without IEEE 754 precision issues', () => {
  assert.equal(verifyInvoiceMath('1000.00', '130.00', '1130.00'), true);
  assert.equal(verifyInvoiceMath('0.01', '0.02', '0.03'), true);
  assert.equal(verifyInvoiceMath('1000.00', '120.00', '1130.00'), false);
  assert.equal(verifyInvoiceMath('100.55', '13.07', '113.62'), true);
  assert.equal(verifyInvoiceMath('100.55', '13.07', '113.63'), false);
});

test('EV02: compareOcrInvoice detects field discrepancies and sets taxBureauVerification strictly to NOT_VERIFIED', () => {
  const structured = {
    fapiaoNumber: '24110000000000000001',
    buyerTaxNo: '91110000MA00112233',
    sellerTaxNo: '91310000MA99887766',
    untaxedAmount: '1000.00',
    taxAmount: '130.00',
    totalAmount: '1130.00',
  };

  // 1. Exact match
  const matchResult = compareOcrInvoice({
    structured,
    ocr: {
      fapiaoNumber: '24110000000000000001',
      buyerTaxNo: '91110000MA00112233',
      sellerTaxNo: '91310000MA99887766',
      untaxedAmount: '1000.00',
      taxAmount: '130.00',
      totalAmount: '1130.00',
    },
  });
  assert.equal(matchResult.status, 'VERIFIED_MATCH');
  assert.equal(matchResult.taxBureauVerification, 'NOT_VERIFIED');
  assert.equal(Object.keys(matchResult.discrepancies).length, 0);

  // 2. Discrepancy in tax amount & buyer tax No
  const mismatchResult = compareOcrInvoice({
    structured,
    ocr: {
      fapiaoNumber: '24110000000000000001',
      buyerTaxNo: '91110000MA00119999', // mismatch
      sellerTaxNo: '91310000MA99887766',
      untaxedAmount: '1000.00',
      taxAmount: '120.00', // mismatch
      totalAmount: '1130.00',
    },
  });
  assert.equal(mismatchResult.status, 'MISMATCH_MANUAL_REVIEW');
  assert.equal(mismatchResult.taxBureauVerification, 'NOT_VERIFIED');
  assert.ok(mismatchResult.discrepancies.taxAmount);
  assert.ok(mismatchResult.discrepancies.buyerTaxNo);
});

test('EV03: calculateAvailableQuota enforces capacity >= reserved + committed', () => {
  assert.equal(calculateAvailableQuota('1000.00', '300.00', '200.00'), '500.00');
  assert.equal(calculateAvailableQuota('1000.00', '1000.00', '0.00'), '0.00');
  assert.throws(() => calculateAvailableQuota('1000.00', '600.00', '500.00'), /Quota invariant violated/);
});

test('EV04: checkExceptionSod enforces separation of duties between responsible person and approver', () => {
  assert.equal(checkExceptionSod('USER_RESP_01', 'USER_APPROVER_02'), true);
  assert.equal(checkExceptionSod('USER_SAME_01', 'USER_SAME_01'), false);
  assert.equal(checkExceptionSod('', 'USER_APPROVER_02'), false);
});

test('FP01: isValidFapiaoNumber validates 20-digit and 8-digit formats', () => {
  assert.equal(isValidFapiaoNumber('24110000000000000001'), true);
  assert.equal(isValidFapiaoNumber('12345678'), true);
  assert.equal(isValidFapiaoNumber('12345'), false);
  assert.equal(isValidFapiaoNumber('abc12345678901234567'), false);
});

test('FP01: isValidTaxNumber validates 18-char USCC and 15/20-digit tax IDs', () => {
  assert.equal(isValidTaxNumber('91110000MA00112233'), true); // 18-char USCC
  assert.equal(isValidTaxNumber('110101123456789'), true);     // 15-digit
  assert.equal(isValidTaxNumber('SHORT'), false);
});

test('FP03: isRedNoticeExpired enforces strict 72-hour expiration window', () => {
  const registeredAt = '2026-11-10T08:00:00Z';
  const t71h = '2026-11-13T07:00:00Z'; // 71 hours later -> valid
  const t73h = '2026-11-13T09:00:00Z'; // 73 hours later -> expired

  assert.equal(isRedNoticeExpired(registeredAt, t71h), false);
  assert.equal(isRedNoticeExpired(registeredAt, t73h), true);
});

test('FP03: validateRedNoticeLimits checks negative signs and blue fapiao balance bounds', () => {
  const blueUntaxed = '10000.00';
  const blueTax = '1300.00';

  // Valid partial red notice
  const validPartial = validateRedNoticeLimits(blueUntaxed, blueTax, '-5000.00', '-650.00');
  assert.equal(validPartial.isValid, true);

  // Positive values rejected
  const positiveReject = validateRedNoticeLimits(blueUntaxed, blueTax, '5000.00', '650.00');
  assert.equal(positiveReject.isValid, false);
  assert.match(positiveReject.error, /负数/);

  // Exceeding blue fapiao bounds rejected
  const overBoundReject = validateRedNoticeLimits(blueUntaxed, blueTax, '-12000.00', '-1560.00');
  assert.equal(overBoundReject.isValid, false);
  assert.match(overBoundReject.error, /超限/);

  // Cumulative limit: existing -8000.00 means remaining is only 2000.00; requesting -3000.00 rejected
  const cumulativeExceeded = validateRedNoticeLimits(blueUntaxed, blueTax, '-3000.00', '-390.00', '-8000.00', '-1040.00');
  assert.equal(cumulativeExceeded.isValid, false);
  assert.match(cumulativeExceeded.error, /超限/);

  // Cumulative limit: requesting -1500.00 within remaining 2000.00 passes
  const cumulativeValid = validateRedNoticeLimits(blueUntaxed, blueTax, '-1500.00', '-195.00', '-8000.00', '-1040.00');
  assert.equal(cumulativeValid.isValid, true);

  // Non-positive blue invoice rejected
  const nonPositiveBlue = validateRedNoticeLimits('-1000.00', '-130.00', '-500.00', '-65.00');
  assert.equal(nonPositiveBlue.isValid, false);
  assert.match(nonPositiveBlue.error, /正数/);
});

test('EV04: detectGraphCycle detects directed cycles and permits acyclic evidence networks', () => {
  const edges = [
    { sourceNodeId: 'PO_1', targetNodeId: 'CONTRACT_1' },
    { sourceNodeId: 'ACCEPT_1', targetNodeId: 'PO_1' },
    { sourceNodeId: 'ACCEPT_1', targetNodeId: 'INVOICE_1' },
    { sourceNodeId: 'INVOICE_1', targetNodeId: 'SLIP_1' },
  ];

  // Self loop: INVOICE_1 -> INVOICE_1
  assert.equal(detectGraphCycle(edges, 'INVOICE_1', 'INVOICE_1'), true);

  // Directed cycle: CONTRACT_1 -> ACCEPT_1 (since ACCEPT_1 -> PO_1 -> CONTRACT_1)
  assert.equal(detectGraphCycle(edges, 'CONTRACT_1', 'ACCEPT_1'), true);

  // Directed cycle: SLIP_1 -> ACCEPT_1 (since ACCEPT_1 -> INVOICE_1 -> SLIP_1)
  assert.equal(detectGraphCycle(edges, 'SLIP_1', 'ACCEPT_1'), true);

  // Valid forward link without cycle: SLIP_1 -> JOURNAL_1
  assert.equal(detectGraphCycle(edges, 'SLIP_1', 'JOURNAL_1'), false);
});

test('FP01: evaluateReconciliation correctly maps ledger journal and tax worksheet IDs', () => {
  const unreconciled = evaluateReconciliation(null, null);
  assert.equal(unreconciled.reconciliationStatus, 'UNRECONCILED');
  assert.equal(unreconciled.lifecycleStatus, FapiaoLifecycleStatus.UNVERIFIED_LOCAL);

  const ledgerReconciled = evaluateReconciliation('JRN-001', null);
  assert.equal(ledgerReconciled.reconciliationStatus, 'RECONCILED');
  assert.equal(ledgerReconciled.lifecycleStatus, FapiaoLifecycleStatus.MATCHED);

  const taxReconciled = evaluateReconciliation(null, 'TAX-WS-001');
  assert.equal(taxReconciled.reconciliationStatus, 'RECONCILED');
  assert.equal(taxReconciled.lifecycleStatus, FapiaoLifecycleStatus.MATCHED);
});

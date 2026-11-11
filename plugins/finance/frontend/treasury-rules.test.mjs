import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  validateCnyAmount,
  parseCny,
  formatCny,
  canActivateAccount,
  canFreezeAccount,
  canUnfreezeAccount,
  canCloseAccount,
  formatTreasuryOrderNo,
  validateTransferPairing,
  canApproveInstruction,
  canDispatchInstruction,
  canSettleInstruction,
  canFailInstruction,
  reconcileSourceTotals,
  calculateBankReconciliation,
  calculateAgingDays,
  isOverdueWarning,
  calculateCashForecast,
  canConfirmReconciliation,
  canApproveNumberingPolicy
} from './treasury-rules.ts';

test('MD04: CNY amount validation and formatting', () => {
  assert.equal(validateCnyAmount('100.50'), true);
  assert.equal(validateCnyAmount('0.01'), true);
  assert.equal(validateCnyAmount('5000'), true);
  assert.equal(validateCnyAmount('100.555'), false); // reject >2 decimals
  assert.equal(validateCnyAmount('xyz'), false);

  assert.equal(parseCny('1250.80'), 1250.8);
  assert.equal(formatCny(1250.8), '1250.80');
});

test('MD04: Account lifecycle predicates and zero balance check on closing', () => {
  assert.equal(canActivateAccount('PENDING_REVIEW'), true);
  assert.equal(canActivateAccount('FROZEN'), true);
  assert.equal(canActivateAccount('ACTIVE'), false);

  assert.equal(canFreezeAccount('ACTIVE'), true);
  assert.equal(canFreezeAccount('FROZEN'), false);

  assert.equal(canUnfreezeAccount('FROZEN'), true);

  // Close requires zero balance
  assert.equal(canCloseAccount('ACTIVE', 0), true);
  assert.equal(canCloseAccount('ACTIVE', 100), false);
  assert.equal(canCloseAccount('FROZEN', 0), true);
  assert.equal(canCloseAccount('PENDING_REVIEW', 0), false);
});

test('MD04: Numbering policy formatting', () => {
  assert.equal(formatTreasuryOrderNo('PAY-', '202611', 1), 'PAY-202611-0001');
  assert.equal(formatTreasuryOrderNo('REC-', '202611', 42), 'REC-202611-0042');
  assert.throws(() => formatTreasuryOrderNo('PAY-', '202611', 0), /序号必须大于0/);
});

test('TR01: Transfer pairing validation', () => {
  const acct1 = { id: 'a1', tenantId: 't1', bookId: 'b1', status: 'ACTIVE' };
  const acct2 = { id: 'a2', tenantId: 't1', bookId: 'b1', status: 'ACTIVE' };
  const acctCross = { id: 'a3', tenantId: 't1', bookId: 'b2', status: 'ACTIVE' };
  const acctFrozen = { id: 'a4', tenantId: 't1', bookId: 'b1', status: 'FROZEN' };

  // Valid
  assert.deepEqual(validateTransferPairing(acct1, acct2, 5000, 15), { valid: true });

  // Same account error
  const r1 = validateTransferPairing(acct1, acct1, 5000);
  assert.equal(r1.valid, false);
  assert.match(r1.error, /两端账户不能相同/);

  // Cross book error
  const r2 = validateTransferPairing(acct1, acctCross, 5000);
  assert.equal(r2.valid, false);
  assert.match(r2.error, /跨法人不能使用同账套内部转账捷径/);

  // Frozen account error
  const r3 = validateTransferPairing(acct1, acctFrozen, 5000);
  assert.equal(r3.valid, false);
  assert.match(r3.error, /划入账户非正常激活状态/);
});

test('TR02: Payment instruction lifecycle with strict UNKNOWN hold and SHA-256 receipt', () => {
  const makerId = 'user-1';
  const approverId = 'user-2';
  const verifierId = 'user-3';
  const validSha = 'b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9';

  // Approver must not be maker
  assert.equal(canApproveInstruction('DRAFT', makerId, makerId), false);
  assert.equal(canApproveInstruction('DRAFT', makerId, approverId), true);
  assert.equal(canApproveInstruction('APPROVED', makerId, approverId), false);

  assert.equal(canDispatchInstruction('APPROVED'), true);
  assert.equal(canDispatchInstruction('DRAFT'), false);

  // Settle requires DISPATCHED_UNKNOWN (Strict Hold) and valid SHA-256
  assert.equal(canSettleInstruction('APPROVED', makerId, verifierId, validSha), false);
  assert.equal(canSettleInstruction('DISPATCHED_UNKNOWN', makerId, makerId, validSha), false); // self-review fail
  assert.equal(canSettleInstruction('DISPATCHED_UNKNOWN', makerId, verifierId, 'invalid-sha'), false);
  assert.equal(canSettleInstruction('DISPATCHED_UNKNOWN', makerId, verifierId, validSha), true);

  // Fail
  assert.equal(canFailInstruction('DISPATCHED_UNKNOWN', makerId, verifierId, 'Account closed'), true);
  assert.equal(canFailInstruction('DISPATCHED_UNKNOWN', makerId, verifierId, ''), false);
});

test('TR03: Bank statement source totals reconciliation', () => {
  const lines = [
    { direction: 'CREDIT', amount: 15000 },
    { direction: 'DEBIT', amount: 5000 }
  ];

  // opening 50000 + 15000 - 5000 = closing 60000
  const ok = reconcileSourceTotals(50000, 60000, 15000, 5000, 2, lines);
  assert.equal(ok.valid, true);

  // Mismatched closing
  const failClosing = reconcileSourceTotals(50000, 65000, 15000, 5000, 2, lines);
  assert.equal(failClosing.valid, false);
  assert.match(failClosing.error, /期初期末余额对账不平/);

  // Mismatched line count
  const failCount = reconcileSourceTotals(50000, 60000, 15000, 5000, 3, lines);
  assert.equal(failCount.valid, false);
  assert.match(failCount.error, /明细行数/);
});

test('TR04: Bank balance reconciliation 4-item formula and aging', () => {
  const items = [
    { itemType: 'ENTERPRISE_RECORDED_BANK_UNRECORDED', amount: 20000 },
    { itemType: 'ENTERPRISE_PAID_BANK_UNPAID', amount: 10000 },
    { itemType: 'BANK_CREDITED_ENTERPRISE_UNRECORDED', amount: 18000, supplementaryEvidenceId: 'ev-1' },
    { itemType: 'BANK_DEBITED_ENTERPRISE_UNRECORDED', amount: 3000 }
  ];

  const res = calculateBankReconciliation(100000, 95000, items);
  assert.equal(res.adjustedBankBalance, 110000);
  assert.equal(res.adjustedLedgerBalance, 110000);
  assert.equal(res.isBalanced, true);

  // Missing supplementary evidence for BANK_CREDITED_ENTERPRISE_UNRECORDED
  const invalidItems = [
    { itemType: 'BANK_CREDITED_ENTERPRISE_UNRECORDED', amount: 5000 } // no evidenceId
  ];
  assert.throws(() => calculateBankReconciliation(100000, 95000, invalidItems), /必须关联补录依据原件ID/);

  // Aging days and warning
  const agingRecent = calculateAgingDays('2026-11-28', '2026-11-30');
  assert.equal(agingRecent, 2);
  assert.equal(isOverdueWarning(agingRecent), false);

  const agingOverdue = calculateAgingDays('2026-10-15', '2026-11-30');
  assert.equal(agingOverdue, 46);
  assert.equal(isOverdueWarning(agingOverdue), true);
});

test('TR04: Cash forecast calculation', () => {
  const open = 100000;
  const inflow = 50000;
  const outflow = 20000;
  const close = calculateCashForecast(open, inflow, outflow);
  assert.equal(close, 130000);
});

test('TR04 & MD04: Reconciliation confirmation and Numbering policy approval SoD', () => {
  // Reconciliation confirmation SoD
  assert.equal(canConfirmReconciliation('DRAFT', true, 'user-maker', 'user-reviewer'), true);
  assert.equal(canConfirmReconciliation('DRAFT', true, 'user-maker', 'user-maker'), false); // self-review rejected
  assert.equal(canConfirmReconciliation('DRAFT', false, 'user-maker', 'user-reviewer'), false); // unbalanced rejected
  assert.equal(canConfirmReconciliation('CONFIRMED', true, 'user-maker', 'user-reviewer'), false);

  // Numbering policy approval SoD
  assert.equal(canApproveNumberingPolicy('user-maker', 'user-reviewer'), true);
  assert.equal(canApproveNumberingPolicy('user-maker', 'user-maker'), false); // self-approval rejected
});


/**
 * Frontend domain rules and arithmetic validations for Treasury (MD04, TR01, TR02, TR03, TR04).
 */

export const CNY_REGEX = /^-?\d+(\.\d{1,2})?$/;

export function validateCnyAmount(amountStr: string): boolean {
  if (!amountStr || typeof amountStr !== 'string') return false;
  return CNY_REGEX.test(amountStr.trim());
}

export function parseCny(amountStr: string): number {
  if (!validateCnyAmount(amountStr)) {
    throw new Error(`必须是合规的人民币金额格式（最多两位小数）: ${amountStr}`);
  }
  return Math.round(parseFloat(amountStr.trim()) * 100) / 100;
}

export function formatCny(amount: number): string {
  return (Math.round(amount * 100) / 100).toFixed(2);
}

// ==========================================
// MD04: Account State Evaluation
// ==========================================

export function canActivateAccount(status: string): boolean {
  return status === 'PENDING_REVIEW' || status === 'FROZEN';
}

export function canFreezeAccount(status: string): boolean {
  return status === 'ACTIVE';
}

export function canUnfreezeAccount(status: string): boolean {
  return status === 'FROZEN';
}

export function canCloseAccount(status: string, balance: number): boolean {
  return (status === 'ACTIVE' || status === 'FROZEN') && Math.abs(balance) < 0.0000001;
}

export function formatTreasuryOrderNo(prefix: string, pattern: string, seq: number): string {
  if (seq <= 0) throw new Error('序号必须大于0');
  const pad = String(seq).padStart(4, '0');
  return `${prefix || 'TR-'}${pattern}-${pad}`;
}

// ==========================================
// TR01: Transfer Pairing
// ==========================================

export interface AccountRef {
  id: string;
  tenantId: string;
  bookId: string;
  status: string;
}

export function validateTransferPairing(
  source: AccountRef,
  target: AccountRef,
  amount: number,
  feeAmount: number = 0
): { valid: boolean; error?: string } {
  if (source.id === target.id) {
    return { valid: false, error: '内部划转两端账户不能相同' };
  }
  if (source.tenantId !== target.tenantId || source.bookId !== target.bookId) {
    return { valid: false, error: '跨法人不能使用同账套内部转账捷径：划转两端必须属于同一租户与账套' };
  }
  if (source.status !== 'ACTIVE') {
    return { valid: false, error: `划出账户非正常激活状态: ${source.status}` };
  }
  if (target.status !== 'ACTIVE') {
    return { valid: false, error: `划入账户非正常激活状态: ${target.status}` };
  }
  if (amount <= 0) {
    return { valid: false, error: '划转金额必须大于零' };
  }
  if (feeAmount < 0) {
    return { valid: false, error: '手续费不能为负数' };
  }
  return { valid: true };
}

// ==========================================
// TR02: Payment Instruction Lifecycle
// ==========================================

export function canApproveInstruction(status: string, makerId: string, approverId: string): boolean {
  if (status !== 'DRAFT') return false;
  return makerId !== approverId; // Independent natural-person SoD
}

export function canDispatchInstruction(status: string): boolean {
  return status === 'APPROVED';
}

export function canSettleInstruction(status: string, makerId: string, verifierId: string, receiptSha256: string): boolean {
  if (status !== 'DISPATCHED_UNKNOWN') return false; // Strict UNKNOWN hold
  if (makerId === verifierId) return false;
  if (!receiptSha256 || !/^[a-fA-F0-9]{64}$/.test(receiptSha256)) return false;
  return true;
}

export function canFailInstruction(status: string, makerId: string, verifierId: string, reason: string): boolean {
  if (status !== 'DISPATCHED_UNKNOWN') return false;
  if (makerId === verifierId) return false;
  return Boolean(reason && reason.trim());
}

// ==========================================
// TR03: Bank Statement Totals Reconciliation
// ==========================================

export interface StatementLineItem {
  direction: 'CREDIT' | 'DEBIT';
  amount: number;
}

export function reconcileSourceTotals(
  opening: number,
  closing: number,
  totalCredit: number,
  totalDebit: number,
  expectedCount: number,
  lines: StatementLineItem[]
): { valid: boolean; error?: string } {
  if (lines.length !== expectedCount) {
    return { valid: false, error: `明细行数 (${lines.length}) 与申报记录数 (${expectedCount}) 不一致` };
  }
  let sumCredit = 0;
  let sumDebit = 0;
  for (const l of lines) {
    if (l.direction === 'CREDIT') sumCredit += l.amount;
    else if (l.direction === 'DEBIT') sumDebit += l.amount;
  }
  sumCredit = Math.round(sumCredit * 100) / 100;
  sumDebit = Math.round(sumDebit * 100) / 100;

  if (Math.abs(sumCredit - totalCredit) > 0.001) {
    return { valid: false, error: `解析贷方收入合计 (${sumCredit.toFixed(2)}) 与文件总额 (${totalCredit.toFixed(2)}) 不符` };
  }
  if (Math.abs(sumDebit - totalDebit) > 0.001) {
    return { valid: false, error: `解析借方支出合计 (${sumDebit.toFixed(2)}) 与文件总额 (${totalDebit.toFixed(2)}) 不符` };
  }
  const computedClosing = Math.round((opening + totalCredit - totalDebit) * 100) / 100;
  if (Math.abs(computedClosing - closing) > 0.001) {
    return {
      valid: false,
      error: `期初期末余额对账不平：期初 ${opening.toFixed(2)} + 贷方 ${totalCredit.toFixed(2)} - 借方 ${totalDebit.toFixed(2)} = ${computedClosing.toFixed(2)} != 期末 ${closing.toFixed(2)}`
    };
  }
  return { valid: true };
}

// ==========================================
// TR04: Reconciliation & Aging
// ==========================================

export interface UnreachedItemCalc {
  itemType:
    | 'ENTERPRISE_RECORDED_BANK_UNRECORDED'
    | 'ENTERPRISE_PAID_BANK_UNPAID'
    | 'BANK_CREDITED_ENTERPRISE_UNRECORDED'
    | 'BANK_DEBITED_ENTERPRISE_UNRECORDED';
  amount: number;
  supplementaryEvidenceId?: string;
}

export function calculateBankReconciliation(
  bankStatementBalance: number,
  ledgerBalance: number,
  items: UnreachedItemCalc[]
): {
  adjustedBankBalance: number;
  adjustedLedgerBalance: number;
  isBalanced: boolean;
  entRecBankUnrec: number;
  entPaidBankUnpaid: number;
  bankCredEntUnrec: number;
  bankDebEntUnrec: number;
} {
  let entRecBankUnrec = 0;
  let entPaidBankUnpaid = 0;
  let bankCredEntUnrec = 0;
  let bankDebEntUnrec = 0;

  for (const item of items) {
    if (item.amount <= 0) throw new Error('未达金额必须大于零');
    switch (item.itemType) {
      case 'ENTERPRISE_RECORDED_BANK_UNRECORDED':
        entRecBankUnrec += item.amount;
        break;
      case 'ENTERPRISE_PAID_BANK_UNPAID':
        entPaidBankUnpaid += item.amount;
        break;
      case 'BANK_CREDITED_ENTERPRISE_UNRECORDED':
        if (!item.supplementaryEvidenceId) {
          throw new Error('银行已收企业未收项目必须关联补录依据原件ID');
        }
        bankCredEntUnrec += item.amount;
        break;
      case 'BANK_DEBITED_ENTERPRISE_UNRECORDED':
        bankDebEntUnrec += item.amount;
        break;
    }
  }

  const adjustedBankBalance = Math.round((bankStatementBalance + entRecBankUnrec - entPaidBankUnpaid) * 100) / 100;
  const adjustedLedgerBalance = Math.round((ledgerBalance + bankCredEntUnrec - bankDebEntUnrec) * 100) / 100;
  const isBalanced = Math.abs(adjustedBankBalance - adjustedLedgerBalance) < 0.001;

  return {
    adjustedBankBalance,
    adjustedLedgerBalance,
    isBalanced,
    entRecBankUnrec,
    entPaidBankUnpaid,
    bankCredEntUnrec,
    bankDebEntUnrec
  };
}

export function calculateAgingDays(recordedDateStr: string, cutoffDateStr: string): number {
  const tRecorded = new Date(recordedDateStr).getTime();
  const tCutoff = new Date(cutoffDateStr).getTime();
  const diffDays = Math.floor((tCutoff - tRecorded) / (1000 * 60 * 60 * 24));
  return Math.max(0, diffDays);
}

export function isOverdueWarning(agingDays: number): boolean {
  return agingDays > 30; // 超过30天触发长期未达预警
}

export function calculateCashForecast(openingCash: number, inflow: number, outflow: number): number {
  return Math.round((openingCash + inflow - outflow) * 100) / 100;
}

export function canConfirmReconciliation(
  status: string,
  isBalanced: boolean,
  preparerId: string,
  reviewerId: string
): boolean {
  if (status !== 'DRAFT') return false;
  if (!isBalanced) return false;
  if (!reviewerId || preparerId === reviewerId) return false;
  return true;
}

export function canApproveNumberingPolicy(makerId: string, reviewerId: string): boolean {
  if (!reviewerId || makerId === reviewerId) return false;
  return true;
}


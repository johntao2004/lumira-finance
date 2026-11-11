/**
 * Pure domain rules and calculation functions for W04 Evidence & Fapiao.
 * Zero external runtime dependencies, compatible with Node 24 and browser.
 */

export const EvidenceClassification = {
  EINVOICE_XML: 'EINVOICE_XML',
  EINVOICE_OFD: 'EINVOICE_OFD',
  EINVOICE_PDF: 'EINVOICE_PDF',
  CONTRACT_SCAN: 'CONTRACT_SCAN',
  BANK_SLIP: 'BANK_SLIP',
  ACCEPTANCE_NOTE: 'ACCEPTANCE_NOTE',
  OTHER_PAPER_SCAN: 'OTHER_PAPER_SCAN',
} as const;
export type EvidenceClassification = typeof EvidenceClassification[keyof typeof EvidenceClassification];

export const EvidenceCompletenessState = {
  RECEIVED: 'RECEIVED',
  PENDING_VERIFICATION: 'PENDING_VERIFICATION',
  PENDING_COMPLETION: 'PENDING_COMPLETION',
  USABLE: 'USABLE',
  REPLACED: 'REPLACED',
} as const;
export type EvidenceCompletenessState = typeof EvidenceCompletenessState[keyof typeof EvidenceCompletenessState];

export const ExtendedQuotaBucket = {
  SETTLEMENT_QUOTA: 'SETTLEMENT_QUOTA',
  TAX_DEDUCTION_QUOTA: 'TAX_DEDUCTION_QUOTA',
  REIMBURSEMENT_QUOTA: 'REIMBURSEMENT_QUOTA',
} as const;
export type ExtendedQuotaBucket = typeof ExtendedQuotaBucket[keyof typeof ExtendedQuotaBucket];

export const GraphNodeType = {
  CONTRACT: 'CONTRACT',
  PURCHASE_ORDER: 'PURCHASE_ORDER',
  ACCEPTANCE_NOTE: 'ACCEPTANCE_NOTE',
  INVOICE: 'INVOICE',
  BANK_SLIP: 'BANK_SLIP',
  JOURNAL_ENTRY: 'JOURNAL_ENTRY',
} as const;
export type GraphNodeType = typeof GraphNodeType[keyof typeof GraphNodeType];

export const GraphEdgeType = {
  FULFILLS: 'FULFILLS',
  ACCEPTS: 'ACCEPTS',
  BILLED_BY: 'BILLED_BY',
  PAID_BY: 'PAID_BY',
  POSTED_BY: 'POSTED_BY',
} as const;
export type GraphEdgeType = typeof GraphEdgeType[keyof typeof GraphEdgeType];

export const EvidenceExceptionType = {
  MISSING_INVOICE_PROVISIONAL_ESTIMATE: 'MISSING_INVOICE_PROVISIONAL_ESTIMATE',
  MISSING_ACCEPTANCE_NOTE: 'MISSING_ACCEPTANCE_NOTE',
  MISSING_CONTRACT: 'MISSING_CONTRACT',
  OCR_TAX_MISMATCH: 'OCR_TAX_MISMATCH',
  TAX_DEDUCTION_PENDING_AUDIT: 'TAX_DEDUCTION_PENDING_AUDIT',
} as const;
export type EvidenceExceptionType = typeof EvidenceExceptionType[keyof typeof EvidenceExceptionType];

export const EvidenceExceptionStatus = {
  REGISTERED: 'REGISTERED',
  RECTIFYING: 'RECTIFYING',
  UNDER_REVIEW: 'UNDER_REVIEW',
  APPROVED_EXCEPTION: 'APPROVED_EXCEPTION',
  CLOSED: 'CLOSED',
} as const;
export type EvidenceExceptionStatus = typeof EvidenceExceptionStatus[keyof typeof EvidenceExceptionStatus];

export const FapiaoDirection = {
  INPUT: 'INPUT',
  OUTPUT: 'OUTPUT',
} as const;
export type FapiaoDirection = typeof FapiaoDirection[keyof typeof FapiaoDirection];

export const FapiaoLifecycleStatus = {
  UNVERIFIED_LOCAL: 'UNVERIFIED_LOCAL',
  MATCHED: 'MATCHED',
  CERTIFIED_DECLARED: 'CERTIFIED_DECLARED',
  TRANSFERRED_OUT: 'TRANSFERRED_OUT',
  VOIDED: 'VOIDED',
  RED_OFFSET: 'RED_OFFSET',
} as const;
export type FapiaoLifecycleStatus = typeof FapiaoLifecycleStatus[keyof typeof FapiaoLifecycleStatus];

export const FapiaoType = {
  SPECIAL_VAT: 'SPECIAL_VAT',
  GENERAL_VAT: 'GENERAL_VAT',
  ELECTRONIC_SPECIAL: 'ELECTRONIC_SPECIAL',
  ELECTRONIC_GENERAL: 'ELECTRONIC_GENERAL',
  TOLL_INVOICE: 'TOLL_INVOICE',
} as const;
export type FapiaoType = typeof FapiaoType[keyof typeof FapiaoType];

export const BuyerQualificationStatus = {
  PENDING_REVIEW: 'PENDING_REVIEW',
  QUALIFIED: 'QUALIFIED',
  DISQUALIFIED: 'DISQUALIFIED',
} as const;
export type BuyerQualificationStatus = typeof BuyerQualificationStatus[keyof typeof BuyerQualificationStatus];

export const IssuanceAppStatus = {
  DRAFT: 'DRAFT',
  SUBMITTED: 'SUBMITTED',
  APPROVED: 'APPROVED',
  WAITING_CHANNEL: 'WAITING_CHANNEL',
  ISSUED_MANUAL_CONFIRMED: 'ISSUED_MANUAL_CONFIRMED',
  REJECTED: 'REJECTED',
  CANCELLED: 'CANCELLED',
} as const;
export type IssuanceAppStatus = typeof IssuanceAppStatus[keyof typeof IssuanceAppStatus];

export const RedNoticeStatus = {
  DRAFT: 'DRAFT',
  WAITING_CONFIRMATION: 'WAITING_CONFIRMATION',
  CONFIRMED: 'CONFIRMED',
  EXPIRED: 'EXPIRED',
  CANCELLED: 'CANCELLED',
  RED_INVOICE_ISSUED: 'RED_INVOICE_ISSUED',
} as const;
export type RedNoticeStatus = typeof RedNoticeStatus[keyof typeof RedNoticeStatus];

// Decimal helper to prevent JS floating point precision issues
export function parseCents(amountStr: string | number): bigint {
  const s = String(amountStr).trim();
  const negative = s.startsWith('-');
  const clean = negative ? s.slice(1) : s;
  const parts = clean.split('.');
  const whole = BigInt(parts[0] || '0');
  const fraction = (parts[1] || '').padEnd(2, '0').slice(0, 2);
  const cents = whole * 100n + BigInt(fraction);
  return negative ? -cents : cents;
}

export function formatCents(cents: bigint): string {
  const negative = cents < 0n;
  const abs = negative ? -cents : cents;
  const whole = abs / 100n;
  const frac = abs % 100n;
  const fracStr = frac.toString().padStart(2, '0');
  return `${negative ? '-' : ''}${whole}.${fracStr}`;
}

export function formatMoney(amount: string | number): string {
  return formatCents(parseCents(amount));
}

// EV01: Completeness state machine calculation
export function evaluateCompleteness(
  classification: EvidenceClassification,
  isScreenshotOnly: boolean,
  paperStorageLocation?: string | null
): {
  isComplete: boolean;
  state: EvidenceCompletenessState;
  missingReasons: string[];
} {
  const missingReasons: string[] = [];
  if (isScreenshotOnly) {
    missingReasons.push('SCREENSHOT_ONLY_MISSING_ORIGINAL_XML_OR_OFD');
  }
  if (classification === EvidenceClassification.OTHER_PAPER_SCAN && (!paperStorageLocation || !paperStorageLocation.trim())) {
    missingReasons.push('MISSING_PHYSICAL_PAPER_STORAGE_LOCATION');
  }

  const isComplete = missingReasons.length === 0 && classification !== EvidenceClassification.OTHER_PAPER_SCAN;
  const state = isComplete ? EvidenceCompletenessState.USABLE : EvidenceCompletenessState.PENDING_COMPLETION;
  return { isComplete, state, missingReasons };
}

// EV02: Decimal math verification
export function verifyInvoiceMath(untaxed: string, tax: string, total: string): boolean {
  const u = parseCents(untaxed);
  const t = parseCents(tax);
  const tot = parseCents(total);
  return u + t === tot;
}

// EV02: OCR cross-check comparator
export interface OcrComparisonInput {
  structured: {
    fapiaoNumber: string;
    buyerTaxNo: string;
    sellerTaxNo: string;
    untaxedAmount: string;
    taxAmount: string;
    totalAmount: string;
  };
  ocr: {
    fapiaoNumber?: string;
    buyerTaxNo?: string;
    sellerTaxNo?: string;
    untaxedAmount?: string;
    taxAmount?: string;
    totalAmount?: string;
  };
}

export interface OcrComparisonResult {
  status: 'VERIFIED_MATCH' | 'MISMATCH_MANUAL_REVIEW';
  taxBureauVerification: 'NOT_VERIFIED';
  discrepancies: Record<string, { structured: string; ocr: string; message: string }>;
}

export function compareOcrInvoice(input: OcrComparisonInput): OcrComparisonResult {
  const discrepancies: Record<string, { structured: string; ocr: string; message: string }> = {};

  if (input.ocr.fapiaoNumber && input.ocr.fapiaoNumber.trim() !== input.structured.fapiaoNumber.trim()) {
    discrepancies.fapiaoNumber = {
      structured: input.structured.fapiaoNumber,
      ocr: input.ocr.fapiaoNumber.trim(),
      message: '发票号码不匹配',
    };
  }

  if (input.ocr.buyerTaxNo && input.ocr.buyerTaxNo.trim() !== input.structured.buyerTaxNo.trim()) {
    discrepancies.buyerTaxNo = {
      structured: input.structured.buyerTaxNo,
      ocr: input.ocr.buyerTaxNo.trim(),
      message: '购买方纳税人识别号不匹配',
    };
  }

  if (input.ocr.sellerTaxNo && input.ocr.sellerTaxNo.trim() !== input.structured.sellerTaxNo.trim()) {
    discrepancies.sellerTaxNo = {
      structured: input.structured.sellerTaxNo,
      ocr: input.ocr.sellerTaxNo.trim(),
      message: '销售方纳税人识别号不匹配',
    };
  }

  if (input.ocr.totalAmount) {
    const sTot = parseCents(input.structured.totalAmount);
    const oTot = parseCents(input.ocr.totalAmount);
    if (sTot !== oTot) {
      discrepancies.totalAmount = {
        structured: formatCents(sTot),
        ocr: formatCents(oTot),
        message: '价税合计存在差异',
      };
    }
  }

  if (input.ocr.taxAmount) {
    const sTax = parseCents(input.structured.taxAmount);
    const oTax = parseCents(input.ocr.taxAmount);
    if (sTax !== oTax) {
      discrepancies.taxAmount = {
        structured: formatCents(sTax),
        ocr: formatCents(oTax),
        message: '税额存在差异',
      };
    }
  }

  const isMatch = Object.keys(discrepancies).length === 0;
  return {
    status: isMatch ? 'VERIFIED_MATCH' : 'MISMATCH_MANUAL_REVIEW',
    taxBureauVerification: 'NOT_VERIFIED', // Rule: Never claim live government verification
    discrepancies,
  };
}

// EV03: Quota available balance calculation
export function calculateAvailableQuota(capacity: string, reserved: string, committed: string): string {
  const cap = parseCents(capacity);
  const res = parseCents(reserved);
  const com = parseCents(committed);
  const avail = cap - res - com;
  if (avail < 0n) throw new Error('Quota invariant violated: reserved + committed > capacity');
  return formatCents(avail);
}

// EV04: SoD check for exception approval
export function checkExceptionSod(responsiblePersonId: string, approverPersonId: string): boolean {
  if (!responsiblePersonId || !approverPersonId) return false;
  return responsiblePersonId.trim() !== approverPersonId.trim();
}

// FP01: Validate fapiao number
export function isValidFapiaoNumber(num: string): boolean {
  if (!num) return false;
  const clean = num.trim();
  // 20-digit fully digital invoice or 8-digit legacy invoice
  return /^\d{8}$/.test(clean) || /^\d{20}$/.test(clean);
}

// FP01: Validate Chinese Unified Social Credit Code or Tax ID
export function isValidTaxNumber(taxNo: string): boolean {
  if (!taxNo) return false;
  const clean = taxNo.trim().toUpperCase();
  // 18-character USCC or 15/20-digit legacy tax number
  return /^[0-9A-Z]{15,20}$/.test(clean);
}

// FP03: Red notice 72-hour expiration check
export function isRedNoticeExpired(registeredAtIso: string, currentTimeIso: string): boolean {
  const registered = new Date(registeredAtIso).getTime();
  const current = new Date(currentTimeIso).getTime();
  const diffHours = (current - registered) / (1000 * 3600);
  return diffHours > 72;
}

// FP03: Validate red notice limits against original blue fapiao and cumulative balances
export function validateRedNoticeLimits(
  blueUntaxed: string,
  blueTax: string,
  redUntaxed: string,
  redTax: string,
  existingRedUntaxed: string = '0.00',
  existingRedTax: string = '0.00'
): { isValid: boolean; error?: string } {
  const bU = parseCents(blueUntaxed);
  const bT = parseCents(blueTax);
  const rU = parseCents(redUntaxed);
  const rT = parseCents(redTax);
  const exU = parseCents(existingRedUntaxed);
  const exT = parseCents(existingRedTax);

  if (bU <= 0n) {
    return { isValid: false, error: '原始蓝票不含税金额必须为正数' };
  }

  if (rU >= 0n || rT > 0n) {
    return { isValid: false, error: '红字发票金额必须为负数' };
  }

  const absRU = -rU;
  const absRT = -rT;
  const absExU = exU < 0n ? -exU : exU;
  const absExT = exT < 0n ? -exT : exT;

  const remU = bU - absExU;
  const remT = bT - absExT;

  if (absRU > remU) {
    return { isValid: false, error: `红字不含税金额超限: ${formatCents(absRU)} > 蓝票剩余 ${formatCents(remU)}` };
  }
  if (absRT > remT) {
    return { isValid: false, error: `红字税额超限: ${formatCents(absRT)} > 蓝票税额 ${formatCents(remT)}` };
  }

  return { isValid: true };
}

// EV04: Directed cycle detection in business evidence graph
export function detectGraphCycle(
  existingEdges: Array<{ sourceNodeId: string; targetNodeId: string }>,
  newSourceId: string,
  newTargetId: string
): boolean {
  if (newSourceId === newTargetId) return true;
  const adj = new Map<string, string[]>();
  for (const e of existingEdges) {
    if (!adj.has(e.sourceNodeId)) adj.set(e.sourceNodeId, []);
    adj.get(e.sourceNodeId)!.push(e.targetNodeId);
  }

  const visited = new Set<string>();
  const queue = [newTargetId];
  while (queue.length > 0) {
    const curr = queue.shift()!;
    if (curr === newSourceId) return true;
    if (!visited.has(curr)) {
      visited.add(curr);
      const neighbors = adj.get(curr) || [];
      for (const n of neighbors) {
        if (!visited.has(n)) queue.push(n);
      }
    }
  }
  return false;
}

// FP01: Ledger & tax worksheet reconciliation status evaluator
export function evaluateReconciliation(
  ledgerJournalId?: string | null,
  taxWorksheetId?: string | null
): {
  reconciliationStatus: 'UNRECONCILED' | 'RECONCILED';
  lifecycleStatus: FapiaoLifecycleStatus;
} {
  const isReconciled = Boolean(ledgerJournalId || taxWorksheetId);
  return {
    reconciliationStatus: isReconciled ? 'RECONCILED' : 'UNRECONCILED',
    lifecycleStatus: isReconciled ? FapiaoLifecycleStatus.MATCHED : FapiaoLifecycleStatus.UNVERIFIED_LOCAL,
  };
}

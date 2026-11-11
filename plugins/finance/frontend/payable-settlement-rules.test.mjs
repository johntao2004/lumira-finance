import assert from "node:assert/strict";
import test from "node:test";
import {
  formatTaxWorksheetKindLabel,
  isPayableSettlementJournalType,
  payableSettlementNextActions,
  validatePayableSettlementAmount,
} from "./payable-settlement-rules.ts";

test("应付结算原生凭证类型识别闭合，不误判人工或AP01费用确认凭证", () => {
  assert.equal(isPayableSettlementJournalType("AP_PAYMENT"), true);
  assert.equal(isPayableSettlementJournalType("AP_PAYMENT_REVERSAL"), true);
  assert.equal(isPayableSettlementJournalType("AP_EXPENSE"), false);
  assert.equal(isPayableSettlementJournalType("NORMAL"), false);
});

test("应付结算人民币金额以BigInt分值精确校验，严格阻断超未清项或超EV03结算配额", () => {
  const ok = validatePayableSettlementAmount("300.00", "600.00", "500.00");
  assert.deepEqual(ok, { ok: true, cents: 30000n });

  assert.deepEqual(
    validatePayableSettlementAmount("0.00", "600.00", "500.00"),
    { ok: false, code: "CNY_PRECISION_OR_RANGE" },
  );
  assert.deepEqual(
    validatePayableSettlementAmount("300.123", "600.00", "500.00"),
    { ok: false, code: "CNY_PRECISION_OR_RANGE" },
  );
  assert.deepEqual(
    validatePayableSettlementAmount("550.00", "600.00", "500.00"),
    { ok: false, code: "AP_SETTLEMENT_BASIS_EXCEEDED" },
  );
  assert.deepEqual(
    validatePayableSettlementAmount("600.01", "600.00", "900.00"),
    { ok: false, code: "AP_OPEN_ITEM_EXCEEDED" },
  );
});

test("应付结算单状态机动作矩阵闭合，已冲回或冲销单不再暴露二次冲回", () => {
  assert.deepEqual(payableSettlementNextActions("PAYMENT", "DRAFT", null), ["submit", "cancel"]);
  assert.deepEqual(payableSettlementNextActions("PAYMENT", "SUBMITTED", null), ["approve", "cancel"]);
  assert.deepEqual(payableSettlementNextActions("PAYMENT", "APPROVED", null), ["post", "cancel"]);
  assert.deepEqual(payableSettlementNextActions("PAYMENT", "POSTED", null), ["reverse"]);
  assert.deepEqual(payableSettlementNextActions("PAYMENT", "POSTED", "rev-id"), []);
  assert.deepEqual(payableSettlementNextActions("REVERSAL", "POSTED", null), []);
  assert.deepEqual(payableSettlementNextActions("PAYMENT", "CANCELLED", null), []);
  assert.equal(
    formatTaxWorksheetKindLabel("VAT_GENERAL_SURCHARGE"),
    "一般纳税人增值税及附加税费（TAX02 / TAX03）",
  );
});

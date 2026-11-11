import test from "node:test";
import assert from "node:assert/strict";
import {
  ASSET_PRODUCER_JOURNAL_TYPES,
  assetCnyAmount,
  assetZeroDifference,
  isAssetProducerJournalType,
  isDefinitiveAssetRejection,
  validateAssetPendingCommand,
} from "./asset-rules.ts";

test("资产原生凭证类型识别闭合，不误判人工或应付/应收凭证", () => {
  assert.equal(ASSET_PRODUCER_JOURNAL_TYPES.length, 5);
  for (const type of ASSET_PRODUCER_JOURNAL_TYPES) {
    assert.equal(isAssetProducerJournalType(type), true);
  }
  for (const other of [
    undefined,
    "NORMAL",
    "PROFIT_CLOSE",
    "AP_EXPENSE",
    "AR_REVENUE",
  ]) {
    assert.equal(isAssetProducerJournalType(other), false);
  }
});

test("人民币资产金额与对账零差额均使用精确十进制与BigInt判定，不经过浮点转换", () => {
  assert.equal(assetCnyAmount("12000"), "12000.00");
  assert.equal(assetCnyAmount("500.5"), "500.50");
  assert.equal(assetCnyAmount("0", true), "0.00");
  for (const bad of ["0", "0.00", "-1.00", "1.234", 100, null]) {
    assert.throws(() => assetCnyAmount(bad));
  }

  assert.equal(
    assetZeroDifference([
      { difference: "0.00000000" },
      { difference: "0" },
      { difference: "-0.00000000" },
    ]),
    true,
  );
  assert.equal(
    assetZeroDifference([
      { difference: "0.00000000" },
      { difference: "0.00000001" },
    ]),
    false,
  );
  assert.equal(assetZeroDifference([{ difference: "NaN" }]), false);
});

test("未确认资产操作挂起记录严格校验动作、UUID请求键与对象负载，且确定性4xx拒绝释放挂起锁", () => {
  const valid = validateAssetPendingCommand(
    JSON.stringify({
      action: "depreciate",
      key: "00000000-0000-4000-8000-000000000011",
      body: { assetId: "card-1", expectedVersion: 1, reason: "月度折旧" },
    }),
  );
  assert.equal(valid.action, "depreciate");
  assert.equal(valid.key, "00000000-0000-4000-8000-000000000011");

  for (const broken of [
    "[]",
    JSON.stringify({
      action: "unknown",
      key: "00000000-0000-4000-8000-000000000011",
      body: {},
    }),
    JSON.stringify({ action: "post", key: "not-a-uuid", body: {} }),
    JSON.stringify({
      action: "post",
      key: "00000000-0000-4000-8000-000000000011",
      body: [],
    }),
  ]) {
    assert.throws(() => validateAssetPendingCommand(broken));
  }

  for (const code of [400, 403, 404, 409, 422, 429]) {
    assert.equal(isDefinitiveAssetRejection({ status: code }), true);
  }
  for (const ambiguous of [
    null,
    new TypeError("Failed to fetch"),
    { status: 408 },
    { status: 500 },
    { status: 502 },
    { status: 504 },
  ]) {
    assert.equal(isDefinitiveAssetRejection(ambiguous), false);
  }
});


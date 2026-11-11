import test from "node:test";
import assert from "node:assert/strict";
import {
  cny,
  toBigIntUnits,
  fromBigIntUnits,
  calculatePriceVariance,
  calculateQuantityVariance,
  calculateTotalVariance,
  calculateEclProvision,
  calculateEclAdjustment,
  calculateConstrainedCap,
  validateStandardForContractAsset,
  validateStandardForEclProvision,
  validateSegregationOfDuties,
  canonicalJson,
  retryIdentity,
  agingBucketLossRates,
} from "./ap-ar-ext-rules.ts";

test("CNY scale and boundary validation", () => {
  assert.equal(cny("100"), "100.00");
  assert.equal(cny("100.5"), "100.50");
  assert.equal(cny("100.55"), "100.55");
  assert.equal(cny("0", true), "0.00");
  assert.equal(toBigIntUnits("12.34"), 1234n);
  assert.equal(fromBigIntUnits(1234n), "12.34");
  assert.equal(fromBigIntUnits(-500n), "-5.00");

  for (const invalid of ["0", "0.00", "-1.00", "01.00", "1.001", "1e2", null, undefined, 100]) {
    assert.throws(() => cny(invalid));
  }
});

test("Variance calculation decomposition (price, quantity, total)", () => {
  // estPrice = 100.00, actPrice = 105.00, invoicedQty = 50.00
  // priceVariance = (105 - 100) * 50 = +250.00
  const pv = calculatePriceVariance("100.00", "105.00", "50.00");
  assert.equal(pv, "250.00");

  // acceptedQty = 48.00, invoicedQty = 50.00, estPrice = 100.00
  // quantityVariance = (50 - 48) * 100 = +200.00
  const qv = calculateQuantityVariance("48.00", "50.00", "100.00");
  assert.equal(qv, "200.00");

  // totalVariance = 5250.00 - 4800.00 = 450.00
  const tv = calculateTotalVariance("4800.00", "5250.00");
  assert.equal(tv, "450.00");

  // Check sum: pv + qv == tv
  const sumUnits = toBigIntUnits(pv) + toBigIntUnits(qv);
  assert.equal(fromBigIntUnits(sumUnits), tv);
});

test("ECL aging bucket loss rates and adjustment", () => {
  assert.equal(agingBucketLossRates["NOT_DUE"], "0.0050");
  assert.equal(agingBucketLossRates["DAYS_181_PLUS"], "0.5000");

  // 100,000 receivable * 10% (DAYS_61_90) = 10,000.00
  const prov = calculateEclProvision("100000.00", "0.1000");
  assert.equal(prov, "10000.00");

  // target 10,000.00 - prior 3,000.00 = 7,000.00
  const adj = calculateEclAdjustment(prov, "3000.00");
  assert.equal(adj, "7000.00");
});

test("Constrained variable consideration cap calculation", () => {
  // estimated 50,000 * 80% confidence = 40,000.00 cap
  const cap = calculateConstrainedCap("50000.00", "0.8000");
  assert.equal(cap, "40000.00");

  assert.throws(() => calculateConstrainedCap("50000.00", "1.5000")); // > 1
});

test("CAS vs SMALL accounting policy boundary validation", () => {
  // Contract Assets
  assert.doesNotThrow(() => validateStandardForContractAsset("CAS"));
  assert.throws(() => validateStandardForContractAsset("SMALL"), /SMALL_DOES_NOT_SUPPORT_CONTRACT_ASSET/);

  // ECL Bad Debt Provision
  assert.doesNotThrow(() => validateStandardForEclProvision("CAS", "PROVISION"));
  assert.throws(() => validateStandardForEclProvision("SMALL", "PROVISION"), /SMALL_DOES_NOT_SUPPORT_BAD_DEBT_PROVISION/);
  assert.throws(() => validateStandardForEclProvision("SMALL", "REVERSAL"), /SMALL_DOES_NOT_SUPPORT_BAD_DEBT_PROVISION/);

  // Write-off allowed under both
  assert.doesNotThrow(() => validateStandardForEclProvision("CAS", "WRITE_OFF"));
  assert.doesNotThrow(() => validateStandardForEclProvision("SMALL", "WRITE_OFF"));
});

test("Segregation of duties validation (preparer != approver)", () => {
  const u1 = "00000000-0000-0000-0000-000000000001";
  const u2 = "00000000-0000-0000-0000-000000000002";

  assert.doesNotThrow(() => validateSegregationOfDuties(u1, u2));
  assert.throws(() => validateSegregationOfDuties(u1, u1), /SELF_APPROVAL_FORBIDDEN/);
});

test("Idempotency retry identity generation and replaying", async () => {
  const map = new Map();
  const mockStorage = {
    getItem: (k) => map.get(k) ?? null,
    setItem: (k, v) => map.set(k, v),
  };

  let counter = 0;
  const genUuid = () => `00000000-0000-4000-8000-${String(++counter).padStart(12, "0")}`;

  const payload1 = { b: 2, a: { x: "100.00", y: "data" } };
  const payload1Permuted = { a: { y: "data", x: "100.00" }, b: 2 };
  assert.equal(canonicalJson(payload1), canonicalJson(payload1Permuted));

  const first = await retryIdentity("tenant1:book1:year1", "/ap/po", payload1, mockStorage, genUuid);
  const replayed = await retryIdentity("tenant1:book1:year1", "/ap/po", payload1Permuted, mockStorage, genUuid);
  assert.deepEqual(replayed, first);

  const diffScope = await retryIdentity("tenant2:book1:year1", "/ap/po", payload1, mockStorage, genUuid);
  assert.notDeepEqual(diffScope, first);
});

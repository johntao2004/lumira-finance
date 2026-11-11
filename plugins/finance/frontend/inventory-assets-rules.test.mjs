import test from "node:test";
import assert from "node:assert/strict";
import {
  validateMoneyCny,
  calculateFifo,
  calculateMovingWeightedAverage,
  allocateLandedCosts,
  evaluateInventoryImpairment,
  evaluateAssetImpairment,
  validateTransferMovement,
  validateCipCapitalization,
  validateRdCapitalization,
} from "./inventory-assets-rules.ts";

test("validateMoneyCny accepts valid CNY currency strings and rejects invalid inputs", () => {
  assert.equal(validateMoneyCny("100"), true);
  assert.equal(validateMoneyCny("100.5"), true);
  assert.equal(validateMoneyCny("100.50"), true);
  assert.equal(validateMoneyCny("0"), true);
  assert.equal(validateMoneyCny("0.00"), true);

  assert.equal(validateMoneyCny("100.555"), false);
  assert.equal(validateMoneyCny("-10.00"), false);
  assert.equal(validateMoneyCny("abc"), false);
  assert.equal(validateMoneyCny(""), false);
});

test("calculateFifo consumes layers in chronological sequence with exact cost attribution", () => {
  const layers = [
    { batchId: "b1", inboundDate: "2026-01-05", remainingQuantity: "100.00000000", unitCost: "10.00000000" },
    { batchId: "b2", inboundDate: "2026-01-10", remainingQuantity: "200.00000000", unitCost: "12.00000000" },
  ];

  // Consume 150: 100 @ 10 + 50 @ 12 = 1600.00000000
  const res = calculateFifo(layers, "150.00000000");
  assert.equal(res.totalConsumedQuantity, "150.00000000");
  assert.equal(res.totalConsumedCost, "1600.00000000");
  assert.equal(res.allocations.length, 2);
  assert.equal(res.allocations[0].batchId, "b1");
  assert.equal(res.allocations[0].quantityConsumed, "100.00000000");
  assert.equal(res.allocations[1].batchId, "b2");
  assert.equal(res.allocations[1].quantityConsumed, "50.00000000");

  assert.equal(res.updatedLayers.length, 1);
  assert.equal(res.updatedLayers[0].batchId, "b2");
  assert.equal(res.updatedLayers[0].remainingQuantity, "150.00000000");
});

test("calculateFifo throws when stock is insufficient", () => {
  const layers = [
    { batchId: "b1", inboundDate: "2026-01-05", remainingQuantity: "10.00000000", unitCost: "10.00000000" },
  ];
  assert.throws(() => calculateFifo(layers, "20.00000000"), /INSUFFICIENT_STOCK/);
});

test("calculateMovingWeightedAverage updates unit cost accurately upon new inbounds", () => {
  // 100 units @ 10 (cost 1000) + 100 units @ 14 (cost 1400) = 2400 / 200 = 12.00000000
  const avg = calculateMovingWeightedAverage(
    "100.00000000", "1000.00000000",
    "100.00000000", "1400.00000000"
  );
  assert.equal(avg, "12.00000000");
});

test("allocateLandedCosts proportionally allocates freight/duties with zero remainder leakage", () => {
  const inbounds = [
    { id: "m1", totalCost: "1000.00000000", quantity: "100.00000000" },
    { id: "m2", totalCost: "2000.00000000", quantity: "200.00000000" },
  ];
  const landed = "100.00000000";
  const allocs = allocateLandedCosts(inbounds, landed);
  assert.equal(allocs.length, 2);

  const sumAllocated = (BigInt(allocs[0].allocatedAmount.replace(".", "")) +
                        BigInt(allocs[1].allocatedAmount.replace(".", ""))).toString();
  assert.equal(sumAllocated, "10000000000"); // 100.00000000
});

test("evaluateInventoryImpairment handles CAS 1 lower-of-cost-and-NRV and blocks on SMALL", () => {
  // Period 1: NRV drops to 800 -> provision 200
  const casP1 = evaluateInventoryImpairment("CAS", "1000.00000000", "800.00000000", "0.00000000");
  assert.equal(casP1.status, "PROVISIONED");
  assert.equal(casP1.adjustment, "200.00000000");
  assert.equal(casP1.newImpairment, "200.00000000");

  // Period 2: NRV recovers to 950 -> provision drops to 50, reverse 150
  const casP2 = evaluateInventoryImpairment("CAS", "1000.00000000", "950.00000000", "200.00000000");
  assert.equal(casP2.status, "REVERSED");
  assert.equal(casP2.adjustment, "-150.00000000");
  assert.equal(casP2.newImpairment, "50.00000000");

  // SMALL blocks impairment
  const small = evaluateInventoryImpairment("SMALL", "1000.00000000", "800.00000000", "0.00000000");
  assert.equal(small.status, "BLOCKED");
  assert.equal(small.newImpairment, "0.00000000");
});

test("evaluateAssetImpairment applies CAS 8 non-reversible rule and blocks on SMALL", () => {
  const cas = evaluateAssetImpairment("CAS", "100000.00000000", "85000.00000000");
  assert.equal(cas.status, "POSTED");
  assert.equal(cas.impairment, "15000.00000000");
  assert.equal(cas.isReversible, false);

  assert.throws(() => evaluateAssetImpairment("SMALL", "100000.00000000", "85000.00000000"), /SMALL_IMPAIRMENT_BLOCKED/);
});

test("validateTransferMovement enforces distinct warehouses and stock conservation", () => {
  const same = validateTransferMovement("w1", "w1", "10.00000000", "100.00000000");
  assert.equal(same.valid, false);
  assert.equal(same.error, "DISTINCT_TRANSFER_WAREHOUSES_REQUIRED");

  const overdraft = validateTransferMovement("w1", "w2", "150.00000000", "100.00000000");
  assert.equal(overdraft.valid, false);
  assert.equal(overdraft.error, "INSUFFICIENT_STOCK");

  const ok = validateTransferMovement("w1", "w2", "50.00000000", "100.00000000");
  assert.equal(ok.valid, true);
});

test("validateCipCapitalization ensures card costs equal capitalized CIP balance", () => {
  const mismatch = validateCipCapitalization("100000.00000000", ["60000.00000000", "30000.00000000"]);
  assert.equal(mismatch.valid, false);
  assert.equal(mismatch.error, "CIP_CAPITALIZATION_CARDS_MISMATCH");

  const match = validateCipCapitalization("100000.00000000", ["60000.00000000", "40000.00000000"]);
  assert.equal(match.valid, true);
});

test("validateRdCapitalization enforces development stage and statutory evidence", () => {
  const research = validateRdCapitalization("RESEARCH", "ev-1", "50000.00000000", "50000.00000000");
  assert.equal(research.valid, false);
  assert.equal(research.error, "RESEARCH_STAGE_CANNOT_CAPITALIZE");

  const noEvidence = validateRdCapitalization("DEVELOPMENT", null, "50000.00000000", "50000.00000000");
  assert.equal(noEvidence.valid, false);
  assert.equal(noEvidence.error, "STATUTORY_EVIDENCE_REQUIRED");

  const ok = validateRdCapitalization("DEVELOPMENT", "ev-1", "50000.00000000", "50000.00000000");
  assert.equal(ok.valid, true);
});

test("parseFixed8 and roundCents handle negative numbers correctly without sign inversion", () => {
  import("./inventory-assets-rules.ts").then((mod) => {
    assert.equal(mod.parseFixed8("-2.50000000"), -250000000n);
    assert.equal(mod.parseFixed8("-0.50000000"), -50000000n);
    assert.equal(mod.parseFixed8("-0.00000001"), -1n);
    assert.equal(mod.parseFixed8("0.00000000"), 0n);
    assert.equal(mod.parseFixed8("100.50000000"), 10050000000n);

    // Negative rounding: -0.015 rounds half away from zero to -0.02 (-2000000n)
    assert.equal(mod.roundCents(-1500000n), -2000000n);
    assert.equal(mod.roundCents(-1400000n), -1000000n);
    assert.equal(mod.roundCents(1500000n), 2000000n);
  });
});

test("calculateMovingWeightedAverage rejects non-positive inbound qty", () => {
  assert.throws(
    () => calculateMovingWeightedAverage("100.00000000", "1000.00000000", "0.00000000", "0.00000000"),
    /POSITIVE_INBOUND_QTY_REQUIRED/
  );
  assert.throws(
    () => calculateMovingWeightedAverage("100.00000000", "1000.00000000", "-10.00000000", "100.00000000"),
    /POSITIVE_INBOUND_QTY_REQUIRED/
  );
});

test("allocateLandedCosts rejects non-positive item quantity", () => {
  assert.throws(
    () => allocateLandedCosts([{ id: "m1", totalCost: "100.00000000", quantity: "0.00000000" }], "10.00000000"),
    /POSITIVE_QUANTITY_REQUIRED/
  );
});


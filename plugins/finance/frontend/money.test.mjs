import assert from "node:assert/strict";
import test from "node:test";
import { amount, units, cnyUnits } from "./types.ts";
test("V4 internal fixed8 preserves 20-digit values and legacy six-place history", () => {
  assert.equal(
    amount(units("10000000000000000000.01")),
    "10000000000000000000.01000000",
  );
  assert.equal(amount(units("0.00000001")), "0.00000001");
  assert.equal(amount(units("100.000001")), "100.00000100");
  assert.equal(amount(units("0.1") + units("0.2")), "0.30000000");
  assert.equal(amount(-1n), "-0.00000001");
});
test("CNY submission accepts exact cents and rejects excess precision without rounding", () => {
  assert.equal(cnyUnits("100.01"), 10001000000n);
  assert.equal(
    cnyUnits("10000000000000000000.01"),
    1000000000000000000001000000n,
  );
  for (const value of ["1.001", "1.005", "0.00000001"])
    assert.throws(() => cnyUnits(value));
  assert.equal(units("1.005"), 100500000n);
});
test("V4 internal parser rejects overflow and malformed values", () => {
  for (const value of [
    "100000000000000000000",
    "1.000000001",
    "1e3",
    "-1",
    "01",
    "",
    "NaN",
    "1,000",
    " 1",
  ])
    assert.throws(() => units(value));
});

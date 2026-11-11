import test from "node:test";
import assert from "node:assert/strict";
import {
  glCny,
  glCnyUnits,
  glCanonical,
  validateCutoverInput,
  evaluateAutoReviewPolicy,
  validateCorrectionStandard,
  validateCorrectionAllocations,
  evaluatePeriodCloseGates,
  glYearendRetryIdentity,
  validateConsecutiveYears,
  validateMultiYearReadScope,
  validateChartOfAccountsMapping,
  GL_YEAREND_LABELS
} from "./gl-yearend-rules.ts";

test("glCny validates strict 2-decimal CNY strings and rejects invalid formats", () => {
  assert.equal(glCny("100"), "100.00");
  assert.equal(glCny("100.5"), "100.50");
  assert.equal(glCny("1234567.89"), "1234567.89");
  assert.equal(glCny("0", true), "0.00");
  assert.equal(glCnyUnits("123.45"), 12345n);

  assert.throws(() => glCny("0")); // non-zero default
  assert.throws(() => glCny("-10.00"));
  assert.throws(() => glCny("10.001"));
  assert.throws(() => glCny("1e5"));
  assert.throws(() => glCny(100));
  assert.throws(() => glCny(null));
});

test("Mid-year cutover mode mutual exclusivity and tie-out verification", () => {
  const balances = [
    { accountId: "a1", accountCode: "1001", accountName: "Cash", direction: "DEBIT", amount: "5000.00" },
    { accountId: "a2", accountCode: "1122", accountName: "AR", direction: "DEBIT", amount: "10000.00" },
    { accountId: "a3", accountCode: "1601", accountName: "Fixed Assets Gross", direction: "DEBIT", amount: "25000.00" },
    { accountId: "a4", accountCode: "1602", accountName: "Accumulated Depreciation", direction: "CREDIT", amount: "5000.00" },
    { accountId: "a5", accountCode: "2202", accountName: "AP", direction: "CREDIT", amount: "35000.00" },
  ];
  const baselines = [
    { baselineType: "CASHFLOW", periodMonth: 6, categoryCode: "SALES", categoryName: "Cash", amount: "5000.00" }
  ];
  // Asset subledger net book value: 25000 - 5000 = 20000
  const subledgers = { AR: "10000.00", AP: "35000.00", ASSET: "20000.00" };

  // FULL_HISTORY rejects non-empty baselines
  assert.throws(() => validateCutoverInput("FULL_HISTORY", balances, baselines, subledgers));

  // OPENING_PLUS_YTD requires non-empty baselines
  assert.throws(() => validateCutoverInput("OPENING_PLUS_YTD", balances, [], subledgers));

  // Valid OPENING_PLUS_YTD with balanced books
  const validResult = validateCutoverInput("OPENING_PLUS_YTD", balances, baselines, subledgers);
  assert.equal(validResult.passed, true);
  assert.equal(validResult.tieOuts.length, 6);

  // Mismatched subledger causes failure
  const badSubledgers = { AR: "9999.00", AP: "15000.00" };
  const failResult = validateCutoverInput("OPENING_PLUS_YTD", balances, baselines, badSubledgers);
  assert.equal(failResult.passed, false);
});

test("Auto review policy evaluation: amount ceiling and zero exception", () => {
  const policy = {
    policyCode: "POL_01",
    maxAmount: "50000.00",
    scopeBusinessTypes: ["AR_SALES", "AP_PURCHASE"],
    allowedAccountCodes: ["1122", "6001", "2202"],
    zeroExceptionRequired: true,
  };

  // Case 1: Pass
  const resPass = evaluateAutoReviewPolicy(policy, "20000.00", "AR_SALES", ["1122", "6001"]);
  assert.equal(resPass.autoApproved, true);
  assert.equal(resPass.exceptions.length, 0);

  // Case 2: Exceeds amount ceiling
  const resAmount = evaluateAutoReviewPolicy(policy, "60000.00", "AR_SALES", ["1122"]);
  assert.equal(resAmount.autoApproved, false);
  assert.equal(resAmount.exceptions.length, 1);

  // Case 3: Out of scope business type
  const resType = evaluateAutoReviewPolicy(policy, "1000.00", "PAYROLL", ["1122"]);
  assert.equal(resType.autoApproved, false);
});

test("CAS vs SMALL prior-period error adjustment routing rules", () => {
  // CAS requires 6901
  assert.doesNotThrow(() => validateCorrectionStandard("CAS", "PRIOR_PERIOD_ADJUSTMENT", "6901"));
  assert.throws(() => validateCorrectionStandard("CAS", "PRIOR_PERIOD_ADJUSTMENT", "6001"));

  // SMALL forbids 6901
  assert.throws(() => validateCorrectionStandard("SMALL", "PRIOR_PERIOD_ADJUSTMENT", "6901"));
  assert.doesNotThrow(() => validateCorrectionStandard("SMALL", "PRIOR_PERIOD_ADJUSTMENT", "3103"));
});

test("Correction line allocation watermark ceiling validation", () => {
  const watermarks = { 1: "1000.00", 2: "500.00" };

  // Under ceiling
  assert.doesNotThrow(() =>
    validateCorrectionAllocations(
      [
        { sourceLineNo: 1, amount: "800.00", targetAccountVersionId: "v1" },
        { sourceLineNo: 2, amount: "500.00", targetAccountVersionId: "v2" },
      ],
      watermarks
    )
  );

  // Over ceiling
  assert.throws(() =>
    validateCorrectionAllocations(
      [{ sourceLineNo: 1, amount: "1200.00", targetAccountVersionId: "v1" }],
      watermarks
    )
  );
});

test("Month-end close checklist 6-gate evaluation", () => {
  // All pass
  const pass = evaluatePeriodCloseGates(0, "10000.00", "10000.00", true, true, true, true);
  assert.equal(pass.allChecksPassed, true);
  assert.equal(pass.trialBalanceBalanced, true);

  // Unposted journals remaining blocks close
  const failUnposted = evaluatePeriodCloseGates(1, "10000.00", "10000.00", true, true, true, true);
  assert.equal(failUnposted.allChecksPassed, false);

  // Unbalanced trial balance blocks close
  const failTb = evaluatePeriodCloseGates(0, "10000.00", "10000.01", true, true, true, true);
  assert.equal(failTb.allChecksPassed, false);
});

test("Idempotent retry identity caching across repeated invocations", async () => {
  const map = new Map();
  const mockStorage = {
    getItem: (k) => map.get(k) || null,
    setItem: (k, v) => map.set(k, v),
  };

  const id1 = await glYearendRetryIdentity("book1:2026", "/cutover", { mode: "FULL_HISTORY" }, mockStorage);
  const id2 = await glYearendRetryIdentity("book1:2026", "/cutover", { mode: "FULL_HISTORY" }, mockStorage);
  assert.equal(id1.operationId, id2.operationId);
  assert.equal(id1.idempotencyKey, id2.idempotencyKey);

  const id3 = await glYearendRetryIdentity("book1:2026", "/cutover", { mode: "OPENING_PLUS_YTD" }, mockStorage);
  assert.notEqual(id1.operationId, id3.operationId);
});

test("Consecutive years validation for cross-year carry (YEAR01)", () => {
  assert.doesNotThrow(() => validateConsecutiveYears(2026, 2027));
  assert.throws(() => validateConsecutiveYears(2026, 2028));
  assert.throws(() => validateConsecutiveYears(2027, 2026));
});

test("Multi-year read scope authorization validation (YEAR01)", () => {
  assert.doesNotThrow(() => validateMultiYearReadScope([2025, 2026], [2025, 2026]));
  assert.throws(() => validateMultiYearReadScope([2025, 2026], [2026, 2027]));
  assert.throws(() => validateMultiYearReadScope([2025, 2026], []));
});

test("Chart of Accounts mapping validation (YEAR01)", () => {
  const mappings = [
    { sourceCode: "1122", targetCode: "1122", netOpeningAmount: "1000.00" }
  ];
  assert.doesNotThrow(() => validateChartOfAccountsMapping("MAP_V1", mappings));
  assert.throws(() => validateChartOfAccountsMapping("", mappings));
  assert.throws(() => validateChartOfAccountsMapping("MAP_V1", []));
  assert.throws(() => validateChartOfAccountsMapping("MAP_V1", [{ sourceCode: "1122", targetCode: "", netOpeningAmount: "100.00" }]));
});


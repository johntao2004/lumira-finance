import test from "node:test";
import assert from "node:assert/strict";
import {
  parseMoney,
  formatMoney,
  parseRate,
  formatRate,
  translateFx,
  assessStandard,
  validateMultiDate,
  calculateRevaluation,
} from "./org-fx-rules.ts";

test("parseMoney and formatMoney 8-decimal precision", () => {
  const m1 = parseMoney("123.45678901");
  assert.equal(m1, 12345678901n);
  assert.equal(formatMoney(m1), "123.45678901");

  const m2 = parseMoney("0.00000001");
  assert.equal(m2, 1n);
  assert.equal(formatMoney(m2), "0.00000001");

  const m3 = parseMoney("50000000.00");
  assert.equal(formatMoney(m3), "50000000.00000000");
});

test("parseRate and formatRate 12-decimal precision", () => {
  const r1 = parseRate("7.123456789012");
  assert.equal(r1, 7123456789012n);
  assert.equal(formatRate(r1), "7.123456789012");

  const r2 = parseRate("0.000000000001");
  assert.equal(r2, 1n);
  assert.equal(formatRate(r2), "0.000000000001");
});

test("translateFx: exact 8-decimal, quantized 2-decimal and difference", () => {
  // 1,234.56780000 * 7.123456789012 = 8794.39037641
  const res = translateFx("1234.56780000", "7.123456789012");
  assert.equal(res.exact, "8794.39037641");
  assert.equal(res.quantized, "8794.39");
  assert.equal(res.difference, "0.00037641");

  // Sum check: quantized + difference == exact
  const qBig = parseMoney(res.quantized);
  const diffBig = parseMoney(res.difference);
  const exactBig = parseMoney(res.exact);
  assert.equal(qBig + diffBig, exactBig);
});

test("assessStandard: small enterprise qualification and disqualifications", () => {
  // Eligible small enterprise
  const eligible = assessStandard({
    isPubliclyTraded: false,
    isFinancialInstitution: false,
    isGroupSubsidiaryOfPublic: false,
    employeeCount: 120,
    annualRevenueStr: "45000000.00",
    totalAssetsStr: "35000000.00",
  });
  assert.equal(eligible.eligibleForSmall, true);
  assert.equal(eligible.standard, "SMALL");
  assert.equal(eligible.disqualifications.length, 0);

  // Disqualified by public trading
  const publicCompany = assessStandard({
    isPubliclyTraded: true,
    isFinancialInstitution: false,
    isGroupSubsidiaryOfPublic: false,
    employeeCount: 50,
    annualRevenueStr: "10000000.00",
    totalAssetsStr: "10000000.00",
  });
  assert.equal(publicCompany.eligibleForSmall, false);
  assert.equal(publicCompany.standard, "CAS");
  assert.ok(publicCompany.disqualifications.includes("PUBLICLY_TRADED"));

  // Disqualified by exceeding revenue & assets & staff limits
  const largeCorp = assessStandard({
    isPubliclyTraded: false,
    isFinancialInstitution: false,
    isGroupSubsidiaryOfPublic: false,
    employeeCount: 450,
    annualRevenueStr: "250000000.00",
    totalAssetsStr: "180000000.00",
  });
  assert.equal(largeCorp.eligibleForSmall, false);
  assert.equal(largeCorp.standard, "CAS");
  assert.equal(largeCorp.disqualifications.length, 3);
});

test("validateMultiDate: presence, horizon, closed period, and >90 day explanation", () => {
  // All present, open period, 10-day span -> valid
  const res1 = validateMultiDate(
    "2026-03-01",
    "2026-03-05",
    "2026-03-10",
    "2026-03-05",
    "OPEN"
  );
  assert.equal(res1.isValid, true);
  assert.equal(res1.requiresExplanation, false);
  assert.equal(res1.dateSpanDays, 9);

  // Closed period rejected
  const res2 = validateMultiDate(
    "2026-03-01",
    "2026-03-05",
    "2026-03-10",
    "2026-03-05",
    "CLOSED"
  );
  assert.equal(res2.isValid, false);
  assert.ok(res2.errors.includes("ACCOUNTING_PERIOD_CLOSED"));

  // >90 days without justification rejected
  const res3 = validateMultiDate(
    "2025-11-01",
    "2026-03-05",
    "2026-03-10",
    "2026-03-05",
    "OPEN"
  );
  assert.equal(res3.isValid, false);
  assert.equal(res3.requiresExplanation, true);
  assert.ok(res3.errors.includes("EXPLANATION_REQUIRED"));

  // >90 days with justification accepted
  const res4 = validateMultiDate(
    "2025-11-01",
    "2026-03-05",
    "2026-03-10",
    "2026-03-05",
    "OPEN",
    "Long-term equipment installation accepted after multi-stage trial"
  );
  assert.equal(res4.isValid, true);
  assert.equal(res4.requiresExplanation, true);
});

test("calculateRevaluation: foreign currency revaluation and gain/loss", () => {
  // 10,000 USD foreign balance, 71,000 carrying CNY, closing rate 7.250000000000
  // restated = 10000 * 7.25 = 72,500.00
  // gainLoss = 72500 - 71000 = +1500.00
  const reval = calculateRevaluation("10000.00000000", "71000.00000000", "7.250000000000");
  assert.equal(reval.restatedCny, "72500.00000000");
  assert.equal(reval.gainLoss, "1500.00000000");
});

test("parseMoney and formatMoney negative numbers between 0 and -1", () => {
  const m1 = parseMoney("-0.50");
  assert.equal(m1, -50000000n);
  assert.equal(formatMoney(m1), "-0.50000000");

  const m2 = parseMoney("-0.00000001");
  assert.equal(m2, -1n);
  assert.equal(formatMoney(m2), "-0.00000001");

  const m3 = parseMoney("-123.45678901");
  assert.equal(m3, -12345678901n);
  assert.equal(formatMoney(m3), "-123.45678901");
});

test("translateFx with negative amounts and symmetric HALF_UP rounding", () => {
  // -1,234.56780000 * 7.123456789012 = -8794.39037641
  const res = translateFx("-1234.56780000", "7.123456789012");
  assert.equal(res.exact, "-8794.39037641");
  assert.equal(res.quantized, "-8794.39");
  assert.equal(res.difference, "-0.00037641");

  // Sum check: quantized + difference == exact
  const qBig = parseMoney(res.quantized);
  const diffBig = parseMoney(res.difference);
  const exactBig = parseMoney(res.exact);
  assert.equal(qBig + diffBig, exactBig);
});

test("calculateRevaluation with negative balances", () => {
  // -10,000 USD overdraft, -71,000 carrying CNY, closing rate 7.250000000000
  // restated = -10000 * 7.25 = -72,500.00
  // gainLoss = -72500 - (-71000) = -1500.00
  const reval = calculateRevaluation("-10000.00000000", "-71000.00000000", "7.250000000000");
  assert.equal(reval.restatedCny, "-72500.00000000");
  assert.equal(reval.gainLoss, "-1500.00000000");
});

test("validateLifecycleTransition state machine and SoD rules", async () => {
  const { validateLifecycleTransition } = await import("./org-fx-rules.ts");

  // Valid transitions
  assert.equal(validateLifecycleTransition("PREPARING", "PENDING_CONFIRMATION", "p1", "p2").isValid, true);
  assert.equal(validateLifecycleTransition("PENDING_CONFIRMATION", "ACTIVE", "p1", "p2").isValid, true);
  assert.equal(validateLifecycleTransition("PENDING_CONFIRMATION", "PREPARING", "p1", "p2").isValid, true);
  assert.equal(validateLifecycleTransition("ACTIVE", "SUSPENDED", "p1", "p2").isValid, true);
  assert.equal(validateLifecycleTransition("SUSPENDED", "ACTIVE", "p1", "p2").isValid, true);
  assert.equal(validateLifecycleTransition("ACTIVE", "TERMINATED", "p1", "p2").isValid, true);
  assert.equal(validateLifecycleTransition("SUSPENDED", "TERMINATED", "p1", "p2").isValid, true);

  // SoD violation
  const sodRes = validateLifecycleTransition("ACTIVE", "SUSPENDED", "person-same", "person-same");
  assert.equal(sodRes.isValid, false);
  assert.ok(sodRes.error.includes("SOD_VIOLATION"));

  // Same status
  const sameRes = validateLifecycleTransition("ACTIVE", "ACTIVE", "p1", "p2");
  assert.equal(sameRes.isValid, false);
  assert.ok(sameRes.error.includes("SAME_STATUS"));

  // Terminal state
  const termRes = validateLifecycleTransition("TERMINATED", "ACTIVE", "p1", "p2");
  assert.equal(termRes.isValid, false);
  assert.ok(termRes.error.includes("TERMINATED_IS_TERMINAL"));

  // Illegal jump
  const jumpRes = validateLifecycleTransition("PREPARING", "ACTIVE", "p1", "p2");
  assert.equal(jumpRes.isValid, false);
  assert.ok(jumpRes.error.includes("ILLEGAL_LIFECYCLE_TRANSITION"));
});


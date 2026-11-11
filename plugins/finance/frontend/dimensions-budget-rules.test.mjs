import test from 'node:test';
import assert from 'node:assert/strict';
import {
  allocateCost,
  computeBudgetAvailable,
  computeForecastProjection,
  validateSoD,
  rollbackBudgetConsumption,
} from './dimensions-budget-rules.ts';

// Test 1: Exact Penny Allocation (3-way split on 100.00)
test('Test 1: Exact Penny Allocation (3-way split on 100.00)', () => {
  const targets = [
    { targetId: 'T1', ratio: '0.333333333333', priority: 1 },
    { targetId: 'T2', ratio: '0.333333333333', priority: 2 },
    { targetId: 'T3', ratio: '0.333333333334', priority: 3 },
  ];
  const res = allocateCost('100.00', targets, 2);
  assert.equal(res.length, 3);
  assert.equal(res[0].allocatedAmount, '33.33');
  assert.equal(res[1].allocatedAmount, '33.33');
  assert.equal(res[2].allocatedAmount, '33.34');

  const sum = res.reduce((acc, it) => acc + BigInt(it.allocatedAmount.replace('.', '')), 0n);
  assert.equal(sum, 10000n, 'Sum must equal exactly 100.00 in cents');
});

// Test 2: Exact 8-Decimal Allocation
test('Test 2: Exact 8-Decimal Allocation', () => {
  const targets = [
    { targetId: 'T1', ratio: '0.700000000000', priority: 1 },
    { targetId: 'T2', ratio: '0.300000000000', priority: 2 },
  ];
  const total = '12345.67890123';
  const res = allocateCost(total, targets, 8);
  assert.equal(res.length, 2);

  const sum = res.reduce((acc, it) => acc + BigInt(it.allocatedAmount.replace('.', '')), 0n);
  assert.equal(sum, BigInt(total.replace('.', '')), 'Sum must match exact 8-decimal total');
});

// Test 3: Zero Amount Allocation
test('Test 3: Zero Amount Allocation', () => {
  const targets = [
    { targetId: 'T1', ratio: '0.500000000000', priority: 1 },
    { targetId: 'T2', ratio: '0.500000000000', priority: 2 },
  ];
  const res = allocateCost('0.00', targets, 2);
  assert.equal(res[0].allocatedAmount, '0.00');
  assert.equal(res[1].allocatedAmount, '0.00');
});

// Test 4: Invalid Ratio Sum Rejection
test('Test 4: Invalid Ratio Sum Rejection', () => {
  const invalidTargets = [
    { targetId: 'T1', ratio: '0.500000000000', priority: 1 },
    { targetId: 'T2', ratio: '0.400000000000', priority: 2 },
  ];
  assert.throws(() => allocateCost('100.00', invalidTargets, 2), /Sum of ratios must equal 1/);
});

// Test 5: SoD Segregation of Duties Check
test('Test 5: SoD Segregation of Duties Check', () => {
  const maker = '11111111-1111-1111-1111-111111111111';
  const reviewer = '22222222-2222-2222-2222-222222222222';

  assert.doesNotThrow(() => validateSoD(maker, reviewer));
  assert.throws(() => validateSoD(maker, maker), /SELF_APPROVAL_FORBIDDEN/);
});

// Test 6: Budget Available Calculation and Overspending Guard
test('Test 6: Budget Available Calculation and Overspending Guard', () => {
  const status = computeBudgetAvailable('100000.00000000', '30000.00000000', '20000.00000000');
  assert.equal(status.availableAmount, '50000.00000000');

  // Overspending throws
  assert.throws(
    () => computeBudgetAvailable('100000.00000000', '80000.00000000', '30000.00000000'),
    /BUDGET_EXCEEDED/
  );
});

// Test 7: Forecast Projection & Variance
test('Test 7: Forecast Projection & Variance', () => {
  const proj = computeForecastProjection('45000.00000000', '65000.00000000', '100000.00000000');
  assert.equal(proj.forecastTotal, '110000.00000000');
  assert.equal(proj.projectedVariance, '10000.00000000');

  const projUnder = computeForecastProjection('30000.00000000', '50000.00000000', '100000.00000000');
  assert.equal(projUnder.forecastTotal, '80000.00000000');
  assert.equal(projUnder.projectedVariance, '-20000.00000000');
});

// Test 8: Rollback Budget Consumption
test('Test 8: Rollback Budget Consumption', () => {
  const remainingConsumed = rollbackBudgetConsumption('20000.00000000', '5000.00000000');
  assert.equal(remainingConsumed, '15000.00000000');

  // Over-rollback throws
  assert.throws(
    () => rollbackBudgetConsumption('20000.00000000', '25000.00000000'),
    /BUDGET_ROLLBACK_EXCEEDS_CONSUMED/
  );
});

// Test 9: Nil UUID SoD Rejection
test('Test 9: Nil UUID SoD Rejection', () => {
  const nilUuid = '00000000-0000-0000-0000-000000000000';
  const validUser = '11111111-1111-1111-1111-111111111111';
  assert.throws(() => validateSoD(nilUuid, validUser), /Nil UUID/);
  assert.throws(() => validateSoD(validUser, nilUuid), /Nil UUID/);
});

// Test 10: Allocation Scale Rejection on Fractional Penny
test('Test 10: Allocation Scale Rejection on Fractional Penny', () => {
  const targets = [
    { targetId: 'T1', ratio: '0.500000000000', priority: 1 },
    { targetId: 'T2', ratio: '0.500000000000', priority: 2 },
  ];
  assert.throws(() => allocateCost('100.123', targets, 2), /Scale exceeds 2/);
});

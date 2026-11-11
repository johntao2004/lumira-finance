import test from 'node:test';
import assert from 'node:assert/strict';
import {
  validateVoucherBalance,
  validateBalanceSheetArticulation,
  validateCashFlowBridge,
  evaluatePackagePublication,
  computeKpis,
  projectCashCalendar
} from './statutory-reports-rules.ts';

test('RPT01: Canonical tech-enterprise vouchers balance successfully', () => {
  // 1. Capital contribution
  const capitalVoucher = {
    code: 'VOUCHER_CAPITAL_CONTRIBUTION',
    title: '实收资本出资',
    standard: 'CAS_2019',
    businessDescription: '天使轮股东银行存款实收资本注资 1,000,000 元',
    lines: [
      { lineNo: 1, accountCode: '1002', accountName: '银行存款', direction: 'DEBIT', amount: '1000000.00', memo: '出资款' },
      { lineNo: 2, accountCode: '4001', accountName: '实收资本', direction: 'CREDIT', amount: '1000000.00', memo: '出资款' }
    ],
    totalDebit: '1000000.00',
    totalCredit: '1000000.00'
  };
  assert.strictEqual(validateVoucherBalance(capitalVoucher), true);

  // 2. R&D payroll accrual
  const payrollVoucher = {
    code: 'VOUCHER_RD_PAYROLL',
    title: '研发人员薪酬计提',
    standard: 'CAS_2019',
    businessDescription: '计提当月研发核心团队薪酬 250,000 元',
    lines: [
      { lineNo: 1, accountCode: '5301', accountName: '研发费用-工资', direction: 'DEBIT', amount: '200000.00', memo: '工资' },
      { lineNo: 2, accountCode: '5301', accountName: '研发费用-社保公积金', direction: 'DEBIT', amount: '50000.00', memo: '社保' },
      { lineNo: 3, accountCode: '2211', accountName: '应付职工薪酬', direction: 'CREDIT', amount: '250000.00', memo: '应付薪酬' }
    ],
    totalDebit: '250000.00',
    totalCredit: '250000.00'
  };
  assert.strictEqual(validateVoucherBalance(payrollVoucher), true);

  // 3. SaaS revenue with VAT
  const saasVoucher = {
    code: 'VOUCHER_SAAS_REVENUE',
    title: 'SaaS订阅收入与增值税',
    standard: 'CAS_2019',
    businessDescription: '软件订阅及云端服务年费开票 106,000 元(含6%增值税)',
    lines: [
      { lineNo: 1, accountCode: '1122', accountName: '应收账款', direction: 'DEBIT', amount: '106000.00', memo: '客户开票' },
      { lineNo: 2, accountCode: '6001', accountName: '主营业务收入', direction: 'CREDIT', amount: '100000.00', memo: 'SaaS服务费' },
      { lineNo: 3, accountCode: '2221', accountName: '应交税费-应交增值税(销项税额)', direction: 'CREDIT', amount: '6000.00', memo: '6%增值税' }
    ],
    totalDebit: '106000.00',
    totalCredit: '106000.00'
  };
  assert.strictEqual(validateVoucherBalance(saasVoucher), true);

  // 4. Server purchase & contra-depreciation
  const depreciationVoucher = {
    code: 'VOUCHER_SERVER_DEPRECIATION',
    title: 'AI研发服务器计提折旧(抵减科目)',
    standard: 'CAS_2019',
    businessDescription: '研发用GPU服务器按月计提累计折旧(固定资产抵减) 15,000 元',
    lines: [
      { lineNo: 1, accountCode: '5301', accountName: '研发费用-折旧费', direction: 'DEBIT', amount: '15000.00', memo: 'GPU服务器折旧' },
      { lineNo: 2, accountCode: '1602', accountName: '累计折旧', direction: 'CREDIT', amount: '15000.00', memo: '固定资产备抵' }
    ],
    totalDebit: '15000.00',
    totalCredit: '15000.00'
  };
  assert.strictEqual(validateVoucherBalance(depreciationVoucher), true);

  // 5. Expense claim
  const claimVoucher = {
    code: 'VOUCHER_EXPENSE_CLAIM',
    title: '员工差旅与日常报销',
    standard: 'CAS_2019',
    businessDescription: '员工报销客户差旅费及业务招待费 8,000 元冲销其他应收款/银行付款',
    lines: [
      { lineNo: 1, accountCode: '6602', accountName: '管理费用-差旅费', direction: 'DEBIT', amount: '8000.00', memo: '差旅报销' },
      { lineNo: 2, accountCode: '1002', accountName: '银行存款', direction: 'CREDIT', amount: '8000.00', memo: '报销转账' }
    ],
    totalDebit: '8000.00',
    totalCredit: '8000.00'
  };
  assert.strictEqual(validateVoucherBalance(claimVoucher), true);

  // 6. CIT Accrual
  const citVoucher = {
    code: 'VOUCHER_TAX_ACCRUAL',
    title: '季度企业所得税计提',
    standard: 'CAS_2019',
    businessDescription: '计提科技型企业当期所得税费用 15,000 元',
    lines: [
      { lineNo: 1, accountCode: '6801', accountName: '所得税费用', direction: 'DEBIT', amount: '15000.00', memo: '当期所得税' },
      { lineNo: 2, accountCode: '2221', accountName: '应交税费-应交所得税', direction: 'CREDIT', amount: '15000.00', memo: '应交税金' }
    ],
    totalDebit: '15000.00',
    totalCredit: '15000.00'
  };
  assert.strictEqual(validateVoucherBalance(citVoucher), true);
});

test('RPT01: Voucher validation rejects invalid/unbalanced entries', () => {
  // Imbalanced voucher
  const imbalanced = {
    code: 'VOUCHER_BAD_1',
    title: '不平凭证',
    standard: 'CAS_2019',
    businessDescription: '借贷金额不相等',
    lines: [
      { lineNo: 1, accountCode: '1002', accountName: '银行存款', direction: 'DEBIT', amount: '100.00', memo: 'm1' },
      { lineNo: 2, accountCode: '4001', accountName: '实收资本', direction: 'CREDIT', amount: '90.00', memo: 'm2' }
    ],
    totalDebit: '100.00',
    totalCredit: '90.00'
  };
  assert.strictEqual(validateVoucherBalance(imbalanced), false);

  // Negative amount
  const negative = {
    code: 'VOUCHER_BAD_2',
    title: '负数金额',
    standard: 'CAS_2019',
    businessDescription: '单行金额小于等于0',
    lines: [
      { lineNo: 1, accountCode: '1002', accountName: '银行存款', direction: 'DEBIT', amount: '-50.00', memo: 'm1' },
      { lineNo: 2, accountCode: '4001', accountName: '实收资本', direction: 'CREDIT', amount: '-50.00', memo: 'm2' }
    ],
    totalDebit: '-50.00',
    totalCredit: '-50.00'
  };
  assert.strictEqual(validateVoucherBalance(negative), false);
});

test('RPT02 & RP01: Balance Sheet articulation rule (Assets == Liabilities + Equity)', () => {
  const balanced = validateBalanceSheetArticulation(1500000, 500000, 1000000);
  assert.strictEqual(balanced.pass, true);
  assert.strictEqual(balanced.diff, 0);

  const broken = validateBalanceSheetArticulation(1500000, 500000, 999990);
  assert.strictEqual(broken.pass, false);
  assert.strictEqual(broken.diff, 10);
});

test('RPT04 & RP01: Cash flow statement direct & indirect bridge verification', () => {
  // Perfect match:
  // Opening: 100,000, Closing: 250,000 -> ΔCash = 150,000
  // Net cash increase = 150,000
  // Direct operating cash = 80,000, Indirect operating cash = 80,000
  const passRes = validateCashFlowBridge(100000, 250000, 150000, 80000, 80000);
  assert.strictEqual(passRes.pass, true);
  assert.strictEqual(passRes.cashDiff, 0);
  assert.strictEqual(passRes.indirectDiff, 0);

  // Discrepancy in ΔCash vs Net Cash Increase
  const cashMismatched = validateCashFlowBridge(100000, 240000, 150000, 80000, 80000);
  assert.strictEqual(cashMismatched.pass, false);
  assert.strictEqual(cashMismatched.cashDiff, -10000);

  // Discrepancy in Direct vs Indirect operating cash flow
  const indirectMismatched = validateCashFlowBridge(100000, 250000, 150000, 80000, 75000);
  assert.strictEqual(indirectMismatched.pass, false);
  assert.strictEqual(indirectMismatched.indirectDiff, -5000);
});

test('RPT06: Package publication gating rules (THREE_STATEMENT vs FULL_STATUTORY)', () => {
  // THREE_STATEMENT_PACKAGE passes even if SCE is missing and Notes are incomplete, provided BS & CF pass
  const threeStmt = evaluatePackagePublication('THREE_STATEMENT_PACKAGE', 'CAS_2019', true, true, false, false);
  assert.strictEqual(threeStmt.status, 'APPROVED');
  assert.strictEqual(threeStmt.gaps.length, 0);

  // FULL_STATUTORY_REPORT_PACKAGE blocked if CAS_2019 and SCE is missing
  const fullMissingSce = evaluatePackagePublication('FULL_STATUTORY_REPORT_PACKAGE', 'CAS_2019', true, true, false, true);
  assert.strictEqual(fullMissingSce.status, 'BLOCKED');
  assert.ok(fullMissingSce.gaps.includes('CAS_EQUITY_CHANGES_STATEMENT_MANDATORY'));

  // FULL_STATUTORY_REPORT_PACKAGE blocked if mandatory notes are incomplete
  const fullMissingNotes = evaluatePackagePublication('FULL_STATUTORY_REPORT_PACKAGE', 'CAS_2019', true, true, true, false);
  assert.strictEqual(fullMissingNotes.status, 'BLOCKED');
  assert.ok(fullMissingNotes.gaps.includes('MANDATORY_NOTES_INCOMPLETE'));

  // FULL_STATUTORY_REPORT_PACKAGE blocked if BS or CF articulation fails
  const fullArticulationFail = evaluatePackagePublication('FULL_STATUTORY_REPORT_PACKAGE', 'CAS_2019', false, false, true, true);
  assert.strictEqual(fullArticulationFail.status, 'BLOCKED');
  assert.ok(fullArticulationFail.gaps.includes('BS_ARTICULATION_FAILURE'));
  assert.ok(fullArticulationFail.gaps.includes('CF_ARTICULATION_FAILURE'));

  // FULL_STATUTORY_REPORT_PACKAGE approved when all requirements are met
  const fullSuccess = evaluatePackagePublication('FULL_STATUTORY_REPORT_PACKAGE', 'CAS_2019', true, true, true, true);
  assert.strictEqual(fullSuccess.status, 'APPROVED');
  assert.strictEqual(fullSuccess.gaps.length, 0);
});

test('RP02: Operating KPI calculation (Burn rate, Runway, R&D intensity, Gross margin, DSO, DPO)', () => {
  // Startup scenario:
  // Available Cash: 1,200,000
  // Monthly op outflow 120,000, capex 10,000, op inflow 50,000 over 12 months -> net drain = 120k + 10k - 50k = 80k/mo * 12 = 960k
  // Revenue: 1,000,000, COGS: 300,000, R&D: 350,000
  // Receivables: 100,000, Payables: 50,000
  const kpis = computeKpis(
    1200000, // availableCash
    1440000, // opOutflow (120k/mo)
    600000,  // opInflow (50k/mo)
    120000,  // capexOutflow (10k/mo)
    12,      // months
    1000000, // revenue
    300000,  // cogs
    350000,  // rdExpense
    100000,  // avgReceivables
    50000    // avgPayables
  );

  assert.strictEqual(kpis.burnRate, '80000.00'); // (1440000 + 120000 - 600000) / 12 = 80000
  assert.strictEqual(kpis.runwayMonths, '15.0'); // 1,200,000 / 80,000 = 15.0 months
  assert.strictEqual(kpis.rdIntensity, '35.0000'); // (350,000 / 1,000,000) * 100 = 35%
  assert.strictEqual(kpis.grossMargin, '70.0000'); // ((1,000,000 - 300,000) / 1,000,000) * 100 = 70%
  assert.strictEqual(kpis.dsoDays, '36.5'); // (100,000 * 365) / 1,000,000 = 36.5 days
  assert.strictEqual(kpis.dpoDays, '60.8'); // (50,000 * 365) / 300,000 = 60.833 -> 60.8 days
});

test('RP02: Operating KPI calculation edge cases (Zero revenue, zero COGS)', () => {
  const zeroRevKpis = computeKpis(500000, 120000, 0, 0, 12, 0, 0, 80000, 0, 0);
  assert.strictEqual(zeroRevKpis.burnRate, '10000.00');
  assert.strictEqual(zeroRevKpis.runwayMonths, '50.0');
  assert.strictEqual(zeroRevKpis.rdIntensity, '0.0000');
  assert.strictEqual(zeroRevKpis.grossMargin, '0.0000');
  assert.strictEqual(zeroRevKpis.dsoDays, '0.0');
  assert.strictEqual(zeroRevKpis.dpoDays, '0.0');
});

test('RP02: Rolling Cash Calendar projection & buffer breach alert', () => {
  // Opening: 100,000, Safe buffer: 80,000, Horizon: 5 days
  // Day 1: +20,000 -> 120,000
  // Day 2: -50,000 -> 70,000 (breaches 80,000 buffer)
  // Day 3: +40,000 -> 110,000
  // Day 4: 0 -> 110,000
  // Day 5: -10,000 -> 100,000
  const inflows = {
    '2026-11-12': 20000,
    '2026-11-14': 40000
  };
  const outflows = {
    '2026-11-13': 50000,
    '2026-11-16': 10000
  };

  const res = projectCashCalendar(100000, 80000, 5, inflows, outflows, '2026-11-11');
  assert.strictEqual(res.forecasts.length, 5);
  assert.strictEqual(res.bufferBreached, true);
  assert.strictEqual(res.minBalance, 70000);
  assert.strictEqual(res.forecasts[0].projectedBalance, '120000.00');
  assert.strictEqual(res.forecasts[0].belowBuffer, false);
  assert.strictEqual(res.forecasts[1].projectedBalance, '70000.00');
  assert.strictEqual(res.forecasts[1].belowBuffer, true);
  assert.strictEqual(res.forecasts[2].projectedBalance, '110000.00');
  assert.strictEqual(res.forecasts[2].belowBuffer, false);
});

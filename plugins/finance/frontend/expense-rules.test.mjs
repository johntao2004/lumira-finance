import test from "node:test";
import assert from "node:assert/strict";
import {
  expenseCny,
  expenseUnits,
  fromUnits,
  evaluateTravelCap,
  calculateLineSplit,
  calculateClaimTotals,
  calculateAdvanceBalances,
  validateSod,
  requisitionActions,
  advanceActions,
  claimActions,
  expenseCanonical,
  expenseRetryIdentity,
  expenseScope,
} from "./expense-rules.ts";

test("CNY二位字符串严格校验，拒绝浮点、超两位小数、零金额与越界值", () => {
  assert.equal(expenseCny("100"), "100.00");
  assert.equal(expenseCny("100.5"), "100.50");
  assert.equal(expenseCny("10000000000000000000.01"), "10000000000000000000.01");
  assert.equal(expenseCny("0", true), "0.00");
  assert.equal(expenseUnits("12.34"), 1234n);
  assert.equal(fromUnits(1234n), "12.34");

  for (const invalid of [
    "0",
    "0.00",
    "-1.00",
    "01.00",
    "1.001",
    "1.005",
    "1e2",
    100,
    null,
    undefined,
    "100000000000000000000.00",
  ]) {
    assert.throws(() => expenseCny(invalid));
  }
});

test("差旅标准限额评估（EXP04）：在限额内合规，超标计算超额差额并标记OVER_CAP", () => {
  const cap = {
    id: "cap-01",
    policyId: "pol-01",
    cityTier: "TIER_1",
    employeeRank: "STAFF",
    expenseCategory: "HOTEL",
    dailyCapAmount: "500.00",
    currency: "CNY",
  };

  const res1 = evaluateTravelCap(cap, "450.00");
  assert.equal(res1.status, "COMPLIANT");
  assert.equal(res1.excessAmount, "0.00");

  const res2 = evaluateTravelCap(cap, "680.00");
  assert.equal(res2.status, "OVER_CAP");
  assert.equal(res2.excessAmount, "180.00");
});

test("单行部分认可与增值税进项税拆分计算（EXP03）：核减个人金额从报销额剔除", () => {
  // 提交1060，核减个人60 -> 认可报销1000。税率6% (0.06) -> 净费用943.40，进项税56.60
  const split = calculateLineSplit("1060.00", "60.00", "0.06");
  assert.equal(split.reimbursable, "1000.00");
  assert.equal(split.disallowed, "60.00");
  assert.equal(split.vatInput, "56.60");
  assert.equal(split.netExpense, "943.40");

  // 核减金额超标报错
  assert.throws(() => calculateLineSplit("500.00", "600.00", "0"));
});

test("多行报销单汇总与冲借款扣除（EXP03）：计算总提交、认可额、核减额、冲借款及应付员工净额", () => {
  const lines = [
    { submitted: "1200.00", disallowed: "200.00" }, // reimbursable 1000.00
    { submitted: "500.00", disallowed: "0.00" },     // reimbursable 500.00
  ];
  const advanceOffsets = [{ advanceId: "adv-1", amount: "800.00" }];

  const totals = calculateClaimTotals(lines, advanceOffsets);
  assert.equal(totals.totalSubmitted, "1700.00");
  assert.equal(totals.totalApproved, "1500.00");
  assert.equal(totals.totalDisallowed, "200.00");
  assert.equal(totals.totalOffset, "800.00");
  assert.equal(totals.netPayable, "700.00"); // 1500 - 800 = 700

  // 冲借款超过认可报销额抛异常
  assert.throws(() =>
    calculateClaimTotals(lines, [{ advanceId: "adv-1", amount: "1600.00" }])
  );
});

test("借款单冲销与现金退还闭环（EXP02）：借1000、冲800、退200后余额为0并标记已结清", () => {
  const b1 = calculateAdvanceBalances("1000.00", "800.00", "0.00");
  assert.equal(b1.remainingBalance, "200.00");
  assert.equal(b1.isSettled, false);

  const b2 = calculateAdvanceBalances("1000.00", "800.00", "200.00");
  assert.equal(b2.remainingBalance, "0.00");
  assert.equal(b2.isSettled, true);

  // 超过本金抛异常
  assert.throws(() => calculateAdvanceBalances("1000.00", "900.00", "200.00"));
});

test("职责分离（SoD）自然人独立校验：申请人、审核人、审批人不得重叠，受益员工本人不得自审自批", () => {
  const u1 = "00000000-0000-4000-8000-000000000001";
  const u2 = "00000000-0000-4000-8000-000000000002";
  const u3 = "00000000-0000-4000-8000-000000000003";
  const emp = "00000000-0000-4000-8000-000000000004";

  // 三权独立且与员工受益人分离通过
  assert.equal(validateSod(u1, u2, u3, emp).valid, true);

  // 申请人自审自批被阻断
  assert.equal(validateSod(u1, u1, u3, emp).valid, false);
  assert.equal(validateSod(u1, u2, u1, emp).valid, false);

  // 审核人兼任审批人被阻断
  assert.equal(validateSod(u1, u2, u2, emp).valid, false);

  // 员工本人作为审核人或审批人被阻断 (助理代提报场景防自审自批)
  assert.equal(validateSod(u1, emp, u3, emp).valid, false);
  assert.equal(validateSod(u1, u2, emp, emp).valid, false);
});

test("状态流转动作判断：各生命周期状态允许合法动作", () => {
  assert.deepEqual(requisitionActions("DRAFT"), ["submit", "cancel"]);
  assert.deepEqual(requisitionActions("SUBMITTED"), ["approve", "cancel"]);
  assert.deepEqual(requisitionActions("CLOSED"), []);

  assert.deepEqual(advanceActions("DRAFT"), ["submit", "cancel"]);
  assert.deepEqual(advanceActions("APPROVED"), ["payout", "cancel"]);
  assert.deepEqual(advanceActions("PAID"), ["offset", "refund"]);

  assert.deepEqual(claimActions("DRAFT"), ["submit", "cancel"]);
  assert.deepEqual(claimActions("REVIEWED"), ["approve", "reject", "cancel"]);
  assert.deepEqual(claimActions("POSTED"), ["disburse", "reverse"]);
});

test("幂等身份生成与规范JSON排序（expenseRetryIdentity）", async () => {
  const mem = new Map();
  const mockStorage = {
    getItem: (k) => mem.get(k) ?? null,
    setItem: (k, v) => mem.set(k, v),
  };

  const id1 = await expenseRetryIdentity(
    "tenant-1",
    "/api/claims",
    { b: 2, a: 1 },
    mockStorage
  );
  assert.ok(id1.operationId);
  assert.ok(id1.idempotencyKey);

  // 相同键与乱序属性请求返回相同幂等身份
  const id2 = await expenseRetryIdentity(
    "tenant-1",
    "/api/claims",
    { a: 1, b: 2 },
    mockStorage
  );
  assert.equal(id1.operationId, id2.operationId);
  assert.equal(id1.idempotencyKey, id2.idempotencyKey);
});

test("expenseScope: 规范化后端费用路径，消除 /years/ 路径不匹配", () => {
  assert.equal(
    expenseScope("/finance/v1/books/b1/years/y1"),
    "/finance/v1/books/b1/expense"
  );
  assert.equal(
    expenseScope("/finance/v1/books/b1/years/y1/"),
    "/finance/v1/books/b1/expense"
  );
  assert.equal(
    expenseScope("/finance/v1/books/b1/years/y1?query=1"),
    "/finance/v1/books/b1/expense"
  );
  assert.equal(
    expenseScope("/finance/v1/books/b1/expense"),
    "/finance/v1/books/b1/expense"
  );
  assert.equal(
    expenseScope("/finance/v1/books/b1/expense/"),
    "/finance/v1/books/b1/expense"
  );
  assert.equal(
    expenseScope("/books/b1/years/y1"),
    "/books/b1/expense"
  );
  assert.equal(
    expenseScope("/books/b1/years/y1/"),
    "/books/b1/expense"
  );
  assert.equal(
    expenseScope("/api/finance/v1/books/b1/years/y1"),
    "/finance/v1/books/b1/expense"
  );
  assert.equal(
    expenseScope("/api/finance/v1/books/b1/expense/"),
    "/finance/v1/books/b1/expense"
  );
  assert.equal(
    expenseScope("/finance/v1/books/b1"),
    "/finance/v1/books/b1/expense"
  );
  assert.equal(expenseScope(""), "");
});


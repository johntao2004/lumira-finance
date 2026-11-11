import test from "node:test";
import assert from "node:assert/strict";
import {
  clearPayrollPending,
  isDefinitivePayrollRejection,
  payrollNextAction,
  payrollStorageKey,
  readPayrollPending,
  writePayrollPending,
} from "./payroll-rules.ts";

test("工资批次状态机动作矩阵闭合，已锁定与已撤销批次不再暴露流转动作", () => {
  assert.equal(payrollNextAction("DRAFT"), "CALCULATE");
  assert.equal(payrollNextAction("CALCULATED"), "REVIEW");
  assert.equal(payrollNextAction("REVIEWED"), "APPROVE");
  assert.equal(payrollNextAction("APPROVED"), "LOCK");
  assert.equal(payrollNextAction("LOCKED"), undefined);
  assert.equal(payrollNextAction("CANCELLED"), undefined);
});

test("跨刷新重试仅保存命令幂等键与期望版本，严禁薪酬或员工数据进入浏览器存储且确定性4xx拒绝释放挂起锁", () => {
  const map = new Map();
  const storage = {
    getItem: (k) => map.get(k) ?? null,
    setItem: (k, v) => map.set(k, v),
    removeItem: (k) => map.delete(k),
  };

  const pending = {
    command: {
      key: "00000000-0000-4000-8000-000000000099",
      client: "finance-web",
      expectedVersion: 2,
    },
    action: "APPROVE",
  };

  writePayrollPending(storage, "tenantA:book1:2041:entity1:acctA", "batch-jan", pending);
  assert.deepEqual(
    readPayrollPending(storage, "tenantA:book1:2041:entity1:acctA", "batch-jan"),
    pending,
  );
  assert.equal(
    readPayrollPending(storage, "tenantA:book1:2041:entity1:acctB", "batch-jan"),
    undefined,
  );

  for (const [, raw] of map.entries()) {
    assert.equal(raw.includes("gross"), false);
    assert.equal(raw.includes("employee"), false);
  }

  // Tampered payload containing salary/employee fields must be rejected fail-closed
  const key = payrollStorageKey("tenantA:book1:2041:entity1:acctA", "batch-jan");
  map.set(key, JSON.stringify({ ...pending, gross: "20000.00" }));
  assert.throws(() =>
    readPayrollPending(storage, "tenantA:book1:2041:entity1:acctA", "batch-jan"),
  );

  clearPayrollPending(storage, "tenantA:book1:2041:entity1:acctA", "batch-jan");
  assert.equal(
    readPayrollPending(storage, "tenantA:book1:2041:entity1:acctA", "batch-jan"),
    undefined,
  );

  for (const code of [400, 403, 404, 409, 422, 429]) {
    assert.equal(isDefinitivePayrollRejection({ status: code }), true);
  }
  for (const ambiguous of [
    null,
    new TypeError("Failed to fetch"),
    { status: 408 },
    { status: 500 },
    { status: 502 },
    { status: 504 },
  ]) {
    assert.equal(isDefinitivePayrollRejection(ambiguous), false);
  }
});


import test from "node:test";
import assert from "node:assert/strict";
import {
  canonicalJson,
  checkAuditorReadOnly,
  getRequiredStages,
  hasSodConflict,
  isDiagnosticGrantActive,
  maskBankAccount,
  maskIdCard,
  maskPayroll,
  validateDelegation,
  validateEmergencyGrantApprovals,
  validateStageDecider,
} from "./iam-ops-rules.ts";

test("IAM02: 敏感字段脱敏规则测试（薪酬、身份证、银行卡）", () => {
  // 薪酬脱敏
  assert.equal(maskPayroll("15000.50", false), "***.00");
  assert.equal(maskPayroll("15000.50", true), "15000.50");
  assert.equal(maskPayroll(0, false), "***.00");
  assert.equal(maskPayroll(0, true), "0.00");
  assert.equal(maskPayroll(null, false), "0.00");

  // 身份证脱敏
  assert.equal(maskIdCard("11010119900101001X", false), "110101********001X");
  assert.equal(maskIdCard("11010119900101001X", true), "11010119900101001X");
  assert.equal(maskIdCard("123", false), "******");

  // 银行卡脱敏
  assert.equal(maskBankAccount("6222021234567891234", false), "622202******1234");
  assert.equal(maskBankAccount("6222021234567891234", true), "6222021234567891234");
  assert.equal(maskBankAccount("1234", false), "******");
});

test("IAM03: 法定职责分离（SoD）冲突检测矩阵测试", () => {
  // 出纳不得兼任稽核
  const c1 = hasSodConflict(["CASHIER"], "AUDITOR");
  assert.equal(c1.conflict, true);
  assert.ok(c1.reason.includes("第三十七条"));

  // 出纳不得兼任审批人
  const c2 = hasSodConflict(["CASHIER"], "APPROVER");
  assert.equal(c2.conflict, true);
  assert.ok(c2.reason.includes("审批"));

  // 出纳不得兼任总账会计
  const c3 = hasSodConflict(["ACCOUNTANT"], "CASHIER");
  assert.equal(c3.conflict, true);

  // 会计不得兼任审计
  const c4 = hasSodConflict(["ACCOUNTANT"], "AUDITOR");
  assert.equal(c4.conflict, true);

  // 合法兼容角色
  const c5 = hasSodConflict(["READER"], "APPROVER");
  assert.equal(c5.conflict, false);

  // 角色别名映射测试：REVIEWER 映射至 APPROVER，与 CASHIER 冲突
  const c6 = hasSodConflict(["CASHIER"], "REVIEWER");
  assert.equal(c6.conflict, true);
  assert.match(c6.reason, /职责分离/);

  // 角色别名映射测试：POSTER 映射至 ACCOUNTANT，与 CASHIER 冲突
  const c7 = hasSodConflict(["CASHIER"], "POSTER");
  assert.equal(c7.conflict, true);
});

test("IAM03: 阶梯金额多级审批门槛测试", () => {
  const t1 = getRequiredStages("30000.00");
  assert.equal(t1.stages, 1);
  assert.deepEqual(t1.stageRoles, ["APPROVER"]);

  const t1Exact = getRequiredStages("50000.00");
  assert.equal(t1Exact.stages, 1);

  // 边界连续性测试：50000.005 正确进入第2阶（而非因浮点间隙滑入第3阶）
  const t2Boundary = getRequiredStages("50000.005");
  assert.equal(t2Boundary.stages, 2);
  assert.deepEqual(t2Boundary.stageRoles, ["APPROVER", "FINANCE_DIRECTOR"]);

  const t2 = getRequiredStages("150000.00");
  assert.equal(t2.stages, 2);
  assert.deepEqual(t2.stageRoles, ["APPROVER", "FINANCE_DIRECTOR"]);

  const t3 = getRequiredStages("5000000.00");
  assert.equal(t3.stages, 3);
  assert.deepEqual(t3.stageRoles, ["APPROVER", "FINANCE_DIRECTOR", "TREASURY_BOARD"]);

  assert.throws(() => getRequiredStages("-100"));
  assert.throws(() => getRequiredStages("invalid"));
});

test("IAM03: 多级审批不同自然人独立审核规则测试", () => {
  // 制单人自审阻断
  const d1 = validateStageDecider("p-maker", "p-maker", []);
  assert.equal(d1.valid, false);
  assert.match(d1.error, /禁止自审/);

  // 一审审批人试图继续审批二审阻断
  const d2 = validateStageDecider("p-maker", "p-approver1", ["p-approver1"]);
  assert.equal(d2.valid, false);
  assert.match(d2.error, /禁止同一自然人跨阶段兼审/);

  // 独立二审审批人审核通过
  const d3 = validateStageDecider("p-maker", "p-approver2", ["p-approver1"]);
  assert.equal(d3.valid, true);
});

test("IAM04: 独立审计角色法定只读限制测试 (ROLE_RESTRICTED_READ_ONLY)", () => {
  // 审计员尝试写操作必须被阻断
  const w1 = checkAuditorReadOnly(["AUDITOR"], "POST_JOURNAL");
  assert.equal(w1.allowed, false);
  assert.match(w1.error, /ROLE_RESTRICTED_READ_ONLY/);

  const w2 = checkAuditorReadOnly(["AUDITOR"], "REVERSE");
  assert.equal(w2.allowed, false);

  const w3 = checkAuditorReadOnly(["AUDITOR"], "DELETE");
  assert.equal(w3.allowed, false);

  // 审计员读操作允许
  const r1 = checkAuditorReadOnly(["AUDITOR"], "READ_LOGS");
  assert.equal(r1.allowed, true);

  // 非审计员写操作不触发此只读限制
  const nonAuditor = checkAuditorReadOnly(["ACCOUNTANT"], "POST_JOURNAL");
  assert.equal(nonAuditor.allowed, true);
});

test("IAM03: 限时非自我代理转交与SoD校验测试", () => {
  const now = new Date();
  const from = now.toISOString();
  const to = new Date(now.getTime() + 86400000 * 3).toISOString();

  // 自我代理阻断
  const r1 = validateDelegation({
    delegatorPerson: "person-A",
    delegateePerson: "person-A",
    effectiveFrom: from,
    effectiveTo: to,
    role: "APPROVER",
    delegateeRoles: ["ACCOUNTANT"],
  });
  assert.equal(r1.valid, false);
  assert.match(r1.error, /禁止自我代理/);

  // 截止时间早于生效时间阻断
  const r2 = validateDelegation({
    delegatorPerson: "person-A",
    delegateePerson: "person-B",
    effectiveFrom: to,
    effectiveTo: from,
    role: "APPROVER",
    delegateeRoles: ["ACCOUNTANT"],
  });
  assert.equal(r2.valid, false);
  assert.match(r2.error, /代理截止时间必须晚于生效时间/);

  // 受托人已有出纳身份，转交审批职责被SoD阻断
  const r3 = validateDelegation({
    delegatorPerson: "person-A",
    delegateePerson: "person-B",
    effectiveFrom: from,
    effectiveTo: to,
    role: "APPROVER",
    delegateeRoles: ["CASHIER"],
  });
  assert.equal(r3.valid, false);
  assert.match(r3.error, /职责分离/);

  // 正常合法代理转交
  const r4 = validateDelegation({
    delegatorPerson: "person-A",
    delegateePerson: "person-B",
    effectiveFrom: from,
    effectiveTo: to,
    role: "APPROVER",
    delegateeRoles: ["ACCOUNTANT"],
  });
  assert.equal(r4.valid, true);
});

test("IAM04: 双人审批紧急特权审计授权规则测试", () => {
  // 申请人不能自审（第一审批人）
  const a1 = validateEmergencyGrantApprovals("person-req", "person-req", "person-sec2");
  assert.equal(a1.valid, false);
  assert.match(a1.error, /禁止自审/);

  // 申请人不能自审（第二审批人）
  const a2 = validateEmergencyGrantApprovals("person-req", "person-sec1", "person-req");
  assert.equal(a2.valid, false);
  assert.match(a2.error, /禁止自审/);

  // 审批人1与审批人2不能为同一人
  const a3 = validateEmergencyGrantApprovals("person-req", "person-sec1", "person-sec1");
  assert.equal(a3.valid, false);
  assert.match(a3.error, /禁止同人兼审/);

  // 独立双人复核通过
  const a4 = validateEmergencyGrantApprovals("person-req", "person-sec1", "person-sec2");
  assert.equal(a4.valid, true);
});

test("OPS02: 客户诊断访问限时生效与撤销状态测试", () => {
  const now = Date.now();
  const grant = {
    status: "ACTIVE",
    activatedAt: new Date(now - 10000).toISOString(),
    expiresAt: new Date(now + 60000).toISOString(),
    revokedAt: null,
  };
  assert.equal(isDiagnosticGrantActive(grant, now), true);

  // 已过期
  assert.equal(isDiagnosticGrantActive(grant, now + 120000), false);

  // 已被客户主动撤销
  const revokedGrant = { ...grant, status: "REVOKED", revokedAt: new Date(now).toISOString() };
  assert.equal(isDiagnosticGrantActive(revokedGrant, now), false);
});

test("通用: canonicalJson稳定键排序序列化测试", () => {
  const o1 = { z: 1, a: 2, m: { b: 3, a: 4 } };
  const o2 = { a: 2, m: { a: 4, b: 3 }, z: 1 };
  assert.equal(canonicalJson(o1), canonicalJson(o2));
  assert.equal(canonicalJson(o1), '{"a":2,"m":{"a":4,"b":3},"z":1}');
});

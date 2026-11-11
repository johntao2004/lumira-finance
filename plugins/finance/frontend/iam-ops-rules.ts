// Deterministic frontend rules and validation logic for Lane W02 (IAM & OPS)

export type Role = "OWNER" | "ACCOUNTANT" | "CASHIER" | "AUDITOR" | "APPROVER" | "READER";

export interface SodConflictRule {
  roleA: string;
  roleB: string;
  severity: "STRICT_BLOCK" | "WARNING";
  basis: string;
}

export const STATUTORY_SOD_RULES: SodConflictRule[] = [
  {
    roleA: "CASHIER",
    roleB: "AUDITOR",
    severity: "STRICT_BLOCK",
    basis: "《中华人民共和国会计法》第三十七条：出纳人员不得兼任稽核、会计档案保管和收入、支出、费用、债权债务账目的登记工作。",
  },
  {
    roleA: "CASHIER",
    roleB: "APPROVER",
    severity: "STRICT_BLOCK",
    basis: "内控基本规范职责分离：资金经办人员（出纳）不得兼任资金支付审批人员。",
  },
  {
    roleA: "CASHIER",
    roleB: "ACCOUNTANT",
    severity: "STRICT_BLOCK",
    basis: "会计法第三十七条：出纳人员不得兼任总账会计账目登记工作。",
  },
  {
    roleA: "ACCOUNTANT",
    roleB: "AUDITOR",
    severity: "STRICT_BLOCK",
    basis: "内部控制评价与稽核独立性原则：记账人员不得兼任同范围内部稽核审计。",
  },
];

// ----------------------------------------------------------------------------
// 1. Sensitive Field Masking
// ----------------------------------------------------------------------------

export function maskPayroll(amount: string | number | null | undefined, hasUnmaskPermission: boolean): string {
  if (amount === null || amount === undefined || amount === "") return "0.00";
  if (hasUnmaskPermission) {
    const num = typeof amount === "number" ? amount : parseFloat(String(amount));
    return isNaN(num) ? "0.00" : num.toFixed(2);
  }
  return "***.00";
}

export function maskIdCard(idCard: string | null | undefined, hasUnmaskPermission: boolean): string {
  if (!idCard) return "";
  const s = idCard.trim();
  if (hasUnmaskPermission) return s;
  if (s.length < 10) return "******";
  return s.substring(0, 6) + "********" + s.substring(s.length - 4);
}

export function maskBankAccount(bankAccount: string | null | undefined, hasUnmaskPermission: boolean): string {
  if (!bankAccount) return "";
  const s = bankAccount.trim();
  if (hasUnmaskPermission) return s;
  if (s.length < 10) return "******";
  return s.substring(0, 6) + "******" + s.substring(s.length - 4);
}

// ----------------------------------------------------------------------------
// Canonical Role Normalization
// ----------------------------------------------------------------------------

export function canonicalRole(role: string): string {
  if (!role) return "";
  const r = role.trim().toUpperCase();
  switch (r) {
    case "REVIEWER":
      return "APPROVER";
    case "MAKER":
    case "POSTER":
      return "ACCOUNTANT";
    case "VIEWER":
      return "READER";
    default:
      return r;
  }
}

// ----------------------------------------------------------------------------
// 2. SoD Conflict Enforcement
// ----------------------------------------------------------------------------

export function hasSodConflict(
  existingRoles: string[],
  newRole: string,
  rules: SodConflictRule[] = STATUTORY_SOD_RULES
): { conflict: boolean; reason?: string } {
  const normNew = canonicalRole(newRole);
  const normExisting = existingRoles.map(canonicalRole);

  for (const r of rules) {
    const a = canonicalRole(r.roleA);
    const b = canonicalRole(r.roleB);
    if ((normNew === a && normExisting.includes(b)) || (normNew === b && normExisting.includes(a))) {
      return {
        conflict: true,
        reason: `职责分离（SoD）冲突阻断：${r.roleA} 与 ${r.roleB} 不得同时兼任。依据：${r.basis}`,
      };
    }
  }
  return { conflict: false };
}

// ----------------------------------------------------------------------------
// Auditor Read-Only Statutory Invariant
// ----------------------------------------------------------------------------

export const AUDITOR_WRITE_ACTIONS = [
  "CREATE",
  "POST",
  "REVERSE",
  "DELETE",
  "UPDATE",
  "WRITE",
  "CREATE_JOURNAL",
  "POST_JOURNAL",
  "REVERSE_JOURNAL",
  "DELETE_JOURNAL",
];

export function checkAuditorReadOnly(roles: string[], action: string): { allowed: boolean; error?: string } {
  const isAuditor = roles.some((r) => canonicalRole(r) === "AUDITOR");
  if (isAuditor) {
    const normAct = action.trim().toUpperCase();
    if (AUDITOR_WRITE_ACTIONS.includes(normAct)) {
      return {
        allowed: false,
        error: `ROLE_RESTRICTED_READ_ONLY: 审计人员角色受法定只读限制，严禁执行记账、冲销、修改或删除操作 (action: ${action})`,
      };
    }
  }
  return { allowed: true };
}

// ----------------------------------------------------------------------------
// 3. Tiered Monetary Approval Stages
// ----------------------------------------------------------------------------

export interface ApprovalTier {
  min: number;
  max: number | null;
  stages: number;
  roles: string[];
}

export const MONETARY_APPROVAL_TIERS: ApprovalTier[] = [
  { min: 0, max: 50000, stages: 1, roles: ["APPROVER"] },
  { min: 50000, max: 500000, stages: 2, roles: ["APPROVER", "FINANCE_DIRECTOR"] },
  { min: 500000, max: null, stages: 3, roles: ["APPROVER", "FINANCE_DIRECTOR", "TREASURY_BOARD"] },
];

export function getRequiredStages(amountStr: string): { stages: number; stageRoles: string[] } {
  const amount = parseFloat(amountStr);
  if (isNaN(amount) || amount < 0) {
    throw new Error("金额必须为非负有效数值");
  }
  if (amount <= 50000) {
    return { stages: 1, stageRoles: ["APPROVER"] };
  }
  if (amount <= 500000) {
    return { stages: 2, stageRoles: ["APPROVER", "FINANCE_DIRECTOR"] };
  }
  return { stages: 3, stageRoles: ["APPROVER", "FINANCE_DIRECTOR", "TREASURY_BOARD"] };
}

// ----------------------------------------------------------------------------
// Multi-Stage Sequential Routing Decider Validation
// ----------------------------------------------------------------------------

export function validateStageDecider(
  requestedByPerson: string,
  deciderPerson: string,
  previousStageDeciders: string[] = []
): { valid: boolean; error?: string } {
  const decider = deciderPerson.trim();
  if (requestedByPerson.trim() === decider) {
    return { valid: false, error: "职责分离冲突：制单人/经办人不得审核本人发起的任务（禁止自审）" };
  }
  if (previousStageDeciders.map((s) => s.trim()).includes(decider)) {
    return { valid: false, error: "职责分离冲突：前序审批人不得重复审批后续阶段（禁止同一自然人跨阶段兼审）" };
  }
  return { valid: true };
}

// ----------------------------------------------------------------------------
// 4. Non-Self Delegation Validation
// ----------------------------------------------------------------------------

export interface DelegationValidationInput {
  delegatorPerson: string;
  delegateePerson: string;
  effectiveFrom: string;
  effectiveTo: string;
  role: string;
  delegateeRoles: string[];
}

export function validateDelegation(
  input: DelegationValidationInput,
  rules: SodConflictRule[] = STATUTORY_SOD_RULES
): { valid: boolean; error?: string } {
  if (input.delegatorPerson.trim() === input.delegateePerson.trim()) {
    return { valid: false, error: "禁止向本人发起代理转交（禁止自我代理）" };
  }
  const fromMs = Date.parse(input.effectiveFrom);
  const toMs = Date.parse(input.effectiveTo);
  if (isNaN(fromMs) || isNaN(toMs)) {
    return { valid: false, error: "代理生效时间或截止时间格式不合法" };
  }
  if (toMs <= fromMs) {
    return { valid: false, error: "代理截止时间必须晚于生效时间" };
  }
  const sodCheck = hasSodConflict(input.delegateeRoles, input.role, rules);
  if (sodCheck.conflict) {
    return { valid: false, error: sodCheck.reason };
  }
  return { valid: true };
}

// ----------------------------------------------------------------------------
// 5. Dual-Person Emergency Grant Validation
// ----------------------------------------------------------------------------

export function validateEmergencyGrantApprovals(
  requesterPerson: string,
  approver1Person: string | null,
  approver2Person: string | null
): { valid: boolean; error?: string } {
  const req = requesterPerson.trim();
  if (approver1Person && approver1Person.trim() === req) {
    return { valid: false, error: "申请人不得作为紧急审计访问第一审批人（禁止自审）" };
  }
  if (approver2Person && approver2Person.trim() === req) {
    return { valid: false, error: "申请人不得作为紧急审计访问第二审批人（禁止自审）" };
  }
  if (approver1Person && approver2Person && approver1Person.trim() === approver2Person.trim()) {
    return { valid: false, error: "双人审批必须由两个不同自然人分别审核（禁止同人兼审）" };
  }
  return { valid: true };
}

// ----------------------------------------------------------------------------
// 6. Diagnostic Grant Active Check
// ----------------------------------------------------------------------------

export function isDiagnosticGrantActive(
  grant: { status: string; activatedAt: string; expiresAt: string; revokedAt: string | null },
  nowMs: number = Date.now()
): boolean {
  if (grant.status.toUpperCase() !== "ACTIVE") return false;
  if (grant.revokedAt !== null) return false;
  const start = Date.parse(grant.activatedAt);
  const end = Date.parse(grant.expiresAt);
  return nowMs >= start && nowMs < end;
}

// ----------------------------------------------------------------------------
// 7. Canonical Retry Identity (SessionStorage Helper)
// ----------------------------------------------------------------------------

export function canonicalJson(obj: unknown): string {
  function sortKeys(val: unknown): unknown {
    if (Array.isArray(val)) return val.map(sortKeys);
    if (val !== null && typeof val === "object") {
      return Object.fromEntries(
        Object.entries(val)
          .sort(([a], [b]) => a.localeCompare(b))
          .map(([k, v]) => [k, sortKeys(v)])
      );
    }
    return val;
  }
  return JSON.stringify(sortKeys(obj));
}

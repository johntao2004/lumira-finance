export const PAYROLL_ACTIONS = [
  "CALCULATE",
  "REVIEW",
  "APPROVE",
  "LOCK",
  "CANCEL",
] as const;

export type PayrollAction = (typeof PAYROLL_ACTIONS)[number];

export type PayrollBatchState =
  | "DRAFT"
  | "CALCULATED"
  | "REVIEWED"
  | "APPROVED"
  | "LOCKED"
  | "CANCELLED";

export type PayrollPendingCommand = {
  command: {
    key: string;
    client: string;
    expectedVersion: number;
  };
  action: PayrollAction;
};

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function payrollNextAction(
  state: PayrollBatchState,
): PayrollAction | undefined {
  switch (state) {
    case "DRAFT":
      return "CALCULATE";
    case "CALCULATED":
      return "REVIEW";
    case "REVIEWED":
      return "APPROVE";
    case "APPROVED":
      return "LOCK";
    default:
      return undefined;
  }
}

export function payrollStorageKey(scopeKey: string, batchId: string): string {
  if (!scopeKey.trim() || !batchId.trim()) {
    throw new Error("工资批次重试作用域与批次标识不能为空");
  }
  return `finance:payroll:v1:pending:${scopeKey}:${batchId.trim()}`;
}

/**
 * Persist ONLY command metadata (UUID key, client, expectedVersion, action) for ambiguous-failure replay.
 * Salary figures, employee identifiers, and tax amounts NEVER enter browser storage.
 */
export function writePayrollPending(
  storage: Pick<Storage, "setItem">,
  scopeKey: string,
  batchId: string,
  pending: PayrollPendingCommand,
): void {
  const validated = validatePayrollPendingObject(pending);
  storage.setItem(
    payrollStorageKey(scopeKey, batchId),
    JSON.stringify(validated),
  );
}

export function readPayrollPending(
  storage: Pick<Storage, "getItem">,
  scopeKey: string,
  batchId: string,
): PayrollPendingCommand | undefined {
  if (!scopeKey.trim() || !batchId.trim()) return undefined;
  const raw = storage.getItem(payrollStorageKey(scopeKey, batchId));
  if (raw === null) return undefined;
  return validatePayrollPendingObject(JSON.parse(raw));
}

export function clearPayrollPending(
  storage: Pick<Storage, "removeItem">,
  scopeKey: string,
  batchId: string,
): void {
  if (!scopeKey.trim() || !batchId.trim()) return;
  storage.removeItem(payrollStorageKey(scopeKey, batchId));
}

export function isDefinitivePayrollRejection(error: unknown): boolean {
  if (!error || typeof error !== "object") return false;
  const status = (error as { status?: unknown }).status;
  return (
    typeof status === "number" &&
    Number.isInteger(status) &&
    status >= 400 &&
    status < 500 &&
    status !== 408
  );
}

function validatePayrollPendingObject(value: unknown): PayrollPendingCommand {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("工资批次未确认命令元数据已损坏，请核实服务器状态");
  }
  const record = value as Record<string, unknown>;
  const keys = Object.keys(record).sort();
  if (keys.length !== 2 || keys[0] !== "action" || keys[1] !== "command") {
    throw new Error("工资批次重试记录包含未允许字段，已拒绝加载");
  }
  const action = record.action;
  if (
    typeof action !== "string" ||
    !(PAYROLL_ACTIONS as readonly string[]).includes(action)
  ) {
    throw new Error("工资批次重试动作无效");
  }
  const cmd = record.command;
  if (!cmd || typeof cmd !== "object" || Array.isArray(cmd)) {
    throw new Error("工资批次命令信封无效");
  }
  const cmdRecord = cmd as Record<string, unknown>;
  const cmdKeys = Object.keys(cmdRecord).sort();
  if (
    cmdKeys.length !== 3 ||
    cmdKeys[0] !== "client" ||
    cmdKeys[1] !== "expectedVersion" ||
    cmdKeys[2] !== "key"
  ) {
    throw new Error("工资批次命令信封包含未允许字段");
  }
  if (
    typeof cmdRecord.key !== "string" ||
    !UUID_PATTERN.test(cmdRecord.key) ||
    typeof cmdRecord.client !== "string" ||
    !cmdRecord.client.trim() ||
    typeof cmdRecord.expectedVersion !== "number" ||
    !Number.isInteger(cmdRecord.expectedVersion) ||
    cmdRecord.expectedVersion < 0
  ) {
    throw new Error("工资批次命令版本或幂等键无效");
  }
  return {
    command: {
      key: cmdRecord.key,
      client: cmdRecord.client,
      expectedVersion: cmdRecord.expectedVersion,
    },
    action: action as PayrollAction,
  };
}

import type {
  Configuration,
  CreateProposal,
  ProposalSummary,
} from "./person-directory-contract";
export const directoryOperations = {
  CREATE_PERSON_AND_BIND: "建立人员并绑定账号",
  BIND_EXISTING_PERSON: "为已有人员绑定另一账号",
  REVOKE_BINDING: "永久撤销账号绑定",
};
export function directoryResourceFailure(code: string, reading: boolean) {
  if (
    code !== "PERSON_DIRECTORY_QUERY_BUDGET_EXCEEDED" &&
    code !== "PERSON_DIRECTORY_BUSY"
  )
    return null;
  const reason =
    code === "PERSON_DIRECTORY_BUSY" ? "目录暂时繁忙" : "本次目录处理超时";
  return {
    clearReadState: reading,
    message: reading
      ? `${reason}，未读取成功。旧内容已清理，请稍后显式重新读取首屏。`
      : `${reason}，处理结果尚未确认。表单已保留，可重试原请求；请重新读取当前提案状态核对。`,
  };
}
export function directoryTabIntent(
  activeTab: string,
  hasYear: boolean,
  directoryAvailable: boolean,
) {
  if (!directoryAvailable && activeTab === "person-directory")
    return "journals";
  if (!hasYear && directoryAvailable) return "person-directory";
  return activeTab;
}
export function directoryConfigurationState(input: {
  canRead: boolean;
  tenantId: string;
  configurationTenantId?: string;
  denied: boolean;
  status?: number;
  fetching: boolean;
  failed: boolean;
}) {
  const visible =
    input.canRead &&
    !!input.tenantId &&
    input.configurationTenantId === input.tenantId &&
    !input.denied &&
    input.status !== 403;
  return { visible, authorizationPending: input.fetching || input.failed };
}
export function directoryAbility(
  configuration: Configuration,
  ability: "READ" | "PROPOSE" | "CONFIRM",
  platformAllowed: boolean,
) {
  return (
    platformAllowed && configuration.currentActorAbilities.includes(ability)
  );
}
export function independentDirectoryConfirmation(
  configuration: Configuration,
  proposal: ProposalSummary,
) {
  return (
    proposal.status === "PENDING" &&
    configuration.currentActorAccountId !== proposal.accountId &&
    configuration.currentActorPersonId !== proposal.preparedPersonId &&
    configuration.currentActorPersonId !== proposal.personId
  );
}
export function directoryCreatePayload(
  values: Record<string, unknown>,
  idempotencyKey: string,
): CreateProposal {
  const operation = values.operation;
  if (
    operation !== "CREATE_PERSON_AND_BIND" &&
    operation !== "BIND_EXISTING_PERSON" &&
    operation !== "REVOKE_BINDING"
  )
    throw new Error("请选择已支持的目录操作。");
  if (
    values.noAccountTransferConfirmed !== true ||
    values.allKnownAccountsDeclared !== true
  )
    throw new Error("请主动确认禁止账号转让及所有已知账号声明。");
  const text = (key: string, maximum: number) => {
    const value = values[key];
    if (typeof value !== "string" || !value.trim() || value.length > maximum)
      throw new Error("请核对必填说明、引用和长度。");
    return value;
  };
  const uuid = (key: string) => {
    const value = text(key, 36);
    if (
      !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
        value,
      )
    )
      throw new Error("账号与人员标识必须是有效UUID，不是身份证号码。");
    return value.toLowerCase();
  };
  const sha = text("sourceSha256", 64);
  if (!/^[a-f0-9]{64}$/.test(sha))
    throw new Error("核验引用SHA-256须为64位小写十六进制。");
  return {
    schemaVersion: "1",
    idempotencyKey,
    operation,
    accountId: uuid("accountId"),
    personId: operation === "CREATE_PERSON_AND_BIND" ? null : uuid("personId"),
    displayName:
      operation === "CREATE_PERSON_AND_BIND" ? text("displayName", 120) : null,
    sourceReference: text("sourceReference", 500),
    sourceSha256: sha,
    reason: text("reason", 1000),
    noAccountTransferConfirmed: true,
    allKnownAccountsDeclared: true,
  };
}
export function directoryGeneration(value: string) {
  if (
    !/^(0|[1-9][0-9]{0,18})$/.test(value) ||
    BigInt(value) > 9223372036854775807n
  )
    throw new Error("目录版本格式不可用。");
  return value;
}
// Writes are serial in the panel: at most one retained entry is in flight.
export function directoryRequestKey(
  keys: Map<string, string>,
  fingerprint: string,
  createKey: () => string,
) {
  const existing = keys.get(fingerprint);
  if (existing) {
    keys.delete(fingerprint);
    keys.set(fingerprint, existing);
    return existing;
  }
  while (keys.size >= 20) {
    const oldest = keys.keys().next().value;
    if (oldest === undefined) break;
    keys.delete(oldest);
  }
  const key = createKey();
  keys.set(fingerprint, key);
  return key;
}
export const directoryErrors: Record<string, string> = {
  SCOPE_DENIED:
    "当前账号没有该租户目录操作授权。目录治理权限与账套岗位相互独立，请联系组织治理负责人核对。",
  PERSON_OPERATION_UNSUPPORTED:
    "本片仅支持新建人员并绑定、为已有人员绑定账号、永久撤销绑定；不支持转让、合并或恢复账号。",
  PERSON_AUTH_ASSURANCE_REQUIRED:
    "此操作要求最近5分钟内完成最终登录并通过TOTP。请通过平台重新登录并完成TOTP后重试；不要在目录表单填入验证码或会话材料。",
  PERSON_DIRECTORY_UNAVAILABLE:
    "该租户目录尚未配置或当前不可用。仅部署治理流程可初始化，界面不能初始化。",
  PERSON_DIRECTORY_PAGE_STALE:
    "目录或授权已变化，旧页及游标已清理。请重新读取首屏。",
  PERSON_BINDING_CONFLICT:
    "该账号已有永久绑定历史，不能转让、恢复或重新绑定。请核对组织记录。",
  PERSON_PROPOSAL_STALE: "提案内容或制备人的当前授权已变化，请重新读取并核对。",
  PERSON_PROPOSAL_EXPIRED:
    "提案已超过7天确认期限，可撤销或独立驳回后重新准备。",
  PERSON_SEED_PROTECTED: "治理种子绑定受保护，不能通过公开目录撤销。",
  SELF_APPROVAL_FORBIDDEN:
    "确认人必须独立于制备人和目标人员，不能使用同一人员的另一账号代为确认。",
};

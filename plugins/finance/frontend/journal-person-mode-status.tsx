import { Alert, Descriptions, Space, Typography } from "antd";
import type { JournalPersonMode } from "./journal-person-rules";
type Props = {
  mode: JournalPersonMode | null;
  busy: boolean;
  error: string;
  generation: string | null;
  effectiveAt: string | null;
  blockers: string[];
};
/** Read-only presentation; commands will use the frozen business DTO, not directory grants. */
export function JournalPersonModeStatus({
  mode,
  busy,
  error,
  generation,
  effectiveAt,
  blockers,
}: Props) {
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      {error && <Alert type="error" title={error} />}
      <Alert
        type={mode === "JOURNAL_PERSON_SOD" ? "success" : "info"}
        title={
          busy
            ? "正在读取本年度凭证审签模式"
            : mode === "JOURNAL_PERSON_SOD"
              ? "本年度已启用凭证人员审签"
              : mode === "ACCOUNT_ONLY"
                ? "账号级审签：尚未启用人员审签"
                : "本年度审签模式未读取成功"
        }
        description={
          mode === "JOURNAL_PERSON_SOD"
            ? "本片仅保护人工普通凭证的制单内容、独立批准和记账证明。组织人工核验不等于政府身份认证；现金业务尚未接入，年结人员审批协议未支持。"
            : "不使用当前人员绑定补核历史凭证。只有服务端确认符合条件的新年度，才能申请单向启用；不能把未知状态当作账号模式。"
        }
      />
      <Descriptions
        size="small"
        bordered
        column={1}
        items={[
          {
            key: "generation",
            label: "模式版本",
            children: generation ?? "尚未读取",
          },
          {
            key: "effective",
            label: "实际启用时间",
            children: effectiveAt ?? "尚未启用",
          },
        ]}
      />
      {blockers.length > 0 && (
        <Alert
          type="warning"
          title="当前提出新启用申请的检查说明（不代替独立审批资格）"
          description={
            <ul>
              {blockers.map((reason, index) => (
                <li key={`${index}:${reason}`}>{reason}</li>
              ))}
            </ul>
          }
        />
      )}
      <Typography.Text type="secondary">
        人员绑定与业务岗位共同约束本业务；目录治理权限不能替代制单、审核或记账权限。已有独立有效批准后，不额外要求第三位记账人。
      </Typography.Text>
    </Space>
  );
}

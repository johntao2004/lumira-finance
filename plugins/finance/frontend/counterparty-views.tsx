import { Descriptions, Space, Tag, Typography } from "antd";
import type {
  Content,
  CounterpartySummary,
  RevisionDetail,
} from "./counterparty-contract";
import { partyStatus } from "./counterparty-rules";
export function PartyCurrent({ party }: { party: CounterpartySummary }) {
  return (
    <Descriptions
      bordered
      size="small"
      column={1}
      styles={{ content: { overflowWrap: "anywhere" } }}
      items={[
        { key: "id", label: "组织ID（非证件）", children: party.id },
        {
          key: "home",
          label: "治理归属账套",
          children: party.governanceHomeBookId,
        },
        {
          key: "identity",
          label: "稳定登记身份（组织声明，非政府核验）",
          children: `${party.identity.jurisdiction} / ${party.identity.identifier}`,
        },
        {
          key: "status",
          label: "组织当前状态",
          children: <Tag>{partyStatus(party.effectiveStatus)}</Tag>,
        },
        {
          key: "name",
          label: "当前发布名称",
          children: party.effectiveDisplayName ?? "尚未发布",
        },
        {
          key: "eligible",
          label: "主数据新用资格",
          children: party.newUseEligible
            ? "当前组织档案可供引用；应付等业务仍须独立核验、审签，本页不执行记账或付款"
            : "当前不可新用",
        },
        {
          key: "pending",
          label: "待审修订",
          children: party.pendingRevisionId ?? "无",
        },
        {
          key: "registry",
          label: "当前组织版本",
          children: party.registryVersion,
        },
      ]}
    />
  );
}
export function PartyContent({ content }: { content: Content }) {
  return (
    <Descriptions
      bordered
      size="small"
      column={1}
      styles={{ content: { overflowWrap: "anywhere", whiteSpace: "pre-wrap" } }}
      items={[
        { key: "name", label: "名称", children: content.displayName },
        {
          key: "roles",
          label: "组织角色",
          children: content.roles
            .map((r) => (r === "CUSTOMER" ? "客户" : "供应商"))
            .join("、"),
        },
        {
          key: "terms",
          label: "结算条款说明（不执行付款）",
          children: content.settlementTerms ?? "未声明",
        },
        {
          key: "contract",
          label: "合同说明",
          children: content.contractDescription ?? "未声明",
        },
        {
          key: "related",
          label: "关联方组织声明",
          children: {
            UNKNOWN: "未知",
            RELATED: "关联方",
            NOT_RELATED: "非关联方",
          }[content.relatedPartyStatus],
        },
        {
          key: "relatedBasis",
          label: "关联方声明依据",
          children: content.relatedPartyBasis ?? "未知，无依据",
        },
        {
          key: "basis",
          label: "登记依据",
          children: content.registrationBasis,
        },
      ]}
    />
  );
}
export function PartyRevision({ revision }: { revision: RevisionDetail }) {
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Typography.Title level={5}>不可变修订内容</Typography.Title>
      <Descriptions
        bordered
        column={1}
        size="small"
        styles={{ content: { overflowWrap: "anywhere" } }}
        items={[
          {
            key: "id",
            label: "修订ID / 序号 / 版本",
            children: `${revision.revision.id} / ${revision.revision.revision} / ${revision.revision.version}`,
          },
          {
            key: "status",
            label: "此修订历史状态 / 拟发布状态",
            children: `${partyStatus(revision.revision.status)} / ${partyStatus(revision.revision.desiredStatus)}`,
          },
          {
            key: "maker",
            label: "提出账号 / 自然人",
            children: `${revision.makerAccountId} / ${revision.makerPersonId}`,
          },
          { key: "date", label: "提出时间", children: revision.createdAt },
          { key: "reason", label: "提出理由", children: revision.reason },
          {
            key: "digest",
            label: "内容摘要",
            children: revision.revision.contentDigest,
          },
          {
            key: "prev",
            label: "前驱修订 / 基础已批准修订",
            children: `${revision.previousRevisionId ?? "无"} / ${revision.baseApprovedRevisionId ?? "无"}`,
          },
          {
            key: "decision",
            label: "真实决定",
            children: revision.decision
              ? `${partyStatus(revision.decision.kind)} · ${revision.decision.actorAccountId} / ${revision.decision.actorPersonId} · ${revision.decision.decidedAt} · ${revision.decision.reason}`
              : "尚无决定",
          },
        ]}
      />
      <PartyContent content={revision.content} />
    </Space>
  );
}

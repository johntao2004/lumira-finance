import { Descriptions, Typography, Space, Alert } from "antd";
import type {
  TaxEntitySummary,
  ProfileSnapshot,
  RevisionDetail,
  CommandReceipt,
} from "./tax-entity-contract";
import { taxStatus } from "./tax-entity-rules";
import { TaxOriginalDownload } from "./tax-entity-original";
export function TaxCurrent({
  value,
  title = "权威当前纳税主体档案",
}: {
  value: TaxEntitySummary;
  title?: string;
}) {
  return (
    <Descriptions
      style={{ overflowWrap: "anywhere", width: "100%" }}
      title={title}
      column={1}
      size="small"
      bordered
      items={[
        {
          key: "id",
          label: "稳定主体ID",
          children: <Typography.Text copyable>{value.id}</Typography.Text>,
        },
        {
          key: "home",
          label: "治理归属账套ID",
          children: (
            <Typography.Text copyable>
              {value.governanceHomeBookId}
            </Typography.Text>
          ),
        },
        {
          key: "identity",
          label: "永久登记身份",
          children: `CN · ${value.identity.canonicalTaxRegistrationNo}`,
        },
        {
          key: "state",
          label: "本地档案状态",
          children: taxStatus(value.effectiveStatus),
        },
        {
          key: "name",
          label: "当前已发布名称",
          children: value.effectiveDisplayName ?? "尚未发布",
        },
        { key: "version", label: "当前版本", children: value.version },
        {
          key: "basis",
          label: "当前批准修订",
          children: value.approvedRevision?.id ?? "无",
        },
        {
          key: "eligible",
          label: "主数据引用资格",
          children: value.masterDataEligible
            ? "仅本地档案资格可用，不代表税务业务资格"
            : "当前不可新引用",
        },
        {
          key: "boundary",
          label: "未核验边界",
          children:
            "税额待评估；税型规则、优惠资格未核验；官方验证、申报、缴款未接入。",
        },
      ]}
    />
  );
}
export function TaxProfile({
  value,
  onDenied,
  readable,
}: {
  value: ProfileSnapshot;
  onDenied: () => void;
  readable: (e: unknown) => string;
}) {
  return (
    <Space
      orientation="vertical"
      style={{ width: "100%", overflowWrap: "anywhere" }}
    >
      <Descriptions
        style={{ overflowWrap: "anywhere", width: "100%" }}
        column={1}
        size="small"
        bordered
        items={[
          { key: "name", label: "登记名称", children: value.displayName },
          {
            key: "kind",
            label: "组织类型声明",
            children: {
              LEGAL_ENTITY: "法人主体",
              BRANCH: "分支机构",
              OTHER_REGISTERED_TAXPAYER: "其他已登记纳税主体",
            }[value.entityKind],
          },
          {
            key: "type",
            label: "登记类型声明",
            children: value.registrationTypeDeclaration,
          },
          {
            key: "residency",
            label: "居民属性声明",
            children: {
              RESIDENT: "居民（组织声明）",
              NON_RESIDENT: "非居民（组织声明）",
              UNKNOWN: "待核验",
            }[value.residencyDeclaration],
          },
          {
            key: "dates",
            label: "声明日期",
            children: `设立：${value.establishedOn ?? "未知"}；注销：${value.cancelledOn ?? "未知"}`,
          },
          {
            key: "codes",
            label: "组织声明代码",
            children: `主管机构：${value.authorityCodeDeclaration ?? "未知"}；行政区划：${value.administrativeDivisionDeclaration ?? "未知"}`,
          },
          {
            key: "basis",
            label: "组织人工核验依据",
            children: (
              <span style={{ whiteSpace: "pre-wrap" }}>
                {value.organizationVerificationBasis}
              </span>
            ),
          },
          {
            key: "claims",
            label: "已冻结主动声明",
            children: `单一纳税主体：${value.singleTaxpayerConfirmed ? "已声明" : "未声明"}；已知登记号完整：${value.allKnownRegistrationIdentifiersDeclared ? "已声明" : "未声明"}；组织核验非官方认证：${value.organizationReviewedNotGovernmentVerified ? "已声明" : "未声明"}`,
          },
        ]}
      />
      <TaxOriginalDownload
        key={`${value.registrationEvidence.bookId}:${value.registrationEvidence.yearId}:${value.registrationEvidence.id}`}
        original={value.registrationEvidence}
        onDenied={onDenied}
        readable={readable}
      />
    </Space>
  );
}
export function TaxRevision({
  value,
  onDenied,
  readable,
}: {
  value: RevisionDetail;
  onDenied: () => void;
  readable: (e: unknown) => string;
}) {
  return (
    <Space
      orientation="vertical"
      style={{ width: "100%", overflowWrap: "anywhere" }}
    >
      <Alert
        type="info"
        title="以下为不可变修订及决定历史，拟启用状态不等于当前档案状态。"
      />
      <Descriptions
        style={{ overflowWrap: "anywhere", width: "100%" }}
        column={1}
        size="small"
        items={[
          { key: "id", label: "修订ID", children: value.revision.id },
          {
            key: "versions",
            label: "修订序号 / 内容版本",
            children: `${value.revision.revision} / ${value.revision.version}`,
          },
          {
            key: "status",
            label: "修订状态 / 拟发布状态",
            children: `${taxStatus(value.revision.status)} / ${taxStatus(value.revision.desiredStatus)}`,
          },
          {
            key: "maker",
            label: "制备人 / 账号",
            children: `${value.revision.preparedBy.personId} / ${value.revision.preparedBy.accountId}`,
          },
          {
            key: "digest",
            label: "冻结内容摘要",
            children: (
              <Typography.Text copyable>
                {value.revision.contentDigest}
              </Typography.Text>
            ),
          },
          {
            key: "decision",
            label: "独立决定",
            children: value.decision
              ? `${taxStatus(value.decision.outcome)} · ${value.decision.actor.personId} · ${value.decision.decidedAt} · ${value.decision.reason}`
              : "尚无决定",
          },
        ]}
      />
      <TaxProfile
        value={value.content}
        onDenied={onDenied}
        readable={readable}
      />
    </Space>
  );
}
export function TaxReceipt({
  value,
  replayed,
}: {
  value: CommandReceipt;
  replayed: boolean;
}) {
  return (
    <Descriptions
      style={{ overflowWrap: "anywhere", width: "100%" }}
      title="不可变命令收据（不是权威当前状态）"
      column={1}
      size="small"
      items={[
        {
          key: "id",
          label: "收据 / 目标修订",
          children: `${value.id} / ${value.revisionId}`,
        },
        {
          key: "action",
          label: "命令动作 / 历史重放",
          children: `${{ CREATE: "登记", PROPOSE_REVISION: "提出修订", APPROVE: "独立批准", REJECT: "独立驳回", CANCEL: "原账号取消" }[value.action]} / ${replayed ? "是" : "否"}`,
        },
        {
          key: "actor",
          label: "执行自然人 / 时间",
          children: `${value.actor.personId} / ${value.createdAt}`,
        },
        { key: "reason", label: "冻结理由", children: value.reason },
        {
          key: "digest",
          label: "目标版本 / 摘要",
          children: `${value.targetVersion} / ${value.contentDigest}`,
        },
      ]}
    />
  );
}

import {
  Alert,
  Button,
  Descriptions,
  Input,
  Select,
  Space,
  Table,
  Typography,
} from "antd";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { useEvidenceOriginal } from "./use-evidence-original";
import type {
  Actor,
  Basis,
  Blocker,
  Original,
} from "./evidence-usage-contract";
import { evidenceMoney } from "./evidence-usage-rules";
export type StoredEvidenceOriginal = {
  id: string;
  filename: string;
  sha256: string;
};
export type EvidenceUiProps = {
  scope: string;
  active: boolean;
  revision: number;
  newUseAllowed: boolean;
  canOwner: boolean;
  canMake: boolean;
  canReview: boolean;
  actor: Actor;
  originals: StoredEvidenceOriginal[];
  refreshOriginals: () => Promise<unknown>;
  onChanged: () => void;
  onReadDenied: () => void;
  onReadFailure: (message: string) => void;
  readable: (error: unknown) => string;
};
export function EvidenceBlockers({ items }: { items: Blocker[] }) {
  return items.length ? (
    <Alert
      type="warning"
      title="当前检查说明"
      description={
        <ul>
          {items.map((item, index) => (
            <li key={`${item.code}:${index}`}>{item.message}</li>
          ))}
        </ul>
      }
    />
  ) : null;
}
export function EvidenceOriginalPicker({
  value,
  onChange,
  ui,
}: {
  value?: string;
  onChange?: (id: string) => void;
  ui: EvidenceUiProps;
}) {
  const evidence = useEvidenceOriginal(
    ui.scope,
    ui.canMake && ui.newUseAllowed,
    ui.onReadDenied,
    ui.readable,
  );
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Select
        aria-label="选择真实已上传原件"
        value={value}
        onChange={onChange}
        showSearch
        optionFilterProp="label"
        style={{ width: "100%" }}
        options={ui.originals.map((original) => ({
          value: original.id,
          label: `${original.filename} · ${original.sha256}`,
        }))}
      />
      <label>
        上传实际原件字节（最多5MiB，保存不等于验真）：
        <input
          type="file"
          disabled={!ui.canMake || !ui.newUseAllowed || evidence.uploading}
          onChange={(event) => {
            const file = event.target.files?.[0];
            event.target.value = "";
            if (file)
              void evidence.upload(file, async (id) => {
                await ui.refreshOriginals();
                onChange?.(id);
              });
          }}
        />
      </label>
      {evidence.error && <Alert type="error" title={evidence.error} />}
    </Space>
  );
}
export function EvidenceOriginalView({
  original,
  ui,
}: {
  original: Original;
  ui: EvidenceUiProps;
}) {
  const evidence = useEvidenceOriginal(
    ui.scope,
    false,
    ui.onReadDenied,
    ui.readable,
  );
  const sameScope =
    ui.scope ===
    `/finance/v1/books/${original.bookId}/years/${original.yearId}`;
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Typography.Text style={{ overflowWrap: "anywhere" }}>
        {original.filename} · {original.originalId}
        <br />
        服务端SHA-256：{original.sha256}
        <br />
        实际上传账号：{original.createdBy}；原件字节未验真
      </Typography.Text>
      <Button
        disabled={!sameScope}
        onClick={() =>
          void evidence.download(original.originalId, original.filename)
        }
      >
        下载实际原件复核
      </Button>
      {!sameScope && (
        <Alert type="warning" title="本片不支持跨原账套、原年度引用或下载。" />
      )}
      {evidence.error && <Alert type="error" title={evidence.error} />}
    </Space>
  );
}
export function EvidenceBasisView({
  basis,
  ui,
}: {
  basis: Basis | null;
  ui: EvidenceUiProps;
}) {
  if (!basis)
    return (
      <Alert
        type="warning"
        title="尚无独立组织复核依据，面额与费用容量未知，不能当作零或无限额。"
      />
    );
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Descriptions
        bordered
        column={1}
        size="small"
        items={[
          {
            key: "version",
            label: "组织复核依据版本",
            children: basis.basisVersion,
          },
          {
            key: "identity",
            label: "永久单据身份",
            children: `${basis.issuerKey} / ${basis.externalIdentityKey}`,
          },
          {
            key: "gross",
            label: "单据面额（人民币）",
            children: evidenceMoney(basis.faceGross),
          },
          {
            key: "capacity",
            label: "允许费用归属上限（非自动报销额）",
            children: evidenceMoney(basis.expenseCapacity),
          },
          {
            key: "reviewer",
            label: "实际复核账号 / 人员 / 时间",
            children: `${basis.reviewedBy.accountId} / ${basis.reviewedBy.personId} / ${basis.reviewedAt}`,
          },
          {
            key: "source",
            label: "组织声明来源 / 原件位置",
            children: `${basis.sourceType === "PAPER_SCAN" ? "纸质扫描件" : "电子原件"} / ${basis.originalLocation ?? "无实体位置声明"}`,
          },
          {
            key: "assurance",
            label: "真实性边界",
            children:
              "仅ORG_REVIEWED面额依据组织复核；原件真实性未验证，不证明税务资格。",
          },
          {
            key: "digest",
            label: "依据内容摘要",
            children: (
              <Typography.Text style={{ overflowWrap: "anywhere" }}>
                {basis.contentDigest}
              </Typography.Text>
            ),
          },
        ]}
      />
      <Typography.Text strong>单据来源原件</Typography.Text>
      <EvidenceOriginalView original={basis.sourceOriginal} ui={ui} />
      <Typography.Text strong>组织复核依据原件</Typography.Text>
      <EvidenceOriginalView original={basis.basisOriginal} ui={ui} />
    </Space>
  );
}
export function EvidenceBuckets({
  buckets,
}: {
  buckets: {
    purpose: string;
    consumptionSupported: boolean;
    qualification: string;
    capacity: string | null;
    reserved: string | null;
    netCommitted: string | null;
    available: string | null;
    version: string;
  }[];
}) {
  const labels: Record<string, string> = {
    EXPENSE_ALLOCATION: "费用归属",
    SETTLEMENT: "结算分配",
    ACCOUNTING_CONFIRMATION: "会计确认",
  };
  return (
    <>
      <Alert
        type="info"
        title="三用途分别核算，不设跨环节占用总计"
        description="本费用归属协议仅核算费用用途；应付会计确认须进入独立应付协议，以其独立额度和真实消费为准。结算与税务仍未开放；未知金额不显示为0，费用上限不替代会计额度。"
      />
      <Table
        rowKey="purpose"
        size="small"
        pagination={false}
        dataSource={buckets}
        scroll={{ x: 1050 }}
        columns={[
          {
            title: "用途",
            width: 140,
            render: (_, row) => labels[row.purpose] ?? row.purpose,
          },
          {
            title: "资格",
            width: 220,
            render: (_, row) =>
              !row.consumptionSupported
                ? "尚未支持消费"
                : row.qualification === "ORG_REVIEWED"
                  ? "组织复核费用上限"
                  : "未知，不能消费",
          },
          {
            title: "容量",
            dataIndex: "capacity",
            width: 180,
            render: evidenceMoney,
          },
          {
            title: "真实未确认预留",
            dataIndex: "reserved",
            width: 180,
            render: evidenceMoney,
          },
          {
            title: "已确认净额",
            dataIndex: "netCommitted",
            width: 180,
            render: evidenceMoney,
          },
          {
            title: "当前可用额",
            dataIndex: "available",
            width: 180,
            render: evidenceMoney,
          },
          { title: "额度版本", dataIndex: "version", width: 120 },
        ]}
      />
    </>
  );
}
export function EvidenceReason() {
  return (
    <Form.Item
      name="reason"
      label="操作及组织核验说明"
      rules={[{ required: true, message: "请填写说明" }]}
    >
      <Input.TextArea maxLength={1000} rows={4} />
    </Form.Item>
  );
}

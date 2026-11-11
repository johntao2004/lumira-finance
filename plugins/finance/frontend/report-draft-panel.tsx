import { useEffect, useRef, useState } from "react";
import { Alert, Button, Card, Form, Input, Modal, Select, Space, Table, Tag, Typography } from "antd";

export type ReportMappingSummary = {
  id: string;
  mappingVersion: string;
  standard: string;
  status: string;
  version: string;
  templateApproved: boolean;
  reason: string;
};

export type ReportDraftSheet = {
  template: string;
  cells: {
    rowId: string;
    rawValue: string | null;
    status: string;
    gaps: string[];
  }[];
};

export type ReportDraftResult = {
  schema: string;
  status: string;
  sheets: ReportDraftSheet[];
  publicationGaps: string[];
};

type Option = { value: string; label: string };

type Props = {
  identity: string;
  scope: string;
  standard: string;
  yearNumber: number;
  active: boolean;
  canManage: boolean;
  canReview: boolean;
  accounts: Option[];
  read: <T>(url: string, signal: AbortSignal) => Promise<T>;
  write: <T>(url: string, body: Record<string, unknown>) => Promise<T>;
};

export function ReportDraftPanel(props: Props) {
  return <ReportDraftWorkbench key={props.identity} {...props} />;
}

function ReportDraftWorkbench(p: Props) {
  const base = `${p.scope}/report-drafts`;
  const [mappings, setMappings] = useState<ReportMappingSummary[]>([]);
  const [selectedMappingId, setSelectedMappingId] = useState("");
  const [draftResult, setDraftResult] = useState<ReportDraftResult | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [refresh, setRefresh] = useState(0);
  const [form] = Form.useForm();
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  useEffect(() => {
    if (!p.active || !p.scope) return;
    const ctrl = new AbortController();
    void p
      .read<{ items: ReportMappingSummary[] }>(`${base}/mappings`, ctrl.signal)
      .then((res) => {
        if (!ctrl.signal.aborted && alive.current) {
          setMappings(res.items);
          const approved = res.items.find((m) => m.status === "APPROVED");
          if (approved) setSelectedMappingId(approved.id);
          setError("");
        }
      })
      .catch((e: unknown) => {
        if (!ctrl.signal.aborted && alive.current) setError(String(e));
      });
    return () => ctrl.abort();
  }, [p.active, p.scope, p.identity, p.read, base, refresh]);

  async function createMapping() {
    if (busy) return;
    try {
      const v = await form.validateFields();
      setBusy(true);
      setError("");
      const defaultRow =
        v.semantic === "FIXED_ASSET"
          ? p.standard === "SMALL"
            ? "16"
            : "A10"
          : p.standard === "SMALL"
            ? "1"
            : "A01";
      const items: Record<string, unknown>[] = [
        { accountId: v.accountId, targetKind: "BS", semantic: v.semantic },
      ];
      if (v.contraAccountId && v.contraType) {
        items.push({
          accountId: v.contraAccountId,
          targetKind: "CONTRA",
          semantic: v.contraType,
          contraType: v.contraType,
          targetAccountId: v.accountId,
        });
      }
      await p.write(`${base}/mappings`, {
        mappingVersion: v.mappingVersion,
        mappings: items,
        certifiedRows: [{ kind: "BS", rowId: defaultRow }],
        reason: v.reason,
      });
      if (alive.current) {
        setCreateOpen(false);
        setRefresh((x) => x + 1);
      }
    } catch (e: unknown) {
      if (alive.current) setError(e instanceof Error ? e.message : String(e));
    } finally {
      if (alive.current) setBusy(false);
    }
  }

  async function approveMapping(m: ReportMappingSummary) {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      await p.write(`${base}/mappings/${m.id}/approve`, {
        expectedVersion: Number(m.version),
        reason: "独立自然人复核批准三表底稿科目语义映射",
      });
      if (alive.current) setRefresh((x) => x + 1);
    } catch (e: unknown) {
      if (alive.current) setError(e instanceof Error ? e.message : String(e));
    } finally {
      if (alive.current) setBusy(false);
    }
  }

  async function evaluateDraft() {
    if (busy || !selectedMappingId) return;
    setBusy(true);
    setError("");
    try {
      const res = await p.write<ReportDraftResult>(`${base}/evaluate`, {
        mappingId: selectedMappingId,
        from: `${p.yearNumber}-01-01`,
        through: `${p.yearNumber}-12-31`,
      });
      if (alive.current) setDraftResult(res);
    } catch (e: unknown) {
      if (alive.current) setError(e instanceof Error ? e.message : String(e));
    } finally {
      if (alive.current) setBusy(false);
    }
  }

  return (
    <Card
      title="受限三表取数底稿试算（ReportSourceEngine · BLOCKED_DRAFT）"
      size="small"
      style={{ marginTop: 16 }}
    >
      <Space orientation="vertical" style={{ width: "100%" }}>
        <Alert
          type="info"
          title="领域取数引擎预览（支持备抵科目抵减 · 不构成正式法定财务报表发布）"
          description="基于独立批准的科目语义映射（含固定资产累计折旧/无形资产累计摊销等 CONTRA 备抵科目映射）与一致总账水位计算单列精确底稿（CAS_2019 / SBE_2011），自动排除损益结转分录，保留行完备性声明与缺口诊断。"
        />
        {error && <Alert type="error" title={error} />}
        <Space wrap>
          <Button
            disabled={!p.canManage || busy || p.accounts.length === 0}
            onClick={() => {
              form.setFieldsValue({
                mappingVersion: `RPT-MAP-${p.yearNumber}-V1`,
                accountId: p.accounts[0]?.value,
                semantic: "CASH",
                contraAccountId: undefined,
                contraType: undefined,
                reason: "配置货币资金科目语义映射用于底稿试算",
              });
              setCreateOpen(true);
            }}
          >
            新增科目语义映射
          </Button>
          <Select
            aria-label="已批准报表映射"
            placeholder="选择已批准报表映射版本"
            style={{ minWidth: 260 }}
            value={selectedMappingId || undefined}
            onChange={setSelectedMappingId}
            options={mappings
              .filter((m) => m.status === "APPROVED")
              .map((m) => ({
                value: m.id,
                label: `${m.mappingVersion} (${m.standard})`,
              }))}
          />
          <Button
            type="primary"
            disabled={!selectedMappingId || busy}
            onClick={() => void evaluateDraft()}
          >
            试算三表取数底稿
          </Button>
        </Space>

        <Table<ReportMappingSummary>
          rowKey="id"
          size="small"
          pagination={false}
          dataSource={mappings}
          columns={[
            { title: "映射版本", dataIndex: "mappingVersion" },
            { title: "制度模板", dataIndex: "standard" },
            { title: "状态", dataIndex: "status", render: (s: string) => <Tag>{s}</Tag> },
            {
              title: "操作",
              render: (_, m) =>
                m.status === "PENDING" ? (
                  <Button
                    size="small"
                    disabled={!p.canReview || busy}
                    onClick={() => void approveMapping(m)}
                  >
                    独立批准映射
                  </Button>
                ) : null,
            },
          ]}
        />

        {draftResult && (
          <Space orientation="vertical" style={{ width: "100%" }}>
            <Typography.Text strong>
              底稿引擎状态：{draftResult.status} · 阻断项数：{draftResult.publicationGaps.length}
            </Typography.Text>
            {draftResult.sheets.map((sh) => (
              <Card key={sh.template} title={sh.template} size="small">
                <Table
                  rowKey="rowId"
                  size="small"
                  pagination={{ pageSize: 8 }}
                  dataSource={sh.cells.filter((c) => c.status !== "MISSING").length > 0
                    ? sh.cells.filter((c) => c.status !== "MISSING")
                    : sh.cells.slice(0, 6)}
                  columns={[
                    { title: "行次", dataIndex: "rowId" },
                    { title: "状态", dataIndex: "status", render: (s: string) => <Tag>{s}</Tag> },
                    { title: "精确原值", render: (_, c) => c.rawValue ?? "MISSING" },
                    { title: "缺口诊断", render: (_, c) => c.gaps.join("; ") || "已覆盖底稿草稿" },
                  ]}
                />
              </Card>
            ))}
          </Space>
        )}

        <Modal
          title="新增报表科目语义映射"
          open={createOpen}
          confirmLoading={busy}
          destroyOnHidden={false}
          onCancel={() => !busy && setCreateOpen(false)}
          onOk={() => void createMapping()}
        >
          <Form form={form} layout="vertical">
            <Form.Item name="mappingVersion" label="映射版本号" rules={[{ required: true }]}>
              <Input />
            </Form.Item>
            <Form.Item name="accountId" label="本年度主科目" rules={[{ required: true }]}>
              <Select options={p.accounts} showSearch optionFilterProp="label" />
            </Form.Item>
            <Form.Item name="semantic" label="领域语义（BS）" rules={[{ required: true }]}>
              <Select
                options={[
                  { value: "CASH", label: "货币资金（CASH）" },
                  { value: "RECEIVABLE", label: "应收账款（RECEIVABLE）" },
                  { value: "PAYABLE", label: "应付账款（PAYABLE）" },
                  { value: "FIXED_ASSET", label: "固定资产（FIXED_ASSET）" },
                  { value: "INTANGIBLE", label: "无形资产（INTANGIBLE）" },
                  { value: "CAPITAL", label: "实收资本（CAPITAL）" },
                ]}
              />
            </Form.Item>
            <Form.Item name="contraAccountId" label="关联备抵科目（可选，如累计折旧/累计摊销）">
              <Select allowClear options={p.accounts} showSearch optionFilterProp="label" />
            </Form.Item>
            <Form.Item name="contraType" label="备抵类型（可选，CONTRA）">
              <Select
                allowClear
                options={[
                  { value: "DEPRECIATION", label: "累计折旧（DEPRECIATION）" },
                  { value: "AMORTIZATION", label: "累计摊销（AMORTIZATION）" },
                  { value: "IMPAIRMENT", label: "资产减值准备（IMPAIRMENT）" },
                ]}
              />
            </Form.Item>
            <Form.Item name="reason" label="编制说明" rules={[{ required: true }]}>
              <Input.TextArea />
            </Form.Item>
          </Form>
        </Modal>
      </Space>
    </Card>
  );
}

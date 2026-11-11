import { useEffect, useRef, useState } from "react";
import { Alert, Button, Card, Form, Input, Modal, Select, Space, Table, Tag, Typography } from "antd";

export type TaxBindingItem = {
  taxEntityId: string;
  bookId: string;
  sourceRole: string;
  mappingVersion: string;
  effectiveFrom: string;
  status: string;
  version: string;
};

export type TaxDeclarationItem = {
  id: string;
  revision: string;
  version: string;
  kind: string;
  predecessorId: string | null;
  state: string;
  dataDigest: string;
  activeCommandId: string | null;
  receiptRef: string | null;
  paymentState: string;
  officialCapability: string;
  reason: string;
};

export type TaxObligationItem = {
  id: string;
  taxEntityId: string;
  authority: string;
  filingUnit: string;
  role: string;
  periodStart: string;
  periodEnd: string;
  occurrence: string;
  scopeCode: string;
  obligationStatus: string;
  version: string;
  acceptedDeclarationId: string | null;
  declarations: TaxDeclarationItem[];
};

export type TaxObligationsOverview = {
  bindings: TaxBindingItem[];
  obligations: TaxObligationItem[];
  officialCapability: string;
  paymentState: string;
};

export type TaxWorksheetItem = {
  id: string;
  yearId: string;
  taxEntityId: string;
  worksheetCode: string;
  kind: string;
  periodStart: string;
  periodEnd: string;
  ruleVersion: string;
  basisOriginalId: string;
  basisOriginalSha256: string;
  inputs: Record<string, unknown>;
  outputs: Record<string, unknown>;
  worksheetDigest: string;
  status: string;
  version: string;
  reason: string;
  officialCapability: string;
  paymentState: string;
};

type Option = { value: string; label: string };

type Props = {
  identity: string;
  bookId: string;
  yearId: string;
  yearNumber: number;
  active: boolean;
  canManage: boolean;
  canCreate: boolean;
  canReview: boolean;
  canPost: boolean;
  taxEntities: Option[];
  originals: Option[];
  read: <T>(url: string, signal: AbortSignal) => Promise<T>;
  write: <T>(url: string, body: Record<string, unknown>) => Promise<T>;
};

export function TaxObligationPanel(props: Props) {
  return <TaxObligationWorkbench key={props.identity} {...props} />;
}

function TaxObligationWorkbench(p: Props) {
  const root = `/finance/v1/books/${p.bookId}/tax-obligations`;
  const [data, setData] = useState<TaxObligationsOverview | null>(null);
  const [worksheets, setWorksheets] = useState<TaxWorksheetItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [refresh, setRefresh] = useState(0);
  const [bindingOpen, setBindingOpen] = useState(false);
  const [worksheetOpen, setWorksheetOpen] = useState(false);
  const [buildOpen, setBuildOpen] = useState(false);
  const [reconcileTarget, setReconcileTarget] = useState<{
    obligation: TaxObligationItem;
    declaration: TaxDeclarationItem;
  } | null>(null);
  const [bindForm] = Form.useForm();
  const [worksheetForm] = Form.useForm();
  const [buildForm] = Form.useForm();
  const [reconcileForm] = Form.useForm();
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  useEffect(() => {
    if (!p.active || !p.bookId || !p.yearId) return;
    const ctrl = new AbortController();
    setLoading(true);
    void Promise.all([
      p.read<TaxObligationsOverview>(root, ctrl.signal),
      p.read<{ items: TaxWorksheetItem[] }>(
        `${root}/worksheets?yearId=${encodeURIComponent(p.yearId)}`,
        ctrl.signal,
      ),
    ])
      .then(([res, ws]) => {
        if (!ctrl.signal.aborted && alive.current) {
          setData(res);
          setWorksheets(ws.items ?? []);
          setError("");
        }
      })
      .catch((e: unknown) => {
        if (!ctrl.signal.aborted && alive.current) setError(String(e));
      })
      .finally(() => {
        if (!ctrl.signal.aborted && alive.current) setLoading(false);
      });
    return () => ctrl.abort();
  }, [p.active, p.bookId, p.yearId, p.identity, p.read, root, refresh]);

  async function exec(url: string, body: Record<string, unknown>, onDone?: () => void) {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      await p.write(url, { ...body, idempotencyKey: `tax-${crypto.randomUUID()}` });
      if (alive.current) {
        onDone?.();
        setRefresh((x) => x + 1);
      }
    } catch (e: unknown) {
      if (alive.current) setError(e instanceof Error ? e.message : String(e));
    } finally {
      if (alive.current) setBusy(false);
    }
  }

  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Alert
        type="warning"
        title="纳税申报义务、税费计算底稿与人工真实回执核销（TAX01–TAX06）"
        description="基于已激活纳税主体与独立批准的账套税务来源绑定，编制并审签确定性税费计算底稿（TAX02–TAX04），冻结账套年度水位构建申报底稿（TAX05），经独立自然人审签、线下交接锁定（UNKNOWN）后，由独立第三人核验真实税务回执原件（TAX06）。官方直连通道 NOT_VERIFIED，税款缴纳 NOT_ASSESSED。"
      />
      {error && <Alert type="error" title={error} />}
      <Space wrap>
        <Button disabled={busy || loading} onClick={() => setRefresh((x) => x + 1)}>
          刷新列表
        </Button>
        <Button
          disabled={!p.canManage || busy}
          onClick={() => {
            bindForm.setFieldsValue({
              taxEntityId: p.taxEntities[0]?.value,
              sourceRole: "STATUTORY_SOURCE",
              mappingVersion: `TAX-MAP-${p.yearNumber}-V1`,
              effectiveFrom: `${p.yearNumber}-01-01`,
            });
            setBindingOpen(true);
          }}
        >
          登记账套税务绑定
        </Button>
        <Button
          disabled={!p.canCreate || busy}
          onClick={() => {
            worksheetForm.setFieldsValue({
              taxEntityId: p.taxEntities[0]?.value,
              worksheetCode: `WS-${p.yearNumber}-${worksheets.length + 1}`,
              kind: "VAT_SMALL",
              periodStart: `${p.yearNumber}-01-01`,
              periodEnd: `${p.yearNumber}-03-31`,
              ruleVersion: `TAX-RULE-${p.yearNumber}-V1`,
              basisOriginalId: p.originals[0]?.value,
              quarterCode: `${p.yearNumber}-Q1`,
              taxableSalesExcludingVat: "250000.00",
              applicableRate: "0.01",
              smallScaleExemptApplied: false,
              taxReductionAmount: "500.00",
              reason: "基于计税依据原件编制税费计算底稿（TAX02–TAX04）",
            });
            setWorksheetOpen(true);
          }}
        >
          编制税费计算底稿
        </Button>
        <Button
          type="primary"
          disabled={!p.canCreate || busy}
          onClick={() => {
            const approvedWs = worksheets.find((w) => w.status === "APPROVED");
            buildForm.setFieldsValue({
              taxEntityId: approvedWs?.taxEntityId ?? p.taxEntities[0]?.value,
              authority: "CN-STA-LOCAL",
              filingUnit: "VAT-GENERAL-MONTHLY",
              role: "SELF",
              periodStart: approvedWs?.periodStart ?? `${p.yearNumber}-01-01`,
              periodEnd: approvedWs?.periodEnd ?? `${p.yearNumber}-01-31`,
              occurrence: `${p.yearNumber}-01`,
              scopeCode: "MAIN",
              kind: "INITIAL",
              worksheetDigest: approvedWs?.worksheetDigest ?? "a".repeat(64),
              reason: "冻结账套水位构建纳税申报底稿",
            });
            setBuildOpen(true);
          }}
        >
          构建申报义务底稿
        </Button>
      </Space>

      <Card title="已登记账套税务来源绑定" size="small">
        <Table<TaxBindingItem>
          rowKey={(r) => `${r.taxEntityId}:${r.bookId}`}
          loading={loading}
          dataSource={data?.bindings ?? []}
          pagination={false}
          size="small"
          columns={[
            { title: "纳税主体", dataIndex: "taxEntityId" },
            { title: "来源角色", dataIndex: "sourceRole" },
            { title: "映射版本", dataIndex: "mappingVersion" },
            { title: "生效日", dataIndex: "effectiveFrom" },
            { title: "状态", dataIndex: "status", render: (s: string) => <Tag>{s}</Tag> },
            {
              title: "操作",
              render: (_, r) =>
                r.status === "PENDING" ? (
                  <Button
                    size="small"
                    disabled={!p.canReview || busy}
                    onClick={() =>
                      void exec(`${root}/book-bindings/${r.taxEntityId}/approve`, {
                        expectedVersion: Number(r.version),
                      })
                    }
                  >
                    独立批准绑定
                  </Button>
                ) : null,
            },
          ]}
        />
      </Card>

      <Card title="税费计算底稿（TAX02–TAX04 · 原件哈希与确定性规则计算）" size="small">
        <Table<TaxWorksheetItem>
          rowKey="id"
          loading={loading}
          dataSource={worksheets}
          pagination={false}
          size="small"
          scroll={{ x: 960 }}
          columns={[
            { title: "底稿编码", dataIndex: "worksheetCode" },
            { title: "税种底稿类型", dataIndex: "kind" },
            { title: "所属期", render: (_, r) => `${r.periodStart} ~ ${r.periodEnd}` },
            { title: "规则版本", dataIndex: "ruleVersion" },
            {
              title: "计算结果摘要",
              render: (_, r) =>
                String(
                  r.outputs?.payableVat ??
                    r.outputs?.totalVatAndSurcharges ??
                    r.outputs?.currentPrepayCit ??
                    "—",
                ),
            },
            {
              title: "底稿哈希摘要",
              render: (_, r) => <Typography.Text code>{r.worksheetDigest.slice(0, 12)}…</Typography.Text>,
            },
            { title: "状态", dataIndex: "status", render: (s: string) => <Tag>{s}</Tag> },
            {
              title: "操作",
              render: (_, w) =>
                w.status === "PENDING" ? (
                  <Button
                    size="small"
                    disabled={!p.canReview || busy}
                    onClick={() =>
                      void exec(`${root}/worksheets/${w.id}/approve`, {
                        yearId: p.yearId,
                        expectedVersion: Number(w.version),
                        reason: "独立自然人审签税费计算底稿",
                      })
                    }
                  >
                    独立批准计算底稿
                  </Button>
                ) : null,
            },
          ]}
        />
      </Card>

      <Card title="纳税申报义务与版本状态机（TAX05 / TAX06）" size="small">
        <Table<TaxObligationItem>
          rowKey="id"
          loading={loading}
          dataSource={data?.obligations ?? []}
          pagination={false}
          scroll={{ x: 1000 }}
          columns={[
            { title: "主管机关 / 申报单元", render: (_, r) => `${r.authority} / ${r.filingUnit}` },
            { title: "所属期", render: (_, r) => `${r.periodStart} ~ ${r.periodEnd}` },
            { title: "义务版本", dataIndex: "version" },
            {
              title: "最新底稿状态",
              render: (_, r) => {
                const d = r.declarations[0];
                return d ? (
                  <Space>
                    <Tag>{d.state}</Tag>
                    <Typography.Text type="secondary">
                      rev#{d.revision} · {d.officialCapability} / {d.paymentState}
                    </Typography.Text>
                  </Space>
                ) : (
                  "—"
                );
              },
            },
            {
              title: "操作",
              render: (_, ob) => {
                const d = ob.declarations[0];
                if (!d) return null;
                const base = `${root}/${ob.id}/declarations/${d.id}`;
                const expVer = Number(ob.version);
                return (
                  <Space wrap>
                    {d.state === "DRAFT" && (
                      <Button
                        size="small"
                        disabled={!p.canCreate || busy}
                        onClick={() =>
                          void exec(`${base}/review`, {
                            expectedObligationVersion: expVer,
                            reason: "提交申报底稿复核",
                          })
                        }
                      >
                        提交复核
                      </Button>
                    )}
                    {d.state === "READY_FOR_REVIEW" && (
                      <Button
                        size="small"
                        disabled={!p.canReview || busy}
                        onClick={() =>
                          void exec(`${base}/approve`, {
                            expectedObligationVersion: expVer,
                            reason: "独立自然人批准申报底稿",
                          })
                        }
                      >
                        独立批准
                      </Button>
                    )}
                    {d.state === "APPROVED" && (
                      <Button
                        size="small"
                        disabled={!p.canPost || busy}
                        onClick={() =>
                          void exec(`${base}/handoff`, {
                            expectedObligationVersion: expVer,
                            reason: "转入人工线下申报交接槽位",
                          })
                        }
                      >
                        线下交接锁定
                      </Button>
                    )}
                    {d.state === "UNKNOWN" && (
                      <Button
                        size="small"
                        type="primary"
                        disabled={!p.canReview || busy}
                        onClick={() => {
                          reconcileForm.setFieldsValue({
                            receiptOriginalId: p.originals[0]?.value,
                            providerReference: `STA-RCPT-${ob.id.slice(0, 8)}`,
                            outcome: "ACCEPTED",
                            evidenceRef: "组织独立核验主管税务机关受理回执原件",
                          });
                          setReconcileTarget({ obligation: ob, declaration: d });
                        }}
                      >
                        核销真实回执
                      </Button>
                    )}
                  </Space>
                );
              },
            },
          ]}
        />
      </Card>

      <Modal
        title="登记纳税主体与账套来源授权绑定"
        open={bindingOpen}
        confirmLoading={busy}
        destroyOnHidden={false}
        onCancel={() => !busy && setBindingOpen(false)}
        onOk={() =>
          void bindForm.validateFields().then((v) =>
            exec(`${root}/book-bindings`, v, () => setBindingOpen(false)),
          )
        }
      >
        <Form form={bindForm} layout="vertical">
          <Form.Item name="taxEntityId" label="已激活纳税主体" rules={[{ required: true }]}>
            <Select options={p.taxEntities} />
          </Form.Item>
          <Form.Item name="sourceRole" label="来源账套角色" rules={[{ required: true }]}>
            <Select
              options={[
                { value: "STATUTORY_SOURCE", label: "法定核算主来源（STATUTORY_SOURCE）" },
                { value: "AUTHORIZED_ADJUSTMENT_SOURCE", label: "授权纳税调整来源（AUTHORIZED_ADJUSTMENT_SOURCE）" },
                { value: "READ_ONLY_REFERENCE", label: "只读参考账套（READ_ONLY_REFERENCE）" },
              ]}
            />
          </Form.Item>
          <Form.Item name="mappingVersion" label="税务取数映射版本" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="effectiveFrom" label="生效日期" rules={[{ required: true }]}>
            <Input type="date" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="编制确定性税费计算底稿（TAX02–TAX04）"
        open={worksheetOpen}
        confirmLoading={busy}
        destroyOnHidden={false}
        onCancel={() => !busy && setWorksheetOpen(false)}
        onOk={() =>
          void worksheetForm.validateFields().then((v) => {
            const inputs =
              v.kind === "VAT_GENERAL_SURCHARGE"
                ? {
                    periodCode: v.quarterCode || `${p.yearNumber}-01`,
                    outputTax: v.taxableSalesExcludingVat || "26000.00",
                    certifiedInputTax: "10000.00",
                    inputTaxTransferOut: "1000.00",
                    priorEndingCredit: "2000.00",
                    urbanConstructionRate: "0.07",
                    educationSurchargeRate: "0.03",
                    localEducationSurchargeRate: "0.02",
                    halfSurchargeApplied: false,
                    stampDutyTaxableBase: "500000.00",
                    stampDutyRate: "0.0003",
                  }
                : v.kind === "CIT_ADJUSTMENT_AND_PREPAY"
                  ? {
                      periodCode: v.quarterCode || `${p.yearNumber}-Q1`,
                      accountingProfitYtd: v.taxableSalesExcludingVat || "100000.00",
                      nonDeductibleExpenseAddBack: "20000.00",
                      taxExemptIncomeDeduction: "5000.00",
                      acceleratedDepreciationDeduction: "10000.00",
                      priorLossOffset: "5000.00",
                      statutoryTaxRate: "0.25",
                      taxCreditReduction: "3000.00",
                      alreadyPrepaidYtd: "12000.00",
                    }
                  : {
                      quarterCode: v.quarterCode || `${p.yearNumber}-Q1`,
                      taxableSalesExcludingVat: v.taxableSalesExcludingVat || "250000.00",
                      applicableRate: v.applicableRate || "0.01",
                      smallScaleExemptApplied: Boolean(v.smallScaleExemptApplied),
                      taxReductionAmount: v.taxReductionAmount || "500.00",
                    };
            return exec(
              `${root}/worksheets`,
              {
                yearId: p.yearId,
                taxEntityId: v.taxEntityId,
                worksheetCode: v.worksheetCode,
                kind: v.kind,
                periodStart: v.periodStart,
                periodEnd: v.periodEnd,
                ruleVersion: v.ruleVersion,
                basisOriginalId: v.basisOriginalId,
                inputs,
                reason: v.reason,
              },
              () => setWorksheetOpen(false),
            );
          })
        }
      >
        <Form form={worksheetForm} layout="vertical">
          <Form.Item name="taxEntityId" label="已激活纳税主体" rules={[{ required: true }]}>
            <Select options={p.taxEntities} />
          </Form.Item>
          <Form.Item name="worksheetCode" label="计税底稿编码" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="kind" label="税费计算底稿类型" rules={[{ required: true }]}>
            <Select
              options={[
                { value: "VAT_SMALL", label: "小规模纳税人增值税（VAT_SMALL · TAX02）" },
                {
                  value: "VAT_GENERAL_SURCHARGE",
                  label: "一般纳税人增值税销进项与附加税/印花税（VAT_GENERAL_SURCHARGE · TAX02/TAX03）",
                },
                {
                  value: "CIT_ADJUSTMENT_AND_PREPAY",
                  label: "企业所得税纳税调整与季度预缴（CIT_ADJUSTMENT_AND_PREPAY · TAX04）",
                },
              ]}
            />
          </Form.Item>
          <Form.Item name="basisOriginalId" label="计税依据原始凭证" rules={[{ required: true }]}>
            <Select options={p.originals} />
          </Form.Item>
          <Space>
            <Form.Item name="periodStart" label="所属期起" rules={[{ required: true }]}>
              <Input type="date" />
            </Form.Item>
            <Form.Item name="periodEnd" label="所属期止" rules={[{ required: true }]}>
              <Input type="date" />
            </Form.Item>
          </Space>
          <Space wrap>
            <Form.Item name="ruleVersion" label="税法计算规则版本" rules={[{ required: true }]}>
              <Input />
            </Form.Item>
            <Form.Item name="quarterCode" label="期间标识">
              <Input />
            </Form.Item>
          </Space>
          <Space wrap>
            <Form.Item name="taxableSalesExcludingVat" label="不含税销售额 / 销项或利润基数">
              <Input />
            </Form.Item>
            <Form.Item name="applicableRate" label="征收率">
              <Input />
            </Form.Item>
            <Form.Item name="taxReductionAmount" label="税额减免">
              <Input />
            </Form.Item>
          </Space>
          <Form.Item name="reason" label="计算底稿编制说明" rules={[{ required: true }]}>
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="构建纳税申报义务底稿"
        open={buildOpen}
        confirmLoading={busy}
        destroyOnHidden={false}
        onCancel={() => !busy && setBuildOpen(false)}
        onOk={() =>
          void buildForm.validateFields().then((v) =>
            exec(
              root,
              {
                taxEntityId: v.taxEntityId,
                authority: v.authority,
                filingUnit: v.filingUnit,
                role: v.role,
                periodStart: v.periodStart,
                periodEnd: v.periodEnd,
                occurrence: v.occurrence,
                scopeCode: v.scopeCode,
                kind: v.kind,
                predecessorDeclarationId: null,
                formVersion: crypto.randomUUID(),
                rules: [crypto.randomUUID()],
                adjustments: [],
                worksheetDigest: v.worksheetDigest || "a".repeat(64),
                sources: [{ bookId: p.bookId, yearId: p.yearId }],
                reason: v.reason,
              },
              () => setBuildOpen(false),
            ),
          )
        }
      >
        <Form form={buildForm} layout="vertical">
          <Form.Item name="taxEntityId" label="纳税主体" rules={[{ required: true }]}>
            <Select options={p.taxEntities} />
          </Form.Item>
          <Form.Item name="authority" label="主管税务机关标识" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="filingUnit" label="税种申报单元编码" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="role" label="纳税义务角色" rules={[{ required: true }]}>
            <Select
              options={[
                { value: "SELF", label: "自行申报纳税人（SELF）" },
                { value: "WITHHOLDING", label: "扣缴义务人（WITHHOLDING）" },
              ]}
            />
          </Form.Item>
          <Space>
            <Form.Item name="periodStart" label="税款所属期起" rules={[{ required: true }]}>
              <Input type="date" />
            </Form.Item>
            <Form.Item name="periodEnd" label="税款所属期止" rules={[{ required: true }]}>
              <Input type="date" />
            </Form.Item>
          </Space>
          <Space>
            <Form.Item name="occurrence" label="申报频次期号" rules={[{ required: true }]}>
              <Input />
            </Form.Item>
            <Form.Item name="scopeCode" label="汇总范围编码" rules={[{ required: true }]}>
              <Input />
            </Form.Item>
          </Space>
          <Form.Item name="kind" label="申报类型" rules={[{ required: true }]}>
            <Select options={[{ value: "INITIAL", label: "首次申报（INITIAL）" }]} />
          </Form.Item>
          <Form.Item
            name="worksheetDigest"
            label="已批准计算底稿摘要（SHA-256）"
            rules={[{ required: true, pattern: /^[a-f0-9]{64}$/ }]}
          >
            <Input />
          </Form.Item>
          <Form.Item name="reason" label="底稿编制说明" rules={[{ required: true }]}>
            <Input.TextArea />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="独立核验真实税务回执原件"
        open={!!reconcileTarget}
        confirmLoading={busy}
        destroyOnHidden={false}
        onCancel={() => !busy && setReconcileTarget(null)}
        onOk={() => {
          if (!reconcileTarget) return;
          void reconcileForm.validateFields().then((v) =>
            exec(
              `${root}/${reconcileTarget.obligation.id}/declarations/${reconcileTarget.declaration.id}/reconcile-receipt`,
              {
                expectedObligationVersion: Number(reconcileTarget.obligation.version),
                receiptYearId: p.yearId,
                receiptOriginalId: v.receiptOriginalId,
                providerReference: v.providerReference,
                outcome: v.outcome,
                evidenceRef: v.evidenceRef,
              },
              () => setReconcileTarget(null),
            ),
          );
        }}
      >
        <Form form={reconcileForm} layout="vertical">
          <Form.Item
            name="receiptOriginalId"
            label="已上传回执原件（核验人须独立于制单人与上传人）"
            rules={[{ required: true }]}
          >
            <Select options={p.originals} />
          </Form.Item>
          <Form.Item name="providerReference" label="主管机关唯一回执编号" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="outcome" label="核验结论" rules={[{ required: true }]}>
            <Select
              options={[
                { value: "ACCEPTED", label: "已受理（ACCEPTED）" },
                { value: "REJECTED", label: "已退回（REJECTED）" },
              ]}
            />
          </Form.Item>
          <Form.Item name="evidenceRef" label="人工核验记录说明" rules={[{ required: true }]}>
            <Input.TextArea />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}

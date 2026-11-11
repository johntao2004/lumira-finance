import { useEffect, useRef, useState } from "react";
import { Alert, Button, Card, Form, Input, Modal, Select, Space, Table, Tag, Typography } from "antd";
import {
  payableSettlementNextActions,
  validatePayableSettlementAmount,
} from "./payable-settlement-rules";

export type PayableOpenItemSummary = {
  id: string;
  runId: string;
  supplierId: string;
  supplierRevisionId: string;
  payableAccountId: string;
  amount: string;
  settledAmount: string;
  reservedAmount: string;
  availableAmount: string;
  dueDate: string | null;
  reversed: boolean;
  aging: string;
};

export type PayableSettlementBasisItem = {
  id: string;
  originalId: string;
  originalSha256: string;
  purpose: string;
  supplierId: string;
  supplierRevisionId: string;
  payeeAccountLabel: string;
  bankChannel: string;
  capacity: string;
  usedAmount: string;
  availableAmount: string;
  status: string;
  version: string;
  reason: string;
};

export type PayableSettlementOrderItem = {
  id: string;
  kind: "PAYMENT" | "REVERSAL";
  state: "DRAFT" | "SUBMITTED" | "APPROVED" | "POSTED" | "CANCELLED";
  version: string;
  openItemId: string;
  payableRunId: string;
  settlementBasisId: string;
  supplierId: string;
  payableAccountId: string;
  cashAccountId: string;
  bankSlipOriginalId: string;
  paymentInstructionRef: string;
  verificationMode: string;
  bankChannel: string;
  accountingDate: string;
  amount: string;
  summary: string;
  journalId: string | null;
  postingSeq: string | null;
  reversesId: string | null;
  reversedById: string | null;
};

export type PayableSettlementsOverview = {
  asOf: string;
  openItems: PayableOpenItemSummary[];
  bases: PayableSettlementBasisItem[];
  orders: PayableSettlementOrderItem[];
  bankChannel: string;
  officialCapability: string;
};

type Option = { value: string; label: string };

type Props = {
  identity: string;
  scope: string;
  yearNumber: number;
  active: boolean;
  canCreate: boolean;
  canReview: boolean;
  canPost: boolean;
  suppliers: Option[];
  cashAccounts: Option[];
  originals: Option[];
  read: <T>(url: string, signal: AbortSignal) => Promise<T>;
  write: <T>(url: string, body: Record<string, unknown>) => Promise<T>;
};

export function PayableSettlementPanel(props: Props) {
  return <PayableSettlementWorkbench key={props.identity} {...props} />;
}

function PayableSettlementWorkbench(p: Props) {
  const root = `${p.scope}/payable-settlements`;
  const [data, setData] = useState<PayableSettlementsOverview | null>(null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [refresh, setRefresh] = useState(0);
  const [basisOpen, setBasisOpen] = useState(false);
  const [orderOpen, setOrderOpen] = useState(false);
  const [reverseTarget, setReverseTarget] = useState<PayableSettlementOrderItem | null>(null);
  const [basisForm] = Form.useForm();
  const [orderForm] = Form.useForm();
  const [reverseForm] = Form.useForm();
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
    setLoading(true);
    void p
      .read<PayableSettlementsOverview>(root, ctrl.signal)
      .then((res) => {
        if (!ctrl.signal.aborted && alive.current) {
          setData(res);
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
  }, [p.active, p.scope, p.identity, p.read, root, refresh]);

  async function exec(url: string, body: Record<string, unknown>, onDone?: () => void) {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      await p.write(url, { ...body, idempotencyKey: `ap02-${crypto.randomUUID()}` });
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

  const approvedBases = (data?.bases ?? []).filter((b) => b.status === "APPROVED");
  const openItems = (data?.openItems ?? []).filter((i) => !i.reversed);

  return (
    <Card
      title="应付结算与人工银行水单核销（AP02 / EV03 / TR01 / TR02）"
      size="small"
      style={{ marginTop: 16 }}
    >
      <Space orientation="vertical" style={{ width: "100%" }}>
        <Alert
          type="warning"
          title="受限应付清偿、结算证据配额与原生 AP_PAYMENT / AP_PAYMENT_REVERSAL 记账"
          description="基于已记账 AP01 应付未清项与独立批准的 EV03 结算证据配额，结合人工上传并经 SHA-256 校验的真实银行水单原件执行全额或分次清偿，自动生成不可手工伪造的 AP_PAYMENT / AP_PAYMENT_REVERSAL 凭证。存在下游未冲回结算时自动阻断上游 AP01 冲销（AP_DEPENDENCY_PRESENT）。银企直联通道 NOT_VERIFIED。"
        />
        {error && <Alert type="error" title={error} />}
        <Space wrap>
          <Button disabled={busy || loading} onClick={() => setRefresh((x) => x + 1)}>
            刷新结算台账
          </Button>
          <Button
            disabled={!p.canCreate || busy}
            onClick={() => {
              basisForm.setFieldsValue({
                supplierId: openItems[0]?.supplierId ?? p.suppliers[0]?.value,
                originalId: p.originals[0]?.value,
                payeeAccountLabel: "供应商离线核对收款账户（银企直联 NOT_VERIFIED）",
                capacity: openItems[0]?.availableAmount ?? "500.00",
                reason: "登记应付结算协议与付款核定配额（EV03）",
              });
              setBasisOpen(true);
            }}
          >
            登记结算证据配额（EV03）
          </Button>
          <Button
            type="primary"
            disabled={!p.canCreate || busy || openItems.length === 0 || approvedBases.length === 0}
            onClick={() => {
              const firstItem = openItems[0];
              const matchingBasis =
                approvedBases.find((b) => b.supplierId === firstItem?.supplierId) ?? approvedBases[0];
              orderForm.setFieldsValue({
                openItemId: firstItem?.id,
                settlementBasisId: matchingBasis?.id,
                cashAccountId: p.cashAccounts[0]?.value,
                bankSlipOriginalId: p.originals[0]?.value,
                paymentInstructionRef: `TR01-${p.yearNumber}-01`,
                accountingDate: `${p.yearNumber}-01-25`,
                amount: firstItem?.availableAmount ?? "100.00",
                summary: "应付账款分次/全额银行转账清偿",
                reason: "核验线下银行付款回单原件后发起应付结算指令",
              });
              setOrderOpen(true);
            }}
          >
            新建应付清偿单（AP02）
          </Button>
        </Space>

        <Card title="AP01 应付未清项余额与账龄（含下游结算占用保护）" size="small">
          <Table<PayableOpenItemSummary>
            rowKey="id"
            size="small"
            loading={loading}
            pagination={false}
            dataSource={data?.openItems ?? []}
            scroll={{ x: 900 }}
            columns={[
              { title: "未清项ID", dataIndex: "id", render: (v: string) => v.slice(0, 8) },
              { title: "供应商ID", dataIndex: "supplierId", render: (v: string) => v.slice(0, 8) },
              { title: "原始应付总额", dataIndex: "amount" },
              { title: "已记账清偿额", dataIndex: "settledAmount" },
              { title: "在途结算预占", dataIndex: "reservedAmount" },
              { title: "剩余可清偿额", dataIndex: "availableAmount" },
              { title: "到期日", dataIndex: "dueDate" },
              { title: "账龄/状态", dataIndex: "aging", render: (s: string) => <Tag>{s}</Tag> },
            ]}
          />
        </Card>

        <Card title="EV03 结算证据配额（SETTLEMENT_EVIDENCE）" size="small">
          <Table<PayableSettlementBasisItem>
            rowKey="id"
            size="small"
            loading={loading}
            pagination={false}
            dataSource={data?.bases ?? []}
            scroll={{ x: 900 }}
            columns={[
              { title: "配额ID", dataIndex: "id", render: (v: string) => v.slice(0, 8) },
              { title: "收款账户标签", dataIndex: "payeeAccountLabel" },
              { title: "核定总额", dataIndex: "capacity" },
              { title: "已用/预占", dataIndex: "usedAmount" },
              { title: "剩余可用", dataIndex: "availableAmount" },
              {
                title: "状态",
                render: (_, r) => (
                  <Space>
                    <Tag>{r.status}</Tag>
                    <Typography.Text type="secondary">{r.bankChannel}</Typography.Text>
                  </Space>
                ),
              },
              {
                title: "操作",
                render: (_, r) =>
                  r.status === "PENDING" ? (
                    <Button
                      size="small"
                      disabled={!p.canReview || busy}
                      onClick={() =>
                        void exec(`${root}/bases/${r.id}/approve`, {
                          expectedVersion: Number(r.version),
                          reason: "独立自然人审签批准应付结算证据配额",
                        })
                      }
                    >
                      独立批准配额
                    </Button>
                  ) : null,
              },
            ]}
          />
        </Card>

        <Card title="AP02 应付结算与付款指令状态机（原生 AP_PAYMENT / AP_PAYMENT_REVERSAL）" size="small">
          <Table<PayableSettlementOrderItem>
            rowKey="id"
            size="small"
            loading={loading}
            pagination={false}
            dataSource={data?.orders ?? []}
            scroll={{ x: 1000 }}
            columns={[
              { title: "类型", dataIndex: "kind", render: (k: string) => <Tag>{k}</Tag> },
              { title: "付款指令号", dataIndex: "paymentInstructionRef" },
              { title: "记账日", dataIndex: "accountingDate" },
              { title: "金额(CNY)", dataIndex: "amount" },
              {
                title: "状态与水单模式",
                render: (_, r) => (
                  <Space>
                    <Tag>{r.state}</Tag>
                    <Typography.Text type="secondary">
                      {r.verificationMode} · {r.bankChannel}
                    </Typography.Text>
                  </Space>
                ),
              },
              {
                title: "原生凭证",
                render: (_, r) => (r.journalId ? `${r.journalId.slice(0, 8)} (#${r.postingSeq})` : "—"),
              },
              {
                title: "操作",
                render: (_, r) => {
                  const acts = payableSettlementNextActions(r.kind, r.state, r.reversedById);
                  if (acts.length === 0) return null;
                  const expVer = Number(r.version);
                  return (
                    <Space wrap>
                      {acts.includes("submit") && (
                        <Button
                          size="small"
                          disabled={!p.canCreate || busy}
                          onClick={() =>
                            void exec(`${root}/orders/${r.id}/submit`, {
                              expectedVersion: expVer,
                              reason: "提交应付结算单复核",
                            })
                          }
                        >
                          提交复核
                        </Button>
                      )}
                      {acts.includes("approve") && (
                        <Button
                          size="small"
                          disabled={!p.canReview || busy}
                          onClick={() =>
                            void exec(`${root}/orders/${r.id}/approve`, {
                              expectedVersion: expVer,
                              reason: "独立自然人审签批准应付结算指令",
                            })
                          }
                        >
                          独立批准
                        </Button>
                      )}
                      {acts.includes("post") && (
                        <Button
                          size="small"
                          type="primary"
                          disabled={!p.canPost || busy}
                          onClick={() =>
                            void exec(`${root}/orders/${r.id}/post`, {
                              expectedVersion: expVer,
                              reason: "正式过账生成原生应付清偿凭证",
                            })
                          }
                        >
                          正式记账
                        </Button>
                      )}
                      {acts.includes("cancel") && (
                        <Button
                          size="small"
                          disabled={!p.canCreate || busy}
                          onClick={() =>
                            void exec(`${root}/orders/${r.id}/cancel`, {
                              expectedVersion: expVer,
                              reason: "作废未过账应付结算单并释放配额",
                            })
                          }
                        >
                          作废
                        </Button>
                      )}
                      {acts.includes("reverse") && (
                        <Button
                          size="small"
                          danger
                          disabled={!p.canCreate || busy}
                          onClick={() => {
                            reverseForm.setFieldsValue({
                              bankSlipOriginalId: p.originals[0]?.value ?? r.bankSlipOriginalId,
                              paymentInstructionRef: `${r.paymentInstructionRef}-REV`,
                              accountingDate: r.accountingDate,
                              summary: `冲回应付清偿单 ${r.paymentInstructionRef}`,
                              reason: "依据银行退汇/冲正水单原件全额冲回已记账结算单",
                            });
                            setReverseTarget(r);
                          }}
                        >
                          冲销结算
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
          title="登记应付结算证据配额（EV03 SETTLEMENT_EVIDENCE）"
          open={basisOpen}
          confirmLoading={busy}
          destroyOnHidden={false}
          onCancel={() => !busy && setBasisOpen(false)}
          onOk={() =>
            void basisForm.validateFields().then((v) =>
              exec(`${root}/bases`, v, () => setBasisOpen(false)),
            )
          }
        >
          <Form form={basisForm} layout="vertical">
            <Form.Item name="supplierId" label="已批准有效供应商" rules={[{ required: true }]}>
              <Select options={p.suppliers} />
            </Form.Item>
            <Form.Item name="originalId" label="结算协议/付款核定原件" rules={[{ required: true }]}>
              <Select options={p.originals} />
            </Form.Item>
            <Form.Item name="payeeAccountLabel" label="离线收款账户核对标签" rules={[{ required: true }]}>
              <Input />
            </Form.Item>
            <Form.Item name="capacity" label="核定结算上限金额（CNY）" rules={[{ required: true }]}>
              <Input />
            </Form.Item>
            <Form.Item name="reason" label="核定依据说明" rules={[{ required: true }]}>
              <Input.TextArea />
            </Form.Item>
          </Form>
        </Modal>

        <Modal
          title="新建应付清偿与付款指令（AP02 / TR01 / TR02）"
          open={orderOpen}
          confirmLoading={busy}
          destroyOnHidden={false}
          onCancel={() => !busy && setOrderOpen(false)}
          onOk={() =>
            void orderForm.validateFields().then((v) => {
              const oi = openItems.find((i) => i.id === v.openItemId);
              const bs = approvedBases.find((b) => b.id === v.settlementBasisId);
              if (oi && bs) {
                const check = validatePayableSettlementAmount(
                  String(v.amount ?? ""),
                  oi.availableAmount,
                  bs.availableAmount,
                );
                if (!check.ok) {
                  setError(`结算金额校验未通过：${check.code}`);
                  return;
                }
              }
              return exec(
                `${root}/orders`,
                {
                  kind: "PAYMENT",
                  openItemId: v.openItemId,
                  settlementBasisId: v.settlementBasisId,
                  cashAccountId: v.cashAccountId,
                  bankSlipOriginalId: v.bankSlipOriginalId,
                  paymentInstructionRef: v.paymentInstructionRef,
                  accountingDate: v.accountingDate,
                  amount: v.amount,
                  summary: v.summary,
                  reversesId: null,
                  reason: v.reason,
                },
                () => setOrderOpen(false),
              );
            })
          }
        >
          <Form form={orderForm} layout="vertical">
            <Form.Item name="openItemId" label="AP01 应付未清项" rules={[{ required: true }]}>
              <Select
                options={openItems.map((i) => ({
                  value: i.id,
                  label: `${i.id.slice(0, 8)} · 可清偿 ${i.availableAmount} / 总额 ${i.amount}`,
                }))}
              />
            </Form.Item>
            <Form.Item name="settlementBasisId" label="已批准 EV03 结算证据配额" rules={[{ required: true }]}>
              <Select
                options={approvedBases.map((b) => ({
                  value: b.id,
                  label: `${b.id.slice(0, 8)} · 可用 ${b.availableAmount} (${b.payeeAccountLabel})`,
                }))}
              />
            </Form.Item>
            <Form.Item name="cashAccountId" label="付款货币资金科目" rules={[{ required: true }]}>
              <Select options={p.cashAccounts} />
            </Form.Item>
            <Form.Item name="bankSlipOriginalId" label="线下真实银行付款回单原件" rules={[{ required: true }]}>
              <Select options={p.originals} />
            </Form.Item>
            <Space>
              <Form.Item name="paymentInstructionRef" label="付款指令/水单参考号" rules={[{ required: true }]}>
                <Input />
              </Form.Item>
              <Form.Item name="accountingDate" label="记账日期" rules={[{ required: true }]}>
                <Input type="date" />
              </Form.Item>
            </Space>
            <Form.Item name="amount" label="本次清偿金额（CNY）" rules={[{ required: true }]}>
              <Input />
            </Form.Item>
            <Form.Item name="summary" label="原生凭证摘要" rules={[{ required: true }]}>
              <Input />
            </Form.Item>
            <Form.Item name="reason" label="业务制单原因" rules={[{ required: true }]}>
              <Input.TextArea />
            </Form.Item>
          </Form>
        </Modal>

        <Modal
          title="冲销已记账应付结算单（生成 AP_PAYMENT_REVERSAL）"
          open={!!reverseTarget}
          confirmLoading={busy}
          destroyOnHidden={false}
          onCancel={() => !busy && setReverseTarget(null)}
          onOk={() => {
            if (!reverseTarget) return;
            void reverseForm.validateFields().then((v) =>
              exec(
                `${root}/orders`,
                {
                  kind: "REVERSAL",
                  openItemId: reverseTarget.openItemId,
                  settlementBasisId: reverseTarget.settlementBasisId,
                  cashAccountId: reverseTarget.cashAccountId,
                  bankSlipOriginalId: v.bankSlipOriginalId,
                  paymentInstructionRef: v.paymentInstructionRef,
                  accountingDate: v.accountingDate,
                  amount: reverseTarget.amount,
                  summary: v.summary,
                  reversesId: reverseTarget.id,
                  reason: v.reason,
                },
                () => setReverseTarget(null),
              ),
            );
          }}
        >
          <Form form={reverseForm} layout="vertical">
            <Form.Item name="bankSlipOriginalId" label="银行退汇/冲正水单原件" rules={[{ required: true }]}>
              <Select options={p.originals} />
            </Form.Item>
            <Space>
              <Form.Item name="paymentInstructionRef" label="冲正水单参考号" rules={[{ required: true }]}>
                <Input />
              </Form.Item>
              <Form.Item name="accountingDate" label="冲销记账日期" rules={[{ required: true }]}>
                <Input type="date" />
              </Form.Item>
            </Space>
            <Form.Item name="summary" label="冲销凭证摘要" rules={[{ required: true }]}>
              <Input />
            </Form.Item>
            <Form.Item name="reason" label="冲销原因说明" rules={[{ required: true }]}>
              <Input.TextArea />
            </Form.Item>
          </Form>
        </Modal>
      </Space>
    </Card>
  );
}

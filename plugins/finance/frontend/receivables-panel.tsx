import { useEffect, useRef, useState } from "react";
import { Alert, Button, Form, Input, Modal, Select, Space, Table, Tag, Typography } from "antd";
import { arActions, arAllocation, arCny, arLabels, arRetryIdentity, type ArKind, type ArObject } from "./receivables-rules";

type Page = { items: ArObject[]; nextCursor: string | null; nativeProducerAvailable: boolean; bankChannel: string };
type Option = { value: string; label: string };
type Props = {
  /** Include account/person/binding/auth generation and tenant/book/year; never only the URL. */
  identity: string; scope: string; active: boolean; defaultAsOf?: string; canCreate: boolean; canReview: boolean; canPost: boolean;
  customers: (Option & { revisionId: string })[]; originals: Option[];
  /** Host adapters must use the existing authenticated, CSRF-protected API client. */
  read: <T>(url: string, signal: AbortSignal) => Promise<T>;
  write: <T>(url: string, body: unknown) => Promise<T>;
};
const actionLabels: Record<string, string> = { submit: "提交", approve: "独立批准", post: "记账", reject: "退回", withdraw: "撤回", cancel: "作废" };
const extraFields: Record<ArKind, [string, string][]> = {
  CONTRACT: [["terms", "合同条款与履约约定"]],
  PERFORMANCE: [["contractId", "已确认合同ID"], ["performanceRule", "履约阶段及确认依据"], ["acceptedOn", "实际验收日期"]],
  RECOGNITION: [["performanceId", "已独立确认的履约阶段ID"], ["decisionId", "已复核会计与税务判断ID"], ["dueDate", "应收到期日"]],
  RECEIPT: [["receiptDecisionId", "人工回执及科目复核决定ID"], ["bankAccountReference", "已复核收款账户引用"], ["receiptReference", "原始收款回执编号"]],
  ALLOCATION: [["receiptId", "已记账收款ID"], ["allocationDecisionId", "核销会计判断ID"]],
  REVERSAL: [["originalObjectId", "待完整反向的已记账对象ID"], ["correctionDecisionId", "更正会计判断ID"]],
};
export function ReceivablesPanel(props: Props) { return <Workbench key={props.identity} {...props} />; }
function Workbench(p: Props) {
  const [data, setData] = useState<Page | null>(null), [error, setError] = useState("");
  const [asOf, setAsOf] = useState(p.defaultAsOf ?? ""), [after, setAfter] = useState<string | null>(null), [revision, setRevision] = useState(0);
  const [busy, setBusy] = useState(false), [loading, setLoading] = useState(false);
  const [kind, setKind] = useState<ArKind>("CONTRACT"), [creating, setCreating] = useState(false);
  const [action, setAction] = useState<{ row: ArObject; name: string } | null>(null);
  const [form] = Form.useForm(), [decisionForm] = Form.useForm();
  const alive = useRef(true), writeLock = useRef(false);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => {
    const controller = new AbortController();
    setData(null);
    if (!p.active || !/^\d{4}-\d{2}-\d{2}$/.test(asOf)) return () => controller.abort();
    setLoading(true);
    const query = new URLSearchParams({ asOf, limit: "20", ...(after ? { after } : {}) });
    void p.read<Page>(`${p.scope}/receivables?${query}`, controller.signal).then((page) => {
      if (!controller.signal.aborted) { setData(page); setError(""); }
    }).catch((e: unknown) => { if (!controller.signal.aborted) setError(String(e)); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [p.active, p.identity, p.scope, p.read, asOf, after, revision]);

  async function send(route: string, body: Record<string, unknown>, create: boolean) {
    if (writeLock.current || !p.active) return;
    writeLock.current = true; setBusy(true); setError("");
    try {
      const ids = await arRetryIdentity(p.identity, route, body, sessionStorage);
      if (!alive.current) return;
      await p.write(route, { ...body, idempotencyKey: ids.idempotencyKey, ...(create ? { operationId: ids.operationId } : {}) });
      if (alive.current) { setCreating(false); setAction(null); setRevision((v) => v + 1); }
    } catch (e) { if (alive.current) setError(String(e)); }
    finally { writeLock.current = false; if (alive.current) setBusy(false); }
  }
  async function create() {
    try {
      const values = await form.validateFields();
      const customer = p.customers.find((c) => c.value === values.customerId);
      if (!customer) throw new Error("请选择当前有效客户修订");
      const body: Record<string, unknown> = { kind, customerId: customer.value, customerRevisionId: customer.revisionId,
        originalId: values.originalId, reason: values.reason, businessKey: values.businessKey,
        date: values.date, amount: arCny(values.amount) };
      for (const [key] of extraFields[kind]) body[key] = values[key];
      if (kind === "ALLOCATION") body.items = arAllocation(values.items ?? [], String(body.amount));
      await send(`${p.scope}/receivables`, body, true);
    } catch (e) { if (alive.current) setError(String(e)); }
  }
  const canAction = (name: string) => name === "post" ? p.canPost : ["approve", "reject"].includes(name) ? p.canReview : p.canCreate;
  return <Space orientation="vertical" style={{ width: "100%" }}>
    <Alert type="info" title="销售应收管理（有界人民币 AR01 / AR02）" description="合同、履约、收入、收款、核销与完整反向分别确认，由独立审定会计/税务判断与原生应收凭证入账。人工回执不代表外部银行接口验证（NOT_VERIFIED）；核销不自动确认坏账。" />
    {error && <Alert type="error" title={error} description="请求结果不明时保留相同正文重试；重试标识跨刷新保留。" />}
    <Space wrap><label>账龄观察日期 <Input type="date" value={asOf} onChange={(e) => { setAsOf(e.target.value); setAfter(null); }} /></label>
      <Button onClick={() => setRevision((v) => v + 1)} disabled={!asOf || busy}>刷新</Button>
      <Button type="primary" disabled={!p.canCreate || !p.active || busy} onClick={() => setCreating(true)}>新增业务</Button>
    </Space>
    <Table<ArObject> rowKey="id" loading={loading} dataSource={data?.items ?? []} pagination={false} scroll={{ x: 980 }} columns={[
      { title: "业务", dataIndex: "kind", render: (k: ArKind) => arLabels[k] },
      { title: "标识", dataIndex: "id", render: (id: string) => <Typography.Text copyable>{id}</Typography.Text> },
      { title: "状态", dataIndex: "state", render: (s: string, r) => <Tag>{r.reversedBy ? "已反向" : s}</Tag> },
      { title: "金额", dataIndex: "amount" },
      { title: "未结 / 预占 / 账龄", render: (_, r) => r.balances?.map((b) => <div key={b.purpose}>{b.outstanding} / {b.reserved} / {b.aging ?? "—"}</div>) },
      { title: "操作", render: (_, row) => <Space wrap>{arActions(row.state).map((name) => <Button key={name} size="small" disabled={busy || !canAction(name)} onClick={() => { decisionForm.resetFields(); setAction({ row, name }); }}>{actionLabels[name]}</Button>)}</Space> },
    ]} />
    <Space><Button disabled={!after || busy} onClick={() => setAfter(null)}>首页</Button><Button disabled={!data?.nextCursor || busy} onClick={() => setAfter(data?.nextCursor ?? null)}>下一页</Button></Space>
    <Modal title="新增销售应收业务" open={creating} onCancel={() => !busy && setCreating(false)} onOk={() => void create()} confirmLoading={busy} width={720} destroyOnHidden={false}>
      <Select<ArKind> value={kind} style={{ width: "100%" }} disabled={busy} onChange={(v) => { setKind(v); form.resetFields(); }} options={Object.entries(arLabels).map(([value, label]) => ({ value: value as ArKind, label, disabled: !["CONTRACT", "PERFORMANCE"].includes(value) && !data?.nativeProducerAvailable }))} />
      <Form form={form} layout="vertical" disabled={busy}>
        <Form.Item name="customerId" label="客户（当前批准修订）" rules={[{ required: true }]}><Select options={p.customers} showSearch optionFilterProp="label" /></Form.Item>
        <Form.Item name="originalId" label="真实原件" rules={[{ required: true }]}><Select options={p.originals} showSearch optionFilterProp="label" /></Form.Item>
        <Form.Item name="businessKey" label="稳定业务编号（新请求键不会解除防重）" rules={[{ required: true, max: 160 }]}><Input /></Form.Item>
        <Form.Item name="date" label="业务 / 会计日期" rules={[{ required: true }]}><Input type="date" /></Form.Item>
        <Form.Item name="amount" label="本阶段含税人民币金额 / 核销金额" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
        {extraFields[kind].map(([name, label]) => <Form.Item key={name} name={name} label={label} rules={[{ required: true }]}><Input type={name === "dueDate" || name === "acceptedOn" ? "date" : "text"} /></Form.Item>)}
        {kind === "ALLOCATION" && <Form.List name="items">{(fields, { add, remove }) => <>
          {fields.map((field) => <Space key={field.key} align="start"><Form.Item name={[field.name, "recognitionId"]} rules={[{ required: true }]}><Input placeholder="已记账应收ID" /></Form.Item><Form.Item name={[field.name, "amount"]} rules={[{ required: true }]}><Input placeholder="本次核销金额" inputMode="decimal" /></Form.Item><Button onClick={() => remove(field.name)}>移除</Button></Space>)}
          <Button onClick={() => add()} disabled={fields.length >= 50}>增加应收核销行</Button>
        </>}</Form.List>}
        <Form.Item name="reason" label="业务依据 / 更正原因" rules={[{ required: true, max: 1000 }]}><Input.TextArea /></Form.Item>
      </Form>
    </Modal>
    <Modal title={action ? actionLabels[action.name] : "业务操作"} open={!!action} confirmLoading={busy} onCancel={() => !busy && setAction(null)} onOk={() => {
      if (!action) return;
      void decisionForm.validateFields().then((v) => send(`${p.scope}/receivables/${action.row.id}/${action.name}`, { expectedVersion: action.row.version, reason: v.reason }, false)).catch((e: unknown) => setError(String(e)));
    }}><Form form={decisionForm} layout="vertical"><Form.Item name="reason" label="操作依据（独立自然人及当前权限由服务器复核）" rules={[{ required: true, max: 1000 }]}><Input.TextArea disabled={busy} /></Form.Item></Form></Modal>
  </Space>;
}

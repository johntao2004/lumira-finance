import { useEffect, useRef, useState } from "react";
import { Alert, Button, Input, Select, Space, Table, Typography } from "antd";
import {
  clearPayrollPending,
  isDefinitivePayrollRejection,
  payrollNextAction,
  readPayrollPending,
  writePayrollPending,
  type PayrollAction,
  type PayrollPendingCommand,
} from "./payroll-rules";

type Amount = string;
type Action = PayrollAction;
export type PayrollBatchSummary = {
  id: string;
  payDate: string;
  kind: string;
  state: string;
  version: number;
};
export type PayrollBatch = {
  draft: { id: string; book: string; payDate: string; kind: string; predecessor: string | null };
  state: "DRAFT" | "CALCULATED" | "REVIEWED" | "APPROVED" | "LOCKED" | "CANCELLED";
  version: number;
  computed: { employee: string; gross: Amount; employerSocial: Amount; netPay: Amount;
    tax: { taxable: Amount; basic: Amount; cumulativeTax: Amount; currentWithholding: Amount; overwithheld: Amount } }[];
  facts: { kind: string; reference: string; amount: Amount }[];
};
export interface PayrollApi {
  listBatches?(signal: AbortSignal): Promise<PayrollBatchSummary[]>;
  read(id: string, signal: AbortSignal): Promise<PayrollBatch>;
  act(id: string, body: { command: { key: string; client: string; expectedVersion: number }; action: Action }, signal: AbortSignal): Promise<PayrollBatch>;
}
// Adapter must retain decimal strings (including Java BigDecimal responses), apply CSRF,
// enforce no-store, and map platform Problems. No salary data enters persistent browser storage.
export function PayrollPanel({ api, scopeKey, canReadSalary, actions }: {
  api: PayrollApi; scopeKey: string; canReadSalary: boolean; actions: readonly Action[];
}) {
  const [id, setId] = useState("");
  const [batches, setBatches] = useState<PayrollBatchSummary[]>([]);
  const [batch, setBatch] = useState<PayrollBatch>();
  const [loadedScope, setLoadedScope] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [pendingState, setPendingState] = useState<PayrollPendingCommand | undefined>(undefined);
  const pending = useRef<PayrollPendingCommand | undefined>(undefined);
  const abort = useRef<AbortController | undefined>(undefined);
  const generation = useRef(0);

  function syncPending(next: PayrollPendingCommand | undefined) {
    pending.current = next;
    setPendingState(next);
  }

  useEffect(() => {
    generation.current++;
    abort.current?.abort();
    setBatches([]);
    setBatch(undefined);
    setId("");
    setError("");
    setBusy(false);
    syncPending(undefined);
    if (!canReadSalary || !api.listBatches) {
      return () => { generation.current++; abort.current?.abort(); };
    }
    const current = generation.current;
    const listController = new AbortController();
    void api.listBatches(listController.signal).then((rows) => {
      if (generation.current !== current || listController.signal.aborted) return;
      setBatches(rows);
      if (rows.length > 0) {
        const firstId = rows[0].id;
        setId(firstId);
        try {
          syncPending(readPayrollPending(sessionStorage, scopeKey, firstId));
        } catch (e) {
          setError(e instanceof Error ? e.message : String(e));
        }
        void loadById(firstId, current);
      }
    }).catch((e) => {
      if (generation.current === current && !listController.signal.aborted) {
        setError(e instanceof Error ? e.message : String(e));
      }
    });
    return () => { generation.current++; listController.abort(); abort.current?.abort(); };
  }, [scopeKey, canReadSalary, api]);

  async function loadById(targetId: string, expectedGeneration = generation.current) {
    if (!canReadSalary || !targetId) return;
    abort.current?.abort();
    const controller = new AbortController();
    abort.current = controller;
    setBusy(true);
    setError("");
    try {
      const saved = readPayrollPending(sessionStorage, scopeKey, targetId);
      syncPending(saved);
      const result = await api.read(targetId, controller.signal);
      if (generation.current === expectedGeneration) {
        setLoadedScope(scopeKey);
        setBatch(result);
      }
    } catch (e) {
      if (generation.current === expectedGeneration && !controller.signal.aborted) {
        setError(e instanceof Error ? e.message : "读取失败，请核对批次编号");
      }
    } finally {
      if (generation.current === expectedGeneration) setBusy(false);
    }
  }

  async function run(action?: Action) {
    if (!canReadSalary || busy || (action && loadedScope !== scopeKey)) return;
    const current = generation.current;
    if (!action) {
      await loadById(id, current);
      return;
    }
    if (!batch) return;
    abort.current?.abort();
    const controller = new AbortController();
    abort.current = controller;
    setBusy(true);
    setError("");
    try {
      let commandEnvelope = pending.current;
      if (!commandEnvelope) {
        commandEnvelope = {
          command: { key: crypto.randomUUID(), client: "finance-web", expectedVersion: batch.version },
          action,
        };
        writePayrollPending(sessionStorage, scopeKey, batch.draft.id, commandEnvelope);
        syncPending(commandEnvelope);
      }
      if (commandEnvelope.action !== action) throw new Error("先核实或重试上一操作结果");
      const result = await api.act(batch.draft.id, commandEnvelope, controller.signal);
      clearPayrollPending(sessionStorage, scopeKey, batch.draft.id);
      if (generation.current === current) {
        syncPending(undefined);
        setLoadedScope(scopeKey);
        setBatch(result);
        setBatches((prev) => prev.map((b) => b.id === result.draft.id ? { ...b, state: result.state, version: result.version } : b));
      }
    } catch (e) {
      if (isDefinitivePayrollRejection(e)) {
        clearPayrollPending(sessionStorage, scopeKey, batch.draft.id);
        if (generation.current === current) syncPending(undefined);
      }
      if (generation.current === current && !controller.signal.aborted) {
        setError(e instanceof Error ? e.message : "操作失败，请重试原请求");
      }
    } finally {
      if (generation.current === current) setBusy(false);
    }
  }

  if (!canReadSalary) return <Alert type="info" title="需要独立薪酬字段读取权限" />;
  const currentBatch = loadedScope === scopeKey ? batch : undefined;
  const action = currentBatch && (pendingState?.action ?? payrollNextAction(currentBatch.state));
  return <Space orientation="vertical" style={{ width: "100%" }}>
    <Typography.Title level={4}>工资核算与累计预扣</Typography.Title>
    <Alert type="info" title="工资核算与居民工资薪金累计预扣（PAY01 / PAY02）" description="工资批次计算、独立复核、独立批准、锁定及计提/实发/已扣税/申报事实分别核验。外部银行代发、社保及税务联网通道尚未验证（NOT_VERIFIED），本页不发起外部支付或申报请求。" />
    <Space wrap>
      {batches.length > 0 && (
        <Select
          aria-label="已登记工资批次"
          style={{ minWidth: 280 }}
          disabled={busy || !!pendingState}
          value={batches.some((b) => b.id === id) ? id : undefined}
          placeholder="选择已登记工资批次"
          options={batches.map((b) => ({
            value: b.id,
            label: `${b.payDate} · ${b.kind} · ${b.state} (${b.id})`,
          }))}
          onChange={(nextId) => {
            setId(nextId);
            setBatch(undefined);
            void loadById(nextId);
          }}
        />
      )}
      <Input aria-label="工资批次编号" placeholder="输入工资批次编号" value={id} disabled={busy || !!pendingState} onChange={e => { setId(e.target.value); setBatch(undefined); }} />
      <Button disabled={!id || busy} onClick={() => void run()}>读取批次</Button>
      {action && actions.includes(action) && <Button loading={busy} onClick={() => void run(action)}>{pendingState ? "重试原操作" : action}</Button>}
      {currentBatch && !pendingState && actions.includes("CANCEL") && currentBatch.state !== "LOCKED" && currentBatch.state !== "CANCELLED" &&
        <Button disabled={busy} onClick={() => void run("CANCEL")}>撤销未锁定批次</Button>}
    </Space>
    {error && <Alert type="error" title={error} />}
    {currentBatch && <>
      <Typography.Text>{currentBatch.draft.payDate} · {currentBatch.draft.kind} · {currentBatch.state} · 版本 {currentBatch.version}</Typography.Text>
      <Table rowKey="employee" dataSource={currentBatch.computed} pagination={{ pageSize: 20 }} scroll={{ x: 1000 }} columns={[
        { title: "员工编号", dataIndex: "employee" }, { title: "本次工资", dataIndex: "gross" },
        { title: "累计基本减除", render: (_, r) => r.tax.basic }, { title: "累计应税所得", render: (_, r) => r.tax.taxable },
        { title: "本次预扣", render: (_, r) => r.tax.currentWithholding }, { title: "待核实多扣（不自动退）", render: (_, r) => r.tax.overwithheld },
        { title: "应发净额", dataIndex: "netPay" }, { title: "单位社保", dataIndex: "employerSocial" },
      ]} />
      <Table rowKey="kind" dataSource={currentBatch.facts} pagination={false} columns={[
        { title: "独立核验事实", dataIndex: "kind" }, { title: "证据引用", dataIndex: "reference" }, { title: "核验金额", dataIndex: "amount" },
      ]} />
    </>}
  </Space>;
}

export type PayrollRestatementPlanItem = {
  id: string;
  targetBatchId: string;
  rulePackId: string;
  planPayload: Record<string, unknown>;
  planDigest: string;
  totalTaxDelta: string;
  totalOverwithheld: string;
  requiresPriorAmendment: boolean;
  status: string;
  version: string;
  reason: string;
};

export function PayrollRestatementCard(p: {
  identity: string;
  bookId: string;
  yearId: string;
  taxEntityId: string;
  taxYear: number;
  active: boolean;
  canCreate: boolean;
  canReview: boolean;
  read: <T>(url: string, signal: AbortSignal) => Promise<T>;
  write: <T>(url: string, body: Record<string, unknown>) => Promise<T>;
}) {
  const base = `/finance/v1/books/${p.bookId}/payroll/${encodeURIComponent(p.taxEntityId)}/${p.taxYear}`;
  const [plans, setPlans] = useState<PayrollRestatementPlanItem[]>([]);
  const [employeeId, setEmployeeId] = useState("");
  const [rulePackId, setRulePackId] = useState("");
  const [targetBatchId, setTargetBatchId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [refresh, setRefresh] = useState(0);
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  useEffect(() => {
    if (!p.active || !p.bookId || !p.taxEntityId || !p.taxYear) return;
    const ctrl = new AbortController();
    void Promise.all([
      p.read<{ items: PayrollRestatementPlanItem[] }>(`${base}/restatements`, ctrl.signal),
      p.read<{
        employments?: { employeeId: string }[];
        rulePacks?: { id: string }[];
        batches?: { id: string; state: string }[];
      }>(base, ctrl.signal),
    ])
      .then(([res, setup]) => {
        if (!ctrl.signal.aborted && alive.current) {
          setPlans(res.items ?? []);
          const emp = (setup.employments ?? [])[0];
          if (emp) setEmployeeId(emp.employeeId);
          const rp = (setup.rulePacks ?? [])[0];
          if (rp) setRulePackId(rp.id);
          const lb = (setup.batches ?? []).find((b) => b.state === "LOCKED");
          if (lb) setTargetBatchId(lb.id);
          setError("");
        }
      })
      .catch((e: unknown) => {
        if (!ctrl.signal.aborted && alive.current) setError(String(e));
      });
    return () => ctrl.abort();
  }, [p.active, p.bookId, p.taxEntityId, p.taxYear, p.identity, p.read, base, refresh]);

  async function createPlan() {
    if (busy || !employeeId || !rulePackId || !targetBatchId) return;
    setBusy(true);
    setError("");
    try {
      await p.write(`${base}/restatements`, {
        idempotencyKey: `pay-rest-create-${crypto.randomUUID()}`,
        employeeId,
        rulePackId,
        periods: [
          {
            batchId: targetBatchId,
            cumulativeIncome: "18000.00",
            cumulativeExempt: "0.00",
            cumulativeSocial: "2200.00",
            cumulativeSpecial: "2000.00",
            cumulativeOther: "0.00",
            cumulativeDonation: "0.00",
            cumulativeReduction: "0.00",
            alreadyWithheld: "100.00",
          },
        ],
        reason: "跨期专项附加扣除与累计预扣重算差额方案（PAY02）",
      });
      if (alive.current) setRefresh((x) => x + 1);
    } catch (e: unknown) {
      if (alive.current) setError(e instanceof Error ? e.message : String(e));
    } finally {
      if (alive.current) setBusy(false);
    }
  }

  async function approvePlan(plan: PayrollRestatementPlanItem) {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      await p.write(`${base}/restatements/${plan.id}/approve`, {
        expectedVersion: Number(plan.version),
        reason: "独立自然人复核批准跨期薪酬累计重算差额方案",
        idempotencyKey: `pay-rest-app-${crypto.randomUUID()}`,
      });
      if (alive.current) setRefresh((x) => x + 1);
    } catch (e: unknown) {
      if (alive.current) setError(e instanceof Error ? e.message : String(e));
    } finally {
      if (alive.current) setBusy(false);
    }
  }

  if (!p.taxEntityId) return null;
  return (
    <Space orientation="vertical" style={{ width: "100%", marginTop: 12 }}>
      <Typography.Title level={5} style={{ margin: 0 }}>
        跨期专项附加扣除与累计预扣重算差额方案（PAY02 · PayrollRestatement）
      </Typography.Title>
      {error && <Alert type="error" title={error} />}
      <Space wrap>
        <Button size="small" disabled={busy} onClick={() => setRefresh((x) => x + 1)}>
          刷新重算方案
        </Button>
        {employeeId && rulePackId && targetBatchId && (
          <Button
            size="small"
            disabled={!p.canCreate || busy}
            onClick={() => void createPlan()}
          >
            生成跨期重算差额方案
          </Button>
        )}
        {rulePackId && targetBatchId && (
          <Typography.Text type="secondary">
            已识别已锁定批次 {targetBatchId.slice(0, 8)} · 规则包 {rulePackId.slice(0, 8)}
          </Typography.Text>
        )}
      </Space>
      <Table<PayrollRestatementPlanItem>
        rowKey="id"
        size="small"
        pagination={false}
        dataSource={plans}
        scroll={{ x: 860 }}
        columns={[
          { title: "目标锁定批次", dataIndex: "targetBatchId", render: (v: string) => v.slice(0, 8) },
          { title: "预扣税额差额合计", dataIndex: "totalTaxDelta" },
          { title: "待核实多扣合计", dataIndex: "totalOverwithheld" },
          {
            title: "需更正往期申报",
            dataIndex: "requiresPriorAmendment",
            render: (b: boolean) => (b ? "是（PRIOR_RETURN_SUBMITTED）" : "否（当期吸收）"),
          },
          { title: "状态", dataIndex: "status" },
          {
            title: "操作",
            render: (_, r) =>
              r.status === "PENDING" ? (
                <Button
                  size="small"
                  type="primary"
                  disabled={!p.canReview || busy}
                  onClick={() => void approvePlan(r)}
                >
                  独立批准重算差额方案
                </Button>
              ) : null,
          },
        ]}
      />
    </Space>
  );
}

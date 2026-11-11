import { useEffect, useRef, useState } from "react";
import { Alert, Button, Form, Input, InputNumber, Modal, Select, Space, Table, Tabs, Tag, Typography } from "antd";
import {
  type CityTier,
  type EmployeeRank,
  type ExpenseCategory,
  type TravelPolicy,
  type TravelException,
  type ExpenseRequisition,
  type ExpenseAdvance,
  type ExpenseClaim,
  type ExpensePayableLedger,
  expenseCny,
  evaluateTravelCap,
  calculateLineSplit,
  calculateClaimTotals,
  calculateAdvanceBalances,
  validateSod,
  requisitionActions,
  advanceActions,
  claimActions,
  expenseRetryIdentity,
  expenseScope,
} from "./expense-rules";

type Option = { value: string; label: string };

type Props = {
  identity: string;
  scope: string;
  active: boolean;
  canCreate: boolean;
  canReview: boolean;
  canApprove: boolean;
  employees: Option[];
  departments: Option[];
  projects: Option[];
  read: <T>(url: string, signal: AbortSignal) => Promise<T>;
  write: <T>(url: string, body: unknown) => Promise<T>;
};

export function ExpensePanel(props: Props) {
  return <ExpenseWorkbench key={props.identity} {...props} />;
}

function ExpenseWorkbench(p: Props) {
  const [activeTab, setActiveTab] = useState("claims");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);

  const expenseBase = expenseScope(p.scope);

  // Data states
  const [policies, setPolicies] = useState<TravelPolicy[]>([]);
  const [requisitions, setRequisitions] = useState<ExpenseRequisition[]>([]);
  const [advances, setAdvances] = useState<ExpenseAdvance[]>([]);
  const [claims, setClaims] = useState<ExpenseClaim[]>([]);
  const [payables, setPayables] = useState<ExpensePayableLedger[]>([]);

  // Dialog states
  const [modalType, setModalType] = useState<string | null>(null);
  const [selectedClaim, setSelectedClaim] = useState<ExpenseClaim | null>(null);
  const [form] = Form.useForm();
  const writeLock = useRef(false);
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  // Fetch data
  useEffect(() => {
    if (!p.active || !expenseBase) return;
    const ac = new AbortController();

    async function loadData() {
      try {
        const [cList, aList, rList, pList, pyList] = await Promise.all([
          p.read<ExpenseClaim[]>(`${expenseBase}/claims`, ac.signal).catch(() => []),
          p.read<ExpenseAdvance[]>(`${expenseBase}/advances`, ac.signal).catch(() => []),
          p.read<ExpenseRequisition[]>(`${expenseBase}/requisitions`, ac.signal).catch(() => []),
          p.read<TravelPolicy[]>(`${expenseBase}/policies`, ac.signal).catch(() => []),
          p.read<ExpensePayableLedger[]>(`${expenseBase}/payables`, ac.signal).catch(() => []),
        ]);
        if (!alive.current) return;
        setClaims(cList || []);
        setAdvances(aList || []);
        setRequisitions(rList || []);
        setPolicies(pList || []);
        setPayables(pyList || []);
      } catch (e) {
        if (alive.current) setError(String(e));
      }
    }

    loadData();
    return () => ac.abort();
  }, [p.active, expenseBase, revision]);

  async function send(route: string, body: Record<string, unknown>) {
    if (writeLock.current || !p.active) return;
    writeLock.current = true;
    setBusy(true);
    setError("");
    try {
      const ids = await expenseRetryIdentity(p.identity, route, body, sessionStorage);
      if (!alive.current) return;
      await p.write(route, {
        ...body,
        idempotencyKey: ids.idempotencyKey,
        operationId: ids.operationId,
      });
      if (alive.current) {
        setModalType(null);
        setSelectedClaim(null);
        form.resetFields();
        setRevision((v) => v + 1);
      }
    } catch (e) {
      if (alive.current) setError(String(e));
    } finally {
      writeLock.current = false;
      if (alive.current) setBusy(false);
    }
  }

  // --- Render Sections ---

  const tabItems = [
    {
      key: "claims",
      label: "费用报销单 (EXP03)",
      children: (
        <Space direction="vertical" style={{ width: "100%" }}>
          <Space>
            <Button
              type="primary"
              disabled={!p.canCreate || busy}
              onClick={() => {
                form.resetFields();
                setModalType("create-claim");
              }}
            >
              新建报销单 (多票/部分认可)
            </Button>
          </Space>
          <div style={{ width: "100%", maxWidth: "100%", overflowX: "auto" }}>
            <Table
              rowKey="id"
              dataSource={claims}
              scroll={{ x: "max-content" }}
              columns={[
                { title: "报销单号", dataIndex: "claimNo" },
                { title: "事由", dataIndex: "title" },
                { title: "申报金额 (CNY)", dataIndex: "submittedAmount" },
                { title: "认可金额 (CNY)", dataIndex: "approvedAmount" },
                { title: "核减个人 (CNY)", dataIndex: "disallowedAmount" },
                { title: "冲抵借款 (CNY)", dataIndex: "offsetAdvanceAmount" },
                { title: "应付员工 (CNY)", dataIndex: "payableAmount" },
                {
                  title: "状态",
                  dataIndex: "status",
                  render: (st: string) => <Tag color={st === "POSTED" || st === "SETTLED" ? "green" : "blue"}>{st}</Tag>,
                },
                {
                  title: "操作",
                  render: (_, r: ExpenseClaim) => (
                    <Space wrap>
                      {claimActions(r.status).map((act) => (
                        <Button
                          key={act}
                          size="small"
                          disabled={busy}
                          onClick={() =>
                            send(`${expenseBase}/claims/${r.id}/actions`, {
                              action: act,
                              actorId: p.identity,
                            })
                          }
                        >
                          {act}
                        </Button>
                      ))}
                      {(r.status === "APPROVED" || r.status === "POSTED") && (
                        <Button
                          size="small"
                          type="dashed"
                          disabled={busy}
                          onClick={() => {
                            setSelectedClaim(r);
                            form.setFieldsValue({ amount: r.payableAmount });
                            setModalType("disburse-payment");
                          }}
                        >
                          支付
                        </Button>
                      )}
                    </Space>
                  ),
                },
              ]}
            />
          </div>
        </Space>
      ),
    },
    {
      key: "advances",
      label: "员工借款与冲抵 (EXP02)",
      children: (
        <Space direction="vertical" style={{ width: "100%" }}>
          <Space>
            <Button
              type="primary"
              disabled={!p.canCreate || busy}
              onClick={() => {
                form.resetFields();
                setModalType("create-advance");
              }}
            >
              申请借款
            </Button>
          </Space>
          <div style={{ width: "100%", maxWidth: "100%", overflowX: "auto" }}>
            <Table
              rowKey="id"
              dataSource={advances}
              scroll={{ x: "max-content" }}
              columns={[
                { title: "借款单号", dataIndex: "advanceNo" },
                { title: "用途", dataIndex: "purpose" },
                { title: "借款本金 (CNY)", dataIndex: "principalAmount" },
                { title: "累计冲借款 (CNY)", dataIndex: "offsetAmount" },
                { title: "退回现金 (CNY)", dataIndex: "refundedAmount" },
                { title: "未结余额 (CNY)", dataIndex: "remainingBalance" },
                {
                  title: "状态",
                  dataIndex: "status",
                  render: (st: string) => <Tag color={st === "SETTLED" ? "green" : "gold"}>{st}</Tag>,
                },
                {
                  title: "操作",
                  render: (_, r: ExpenseAdvance) => (
                    <Space>
                      {advanceActions(r.status).map((act) => (
                        <Button
                          key={act}
                          size="small"
                          disabled={busy}
                          onClick={() =>
                            send(`${expenseBase}/advances/${r.id}/actions`, {
                              action: act,
                              actorId: p.identity,
                              payoutRef: act === "payout" ? `PAYOUT-${Date.now()}` : undefined,
                            })
                          }
                        >
                          {act}
                        </Button>
                      ))}
                    </Space>
                  ),
                },
              ]}
            />
          </div>
        </Space>
      ),
    },
    {
      key: "requisitions",
      label: "费用申请与预算占用 (EXP01)",
      children: (
        <Space direction="vertical" style={{ width: "100%" }}>
          <Space>
            <Button
              type="primary"
              disabled={!p.canCreate || busy}
              onClick={() => {
                form.resetFields();
                setModalType("create-req");
              }}
            >
              提交费用申请
            </Button>
          </Space>
          <div style={{ width: "100%", maxWidth: "100%", overflowX: "auto" }}>
            <Table
              rowKey="id"
              dataSource={requisitions}
              scroll={{ x: "max-content" }}
              columns={[
                { title: "申请单号", dataIndex: "requisitionNo" },
                { title: "业务目的", dataIndex: "businessPurpose" },
                { title: "总预算 (CNY)", dataIndex: "totalBudgetAmount" },
                { title: "预占额度 (CNY)", dataIndex: "reservedAmount" },
                { title: "已执行额度 (CNY)", dataIndex: "utilizedAmount" },
                {
                  title: "状态",
                  dataIndex: "status",
                  render: (st: string) => <Tag color={st === "APPROVED" ? "green" : "blue"}>{st}</Tag>,
                },
                {
                  title: "操作",
                  render: (_, r: ExpenseRequisition) => (
                    <Space>
                      {requisitionActions(r.status).map((act) => (
                        <Button
                          key={act}
                          size="small"
                          disabled={busy}
                          onClick={() =>
                            send(`${expenseBase}/requisitions/${r.id}/actions`, {
                              action: act,
                              approverId: p.identity,
                            })
                          }
                        >
                          {act}
                        </Button>
                      ))}
                    </Space>
                  ),
                },
              ]}
            />
          </div>
        </Space>
      ),
    },
    {
      key: "standards",
      label: "差旅标准版本 (EXP04)",
      children: (
        <Space direction="vertical" style={{ width: "100%" }}>
          <Space>
            <Button
              type="primary"
              disabled={!p.canCreate || busy}
              onClick={() => {
                form.resetFields();
                setModalType("create-policy");
              }}
            >
              拟定差旅标准版本
            </Button>
          </Space>
          <div style={{ width: "100%", maxWidth: "100%", overflowX: "auto" }}>
            <Table
              rowKey="id"
              dataSource={policies}
              scroll={{ x: "max-content" }}
              columns={[
                { title: "标准编码", dataIndex: "policyCode" },
                { title: "版本号", dataIndex: "versionInt" },
                { title: "标准名称", dataIndex: "name" },
                { title: "生效起始", dataIndex: "effectiveFrom" },
                { title: "生效截止", dataIndex: "effectiveTo" },
                {
                  title: "状态",
                  dataIndex: "status",
                  render: (st: string) => <Tag color={st === "ACTIVE" ? "green" : "default"}>{st}</Tag>,
                },
                {
                  title: "操作",
                  render: (_, r: TravelPolicy) =>
                    r.status === "DRAFT" ? (
                      <Button
                        size="small"
                        disabled={busy}
                        onClick={() => send(`${expenseBase}/policies/${r.id}/activate`, {})}
                      >
                        激活生效
                      </Button>
                    ) : null,
                },
              ]}
            />
          </div>
        </Space>
      ),
    },
    {
      key: "payables",
      label: "员工应付款台账 (EXP03)",
      children: (
        <div style={{ width: "100%", maxWidth: "100%", overflowX: "auto" }}>
          <Table
            rowKey="id"
            dataSource={payables}
            scroll={{ x: "max-content" }}
            columns={[
              { title: "台账编号", dataIndex: "id" },
              { title: "关联报销单", dataIndex: "claimId" },
              { title: "应付总额 (CNY)", dataIndex: "payableAmount" },
              { title: "已付金额 (CNY)", dataIndex: "paidAmount" },
              { title: "未付余额 (CNY)", dataIndex: "outstandingBalance" },
              {
                title: "结算状态",
                dataIndex: "settlementStatus",
                render: (st: string) => (
                  <Tag color={st === "SETTLED" ? "green" : st === "CANCELLED" ? "red" : "orange"}>
                    {st}
                  </Tag>
                ),
              },
            ]}
          />
        </div>
      ),
    },
  ];

  return (
    <div style={{ padding: 16, maxWidth: "100%", minWidth: 0, boxSizing: "border-box", overflowX: "hidden" }}>
      <Typography.Title level={4}>费用中心与差旅管理工作台 (EXP01 - EXP04)</Typography.Title>
      {error && <Alert type="error" message={error} style={{ marginBottom: 16 }} closable onClose={() => setError("")} />}
      <Tabs activeKey={activeTab} onChange={setActiveTab} items={tabItems} />

      {/* Modal: Create Requisition */}
      <Modal
        title="新建费用申请单 (EXP01)"
        open={modalType === "create-req"}
        onCancel={() => setModalType(null)}
        footer={null}
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={(values) =>
            send(`${expenseBase}/requisitions`, {
              requisitionNo: values.requisitionNo || `REQ-${Date.now()}`,
              applicantId: values.applicantId,
              departmentId: values.departmentId,
              purpose: values.purpose,
              totalBudget: expenseCny(values.totalBudget),
              effectiveUntil: values.effectiveUntil,
            })
          }
        >
          <Form.Item name="requisitionNo" label="申请单号">
            <Input placeholder="自动生成如留空" />
          </Form.Item>
          <Form.Item name="applicantId" label="申请人" rules={[{ required: true }]}>
            <Select options={p.employees} />
          </Form.Item>
          <Form.Item name="departmentId" label="归属部门" rules={[{ required: true }]}>
            <Select options={p.departments} />
          </Form.Item>
          <Form.Item name="purpose" label="申请事由" rules={[{ required: true }]}>
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item name="totalBudget" label="申请预算 (CNY)" rules={[{ required: true }]}>
            <Input placeholder="例如 5000.00" />
          </Form.Item>
          <Form.Item name="effectiveUntil" label="有效截止日期" rules={[{ required: true }]}>
            <Input type="date" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={busy}>
              提交申请
            </Button>
          </Form.Item>
        </Form>
      </Modal>

      {/* Modal: Create Advance */}
      <Modal
        title="申请员工借款单 (EXP02)"
        open={modalType === "create-advance"}
        onCancel={() => setModalType(null)}
        footer={null}
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={(values) =>
            send(`${expenseBase}/advances`, {
              advanceNo: values.advanceNo || `ADV-${Date.now()}`,
              employeeId: values.employeeId,
              applicantId: p.identity,
              purpose: values.purpose,
              principalAmount: expenseCny(values.principalAmount),
            })
          }
        >
          <Form.Item name="advanceNo" label="借款单号">
            <Input placeholder="自动生成如留空" />
          </Form.Item>
          <Form.Item name="employeeId" label="借款员工" rules={[{ required: true }]}>
            <Select options={p.employees} />
          </Form.Item>
          <Form.Item name="purpose" label="借款用途" rules={[{ required: true }]}>
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item name="principalAmount" label="借款本金 (CNY)" rules={[{ required: true }]}>
            <Input placeholder="例如 1000.00" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={busy}>
              确认申请
            </Button>
          </Form.Item>
        </Form>
      </Modal>

      {/* Modal: Create Claim */}
      <Modal
        title="新建费用报销单 (EXP03 多票/部分认可)"
        open={modalType === "create-claim"}
        onCancel={() => setModalType(null)}
        footer={null}
        width={600}
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={(values) => {
            const sub = expenseCny(values.submittedAmount);
            const dis = values.disallowedAmount ? expenseCny(values.disallowedAmount, true) : "0.00";
            send(`${expenseBase}/claims`, {
              claimNo: values.claimNo || `CLM-${Date.now()}`,
              employeeId: values.employeeId,
              submitterId: p.identity,
              title: values.title,
              claimDate: values.claimDate || new Date().toISOString().split("T")[0],
              lines: [
                {
                  lineNo: 1,
                  expenseCategory: values.category || "MEAL",
                  departmentId: values.departmentId,
                  submittedAmount: sub,
                  disallowedAmount: dis,
                  disallowanceReason: dis !== "0.00" ? values.disallowanceReason || "个人自理部分核减" : undefined,
                  vatRate: values.vatRate || "0",
                },
              ],
            });
          }}
        >
          <Form.Item name="claimNo" label="报销单号">
            <Input placeholder="自动生成如留空" />
          </Form.Item>
          <Form.Item name="employeeId" label="报销员工" rules={[{ required: true }]}>
            <Select options={p.employees} />
          </Form.Item>
          <Form.Item name="departmentId" label="费用归属部门" rules={[{ required: true }]}>
            <Select options={p.departments} />
          </Form.Item>
          <Form.Item name="category" label="费用类别" initialValue="MEAL">
            <Select
              options={[
                { value: "HOTEL", label: "住宿费" },
                { value: "MEAL", label: "餐饮费" },
                { value: "TRANSPORT", label: "交通费" },
                { value: "DAILY_ALLOWANCE", label: "差旅补贴" },
                { value: "OFFICE", label: "办公费" },
              ]}
            />
          </Form.Item>
          <Form.Item name="title" label="报销事由" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="submittedAmount" label="申报金额 (CNY)" rules={[{ required: true }]}>
            <Input placeholder="例如 1200.00" />
          </Form.Item>
          <Form.Item name="disallowedAmount" label="核减个人自理金额 (CNY)">
            <Input placeholder="例如 200.00 (无核减填0)" />
          </Form.Item>
          <Form.Item name="disallowanceReason" label="核减原因说明">
            <Input placeholder="有核减金额时必填" />
          </Form.Item>
          <Form.Item name="vatRate" label="增值税税率 (进项抵扣)" initialValue="0">
            <Select
              defaultValue="0"
              options={[
                { value: "0", label: "0% (不含进项税)" },
                { value: "0.06", label: "6% 服务/餐饮/住宿" },
                { value: "0.09", label: "9% 交通运输" },
                { value: "0.13", label: "13% 货物物资" },
              ]}
            />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={busy}>
              确认创建
            </Button>
          </Form.Item>
        </Form>
      </Modal>

      {/* Modal: Create Policy */}
      <Modal
        title="拟定差旅标准版本 (EXP04)"
        open={modalType === "create-policy"}
        onCancel={() => setModalType(null)}
        footer={null}
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={(values) =>
            send(`${expenseBase}/policies`, {
              policyCode: values.policyCode,
              versionInt: Number(values.versionInt) || 1,
              name: values.name,
              effectiveFrom: values.effectiveFrom,
              effectiveTo: values.effectiveTo || null,
              createdBy: p.identity,
              caps: [
                {
                  cityTier: "TIER_1",
                  employeeRank: "STAFF",
                  expenseCategory: "HOTEL",
                  dailyCapAmount: expenseCny(values.hotelCap || "500.00"),
                },
              ],
            })
          }
        >
          <Form.Item name="policyCode" label="标准编码" rules={[{ required: true }]}>
            <Input placeholder="如 POL-2026-T1" />
          </Form.Item>
          <Form.Item name="versionInt" label="版本序号" initialValue={1} rules={[{ required: true }]}>
            <InputNumber min={1} />
          </Form.Item>
          <Form.Item name="name" label="标准名称" rules={[{ required: true }]}>
            <Input placeholder="如 2026年度差旅住宿标准" />
          </Form.Item>
          <Form.Item name="effectiveFrom" label="生效起始日期" rules={[{ required: true }]}>
            <Input type="date" />
          </Form.Item>
          <Form.Item name="effectiveTo" label="生效截止日期">
            <Input type="date" />
          </Form.Item>
          <Form.Item name="hotelCap" label="一线城市员工酒店限额 (CNY)" initialValue="500.00">
            <Input />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={busy}>
              拟定草案
            </Button>
          </Form.Item>
        </Form>
      </Modal>

      {/* Modal: Disburse Payment */}
      <Modal
        title="报销款对外支付 (EXP03)"
        open={modalType === "disburse-payment"}
        onCancel={() => {
          setModalType(null);
          setSelectedClaim(null);
        }}
        footer={null}
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={(values) => {
            if (!selectedClaim) return;
            send(`${expenseBase}/claims/${selectedClaim.id}/disburse`, {
              paymentNo: values.paymentNo || `PAY-${Date.now()}`,
              amount: expenseCny(values.amount),
              paymentDate: values.paymentDate || new Date().toISOString().split("T")[0],
              paymentMethod: values.paymentMethod || "BANK_TRANSFER",
              paymentRef: values.paymentRef || `TX-REF-${Date.now()}`,
              cashierId: p.identity,
            });
          }}
        >
          <Form.Item name="paymentNo" label="支付单号">
            <Input placeholder="自动生成如留空" />
          </Form.Item>
          <Form.Item name="amount" label="支付金额 (CNY)" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="paymentMethod" label="支付方式" initialValue="BANK_TRANSFER">
            <Select
              options={[
                { value: "BANK_TRANSFER", label: "银行转账" },
                { value: "CASH", label: "现金" },
                { value: "CORPORATE_CARD", label: "企业公务卡" },
              ]}
            />
          </Form.Item>
          <Form.Item name="paymentRef" label="银行流水参考号">
            <Input placeholder="自动生成如留空" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={busy}>
              确认对外支付
            </Button>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

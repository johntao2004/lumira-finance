import { useEffect, useRef, useState } from "react";
import { Alert, Button, Form, Input, Modal, Select, Space, Table, Tabs, Tag, Typography } from "antd";
import {
  cny,
  agingBucketLabels,
  agingBucketLossRates,
  calculatePriceVariance,
  calculateQuantityVariance,
  calculateTotalVariance,
  calculateEclProvision,
  calculateConstrainedCap,
  validateStandardForContractAsset,
  validateStandardForEclProvision,
  validateSegregationOfDuties,
  retryIdentity,
  type AccountingStandard,
  type AgingBucket,
  type EclOperationKind,
} from "./ap-ar-ext-rules";

type Option = { value: string; label: string };

type Props = {
  identity: string;
  scope: string;
  active: boolean;
  canCreate: boolean;
  canReview: boolean;
  accountingStandard: AccountingStandard;
  suppliers: Option[];
  customers: Option[];
  read: <T>(url: string, signal: AbortSignal) => Promise<T>;
  write: <T>(url: string, body: unknown) => Promise<T>;
};

export function ApArExtPanel(props: Props) {
  const [activeTab, setActiveTab] = useState("ap-provisional");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [revision, setRevision] = useState(0);

  // Forms
  const [poForm] = Form.useForm();
  const [accForm] = Form.useForm();
  const [provForm] = Form.useForm();
  const [settleForm] = Form.useForm();
  const [contractAssetForm] = Form.useForm();
  const [varForm] = Form.useForm();
  const [eclForm] = Form.useForm();
  const [allowanceForm] = Form.useForm();

  // Modals
  const [modalOpen, setModalOpen] = useState(false);
  const [modalType, setModalType] = useState<string>("");

  const alive = useRef(true);
  const writeLock = useRef(false);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  async function handleSend(route: string, body: Record<string, unknown>) {
    if (writeLock.current || !props.active) return;
    writeLock.current = true;
    setBusy(true);
    setError("");
    try {
      const ids = await retryIdentity(props.identity, route, body, sessionStorage);
      if (!alive.current) return;
      await props.write(route, { ...body, idempotencyKey: ids.idempotencyKey });
      if (alive.current) {
        setModalOpen(false);
        setRevision((v) => v + 1);
      }
    } catch (e: unknown) {
      if (alive.current) {
        setError(e instanceof Error ? e.message : String(e));
      }
    } finally {
      writeLock.current = false;
      if (alive.current) setBusy(false);
    }
  }

  return (
    <div style={{ padding: 16 }}>
      <Typography.Title level={4}>
        AP01 采购暂估与冲回 / AR01 合同资产 / AR02 坏账准备与折让扩展
      </Typography.Title>

      <Space orientation="horizontal" style={{ marginBottom: 16 }}>
        <Tag color={props.accountingStandard === "CAS" ? "blue" : "green"}>
          核算制度: {props.accountingStandard === "CAS" ? "企业会计准则 (CAS)" : "小企业会计准则 (SMALL)"}
        </Tag>
        <Tag color="purple">通道 W06 (ap-ar-ext)</Tag>
      </Space>

      {error && (
        <Alert
          type="error"
          showIcon
          closable
          message="操作失败"
          description={error}
          onClose={() => setError("")}
          style={{ marginBottom: 16 }}
        />
      )}

      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          {
            key: "ap-provisional",
            label: "AP01 采购订单、验收与暂估",
            children: (
              <div>
                <Space style={{ marginBottom: 16 }}>
                  <Button
                    type="primary"
                    disabled={!props.canCreate}
                    onClick={() => {
                      setModalType("CREATE_PO");
                      poForm.resetFields();
                      setModalOpen(true);
                    }}
                  >
                    新增采购订单 (PO)
                  </Button>
                  <Button
                    disabled={!props.canCreate}
                    onClick={() => {
                      setModalType("CREATE_ACCEPTANCE");
                      accForm.resetFields();
                      setModalOpen(true);
                    }}
                  >
                    登记收货验收单
                  </Button>
                  <Button
                    disabled={!props.canCreate}
                    onClick={() => {
                      setModalType("CREATE_PROVISIONAL");
                      provForm.resetFields();
                      setModalOpen(true);
                    }}
                  >
                    月末暂估入账 (先收货后到票)
                  </Button>
                  <Button
                    type="dashed"
                    disabled={!props.canCreate}
                    onClick={() => {
                      setModalType("SETTLE_PROVISIONAL");
                      settleForm.resetFields();
                      setModalOpen(true);
                    }}
                  >
                    到票冲暂估及差额调整
                  </Button>
                </Space>
                <Alert
                  type="info"
                  message="暂估业务规则"
                  description="月末对于已验收但发票尚未到达的物料/服务，系统按估计净价入账（借：原材料/费用，贷：应付账款-暂估应付），不估进项税额；次月或到票时全额冲回暂估，录入实际发票并自动分解价格差异与数量差异。"
                />
              </div>
            ),
          },
          {
            key: "ar-contract-asset",
            label: "AR01 合同资产与可变对价",
            children: (
              <div>
                <Space style={{ marginBottom: 16 }}>
                  <Button
                    type="primary"
                    disabled={!props.canCreate || props.accountingStandard === "SMALL"}
                    onClick={() => {
                      setModalType("CREATE_CONTRACT_ASSET");
                      contractAssetForm.resetFields();
                      setModalOpen(true);
                    }}
                  >
                    确认合同资产 (CAS 14)
                  </Button>
                  <Button
                    disabled={!props.canCreate || props.accountingStandard === "SMALL"}
                    onClick={() => {
                      setModalType("CREATE_VAR_CONSIDERATION");
                      varForm.resetFields();
                      setModalOpen(true);
                    }}
                  >
                    申报可变对价上限
                  </Button>
                </Space>
                {props.accountingStandard === "SMALL" && (
                  <Alert
                    type="warning"
                    message="制度限制提醒"
                    description="小企业会计准则 (SMALL) 不设置合同资产科目，履约直接形成无条件应收账款；合同资产仅在 CAS 下开放。"
                    style={{ marginBottom: 16 }}
                  />
                )}
                <Alert
                  type="info"
                  message="CAS 14 履约与对价规则"
                  description="客户取得商品控制权但支付仍取决于时间流逝之外的其他条件的，计入合同资产；待条件成就后重分类为应收账款。可变对价须经极可能不发生重大转回原则约束，由独立审批人审签上限。"
                />
              </div>
            ),
          },
          {
            key: "ar-ecl-allowance",
            label: "AR02 坏账准备、核销与折让退回",
            children: (
              <div>
                <Space style={{ marginBottom: 16 }}>
                  <Button
                    type="primary"
                    disabled={!props.canCreate}
                    onClick={() => {
                      setModalType("CREATE_ECL");
                      eclForm.resetFields();
                      setModalOpen(true);
                    }}
                  >
                    计提/转回/核销坏账准备
                  </Button>
                  <Button
                    disabled={!props.canCreate}
                    onClick={() => {
                      setModalType("CREATE_ALLOWANCE");
                      allowanceForm.resetFields();
                      setModalOpen(true);
                    }}
                  >
                    登记销售折让/商业折扣/退回
                  </Button>
                </Space>
                <Alert
                  type="info"
                  message="坏账与折让核算规则"
                  description="CAS 模式下按账龄矩阵或单项重大信用风险计提预期信用损失 (ECL)；小企业 (SMALL) 模式下不计提坏账准备，在实际坏账发生时直接列支营业外支出。销售折让附红字发票信息表核减应收账款及销项税额。"
                />
              </div>
            ),
          },
        ]}
      />

      <Modal
        title={
          modalType === "CREATE_PO"
            ? "新增采购订单"
            : modalType === "CREATE_ACCEPTANCE"
            ? "收货服务验收登记"
            : modalType === "CREATE_PROVISIONAL"
            ? "月末暂估应付入账"
            : modalType === "SETTLE_PROVISIONAL"
            ? "到票冲暂估及差额调整"
            : modalType === "CREATE_CONTRACT_ASSET"
            ? "确认合同资产"
            : modalType === "CREATE_VAR_CONSIDERATION"
            ? "申报可变对价上限"
            : modalType === "CREATE_ECL"
            ? "坏账准备/核销处理"
            : "销售折让与退回调整"
        }
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        footer={null}
        destroyOnClose
      >
        {modalType === "CREATE_PROVISIONAL" && (
          <Form
            form={provForm}
            layout="vertical"
            onFinish={(values) =>
              handleSend(`${props.scope}/subledger-ext/ap/provisional`, {
                acceptanceId: values.acceptanceId,
                poId: values.poId,
                accrualPeriod: Number(values.accrualPeriod),
                accrualDate: values.accrualDate,
                estimatedNetAmount: cny(values.estimatedNetAmount),
                reason: values.reason,
              })
            }
          >
            <Form.Item name="acceptanceId" label="关联验收单ID" rules={[{ required: true }]}>
              <Input placeholder="验收单 UUID" />
            </Form.Item>
            <Form.Item name="poId" label="关联采购订单ID" rules={[{ required: true }]}>
              <Input placeholder="采购订单 UUID" />
            </Form.Item>
            <Form.Item name="accrualPeriod" label="所属期间 (1-12)" rules={[{ required: true }]}>
              <Input placeholder="例如: 11" />
            </Form.Item>
            <Form.Item name="accrualDate" label="暂估入账日期" rules={[{ required: true }]}>
              <Input placeholder="YYYY-MM-DD" />
            </Form.Item>
            <Form.Item name="estimatedNetAmount" label="暂估金额 (不含税)" rules={[{ required: true }]}>
              <Input placeholder="0.00" />
            </Form.Item>
            <Form.Item name="reason" label="暂估事由" rules={[{ required: true }]}>
              <Input.TextArea placeholder="到期未到票月末暂估依据" />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={busy} block>
              提交暂估入账
            </Button>
          </Form>
        )}

        {modalType === "SETTLE_PROVISIONAL" && (
          <Form
            form={settleForm}
            layout="vertical"
            onFinish={(values) =>
              handleSend(`${props.scope}/subledger-ext/ap/provisional/settle`, {
                provisionalId: values.provisionalId,
                invoiceNumber: values.invoiceNumber,
                invoiceDate: values.invoiceDate,
                actualNetAmount: cny(values.actualNetAmount, true),
                actualTaxAmount: cny(values.actualTaxAmount, true),
                actualGrossAmount: cny(values.actualGrossAmount, true),
              })
            }
          >
            <Form.Item name="provisionalId" label="原暂估记录ID" rules={[{ required: true }]}>
              <Input placeholder="暂估记录 UUID" />
            </Form.Item>
            <Form.Item name="invoiceNumber" label="到票发票代码/号码" rules={[{ required: true }]}>
              <Input placeholder="发票代码号码" />
            </Form.Item>
            <Form.Item name="invoiceDate" label="发票开具日期" rules={[{ required: true }]}>
              <Input placeholder="YYYY-MM-DD" />
            </Form.Item>
            <Form.Item name="actualNetAmount" label="发票净额 (不含税)" rules={[{ required: true }]}>
              <Input placeholder="0.00" />
            </Form.Item>
            <Form.Item name="actualTaxAmount" label="增值税进项税额" rules={[{ required: true }]}>
              <Input placeholder="0.00" />
            </Form.Item>
            <Form.Item name="actualGrossAmount" label="发票价税合计" rules={[{ required: true }]}>
              <Input placeholder="0.00" />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={busy} block>
              确认到票冲暂估并记差异
            </Button>
          </Form>
        )}

        {modalType === "CREATE_CONTRACT_ASSET" && (
          <Form
            form={contractAssetForm}
            layout="vertical"
            onFinish={(values) => {
              validateStandardForContractAsset(props.accountingStandard);
              return handleSend(`${props.scope}/subledger-ext/ar/contract-assets`, {
                contractId: values.contractId,
                customerId: values.customerId,
                stageName: values.stageName,
                milestoneDate: values.milestoneDate,
                assetAmount: cny(values.assetAmount),
                conditionDescription: values.conditionDescription,
              });
            }}
          >
            <Form.Item name="contractId" label="销售合同ID" rules={[{ required: true }]}>
              <Input placeholder="合同 UUID" />
            </Form.Item>
            <Form.Item name="customerId" label="客户ID" rules={[{ required: true }]}>
              <Input placeholder="客户 UUID" />
            </Form.Item>
            <Form.Item name="stageName" label="履约阶段名称" rules={[{ required: true }]}>
              <Input placeholder="如: 第一阶段交付验收" />
            </Form.Item>
            <Form.Item name="milestoneDate" label="里程碑达成日期" rules={[{ required: true }]}>
              <Input placeholder="YYYY-MM-DD" />
            </Form.Item>
            <Form.Item name="assetAmount" label="合同资产确认金额" rules={[{ required: true }]}>
              <Input placeholder="0.00" />
            </Form.Item>
            <Form.Item name="conditionDescription" label="收款附带条件描述" rules={[{ required: true }]}>
              <Input.TextArea placeholder="如: 需待二阶段系统联调通过后方具有无条件收款权" />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={busy} block>
              提交合同资产确认
            </Button>
          </Form>
        )}

        {modalType === "CREATE_ECL" && (
          <Form
            form={eclForm}
            layout="vertical"
            onFinish={(values) => {
              const opKind = values.operationKind as EclOperationKind;
              validateStandardForEclProvision(props.accountingStandard, opKind);
              return handleSend(`${props.scope}/subledger-ext/ar/ecl`, {
                period: Number(values.period),
                assessmentType: values.assessmentType,
                customerId: values.customerId,
                receivableAmount: cny(values.receivableAmount, true),
                agingBucket: values.agingBucket,
                lossRate: values.lossRate,
                priorAllowance: cny(values.priorAllowance || "0.00", true),
                operationKind: values.operationKind,
                evidenceId: values.evidenceId || null,
              });
            }}
          >
            <Form.Item name="period" label="所属期间" rules={[{ required: true }]}>
              <Input placeholder="1-12" />
            </Form.Item>
            <Form.Item name="customerId" label="客户ID" rules={[{ required: true }]}>
              <Input placeholder="客户 UUID" />
            </Form.Item>
            <Form.Item name="assessmentType" label="评估模式" initialValue="AGING_BUCKET" rules={[{ required: true }]}>
              <Select
                options={[
                  { value: "AGING_BUCKET", label: "账龄组合矩阵计提" },
                  { value: "INDIVIDUAL_ASSESSMENT", label: "单项重大风险评估" },
                ]}
              />
            </Form.Item>
            <Form.Item name="agingBucket" label="账龄区间" initialValue="DAYS_1_30" rules={[{ required: true }]}>
              <Select
                options={Object.entries(agingBucketLabels).map(([k, v]) => ({
                  value: k,
                  label: `${v} (参考违约率: ${(parseFloat(agingBucketLossRates[k as AgingBucket]) * 100).toFixed(1)}%)`,
                }))}
              />
            </Form.Item>
            <Form.Item name="operationKind" label="处理类型" initialValue="PROVISION" rules={[{ required: true }]}>
              <Select
                options={[
                  { value: "PROVISION", label: "计提减值准备 (CAS)" },
                  { value: "REVERSAL", label: "转回减值准备 (CAS)" },
                  { value: "WRITE_OFF", label: "坏账核销 (CAS / SMALL)" },
                ]}
              />
            </Form.Item>
            <Form.Item name="receivableAmount" label="应收账款余额" rules={[{ required: true }]}>
              <Input placeholder="0.00" />
            </Form.Item>
            <Form.Item name="lossRate" label="预期违约损失率" initialValue="0.020000000000" rules={[{ required: true }]}>
              <Input placeholder="例如: 0.0200" />
            </Form.Item>
            <Form.Item name="priorAllowance" label="前期已计提坏账准备" initialValue="0.00">
              <Input placeholder="0.00" />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={busy} block>
              提交坏账评估
            </Button>
          </Form>
        )}
      </Modal>
    </div>
  );
}

import { useEffect, useState } from "react";
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Divider,
  Form,
  Input,
  InputNumber,
  Row,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
} from "antd";
import {
  assessStandard,
  calculateRevaluation,
  translateFx,
  validateLifecycleTransition,
  validateMultiDate,
} from "./org-fx-rules";

export interface OrgFxApi {
  listBranches(entityId: string, signal?: AbortSignal): Promise<any[]>;
  createBranch(entityId: string, data: any, retryKey: string): Promise<any>;
  transitionLifecycle(entityId: string, data: any, retryKey: string): Promise<any>;
  getLifecycle(entityId: string): Promise<any>;
  listTaxBureaus(): Promise<any[]>;
  assessStandardEligibility(entityId: string, data: any, retryKey: string): Promise<any>;
  createPolicyVersion(bookId: string, data: any, retryKey: string): Promise<any>;
  approvePolicyVersion(bookId: string, versionId: string, data: any, retryKey: string): Promise<any>;
  validateMultiDate(bookId: string, data: any): Promise<any>;
  handlePeriodReopen(bookId: string, periodId: string, data: any, retryKey: string): Promise<any>;
  registerFxRate(data: any, retryKey: string): Promise<any>;
  translateLine(data: any): Promise<any>;
  createFxRevaluationPlan(bookId: string, data: any, retryKey: string): Promise<any>;
  resolveTaxObligations(data: any): Promise<any>;
  listCurrencies(): Promise<any[]>;
}

export function OrgFxPanel({
  api,
  scopeKey,
  canMake,
  canReview,
}: {
  api: OrgFxApi;
  scopeKey: string;
  canMake: boolean;
  canReview: boolean;
}) {
  const [activeTab, setActiveTab] = useState("org01");
  const [error, setError] = useState("");
  const [successMsg, setSuccessMsg] = useState("");
  const [busy, setBusy] = useState(false);
  const [pendingCommand, setPendingCommand] = useState<{ action: string; body: any; key: string } | null>(null);

  const storageKey = `finance.orgfx.pending.${scopeKey}`;

  // SessionStorage retry key resilience
  useEffect(() => {
    try {
      const saved = sessionStorage.getItem(storageKey);
      if (saved) {
        const parsed = JSON.parse(saved);
        setPendingCommand(parsed);
        setError("存在尚未确认的组织/汇率操作，请使用原请求键重试。");
      }
    } catch (e) {
      setError(String(e));
    }
  }, [storageKey]);

  async function executeWithRetry(action: string, body: any, isRetry = false) {
    if (busy) return;
    if (pendingCommand && !isRetry) {
      setError("上一请求结果未确认，请先使用原请求键重试。");
      return;
    }
    const command = pendingCommand ?? { action, body, key: crypto.randomUUID() };
    setBusy(true);
    setError("");
    setSuccessMsg("");
    try {
      sessionStorage.setItem(storageKey, JSON.stringify(command));
      let res: any;
      if (action === "createBranch") {
        res = await api.createBranch(body.entityId, body.data, command.key);
      } else if (action === "transitionLifecycle") {
        res = await api.transitionLifecycle(body.entityId, body.data, command.key);
      } else if (action === "assessStandardEligibility") {
        res = await api.assessStandardEligibility(body.entityId, body.data, command.key);
      } else if (action === "createPolicyVersion") {
        res = await api.createPolicyVersion(body.bookId, body.data, command.key);
      } else if (action === "approvePolicyVersion") {
        res = await api.approvePolicyVersion(body.bookId, body.versionId, body.data, command.key);
      } else if (action === "handlePeriodReopen") {
        res = await api.handlePeriodReopen(body.bookId, body.periodId, body.data, command.key);
      } else if (action === "registerFxRate") {
        res = await api.registerFxRate(body.data, command.key);
      } else if (action === "createFxRevaluationPlan") {
        res = await api.createFxRevaluationPlan(body.bookId, body.data, command.key);
      }
      sessionStorage.removeItem(storageKey);
      setPendingCommand(null);
      setSuccessMsg(`操作成功执行 [${action}]`);
      return res;
    } catch (e) {
      setError(String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="org-fx-workbench" style={{ width: "100%", maxWidth: "1440px", margin: "0 auto", padding: "16px" }}>
      <Space direction="vertical" style={{ width: "100%" }}>
        <Alert
          type="info"
          showIcon
          title="组织主数据、多日期、汇率与税务日历工作台 (ORG01–ORG05)"
          description="严格执行自然人职责分离 (SoD)；金额采用 numeric(28,8)，汇率采用 numeric(28,12)；外部税务接口保持 NOT_VERIFIED 隔离状态。"
        />

        {error && <Alert type="error" showIcon title={error} closable onClose={() => setError("")} />}
        {successMsg && <Alert type="success" showIcon title={successMsg} closable onClose={() => setSuccessMsg("")} />}

        {pendingCommand && (
          <Button
            type="primary"
            danger
            disabled={busy}
            onClick={() => executeWithRetry(pendingCommand.action, pendingCommand.body, true)}
          >
            使用原请求键重试未确认操作 ({pendingCommand.action})
          </Button>
        )}

        {/* Responsive Grid Layout: Wide desktop 1440px, tablet/mobile 758px */}
        <Row gutter={[16, 16]}>
          <Col xs={24} md={24} lg={24}>
            <Tabs
              activeKey={activeTab}
              onChange={setActiveTab}
              items={[
                {
                  key: "org01",
                  label: "ORG01 组织机构与主体生命周期",
                  children: (
                    <Org01Section
                      api={api}
                      canMake={canMake}
                      canReview={canReview}
                      busy={busy}
                      onExecute={executeWithRetry}
                    />
                  ),
                },
                {
                  key: "org02",
                  label: "ORG02 会计准则适用与政策版本",
                  children: (
                    <Org02Section
                      api={api}
                      canMake={canMake}
                      canReview={canReview}
                      busy={busy}
                      onExecute={executeWithRetry}
                    />
                  ),
                },
                {
                  key: "org03",
                  label: "ORG03 四日期语义与期间重开",
                  children: (
                    <Org03Section
                      api={api}
                      canMake={canMake}
                      canReview={canReview}
                      busy={busy}
                      onExecute={executeWithRetry}
                    />
                  ),
                },
                {
                  key: "org04",
                  label: "ORG04 外币汇率快照与月末重估",
                  children: (
                    <Org04Section
                      api={api}
                      canMake={canMake}
                      canReview={canReview}
                      busy={busy}
                      onExecute={executeWithRetry}
                    />
                  ),
                },
                {
                  key: "org05",
                  label: "ORG05 区域征期与纳税义务清单",
                  children: <Org05Section api={api} />,
                },
              ]}
            />
          </Col>
        </Row>
      </Space>
    </div>
  );
}

function Org01Section({
  api,
  canMake,
  canReview,
  busy,
  onExecute,
}: {
  api: OrgFxApi;
  canMake: boolean;
  canReview: boolean;
  busy: boolean;
  onExecute: (action: string, body: any) => Promise<any>;
}) {
  const [lifecycleForm] = Form.useForm();
  const [branchForm] = Form.useForm();
  const [branches, setBranches] = useState<any[]>([]);
  const [taxBureaus, setTaxBureaus] = useState<any[]>([]);
  const [selectedEntityId, setSelectedEntityId] = useState("");
  const [currentLifecycle, setCurrentLifecycle] = useState<any>(null);

  useEffect(() => {
    api.listTaxBureaus().then(setTaxBureaus).catch(() => {});
  }, [api]);

  async function loadEntityData(entityId: string) {
    if (!entityId) return;
    setSelectedEntityId(entityId);
    try {
      const [bList, life] = await Promise.all([
        api.listBranches(entityId),
        api.getLifecycle(entityId),
      ]);
      setBranches(bList || []);
      setCurrentLifecycle(life || null);
    } catch (e) {
      // fallback
    }
  }

  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Card title="法人主体生命周期流转 (天然人 SoD 双签)">
        <Form
          form={lifecycleForm}
          layout="vertical"
          onFinish={async (values) => {
            const check = validateLifecycleTransition(
              currentLifecycle?.currentStatus || "PREPARING",
              values.targetStatus,
              values.makerPersonId,
              values.reviewerPersonId
            );
            if (!check.isValid) {
              alert(check.error);
              return;
            }
            await onExecute("transitionLifecycle", { entityId: values.entityId, data: values });
            loadEntityData(values.entityId);
          }}
        >
          <Row gutter={16}>
            <Col xs={24} sm={12}>
              <Form.Item name="entityId" label="法人主体标识 (UUID)" rules={[{ required: true }]}>
                <Input
                  placeholder="输入法人主体 UUID 并回车查询"
                  onBlur={(e) => loadEntityData(e.target.value)}
                />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item label="当前生命周期状态">
                <Tag color="blue">{currentLifecycle?.currentStatus || "PREPARING"}</Tag>
                {currentLifecycle?.version && <span>(v{currentLifecycle.version})</span>}
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item name="targetStatus" label="目标流转状态" rules={[{ required: true }]}>
                <Select
                  options={[
                    { label: "待财务确认 (PENDING_CONFIRMATION)", value: "PENDING_CONFIRMATION" },
                    { label: "正式启用 (ACTIVE)", value: "ACTIVE" },
                    { label: "暂停停用 (SUSPENDED)", value: "SUSPENDED" },
                    { label: "终止注销 (TERMINATED)", value: "TERMINATED" },
                  ]}
                />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item name="makerPersonId" label="经办人天然人标识 (Maker)" rules={[{ required: true }]}>
                <Input placeholder="经办人天然人 UUID" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item name="reviewerPersonId" label="复核人天然人标识 (Reviewer)" rules={[{ required: true }]}>
                <Input placeholder="必须与经办人不同" />
              </Form.Item>
            </Col>
            <Col xs={24}>
              <Form.Item name="reason" label="变更事实理由" rules={[{ required: true, max: 1000 }]}>
                <Input.TextArea placeholder="记录主体生命周期流转的事实依据" />
              </Form.Item>
            </Col>
          </Row>
          <Button type="primary" htmlType="submit" disabled={busy || !canMake || !canReview}>
            提交状态流转 (双签审核)
          </Button>
        </Form>
      </Card>

      <Card title="分支机构管理与主管税局指定">
        <Form
          form={branchForm}
          layout="vertical"
          onFinish={async (values) => {
            const bureau = taxBureaus.find((b) => b.bureauCode === values.taxBureauCode);
            const data = {
              ...values,
              taxBureauName: bureau ? bureau.bureauName : "主管税务机关",
            };
            await onExecute("createBranch", { entityId: values.entityId, data });
            loadEntityData(values.entityId);
            branchForm.resetFields(["branchCode", "branchName", "address"]);
          }}
        >
          <Row gutter={16}>
            <Col xs={24} sm={8}>
              <Form.Item name="entityId" label="所属法人主体标识" initialValue={selectedEntityId} rules={[{ required: true }]}>
                <Input placeholder="所属法人主体 UUID" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="branchCode" label="分支机构代码" rules={[{ required: true }]}>
                <Input placeholder="SH-BRANCH-01" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="branchName" label="分支机构全称" rules={[{ required: true }]}>
                <Input placeholder="上海陆家嘴独立核算分公司" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="branchType" label="分支类型" initialValue="INDEPENDENT_ACCOUNTING_BRANCH" rules={[{ required: true }]}>
                <Select
                  options={[
                    { label: "内部非独立核算分支", value: "INTERNAL_NON_INDEPENDENT" },
                    { label: "独立核算分支机构", value: "INDEPENDENT_ACCOUNTING_BRANCH" },
                    { label: "跨地区分支机构", value: "CROSS_REGIONAL_BRANCH" },
                  ]}
                />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="taxFilingMode" label="税收申报模式" initialValue="LOCAL_INDEPENDENT_FILING" rules={[{ required: true }]}>
                <Select
                  options={[
                    { label: "总部集中汇算申报", value: "CONSOLIDATED_HEADQUARTERS" },
                    { label: "属地独立申报纳税", value: "LOCAL_INDEPENDENT_FILING" },
                  ]}
                />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="taxBureauCode" label="主管税务机关" rules={[{ required: true }]}>
                <Select
                  showSearch
                  placeholder="选择主管税局"
                  options={taxBureaus.map((b) => ({
                    label: `${b.bureauName} (${b.bureauCode})`,
                    value: b.bureauCode,
                  }))}
                />
              </Form.Item>
            </Col>
            <Col xs={24}>
              <Form.Item name="address" label="经营场所地址">
                <Input placeholder="经营注册地详细地址" />
              </Form.Item>
            </Col>
          </Row>
          <Button type="primary" htmlType="submit" disabled={busy || !canMake}>
            创建/登记分支机构
          </Button>
        </Form>

        {branches.length > 0 && (
          <div style={{ marginTop: 16 }}>
            <Table
              rowKey="id"
              dataSource={branches}
              pagination={false}
              scroll={{ x: 758 }}
              columns={[
                { title: "分支代码", dataIndex: "branchCode" },
                { title: "分支名称", dataIndex: "branchName" },
                { title: "分支类型", dataIndex: "branchType" },
                { title: "申报模式", dataIndex: "taxFilingMode" },
                { title: "主管税局", dataIndex: "taxBureauName" },
                { title: "状态", dataIndex: "status", render: (s) => <Tag color={s === "ACTIVE" ? "green" : "red"}>{s}</Tag> },
              ]}
            />
          </div>
        )}
      </Card>
    </Space>
  );
}

function Org02Section({
  api,
  canMake,
  canReview,
  busy,
  onExecute,
}: {
  api: OrgFxApi;
  canMake: boolean;
  canReview: boolean;
  busy: boolean;
  onExecute: (action: string, body: any) => Promise<any>;
}) {
  const [result, setResult] = useState<any>(null);
  const [form] = Form.useForm();
  const [policyForm] = Form.useForm();
  const [approveForm] = Form.useForm();

  function onAssess(values: any) {
    const res = assessStandard({
      isPubliclyTraded: !!values.isPubliclyTraded,
      isFinancialInstitution: !!values.isFinancialInstitution,
      isGroupSubsidiaryOfPublic: !!values.isGroupSubsidiaryOfPublic,
      employeeCount: Number(values.employeeCount || 0),
      annualRevenueStr: String(values.annualRevenue || "0"),
      totalAssetsStr: String(values.totalAssets || "0"),
    });
    setResult(res);
  }

  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Card title="小企业准则 (SMALL) 资格评估与降级阻断">
        <Form form={form} layout="vertical" onFinish={onAssess}>
          <Row gutter={16}>
            <Col xs={24} sm={8}>
              <Form.Item name="entityId" label="评估目标法人主体 UUID" rules={[{ required: true }]}>
                <Input placeholder="输入目标主体 UUID" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="isPubliclyTraded" label="是否上市公司">
                <Select options={[{ label: "否", value: false }, { label: "是", value: true }]} defaultValue={false} />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="isFinancialInstitution" label="是否金融机构">
                <Select options={[{ label: "否", value: false }, { label: "是", value: true }]} defaultValue={false} />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="isGroupSubsidiaryOfPublic" label="是否公众母公司下属集团子公司">
                <Select options={[{ label: "否", value: false }, { label: "是", value: true }]} defaultValue={false} />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="employeeCount" label="员工人数" rules={[{ required: true }]}>
                <InputNumber min={0} style={{ width: "100%" }} />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="annualRevenue" label="年营业收入 (CNY)" rules={[{ required: true }]}>
                <Input placeholder="50000000.00" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="totalAssets" label="资产总额 (CNY)" rules={[{ required: true }]}>
                <Input placeholder="40000000.00" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="assessorPersonId" label="评估人天然人标识" rules={[{ required: true }]}>
                <Input placeholder="评估人天然人 UUID" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8} style={{ display: "flex", alignItems: "flex-end" }}>
              <Space>
                <Button type="primary" htmlType="submit">
                  试算资格
                </Button>
                <Button
                  onClick={async () => {
                    const values = form.getFieldsValue();
                    await onExecute("assessStandardEligibility", {
                      entityId: values.entityId,
                      data: {
                        ...values,
                        annualRevenue: String(values.annualRevenue || "0"),
                        totalAssets: String(values.totalAssets || "0"),
                      },
                    });
                  }}
                  disabled={busy || !canMake}
                >
                  持久化评估底稿
                </Button>
              </Space>
            </Col>
          </Row>
        </Form>
        {result && (
          <div style={{ marginTop: "16px" }}>
            <Descriptions bordered size="small">
              <Descriptions.Item label="评估准则">
                <Tag color={result.standard === "SMALL" ? "green" : "blue"}>{result.standard}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="小企业准则资格">
                {result.eligibleForSmall ? "符合小企业准则" : "不符合 (强制 CAS)"}
              </Descriptions.Item>
              <Descriptions.Item label="不合规原因清单">
                {result.disqualifications.length > 0 ? result.disqualifications.join("; ") : "无限制"}
              </Descriptions.Item>
            </Descriptions>
          </div>
        )}
      </Card>

      <Card title="会计政策版本管理与生效审批 (SoD)">
        <Form
          form={policyForm}
          layout="vertical"
          onFinish={async (values) => {
            await onExecute("createPolicyVersion", { bookId: values.bookId, data: values });
          }}
        >
          <Row gutter={16}>
            <Col xs={24} sm={8}>
              <Form.Item name="bookId" label="账套标识 (bookId)" rules={[{ required: true }]}>
                <Input placeholder="目标账套 UUID" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="accountingStandard" label="执行会计准则" initialValue="CAS" rules={[{ required: true }]}>
                <Select options={[{ label: "企业会计准则 (CAS)", value: "CAS" }, { label: "小企业会计准则 (SMALL)", value: "SMALL" }]} />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="depreciationMethod" label="固定资产折旧方法" initialValue="STRAIGHT_LINE" rules={[{ required: true }]}>
                <Select
                  options={[
                    { label: "年限平均法 (STRAIGHT_LINE)", value: "STRAIGHT_LINE" },
                    { label: "双倍余额递减法 (DOUBLE_DECLINING)", value: "DOUBLE_DECLINING" },
                    { label: "年数总和法 (SUM_OF_YEARS_DIGITS)", value: "SUM_OF_YEARS_DIGITS" },
                  ]}
                />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="inventoryCostingMethod" label="存货发出计价方法" initialValue="WEIGHTED_AVERAGE" rules={[{ required: true }]}>
                <Select
                  options={[
                    { label: "月末一次加权平均法 (WEIGHTED_AVERAGE)", value: "WEIGHTED_AVERAGE" },
                    { label: "先进先出法 (FIFO)", value: "FIFO" },
                    { label: "个别计价法 (INDIVIDUAL_VALUATION)", value: "INDIVIDUAL_VALUATION" },
                  ]}
                />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="impairmentAllowed" label="资产减值准备计提" initialValue={false} rules={[{ required: true }]}>
                <Select options={[{ label: "允许计提 (CAS专属)", value: true }, { label: "不计提 (SMALL法定)", value: false }]} />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="badDebtProvisionAllowed" label="坏账准备计提" initialValue={false} rules={[{ required: true }]}>
                <Select options={[{ label: "允许计提", value: true }, { label: "直接转销", value: false }]} />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="effectiveFrom" label="生效起始日期" rules={[{ required: true }]}>
                <Input type="date" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="effectiveTo" label="生效终止日期 (可选)">
                <Input type="date" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="makerPersonId" label="起草经办人天然人" rules={[{ required: true }]}>
                <Input placeholder="Maker 天然人 UUID" />
              </Form.Item>
            </Col>
          </Row>
          <Button type="primary" htmlType="submit" disabled={busy || !canMake}>
            创建会计政策版本草案
          </Button>
        </Form>

        <Divider />

        <Form
          form={approveForm}
          layout="vertical"
          onFinish={async (values) => {
            await onExecute("approvePolicyVersion", {
              bookId: values.bookId,
              versionId: values.versionId,
              data: values,
            });
          }}
        >
          <Row gutter={16}>
            <Col xs={24} sm={6}>
              <Form.Item name="bookId" label="账套标识" rules={[{ required: true }]}>
                <Input placeholder="账套 UUID" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={6}>
              <Form.Item name="versionId" label="政策版本标识 (ID)" rules={[{ required: true }]}>
                <Input placeholder="待审政策版本 UUID" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={6}>
              <Form.Item name="reviewerPersonId" label="复核人天然人 (必须不同于经办人)" rules={[{ required: true }]}>
                <Input placeholder="Reviewer 天然人 UUID" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={6}>
              <Form.Item name="approvalDigest" label="批准签署摘要 (SHA-256)" rules={[{ required: true }]}>
                <Input placeholder="SHA-256 政策签章摘要" />
              </Form.Item>
            </Col>
          </Row>
          <Button type="primary" htmlType="submit" disabled={busy || !canReview}>
            签署批准会计政策版本 (独立复核)
          </Button>
        </Form>
      </Card>
    </Space>
  );
}

function Org03Section({
  api,
  canMake,
  canReview,
  busy,
  onExecute,
}: {
  api: OrgFxApi;
  canMake: boolean;
  canReview: boolean;
  busy: boolean;
  onExecute: (action: string, body: any) => Promise<any>;
}) {
  const [validationResult, setValidationResult] = useState<any>(null);
  const [validateForm] = Form.useForm();
  const [reopenForm] = Form.useForm();

  function onValidate(values: any) {
    const res = validateMultiDate(
      values.businessDate,
      values.invoiceDate,
      values.accountingDate,
      values.taxObligationDate,
      values.periodStatus,
      values.justification
    );
    setValidationResult(res);
  }

  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Card title="四日期语义校验 (业务日、开票日、入账日、税点日)">
        <Form form={validateForm} layout="vertical" onFinish={onValidate}>
          <Row gutter={16}>
            <Col xs={24} sm={12} md={6}>
              <Form.Item name="businessDate" label="业务事实发生日" rules={[{ required: true }]}>
                <Input type="date" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12} md={6}>
              <Form.Item name="invoiceDate" label="发票开具日" rules={[{ required: true }]}>
                <Input type="date" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12} md={6}>
              <Form.Item name="accountingDate" label="会计记账日" rules={[{ required: true }]}>
                <Input type="date" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12} md={6}>
              <Form.Item name="taxObligationDate" label="纳税义务发生时间" rules={[{ required: true }]}>
                <Input type="date" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item name="periodStatus" label="目标账期状态" rules={[{ required: true }]} initialValue="OPEN">
                <Select options={[{ label: "OPEN (开放)", value: "OPEN" }, { label: "CLOSED (已关闭)", value: "CLOSED" }]} />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item name="justification" label="跨期/超90天事实说明">
                <Input placeholder="超90天时必填" />
              </Form.Item>
            </Col>
          </Row>
          <Button type="primary" htmlType="submit">
            校验日期语义合规性
          </Button>
        </Form>
        {validationResult && (
          <div style={{ marginTop: "16px" }}>
            <Descriptions bordered size="small">
              <Descriptions.Item label="合规状态">
                <Tag color={validationResult.isValid ? "success" : "error"}>
                  {validationResult.isValid ? "通过" : "拒绝"}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="业务与入账间隔天数">
                {validationResult.dateSpanDays} 天
              </Descriptions.Item>
              <Descriptions.Item label="错误详情">
                {validationResult.errors.length > 0 ? validationResult.errors.join("; ") : "无错误"}
              </Descriptions.Item>
            </Descriptions>
          </div>
        )}
      </Card>

      <Card title="已结账期重开申请与审批 (天然人 SoD & 不可变审计轨迹)">
        <Form
          form={reopenForm}
          layout="vertical"
          onFinish={async (values) => {
            await onExecute("handlePeriodReopen", {
              bookId: values.bookId,
              periodId: values.periodId,
              data: values,
            });
          }}
        >
          <Row gutter={16}>
            <Col xs={24} sm={6}>
              <Form.Item name="bookId" label="账套标识" rules={[{ required: true }]}>
                <Input placeholder="账套 UUID" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={6}>
              <Form.Item name="periodId" label="会计期间标识 (periodId)" rules={[{ required: true }]}>
                <Input placeholder="目标已锁期间 UUID" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={6}>
              <Form.Item name="action" label="操作类型" initialValue="REQUEST" rules={[{ required: true }]}>
                <Select
                  options={[
                    { label: "提交重开申请 (REQUEST)", value: "REQUEST" },
                    { label: "独立复核批准重开 (APPROVE)", value: "APPROVE" },
                    { label: "驳回重开申请 (REJECT)", value: "REJECT" },
                    { label: "工作完毕重新关期 (RECLOSE)", value: "RECLOSE" },
                  ]}
                />
              </Form.Item>
            </Col>
            <Col xs={24} sm={6}>
              <Form.Item name="actorPersonId" label="操作人天然人标识" rules={[{ required: true }]}>
                <Input placeholder="操作人天然人 UUID" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item name="reason" label="重开申请/重新关期理由" rules={[{ required: true, max: 1000 }]}>
                <Input placeholder="说明重开或重新关期业务原因" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item name="decisionReason" label="审批决策理由 (审批/驳回时必填)">
                <Input placeholder="审核意见及审计依据" />
              </Form.Item>
            </Col>
          </Row>
          <Button type="primary" htmlType="submit" disabled={busy}>
            提交期间重开/审批流转
          </Button>
        </Form>
      </Card>
    </Space>
  );
}

function Org04Section({
  api,
  canMake,
  canReview,
  busy,
  onExecute,
}: {
  api: OrgFxApi;
  canMake: boolean;
  canReview: boolean;
  busy: boolean;
  onExecute: (action: string, body: any) => Promise<any>;
}) {
  const [conversionResult, setConversionResult] = useState<any>(null);
  const [revaluationResult, setRevaluationResult] = useState<any>(null);
  const [translateForm] = Form.useForm();
  const [rateForm] = Form.useForm();
  const [revalForm] = Form.useForm();

  function onTranslate(values: any) {
    const res = translateFx(values.amount, values.rate);
    setConversionResult(res);
  }

  function onRevalue(values: any) {
    const res = calculateRevaluation(values.foreignBalance, values.carryingCny, values.closingRate);
    setRevaluationResult(res);
  }

  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Card title="分录外币折算 (28,12 汇率与 28,8 功能币精确性)">
        <Form form={translateForm} layout="vertical" onFinish={onTranslate}>
          <Row gutter={16}>
            <Col xs={24} sm={8}>
              <Form.Item name="amount" label="原币金额" rules={[{ required: true }]}>
                <Input placeholder="1000.55" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="rate" label="折算汇率 (28,12 精度)" rules={[{ required: true }]}>
                <Input placeholder="7.123456789012" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8} style={{ display: "flex", alignItems: "flex-end" }}>
              <Button type="primary" htmlType="submit">
                测算折算金额
              </Button>
            </Col>
          </Row>
        </Form>
        {conversionResult && (
          <Descriptions bordered size="small" style={{ marginTop: "16px" }}>
            <Descriptions.Item label="内部精确折算 (28,8)">
              {conversionResult.exact} CNY
            </Descriptions.Item>
            <Descriptions.Item label="记账提交金额 (严格2位)">
              {conversionResult.quantized} CNY
            </Descriptions.Item>
            <Descriptions.Item label="舍入差额调节项">
              {conversionResult.difference} CNY
            </Descriptions.Item>
          </Descriptions>
        )}
      </Card>

      <Card title="外币汇率快照登记 (numeric(28,12))">
        <Form
          form={rateForm}
          layout="vertical"
          onFinish={async (values) => {
            await onExecute("registerFxRate", { data: values });
            rateForm.resetFields(["rateValue"]);
          }}
        >
          <Row gutter={16}>
            <Col xs={24} sm={8}>
              <Form.Item name="sourceCurrency" label="源币种代码" initialValue="USD" rules={[{ required: true, pattern: /^[A-Z]{3}$/ }]}>
                <Select
                  options={[
                    { label: "美元 (USD)", value: "USD" },
                    { label: "欧元 (EUR)", value: "EUR" },
                    { label: "港币 (HKD)", value: "HKD" },
                    { label: "日元 (JPY)", value: "JPY" },
                    { label: "英镑 (GBP)", value: "GBP" },
                  ]}
                />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="rateDate" label="汇率基准日期" rules={[{ required: true }]}>
                <Input type="date" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="rateType" label="汇率类型" initialValue="CENTRAL_PARITY" rules={[{ required: true }]}>
                <Select
                  options={[
                    { label: "中国人民银行中间价 (CENTRAL_PARITY)", value: "CENTRAL_PARITY" },
                    { label: "银行现汇买入价 (SPOT_BUY)", value: "SPOT_BUY" },
                    { label: "银行现汇卖出价 (SPOT_SELL)", value: "SPOT_SELL" },
                    { label: "自定义批准汇率 (CUSTOM_APPROVED)", value: "CUSTOM_APPROVED" },
                  ]}
                />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="rateValue" label="汇率数值 (精确至12位小数)" rules={[{ required: true }]}>
                <Input placeholder="7.123456789012" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="quoteSource" label="报价来源机构" initialValue="PBOC_OFFICIAL" rules={[{ required: true }]}>
                <Input placeholder="PBOC_OFFICIAL / BANK_OF_CHINA" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="makerPersonId" label="登记经办人天然人" rules={[{ required: true }]}>
                <Input placeholder="Maker 天然人 UUID" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="reviewerPersonId" label="复核人天然人 (自定义汇率时必填)">
                <Input placeholder="Reviewer 天然人 UUID" />
              </Form.Item>
            </Col>
          </Row>
          <Button type="primary" htmlType="submit" disabled={busy || !canMake}>
            登记汇率快照
          </Button>
        </Form>
      </Card>

      <Card title="月末外币货币性项目重估底稿">
        <Form form={revalForm} layout="vertical" onFinish={onRevalue}>
          <Row gutter={16}>
            <Col xs={24} sm={8}>
              <Form.Item name="foreignBalance" label="外币期末余额" rules={[{ required: true }]}>
                <Input placeholder="10000.00" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="carryingCny" label="账面人民币余额" rules={[{ required: true }]}>
                <Input placeholder="71000.00" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="closingRate" label="月末期末汇率 (28,12)" rules={[{ required: true }]}>
                <Input placeholder="7.250000000000" />
              </Form.Item>
            </Col>
          </Row>
          <Button type="primary" htmlType="submit">
            计算月末汇兑损益
          </Button>
        </Form>
        {revaluationResult && (
          <Descriptions bordered size="small" style={{ marginTop: "16px" }}>
            <Descriptions.Item label="调整后人民币余额">
              {revaluationResult.restatedCny} CNY
            </Descriptions.Item>
            <Descriptions.Item label="计入财务费用-汇兑损益">
              <Typography.Text type={revaluationResult.gainLoss.startsWith("-") ? "danger" : "success"}>
                {revaluationResult.gainLoss} CNY
              </Typography.Text>
            </Descriptions.Item>
          </Descriptions>
        )}
      </Card>
    </Space>
  );
}

function Org05Section({ api }: { api: OrgFxApi }) {
  const [obligations, setObligations] = useState<any[]>([]);
  const [form] = Form.useForm();

  function onResolve(values: any) {
    api.resolveTaxObligations(values).then((res) => {
      setObligations(res.obligations || []);
    });
  }

  return (
    <Card title="纳税主体义务清单推导 (小规模与小型微利解耦)">
      <Form form={form} layout="vertical" onFinish={onResolve}>
        <Row gutter={16}>
          <Col xs={24} sm={6}>
            <Form.Item name="regionCode" label="区域代码" initialValue="CN_NATIONAL" rules={[{ required: true }]}>
              <Input placeholder="CN_NATIONAL / CN_SHANGHAI" />
            </Form.Item>
          </Col>
          <Col xs={24} sm={4}>
            <Form.Item name="taxYear" label="公历税款所属年" initialValue={2026} rules={[{ required: true }]}>
              <InputNumber min={1900} max={2100} style={{ width: "100%" }} />
            </Form.Item>
          </Col>
          <Col xs={24} sm={4}>
            <Form.Item name="periodMonth" label="所属期间月份 (1-12)" initialValue={3} rules={[{ required: true }]}>
              <InputNumber min={1} max={12} style={{ width: "100%" }} />
            </Form.Item>
          </Col>
          <Col xs={24} sm={5}>
            <Form.Item name="taxpayerType" label="增值税纳税人身份" initialValue="SMALL_SCALE" rules={[{ required: true }]}>
              <Select options={[{ label: "一般纳税人", value: "GENERAL_TAXPAYER" }, { label: "小规模纳税人", value: "SMALL_SCALE" }]} />
            </Form.Item>
          </Col>
          <Col xs={24} sm={5}>
            <Form.Item name="citMode" label="所得税征收方式" initialValue="AUDIT_COLLECTION" rules={[{ required: true }]}>
              <Select options={[{ label: "查账征收", value: "AUDIT_COLLECTION" }, { label: "核定征收", value: "VERIFIED_COLLECTION" }]} />
            </Form.Item>
          </Col>
          <Col xs={24} sm={6}>
            <Form.Item name="isSmallMicroEnterprise" label="小型微利所得税资格核定" initialValue={false}>
              <Select options={[{ label: "未取得资格 / 不适用", value: false }, { label: "已独立取得资格", value: true }]} />
            </Form.Item>
          </Col>
          <Col xs={24} sm={6} style={{ display: "flex", alignItems: "flex-end" }}>
            <Button type="primary" htmlType="submit">
              推导本期申报义务
            </Button>
          </Col>
        </Row>
      </Form>
      {obligations.length > 0 && (
        <Table
          rowKey="obligationCode"
          dataSource={obligations}
          pagination={false}
          scroll={{ x: 758 }}
          columns={[
            { title: "义务代码", dataIndex: "obligationCode" },
            { title: "税种名称", dataIndex: "taxTypeName" },
            { title: "申报截止日", dataIndex: "deadline" },
            { title: "征期顺延", render: (_, r) => (r.isExtended ? <Tag color="warning">节假日顺延</Tag> : "法定截止日") },
            { title: "小型微利优惠", render: (_, r) => (r.smallMicroCitApplicable ? <Tag color="green">适用优惠税率</Tag> : <Tag>标准税率</Tag>) },
          ]}
        />
      )}
    </Card>
  );
}

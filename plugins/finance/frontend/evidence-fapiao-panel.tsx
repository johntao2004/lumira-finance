import React, { useState } from "react";
import { Alert, Badge, Button, Card, Col, Divider, Form, Input, Modal, Row, Select, Space, Table, Tabs, Tag, Typography } from "antd";
import {
  EvidenceClassification,
  EvidenceCompletenessState,
  ExtendedQuotaBucket,
  GraphNodeType,
  GraphEdgeType,
  EvidenceExceptionType,
  EvidenceExceptionStatus,
  FapiaoDirection,
  FapiaoLifecycleStatus,
  FapiaoType,
  BuyerQualificationStatus,
  IssuanceAppStatus,
  RedNoticeStatus,
  evaluateCompleteness,
  verifyInvoiceMath,
  compareOcrInvoice,
  calculateAvailableQuota,
  checkExceptionSod,
  isValidFapiaoNumber,
  isValidTaxNumber,
  isRedNoticeExpired,
  validateRedNoticeLimits,
  formatMoney,
} from "./evidence-fapiao-rules";

export interface EvidenceFapiaoPanelProps {
  tenantId: string;
  legalEntityId: string;
  canCreate?: boolean;
  canReview?: boolean;
}

export function EvidenceFapiaoPanel(props: EvidenceFapiaoPanelProps) {
  const [activeTab, setActiveTab] = useState("ev01");

  return (
    <div style={{ padding: 16, maxWidth: "100%", minWidth: 0, boxSizing: "border-box", overflowX: "hidden" }}>
      <Typography.Title level={4} style={{ wordBreak: "break-word" }}>发票与原始凭证中心 (EV01-EV04 / FP01-FP03 实施工作台)</Typography.Title>
      <Alert
        type="info"
        showIcon
        message="V4 进销项发票池、电子原件完整性、OCR交叉核验、证据链图谱与红字确认单候选工作台"
        description="系统执行确定性结构化解析、高精度十进制金额校验与OCR字段对照。外部税局联网渠道 (taxBureauVerification / externalTaxChannel) 保持严格 NOT_VERIFIED，不伪造政府联网验真或在线开票结论。"
        style={{ marginBottom: 16 }}
      />

      <Tabs activeKey={activeTab} onChange={setActiveTab} items={[
        { key: "ev01", label: "EV01: 电子原件与完整性", children: <EvidenceCompletenessTab {...props} /> },
        { key: "ev02", label: "EV02: 结构化解析与OCR对照", children: <StructuredVerificationTab {...props} /> },
        { key: "ev03", label: "EV03: 扩展额度桶", children: <ExtendedQuotaTab {...props} /> },
        { key: "ev04", label: "EV04: 证据图谱与缺件异常", children: <EvidenceGraphTab {...props} /> },
        { key: "fp01", label: "FP01: 进销项发票池", children: <FapiaoPoolTab {...props} /> },
        { key: "fp02", label: "FP02: 销项开票与人工确认", children: <IssuanceAppTab {...props} /> },
        { key: "fp03", label: "FP03: 红字确认单与税会联动", children: <RedNoticeTab {...props} /> },
      ]} />
    </div>
  );
}

// -----------------------------------------------------------------------------
// EV01 Tab
// -----------------------------------------------------------------------------
function EvidenceCompletenessTab(props: EvidenceFapiaoPanelProps) {
  const [classification, setClassification] = useState<EvidenceClassification>(EvidenceClassification.EINVOICE_XML);
  const [isScreenshot, setIsScreenshot] = useState(false);
  const [paperLocation, setPaperLocation] = useState("");
  const evalResult = evaluateCompleteness(classification, isScreenshot, paperLocation);

  return (
    <Card title="EV01: 电子原件分类与完整性状态机">
      <Form layout="vertical">
        <Row gutter={16}>
          <Col xs={24} sm={12} md={8}>
            <Form.Item label="原件格式分类">
              <Select
                value={classification}
                onChange={setClassification}
                options={Object.keys(EvidenceClassification).map(k => ({ value: k, label: k }))}
              />
            </Form.Item>
          </Col>
          <Col xs={24} sm={12} md={8}>
            <Form.Item label="是否仅为手机/屏幕截图">
              <Select
                value={isScreenshot ? "yes" : "no"}
                onChange={v => setIsScreenshot(v === "yes")}
                options={[{ value: "no", label: "否 (标准原件)" }, { value: "yes", label: "是 (仅截图，缺电子原件)" }]}
              />
            </Form.Item>
          </Col>
          <Col xs={24} sm={12} md={8}>
            <Form.Item label="纸质原件物理存放位置 (扫描件必填)">
              <Input
                placeholder="例如: 档案室03号密集架-2026凭证盒"
                value={paperLocation}
                onChange={e => setPaperLocation(e.target.value)}
              />
            </Form.Item>
          </Col>
        </Row>
      </Form>

      <Divider />
      <Typography.Title level={5}>完整性评估结果</Typography.Title>
      <Space orientation="vertical">
        <div>
          状态机结果: <Tag color={evalResult.isComplete ? "green" : "orange"}>{evalResult.state}</Tag>
          完整性判定: <Badge status={evalResult.isComplete ? "success" : "warning"} text={evalResult.isComplete ? "资料完整 (可使用)" : "资料不完整 (待补件)"} />
        </div>
        {evalResult.missingReasons.length > 0 && (
          <Alert
            type="warning"
            message="缺失资料项说明"
            description={evalResult.missingReasons.map((r, i) => <div key={i}>• {r}</div>)}
          />
        )}
      </Space>
    </Card>
  );
}

// -----------------------------------------------------------------------------
// EV02 Tab
// -----------------------------------------------------------------------------
function StructuredVerificationTab(props: EvidenceFapiaoPanelProps) {
  const [untaxed, setUntaxed] = useState("1000.00");
  const [tax, setTax] = useState("130.00");
  const [total, setTotal] = useState("1130.00");
  const isMathValid = verifyInvoiceMath(untaxed, tax, total);

  const [ocrTax, setOcrTax] = useState("130.00");
  const ocrCmp = compareOcrInvoice({
    structured: {
      fapiaoNumber: "24110000000000000001",
      buyerTaxNo: "91110000MA00112233",
      sellerTaxNo: "91310000MA99887766",
      untaxedAmount: untaxed,
      taxAmount: tax,
      totalAmount: total,
    },
    ocr: {
      fapiaoNumber: "24110000000000000001",
      buyerTaxNo: "91110000MA00112233",
      sellerTaxNo: "91310000MA99887766",
      untaxedAmount: untaxed,
      taxAmount: ocrTax,
      totalAmount: total,
    },
  });

  return (
    <Card title="EV02: 确定性XML解析与OCR交叉比对队列">
      <Row gutter={16}>
        <Col xs={24} md={12}>
          <Typography.Title level={5}>高精度十进制金额校验 (untaxed + tax === total)</Typography.Title>
          <Form layout="vertical">
            <Form.Item label="不含税金额 (untaxedAmount)"><Input value={untaxed} onChange={e => setUntaxed(e.target.value)} /></Form.Item>
            <Form.Item label="税额 (taxAmount)"><Input value={tax} onChange={e => setTax(e.target.value)} /></Form.Item>
            <Form.Item label="价税合计 (totalAmount)"><Input value={total} onChange={e => setTotal(e.target.value)} /></Form.Item>
          </Form>
          <div>
            校验结果: <Tag color={isMathValid ? "green" : "red"}>{isMathValid ? "精确平衡" : "金额不平衡"}</Tag>
          </div>
        </Col>

        <Col xs={24} md={12}>
          <Typography.Title level={5}>OCR交叉对照与验真结论</Typography.Title>
          <Form layout="vertical">
            <Form.Item label="OCR识别税额 (修改可模拟差异)"><Input value={ocrTax} onChange={e => setOcrTax(e.target.value)} /></Form.Item>
          </Form>
          <Space orientation="vertical" style={{ width: "100%" }}>
            <div>比对状态: <Tag color={ocrCmp.status === "VERIFIED_MATCH" ? "green" : "volcano"}>{ocrCmp.status}</Tag></div>
            <div>
              税局联网验真状态: <Tag color="blue">{ocrCmp.taxBureauVerification}</Tag>
              <Typography.Text type="secondary">(仅本地结构化与OCR比对，绝不伪造政府联网验证)</Typography.Text>
            </div>
            {Object.keys(ocrCmp.discrepancies).length > 0 && (
              <Alert
                type="error"
                message="检测到字段差异，已进入人工复核队列"
                description={Object.entries(ocrCmp.discrepancies).map(([k, v]) => (
                  <div key={k}>{k}: 结构化值[{v.structured}] vs OCR值[{v.ocr}] ({v.message})</div>
                ))}
              />
            )}
          </Space>
        </Col>
      </Row>
    </Card>
  );
}

// -----------------------------------------------------------------------------
// EV03 Tab
// -----------------------------------------------------------------------------
function ExtendedQuotaTab(props: EvidenceFapiaoPanelProps) {
  const buckets = [
    { bucket: ExtendedQuotaBucket.SETTLEMENT_QUOTA, name: "应付应收结算配额", capacity: "1000.00", reserved: "300.00", committed: "200.00" },
    { bucket: ExtendedQuotaBucket.TAX_DEDUCTION_QUOTA, name: "增值税进项抵扣配额", capacity: "130.00", reserved: "0.00", committed: "130.00" },
    { bucket: ExtendedQuotaBucket.REIMBURSEMENT_QUOTA, name: "员工报销认领配额", capacity: "1000.00", reserved: "0.00", committed: "0.00" },
  ];

  return (
    <Card title="EV03: 独立扩展额度桶 (SETTLEMENT_QUOTA, TAX_DEDUCTION_QUOTA, REIMBURSEMENT_QUOTA)">
      <Alert
        type="info"
        message="额度多环节正交隔离原则"
        description="同一张发票在结算、税务抵扣与报销认领环节分别核算额度，各环节余额完全独立，禁止跨环节相加得出虚假占用合计。"
        style={{ marginBottom: 16 }}
      />
      <div style={{ width: "100%", maxWidth: "100%", overflowX: "auto" }}>
        <Table
          dataSource={buckets}
          rowKey="bucket"
          pagination={false}
          scroll={{ x: "max-content" }}
          columns={[
            { title: "额度桶类别", dataIndex: "bucket", render: (b: string, r) => <b>{b} ({r.name})</b> },
            { title: "总额度 (capacity)", dataIndex: "capacity", render: (v: string) => formatMoney(v) },
            { title: "预占中 (reserved)", dataIndex: "reserved", render: (v: string) => formatMoney(v) },
            { title: "已生效 (committed)", dataIndex: "committed", render: (v: string) => formatMoney(v) },
            {
              title: "可用余额 (available)",
              render: (_, r) => <Tag color="blue">{calculateAvailableQuota(r.capacity, r.reserved, r.committed)}</Tag>,
            },
          ]}
        />
      </div>
    </Card>
  );
}

// -----------------------------------------------------------------------------
// EV04 Tab
// -----------------------------------------------------------------------------
function EvidenceGraphTab(props: EvidenceFapiaoPanelProps) {
  const sampleNodes = [
    { type: GraphNodeType.CONTRACT, label: "采购框架合同 (CNT-2026-001)" },
    { type: GraphNodeType.PURCHASE_ORDER, label: "采购订单 (PO-2026-001)" },
    { type: GraphNodeType.ACCEPTANCE_NOTE, label: "验收单 (ACC-2026-001)" },
    { type: GraphNodeType.INVOICE, label: "增值税数电票 (24110000000000000001)" },
    { type: GraphNodeType.BANK_SLIP, label: "银行付款电子回单 (SLIP-001)" },
  ];

  const sampleExceptions = [
    {
      id: "EX-001",
      type: EvidenceExceptionType.MISSING_INVOICE_PROVISIONAL_ESTIMATE,
      desc: "货物已完成入库验收但发票尚未到达，执行暂估入账",
      amount: "50000.00",
      responsible: "USER_CLERK_01 (张经办)",
      status: EvidenceExceptionStatus.APPROVED_EXCEPTION,
    },
  ];

  return (
    <Card title="EV04: 业务证据网络图谱与缺件异常处理">
      <Typography.Title level={5}>五单证据图谱 (合同 ➔ 订单 ➔ 验收 ➔ 发票 ➔ 水单)</Typography.Title>
      <Space wrap style={{ marginBottom: 16 }}>
        {sampleNodes.map((n, i) => (
          <React.Fragment key={n.type}>
            <Tag color="cyan">{n.type}: {n.label}</Tag>
            {i < sampleNodes.length - 1 && <span>➔</span>}
          </React.Fragment>
        ))}
      </Space>

      <Divider />
      <Typography.Title level={5}>缺件异常与例外审批 (职责分离控制: 审批人 != 经办责任人)</Typography.Title>
      <div style={{ width: "100%", maxWidth: "100%", overflowX: "auto" }}>
        <Table
          dataSource={sampleExceptions}
          rowKey="id"
          pagination={false}
          scroll={{ x: "max-content" }}
          columns={[
            { title: "异常编号", dataIndex: "id" },
            { title: "异常类型", dataIndex: "type" },
            { title: "说明", dataIndex: "desc" },
            { title: "暂估金额", dataIndex: "amount", render: (v: string) => formatMoney(v) },
            { title: "经办责任人", dataIndex: "responsible" },
            { title: "状态", dataIndex: "status", render: (s: string) => <Tag color="green">{s}</Tag> },
          ]}
        />
      </div>
    </Card>
  );
}

// -----------------------------------------------------------------------------
// FP01 Tab
// -----------------------------------------------------------------------------
function FapiaoPoolTab(props: EvidenceFapiaoPanelProps) {
  const sampleFapiao = [
    {
      id: "FP-001",
      direction: FapiaoDirection.INPUT,
      fapiaoNumber: "24110000000000000001",
      fapiaoType: FapiaoType.ELECTRONIC_SPECIAL,
      untaxed: "10000.00",
      tax: "1300.00",
      total: "11300.00",
      status: FapiaoLifecycleStatus.CERTIFIED_DECLARED,
      taxBureau: "NOT_VERIFIED",
    },
    {
      id: "FP-002",
      direction: FapiaoDirection.OUTPUT,
      fapiaoNumber: "24110000000000000002",
      fapiaoType: FapiaoType.ELECTRONIC_GENERAL,
      untaxed: "5000.00",
      tax: "300.00",
      total: "5300.00",
      status: FapiaoLifecycleStatus.MATCHED,
      taxBureau: "NOT_VERIFIED",
    },
  ];

  return (
    <Card title="FP01: 进销项发票池 (防重复采集与生命周期流转)">
      <div style={{ width: "100%", maxWidth: "100%", overflowX: "auto" }}>
        <Table
          dataSource={sampleFapiao}
          rowKey="id"
          pagination={false}
          scroll={{ x: "max-content" }}
          columns={[
            { title: "流向", dataIndex: "direction", render: (d: string) => <Tag color={d === "INPUT" ? "blue" : "purple"}>{d}</Tag> },
            { title: "数电发票号码 (20位)", dataIndex: "fapiaoNumber", render: (n: string) => <code>{n}</code> },
            { title: "类型", dataIndex: "fapiaoType" },
            { title: "不含税金额", dataIndex: "untaxed", render: (v: string) => formatMoney(v) },
            { title: "税额", dataIndex: "tax", render: (v: string) => formatMoney(v) },
            { title: "价税合计", dataIndex: "total", render: (v: string) => formatMoney(v) },
            { title: "税局验证状态", dataIndex: "taxBureau", render: (s: string) => <Tag color="default">{s}</Tag> },
            { title: "生命周期状态", dataIndex: "status", render: (s: string) => <Tag color="green">{s}</Tag> },
          ]}
        />
      </div>
    </Card>
  );
}

// -----------------------------------------------------------------------------
// FP02 Tab
// -----------------------------------------------------------------------------
function IssuanceAppTab(props: EvidenceFapiaoPanelProps) {
  const sampleApps = [
    {
      appNo: "APP-2026-0001",
      customer: "北京飞轮数智科技有限公司",
      buyerTaxNo: "91110000MA00112233",
      amount: "22600.00",
      qualification: BuyerQualificationStatus.QUALIFIED,
      status: IssuanceAppStatus.ISSUED_MANUAL_CONFIRMED,
      externalChannel: "NOT_VERIFIED",
      issuedNumber: "24110000000000009999",
    },
  ];

  return (
    <Card title="FP02: 销项开票申请与人工办理确认">
      <Alert
        type="warning"
        message="外部开票通道资质声明"
        description="由于本环境尚未直连国家税务局增值税发票综合服务平台，外部渠道恒标为 NOT_VERIFIED。系统支持开票资料生成、购买方纳税资质审核与离线人工开票结果登记回填。"
        style={{ marginBottom: 16 }}
      />
      <div style={{ width: "100%", maxWidth: "100%", overflowX: "auto" }}>
        <Table
          dataSource={sampleApps}
          rowKey="appNo"
          pagination={false}
          scroll={{ x: "max-content" }}
          columns={[
            { title: "申请单号", dataIndex: "appNo" },
            { title: "购买方名称", dataIndex: "customer" },
            { title: "购买方税号", dataIndex: "buyerTaxNo" },
            { title: "含税总额", dataIndex: "amount", render: (v: string) => formatMoney(v) },
            { title: "购买方资质复核", dataIndex: "qualification", render: (q: string) => <Tag color="green">{q}</Tag> },
            { title: "外部渠道", dataIndex: "externalChannel", render: (c: string) => <Tag>{c}</Tag> },
            { title: "申请状态", dataIndex: "status", render: (s: string) => <Tag color="blue">{s}</Tag> },
            { title: "已开具发票号码", dataIndex: "issuedNumber", render: (n: string) => <code>{n}</code> },
          ]}
        />
      </div>
    </Card>
  );
}

// -----------------------------------------------------------------------------
// FP03 Tab
// -----------------------------------------------------------------------------
function RedNoticeTab(props: EvidenceFapiaoPanelProps) {
  const sampleRedNotices = [
    {
      noticeNo: "RED-NOTICE-20261110-001",
      blueFapiaoNo: "24110000000000007777",
      reason: "销货退回",
      redUntaxed: "-10000.00",
      redTax: "-1300.00",
      redTotal: "-11300.00",
      status: RedNoticeStatus.RED_INVOICE_ISSUED,
      registeredAt: "2026-11-10T10:00:00Z",
      revReversal: "-10000.00",
      vatAdjustment: "-1300.00",
    },
  ];

  return (
    <Card title="FP03: 红字信息确认单 (72小时失效监控与税会分别调整)">
      <Alert
        type="info"
        message="红字发票冲销与税会分离原则"
        description="开具红字发票严格以72小时内确认的红字信息确认单为前提；红字发票开具后，主营业务收入冲减与增值税销项/进项转出分别记入不同科目凭证，不直接自动触发银行退款。"
        style={{ marginBottom: 16 }}
      />
      <div style={{ width: "100%", maxWidth: "100%", overflowX: "auto" }}>
        <Table
          dataSource={sampleRedNotices}
          rowKey="noticeNo"
          pagination={false}
          scroll={{ x: "max-content" }}
          columns={[
            { title: "红字确认单号", dataIndex: "noticeNo" },
            { title: "原蓝票号码", dataIndex: "blueFapiaoNo", render: (n: string) => <code>{n}</code> },
            { title: "红冲原因", dataIndex: "reason" },
            { title: "红冲含税总额", dataIndex: "redTotal", render: (v: string) => <span style={{ color: "red" }}>{formatMoney(v)}</span> },
            { title: "收入冲减额", dataIndex: "revReversal", render: (v: string) => formatMoney(v) },
            { title: "税额调整额", dataIndex: "vatAdjustment", render: (v: string) => formatMoney(v) },
            { title: "确认单状态", dataIndex: "status", render: (s: string) => <Tag color="green">{s}</Tag> },
          ]}
        />
      </div>
    </Card>
  );
}

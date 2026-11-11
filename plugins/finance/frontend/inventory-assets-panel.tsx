import { useState, useEffect, type ReactNode } from "react";
import { Alert, Button, Card, Form, Input, Modal, Select, Space, Table, Tabs, Tag, Typography } from "antd";
import {
  type AccountingRegime,
  type DepreciationMethod,
  type InventoryValuationMethod,
  type StockMovementType,
  evaluateInventoryImpairment,
  evaluateAssetImpairment,
  validateTransferMovement,
  validateCipCapitalization,
  validateRdCapitalization,
  validateMoneyCny,
} from "./inventory-assets-rules";

export interface InventoryAssetsPanelProps {
  scopeKey: string;
  canMake: boolean;
  canReview: boolean;
  canPost: boolean;
}

export function InventoryAssetsPanel({
  scopeKey,
  canMake,
  canReview,
  canPost,
}: InventoryAssetsPanelProps) {
  const [activeTab, setActiveTab] = useState("inv01");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const storageKey = `lumira_finance_inventory_assets_pending_${scopeKey || "default"}`;

  useEffect(() => {
    try {
      const saved = sessionStorage.getItem(storageKey);
      if (saved) {
        const parsed = JSON.parse(saved);
        setError(`检测到未提交的操作草稿 [${parsed.type || "MOVEMENT"}]，可点击恢复`);
      }
    } catch {
      // ignore storage access errors in sandboxed contexts
    }
  }, [storageKey]);

  // INV01 Movements State
  const [movements] = useState([
    {
      id: "MOV-1001",
      movementNo: "IN-202602-001",
      movementType: "PURCHASE_IN" as StockMovementType,
      itemCode: "ITEM-CHIP-A",
      quantity: "100.00000000",
      unitCost: "25.00000000",
      totalCost: "2500.00000000",
      status: "POSTED",
      sourceEvidenceId: "EV-CONFIRM-9981",
    },
    {
      id: "MOV-1002",
      movementNo: "TR-202602-002",
      movementType: "TRANSFER" as StockMovementType,
      itemCode: "ITEM-CHIP-A",
      quantity: "30.00000000",
      unitCost: "25.00000000",
      totalCost: "750.00000000",
      status: "POSTED",
      sourceEvidenceId: null,
    },
  ]);

  // INV03 Stocktake State
  const [stocktakes] = useState([
    {
      id: "ST-202602-1",
      sheetNo: "ST-WH-MAIN-01",
      cutoffDate: "2026-02-28",
      status: "COUNTED",
      lines: [
        {
          id: "STL-1",
          itemCode: "ITEM-CHIP-A",
          bookQty: "70.00000000",
          countedQty: "68.00000000",
          varianceQty: "-2.00000000",
          varianceType: "DEFICIT",
          disposition: "MANAGEMENT_EXPENSE",
        },
      ],
    },
  ]);

  // Form modal state
  const [modalType, setModalType] = useState<string | null>(null);
  const [form] = Form.useForm();

  const handleCreateMovement = (values: Record<string, string>) => {
    try {
      sessionStorage.setItem(storageKey, JSON.stringify({ type: "MOVEMENT", values }));
    } catch {
      // ignore
    }
    if (values.movementType === "TRANSFER") {
      const check = validateTransferMovement(
        values.sourceWarehouse,
        values.destWarehouse,
        values.quantity,
        "100.00000000"
      );
      if (!check.valid) {
        setError(check.error || "调拨参数错误");
        return;
      }
    }
    try {
      sessionStorage.removeItem(storageKey);
    } catch {
      // ignore
    }
    setError("");
    setModalType(null);
  };

  return (
    <Space direction="vertical" style={{ width: "100%" }}>
      <Alert
        type="info"
        title="存货与长期资产扩展工作台（W08：INV01-03, AS01-03）"
        description="本工作台受严格制度隔离约束：CAS 1 存货跌价准备与可转回 vs SMALL 实际损失直接计入；CAS 8 资产减值准备一经计提严禁转回（SMALL阻断）；跨仓调拨严格守恒；在建工程转固与研发资本化需独立凭据绑定与独立人员审签。"
      />
      {error && <Alert type="error" title={error} closable onClose={() => setError("")} />}

      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          {
            key: "inv01",
            label: "存货收发与批次 (INV01)",
            children: (
              <Card
                title="出入库单据与调拨流转"
                extra={
                  <Button
                    type="primary"
                    disabled={!canMake || busy}
                    onClick={() => {
                      setModalType("movement");
                      form.resetFields();
                    }}
                  >
                    新建出入库单
                  </Button>
                }
              >
                <Table
                  dataSource={movements}
                  rowKey="id"
                  pagination={false}
                  columns={[
                    { title: "单据编号", dataIndex: "movementNo" },
                    {
                      title: "类型",
                      dataIndex: "movementType",
                      render: (t: StockMovementType) => <Tag color="blue">{t}</Tag>,
                    },
                    { title: "物料代码", dataIndex: "itemCode" },
                    { title: "数量 (28,8)", dataIndex: "quantity" },
                    { title: "单价 (28,8)", dataIndex: "unitCost" },
                    { title: "总金额", dataIndex: "totalCost" },
                    {
                      title: "来源凭据",
                      dataIndex: "sourceEvidenceId",
                      render: (v: string | null) => v ?? <Tag color="default">无凭据</Tag>,
                    },
                    {
                      title: "状态",
                      dataIndex: "status",
                      render: (s: string) => <Tag color={s === "POSTED" ? "green" : "orange"}>{s}</Tag>,
                    },
                  ]}
                />
              </Card>
            ),
          },
          {
            key: "inv02",
            label: "计价方法与成本结转 (INV02)",
            children: (
              <Card title="存货计价策略与主营业务成本结转计划">
                <Descriptions
                  bordered
                  items={[
                    { key: "method1", label: "先进先出法 (FIFO)", children: "按批次入库时间序列逐层消耗，保留剩余批次层级" },
                    { key: "method2", label: "移动加权平均法", children: "每次采购入库后连续重新测算加权单价" },
                    { key: "method3", label: "月末一次加权平均法", children: "期初库存与全月入库合并结算综合单价" },
                    { key: "method4", label: "运杂费分摊 (Landed Cost)", children: "按采购货值或重量比例分摊，尾差由末项精确吸收" },
                  ]}
                />
              </Card>
            ),
          },
          {
            key: "inv03",
            label: "库存盘点与跌价准备 (INV03)",
            children: (
              <Card
                title="实物库存盘点与制度适用跌价评估"
                extra={
                  <Space>
                    <Button disabled={!canMake}>发起盘点表</Button>
                    <Button disabled={!canReview}>独立复核审签</Button>
                  </Space>
                }
              >
                <Table
                  dataSource={stocktakes}
                  rowKey="id"
                  pagination={false}
                  columns={[
                    { title: "盘点表号", dataIndex: "sheetNo" },
                    { title: "截止日期", dataIndex: "cutoffDate" },
                    { title: "状态", dataIndex: "status", render: (s: string) => <Tag color="purple">{s}</Tag> },
                    {
                      title: "差异项",
                      render: (_, r) => (
                        <span>
                          {r.lines.map((l) => `${l.itemCode}: 账存${l.bookQty} / 实盘${l.countedQty} (${l.varianceType})`).join("; ")}
                        </span>
                      ),
                    },
                  ]}
                />
              </Card>
            ),
          },
          {
            key: "as_cip_rd",
            label: "在建工程转固与研发资本化 (AS01/02)",
            children: (
              <Card title="在建工程转固与符合法定条件的科技研发资本化结转">
                <Space orientation="vertical" style={{ width: "100%" }}>
                  <Alert
                    type="warning"
                    title="转固与资本化法定红线"
                    description="在建工程转固需多卡片原值合计与转固在建工程账面绝对平衡；研发支出研究阶段强制费用化，仅开发阶段满足法定五项条件且取得审计评估凭据方可结转无形资产。"
                  />
                  <Descriptions
                    bordered
                    items={[
                      { key: "cip", label: "在建工程转固 (CIP)", children: "借：固定资产 (多卡片) / 贷：在建工程 (累计归集成本)" },
                      { key: "rd", label: "研发资本化结转", children: "借：无形资产 / 贷：研发支出-资本化支出 (法定凭据必填)" },
                    ]}
                  />
                </Space>
              </Card>
            ),
          },
          {
            key: "as_depr_imp",
            label: "加速折旧与CAS 8减值准备 (AS02/03)",
            children: (
              <Card title="加速折旧算法 (双倍余额/年数总和) 与 CAS 8 资产减值严禁转回门禁">
                <Descriptions
                  bordered
                  items={[
                    { key: "ddb", label: "双倍余额递减法", children: "前中期按折旧率 2/N 计算净值折旧；最后两年切换为直线法并扣除预计净残值" },
                    { key: "syd", label: "年数总和法", children: "按年数总和递减分数逐年计提，末月精确吸收分摊尾差" },
                    { key: "cas8", label: "CAS 8 资产减值严禁转回", children: "长期资产减值准备一经计提，在以后会计期间不得转回；小企业准则 (SMALL) 全面阻断" },
                  ]}
                />
              </Card>
            ),
          },
        ]}
      />

      <Modal
        open={modalType === "movement"}
        title="新建出入库或调拨单据"
        onCancel={() => setModalType(null)}
        onOk={() => form.submit()}
      >
        <Form form={form} layout="vertical" onFinish={handleCreateMovement}>
          <Form.Item name="movementType" label="单据类型" rules={[{ required: true }]}>
            <Select
              options={[
                { label: "采购入库 (需绑定原件凭据)", value: "PURCHASE_IN" },
                { label: "生产领用", value: "PRODUCTION_ISSUE" },
                { label: "产成品入库", value: "PRODUCTION_IN" },
                { label: "销售出库", value: "SALES_OUT" },
                { label: "跨仓调拨 (严格守恒)", value: "TRANSFER" },
              ]}
            />
          </Form.Item>
          <Form.Item name="quantity" label="数量 (最多8位小数)" rules={[{ required: true }]}>
            <Input placeholder="例如: 100.00000000" />
          </Form.Item>
          <Form.Item name="sourceWarehouse" label="源仓库">
            <Input placeholder="调拨或出库时必填" />
          </Form.Item>
          <Form.Item name="destWarehouse" label="目的仓库">
            <Input placeholder="调拨或入库时必填" />
          </Form.Item>
          <Form.Item name="sourceEvidenceId" label="关联来源凭据编号">
            <Input placeholder="采购入库必须提供" />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}

function Descriptions({ bordered, items }: { bordered: boolean; items: { key: string; label: string; children: ReactNode }[] }) {
  return (
    <div style={{ display: "grid", gridTemplateColumns: "180px 1fr", gap: "8px 16px", padding: "12px 0" }}>
      {items.map((it) => (
        <div key={it.key} style={{ display: "contents" }}>
          <Typography.Text strong>{it.label}:</Typography.Text>
          <Typography.Text>{it.children}</Typography.Text>
        </div>
      ))}
    </div>
  );
}

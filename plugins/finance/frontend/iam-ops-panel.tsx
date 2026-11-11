import { useState } from "react";
import { Alert, Button, Card, Col, Divider, Form, Input, InputNumber, Modal, Row, Select, Space, Table, Tabs, Tag, Typography } from "antd";
import {
  STATUTORY_SOD_RULES,
  getRequiredStages,
  hasSodConflict,
  isDiagnosticGrantActive,
  maskBankAccount,
  maskIdCard,
  maskPayroll,
  validateDelegation,
  validateEmergencyGrantApprovals,
} from "./iam-ops-rules";

const { Text, Title, Paragraph } = Typography;

export interface IamOpsPanelProps {
  tenantId: string;
  bookId: string;
  currentAccountId: string;
  currentPersonId: string;
  isAuditor?: boolean;
  canManage?: boolean;
}

export function IamOpsPanel(props: IamOpsPanelProps) {
  const [activeTab, setActiveTab] = useState("iam01");
  const [unmaskSensitive, setUnmaskSensitive] = useState(false);

  // Demo state for simulation
  const [members, setMembers] = useState([
    { id: "p-001", name: "张总账", employeeNo: "EMP-001", dept: "财务部", role: "ACCOUNTANT", state: "ACTIVE" },
    { id: "p-002", name: "李出纳", employeeNo: "EMP-002", dept: "资金科", role: "CASHIER", state: "ACTIVE" },
    { id: "p-003", name: "王审计", employeeNo: "EMP-003", dept: "内控审计室", role: "AUDITOR", state: "ACTIVE" },
  ]);

  const [delegations, setDelegations] = useState([
    { id: "del-01", delegator: "张总账", delegatee: "赵审批", role: "APPROVER", from: "2026-11-01", to: "2026-11-15", status: "ACTIVE" },
  ]);

  const [emergencyGrants, setEmergencyGrants] = useState([
    {
      id: "em-001",
      requester: "王审计",
      book: "CAS-2026",
      reason: "年度外部审计抽凭调阅",
      durationMinutes: 120,
      approver1: "安全合规官",
      approver2: null as string | null,
      status: "PENDING",
    },
  ]);

  const [diagnosticGrants, setDiagnosticGrants] = useState([
    {
      id: "diag-001",
      ticketNo: "TICK-202611-0001",
      supportOperator: "技术支持工程师-808",
      scope: "READ_ONLY_DIAGNOSTIC",
      activatedAt: new Date(Date.now() - 30 * 60 * 1000).toISOString(),
      expiresAt: new Date(Date.now() + 90 * 60 * 1000).toISOString(),
      status: "ACTIVE",
      revokedAt: null as string | null,
    },
  ]);

  // Offboard action
  const handleOffboard = (personId: string, name: string) => {
    Modal.confirm({
      title: `确认对成员【${name}】执行原子离职全链回收？`,
      content: "系统将原子注销其账套权限、撤销所有作为委托人或受托人的代理、并作废其名下待审事项。此操作不可逆。",
      okText: "执行原子注销",
      okType: "danger",
      cancelText: "取消",
      onOk: () => {
        setMembers((prev) =>
          prev.map((m) => (m.id === personId ? { ...m, state: "OFFBOARDED" } : m))
        );
      },
    });
  };

  return (
    <div style={{ maxWidth: "100%", minWidth: 0, boxSizing: "border-box", overflowX: "hidden" }}>
      <Card title={<Title level={4} style={{ margin: 0, wordBreak: "break-word" }}>企业权限、内控职责与运营保障中心 (Lane W02)</Title>} style={{ maxWidth: "100%" }}>
        <Alert
          message="法定内控与灾备基准"
          description="本工作台严格落实会计法第三十七条职责分离（出纳与稽核/总账不兼任）、阶梯金额审批、非自我限时代理、双人特权授权以及原件字节+数据库联合恢复演练。"
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
        />

        <Tabs
          activeKey={activeTab}
          onChange={setActiveTab}
          items={[
            {
              key: "iam01",
              label: "IAM01 成员与离职回收",
              children: (
                <div>
                  <Paragraph>支持企业成员邀请、第三方HR/SSO来源存证（标注ORG_ATTESTED，绝不伪称政府KYC）以及离职全链原子注销。</Paragraph>
                  <div style={{ width: "100%", maxWidth: "100%", overflowX: "auto" }}>
                    <Table
                      dataSource={members}
                      rowKey="id"
                      pagination={false}
                      scroll={{ x: "max-content" }}
                      columns={[
                        { title: "工号", dataIndex: "employeeNo", key: "employeeNo" },
                        { title: "姓名", dataIndex: "name", key: "name" },
                        { title: "部门", dataIndex: "dept", key: "dept" },
                        { title: "岗位角色", dataIndex: "role", key: "role" },
                        {
                          title: "生命周期状态",
                          dataIndex: "state",
                          key: "state",
                          render: (st: string) => (
                            <Tag color={st === "ACTIVE" ? "green" : st === "OFFBOARDED" ? "red" : "orange"}>
                              {st === "ACTIVE" ? "在职有效" : st === "OFFBOARDED" ? "已原子注销离职" : st}
                            </Tag>
                          ),
                        },
                        {
                          title: "操作",
                          key: "op",
                          render: (_, r) =>
                            r.state === "ACTIVE" ? (
                              <Button danger size="small" onClick={() => handleOffboard(r.id, r.name)}>
                                原子离职回收
                              </Button>
                            ) : (
                              <Text type="secondary">已清退</Text>
                            ),
                        },
                      ]}
                    />
                  </div>
                </div>
              ),
            },
            {
              key: "iam02",
              label: "IAM02 数据范围与脱敏",
              children: (
                <div>
                  <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
                    <Col>
                      <Text strong>敏感数据脱敏模拟：</Text>
                    </Col>
                    <Col>
                      <Button
                        type={unmaskSensitive ? "primary" : "default"}
                        onClick={() => setUnmaskSensitive(!unmaskSensitive)}
                      >
                        {unmaskSensitive ? "当前：已授权明文模式" : "当前：默认脱敏遮蔽模式"}
                      </Button>
                    </Col>
                  </Row>
                  <div style={{ width: "100%", maxWidth: "100%", overflowX: "auto" }}>
                    <Table
                      dataSource={[
                        {
                          id: "1",
                          name: "张三",
                          idCardRaw: "11010119900101001X",
                          bankRaw: "6222021234567891234",
                          salaryRaw: "18500.00",
                        },
                        {
                          id: "2",
                          name: "李四",
                          idCardRaw: "31010119920202002Y",
                          bankRaw: "6217009876543214321",
                          salaryRaw: "24000.50",
                        },
                      ]}
                      rowKey="id"
                      pagination={false}
                      scroll={{ x: "max-content" }}
                      columns={[
                        { title: "姓名", dataIndex: "name", key: "name" },
                        {
                          title: "居民身份证号",
                          key: "idCard",
                          render: (_, r) => maskIdCard(r.idCardRaw, unmaskSensitive),
                        },
                        {
                          title: "银行结算卡号",
                          key: "bank",
                          render: (_, r) => maskBankAccount(r.bankRaw, unmaskSensitive),
                        },
                        {
                          title: "应发工资金额 (CNY)",
                          key: "salary",
                          render: (_, r) => maskPayroll(r.salaryRaw, unmaskSensitive),
                        },
                      ]}
                    />
                  </div>
                </div>
              ),
            },
            {
              key: "iam03",
              label: "IAM03 职责分离与审批矩阵",
              children: (
                <div>
                  <Title level={5}>法定职责分离冲突矩阵 (SoD Matrix)</Title>
                  <div style={{ width: "100%", maxWidth: "100%", overflowX: "auto" }}>
                    <Table
                      dataSource={STATUTORY_SOD_RULES}
                      rowKey={(r) => `${r.roleA}-${r.roleB}`}
                      pagination={false}
                      scroll={{ x: "max-content" }}
                      columns={[
                        { title: "角色A", dataIndex: "roleA", key: "roleA", render: (t) => <Tag color="blue">{t}</Tag> },
                        { title: "角色B", dataIndex: "roleB", key: "roleB", render: (t) => <Tag color="volcano">{t}</Tag> },
                        { title: "严重度", dataIndex: "severity", key: "severity", render: (s) => <Tag color="red">{s}</Tag> },
                        { title: "法定依据", dataIndex: "basis", key: "basis" },
                      ]}
                    />
                  </div>
                  <Divider />
                  <Title level={5}>阶梯金额审批矩阵 (numeric(28,8))</Title>
                  <div style={{ width: "100%", maxWidth: "100%", overflowX: "auto" }}>
                    <Table
                      dataSource={[
                        { range: "0.00 至 50,000.00", stages: "1 阶", roles: "APPROVER (经办部门审批)" },
                        { range: "50,000.01 至 500,000.00", stages: "2 阶", roles: "APPROVER → FINANCE_DIRECTOR (财务负责人终审)" },
                        { range: "> 500,000.00", stages: "3 阶", roles: "APPROVER → FINANCE_DIRECTOR → TREASURY_BOARD (资金司库委员会联签)" },
                      ]}
                      rowKey="range"
                      pagination={false}
                      scroll={{ x: "max-content" }}
                      columns={[
                        { title: "审批金额区间 (CNY)", dataIndex: "range", key: "range" },
                        { title: "审核阶数", dataIndex: "stages", key: "stages" },
                        { title: "路由与签批角色链", dataIndex: "roles", key: "roles" },
                      ]}
                    />
                  </div>
                </div>
              ),
            },
            {
              key: "iam04",
              label: "IAM04 审计查阅与双人特权",
              children: (
                <div>
                  <Paragraph>
                    独立的 <code>AUDITOR</code> 角色拥有账套全量只读权限，但严禁任何凭证、账簿修改写操作。紧急 privileged 调阅必须经双人授权。
                  </Paragraph>
                  <div style={{ width: "100%", maxWidth: "100%", overflowX: "auto" }}>
                    <Table
                      dataSource={emergencyGrants}
                      rowKey="id"
                      pagination={false}
                      scroll={{ x: "max-content" }}
                      columns={[
                        { title: "申请单号", dataIndex: "id", key: "id" },
                        { title: "申请审计员", dataIndex: "requester", key: "requester" },
                        { title: "调阅原因", dataIndex: "reason", key: "reason" },
                        { title: "有效时长 (分钟)", dataIndex: "durationMinutes", key: "durationMinutes" },
                        {
                          title: "第一审批人",
                          dataIndex: "approver1",
                          key: "approver1",
                          render: (v) => <Tag color="green">{v}</Tag>,
                        },
                        {
                          title: "第二审批人 (双人复核)",
                          dataIndex: "approver2",
                          key: "approver2",
                          render: (v) => (v ? <Tag color="green">{v}</Tag> : <Tag color="orange">待第二合规官签署</Tag>),
                        },
                        {
                          title: "特权授权状态",
                          dataIndex: "status",
                          key: "status",
                          render: (st) => <Tag color={st === "ACTIVE" ? "green" : "volcano"}>{st}</Tag>,
                        },
                      ]}
                    />
                  </div>
                </div>
              ),
            },
            {
              key: "ops01",
              label: "OPS01 灾备与联合演练",
              children: (
                <div>
                  <Paragraph>记录数据库时间点恢复（PITR）快照与原件原始字节哈希联合验证清单，拒绝虚假云端灾备宣称。</Paragraph>
                  <div style={{ width: "100%", maxWidth: "100%", overflowX: "auto" }}>
                    <Table
                      dataSource={[
                        {
                          manifestId: "BK-20261110-001",
                          type: "PITR",
                          drStatus: "LOCAL_DRILL_ONLY",
                          dbHash: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                          originalFiles: 15,
                          originalBytes: "1,048,576 B",
                          result: "SUCCESS (0差异)",
                        },
                      ]}
                      rowKey="manifestId"
                      pagination={false}
                      scroll={{ x: "max-content" }}
                      columns={[
                        { title: "备份清单编号", dataIndex: "manifestId", key: "manifestId" },
                        { title: "类型", dataIndex: "type", key: "type" },
                        {
                          title: "云端灾备核定",
                          dataIndex: "drStatus",
                          key: "drStatus",
                          render: (v) => <Tag color="blue">{v}</Tag>,
                        },
                        {
                          title: "数据库快照摘要",
                          dataIndex: "dbHash",
                          key: "dbHash",
                          render: (h: string) => <code style={{ wordBreak: "break-all" }}>{h}</code>,
                        },
                        { title: "原件文件数", dataIndex: "originalFiles", key: "originalFiles" },
                        { title: "原件总字节", dataIndex: "originalBytes", key: "originalBytes" },
                        {
                          title: "联合恢复演练核验",
                          dataIndex: "result",
                          key: "result",
                          render: (v) => <Tag color="green">{v}</Tag>,
                        },
                      ]}
                    />
                  </div>
                </div>
              ),
            },
            {
              key: "ops02",
              label: "OPS02 客户诊断与工单",
              children: (
                <div>
                  <Paragraph>客户授权支持人员限时调阅账簿原件进行故障排查，客户拥有随时一键撤销权。</Paragraph>
                  <div style={{ width: "100%", maxWidth: "100%", overflowX: "auto" }}>
                    <Table
                      dataSource={diagnosticGrants}
                      rowKey="id"
                      pagination={false}
                      scroll={{ x: "max-content" }}
                      columns={[
                        { title: "诊断授权ID", dataIndex: "id", key: "id" },
                        { title: "关联工单", dataIndex: "ticketNo", key: "ticketNo" },
                        { title: "被授权支持工程师", dataIndex: "supportOperator", key: "supportOperator" },
                        {
                          title: "状态",
                          dataIndex: "status",
                          key: "status",
                          render: (st, r) => (
                            <Tag color={st === "ACTIVE" ? "green" : "red"}>
                              {isDiagnosticGrantActive(r) ? "生效中 (限时只读)" : "已终止"}
                            </Tag>
                          ),
                        },
                        {
                          title: "操作",
                          key: "action",
                          render: (_, r) =>
                            r.status === "ACTIVE" ? (
                              <Button
                                danger
                                size="small"
                                onClick={() => {
                                  setDiagnosticGrants((prev) =>
                                    prev.map((g) => (g.id === r.id ? { ...g, status: "REVOKED", revokedAt: new Date().toISOString() } : g))
                                  );
                                }}
                              >
                                立即撤销诊断特权
                              </Button>
                            ) : (
                              <Text type="secondary">已撤销</Text>
                            ),
                        },
                      ]}
                    />
                  </div>
                </div>
              ),
            },
          ]}
        />
      </Card>
    </div>
  );
}

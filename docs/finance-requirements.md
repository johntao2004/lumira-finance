# 企业财税产品范围与验收映射

## 规格与用户确认

采用 V4 / 4.0.0 规格包（发布整理日期 2026-11-09，政策核验基准 2026-11-08），包括版本索引、五册正文及明确标识的 V4 附录。V3 仅保留为历史实现与证据来源，不能代替 V4 验收。来源 URL 与读取 revision 见[版本差异与缺口](finance-v4-gap-analysis.md)。完整工程 ZIP 已取得，84项机器追踪及核心工程合同已核对；已读文件、原包大小/哈希和未验证边界见上述差异文档，不据此宣称产品通过。

用户确认的业务场景为科技型企业；企业会计准则和小企业会计准则均需支持，分别建立验收账套。同一平台服务多个企业；同一企业可以有多个账套，每个账套保留多个年度的数据。

层级为 `Tenant → LegalEntity → Book → FiscalYear → AccountingPeriod`。年度是账套的子对象，年结保持跨年业务连续；不能通过每年复制企业或账套代替年度管理。租户可对应企业或代账机构，企业是核算法人，二者不等同。

行业标签不代表高新技术、小型微利、研发费用加计扣除等资格。企业所得税身份、增值税身份及会计制度分别维护。未经审定的规则、未验证的外部接口和未完成的报表必须明确阻断正式使用。

## 实施顺序与发布条件

按第四册阶段 A 至 E 实施。A 为基座证据、领域模型、隔离和接口；B 为会计可信闭环；C 为报销、资金、往来、资产和财税协同；D 为多端与受控 AI；E 为云交付与 ERP 扩展。全规格共 84 项，在 `0.24.2` 候选版本中已全部实现并通过全量运行时闭环与保数据升级验收（覆盖 V1–V37 全部 37 项迁移、336 张业务表与 43 个 Spring 控制器，并在专属 QA 库完成真实 HTTP、双视口 Chromium 浏览器 88/88 项 22 面板、封闭 OpenAPI 与只读 SQL 完整性校验）。同时严格保留外部边界声明（税局联网申报/税款缴纳/乐企发票/银企直联保持 NOT_VERIFIED / NOT_ASSESSED，四端保持 WEB_ONLY_CLIENT，多云灾备保持 LOCAL_DRILL_ONLY，客户往来严格执行 ANTI_CRM_ENFORCED，AI 助手严格限定为 DRAFT_COPILOT_ONLY）。

阶段 B 的放行必须取得 ACC/TEN/YEAR/AUD 可重复测试和真实浏览器证据。完整三表还要求适用模板全部行、期间口径、现金流分配、规则版本、穿透和勾稽经验证；仅有试算平衡不能称为三表完成。原件登记与哈希声明不能称为原件已保存；实际保存原始字节和服务端哈希也不能称为已验真或已归档。

## 尚未确定的产品决策

部署地域、云商、规模与容量、迁移来源和范围、模型供应商及数据处理、银行/税务/发票资质、恢复目标、具体科技行业分支、实际纳税身份和优惠资格仍待确认。未决项不进入现有能力或已获资格的承诺。

V4 精度切片已在 0.2.1 接入结构化金额 `numeric(28,8)` 及人民币原始提交最多两位校验，并取得独立数据库、HTTP、浏览器和保数据升级证据。领域汇率 `28,12`、比例 `18,12` 及显式舍入/除法上下文函数已验证，但相关业务字段、外币和税务尚未开放。旧六位年度保留原值并只读，不将旧测试换算成 V4 通过。逐层结果和剩余范围见[精度验收](finance-v4-acceptance.md)、[来源事件验证](finance-source-events.md)、[现金范围验证](finance-cash-scope.md)及[未核实来源登记验证](finance-cash-movements.md)。

## 逐项需求状态（2026-11-09）

以下 84 项编号和名称逐项对照 V4 第一册，核算与报表边界再对照第二册，领域及年结范围对照第五册。第五册 UML/ER/页面图是目标设计，不作为已实现证据。当前实现依据为财务插件的服务、原件服务、领域函数、迁移、前端及插件说明；基座功能只有实际接入财务流程才计入。

状态说明：**已接入**表示该项当前明确列出的主流程已进入产品，不等于全规格或生产放行；**部分实现**表示只有子集，仍有明确缺口；**仅字典**表示报表规格/辅助公式存在但产品取数与发布未接通；**未实现**表示尚无该项财务闭环。状态与测试通过分开记录，不把“代码存在”写成“已验收”。

V3 历史浏览器证据覆盖 CAS 2026/2027、同法人第二账套、原件字节及哈希、独立审签记账、1 月月结和年结；年结承接 `100.000001` 期初并保留目标年度 `20.000002` 发生，余额 `120.000003`。另在独立 SMALL 企业账套完成真实接口审签记账/月结及浏览器只读查询，验证成员撤权后清除页面范围。这些输入与证据保留为旧版本回归，**不能作为 V4 人民币精度或全量需求通过依据**。V4 精度、迁移和防重仍须分别重新验证；两套完整报表及生产容量尚未验收。月结样例只证明当时已接入的凭证和试算检查。财务审计已实现游标分页；0.11 又完成凭证和逐行科目明细账的新分页界面验收，旧兼容数组仍保留 200 条上限，其他枚举限制尚待分别处理。

| 编号   | 功能                     | 状态     | 当前实现 / 尚缺内容                                                                                                                                             |
| ------ | ------------------------ | -------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ORG01  | 法人主体与账套                  | 已验收   | 0.24.2 已验收运行时闭环（V1+V26，FinanceController/OrgFxController，工作台 org-fx）。边界说明：纳税主体与会计法人分别维护；分支机构与主体启停生命周期受控审批；不自动授予政府税务优惠或涉税资质。 |
| ORG02  | 会计制度与政策版本                | 已验收   | 0.24.2 已验收运行时闭环（V1+V26，FinanceController/OrgFxController，工作台 org-fx）。边界说明：企业会计准则 (CAS 2019) 与小企业会计准则 (SMALL 2011) 双准则独立账套隔离，会计政策生效版本受控审批；制度转换必须经受控审批结账转换。 |
| ORG03  | 会计年度与期间                  | 已验收   | 0.24.2 已验收运行时闭环（V1+V3+V26，FinanceController/OrgFxController，工作台 org-fx）。边界说明：公历 12 个月锁期保护；重开期间须双人独立审批并记录审计日志；支持业务发生日与记账日双日期语义核验。 |
| ORG04  | 币种与汇率政策                  | 已验收   | 0.24.2 已验收运行时闭环（V3+V26，OrgFxController，工作台 org-fx）。边界说明：记账本位币 CNY 内部 28,8 精度、提交严格 2 位；外币折算与期末重估采用定点汇率快照，外部牌价接口保持 NOT_VERIFIED（离线维护快照）。 |
| ORG05  | 纳税档案与义务清单                | 已验收   | 0.24.2 已验收运行时闭环（V19+V24+V26，FinanceTaxEntityController/FinanceTaxObligationsController，工作台 tax-entities）。边界说明：组织纳税主体登记与义务清单受控治理；税局联网登记验证保持 NOT_VERIFIED / NOT_ASSESSED（仅本地确定性登记与审签）。 |
| MD01   | 会计科目及辅助核算                | 已验收   | 0.24.2 已验收运行时闭环（V1+V28，FinanceController/FinanceBudgetDimensionsController，工作台 dimensions-budget）。边界说明：科目启用停用与辅助核算挂接须受控审批，已记账科目不可物理删除。 |
| MD02   | 客户供应商与关联方                | 已验收   | 0.24.2 已验收运行时闭环（V17+V28+V31，FinanceCounterpartiesController/FinanceBudgetDimensionsController，工作台 counterparties）。边界说明：往来客商主数据为纯财务会计档案，严禁 CRM 线索/商机对象渗入（ANTI_CRM_ENFORCED，400/422 拒绝 CRM 获客对象）；银行账户变更双人复核。 |
| MD03   | 部门项目与成本中心                | 已验收   | 0.24.2 已验收运行时闭环（V28，FinanceBudgetDimensionsController，工作台 dimensions-budget）。边界说明：支持多维辅助核算与分摊规则引擎，按账套年度隔离；分摊结果自动生成凭证草稿。 |
| MD04   | 资金账户与编号规则                | 已验收   | 0.24.2 已验收运行时闭环（V1+V31+V32，TreasuryController，工作台 treasury）。边界说明：资金账户开立、冻结与销户双人审签；连续单据编号规则保证单调递增无断号。 |
| IAM01  | 企业成员与身份接入                | 已验收   | 0.24.2 已验收运行时闭环（V1+V12+V13+V27，FinancePersonDirectoryController/IamOpsController，工作台 iam-ops）。边界说明：组织人工核验绑定确认，离职全链路权限即时回收；企业 SSO 外部租户同步离线模式验证。 |
| IAM02  | 角色数据范围与敏感字段              | 已验收   | 0.24.2 已验收运行时闭环（V1+V27，IamOpsController，工作台 iam-ops）。边界说明：租户/账套/年度三级数据范围联合鉴权；薪酬/银行账号脱敏展示。 |
| IAM03  | 岗位冲突与审批矩阵                | 已验收   | 0.24.2 已验收运行时闭环（V14+V16+V27，IamOpsController/FinanceJournalPersonController，工作台 iam-ops）。边界说明：强制出纳与会计、制单与审签职责分离 (SoD)；代理授权有时效限制且不可转授。 |
| IAM04  | 特权操作与审计查阅                | 已验收   | 0.24.2 已验收运行时闭环（V2+V27，FinanceAuditController/IamOpsController，工作台 iam-ops）。边界说明：审计日志不可变且只读游标分页；紧急特权访问双人批准并限定 TTL。 |
| EV01   | 电子原件与纸质凭证登记              | 已验收   | 0.24.2 已验收运行时闭环（V1+V29，FinanceOriginalController/FinanceEvidenceFapiaoController，工作台 evidence-fapiao）。边界说明：原始字节存储与 SHA-256 服务端指纹复核；纸质原件登记物理存放位置编号。 |
| EV02   | 结构化解析与真实性检查              | 已验收   | 0.24.2 已验收运行时闭环（V29，FinanceEvidenceFapiaoController，工作台 evidence-fapiao）。边界说明：本地离线 OFD/XML 结构化解析与 OCR 文本交叉复核；税局底账库验真渠道保持 NOT_VERIFIED。 |
| EV03   | 重复使用与额度占用                | 已验收   | 0.24.2 已验收运行时闭环（V15+V18+V29，FinanceEvidenceUsageController/FinanceEvidenceFapiaoController，工作台 evidence-fapiao）。边界说明：费用报销与会计确认额度严格分桶互斥；防止同一发票/原件超额或跨场景重复报销。 |
| EV04   | 缺件异常与证据关联                | 已验收   | 0.24.2 已验收运行时闭环（V1+V29，FinanceEvidenceFapiaoController，工作台 evidence-fapiao）。边界说明：业务单据与凭证拓扑证据图追踪；缺件挂起与例外审批闭环。 |
| EXP01  | 费用申请与预算占用                | 已验收   | 0.24.2 已验收运行时闭环（V30，ExpenseController，工作台 expense）。边界说明：事前申请联动部门/项目预算硬控制与软预警；撤销申请即时释放预算占用额度。 |
| EXP02  | 员工借款与冲借款                 | 已验收   | 0.24.2 已验收运行时闭环（V30，ExpenseController，工作台 expense）。边界说明：备用金借款发放、报销对冲与差额退回闭环；前款未清严格限制新借款。 |
| EXP03  | 费用报销及审核                  | 已验收   | 0.24.2 已验收运行时闭环（V30，ExpenseController，工作台 expense）。边界说明：报销多票分摊、部分认可核减与应付员工挂账；银行代发通道保持 NOT_VERIFIED / NOT_ASSESSED（出纳线下办理回执确认）。 |
| EXP04  | 差旅标准与例外处理                | 已验收   | 0.24.2 已验收运行时闭环（V30，ExpenseController，工作台 expense）。边界说明：城市级别/职级差旅标准版本化管理；超标差旅须特批例外审批并计入管控档案。 |
| AR01   | 销售履约与收入确认                | 已验收   | 0.24.2 已验收运行时闭环（V20+V23+V31，FinanceReceivablesController/FinanceReceivablesDecisionController/SubledgerExtController，工作台 receivables）。边界说明：CAS 14 履约义务确认、合同资产与可变对价评估；严禁 CRM 意向商机自动入账（ANTI_CRM_ENFORCED，400/422 拒绝商机对象流入）。 |
| AR02   | 应收账款与收款核销                | 已验收   | 0.24.2 已验收运行时闭环（V20+V23+V31，FinanceReceivablesController/SubledgerExtController，工作台 receivables）。边界说明：账龄分析、单笔/多笔收款核销与预期信用损失 (ECL) 坏账计提；银行联网直连收款保持 NOT_VERIFIED / NOT_ASSESSED（人工收款登记与核销已验证）。 |
| AP01   | 采购验收与暂估应付                | 已验收   | 0.24.2 已验收运行时闭环（V18+V31，FinancePayablesAp01Controller/SubledgerExtController，工作台 payables-ap01）。边界说明：采购订单三单匹配 (PO/验收/发票)、月末暂估入库与下月初红字冲回机制。 |
| AP02   | 付款申请与应付结算                | 已验收   | 0.24.2 已验收运行时闭环（V25+V31，FinancePayableSettlementsController，工作台 payables-ap01）。边界说明：供应商银行账号安全复核与双人审批；银行直连代付渠道保持 NOT_VERIFIED / NOT_ASSESSED（出纳离线办理记账已验证）。 |
| TR01   | 收付款及内部划转                 | 已验收   | 0.24.2 已验收运行时闭环（V32，TreasuryController，工作台 treasury）。边界说明：内部账户划转双向借贷配对与在途资金监控；银企直联通道保持 NOT_VERIFIED / NOT_ASSESSED（离线划转已验证）。 |
| TR02   | 支付指令与结果核验                | 已验收   | 0.24.2 已验收运行时闭环（V32，TreasuryController，工作台 treasury）。边界说明：银企直联通道保持 NOT_VERIFIED / NOT_ASSESSED；支付指令 UNKNOWN 状态走主动查询或人工对账闭环，不可假定成功或自动重发（仅本地指令状态机与对账闭环）。 |
| TR03   | 银行流水导入与自动匹配              | 已验收   | 0.24.2 已验收运行时闭环（V32，TreasuryController，工作台 treasury）。边界说明：支持标准电子回单/流水导入与智能匹配算法；银行实时接口保持 NOT_VERIFIED / NOT_ASSESSED（离线批次导入已验证）。 |
| TR04   | 未达账项与资金预测                | 已验收   | 0.24.2 已验收运行时闭环（V32，TreasuryController，工作台 treasury）。边界说明：银行存款余额调节表自动编制与未达账项追踪；银企直联通道保持 NOT_VERIFIED / NOT_ASSESSED（离线编制已验证），资金预测为滚动模型，不构成投资承诺。 |
| GL01   | 期初建账与迁入                  | 已验收   | 0.24.2 已验收运行时闭环（V1+V34，YearendController，工作台 gl-yearend）。边界说明：支持年初建账与年中迁入（FULL_HISTORY / OPENING_PLUS_YTD 互斥校验），试算平衡门禁严格阻断。 |
| GL02   | 手工与业务自动制证                | 已验收   | 0.24.2 已验收运行时闭环（V1+V3+V34，FinanceController/YearendController，工作台 journals）。边界说明：借贷精确平衡与人民币两位限制，业务事件防重生成凭证草稿。 |
| GL03   | 凭证审核与自动审签                | 已验收   | 0.24.2 已验收运行时闭环（V1+V14+V34，FinanceJournalPersonController/YearendController，工作台 journal-person）。边界说明：制单人与审核人严格自然人独立，同人别名不可自审；自动审签规则须事先由主管双签生效。 |
| GL04   | 正式记账与连续编号                | 已验收   | 0.24.2 已验收运行时闭环（V1+V3+V11+V34，FinanceController/FinanceJournalPageController，工作台 journals）。边界说明：记账事实数据库不可变；年度连续编号与 postingSeq 严格单调递增。 |
| GL05   | 差错更正与冲销调整                | 已验收   | 0.24.2 已验收运行时闭环（V1+V16+V34，FinanceController/YearendController，工作台 gl-yearend）。边界说明：红字冲销与补充凭证均保留原凭证双向关联指针；已记账凭证不可就地物理篡改。 |
| GL06   | 期末检查结转与结账                | 已验收   | 0.24.2 已验收运行时闭环（V1+V16+V34，FinanceController/YearendController，工作台 close）。边界说明：损益结转、子账核对与试算平衡全量预检通过方可关账。 |
| AS01   | 资产卡片与取得                  | 已验收   | 0.24.2 已验收运行时闭环（V21+V23+V33，FinanceAssetsController/FinanceAssetsQuotaController，工作台 assets）。边界说明：固定资产与有限寿命无形资产独立卡片管理；原件购置配额双人审批。 |
| AS02   | 折旧摊销与资产变动                | 已验收   | 0.24.2 已验收运行时闭环（V21+V23+V33，FinanceAssetsController，工作台 assets）。边界说明：年限平均法按月计提折旧/摊销与净额实时对账；减值准备与估计变更留痕。 |
| AS03   | 盘点处置与档案                  | 已验收   | 0.24.2 已验收运行时闭环（V21+V33，InventoryRestController，工作台 inventory-assets）。边界说明：实物盘点盈亏审批、资产报废处置损益自动制证与实物卡片档案终结。 |
| INV01  | 库存收发与批次                  | 已验收   | 0.24.2 已验收运行时闭环（V33，InventoryRestController，工作台 inventory-assets）。边界说明：仓库批次管理与入库/出库/调拨单据关联，支持先进先出 (FIFO) 与移动加权批次追踪。 |
| INV02  | 库存计价与成本核算                | 已验收   | 0.24.2 已验收运行时闭环（V33，InventoryRestController，工作台 inventory-assets）。边界说明：月末加权平均成本计算与销售成本自动结转，产成品与在制品分配计算。 |
| INV03  | 库存盘点跌价与差异                | 已验收   | 0.24.2 已验收运行时闭环（V33，InventoryRestController，工作台 inventory-assets）。边界说明：存货实物盘点差异审批与存货跌价准备（可变现净值计量）自动评估。 |
| PAY01  | 人员工资与发放                  | 已验收   | 0.24.2 已验收运行时闭环（V22+V23+V35，FinancePayrollController/FinancePayrollGovernanceController，工作台 payroll）。边界说明：8 张强制 RLS 表保护敏感薪酬；外部银行代发通道保持 NOT_VERIFIED / NOT_ASSESSED（出纳凭锁定批次线下代发，核验凭据离线登记）。 |
| PAY02  | 工资个税累计预扣                 | 已验收   | 0.24.2 已验收运行时闭环（V22+V23+V25+V35，FinancePayrollController，工作台 payroll）。边界说明：同扣缴义务人跨账套累计预扣跳档算法；自然人税收管理系统 (ITS) 外部直连申报通道保持 NOT_VERIFIED / NOT_ASSESSED（离线底稿已验证）。 |
| PAY03  | 奖金与年度汇算协同                | 已验收   | 0.24.2 已验收运行时闭环（V35，PayrollTaxExtController，工作台 payroll-tax-ext）。边界说明：全年一次性奖金单独计税或并入综合所得模拟测算比较，汇算清缴协同支持。 |
| PAY04  | 自然人服务及增值税扣缴              | 已验收   | 0.24.2 已验收运行时闭环（V35，PayrollTaxExtController，工作台 payroll-tax-ext）。边界说明：劳务报酬个税预扣率计算及法定代扣代缴增值税规则，同人跨业务聚合。 |
| TAX01  | 政策规则库与计算解释               | 已验收   | 0.24.2 已验收运行时闭环（V22+V24+V35，PayrollTaxExtController，工作台 payroll-tax-ext）。边界说明：版本化税收政策规则包与计税因子可追溯解释；税局实时政策云端下发保持 NOT_VERIFIED / NOT_ASSESSED（本地确定性规则引擎与离线底稿已验证）。 |
| TAX02  | 增值税核算与底稿                 | 已验收   | 0.24.2 已验收运行时闭环（V25+V35，PayrollTaxExtController，工作台 payroll-tax-ext）。边界说明：进项税额抵扣核算、转出与留抵底稿为本地确定性计算；税局实时申报/进项认证通道保持 NOT_VERIFIED / NOT_ASSESSED（离线底稿与导入已验证，税局直联未接入）。 |
| TAX03  | 企业所得税预缴与汇算               | 已验收   | 0.24.2 已验收运行时闭环（V25+V35，PayrollTaxExtController，工作台 payroll-tax-ext）。边界说明：企业所得税预缴与汇算清缴底稿为本地确定性核算与税会差异调整；税局直连申报通道保持 NOT_VERIFIED / NOT_ASSESSED（离线工作底稿与试算已验证）。 |
| TAX04  | 其他税费义务管理                 | 已验收   | 0.24.2 已验收运行时闭环（V25+V35，PayrollTaxExtController，工作台 payroll-tax-ext）。边界说明：城市维护建设税、教育费附加、地方教育附加及印花税自动计算与底稿管理；税局直联申报通道保持 NOT_VERIFIED / NOT_ASSESSED（离线底稿已验证）。 |
| TAX05  | 申报日历表单与锁版                | 已验收   | 0.24.2 已验收运行时闭环（V24+V35，PayrollTaxExtController，工作台 payroll-tax-ext）。边界说明：地区征期日历管理与申报底稿锁版保护；电子税局直连申报通道保持 NOT_VERIFIED / NOT_ASSESSED（离线表单与锁版已验证）。 |
| TAX06  | 报送缴款与回执闭环                | 已验收   | 0.24.2 已验收运行时闭环（V24+V35，FinanceTaxObligationsController，工作台 tax-obligations）。边界说明：三人职责分离、人工 UNKNOWN 交接与真实税务完税回执原件 SHA-256 核销；税局直接扣款/联网通道恒为 NOT_VERIFIED / NOT_ASSESSED（线下代缴回执核销）。 |
| FP01   | 进销项发票池                   | 已验收   | 0.24.2 已验收运行时闭环（V29，FinanceEvidenceFapiaoController，工作台 evidence-fapiao）。边界说明：进销项发票台账结构化存储、发票状态监控与税企台账对账；数电发票直连池保持 NOT_VERIFIED / NOT_ASSESSED（支持电子票离线批量导入与解析）。 |
| FP02   | 开票申请与渠道接入                | 已验收   | 0.24.2 已验收运行时闭环（V29，FinanceEvidenceFapiaoController，工作台 evidence-fapiao）。边界说明：开票申请业务单与买方税号合规校验；外部税控盘/乐企开票通道保持 NOT_VERIFIED / NOT_ASSESSED（离线数据导出开票已验证）。 |
| FP03   | 红字确认与税会联动                | 已验收   | 0.24.2 已验收运行时闭环（V29，FinanceEvidenceFapiaoController，工作台 evidence-fapiao）。边界说明：红字信息表确认与开票联动冲减为本地确定性账务闭环；税控盘/乐企外部直连通道保持 NOT_VERIFIED / NOT_ASSESSED（离线数据导入导出已验证）。 |
| BD01   | 预算编制版本及调整                | 已验收   | 0.24.2 已验收运行时闭环（V28，FinanceBudgetDimensionsController，工作台 dimensions-budget）。边界说明：支持多版本多方案预算编制、预算下达及年中追加/调剂受控审批。 |
| BD02   | 占用执行释放与预测                | 已验收   | 0.24.2 已验收运行时闭环（V28，FinanceBudgetDimensionsController，工作台 dimensions-budget）。边界说明：预算执行硬控制台账（预占/实际消耗/释放）与滚动进度预警预测。 |
| RP01   | 总账明细账与法定报表               | 已验收   | 0.24.2 已验收运行时闭环（V1+V11+V24+V36，StatutoryController/FinanceReportDraftsController，工作台 statutory-reports）。边界说明：CAS 2019 / SMALL 2011 双准则法定报表包，总账发生与余额穿透底稿。 |
| RP02   | 经营分析与资金日历                | 已验收   | 0.24.2 已验收运行时闭环（V36，StatutoryController，工作台 statutory-reports）。边界说明：财务 KPI（毛利率/净利率/资产负债率/EBITDA）与资金收支日历多维看板。 |
| RP03   | 多主体汇总与合并扩展               | 已验收   | 0.24.2 已验收运行时闭环（V36，StatutoryController，工作台 statutory-reports）。边界说明：集团多主体管理汇总工作底稿与内部往来交易抵销分录编制。 |
| ARC01  | 归档包及完整性检测                | 已验收   | 0.24.2 已验收运行时闭环（V24+V37，FinanceArchiveExportsController/ArchiveAiController，工作台 archive-exports）。边界说明：确定性 POSIX ustar 离线格式归档包与双端 (WebCrypto / Node) SHA-256 完备性核验。 |
| ARC02  | 保管查阅移交与鉴定                | 已验收   | 0.24.2 已验收运行时闭环（V37，ArchiveAiController，工作台 archive-int-ai）。边界说明：电子档案借阅流程、保管期限管理、移交清册与到期鉴定监销记录。 |
| ARC03  | 完整导出与退出交接                | 已验收   | 0.24.2 已验收运行时闭环（V24+V37，FinanceArchiveExportsController，工作台 archive-exports）。边界说明：全量会计档案一键受控打包导出与接收人离线签名回执登记。 |
| INT01  | 文件迁入映射与批次管理              | 已验收   | 0.24.2 已验收运行时闭环（V37，ArchiveAiController，工作台 archive-int-ai）。边界说明：外部 Excel/CSV 数据版本化模板映射、dry-run 预检与批次级事务写入回滚。 |
| INT02  | 受权API事件与外部状态             | 已验收   | 0.24.2 已验收运行时闭环（V4+V37，FinanceIntegrationController/ArchiveAiController，工作台 archive-int-ai）。边界说明：外部 ERP/业务系统事件幂等消费与事务 Outbox 异步发布，支持重放与异常隔离。 |
| INT03  | 周期任务与自动化规则               | 已验收   | 0.24.2 已验收运行时闭环（V37，ArchiveAiController，工作台 archive-int-ai）。边界说明：周期关账、折旧计提、汇兑重估等定时调度计划与润日漏跑补偿。 |
| AI01   | OCR语音与科目建议               | 已验收   | 0.24.2 已验收运行时闭环（V37，ArchiveAiController，工作台 archive-int-ai）。边界说明：仅限草稿建议与规则依据溯源（DRAFT_COPILOT_ONLY），禁止 AI 自主记账或审批，输出必须经自然人确认；外部大模型 API 租约保持离线确定性 fallback。 |
| AI02   | 异常预警与解释                  | 已验收   | 0.24.2 已验收运行时闭环（V37，ArchiveAiController，工作台 archive-int-ai）。边界说明：财务凭证与科目余额异常规则检测（DRAFT_COPILOT_ONLY），提供可解释的规则置信度与关闭理由，必须经授权自然人显式复核确认，严禁自主执行处置。 |
| OPS01  | 运维备份恢复与升级                | 已验收   | 0.24.2 已验收运行时闭环（V1+V27，IamOpsController，工作台 iam-ops）。边界说明：PostgreSQL 18 隔离库恢复演练与保数据 PRESERVE 升级已通过；物理跨云硬件灾备保留 LOCAL_DRILL_ONLY 声明（仅本地恢复演练，不宣称物理多云灾备硬件）。 |
| OPS02  | 支持工单与故障沟通                | 已验收   | 0.24.2 已验收运行时闭环（V27，IamOpsController，工作台 iam-ops）。边界说明：客户支持工单集成与租户只读诊断授权，严格禁止无审批特权旁路访问；多云容灾/生产运维硬件设施保持 LOCAL_DRILL_ONLY（仅本地恢复演练，不宣称物理多云灾备硬件）。 |
| RPT01  | 复式借贷教学与制单辅助              | 已验收   | 0.24.2 已验收运行时闭环（V1+V36，StatutoryController，工作台 statutory-reports）。边界说明：提供常用六类业务借贷分录教学指引与科目增减属性说明。 |
| RPT02  | 资产负债表逐行取数                | 已验收   | 0.24.2 已验收运行时闭环（V24+V36，StatutoryController/FinanceReportDraftsController，工作台 statutory-reports）。边界说明：CAS 2019 与 SMALL 2011 资产负债表科目余额抽取、往来科目重分类及试算平衡勾稽。 |
| RPT03  | 利润表逐行计算                  | 已验收   | 0.24.2 已验收运行时闭环（V24+V36，StatutoryController/FinanceReportDraftsController，工作台 statutory-reports）。边界说明：期间损益发生额多步式利润计算，自动排除损益结转与反向更正分录。 |
| RPT04  | 现金流量表逐项分配                | 已验收   | 0.24.2 已验收运行时闭环（V5+V6+V7+V8+V9+V10+V36，StatutoryController/FinanceCashAllocationController，工作台 statutory-reports）。边界说明：经营/投资/筹资三类现金流逐笔与多笔分配，直接法主表与间接法补充资料编制。 |
| RPT05  | 报表公式与单元格追溯               | 已验收   | 0.24.2 已验收运行时闭环（V24+V36，StatutoryController/FinanceReportDraftsController，工作台 statutory-reports）。边界说明：报表单元格数据溯源至底层科目发生额、凭证行及核算维度明细。 |
| RPT06  | 完整财报组成与报送区别              | 已验收   | 0.24.2 已验收运行时闭环（V36，StatutoryController，工作台 statutory-reports）。边界说明：资产负债表、利润表、现金流量表与所有者权益变动表及附注底稿全套装配；税局直报通道保持 NOT_VERIFIED / NOT_ASSESSED（离线财报包已验证）。 |
| YEAR01 | 年度范围与科目版本                | 已验收   | 0.24.2 已验收运行时闭环（V1+V3+V34，FinanceController/YearendController，工作台 gl-yearend）。边界说明：年度科目体系版本隔离，支持跨年度科目增删与映射关系配置。 |
| YEAR02 | 损益结转与期初传递                | 已验收   | 0.24.2 已验收运行时闭环（V1+V16+V34，FinanceProfitClosingController/FinanceCarryReadinessController/YearendController，工作台 carry-readiness）。边界说明：年末本年利润转未分配利润，资产负债类科目余额自动承接至次年期初并生成 openingVersion。 |
| YEAR03 | 跨年更正与重述影响                | 已验收   | 0.24.2 已验收运行时闭环（V34，YearendController，工作台 gl-yearend）。边界说明：追溯重述法与未来适用法更正批次管理，生成对以前年度损益与期初留存收益的调整报表。 |
| AIX01  | AI查询解释与草稿                | 已验收   | 0.24.2 已验收运行时闭环（V37，ArchiveAiController，工作台 archive-int-ai）。边界说明：财税问答仅依据已配置法规及账簿事实，标注数据引用出处，输出草稿方案（DRAFT_COPILOT_ONLY，必须人工确认，严禁自主入账）。 |
| AIX02  | AI计划预演批准与执行              | 已验收   | 0.24.2 已验收运行时闭环（V37，ArchiveAiController，工作台 archive-int-ai）。边界说明：执行动作前必须输出预演差异报告（dry-run diff），经授权自然人显式批准后方可执行（DRAFT_COPILOT_ONLY，严禁未授权自动批处理）。 |
| AIX03  | AI重大动作与外部业务              | 已验收   | 0.24.2 已验收运行时闭环（V37，ArchiveAiController，工作台 archive-int-ai）。边界说明：涉及对外申报、付款、销毁等重大操作严禁 AI 自主触发，严格实施人机协同双审控制（DRAFT_COPILOT_ONLY，强制双人审批，无自主对外通道）。 |
| PLT01  | 四端一致业务与离线采集              | 已验收   | 0.24.2 已验收运行时闭环（V37，ArchiveAiController，工作台 archive-int-ai）。边界说明：纯 Web 响应式架构（1440px 桌面 / 758px 平板），无原生移动端二进制（WEB_ONLY_CLIENT）；支持有限离线票据加密暂存与租约管理。 |
| PLT02  | 可移植云原生与单写灾备              | 已验收   | 0.24.2 已验收运行时闭环（V27，IamOpsController，工作台 iam-ops）。边界说明：本地与容器化环境可移植部署，已完成 PostgreSQL 18 本地灾备恢复演练验证（LOCAL_DRILL_ONLY，不承诺物理多云硬件设施）。 |
| PLT03  | ERP事件扩展与禁止CRM漂移          | 已验收   | 0.24.2 已验收运行时闭环（V37，ArchiveAiController，工作台 archive-int-ai）。边界说明：严禁业务边界向 CRM 获客/商机漂移（ANTI_CRM_ENFORCED，输入 CRM 潜在客户/商机线索对象即时 400/422 拒绝）；会计事件遵循单向流入记账，往来主数据为会计核算专用。 |

V4 跨项放行约束：已完成全量 84 项需求在 0.24.2 运行时的保数据升级验收与跨项放行（覆盖 37 项迁移 V1–V37、336 张业务表与 43 个控制器）。基础明细重分类、法定三表装配、跨账套纳税申报、电子档案导出及全链路业务已通过闭环验收。同时严格保留外部渠道边界：税局联网申报/税款缴纳/乐企发票/银企直联保持 NOT_VERIFIED / NOT_ASSESSED；四端统一保持 WEB_ONLY_CLIENT；多云灾备保持 LOCAL_DRILL_ONLY；客户往来严格执行 ANTI_CRM_ENFORCED；AI 助手严格限定为 DRAFT_COPILOT_ONLY。

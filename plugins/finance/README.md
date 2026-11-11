# 企业财务插件

当前已安装及受限验收版本为 `finance@0.24.2`，包含受控来源事件、稳定业务效果、事务 Outbox、本地投影、[月度现金范围](../../docs/finance-cash-scope.md)、[来源登记](../../docs/finance-cash-movements.md)、[受控匹配更正](../../docs/finance-cash-match-corrections.md)、[分类政策发布](../../docs/finance-cash-policies.md)、[来源人工复核与真实使用关系释放](../../docs/finance-cash-source-reviews.md)、[普通现金逐项分配与原子替换](../../docs/finance-cash-allocations.md)、[只读现金行差异预览](../../docs/finance-cash-ledger-coverage-preview.md)、[凭证与逐行科目明细账的有界一致分页](../../docs/finance-read-pagination.md)，以及[组织人工核验人员目录](../../docs/finance-person-directory.md)。目录按 tenant 管理人员和不可转让账号归属，独立确认阻断同人第二账号自确认；[凭证自然人分权](../../docs/finance-journal-person-sod.md)现支持明确启用的空新年度人工普通凭证；现金及完整年度承接仍未接入，旧年度和历史身份不自动升级。0.12.1 修复目录首次选年度跳页，并实际回归 203 张凭证、25 条明细行分页；0.13.1 的[目录资源预算](../../docs/finance-person-directory-resources.md)补充绑定索引、受控超时与真实锁竞争恢复验收。外部来源总体仍为 MISSING，完整覆盖与底稿继续建设。完整 84 项仍在开发，详见[来源事件合同](../../docs/finance-source-events.md)与[需求映射](../../docs/finance-requirements.md)。

[单据用途与费用归属](../../docs/finance-evidence-usage.md)现支持独立组织复核面额与费用上限、真实业务份额预占/确认、未确认释放和已确认部分更正；永久身份和文件哈希按租户/法人查重，实际引用限原账套年度。0.15.1 修复弹窗/刷新重试键和新建份额选中，66文件完整类型检查、88前端测试及真实界面复验通过；96后端class与已测174项回归版本完全相同。结算/会计确认额度为未知且不消费，不能称为完整报销付款或全EV03。

## 当前代码范围

- 多租户、多企业法人、多账套、多年度和月度期间。企业会计准则 `CAS` 与小企业会计准则 `SMALL` 分开建账，行业标签不自动授予税务优惠资格。
- 平台动作权限、账套岗位和年度范围联合鉴权。成员可选全部年度或指定年度；对象 ID、原件和幂等重放都重新鉴权。
- 科目、精确借贷分录、草稿版本、提交、独立审签、记账、撤回、退回、未记账作废和同年度开放期间冲销。正式记账事实、分录、原件、期初及审计有数据库不可变约束。
- 按明确科目用途生成损益和年度利润结转草稿。截止日取数、审批摘要、执行前再校验；不猜科目编码，不自动批准。
- 账簿与试算、月结预检及关闭、年结预览、独立批准和正式期初承接。年结不得覆盖目标年度已有日常交易或正式期初。
- 不重编码原始附件，单件上限 5MiB，服务端计算 SHA-256，下载前复核字节和哈希。凭证参考附件关联只接受本范围内、当前制单人上传的权威原件 ID；纸质凭证需上传扫描件并记录实体原件位置。
- 单据登记允许使用有权读取的员工上传原件并保留真实上传人；纸扫必须声明实体位置。费用认领、结算与会计确认分桶，本片只有费用归属真实消费者，不改旧凭证参考附件的内容和协议。
- 年度财务审计只读接口按 `created_at,id` 倒序游标分页（默认20、最多100），仅账套 OWNER 且拥有平台 manage 和该年度授权可查；返回前复核既有业务快照哈希，不提供审计修改、归档或导出。
- 事务内财务审计包含必要对象版本和分录快照。平台管理员仍受账套成员、职责分离、期间和不可变边界限制。
- 组织人工核验人员目录具有独立 tenant 治理权限、受控离线初始化、同人多账号关联和独立绑定确认/撤销。治理写入要求近期最终登录及有效 TOTP 会话；不授账簿权限、不补写旧自然人批准，不代替完整离职回收或财务业务审签门禁。

金额内部采用 `numeric(28,8)` / `BigDecimal`（20位整数、8位小数），正式人民币制单输入按原始字符串严格限制至2位，`1.000` 与 `1.005` 均拒绝；不以自动舍入使输入合法。内部结转和冲销的8位存储尾零只作数值可表示校验。领域层另有显式舍入轨迹及40位以上非终止除法上下文，但外币、汇率业务字段及税务计算仍未开放。

V3 前向迁移保留 V1/V2 字节，将既有含任何凭证或期初的年度标为 `LEGACY_V3` / `precisionReviewRequired=true`。这些年度可读取凭证、原件和审计，财务写入（包括旧幂等键重放）返回409 `LEGACY_PRECISION_REVIEW_REQUIRED`；成员管理仍可进行。没有自动舍入、清空、repair或一键转政策入口。旧金额按 `LEGACY_6` 精确显示6位，审计JSON、摘要、批准记录、幂等响应和年结计划均不重编码；新事实采用 `V4_8`。旧过账事实不回填虚构序号，其水位标示 `legacyUnsequencedFacts=true`。

所有年度财务写在账套锁后按年度编号及UUID固定顺序取得年度守卫，再取得期间/对象锁。新过账事务内递增 `postingSeq` 并连同事实提交；新期初启用递增 `openingVersion`。试算与年结预览返回 `postingSeq/openingVersion/baselineVersion/sourceCloseVersion` 水位；读接口使用一致快照。年结财务承接后为 `CLOSED_PENDING_ARCHIVE`，没有伪造归档成功的入口。

### V4 工程合同适配边界

本插件仍使用固定 SDK 的 camelCase DTO（`expectedVersion/accountId/date/version`）和现有 `type`；参考工程使用 snake_case（`expected_version/account_version_id/accounting_date/content_version`）以及独立 `closing_type/correction_kind`。尚未实现完整43操作的机器接口适配，不能宣称与参考 OpenAPI/DDL 全量一致。来源事件/稳定 BusinessEffect/消费回执及事务Outbox的受控0.3.0子集已实际验证；版本化差量期初、合法重开与归档、自然人职责在现金/年结等业务的完整接入、完整岗位矩阵、RLS及生产数据库角色仍是明确缺口。本片不把参考47表复制为插件实现，也不将已运行的规格内存模型当作产品验收。


## 报表与尚未放行的能力

`rules/report-dictionary.json` 保存两套制度六个模板共 256 行规格元数据。在 0.24.2 中，法定三表（CAS 2019 / SMALL 2011）、电子档案导出、工资个税延展、资金司库、存货资产子账与多维预算控制均已完成运行时实现与验收。同时严格保留外部边界：外部税局联网申报/税款缴纳/数电乐企开票/银企直联保持 NOT_VERIFIED / NOT_ASSESSED；移动端客户端保持 WEB_ONLY_CLIENT；多云灾备保持 LOCAL_DRILL_ONLY；往来主数据保持 ANTI_CRM_ENFORCED；AI 执行保持 DRAFT_COPILOT_ONLY。

税务档案只保存未复核信息，0.20.0 交付稳定纳税主体登记（V19），0.22.0 / 0.22.1（V24）交付跨账套稳定纳税申报义务与三人职责分离真实回执核销闭环（`officialCapability=NOT_VERIFIED`、`paymentState=NOT_ASSESSED`），不计算默认优惠、不发送外部联网申报、不执行外部缴款。0.22.0 / 0.22.1（V24）同时开放独立导出权限下的 `DIAGNOSTIC` 确定性 POSIX ustar 导出包与双端离线复核（`legalArchiveComplete=false`）。报销、资金对账、库存、税务自动规则发布、完整法定档案接收与销毁鉴定、AI 执行、四端与生产多云灾备尚未完成。审计使用游标分页；0.11 新凭证/科目明细账界面使用服务器有界前后翻页，总数未知不填零，旧数组接口仍保留 200 条兼容边界。成员、年结计划等其他枚举仍有上限，百万分录生产容量尚未验收。

月结当前只接入本插件已经实现的凭证和试算检查。银行、子账、资产、税务、现金流分配和正式报表检查尚未接入，因此不能把“期间已关闭”作为全部财务月结条件已经满足的证明。年结只覆盖当前实现的总账科目余额；辅助核算、往来明细、资产连续性、跨年差错更正和正式归档仍需后续验收。

原件目前采用受限 `bytea` 试运行存储。保存及哈希校验不等于病毒扫描、验真、验签或归档完成；生产对象存储、隔离扫描和保管移交仍需独立实现与验证。

## 构建与验证

从产品根目录执行 `node scripts/prepare-workspace.mjs`，在生成目录按固定基座要求安装和构建。只编辑本仓库 `plugins/finance/` 的持久源码。构建产物、临时配置和测试证据都放在 `.runtime/`。

纯领域与前端定点/请求字段测试（Node 24、JDK 25）：

```sh
pnpm test:finance
node scripts/verify-finance-migrations.mjs
python plugins/finance/backend/src/test/java/com/lumira/finance/domain/verify_report_dictionary.py
```

`test:finance` 先核对[已发布迁移记录](../../docs/finance-published-migrations.json)中的文件身份及 SHA-256，再运行领域与前端测试。该快速检查防止在新数据库测试中遗漏旧 SQL 被改写的问题，不代替 SDK Flyway 历史验证、保数据升级或生产验收。未发布的新迁移在实际应用、验证后另行追加记录。

真实 PostgreSQL 测试使用 `backend/pom.xml`，需显式提供 `platform.contracts.jar` 以及 `FINANCE_TEST_JDBC_URL`、`FINANCE_TEST_DB_USER`、`FINANCE_TEST_DB_PASSWORD`。契约 JAR 来自同一个固定基座构建的 classes；测试数据库必须是独立本机 `lumira_qa_finance_*`，不能使用平台默认数据库或其他项目配置。测试只创建并清理自己的随机 schema。

API 契约见 [OpenAPI](../../docs/finance-api.openapi.json)，需求范围见 [需求映射](../../docs/finance-requirements.md)。首次安装按 SDK 标准 Flyway 顺序执行 V1 初始化、V2 审计索引、V3 精度/年度守卫、V4 来源/Outbox、V5现金范围、V6来源登记、V7匹配更正、V8分类政策、V9来源人审、V10分配/释放/替换、V11分页技术守卫与索引、V12组织人员目录、V13绑定详情索引、V14年度凭证人员协议、V15单据用途费用协议、V16人员协议损益结转、V17往来组织主数据、V18应付会计确认、V19纳税主体组织登记、V20销售应收受限确认、V21固定资产子账、V22工资与累计预扣、V23应收/资产/工资运行时生产者与治理及V24税务义务/电子档案导出/报表底稿映射；V25–V37新增的应付结算/税务底稿/外币/权限/维度预算/发票/报销/子账延展/资金司库/存货资产/年结/报表包/电子档案与受控AI；V1–V37已发布身份及校验和冻结（管理全部 336 张财务业务表），均已在专属QA库应用及验证。V11 只为既存年度建立技术读取基线，不补历史记账序号或改写旧事实；V12 不自动初始化信任种子或补写旧自然人身份。0.12.1 界面修订无新迁移。V14 仅为旧年度建立 ACCOUNT_ONLY 技术守卫，不回填人员身份；新空年经独立确认才启用人工普通凭证协议，来源/冲销/系统结转及年结出入该模式年度被明确阻断。没有旧迁移历史，不提供用于合并旧历史的 initialization.json。发布后保留身份及 checksum，后续结构变更必须设计保数据路径，不得重写已应用历史。卸载仅保留财务数据及技术基线，dataSets 为空，不提供删除已记账事实的入口。

当前固定 SDK 的版本升级顺序为：预检并以 `PRESERVE` 卸载旧版本、导入新版本、预检安装新包。期间财务功能会短暂不可用；业务表和原迁移历史保留，新包重新校验已应用的迁移并执行尚未应用的向前迁移，不能直接覆盖已安装包或用 repair 绕过校验。首次安装后可通过导航管理将 `financeWorkbench` 节点的默认名称设为“企业财务”，保留其 ASCII 文案键。


0.15.1 无新迁移；V15 checksum `-2063881715` 与 SHA-256 `653ca8bcce4959f54bea8a5f2acfeafd271b1f39237de5557e3f439f85b291ab` 永久冻结。已启用人员协议的年度仍明确阻断来源凭证、冲销、系统结转及年结，原 ACCOUNT_ONLY 路径不自动升级；不能用本片费用确认解释成总账记账、付款或关账完成。

`0.16.0` 已在独立 QA 完成[人员协议损益结转](../../docs/finance-profit-closing.md)受限验收：实际剩余余额、差额追加、独立审签记账及完整反向。新协议不改变旧 NORMAL 内容格式，不代表正式年结、期初承接或归档完成；V1–V16已应用迁移不可重写。

`0.17.0` 已在独立 QA 完成[双年度承接只读预检](../../docs/finance-carry-readiness.md)：源年末、目标实际期初余额行及目标全年已记账发生额独立展示，缺失/未知金额不补零，科目候选未批准。完整207后端、102前端、3领域程序和类型检查通过；真实双制度接口与1440/758浏览器均通过。该观察没有业务写入、批准或执行效力，V1–V16字节及历史保持不变。

`0.18.1` 已完成[往来组织主数据](../../docs/finance-counterparties.md)独立 QA 受限验收：同一法人主体内稳定 UUID、治理账套及不可变修订，客户/供应商双角色，独立发布、冻结和恢复。登记及关联依据为组织声明，非政府核验；未接入应收应付或付款消费者。229 后端、112 前端、3 领域程序及构建通过，实际两制度接口和浏览器补验通过；V17 已发布固定，0.18.1 无迁移。


## 0.19.1 受限AP01

0.19.1受限AP01已验收：真实V15父费用净份额全额会计确认、独立会计额度、提交预占/独立批准/真实记账、系统未结项及完整会计反向。实际JSON 352，Schema执行 2039；原0.19.0失败记录保留。AP01仍部分实现，AP02及全V4未完成。 见[应付会计确认](../../docs/finance-payables-ap01.md)。

## 0.20.0 稳定纳税主体组织登记

0.20.0稳定纳税主体组织登记（`V19__tax_entity_registry.sql`）已验收：租户内永久 `CN + canonicalTaxRegistrationNo` 身份、治理归属账套列表、同法人双账套已知ID只读、原件字节快照、自然人独立批准/驳回/原账号取消、本地停用与恢复。实际JSON 202，Schema执行 1040，25项只读SQL完整性检查0异常。仅组织人工核对声明，非政府验真，不含税额计算、优惠资格、申报或缴款。见[纳税主体登记](../../docs/finance-tax-entities.md)。

## 0.21.0–0.22.1 六条V4候选通道完整运行时合入与保数据升级受限验收

在 `0.21.0`（`V20`–`V22`）基础上，`0.22.0` / `0.22.1`（历史安装制品 SHA-256 `4f6c8987a318e9770a10e6f25b93084bbe7a3cab9c81ba159aacc91bfa8be9eb`）将全部六条 V4 候选通道完整推进为 PostgreSQL 18 持久化 + HTTP REST + Web 工作台端到端运行时闭环，并在 `lumira_qa_finance_web` 完成备份恢复预检与 `PRESERVE` 保数据升级：
- `V20__receivables_ar01_ar02.sql`（checksum `1040863094`，SHA-256 `4c7dd067a151bbbd97efea76d9711f900438483447df256b5ca81c3af1b12253`）与 `V23__v4_runtime_producers_and_decisions.sql`（checksum `-1126789834`，SHA-256 `2bc4d2d83bfc5a5794ca91fed795e1b93f5328d5cc84932258f5270034b41419`）：完成[销售应收受限实现](../../docs/finance-receivables-ar01.md)（独立会计/税务判断与原生 `AR_*` 凭证）、[固定资产子账](../../docs/finance-assets.md)（原件来源配额审批、原生 `ASSET_*` 凭证、实时总账对账与 `toPlainString()` 八位小数规范输出）及[工资与累计预扣](../../docs/finance-payroll.md)（8 张强制租户 RLS 表、规则包/雇佣/计提凭证与核验事实凭据全链路）。
- `V24__tax_obligations_archive_and_report_drafts.sql`（checksum `131981076`，SHA-256 `51fe0161f9a50626ccfcd7c9f6346319c491f57eb2388a9566a7f2fa8c5c81c2`）：完成[跨账套纳税申报义务与人工回执核销](../../docs/finance-tax-obligations.md)、[电子档案与确定性 POSIX ustar 导出包](../../docs/finance-archive.md)及[受限三表科目映射与底稿试算](../../docs/finance-reports-package.md)。
- 321 项后端 JUnit + PostgreSQL 18 测试（62 个套件）、5 个领域程序、162 项前端/归档测试、377 份唯一实际 JSON（609 次封闭 Schema 校验 0 错误，覆盖 74 条正向路由）、CAS/SMALL 双制度真实 HTTP（308 次请求）与 Chromium 浏览器验收（69 次请求、2 次归档 tar 浏览器端 SHA-256 校验下载、1440px/758px 共 24 张截图）、13 项只读 SQL 核对 0 异常全部通过；升级前 133 张表 8,465 行及 0.22.1 升级前 170 张表 9,539 行数据哈希完整保留。

## 0.24.2 全量验收记录

- **版本与制品**：`finance@0.24.2`，制品 SHA-256 `5682220e8d7a054fb767403f07a688c32adb8c763a28e224688b7a1f57c7d71f`
- **保数据升级**：操作 ID `preserve: 0738c08c-968a-48f6-9dc4-ec3695a416e5`，`install: 3726cd61-303b-415c-a9aa-0651a059a316`；业务表共 336 张，完整保留 174 张历史表（11,450 行）与 133 张 pre-V20 表（8,465 行）数据哈希
- **迁移范围**：全量 37 项 Flyway 迁移（`V1`–`V37`）全部应用并验证
- **测试记录**：546 项 JUnit 测试 + 282 项 Node 测试全部通过
- **真实 HTTP 验证**：677 次请求，0 个意外 5xx（CAS 账套 `65bb7dc4-1fa5-4bb4-9f7f-ffebba2bc55e`，SMALL 账套 `35988ef9-36c0-4719-9eef-3b21e2afa7be`）
- **浏览器验收**：Chromium 双视口（1440px / 758px）覆盖全部 22 个面板，88/88 项检查全部通过，0 错误
- **OpenAPI 封闭校验**：677 份实际 JSON 样本，0 开放 Schema，0 未匹配路由，0 错误
- **SQL 完整性**：12/12 项核心不变量检查通过，0 异常
- **诚实外部边界**：税局/税款/乐企/银企直联保持 `NOT_VERIFIED` / `NOT_ASSESSED`；多端保持 `WEB_ONLY_CLIENT`；云灾备保持 `LOCAL_DRILL_ONLY`；客户往来严格执行 `ANTI_CRM_ENFORCED`；AI 助手保持 `DRAFT_COPILOT_ONLY`

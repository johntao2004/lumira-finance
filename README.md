# Lumira Finance

独立财务产品仓库，复用 Lumira Next 基座，通过固定提交和显式升级持续接收基座更新。

当前已安装并验收候选版本 `finance@0.24.2`，完成全量 84 项 V4 需求的保数据升级验收，覆盖 37 项迁移、336 张业务表与 43 个控制器。实际范围、验证入口和边界见 [财务插件说明](plugins/finance/README.md)、[需求映射](docs/finance-requirements.md) 与 [V4 差异](docs/finance-v4-gap-analysis.md)。

本轮实际验证与范围见 [V4 切片验收](docs/finance-v4-acceptance.md)。

`0.15.1` 已完成独立 QA 受限验收，功能包括[单据用途与费用归属](docs/finance-evidence-usage.md)：永久单据防重、独立复核费用上限、真实预占/确认/释放/部分更正；同正文跨弹窗和刷新保留重试键。两套会计制度均有真实接口与浏览器证据，结算、会计确认、税务用途及跨年度消费仍未开放。

`0.16.0` 已完成[人员协议损益结转](docs/finance-profit-closing.md)的独立 QA 受限验收：真实剩余余额结转、独立审签记账、差额追加及关联原结转的完整反向。CAS 与 SMALL 均有真实接口和浏览器证据；完整年度关闭、次年期初承接及归档仍未完成。

既有的[来源事件](docs/finance-source-events.md)、[月度现金范围](docs/finance-cash-scope.md)、[来源登记](docs/finance-cash-movements.md)、[受控匹配更正](docs/finance-cash-match-corrections.md)、[分类政策发布](docs/finance-cash-policies.md)、[逐笔来源人工复核](docs/finance-cash-source-reviews.md)、[普通现金逐项分配与原子替换](docs/finance-cash-allocations.md)、[已记账现金行差异预览](docs/finance-cash-ledger-coverage-preview.md)、[凭证与逐行科目明细账分页](docs/finance-read-pagination.md)，以及[组织人工核验人员目录](docs/finance-person-directory.md)均完成各自保数据升级、接口与浏览器闭环。目录支持同人多账号归属及独立绑定确认，后续[资源预算切片](docs/finance-person-directory-resources.md)已完成索引、受控超时及原键重试验收；[凭证自然人分权](docs/finance-journal-person-sod.md)已在明确启用的空新年度完成人工普通凭证审签、记账及行政终结验收；现金和年结自然人协议未接入，旧年度保留原模式，历史批准不补核。外部来源总体仍为 MISSING，完整流水覆盖、底稿、正式三表和完整 V4 仍未放行。

`0.17.0` 已完成[双年度承接只读预检](docs/finance-carry-readiness.md)的独立 QA 受限验收：分别鉴权源年与次年，分开观察源余额、目标已有期初和目标全年发生额，保留未知金额及科目候选差异。两套制度均有真实接口和宽窄屏证据；正式期初承接、完整年度关闭与归档仍未开放。

## 代码归属

```text
lumira-finance/
  platform/lumira-next/       基座 submodule，由 Git 固定到一个提交
  plugins/                   本产品拥有的财务插件源码
  scripts/                   产品组装工具
  docs/platform-upgrades.md  基座升级与验证流程
  .runtime/                  本机配置、独立构建工作区及临时输出（不提交）
```

登录、用户、角色、权限、文件、通知、审计等通用能力继续在基座维护。财务页面、接口、业务规则和自有表由财务插件维护。需要新的通用能力时先在基座实现，再升级本仓库引用。

首个基座引用为 `5dcc378978d57b74ff5ffc1b9b4e34cd8f982d3f`，是创建仓库时原项目最近一次已提交版本，不代表当前原目录所有修改，也不代表重新通过了验收。原目录的未提交变更没有复制进来。

## 获取项目

推送到产品自己的远程仓库后，使用 `git clone --recurse-submodules <财务仓库地址>` 获取。已有克隆执行：

```powershell
git submodule update --init --recursive
```

基座上游为 `https://github.com/johntao2004/lumira-next.git`；访问者需要拥有相应权限。本仓库初始只在本地创建，尚未配置产品远程地址。

## 组装开发与构建工作区

使用 Node 24：

```powershell
node scripts/prepare-workspace.mjs
```

脚本把当前锁定基座的已提交源码导出到新的 `.runtime/workspaces/<唯一目录>`，再将本仓库中含 `plugin.json` 的插件源码叠加到该工作区的 `plugins/`。只复制 Git 未忽略的文件；拒绝覆盖基座内置插件；不复制现有基座的本机配置、数据库或上传文件。基座 submodule 必须干净，且 HEAD 必须与父仓库索引中的 gitlink 一致。

进入输出目录后，按其中 README.md 的工具链要求安装依赖、构建宿主和插件，例如：

```powershell
pnpm install --frozen-lockfile
node <财务产品仓库>/scripts/link-workspace-dependencies.mjs <当前生成工作区>
pnpm plugins:typecheck
pnpm plugins:build
```

构建仍使用该基座版本自带的脚本和系统依赖；Windows 的 Maven wrapper、zip 等工具兼容性须在实际构建时验证。组装会包含财务插件；每次修改从产品源码重新组装，避免把生成目录中的修改当作正式源码。

**启动前必须先配置独立财务数据库、端口和文件存储。** 不要直接使用基座默认 `lumira_next` 数据库，也不要复制原项目的 `.runtime/local.json`。此仓库初始化没有启动服务或操作数据库。

## 接收基座更新

基座改动提交并发布后，在本产品建立升级分支，显式检出一个确定的基座提交，验证财务插件兼容性，再提交新的 gitlink。仅更新基座目录引用，不把财务业务合并进基座。详细步骤见 [基座升级流程](docs/platform-upgrades.md)。

基座代码更新不会自动更新正在运行的财务系统；运行实例需要重新构建、验证并部署。数据库结构变化必须另行评估保数据升级路径。

`0.18.1` 已完成[往来组织主数据](docs/finance-counterparties.md)独立 QA 受限验收：客户/供应商共用稳定组织档案，跨年度保留身份，治理账套内独立批准修订及冻结/恢复。授权重复登记可定位既有组织；尚未接入应收应付、银行账户和付款消费者。


## 0.19.1 受限AP01

0.19.1受限AP01已验收：真实V15父费用净份额全额会计确认、独立会计额度、提交预占/独立批准/真实记账、系统未结项及完整会计反向。实际JSON 352，Schema执行 2039；原0.19.0失败记录保留。AP01仍部分实现，AP02及全V4未完成。 见[应付会计确认](docs/finance-payables-ap01.md)。

## 0.20.0 稳定纳税主体组织登记

0.20.0稳定纳税主体组织登记（`V19__tax_entity_registry.sql`）已验收：组织声明稳定纳税主体身份、治理归属账套、原件字节快照、独立自然人审签、本地停用与恢复及跨账套已知ID只读；实际JSON 202，Schema执行 1040，25项只读SQL完整性检查0异常。非政府验真，不含税额计算、优惠资格、申报或缴款。见[稳定纳税主体组织登记](docs/finance-tax-entities.md)。

## 0.21.0–0.22.1 六条V4并行通道完整运行时集成与保数据升级受限验收

`0.21.0`–`0.22.1`（历史安装制品 `finance@0.22.1`，SHA-256 `4f6c8987a318e9770a10e6f25b93084bbe7a3cab9c81ba159aacc91bfa8be9eb`）已完成六条 V4 并行候选通道（`V20`–`V24` 共新增 37 张业务表，总计 170 张财务业务表）全量合入正式 `plugins/finance/` 源码，并在独立 QA 库 `lumira_qa_finance_web` 完成保数据升级（`PRESERVE`）与全链路闭环验收：
- **销售应收全链路（AR01/AR02，`V20`+`V23`）**：独立会计/税务判断审签（`/receivables/decisions`）、合同与履约确认、无条件应收收入确认、人工收款登记、核销分配及全额反向冲销，同连接产出原生 `AR_REVENUE` / `AR_RECEIPT` / `AR_ALLOCATION` / `AR_REVERSAL` 凭证（`bankChannel=NOT_VERIFIED`）。详见[销售应收受限确认](docs/finance-receivables-ar01.md)。
- **固定/无形资产子账全链路（AS01/AS02，`V21`+`V23`）**：原件购置配额独立审批（`/assets/quotas`）、卡片拆分取得、直线法按月折旧/摊销、未来估计变更、处置会计、全额冲销、原生 `ASSET_*` 凭证记账及子账与总账实时净额对账（`0.22.1` 修正八位定点零值 `toPlainString()` 输出）。详见[资产子账说明](docs/finance-assets.md)。
- **人员工资与个税累计预扣全链路（PAY01/PAY02，`V22`+`V23`）**：8 张强制租户 RLS 表支撑员工雇佣登记、7 级规则包独立审签发布、跨月跳档累计预扣计算（`360.00` → `1020.00`）、自然人复核/批准/锁定及计提凭证与实发/扣缴/申报 4 类核验事实绑定（`PAY03`/`PAY04` 未包含）。详见[工资与累计预扣说明](docs/finance-payroll.md)。
- **跨账套纳税申报义务与回执核销（TAX01/TAX05/TAX06，`V24`）**：账套税务来源绑定独立批准、稳定义务水位冻结、三人自然人职责分离、人工 `UNKNOWN` 交接槽位与真实税务回执原件 SHA-256 核销（`officialCapability=NOT_VERIFIED`、`paymentState=NOT_ASSESSED`）。详见[税务义务说明](docs/finance-tax-obligations.md)。
- **电子档案与确定性离线导出包（ARC01/ARC03，`V24`）**：独立 `platform:finance:export` 权限、范围水位预检、自然人审签、`DIAGNOSTIC` 确定性 POSIX ustar 包生成及浏览器 WebCrypto / Node `archive-package.mjs` 双端离线 SHA-256 复核（`legalArchiveComplete=false`，`ARCHIVE`/`EXIT` 在法定三表未齐前返回 `422` 阻断）。详见[电子档案包](docs/finance-archive.md)。
- **受限三表取数底稿与法定门禁（RP01/RPT02–RPT05，`V24`）**：科目语义映射独立审批与一致水位总账 `ReportSourceEngine` 底稿试算（返回 `BLOCKED_DRAFT`，自动排除损益结转分录），正式 `/reports` 继续返回 `RULE_VERSION_UNAVAILABLE` 阻断法定发布。详见[受限报表包](docs/finance-reports-package.md)。
- **全量验证结果**：24 个已发布迁移身份与 SHA-256 冻结校验通过；321 项后端 JUnit + PostgreSQL 18 测试（62 个套件）、5 个领域程序、162 项前端/归档测试、完整 TypeScript 类型检查与构建全部通过；CAS/SMALL 双制度真实 HTTP（308 次请求）与真实 Chromium 浏览器验收（69 次 JSON 请求、2 次归档 tar 下载哈希复核、1440px 与 758px 共 24 张截图、0 异常）；377 份唯一实际 JSON 通过封闭 OpenAPI Schema 共 609 次校验执行 0 错误（覆盖 74 条正向路由）；13 项只读 SQL 完整性对账 0 异常；升级前 133 张表 8,465 行及 0.22.1 升级前 170 张表 9,539 行历史数据哈希 100% 完整保留。

## 0.24.2 全量 84 项 V4 需求验收与 37 个迁移运行时保数据升级

`0.24.2`（当前安装制品 `finance@0.24.2`，SHA-256 `5682220e8d7a054fb767403f07a688c32adb8c763a28e224688b7a1f57c7d71f`）已完成全量 84 项 V4 需求全量合入正式 `plugins/finance/` 源码，并在专属 QA 库 `lumira_qa_finance_web` 完成保数据升级（`PRESERVE`，操作 ID `preserve: 0738c08c-968a-48f6-9dc4-ec3695a416e5`，`install: 3726cd61-303b-415c-a9aa-0651a059a316`）与全链路闭环验收：
- **全量 84 项需求闭环**：ORG01–PLT03 全部 84 项需求已实现并验收，管理 336 张财务业务表与 43 个 Spring 控制器，支撑 CAS 2019 与 SMALL 2011 双准则端到端闭环。
- **全量 37 项迁移与保数据升级**：全量 37 项 Flyway 迁移（`V1`–`V37`）全部校验通过，完整保留 174 张历史业务表（11,450 行数据）及 133 张 pre-V20 表（8,465 行数据）的行级哈希不变。
- **四层网关验证全部通过**：
  - 自动化测试：546 项 JUnit 测试 + 282 项 Node 测试全部通过（0 失败/错误/跳过）。
  - 真实 HTTP 接口：双制度账套执行 677 次真实 HTTP 请求，断言通过率 100%，意外 5xx 为 0。
  - 双视口 Chromium 浏览器：覆盖全部 22 个面板，在 1440px 与 758px 双视口下完成 88/88 项检查，0 错误。
  - 封闭 OpenAPI 校验：677 份实际 JSON 通过封闭 Schema 校验（0 开放 Schema，0 未匹配路由，0 错误）。
  - 只读 SQL 完整性：12 项核心不变量检查全部通过（12/12），0 异常。
- **100% 诚实外部边界声明**：税局直联/税款缴纳/乐企开票/银企直联保持 `NOT_VERIFIED` / `NOT_ASSESSED`；客户端统一为 `WEB_ONLY_CLIENT`；云灾备保持 `LOCAL_DRILL_ONLY`；客户往来严格执行 `ANTI_CRM_ENFORCED`；AI 助手保持 `DRAFT_COPILOT_ONLY`。

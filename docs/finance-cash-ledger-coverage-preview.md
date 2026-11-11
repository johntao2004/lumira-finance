# 受限账务现金行覆盖差异预览（0.10 已验收的受限切片）

0.10 已在独立财务 QA 环境完成构建、数据库及接口测试、备份恢复和 PRESERVE 保数据安装，实际 HTTP 与浏览器的受限流程均通过。精确合同见 [OpenAPI](finance-api.openapi.json)。已验收 0.9 的逐项分配证据单独保留，不用来代替新增预览验收。此片仅一个只读 GET，不创建、提交或批准工作包，不新增持久消费者、usage 或报表资格。

## 观察什么

预览限定一个账套年度、一个已批准单工具范围修订、完整自然月、CNY 和单法人个别口径。它从该范围全部 INCLUDED 科目的 POSTED 账务行枚举总体，再关联实际有效匹配、人审及分配。不是从已登记来源、已匹配行或已支持分配反推总体。

总体使用独立宽查询，允许 posting 缺失或序号为空的行继续可见；旧精度、非单边、金额异常、范围/科目/原件元数据失效和复杂事实不能被原匹配选择器过滤掉。若范围缺失、不属当前授权或不适用，返回明确错误，不把取数失败显示为 0 行。范围之外的账户、实际银行文件及其全部行仍未证明齐备。

每条账务行只有一个主阶段及多个问题，避免一对多关联或多标签把同一金额重复计算。正反更正凭证按实际日期各记一次，旧 RELEASED/SUPERSEDED 历史只用于追溯，不重复贡献当前分配。永久 VOIDED Raw 的实际有效更正可形成真实匹配链。

## 阶段、金额与无法判断

主阶段按以下优先级选择：UNSUPPORTED_LEDGER_FACT、SCOPE_LINEAGE_MISMATCH、NO_ACTIVE_CLAIM、NO_EFFECTIVE_REVIEW、NO_EFFECTIVE_ALLOCATION、DEPENDENCY_PENDING、SUPPORTED_CHAIN。任何 BLOCKING 或 JUDGMENT_REQUIRED 问题都禁止 SUPPORTED_CHAIN，也不能计入 supported 收/付金额。

复杂或疑似内部划转即使有完整普通匹配链，仍是不支持、待判断的事实，不自动当作外部收付或配对。范围、账户和证据元数据异常归范围链路差异；缺匹配/人审/分配归对应阶段；待审释放或替换归 pending。当前信赖仍有效和允许新增下游使用是不同事实，pending 不能被说成旧批准已经不存在。

金额使用精确十进制字符串，分别列借方、贷方、已知方向收入和支出，不把收 100、付 100 净成零差异。正常 V4 行保留八位金额，旧 LEGACY_6 行保留六位标识并作受限诊断，不自动四舍五入迁移。无法读取或非有限的借贷值为 null，方向 UNKNOWN、标准金额 null；对应整项原始借/贷总额也为 null，不能用已知子集冒充完整合计。方向合计只统计已知行，另列未知数量和 PARTIAL 状态。

同一行仅进入一个阶段汇总；过滤只改变显示数量及分页明细，不缩小总体、总额或结果判断。中间合计使用任意精度，超过输出容量报错，不截断、补其他或写回凭证。

## 局部支持不是完整现金覆盖

响应固定 externalSourceCompleteness=MISSING、coverageVerified=false、formalReportContributionEnabled=false、reportPublishable=false。即使所有范围内账务行均具支持链，或本范围确知零行，也只能说明本次受限账务观察，不能整体标绿为现金完整、银行对账完成、法定现金流量表完成。

真实完整文件、全部账户、未入账外部现金及跨范围变动尚未建立双向总体。普通政策目录也不是法定模板专业发布。历史 ACTIVE 分配所用政策后来退役不会使其自动消失；新政策使用资格仍由原写流程控制，预览不重新激活或重算历史。

## 当前性、分页与公开授权

每页在单个只读 REPEATABLE_READ 事务内取得总体、摘要、水位、指纹和明细，仅代表该时点观察。后续页重新计算完整上下文，变化返回 CASH_COVERAGE_PREVIEW_STALE，调用方须丢弃整组旧页后显式重取；不能静默拼接新旧页或把错误替换成空表。

上下文包括实际 scope 自身版本/摘要、账务/登记/匹配/人审/分配/政策水位、全部总体行与异常、相关当前/草稿/已终结请求的 scalar 摘要、账户及原件元数据。不能只靠 postingSeq 或有效性计数器；新增后取消的提案也会改变指纹。全年其他月份变化可保守使本月观察过期，不承诺月度精确增量快照。

访问要求当前 access/read、账套成员和年度范围。授权摘要仅来自固定 SDK 的 AdministrationPort 和 AuthorizationContextProjectionPort.current(principalName) 已验证 Snapshot，包含账号 UUID、有效权限、preview/目标角色及当前财务成员/年度 grant 的规范摘要。公开 Snapshot 没有 revision getter；宿主每请求另校验其 revision，不读取 identity 内部表或相信客户端租户头。公开快照缺失时 fail closed，不以空摘要继续。

游标绑定 actor、授权摘要、tenant/book/year、scope、月份、过滤与 limit、上下文及真实末行锚点。当前无权优先 403，跨身份/范围/筛选错配 400，仍有 read 但授权上下文或事实改变则 409。游标不是授权令牌、签章或永久批准快照。已关闭年度/月可按现时权限只读诊断，响应显示真实 yearStatus/periodStatus，不因此开放写。

## 资源、原件与接口

接口为 `GET /api/finance/v1/books/{book}/years/{year}/cashflow/ledger-coverage-preview`。必填 scopeRevisionId；可选 limit、cursor、primaryStage，拒绝未知/重复 query 和 request body，不接受自行改月或缩小科目总体。默认分页 20、最多 100；完整总体最多 5000 行、相关 workflow scalar 元组 50000、不同原件元数据 10000；每类 pending ID 数组最多 100、每行 issue 最多 32。越界返回 PREVIEW_LIMIT_EXCEEDED，不截断后称完整。

查询只投影必要 scalar 与摘要，不装载全部 content JSON 或原件 bytes。原件状态仅去重读取存在性、版本及不可变权威 SHA，不在预览逐页重新 hash 字节，因此不声称重新检验存储损坏或银行真实性。已有原件下载继续单独授权和校验字节。

合同提供 16 个闭合定义，所有响应字段必填，空值仅限明确 nullable；版本/计数为 JSON 整数，金额为字符串。错误包括 400 INVALID_INPUT、403 SCOPE_DENIED、409 CASH_COVERAGE_SCOPE_UNAVAILABLE/CASH_COVERAGE_PREVIEW_STALE、422 PREVIEW_LIMIT_EXCEEDED/PREVIEW_TOTAL_OVERFLOW；宿主认证错误保留宿主响应边界。

## 验证状态与未完成能力

当前已有下列独立后端、构建、恢复、安装及 HTTP 证据，浏览器验证见下方最终收口。静态合同检查与实际验证分别记录；受控 PostgreSQL 测试覆盖异常事实，真实 HTTP 另验证账务总体与分页/权限边界，不将这些证据自动扩大为全部外部现金完整或生产容量通过。

没有实际消费者和受控释放协议，本预览不能称冻结批准工作包。银行真实性、外部总体、内转/复杂交易、余额桥、净利润调节、编制底稿、专业模板发布、完整三表、归档、自然人职责分离及数据库 RLS 仍有独立缺口。

范围错误边界：scope UUID 不存在或不属于当前 tenant/book/year 时按通用隔离边界返回 403 SCOPE_DENIED，不揭露外部 scope 是否存在；本已授权范围内实际找到的 scope 未批准或资格不支持，才返回 409 CASH_COVERAGE_SCOPE_UNAVAILABLE。两者均不得改成成功零行。

## 已完成的 QA 验证

[后端验证记录](../.runtime/cash-ledger-coverage-backend-validation.json)与[汇总记录](../.runtime/cash-ledger-coverage-runtime-validation.json)记录：102 个后端测试、22 个套件全部通过，无失败、错误或跳过，含新增 6 个 Coverage PostgreSQL 测试和 3 个 Controller 测试；3 个 Java 领域程序、53 个前端轻量测试、完整类型检查和统一插件构建通过。类型检查工作区前端源码逐字节核对一致。未新增 V11，已发布 V1–V10 原身份与字节保持。

本轮不是首次执行即全通过：Controller 的重复 query 负例最初实际只添加了单个 cursor/filter 值，改成真实重复参数后重跑；前端 unknown 类型进入 JSX 的展示位置补类型 guard 后完成类型检查。生产 query 校验和业务限制未为测试放宽。静审发现的外部 sourceDocumentVersion 与服务端 originalSnapshot.version 混用也在 PostgreSQL 测试前修正，现使用原件真实版本；不能将这些修正记录抹成首轮无问题。

同一后端证据另记录 5000 行总体、返回 page 100、测试 JVM heap 384 MiB 的预览耗时 3031 ms，实际 EXPLAIN ANALYZE/BUFFERS 保存于 `.runtime/cash-coverage-query-plan.txt`。这是该 QA 样本下的受限预算测试，不是生产并发、所有账务分布或容量认证。

[备份恢复证据](../.runtime/coverage-qa-backup-evidence.json)记录升级前 0.9 数据库备份 1094188 字节、归档目录有效，并实际恢复到新建隔离财务 QA 数据库。全部 56 张财务业务表指纹一致、迁移历史保留，恢复后数据库原件哈希异常数为 0。恢复范围仅数据库及其中财务原件，不包含外部上传存储、整机灾备或生产恢复承诺。

[实际安装记录](../.runtime/coverage-candidate-release.json)确认 0.9.0→0.10.0 的 PRESERVE 卸载与安装均 SUCCEEDED，安装包 417122 字节，SHA-256 `e877cee0bee896f65c06f8127aefa2f47165a981e30aed38feb84229cdbbf180`。没有新迁移；V1–V10 历史保持，旧 0.9 最终证据未重跑或覆盖。较早后端报告的未部署是该阶段事实，后续 QA 安装由该独立交付记录证明。

### 真实 HTTP 与阶段副作用边界

[实际 HTTP 证据](../.runtime/coverage-live-evidence.json)在 CAS、SMALL 各自完整自然月观察到 3 条已记账现金行：2 条无 ACTIVE claim、1 条真实人审及有效分配，证明总体不是只从已登记或匹配行反推。CAS 支持行来自 Raw 永久 VOIDED 的 MATCH_CORRECTION，SMALL 来自 RAW_REGISTRATION。完整三页无重复/遗漏，金额用精确十进制核对，显示筛选保持全范围分母；外部总体 MISSING、全部正式放行标志 false。

[实际闭合 Schema 记录](../.runtime/coverage-openapi-runtime-validation.json)的 54 组 query/JSON 实际负载全部通过 Draft202012 Schema 与 format 校验；该阶段全部为 HTTP，browserCases 为 0、browserComplete 为 false；后续最终计数见下节，不把 54 组称为 54 次独立请求。跨 actor、筛选、limit、未知/重复 query、非法 cursor 负例均拒绝；外部 scope UUID 为 403 SCOPE_DENIED。

[无财务副作用证据](../.runtime/coverage-financial-noeffects-evidence.json)区分两段：**纯预览 GET 阶段全部 56 张财务业务表前后完全相同**；随后额外使用既有 0.9 命令做受控探针，不归因于 GET 写入。草稿创建/取消未改变 allocationVersion（实际 11→11），但 workflowDigest 改变并使旧 cursor 409；已有有效行仍 SUPPORTED_CHAIN。待审 allocation release 保留 currentChainEffective=true，却 newUseFrozen=true、主阶段 DEPENDENCY_PENDING、supported 收入为 0，取消后恢复原有效链。

权限探针把 READER 改为仍可读取的 MAKER 后，旧页返回 CASH_COVERAGE_PREVIEW_STALE；禁用成员后旧 cursor 返回 403，finally 已恢复原 grant 并记录清理完成。额外探针实际变化仅为声明的 allocation、line、guard、release、outbox、业务 audit 和 idempotency 表；成员临时变更后的最终状态已恢复，其他表无未声明变化。探针后另存全部 56 表基线，供后续浏览器只读验收比较。

同一最终关系核对的 10 类 typed/deferred 异常计数全部为 0，V1–V10 历史与预览前一致。HTTP 阶段当时汇总为 `http=true`、`browser=false`；后续浏览器最终结果见下节，阶段证据不混计。不存在数据库 RLS、自然人职责分离、完整外部总体、冻结批准工作包或正式三表完成声明。

### 浏览器最终收口

[浏览器证据](../.runtime/coverage-browser-evidence.json)已完成 CAS 与 SMALL 的真实只读操作：各自仍为 3 条总体、1 条实际支持链，另 2 条无 ACTIVE claim；完成 11 次实体详情下钻及 14 次原件真实下载，SHA 均与已存摘要相同。浏览器阶段 finance 业务写请求为 0；[最终无副作用证据](../.runtime/coverage-financial-noeffects-evidence.json)确认全部 56 业务表与 HTTP 控制探针后的基线等值、10 类 typed 关系异常仍全部为 0、V1–V10 历史不变。

[最终实际 Schema 记录](../.runtime/coverage-openapi-runtime-validation.json)共 86 组真实服务端负载（54 HTTP＋32 browser）通过；另有 2 组 synthetic UI query/response 单独校验计数，不冒充真实服务端返回或加成真实 88 组。Synthetic UNKNOWN 200、STALE 409 及 403 用于验证未知金额不显示为零、过期丢弃/显式重取、撤权回收当前视图；全部模拟路由已撤销，恢复真实返回，最终浏览器离开至 about:blank。

758 与 1440 宽度下完成视觉检查。用途列宽与固定布局修正为用途列 210、fixed 布局、表格横向内容宽 1610，稳定后的对话框 opacity 为 1，验证窄屏可横向访问且详情不半透明。重新取数时，自动化 helper 曾使用 accessible name 精确匹配；离场图标残留的 loading 名称导致未定位并未实际点击按钮。按可见按钮文字定位后，真实 GET 200 正常恢复。该定位问题不计作产品重试故障，恢复与路由清理均有记录。

[汇总记录](../.runtime/cash-ledger-coverage-runtime-validation.json)现为 `http=true`、`browser=true`，只建立本受限切片验收。56 表零写入、关系健康或 UI 合格不等于外部来源齐备、银行验真、百万规模性能、批准冻结工作包、RLS、自然人职责分离、完整三表或完整 V4 完成；固定 MISSING 与 false 放行标志继续保留。

# 普通现金逐项分配（0.9 QA 验收）

0.9.0 已在独立财务 QA 环境完成构建、数据库约束测试、备份恢复、PRESERVE 保数据升级，以及两制度真实 HTTP 和 CAS 浏览器闭环。范围限于普通用途分配、独立释放和原子替换，完整 V4 仍在开发。精确闭合 DTO 与操作权限见 [OpenAPI](finance-api.openapi.json)。

## 实际依赖与支持范围

分配依赖已批准的来源人审、真实 ACTIVE SOURCE_REVIEW usage 和当前有效 claim。普通 REGISTERED 来源及永久 VOIDED Raw 的已批准匹配更正都通过同一实际 claim 进入。冻结 review/version/digest、真实 usage、claim ID/kind/generation/digest、完整现金腿版本/过账水位，以及实际范围、账户、日期、方向和全额。客户端 expectedParent 仅为并发绑定。

本片不扩展 V9 usage 成任意多态消费者；另建真实 allocation dependency，外键指向实际人审/usage/claim 和实际分配实体，配合反向 effect pointer 与提交时状态约束。原 Raw 仍 UNREVIEWED/UNCLASSIFIED/不贡献报表。分配不生成凭证、付款、过账、期初或现金事实，不出具覆盖完整性、工作底稿或正式三表。

## 组件、用途与政策

每笔完整来源分为 1 至 20 个正 CNY 组件，输入小数最多两位，精确合计等于已复核来源全额。100=60+40 合法；零、负数、反方向、重复 componentKey、少分、多分或 OTHER 补差均拒绝。同一叶子可有多个不同真实组件；一份真实原件可支持多个组件，各自保留说明、金额和用途确认，不要求把同一 PDF 重复上传。

初片仅同来源 kind 的一个明确普通用途。供应商、工资或税标签不证明经营性质；每个组件须实际用途证据。混合经营/资本用途、不明税款或扣减、资本化工资/利息、退款、内部转账、外币、控制权变化及净额结算尚不支持。

服务端按实际发布政策派生准则、模板、目录版本、方向、语义叶子和行号，并冻结确切政策内容、生命周期版本及原件字节摘要。客户端不能提交任意报表行。V8 ordinary-cash-policy-dictionary/1 不是法定模板专业批准。政策必须属于同实体/账套/年度/个别口径/CNY/交易形式/kind/用途，实际现金日期须落包含首尾日的有效区间，不以今天替代现金日期。

新建、提交及新批准/替换使用当前 APPROVED 且无待审退役的政策。既有有效分配保留原批准快照，政策后来退役或进入退役待审不会使它自动失效或重算。分配释放也不要求历史政策继续发布。父人审/usage/claim 当前有效与历史政策保留是不同条件。

## 制备、审签与过时提案

内容不可编辑。现任 OWNER/MAKER 的 access/create 可制备；原分配制备人可提交或取消。DRAFT 提交后为 SUBMITTED；批准仅接受 SUBMITTED。当前独立 OWNER/REVIEWER 的 access/read/approve 可批准，UUID 不同于 Raw 制备人及本分配制备人/最后编辑者，并复核现时分配制备人权限。

不额外排除已独立批准事实的历史父人审制备人；这不构成三个自然人控制。独立性仅按账号 UUID，未验证同自然人双账号。

当前独立审批人可驳回 DRAFT 或 SUBMITTED，以版本/摘要/冻结父引用和理由终结失权或过时内容，不要求旧 maker、父 claim 或政策仍新鲜。草稿驳回不伪造提交时间和到期时间。七天（7×24 小时）是运营审签时限，不是法律期限；已接受历史不随待审期限经过消失。真实来源年月 OPEN 与 V4 精度仍是写门禁。

## 独立释放与交接

现任 OWNER 的 access/manage/create 可请求释放旧有效分配，支持原制备人离职后的交接。独立批准需 access/read/approve，排除请求人、Raw 制备人及分配制备人/最后编辑者，并检查当前请求人权限，不要求旧 maker 继续在岗。

待审释放保留 APPROVED、ACTIVE dependency 和 currentEffective，冻结未来新增依赖。批准释放后仅分配及其真实 dependency 变为 RELEASED，保留实际 typed 决策历史，不释放人审、SOURCE_REVIEW usage 或 claim。驳回/取消保持旧有效状态。不能靠任意 UUID 或客户端计数声称没有依赖；未来底稿/报表消费者须先增加真实外键和释放协议。

释放后可对同一仍有效父人审新建分配，存在明确空档；若期间父人审或 claim 已合法释放，新提案过时，不自动重定向。

## 真正的原子替换

替换候选显式引用旧有效分配 ID、版本和摘要，必须保持相同人审、usage、claim 和代次。它可与旧有效分配同时存在，但普通 approve 不允许激活，必须走真实 replacement request。新制备人提交替换候选后申请，绑定旧 dependency、旧/新版本摘要和父引用。

替换独立审批人须不同于 Raw 制备人、旧/新分配制备人及请求人，检查当前新制备人和请求人权限，不重验旧 maker 是否仍在岗。批准同事务使旧分配 SUPERSEDED、旧 dependency RELEASED，新分配 APPROVED、新 dependency ACTIVE，并将真实 effect pointer 指向新分配，无提交后的双计或空档。实际 replacement decision 与不可变 activation receipt 绑定这对旧/新实体，不是任意关联 UUID。

历史替换校验允许新分配以后再合法 RELEASED 或 SUPERSEDED，不要求它永远 APPROVED；原激活凭据与当前有效状态分开。拒绝、取消或过期不会改变旧有效分配，新候选仍可明确终结。待审释放与待审替换互斥。本片实际后端、HTTP 与浏览器均验证了闭环后才开放独立替换批准，未以静态接口声明代替运行验收。

## 数据库、事务与读取

V10 新增 typed dependency、反向 effect pointer、释放/替换决策和不可变激活凭据。双向延迟提交约束拒绝缺 dependency 的批准分配、指向草稿的 dependency、错误范围/代次/内容摘要、没有真实独立决策的释放以及虚构替换。父人审释放必须增加实际 dependency 的 API 与数据库门禁，不能只看旧 usage 计数；不修改 V1–V9 已发布文件。

共享父 Raw/claim/人审锁序使新用与父人审释放、匹配撤回、作废竞争；政策 guard 保护新批准与退役竞争。两个替换或释放与替换必须只提交一个合法结果。任意直接 SQL 仍可能死锁回滚，不声称普遍无死锁。

allocationVersion 与匹配、人审、政策和财务水位区分；一次原子替换只增一次效果水位。待审释放/替换及其驳回/取消若改变新依赖资格，也按真实事务递增。信封记录最终水位，仅为本地未投递事务证据，无消费者或成功通知承诺。

回放先验证当前范围、权限和实际 OPEN 年月，返回当前权威状态；旧 approve 不复活已释放或被替换分配。批准回放检查当前 maker，释放批准检查当前请求人，替换批准检查当前新 maker/请求人。详情区分历史批准、当前 dependency/effect 和待审请求。

## 接口与验收边界

基址 `/api/finance/v1/books/{book}/years/{year}/cashflow/allocations`，提供父人审选择、列表/详情、制备及 submit/approve/reject/cancel，释放请求与历史/审签，以及真实替换请求与历史/审签，共 18 操作。创建上限 100000 字节，其余写动作 20000 字节；分页 limit 1 至 100。金额 scale/overflow 使用既有 INVALID_CURRENCY_SCALE/AMOUNT_OVERFLOW，非字符串、零、负数或非法格式拒绝；精确合计不符为冲突。

实际 HTTP 闭 Schema 与浏览器流程分别通过，证据见下文。完整现金覆盖、专项交易、专业模板发布、底稿冻结、正式三表、归档及完整 V4 仍未完成。

## 已验证事实与待验项

[后端验证记录](../.runtime/cash-allocation-backend-validation.json)及[汇总记录](../.runtime/cash-allocation-runtime-validation.json)记录：93 个后端测试、20 个套件全部通过，无失败、错误或跳过；3 个 Java 领域程序、46 个前端轻量测试通过，完整类型检查和最终构建通过。类型检查工作区的前端源码与最终来源逐字节一致；已发布 V1–V9 迁移字节保护通过，最终组装另核对包含 V10 的迁移字节。本轮不是首次执行即全通过。初始夹具的随机 policyCode 含小写，被已有格式规则正确拒绝，后改为大写；交接夹具缺真实 OWNER 成员导致 403，补齐真实成员；原件上传晚于事实指纹基线，调整夹具取基线时序。`expirationAllowsTerminationAndDatabaseRejectsRealDraftConsumer` 的提交已按预期失败，但 cause 断言仅遍历 RuntimeException，修为完整 Throwable 链后确认 `CASH_ALLOCATION_NON_EFFECTIVE_HAS_DEPENDENCY`。另修正新增测试 lambda 捕获可变局部变量的编译问题。生产约束未为测试放宽；旧失败报告被后续 JUnit 结果覆盖，最终汇总仅证明修正后的重跑结果。

后端验证还覆盖真实 typed/deferred 约束与合法后续链 `A→B→C→releaseC→releaseParent`，保留三份激活凭据和 dependency，R1/R2 历史批准回放仍成立。此处是后端测试证据，不等于真实浏览器完成这些操作。较早后端报告的 `deploymentPerformed=false` 是该阶段事实；后续实际安装见独立交付记录。

[QA 备份与恢复证据](../.runtime/allocation-qa-backup-evidence.json)记录升级前数据库备份 885786 字节、归档目录可读，并实际恢复到新建的独立临时财务 QA 数据库。恢复后 30 张表指纹全部一致，17 份 PostgreSQL 内原件的字节 SHA 汇总一致，Flyway V1–V9 历史及校验和保留。该证据限于数据库恢复及其中保存的财务原件，不建立外部上传存储恢复、整机灾备或生产恢复能力；未更改运行数据库、配置或外部上传目录。

[实际安装记录](../.runtime/allocation-candidate-release.json)记录 0.8.0→0.9.0 的 PRESERVE 卸载与安装均 SUCCEEDED。安装包为 379541 字节，SHA-256 `ea9f9306a3b7c9a0a5b32f6c437fcff00e35f6fd5603b0c02743ced34a74bb68`。[汇总记录](../.runtime/cash-allocation-runtime-validation.json)确认既有 30 张表及旧精度财务/证据指纹保持；V10 实际 Flyway checksum 为 `-1032479335`，文件 SHA-256 为 `fd5e4ed27317ebe618f539a43604b16621408f5410944f56ffbc7a8298ff47f3`。V10 现已应用冻结，不得改写或 repair。

当前汇总已更新为 `http=true`、`browser=true`。HTTP 与浏览器分别记录实际操作证据，不将一方代替另一方。账号 UUID 独立、无银行验真、本地信封无投递消费者等限制继续有效；未宣称零 UI 问题或完整 V4 发布。

### 实际 HTTP 验收

[HTTP 记录](../.runtime/allocation-live-evidence.json)覆盖 CAS 与 SMALL 两制度：精确 full-gross 组件、独立批准、政策退役后旧有效分配及内容保持、政策待退役阻断新替换批准、真实独立原子替换和后继释放。后继 RELEASED 后旧 replacement decision 回放仍 APPROVED，未重新激活已释放分配。CAS 旧 maker 撤权后原批准 key 回放返回 APPROVAL_STALE，现任 OWNER 可按实际权限完成交接替换/释放；释放后同父可新建 DRAFT 并由独立审批人驳回，提交时间及到期时间仍为空。

真实 ACTIVE child dependency 阻断父人审释放；子依赖全部释放后，父释放请求可实际成立，待审期间又阻断新分配，随后独立驳回保留父人审和原 ACTIVE usage/claim。HTTP 还记录调用者报表行注入、三位货币输入、合计不符、只读制备和自审拒绝等负例。宿主权限 403 与财务服务结构化错误按真实响应分别记录，不伪造宿主 traceId/code。

[实际闭合 Schema 记录](../.runtime/allocation-openapi-runtime-validation.json)最终为 290 份实际请求/响应：123 份 HTTP、167 份浏览器，Draft202012 schema 和 format 全部通过。HTTP 独立阶段的 123 份先通过，随后另采浏览器负载并重跑校验。

[无财务副作用与关系健康证据](../.runtime/allocation-financial-noeffects-evidence.json)将阶段分开：主分配流程的 24 张既有底层表指纹相等；政策 6 表的明确退役/继任/待退役驳回动作另记。之后独立 parent probe 只改变声明的 `cash_source_review_guard`、`cash_source_review_release`、`cash_source_review_outbox` 三表的资格水位及历史；其余基础事实保持。parent probe 后 30 张既有表保存为浏览器比较基线；浏览器完成后再次比较全部相等，10 类关系异常仍全部为 0。

同一证据中 10 类 typed/deferred 关系异常计数均为 0，覆盖有效 consumer/dependency/pointer/parent、激活凭据、实际释放/替换决策及精确 gross。**HTTP 阶段终点**保留 6 个 allocation（2 SUPERSEDED、2 RELEASED、2 REJECTED）、4 份 activation receipt、4 个 RELEASED dependency、0 个 ACTIVE dependency 和 0 个 effect pointer；2 个批准的 replacement 及 2 个批准的 release 保留历史。这些是 HTTP 终点历史计数；浏览器另增加其明确创建的两代分配和决策。

### 实际浏览器验收与界面限制

[浏览器证据](../.runtime/allocation-browser-evidence.json)记录 CAS 页面真实创建、提交、独立批准、同父替换候选、替换请求与独立原子批准，再请求并独立批准释放，共 9 次分配域 POST 均为 200。另上传 4 份实际用途原件，9 次下载文件的 SHA-256 全部匹配。`20.000`、`20.005` 及 `19.99+14.56` 在字段齐备时被可读拒绝，均未发出创建请求；合法 `20.00+14.56` 通过。金额初始为空、确认默认未选，自审按钮禁用，替换候选普通批准禁用。

替换待审时旧效果及依赖保留；批准后旧分配为 SUPERSEDED、新分配为 APPROVED，效果指针和真实 dependency 换位。释放待审时新效果仍有效，独立释放后为 RELEASED、当前依赖为空，旧新内容摘要不变。再次打开历史对照仍正确保留旧替换决策；父人审保持 APPROVED 和原 ACTIVE usage。旧命令 key 回放与父释放待审由独立 HTTP 验证，浏览器未用直接注入命令伪称复测。

HTTP 与浏览器结束后的独立 SQL 核对保留 8 个分配（3 SUPERSEDED、3 RELEASED、2 REJECTED）、6 份激活凭据、6 个 RELEASED dependency，ACTIVE dependency 与当前 effect pointer 均为 0。3 份批准的替换和 3 份批准的释放保持真实历史关系；这些是本轮合成 QA 样本计数。

本轮首次导航直接成功；切换账套/年度后首次标签被上下文刷新回凭证，按实际 DOM 重选后继续，未掩盖该观察。synthetic 403 后敏感范围及行数据清空，拦截恢复，浏览器最终停在 about:blank。窄视口的组件用途表头有被长文本挤成竖排的可读性风险；部分截图取得较早，存在弹窗过渡状态。功能闭环不等于全部尺寸的视觉验收，列宽及稳定截图将在后续界面改动中修正并复验。

后续 0.10 已为该表固定列宽和横滚，并在 758px 与宽桌面等待加载及动画稳定后实际复验：用途列 210px，表头横排可读，弹窗不透明。详见[0.10 验收](finance-cash-ledger-coverage-preview.md)。本节保留 0.9 当时观察，不把后续修正倒写成原版已通过视觉检查。

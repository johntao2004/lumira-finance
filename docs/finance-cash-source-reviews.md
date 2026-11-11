# 现金来源事实人工复核

本文记录 `finance@0.8.0` 在独立本机 QA 完成构建、V9 迁移、保数据升级、实际 HTTP 和浏览器验证的有限产品合同；不代表生产或完整 V4 放行。精确 DTO、空值与操作权限见 [OpenAPI](finance-api.openapi.json)。

## 人审结论与原始来源分开

本片记录 HUMAN_DOCUMENT_REVIEW：独立人员查阅实际原件并确认普通来源事实可供后续准备，结论为 SUPPORTED_FOR_PREPARATION。它不证明银行、法律或税务真实性，不决定现金流分类，不依赖已发布分类政策，也不生成分配、覆盖结论、底稿或报表。

Raw 保持原来的 UNREVIEWED/UNCLASSIFIED/不贡献报表标记、内容摘要和永久来源身份。人审不改原始来源、凭证、过账、期初、政策或现金腿占用代次。

依据是实际有效 claim，而不是只看 Raw 状态：RAW_REGISTRATION 对应 REGISTERED Raw；MATCH_CORRECTION 对应永久 VOIDED Raw 的当前已批准更正匹配。两者均可复核。服务端读取并冻结原来源及文件行身份、原件版本/SHA、已批准月度范围及账户快照、真实现金日期/方向/全额、完整 POSTED 现金腿、凭证版本和 postingSeq/digest，绑定精确 claim ID/kind/generation/contentDigest。客户端 expectedClaim 仅用于并发绑定，不是事实来源。

## 资料与范围

制备人须说明真实业务用途、核对事实并提供 1 至 10 份同范围原件，其中至少一份 BUSINESS_PURPOSE_SUPPORT；可附 FACT_CORROBORATION，原件 ID 不重复。服务器取真实字节 SHA、版本与文件名；审批人可按当前权限读取原件。勾选清单或上传文件本身不等于外部认证。

初片仅普通 CNY 正金额毛额事实。未知用途、净额结算、内部转账、退款、外币、范围/控制变化、资本化及不明税款等复杂事项不能靠勾选改成支持结论。普通事实的人审不代表最终应列经营现金；具体分类及分配仍需独立后续能力。

## 生命周期与独立性

内容不可编辑，DRAFT 可提交为 SUBMITTED，独立批准为 APPROVED；独立审批人可将 DRAFT 或 SUBMITTED 驳回为 REJECTED，原人审制备人可取消未生效内容。每个 claim 最多一个待审和一个有效人审。内容变化须新建不可变记录。

现任 OWNER/MAKER 的 access/create 与当前账套年度授权可制备，不要求其就是原 Raw 制备人。批准须 OWNER/REVIEWER 的当前 access/read/approve，审批 UUID 不同于 Raw 制备人、人审制备人及最后编辑者，并复核现时人审制备人 create/年度权限。历史 Raw 制备人是否仍在职或保有 create 不作为批准条件；历史身份仍保留用于独立性与追溯。账号 UUID 独立不证明不同自然人。

批准和提交重查当前 ACTIVE claim、代次、原件、范围、账户及完整已记账事实；待审匹配撤回阻止新增人审依赖。驳回/取消的 expectedClaim 仅绑定原人审冻结快照，不要求该旧 claim 仍活跃，避免过时提案无法终结。驳回仍需当前独立审批权限，但不重验旧制备人权限或旧原件有效性。原制备人离职遗留的 DRAFT 可被独立驳回，解除唯一待审占用后由现任制备人新建；该操作不代填提交时间或审签到期，批准仍只接受 SUBMITTED。

全部写动作要求 V4 年度及实际来源月份 OPEN。七天（7×24 小时）是服务端运营审签期限，不是法律期限；到期不能批准，仍可按权限驳回/取消后新建内容。已批准历史不随待审期限经过而消失。

## 真正的 typed usage

人审批准在同一事务建立真实 SOURCE_REVIEW usage。V9 在保留 V7 已发布文件的基础上，为 usage 加入实际人审实体、完整 claim tuple 和内容摘要的复合外键；人审反向 approvedUsageId 又指回同一实际 usage。提交时约束同时检查人审、usage、claim 及释放决策：没有 usage 的已批准人审、指向草稿或错误摘要/范围/claim 的 usage、仍依赖已释放 claim 的人审不能提交。

V7 关闭的 CHECK(false) 接口只有在真实正反向外键及状态约束安装后才开放。本片没有任意 consumer UUID 或 usage 计数写入接口。V6 旧 downstream_use_count 仍为零，不作为无真实使用的证明。

旧 claim 释放、匹配撤回守卫和服务 unused 查询改为检查 ACTIVE usage；Raw 作废同时有 API 和数据库 ACTIVE usage 守卫。RELEASED usage 历史保留，但不会永久锁死当前容量。存在有效人审时不能作废 Raw 或释放其 claim，旧二进制或直接 SQL 也须受数据库约束。

## 独立释放、离职交接与再次复核

释放不是删除或直接释放 claim。现任 OWNER 的 access/manage/create 可对旧有效人审提出 REQUESTED 释放申请，支持原人审制备人离职后的交接。独立批准须 access/read/approve，且审批人不同于请求人、Raw 制备人及人审制备人/最后编辑者；重验当前请求人权限，不要求历史 Raw 或人审制备人继续在岗。请求人失权时可由独立审批人驳回，现任 OWNER 再提出申请。

pendingRelease 期间人审仍 APPROVED、usage 仍 ACTIVE，currentEffective 仍为 true；它冻结未来新增依赖，不代表旧信赖已失效。独立批准后，人审及其精确 usage 才分别变为 RELEASED；审批历史、usage 和真实释放决策关联永久保留。驳回/取消维持原有效信赖。释放也有七天运营审签期限。

本片没有 allocation/report 消费类型；后续引入依赖必须先加真实 typed 外键、共享锁及释放协议，有效依赖不能被一个客户端无依赖声明绕过。释放人审不自动作废 Raw、撤回匹配或改变记账。

允许同一仍有效 claim 在独立释放旧人审后新建并重新复核，保留明确“当前无人审”的空档。新审批建立新 usage，不复活旧 usage。若空档期间 claim 已被合法释放或更正，新提案过时，必须显式选择当前新 claim；不自动重定向。原子批量替换、已关闭期间及正式报出版本更正尚未支持。

## 事务、读取与回放

可信 API 按账套/年度、现金登记 guard、真实月份、匹配 guard、Raw、来源代次、claim、人审 guard 与实体行顺序串行化。新增使用与 Raw 作废、匹配撤回请求/批准必须争用相同 Raw/claim 锁：先有效使用则撤回被阻；先待审撤回则新使用被阻；先释放 claim 则旧提案过时。任意直接 SQL 仍可能因行锁顺序死锁回滚，不宣称消除所有死锁。

sourceReviewVersion 首值 0；人审批准/释放以及释放 REQUESTED/REJECTED/CANCELLED 的新增依赖资格变化各递增一次，释放批准通过嵌套人审 RELEASED 递增，不双算。匹配和财务水位保持真实各自含义。

Idempotency-Key、If-Match/expectedVersion 须一致，当前授权和 OPEN 门禁先于缓存回放。旧批准 key 在释放后返回当前 RELEASED，不再建立使用。批准回放重验当前人审制备人权限；释放批准回放重验当前请求人权限。历史按当前 read 范围仍可读取。

eligible-effective-claims 返回实际 ACTIVE claim，允许已有人审的 claim 继续用于查看当前及历史；创建另行阻止重复待审/有效人审。游标绑定真实范围、筛选条件与锚点。详情 activeUsageId 仅为真实 ACTIVE usage；释放后为 null，历史批准/释放仍可追溯。本地 outbox 仅保留未投递事务信封，无消费者或成功通知声明。

## API 与验收边界

`/api/finance/v1/books/{book}/years/{year}/cashflow/source-reviews` 提供有效 claim 读取、列表/详情、创建、submit/approve/reject/cancel、release-requests 创建/历史及 approve/reject/cancel，共 13 个操作。创建 body 上限 60000 字节，其余写动作 20000 字节，分页 limit 为 1 至 100。

完整后端回归 79 项全部通过，无失败、错误或跳过；其中新增 8 项真实 PostgreSQL、1 项 Controller 边界测试。另有 3 个领域 Java 程序、36 项前端测试、最终完整类型检查和实际打包通过。重测试与构建采用受限堆、单测试 fork 串行执行。

数据库测试验证真实消费方与反向外键、双方延迟提交约束、缺失 usage 的批准、DRAFT 消费者及伪造状态不能提交；实际使用阻断原登记作废和匹配撤回，释放后的历史不永久锁死旧路径。ROOT 与更正 claim、人审释放后再审、历史 Raw 制备人离职、现任 OWNER 交接、离职未提交草稿的独立驳回、到期终结、并发与回滚均有定向反例。历史迁移兼容夹具仅用于随机测试 schema，生产代码没有缺表或缺列回退。

专属 QA 库先备份并验证归档目录可读，再经 SDK PRESERVE 完成 0.7→0.8 升级，没有重置、repair 或恢复。升级前后原财务事实、原件、审计、幂等及 25 张既有表指纹保持一致。已发布迁移身份和原字节另由[迁移记录](finance-published-migrations.json)及快速校验脚本保护，不替代实际数据库历史验证。

实际 HTTP 在 CAS 的更正来源（Raw 永久 VOIDED）和 SMALL 的 REGISTERED 根来源分别完成事实提案、提交、独立批准、真实使用阻断、独立释放及再次起草。CAS 人审制备人撤权后，旧创建键被拒，现任 OWNER 仍能请求释放、独立审核人能完成交接；旧批准键因现时制备人失权被阻断，历史通过 GET 查阅。SMALL 保留权限时，释放后的旧批准键返回 RELEASED 当前结果，不复活使用。还验证独立驳回 DRAFT 后提交时间及到期时间仍为空，且可重新起草。

事实人审及释放前后 24 张既有财务、范围、来源、匹配和政策表完整指纹一致；真实 usage 是本片有意新增的数据。另单独创建并驳回一条匹配撤回 QA 请求，证明已释放的 usage 历史不阻碍合法撤回请求、待审撤回又阻挡新的人审。这条探针历史单独计入，随后浏览器以探针后的快照再次比对 24 表一致，不把探针新增历史说成没有写入。

浏览器完成八项主动事实检查门禁、实际业务用途原件上传、本人提交及自审禁用、独立批准、OWNER 请求释放及自批禁用、独立批准释放。待释放阶段仍为 currentEffective=true、activeUsageId 非空；释放后为 RELEASED/false/null。独立审核人下载来源、范围和用途三类原件的实际 SHA-256 全部一致。释放后显式再次起草并由独立审核人驳回，提交时间及到期时间保持为空；原 Raw 仍为 VOIDED/UNREVIEWED/UNCLASSIFIED/不入报表，原匹配 claim 同 ID、同代次并保持 ACTIVE。模拟读取 403 后回收范围与敏感数据，随后移除路由并停在空白页；模拟不作为服务端授权测试。

首次登录导航曾短暂显示宿主尚未注册插件页面，一次普通刷新后恢复，功能闭环随后通过。固定基座存在导航先于异步插件注册的加载窗口，并有注册完成后的刷新通知路径；现有观察不足以认定永久加载失败。此观察保留为宿主集成限制，未改写固定 submodule，不宣称零界面问题或生产验收。

共 125 组实际载荷（HTTP 53 组、浏览器 72 组）通过 JSON Schema Draft 2020-12 封闭字段及格式校验；模拟错误响应不计入。该批验收结束时 3 条真实 usage 均为 RELEASED，消费与释放关联检查无异常；后续候选的正常 QA 准备另计。

0.8.0 制品 SHA-256：`3c239abe9fd4be87ae7505fd69a6e602ee32ab9c93bdce0e7f55a82c3ab1f64c`。V9 SHA-256：`bd5ccfba44b3a4c9a86552fb4f5d346cabd5d94d553c16b384f401472d83c9ac`，实际 Flyway checksum `707592962`；V1—V9 已发布文件冻结。

忽略的本地证据包括 `.runtime/cash-source-review-runtime-validation.json`、`cash-source-review-api-validation.json`、`source-review-qa-backup-evidence.json`、`source-review-candidate-release.json`、`source-review-live-evidence.json`、`source-review-browser-evidence.json`、`source-review-openapi-runtime-validation.json`、`source-review-financial-noeffects-evidence.json`、`platform-plugin-initial-navigation-observation.md`及 `.runtime/acceptance/finance-0.8.0/surefire-reports`。备份归档目录检查不代表恢复演练。

专业模板发布、分类政策适用判定、逐项分配、覆盖完整性、现金底稿、正式三表、归档与完整 V4 仍未放行。

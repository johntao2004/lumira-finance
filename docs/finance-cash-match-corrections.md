# 现金腿匹配更正

本文记录 `finance@0.6.0` 已在独立本机 QA 完成构建、迁移、保数据升级、真实 HTTP 和浏览器验证的有限产品合同；不代表生产或完整 V4 放行。精确请求与响应以 [OpenAPI](finance-api.openapi.json) 为准。

## 范围与原始事实

本片仅更正永久 VOIDED、未被下游使用的现金来源登记所匹配的已记账现金腿。原 Raw 的 sourceSystem/sourceMovementKey、文件 SHA 与行身份、金额、方向、日期、账户、资格范围和原内容摘要均保留；Raw 不复活。更正不新增凭证、记账、付款或现金事实。

目标必须是同账套、年度、账户、日期、方向、全额的真实 POSTED 现金腿，绑定实际 journalVersion、postingSeq 和 postingDigest，且不同于原错误匹配。重新核对已批准现金范围、当前账户快照与原件摘要。仅普通 CNY、新精度、OPEN 年度及月份受支持。

## 流程和权限

读取要求平台 access/read 与当前账套、年度授权。提案、提交、取消及请求撤回要求 access/create、OWNER 或 MAKER、当前年度授权，且必须为原 Raw 制备账号；没有管理员代接离职人员流程。

提案内容不可编辑。DRAFT 可提交为 SUBMITTED，或由原制备人取消为 CANCELLED；SUBMITTED 可独立批准为 APPROVED、驳回为 REJECTED，或取消。每个 Raw 仅一个待处理提案。后续提案仍引用同一 Raw，不能靠随机来源键绕过永久身份。

批准和驳回要求 access/approve、OWNER 或 REVIEWER、当前年度授权，审批账号 UUID 不同于原 Raw 制备人及提案制备人。批准重验原制备人当前 create/年度授权、内容版本摘要、源匹配代次、当前范围、原件和实际目标；驳回允许终结原制备人已失权的待审提案。账号 UUID 独立不证明两个账号属于不同自然人。

批准后的匹配不能直接取消。原制备人创建不可编辑撤回请求（REQUESTED），独立审批人批准或驳回；请求待审期间占用仍有效。批准撤回后更正为 WITHDRAWN，释放精确有效 claim，保留审批与撤回历史；驳回保持原有效匹配。撤回成功后可对同一 Raw 新建提案，不修改既有内容。

## 统一占用、历史与水位

V7 新建 typed cash-leg claim，种类为 RAW_REGISTRATION 或 MATCH_CORRECTION。有效 claim 对来源及现金腿分别唯一；复合外键绑定 tenant/book/year、原登记、目标分录行、账户、版本、过账序号及更正身份。claim 只从 ACTIVE 释放为 RELEASED，历史不删除。

迁移仅为现有 REGISTERED Raw 回填 RAW_REGISTRATION 有效 claim，标记 MIGRATION_V7；既有 VOIDED Raw 不伪造活跃或已释放历史。V6 已发布文件不重写。新登记与 VOID 通过 V7 触发器建立或释放根 claim。

sourceMatchGeneration 是单一来源匹配代次，yearMatchVersion 是年度匹配水位，均与财务 postingSeq 区分。批准及释放改变真实匹配代次，不由客户端指定结果。详情同时保留 approvedClaimId/approvedMatchGeneration 历史和 effectiveClaim 当前状态；历史批准不能当作当前占用。

旧 eligible 的 activeRegistrationId 兼容返回占用所属 Raw UUID，即使 Raw 已 VOIDED 而更正有效；新增 activeClaimId、activeClaimKind、activeCorrectionId 说明实际占用。界面应指向更正单。目标快照不保存这些动态占用字段。

## API 与回放

基址为 `/api/finance/v1/books/{book}/years/{year}/cashflow`。`match-corrections` 支持列表、详情、创建；`eligible-cash-legs` 按 originalRegistrationId 选择目标；详情动作包括 submit/approve/reject/cancel、创建及读取 withdrawal-requests、批准及驳回撤回请求。`movement-registrations/{rawId}/effective-match` 读取当前有效匹配。

写请求使用 schemaVersion `cash-match-correction/1`、Idempotency-Key、内容摘要及预期版本/源代次；动作可通过 If-Match 提供预期版本，与 JSON 同时出现必须一致。创建 body 上限 30000 字节，其余写动作 20000 字节。列表 limit 为 1..100，游标长度受限且绑定资源范围；目标游标额外绑定 Raw UUID 和内容摘要。

回放首先验证调用者当前权限、范围、OPEN 年月及原账号独立性。approve/withdrawal-approve 包括旧 key 回放也检查原制备人当前 create/年度权限，失效返回 APPROVAL_STALE；历史仍可 GET。相同 key 不重执行审批或 claim 副作用，返回权威当前状态；因此已撤回更正的旧批准 key 回放也不能重新占用。不同命令或内容复用 key 拒绝。

## 事务及明确限制

可信 API 使用统一账套/年度与现金登记 guard、月份、匹配 guard、Raw 行和目标/claim 行锁序；数据库唯一键、复合外键及触发器共同约束占用。任意直接 SQL 的行锁可能与触发器锁形成死锁，数据库会回滚其中事务；本片不宣称消除任意 SQL 死锁。

当前无下游使用创建端点；usage 表以拒绝写入约束保持关闭。未来分配或其他真实使用必须新增绑定有效 claim 的 typed 消费外键及事务 guard，并阻止作废、更正和撤回，不能使用客户端计数代替。原 Raw 永久 UNREVIEWED/UNCLASSIFIED，不直接贡献报表。资料留存及人审不代表银行验真。

不支持源内容、跨期、外币、退款、内部转账、控制并购、更正链财务重述、已关闭期间处理或离职制备人接管。不提供覆盖完整性、分类分配、正式现金流量表、完整三表或阶段 B 完成声明。

## 验证状态

完整后端回归 62 项全部通过，无失败、错误或跳过；其中新增 8 项更正 PostgreSQL、1 项非空 V6→V7 PostgreSQL 迁移及 1 项 Controller 边界检查。另有 3 个领域 Java 程序、23 项前端测试、完整类型检查和实际打包通过。构建与测试串行，以受限堆及单测试 fork 执行。前端类型检查后的 24 份文件与最终组装版本字节一致。

数据库测试覆盖 CAS/SMALL 重复更正及独立撤回、撤权与旧键重放、封闭整数和目标游标、两提案并发、普通登记与更正争用同一现金行、直接旧程序式 SQL 插入冲突，以及批准或释放后注入失败的事务回滚。非空迁移分别准备 REGISTERED 与 VOIDED 记录，核对原 Raw、审计和幂等响应原字节；只为 REGISTERED 回填有效根 claim，不虚构已作废来源的历史。

专属 QA 数据库备份后验证归档目录可读，通过 SDK PRESERVE 完成 0.5→0.6 升级，没有重置、repair 或恢复数据库。既有财务事实、原件字节、审计、幂等、来源链、现金范围、来源登记和登记守卫指纹均不变。实际应用库的 REGISTERED 1 条回填为 MIGRATION_V7 根 claim 1 条，VOIDED 根有效占用为 0 条。

CAS、SMALL 实际 HTTP 分别完成提案、提交、独立批准、统一占用查询、待审撤回保持占用、独立批准撤回、旧批准键返回 WITHDRAWN 当前结果，以及后续提案取消。未知来源事实覆盖和越权写入被拒绝。测试所需目标凭证通过正常制单、独立审签和记账预先准备；更正流程开始后的 9 张完整财务、范围及原始来源表指纹不变。

浏览器以 CAS 原作废来源完成真实提案、提交、独立批准、撤回申请和独立批准撤回，5 个写请求均成功。编制人自审及自撤回审批按钮禁用；独立审核人下载实际原件的哈希与保存快照一致。最终界面及 GET 均为 WITHDRAWN，历史批准仍保留，当前无有效 claim，原 Raw 仍为 VOIDED 且摘要未变。模拟新面板读取 403 后账套、年度、旧行及详情一起回收；该模拟只验证界面反应，实际服务端授权由 HTTP 和数据库测试覆盖。浏览器停留空白页并移除模拟路由。

共 67 组实际载荷（HTTP 47 组、浏览器 20 组）通过 JSON Schema Draft 2020-12 封闭字段与格式校验，模拟错误响应不计入实际成功载荷。完整浏览器流程后再核对，9 表财务、范围及原始来源指纹，以及既有登记守卫、稳定来源链和不可变事件信封仍一致。

0.6.0 制品 SHA-256：`2e4edd05983e8f1752d39618150fecb6b24e691fb777457c794dca3db00c37f0`。V7 SHA-256：`4b86d920d8ad9d3bb918a59d0e6d5fdc9358cbf5885fce7df333a15c9838cd99`，实际 Flyway checksum `-1410943516`；已发布 V1—V7 均冻结。

忽略的本地证据包括 `.runtime/cash-match-runtime-validation.json`、`cash-match-api-validation.json`、`correction-qa-backup-evidence.json`、`correction-candidate-release.json`、`correction-live-evidence.json`、`correction-browser-evidence.json`、`correction-openapi-runtime-validation.json`、`correction-financial-noeffects-evidence.json`，以及 `.runtime/acceptance/finance-0.6.0/surefire-reports`。备份目录检查没有代替恢复演练，开发机通过没有代替生产容量与多端验收。

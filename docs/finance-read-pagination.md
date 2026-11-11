# 凭证与科目明细账分页（0.11 已验收）

0.11 已完成后端、领域、前端轻测、类型检查、构建、备份恢复、保数据安装及真实 HTTP、浏览器验收。本文及 [OpenAPI](finance-api.openapi.json) 记录本片接口边界，已验收 0.10 的历史证据保持独立。

## 兼容边界

新增 `GET /api/finance/v1/books/{book}/years/{year}/journal-pages` 和 `ledger-pages`。新 UI 将凭证、单科目明细账切换为服务端有界分页；旧 journals、ledger 数组接口仍保留原形状、排序及最多 200 条，不悄悄更换为分页对象，也不改成无限读取。其他 members、plans 或全平台枚举上限不在本片，不宣称全部移除 200。

旧 200 上限是展示枚举缺口：UI 只能在已截断数组里本地翻页，较晚记录可能无法列表访问。实际 trial 汇总全部已记账行及正式期初，月结全量检查待记账项，年结用完整试算，不从这些数组相加；本片不改变权威核算、试算或月年结规则，不把列表页金额当年度金额。

## 查询与稳定行身份

journal-pages 返回真实凭证摘要，可选 status、成对 dateFrom/dateTo、keyword、limit、cursor。ledger-pages 必填真实年度 accountId，可选成对日期、limit、cursor，只枚举 POSTED 行；停用历史科目可读，不套现金范围资格。日期默认全年，显式范围必须同年、包含边界且起止合法。拒绝未知、重复或无值 query，不接受任意排序/SQL字段。

Journal 固定按 date、id 升序；Ledger 固定按 date、journalId、lineNo 升序。凭证号只用于显示，不是唯一分页键。账簿行以 journalId＋lineNo 唯一，合法的同凭证同科目多行全部保留，修复旧 UI 仅 journalId 重复 key 的问题。旧数组排序及字段不受影响。

下一页用真实末键向后 seek，上一页用首键反向 seek 后恢复升序；每次最多 100 条，默认 20。hasNext/hasPrevious 及 token 根据有界 limit＋1/存在性判断，不跑全表 COUNT，不用深页 OFFSET。returnedCount 是当前页数；totalCount 固定 null、totalStatus=NOT_COMPUTED，未知总数不能显示为 0、200、最高凭证号或已看页数。

UI 仅保存当前页与前后 token，或最多五页可淘汰缓存，不全年 fetch-all 或永久无限累加。范围、筛选、limit、授权或失效变化须清空页面/token 并取消旧请求，不能让旧响应覆盖新上下文。查看/处理仍通过既有凭证详情和独立写权限、版本检查。

## 关键词与资源预算

仅 journal-pages 提供不区分大小写的 ILIKE literal contains，使用部署数据库的确定性 collation，不改原财务文本。`%`、`_`、反斜杠均逐字转义，不是 wildcard、regex 或任意 SQL。空串归无筛选，前后空格仍为真实文字，不能 trim 后偷偷变更含义；拒绝 NUL/不可表示字符串。

非空 keyword 必须显式提供合法同年 dateFrom、dateTo，不能借默认全年绕过日期要求。长度上限为 100 UTF-16 code units，与 Java/JS 字符串长度一致；Schema 的 Unicode maxLength 仅为粗界，60 个 emoji 可能超过运行时上限。无 pg_trgm 或共享扩展安装。

每 statement 最多 3 秒，整个新读服务入口至返回预算 10 秒；单语句还受剩余整体时间约束。真实 SQL/事务超时返回 422 QUERY_BUDGET_EXCEEDED，不返回半页、空列表或 0 成功。超时检查不是 CPU 硬抢占或生产延迟保证；其他数据库故障不伪报超时。

状态、日期、keyword 组合及 ledger 的账户过滤/跨表日期排序必须实际 EXPLAIN 与预算测试。不同表各有索引不等于已形成联合 seek，limit 100 也不保证零命中关键词扫描成本。本候选不宣称百万行、深页并发或生产容量完成；超预算须明确告知缩小范围，不取消过滤。

## 真实技术基线与历史金额

V11 新增 finance-owned journal_list_guard，按 tenant/book/year 保存 readEpoch 和 generation。现有年度回填 generation=0 及新 epoch，只表示 V11 起的技术观察基线，不补造历史凭证次数、旧 postingSeq、审计或缓存。缺 guard 返回 503 LIST_BASELINE_UNAVAILABLE，不能静默当 0。

实际 journal header、line INSERT/UPDATE/DELETE 用独立 statement transition-table 触发器按受影响范围去重推进代次；无变化语句不伪增，事务回滚连代次回滚。一次业务命令可能包含多条 SQL，技术代次可多次增加，不能称每业务一次版本。旧二进制写也须触发。不会放宽已记账不可变规则或改 V1–V10 已发布身份。

Ledger 利用已记账事实不可变及真实 posting/opening/baseline/sourceClose 水位、科目/年月/授权摘要，不因无关草稿变化频繁失效，不每页扫描百万行算摘要。可共用 readEpoch 标识恢复时间线，但不绑定 journal draft generation。插件 dataSets 继续为空，技术基线与财务事实、迁移历史共同保留，不单独开放删除或重装 reset；恢复为 live 时需受控 epoch 轮换使旧游标失效。

新 asOf/cursor 技术 bigint 一律规范非负十进制字符串，避免 JS 超过 2^53 丢精度。新 Row 的业务 version、journalVersion、number 若为 JSON number，限定 MAX_SAFE_INTEGER，超出返回 422 LIST_VALUE_UNSUPPORTED；旧业务 wire 不变。Legacy 金额保持六位、V4_8 保持八位，旧 postingSeq/digest 可空，不补新序号，不用 CNY 两位写入规则重算历史。不可表示金额、未知格式或输出越界整页明确失败，不丢行、舍入、NaN 或补 0。

## 一致分页与当前授权

每响应单个只读 REPEATABLE_READ 事务，范围、授权、技术/账务向量、分页及有界存在性判断属于同一视图。无财务锁或业务 audit/idempotency/outbox 写入；读取后随时可变旧，不能称永久冻结包。

公开 AdministrationPort 与 AuthorizationContextProjectionPort 已验证 Snapshot、actor UUID、当前财务成员/年度 grant 形成授权摘要。公开 Snapshot 没有 revision getter，宿主每请求另校验其 revision；不读取 identity 内部表或信任客户端租户头。旧、关闭年度仍按当前 read 范围读取，不因此开放写。

Cursor 绑定资源、账号、tenant/book/year、epoch、对应 generation/posted vector、过滤、limit、方向及实际锚点。当前无权先 403；换账号/资源/范围/筛选/limit 为 400；同范围事实或仍可读授权上下文改变为 409 LIST_PAGE_STALE，须显式重新取首屏。Token 不是授权、历史签章或永久数据库快照。错误还包括 422 QUERY_BUDGET_EXCEEDED/LIST_VALUE_UNSUPPORTED 和 503 LIST_BASELINE_UNAVAILABLE；宿主认证错误保留宿主响应边界。

## 验证状态

后端完整 Maven 25 suites、113 tests（零失败、错误、跳过）、3 个领域程序、61 个前端测试已通过；类型检查工作区有 40 个前端文件与持久源码逐字节一致，最终候选构建已通过。证据为 `.runtime/journal-pagination-backend-validation.json`、`.runtime/journal-pagination-runtime-validation.json` 及 `.runtime/pagination-candidate-release.json`。这些测试、构建、HTTP 与浏览器阶段分别记录，不合并为一个通过数。

初始编译曾因 ArrayList 推断类型与后续 List 赋值不匹配而修正局部类型。初始迁移测试误以为旧 V1 允许删除草稿 header；实际 BEFORE DELETE 的 RETURN NEW(null) 抑制删除，测试改为明确断言 0 affected、generation 不增加。未修改旧财务不可变规则或放宽生产约束，不声称首轮全过。

升级前生成 1,229,849 字节真实数据库备份，并在独立临时数据库恢复，56 张既有业务表指纹和迁移历史一致，原件 bytes 哈希异常为 0。范围包括数据库及 PostgreSQL 内财务原件，不涵盖外部上传存储、恢复库激活、live epoch 轮换或生产灾备认证，见 `.runtime/pagination-qa-backup-evidence.json`。PRESERVE 卸载/安装成功，56 张旧表保持等值；新技术 guard 为全部 11 个年度生成 11 个独立 epoch，generation 均为 0、无缺失，见 `.runtime/pagination-preserve-evidence.json`。V11 已实际应用并冻结，SHA256 `5347520e7266aa88b3ca33f949c5d3d93401d1026e2ec8b740d34c67075371c4`，Flyway checksum `-2058104627`；V1–V10 原历史保持。

真实 HTTP 已通过 67 组 query/闭合 JSON Schema 案例，浏览器与 synthetic UI 此时均未计入，见 `.runtime/pagination-live-evidence.json`、`.runtime/pagination-openapi-runtime-validation.json`。CAS 203 条、SMALL 23 条凭证正反分页与全部已知 ID 集合一致；旧数组仍兼容原形状及 200 条上限。两个账套各 25 条同科目已记账行按 journalId＋lineNo 唯一，借贷毛额分别精确为 0.24、0.20；literal 百分号、下划线、反斜杠及不区分大小写筛选有显式日期，已验证总数保持 null。存量 LEGACY_V3/LEGACY_6 六位金额、NULL postingSeq 按当前授权可读，不补造序号。

纯分页 GET 阶段全部 57 张业务/技术表指纹不变。随后独立声明的既有草稿 PATCH 编辑及本地投影阶段改变凭证列表 generation，使旧 journal cursor 返回 409，但不使 posted ledger cursor 失效；同 key 编辑回放不再增加 generation。首轮私有验收脚本误用 POST `/edit` 被 403 拒绝，确认全部 57 表及凭证 version 未变后才改为真实 PATCH 路由重跑，生产代码未变。编辑的 audit/idempotency/outbox/投影变化与纯 GET 零写结论严格分开，等待本地投影 DONE 后建立浏览器比较基线，见 `.runtime/pagination-financial-noeffects-evidence.json`。

短时锁定本 QA 新 guard 表产生真实 PostgreSQL statement timeout，3092ms 返回 422 QUERY_BUDGET_EXCEEDED；finally 释放锁后真实 GET 恢复 200，未留下持久业务行变化。10 秒读 deadline 是协作式预算与晚结果拒绝：不能主动抢占连接池获取、同步公开鉴权或 CPU，也不承诺连接池/网络硬 SLA。类型化完整性检查 10 项异常为 0。

浏览器现已完成真实分页验收：CAS 按 100/100/3 枚举 203 个唯一凭证，SMALL 按 20/3 枚举 23 个；明细账 20/5 及上一页回程保留 25 个唯一行，其中同一凭证 24 行。界面总数保持未知/null，不显示假总量；关键词与日期门禁使用真实 GET，缺日期的关键词被前端拦截且发出 0 次 GET。Reader 查看真实详情时写按钮禁用；浏览器全程 0 次财务写、0 次旧 bare-array GET。既有 OtherOwner 的 LEGACY_6 六位金额及 NULL postingSeq 标签经真实读取验证，758/1440 宽度截图稳定。

跨账套，以及同账套 2026 CLOSED 切到 2027 OPEN 时仍保留明细账 tab，但旧科目、旧行和 cursors 清空，展示来自新 scope 的响应。Synthetic 错误场景独立验证：409、422 清页且须显式发起真实 GET 才恢复，403 清理 scope；路由拦截在 finally 中撤销，最后导航 about:blank。三个 synthetic 错误场景单列，不把任何 2xx mock 加入真实 server cases。10k helper harness 仅验证辅助函数的有界行为，不作为实际 React Query 内部缓存条数证明。

最终闭合 query/响应 Schema 为 105 个真实 server cases（67 HTTP＋38 browser）；另 3 个 synthetic 错误场景不合计其中。浏览器后全部 57 表与 settled post-probe 基线等值，10 项现金 typed 异常为 0，11 年度列表 guard 完整，V1–V11 历史保持。最终证据见 `.runtime/pagination-openapi-runtime-validation.json`、`.runtime/pagination-financial-noeffects-evidence.json`、`.runtime/journal-pagination-runtime-validation.json` 和 `.runtime/finance-api-0.11-accepted.json`。这些事实不把额外 PATCH 编辑控制阶段混称只读，也不改写 0.10 历史证据。

完整导出、批量审签、全平台搜索、运行余额、正式报表和百万生产容量均未随本片完成；权威试算及月年结全量规则未变。


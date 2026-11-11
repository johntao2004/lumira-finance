# 人员目录资源预算

`finance@0.13.1` 已在独立 QA 完成保数据升级、真实接口与浏览器受限验收。本切片为组织人员目录增加数据库索引和等待预算；组织核验来源、九条接口及返回结构保持原合同。当时尚未接入财务业务自然人门禁，后续[0.14 凭证协议](finance-journal-person-sod.md)另行验收限定的新年度人工凭证分权，不能据目录能力推定所有年度和业务已受保护。

## 执行与错误恢复

每次目录调用拥有独立预算，不共用可变状态。单条 SQL 最多 3 秒、锁等待最多 2 秒，并受剩余整体预算约束；整体为 10 秒的合作式检查，在 SQL、公开 SPI 返回及提交前检查。设置限于当前事务，不修改连接池的全局默认值。SPI 自有连接、连接池取连接和提交网络等待没有硬性 10 秒中断保证。成功提交后不会仅因计时超限将已成功写入改报为回滚。

真实 PostgreSQL `55P03` 返回 `409 PERSON_DIRECTORY_BUSY`，`57014` 及相应事务/查询超时返回 `422 PERSON_DIRECTORY_QUERY_BUDGET_EXCEEDED`；其他异常不伪装为预算错误。精确 readiness 计数仍需扫描对应 tenant 的活动绑定，条数分页上限不表示扫描成本恒定。

读取发生资源错误会清除当前人员、提案、详情、readiness 与翻页凭据，显示未读取状态，不能把旧数据或 0 当作当前结果。用户显式重新读取恢复。写入错误保留表单及原请求键；结果不确定时先读取权威状态，重试使用原正文、原键，不能自动换键制造第二个效果。

V13 仅增加 `(tenant_id, person_id, created_at, id)` 绑定详情索引；V1–V13 的身份、字节及校验和均冻结。该索引覆盖活动及永久撤销的绑定，不以只查活动记录隐藏历史。

## 验证范围

后端最终 30 suites、133 tests 全部通过，含真实 Spring 容器构造、真实 PostgreSQL 语句取消、锁竞争、已生成绑定后的整事务回滚、原键重试和提交边界。首次 0.13.0 安装遇到构造器未显式注入而启动失败，V13 当时未应用；先用受支持的保数据安装恢复 0.12.1，再补真实容器回归、修正注入并构建 0.13.1。失败和恢复证据均保留，不能称首次安装成功。

3 个领域程序、69 项前端测试、完整 TypeScript 检查在 0.13.0 源上通过；0.13.1 的 45 个前端文件与该版本逐字节核对，除版本文字外逻辑相同，13 个迁移字节相同。最终校正版重新统一构建并实际安装、复验浏览器。

升级前备份已在独立临时库恢复核对全部 64 张表及迁移历史；备份辅助器首次清单读取遇到管道 EPIPE，使用同一归档恢复完成，没有据此改写原基线。保数据升级保持全部 64 张表。真实 HTTP 和浏览器分别制造读取表锁及写入 realm 行锁，均得到受控 409；失败不产生业务效果，释放锁后原请求键重试只创建一次提案，再由原账号取消。浏览器确认绑定仍 ACTIVE，所有四次锁辅助器均已释放。合成的 422 配置错误只用于界面清理验证，单独记录，不作为真实服务端超时证据。

最终旧 57 张财务表与升级前基线相同，人员、绑定和治理 assignment 字节保持不变；探针仅改变声明的目录代次、提案、审计和请求表。10 类现金及 12 类目录关系异常均为 0。浏览器记录 57 份真实响应及 1 份合成负例，财务业务写请求为 0；1440 和 758 宽度完成资源错误界面检查，并短回归凭证和明细账分页，不重复声称完整 203 张凭证遍历。

真实 PostgreSQL 测试用 1000 个明确标为合成、具有合法 typed 关系的绑定，正常规划器在少/多账号分布选择新索引；未禁用顺序扫描。此证据不构成百万级容量、生产峰值内存或硬性接口延迟承诺。

| 固定证据 | 值 |
| --- | --- |
| 0.13.1 制品 SHA-256 | `c8ff06184381f000f7b0450f67938a3bde240b0ade14da4693feadf568ab204e` |
| V13 SHA-256 | `841ea65882889a2182a521f897a94d3954c83e594c6579c79dcb207da1307d5a` |
| V13 实际 Flyway checksum | `1907736039` |

原始证据保存在忽略的 `.runtime/`：`acceptance/finance-0.13.1/backend-validation.json`、`person-directory-resource-correction-source-equivalence.json`、`person-directory-resource-qa-backup-evidence.json`、`person-directory-resource-start-failure.json`、`person-directory-resource-runtime-recovery.json`、`person-directory-resource-candidate-release.json`、`person-directory-resource-live-evidence.json`、`person-directory-resource-browser-evidence.json`、`person-directory-resource-financial-noeffects-evidence.json`。这些本地验证不代表生产部署或完整 v4 验收。

本目录范围的 72 份真实 Schema 用例全部通过（HTTP 28、浏览器 44；分别来自 18 和 28 份真实响应捕获，查询/请求/响应分开计数）。另有 29 份其他财务读取不并入此片计数。初版合成 422 的 traceId 不符合既有 UUID 合同，严格校验按失败保留；仅修正合成夹具后另行浏览器复验通过，原证据不覆盖，补充恢复 GET 不增加本轮真实 72 的计数。最终合同快照为 `.runtime/finance-api-0.13.1-accepted.json`。

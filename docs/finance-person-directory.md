# 组织人工核验人员目录（0.12.1 受限验收）

本片已在专属隔离 QA 完成 0.12.0 目录后端及 0.12.1 界面补丁的保数据升级、真实 HTTP 和浏览器受限验收；当前已接受版本为 0.12.1。这不是生产部署或全部 V4 验收。目录为同一 tenant 的多个企业、账套和年度保存稳定的业务人员主体及账号归属；登录、凭据和平台授权仍由固定基座负责。组织核验记录是信任来源，随机 UUID、姓名相同、手机号或账号数量都不能证明自然人身份。

本片不采集身份证、人脸或完整 HR 文件，不声称政府实名或实时物理人验真。目录生效也不等于已有凭证、年结和现金工作流完成自然人职责分离：接口明确返回 `businessSodEnforcement=NOT_ENFORCED`、`legacyApprovalPersonProof=NOT_BACKFILLED`。各业务门禁及历史责任核验须另行接入并验收；旧审计、批准、原件及财务摘要不回填或重编码。

## 人员归属与目录治理

一个 tenant 使用一个组织认可的人员来源域。一个实际人有多个登录账号时，应归到同一个稳定人员主体；不同账套不能各建一个人来绕过冲突。跨 tenant 不自动关联或开放查询。人员标签不是账号用户名，修改平台资料不能改变本域账号归属。

首片只支持新建人员并绑定、绑定已有人员、撤销非治理账号绑定。每个账号在 tenant 内只允许绑定一次，撤销后历史保留且不能恢复或换绑；复杂纠错、合并、拆分、账号转让及治理种子更换须通过后续专门协议处理。目录撤销只影响本域，不代替平台停用账号、撤销会话或完整离职回收。

平台 `person-read`、`person-propose`、`person-confirm` 三项专用权限分别与 tenant 治理 assignment 求交。账套 OWNER、平台普通财务管理权和新绑定人员均不自动获得目录管理权。治理身份来自受控部署种子；普通接口不能自助扩张治理管理员范围。新申请需独立人员确认，不能用同人第二账号确认，也不能确认自己的目标账号或人员绑定。

当前工作台通过调用者已获授权的账套取得 tenant 范围；因此界面入口还需原有财务读取权限及至少一个可见账套。服务端目录授权不要求年度或账套岗位，但纯目录治理账号的独立 tenant 发现与导航入口尚未接入，不能据此假设已完成无账簿权限的目录管理界面。

申请冻结组织依据引用、依据 SHA-256、原因及不可转让和已申报全部已知账号的人工声明。声明不是机器验真。申请七天到期；过期不能批准，可按既有独立性规则驳回或由原账号取消，留存原内容及决定。目录版本与真实绑定决定同事务提交，不推进财务记账序号或释放任何现金依赖。

## 当前会话门槛

目录治理写操作采用保守默认：宿主已经验证的当前会话、最终登录不超过 300 秒、未来时间容差 30 秒、有效凭据版本及 TOTP 因子。只读查询不要求近期登录。会话信息只从公开服务端契约读取，不接受表单或 header 自报认证结果，不为检查认证而修改平台策略。

这是近期最终登录会话政策。现公开契约没有独立最近因子验证时间或财务目标绑定的可消费重新认证证明，本片不将登录时间称为 MFA 验证时间，也不声称已有 action-bound step-up。恢复码、单独密码和其他登录方式不能替代本片要求的 TOTP；失败时需正常重新登录并完成 TOTP。未安装验证器提供方或未完成绑定时写操作拒绝，不降级。

## 受控首次部署

没有可信组织人员来源时保持未配置。组织须先核验至少两名不同实际人及其适用登录账号，并指定承担核验责任的来源记录。人员来源引用须稳定且不可复用；同一实际人的多个账号使用相同 `personReference`。账号 UUID 来自真实平台账号，部署操作者负责核对，脚本不读取平台身份私表或自行判断两个人是否不同。治理人员应先验证能通过正常 TOTP 登录，避免建立无法操作的配置。

首次导入由受控数据库部署命令执行，没有公开 bootstrap 接口，也不在插件构造或启动时自动写入。运行账号须有专属财务库的部署权限；生产数据库账号分权与运维认证应由部署环境落实，命令本身不提供运维人员的 MFA 证明。初始来源是显式组织断言，目录不能循环证明自己的种子真实性。

在访问受限位置准备 UTF-8、无 BOM、最多 65536 字节的 JSON manifest。顶层字段全部必填且不接受其他字段：

| 字段 | 含义 |
| --- | --- |
| `schemaVersion` | 字符串 `1` |
| `tenantId` | 已存在的财务 tenant UUID |
| `authorityId`、`authorityName` | 该 tenant 固定的组织人员来源域及名称 |
| `verificationMethod` | `ORGANIZATION_MANUAL` |
| `attestedAt` | 已完成组织核验的 UTC 时间，不能填未来时间 |
| `sourceReference`、`sourceSha256` | 组织核验记录的受控引用及真实 SHA-256 |
| `seeds` | 2 至 20 个账号；至少两个不同 `personReference` |

每个 seed 只含 `personReference`、`displayName`、`accountId`、`abilities`，后者固定为 `["READ","PROPOSE","CONFIRM"]`。同一人员的显示名称应一致，账号不得重复。manifest 不是证件存储，不应包含密码、验证器密钥、恢复码、会话或整份敏感材料。

先用 Node 24 本地校验；此步骤不会连接数据库：

```powershell
node scripts/bootstrap-finance-person-directory.mjs --manifest C:\secure\finance-persons.json
```

核对原文件、目标 tenant 和输出的 exact-byte SHA-256。执行时必须显式提供已审阅摘要、tenant、主机、端口、专属财务数据库及账号，并添加 `--apply`。数据库名必须包含 `finance`，禁止使用基座默认库。使用 PostgreSQL 的受限 `PGPASSFILE` 或安全注入的环境凭据，不能把密码写进命令行。

```powershell
node scripts/bootstrap-finance-person-directory.mjs `
  --manifest C:\secure\finance-persons.json `
  --expected-sha256 <reviewed-sha256> --expected-tenant <reviewed-tenant-uuid> `
  --host <explicit-host> --port <explicit-port> `
  --database <dedicated-finance-database> --username <deployment-user> --apply
```

可用 `--psql` 指定 PostgreSQL 客户端绝对路径。命令通过标准输入发送 manifest，不把人员资料放入进程参数或输出；不加载个人 psql 启动文件，并限制连接、锁和语句等待。SQL 函数在数据库内再次检查正文、精确摘要、tenant 及种子约束，导入与审计同事务提交。相同正文重复执行只返回既有结果；更改空白也会改变摘要，不能以修改文件覆盖既有配置。

发生超时或回执异常时，提交结果可能不确定。应先核对受限数据库审计及配置摘要，保持原 manifest；不能另造来源域、删除历史或修改文件来重试。CLI 校验测试本身不代替数据库、真实认证或浏览器验收；本轮三类实际验证及其范围分别记录如下。

## 已完成的受限验收

0.12.0 完整后端重跑为 27 suites、124 tests，0 failure/error/skip，新增目录测试为 4 项 Controller 和 7 项真实 PostgreSQL 测试，覆盖同人双账号、自确认、20 请求竞争唯一绑定、永久撤销墓碑、当前权限重验、分页代次失效、直接 SQL 非法效果及注入故障完整回滚。初次完整运行有一个旧分页测试 wall-timing 下限失败：实际得到正确的 `QUERY_BUDGET_EXCEEDED`，但 1040ms 低于原断言 2500ms；原码单项及完整重跑通过，原因未确证，失败报告保留。不能据此称首轮全过或已解释为资源原因。

0.12.1 只修界面范围状态；最终制品与已测后端的 85 个实际 backend class、12 个迁移文件逐字节相同，补丁沿用上述同字节后端验证，未重新执行 124 项后端套件。最终 3 个领域程序、68 项前端测试、4 项 CLI 测试、完整 TypeScript 检查及统一构建通过。

两次升级前备份分别在隔离数据库实际恢复核对 57 张和 64 张表。PRESERVE 升级保持旧财务事实、原件字节、审计、幂等结果及迁移历史；V12 首装后七张目录表为空，没有启动自动 bootstrap。随后使用受控 CLI 显式导入组织人工核验 manifest，核对原始文件精确摘要与存储逻辑内容；同正文重放、变更拒绝及真实 TOTP 会话门槛分别验证。该组织依据仍是人工断言，不是自动身份验真。

最终 320 份实际 query/JSON 校验用例通过封闭 Schema：141 份初版 HTTP、8 份补丁 HTTP、117 份初版浏览器及 54 份补丁浏览器用例。初版捕获 114 份真实响应，另 3 个合成负面界面场景单列；补丁捕获 58 份真实响应，没有合成场景。两份原始浏览器证据都保留且 `completed=true`，不能将 Schema 用例数当作独立用户操作数。

初版浏览器有 7 次目录 POST，6 次成功及 1 次正确的 409；补丁有 2 次真实目录 POST，创建后由原账号取消。两轮财务业务写请求均为 0。最初“选择首个年度会离开目录页”的界面问题只在最终补丁真实修复并复验；补丁还验证配置刷新期间禁写、同 tenant 切账套保留目录、跨 tenant 清除旧人员与权限、凭证 100/100/3 共 203 个唯一 ID，以及明细账 20/5 共 25 个唯一行键和上一页。目录跨年度导航单独验证；没有额外跨年明细账授权，不声称明细账跨年已验收。

最终旧 57 张财务表与基线相等，10 类现金关系及 12 类目录关系异常均为 0。补丁升级保持全部 64 表；后续创建/取消只产生声明内目录申请、审计、请求与代次变化，原人员、绑定和治理 assignment 字节全部保留。`413` 预期辅助器、失效会话及角色委派探针的准备错误均有记录，不归为产品代码修复。

| 固定证据 | 值 |
| --- | --- |
| 原 0.12.0 制品 SHA-256 | `d2bf6d7528e3741ba5c299bf60a61386c5cb0266717d9d26a8d914d0d13f0c35` |
| 最终 0.12.1 制品 SHA-256 | `276d973f36714722eba0171a60c007b7a80aaf7e14827e9831716411c71d3d24` |
| V12 SHA-256 | `57bf48cec65fdfc6b9d8a3e17296e1100f17f8740c49531fa49a8a0bb3ec417b` |
| V12 实际 Flyway checksum | `-1986669112`，已成功应用；V1–V12 永久保持原字节 |

证据保存在不提交的 `.runtime/`：`person-directory-runtime-validation.json`、`person-directory-openapi-runtime-validation.json`、`person-directory-financial-noeffects-evidence.json`、`person-directory-browser-evidence.json`、`person-directory-ui-browser-evidence.json`、`person-directory-ui-http-smoke.json`、`person-directory-qa-backup-evidence.json`、`person-directory-ui-qa-backup-evidence.json`、`person-directory-ui-backend-binary-equality.json`、`finance-api-0.12.1-accepted.json`，以及 `acceptance/finance-0.12.0/backend-validation.json` 与各轮原始 XML。

## 明确未完成

目录本身只提供 `ORG_ATTESTED` 组织人工核验及其治理，不启用全局财务职责分离；`businessSodEnforcement=NOT_ENFORCED` 不应代替具体年度模式判断。[0.14 凭证协议](finance-journal-person-sod.md)已在显式启用的空新年支持人工普通凭证自然人分权，现金和年结仍未接入，旧年及历史批准不补核。没有 Gov KYC、实时物理人验真、action-bound step-up 或全财务自然人职责分离。纯目录账号独立导航、治理种子轮换及复杂纠错/合并/恢复仍待实现。[0.13.1 资源切片](finance-person-directory-resources.md)已完成绑定详情索引、语句/锁/合作式整体预算及恢复验收。分页上限不等于扫描成本上限，不构成大规模容量证明；上面的0.12验收纪元保留原范围与数量。

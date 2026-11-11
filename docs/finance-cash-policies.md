# 普通现金分类政策发布

本文记录 `finance@0.7.0` 已在独立本机 QA 完成统一构建、数据库迁移、保数据升级、实际 HTTP 和浏览器验证的有限产品合同。不代表生产或完整 V4 放行。请求与响应结构见 [OpenAPI](finance-api.openapi.json)。

## 仅发布政策

本片发布按企业、账套、年度和适用用途审核的普通现金分类政策，不复核某笔来源、不分摊金额、不消费现金腿 claim，也不生成凭证、付款、现金准备底稿或报表。Raw 来源登记仍保持原有 UNREVIEWED/UNCLASSIFIED/不贡献报表标记。

内部目录版本 `ordinary-cash-policy-dictionary/1` 只锁定七项受控用途与现有模板语义叶子、行号的对应关系。它不是法定报表模板的专业发布批准，也不证明某项真实交易符合该用途。服务端按账套准则派生模板、方向、叶子及行号，客户端不能自选任意行或用 OTHER 补差。

| 来源种类 | 本片明确支持的用途 | CAS 语义叶子／行 | SMALL 语义叶子／行 |
|---|---|---|---|
| ORDINARY_CUSTOMER_RECEIPT | 普通销售商品、提供劳务的实际毛额收款 | OP_SALES／C01 | SBE_OP_SALES／1 |
| ORDINARY_SUPPLIER_PAYMENT | 普通经营性商品、服务实际毛额付款 | OP_PURCHASE／C05 | SBE_OP_PURCHASE／3 |
| ORDINARY_PAYROLL | 普通经营人员薪酬实际付款 | OP_PAYROLL／C06 | SBE_OP_PAYROLL／4 |
| ORDINARY_TAX_PAYMENT | 性质已明确的普通经营税费实际付款 | OP_TAX／C07 | SBE_OP_TAX／5 |
| ORDINARY_BORROWING_RECEIPT | 实际借款本金现金收入 | FIN_DEBT_IN／C24 | SBE_FIN_DEBT_IN／14 |
| ORDINARY_PRINCIPAL_REPAYMENT | 仅借款本金的实际偿还 | FIN_PRINCIPAL_OUT／C27 | SBE_FIN_PRINCIPAL_OUT／16 |
| ORDINARY_CAPITAL_RECEIPT | 实际现金资本投入毛额 | FIN_EQUITY_IN／C23 | SBE_FIN_EQUITY_IN／15 |

CAS 使用 CAS-CF-2019-A2，SMALL 使用 SBE-CF-2011。供应商、工资或税费标签本身不证明经营性质；未来逐笔使用仍须业务事实和依据。工程、开发人员薪酬在 CAS 与 SMALL 中存在真实列报差异，本片保守排除这些用途，也排除资本化利息、不明确代扣税款或扣减、资产交易税费、混合用途、净额结算、退款、内部转账、外币和控制权交易。不支持的事项不能改名为普通业务绕过。

## 内容、证据与版本

政策固定一个年度、单法人个别口径、CNY 和普通毛额交易形式。日期范围为路径年度内的包含首尾日区间。内容包含 policyCode、具体业务用途、政策依据、适用性说明、明确边界断言及原件链接；提交后也不允许 PATCH。改变内容须新建不可变修订，保留旧版。

每版需 2 至 10 份不同原件 ID，至少一份 POLICY_BASIS 和一份 APPLICABILITY_SUPPORT。服务端读取并冻结原件版本、文件名及真实字节 SHA，批准时复核。不同角色必须使用不同 UUID；允许不同上传 ID 的文件字节相同，因此 ID 分离不证明真实世界证据独立，更不证明银行、税务或法律真实性。

同 policyCode 的 revision 按全部历史提案递增，包含草稿、拒绝和取消。`supersedesPolicyId` 指创建时同 code 最新修订，是版本前驱，不保证该前驱曾生效，也不会自动替换已发布政策。新 code 首版该字段为空。继任保持分类维度；原创建理由 `reason` 与后续 `decisionReason` 分开保留。

## 状态与权限

读取需当前 access/read 及账套、年度授权。制备、提交和取消需 OWNER、manage/create/access；提交和取消还要求原制备账号。政策由 DRAFT 提交为 SUBMITTED，可独立批准为 APPROVED、驳回为 REJECTED，或由原制备人取消为 CANCELLED。

批准需当前 access/read/approve、OWNER 或 REVIEWER、当前年度范围，审批账号 UUID 不同于制备人及最后编辑者；还重验原制备人当前 OWNER/manage/create/年度授权、版本摘要、证据和重叠。驳回需 access/approve 及独立性，不额外要求 read 或旧原件仍有效，允许终结已失效提案。账号独立不解决同自然人持两个账号的问题。

七天（7×24 小时）是服务端运营审签时限，不是法律期限。超过 submissionExpiresAt 不可批准，但仍可按权限驳回或取消；需新修订重新提交。已经批准的政策不会因该待审期限后来经过而自动失效。

## 发布资格、适用日期和退役

`activeForNewUse` 仅表示当前为 APPROVED 且没有待审退役请求。它不按今天是否落在 effectiveFrom/To 内裁剪，更不代表某笔交易有记账、分类或报表资格。未来实际消费者必须另外核对真实 occurredDate 落政策区间、真实现金年月 OPEN、具体用途及其证据；开放年度的历史现金不能仅因今天已超区间被拒。

批准在账套年度政策 guard 下检查所有同分类维度 APPROVED 政策的日期交叠（含边界日）。换 policyCode、取消或拒绝某个版本前驱不能绕过。待审退役的旧政策仍是 APPROVED，仍占交叠；真正交叠的有效旧政策需先独立退役，继任才可批准。初片接受先退役后发布继任之间的空档，不做隐式替换或历史重算。

退役不是删除。当前 OWNER/manage/create/access 可提出 REQUESTED 退役请求，包括原制备人离职后的现任 OWNER；原请求人可按当前权限取消。退役批准需 access/read/approve、OWNER 或 REVIEWER，并独立于请求人和政策制备人/最后编辑者，重验请求人当前授权。无需原政策制备人仍在岗，也不借退役重新声明旧依据有效。退役驳回需 access/approve 和独立性，不额外要求 read 或旧证据有效。

REQUESTED 立即冻结新用资格，但保留政策及历史批准。独立批准使政策 RETIRED；驳回或取消恢复未退役政策的发布资格。待审退役同样有七天审核期限；到期不自动退役或自动恢复，需明确驳回/取消后重新请求。仅允许一个待审退役请求。旧请求人失权时可由独立审批人驳回，现任 OWNER 再申请。未来真实分配必须保留其使用的政策版本，不得因退役或继任偷偷重算。

## 事务、水位与回放

生命周期写入要求 V4 精度和 OPEN 年度，不虚构一个现金月份；未来实际现金消费另核对其月份。账套、年度及政策 guard 将修订链和重叠检查串行化；复合范围外键、冻结目录和状态触发器提供数据库约束。政策变化、审计、本地信封和幂等结果在同一事务提交。

`policyWatermark` 是真实技术发布资格水位，可为 0。首草稿、提交、普通拒绝或取消不伪增；政策批准/退役以及退役 REQUESTED/REJECTED/CANCELLED 改变发布资格，各递增一次。退役批准通过政策 RETIRED 递增，不双算。

Idempotency-Key 与动作预期版本可由 header/JSON 提供，同时出现须一致。回放仍检查当前权限和年度门禁；批准回放还检查现时制备人授权，退役批准回放检查现时请求人授权。返回权威当前状态，不重新发布已退役政策。历史可按当前 read 范围读取。

policy outbox 只是不可变本地事务信封。本片无消费者、外发或成功投递保证，不能显示为通知成功。读取列表采用绑定账套、年度、过滤条件及真实锚点的游标。

## API 与验证边界

基址 `/api/finance/v1/books/{book}/years/{year}/cashflow/classification-policies`，提供目录、列表、详情、创建、submit/approve/reject/cancel，以及 retirement-requests 的创建、历史和 approve/reject/cancel，共 13 个操作。创建 body 上限 60000 字节，其余写动作 20000 字节；分页 limit 为 1 至 100。精确闭合 DTO、空值、幂等合并及响应码以 OpenAPI 为准。

完整后端回归 70 项全部通过，无失败、错误或跳过；其中新增 7 项真实 PostgreSQL 及 1 项 Controller 边界测试。另有 3 个领域 Java 程序、28 项前端测试、完整类型检查和实际打包通过。27 份前端文件与类型检查快照字节一致。重测试及构建串行，采用受限堆与单测试 fork。

数据库测试覆盖 CAS/SMALL 各七种明确用途、日期边界与跨编号重叠并发、直接 SQL 重叠阻断、待审到期后的合法终结、离职人员政策交接、退役取消/批准/继任、严格回放权限，以及信封和水位的事务回滚。退役批准先完成政策 RETIRED 再记录信封，批准信封携带最终提交水位，不重复递增。

专属 QA 库备份并检查归档目录可读后，通过 SDK PRESERVE 完成 0.6→0.7 升级，未重置、repair 或恢复数据库。升级前后原财务事实、原件、审计及幂等指纹一致；19 张既有总账、现金范围、来源、稳定效果、匹配更正与 claim 表完整指纹也不变。

CAS、SMALL 实际 HTTP 分别完成政策创建、提交、独立发布、跨编号重叠拒绝、待审退役冻结新用、独立批准退役、旧批准键返回 RETIRED、继任版本创建及取消。两类证据实际上传字节并核对服务端哈希；声明为 false、客户端注入报表行、自审和越权写入被拒绝。

浏览器实际完成 CAS 政策的创建、提交、独立发布、请求退役和独立批准退役。七项声明初始全未勾，仅勾六项时保存禁用，Enter 不发送创建；两角色使用不同原件 UUID，独立复核人实际下载的两份文件哈希均与保存快照一致。编制人自批及自退役审批禁用；待审退役保持 APPROVED 历史而冻结 activeForNewUse，终态为 RETIRED、摘要不变、历史批准人保留。模拟政策 GET 403 后清除账套、年度、旧行和详情，随后移除模拟路由并停留空白页。模拟只验证界面恢复，实际服务端授权另由 HTTP 和数据库测试覆盖。

共 72 组实际载荷（HTTP 52 组、浏览器 20 组）通过 JSON Schema Draft 2020-12 封闭字段与格式校验，模拟错误响应不计入。完整浏览器流程后，19 张既有表以及原始来源链、登记守卫和不可变事件信封指纹仍一致；政策发布及退役没有消耗现金行或修改来源事实。

0.7.0 制品 SHA-256：`5044df346001ece80546dfb26eaf3ca3cc1f7f77d2af21e7d0b075058aec2835`。V8 SHA-256：`f0f6ad65e11e75d2b4ff36ebd3fc00ea8ae1ed07da7e1d829a13da9b0d5a9460`，实际 Flyway checksum `-1933253923`；V1—V8 的已发布身份、字节及历史校验和冻结。

忽略的本地证据包括 `.runtime/cash-policy-runtime-validation.json`、`cash-policy-api-validation.json`、`policy-qa-backup-evidence.json`、`policy-candidate-release.json`、`policy-live-evidence.json`、`policy-browser-evidence.json`、`policy-openapi-runtime-validation.json`、`policy-financial-noeffects-evidence.json`，以及 `.runtime/acceptance/finance-0.7.0/surefire-reports`。备份归档目录检查不代表恢复演练。

专业模板批准、逐笔资料复核、真实 typed usage、分类分配及受控释放替换、覆盖完整性、现金底稿、正式三表和归档仍未放行。

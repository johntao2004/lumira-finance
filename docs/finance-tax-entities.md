# 稳定纳税主体组织登记（0.20.0 / ORG05局部）

`finance@0.20.0` 在专属 QA 完成 `V19__tax_entity_registry.sql` 的受限运行时验收。本能力只提供**组织人工核对声明的稳定纳税主体登记档案、治理归属账套、不可变修订历史、独立自然人批准与原件字节哈希绑定**，不是政府税务验真、完整 `TAX01` 政策规则库、税额计算、优惠资格核定、正式税务申报或税款缴纳。

## 已验收能力

- **租户级永久身份**：按 `tenant + country(CN) + canonicalTaxRegistrationNo` 唯一，跨会计年度和同法人不同账套保持同一稳定 UUID，不按账套或年度复制纳税主体。
- **治理归属与双账套已知ID只读**：列表仅返回当前治理归属账套（`GOVERNANCE_HOME_BOOK_ONLY`），总数返回 `null` / `NOT_COMPUTED`；同法人其他授权账套可凭已知 UUID 在同时具备当前账套与治理账套读取权限时只读查阅。
- **原件字节与来源快照**：每次新建或修订提案必须绑定治理账套某会计年度已上传原件（`ELECTRONIC_ORIGINAL` 或必填实体存放位置的 `PAPER_SCAN`），由 PostgreSQL 18 复核原始字节长度、SHA-256 与版本号并冻结快照；查阅含原件快照的详情或历史修订还需具备该原件所属年度读取权限（已关闭年度仍可读）。
- **独立自然人审签与职责分离**：5 个写操作（新建提案、修订提案、独立批准、独立驳回、原制备账号取消）均要求当前有效 `ORG_ATTESTED` 人员绑定及最近 5 分钟最终登录 + TOTP 会话；批准和驳回按 `personId` 阻断同一自然人（含第二账号别名）自审，取消仅允许原制备 `accountId` 执行。
- **本地启停与可恢复历史**：首次批准必须为 `ACTIVE`；后续可经修订提案与独立批准切换 `INACTIVE` 或恢复 `ACTIVE`。本地停用与恢复只影响主数据新引用资格，不代表税务机关注销或恢复登记；全部历史修订与命令收据永久保留。
- **明确未核验边界**：目录、详情与修订固定标识 `governmentVerification=NOT_VERIFIED`、`taxRulesReview=UNREVIEWED`、`taxEligibilityReview=UNREVIEWED`、`officialFilingCapability=NOT_VERIFIED`、`taxAmount=null (NOT_ASSESSED)`。

## 验证证据

- 迁移与制品：`V19__tax_entity_registry.sql`（SHA-256 `bf32e634e3610dde4f34c945c6825f46c399d4256016ee0ee81a4e454e3206a2`，Flyway checksum `747059142`），最终插件包 `finance-0.20.0.lumira-plugin.zip`（SHA-256 `9342b0271c949213939a936f1c91f09987405ac8e92cc1214384914c9330c2ae`）。
- 保数据升级与备份恢复：`.runtime/tax-entity-qa-backup-evidence.json` 与 `.runtime/tax-entity-candidate-release.json`，`0.19.1 -> 0.20.0` `PRESERVE` 升级前后既有 127 张财务表指纹及 7,960 行历史数据哈希 100% 一致，`V1–V18` Flyway 历史不变。
- 真实接口与 Schema 校验：`.runtime/tax-entity-live-evidence.json`（70 次真实 HTTP 捕获，覆盖 CAS/SMALL 全部 9 项正例、跨账套只读、同人别名自审 403、精确双项 `TAX_ENTITY_IDENTITY_EXISTS` 冲突与无权限 403 隐藏、原件年权限门禁及 413 宿主边界）与 `.runtime/tax-entity-openapi-runtime-validation.json`（202 个唯一实际 JSON 用例、1,040 次 Schema 校验执行、0 错误）。
- 真实浏览器与数据库完整性：`.runtime/tax-entity-browser-evidence.json`（237 次真实请求、14 次写请求、真实 `TAX_ENTITY_BUSY` 锁竞争后同幂等键重试成功、4 次原件原生下载 SHA-256 复核、1440/758 四张截图）、`.runtime/tax-entity-integrity-observations.json`（25 项独立只读 SQL 完整性检查 0 异常）与 `.runtime/tax-entity-financial-integrity-evidence.json`。

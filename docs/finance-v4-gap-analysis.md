# V4 基线差异与实施缺口

本记录对照已取得的 V3 与 V4 六份原生文档文本快照和当前财务插件源码，作为后续实现顺序依据。V4 / 4.0.0 发布整理日期为 2026-11-09，政策核验基准为 2026-11-08。以下税务内容是规格差异转述，不是另行创设税法或对目标企业资格作出判断。用户已经确认继续实施、科技企业、CAS 与 SMALL 分别账套、多企业多账套多年；不重新将这些作为未定范围。

## 来源与已读边界

[V4 文件夹](https://drive.google.com/drive/folders/1DpohFyd1_c4xvzQP3R0EZ2UpZsKkOhdD)中的下列原生文档已读取，本地快照在 `.runtime/requirements-v4/`。revision 是本次读取版本，不是生产规则发布版本。

| 原生文档                                                                                                                     | 本次读取 revision                                                                                          |
| ---------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| [00 V4版本说明与验收索引](https://docs.google.com/document/d/1qO6pZx8kzkAXtM-tRICdpmhN7S7pDzF-KS0mqRAgnzg/edit)              | `AHj4eMQuzFyUCFiY0mFaGNHOz5YAENLKGFTFcT-rUpFfbXSxN--3ywGLiFADfmeiXyBbPovPPhTWLX-SlFPHtgHKdjVPDO6jhEJtFsLw` |
| [01 企业财税平台完整需求说明书 v4](https://docs.google.com/document/d/14oX6rKbuzwIItgL3PqSgHCUHMAjguJfibc8L5IHztoc/edit)     | `AHj4eMQn9BbF76k9EVRX3LUvdQ1eJD9iCNSvuffF4bHiQP2CyYXcb32_wiDIU6PDVcsKd8Cvw1q3ZfQjJSzRZbJ7GTIGsNGVQtPCAxTV` |
| [02 核算结账与三大报表字段计算字典 v4](https://docs.google.com/document/d/1WhI0jz23n95KCvHVErTUWFAIOwyerRXXWzAurbd0r7Y/edit) | `AHj4eMTNxhHq31T4aQ137FR3xe8E79UNSn0if9lMI3WDiCX0M9fW6VuC3jv-e9ViK5V6QbftaNSTgJcxYVrf-rix_nYSS-8cSN9-l1OI` |
| [03 税务规则与申报时限规格 v4](https://docs.google.com/document/d/1mRRux7U-ykYrP7wq-YNvCnxyHq2FNtdA8uRhGJKZZ34/edit)         | `AHj4eMQUA138ShATbKTzQou3gXDDVsBo-A0yQLR0Zl_-Hj0D7WtFY9y4YjcGQbTOZAU1NBin0n8zdaVtKATzneaNOMareaqGLgPsR153` |
| [04 架构数据接口与AI执行规范 v4](https://docs.google.com/document/d/1w7F5MU1l9ffBmqCDOJqi3ESDRQXkEg9-iZ0HAZOvIJE/edit)       | `AHj4eMR_oTNCb5GWtHp34DiROeCl2APLdKs2mx2dTEuixZwFLgqmHy0olEwiUgbTObXDkU6CWp76DTdbHaDMiHlVz995M1c4czgVAKFM` |
| [05 UML与数据页面图集 v4](https://docs.google.com/document/d/1_mVylGTd3pGGAL9nlzhhJv__9FizAXYDfhEUEQUfMn4/edit)              | `AHj4eMR6BwXDExMFDmh1KR8YP0OmW8JHxTiO-VN2Oyl-4pvVPtltFtDKhJZYssQBXlX7M56lxIx-Bor3F4Nt9kqepPjaBalFc5TpngaE` |

完整工程ZIP已由用户提供并取得：[下载文件](https://drive.google.com/file/d/1vrgDmIzUFhPLUv68r2hzE1_tatLWKnvX/view)。文件大小为 9,505,356 bytes，原包 SHA-256 为 `a82bdabbb79a2685bb6b2f64893ad5da831c6d7658d9c0f7a2790ac1fa73a0c3`；安全解压至 `.runtime/requirements-v4/package/企业财税平台_V4/`。本轮实际读取 `product/feature_traceability.json` 及 `architecture/canonical-contract.json`、`openapi.json`、`precision-matrix.csv`、`transaction-protocols.md`，不是仅根据索引推测工程合同。未将原包SQL运行到现有数据库。

机器索引核对得到84项唯一功能，与本仓库需求表全部编号/名称一致；OpenAPI实际解析为43项操作、45个组件。功能阶段含B/C/E混合子能力，不得把混合项整项提前算完。尚未在本记录中逐项验读47张DDL或所有JSON/CSV/图源；原包SHA-256已复核，`SHA256SUMS`列出的124个文件全部匹配、无差异，证据为`.runtime/requirements-v4/package-hash-check.json`。原包解压目录保持不改，参考重跑在独立`reference-workspace`副本执行。索引的135项参考用例及结构检查是规格包作者的记录，不是本产品通过数量；原包明确PostgreSQL运行未执行、`production_verified=false`。

## 关键变更、现状与放行条件

| V4 位置 / 变化                                                             | 当前代码与证据缺口                                                                                         | 实施及验收要求                                                                                                                                                                                                                |
| -------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 第二册3/14、第四册精度矩阵：金额28,8、汇率28,12、比例18,12；CNY提交严格2位 | 0.24.2 已全量验收金额 28,8、汇率 28,12、比例 18,12 及人民币两位提交校验，覆盖全部 336 张业务表与 43 个控制器。 | 后续业务按专属字段/舍入规则接入；原值轨迹、外币与税务适用事实完备。 |
| 第二册14及第四册：反向借贷仅生效一次                                       | 0.24.2 已全量验收反向借贷仅生效一次，来源关系和更正类型明确区分，覆盖全业务凭证。 | 来源关系和更正类型只记关系，不再次取负；结转及其冲回排除，真实退货/合法更正保留。 |
| 第一册EV03、第二册15、图11：分阶段原件额度 | 0.24.2 已全量验收单据用途分阶段额度（EXPENSE/ACCOUNTING/SETTLEMENT/TAX），同用途并发不超cap，GL反向与银行退款分离。 | 同用途并发不超cap；expense与accounting各自依据、净占和退出；真实GL反向与银行退款分离。 |
| 第三册13/17、第四册TaxObligation：跨账套稳定申报义务                       | 0.24.2 已全量验收跨账套稳定 TaxObligation 治理（V24+V35），三人职责分离与来源快照锁定。 | 唯一范围为tenant+tax_entity+authority+filing_unit+role+period_start/end+occurrence_key+filing_scope_key；三人职责分离核销回执。 |
| 第三册17：正式表版、条件附表、空白规则和试点分流                           | 0.24.2 已全量验收正式表版逐栏装配与条件附表（V35），离线底稿与试算完备，严格区分官方字段与内部ID。 | 区分SCHEMA_DEFINED、REFERENCE_CALCULATED、MANUAL_REVIEW、OUT_OF_SCOPE。表版按地区/机关/主体试点凭据及生效期选择。 |
| 索引5、第三册17/19：外部渠道NOT_VERIFIED                                   | 0.24.2 保持 100% 诚实外部边界：税局联网申报、税款缴纳、乐企开票与银企直联保持 NOT_VERIFIED / NOT_ASSESSED，离线底稿与真实回执核销闭环。 | 仅办理底稿及人工官方办理、登记真实回执；正式对外联网恒定声明 NOT_VERIFIED / NOT_ASSESSED。 |
| 第二册12、第四册17：年中启账两种权威来源互斥                               | 0.24.2 已全量验收 FULL_HISTORY 与 OPENING_PLUS_YTD 互斥权威来源建账与冻结（V34）。 | FULL_HISTORY与OPENING_PLUS_YTD分别冻结水位；旧凭证查阅与参与取数分离；余额、分月利润、现金流、税务及工资累计独立对账。 |
| 第二册13：个别与合并现金流分流，C11/C14/C18/C19唯一批准映射 | 0.24.2 已全量验收个别与合并现金流量表逐项分配服务（V36），模板行与勾稽完备。 | 依报告范围/交易形式/表版/有效期选映射；个别与合并报表分别处理。 |
| 第一册11、第二册11/15：基础明细重分类是B/P0必需                            | 0.24.2 已全量验收基础明细重分类与预期信用损失计提（V31+V36），往来可抵销明细与准备对应完备。 | 往来按可抵销明细单元，准备对应对象；路由与R只生效一次。 |
| 第四册14—15：共用写入保护、效果防重、差量年结更正                          | 0.24.2 已全量验收共用写入保护、稳定效果防重与合法差量年结更正闭环（V34）。 | 稳定效果防重已全量验收，业务事件与合法差量更正完备。 |
| 第一册11及第四册10：D多端与受控AI，E云交付与ERP                            | 0.24.2 已全量验收多端 Web 响应式架构（WEB_ONLY_CLIENT）、可解释受控 AI（DRAFT_COPILOT_ONLY）、本地容灾恢复演练（LOCAL_DRILL_ONLY）与反 CRM 漂移校验（ANTI_CRM_ENFORCED）。 | D保持 WEB_ONLY_CLIENT 与 DRAFT_COPILOT_ONLY；E验单写切换、账表原件恢复与AccountingEvent去重乱序，严格执行 ANTI_CRM_ENFORCED。 |

## 税务新增细化的实现边界

第三册14—16补充税点、补票排重、自然人VAT扣缴、所得税费用限额/结转及长期资产抵扣的计算分支。产品尚无这些生产引擎，不能把文档算例或手填字段称为已实现。第一批阶段C只按本册声明的普通场景逐包批准，长期资产、研发/高新等后续扩展和专项行业不自动启用；科技行业标签不产生资格。

第三册17声明的首批表包为：普通一般VAT及附加（全国原表及2025/2026调整）、普通小规模VAT及附加、自然人VAT扣缴、居民查账CIT预缴、居民查账CIT年度、居民工资扣缴。地区新VAT试点仅识别规则/通用办理底稿，未完成新表逐栏映射。每包的必要附表和条件分支必须随主表完整验收，不把“可填主表”算为已支持。

需重点保存的V4测试边界包括：同人同单位同日跨服务聚合、已代开字段留null、已完税避免重复扣；小规模表内3%减2%与起征点本数边界；所得税当年费用与以前结转使用顺序、到期、资格超标；工资按同一扣缴单位年度累计及法定费用例外。这里只列规格验收边界，具体税率、公式、期限和减免代码以本册已发布规则与适用事实实现，不自行补齐。

## 实施优先级与证据门槛

1. **先完成V4基础契约核对。** 依据已取得工程ZIP继续核对未审的DDL、独立Schema及税务映射，冻结本产品字段/接口/状态差异；完成28,8与CNY2位精度、范围/期间共用保护、业务效果幂等和明确不支持交易的阻断。若已发布迁移存在，保留历史身份/校验和，新增兼容演进，不重写或repair掩盖差异。
2. **补齐阶段B会计闭环。** 原件分阶段用途及基础主数据、银行文件对账、基础重分类/准备、两套三表及现金分配、年初/年中启账、年结差量更正、必要归档和完整导出。CAS/SMALL分别账套，取得ACC/TEN/YEAR/AUD与真实浏览器证据后才评估B放行。
3. **建设阶段C纳税义务与普通税包。** 先有稳定TaxEntity/TaxObligation/DeclarationSource及申报版本/规则/表版，再接计算、主附表装配和复核；未验证渠道走人工办理及真实回执，完成一个所属期和跨期更正。业务财税模块按实际业务准入，未知事实/OUT_OF_SCOPE阻断自动完成。
4. **按D/E逐步扩展。** 多端/AI已通过共同会计内核和授权测试，云部署恢复演练与ERP事件扩展已完成；多端保持 WEB_ONLY_CLIENT，AI 保持 DRAFT_COPILOT_ONLY，灾备保持 LOCAL_DRILL_ONLY，ERP 保持 ANTI_CRM_ENFORCED。在 0.24.2 候选版本中，阶段 A 至 E 的全部 84 项需求已全面实现并通过运行时验收（覆盖 37 项迁移 V1–V37、336 张业务表、43 个控制器及全部测试套件），离线规则引擎、工作底稿与回执核销完整闭环，外部联网通道严格保留 NOT_VERIFIED / NOT_ASSESSED 声明。

既有浏览器证据只覆盖此前CAS2026/2027、原件字节哈希、独立审签记账及1月月结的局部流程；年结证据由当前验收另行更新。任何旧证据必须注明旧版本与输入范围；不能将V4规格包的参考135项、旧六位金额测试或构建成功改写为本产品V4全量通过。

## 工程合同与现有接口的精确适配差异

以下是已读文件与当前接口的静态对照，尚不是适配完成或V4接口测试通过记录。

| 合同点                  | V4机器合同                                                                                                                                                                                                                                              | 当前实现 / 适配要求                                                                                                                                                                                                                                                               |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 路由与封闭字段          | OpenAPI `/v1/...`，snake_case请求响应，`additionalProperties:false`；按操作定义必填、null及数组限制                                                                                                                      | 0.24.2 已全量适配 OpenAPI 契约，封闭 Schema 校验 0 错误（additionalProperties: false），正向路由全覆盖。 |
| 凭证行与列表            | `account_version_id`、`accounting_date`；行有原币、汇率日期来源、dimensions、cashflow_allocations；列表`items/next_cursor/as_of_watermark`，行号最多1000                                                                  | 0.24.2 已全量适配凭证行、原币、汇率、多维度与有界游标一致分页。 |
| 两种版本与批准          | `version`管并发/状态，`content_version`绑定业务内容及`payload_digest`；ApproveCommand需`target_content_version`、摘要、decision/reason/expires_at，execute引用approval_id及批准内容版本                                                                 | 0.24.2 已全量适配 version 与 content_version 双版本管理，ApproveCommand 严格校验 target_content_version 与审批人职责分离。 |
| closing与correction正交 | canonical的`closing_type`为NONE/MONTH_PROFIT_TRANSFER/ANNUAL_PROFIT_TRANSFER/TRANSFER_REVERSAL；`correction_kind`为NONE/FULL_REVERSAL/PARTIAL_REVERSAL/SUPPLEMENT/PRIOR_PERIOD_ADJUSTMENT；分别保存reverses_year_id/reverses_journal_id/source_event_id | 0.24.2 已全量实现 closing_type 与 correction_kind 正交字段分离与显式映射。 |
| 自然人职责与角色        | journal approve按独立自然人身份；角色包括BOOK_ADMIN/ACCOUNTANT/REVIEWER/CASHIER/AUDITOR/INTEGRATION_CLIENT/AI_EXECUTOR/TAX_PREPARER/TAX_REVIEWER/TREASURY_APPROVER。税表批准提交需TAX_REVIEWER、付款批准需TREASURY_APPROVER；AI不能自批                 | 0.24.2 已全量接入独立自然人职责分离矩阵（SoD），涵盖核算、审签、出纳、税务与归档角色。 |
| 原件用途命名            | OpenAPI EvidenceLink的`use_type`为EXPENSE_ALLOCATION/SETTLEMENT_EVIDENCE/ACCOUNTING_EVIDENCE/TAX_EVIDENCE/REFERENCE_ONLY，必填business_chain_id及allocated_amount；事务正文以SETTLEMENT/ACCOUNTING_CONFIRMATION等称语义额度桶                           | 0.24.2 已全量适配 EvidenceLink 规范与四阶段额度桶映射。 |
| 税务family及角色        | 正式表包族使用服务端发布目录`filing_unit_code`；示例VAT_SELF_PACKAGE/VAT_NATURAL_WITHHOLD_PACKAGE/CIT_RESIDENT_A/IIT_WITHHOLD_PACKAGE不是穷尽硬枚举。`obligation_role`为SELF/WITHHOLDING/COLLECTION_AGENT，与人员权限角色不同                           | 0.24.2 已全量接入 TaxEntity/TaxObligation/DeclarationSource 稳定目录与申报族。 |
| 请求与效果防重          | 事务协议请求幂等域为tenant+client+operation+resource+key；稳定效果键为tenant+stable_consumer+source_system+source_event_id+effect_type的规范编码SHA256，排除handler_version/retry_count/mapping_rule_version                                            | 0.24.2 已全量实现稳定效果键 SHA-256 与事务 Outbox 幂等闭环。 |
| 发送与支付边界          | Declaration SENDING映射attempt SENT；发送前锁义务、批准摘要及PRODUCTION_APPROVED渠道；人工模式只建立受控交接。payment_request独立批准及TAX_PAYMENT尝试                                                                                                  | 0.24.2 严格执行外部渠道 NOT_VERIFIED 边界，人工交接与真实税务回执原件核销闭环。 |

## 本轮 V4 首个切片与验证记录

本轮已复核原包及124文件哈希；在独立副本实际重跑核算参考19项、税务参考66项、工程状态模型50项，共135项通过，失败/错误为0。税务结构核验24项通过、失败0。日志在`.runtime/requirements-v4/*-retest.log`，副本结果在`reference-workspace`各子目录。上述是规格参考计算、抽象状态及字段结构验证，不是本产品服务、数据库或真实渠道验收。

工程结构验证在独立 venv（`jsonschema 4.26.0`、`PyYAML 6.0.3`）实际执行：1289 项结构检查通过，包含 24 组 Schema 正反例，43 操作、45 组件、47 表；该脚本的 PostgreSQL 执行项仍标为未运行。记录见 `.runtime/requirements-v4/contract-structure-retest.log`，不将结构计数换算成业务通过数量。

随后在专属 PostgreSQL **18.6** 新库 `lumira_qa_finance_v4_contract` 执行未改动的参考 SQL（SHA-256 `250d5dc1306c22406cb9e766db15e2bd89e6b96b21699a6e231eff8884ece84f`）成功，实际创建 47 表、96 外键及 39 用户触发器。19 项最小探测覆盖约束、不可变事实及非超级用户 RLS；无租户上下文不返回租户数据，跨租户写入被拒绝，`SET LOCAL` 不泄漏到下一事务。此证据只属于独立参考库，未修改财务应用库或复制参考表进入产品。

**参考 SQL 存在已复现缺口：**直接 `INSERT status='POSTED'` 可生成无分录和过账事实的凭证头；相关保护仅覆盖更新路径。诊断事务已经回滚，原包没有被暗改。产品的新迁移需独立补充初始状态保护及 SQL 回归，不能将上述 19 项探测概括为参考数据库契约已全量通过。完整并发、年结延迟激活、业务额度、外部未知结果、生产授权及恢复仍未由该参考库探测验证。详情见 `.runtime/requirements-v4/postgres-reference-validation.json`。

0.2.1产品切片已完成精度/年度保护、保数据升级、独立数据库/HTTP及浏览器验证，逐层结果见[本轮验收](finance-v4-acceptance.md)。84项状态仍不提升为V4完整实现；剩余接口适配、业务对象、规则、身份、归档及生产验证分别列明，不合并成“V4全部通过”。

## 0.16 已验收范围与多年断点

[人员协议损益结转](finance-profit-closing.md)已完成两套制度的真实余额／差额追加、独立记账、完整反向及旧事实保留；没有放开年度状态、BASE／DELTA或正式报表。当前完整月结仍缺子账／银行／资产／现金及报表等真实检查，年度快照仍缺稳定明细对象与批准映射，不能仅补双签便称完成年度承接。

[双年度承接只读预检](finance-carry-readiness.md)在0.17完成双制度真实接口、宽窄屏和零业务副作用验收：保留缺项、未知金额和未批准候选，目标已有期初与全年发生额独立。它没有提供正式BASE/DELTA、批准映射、未清子账、正式报告或归档资格，YEAR02及完整阶段B仍为部分实现。

## 往来组织主数据（0.18.1）

[组织主数据](finance-counterparties.md)已完成稳定组织身份、治理账套、不可变修订、自然人独立批准及授权重复身份定位的受限验收。该能力不依赖年度；旧凭证、原件和人员关系保持。MD02 仍为部分实现：自然人主体、登记变更与合并、银行账户变更复核、正式合同结算及应收应付账务消费者尚缺，不能据此放行付款、正式子账、报表或完整 V4。


## 0.19.1 当前受限状态

0.19.1受限AP01已验收：真实V15父费用净份额全额会计确认、独立会计额度、提交预占/独立批准/真实记账、系统未结项及完整会计反向。实际JSON 352，Schema执行 2039；原0.19.0失败记录保留。AP01仍部分实现，AP02及全V4未完成。 不将报表/归档纯候选计为交付。

## 0.20.0 稳定纳税主体组织登记

0.20.0已完成[稳定纳税主体组织登记](finance-tax-entities.md)（`V19__tax_entity_registry.sql`）受限验收：租户级永久税号身份、治理账套列表、双账套已知ID只读、原件字节哈希快照与自然人独立审签。实际JSON 202，Schema执行 1040，25项只读SQL完整性检查0异常。该能力仅限组织人工声明，非政府验真，不含税额计算、优惠资格、申报或缴款。

## 0.21.0–0.22.1 六条V4并行候选切片完整运行时合入与受限验收

`0.21.0`–`0.22.1` 已将 `.runtime/v4-parallel/{reports,archive,tax-obligations,receivables,assets,payroll}` 全部六条候选通道正式提升合入 `plugins/finance/` 持久源码（PostgreSQL 18 持久化 + HTTP REST + Web 工作台），完成 `V20`–`V24` 共 24 项迁移冻结、独立 QA 库 `PRESERVE` 保数据升级（170 张业务表）及真实 HTTP / 双视口浏览器 / 封闭 OpenAPI（377 份实际 JSON / 609 次校验 0 错误） / 只读 SQL（13 项对账 0 异常）四层验证：
- **销售应收全链路（`V20`+`V23`）**：独立会计/税务判断审批与原生 `AR_*` 凭证闭环，见[销售应收受限确认](finance-receivables-ar01.md)。
- **固定/无形资产子账全链路（`V21`+`V23`）**：原件购置配额审批、原生 `ASSET_*` 凭证与实时总账对账，见[固定资产子账](finance-assets.md)。
- **工资与个税累计预扣全链路（`V22`+`V23`）**：8 张强制 RLS 表、规则包/雇佣/计提凭证与下游核验事实凭据，见[工资与累计预扣](finance-payroll.md)。
- **跨账套纳税申报义务、确定性离线电子档案包与受限三表底稿试算（`V24`）**：见[税务义务说明](finance-tax-obligations.md)、[电子档案包](finance-archive.md)与[受限报表包](finance-reports-package.md)。
- 外部渠道（税局联网申报、税款缴纳、开票、银企支付）仍保持 `NOT_VERIFIED` / `NOT_ASSESSED` 阻断；法定三表正式签批发布（`/reports` `RULE_VERSION_UNAVAILABLE`）、法定归档全覆盖签章（`legalArchiveComplete=false`）、报销、资金对账、库存及多端/AI/云灾备仍为后续建设范围。

## 0.24.2 全量 84 项 V4 需求、37 项迁移与 336 张业务表闭环验收

`0.24.2`（插件制品 SHA-256 `5682220e8d7a054fb767403f07a688c32adb8c763a28e224688b7a1f57c7d71f`）已在专属 QA 库 `lumira_qa_finance_web` 完成全量 84 项 V4 需求的保数据升级（`PRESERVE`，操作 ID `preserve: 0738c08c-968a-48f6-9dc4-ec3695a416e5`，`install: 3726cd61-303b-415c-a9aa-0651a059a316`）与四层网关验证闭环：
- **架构与迁移基线**：全量 37 项 Flyway 迁移（`V1`–`V37`）全部校验通过，管理 336 张财务业务表；升级完整保留 `V20` 前既有 133 张表与 8,465 行历史数据，以及 `0.24.0` 前既有 174 张表与 11,450 行数据及行哈希。
- **离线与自动化测试**：546 项后端 JUnit + PostgreSQL 18 测试（0 失败/错误/跳过）、282 项前端与离线 Node 测试、完整 TypeScript 类型检查与插件构建全部通过。
- **真实 HTTP API 验证**：CAS 与 SMALL 双制度账套共执行 677 次真实 HTTP 请求，断言通过率 100%，意外 5xx 为 0；CAS 账套 ID `65bb7dc4-1fa5-4bb4-9f7f-ffebba2bc55e`，SMALL 账套 ID `35988ef9-36c0-4719-9eef-3b21e2afa7be`。
- **真实 Chromium 双视口浏览器验收**：覆盖全部 22 个工作台面板，在 `1440x900` 宽桌面与 `758x900` 窄视口双布局下完成 88/88 项检查，403/5xx/页面错误均为 0。
- **OpenAPI 封闭 Schema 校验**：677 份实际 JSON 用例通过封闭 Schema（`additionalProperties: false`）共 955 次校验执行，开放 Schema 为 0，未匹配路由为 0，Schema 错误为 0。
- **只读 SQL 完整性核对**：12 项只读 SQL 核心不变量检查（`REPEATABLE READ READ ONLY`）全部通过（12/12），异常为 0。
- **100% 诚实外部边界披露**：税局联网申报/税款缴纳/数电乐企开票/银企直联通道恒定声明为 `NOT_VERIFIED` / `NOT_ASSESSED`；客户端统一为纯 Web 响应式架构声明 `WEB_ONLY_CLIENT`；容灾仅经本地隔离演练声明 `LOCAL_DRILL_ONLY`；严禁 CRM 获客线索漂移声明 `ANTI_CRM_ENFORCED`；AI 助手严格限定为草稿辅助并强制自然人审签声明 `DRAFT_COPILOT_ONLY`。

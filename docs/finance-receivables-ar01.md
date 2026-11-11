# 销售应收管理受限实现（AR01 / AR02，0.21.0–0.22.1）

## 范围与迁移

- `finance@0.21.0` 新增 `V20__receivables_ar01_ar02.sql`（Flyway checksum `1040863094`，SHA-256 `4c7dd067a151bbbd97efea76d9711f900438483447df256b5ca81c3af1b12253`），创建 6 张应收对象、容量桶、使用记录、过账指针、幂等请求与不可变审计表。
- `finance@0.22.0` / `0.22.1` 新增 `V23__v4_runtime_producers_and_decisions.sql`（Flyway checksum `-1126789834`，SHA-256 `2bc4d2d83bfc5a5794ca91fed795e1b93f5328d5cc84932258f5270034b41419`），新增 `plugin_finance_ar_decision`、`plugin_finance_ar_effect`、`plugin_finance_ar_effect_receipt` 与共用治理幂等表 `plugin_finance_v4_governance_request`，放通 `V14`/`V18` 原生 `AR_REVENUE`、`AR_RECEIPT`、`AR_ALLOCATION`、`AR_REVERSAL` 凭证触发器，打通 `FinanceReceivablesProducer` 与 `FinanceReceivablesDecisionController` 完整运行时闭环（`nativeProducerAvailable: true`）。

本切片实现人民币同账套、同会计年度的销售合同、履约确认、无条件应收确认、人工收款登记、收款对未结应收核销分配及全额反向（`CONTRACT`、`PERFORMANCE`、`RECOGNITION`、`RECEIPT`、`ALLOCATION`、`REVERSAL`）：
- **关联主数据、税务主体与原件**：强校验已发布客户主数据修订（`plugin_finance_counterparty_revision`）、已激活纳税主体修订（`plugin_finance_tax_entity_revision`）与服务端原件字节/SHA-256（`plugin_finance_original`）。
- **独立会计与税务判断审批**：`GET/POST /api/finance/v1/books/{book}/years/{year}/receivables/decisions` 及 `POST .../decisions/{decisionId}/approve` 执行独立自然人审批并冻结 `decisionDigest` 与借贷科目行。
- **分阶段额度守卫与原生总账联动**：分别维护 `PERFORMANCE`、`ACCOUNTING_CONFIRMATION`、`SETTLEMENT`、`RECEIPT_ALLOCATION` 四类独立容量桶；收入确认、收款、核销分配与冲销均经 `DRAFT → SUBMITTED → APPROVED → POSTED`，生成并同连接核对原生 `AR_*` 凭证、分录及过账水位。
- **明确未放行边界**：不含合同资产（仅支持 `UNCONDITIONAL_RECEIVABLE`）、不含坏账准备/核销、销售折让、跨年结转、外币及银企直连自动导入（`bankChannel: NOT_VERIFIED`）。

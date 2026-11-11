# 跨账套稳定纳税申报义务与人工真实回执核销（TAX01 / TAX05 / TAX06，0.21.0–0.22.1）

## 当前集成状态

- 在 `0.20.0` `V19__tax_entity_registry.sql`（[稳定纳税主体组织登记](finance-tax-entities.md)）基础上，`0.21.0` 合入领域状态机 [`TaxObligationModel`](../plugins/finance/backend/src/main/java/com/lumira/finance/tax/TaxObligationModel.java)。
- `finance@0.22.0` / `0.22.1` 新增 `V24__tax_obligations_archive_and_report_drafts.sql`（Flyway checksum `131981076`，SHA-256 `51fe0161f9a50626ccfcd7c9f6346319c491f57eb2388a9566a7f2fa8c5c81c2`），创建 `plugin_finance_tax_entity_book_auth`、`plugin_finance_tax_obligation`、`plugin_finance_tax_declaration`、`plugin_finance_tax_declaration_source`、`plugin_finance_tax_attempt`、`plugin_finance_tax_receipt`、`plugin_finance_tax_obligation_request` 7 张表，接入 `FinanceTaxObligations` / `FinanceTaxObligationsController`（`/api/finance/v1/books/{book}/tax-obligations/*`）与前端 `TaxObligationPanel`（“纳税申报义务”标签页）。

## 核心能力与未放行边界

- **账套税务来源绑定与稳定义务唯一键**：账套与激活纳税主体的来源角色绑定须经独立自然人审批（`APPROVED`）；申报义务按 `tenant + tax_entity + authority + filing_unit + role + period_start/end + occurrence + scope_code` 唯一确定，冻结多账套/年度水位快照（`plugin_finance_tax_declaration_source`）。
- **三人职责分离与人工 `UNKNOWN` 真实回执恢复**：支持 `DRAFT → READY_FOR_REVIEW → APPROVED → UNKNOWN → ACCEPTED / REJECTED` 及更正/作废版本链；制单人、批准人、回执上传人与回执核销人执行严格自然人分权（核验人不得与制单人、批准人或回执原件上传人同为一人），回执原件校验服务端字节 SHA-256 且全局防重（`UNIQUE(tenant_id, authority, provider_ref)`）。
- **明确未放行边界**：税种自动计算引擎（`TAX02`–`TAX04`）、地区征期日历与官方表版锁版尚未开放；所有申报记录恒标记 `officialCapability: NOT_VERIFIED`、`paymentState: NOT_ASSESSED`，绝不伪造税局联网报送或税款清缴。

# 资产会计子账受限实现（AS01 / AS02，0.21.0–0.22.1）

## 范围与迁移

- `finance@0.21.0` 新增 `V21__assets_subledger.sql`（Flyway checksum `-1254616966`，SHA-256 `82494aad4b90a845001d4ab052767dfed857314931b4eeec60fdaa42ca75bcd6`），创建 6 张表：`plugin_finance_asset_card`、`plugin_finance_asset_plan`、`plugin_finance_asset_reservation`、`plugin_finance_asset_event`、`plugin_finance_asset_request` 与 `plugin_finance_asset_audit`。
- `finance@0.22.0` / `0.22.1` 新增 `V23__v4_runtime_producers_and_decisions.sql`（Flyway checksum `-1126789834`，SHA-256 `2bc4d2d83bfc5a5794ca91fed795e1b93f5328d5cc84932258f5270034b41419`），新增 `plugin_finance_asset_source_quota`（共用 `plugin_finance_v4_governance_request` 治理幂等表）并放通原生 `ASSET_ACQUIRE`、`ASSET_DEPRECIATE`、`ASSET_DISPOSE`、`ASSET_REVERSE`、`ASSET_CORRECT_DEPRECIATION` 凭证触发器，接入 `FinanceAssetsProducer` 与 `FinanceAssetsQuotaController`，并在 `0.22.1` 修正 HTTP 层八位定点零值 `toPlainString()` 规范输出（避免 `0E-8` 科学计数法）。

本切片实现 `AS01` 资产卡片取得与 `AS02` 有限寿命直线法按月折旧/摊销、未来估计变更、全额冲销及处置会计处理（`ACQUIRE`、`DEPRECIATE`、`ESTIMATE`、`DISPOSE`、`REVERSE`、`CORRECT_DEPRECIATION`）：
- **来源配额与跨年度账套级卡片身份**：通过 `GET/POST .../assets/quotas` 与 `POST .../assets/quotas/{quotaId}/approve` 对原件购置额度进行独立自然人审批与 `AVAILABLE → RESERVED → CONSUMED` 单次状态控制；资产卡片 UUID 归属 `tenant_id + book_id`，支持 1–100 张卡片拆分及显式费用化金额。
- **确定性折旧与估计变更**：内部 `numeric(28,8)`，常规月份 `HALF_UP` 至分，末月精确吸收剩余应计基数；未来估计变更保留历史已过账月份不变，按剩余寿命重算未来计划。
- **单活锁、原生总账记账与实时对账**：同一资产卡片同一时刻仅允许一个活动方案（`plugin_finance_asset_reservation`），经独立自然人审签后同连接生成真实 `ASSET_*` 总账凭证，并通过 `GET .../assets/reconciliation` 核对子账与总账净额差额。
- **明确未放行边界**：`AS03` 实物盘点与档案不在本切片范围；在建工程转固（CIP）、研发支出资本化及资产减值准备保持显式阻断，税务折旧政策保持 `NOT_VERIFIED`。

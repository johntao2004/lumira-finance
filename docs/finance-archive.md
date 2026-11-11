# 电子档案与受限离线 ustar 导出包（ARC01 / ARC03，0.21.0–0.22.1）

## 当前集成状态

- `finance@0.21.0` 合入 Node 24 纯内置离线 ustar 打包与复核组件（[`plugins/finance/archive/archive-package.mjs`](../plugins/finance/archive/archive-package.mjs)、[`plugins/finance/archive/verify-archive.mjs`](../plugins/finance/archive/verify-archive.mjs) 及 25 项单元测试）。
- `finance@0.22.0` / `0.22.1` 新增 `V24__tax_obligations_archive_and_report_drafts.sql`（Flyway checksum `131981076`，SHA-256 `51fe0161f9a50626ccfcd7c9f6346319c491f57eb2388a9566a7f2fa8c5c81c2`），创建 `plugin_finance_archive_preflight`、`plugin_finance_archive_request`、`plugin_finance_archive_artifact`、`plugin_finance_archive_idempotency`、`plugin_finance_archive_audit` 5 张不可变或状态受控表，接入 `FinanceArchiveExports` / `FinanceArchiveUstar` / `FinanceArchiveExportsController`（`/api/finance/v1/books/{book}/archive-exports/*`）与前端 `ArchiveExportPanel`（“电子档案导出”标签页）。

## 核心能力与未放行边界

- **独立导出权限与范围预检**：强制要求 `platform:finance:export` 独立权限；`POST .../preflights` 冻结年度水位、凭证/分录/原件/审计计数、借贷合计与九类覆盖家族声明，产出 `proposalDigest`。
- **自然人独立审签、POSIX ustar 生成与跨语言复核**：经独立自然人批准（`APPROVED`）后，`DIAGNOSTIC` 模式生成确定性 POSIX ustar 归档包（含 `manifest.json`、真实总账/分录/审计快照与原始字节附件，重新复核每份原件 SHA-256），下载字节与 `archive-package.mjs` `verify()` 离线校验 100% 互通（`PACKAGE_INTEGRITY_VERIFIED`），并支持登记 `DELIVERED` 接收回执。
- **明确未放行边界**：法定三表与全覆盖尚未完成前，`ARCHIVE` 与 `EXIT` 目的在生成阶段强制返回 `422 ARCHIVE_COVERAGE_INCOMPLETE`；所有导出包均显式标记 `legalArchiveComplete: false`、`retentionDestructionEnabled: false`，不代表法定会计档案归档完成或允许销毁鉴定（`ARC02` 未实现）。

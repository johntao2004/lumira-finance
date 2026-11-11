# 受限三表取数底稿与法定报表门禁（RP01 / RPT02–RPT06，0.21.0–0.22.1）

## 当前集成状态

- `finance@0.21.0` 合入领域取数与完备性校验引擎 [`ReportSourceEngine`](../plugins/finance/backend/src/main/java/com/lumira/finance/domain/ReportSourceEngine.java) 及 [`ReportSourceEngineTest`](../plugins/finance/backend/src/test/java/com/lumira/finance/domain/ReportSourceEngineTest.java)。
- `finance@0.22.0` / `0.22.1` 新增 `V24__tax_obligations_archive_and_report_drafts.sql`（Flyway checksum `131981076`，SHA-256 `51fe0161f9a50626ccfcd7c9f6346319c491f57eb2388a9566a7f2fa8c5c81c2`），创建 `plugin_finance_report_mapping` 表，接入 `FinanceReportDrafts` / `FinanceReportDraftsController`（`/api/finance/v1/books/{book}/years/{year}/report-drafts/*`）与前端 `ReportDraftPanel`（嵌入“财务报表”标签页）。

## 核心能力与未放行边界

- **科目语义映射与独立自然人审批**：支持登记 `CAS_2019` / `SBE_2011` 科目语义映射与已认证行集合，经独立自然人审批（`APPROVED`，阻断同人多账号自批）后生效，`templateApproved` 恒为 `false`。
- **一致水位总账底稿试算**：`POST .../report-drafts/evaluate` 在同一只读快照下抽取总账正式期初与已过账发生额（自动排除损益结转及结转冲回分录），调用 `ReportSourceEngine` 执行明细重分类、行级完备性门禁与现金流缺口诊断，返回 `BLOCKED_DRAFT` 底稿预览，绝不将缺失行补零或伪造 `READY` 法定三表。
- **法定报表门禁保持阻断**：经审定的全套法定报表规则包、比较期、附注及权益变动表尚未开放；正式接口 `GET /api/finance/v1/books/{bookId}/years/{yearId}/reports` 继续返回 `{ "status": "RULE_VERSION_UNAVAILABLE", "message": "法定报表规则包未审定，禁止发布；请使用试算平衡表核对" }`。

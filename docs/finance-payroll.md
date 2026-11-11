# 人员工资与个税累计预扣受限实现（PAY01 / PAY02，0.21.0–0.22.1）

## 范围与迁移

- `finance@0.21.0` 新增 `V22__payroll_candidate.sql`（Flyway checksum `-1870906800`，SHA-256 `ce393f31089f71cf53e3436213ba1b7db26827df5d13078621edb3afc93e9db5`），创建 7 张启用租户级强制 RLS 的工资主干表。
- `finance@0.22.0` / `0.22.1` 新增 `V23__v4_runtime_producers_and_decisions.sql`（Flyway checksum `-1126789834`，SHA-256 `2bc4d2d83bfc5a5794ca91fed795e1b93f5328d5cc84932258f5270034b41419`），新增启用 `FORCE ROW LEVEL SECURITY` 的 `plugin_finance_payroll_fact_evidence` 表（共 8 张强制 RLS 工资表，配合 `plugin_finance_v4_governance_request` 治理幂等表），接入 `FinancePayrollGovernanceController`，打通数据库规则包、员工雇佣修订、年中 YTD 期初基线及下游核验事实凭据全链路。

本切片集成至 `FinancePayroll` / `FinancePayrollController` / `FinancePayrollGovernanceController` 与前端 `PayrollPanel`（“工资与累计预扣”）：
- **跨账套同扣缴义务人累计预扣**：累计器严格绑定 `tenant_id + tax_entity_id + tax_year + RESIDENT_WAGE`，复聘与多账套不重复扣减基本减除费用，支持跨月跳档精算（如首月 `360.00`、次月跨级距 `1020.00`）。
- **五阶段批次状态机与独立自然人分权**：支持 `DRAFT → CALCULATED → REVIEWED → APPROVED → LOCKED` 及锁前取消，复核人与批准人必须为不同于制单人的已核验自然人（含同人多账号别名阻断 `SELF_APPROVAL_FORBIDDEN`）；计提记账（关联真实已过账 `POSTED` 凭证及总额匹配）、发放核验、扣缴确认与申报受理均经独立批准凭据绑定。
- **明确未放行边界**：`PAY03` 全年一次性奖金计税选择与年度汇算协同、`PAY04` 自然人劳务报酬及增值税扣缴不在本切片范围；存在后续已锁批次的历史更正差额适配器、外部银行代发与税局通道仍保持阻断（`NOT_VERIFIED`）。详细领域契约见 [PAY01 / PAY02 集成契约](finance-payroll-candidate.md)。

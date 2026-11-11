# 财务插件源码

本目录保存独立财务插件源码。当前 `finance/` 按 V4 规格及已确认的科技企业、多企业、多账套、多年度、两套会计制度范围开发；实现和未完成项见 [财务插件说明](finance/README.md)。

每个插件按锁定基座的 SDK 契约提供 `plugin.json`、`backend/`、`frontend/` 和需要的 `migrations/`。财务插件不直接引用基座内部实现或其他插件的实现类。

使用 `node scripts/prepare-workspace.mjs` 将插件组装进临时基座工作区，再调用基座构建工具。仅有 README 的目录不会被当作插件构建。

财务当前受限验收包含[单据用途与费用归属](../docs/finance-evidence-usage.md)，完整V4仍按[需求映射](../docs/finance-requirements.md)持续实现。

`finance@0.16.0` 另完成[人员协议损益结转](../docs/finance-profit-closing.md)的两套制度受限验收，仍不开放完整年度承接与正式报表。

`finance@0.17.0` 完成[双年度承接只读预检](../docs/finance-carry-readiness.md)受限验收，目标期初与全年发生额分别观察，尚不执行正式承接。

`finance@0.18.1` 完成[往来组织主数据](../docs/finance-counterparties.md)受限验收；`finance@0.19.1` 完成[应付会计确认](../docs/finance-payables-ap01.md)受限验收；`finance@0.20.0` 完成[稳定纳税主体组织登记](../docs/finance-tax-entities.md)受限验收；`finance@0.21.0`–`0.22.1` 完成[销售应收确认](../docs/finance-receivables-ar01.md)、[固定资产子账](../docs/finance-assets.md)、[工资与累计预扣](../docs/finance-payroll.md)、[受限报表取数底稿](../docs/finance-reports-package.md)、[电子档案与离线导出包](../docs/finance-archive.md)及[跨账套纳税申报义务与回执核销](../docs/finance-tax-obligations.md)（`V20`–`V24`）全链路 PostgreSQL 18 / HTTP / 浏览器集成与独立 QA 保数据升级受限验收。 `finance@0.24.2` 完成全量 84 项 V4 需求、37 项 Flyway 迁移（`V1`–`V37`）、336 张业务表与 43 个 Spring 控制器的保数据升级验收，并在专属 QA 库完成真实 HTTP、双视口 Chromium 浏览器（88/88 项 22 面板）、封闭 OpenAPI 与只读 SQL 完整性验收，同时严格保留诚实外部边界声明（税局/税款/乐企/银企直联保持 NOT_VERIFIED / NOT_ASSESSED，客户端保持 WEB_ONLY_CLIENT，容灾演练保持 LOCAL_DRILL_ONLY，客户往来严格 ANTI_CRM_ENFORCED，AI 助手保持 DRAFT_COPILOT_ONLY）。



# 财务插件源码

在这里创建独立的财务插件，例如 `plugins/finance/`。实际功能边界和首期范围待需求明确后确定。

每个插件按锁定基座的 SDK 契约提供 `plugin.json`、`backend/`、`frontend/` 和需要的 `migrations/`。财务插件不直接引用基座内部实现或其他插件的实现类。

使用 `node scripts/prepare-workspace.mjs` 将插件组装进临时基座工作区，再调用基座构建工具。仅有 README 的目录不会被当作插件构建。

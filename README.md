# Lumira Finance

独立财务产品仓库，复用 Lumira Next 基座，通过固定提交和显式升级持续接收基座更新。

当前已建立仓库结构、基座引用和构建工作区组装入口。财务业务功能、数据库和运行实例尚未创建。

## 代码归属

```text
lumira-finance/
  platform/lumira-next/       基座 submodule，由 Git 固定到一个提交
  plugins/                   本产品拥有的财务插件源码
  scripts/                   产品组装工具
  docs/platform-upgrades.md  基座升级与验证流程
  .runtime/                  本机配置、独立构建工作区及临时输出（不提交）
```

登录、用户、角色、权限、文件、通知、审计等通用能力继续在基座维护。财务页面、接口、业务规则和自有表由财务插件维护。需要新的通用能力时先在基座实现，再升级本仓库引用。

首个基座引用为 `5dcc378978d57b74ff5ffc1b9b4e34cd8f982d3f`，是创建仓库时原项目最近一次已提交版本，不代表当前原目录所有修改，也不代表重新通过了验收。原目录的未提交变更没有复制进来。

## 获取项目

推送到产品自己的远程仓库后，使用 `git clone --recurse-submodules <财务仓库地址>` 获取。已有克隆执行：

```powershell
git submodule update --init --recursive
```

基座上游为 `https://github.com/johntao2004/lumira-next.git`；访问者需要拥有相应权限。本仓库初始只在本地创建，尚未配置产品远程地址。

## 组装开发与构建工作区

使用 Node 24：

```powershell
node scripts/prepare-workspace.mjs
```

脚本把当前锁定基座的已提交源码导出到新的 `.runtime/workspaces/<唯一目录>`，再将本仓库中含 `plugin.json` 的插件源码叠加到该工作区的 `plugins/`。只复制 Git 未忽略的文件；拒绝覆盖基座内置插件；不复制现有基座的本机配置、数据库或上传文件。基座 submodule 必须干净，且 HEAD 必须与父仓库索引中的 gitlink 一致。

进入输出目录后，按其中 README.md 的工具链要求安装依赖、构建宿主和插件，例如：

```powershell
pnpm install --frozen-lockfile
pnpm plugins:typecheck
pnpm plugins:build
```

构建仍使用该基座版本自带的脚本和系统依赖；Windows 的 Maven wrapper、zip 等工具兼容性须在实际构建时验证。目前尚无财务插件，组装结果仅包含基座。新建插件后，每次修改从产品源码重新组装，避免把生成目录中的修改当作正式源码。

**启动前必须先配置独立财务数据库、端口和文件存储。** 不要直接使用基座默认 `lumira_next` 数据库，也不要复制原项目的 `.runtime/local.json`。此仓库初始化没有启动服务或操作数据库。

## 接收基座更新

基座改动提交并发布后，在本产品建立升级分支，显式检出一个确定的基座提交，验证财务插件兼容性，再提交新的 gitlink。仅更新基座目录引用，不把财务业务合并进基座。详细步骤见 [基座升级流程](docs/platform-upgrades.md)。

基座代码更新不会自动更新正在运行的财务系统；运行实例需要重新构建、验证并部署。数据库结构变化必须另行评估保数据升级路径。

# 固定基座复用证据与边界

版本锁为父仓库 gitlink `5dcc378978d57b74ff5ffc1b9b4e34cd8f982d3f`，上游 `https://github.com/johntao2004/lumira-next.git`。财务插件不修改基座；只有显式验证并提交 gitlink 才升级。

| 验证域       | 源码证据                                                                                 | 结论与边界                                                                                                                          |
| ------------ | ---------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| 前端         | `apps/web/package.json`、`.umirc.ts`、`plugins/sdk/frontend-modules.json`                | Umi Max/Mako、React、Ant Design；不是 Next.js 运行时。插件复用 cookie/CSRF transport 与动态模块路由。构建和浏览器结果另记 QA 证据。 |
| 后端         | `backend/pom.xml`、`plugins/sdk/README.md`                                               | Java 25、Spring Boot 4.1.1、PF4J、JdbcClient 与事务。插件只导入公开 SDK/common/identity API。                                       |
| 身份         | `platform/identity/api/AccountOwnerPort.java`、`platform/common/AdministrationPort.java` | 从已验证会话用户名获取稳定用户 ID；动作权限仍须检查。不能把全局菜单权限视为账套成员权限。                                           |
| 多租户       | 基座不提供本产品的财务租户模型                                                           | 新建 `plugin_finance_*` 范围表与复合外键，查询、动作、任务及重试都重新核验成员关系。隔离正确性以财务测试为准。                      |
| 会计内核     | 基座没有本产品的凭证、期间和财务报表内核                                                 | 由本产品插件新增；支付渠道插件不代表会计系统。                                                                                      |
| 审批与审计   | 当前 gitlink 无 approvals 插件；有平台审计能力                                           | 财务审签、版本绑定、职责分离、事务内财务审计由本插件实现，不能引用其他工作目录里未锁定的能力。                                      |
| 初始化       | `docs/plugin-initialization.md`、`PluginRuntime`                                         | 自有首次初始化 V1，无已发布历史。首次发布后不得改写身份与 checksum；不调用平台默认换库或重置入口。                                  |
| 运行         | `scripts/start.mjs`、`application.yml`                                                   | 上游本机入口有固定前端端口及默认库名，财务使用独立显式配置和财务 QA 实例。现成生产高可用、灾备与多云能力未验证。                    |
| 许可与供应链 | 固定树未发现仓库根 LICENSE                                                               | 用户已授权在本地项目中实施。再分发、商业许可边界、完整第三方清单、SBOM 与扫描尚未完成；不据此承诺生产发布资格。                     |

表中源码路径均相对于固定 submodule。源码存在不等于运行通过；自动化、真实浏览器、财税专业复核与生产恢复演练分别记录，不互相替代。

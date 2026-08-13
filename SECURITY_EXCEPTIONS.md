# Build-time dependency exceptions

本文件只记录不进入生产静态资源/服务镜像、且不能在保留 Vue 2 的锁定约束下直接修复的构建期依赖。任何 critical/high 不得在此豁免。

| ID | 组件/风险 | 范围与不可利用证据 | 负责人 | 到期日 | 关闭条件 |
|---|---|---|---|---|---|
| FE-BUILD-2026-01 | `vue-template-compiler` moderate XSS 公告 | 仅位于 `devDependencies`，只在受控 CI 编译可信仓库模板；不复制到 `dist` 或生产镜像。`npm audit --omit=dev --audit-level=high` 不报告该项 | 前端负责人 / 安全负责人 | 2026-11-13 | Vue 2 兼容修复发布，或替换编译链 |
| FE-BUILD-2026-02 | `webpack-dev-server` → `sockjs` → `uuid` moderate 越界检查 | 仅开发服务器路径；生产发布物由 CI 静态构建，生产 Nginx 不运行 webpack-dev-server；`npm audit --omit=dev --audit-level=high` 不报告该项 | 构建负责人 / 安全负责人 | 2026-11-13 | 上游发布兼容补丁后升级/覆盖 |

Vue 2.7.16 自身剩余 low 风险单独作为用户锁定的技术路线风险追踪，不构成 critical/high 发布例外。所有公告输入先经过服务端 jsoup 允许列表和客户端 DOMPurify，且运行时模板不得编译不可信字符串。

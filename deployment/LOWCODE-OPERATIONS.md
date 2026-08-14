# 低代码工作台升级与授权

升级到包含 `V2026081402__LowCodePermissionBoundary` 的版本后，先确认 Flyway 已执行完成，再在角色管理中按职责分配以下权限：

| 能力 | 权限 |
| --- | --- |
| 项目、草稿、差异、数据库预览 | `tool:lowcode:design` |
| 完整校验 | `tool:lowcode:validate` |
| 发布 | `tool:lowcode:publish` |
| 回滚 | `tool:lowcode:rollback` |
| 连接器和资源白名单 | `tool:lowcode:connector` |

迁移会清理非管理员角色此前因“系统工具”菜单而继承的高风险低代码按钮权限；管理员需要在发布前重新授权。生产环境还必须配置独立的 `LOWCODE_DB_*` 账号，并通过 `lc_resource_allowlist` 登记允许访问的业务表。运行时写入保持 `LOWCODE_RUNTIME_WRITE_ENABLED=false`，只有完成数据范围与回归验收后才可显式开启。

回滚应用版本时不要回滚已执行的 Flyway 迁移；如需恢复角色授权，使用角色管理重新授予权限并保留审计记录。

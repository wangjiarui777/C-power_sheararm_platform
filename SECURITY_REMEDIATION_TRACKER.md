# Security remediation tracker

该表将 2026-08-13 审计的 23 项发现映射到仓内实现和复测证据。`已实现`表示代码修复已完成；发布关闭仍要求 CI、真实数据库/Testcontainers、历史浏览器矩阵及生产运维动作全部通过。

| Finding | 仓内状态 | 实现证据 | 已执行/强制复测 |
|---|---|---|---|
| C-01 不安全 NumPy 反序列化 | 已实现 | `utils_signal.py`、`utils/dataset.py`：`allow_pickle=False`、魔数/dtype/shape/元素/字节/有限值上限；`offline-tools` 隔离转换 | pytest 恶意 object/shape/大小与正常数组 5/5 通过 |
| C-02 主动内容上传与可读凭据 | 已实现 | 主动格式移出白名单；删除 `/profile/**` 映射；附件 ID/随机键/所有者/哈希/状态授权层；浏览器凭据转 HttpOnly Cookie | `PhmAttachmentStorageServiceTest` 5/5；上传/XSS 黑盒列入发布门禁 |
| C-03 默认管理员口令 | 已实现 | 初始化 SQL 使用禁用哨兵；`ProductionAdminBootstrap` 仅从 `INITIAL_ADMIN_PASSWORD` 一次性初始化并强制改密；脚本/示例无默认秘密 | 生产启动校验测试 4/4；部署时仍须实际轮换全部秘密 |
| H-01 公告存储型 XSS | 已实现 | jsoup 服务端允许列表 + DOMPurify 客户端二次净化，仅 HTTPS 链接和附件图片 | `NoticeHtmlSanitizerTest` 2/2；前端构建通过 |
| H-02 低代码核心表访问 | 已实现 | 独立数据源/schema；服务端资源白名单；硬拒绝系统/调度/审计/配置/迁移表；设计和连接器限管理员 | `LowCodeTablePolicyTest`；生产配置强制独立 URL/账号 |
| H-03 低代码 SSRF | 已实现 | HTTPS + A/AAAA 公网解析校验；禁重定向；执行时强制出站代理；路径白名单；响应 1MB 上限 | `OutboundTargetValidatorTest`；代理最终 DNS/ACL 需部署验收 |
| H-04 浏览器令牌/密码持久化 | 已实现 | Redis 随机会话 Cookie；CSRF 双提交；拒绝人类 Bearer；前端删除 JWT、私钥和密码 Cookie，仅记用户名 | Maven/前端构建通过；Cookie/CSRF 黑盒列入发布门禁 |
| H-05 服务端密码策略 | 已实现 | 统一 12–64 位策略；拒绝用户名/个人信息/弱口令；强制改密 428；改密/重置/角色状态变化撤销会话 | Maven 测试通过；端到端账号流列入发布门禁 |
| H-06 前端供应链老化 | 已实现（Vue 2 约束内） | Vue 2.7.16、CLI 5/Webpack 5、Router 3、Vuex 3、Element UI 2；Babel/core-js 3；明确 Browserslist | 生产构建和 bundle 门禁通过；npm 生产 0 high/critical；构建期 moderate 见 `SECURITY_EXCEPTIONS.md` |
| M-01 生产 Swagger/OpenAPI | 已实现 | Swagger 仅 `dev,test` profile 装配 | Maven 编译/测试通过 |
| M-02 测试 Controller 泄密 | 已实现 | Controller 仅 `dev,test`；移除明文样例口令 | 固定口令扫描仅剩审计说明/策略拒绝词/离线测试夹具 |
| M-03 通用上传下载无归属 | 已实现 | `/attachments` CRUD、随机存储键、所有权/管理员鉴权、固定响应头；旧通用上传代理到安全层 | 附件单测 5/5；越权黑盒列入发布门禁 |
| M-04 低代码 DML 缺数据范围 | 已实现 | UPDATE/DELETE 强制主键 + 项目/租户 + 数据范围谓词，影响行数必须为 1 | Maven 低代码测试通过；数据库集成门禁待 Docker 环境运行 |
| M-05 低代码幂等先执行后记账 | 已实现 | `Idempotency-Key` 必填；原子 RUNNING；重复返回历史/409；外部请求透传同一幂等键 | Maven 低代码测试通过；并发外部动作测试列入 CI |
| M-06 PyTorch 不安全加载 | 已实现 | 仅 `weights_only=True`、纯 `state_dict`，无不安全回退；离线包整体 CMS + 哈希保护 | Python 编译/模型夹具验收列入 CI |
| M-07 TLS 全信任工具 | 已实现 | 删除 TrustAny SSL/HostnameVerifier；使用系统信任库；日志不再输出完整参数/正文 | Java 编译与测试通过 |
| M-08 缺少安全响应头 | 已实现 | Nginx HSTS/CSP/Permissions-Policy/nosniff；CSP 无 `unsafe-inline/eval` | 配置复核；部署响应头黑盒待验收 |
| M-09 登录仅用户名限流 | 已实现 | Redis 全局/IP/用户名+IP 计数、递增冷却和验证码门槛；成功不清除其他 IP 记录 | Maven 编译/测试；压测与告警联动待发布环境 |
| M-10 Actuator 暴露过宽 | 已实现 | prod 仅 health/prometheus，独立管理端口、隐藏详情、管理认证/ACL 配置 | 生产配置启动黑盒待验收 |
| M-11 上传/推理资源配额 | 已实现 | 附件按用途上限；数组元素/解压大小；推理队列/并发/超时配置；连接器响应上限 | Python/附件单测；性能压测待发布环境 |
| M-12 离线包迁移目录错误 | 已实现 | 构建脚本改用编译产物中的 Flyway 迁移目录 | PowerShell 语法检查通过；干净 Windows 构建待 RC |
| M-13 离线包完整性复核不足 | 已实现 | zip-slip/重复路径/数量/体积/CMS/逐文件 SHA-256/必需文件；安全解压后复核 | PowerShell 语法检查通过；签名安装/回滚演练待 RC |
| M-14 前端资源过大 | 已实现门禁 | 路由懒加载、Element UI/图表拆包、本地 SVG sprite；CI bundle 预算 | entry 1000.9 KiB、最大异步 477.1 KiB，预算通过 |

## 当前验证摘要

- `mvn --batch-mode test`：73 项通过，5 项因本机无 Docker/IoTDB 按条件跳过；CI 不允许跳过 Testcontainers。
- `python -m pytest tests/test_safe_numpy_loading.py tests/test_offline_npy_converter.py`：5 项通过。
- `npm run build:prod`、`npm run check:bundle`：通过；`npm audit --omit=dev --audit-level=high`：0 high/critical；构建期仅 low/moderate，时限例外见 `SECURITY_EXCEPTIONS.md`。
- Playwright：本机 Chromium/Edge 10 项通过、4 项因无后端凭据跳过；Firefox/WebKit 运行时下载超时，CI 安装后必须运行全矩阵。
- OWASP Dependency-Check 已按 CVSS 7 接入 `security-gates` profile；本机 NVD 首次同步超时，CI 必须提供 NVD API key/共享缓存并成功扫描。SpotBugs/JaCoCo 同 profile 执行。

## 发布前外部关闭项

1. 轮换数据库、Redis、管理员、采集器、推理、IoTDB、Druid 等实际生产秘密并移除一次性管理员秘密。
2. 建立低代码独立 schema/最小权限账号和出站代理最终白名单、DNS/地址 ACL；验证应用主账号无法访问低代码 schema，反之亦然。
3. 在有 Docker 的 CI 执行所有 Testcontainers；完成 SCA、secret scan、SpotBugs/JaCoCo 和签名离线包演练。
4. 使用 Chrome/Edge/Firefox 当前及前 4 个主要版本以及真实 Safari 15+ 完成黑盒、性能、备份恢复和整体回滚验收。

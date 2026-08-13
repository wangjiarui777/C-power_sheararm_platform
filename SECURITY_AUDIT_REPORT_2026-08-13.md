# RuoYi-Vue 工业 PHM 平台安全与质量审计报告

审计日期：2026-08-13  
审计对象：当前工作区快照（含审计期间并行出现的未提交低代码模块）  
审计方式：源码与配置审查、敏感信息扫描、权限/数据流追踪、Maven 测试、Vue 生产构建、npm 漏洞审计、部署脚本审查。

## 1. 结论

当前版本不应直接用于生产发布。已确认 3 项严重问题、6 项高风险问题、14 项中风险问题，以及多项可靠性、测试和部署不足。

最高优先级风险是：

1. 正式推理服务会对诊断上传的 NumPy 文件执行 `allow_pickle=True`，可导致 Python 任意代码执行。
2. 通用上传允许 HTML，并将文件映射为同源可访问资源；JWT 与“记住密码”又都可被前端脚本读取，形成会话/密码窃取链。
3. 初始化、前端和部署流程公开并继续使用已知管理员默认口令，且没有可靠的首次登录强制改密闭环。
4. 公告内容未经净化直接 `v-html` 渲染，构成存储型 XSS。
5. 新增低代码运行时可绑定 `sys_*` 核心表，绕过原有业务服务的数据范围、字段脱敏和业务校验。

“所有不足”在这里指当前约 9.16 万行代码/配置中，通过静态审查和可执行验证能够举证的问题。没有运行中的完整生产拓扑、真实角色矩阵、Docker 集成环境和渗透授权，因此不能声称数学意义上的零遗漏。

## 2. 审计范围与验证结果

- 扫描文件约 962 个，其中 Java 477 个、Vue/JS 231 个、SQL 20 个。
- 主要源码/配置约 752 个文件、91,636 行。
- 最终快照 `mvn test`：通过；共 68 个测试，5 个集成测试因 Docker 不可用而跳过。
- 最终快照 `npm run build:prod`：通过但有体积警告；入口约 1.09 MiB，多项 JS/CSS/图片超过推荐阈值。审计中途曾因并行开发中的低代码 SCSS 缺失闭合大括号失败，随后被修复，说明本次工作区并非不可变快照。
- `npm audit`：116 个告警（9 critical、31 high、65 moderate、11 low）。
- `npm audit --omit=dev`：生产依赖 6 个告警（1 high、1 moderate、4 low）。
- Java OWASP Dependency-Check 与 Python `pip-audit`：首次漏洞库同步在限定时间内未完成，未据此猜测结果。
- `.env` 已被 Git 忽略，但当前本机配置包含弱/默认型口令和密钥；报告不记录具体值。

## 3. 严重问题

### C-01 正式推理链路对不可信 NPY 执行 Pickle 反序列化

证据：

- `ruoyi-sensor/inference/utils_signal.py:124-126` 使用 `np.load(..., allow_pickle=True)`。
- `ruoyi-sensor/inference/inference_service.py:332-352` 的正式 `_load_signal_from_path` 调用该加载器。
- `ruoyi-sensor/inference/inference_service.py:1188-1205` 将受信路径中的诊断输入传入上述函数。
- Java 侧允许用户上传 `.npy` 作为诊断输入，再把存储路径交给内部推理服务。

影响：拥有诊断上传/执行能力的账号可提交包含对象数组的恶意 NPY，在 Python 服务账户权限下执行任意代码，进而读取模型、内部令牌、本机文件或横向访问内网。

修复：

- 所有用户或采集器可控输入必须改为 `allow_pickle=False`。
- 只接受纯数值、固定 dtype、固定维度和受限元素数量的 NPY；拒绝 object dtype、结构化对象和压缩炸弹式 NPZ。
- 在隔离进程/容器中解析，设置 CPU、内存、文件大小和超时限制。
- 为恶意 object-array NPY 增加回归测试，确保在模型加载前拒绝。

### C-02 同源 HTML 上传可窃取会话和“记住的密码”

证据链：

- `ruoyi-common/.../MimeTypeUtils.java:29-39` 的默认上传白名单包含 `html`、`htm`。
- `ruoyi-admin/.../CommonController.java:74-88` 允许任意已登录用户调用通用上传，没有细粒度权限。
- `ruoyi-framework/.../ResourcesConfig.java:36-42` 将整个 profile 目录映射到 `/profile/**`。
- `ruoyi-framework/.../SecurityConfig.java:107-110` 匿名放行所有匹配 `/**.html` 的 GET。
- `ruoyi-ui/src/utils/auth.js:5-10` 把 JWT 放在 JavaScript 可读 Cookie 中。
- `ruoyi-ui/src/utils/jsencrypt.js:8-28` 把用于解密“记住密码”的 RSA 私钥发布到浏览器。

影响：攻击者上传主动 HTML 后，只需诱导用户访问同源资源，即可读取 JWT；若用户启用记住密码，还能读取 Cookie 并使用公开私钥还原密码。部署时经 `/prod-api/profile/...` 反向代理会进一步强化同源条件。

修复：

- 从通用白名单移除 HTML/HTM/SVG/SWF 等主动内容。
- 上传对象放在独立不可执行域名或对象存储域名，强制 `Content-Disposition: attachment`、固定安全 Content-Type、`nosniff`。
- 不再由 JavaScript 持有长期 JWT；优先使用服务端设置的 `HttpOnly; Secure; SameSite` 会话 Cookie，并同步设计 CSRF 防护。
- 删除可逆“记住密码”，改为只记用户名或使用服务端持久化的可撤销 remember-me 令牌。

### C-03 已知默认管理员口令贯穿初始化与部署

证据：

- `sql/ry_20260417.sql:69-70` 为 `admin` 和 `ry` 写入同一个已知 BCrypt 密码哈希。
- `ruoyi-ui/src/views/login.vue:77-80` 默认填充 `admin/admin123`。
- `setup/DEPLOY.md:365-368` 明示管理员及 Druid 默认口令。
- `setup/setup-env.ps1:16`、`setup/phm-smoke-test.ps1:2-4` 使用固定口令。
- `sql/ry_20260417.sql:554` 将新用户初始密码设为 `123456`。
- 初始化 SQL 未建立可靠的首次登录强制改密配置；前端提示也不能替代服务端强制策略。

影响：按文档或脚本部署的实例可能被直接接管；固定数据库 root 口令还会扩大到数据库主机。

修复：

- 初始化时随机生成一次性管理员密码，通过安全渠道交付，首次登录前不可启用业务入口。
- 服务端强制首次登录改密；未改密账号只能访问改密/退出接口。
- 删除前端预填密码、文档固定口令和脚本默认口令；脚本改为必填安全参数或凭据管理器读取。
- 轮换当前本机 `.env` 中的数据库、JWT、采集器、推理和控制台秘密。

## 4. 高风险问题

### H-01 公告存储型 XSS 可接管所有查看者会话

- `ruoyi-ui/src/layout/components/HeaderNotice/DetailView.vue:45` 使用 `v-html="detail.noticeContent"`。
- `SysNoticeController` 和 `SysNoticeServiceImpl` 保存正文前没有 HTML 净化。
- 公共配置还把 `/system/notice/*` 放入 XSS 排除路径；JSON body 本身也不应依赖通用字符替换过滤器。
- 结合 C-02 中可读 JWT Cookie，恶意公告可窃取所有查看者会话。

修复：服务端使用明确允许列表净化 HTML（标签、属性、URL 协议），前端再做一层 DOMPurify；若不需要富文本则按纯文本渲染。增加事件属性、`javascript:`、恶意 SVG/MathML 回归用例。

### H-02 低代码可映射并操作系统核心表

- `LowCodeRuntimeService.java:151-169` 仅排除 `lc_*` 与 `flyway_*`，会枚举 `sys_user`、`sys_role`、`sys_config` 等表。
- `LowCodeMetadataValidator.java:50-89` 只校验标识符和字段存在性，没有业务表前缀/数据源白名单。
- `LowCodeProjectController.java:62-72` 具有设计权限的用户可检查全库结构并迁移旧表。
- 运行时直接使用 JDBC 读写目标表，会绕过原 Service 的数据范围、密码哈希保护、管理员保护和业务审计。

影响：低代码设计权限可能间接转化为读取密码哈希、篡改角色/菜单/系统配置甚至提权的能力；`LOWCODE_RUNTIME_WRITE_ENABLED=true` 时尤其危险。

修复：低代码使用独立数据库账号与独立 schema；只允许显式登记的业务表/视图；硬拒绝 `sys_*`、`qrtz_*`、`gen_*`、审计/凭据/配置表；发布时再次服务端校验，不能只信草稿校验结果。

### H-03 低代码 HTTP 连接器提供有权限的 SSRF 能力

- `LowCodeConnectorService.java:74-83` 只验证 http/https、host 非空等语法，不拒绝 localhost、内网、链路本地、云元数据地址或 DNS 重绑定。
- `LowCodeHttpActionHandler.java:26-40` 会向固定 base URL + 白名单 path 发起 POST。

影响：拥有连接器/发布权限的账号可探测或调用 Java 服务能访问的内部 HTTP 服务。权限较高，但一旦角色误配会成为横向移动通道。

修复：连接器目标采用管理员维护的服务注册表；解析并验证所有 A/AAAA 地址；拒绝 loopback、private、link-local、multicast、metadata；禁跟随跨主机重定向；使用出站代理/防火墙和每连接器独立凭据。

### H-04 令牌与密码持久化设计放大任何 XSS

- JWT Cookie 未设置 `HttpOnly`、`Secure`、`SameSite`、显式 path 和合理生命周期。
- “记住密码”把密码加密 30 天，但私钥同包发布，因此等价于可逆明文存储。

影响：任意同源 XSS、恶意浏览器扩展或本机低权限恶意软件都可直接取得会话与密码；用户在其他系统复用密码时影响扩大。

修复：见 C-02；同时在改密后撤销所有旧令牌，提供会话列表和一键下线。

### H-05 服务端没有强制新密码复杂度

- `SysProfileController.java:93-110` 仅检查旧密码正确、新旧不同，然后直接 BCrypt。
- `SysUserController.java:195-201` 管理员重置密码同样直接加密。
- 搜索不到服务端的新密码长度、字符类别、常见口令或泄漏口令校验；现有规则主要在前端，可绕过。

影响：用户可把密码改成极短弱口令，管理员也可批量重置为相同弱口令。

修复：建立单一服务端 PasswordPolicyService，在注册、创建、导入、个人改密和管理员重置全部调用；至少校验长度、常见/泄漏口令、用户名相关性，并支持组织策略。

### H-06 前端供应链严重老化

- 全依赖树 `npm audit`：116 项，其中 9 critical、31 high。
- 生产依赖仍有 6 项，其中 `brace-expansion` high、ECharts XSS moderate、Vue 2 ReDoS low。
- Vue 2.6、Vue CLI 4、Webpack 4 生态已长期停滞，很多修复需要主版本迁移。

影响：构建机处理不可信分支/资源时面临代码执行、路径遍历与 DoS；运行时图表和 Vue 解析也有已知风险。

修复：短期锁定并升级可无破坏修复的传递依赖；中期迁移 Vue 3 + Vite 或至少 Vue 2.7/LTS 可支持方案；CI 同时执行 `npm audit --omit=dev` 和完整供应链审计，使用只读最小权限构建凭据。

## 5. 中风险问题

### M-01 Swagger/OpenAPI 在所有 profile 匿名开放

`SecurityConfig.java:110` 无条件放行 `/v3/api-docs/**`、`/swagger-ui/**`。生产 Nginx 的 `/prod-api/` 没有显式阻断这些路径。会暴露完整端点、模型和测试接口。应以 profile/配置控制，生产默认 404 或仅管理网可达。

### M-02 Swagger 测试 Controller 在生产生效并泄露明文样例口令

`TestController.java:27-35` 没有 `@Profile("dev")`，列表接口向任何已登录用户返回包含明文 `admin123` 的对象；写接口也无细粒度权限。虽然数据仅在内存，仍会误导密码复用和扩大接口面。应仅 dev/test 装配或删除。

### M-03 通用上传/下载缺少细粒度权限和资源归属

`CommonController` 的上传、批量上传、下载、可选删除仅要求“已登录”。下载只校验扩展名和 `..`，没有文件所有者、业务对象、一次性下载令牌或审计。应迁移到附件服务，通过数据库对象 ID + 数据范围授权，禁止客户端传物理路径和任意 delete 标志。

### M-04 低代码 UPDATE/DELETE 没把数据范围写进最终 SQL

`LowCodeRuntimeService.java:115-140` 先调用带范围的 `get`，真正的 UPDATE/DELETE 却只按主键执行。当前同事务降低了常规利用概率，但这是 TOCTOU/防御缺失，也给未来异步化或事务传播变化留下越权风险。最终 DML 必须包含同一 data-scope predicate，并校验影响行数为 1。

### M-05 低代码动作幂等键不是必填，且先执行后记账

动作接口允许缺失 `Idempotency-Key`；`invoke` 先执行外部动作再写 `lc_action_log`。唯一索引无法阻止 `NULL` 重复，重试会重复调用外部系统。应在副作用动作中强制幂等键，先原子占位为 RUNNING，再执行并更新状态。

### M-06 模型/实验脚本显式允许不安全 PyTorch 反序列化

`04.4_diagnose_unlabeled_target.py:47-54` 和 `04_diagnose_unlabeled_target_closed_v8-test.py:101-105` 使用 `torch.load(..., weights_only=False)`。正式模型有 SHA-256 校验可降低风险，但模型供应链或本地制品被替换时仍可代码执行。优先使用纯 state_dict + `weights_only=True` 或 safetensors，并让哈希清单由独立签名保护。

### M-07 通用 HTTPS 工具完全绕过 TLS 验证

`HttpUtils.java:218-231` 使用 `SSL`、TrustAnyTrustManager 与 TrustAnyHostnameVerifier；同一类还会记录完整请求参数和响应。当前核心路径调用较少，但任何未来复用都会遭中间人攻击和日志泄密。应删除该方法，使用系统信任库、TLS 1.2/1.3 和敏感字段脱敏日志。

### M-08 生产缺少 CSP/HSTS/Permissions-Policy

Nginx 已配置 `nosniff`、`SAMEORIGIN` 和 Referrer-Policy，但没有 CSP、HSTS、Permissions-Policy。CSP 尤其能降低存储型 XSS 的影响。修复 XSS 后再引入 nonce/hash 型 CSP，禁止 `unsafe-inline`，并在确认全站 HTTPS 后启用 HSTS。

### M-09 登录只有按用户名锁定，没有入口级 IP/设备限流

项目有 `@RateLimiter` 基础设施，但 `/login` 未使用；Nginx 也没有 `limit_req`。攻击者可轮换用户名绕过单账号失败次数限制，验证码会增加成本但不是完整限流。应按 IP、用户名、设备指纹和全局并发做分层限流及告警。

### M-10 Actuator 暴露范围过宽

`application-prod.yml:86-100` 虽绑定 127.0.0.1，但启用 `metrics`、`loggers` 且 health `show-details: always`。若端口转发、代理或主机被低权限用户访问，会泄露内部细节并允许更改日志级别。生产只开放 health/prometheus，独立认证授权并限制管理网。

### M-11 生产上传上限过大，缺少推理资源配额

生产 multipart 上限为 128/130MB，诊断文件还会解析、转换和进入模型推理。仅文件大小限制不足以防止巨大数组、异常维度、压缩比和并发耗尽。应在文件头解析后限制元素数、shape、dtype、推理队列、每用户并发与超时。

### M-12 离线包构建引用不存在的迁移目录

`deployment/build-offline-package.ps1:76` 复制 `ruoyi-admin/src/main/resources/db/migration`，但真实迁移在 `ruoyi-admin/src/main/java/db/migration`。脚本会在此处失败，离线包无法构建。应删除冗余复制或复制编译后的迁移类/源码清单，并用干净 Windows VM 验证。

### M-13 离线包安装后没有逐文件完整性复核

`verify-offline-package.ps1` 只验证 ZIP 的 CMS 签名和证书指纹，不解压并验证 `SHA256SUMS.json`，也不检查必需目录、模型清单和安装后文件。签名可保护传输中的 ZIP，但不能发现解压/安装后的篡改或缺件。应在签名通过后安全解压，阻止 zip-slip，逐项核对 hash/size/路径，再安装并在启动前复核关键二进制与模型。

### M-14 前端入口和核心资源体积过大

最终生产构建虽通过，但入口约 1.09 MiB；ECharts chunk 约 461 KiB、公共依赖约 352 KiB、app JS 约 279 KiB、app CSS 约 322 KiB，登录背景图约 509 KiB，均触发构建警告。工业现场的弱网、旧终端和冷启动会明显受影响。应按路由懒加载图表/编辑器、移除未使用 Element UI 组件、压缩图片、分析重复依赖，并让 `npm run check:bundle` 成为不可绕过的门禁。

## 6. 低风险与工程不足

1. 安全响应头配置注释称“禁用 HTTP 响应标头”，实际仅关闭 cache-control 并设置 frame options，容易误维护。
2. XSS 过滤采用全局字符替换思路并带大量 exclusions，不能替代按输出上下文编码和富文本净化。
3. `HttpUtils` 会在 INFO/ERROR 中记录完整 URL、参数和响应，未来一旦参数含令牌会进入日志。
4. 当前 `.env` 虽被忽略，但包含弱/默认型秘密；本地泄漏、备份或截图仍有风险。
5. 生产配置校验只检查秘密长度和少量黑名单，没有熵、重复秘密、已泄漏口令或密钥分离检查。
6. 前端 `window.open` 多处没有统一使用 `noopener,noreferrer`；外链路由还应限制协议和可信域名。
7. 公告详情 GET 对所有登录用户开放是业务需要，但返回前应只允许正常状态公告，并明确可见范围；当前按 ID 直接读取没有受众范围模型。
8. 监控 overview 服务会把完整 recent 列表排序后再组装，数据量增长时应在数据库/IoTDB 端限制时间窗和行数。
9. Tomcat 最大线程 800、最小空闲 100 偏大，需按数据库连接池、内存和推理吞吐容量测试，否则会把过载传导到依赖服务。
10. 代码中仍有明显乱码注释/错误信息，降低审查和运维可读性，也可能造成错误响应不可理解。
11. 新低代码代码在审计期间并行变化且未提交；其权限菜单、数据库迁移、配置和前端不是一个可复现提交快照。
12. 低代码 export 在内存中一次性生成 ZIP；元数据增大后应设置大小上限或流式输出。
13. 低代码数据库元数据检查会枚举整库，对大库昂贵且泄露 schema；应限制 schema/table 列表并缓存。
14. `application.yml` 默认激活 prod，能避免误以 dev 运行，但也让普通本地启动依赖大量秘密；开发文档和启动脚本必须始终显式选择 dev。
15. Java 根项目没有 JaCoCo/覆盖率门禁、SpotBugs、Error Prone、Checkstyle 或 SAST 门禁。

## 7. 测试与可维护性不足

- 约 9.16 万行主要代码只有 33 个测试文件。
- `ruoyi-common`、`ruoyi-system`、`ruoyi-framework`、`ruoyi-quartz`、`ruoyi-generator` 在 Maven 运行中基本没有测试；这些模块恰好承载认证、权限、文件、任务和低代码高风险逻辑。
- 4 个数据库/Redis/IoTDB 集成测试因 Docker 不可用而跳过；“BUILD SUCCESS”不代表这些链路通过。
- 新低代码模块只有规则引擎的 3 个单元测试和一个因 Docker 不可用而跳过的迁移测试；没有 Controller 权限契约、运行时数据范围、并发、系统表隔离或真实数据库集成测试。
- 前端仅 2 个 Playwright spec，没有单元测试、组件测试和 lint script；最终生产构建通过但存在显著体积警告。
- CI 使用 JDK 25，但项目目标 Java 17；应同时在真实运行时 JDK 17 验证，避免只在更高版本编译器上通过。
- CI 的 `npm audit --omit=dev --audit-level=high` 看不到 110 个构建链漏洞；应另设供应链报告而不是全部忽略。
- Python requirements 固定了版本，但没有哈希锁文件、SBOM、依赖许可证/漏洞门禁；`pip-audit` 本次未完成。

建议最低门禁：

1. 后端：JDK 17 `clean verify`、JaCoCo、SpotBugs/Semgrep、OWASP Dependency-Check、数据库/Redis/IoTDB 集成测试。
2. 前端：`npm ci`、lint、单元测试、生产构建、bundle budget、Playwright、生产与全量 npm audit 分开报告。
3. Python：hash-locked requirements、`pip-audit`、Bandit/Semgrep、恶意 NPY/模型回归测试、受限进程测试。
4. 部署：SBOM、签名、逐文件 hash、干净离线 VM 的安装/升级/回滚/恢复演练。

## 8. 修复优先级

### 24 小时内

1. 禁止 `allow_pickle=True` 处理任何诊断输入，暂停 NPY 上传或只接受 `allow_pickle=False` 可解析的纯数值文件。
2. 从通用上传移除 HTML/HTM，并临时阻断 `/profile/**/*.html`。
3. 轮换所有默认/弱秘密，停用默认 admin/ry 口令，删除前端预填密码。
4. 公告改为纯文本渲染或在服务端严格净化后再恢复富文本。
5. 保持 `LOWCODE_RUNTIME_WRITE_ENABLED=false`；在加入系统表/数据源白名单前不要开放低代码设计与发布权限。

### 7 天内

1. 重构 JWT/remember-me 存储，增加 CSP、HSTS 和登录限流。
2. 服务端统一密码策略与首次登录强制改密。
3. 修复低代码系统表隔离、最终 DML 数据范围、连接器 SSRF 和动作幂等。
4. 修复前端构建，升级生产依赖，删除生产 TestController/公开 Swagger。
5. 修复离线包迁移路径和安装后完整性校验。

### 30 天内

1. 迁移老旧 Vue CLI/Webpack 供应链。
2. 建立后端、前端、Python 的 SAST/SCA/覆盖率门禁。
3. 为权限矩阵、对象级授权、附件、低代码、推理和部署恢复建立自动化集成测试。
4. 完成一次授权范围内的黑盒渗透测试：匿名、普通用户、业务管理员、低代码设计者、采集器和内部推理身份分别验证。

## 9. 未发现或已具备的正向控制

- 传统 MyBatis Mapper 主要使用参数绑定；本次未确认经典 `${}` SQL 注入。
- 生产 profile 有启动时秘密、路径、Origin、TCP 绑定和内部推理 URL 校验。
- 采集器、WebSocket 票据、附件隔离/病毒扫描、IoTDB 配置校验已有一定测试。
- Nginx 禁止版本泄露并限定 TLS 1.2/1.3。
- 低代码动态标识符使用正则和反引号，降低了直接 SQL 注入；核心问题是授权边界而非字符串注入。
- Maven 当前源码可编译且最终有 68 个测试通过，但不能抵消上述未覆盖风险。

## 10. 复测标准

完成修复后至少满足：

- 恶意 object-array NPY 被拒绝，Python 不执行 Pickle。
- HTML/HTM/SVG 等主动内容不能通过通用上传，也不能以内联方式从业务域打开。
- 公告 XSS 测试载荷只作为文本或被净化，不触发脚本/事件/危险 URL。
- 浏览器脚本无法读取认证令牌或任何可逆密码；改密后旧会话失效。
- 全新安装不存在固定管理员、数据库、Druid、JWT、采集器或推理秘密。
- 低代码无法发现、绑定或操作系统/凭据/审计表，运行时 DB 账号也没有权限。
- 低代码 UPDATE/DELETE 的最终 SQL 带数据范围，SSRF 测试覆盖 IPv4/IPv6/DNS 重绑定/重定向。
- Maven、前端生产构建、Python 测试、全部集成测试和离线 VM 演练通过，且无意外 skipped。
- 生产依赖不存在 critical/high；构建依赖风险有明确时限、隔离和升级计划。

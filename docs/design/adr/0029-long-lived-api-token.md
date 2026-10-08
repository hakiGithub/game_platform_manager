# ADR-0029: 长期可吊销 API Token（凭证方案 A）

- 状态：Accepted
- 日期：2026-10-02
- 关联：[ADR-0015](0015-multi-database-dialect-support.md)（多方言建表/迁移约束）、[ADR-0024](0024-cloud-drive-host-capability.md)（`ROLE_ADMIN` 收紧先例）、PLUT-35 §5（上游规格）、PLUT-36（本 ADR 对应的设计票）
- 实施细节（DDL 全文、文件级改造点、测试清单）：[api-token-backend-design.md](../specs/api-token-backend-design.md)

## Context

`gpmcli`（游戏管理平台命令行）需要一个可在脚本/agent 环境中长期使用的凭证。平台现状只有登录 JWT：
`JwtTokenProvider` 用对称密钥签发、TTL 固定 7 天（`jwt.expiration=604800000`）、**服务端无状态、无存储、不可吊销**，
`/auth/refresh` 依赖用户手持旧 token 换取新 token。把 JWT 直接塞进 `GPM_TOKEN` 意味着：凭据 7 天必失效、
无法单独吊销某个自动化用途、审计上无法区分"人"与"自动化"。

PLUT-35 §11 Q1 选定方案 A：**新增长期 API Token，与既有 JWT 并存**。约束是纯增量、向后兼容——
既有 JWT 登录/刷新/登出与前端零改动，不引入新依赖（复用 JDK + MyBatis-Plus + 既有 `Result`/`ResultCode`），
并且受 ADR-0015 限制：`db/migration/` 迁移体系**仅对 SQLite 生效**，MySQL/PG 靠 `schema-{方言}.sql` 一次性建最新结构。

设计需要回答的开放问题（PLUT-35 §12.4 明确要求 S3 定稿）：令牌怎么存、作用域与过期语义如何裁定、
`last_used_at` 的写放大节流策略、以及"谁能管理令牌"这条权限回路。

## Decision

### D1 令牌形态与存储：高熵随机明文 + SHA-256 摘要落库

明文 = `gpm_` + `base64url(32 随机字节)`（`SecureRandom` + `Base64.getUrlEncoder().withoutPadding()`，
定长 47 字符，**只在创建响应里返回一次**）；库里只存 `SHA-256(明文)` 的小写十六进制（64 字符，`java.security.MessageDigest` + `HexFormat`）。
高熵随机值不含口令，无需加盐/慢哈希，与既有 `password_hash` 列风格一致；校验按哈希等值查活跃记录，
不做逐条比对，故无需常数时间比较。索引 `uk_api_token_hash` 建在哈希列上，既是查询路径也是唯一性约束。

`token_prefix` 存明文前 12 字符（含 `gpm_`），供列表展示与人工识别，非机密。

### D2 凭证判定收敛为单一入口 `ApiTokenService.authenticate(plainToken, httpMethod)`

有效性（存在 / 未吊销 / 未过期）与作用域（`read` 只允许 GET/HEAD/OPTIONS）是**同一个安全决策的两半**，
拆成两个方法会让调用方自己拼装裁定结果，测试也要各测一遍。合为一个方法后：

- 返回三值裁定 `ALLOW / INVALID / SCOPE_DENIED`，外加 `username / tokenId / tokenName / scope`（仅 ALLOW 时有值）。
- `INVALID` 刻意**不区分**"不存在 / 已吊销 / 已过期"三种原因——对外只说"令牌无效"（不给探测 oracle），
  具体原因进服务端日志。
- 过期判定在 **Java 侧**用 `LocalDateTime` 比较，不写 `datetime()`/`NOW()` 一类的 SQL 日期函数：
  SQLite 把 DATETIME 存成字符串，SQL 侧跨方言比较不可靠。

判定入口不调用 `UserDetailsService`（返回 `username` 交给过滤器），因此实现只依赖 Mapper 与配置——
避免 `过滤器 → 服务 → UserDetailsService → AuthenticationManager → 过滤器` 这条潜在环，也让服务层测试不需要 Spring Security 上下文。

### D3 `JwtAuthenticationFilter` 加分支，JWT 路径逐行不动

`Authorization: Bearer <token>` 剥掉前缀后，**先按 `gpm_` 前缀分流**：命中即由令牌分支处理并自行写出 401/403，
不再触碰 `jwtTokenProvider`；未命中走既有 `validateToken` 路径，代码位置与语义保持原样。
既有的 `catch (Exception) → handleAuthenticationError(401)` 包住两条路径，异常一律 fail-closed。

### D4 `api_token` 表：新增核心表，吊销用 `revoked` 而非逻辑删除

列与规格一致（`name / token_prefix / token_hash / scope / user_id / expires_at / revoked / revoked_at / last_used_at` + BaseEntity 标准列）。
两个要点：

- **吊销只置 `revoked=1` + `revoked_at`，`is_deleted` 恒为 0**。`BaseEntity` 的 `@TableLogic` 会把 `is_deleted=1` 的行
  从所有查询里隐藏，用逻辑删除做吊销会让"已吊销"从列表和审计里消失；吊销记录必须仍然可见。
- `expires_at` 允许 NULL（语义 = 永不过期，留给直连库运维），但**创建 API 不接受 NULL**：
  `expiresInDays` 必填范围 1–3650、缺省 365。理由——通过 HTTP 接口不应能造出无限期凭据。

### D5 API Token 主体不带 `ROLE_ADMIN`（切断"令牌管令牌"的回路）

若照上游规格伪码原样把 `loadUserByUsername()` 的权限集直接放进 `SecurityContext`，
则令牌归属内置 admin 时持有 `ROLE_ADMIN`，叠加 §5.5 的 `/tokens/** → hasRole(ADMIN)` 之后，
**任何一枚 read 令牌都能自己创建/吊销令牌**，也能访问 `/cloud/**` 凭证资产——作用域限制形同虚设。

决策：令牌认证出的主体权限集固定为 `ROLE_USER` + 标记权限 `ROLE_API_TOKEN`，**由平台设计保证 API 令牌永远不是管理员主体**，
而不是依赖"记得给管理端点加条件"。直接后果：

- `/tokens/**`、`/cloud/**` 等 `hasRole('ADMIN')` 规则对 API 令牌天然 403——凭据自复制与越权路径一次关闭。
- v1 的 CLI 命令面（`/system`、`/hosts`、`/instances`、`/tasks`、`/games`、`/backups`、`/schedules`、实例启停、任务取消/重试）
  全部只需 `authenticated()`，实测源码中除 `/cloud/**` 外无任何 `hasRole/hasAuthority/@PreAuthorize`，功能不受影响。
- `write` 令牌的写权限来自"方法不是安全方法"这一作用域判定，与角色无关。

令牌身份与用户身份在审计上仍需区分：日志行记 `token name`，任务 `submitter`（`authentication.getName()`）仍是归属用户名，语义不变。

### D6 `last_used_at` 节流：一条条件 UPDATE 在库里判定，不引入内存状态

写放大来源是"每次请求一次 UPDATE"。定稿：**由 SQL 自己判定是否该写**，不查后再判、不用进程内缓存。

```sql
UPDATE api_token SET last_used_at = :now
WHERE id = :id AND (last_used_at IS NULL OR last_used_at < :now - :throttle)
```

`throttle` 默认 60s，可配 `game-platform.api-token.last-used-throttle-seconds`（配 0 = 每次都写）。
好处：一次判定 + 一次条件写、并发下最多多写一行、多实例共享同一节流判断、三种方言同一条语句可用。
只在 ALLOW 分支调用，`best-effort`：失败记 WARN 不影响请求。

同时**给 `api_token` 的 `update_time` 刻意不挂自动刷新**（MySQL 不写 `ON UPDATE CURRENT_TIMESTAMP`、SQLite 不建 `trg_api_token_update_time`，
与 V1.10 的四个触发器惯例相反，但与新近表 `task_record`/`scheduled_task` 的 `TIMESTAMP NULL` 惯例一致）：
否则节流写会把 `update_time` 一起刷成"最近一次使用时刻"，`update_time` 就再也不能表达"这条凭据记录被改动过"。
`update_time` 由 MyBatis-Plus 填充，仅在创建/吊销等真实变更时变化。

### D7 403 也要有 JSON 体：补 `accessDeniedHandler`

`SecurityConfig` 已有 `authenticationEntryPoint`（401 JSON），但没有 `accessDeniedHandler`，
所以 Spring 判定的 403（`/cloud/**`、`/tokens/**` 越权）目前是**空响应体**。CLI 侧按"非 JSON → 网络类错误"归类，
会把权限不足误报成平台不可达。决策：补一个与 EntryPoint 对称的 `accessDeniedHandler`，写 `Result.fail(ResultCode.FORBIDDEN)`（HTTP 403）。
过滤器主动写出的作用域 403 走同一条 `ResultCode.FORBIDDEN`，两类 403 语义统一。

### D8 多方言同步点：四份 schema + 一份 SQLite 迁移

`api_token` 必须同时出现在：`db/schema-sqlite.sql`、`db/schema-mysql.sql`、`db/schema-postgresql.sql`、
测试用的 `core/src/test/resources/db/schema-h2.sql`，存量 SQLite 库另加 `db/migration/V1.11__api_token.sql`
并在 `SchemaMigrationRunner` 注册 `ensureSqlFileExecuted("api_token", "db/migration/V1.11__api_token.sql")`
（标志表法与 V1.5/V1.7 同构）。MySQL 索引一律内联 `KEY`/`UNIQUE KEY`；PG 用独立 `CREATE [UNIQUE] INDEX IF NOT EXISTS`。

MySQL/PG **存量库无增量机制**（ADR-0015 已知限制）：这两个方言的既有部署需人工执行建表 SQL，发布说明必须标注。

## Consequences

- 平台出现两类可长期并存的凭证：JWT（人/前端）与 API Token（脚本/CLI），共用同一个 `UserDetailsService` 主体加载路径，下游业务代码零改动。
- 令牌表数据量小（个位数～十几条），哈希唯一索引让认证恒定一次索引命中；每请求最多 2 次 SELECT + 每 60s 一次 UPDATE。
- 前端不做令牌管理页（v1 范围外），创建/吊销由持有 JWT 的管理员用 HTTP 接口完成。
- `is_deleted` 在令牌表上永远是 0：`@TableLogic` 的全表过滤对本表是"空转"，这是刻意为之，后来者勿改成用逻辑删除做吊销。
- API 令牌主体失去 `ROLE_ADMIN`，将来若有"CLI 也要调用的管理员级端点"，需要显式设计（新增权限位或专用端点），不能靠给令牌发 ADMIN 绕过。
- `docs/api/api-doc.md` 需新增"API 令牌管理模块"一节（与实现同 PR），PLUT-35 §12.5 的文档补齐建议在此落地。

## Alternatives

- **复用登录 JWT（方案 B）**：被否（PLUT-34/35 Q1）——无吊销、无审计边界，自动化身份退化为"某个人的登录态"。
- **长期 JWT（方案 C）**：被否——仍需服务端记录才支持吊销，等于表还是要建，只是把哈希存储换成 claim 解析，安全性不升。
- **每条令牌独立列存明文 / AES 加密存储**：被否——认证只需等值查找，明文/可解密存储把库泄漏的爆炸半径从"可冒用"扩大到"必然可冒用"；SSH key 的摘要存储范式已足够。
- **作用域做成细粒度权限位（`hosts:r`、`instances:w`…）**：被否——v1 命令面只需要"读/写"两档，细粒度会连带引入权限判定表与迁移成本；`scope` 用 VARCHAR 存 `read`/`write` 保留了以后加值的空间。
- **`last_used_at` 用进程内缓存节流**：被否——多实例/重启即失效，且为省一次已受 60s 节流约束的写引入了新的状态源。
- **API 令牌保留归属用户完整角色，改为在 `/tokens/**` 上加"排除 API 主体"的判定**：被否——把"令牌不能管令牌"这条不变量分散成一处授权表达式，一旦漏配就是凭据自复制；D5 在主体侧一次性关闭，改动面更小、更难写错。
- **给 `api_token` 也补 `update_time` 触发器以对齐 V1.10**：被否（见 D6）——会让"最近使用时间"污染"记录变更时间"。

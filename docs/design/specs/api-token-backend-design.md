# API Token（方案 A）后端设计与改造点清单

> 决策依据见 [ADR-0029](../adr/0029-long-lived-api-token.md)；本文是 T3（后端 API Token 实现）的直接输入，
> 目标是"照着就能写"：给出文件级改造点、四份方言 DDL 全文、接口签名、错误语义与测试清单。
>
> 契约基准：`haki/game_platform_manager@3c5df3e`。文中行号均为该 revision 的源码位置，实现时以实际代码为准。
> 原则：纯增量、向后兼容，**既有 JWT 登录/刷新/登出与前端零改动**，不引入任何新依赖。

---

## 1. 模块与接缝

```
                    ┌──────────────── 既有（不改） ────────────────┐
Authorization: Bearer <t>                                          │
   │ JwtAuthenticationFilter.getTokenFromRequest  (:81)            │
   ├─ t 以 "gpm_" 开头 ──► ApiTokenService.authenticate(t, method) │  ← 新增接缝（凭证判定）
   │                        ├ ALLOW ──► loadUserByUsername(username)│  （既有 UserDetailsService）
   │                        ├ INVALID ──► 401 JSON                 │  └─► SecurityContext 建立
   │                        └ SCOPE_DENIED ──► 403 JSON            │
   └─ 否则 ──► jwtTokenProvider.validateToken(t) … 原路径逐行不动 ──┘
```

`ApiTokenService` 是本设计的唯一深模块：小接口（5 个方法）、大行为（生成、摘要存储、有效性裁定、作用域裁定、
节流记账、吊销、列表投影）。过滤器与管理控制器都只是它的适配器，不各自复制判定规则。

### 1.1 接口签名（`core/src/main/java/com/gameplatform/service/ApiTokenService.java`）

```java
public interface ApiTokenService {

    /** 明文前缀常量，过滤器与生成器共用（"gpm_"）。 */
    String PLAINTEXT_PREFIX = "gpm_";

    /** 签发一枚令牌；返回体含明文（仅此一次可见）。 */
    ApiTokenCreatedVO create(ApiTokenCreateDTO dto);

    /** 当前归属用户名下的全部令牌（含已吊销），投影不含明文与哈希。 */
    List<ApiTokenVO> list();

    /** 吊销（幂等：已吊销再次调用不报错、不覆盖 revoked_at）。 */
    ApiTokenVO revoke(Long id);

    /** 凭证 + 作用域一次性裁定；httpMethod 传 request.getMethod() 的大写串。 */
    Decision authenticate(String plainToken, String httpMethod);

    /** best-effort 记录使用时间，内部按配置节流；失败只记日志，绝不抛出。 */
    void touch(Long tokenId);

    /** 裁定结果。INVALID 刻意合并"不存在/已吊销/已过期"，具体原因只在 reason 里供日志使用。 */
    @Data
    class Decision {
        public enum Outcome { ALLOW, INVALID, SCOPE_DENIED }

        private Outcome outcome;
        private String reason;   // 仅日志用：not_found | revoked | expired | scope_read
        private Long tokenId;
        private String tokenName;
        private String username; // 仅 ALLOW：归属用户，交给过滤器加载 UserDetails
        private TokenScope scope;
    }
}
```

**接缝纪律**：`Decision.ALLOW` 一定带 `tokenId/tokenName/username/scope`；非 ALLOW 一律只带 `reason`。
`authenticate` **不写库、不设 SecurityContext、不依赖 Spring Security 类**（只依赖 Mapper + 配置），
因此服务层测试不需要 Web/Security 上下文，也不会与 `AuthenticationManager` 形成 bean 环。

---

## 2. 表结构与四份方言 DDL

### 2.1 列定义

| 列 | 语义 | 备注 |
|---|---|---|
| `id` | 主键 | SQLite `INTEGER PRIMARY KEY AUTOINCREMENT` / MySQL `BIGINT AUTO_INCREMENT` / PG `BIGSERIAL` |
| `name` | 令牌名（人工识别与审计） | 必填、≤100；同一用户下未吊销令牌不允许重名（服务层查询判定，不建索引） |
| `token_prefix` | 明文前 12 字符 | 非机密，列表展示 |
| `token_hash` | `SHA-256(明文)` 小写 hex（64 字符） | **唯一索引**，认证查询路径 |
| `scope` | `read` / `write` | 默认 `read` |
| `user_id` | 归属 `sys_user.id` | 创建时取当前登录用户 |
| `expires_at` | 过期时刻，NULL = 不过期 | 创建 API 不接受 NULL（ADR-0029 D4） |
| `revoked` | 0/1 吊销标记 | **吊销不用 `is_deleted`**（ADR-0029 D4） |
| `revoked_at` | 吊销时刻 | |
| `last_used_at` | 最近使用（≥60s 节流） | |
| `create_time` / `update_time` / `is_deleted` / `remark` | BaseEntity 标准列 | `is_deleted` 恒为 0 |

`update_time` **不带自动刷新**（MySQL 无 `ON UPDATE CURRENT_TIMESTAMP`、SQLite 无触发器、PG 无触发器），
由 MyBatis-Plus `MyMetaObjectHandler` 在真实变更时填充——避免节流写污染"记录变更时间"（ADR-0029 D6）。

### 2.2 `db/schema-sqlite.sql`（追加为第 10 节；同时把文件头版本说明的表清单补上 api_token）

```sql
-- =====================================================
-- 10. API 令牌表 (api_token) — ADR-0029 长期可吊销凭证
-- =====================================================
CREATE TABLE IF NOT EXISTS api_token (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    name         VARCHAR(100) NOT NULL,
    token_prefix VARCHAR(16)  NOT NULL,
    token_hash   VARCHAR(128) NOT NULL,
    scope        VARCHAR(20)  NOT NULL DEFAULT 'read',
    user_id      INTEGER      NOT NULL,
    expires_at   DATETIME,
    revoked      INTEGER      NOT NULL DEFAULT 0,
    revoked_at   DATETIME,
    last_used_at DATETIME,
    create_time  DATETIME DEFAULT (datetime('now', 'localtime')),
    -- 刻意不建 trg_api_token_update_time：节流写 last_used_at 不得刷新 update_time（ADR-0029 D6）
    update_time  DATETIME DEFAULT (datetime('now', 'localtime')),
    is_deleted   INTEGER DEFAULT 0,
    remark       TEXT,
    FOREIGN KEY (user_id) REFERENCES sys_user(id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_api_token_hash ON api_token(token_hash);
CREATE INDEX IF NOT EXISTS idx_api_token_user_id ON api_token(user_id);
CREATE INDEX IF NOT EXISTS idx_api_token_is_deleted ON api_token(is_deleted);
```

### 2.3 `db/migration/V1.11__api_token.sql`（新建，存量 SQLite 库升级）

内容与 2.2 的 `api_token` 段落**逐字符一致**（同一份 DDL，含 `IF NOT EXISTS`），保证
"新库走 schema、老库走迁移"两条路径产出完全相同的结构。不要在这里写 `--` 之外的注释性 SQL 片段：
`SchemaMigrationRunner` 会剔除整行注释后按 `;` 拆分逐条执行（`SchemaMigrationRunner.java:99-111`）。

### 2.4 `db/schema-mysql.sql`（索引内联，MariaDB 兼容）

```sql
-- =====================================================
-- 10. API 令牌表 (api_token) — ADR-0029 长期可吊销凭证
-- =====================================================
CREATE TABLE IF NOT EXISTS api_token (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    token_prefix VARCHAR(16)  NOT NULL,
    token_hash   VARCHAR(128) NOT NULL,
    scope        VARCHAR(20)  NOT NULL DEFAULT 'read',
    user_id      BIGINT       NOT NULL,
    expires_at   DATETIME NULL,
    revoked      TINYINT      NOT NULL DEFAULT 0,
    revoked_at   DATETIME NULL,
    last_used_at DATETIME NULL,
    create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME NULL COMMENT '刻意无 ON UPDATE：last_used_at 节流写不得刷新它（ADR-0029 D6）',
    is_deleted   TINYINT DEFAULT 0,
    remark       TEXT,
    UNIQUE KEY uk_api_token_hash (token_hash),
    KEY idx_api_token_user_id (user_id),
    KEY idx_api_token_is_deleted (is_deleted),
    CONSTRAINT fk_api_token_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
```

MySQL 不支持 `CREATE INDEX IF NOT EXISTS` → 全部索引内联 `KEY`/`UNIQUE KEY`（ADR-0015 既有约定）。

### 2.5 `db/schema-postgresql.sql`

```sql
-- =====================================================
-- 10. API 令牌表 (api_token) — ADR-0029 长期可吊销凭证
-- =====================================================
CREATE TABLE IF NOT EXISTS api_token (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    token_prefix VARCHAR(16)  NOT NULL,
    token_hash   VARCHAR(128) NOT NULL,
    scope        VARCHAR(20)  NOT NULL DEFAULT 'read',
    user_id      BIGINT       NOT NULL,
    expires_at   TIMESTAMP NULL,
    revoked      INTEGER      NOT NULL DEFAULT 0,
    revoked_at   TIMESTAMP NULL,
    last_used_at TIMESTAMP NULL,
    create_time  TIMESTAMP DEFAULT LOCALTIMESTAMP,
    update_time  TIMESTAMP NULL,
    is_deleted   INTEGER DEFAULT 0,
    remark       TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_api_token_hash ON api_token(token_hash);
CREATE INDEX IF NOT EXISTS idx_api_token_user_id ON api_token(user_id);
CREATE INDEX IF NOT EXISTS idx_api_token_is_deleted ON api_token(is_deleted);
```

### 2.6 `core/src/test/resources/db/schema-h2.sql`（**第五个同步点，规格 §5.1 未列出**）

测试 profile 用 H2（`application-test.yml` 的 `spring.sql.init`），方言脚本不参与，必须手工补表，
否则任何触碰 `api_token` 的测试直接报"表不存在"。沿用该文件既有风格（内联 UNIQUE、无独立索引）：

```sql
-- API 令牌表（ADR-0029）
CREATE TABLE IF NOT EXISTS api_token (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    token_prefix VARCHAR(16) NOT NULL,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    scope VARCHAR(20) NOT NULL DEFAULT 'read',
    user_id BIGINT NOT NULL,
    expires_at TIMESTAMP,
    revoked TINYINT NOT NULL DEFAULT 0,
    revoked_at TIMESTAMP,
    last_used_at TIMESTAMP,
    remark VARCHAR(500),
    is_deleted TINYINT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP
);
```

> 注意：H2 脚本里既有表用 `deleted` 列名（历史遗留，与生产 `is_deleted` 不一致）。新表**按生产口径写 `is_deleted`**，
> 不要沿用 H2 的旧列名。

### 2.7 迁移注册点

`SchemaMigrationRunner.afterPropertiesSet()`（`config/SchemaMigrationRunner.java:45-53`）末尾追加：

```java
// V1.11: API 令牌表（ADR-0029 长期可吊销凭证）
ensureSqlFileExecuted("api_token", "db/migration/V1.11__api_token.sql");
```

启动时序无需额外处理：`SchemaMigrationRunner` 是 `InitializingBean`（早于 `@PostConstruct` 的 `DatabaseInitializer.run()`，
见 `DatabaseInitializer.java:44-56`）。全新库由 V1.11 先建表、`schema-sqlite.sql` 的 `IF NOT EXISTS` 随后空转；
老库由 V1.11 补建。两条路径都要求 2.2 与 2.3 内容一致。

> **实现时必须顺带核对的既有缺口（不属本票范围，不要顺手修）**：`db/migration/V1.9__sys_setting.sql` 与
> `V1.10__update_time_triggers.sql` 在 `SchemaMigrationRunner` 中**没有注册**，只对方言脚本负责的新库生效。
> 存量 SQLite 库升级后可能缺 `sys_setting` 表（`SystemController` 会 500）。这印证了注册点必须写、也提示 T3
> 别以为"文件放进 migration 目录"就等于执行了。

---

## 3. 新增文件与职责

| 文件 | 模块 | 职责 |
|---|---|---|
| `entity/ApiToken.java` | core | `@TableName("api_token")` extends `BaseEntity`；字段 `name/tokenPrefix/tokenHash/scope/userId/expiresAt/revoked/revokedAt/lastUsedAt` + `@TableField(exist = false) String username`（仅 `selectActiveByHash` 的 JOIN 填充） |
| `enums/TokenScope.java` | core | `READ("read")` / `WRITE("write")`，`fromCode(String)` 未知识别返回 `Optional.empty()`（脏数据要 fail-closed 成 INVALID，不能当 write） |
| `mapper/ApiTokenMapper.java` | core | 见 3.1 |
| `util/ApiTokenCodec.java` | core | 三个静态纯函数：`generatePlaintext()` / `hash(plain)` / `prefixOf(plain)`；`SecureRandom` + `Base64.getUrlEncoder().withoutPadding()`（32 字节 → 43 字符，总长 47）；`MessageDigest("SHA-256")` + `HexFormat.of()`（小写 hex）。无状态、不注入 bean |
| `service/ApiTokenService.java` | core | 1.1 的接口 |
| `service/impl/ApiTokenServiceImpl.java` | core | 唯一实现；依赖 `ApiTokenMapper` + `UserMapper`（取当前用户）+ `ApiTokenProperties` |
| `config/ApiTokenProperties.java` | core | `@ConfigurationProperties(prefix = "game-platform.api-token")`，字段默认值自负（与插件配置同规矩）：`long lastUsedThrottleSeconds = 60` |
| `controller/ApiTokenController.java` | core | 见 §5 |
| `dto/ApiTokenCreateDTO.java` | **api** | 见 5.1（`core` 无 `dto` 包，DTO 归 `api` 模块，与 `LoginDTO` 同处） |
| `vo/ApiTokenVO.java`、`vo/ApiTokenCreatedVO.java` | **api** | 见 5.2 |

### 3.1 Mapper（注解 SQL，不写 XML；风格与 `UserMapper.updateLoginInfo` 一致）

```java
@Mapper
public interface ApiTokenMapper extends BaseMapper<ApiToken> {

    /** 认证查询：按哈希取活跃记录并带出归属用户名。不过滤 expires_at（过期在 Java 侧判，见 ADR-0029 D2）。 */
    @Select("SELECT t.*, u.username FROM api_token t " +
            "JOIN sys_user u ON u.id = t.user_id " +
            "WHERE t.token_hash = #{hash} AND t.is_deleted = 0")
    ApiToken selectActiveByHash(@Param("hash") String hash);

    /** 节流写：是否要写由 SQL 自己判，返回 0 表示本次不写（ADR-0029 D6）。 */
    @Update("UPDATE api_token SET last_used_at = #{now} " +
            "WHERE id = #{id} AND (last_used_at IS NULL OR last_used_at < #{threshold})")
    int touchLastUsed(@Param("id") Long id, @Param("now") LocalDateTime now,
                      @Param("threshold") LocalDateTime threshold);

    /** 配额与重名检查。 */
    @Select("SELECT COUNT(*) FROM api_token WHERE user_id = #{userId} AND revoked = 0 AND is_deleted = 0")
    int countActiveByUser(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM api_token WHERE user_id = #{userId} AND name = #{name} " +
            "AND revoked = 0 AND is_deleted = 0")
    int countActiveByName(@Param("userId") Long userId, @Param("name") String name);

    /** 吊销（幂等：只动未吊销的行）。 */
    @Update("UPDATE api_token SET revoked = 1, revoked_at = #{now}, update_time = #{now} " +
            "WHERE id = #{id} AND revoked = 0 AND is_deleted = 0")
    int revokeById(@Param("id") Long id, @Param("now") LocalDateTime now);
}
```

三种方言共用以上语句：只用 `IS NULL`、`<`、`=`，不含 `datetime()`/`NOW()`/`IFNULL` 等方言函数。
`LocalDateTime` 作参数在 SQLite/MySQL/PG 均已有先例（`UserMapper.updateLoginInfo`）。

---

## 4. `JwtAuthenticationFilter` 改造（JWT 路径零回归的硬约束）

`config/JwtAuthenticationFilter.java`：新增一个 `final` 依赖 `ApiTokenService`（`@RequiredArgsConstructor` 自动注入），
`doFilterInternal` 的 try 块改为：

```java
String token = getTokenFromRequest(request);

if (StringUtils.hasText(token) && token.startsWith(ApiTokenService.PLAINTEXT_PREFIX)) {
    // 长期 API Token 分支（ADR-0029 D3/D5）：既有 JWT 路径不经过此处
    ApiTokenService.Decision decision =
            apiTokenService.authenticate(token, request.getMethod());
    if (decision.getOutcome() == ApiTokenService.Decision.Outcome.ALLOW) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(decision.getUsername());
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, apiTokenAuthorities(userDetails));   // ← 注意：不是 userDetails.getAuthorities()
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        log.info("API 令牌 '{}' (scope={}) → {} {}", decision.getTokenName(),
                decision.getScope(), request.getMethod(), request.getRequestURI());
        apiTokenService.touch(decision.getTokenId());
    } else if (decision.getOutcome() == ApiTokenService.Decision.Outcome.SCOPE_DENIED) {
        writeError(response, HttpServletResponse.SC_FORBIDDEN, ResultCode.FORBIDDEN);
        return;                       // 不再 doFilter
    } else {
        log.warn("API 令牌认证失败: {} (path={} {})", decision.getReason(),
                request.getMethod(), request.getRequestURI());
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ResultCode.UNAUTHORIZED);
        return;                       // 不再 doFilter
    }
} else if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {
    // ===== 既有 JWT 逻辑，原样保留（:50-67），一行不改 =====
}
```

配套要求：

1. **`apiTokenAuthorities(...)`** 返回固定 `ROLE_USER` + `ROLE_API_TOKEN`，**丢弃 `ROLE_ADMIN`**（ADR-0029 D5）。
   与用户名无关：即使令牌归属 admin，也不是管理员主体。日志行仍需 `decision.getTokenName()`，
   以便区分同一用户签发的不同令牌。
2. **`gpm_` 分支绝不进入 `jwtTokenProvider`**，反之亦然——单测要钉住这条互斥（§7 T-6）。
3. **抽取 `writeError(response, status, resultCode)`**：现有 `handleAuthenticationError`（:92-98）就是它的 401 特例，
   改成 `writeError(response, SC_UNAUTHORIZED, ResultCode.UNAUTHORIZED)` 并保留方法名或调用点等价，
   两条分支共用一个 JSON 写出实现（`application/json;charset=UTF-8` + `Result.fail(code)`）。
   `Result.fail(ResultCode.UNAUTHORIZED)` 的响应体形状不变（`{code:401,message:"未授权,请先登录",data:null,timestamp:…}`）。
4. 既有 `catch (Exception e) → handleAuthenticationError → return` 保持包住全部逻辑：DB 不可用时令牌认证 fail-closed 成 401
   （已知取舍，见 §8 R3）。
5. `getTokenFromRequest`（:81-87）**不改**：`gpm_` 明文同样要求 `Authorization: Bearer gpm_xxx` 头形式。

---

## 5. 管理控制器契约 `ApiTokenController`

```java
@Tag(name = "API 令牌管理", description = "长期可吊销 API Token（ADR-0029）")
@RestController
@RequestMapping("/tokens")     // context-path=/api 已剥离，实际路径 /api/tokens
@RequiredArgsConstructor
@Validated
public class ApiTokenController { … }
```

### 5.1 请求体（`ApiTokenCreateDTO`）

| 字段 | 类型 | 校验 | 缺省 |
|---|---|---|---|
| `name` | String | `@NotBlank`、`@Size(max=100)` | 必填 |
| `scope` | String | `@Pattern(regexp="read|write")` | `read` |
| `expiresInDays` | Integer | `@Min(1) @Max(3650)` | `365` |

### 5.2 响应

```java
ApiTokenCreatedVO { Long id; String name; String token; String scope; LocalDateTime expiresAt; }   // token = 明文，只在此出现
ApiTokenVO        { Long id; String name; String prefix; String scope; LocalDateTime expiresAt;
                    Integer revoked; LocalDateTime revokedAt; LocalDateTime lastUsedAt; LocalDateTime createTime; }
```

`ApiTokenVO` **不得包含** `tokenHash` / `tokenPrefix` 之外的机密字段，尤其不能有明文；序列化前由服务层投影，
不给 Mapper 直接吐实体（防"以后有人顺手把实体当响应"）。

### 5.3 端点矩阵

| 方法 | 路径 | 权限 | 成功 | 失败 |
|---|---|---|---|---|
| POST | `/api/tokens` | JWT + `ROLE_ADMIN` | `Result.success(ApiTokenCreatedVO)`，`data.token` 为明文 | 校验失败 400 `VALIDATE_FAILED`；重名/超配额 → `BusinessException` → HTTP 200 + `code=400` |
| GET | `/api/tokens` | JWT + `ROLE_ADMIN` | `Result.success(List<ApiTokenVO>)`（不分页，令牌数量级为个位数～几十） | — |
| DELETE | `/api/tokens/{id}` | JWT + `ROLE_ADMIN` | `Result.success(ApiTokenVO)`（`revoked=1`，幂等） | id 不存在 → `Result.fail(404, "API 令牌不存在: <id>")`（HTTP 200 + body code 404） |

- 归属用户取 `SecurityContextHolder.getContext().getAuthentication().getName()` → `UserMapper.selectByUsername`，
  与 `UserServiceImpl.getCurrentUser()`（:78-91）同一口径；不由请求体传入。
- 配额：`maxActiveTokensPerUser = 20`（服务层常量）。
- 创建/吊销都记 INFO：`创建 API 令牌 '<name>' (id=…, scope=…, expiresAt=…) by <user>`、
  `吊销 API 令牌 id=… by <user>`，**只打 prefix，绝不打明文/哈希**。
- 明文只在 POST 响应出现一次：`Result` 序列化后不落库、不入日志、不进 GET 列表。

### 5.4 错误语义总表（对齐 PLUT-35 §4，供 CLI 映射）

| 场景 | HTTP | body `code` | body `message` | CLI 退出码 |
|---|---|---|---|---|
| 无凭据 / JWT 过期无效（既有） | 401 | 401 | 未授权,请先登录 | 3 |
| `gpm_` 不存在 / 已吊销 / 已过期 | 401 | 401 | 未授权,请先登录 | 3 |
| `read` 令牌发非安全方法 | 403 | 403 | 没有相关权限 | 4 |
| 令牌主体访问 `/tokens/**`、`/cloud/**` | 403 | 403 | 没有相关权限 | 4 |
| 参数非法（含 scope 非 read/write） | 400 | 400 | 参数校验失败 | 6 |
| 令牌名重复 / 超配额 | 200 | 400 | 具体文案 | 6 |
| DELETE 未知 id | 200 | **404** | `API 令牌不存在: <id>` | 5 |

> **跨票契约项（需队长转给 T2/T4 owner）**：最后一行的"HTTP 200 + body code 404"不在 PLUT-35 §4 表中，
> CLI 的映射表需补一条 `code=404 → not_found / exit 5`（与业务码 1101 一类同处理）。
> 若希望 CLI 零改动，备选是改成 HTTP 404（`@ResponseStatus`），但会与平台"业务错误 HTTP 200 + body code"的既有口径分叉（PLUT-35 C4）。推荐前者（补映射）。

---

## 6. `SecurityConfig` 放行调整

`config/SecurityConfig.java`：

1. 在 `/cloud/**`（:93-95）之后、`anyRequest().authenticated()`（:98）之前插入：

```java
// API 令牌管理（ADR-0029）：仅管理员，且 API 令牌主体永不含 ROLE_ADMIN ⇒ 令牌无法管令牌
.requestMatchers("/tokens/**").hasRole("ADMIN")
```

2. 在既有 `exceptionHandling(...)`（:103-108）里补 `accessDeniedHandler`（ADR-0029 D7）：

```java
.handling.accessDeniedHandler((request, response, ex) -> {
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.getWriter().write(objectMapper.writeValueAsString(Result.fail(ResultCode.FORBIDDEN)));
})
```

3. **其余一律不动**：`/auth/login`、`/auth/register`、swagger、静态资源、`/pf4j/**/ui/**`、`/ws/**`、`/cloud/**`、
   `anyRequest().authenticated()`、`authenticationEntryPoint`、`addFilterBefore` 全部保持现状。
   （补 `accessDeniedHandler` 只改变 403 的**响应体**从空变 JSON，不改变任何请求的**放行/拒绝判定**。）

---

## 7. 测试清单

> 仓库现状（实现前必读）：**没有可用的 `@SpringBootTest` 全上下文测试**——`BaseTest` 无人继承（死代码），
> `DatabaseDialect.fromProductName("H2")` 会抛 `IllegalStateException`，CI 只跑 `mvn -B test`。
> 因此**不要**规划"全上下文 MockMvc + springSecurity()"用例；集成性验证走 §7.1 的手建对象路线（既有 `SqliteDialectInitializationTest` 即此风格），
> 端到端权限判定走 §7.3 的手工 curl。

### 7.1 单元测试（新增）

| 用例 | 断言要点 |
|---|---|
| T-1 `ApiTokenCodecTest` | 明文匹配 `^gpm_[A-Za-z0-9_-]{43}$`、无 `=` 填充；`hash` 为 64 位小写 hex 且对固定输入稳定；`prefixOf` 取前 12 字符；连抽 1000 次无重复 |
| T-2 `ApiTokenServiceImplTest#create`（mock Mapper） | 落库的是哈希不是明文（`ArgumentCaptor` 断言 `tokenHash == hash(返回的明文)`）；缺省 scope=read、缺省 +365 天；`expiresInDays` 越界 / name 空白 / 重名 / 超配额 各自抛 `BusinessException` 且消息可读 |
| T-3 `ApiTokenServiceImplTest#authenticate` | 查无 → INVALID(not_found)；`revoked=1` → INVALID(revoked)；`expires_at` 已过 → INVALID(expired)、恰未过 → ALLOW；`expires_at` 为 NULL → ALLOW；脏 `scope` 值 → INVALID（fail-closed，不得当 write）；read+GET/HEAD/OPTIONS → ALLOW；read+POST/PUT/DELETE/PATCH → SCOPE_DENIED(scope_read)；write+POST → ALLOW；ALLOW 时 `username` 来自 JOIN |
| T-4 `ApiTokenServiceImplTest#revoke/touch` | 吊销走 `revokeById` 且 `revoked_at` 不被二次覆盖（返回受影响行 0 时不报错，幂等）；`touch` 传的 `threshold == now - throttleSeconds`，配 0 时 `threshold == now` |
| T-5 `ApiTokenControllerTest`（`MockMvcBuilders.standaloneSetup`，mock Service） | POST 200 且 `data.token` 匹配 `^gpm_`；GET 列表 JSON **不含** `token`/`tokenHash` 字段（`jsonPath("$.data[0].tokenHash").doesNotExist()`）；DELETE 返回 `revoked=1`；非法 scope → 400 |
| T-6 `JwtAuthenticationFilterTest`（`MockHttpServletRequest/Response` + 记录式 `FilterChain`，**本票最重要的回归护栏**） | ① `gpm_` 有效 → 链继续、上下文已认证、权限含 `ROLE_USER`+`ROLE_API_TOKEN` 且**不含** `ROLE_ADMIN`、`touch` 被调一次、`verify(jwtTokenProvider, never()).validateToken(any())`；② `gpm_` INVALID → 链**未**继续、HTTP 401、体含 `"code":401`；③ `gpm_` SCOPE_DENIED → 链未继续、HTTP 403、体含 `"code":403`；④ 有效 JWT → 链继续 + 上下文权限 = `userDetails.getAuthorities()` 原样（含 ROLE_ADMIN 场景）；⑤ 过期/垃圾 JWT → 链继续、**未写响应**（`response.isCommitted()==false`，401 留给 EntryPoint，与 3c5df3e 行为逐字一致）、`verify(apiTokenService, never()).authenticate(...)`；⑥ JWT 路径用户不存在 → 401（既有 `catch` 行为） |
| T-7 `ApiTokenSchemaParityTest`（读 classpath 四份脚本） | 解析 `schema-sqlite.sql`/`schema-mysql.sql`/`schema-postgresql.sql`/`schema-h2.sql` 与 `V1.11__api_token.sql` 的 `api_token` 列名集合，断言六者一致（**唯一能廉价挡住"忘了同步 H2/PG"的守卫**）；另断言 sqlite 段与 V1.11 段全文一致 |
| T-8 `SqliteApiTokenMigrationTest`（临时文件 SQLite，仿 `SqliteDialectInitializationTest`） | 预置老库结构后连跑 `SchemaMigrationRunner.afterPropertiesSet()` **两次** → `api_token` 存在、列齐、`uk_api_token_hash` 生效（重复哈希抛 `DuplicateKeyException`）、第二次不报错不重复建 |

### 7.2 既有测试同步点（改文件，不加用例）

- `SqliteDialectInitializationTest.ALL_TABLES`（:31-35）与 `MysqlDialectInitializationTest.ALL_TABLES`（:47）加 `"api_token"`；
- 前者的"非 SQLite 跳过迁移"用例不受影响；`DatabaseDialectTest` 不需要改（不新增方言）。

### 7.3 JWT 零回归的验证方式（票的验收硬条款）

1. **静态**：`git diff 3c5df3e..HEAD -- backend/core/src/main/java/com/gameplatform/config/` 只允许出现
   `JwtAuthenticationFilter`（新增分支 + `writeError` 抽取）、`SecurityConfig`（1 个 matcher + accessDeniedHandler）、新增文件；
   `JwtTokenProvider` / `UserDetailsServiceImpl` / `AuthController` / `UserServiceImpl` / `GlobalExceptionHandler` **diff 必须为空**。
2. **单元**：T-6 的 ④⑤⑥ 三条把既有 JWT 语义钉死（含"垃圾 JWT 不写响应、交给 EntryPoint"这一易被改坏的细节）。
3. **手工（测试环境 `http://192.168.3.50:8081/api`，T3 自测必做）**：
   ```bash
   BASE=http://192.168.3.50:8081/api
   JWT=$(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
         -d '{"username":"admin","password":"<口令>"}' | jq -r .data.token)
   curl -s -H "Authorization: Bearer $JWT" $BASE/system/health | jq .code      # 期望 200
   curl -s -H "Authorization: Bearer $JWT" "$BASE/hosts?current=1&size=5" | jq .code   # 期望 200
   curl -s -H "Authorization: Bearer garbage" $BASE/system/health | jq '{code}' # 期望 401（形状与升级前一致）
   curl -s -X POST -H "Authorization: Bearer $JWT" $BASE/auth/refresh | jq .code # 期望 200
   ```
   并确认前端登录页照常工作（JWT 未被令牌分支截胡）。
4. **令牌面自测（同环境）**：
   ```bash
   T=$(curl -s -X POST $BASE/tokens -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
       -d '{"name":"gpmcli","scope":"read"}' | jq -r .data.token)
   curl -s -H "Authorization: Bearer $T" $BASE/system/health | jq .code                 # 200
   curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $T" \
        $BASE/instances/1/restart                                                       # 403
   curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $T" $BASE/tokens   # 403（D5：令牌不能管令牌）
   curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer gpm_nope" $BASE/system/health  # 401
   ```

---

## 8. 评审重点风险清单（给 S4 评审官）

| # | 风险 | 处置/结论 |
|---|---|---|
| R1 | **令牌管令牌回路**：若 ALLOW 分支直接放 `userDetails.getAuthorities()`，admin 的 read 令牌即可签发/吊销令牌并访问 `/cloud/**` | 已由 ADR-0029 D5 在主体侧关闭（`ROLE_API_TOKEN` + 固定去 ADMIN）。评审请确认 T-6① 断言存在 |
| R2 | **五处 schema 同步**（sqlite/mysql/pg/h2 + V1.11）漏一处：生产或测试一侧建不出表，且 MySQL/PG 存量库永不自动补齐 | T-7 奇偶校验用例兜住列名集合；发布说明必须写"MySQL/PG 存量库需人工执行 §2.4 DDL"（ADR-0015 已知限制） |
| R3 | **DB 故障 → 401 误报**：既有 `catch` 把令牌分支的异常也变成 401（fail-closed） | 接受（安全优先），但必须 `log.error` 级别，且 CLI 会把"平台 DB 挂了"报成 auth 错误（exit 3）——在发布说明中点一句，供 T9 联调识别 |
| R4 | **明文泄漏面**：日志、异常堆栈、GET 列表、`/auth/info` 均可能顺带带出 | 明文只存在于 `ApiTokenCreatedVO.token` 一个字段；`ApiToken` 实体的 `toString()`（Lombok `@Data`）会打出 `tokenHash`——**日志里绝不打实体对象**，评审检查点；T-5 断言列表 JSON 无机密字段 |
| R5 | **写作用域粒度粗**：`write` 令牌可发任意非安全方法（含平台内所有 DELETE 端点），而 CLI v1 只需要启停/取消/重试 | v1 接受（PLUT-35 §5.2 即如此定义）。若评审要求收紧，成本最低的是把 `scope` 判定改为"方法白名单 + 端点前缀表"，但会扩大接口面——记为 v1.1 议题，不在本票做 |
| R6 | **`update_time` 语义**：新表刻意不挂自动刷新，与 V1.10 的四表触发器惯例相反 | 有意为之（ADR-0029 D6），与更晚的 `task_record`/`scheduled_task` 惯例一致。评审若要求补触发器，则必须同时把"节流写不改 update_time"改成显式列级处理，二选一，别混 |
| R7 | **配额与枚举**：`maxActiveTokensPerUser=20` 是服务层常量、重名判定走查询非唯一索引 | 与"个位数令牌"的现实规模匹配；若评审认为重名应更强约束，需引入 `(user_id,name)` 部分唯一索引，MySQL/PG 语法分叉大，不划算 |
| R8 | **跨票契约**：§5.4 最后一行需要 CLI 映射表补 `body code=404 → exit 5` | 已在 §5.4 标注为交队长的转达项，T3 不自行改 CLI |

---

## 9. 落地顺序建议（T3 可直接照此排 sub-task）

1. 五份 SQL（§2）+ 注册点（§2.7）+ T-7/T-8 → 先把"建得出表"钉死。
2. `ApiTokenCodec` + `ApiToken`/`TokenScope`/`ApiTokenMapper` + T-1。
3. `ApiTokenService(-Impl)` + `ApiTokenProperties` + T-2/T-3/T-4。
4. DTO/VO + `ApiTokenController` + T-5。
5. `JwtAuthenticationFilter` 分支（§4）+ `SecurityConfig`（§6）+ T-6（含 ④⑤⑥ 回归护栏）。
6. §7.2 既有测试同步 + `docs/api/api-doc.md` 新增 `## 11. API 令牌管理模块`（沿用现有小节格式：请求/响应示例 + 字段表）与目录条目。
7. §7.3 手工自测（含 JWT 回归与令牌面 401/403），把 curl 输出贴进 PR 描述。

不改动前端、不引依赖、不新增配置必填项（`game-platform.api-token.*` 全部有字段默认值）。

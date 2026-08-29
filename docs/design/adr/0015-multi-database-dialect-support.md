# ADR-0015: 多数据库方言支持（SQLite / MySQL / PostgreSQL）

- 状态：Accepted
- 日期：2026-08-29
- 关联：[ADR-0002 主应用与插件范围隔离规约](0002-main-app-plugin-scope-isolation.md)、[ADR-0012 应用容器化与 CI 镜像构建](0012-app-containerization-and-db-reserve.md)、[Glossary](glossary.md)

## 背景（Context）

主应用数据源原先为 SQLite 独占：`spring.datasource` 写死 `org.sqlite.JDBC`，建表/种子脚本 `db/schema.sql`、`db/data.sql` 使用 SQLite 方言（`INTEGER PRIMARY KEY AUTOINCREMENT`、`datetime('now','localtime')` 默认值），表存在性检查依赖 `sqlite_master` 系统表，增量迁移体系（`db/migration/`）也是 SQLite 方言。ADR-0012 容器化时已预留 `docker-compose.mysql.yml` / `docker-compose.pg.yml` 与接入清单，等待数据源层支持 MySQL/PostgreSQL。

需求：通过配置项决定启用哪个数据库；启动时若表不存在则自动初始化；**仅改 core 层**（plugin SDK 的 `ExtensionClient` 只是 API，实现全部在 core 的 `plugin/extension/` 包内，故插件扩展存储的方言适配不越界）。

## 决策（Decision）

1. **无新配置开关**：方言由启动时 `DatabaseDialectResolver` 读取 `DatabaseMetaData.getDatabaseProductName()` 自动判定——`SQLite` / `MySQL`（含 MariaDB） / `PostgreSQL`。MariaDB 产品名为 "MariaDB"，归入 MySQL 方言；平台的 MySQL 方言脚本刻意只使用 MariaDB 也兼容的语法。切换数据库 = 替换 `spring.datasource` 的 `driver-class-name` + `url` + 凭证，沿用标准 Spring 配置形态。
2. **三份方言脚本**：`db/schema-{sqlite|mysql|postgresql}.sql` 与 `db/data-{sqlite|mysql|postgresql}.sql`。PG 自增用 `BIGSERIAL`、索引独立 `CREATE INDEX IF NOT EXISTS`；MySQL 自增用 `BIGINT AUTO_INCREMENT`、**索引一律内联在 CREATE TABLE 的 KEY 子句中**（规避 MySQL 不支持的 `CREATE INDEX IF NOT EXISTS`，随表的 `IF NOT EXISTS` 天然幂等）、显式 `ENGINE=InnoDB` + `utf8mb4`。种子数据防重：MySQL 用 `INSERT IGNORE`，PG 用 `ON CONFLICT DO NOTHING`。
3. **不做 SQLite→MySQL/PG 数据迁移**：MySQL/PG 面向全新部署，存量 SQLite 部署继续用 SQLite。
4. **新库一步到位建最新完整结构**：方言 schema 含 6 张核心表 + 任务中心/定时计划表（`task_record` 等 5 张）+ 历次迁移累积列（如 `game_instance.game_code`、`plugin_info` 6 个扩展列）。**迁移体系（`db/migration/`、PRAGMA/sqlite_master 检查）仅对 SQLite 生效**，`SchemaMigrationRunner` 在非 SQLite 方言下整体跳过。
5. **保留自研初始化链**：`DatabaseInitializer`（CommandLineRunner）按方言选择脚本；不启用 Spring `sql.init`（`mode: never` 维持）。启动链路：核心表不存在 → 执行方言 schema + data；已存在且 SQLite → 走历史迁移；已存在且 MySQL/PG → 不做任何迁移。
6. **插件扩展存储同套方言**：`DdlTemplate.generate(table, dialect)` 按方言生成宽表 DDL——MySQL 版为单条语句（索引内联，绕开 Connector/J 默认禁多语句的限制），SQLite/PG 版维持"建表 + CREATE INDEX IF NOT EXISTS"多语句形态（调用方统一按分号拆分逐条执行）。`SqliteQueryDialect` 的查询 SQL 实为 ANSI 中性（内存过滤 JSON + `LIMIT ? OFFSET ?`），保持三库共享，不新建 Mysql/PgQueryDialect；表存在性检查改走 JDBC `DatabaseMetaData.getTables()`（`DatabaseScriptExecutor`）。

## 验证（Verification）

- 单元测试 `DatabaseDialectTest`：产品名映射（含 MariaDB→MySQL）、脚本路径、索引语法能力位。
- 集成测试 `MysqlDialectInitializationTest`：**MariaDB4j 内嵌 MariaDB（3.1.0，自带 win64 二进制，零 Docker）**实测全新库初始化——11 张表建齐、种子数据写入且重复执行防重、迁移累积列存在、非 SQLite 方言跳过迁移、插件宽表按 MySQL 方言创建。本机二进制不可用时：优先回退环境变量 `MYSQL_UT_JDBC_URL`（可带 `MYSQL_UT_USER`/`MYSQL_UT_PASSWORD`）指向外部 MySQL/MariaDB，再不可用则自动跳过（assumption）。
- PostgreSQL 脚本不进 UT，仅人工评审（PG 方言语法简单且与 SQLite 更接近，风险集中在 MySQL 的索引语法差异，已由 MariaDB4j 实测覆盖）。

## 后果（Consequences）

- `docker-compose.mysql.yml` / `docker-compose.pg.yml` 的接入清单已满足：叠加 compose 后通过 `SPRING_DATASOURCE_*` 环境变量即可切库，启动时自动建库表。
- 数据库备份/还原（`BackupServiceImpl`）目前仅支持 SQLite（复制文件/`.dump`）；MySQL/PG 的备份还原是后续独立需求。
- 新增表结构变更时：SQLite 存量库走 `db/migration/`；**MySQL/PG 的结构升级暂无增量机制**（需在方言 schema 同步最新结构，存量库手工处理），出现真实需求时再引入方言化的 Flyway/迁移框架。
- `SqliteQueryDialect` 名称与"三库共享"的事实不符，保留是为避免无谓改动；JSON 过滤 SQL 下推（PG `jsonb`、MySQL `JSON_EXTRACT`）留作后续演进方向。
- MariaDB4j UT 首次运行需解包二进制并初始化数据目录（本机实测约 4 分钟），之后显著加快；`mvn test` 总时长因此变长。

## 术语（Glossary）

- **数据库方言（Database Dialect）**：`DatabaseDialect` 枚举，SQLITE / MYSQL / POSTGRESQL 三值，决定脚本选择、DDL 生成与迁移体系是否执行。
- **核心库表（Core Schema）**：主应用自管的表（`sys_user`、`host_info` 等），由方言 schema 脚本初始化。
- **插件扩展存储（Extension Store）**：`ext_plugin_*` / `extensions` 统一宽表，由 `DdlTemplate` 按方言在插件加载时创建，与核心库表物理隔离。

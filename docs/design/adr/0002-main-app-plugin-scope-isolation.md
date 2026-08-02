# ADR-0002: 主应用与插件范围隔离规约

| 字段 | 值 |
|------|----|
| 状态 | Accepted |
| 日期 | 2026-08-03 |
| 决策者 | User (grill-with-docs session) |
| 关联 | [ADR-0001](0001-plugin-menu-ownership.md)（菜单归属权） |
| Supersedes | 无 |

## 背景（Context）

在梳理项目时发现主应用 `core/` 模块存在两处插件业务越界：

### 越界 1：主应用配置文件包含插件业务配置

[`backend/core/src/main/resources/application.yml`](../../../backend/core/src/main/resources/application.yml) 第 204-221 行（已删除前）包含 `plugin.l4d2` 配置块：

```yaml
plugin:
  l4d2:
    rcon-timeout: 5000
    rcon-retry-count: 3
    vpk-scan-path: addons
    plugin-store:
      branch: master
      proxy-url: ""
      github-token: ""
    # ... 共 18 行 L4D2 专属配置
```

该配置块供 [`L4D2Config`](../../../backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/config/L4D2Config.java) `@ConfigurationProperties(prefix = "plugin.l4d2")` 读取。注释自承"与 standalone 模式保持一致"——即主应用代插件托管了本应由插件自带的默认值。

**冗余验证**：`L4D2Config.java` 所有字段已有 Java 默认值（`= 5000`、`= "addons"` 等），且与主应用 yml 中的值完全一致。主应用 yml 中的配置项纯属冗余。

### 越界 2：主应用迁移目录包含插件专属表

[`backend/core/src/main/resources/db/migration/V1.4__L4D2_plugin_tables.sql`](../../../backend/core/src/main/resources/db/migration/) （已删除）在主应用迁移目录创建 4 张 L4D2 专属表：

- `l4d2_system_metric`（监控历史）
- `l4d2_plugin_config`（插件配置）
- `l4d2_download_task`（下载任务）
- `l4d2_admin`（管理员）

**废弃验证**：全代码库搜索 `l4d2_system_metric` / `l4d2_plugin_config` / `l4d2_download_task` / `l4d2_admin`，**无任何 Entity / Mapper / Service 引用**。插件实际使用 ExtensionClient 自动管理的 `ext_plugin_l4d2_*` 表（通过 [`DdlTemplate.generate`](../../../backend/core/src/main/java/com/gameplatform/plugin/extension/DdlTemplate.java) 动态建表）。V1.4 是历史遗留，已被 ExtensionClient 机制取代。

### 违反的原则

[AGENTS.md 约定](../../../AGENTS.md) 明确："plugin-l4d2 模块禁止直接依赖 game-platform-core 模块"。但上述越界是**反向耦合**——主应用 `core` 模块代插件托管配置和 schema，导致：

- 主应用感知了具体插件（`plugin.l4d2` 前缀、`l4d2_*` 表前缀）
- 插件卸载后，主应用残留无效配置和空表
- 新增插件时需修改主应用配置文件和迁移目录，破坏插件"即插即用"语义

## 决策（Decision）

确立主应用与插件的范围隔离规约，经 grill-with-docs session 收敛为以下条目：

### 规约 1：配置文件隔离

主应用配置文件（`core/src/main/resources/application.yml` 及 `application-*.yml`）**不得包含**插件业务配置项。

- **禁止**：`plugin.{gameCode}` 前缀的配置块（如 `plugin.l4d2`）
- **允许**：`game-platform.plugin` 命名空间（这是主应用插件框架自身的配置，如 `plugins-dir`、`hot-reload`，不是具体插件的业务配置）
- **插件配置来源**：插件 `@ConfigurationProperties` 类的字段 Java 默认值；需要覆盖时由 standalone yml 或环境变量处理

### 规约 2：数据库迁移隔离

主应用迁移目录（`core/src/main/resources/db/migration/`）**不得包含**插件专属表。

- **禁止**：`{gameCode}_*` 前缀的表（如 `l4d2_system_metric`）
- **允许**：主应用核心表（`host`、`game_instance`、`task_record` 等）和插件框架表（`extension_resource`、`plugin_extension` 等框架基础设施）
- **插件表来源**：ExtensionClient 自动管理的 `ext_plugin_{pluginId}_{resource}` 表（通过 `DdlTemplate` 动态建表）

### 规约 3：代码依赖隔离

主应用 `core` 模块代码**不得直接引用**插件业务包。

- **禁止**：`import com.gameplatform.plugin.{gameCode}.*`、硬编码具体 pluginId 作为业务逻辑分支条件
- **允许**：插件框架通用测试用具体 pluginId（如 `"plugin-l4d2"`）作为测试数据；SDK 文档注释中提及示例 pluginId

### 规约 4：游戏元数据例外

主应用 `core/src/main/resources/games/{gameCode}.yml` 是**例外**，允许且应当由主应用维护。

- **原因**：游戏元数据（部署方式、默认端口、镜像配置）是主应用部署向导的输入，不属于插件业务
- **边界**：插件不读取 `games/` 目录；主应用不读取插件的 `plugin.{gameCode}` 配置

### 规约 5：standalone 模式自治

`plugin-l4d2-standalone` 等独立运行模式的 `application.yml` **不受本规约约束**。

- **原因**：standalone 模式下插件即主应用，自带全部配置是合理的
- **示例**：[standalone/application.yml](../../../backend/plugin-l4d2/plugin-l4d2-standalone/src/main/resources/application.yml) 中的 `plugin.l4d2` 块是合法的

## 后果（Consequences）

### 正面

- **主应用纯净**：`core/` 不再感知任何具体插件，配置文件和迁移目录只包含框架自身内容
- **插件即插即用**：新增/卸载插件无需修改主应用配置和迁移
- **职责清晰**：插件配置默认值由插件源码自负，插件表由 ExtensionClient 自管
- **调试简单**：插件行为完全由插件 JAR 决定，无需追踪主应用配置覆盖

### 负面

- **配置可发现性下降**：主应用 yml 不再"文档化"插件配置项；需查阅插件源码或 SKILL 了解可配置项
- **缓解**：插件 `@ConfigurationProperties` 类的字段 Javadoc 即文档；SKILL `references/sdk_reference.md` 登记配置项

### 中性

- **存量数据库**：已部署实例的 SQLite 库中可能残留 `l4d2_*` 空表（V1.4 已执行过）。无害，不强制清理
- **强制手段**：本规约仅文档约定 + 代码评审，不加预提交脚本检查（适合当前团队规模）

## 备选方案（Alternatives）

| 方案 | 描述 | 否决理由 |
|------|------|---------|
| 插件自带 defaults.yml | 插件 JAR 内置 `classpath:plugin-l4d2-defaults.yml`，由插件子容器加载 | 增加一层间接性，且主应用仍需扫描插件 yml；Java 字段默认值已足够 |
| 保留配置 + 加注释 | 主应用保留 `plugin.l4d2` 块作为"文档化默认值" | 未真正解决范围越界，注释无法阻止后续堆积 |
| 插件自管 Flyway | 插件自带 Flyway 在子容器独立迁移 | SQLite 单文件下可能锁冲突；ExtensionClient 的 DdlTemplate 已满足需求 |
| 预提交脚本检查 | CI 扫描 core/ 下是否出现 `plugin.{gameCode}` 或 `{gameCode}_*` SQL | 强制力强但需维护脚本；当前团队规模下文档约定 + 评审足够 |

## 迁移记录

| 日期 | 操作 | 提交 |
|------|------|------|
| 2026-08-03 | 删除主应用 `application.yml` 的 `plugin.l4d2` 配置块（18 行） | 本提交 |
| 2026-08-03 | 删除废弃的 `V1.4__L4D2_plugin_tables.sql`（4 张废弃表） | 本提交 |

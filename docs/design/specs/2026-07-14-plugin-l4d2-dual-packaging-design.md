# plugin-l4d2 双模式打包设计

> 日期：2026-07-14
> 状态：已批准（待实现）
> 范围：backend/plugin-l4d2（aggregator + 双子模块）
> 目标：plugin-l4d2 支持两种打包产物——① PF4J 插件 JAR（被主应用加载）② Standalone fat JAR（独立运行）

---

## 1. 背景与目标

### 1.1 现状

`plugin-l4d2` 当前是单模块，已有 `L4D2StandaloneApp` 骨架和 pom.xml 中的 `spring-boot-maven-plugin` 配置（`classifier=standalone`、`mainClass=L4D2StandaloneApp`），但存在三个问题：

1. **`<skip>true</skip>` 禁用了 repackage**：standalone JAR 实际不生成
2. **缺少 application.yml**：无数据源、端口等配置
3. **缺少 core 模块提供的 Bean**：`ExtensionClient`、`InstanceQueryService`、`HostQueryService`、`FileAccessService` 接口实现在 core 模块，standalone 启动时注入失败

### 1.2 目标

- 拆分为 aggregator + 双子模块（`plugin-l4d2-core` + `plugin-l4d2-standalone`）
- `plugin-l4d2-core`：保持现有业务代码，生成 PF4J 插件 JAR（与主应用兼容）
- `plugin-l4d2-standalone`：独立运行入口 + 内部独立实现 4 个基础设施服务
- 独立应用用于生产部署，提供完整的 L4D2 管理功能
- 独立应用隐藏底层逻辑，方便独立于主应用进行功能开发
- 将来插件脱离项目时，`plugin-l4d2/` 目录自包含，无外部依赖

### 1.3 设计决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 独立应用用途 | 生产部署 | 需要完整功能，非仅调试 |
| core 依赖策略 | plugin-l4d2 内部独立实现 | 不依赖 core，避免引入主应用无关代码；后续插件各自独立目录管理 |
| 双模式打包触发 | 拆分为两个子模块 | 职责分离最清晰；plugin-l4d2-core 专注插件 JAR，plugin-l4d2-standalone 专注独立运行 |
| 数据管理 | standalone 自带 SQLite + 管理 API | 用户可在 standalone 内自助添加主机/实例 |

---

## 2. 整体架构与模块结构

### 2.1 模块拆分

将现有的 `backend/plugin-l4d2/` 单模块改造为 aggregator + 双子模块：

```
backend/plugin-l4d2/                       ← 改为 aggregator (packaging=pom)
├── pom.xml                                ← 聚合两个子模块，不含业务代码
├── plugin-l4d2-core/                      ← 业务代码模块（PF4J 插件 JAR）
│   └── pom.xml                            ← provided 依赖 + maven-jar-plugin 写 Manifest
└── plugin-l4d2-standalone/                ← 独立运行模块（Standalone fat JAR）
    └── pom.xml                            ← compile 依赖 plugin-l4d2-core + spring-boot repackage
```

### 2.2 依赖关系

```
plugin-l4d2-standalone
    └──(compile)──> plugin-l4d2-core
                        ├──(provided)──> game-platform-plugin  (ExtensionClient 接口等)
                        ├──(provided)──> game-platform-api     (VO/DTO/AbstractExtension)
                        ├──(provided)──> pf4j
                        ├──(provided)──> spring-boot-starter-web
                        └──(provided)──> jackson / hutool / sshd (MINA)
```

- `plugin-l4d2-core`：保持现有 provided 依赖不变，生成 PF4J 插件 JAR（含 Manifest），被主应用加载
- `plugin-l4d2-standalone`：compile 依赖 `plugin-l4d2-core`（把 core 的业务代码打入 fat JAR），同时 compile 依赖 spring-boot-starter-web/jdbc/sqlite/pf4j/jackson/hutool/sshd 等（独立运行所需）

### 2.3 构建产物

| 命令 | 产物 |
|------|------|
| `mvn package -pl plugin-l4d2-core` | `plugin-l4d2-core-1.0.0.jar`（PF4J 插件 JAR，部署到主应用 plugins 目录） |
| `mvn package -pl plugin-l4d2-standalone` | `plugin-l4d2-standalone-1.0.0.jar`（Spring Boot fat JAR，`java -jar` 独立启动） |
| `mvn package -pl plugin-l4d2` | 同时构建上述两个产物 |

### 2.4 父 pom 聚合变更

`backend/pom.xml` 的 `<modules>` 中 `plugin-l4d2` 保持不变（仍指向 aggregator 目录），aggregator 内部声明两个 `<module>`。

---

## 3. plugin-l4d2-core 模块迁移

### 3.1 文件迁移

将现有 `backend/plugin-l4d2/src/` 下的全部内容**原样移入** `backend/plugin-l4d2/plugin-l4d2-core/src/`，保留包结构 `com.gameplatform.plugin.l4d2.*` 不变。

| 原路径 | 新路径 |
|--------|--------|
| `backend/plugin-l4d2/src/main/java/...` | `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/...` |
| `backend/plugin-l4d2/src/main/resources/` | `backend/plugin-l4d2/plugin-l4d2-core/src/main/resources/` |
| `backend/plugin-l4d2/src/test/`（若有） | `backend/plugin-l4d2/plugin-l4d2-core/src/test/` |

**移入的文件清单**（保持不变）：
- `L4D2Plugin.java`、`L4D2Extension.java`（保留，PF4J 入口）
- `config/L4D2Config.java`
- `controller/` 下 6 个 Controller（AdminController、MapController、MonitorController、PluginManageController、RconController、ServerConfigController）
- `service/` 下 `RconService.java`、`VpkParserService.java`
- `extension/` 下 4 组 Resource+Spec（AdminResource、DownloadTaskResource、PluginConfigResource、SystemMetricResource）
- `dto/` 下 12 个 DTO
- `vo/` 下 10 个 VO
- `util/VpkParser.java`
- `resources/plugin.properties`、`resources/ui/`

**移除的文件**：`L4D2StandaloneApp.java` 从 plugin-l4d2-core 移除（迁入 standalone 模块，见第 4 节）。

### 3.2 plugin-l4d2-core/pom.xml

继承父 pom 的 `game-platform-parent`，packaging=jar，保留现有全部 provided 依赖和 maven-jar-plugin 的 Manifest 配置。**移除 spring-boot-maven-plugin**（standalone 的 repackage 由 standalone 模块负责）。

```xml
<parent>
    <groupId>com.gameplatform</groupId>
    <artifactId>game-platform-parent</artifactId>
    <version>1.0.0</version>
    <relativePath>../../pom.xml</relativePath>
</parent>

<artifactId>plugin-l4d2-core</artifactId>
<packaging>jar</packaging>

<properties>
    <plugin.id>plugin-l4d2</plugin.id>
    <plugin.class>com.gameplatform.plugin.l4d2.L4D2Plugin</plugin.class>
    <plugin.version>1.0.0</plugin.version>
    <plugin.provider>GamePlatform</plugin.provider>
    <plugin.description>L4D2 游戏服务器增强插件</plugin.description>
</properties>

<dependencies>
    <!-- 保持现有全部 provided 依赖不变 -->
    <dependency>
        <groupId>com.gameplatform</groupId>
        <artifactId>game-platform-plugin</artifactId>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>com.gameplatform</groupId>
        <artifactId>game-platform-api</artifactId>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>org.pf4j</groupId>
        <artifactId>pf4j</artifactId>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-thymeleaf</artifactId>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>cn.hutool</groupId>
        <artifactId>hutool-all</artifactId>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <!-- 保留：写入 PF4J Manifest -->
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-jar-plugin</artifactId>
            <configuration>
                <archive>
                    <manifestEntries>
                        <Plugin-Id>${plugin.id}</Plugin-Id>
                        <Plugin-Class>${plugin.class}</Plugin-Class>
                        <Plugin-Version>${plugin.version}</Plugin-Version>
                        <Plugin-Provider>${plugin.provider}</Plugin-Provider>
                        <Plugin-Description>${plugin.description}</Plugin-Description>
                    </manifestEntries>
                </archive>
            </configuration>
        </plugin>
        <!-- 移除：spring-boot-maven-plugin（由 standalone 模块负责 repackage） -->
    </plugins>
</build>
```

### 3.3 aggregator pom.xml

```xml
<parent>
    <groupId>com.gameplatform</groupId>
    <artifactId>game-platform-parent</artifactId>
    <version>1.0.0</version>
    <relativePath>../pom.xml</relativePath>
</parent>

<artifactId>plugin-l4d2</artifactId>
<packaging>pom</packaging>
<name>L4D2 Plugin Aggregator</name>

<modules>
    <module>plugin-l4d2-core</module>
    <module>plugin-l4d2-standalone</module>
</modules>
```

### 3.4 验证点

- `mvn package -pl plugin-l4d2-core` 生成 `plugin-l4d2-core-1.0.0.jar`，含 `META-INF/MANIFEST.MF`（Plugin-Id=plugin-l4d2 等）
- 将该 JAR 复制到主应用的 `plugins/` 目录，主应用可正常加载（行为与改造前完全一致）
- 主应用 `mvn test` 全量通过（361 个测试不受影响）

---

## 4. plugin-l4d2-standalone 模块

### 4.1 模块职责

`plugin-l4d2-standalone` 负责将 `plugin-l4d2-core` 的业务代码包装为可独立运行的 Spring Boot 应用，补充 4 类缺失的基础设施：

1. **StandaloneExtensionClient** — 简化版扩展存储实现（不依赖 core 的 ExtensionRouter/DdlTemplate）
2. **StandaloneHostQueryService / StandaloneFileAccessService / StandaloneInstanceQueryService** — 3 个宿主服务接口的独立实现
3. **host/instance 管理 Controller + Repository + Entity** — 让用户能在 standalone 应用内管理主机和实例
4. **application.yml + schema.sql** — 数据源、端口、建表脚本

### 4.2 目录结构

```
plugin-l4d2-standalone/
├── pom.xml
└── src/main/
    ├── java/com/gameplatform/plugin/l4d2/standalone/
    │   ├── L4D2StandaloneApp.java                    ← @SpringBootApplication 入口
    │   ├── config/
    │   │   ├── StandaloneDataSourceConfig.java        ← SQLite 数据源 + 启动建表
    │   │   └── StandaloneServiceConfig.java           ← 注册 4 个独立实现 Bean
    │   ├── ext/
    │   │   ├── StandaloneExtensionClient.java         ← 实现 ExtensionClient 接口
    │   │   ├── StandaloneExtensionRowMapper.java      ← ResultSet → AbstractExtension
    │   │   └── StandaloneDdlTemplate.java             ← 生成 CREATE TABLE SQL
    │   ├── host/
    │   │   ├── StandaloneHostQueryService.java        ← 实现 HostQueryService
    │   │   ├── StandaloneFileAccessService.java       ← 实现 FileAccessService (SFTP)
    │   │   ├── StandaloneHostController.java          ← host CRUD REST API
    │   │   ├── StandaloneHostEntity.java              ← host 表实体
    │   │   └── StandaloneHostRepository.java          ← JdbcTemplate 查询
    │   └── instance/
    │       ├── StandaloneInstanceQueryService.java    ← 实现 InstanceQueryService
    │       ├── StandaloneInstanceController.java      ← instance CRUD + start/stop/restart
    │       ├── StandaloneInstanceEntity.java          ← instance 表实体
    │       ├── StandaloneInstanceRepository.java      ← JdbcTemplate 查询
    │       └── StandaloneInstanceLifecycleService.java ← start/stop/restart 实现（调用 RCON/进程）
    └── resources/
        ├── application.yml                            ← 独立应用配置
        ├── application-dev.yml                        ← 开发配置（可选）
        ├── db/schema.sql                              ← host/instance/扩展表 DDL
        └── static/ui/                                    ← 前端静态资源（从 plugin-l4d2-core/ui 复制）
```

### 4.3 L4D2StandaloneApp 入口

```java
package com.gameplatform.plugin.l4d2.standalone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * L4D2 插件独立运行入口。
 * <p>
 * 不依赖主应用，直接以独立 Spring Boot 应用启动。
 * 扫描 standalone 包（基础设施）和 l4d2 包（业务代码，来自 plugin-l4d2-core）。
 *
 * 使用方式: java -jar plugin-l4d2-standalone-1.0.0.jar
 */
@SpringBootApplication(scanBasePackages = {
        "com.gameplatform.plugin.l4d2",           // 业务代码（controller/service/extension 等）
        "com.gameplatform.plugin.l4d2.standalone"  // 独立运行基础设施
})
public class L4D2StandaloneApp {
    public static void main(String[] args) {
        SpringApplication.run(L4D2StandaloneApp.class, args);
    }
}
```

### 4.4 StandaloneExtensionClient 设计

**关键简化**：standalone 只有一个插件（plugin-l4d2），不需要 `ExtensionRouter` 路由。表名直接按 `ext_plugin-l4d2_{KindName}` 规则生成（与 core 的 `ExtensionRouter` 规则一致）。

```java
package com.gameplatform.plugin.l4d2.standalone.ext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.api.extension.AbstractExtension;
import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.extension.ListOptions;
import com.gameplatform.plugin.extension.exception.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

/**
 * 独立运行的 ExtensionClient 实现。
 * <p>
 * 不依赖 core 的 ExtensionRouter/DdlTemplate/ExtensionRowMapper，
 * 直接使用 JdbcTemplate + Jackson 实现 CRUD。
 * <p>
 * 表名规则：ext_plugin-l4d2_{KindName}（与 core 的 ExtensionRouter 一致）。
 * 表结构：id/name/group_name/kind/version/metadata/spec/status/creation_timestamp/
 *        update_timestamp/version_lock（与 core 的 DdlTemplate 一致）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class StandaloneExtensionClient implements ExtensionClient {

    private static final String PLUGIN_ID = "plugin-l4d2";
    private static final String TABLE_PREFIX = "ext_" + PLUGIN_ID + "_";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public <T extends AbstractExtension<?>> void create(T resource) {
        // 1. 检查 name 是否已存在
        // 2. INSERT 一行（id 用 Snowflake，metadata 写 creation_timestamp）
    }

    @Override
    public <T extends AbstractExtension<?>> void update(T resource) {
        // UPDATE ... SET spec=?, status=?, version_lock=version_lock+1 WHERE id=? AND version_lock=?
    }

    @Override
    public <T extends AbstractExtension<?>> void delete(Class<T> modelClass, String name) {
        // DELETE FROM {table} WHERE name=?
    }

    @Override
    public <T extends AbstractExtension<?>> Optional<T> get(Class<T> modelClass, String name) {
        // SELECT * FROM {table} WHERE name=?
    }

    @Override
    public <T extends AbstractExtension<?>> List<T> list(Class<T> modelClass, ListOptions opts) {
        // 动态拼接 WHERE/ORDER BY/LIMIT，支持 specFilter
    }

    // ===== by-id 方法（与 core 接口一致）=====
    @Override
    public <T extends AbstractExtension<?>> void deleteById(Class<T> modelClass, String id) { ... }

    @Override
    public <T extends AbstractExtension<?>> T updateStatusById(Class<T> modelClass, String id, String status) { ... }

    @Override
    public <T extends AbstractExtension<?>> Optional<T> getById(Class<T> modelClass, String id) { ... }

    // ===== 私有辅助 =====
    private <T extends AbstractExtension<?>> String resolveTableName(Class<T> modelClass) {
        // 从 @ExtensionModel 注解读取 group + kind，拼接 TABLE_PREFIX + group + "_" + kind
    }

    private <T extends AbstractExtension<?>> T mapRow(ResultSet rs, Class<T> modelClass) {
        // 反序列化 spec/status 为 POJO，填充 metadata
    }

    private String generateId() {
        return cn.hutool.core.util.IdUtil.getSnowflakeNextIdStr();
    }
}
```

**DDL 生成**（`StandaloneDdlTemplate`）：复用 core 的表结构（雪花 id 主键 + name/group/kind 唯一约束 + version_lock 乐观锁），在 `StandaloneDataSourceConfig` 启动时扫描 `@ExtensionModel` 类并建表。

### 4.5 宿主服务独立实现

#### StandaloneHostQueryService + StandaloneHostRepository

```java
/**
 * 独立主机查询服务实现。
 * 从 standalone 的 host 表读取主机信息（不依赖 core 的 HostService/HostMapper）。
 */
@Service
@RequiredArgsConstructor
public class StandaloneHostQueryService implements HostQueryService {

    private final StandaloneHostRepository hostRepository;

    @Override
    public HostResourceVO getHostResourceInfo(Long hostId) {
        // 1. 查询 host 记录（IP/端口/SSH 凭据）
        // 2. 通过 SshUtil 获取 CPU/内存/磁盘/网络信息
        // 3. 组装 HostResourceVO 返回
    }

    @Override
    public HostVO getHostById(Long hostId) {
        return hostRepository.findById(hostId)
                .map(this::toVO)
                .orElse(null);
    }
}
```

#### StandaloneFileAccessService

直接复用 Apache MINA SSHD 的 SFTP 能力（与 core 的 `FileService` 实现逻辑相同，但不依赖 core 类）：

```java
/**
 * 独立文件访问服务实现。
 * 基于 Apache MINA SSHD 的 SFTP 实现，与 core 的 FileService 逻辑等价。
 */
@Service
@RequiredArgsConstructor
public class StandaloneFileAccessService implements FileAccessService {

    private final StandaloneHostRepository hostRepository;
    private final ObjectMapper objectMapper;

    // 13 个方法：readTextFile/writeTextFile/downloadFileToMemory/uploadFile/
    //           uploadLocalFile/downloadFile/deleteFile/moveFile/listFiles/
    //           createDirectory/deleteDirectory/exists/getFileInfo

    private HostConnection getConnection(Long hostId) {
        // 从 hostRepository 查询主机，解密密码，返回 HostConnection DTO
    }
}
```

#### StandaloneInstanceQueryService + StandaloneInstanceLifecycleService

```java
/**
 * 独立实例查询服务实现。
 * 从 standalone 的 instance 表读取实例信息。
 */
@Service
@RequiredArgsConstructor
public class StandaloneInstanceQueryService implements InstanceQueryService {

    private final StandaloneInstanceRepository instanceRepository;
    private final StandaloneInstanceLifecycleService lifecycleService;

    // 9 个方法：getInstanceById/getInstancesByHostId/getInstancesByGameId/
    //          getInstanceStatus/startInstance/stopInstance/restartInstance/
    //          getInstanceLogs/executeCommand

    @Override
    public boolean startInstance(Long id) {
        return lifecycleService.start(id);
    }
    // ... stop/restart/logs/command 委托给 lifecycleService
}
```

`StandaloneInstanceLifecycleService` 负责 start/stop/restart 的实际执行（通过 SSH 命令或 RCON），logs 通过 SSH 读取日志文件，command 通过 RCON 执行。

### 4.6 host/instance 管理 Controller

提供 REST API 让用户管理主机和实例（主应用的核心功能在 standalone 中简化）：

| Controller | 端点 | 用途 |
|-----------|------|------|
| `StandaloneHostController` | `POST/GET/PUT/DELETE /api/standalone/hosts` | 主机 CRUD（IP/端口/SSH 用户名/密码/标签） |
| `StandaloneInstanceController` | `POST/GET/PUT/DELETE /api/standalone/instances` | 实例 CRUD（关联 hostId、游戏类型、安装路径、端口配置） |
| `StandaloneInstanceController` | `POST /api/standalone/instances/{id}/start` | 启动实例 |
| `StandaloneInstanceController` | `POST /api/standalone/instances/{id}/stop` | 停止实例 |
| `StandaloneInstanceController` | `POST /api/standalone/instances/{id}/restart` | 重启实例 |

### 4.7 application.yml

```yaml
server:
  port: 8081                          # 独立应用端口（避免与主应用 8080 冲突）

spring:
  application:
    name: plugin-l4d2-standalone
  datasource:
    url: jdbc:sqlite:${user.home}/game-platform-l4d2/data.db
    driver-class-name: org.sqlite.JDBC
    username: sa
    password: ""
  sql:
    init:
      mode: always                    # 启动时执行 schema.sql
      schema-locations: classpath:db/schema.sql

# L4D2 独立应用配置
l4d2:
  standalone:
    # SSH 连接超时（毫秒）
    ssh-timeout: 5000
    # RCON 默认端口（实例未配置时使用）
    default-rcon-port: 27015
    # 日志读取行数上限
    max-log-lines: 1000

logging:
  level:
    com.gameplatform: INFO
    org.apache.sshd: WARN
```

### 4.8 db/schema.sql

```sql
-- 主机表
CREATE TABLE IF NOT EXISTS host (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name VARCHAR(100) NOT NULL UNIQUE,
    ip VARCHAR(45) NOT NULL,
    port INTEGER NOT NULL DEFAULT 22,
    username VARCHAR(50) NOT NULL,
    password VARCHAR(255) NOT NULL,          -- AES 加密存储
    status VARCHAR(20) DEFAULT 'offline',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 实例表
CREATE TABLE IF NOT EXISTS instance (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name VARCHAR(100) NOT NULL,
    host_id INTEGER NOT NULL,
    game_code VARCHAR(20) NOT NULL DEFAULT 'l4d2',
    install_path VARCHAR(500) NOT NULL,
    status VARCHAR(20) DEFAULT 'stopped',
    port_config TEXT,                        -- JSON: {"game": 27015, "rcon": 27016}
    config_info TEXT,                        -- JSON: {"hostname": "...", "rconPassword": "..."}
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (host_id) REFERENCES host(id),
    UNIQUE(host_id, name)
);

-- 扩展资源表（由 StandaloneDdlTemplate 在启动时动态创建，此处仅占位说明）
-- 表名规则：ext_plugin-l4d2_{KindName}
-- 例如：ext_plugin-l4d2_AdminResource, ext_plugin-l4d2_SystemMetricResource 等
```

### 4.9 StandaloneServiceConfig（Bean 注册）

```java
@Configuration
@RequiredArgsConstructor
public class StandaloneServiceConfig {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Bean
    public StandaloneExtensionClient standaloneExtensionClient() {
        return new StandaloneExtensionClient(jdbcTemplate, objectMapper);
    }

    // StandaloneHostQueryService/FileAccessService/InstanceQueryService
    // 用 @Service 自动注册，无需在此手动声明
}
```

### 4.10 pom.xml（关键部分）

```xml
<parent>
    <groupId>com.gameplatform</groupId>
    <artifactId>game-platform-parent</artifactId>
    <version>1.0.0</version>
    <relativePath>../../pom.xml</relativePath>
</parent>

<artifactId>plugin-l4d2-standalone</artifactId>
<packaging>jar</packaging>

<dependencies>
    <!-- 业务代码（compile，打入 fat JAR）-->
    <dependency>
        <groupId>com.gameplatform</groupId>
        <artifactId>plugin-l4d2-core</artifactId>
        <version>${project.version}</version>
    </dependency>

    <!-- 独立运行所需依赖（compile，因不再由主应用 provided）-->
    <dependency>
        <groupId>com.gameplatform</groupId>
        <artifactId>game-platform-plugin</artifactId>
    </dependency>
    <dependency>
        <groupId>com.gameplatform</groupId>
        <artifactId>game-platform-api</artifactId>
    </dependency>
    <dependency>
        <groupId>org.pf4j</groupId>
        <artifactId>pf4j</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <dependency>
        <groupId>org.xerial</groupId>
        <artifactId>sqlite-jdbc</artifactId>
    </dependency>
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
        <groupId>cn.hutool</groupId>
        <artifactId>hutool-all</artifactId>
    </dependency>
    <dependency>
        <groupId>org.apache.sshd</groupId>
        <artifactId>sshd-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.apache.sshd</groupId>
        <artifactId>sshd-sftp</artifactId>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
            <configuration>
                <mainClass>com.gameplatform.plugin.l4d2.standalone.L4D2StandaloneApp</mainClass>
            </configuration>
            <executions>
                <execution>
                    <goals>
                        <goal>repackage</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

---

## 5. 数据流、错误处理、测试策略

### 5.1 启动数据流

```
java -jar plugin-l4d2-standalone-1.0.0.jar
  │
  ├─① Spring Boot 启动 L4D2StandaloneApp
  │   scanBasePackages = {l4d2, l4d2.standalone}
  │
  ├─② StandaloneDataSourceConfig 初始化
  │   ├─ 创建 SQLite 数据源 (${user.home}/game-platform-l4d2/data.db)
  │   ├─ 执行 db/schema.sql（host/instance 建表，IF NOT EXISTS）
  │   └─ StandaloneDdlTemplate 扫描 @ExtensionModel 类
  │      ├─ AdminResource → CREATE TABLE ext_plugin-l4d2_AdminResource IF NOT EXISTS
  │      ├─ DownloadTaskResource → CREATE TABLE ext_plugin-l4d2_DownloadTaskResource
  │      ├─ PluginConfigResource → CREATE TABLE ext_plugin-l4d2_PluginConfigResource
  │      └─ SystemMetricResource → CREATE TABLE ext_plugin-l4d2_SystemMetricResource
  │
  ├─③ Bean 注册完成
  │   ├─ standalone.ext.StandaloneExtensionClient (手动 @Bean)
  │   ├─ standalone.host.StandaloneHostQueryService (@Service 自动扫描)
  │   ├─ standalone.host.StandaloneFileAccessService (@Service)
  │   ├─ standalone.instance.StandaloneInstanceQueryService (@Service)
  │   ├─ standalone.instance.StandaloneInstanceLifecycleService (@Service)
  │   ├─ standalone.host.StandaloneHostRepository (@Service)
  │   ├─ standalone.instance.StandaloneInstanceRepository (@Service)
  │   └─ l4d2.controller.* (6 个 @RestController 自动扫描，注入上述 Bean)
  │
  ├─④ Controller 注册到 DispatcherServlet
  │   ├─ L4D2 业务 Controller（/api/plugin/l4d2/**）
  │   └─ Standalone 管理 Controller（/api/standalone/**）
  │
  └─⑤ 应用就绪，监听 8081
```

### 5.2 请求处理数据流（以"添加管理员"为例）

```
POST /api/plugin/l4d2/admins/add
  │
  ├─ AdminController.addAdmin(dto)
  │   ├─ instanceQueryService.getInstanceById(dto.getInstanceId())
  │   │   └─ StandaloneInstanceRepository.findById() → SQLite 查询 instance 表
  │   │
  │   ├─ extensionClient.create(resource)
  │   │   └─ StandaloneExtensionClient.create()
  │   │       ├─ resolveTableName(AdminResource.class) → "ext_plugin-l4d2_AdminResource"
  │   │       ├─ generateId() → Snowflake ID
  │   │       ├─ INSERT INTO ext_plugin-l4d2_AdminResource (id, name, ...) VALUES (?, ?, ...)
  │   │       └─ 冲突 → DuplicateExtensionException
  │   │
  │   └─ fileAccessService.writeTextFile(hostId, configPath, content)
  │       └─ StandaloneFileAccessService.writeTextFile()
  │           ├─ getConnection(hostId) → StandaloneHostRepository.findById()
  │           ├─ SshClient.setUpDefaultClient() → connect() → SftpClient
  │           └─ 写入远程文件 admins.cfg
  │
  └─ 返回 Result.success(vo)
```

### 5.3 错误处理策略

| 层级 | 错误类型 | 处理方式 |
|------|---------|---------|
| **Controller 层** | 参数校验失败 | `@Valid` + `MethodArgumentNotValidException` 全局处理，返回 400 |
| **Controller 层** | 业务异常（实例不存在、管理员不存在等） | Controller 内 `if (instance == null) return Result.fail(...)` 模式（沿用现有风格） |
| **Service 层** | SSH 连接失败/超时 | 抛出 `BusinessException`，Controller 捕获后返回 500 + 错误消息 |
| **ExtensionClient** | name 冲突 | 抛 `DuplicateExtensionException`，Controller 捕获返回 400 |
| **ExtensionClient** | 资源不存在 | 抛 `ExtensionNotFoundException`，Controller 捕获返回 404 |
| **Repository 层** | SQL 异常 | 抛 `DataAccessException`（Spring 自动转换），由上层捕获 |
| **全局兜底** | 未捕获异常 | `@RestControllerAdvice` + `ExceptionHandler<Exception>` 返回 500 |

**新增**：`standalone/config/StandaloneExceptionHandler.java` — 全局异常处理器，统一错误响应格式（与主应用的 `Result` 格式一致）。

### 5.4 测试策略

#### 5.4.1 plugin-l4d2-core 测试（保持现有）

- 现有测试（若有）随代码迁移到 `plugin-l4d2-core/src/test/`
- 行为不变，仍由主应用 `mvn test` 覆盖

#### 5.4.2 plugin-l4d2-standalone 测试（新增）

| 测试类 | 类型 | 覆盖范围 |
|--------|------|---------|
| `StandaloneExtensionClientTest` | 单元测试（H2 内存库） | create/get/list/update/delete/by-id 方法、name 冲突、乐观锁、specFilter 过滤 |
| `StandaloneHostRepositoryTest` | 单元测试（H2） | host CRUD、findById/findAll |
| `StandaloneInstanceRepositoryTest` | 单元测试（H2） | instance CRUD、findByHostId/findByGameId |
| `StandaloneHostQueryServiceTest` | 单元测试（Mock SshUtil） | getHostResourceInfo 返回 VO 结构正确 |
| `StandaloneInstanceQueryServiceTest` | 单元测试（Mock LifecycleService） | 9 个方法委托正确 |
| `L4D2StandaloneAppTest` | 集成测试（@SpringBootTest） | 应用可启动、4 个扩展表自动创建、Controller 端点可访问 |

**测试数据源**：使用 H2 内存数据库（`jdbc:h2:mem:test`）替代 SQLite，避免文件系统依赖。通过 `application-test.yml` 覆盖数据源配置。

### 5.5 验收标准

1. `mvn package -pl plugin-l4d2-core` 生成含 PF4J Manifest 的插件 JAR
2. `mvn package -pl plugin-l4d2-standalone` 生成可独立启动的 fat JAR
3. `java -jar plugin-l4d2-standalone-1.0.0.jar` 启动成功，监听 8081
4. 启动后 4 个扩展表自动创建
5. `POST /api/standalone/hosts` 可添加主机
6. `POST /api/standalone/instances` 可添加实例
7. `POST /api/plugin/l4d2/admins/add` 可添加管理员（依赖前置 host+instance）
8. 插件 JAR 复制到主应用 plugins 目录后，主应用 `mvn test` 361 个测试全通过
9. standalone 模块单元测试覆盖率 ≥ 70%

---

## 6. 涉及文件清单

### 6.1 新增文件

**aggregator pom（1 个）**：
- `backend/plugin-l4d2/pom.xml`（改造为 packaging=pom，含两个 module）

**plugin-l4d2-core 模块**：
- `backend/plugin-l4d2/plugin-l4d2-core/pom.xml`
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/...`（原样迁移现有代码）
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/resources/`（原样迁移）

**plugin-l4d2-standalone 模块**：
- `backend/plugin-l4d2/plugin-l4d2-standalone/pom.xml`
- `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/L4D2StandaloneApp.java`
- `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/config/StandaloneDataSourceConfig.java`
- `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/config/StandaloneServiceConfig.java`
- `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/config/StandaloneExceptionHandler.java`
- `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/ext/StandaloneExtensionClient.java`
- `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/ext/StandaloneExtensionRowMapper.java`
- `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/ext/StandaloneDdlTemplate.java`
- `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/host/StandaloneHostQueryService.java`
- `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/host/StandaloneFileAccessService.java`
- `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/host/StandaloneHostController.java`
- `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/host/StandaloneHostEntity.java`
- `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/host/StandaloneHostRepository.java`
- `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/instance/StandaloneInstanceQueryService.java`
- `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/instance/StandaloneInstanceController.java`
- `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/instance/StandaloneInstanceEntity.java`
- `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/instance/StandaloneInstanceRepository.java`
- `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/instance/StandaloneInstanceLifecycleService.java`
- `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/resources/application.yml`
- `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/resources/application-dev.yml`
- `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/resources/db/schema.sql`
- `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/resources/static/ui/`（前端资源）

### 6.2 删除文件

- `backend/plugin-l4d2/src/`（原单模块的 src 目录，迁移后删除）
- `backend/plugin-l4d2/pom.xml` 中的 spring-boot-maven-plugin 配置（移到 standalone 子模块）
- `backend/plugin-l4d2/src/main/java/.../L4D2StandaloneApp.java`（从 core 移除，迁入 standalone）

### 6.3 不修改的文件

- `backend/pom.xml` 的 `<modules>` 部分（仍指向 `plugin-l4d2` aggregator）
- 主应用 `backend/core/` 模块的所有代码
- 主应用加载插件的逻辑（`PluginSpringContextFactory`、`PluginAutoLoader` 等）

---

## 7. 风险与回滚

### 7.1 风险

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| 模块迁移后包路径变化导致主应用加载失败 | 低 | 高 | 保持 `com.gameplatform.plugin.l4d2.*` 包名不变，仅目录层级变化 |
| StandaloneExtensionClient 与 core 实现行为不一致 | 中 | 中 | 单元测试覆盖核心场景；DDL 与 core 保持一致 |
| standalone 启动时扩展表建表失败 | 低 | 高 | schema.sql 使用 IF NOT EXISTS；建表失败时日志明确报错 |
| SSH/RCON 连接逻辑在 standalone 中行为与 core 不一致 | 中 | 中 | 复用相同的 Apache MINA SSHD 库；集成测试验证连接 |
| 前端静态资源在 standalone 中无法访问 | 低 | 低 | 将 ui/ 复制到 standalone 的 static/ 下 |

### 7.2 回滚策略

- 所有变更集中在 `backend/plugin-l4d2/` 目录内，不影响其他模块
- 如需回滚：`git revert` 相关提交，恢复单模块结构
- 主应用依赖的插件 JAR 在回滚后仍可从原 `plugin-l4d2` 模块生成

---

## 8. 未来演进

- 后续插件（如 plugin-minecraft）可参考此模式，各自独立目录管理
- 若多个插件的 standalone 实现有共性，可考虑抽取共用基础模块（当前 YAGNI，不做）
- standalone 的 host/instance 管理可逐步增强，接近主应用功能（如 SSH 连接池、资源监控定时任务等）

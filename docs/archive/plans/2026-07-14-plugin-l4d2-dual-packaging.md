# plugin-l4d2 双模式打包实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 backend/plugin-l4d2 单模块改造为 aggregator + plugin-l4d2-core (PF4J 插件 JAR) + plugin-l4d2-standalone (独立运行 fat JAR) 双子模块，standalone 内部独立实现 4 个基础设施服务（ExtensionClient、HostQueryService、FileAccessService、InstanceQueryService），不依赖 game-platform-core。

**Architecture:** aggregator (packaging=pom) 聚合两个子模块；plugin-l4d2-core 保持现有业务代码原样迁移 + provided 依赖 + maven-jar-plugin 写 PF4J Manifest；plugin-l4d2-standalone compile 依赖 plugin-l4d2-core，内部实现 StandaloneExtensionClient（简化版，无路由，单插件）+ host/instance 管理（Entity/Repository/Controller）+ 3 个宿主服务实现 + application.yml + schema.sql。

**Tech Stack:** Java 17、Spring Boot 3.2.5、PF4J 3.10.0、SQLite、Apache MINA SSHD、Hutool、Jackson、JdbcTemplate、H2（测试）

---

## 关键背景信息

### 现有代码关键事实（已通过 Read 工具验证）

1. **现有 plugin-l4d2 单模块结构**：`backend/plugin-l4d2/src/main/java/com/gameplatform/plugin/l4d2/` 包含 L4D2Plugin、L4D2Extension、L4D2StandaloneApp、config/、controller/ (6个)、dto/ (12个)、extension/ (4组)、service/ (2个)、util/、vo/ (10个)
2. **现有 pom.xml**：packaging=jar，含 maven-jar-plugin (写 Manifest) + spring-boot-maven-plugin (classifier=standalone, skip=true)
3. **Controller 依赖注入**：通过 `InstanceQueryService`、`FileAccessService`、`HostQueryService`、`ExtensionClient` 接口（均在 plugin 模块），由主应用 core 实现
4. **core 的 ExtensionClientImpl** 依赖 `ExtensionRouter`/`ExtensionQueryDialect`/`ExtensionRowMapper`/`ExtensionIdGenerator`
5. **表名规则**（ExtensionRouter.sanitize）：`plugin-l4d2` → `plugin_l4d2`，kind `AdminResource` → `adminresource`，表名 = `ext_plugin_l4d2_adminresource`
6. **扩展表 DDL**（DdlTemplate）：`id VARCHAR(20) NOT NULL PRIMARY KEY` + `UNIQUE (name, group_name, kind)` + version 乐观锁列
7. **AesUtil** 在 core 模块，是 static 方法 + 默认密钥 "GamePlatform2024" + 依赖 Hutool SecureUtil
8. **SshUtil** 在 core 模块，是 @Component，依赖 GamePlatformConfig
9. **RconService** 在 plugin-l4d2 模块内，依赖 L4D2Config（plugin.l4d2 前缀）
10. **HostVO** 字段：id/name/ip/sshPort/sshUsername/status/...
11. **InstanceVO** 字段：id/instanceName/hostId/gameId/gameCode/installPath/configInfo (Map)/portConfig (Map)/runStatus/...

### StandaloneExtensionClient 关键设计

- 表名解析：直接读 `@ExtensionModel` 注解的 strategy，MODEL_ISOLATED → `ext_plugin_l4d2_{sanitizeLower(kind)}`
- group_name 列存原始 pluginId `plugin-l4d2`（不 sanitize）
- id 生成：`cn.hutool.core.util.IdUtil.getSnowflakeNextIdStr()`（与 core SnowflakeIdGenerator 一致）
- 不依赖 core 的 ExtensionRouter，因为只有一个插件

---

## Task 1: 创建 aggregator pom 并迁移 plugin-l4d2-core

**Files:**
- Modify: `backend/plugin-l4d2/pom.xml` (改为 aggregator)
- Create: `backend/plugin-l4d2/plugin-l4d2-core/pom.xml`
- Move: `backend/plugin-l4d2/src/` → `backend/plugin-l4d2/plugin-l4d2-core/src/`

- [ ] **Step 1: 创建 plugin-l4d2-core 目录结构**

```bash
mkdir -p backend/plugin-l4d2/plugin-l4d2-core
mkdir -p backend/plugin-l4d2/plugin-l4d2-standalone
```

- [ ] **Step 2: 迁移 src 到 plugin-l4d2-core**

使用 git mv 保留历史：

```bash
cd backend/plugin-l4d2
git mv src plugin-l4d2-core/src
```

- [ ] **Step 3: 从 plugin-l4d2-core 移除 L4D2StandaloneApp.java**

```bash
rm backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/L4D2StandaloneApp.java
```

理由：standalone 入口迁入 standalone 子模块。

- [ ] **Step 4: 创建 plugin-l4d2-core/pom.xml**

写入 `backend/plugin-l4d2/plugin-l4d2-core/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.gameplatform</groupId>
        <artifactId>game-platform-parent</artifactId>
        <version>1.0.0</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>plugin-l4d2-core</artifactId>
    <packaging>jar</packaging>
    <name>L4D2 Plugin Core</name>
    <description>求生之路2 游戏服务器增强插件 - 核心业务代码（PF4J 插件 JAR）</description>

    <properties>
        <plugin.id>plugin-l4d2</plugin.id>
        <plugin.class>com.gameplatform.plugin.l4d2.L4D2Plugin</plugin.class>
        <plugin.version>1.0.0</plugin.version>
        <plugin.provider>GamePlatform</plugin.provider>
        <plugin.description>L4D2 游戏服务器增强插件</plugin.description>
    </properties>

    <dependencies>
        <!-- 插件扩展点模块 -->
        <dependency>
            <groupId>com.gameplatform</groupId>
            <artifactId>game-platform-plugin</artifactId>
            <scope>provided</scope>
        </dependency>

        <!-- API 模块 -->
        <dependency>
            <groupId>com.gameplatform</groupId>
            <artifactId>game-platform-api</artifactId>
            <scope>provided</scope>
        </dependency>

        <!-- PF4J 插件框架 -->
        <dependency>
            <groupId>org.pf4j</groupId>
            <artifactId>pf4j</artifactId>
            <scope>provided</scope>
        </dependency>

        <!-- Spring Boot -->
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

        <!-- Jackson -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <scope>provided</scope>
        </dependency>

        <!-- Hutool 工具类 -->
        <dependency>
            <groupId>cn.hutool</groupId>
            <artifactId>hutool-all</artifactId>
            <scope>provided</scope>
        </dependency>

        <!-- 测试依赖 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- 写入 PF4J Manifest -->
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
        </plugins>
    </build>

</project>
```

- [ ] **Step 5: 改造 backend/plugin-l4d2/pom.xml 为 aggregator**

用 Edit 工具替换整个文件内容为：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.gameplatform</groupId>
        <artifactId>game-platform-parent</artifactId>
        <version>1.0.0</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>plugin-l4d2</artifactId>
    <packaging>pom</packaging>
    <name>L4D2 Plugin Aggregator</name>
    <description>求生之路2 插件聚合模块（core + standalone）</description>

    <modules>
        <module>plugin-l4d2-core</module>
        <module>plugin-l4d2-standalone</module>
    </modules>

</project>
```

- [ ] **Step 6: 验证 plugin-l4d2-core 可独立编译**

```bash
cd backend
mvn clean compile -pl plugin-l4d2/plugin-l4d2-core -am
```

Expected: BUILD SUCCESS。如果有编译错误，检查是否所有依赖都已声明。

- [ ] **Step 7: 验证 plugin-l4d2-core 可打包为 PF4J 插件 JAR**

```bash
cd backend
mvn clean package -pl plugin-l4d2/plugin-l4d2-core -am -DskipTests
```

Expected: 生成 `plugin-l4d2-core/target/plugin-l4d2-core-1.0.0.jar`，用 `jar tf` 检查含 `META-INF/MANIFEST.MF`。

验证 Manifest：

```bash
jar tf plugin-l4d2-core/target/plugin-l4d2-core-1.0.0.jar | findstr MANIFEST
unzip -p plugin-l4d2-core/target/plugin-l4d2-core-1.0.0.jar META-INF/MANIFEST.MF
```

Expected: 含 `Plugin-Id: plugin-l4d2`、`Plugin-Class: com.gameplatform.plugin.l4d2.L4D2Plugin` 等。

- [ ] **Step 8: 验证主应用测试仍通过**

```bash
cd backend
mvn test -pl core -am
```

Expected: 361 个测试全通过（主应用不依赖 plugin-l4d2-core，但验证无回归）。

- [ ] **Step 9: 提交**

```bash
cd backend
git add plugin-l4d2/
git commit -m "refactor(plugin-l4d2): split into aggregator + plugin-l4d2-core sub-module

将 plugin-l4d2 单模块改造为 aggregator (packaging=pom) + plugin-l4d2-core
子模块。业务代码原样迁移，保持包名 com.gameplatform.plugin.l4d2.* 不变。
移除 L4D2StandaloneApp.java（迁入 standalone 子模块）。

plugin-l4d2-core 保持 provided 依赖 + maven-jar-plugin 写 PF4J Manifest，
生成的插件 JAR 与主应用完全兼容。"
```

---

## Task 2: 创建 plugin-l4d2-standalone 模块骨架

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/pom.xml`
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/L4D2StandaloneApp.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/resources/application.yml`
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/resources/db/schema.sql`

- [ ] **Step 1: 创建 plugin-l4d2-standalone/pom.xml**

写入 `backend/plugin-l4d2/plugin-l4d2-standalone/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.gameplatform</groupId>
        <artifactId>game-platform-parent</artifactId>
        <version>1.0.0</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>plugin-l4d2-standalone</artifactId>
    <packaging>jar</packaging>
    <name>L4D2 Plugin Standalone</name>
    <description>求生之路2 插件独立运行模块（Spring Boot fat JAR）</description>

    <dependencies>
        <!-- 业务代码（compile，打入 fat JAR）-->
        <dependency>
            <groupId>com.gameplatform</groupId>
            <artifactId>plugin-l4d2-core</artifactId>
            <version>${project.version}</version>
        </dependency>

        <!-- 插件扩展点模块（compile，standalone 需要接口）-->
        <dependency>
            <groupId>com.gameplatform</groupId>
            <artifactId>game-platform-plugin</artifactId>
        </dependency>

        <!-- API 模块（compile）-->
        <dependency>
            <groupId>com.gameplatform</groupId>
            <artifactId>game-platform-api</artifactId>
        </dependency>

        <!-- PF4J（compile）-->
        <dependency>
            <groupId>org.pf4j</groupId>
            <artifactId>pf4j</artifactId>
        </dependency>

        <!-- Spring Boot -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>

        <!-- SQLite -->
        <dependency>
            <groupId>org.xerial</groupId>
            <artifactId>sqlite-jdbc</artifactId>
        </dependency>

        <!-- Jackson -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>

        <!-- Hutool -->
        <dependency>
            <groupId>cn.hutool</groupId>
            <artifactId>hutool-all</artifactId>
        </dependency>

        <!-- Apache MINA SSHD -->
        <dependency>
            <groupId>org.apache.sshd</groupId>
            <artifactId>sshd-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.sshd</groupId>
            <artifactId>sshd-sftp</artifactId>
        </dependency>

        <!-- 测试依赖 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
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

</project>
```

- [ ] **Step 2: 检查父 pom 是否已声明 sshd 依赖版本**

读取 `backend/pom.xml`，确认 `<dependencyManagement>` 中是否有 `sshd-core`/`sshd-sftp`。如果没有，需要在 standalone 的 pom.xml 中显式声明版本（2.12.1）。

若父 pom 缺失，在 standalone pom.xml 的 dependencies 中改为：

```xml
<dependency>
    <groupId>org.apache.sshd</groupId>
    <artifactId>sshd-core</artifactId>
    <version>2.12.1</version>
</dependency>
<dependency>
    <groupId>org.apache.sshd</groupId>
    <artifactId>sshd-sftp</artifactId>
    <version>2.12.1</version>
</dependency>
```

- [ ] **Step 3: 创建 L4D2StandaloneApp.java**

写入 `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/L4D2StandaloneApp.java`：

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
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@SpringBootApplication(scanBasePackages = {
        "com.gameplatform.plugin.l4d2",
        "com.gameplatform.plugin.l4d2.standalone"
})
public class L4D2StandaloneApp {
    public static void main(String[] args) {
        SpringApplication.run(L4D2StandaloneApp.class, args);
    }
}
```

- [ ] **Step 4: 创建 application.yml**

写入 `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/resources/application.yml`：

```yaml
server:
  port: 8081

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
      mode: always
      schema-locations: classpath:db/schema.sql

# L4D2 插件配置（与主应用 plugin.l4d2 前缀一致，业务代码读这个）
plugin:
  l4d2:
    rcon-timeout: 5000
    rcon-retry-count: 3
    rcon-retry-interval: 1000
    vpk-scan-path: addons
    vpk-cache-enabled: true
    vpk-cache-expire: 300

# L4D2 独立应用配置
l4d2:
  standalone:
    ssh-timeout: 10000
    default-rcon-port: 27015
    max-log-lines: 1000

logging:
  level:
    com.gameplatform: INFO
    org.apache.sshd: WARN
```

- [ ] **Step 5: 创建 db/schema.sql**

写入 `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/resources/db/schema.sql`：

```sql
-- 主机表
CREATE TABLE IF NOT EXISTS host (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name VARCHAR(100) NOT NULL UNIQUE,
    ip VARCHAR(45) NOT NULL,
    ssh_port INTEGER NOT NULL DEFAULT 22,
    username VARCHAR(50) NOT NULL,
    password VARCHAR(255) NOT NULL,
    status VARCHAR(20) DEFAULT 'offline',
    tags VARCHAR(500),
    os_type VARCHAR(50),
    remark VARCHAR(500),
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
    deploy_type VARCHAR(20) DEFAULT 'native',
    run_status INTEGER DEFAULT 0,
    port_config TEXT,
    config_info TEXT,
    start_command VARCHAR(1000),
    stop_command VARCHAR(1000),
    remark VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (host_id) REFERENCES host(id),
    UNIQUE(host_id, name)
);

-- 扩展资源表由 StandaloneDdlTemplate 在启动时动态创建，此处不声明
-- 表名规则：ext_plugin_l4d2_{sanitizeLower(kind)}
-- 例如：ext_plugin_l4d2_adminresource, ext_plugin_l4d2_systemmetricresource,
--       ext_plugin_l4d2_downloadtaskresource, ext_plugin_l4d2_pluginconfigresource
```

- [ ] **Step 6: 验证 standalone 模块可编译（暂无业务代码也能编译 pom）**

```bash
cd backend
mvn clean compile -pl plugin-l4d2/plugin-l4d2-standalone -am
```

Expected: BUILD SUCCESS（此时只有 L4D2StandaloneApp，无业务 Bean，但能编译）。

- [ ] **Step 7: 提交**

```bash
cd backend
git add plugin-l4d2/plugin-l4d2-standalone/
git commit -m "feat(plugin-l4d2-standalone): add standalone module skeleton

新增 plugin-l4d2-standalone 子模块骨架：
- pom.xml: compile 依赖 plugin-l4d2-core + spring-boot/jdbc/sqlite/sshd/hutool
- L4D2StandaloneApp: @SpringBootApplication 入口，扫描 l4d2 + standalone 包
- application.yml: 端口 8081 + SQLite 数据源 + plugin.l4d2 配置
- db/schema.sql: host/instance 表 DDL（IF NOT EXISTS）"
```

---

## Task 3: 实现 StandaloneDdlTemplate 和 StandaloneExtensionRowMapper

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/ext/StandaloneDdlTemplate.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/ext/StandaloneExtensionRowMapper.java`

- [ ] **Step 1: 创建 StandaloneDdlTemplate.java**

写入 `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/ext/StandaloneDdlTemplate.java`：

```java
package com.gameplatform.plugin.l4d2.standalone.ext;

/**
 * 独立运行的扩展表 DDL 模板生成器。
 * <p>
 * 与 core 的 DdlTemplate 表结构完全一致：雪花 id 主键 + name/group/kind 唯一约束。
 * 表名规则：ext_plugin_l4d2_{sanitizeLower(kind)}。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public final class StandaloneDdlTemplate {

    private StandaloneDdlTemplate() {
    }

    /**
     * 生成建表 SQL（含基础索引）。
     *
     * @param tableName 表名（已 sanitize）
     * @return 可执行的 SQL 字符串（含 CREATE TABLE + 3 个 CREATE INDEX）
     */
    public static String generate(String tableName) {
        return "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                + "id VARCHAR(20) NOT NULL, "
                + "name VARCHAR(64) NOT NULL, "
                + "group_name VARCHAR(128) NOT NULL, "
                + "kind VARCHAR(128) NOT NULL, "
                + "version INT DEFAULT 1, "
                + "metadata TEXT NOT NULL, "
                + "spec TEXT NOT NULL, "
                + "status VARCHAR(32) DEFAULT 'ACTIVE', "
                + "creation_timestamp BIGINT, "
                + "update_timestamp BIGINT, "
                + "PRIMARY KEY (id), "
                + "UNIQUE (name, group_name, kind)"
                + ");"
                + "CREATE INDEX IF NOT EXISTS idx_" + tableName + "_group_kind ON " + tableName + "(group_name, kind);"
                + "CREATE INDEX IF NOT EXISTS idx_" + tableName + "_status ON " + tableName + "(status);"
                + "CREATE INDEX IF NOT EXISTS idx_" + tableName + "_creation ON " + tableName + "(creation_timestamp);";
    }

    /**
     * 把输入中非 [a-z0-9_] 的字符替换为下划线并转小写。
     * <p>
     * 与 core 的 ExtensionRouter.sanitize 规则一致。
     *
     * @param input 原始输入（如 "plugin-l4d2" 或 "AdminResource"）
     * @return 安全的表名片段（如 "plugin_l4d2" 或 "adminresource"）
     */
    public static String sanitize(String input) {
        if (input == null || input.isEmpty()) {
            return "_";
        }
        return input.toLowerCase().replaceAll("[^a-z0-9]", "_");
    }
}
```

- [ ] **Step 2: 创建 StandaloneExtensionRowMapper.java**

写入 `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/ext/StandaloneExtensionRowMapper.java`：

```java
package com.gameplatform.plugin.l4d2.standalone.ext;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.api.extension.AbstractExtension;
import com.gameplatform.api.extension.ExtensionMetadata;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 独立运行的扩展表行映射器。
 * <p>
 * 与 core 的 ExtensionRowMapper 逻辑一致：用 Jackson 将 metadata 和 spec TEXT 列
 * 反序列化为强类型对象。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public class StandaloneExtensionRowMapper<T extends AbstractExtension<?>> implements RowMapper<T> {

    private final Class<T> modelClass;
    private final ObjectMapper objectMapper;

    public StandaloneExtensionRowMapper(Class<T> modelClass, ObjectMapper objectMapper) {
        this.modelClass = modelClass;
        this.objectMapper = objectMapper;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T mapRow(ResultSet rs, int rowNum) throws SQLException {
        try {
            T extension = modelClass.getDeclaredConstructor().newInstance();

            extension.setId(rs.getString("id"));
            extension.setName(rs.getString("name"));
            extension.setGroupName(rs.getString("group_name"));
            extension.setKind(rs.getString("kind"));
            int version = rs.getInt("version");
            extension.setVersion(rs.wasNull() ? null : version);
            extension.setStatus(rs.getString("status"));

            String metadataJson = rs.getString("metadata");
            if (metadataJson != null && !metadataJson.isEmpty()) {
                extension.setMetadata(objectMapper.readValue(metadataJson, ExtensionMetadata.class));
            }

            String specJson = rs.getString("spec");
            if (specJson != null && !specJson.isEmpty()) {
                JavaType specType = objectMapper.getTypeFactory().constructType(
                        modelClass.getGenericSuperclass() instanceof Class
                                ? Object.class
                                : ((java.lang.reflect.ParameterizedType) modelClass.getGenericSuperclass()).getActualTypeArguments()[0]);
                extension.setSpec(objectMapper.readValue(specJson, specType));
            }

            return extension;
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("反序列化 Extension 失败: " + modelClass.getName(), e);
        }
    }
}
```

- [ ] **Step 3: 验证编译**

```bash
cd backend
mvn clean compile -pl plugin-l4d2/plugin-l4d2-standalone -am
```

Expected: BUILD SUCCESS。

- [ ] **Step 4: 提交**

```bash
cd backend
git add plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/ext/
git commit -m "feat(standalone): add StandaloneDdlTemplate and StandaloneExtensionRowMapper

- StandaloneDdlTemplate: 生成扩展表 DDL（与 core DdlTemplate 一致）
- StandaloneExtensionRowMapper: ResultSet → AbstractExtension（与 core 一致）"
```

---

## Task 4: 实现 StandaloneExtensionClient

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/ext/StandaloneExtensionClient.java`

- [ ] **Step 1: 创建 StandaloneExtensionClient.java**

写入 `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/ext/StandaloneExtensionClient.java`：

```java
package com.gameplatform.plugin.l4d2.standalone.ext;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.api.extension.AbstractExtension;
import com.gameplatform.api.extension.ExtensionMetadata;
import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.extension.ExtensionModel;
import com.gameplatform.plugin.extension.ListOptions;
import com.gameplatform.plugin.extension.SpecFilter;
import com.gameplatform.plugin.extension.Strategy;
import com.gameplatform.plugin.extension.exception.DuplicateExtensionException;
import com.gameplatform.plugin.extension.exception.ExtensionNotFoundException;
import com.gameplatform.plugin.extension.exception.ExtensionStoreException;
import com.gameplatform.plugin.extension.exception.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 独立运行的 ExtensionClient 实现。
 * <p>
 * 不依赖 core 的 ExtensionRouter/DdlTemplate/ExtensionRowMapper，
 * 直接使用 JdbcTemplate + Jackson 实现 CRUD。
 * <p>
 * 表名规则：ext_plugin_l4d2_{sanitizeLower(kind)}（与 core 的 ExtensionRouter 一致）。
 * 表结构：id/name/group_name/kind/version/metadata/spec/status/creation_timestamp/
 *        update_timestamp（与 core 的 DdlTemplate 一致）。
 * <p>
 * 因为 standalone 只有一个插件（plugin-l4d2），不需要路由，group_name 固定为 "plugin-l4d2"。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class StandaloneExtensionClient implements ExtensionClient {

    private static final String PLUGIN_ID = "plugin-l4d2";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public <T extends AbstractExtension<?>> void create(T extension) {
        ResolvedTable route = resolveTable(toModelClass(extension));
        long now = System.currentTimeMillis();

        extension.setId(IdUtil.getSnowflakeNextIdStr());
        extension.setGroupName(route.group);
        extension.setKind(route.kind);
        extension.setVersion(1);
        if (extension.getStatus() == null) {
            extension.setStatus("ACTIVE");
        }
        if (extension.getMetadata() == null) {
            extension.setMetadata(new ExtensionMetadata());
        }
        extension.getMetadata().setCreationTimestamp(now);
        extension.getMetadata().setUpdateTimestamp(now);

        String sql = "INSERT INTO " + route.table
                + " (id, name, group_name, kind, version, metadata, spec, status, creation_timestamp, update_timestamp)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try {
            jdbcTemplate.update(sql,
                    extension.getId(),
                    extension.getName(),
                    route.group,
                    route.kind,
                    extension.getVersion(),
                    toJson(extension.getMetadata()),
                    toJson(extension.getSpec()),
                    extension.getStatus(),
                    now,
                    now);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new DuplicateExtensionException(
                    "资源已存在: " + route.kind + "/" + extension.getName());
        } catch (org.springframework.dao.DataAccessException e) {
            String msg = e.getMostSpecificCause().getMessage();
            if (msg != null && (msg.contains("CONSTRAINT") || msg.contains("PRIMARY KEY") || msg.contains("UNIQUE"))) {
                throw new DuplicateExtensionException(
                        "资源已存在: " + route.kind + "/" + extension.getName());
            }
            throw new ExtensionStoreException("创建资源失败: " + route.kind + "/" + extension.getName(), e);
        }
    }

    @Override
    public <T extends AbstractExtension<?>> void update(T extension) {
        ResolvedTable route = resolveTable(toModelClass(extension));
        long now = System.currentTimeMillis();
        if (extension.getMetadata() != null) {
            extension.getMetadata().setUpdateTimestamp(now);
        }

        String sql = "UPDATE " + route.table
                + " SET spec=?, metadata=?, version=version+1, status=?, update_timestamp=?"
                + " WHERE id=? AND version=?";
        int affected;
        try {
            affected = jdbcTemplate.update(sql,
                    toJson(extension.getSpec()),
                    toJson(extension.getMetadata()),
                    extension.getStatus(),
                    now,
                    extension.getId(),
                    extension.getVersion());
        } catch (Exception e) {
            throw new ExtensionStoreException("更新资源失败: " + route.kind + "/" + extension.getName(), e);
        }
        if (affected == 0) {
            if (get(toModelClass(extension), extension.getName()).isPresent()) {
                throw new OptimisticLockException(
                        "版本冲突: " + route.kind + "/" + extension.getName());
            }
            throw new ExtensionNotFoundException(
                    "资源不存在: " + route.kind + "/" + extension.getName());
        }
        extension.setVersion(extension.getVersion() + 1);
    }

    @Override
    public <T extends AbstractExtension<?>> void delete(Class<T> modelClass, String name) {
        ResolvedTable route = resolveTable(modelClass);
        String sql = "DELETE FROM " + route.table
                + " WHERE name=? AND group_name=? AND kind=?";
        int affected;
        try {
            affected = jdbcTemplate.update(sql, name, route.group, route.kind);
        } catch (Exception e) {
            throw new ExtensionStoreException("删除资源失败: " + route.kind + "/" + name, e);
        }
        if (affected == 0) {
            throw new ExtensionNotFoundException("资源不存在: " + route.kind + "/" + name);
        }
    }

    @Override
    public <T extends AbstractExtension<?>> T updateStatus(Class<T> modelClass, String name, String status) {
        ResolvedTable route = resolveTable(modelClass);
        long now = System.currentTimeMillis();
        String sql = "UPDATE " + route.table
                + " SET status=?, update_timestamp=?"
                + " WHERE name=? AND group_name=? AND kind=?";
        int affected;
        try {
            affected = jdbcTemplate.update(sql, status, now, name, route.group, route.kind);
        } catch (Exception e) {
            throw new ExtensionStoreException("更新状态失败: " + route.kind + "/" + name, e);
        }
        if (affected == 0) {
            throw new ExtensionNotFoundException("资源不存在: " + route.kind + "/" + name);
        }
        return get(modelClass, name).orElseThrow(() -> new ExtensionNotFoundException(
                "资源不存在: " + route.kind + "/" + name));
    }

    @Override
    public <T extends AbstractExtension<?>> Optional<T> get(Class<T> modelClass, String name) {
        ResolvedTable route = resolveTable(modelClass);
        String sql = "SELECT * FROM " + route.table
                + " WHERE name=? AND group_name=? AND kind=?";
        try {
            List<T> results = jdbcTemplate.query(sql,
                    new StandaloneExtensionRowMapper<>(modelClass, objectMapper),
                    name, route.group, route.kind);
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        } catch (Exception e) {
            throw new ExtensionStoreException("查询资源失败: " + route.kind + "/" + name, e);
        }
    }

    @Override
    public <T extends AbstractExtension<?>> void deleteById(Class<T> modelClass, String id) {
        ResolvedTable route = resolveTable(modelClass);
        String sql = "DELETE FROM " + route.table + " WHERE id=?";
        int affected;
        try {
            affected = jdbcTemplate.update(sql, id);
        } catch (Exception e) {
            throw new ExtensionStoreException("删除资源失败: " + route.kind + "/" + id, e);
        }
        if (affected == 0) {
            throw new ExtensionNotFoundException("资源不存在: " + route.kind + "/" + id);
        }
    }

    @Override
    public <T extends AbstractExtension<?>> T updateStatusById(Class<T> modelClass, String id, String status) {
        ResolvedTable route = resolveTable(modelClass);
        long now = System.currentTimeMillis();
        String sql = "UPDATE " + route.table
                + " SET status=?, update_timestamp=?"
                + " WHERE id=?";
        int affected;
        try {
            affected = jdbcTemplate.update(sql, status, now, id);
        } catch (Exception e) {
            throw new ExtensionStoreException("更新状态失败: " + route.kind + "/" + id, e);
        }
        if (affected == 0) {
            throw new ExtensionNotFoundException("资源不存在: " + route.kind + "/" + id);
        }
        return getById(modelClass, id).orElseThrow(() -> new ExtensionNotFoundException(
                "资源不存在: " + route.kind + "/" + id));
    }

    @Override
    public <T extends AbstractExtension<?>> Optional<T> getById(Class<T> modelClass, String id) {
        ResolvedTable route = resolveTable(modelClass);
        String sql = "SELECT * FROM " + route.table + " WHERE id=?";
        try {
            List<T> results = jdbcTemplate.query(sql,
                    new StandaloneExtensionRowMapper<>(modelClass, objectMapper),
                    id);
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        } catch (Exception e) {
            throw new ExtensionStoreException("查询资源失败: " + route.kind + "/" + id, e);
        }
    }

    @Override
    public <T extends AbstractExtension<?>> List<T> list(Class<T> modelClass, ListOptions opts) {
        ResolvedTable route = resolveTable(modelClass);
        StringBuilder sql = new StringBuilder("SELECT * FROM " + route.table);
        List<Object> args = new ArrayList<>();
        sql.append(" WHERE group_name=? AND kind=?");
        args.add(route.group);
        args.add(route.kind);

        if (opts.getStatus() != null) {
            sql.append(" AND status=?");
            args.add(opts.getStatus());
        }
        if (opts.getCreatedAfter() != null) {
            sql.append(" AND creation_timestamp > ?");
            args.add(opts.getCreatedAfter());
        }

        String orderCol = sanitizeOrderBy(opts.getOrderBy());
        sql.append(" ORDER BY ").append(orderCol).append(" DESC");
        sql.append(" LIMIT ? OFFSET ?");
        args.add(opts.getLimit());
        args.add(opts.getOffset());

        List<T> rows;
        try {
            rows = jdbcTemplate.query(sql.toString(),
                    new StandaloneExtensionRowMapper<>(modelClass, objectMapper),
                    args.toArray());
        } catch (Exception e) {
            throw new ExtensionStoreException("查询资源列表失败: " + route.kind, e);
        }

        return applyMemoryFilters(rows, opts);
    }

    @Override
    public <T extends AbstractExtension<?>> List<T> listAll(Class<T> modelClass) {
        return list(modelClass, new ListOptions());
    }

    @Override
    public long count(Class<? extends AbstractExtension<?>> modelClass, ListOptions opts) {
        ResolvedTable route = resolveTable(modelClass);
        if (opts.getSpecFilters().isEmpty() && opts.getLabelSelector().isEmpty()) {
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM " + route.table);
            List<Object> args = new ArrayList<>();
            sql.append(" WHERE group_name=? AND kind=?");
            args.add(route.group);
            args.add(route.kind);

            if (opts.getStatus() != null) {
                sql.append(" AND status=?");
                args.add(opts.getStatus());
            }
            if (opts.getCreatedAfter() != null) {
                sql.append(" AND creation_timestamp > ?");
                args.add(opts.getCreatedAfter());
            }

            Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
            return count != null ? count : 0;
        }
        // 有 spec/label 过滤时拉全量后内存计数
        ListOptions fetchAll = new ListOptions()
                .setStatus(opts.getStatus())
                .setCreatedAfter(opts.getCreatedAfter())
                .setLimit(Integer.MAX_VALUE)
                .setOffset(0);
        List<? extends AbstractExtension<?>> all = list(modelClass, fetchAll);
        return applyMemoryFilters(all, opts).size();
    }

    @Override
    public Set<String> getManagedTables() {
        return Collections.emptySet();
    }

    // ==================== 私有方法 ====================

    @SuppressWarnings("unchecked")
    private <T extends AbstractExtension<?>> Class<? extends AbstractExtension<?>> toModelClass(T extension) {
        return (Class<? extends AbstractExtension<?>>) extension.getClass();
    }

    /**
     * 解析模型类的表名与身份信息。
     * <p>
     * 与 core 的 ExtensionRouter.resolve 逻辑一致，但 group 固定为 "plugin-l4d2"。
     */
    private <T extends AbstractExtension<?>> ResolvedTable resolveTable(Class<? extends AbstractExtension<?>> modelClass) {
        ExtensionModel meta = modelClass.getAnnotation(ExtensionModel.class);
        Strategy strategy = (meta != null) ? meta.strategy() : Strategy.SHARED;
        String group = (meta != null && !meta.group().isEmpty()) ? meta.group() : PLUGIN_ID;
        String kind = (meta != null && !meta.kind().isEmpty()) ? meta.kind() : modelClass.getSimpleName();

        String table = switch (strategy) {
            case SHARED -> "extensions";
            case PLUGIN_ISOLATED -> "ext_" + StandaloneDdlTemplate.sanitize(PLUGIN_ID);
            case MODEL_ISOLATED -> "ext_" + StandaloneDdlTemplate.sanitize(PLUGIN_ID)
                    + "_" + StandaloneDdlTemplate.sanitize(kind);
        };
        return new ResolvedTable(table, group, kind);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new ExtensionStoreException("序列化失败: " + obj.getClass().getName(), e);
        }
    }

    /**
     * 在内存中对反序列化后的对象应用 spec 字段过滤和 label 过滤。
     * 与 core 的 SqliteQueryDialect.applyMemoryFilters 逻辑一致。
     */
    @SuppressWarnings("unchecked")
    private <T extends AbstractExtension<?>> List<T> applyMemoryFilters(List<T> rows, ListOptions opts) {
        if (opts.getSpecFilters().isEmpty() && opts.getLabelSelector().isEmpty()) {
            return rows;
        }
        return rows.stream()
                .filter(row -> matchesSpecFilters(row, opts) && matchesLabels(row, opts))
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private <T extends AbstractExtension<?>> boolean matchesSpecFilters(T row, ListOptions opts) {
        if (opts.getSpecFilters().isEmpty()) {
            return true;
        }
        Object spec = row.getSpec();
        if (spec == null) {
            return false;
        }
        Map<String, Object> specMap;
        if (spec instanceof Map) {
            specMap = (Map<String, Object>) spec;
        } else {
            try {
                specMap = objectMapper.convertValue(spec, Map.class);
            } catch (Exception e) {
                return false;
            }
        }
        for (SpecFilter f : opts.getSpecFilters()) {
            String key = f.getPath().replace("$.", "");
            Object fieldValue = specMap.get(key);
            if (!matchesOp(fieldValue, f.getOp(), f.getValue())) {
                return false;
            }
        }
        return true;
    }

    private <T extends AbstractExtension<?>> boolean matchesLabels(T row, ListOptions opts) {
        if (opts.getLabelSelector().isEmpty()) {
            return true;
        }
        if (row.getMetadata() == null || row.getMetadata().getLabels() == null) {
            return false;
        }
        Map<String, String> labels = row.getMetadata().getLabels();
        for (Map.Entry<String, String> entry : opts.getLabelSelector().entrySet()) {
            if (!Objects.equals(labels.get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private boolean matchesOp(Object fieldValue, String op, Object expected) {
        if (fieldValue == null) {
            return false;
        }
        switch (op) {
            case "=":
                return Objects.equals(toString(fieldValue), toString(expected));
            case "!=":
                return !Objects.equals(toString(fieldValue), toString(expected));
            case ">":
                return compareNumbers(fieldValue, expected) > 0;
            case "<":
                return compareNumbers(fieldValue, expected) < 0;
            case ">=":
                return compareNumbers(fieldValue, expected) >= 0;
            case "<=":
                return compareNumbers(fieldValue, expected) <= 0;
            case "like":
                return toString(fieldValue).contains(toString(expected).replace("%", ""));
            default:
                return false;
        }
    }

    private String toString(Object o) {
        return o == null ? null : o.toString();
    }

    private int compareNumbers(Object a, Object b) {
        return Double.compare(toDouble(a), toDouble(b));
    }

    private double toDouble(Object o) {
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        try {
            return Double.parseDouble(o.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String sanitizeOrderBy(String orderBy) {
        if (orderBy == null || orderBy.isEmpty()) {
            return "creation_timestamp";
        }
        return switch (orderBy) {
            case "creation_timestamp", "update_timestamp", "name", "status" -> orderBy;
            default -> "creation_timestamp";
        };
    }

    /**
     * 解析后的表信息。
     */
    private record ResolvedTable(String table, String group, String kind) {
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
cd backend
mvn clean compile -pl plugin-l4d2/plugin-l4d2-standalone -am
```

Expected: BUILD SUCCESS。

- [ ] **Step 3: 提交**

```bash
cd backend
git add plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/ext/StandaloneExtensionClient.java
git commit -m "feat(standalone): implement StandaloneExtensionClient

简化版 ExtensionClient 实现，不依赖 core 的 ExtensionRouter/DdlTemplate。
- 表名解析：读 @ExtensionModel 注解，MODEL_ISOLATED → ext_plugin_l4d2_{kind}
- group_name 固定为 'plugin-l4d2'
- id 生成：Hutool IdUtil.getSnowflakeNextIdStr()
- 乐观锁：UPDATE WHERE id=? AND version=?
- 内存过滤 spec/label（与 core SqliteQueryDialect 一致）"
```

---

## Task 5: 实现 host 模块（Entity/Repository/QueryService/FileAccessService/Controller）

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/host/StandaloneHostEntity.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/host/StandaloneHostRepository.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/host/StandaloneHostQueryService.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/host/StandaloneFileAccessService.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/host/StandaloneHostController.java`

- [ ] **Step 1: 创建 StandaloneHostEntity.java**

写入 `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/host/StandaloneHostEntity.java`：

```java
package com.gameplatform.plugin.l4d2.standalone.host;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 主机实体（对应 host 表）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class StandaloneHostEntity {

    /** 主机ID */
    private Long id;

    /** 主机名称 */
    private String name;

    /** IP地址 */
    private String ip;

    /** SSH端口 */
    private Integer sshPort;

    /** SSH用户名 */
    private String username;

    /** SSH密码（AES 加密存储） */
    private String password;

    /** 状态：online/offline */
    private String status;

    /** 标签 */
    private String tags;

    /** 操作系统类型 */
    private String osType;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: 创建 StandaloneHostRepository.java**

写入 `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/host/StandaloneHostRepository.java`：

```java
package com.gameplatform.plugin.l4d2.standalone.host;

import cn.hutool.crypto.SecureUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * 主机数据访问层。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class StandaloneHostRepository {

    /** AES 密钥（与 core AesUtil 一致） */
    private static final String AES_KEY = "GamePlatform2024";

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<StandaloneHostEntity> rowMapper = (rs, rowNum) -> {
        StandaloneHostEntity entity = new StandaloneHostEntity();
        entity.setId(rs.getLong("id"));
        entity.setName(rs.getString("name"));
        entity.setIp(rs.getString("ip"));
        entity.setSshPort(rs.getInt("ssh_port"));
        entity.setUsername(rs.getString("username"));
        entity.setPassword(rs.getString("password"));
        entity.setStatus(rs.getString("status"));
        entity.setTags(rs.getString("tags"));
        entity.setOsType(rs.getString("os_type"));
        entity.setRemark(rs.getString("remark"));
        entity.setCreatedAt(rs.getTimestamp("created_at") != null
                ? rs.getTimestamp("created_at").toLocalDateTime() : null);
        entity.setUpdatedAt(rs.getTimestamp("updated_at") != null
                ? rs.getTimestamp("updated_at").toLocalDateTime() : null);
        return entity;
    };

    /**
     * 根据 ID 查询主机。
     */
    public Optional<StandaloneHostEntity> findById(Long id) {
        String sql = "SELECT * FROM host WHERE id = ?";
        List<StandaloneHostEntity> list = jdbcTemplate.query(sql, rowMapper, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 查询全部主机。
     */
    public List<StandaloneHostEntity> findAll() {
        String sql = "SELECT * FROM host ORDER BY id";
        return jdbcTemplate.query(sql, rowMapper);
    }

    /**
     * 插入主机记录。密码会 AES 加密后存储。
     */
    public StandaloneHostEntity save(StandaloneHostEntity entity) {
        String encryptedPassword = encryptPassword(entity.getPassword());
        String sql = "INSERT INTO host (name, ip, ssh_port, username, password, status, tags, os_type, remark) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql,
                entity.getName(),
                entity.getIp(),
                entity.getSshPort() != null ? entity.getSshPort() : 22,
                entity.getUsername(),
                encryptedPassword,
                entity.getStatus() != null ? entity.getStatus() : "offline",
                entity.getTags(),
                entity.getOsType(),
                entity.getRemark());
        // 查询自增 ID
        Long id = jdbcTemplate.queryForObject("SELECT last_insert_rowid()", Long.class);
        entity.setId(id);
        return entity;
    }

    /**
     * 更新主机记录。
     */
    public StandaloneHostEntity update(StandaloneHostEntity entity) {
        String encryptedPassword = entity.getPassword() != null ? encryptPassword(entity.getPassword()) : null;
        String sql = "UPDATE host SET name=?, ip=?, ssh_port=?, username=?, status=?, tags=?, os_type=?, remark=?, "
                + "updated_at=CURRENT_TIMESTAMP WHERE id=?";
        jdbcTemplate.update(sql,
                entity.getName(),
                entity.getIp(),
                entity.getSshPort(),
                entity.getUsername(),
                entity.getStatus(),
                entity.getTags(),
                entity.getOsType(),
                entity.getRemark(),
                entity.getId());
        if (encryptedPassword != null) {
            jdbcTemplate.update("UPDATE host SET password=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    encryptedPassword, entity.getId());
        }
        return findById(entity.getId()).orElseThrow();
    }

    /**
     * 根据 ID 删除主机。
     */
    public boolean deleteById(Long id) {
        int affected = jdbcTemplate.update("DELETE FROM host WHERE id = ?", id);
        return affected > 0;
    }

    /**
     * 解密密码。
     */
    public String decryptPassword(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) {
            return "";
        }
        try {
            return SecureUtil.aes(AES_KEY.getBytes(StandardCharsets.UTF_8))
                    .decryptStr(encrypted);
        } catch (Exception e) {
            log.warn("解密密码失败，返回原值: {}", e.getMessage());
            return encrypted;
        }
    }

    private String encryptPassword(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        try {
            return SecureUtil.aes(AES_KEY.getBytes(StandardCharsets.UTF_8))
                    .encryptBase64(plain);
        } catch (Exception e) {
            log.error("加密密码失败: {}", e.getMessage());
            throw new RuntimeException("加密失败", e);
        }
    }
}
```

- [ ] **Step 3: 创建 StandaloneHostQueryService.java**

写入 `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/host/StandaloneHostQueryService.java`：

```java
package com.gameplatform.plugin.l4d2.standalone.host;

import com.gameplatform.plugin.service.HostQueryService;
import com.gameplatform.vo.HostResourceVO;
import com.gameplatform.vo.HostVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 独立运行的主机查询服务实现。
 * <p>
 * 从 standalone 的 host 表读取主机信息，通过 SSH 执行命令获取资源监控数据。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StandaloneHostQueryService implements HostQueryService {

    private final StandaloneHostRepository hostRepository;

    @Override
    public HostResourceVO getHostResourceInfo(Long hostId) {
        return hostRepository.findById(hostId)
                .map(this::queryResourceViaSsh)
                .orElse(null);
    }

    @Override
    public HostVO getHostById(Long hostId) {
        return hostRepository.findById(hostId)
                .map(this::toVO)
                .orElse(null);
    }

    private HostResourceVO queryResourceViaSsh(StandaloneHostEntity host) {
        HostResourceVO vo = new HostResourceVO();
        String password = hostRepository.decryptPassword(host.getPassword());

        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();
            try (ClientSession session = client.connect(host.getUsername(), host.getIp(), host.getSshPort())
                    .verify(10, TimeUnit.SECONDS).getSession()) {
                session.addPasswordIdentity(password);
                if (!session.auth().verify(10, TimeUnit.SECONDS).isSuccess()) {
                    log.warn("SSH 认证失败: {}@{}", host.getUsername(), host.getIp());
                    return vo;
                }

                // CPU 信息
                HostResourceVO.CpuInfo cpu = new HostResourceVO.CpuInfo();
                cpu.setCores(parseInt(executeCommand(session, "nproc")));
                cpu.setModel(executeCommand(session, "cat /proc/cpuinfo | grep 'model name' | head -1 | cut -d':' -f2 | sed 's/^ *//'").trim());
                cpu.setUsage(parseDouble(executeCommand(session, "top -bn1 | grep 'Cpu(s)' | awk '{print $2}' | cut -d'%' -f1").trim()));
                vo.setCpu(cpu);

                // 内存信息
                HostResourceVO.MemoryInfo memory = new HostResourceVO.MemoryInfo();
                String memLine = executeCommand(session, "free -m | grep Mem | awk '{print $2, $3, $4}'").trim();
                String[] memParts = memLine.split("\\s+");
                if (memParts.length >= 3) {
                    memory.setTotal(parseLong(memParts[0]));
                    memory.setUsed(parseLong(memParts[1]));
                    memory.setFree(parseLong(memParts[2]));
                    memory.setUsage(memory.getTotal() > 0
                            ? (double) memory.getUsed() / memory.getTotal() * 100 : 0);
                }
                vo.setMemory(memory);

                // 磁盘信息
                HostResourceVO.DiskInfo disk = new HostResourceVO.DiskInfo();
                String diskLine = executeCommand(session, "df -BG / | tail -1 | awk '{print $2, $3, $4}' | sed 's/G//g'").trim();
                String[] diskParts = diskLine.split("\\s+");
                if (diskParts.length >= 3) {
                    disk.setTotal(parseLong(diskParts[0]));
                    disk.setUsed(parseLong(diskParts[1]));
                    disk.setFree(parseLong(diskParts[2]));
                    disk.setUsage(disk.getTotal() > 0
                            ? (double) disk.getUsed() / disk.getTotal() * 100 : 0);
                }
                vo.setDisk(disk);

                // 网络信息
                HostResourceVO.NetworkInfo network = new HostResourceVO.NetworkInfo();
                String netLine = executeCommand(session,
                        "cat /proc/net/dev | grep -E 'eth0|ens33|ens192|enp' | head -1 | awk '{print $2, $10}'").trim();
                String[] netParts = netLine.split("\\s+");
                if (netParts.length >= 2) {
                    network.setRxBytes(parseLong(netParts[0]));
                    network.setTxBytes(parseLong(netParts[1]));
                }
                vo.setNetwork(network);
            }
        } catch (Exception e) {
            log.error("获取主机资源信息失败: {}@{} - {}", host.getUsername(), host.getIp(), e.getMessage());
        }
        return vo;
    }

    private String executeCommand(ClientSession session, String command) throws Exception {
        java.io.ByteArrayOutputStream stdout = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream stderr = new java.io.ByteArrayOutputStream();
        session.executeRemoteCommand(command, stdout, stderr, java.nio.charset.StandardCharsets.UTF_8);
        return stdout.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private HostVO toVO(StandaloneHostEntity entity) {
        HostVO vo = new HostVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setIp(entity.getIp());
        vo.setSshPort(entity.getSshPort());
        vo.setSshUsername(entity.getUsername());
        vo.setStatus("online".equals(entity.getStatus()) ? 1 : 0);
        vo.setTags(entity.getTags());
        vo.setOsType(entity.getOsType());
        vo.setRemark(entity.getRemark());
        vo.setCreateTime(entity.getCreatedAt());
        vo.setUpdateTime(entity.getUpdatedAt());
        return vo;
    }

    private Integer parseInt(String s) {
        try {
            return s == null || s.isEmpty() ? null : Integer.parseInt(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private Double parseDouble(String s) {
        try {
            return s == null || s.isEmpty() ? null : Double.parseDouble(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private Long parseLong(String s) {
        try {
            return s == null || s.isEmpty() ? null : Long.parseLong(s.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
```

- [ ] **Step 4: 创建 StandaloneFileAccessService.java**

写入 `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/host/StandaloneFileAccessService.java`：

```java
package com.gameplatform.plugin.l4d2.standalone.host;

import com.gameplatform.plugin.service.FileAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 独立运行的文件访问服务实现。
 * <p>
 * 基于 Apache MINA SSHD 的 SFTP 实现，与 core 的 FileService 逻辑等价。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StandaloneFileAccessService implements FileAccessService {

    private final StandaloneHostRepository hostRepository;

    @Override
    public String readTextFile(Long hostId, String remotePath) {
        byte[] bytes = downloadFileToMemory(hostId, remotePath);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public void writeTextFile(Long hostId, String remotePath, String content) {
        HostConnection conn = getConnection(hostId);
        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();
            try (ClientSession session = connect(client, conn);
                 SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                Path remoteParent = Paths.get(remotePath).getParent();
                if (remoteParent != null) {
                    try {
                        sftp.mkdir(remoteParent.toString());
                    } catch (Exception e) {
                        log.debug("创建远程目录失败或已存在: {}", remoteParent);
                    }
                }
                try (InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
                     OutputStream os = sftp.write(remotePath)) {
                    is.transferTo(os);
                }
            }
        } catch (Exception e) {
            log.error("写入远程文件失败: {}", remotePath, e);
            throw new RuntimeException("写入文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] downloadFileToMemory(Long hostId, String remotePath) {
        HostConnection conn = getConnection(hostId);
        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();
            try (ClientSession session = connect(client, conn);
                 SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                try (InputStream is = sftp.read(remotePath);
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    is.transferTo(baos);
                    return baos.toByteArray();
                }
            }
        } catch (Exception e) {
            log.error("下载远程文件失败: {}", remotePath, e);
            throw new RuntimeException("下载文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void uploadFile(Long hostId, String remotePath, MultipartFile file) {
        HostConnection conn = getConnection(hostId);
        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();
            try (ClientSession session = connect(client, conn);
                 SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                Path remoteParent = Paths.get(remotePath).getParent();
                if (remoteParent != null) {
                    try {
                        sftp.mkdir(remoteParent.toString());
                    } catch (Exception e) {
                        log.debug("创建远程目录失败或已存在: {}", remoteParent);
                    }
                }
                try (InputStream is = file.getInputStream();
                     OutputStream os = sftp.write(remotePath)) {
                    is.transferTo(os);
                }
            }
        } catch (Exception e) {
            log.error("上传文件失败: {}", remotePath, e);
            throw new RuntimeException("上传文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void uploadLocalFile(Long hostId, String remotePath, String localPath) {
        HostConnection conn = getConnection(hostId);
        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();
            try (ClientSession session = connect(client, conn);
                 SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                Path remoteParent = Paths.get(remotePath).getParent();
                if (remoteParent != null) {
                    try {
                        sftp.mkdir(remoteParent.toString());
                    } catch (Exception e) {
                        log.debug("创建远程目录失败或已存在: {}", remoteParent);
                    }
                }
                try (InputStream is = Files.newInputStream(Paths.get(localPath));
                     OutputStream os = sftp.write(remotePath)) {
                    is.transferTo(os);
                }
            }
        } catch (Exception e) {
            log.error("上传本地文件失败: {}", remotePath, e);
            throw new RuntimeException("上传文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void downloadFile(Long hostId, String remotePath, String localPath) {
        HostConnection conn = getConnection(hostId);
        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();
            try (ClientSession session = connect(client, conn);
                 SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                Path localParent = Paths.get(localPath).getParent();
                if (localParent != null) {
                    Files.createDirectories(localParent);
                }
                try (InputStream is = sftp.read(remotePath);
                     OutputStream os = Files.newOutputStream(Paths.get(localPath))) {
                    is.transferTo(os);
                }
            }
        } catch (Exception e) {
            log.error("下载文件失败: {}", remotePath, e);
            throw new RuntimeException("下载文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteFile(Long hostId, String remotePath) {
        HostConnection conn = getConnection(hostId);
        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();
            try (ClientSession session = connect(client, conn);
                 SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                sftp.remove(remotePath);
            }
        } catch (Exception e) {
            log.error("删除文件失败: {}", remotePath, e);
            throw new RuntimeException("删除文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void moveFile(Long hostId, String oldPath, String newPath) {
        HostConnection conn = getConnection(hostId);
        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();
            try (ClientSession session = connect(client, conn);
                 SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                sftp.rename(oldPath, newPath);
            }
        } catch (Exception e) {
            log.error("移动文件失败: {} -> {}", oldPath, newPath, e);
            throw new RuntimeException("移动文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<FileInfo> listFiles(Long hostId, String remotePath) {
        HostConnection conn = getConnection(hostId);
        List<FileInfo> files = new ArrayList<>();
        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();
            try (ClientSession session = connect(client, conn);
                 SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                Iterable<SftpClient.DirEntry> entries = sftp.readDir(remotePath);
                for (SftpClient.DirEntry entry : entries) {
                    if (".".equals(entry.getFilename()) || "..".equals(entry.getFilename())) {
                        continue;
                    }
                    FileInfo info = new FileInfo();
                    info.setName(entry.getFilename());
                    info.setPath(remotePath + "/" + entry.getFilename());
                    info.setDirectory(entry.getAttributes().isDirectory());
                    info.setSize(entry.getAttributes().getSize());
                    info.setLastModified(entry.getAttributes().getModifyTime().toMillis());
                    files.add(info);
                }
            }
        } catch (Exception e) {
            log.error("列出目录失败: {}", remotePath, e);
            throw new RuntimeException("列出目录失败: " + e.getMessage(), e);
        }
        return files;
    }

    @Override
    public void createDirectory(Long hostId, String remotePath) {
        HostConnection conn = getConnection(hostId);
        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();
            try (ClientSession session = connect(client, conn);
                 SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                sftp.mkdir(remotePath);
            }
        } catch (Exception e) {
            log.error("创建目录失败: {}", remotePath, e);
            throw new RuntimeException("创建目录失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteDirectory(Long hostId, String remotePath, boolean recursive) {
        HostConnection conn = getConnection(hostId);
        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();
            try (ClientSession session = connect(client, conn);
                 SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                if (recursive) {
                    deleteRecursive(sftp, remotePath);
                } else {
                    sftp.rmdir(remotePath);
                }
            }
        } catch (Exception e) {
            log.error("删除目录失败: {}", remotePath, e);
            throw new RuntimeException("删除目录失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(Long hostId, String remotePath) {
        return getFileInfo(hostId, remotePath) != null;
    }

    @Override
    public FileInfo getFileInfo(Long hostId, String remotePath) {
        HostConnection conn = getConnection(hostId);
        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();
            try (ClientSession session = connect(client, conn);
                 SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                SftpClient.Attributes attrs = sftp.stat(remotePath);
                FileInfo info = new FileInfo();
                info.setName(Paths.get(remotePath).getFileName().toString());
                info.setPath(remotePath);
                info.setDirectory(attrs.isDirectory());
                info.setSize(attrs.getSize());
                info.setLastModified(attrs.getModifyTime().toMillis());
                return info;
            }
        } catch (Exception e) {
            log.debug("获取文件信息失败（可能不存在）: {}", remotePath);
            return null;
        }
    }

    private void deleteRecursive(SftpClient sftp, String path) throws Exception {
        Iterable<SftpClient.DirEntry> entries = sftp.readDir(path);
        for (SftpClient.DirEntry entry : entries) {
            if (".".equals(entry.getFilename()) || "..".equals(entry.getFilename())) {
                continue;
            }
            String childPath = path + "/" + entry.getFilename();
            if (entry.getAttributes().isDirectory()) {
                deleteRecursive(sftp, childPath);
            } else {
                sftp.remove(childPath);
            }
        }
        sftp.rmdir(path);
    }

    private HostConnection getConnection(Long hostId) {
        StandaloneHostEntity host = hostRepository.findById(hostId)
                .orElseThrow(() -> new RuntimeException("主机不存在: " + hostId));
        String password = hostRepository.decryptPassword(host.getPassword());
        return new HostConnection(host.getIp(), host.getSshPort(), host.getUsername(), password);
    }

    private ClientSession connect(SshClient client, HostConnection conn) throws Exception {
        ClientSession session = client.connect(conn.username(), conn.host(), conn.port())
                .verify(10, TimeUnit.SECONDS).getSession();
        session.addPasswordIdentity(conn.password());
        if (!session.auth().verify(10, TimeUnit.SECONDS).isSuccess()) {
            session.close();
            throw new RuntimeException("SSH 认证失败: " + conn.username() + "@" + conn.host());
        }
        return session;
    }

    private record HostConnection(String host, int port, String username, String password) {
    }
}
```

- [ ] **Step 5: 创建 StandaloneHostController.java**

写入 `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/host/StandaloneHostController.java`：

```java
package com.gameplatform.plugin.l4d2.standalone.host;

import com.gameplatform.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 独立运行的主机管理控制器。
 * <p>
 * 提供 host CRUD REST API，让用户在 standalone 应用内管理主机。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Tag(name = "Standalone 主机管理", description = "独立运行模式的主机管理接口")
@RestController
@RequestMapping("/api/standalone/hosts")
@RequiredArgsConstructor
public class StandaloneHostController {

    private final StandaloneHostRepository hostRepository;

    @Operation(summary = "获取主机列表")
    @GetMapping
    public Result<List<StandaloneHostEntity>> list() {
        List<StandaloneHostEntity> hosts = hostRepository.findAll();
        hosts.forEach(h -> h.setPassword(null));
        return Result.success(hosts);
    }

    @Operation(summary = "获取主机详情")
    @GetMapping("/{id}")
    public Result<StandaloneHostEntity> get(@PathVariable Long id) {
        return hostRepository.findById(id)
                .map(h -> {
                    h.setPassword(null);
                    return Result.success(h);
                })
                .orElse(Result.fail("主机不存在"));
    }

    @Operation(summary = "添加主机")
    @PostMapping
    public Result<StandaloneHostEntity> create(@RequestBody StandaloneHostEntity entity) {
        StandaloneHostEntity saved = hostRepository.save(entity);
        saved.setPassword(null);
        return Result.success("主机添加成功", saved);
    }

    @Operation(summary = "更新主机")
    @PutMapping("/{id}")
    public Result<StandaloneHostEntity> update(@PathVariable Long id, @RequestBody StandaloneHostEntity entity) {
        entity.setId(id);
        StandaloneHostEntity updated = hostRepository.update(entity);
        updated.setPassword(null);
        return Result.success(updated);
    }

    @Operation(summary = "删除主机")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        if (!hostRepository.deleteById(id)) {
            return Result.fail("主机不存在");
        }
        return Result.success();
    }
}
```

- [ ] **Step 6: 验证编译**

```bash
cd backend
mvn clean compile -pl plugin-l4d2/plugin-l4d2-standalone -am
```

Expected: BUILD SUCCESS。

- [ ] **Step 7: 提交**

```bash
cd backend
git add plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/host/
git commit -m "feat(standalone): implement host module (Entity/Repository/Service/Controller)

- StandaloneHostEntity: host 表实体
- StandaloneHostRepository: JdbcTemplate CRUD + AES 密码加解密
- StandaloneHostQueryService: 实现 HostQueryService，SSH 获取资源信息
- StandaloneFileAccessService: 实现 FileAccessService，SFTP 文件操作
- StandaloneHostController: host CRUD REST API (/api/standalone/hosts)"
```

---

## Task 6: 实现 instance 模块

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/instance/StandaloneInstanceEntity.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/instance/StandaloneInstanceRepository.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/instance/StandaloneInstanceLifecycleService.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/instance/StandaloneInstanceQueryService.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/instance/StandaloneInstanceController.java`

- [ ] **Step 1: 创建 StandaloneInstanceEntity.java**

写入 `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/instance/StandaloneInstanceEntity.java`：

```java
package com.gameplatform.plugin.l4d2.standalone.instance;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 实例实体（对应 instance 表）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class StandaloneInstanceEntity {

    /** 实例ID */
    private Long id;

    /** 实例名称 */
    private String name;

    /** 主机ID */
    private Long hostId;

    /** 游戏编码 */
    private String gameCode;

    /** 安装路径 */
    private String installPath;

    /** 部署类型 */
    private String deployType;

    /** 运行状态：0-已停止 1-运行中 2-异常 */
    private Integer runStatus;

    /** 端口配置（JSON） */
    private String portConfig;

    /** 配置信息（JSON） */
    private String configInfo;

    /** 启动命令 */
    private String startCommand;

    /** 停止命令 */
    private String stopCommand;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: 创建 StandaloneInstanceRepository.java**

写入 `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/instance/StandaloneInstanceRepository.java`：

```java
package com.gameplatform.plugin.l4d2.standalone.instance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 实例数据访问层。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class StandaloneInstanceRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private final RowMapper<StandaloneInstanceEntity> rowMapper = (rs, rowNum) -> {
        StandaloneInstanceEntity entity = new StandaloneInstanceEntity();
        entity.setId(rs.getLong("id"));
        entity.setName(rs.getString("name"));
        entity.setHostId(rs.getLong("host_id"));
        entity.setGameCode(rs.getString("game_code"));
        entity.setInstallPath(rs.getString("install_path"));
        entity.setDeployType(rs.getString("deploy_type"));
        entity.setRunStatus(rs.getInt("run_status"));
        entity.setPortConfig(rs.getString("port_config"));
        entity.setConfigInfo(rs.getString("config_info"));
        entity.setStartCommand(rs.getString("start_command"));
        entity.setStopCommand(rs.getString("stop_command"));
        entity.setRemark(rs.getString("remark"));
        entity.setCreatedAt(rs.getTimestamp("created_at") != null
                ? rs.getTimestamp("created_at").toLocalDateTime() : null);
        entity.setUpdatedAt(rs.getTimestamp("updated_at") != null
                ? rs.getTimestamp("updated_at").toLocalDateTime() : null);
        return entity;
    };

    public Optional<StandaloneInstanceEntity> findById(Long id) {
        List<StandaloneInstanceEntity> list = jdbcTemplate.query(
                "SELECT * FROM instance WHERE id = ?", rowMapper, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<StandaloneInstanceEntity> findByHostId(Long hostId) {
        return jdbcTemplate.query("SELECT * FROM instance WHERE host_id = ? ORDER BY id", rowMapper, hostId);
    }

    public List<StandaloneInstanceEntity> findAll() {
        return jdbcTemplate.query("SELECT * FROM instance ORDER BY id", rowMapper);
    }

    public StandaloneInstanceEntity save(StandaloneInstanceEntity entity) {
        String sql = "INSERT INTO instance (name, host_id, game_code, install_path, deploy_type, run_status, "
                + "port_config, config_info, start_command, stop_command, remark) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql,
                entity.getName(),
                entity.getHostId(),
                entity.getGameCode() != null ? entity.getGameCode() : "l4d2",
                entity.getInstallPath(),
                entity.getDeployType() != null ? entity.getDeployType() : "native",
                entity.getRunStatus() != null ? entity.getRunStatus() : 0,
                entity.getPortConfig(),
                entity.getConfigInfo(),
                entity.getStartCommand(),
                entity.getStopCommand(),
                entity.getRemark());
        Long id = jdbcTemplate.queryForObject("SELECT last_insert_rowid()", Long.class);
        entity.setId(id);
        return entity;
    }

    public StandaloneInstanceEntity update(StandaloneInstanceEntity entity) {
        String sql = "UPDATE instance SET name=?, host_id=?, game_code=?, install_path=?, deploy_type=?, "
                + "run_status=?, port_config=?, config_info=?, start_command=?, stop_command=?, remark=?, "
                + "updated_at=CURRENT_TIMESTAMP WHERE id=?";
        jdbcTemplate.update(sql,
                entity.getName(),
                entity.getHostId(),
                entity.getGameCode(),
                entity.getInstallPath(),
                entity.getDeployType(),
                entity.getRunStatus(),
                entity.getPortConfig(),
                entity.getConfigInfo(),
                entity.getStartCommand(),
                entity.getStopCommand(),
                entity.getRemark(),
                entity.getId());
        return findById(entity.getId()).orElseThrow();
    }

    public boolean updateStatus(Long id, int status) {
        int affected = jdbcTemplate.update(
                "UPDATE instance SET run_status=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                status, id);
        return affected > 0;
    }

    public boolean deleteById(Long id) {
        int affected = jdbcTemplate.update("DELETE FROM instance WHERE id = ?", id);
        return affected > 0;
    }

    /**
     * 解析 portConfig JSON 为 Map。
     */
    public Map<String, Object> parsePortConfig(StandaloneInstanceEntity entity) {
        return parseJson(entity.getPortConfig());
    }

    /**
     * 解析 configInfo JSON 为 Map。
     */
    public Map<String, Object> parseConfigInfo(StandaloneInstanceEntity entity) {
        return parseJson(entity.getConfigInfo());
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("解析 JSON 失败: {} - {}", json, e.getMessage());
            return null;
        }
    }
}
```

- [ ] **Step 3: 创建 StandaloneInstanceLifecycleService.java**

写入 `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/instance/StandaloneInstanceLifecycleService.java`：

```java
package com.gameplatform.plugin.l4d2.standalone.instance;

import com.gameplatform.plugin.l4d2.standalone.host.StandaloneHostEntity;
import com.gameplatform.plugin.l4d2.standalone.host.StandaloneHostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 实例生命周期管理服务。
 * <p>
 * 通过 SSH 执行启动/停止/重启命令，通过 RCON 或日志文件读取状态。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StandaloneInstanceLifecycleService {

    private final StandaloneHostRepository hostRepository;
    private final StandaloneInstanceRepository instanceRepository;

    /**
     * 启动实例。
     */
    public boolean start(Long instanceId) {
        StandaloneInstanceEntity instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new RuntimeException("实例不存在: " + instanceId));
        if (instance.getStartCommand() == null || instance.getStartCommand().isEmpty()) {
            throw new RuntimeException("实例未配置启动命令");
        }
        boolean success = executeSshCommand(instance.getHostId(), instance.getStartCommand());
        if (success) {
            instanceRepository.updateStatus(instanceId, 1);
        }
        return success;
    }

    /**
     * 停止实例。
     */
    public boolean stop(Long instanceId) {
        StandaloneInstanceEntity instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new RuntimeException("实例不存在: " + instanceId));
        if (instance.getStopCommand() == null || instance.getStopCommand().isEmpty()) {
            throw new RuntimeException("实例未配置停止命令");
        }
        boolean success = executeSshCommand(instance.getHostId(), instance.getStopCommand());
        if (success) {
            instanceRepository.updateStatus(instanceId, 0);
        }
        return success;
    }

    /**
     * 重启实例。
     */
    public boolean restart(Long instanceId) {
        stop(instanceId);
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return start(instanceId);
    }

    /**
     * 获取实例日志。
     */
    public String getLogs(Long instanceId, int lines) {
        StandaloneInstanceEntity instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new RuntimeException("实例不存在: " + instanceId));
        String logPath = instance.getInstallPath() + "/logs/console.log";
        String command = "tail -n " + lines + " " + logPath;
        return executeSshCommandWithOutput(instance.getHostId(), command);
    }

    /**
     * 执行命令。
     */
    public String executeCommand(Long instanceId, String command) {
        StandaloneInstanceEntity instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new RuntimeException("实例不存在: " + instanceId));
        return executeSshCommandWithOutput(instance.getHostId(), command);
    }

    private boolean executeSshCommand(Long hostId, String command) {
        StandaloneHostEntity host = hostRepository.findById(hostId)
                .orElseThrow(() -> new RuntimeException("主机不存在: " + hostId));
        String password = hostRepository.decryptPassword(host.getPassword());

        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();
            try (ClientSession session = client.connect(host.getUsername(), host.getIp(), host.getSshPort())
                    .verify(10, TimeUnit.SECONDS).getSession()) {
                session.addPasswordIdentity(password);
                if (!session.auth().verify(10, TimeUnit.SECONDS).isSuccess()) {
                    throw new RuntimeException("SSH 认证失败");
                }
                ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                ByteArrayOutputStream stderr = new ByteArrayOutputStream();
                session.executeRemoteCommand(command, stdout, stderr, StandardCharsets.UTF_8);
                return true;
            }
        } catch (Exception e) {
            log.error("执行 SSH 命令失败: {} - {}", command, e.getMessage());
            return false;
        }
    }

    private String executeSshCommandWithOutput(Long hostId, String command) {
        StandaloneHostEntity host = hostRepository.findById(hostId)
                .orElseThrow(() -> new RuntimeException("主机不存在: " + hostId));
        String password = hostRepository.decryptPassword(host.getPassword());

        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();
            try (ClientSession session = client.connect(host.getUsername(), host.getIp(), host.getSshPort())
                    .verify(10, TimeUnit.SECONDS).getSession()) {
                session.addPasswordIdentity(password);
                if (!session.auth().verify(10, TimeUnit.SECONDS).isSuccess()) {
                    throw new RuntimeException("SSH 认证失败");
                }
                ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                ByteArrayOutputStream stderr = new ByteArrayOutputStream();
                session.executeRemoteCommand(command, stdout, stderr, StandardCharsets.UTF_8);
                return stdout.toString(StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.error("执行 SSH 命令失败: {} - {}", command, e.getMessage());
            throw new RuntimeException("执行命令失败: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 4: 创建 StandaloneInstanceQueryService.java**

写入 `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/instance/StandaloneInstanceQueryService.java`：

```java
package com.gameplatform.plugin.l4d2.standalone.instance;

import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 独立运行的实例查询服务实现。
 * <p>
 * 从 standalone 的 instance 表读取实例信息，委托给 LifecycleService 执行生命周期操作。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StandaloneInstanceQueryService implements InstanceQueryService {

    private final StandaloneInstanceRepository instanceRepository;
    private final StandaloneInstanceLifecycleService lifecycleService;

    @Override
    public InstanceVO getInstanceById(Long id) {
        return instanceRepository.findById(id)
                .map(this::toVO)
                .orElse(null);
    }

    @Override
    public List<InstanceVO> getInstancesByHostId(Long hostId) {
        return instanceRepository.findByHostId(hostId).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    @Override
    public List<InstanceVO> getInstancesByGameId(Long gameId) {
        // standalone 不区分游戏 ID，返回全部
        return instanceRepository.findAll().stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    @Override
    public InstanceVO getInstanceStatus(Long id) {
        return getInstanceById(id);
    }

    @Override
    public boolean startInstance(Long id) {
        return lifecycleService.start(id);
    }

    @Override
    public boolean stopInstance(Long id) {
        return lifecycleService.stop(id);
    }

    @Override
    public boolean restartInstance(Long id) {
        return lifecycleService.restart(id);
    }

    @Override
    public String getInstanceLogs(Long id, int lines) {
        return lifecycleService.getLogs(id, lines);
    }

    @Override
    public String executeCommand(Long id, String command) {
        return lifecycleService.executeCommand(id, command);
    }

    private InstanceVO toVO(StandaloneInstanceEntity entity) {
        InstanceVO vo = new InstanceVO();
        vo.setId(entity.getId());
        vo.setInstanceName(entity.getName());
        vo.setHostId(entity.getHostId());
        vo.setGameCode(entity.getGameCode());
        vo.setDeployType(entity.getDeployType());
        vo.setRunStatus(entity.getRunStatus());
        vo.setInstallPath(entity.getInstallPath());
        vo.setStartCommand(entity.getStartCommand());
        vo.setStopCommand(entity.getStopCommand());
        vo.setRemark(entity.getRemark());
        vo.setCreateTime(entity.getCreatedAt());
        vo.setUpdateTime(entity.getUpdatedAt());

        Map<String, Object> portConfig = instanceRepository.parsePortConfig(entity);
        vo.setPortConfig(portConfig);
        Map<String, Object> configInfo = instanceRepository.parseConfigInfo(entity);
        vo.setConfigInfo(configInfo);

        return vo;
    }
}
```

- [ ] **Step 5: 创建 StandaloneInstanceController.java**

写入 `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/instance/StandaloneInstanceController.java`：

```java
package com.gameplatform.plugin.l4d2.standalone.instance;

import com.gameplatform.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 独立运行的实例管理控制器。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Tag(name = "Standalone 实例管理", description = "独立运行模式的实例管理接口")
@RestController
@RequestMapping("/api/standalone/instances")
@RequiredArgsConstructor
public class StandaloneInstanceController {

    private final StandaloneInstanceRepository instanceRepository;
    private final StandaloneInstanceLifecycleService lifecycleService;

    @Operation(summary = "获取实例列表")
    @GetMapping
    public Result<List<StandaloneInstanceEntity>> list() {
        return Result.success(instanceRepository.findAll());
    }

    @Operation(summary = "获取实例详情")
    @GetMapping("/{id}")
    public Result<StandaloneInstanceEntity> get(@PathVariable Long id) {
        return instanceRepository.findById(id)
                .map(Result::success)
                .orElse(Result.fail("实例不存在"));
    }

    @Operation(summary = "添加实例")
    @PostMapping
    public Result<StandaloneInstanceEntity> create(@RequestBody StandaloneInstanceEntity entity) {
        return Result.success("实例添加成功", instanceRepository.save(entity));
    }

    @Operation(summary = "更新实例")
    @PutMapping("/{id}")
    public Result<StandaloneInstanceEntity> update(@PathVariable Long id, @RequestBody StandaloneInstanceEntity entity) {
        entity.setId(id);
        return Result.success(instanceRepository.update(entity));
    }

    @Operation(summary = "删除实例")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        if (!instanceRepository.deleteById(id)) {
            return Result.fail("实例不存在");
        }
        return Result.success();
    }

    @Operation(summary = "启动实例")
    @PostMapping("/{id}/start")
    public Result<Void> start(@PathVariable Long id) {
        return lifecycleService.start(id) ? Result.success("实例已启动", null) : Result.fail("启动失败");
    }

    @Operation(summary = "停止实例")
    @PostMapping("/{id}/stop")
    public Result<Void> stop(@PathVariable Long id) {
        return lifecycleService.stop(id) ? Result.success("实例已停止", null) : Result.fail("停止失败");
    }

    @Operation(summary = "重启实例")
    @PostMapping("/{id}/restart")
    public Result<Void> restart(@PathVariable Long id) {
        return lifecycleService.restart(id) ? Result.success("实例已重启", null) : Result.fail("重启失败");
    }

    @Operation(summary = "获取实例日志")
    @GetMapping("/{id}/logs")
    public Result<String> getLogs(@PathVariable Long id, @RequestParam(defaultValue = "100") int lines) {
        return Result.success(lifecycleService.getLogs(id, lines));
    }
}
```

- [ ] **Step 6: 验证编译**

```bash
cd backend
mvn clean compile -pl plugin-l4d2/plugin-l4d2-standalone -am
```

Expected: BUILD SUCCESS。

- [ ] **Step 7: 提交**

```bash
cd backend
git add plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/instance/
git commit -m "feat(standalone): implement instance module (Entity/Repository/Service/Controller)

- StandaloneInstanceEntity: instance 表实体
- StandaloneInstanceRepository: JdbcTemplate CRUD + JSON 解析
- StandaloneInstanceLifecycleService: SSH 执行 start/stop/restart/logs
- StandaloneInstanceQueryService: 实现 InstanceQueryService，委托给 LifecycleService
- StandaloneInstanceController: instance CRUD + 生命周期控制 REST API"
```

---

## Task 7: 实现 config 模块（DataSource/ServiceConfig/ExceptionHandler）

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/config/StandaloneDataSourceConfig.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/config/StandaloneServiceConfig.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/config/StandaloneExceptionHandler.java`

- [ ] **Step 1: 创建 StandaloneDataSourceConfig.java**

写入 `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/config/StandaloneDataSourceConfig.java`：

```java
package com.gameplatform.plugin.l4d2.standalone.config;

import com.gameplatform.api.extension.AbstractExtension;
import com.gameplatform.plugin.l4d2.standalone.ext.StandaloneDdlTemplate;
import com.gameplatform.plugin.extension.ExtensionModel;
import com.gameplatform.plugin.extension.Strategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reflections.Reflections;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.annotation.PostConstruct;
import java.util.Set;

/**
 * 独立运行数据源配置。
 * <p>
 * 启动时扫描 com.gameplatform.plugin.l4d2.extension 包下所有 @ExtensionModel 注解的类，
 * 为 MODEL_ISOLATED 策略的类创建扩展表。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class StandaloneDataSourceConfig {

    private static final String PLUGIN_ID = "plugin-l4d2";
    private static final String EXTENSION_PACKAGE = "com.gameplatform.plugin.l4d2.extension";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 启动时创建扩展表。
     * <p>
     * schema.sql 已执行 host/instance 表，这里补充扩展表。
     */
    @PostConstruct
    public void createExtensionTables() {
        Reflections reflections = new Reflections(EXTENSION_PACKAGE);
        Set<Class<? extends AbstractExtension>> modelClasses =
                reflections.getSubTypesOf(AbstractExtension.class);

        for (Class<? extends AbstractExtension> modelClass : modelClasses) {
            ExtensionModel meta = modelClass.getAnnotation(ExtensionModel.class);
            if (meta == null) {
                continue;
            }
            if (meta.strategy() != Strategy.MODEL_ISOLATED) {
                continue;
            }
            String kind = meta.kind().isEmpty() ? modelClass.getSimpleName() : meta.kind();
            String tableName = "ext_" + StandaloneDdlTemplate.sanitize(PLUGIN_ID)
                    + "_" + StandaloneDdlTemplate.sanitize(kind);
            String ddl = StandaloneDdlTemplate.generate(tableName);
            try {
                // SQLite JDBC 不支持一次执行多条语句，需要分割
                String[] statements = ddl.split(";");
                for (String stmt : statements) {
                    String trimmed = stmt.trim();
                    if (!trimmed.isEmpty()) {
                        jdbcTemplate.execute(trimmed);
                    }
                }
                log.info("创建扩展表: {}", tableName);
            } catch (Exception e) {
                log.error("创建扩展表失败: {} - {}", tableName, e.getMessage());
            }
        }
    }
}
```

- [ ] **Step 2: 添加 reflections 依赖到 standalone pom.xml**

在 `backend/plugin-l4d2/plugin-l4d2-standalone/pom.xml` 的 `<dependencies>` 中添加：

```xml
        <!-- Reflections（扫描 @ExtensionModel 类）-->
        <dependency>
            <groupId>org.reflections</groupId>
            <artifactId>reflections</artifactId>
            <version>0.10.2</version>
        </dependency>
```

- [ ] **Step 3: 创建 StandaloneServiceConfig.java**

写入 `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/config/StandaloneServiceConfig.java`：

```java
package com.gameplatform.plugin.l4d2.standalone.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.plugin.l4d2.standalone.ext.StandaloneExtensionClient;
import com.gameplatform.plugin.extension.ExtensionClient;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 独立运行服务配置。
 * <p>
 * 注册 StandaloneExtensionClient 为 ExtensionClient 接口的实现 Bean。
 * 其他 Service（HostQueryService/FileAccessService/InstanceQueryService）用 @Service 自动注册。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Configuration
@RequiredArgsConstructor
public class StandaloneServiceConfig {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 注册 StandaloneExtensionClient 为 ExtensionClient 实现。
     */
    @Bean
    public ExtensionClient extensionClient() {
        return new StandaloneExtensionClient(jdbcTemplate, objectMapper);
    }
}
```

- [ ] **Step 4: 创建 StandaloneExceptionHandler.java**

写入 `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/config/StandaloneExceptionHandler.java`：

```java
package com.gameplatform.plugin.l4d2.standalone.config;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.extension.exception.DuplicateExtensionException;
import com.gameplatform.plugin.extension.exception.ExtensionNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@RestControllerAdvice
public class StandaloneExceptionHandler {

    @ExceptionHandler(ExtensionNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNotFound(ExtensionNotFoundException e) {
        log.warn("资源不存在: {}", e.getMessage());
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(DuplicateExtensionException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleDuplicate(DuplicateExtensionException e) {
        log.warn("资源已存在: {}", e.getMessage());
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        return Result.fail(message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleRuntime(RuntimeException e) {
        log.error("服务器错误: {}", e.getMessage(), e);
        return Result.fail("服务器错误: " + e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("未捕获异常: {}", e.getMessage(), e);
        return Result.fail("服务器内部错误");
    }
}
```

- [ ] **Step 5: 验证编译**

```bash
cd backend
mvn clean compile -pl plugin-l4d2/plugin-l4d2-standalone -am
```

Expected: BUILD SUCCESS。

- [ ] **Step 6: 提交**

```bash
cd backend
git add plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/config/
git add plugin-l4d2/plugin-l4d2-standalone/pom.xml
git commit -m "feat(standalone): implement config module (DataSource/ServiceConfig/ExceptionHandler)

- StandaloneDataSourceConfig: 启动时扫描 @ExtensionModel 创建扩展表
- StandaloneServiceConfig: 注册 StandaloneExtensionClient 为 ExtensionClient Bean
- StandaloneExceptionHandler: 全局异常处理（404/400/500）
- pom.xml: 添加 reflections 依赖（扫描扩展模型类）"
```

---

## Task 8: 编写测试

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/test/java/com/gameplatform/plugin/l4d2/standalone/ext/StandaloneExtensionClientTest.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/test/java/com/gameplatform/plugin/l4d2/standalone/host/StandaloneHostRepositoryTest.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/test/java/com/gameplatform/plugin/l4d2/standalone/instance/StandaloneInstanceRepositoryTest.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-standalone/src/test/resources/application-test.yml`

- [ ] **Step 1: 创建 application-test.yml**

写入 `backend/plugin-l4d2/plugin-l4d2-standalone/src/test/resources/application-test.yml`：

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  sql:
    init:
      mode: always
      schema-locations: classpath:db/schema-test.sql

l4d2:
  standalone:
    ssh-timeout: 5000
    default-rcon-port: 27015
    max-log-lines: 100

plugin:
  l4d2:
    rcon-timeout: 3000
    rcon-retry-count: 1
    rcon-retry-interval: 100
```

- [ ] **Step 2: 创建 schema-test.sql**

写入 `backend/plugin-l4d2/plugin-l4d2-standalone/src/test/resources/db/schema-test.sql`：

```sql
-- H2 兼容的 host/instance 表
CREATE TABLE IF NOT EXISTS host (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    ip VARCHAR(45) NOT NULL,
    ssh_port INTEGER NOT NULL DEFAULT 22,
    username VARCHAR(50) NOT NULL,
    password VARCHAR(255) NOT NULL,
    status VARCHAR(20) DEFAULT 'offline',
    tags VARCHAR(500),
    os_type VARCHAR(50),
    remark VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS instance (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    host_id BIGINT NOT NULL,
    game_code VARCHAR(20) NOT NULL DEFAULT 'l4d2',
    install_path VARCHAR(500) NOT NULL,
    deploy_type VARCHAR(20) DEFAULT 'native',
    run_status INTEGER DEFAULT 0,
    port_config CLOB,
    config_info CLOB,
    start_command VARCHAR(1000),
    stop_command VARCHAR(1000),
    remark VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_instance_host FOREIGN KEY (host_id) REFERENCES host(id),
    CONSTRAINT uk_instance_host_name UNIQUE(host_id, name)
);
```

- [ ] **Step 3: 创建 StandaloneExtensionClientTest.java**

写入 `backend/plugin-l4d2/plugin-l4d2-standalone/src/test/java/com/gameplatform/plugin/l4d2/standalone/ext/StandaloneExtensionClientTest.java`：

```java
package com.gameplatform.plugin.l4d2.standalone.ext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.extension.ListOptions;
import com.gameplatform.plugin.extension.exception.DuplicateExtensionException;
import com.gameplatform.plugin.extension.exception.ExtensionNotFoundException;
import com.gameplatform.plugin.l4d2.extension.AdminResource;
import com.gameplatform.plugin.l4d2.extension.AdminSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StandaloneExtensionClient 单元测试。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
class StandaloneExtensionClientTest {

    private JdbcTemplate jdbcTemplate;
    private ExtensionClient extensionClient;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:h2:mem:test-ext;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbcTemplate = new JdbcTemplate(dataSource);

        // 创建扩展表
        String ddl = StandaloneDdlTemplate.generate("ext_plugin_l4d2_adminresource");
        for (String stmt : ddl.split(";")) {
            String trimmed = stmt.trim();
            if (!trimmed.isEmpty()) {
                jdbcTemplate.execute(trimmed);
            }
        }

        extensionClient = new StandaloneExtensionClient(jdbcTemplate, new ObjectMapper());
    }

    @Test
    void createAndGet_roundTrip_success() {
        AdminResource resource = new AdminResource();
        resource.setName("1-STEAM_0:1:12345");
        AdminSpec spec = new AdminSpec();
        spec.setInstanceId(1L);
        spec.setSteamId("STEAM_0:1:12345");
        spec.setAdminFlags("z");
        spec.setIsActive(true);
        resource.setSpec(spec);

        extensionClient.create(resource);
        assertNotNull(resource.getId());
        assertEquals("plugin-l4d2", resource.getGroupName());
        assertEquals("AdminResource", resource.getKind());

        Optional<AdminResource> found = extensionClient.get(AdminResource.class, "1-STEAM_0:1:12345");
        assertTrue(found.isPresent());
        assertEquals("STEAM_0:1:12345", found.get().getSpec().getSteamId());
    }

    @Test
    void create_duplicateName_throwsException() {
        AdminResource resource = createAdmin("1-STEAM_0:1:99999");
        extensionClient.create(resource);

        AdminResource duplicate = createAdmin("1-STEAM_0:1:99999");
        assertThrows(DuplicateExtensionException.class, () -> extensionClient.create(duplicate));
    }

    @Test
    void delete_existingResource_success() {
        AdminResource resource = createAdmin("1-STEAM_0:1:88888");
        extensionClient.create(resource);

        extensionClient.delete(AdminResource.class, "1-STEAM_0:1:88888");

        Optional<AdminResource> found = extensionClient.get(AdminResource.class, "1-STEAM_0:1:88888");
        assertFalse(found.isPresent());
    }

    @Test
    void delete_nonExisting_throwsException() {
        assertThrows(ExtensionNotFoundException.class,
                () -> extensionClient.delete(AdminResource.class, "non-existing"));
    }

    @Test
    void getById_returnsResource() {
        AdminResource resource = createAdmin("1-STEAM_0:1:77777");
        extensionClient.create(resource);

        Optional<AdminResource> found = extensionClient.getById(AdminResource.class, resource.getId());
        assertTrue(found.isPresent());
        assertEquals(resource.getName(), found.get().getName());
    }

    @Test
    void deleteById_removesResource() {
        AdminResource resource = createAdmin("1-STEAM_0:1:66666");
        extensionClient.create(resource);

        extensionClient.deleteById(AdminResource.class, resource.getId());

        Optional<AdminResource> found = extensionClient.getById(AdminResource.class, resource.getId());
        assertFalse(found.isPresent());
    }

    @Test
    void list_withSpecFilter_returnsMatching() {
        extensionClient.create(createAdmin("1-STEAM_0:1:111"));
        extensionClient.create(createAdmin("1-STEAM_0:1:222"));
        extensionClient.create(createAdmin("2-STEAM_0:1:333"));

        ListOptions opts = ListOptions.builder()
                .specFilter("$.instanceId", "=", 1L)
                .build();

        List<AdminResource> result = extensionClient.list(AdminResource.class, opts);
        assertEquals(2, result.size());
    }

    @Test
    void update_modifiesSpec() {
        AdminResource resource = createAdmin("1-STEAM_0:1:555");
        extensionClient.create(resource);

        resource.getSpec().setAdminFlags("abc");
        extensionClient.update(resource);

        Optional<AdminResource> found = extensionClient.get(AdminResource.class, "1-STEAM_0:1:555");
        assertTrue(found.isPresent());
        assertEquals("abc", found.get().getSpec().getAdminFlags());
        assertEquals(2, found.get().getVersion());
    }

    private AdminResource createAdmin(String name) {
        AdminResource resource = new AdminResource();
        resource.setName(name);
        AdminSpec spec = new AdminSpec();
        spec.setInstanceId(Long.parseLong(name.split("-")[0]));
        spec.setSteamId(name.split("-")[1]);
        spec.setAdminFlags("z");
        spec.setIsActive(true);
        resource.setSpec(spec);
        return resource;
    }
}
```

- [ ] **Step 4: 创建 StandaloneHostRepositoryTest.java**

写入 `backend/plugin-l4d2/plugin-l4d2-standalone/src/test/java/com/gameplatform/plugin/l4d2/standalone/host/StandaloneHostRepositoryTest.java`：

```java
package com.gameplatform.plugin.l4d2.standalone.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StandaloneHostRepository 单元测试。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
class StandaloneHostRepositoryTest {

    private StandaloneHostRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:h2:mem:test-host;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS host ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "name VARCHAR(100) NOT NULL UNIQUE, "
                + "ip VARCHAR(45) NOT NULL, "
                + "ssh_port INTEGER NOT NULL DEFAULT 22, "
                + "username VARCHAR(50) NOT NULL, "
                + "password VARCHAR(255) NOT NULL, "
                + "status VARCHAR(20) DEFAULT 'offline', "
                + "tags VARCHAR(500), "
                + "os_type VARCHAR(50), "
                + "remark VARCHAR(500), "
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

        repository = new StandaloneHostRepository(jdbcTemplate);
    }

    @Test
    void save_andFindById_success() {
        StandaloneHostEntity host = new StandaloneHostEntity();
        host.setName("test-host");
        host.setIp("192.168.1.100");
        host.setSshPort(22);
        host.setUsername("root");
        host.setPassword("secret");

        StandaloneHostEntity saved = repository.save(host);
        assertNotNull(saved.getId());

        Optional<StandaloneHostEntity> found = repository.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("test-host", found.get().getName());
        assertNotEquals("secret", found.get().getPassword());
    }

    @Test
    void decryptPassword_returnsOriginal() {
        StandaloneHostEntity host = new StandaloneHostEntity();
        host.setName("decrypt-test");
        host.setIp("10.0.0.1");
        host.setSshPort(22);
        host.setUsername("admin");
        host.setPassword("mypassword");

        StandaloneHostEntity saved = repository.save(host);

        String decrypted = repository.decryptPassword(saved.getPassword());
        assertEquals("mypassword", decrypted);
    }

    @Test
    void findAll_returnsAll() {
        saveHost("host1", "10.0.0.1");
        saveHost("host2", "10.0.0.2");

        List<StandaloneHostEntity> all = repository.findAll();
        assertEquals(2, all.size());
    }

    @Test
    void deleteById_removesHost() {
        StandaloneHostEntity saved = saveHost("to-delete", "10.0.0.3");
        assertTrue(repository.deleteById(saved.getId()));
        assertFalse(repository.findById(saved.getId()).isPresent());
    }

    private StandaloneHostEntity saveHost(String name, String ip) {
        StandaloneHostEntity host = new StandaloneHostEntity();
        host.setName(name);
        host.setIp(ip);
        host.setSshPort(22);
        host.setUsername("root");
        host.setPassword("pass");
        return repository.save(host);
    }
}
```

- [ ] **Step 5: 创建 StandaloneInstanceRepositoryTest.java**

写入 `backend/plugin-l4d2/plugin-l4d2-standalone/src/test/java/com/gameplatform/plugin/l4d2/standalone/instance/StandaloneInstanceRepositoryTest.java`：

```java
package com.gameplatform.plugin.l4d2.standalone.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StandaloneInstanceRepository 单元测试。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
class StandaloneInstanceRepositoryTest {

    private StandaloneInstanceRepository repository;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:h2:mem:test-instance;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbcTemplate = new JdbcTemplate(dataSource);

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS host ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "name VARCHAR(100) NOT NULL UNIQUE, "
                + "ip VARCHAR(45) NOT NULL, ssh_port INTEGER, username VARCHAR(50), "
                + "password VARCHAR(255), status VARCHAR(20), tags VARCHAR(500), "
                + "os_type VARCHAR(50), remark VARCHAR(500), "
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS instance ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "name VARCHAR(100) NOT NULL, "
                + "host_id BIGINT NOT NULL, "
                + "game_code VARCHAR(20) DEFAULT 'l4d2', "
                + "install_path VARCHAR(500) NOT NULL, "
                + "deploy_type VARCHAR(20) DEFAULT 'native', "
                + "run_status INTEGER DEFAULT 0, "
                + "port_config CLOB, config_info CLOB, "
                + "start_command VARCHAR(1000), stop_command VARCHAR(1000), "
                + "remark VARCHAR(500), "
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                + "CONSTRAINT uk_inst_host_name UNIQUE(host_id, name))");

        repository = new StandaloneInstanceRepository(jdbcTemplate, new ObjectMapper());
    }

    @Test
    void save_andFindById_success() {
        Long hostId = createHost();

        StandaloneInstanceEntity instance = new StandaloneInstanceEntity();
        instance.setName("test-instance");
        instance.setHostId(hostId);
        instance.setInstallPath("/home/l4d2/server");
        instance.setConfigInfo("{\"rconPort\":27015,\"rconPassword\":\"test\"}");

        StandaloneInstanceEntity saved = repository.save(instance);
        assertNotNull(saved.getId());

        Optional<StandaloneInstanceEntity> found = repository.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("test-instance", found.get().getName());
    }

    @Test
    void findByHostId_returnsInstances() {
        Long hostId = createHost();
        saveInstance(hostId, "inst1");
        saveInstance(hostId, "inst2");

        List<StandaloneInstanceEntity> list = repository.findByHostId(hostId);
        assertEquals(2, list.size());
    }

    @Test
    void updateStatus_changesStatus() {
        Long hostId = createHost();
        StandaloneInstanceEntity saved = saveInstance(hostId, "status-test");

        boolean updated = repository.updateStatus(saved.getId(), 1);
        assertTrue(updated);

        Optional<StandaloneInstanceEntity> found = repository.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals(1, found.get().getRunStatus());
    }

    @Test
    void parseConfigInfo_returnsMap() {
        Long hostId = createHost();
        StandaloneInstanceEntity instance = new StandaloneInstanceEntity();
        instance.setName("config-test");
        instance.setHostId(hostId);
        instance.setInstallPath("/test");
        instance.setConfigInfo("{\"rconPort\":27015,\"rconPassword\":\"secret\"}");
        StandaloneInstanceEntity saved = repository.save(instance);

        var config = repository.parseConfigInfo(saved);
        assertNotNull(config);
        assertEquals(27015, ((Number) config.get("rconPort")).intValue());
        assertEquals("secret", config.get("rconPassword"));
    }

    private Long createHost() {
        jdbcTemplate.update("INSERT INTO host (name, ip, ssh_port, username, password) VALUES (?, ?, ?, ?, ?)",
                "test-host", "127.0.0.1", 22, "root", "pass");
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM host", Long.class);
    }

    private StandaloneInstanceEntity saveInstance(Long hostId, String name) {
        StandaloneInstanceEntity instance = new StandaloneInstanceEntity();
        instance.setName(name);
        instance.setHostId(hostId);
        instance.setInstallPath("/home/l4d2/" + name);
        return repository.save(instance);
    }
}
```

- [ ] **Step 6: 运行 standalone 测试**

```bash
cd backend
mvn test -pl plugin-l4d2/plugin-l4d2-standalone -am
```

Expected: 所有测试通过（约 15 个测试）。

- [ ] **Step 7: 提交**

```bash
cd backend
git add plugin-l4d2/plugin-l4d2-standalone/src/test/
git commit -m "test(standalone): add unit tests for ExtensionClient/HostRepository/InstanceRepository

- StandaloneExtensionClientTest: create/get/list/update/delete/by-id + 冲突 + 过滤
- StandaloneHostRepositoryTest: save/findById/findAll/deleteById + 密码加解密
- StandaloneInstanceRepositoryTest: save/findById/findByHostId/updateStatus + JSON 解析
- application-test.yml + schema-test.sql: H2 内存库测试配置"
```

---

## Task 9: 全量验证

**Files:**
- 无新增，仅验证

- [ ] **Step 1: 全量编译验证**

```bash
cd backend
mvn clean compile -pl plugin-l4d2 -am
```

Expected: BUILD SUCCESS（aggregator + 两个子模块全部编译通过）。

- [ ] **Step 2: 全量测试验证**

```bash
cd backend
mvn test -pl plugin-l4d2 -am
```

Expected: 所有测试通过。

- [ ] **Step 3: 主应用回归验证**

```bash
cd backend
mvn test -pl core -am
```

Expected: 361 个测试全通过（主应用不受影响）。

- [ ] **Step 4: 打包 plugin-l4d2-core 验证 PF4J 插件 JAR**

```bash
cd backend
mvn clean package -pl plugin-l4d2/plugin-l4d2-core -am -DskipTests
```

验证生成的 JAR：

```bash
jar tf plugin-l4d2/plugin-l4d2-core/target/plugin-l4d2-core-1.0.0.jar | findstr "L4D2Plugin"
unzip -p plugin-l4d2/plugin-l4d2-core/target/plugin-l4d2-core-1.0.0.jar META-INF/MANIFEST.MF
```

Expected: 含 `Plugin-Id: plugin-l4d2`、`Plugin-Class: com.gameplatform.plugin.l4d2.L4D2Plugin`。

- [ ] **Step 5: 打包 plugin-l4d2-standalone 验证 fat JAR**

```bash
cd backend
mvn clean package -pl plugin-l4d2/plugin-l4d2-standalone -am -DskipTests
```

Expected: 生成 `plugin-l4d2-standalone-1.0.0.jar`（fat JAR，约 50MB+）。

验证 fat JAR 含主类：

```bash
jar tf plugin-l4d2/plugin-l4d2-standalone/target/plugin-l4d2-standalone-1.0.0.jar | findstr "L4D2StandaloneApp"
```

Expected: 含 `com/gameplatform/plugin/l4d2/standalone/L4D2StandaloneApp.class`。

- [ ] **Step 6: 启动 standalone 应用验证**

```bash
java -jar backend/plugin-l4d2/plugin-l4d2-standalone/target/plugin-l4d2-standalone-1.0.0.jar
```

在另一个终端验证：

```bash
# 验证端口监听
curl http://localhost:8081/api/standalone/hosts

# 验证添加主机
curl -X POST http://localhost:8081/api/standalone/hosts \
  -H "Content-Type: application/json" \
  -d '{"name":"test","ip":"127.0.0.1","sshPort":22,"username":"root","password":"test"}'

# 验证获取主机列表
curl http://localhost:8081/api/standalone/hosts

# 停止应用（Ctrl+C）
```

Expected: 应用启动成功，监听 8081，host 表自动创建，API 可访问。

- [ ] **Step 7: 验证扩展表自动创建**

启动后查看日志，确认 4 个扩展表创建：

```
创建扩展表: ext_plugin_l4d2_adminresource
创建扩展表: ext_plugin_l4d2_downloadtaskresource
创建扩展表: ext_plugin_l4d2_pluginconfigresource
创建扩展表: ext_plugin_l4d2_systemmetricresource
```

- [ ] **Step 8: 最终提交（如果有未提交的变更）**

```bash
cd backend
git status
# 如果有未提交的变更
git add -A
git commit -m "chore(standalone): final verification complete"
```

- [ ] **Step 9: 提交根仓库子模块引用更新**

```bash
cd d:\program\ai\game_platform_manger
git add backend
git commit -m "refactor(plugin-l4d2): split into aggregator + core + standalone sub-modules

将 plugin-l4d2 单模块改造为双模式打包：
- plugin-l4d2-core: PF4J 插件 JAR，被主应用加载（行为与改造前完全一致）
- plugin-l4d2-standalone: Spring Boot fat JAR，独立运行
  - 内部独立实现 ExtensionClient/HostQueryService/FileAccessService/InstanceQueryService
  - 不依赖 game-platform-core
  - 自带 SQLite + host/instance 管理 API
  - 启动时自动创建扩展表

全部测试通过，主应用 361 个测试无回归。"
```

---

## 验收清单

执行完所有 Task 后，逐项确认：

- [ ] `mvn package -pl plugin-l4d2/plugin-l4d2-core` 生成含 PF4J Manifest 的插件 JAR
- [ ] `mvn package -pl plugin-l4d2/plugin-l4d2-standalone` 生成可独立启动的 fat JAR
- [ ] `java -jar plugin-l4d2-standalone-1.0.0.jar` 启动成功，监听 8081
- [ ] 启动后 4 个扩展表自动创建（adminresource/downloadtaskresource/pluginconfigresource/systemmetricresource）
- [ ] `POST /api/standalone/hosts` 可添加主机
- [ ] `POST /api/standalone/instances` 可添加实例
- [ ] `POST /api/plugin/l4d2/admins/add` 可添加管理员（依赖前置 host+instance）
- [ ] plugin-l4d2-standalone 单元测试全部通过（约 15 个）
- [ ] 主应用 `mvn test` 361 个测试全通过

---

## 自审记录

### Spec 覆盖检查

| Spec 章节 | 对应 Task | 状态 |
|-----------|----------|------|
| 2. 整体架构与模块结构 | Task 1, 2 | ✓ |
| 3. plugin-l4d2-core 模块迁移 | Task 1 | ✓ |
| 4. plugin-l4d2-standalone 模块 | Task 2-7 | ✓ |
| 4.4 StandaloneExtensionClient | Task 4 | ✓ |
| 4.5 宿主服务独立实现 | Task 5, 6 | ✓ |
| 4.6 host/instance 管理 Controller | Task 5, 6 | ✓ |
| 4.7 application.yml | Task 2 | ✓ |
| 4.8 db/schema.sql | Task 2 | ✓ |
| 4.9 StandaloneServiceConfig | Task 7 | ✓ |
| 4.10 pom.xml | Task 2 | ✓ |
| 5.1 启动数据流 | Task 7 (StandaloneDataSourceConfig) | ✓ |
| 5.3 错误处理 | Task 7 (StandaloneExceptionHandler) | ✓ |
| 5.4 测试策略 | Task 8 | ✓ |
| 5.5 验收标准 | Task 9 | ✓ |

### 类型一致性检查

- `StandaloneExtensionClient` 实现的 `ExtensionClient` 接口方法签名与 plugin 模块定义一致 ✓
- `StandaloneHostQueryService` 实现 `HostQueryService` 接口方法签名一致 ✓
- `StandaloneFileAccessService` 实现 `FileAccessService` 接口的 13 个方法一致 ✓
- `StandaloneInstanceQueryService` 实现 `InstanceQueryService` 接口的 9 个方法一致 ✓
- 表名规则与 core `ExtensionRouter.sanitize` 一致 ✓
- DDL 与 core `DdlTemplate.generate` 一致 ✓
- AES 密钥与 core `AesUtil.DEFAULT_SECRET_KEY` 一致 ✓

无遗漏，计划完整可执行。

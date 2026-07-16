# Docker Compose 部署框架实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建通用 docker-compose 部署框架，支持任意游戏通过 yml 配置接入，使用模板驱动方案（compose 模板保留 `${VAR:default}` 原生语法，后端生成 .env 文件），部署后获取命名卷宿主路径存入 game_instance.runtime_metadata。L4D2 作为首个接入游戏。

**Architecture:** yml 中定义 `dockerCompose.composeTemplate`（compose 原文）+ `variables`（变量元信息）+ `namedVolumes`（命名卷列表）。启动时 GameMetadataScanner 解析存入 `game_metadata.deploy_config["docker-compose"]`。前端根据 variables 渲染动态表单。部署时 DockerComposeAdapter 生成 `.env` 文件并上传，执行 `docker compose up -d`，然后 `docker volume inspect` 获取卷宿主路径，组装 runtimeMetadata 存入 `game_instance.runtime_metadata`。

**Tech Stack:** Java 17 + Spring Boot 3.2.5 + MyBatis-Plus + SnakeYAML + Apache MINA SSHD；Vue 3 + Element Plus + Pinia。

**参考设计文档:** `docs/superpowers/specs/2026-07-16-docker-compose-deploy-framework-design.md`

---

## 文件结构

### 后端新建文件
- `backend/core/src/main/java/com/gameplatform/config/SchemaMigrationRunner.java` — 启动时自动迁移数据库 schema（幂等 ALTER TABLE）
- `backend/core/src/main/java/com/gameplatform/vo/DeployConfigVO.java` — 部署配置响应 VO

### 后端修改文件
- `backend/core/src/main/resources/db/schema.sql` — game_instance 表新增 runtime_metadata 列
- `backend/core/src/main/java/com/gameplatform/entity/GameInstance.java` — 新增 runtimeMetadata 字段
- `backend/core/src/main/java/com/gameplatform/config/GameYamlConfig.java` — 新增 DockerComposeConfig、VariableDefinition 内部类
- `backend/core/src/main/java/com/gameplatform/service/GameMetadataScanner.java` — buildDeployConfig() 新增 docker-compose 处理
- `backend/core/src/main/resources/games/l4d2.yml` — 新增 dockerCompose 配置块
- `backend/core/src/main/java/com/gameplatform/adapter/DockerComposeAdapter.java` — preDeploy/deploy 增强
- `backend/core/src/main/java/com/gameplatform/service/impl/InstanceServiceImpl.java` — buildDeployConfig() 注入 runtimeMetadata
- `backend/core/src/main/java/com/gameplatform/controller/GameMetadataController.java` — 新增 deploy-config 端点

### 前端新建文件
- `frontend/src/components/DeployVariableForm.vue` — 动态变量表单组件

### 前端修改文件
- `frontend/src/api/game.js` — 新增 getDeployConfig 方法
- `frontend/src/views/instance/deploy.vue` — 集成 DeployVariableForm
- `frontend/src/views/instance/detail.vue` — 新增运行时元数据展示卡片

---

## Task 1: 数据层 - 新增 runtime_metadata 字段

**Files:**
- Modify: `backend/core/src/main/resources/db/schema.sql` (game_instance 表)
- Modify: `backend/core/src/main/java/com/gameplatform/entity/GameInstance.java`
- Create: `backend/core/src/main/java/com/gameplatform/config/SchemaMigrationRunner.java`

- [ ] **Step 1: 修改 schema.sql，在 game_instance 表新增 runtime_metadata 列**

在 `backend/core/src/main/resources/db/schema.sql` 的 `game_instance` 表 CREATE 语句中，在 `last_backup_time DATETIME,` 行之后新增：

```sql
    runtime_metadata TEXT,                   -- JSON对象，存储运行时元数据
```

完整修改后的片段（参考）：
```sql
CREATE TABLE IF NOT EXISTS game_instance (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    instance_name   VARCHAR(100) NOT NULL UNIQUE,
    host_id         INTEGER NOT NULL,
    game_id         INTEGER NOT NULL,
    deploy_type     VARCHAR(20) NOT NULL,
    port_config     TEXT,
    run_status      INTEGER DEFAULT 0,
    online_players  INTEGER DEFAULT 0,
    config_info     TEXT,
    install_path    VARCHAR(500),
    start_command   TEXT,
    stop_command    TEXT,
    database_config TEXT,
    save_path       VARCHAR(500),
    config_path     VARCHAR(500),
    last_backup_time DATETIME,
    runtime_metadata TEXT,                   -- JSON对象，存储运行时元数据
    create_time     DATETIME DEFAULT (datetime('now', 'localtime')),
    update_time     DATETIME DEFAULT (datetime('now', 'localtime')),
    is_deleted      INTEGER DEFAULT 0,
    remark          TEXT,
    FOREIGN KEY (host_id) REFERENCES host_info(id),
    FOREIGN KEY (game_id) REFERENCES game_metadata(id)
);
```

- [ ] **Step 2: 修改 GameInstance.java，新增 runtimeMetadata 字段**

在 `backend/core/src/main/java/com/gameplatform/entity/GameInstance.java` 中，在 `private LocalDateTime lastBackupTime;` 字段之后新增：

```java
    /**
     * 运行时元数据(JSON格式)
     * 存储部署后产生的动态信息：volumePaths、containerId、workDir、projectName、generatedAt
     */
    @TableField(typeHandler = JsonTypeHandler.class)
    private Map<String, Object> runtimeMetadata;
```

- [ ] **Step 3: 新建 SchemaMigrationRunner.java**

创建 `backend/core/src/main/java/com/gameplatform/config/SchemaMigrationRunner.java`：

```java
package com.gameplatform.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库Schema迁移执行器
 * 启动时自动执行幂等的ALTER TABLE语句，确保新增列存在
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        ensureColumnExists("game_instance", "runtime_metadata", "TEXT");
    }

    /**
     * 确保指定表的列存在，不存在则添加
     *
     * @param tableName  表名
     * @param columnName 列名
     * @param columnType 列类型（如 TEXT）
     */
    private void ensureColumnExists(String tableName, String columnName, String columnType) {
        try {
            List<String> columns = new ArrayList<>();
            jdbcTemplate.query("PRAGMA table_info(" + tableName + ")", (ResultSet rs) -> {
                columns.add(rs.getString("name"));
            });

            if (!columns.contains(columnName)) {
                String sql = String.format("ALTER TABLE %s ADD COLUMN %s %s",
                        tableName, columnName, columnType);
                jdbcTemplate.execute(sql);
                log.info("Schema迁移: 已添加列 {}.{} {}", tableName, columnName, columnType);
            } else {
                log.debug("Schema迁移: 列 {}.{} 已存在，跳过", tableName, columnName);
            }
        } catch (Exception e) {
            log.error("Schema迁移失败: {}.{} - {}", tableName, columnName, e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `cd backend && mvn -pl core compile -am -q`
Expected: 编译成功，无错误

- [ ] **Step 5: 启动后端验证迁移**

Run: `cd backend && mvn -pl core spring-boot:run -am -q` （等启动后停止）

Expected: 日志中看到 `Schema迁移: 已添加列 game_instance.runtime_metadata TEXT`（首次）或 `列 game_instance.runtime_metadata 已存在，跳过`（后续）

- [ ] **Step 6: Commit**

```bash
git add backend/core/src/main/resources/db/schema.sql backend/core/src/main/java/com/gameplatform/entity/GameInstance.java backend/core/src/main/java/com/gameplatform/config/SchemaMigrationRunner.java
git commit -m "feat: 新增 game_instance.runtime_metadata 字段和 SchemaMigrationRunner"
```

---

## Task 2: yml 配置模型 - 新增 DockerComposeConfig 内部类

**Files:**
- Modify: `backend/core/src/main/java/com/gameplatform/config/GameYamlConfig.java`

- [ ] **Step 1: 在 GameInfo 内部类中新增 dockerCompose 字段**

在 `backend/core/src/main/java/com/gameplatform/config/GameYamlConfig.java` 的 `GameInfo` 内部类中，在 `private LinuxGsmConfig linuxgsm;` 字段之后新增：

```java
        /**
         * Docker Compose部署配置
         */
        private DockerComposeConfig dockerCompose;
```

- [ ] **Step 2: 新增 DockerComposeConfig 内部类**

在 `GameYamlConfig.java` 中 `LinuxGsmConfig` 内部类之后新增：

```java
    /**
     * Docker Compose部署配置
     */
    @Data
    public static class DockerComposeConfig {
        /**
         * Compose模板原文（保留 ${VAR:default} 变量语法）
         */
        private String composeTemplate;

        /**
         * 变量元信息列表（用于前端表单渲染和后端校验）
         */
        private List<VariableDefinition> variables = new ArrayList<>();

        /**
         * 命名卷列表（用于后端识别需要 inspect 的卷）
         */
        private List<String> namedVolumes = new ArrayList<>();

        // SnakeYAML 需要显式的 setter 方法
        public void setVariables(List<VariableDefinition> variables) {
            this.variables = variables != null ? variables : new ArrayList<>();
        }

        public void setNamedVolumes(List<String> namedVolumes) {
            this.namedVolumes = namedVolumes != null ? namedVolumes : new ArrayList<>();
        }
    }

    /**
     * 变量定义
     */
    @Data
    public static class VariableDefinition {
        /**
         * 变量名（对应 compose 中的 ${VAR_NAME}）
         */
        private String name;

        /**
         * 前端表单显示标签
         */
        private String label;

        /**
         * 变量类型: string/integer/boolean/password
         */
        private String type;

        /**
         * 默认值（字符串形式）
         */
        private String defaultValue;

        /**
         * 是否必填
         */
        private boolean required;

        /**
         * 描述文本
         */
        private String description;

        /**
         * 是否在前端隐藏（高级选项）
         */
        private boolean hidden;
    }
```

- [ ] **Step 3: 编译验证**

Run: `cd backend && mvn -pl core compile -am -q`
Expected: 编译成功

- [ ] **Step 4: Commit**

```bash
git add backend/core/src/main/java/com/gameplatform/config/GameYamlConfig.java
git commit -m "feat: GameYamlConfig 新增 DockerComposeConfig 和 VariableDefinition 内部类"
```

---

## Task 3: GameMetadataScanner.buildDeployConfig() 增强

**Files:**
- Modify: `backend/core/src/main/java/com/gameplatform/service/GameMetadataScanner.java` (buildDeployConfig 方法)

- [ ] **Step 1: 在 buildDeployConfig 方法中新增 docker-compose 子键处理**

在 `backend/core/src/main/java/com/gameplatform/service/GameMetadataScanner.java` 的 `buildDeployConfig` 方法中，在 `// LinuxGSM配置` 块之后、`// 配置Schema` 块之前新增：

```java
        // Docker Compose配置
        if (gameInfo.getDockerCompose() != null) {
            GameYamlConfig.DockerComposeConfig dc = gameInfo.getDockerCompose();
            Map<String, Object> composeConfig = new HashMap<>();
            composeConfig.put("composeTemplate", dc.getComposeTemplate());
            composeConfig.put("variables", objectMapper.convertValue(dc.getVariables(),
                    new TypeReference<List<Map<String, Object>>>() {}));
            composeConfig.put("namedVolumes", dc.getNamedVolumes());
            deployConfig.put("docker-compose", composeConfig);
        }
```

注意：需要确保文件顶部已 import `TypeReference`：
```java
import com.fasterxml.jackson.core.type.TypeReference;
```
如果未导入则添加。

- [ ] **Step 2: 编译验证**

Run: `cd backend && mvn -pl core compile -am -q`
Expected: 编译成功

- [ ] **Step 3: Commit**

```bash
git add backend/core/src/main/java/com/gameplatform/service/GameMetadataScanner.java
git commit -m "feat: GameMetadataScanner.buildDeployConfig 支持 docker-compose 配置块"
```

---

## Task 4: l4d2.yml 新增 dockerCompose 配置块

**Files:**
- Modify: `backend/core/src/main/resources/games/l4d2.yml`

- [ ] **Step 1: 修改 l4d2.yml，deployTypes 新增 docker-compose**

在 `backend/core/src/main/resources/games/l4d2.yml` 的 `deployTypes` 列表中新增 `docker-compose`：

```yaml
  deployTypes:
    - docker
    - linuxgsm
    - docker-compose
```

- [ ] **Step 2: 在 l4d2.yml 中新增 dockerCompose 配置块**

在 `linuxgsm:` 块之后、`configSchema:` 块之前新增：

```yaml
  # Docker Compose部署配置
  dockerCompose:
    composeTemplate: |
      volumes:
        l4d2-data:

      networks:
        l4d2-network:

      services:
        l4d2:
          image: laoyutang/l4d2-pure:latest
          container_name: ${CONTAINER_NAME:l4d2}
          restart: unless-stopped
          ports:
            - "${L4D2_PORT:27015}:27015"
            - "${L4D2_PORT:27015}:27015/udp"
          volumes:
            - l4d2-data:/l4d2/left4dead2
            - /etc/localtime:/etc/localtime:ro
            - /etc/timezone:/etc/timezone:ro
          networks:
            - l4d2-network
          security_opt:
            - seccomp:unconfined
          environment:
            - L4D2_TICK=${L4D2_TICK:100}
            - L4D2_VAC=${L4D2_VAC:false}
            - L4D2_PORT=${L4D2_PORT:27015}
            - L4D2_RCON_PASSWORD=${L4D2_RCON_PASSWORD}

    variables:
      - name: L4D2_PORT
        label: 游戏端口
        type: integer
        defaultValue: "27015"
        required: false
        description: 客户端连接端口（TCP+UDP）
      - name: L4D2_TICK
        label: 服务器Tickrate
        type: integer
        defaultValue: "100"
        required: false
        description: 服务器刷新率，影响命中检测精度
      - name: L4D2_VAC
        label: VAC反作弊
        type: boolean
        defaultValue: "false"
        required: false
        description: 是否启用Valve反作弊
      - name: L4D2_RCON_PASSWORD
        label: RCON密码
        type: password
        required: true
        description: 远程控制台密码，必填
      - name: CONTAINER_NAME
        label: 容器名称
        type: string
        defaultValue: "l4d2"
        required: false
        description: Docker容器名称
        hidden: true

    namedVolumes:
      - l4d2-data
```

- [ ] **Step 3: 启动后端验证扫描结果**

Run: `cd backend && mvn -pl core spring-boot:run -am -q`

启动后，通过 API 验证：
```
GET http://localhost:8080/api/games/code/l4d2
```

Expected: 响应中 `deployConfig` 字段包含 `docker-compose` 子键，内含 `composeTemplate`、`variables`、`namedVolumes`。同时 `supportedDeployTypes` 包含 `docker-compose`。

- [ ] **Step 4: Commit**

```bash
git add backend/core/src/main/resources/games/l4d2.yml
git commit -m "feat: l4d2.yml 新增 docker-compose 部署类型和 dockerCompose 配置块"
```

---

## Task 5: DockerComposeAdapter 增强 - .env 生成与上传

**Files:**
- Modify: `backend/core/src/main/java/com/gameplatform/adapter/DockerComposeAdapter.java`

- [ ] **Step 1: 新增 generateEnvFileContent 方法**

在 `backend/core/src/main/java/com/gameplatform/adapter/DockerComposeAdapter.java` 中，在 `generateComposeFile` 方法之前新增：

```java
    /**
     * 生成 .env 文件内容
     * 遍历 variables 元信息，优先使用用户输入值，其次使用默认值
     *
     * @param config 部署配置（包含 variables 元信息和用户输入值）
     * @return .env 文件内容（KEY=VALUE 每行一个）
     * @throws RuntimeException 如果必填变量未提供值
     */
    @SuppressWarnings("unchecked")
    private String generateEnvFileContent(Map<String, Object> config) {
        StringBuilder envContent = new StringBuilder();
        Object variablesObj = config.get("variables");
        if (variablesObj == null) {
            return envContent.toString();
        }

        List<Map<String, Object>> variables;
        if (variablesObj instanceof List) {
            variables = (List<Map<String, Object>>) variablesObj;
        } else {
            log.warn("variables 配置类型异常: {}", variablesObj.getClass());
            return envContent.toString();
        }

        for (Map<String, Object> var : variables) {
            String name = (String) var.get("name");
            if (name == null || name.isEmpty()) {
                continue;
            }
            String defaultValue = var.get("defaultValue") != null ? var.get("defaultValue").toString() : "";
            boolean required = Boolean.TRUE.equals(var.get("required"));

            Object userValue = config.get(name);
            String value = userValue != null ? userValue.toString() : defaultValue;

            if (value.isEmpty() && required) {
                throw new RuntimeException("必填变量 " + name + " 未提供值");
            }

            envContent.append(name).append("=").append(value).append("\n");
        }
        return envContent.toString();
    }
```

- [ ] **Step 2: 新增 uploadEnvFile 方法**

在 `uploadComposeFile` 方法之后新增（复用相同模式：写本地临时文件 + SFTP 上传）：

```java
    /**
     * 上传 .env 文件
     *
     * @param host    远程主机
     * @param workDir 远程工作目录
     * @param content .env 文件内容
     * @return 是否上传成功
     */
    private boolean uploadEnvFile(Host host, String workDir, String content) {
        try {
            String tempFile = "/tmp/env-" + System.currentTimeMillis() + ".txt";
            java.nio.file.Files.write(java.nio.file.Paths.get(tempFile),
                    content.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            String remotePath = workDir + "/.env";
            boolean uploaded = uploadFile(host, tempFile, remotePath);

            java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(tempFile));

            if (uploaded) {
                log.info(".env 文件上传成功: {}", remotePath);
            }
            return uploaded;
        } catch (Exception e) {
            log.error("上传 .env 文件失败", e);
            return false;
        }
    }
```

- [ ] **Step 3: 修改 preDeploy 方法，支持 composeTemplate 模板模式**

在 `preDeploy` 方法中，找到现有的 compose 内容处理逻辑（约 113-133 行）：

```java
            notifyProgress(callback, 40, "PRE_DEPLOY", "生成docker-compose.yml");
            // 生成或上传docker-compose.yml
            String composeContent = getConfigString(config, "composeContent", "");
            String composeFile = getConfigString(config, "composeFile", "");

            if (!composeContent.isEmpty()) {
                // 使用提供的compose内容
                if (!uploadComposeFile(host, workDir, composeContent)) {
                    notifyError(callback, "上传docker-compose.yml失败", "PRE_DEPLOY", false);
                    return false;
                }
            } else if (!composeFile.isEmpty()) {
                // 上传本地compose文件
                String remotePath = workDir + "/" + COMPOSE_FILE;
                if (!uploadFile(host, composeFile, remotePath)) {
                    notifyError(callback, "上传docker-compose.yml失败", "PRE_DEPLOY", false);
                    return false;
                }
            } else {
                // 从配置生成compose文件
                String generatedCompose = generateComposeFile(config);
                if (!uploadComposeFile(host, workDir, generatedCompose)) {
                    notifyError(callback, "生成docker-compose.yml失败", "PRE_DEPLOY", false);
                    return false;
                }
            }
```

替换为以下逻辑（新增 composeTemplate 优先处理 + .env 生成上传）：

```java
            notifyProgress(callback, 30, "PRE_DEPLOY", "生成docker-compose.yml");
            // 生成或上传docker-compose.yml
            // 优先级: composeTemplate(模板驱动) > composeContent(用户直接提供) > composeFile(本地文件) > generateComposeFile(自动生成)
            String composeTemplate = getConfigString(config, "composeTemplate", "");
            String composeContent = getConfigString(config, "composeContent", "");
            String composeFile = getConfigString(config, "composeFile", "");

            String composeToUpload = null;
            if (!composeTemplate.isEmpty()) {
                // 模板驱动模式：使用 yml 中定义的 compose 模板原文
                composeToUpload = composeTemplate;
            } else if (!composeContent.isEmpty()) {
                composeToUpload = composeContent;
            } else if (!composeFile.isEmpty()) {
                // 上传本地compose文件（特殊处理，不通过 uploadComposeFile）
                String remotePath = workDir + "/" + COMPOSE_FILE;
                if (!uploadFile(host, composeFile, remotePath)) {
                    notifyError(callback, "上传docker-compose.yml失败", "PRE_DEPLOY", false);
                    return false;
                }
            } else {
                composeToUpload = generateComposeFile(config);
            }

            if (composeToUpload != null) {
                if (!uploadComposeFile(host, workDir, composeToUpload)) {
                    notifyError(callback, "上传docker-compose.yml失败", "PRE_DEPLOY", false);
                    return false;
                }
            }

            // 模板驱动模式下，生成并上传 .env 文件
            if (!composeTemplate.isEmpty()) {
                notifyProgress(callback, 45, "PRE_DEPLOY", "生成 .env 文件");
                try {
                    String envContent = generateEnvFileContent(config);
                    if (!envContent.isEmpty()) {
                        if (!uploadEnvFile(host, workDir, envContent)) {
                            notifyError(callback, "上传.env文件失败", "PRE_DEPLOY", false);
                            return false;
                        }
                    }
                } catch (RuntimeException e) {
                    notifyError(callback, "生成.env文件失败: " + e.getMessage(), "PRE_DEPLOY", false);
                    return false;
                }
            }
```

- [ ] **Step 4: 编译验证**

Run: `cd backend && mvn -pl core compile -am -q`
Expected: 编译成功

- [ ] **Step 5: Commit**

```bash
git add backend/core/src/main/java/com/gameplatform/adapter/DockerComposeAdapter.java
git commit -m "feat: DockerComposeAdapter 支持 composeTemplate 模板和 .env 文件生成"
```

---

## Task 6: DockerComposeAdapter 增强 - 卷路径获取与 runtimeMetadata 组装

**Files:**
- Modify: `backend/core/src/main/java/com/gameplatform/adapter/DockerComposeAdapter.java`

- [ ] **Step 1: 新增 getVolumeHostPaths 方法**

在 `backend/core/src/main/java/com/gameplatform/adapter/DockerComposeAdapter.java` 中，在 `getWorkDir` 方法之后新增：

```java
    /**
     * 获取命名卷的宿主路径
     * Docker Compose 命名卷规则: {projectName}_{volumeName}
     *
     * @param host        远程主机
     * @param projectName Compose 项目名
     * @param namedVolumes 命名卷列表（来自 yml 配置）
     * @return 卷名 → 宿主路径 的映射
     */
    @SuppressWarnings("unchecked")
    private java.util.Map<String, String> getVolumeHostPaths(Host host, String projectName,
                                                              java.util.List<String> namedVolumes) {
        java.util.Map<String, String> volumePaths = new java.util.LinkedHashMap<>();
        if (namedVolumes == null || namedVolumes.isEmpty()) {
            return volumePaths;
        }

        for (String volumeName : namedVolumes) {
            String fullVolumeName = projectName + "_" + volumeName;
            SshUtil.CommandResult result = executeCommand(host,
                    String.format("docker volume inspect %s -f '{{.Mountpoint}}'", fullVolumeName));
            if (result.isSuccess() && !result.getOutput().trim().isEmpty()) {
                volumePaths.put(volumeName, result.getOutput().trim());
            } else {
                log.warn("获取卷宿主路径失败: {}", fullVolumeName);
            }
        }
        return volumePaths;
    }

    /**
     * 获取 Compose 项目的主容器 ID
     *
     * @param host        远程主机
     * @param projectName Compose 项目名
     * @return 容器 ID（可能为空字符串）
     */
    private String getComposeContainerId(Host host, String projectName) {
        SshUtil.CommandResult result = executeCommand(host,
                String.format("docker compose -p %s ps -q 2>/dev/null | head -1", projectName));
        if (result.isSuccess()) {
            return result.getOutput().trim();
        }
        return "";
    }
```

- [ ] **Step 2: 修改 deploy 方法，新增 runtimeMetadata 组装逻辑**

在 `deploy` 方法中，找到现有的实例更新逻辑（约 213-218 行）：

```java
        // 更新实例信息
        GameInstance instance = info.instance();
        instance.setInstallPath(workDir);
        instance.setStartCommand(String.format("cd %s && docker compose -p %s start", workDir, projectName));
        instance.setStopCommand(String.format("cd %s && docker compose -p %s stop", workDir, projectName));
        instanceMapper.updateById(instance);
```

替换为：

```java
        // 更新实例信息
        GameInstance instance = info.instance();
        instance.setInstallPath(workDir);
        instance.setStartCommand(String.format("cd %s && docker compose -p %s start", workDir, projectName));
        instance.setStopCommand(String.format("cd %s && docker compose -p %s stop", workDir, projectName));

        // 组装运行时元数据（卷宿主路径、容器ID、工作目录、项目名）
        java.util.Map<String, Object> runtimeMetadata = new java.util.LinkedHashMap<>();
        try {
            notifyProgress(callback, 85, "DEPLOY", "获取运行时元数据");

            // 获取命名卷宿主路径
            Object namedVolumesObj = config.get("namedVolumes");
            java.util.List<String> namedVolumes = new java.util.ArrayList<>();
            if (namedVolumesObj instanceof java.util.List) {
                namedVolumes = (java.util.List<String>) namedVolumesObj;
            }
            java.util.Map<String, String> volumePaths = getVolumeHostPaths(host, projectName, namedVolumes);
            runtimeMetadata.put("volumePaths", volumePaths);

            // 获取容器ID
            String containerId = getComposeContainerId(host, projectName);
            runtimeMetadata.put("containerId", containerId);

            runtimeMetadata.put("workDir", workDir);
            runtimeMetadata.put("projectName", projectName);
            runtimeMetadata.put("generatedAt", java.time.LocalDateTime.now().toString());

            instance.setRuntimeMetadata(runtimeMetadata);
            log.info("实例 {} 运行时元数据: 卷路径数={}, 容器ID={}",
                    instance.getId(), volumePaths.size(),
                    containerId.length() > 12 ? containerId.substring(0, 12) : containerId);
        } catch (Exception e) {
            log.warn("组装运行时元数据失败（不影响部署结果）: {}", e.getMessage());
        }

        instanceMapper.updateById(instance);
```

- [ ] **Step 3: 添加必要的 import**

确保 `DockerComposeAdapter.java` 顶部已 import（如果未导入则添加）：

```java
import com.gameplatform.entity.GameInstance;
```

注意：`Host` 和 `SshUtil` 应该已经通过父类或现有代码间接导入。

- [ ] **Step 4: 编译验证**

Run: `cd backend && mvn -pl core compile -am -q`
Expected: 编译成功

- [ ] **Step 5: Commit**

```bash
git add backend/core/src/main/java/com/gameplatform/adapter/DockerComposeAdapter.java
git commit -m "feat: DockerComposeAdapter 部署后获取卷宿主路径并组装 runtimeMetadata"
```

---

## Task 7: InstanceServiceImpl.buildDeployConfig() 注入 runtimeMetadata

**Files:**
- Modify: `backend/core/src/main/java/com/gameplatform/service/impl/InstanceServiceImpl.java` (buildDeployConfig 方法)

- [ ] **Step 1: 修改 buildDeployConfig 方法，注入 runtimeMetadata**

在 `backend/core/src/main/java/com/gameplatform/service/impl/InstanceServiceImpl.java` 的 `buildDeployConfig` 方法中，在 `config.put("gameCode", instance.getGameCode());` 之后新增：

```java
        // 5. 注入运行时元数据（供适配器使用）
        if (instance.getRuntimeMetadata() != null) {
            config.put("runtimeMetadata", instance.getRuntimeMetadata());
        }
```

修改后的片段参考：
```java
    // 4. 注入实例元数据
    config.put("installPath", instance.getInstallPath());
    config.put("instanceId", instance.getId());
    config.put("gameCode", instance.getGameCode());

    // 5. 注入运行时元数据（供适配器使用）
    if (instance.getRuntimeMetadata() != null) {
        config.put("runtimeMetadata", instance.getRuntimeMetadata());
    }

    // 6. 组合完整的镜像名（image:tag）
    // ... 现有逻辑
```

- [ ] **Step 2: 编译验证**

Run: `cd backend && mvn -pl core compile -am -q`
Expected: 编译成功

- [ ] **Step 3: Commit**

```bash
git add backend/core/src/main/java/com/gameplatform/service/impl/InstanceServiceImpl.java
git commit -m "feat: InstanceServiceImpl.buildDeployConfig 注入 runtimeMetadata"
```

---

## Task 8: API 层 - 新增 deploy-config 端点

**Files:**
- Create: `backend/core/src/main/java/com/gameplatform/vo/DeployConfigVO.java`
- Modify: `backend/core/src/main/java/com/gameplatform/controller/GameMetadataController.java`

- [ ] **Step 1: 新建 DeployConfigVO.java**

创建 `backend/core/src/main/java/com/gameplatform/vo/DeployConfigVO.java`：

```java
package com.gameplatform.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 部署配置响应VO
 * 用于返回指定部署类型的配置信息（如变量元信息、compose模板等）
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class DeployConfigVO {

    /**
     * 部署类型
     */
    private String deployType;

    /**
     * Compose 模板原文（仅 docker-compose 类型有值）
     */
    private String composeTemplate;

    /**
     * 变量元信息列表（仅 docker-compose 类型有值）
     * 每个变量含 name/label/type/defaultValue/required/description/hidden 字段
     */
    private List<Map<String, Object>> variables;

    /**
     * 命名卷列表（仅 docker-compose 类型有值）
     * 用于后端识别需要 inspect 的卷
     */
    private List<String> namedVolumes;

    /**
     * 其他配置项（docker/linuxgsm 类型的完整配置）
     */
    private Map<String, Object> config;
}
```

- [ ] **Step 2: 修改 GameMetadataController，新增 deploy-config 端点**

在 `backend/core/src/main/java/com/gameplatform/controller/GameMetadataController.java` 中：

1. 在文件顶部 import 区域新增：
```java
import com.gameplatform.vo.DeployConfigVO;
```

2. 在 `getByCode` 方法之后新增端点方法：

```java
    @Operation(summary = "获取部署配置", description = "根据游戏ID和部署类型获取部署配置（变量元信息、compose模板等）")
    @GetMapping("/{id}/deploy-config/{deployType}")
    public Result<DeployConfigVO> getDeployConfig(
            @Parameter(description = "游戏ID") @PathVariable Long id,
            @Parameter(description = "部署类型（docker/linuxgsm/docker-compose）") @PathVariable String deployType) {
        DeployConfigVO vo = gameService.getDeployConfig(id, deployType);
        return Result.success(vo);
    }
```

- [ ] **Step 3: 在 GameService 接口新增方法声明**

查找 `backend/core/src/main/java/com/gameplatform/service/GameService.java`，在接口中新增方法：

```java
    /**
     * 获取指定游戏的部署配置
     *
     * @param gameId     游戏ID
     * @param deployType 部署类型
     * @return 部署配置VO
     */
    DeployConfigVO getDeployConfig(Long gameId, String deployType);
```

- [ ] **Step 4: 在 GameServiceImpl 实现方法**

查找 `backend/core/src/main/java/com/gameplatform/service/impl/GameServiceImpl.java`，新增方法实现：

```java
    @Override
    public DeployConfigVO getDeployConfig(Long gameId, String deployType) {
        GameMetadata game = gameMetadataMapper.selectById(gameId);
        if (game == null) {
            throw new BusinessException("游戏不存在: " + gameId);
        }
        if (game.getDeployConfig() == null) {
            throw new BusinessException("游戏部署配置为空");
        }

        Object typeConfig = game.getDeployConfig().get(deployType);
        if (!(typeConfig instanceof Map)) {
            throw new BusinessException("不支持的部署类型: " + deployType);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) typeConfig;

        DeployConfigVO vo = new DeployConfigVO();
        vo.setDeployType(deployType);
        vo.setConfig(config);

        // docker-compose 类型特殊处理：提取 composeTemplate/variables/namedVolumes 到顶层
        if ("docker-compose".equals(deployType)) {
            vo.setComposeTemplate((String) config.get("composeTemplate"));

            Object variablesObj = config.get("variables");
            if (variablesObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> variables = (List<Map<String, Object>>) variablesObj;
                vo.setVariables(variables);
            }

            Object namedVolumesObj = config.get("namedVolumes");
            if (namedVolumesObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> namedVolumes = (List<String>) namedVolumesObj;
                vo.setNamedVolumes(namedVolumes);
            }
        }

        return vo;
    }
```

注意：需要在 GameServiceImpl 顶部 import：
```java
import com.gameplatform.vo.DeployConfigVO;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.entity.GameMetadata;
import com.gameplatform.mapper.GameMetadataMapper;
```

并在类中注入（如果尚未注入）：
```java
private final GameMetadataMapper gameMetadataMapper;
```

- [ ] **Step 5: 编译验证**

Run: `cd backend && mvn -pl core compile -am -q`
Expected: 编译成功

- [ ] **Step 6: 启动后端验证 API**

Run: `cd backend && mvn -pl core spring-boot:run -am -q`

启动后调用：
```
GET http://localhost:8080/api/games/1/deploy-config/docker-compose
```
（假设 l4d2 的 gameId=1，如不是请先通过 `GET /api/games/list` 查询）

Expected: 返回结构如下
```json
{
  "code": 200,
  "data": {
    "deployType": "docker-compose",
    "composeTemplate": "volumes:\n  l4d2-data:\n...",
    "variables": [
      {"name": "L4D2_PORT", "label": "游戏端口", "type": "integer", "defaultValue": "27015", "required": false, ...},
      {"name": "L4D2_RCON_PASSWORD", "label": "RCON密码", "type": "password", "required": true, ...}
    ],
    "namedVolumes": ["l4d2-data"],
    "config": { ... }
  }
}
```

- [ ] **Step 7: Commit**

```bash
git add backend/core/src/main/java/com/gameplatform/vo/DeployConfigVO.java backend/core/src/main/java/com/gameplatform/controller/GameMetadataController.java backend/core/src/main/java/com/gameplatform/service/GameService.java backend/core/src/main/java/com/gameplatform/service/impl/GameServiceImpl.java
git commit -m "feat: 新增 GET /games/{id}/deploy-config/{type} API"
```

---

## Task 9: 前端 API - 新增 getDeployConfig 方法

**Files:**
- Modify: `frontend/src/api/game.js`

- [ ] **Step 1: 查看现有 game.js 文件结构**

Run: 使用 Read 工具查看 `frontend/src/api/game.js` 当前内容，了解导出风格（是 `export function` 还是 `export default { method }`）

- [ ] **Step 2: 在 game.js 中新增 getDeployConfig 方法**

根据现有代码风格，在 `frontend/src/api/game.js` 中新增（假设使用 `export function` 风格，如风格不同请调整）：

```javascript
/**
 * 获取指定游戏的部署配置
 * @param {number} gameId 游戏ID
 * @param {string} deployType 部署类型（docker/linuxgsm/docker-compose）
 * @returns {Promise} 部署配置
 */
export function getDeployConfig(gameId, deployType) {
  return request({
    url: `/games/${gameId}/deploy-config/${deployType}`,
    method: 'get'
  })
}
```

如果文件使用 `export default` 风格，则在对象内新增 `getDeployConfig` 方法。

- [ ] **Step 3: Commit**

```bash
git add frontend/src/api/game.js
git commit -m "feat: 前端 game API 新增 getDeployConfig 方法"
```

---

## Task 10: 前端组件 - DeployVariableForm.vue

**Files:**
- Create: `frontend/src/components/DeployVariableForm.vue`

- [ ] **Step 1: 创建 DeployVariableForm.vue 组件**

创建 `frontend/src/components/DeployVariableForm.vue`：

```vue
<template>
  <div class="deploy-variable-form" v-if="visibleVariables.length > 0">
    <el-divider content-position="left">
      <span class="divider-title">部署变量配置</span>
    </el-divider>

    <el-form
      ref="formRef"
      :model="formData"
      :rules="formRules"
      label-width="140px"
      label-position="right"
    >
      <el-row :gutter="16">
        <el-col
          v-for="variable in visibleVariables"
          :key="variable.name"
          :span="12"
        >
          <el-form-item
            :label="variable.label"
            :prop="variable.name"
          >
            <!-- 整数类型 -->
            <el-input-number
              v-if="variable.type === 'integer'"
              v-model="formData[variable.name]"
              :placeholder="`请输入${variable.label}`"
              :min="0"
              controls-position="right"
              style="width: 100%"
            />

            <!-- 布尔类型 -->
            <el-switch
              v-else-if="variable.type === 'boolean'"
              v-model="formData[variable.name]"
              :active-text="formData[variable.name] ? '开启' : '关闭'"
            />

            <!-- 密码类型 -->
            <el-input
              v-else-if="variable.type === 'password'"
              v-model="formData[variable.name]"
              type="password"
              show-password
              :placeholder="`请输入${variable.label}`"
            />

            <!-- 字符串类型（默认） -->
            <el-input
              v-else
              v-model="formData[variable.name]"
              :placeholder="`请输入${variable.label}`"
            />

            <!-- 描述 tooltip -->
            <el-tooltip
              v-if="variable.description"
              :content="variable.description"
              placement="top"
            >
              <el-icon class="description-icon"><InfoFilled /></el-icon>
            </el-tooltip>
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { InfoFilled } from '@element-plus/icons-vue'

const props = defineProps({
  /**
   * 变量元信息列表
   * [{ name, label, type, defaultValue, required, description, hidden }]
   */
  variables: {
    type: Array,
    default: () => []
  }
})

const emit = defineEmits(['update:formData'])

const formRef = ref()

// 表单数据
const formData = reactive({})

// 仅展示非隐藏的变量
const visibleVariables = computed(() => {
  return (props.variables || []).filter(v => !v.hidden)
})

// 表单校验规则
const formRules = computed(() => {
  const rules = {}
  for (const variable of props.variables || []) {
    if (variable.hidden) continue
    if (variable.required) {
      rules[variable.name] = [
        { required: true, message: `${variable.label}为必填项`, trigger: 'blur' }
      ]
    }
  }
  return rules
})

// 监听 variables 变化，初始化表单数据
watch(
  () => props.variables,
  (newVariables) => {
    // 清空旧数据
    Object.keys(formData).forEach(key => delete formData[key])

    for (const variable of newVariables || []) {
      // 布尔类型需要转换为 boolean
      if (variable.type === 'boolean') {
        const defaultVal = variable.defaultValue
        formData[variable.name] = defaultVal === 'true' || defaultVal === true
      } else {
        formData[variable.name] = variable.defaultValue || ''
      }
    }
    emit('update:formData', { ...formData })
  },
  { immediate: true, deep: true }
)

// 监听表单数据变化，向上传递
watch(
  formData,
  (newVal) => {
    emit('update:formData', { ...newVal })
  },
  { deep: true }
)

/**
 * 校验表单
 * @returns {Promise<boolean>}
 */
async function validate() {
  if (!formRef.value) return true
  try {
    await formRef.value.validate()
    return true
  } catch {
    return false
  }
}

/**
 * 获取表单数据
 * @returns {Object}
 */
function getFormData() {
  // 包含隐藏变量的默认值
  const result = {}
  for (const variable of props.variables || []) {
    if (variable.hidden && !formData[variable.name]) {
      // 隐藏变量使用默认值
      if (variable.type === 'boolean') {
        result[variable.name] = variable.defaultValue === 'true' || variable.defaultValue === true ? 'true' : 'false'
      } else {
        result[variable.name] = variable.defaultValue || ''
      }
    } else {
      // 布尔值转换为字符串
      if (variable.type === 'boolean') {
        result[variable.name] = formData[variable.name] ? 'true' : 'false'
      } else {
        result[variable.name] = formData[variable.name]
      }
    }
  }
  return result
}

defineExpose({
  validate,
  getFormData
})
</script>

<style scoped>
.deploy-variable-form {
  margin-top: 16px;
}

.divider-title {
  font-weight: 600;
  color: #303133;
}

.description-icon {
  margin-left: 8px;
  color: #909399;
  cursor: help;
  vertical-align: middle;
}
</style>
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/components/DeployVariableForm.vue
git commit -m "feat: 新增 DeployVariableForm 动态变量表单组件"
```

---

## Task 11: 前端集成 - deploy.vue 集成 DeployVariableForm

**Files:**
- Modify: `frontend/src/views/instance/deploy.vue`

- [ ] **Step 1: 查看 deploy.vue 当前结构**

Run: 使用 Read 工具查看 `frontend/src/views/instance/deploy.vue` 完整内容，了解：
- 部署类型选择器的位置
- 表单提交逻辑
- configInfo 的组装方式

- [ ] **Step 2: 在 deploy.vue 中集成 DeployVariableForm**

根据 deploy.vue 的实际结构，进行以下修改：

1. 在 `<script setup>` 中导入组件和 API：
```javascript
import DeployVariableForm from '@/components/DeployVariableForm.vue'
import { getDeployConfig } from '@/api/game'
import { ref, watch } from 'vue'
```

2. 新增响应式状态：
```javascript
// 部署变量相关
const deployVariables = ref([])
const variableFormData = ref({})
const deployVariableFormRef = ref()

// 监听部署类型变化，加载变量配置
watch(
  () => form.deployType,
  async (newType) => {
    if (newType === 'docker-compose' && form.gameId) {
      try {
        const res = await getDeployConfig(form.gameId, newType)
        deployVariables.value = res.data?.variables || []
      } catch (err) {
        console.error('获取部署配置失败:', err)
        deployVariables.value = []
      }
    } else {
      deployVariables.value = []
    }
  }
)

// 监听游戏变化时也重新加载
watch(
  () => form.gameId,
  () => {
    if (form.deployType === 'docker-compose') {
      // 触发上面的 watch
      const t = form.deployType
      form.deployType = ''
      form.deployType = t
    }
  }
)
```

3. 在模板中部署类型选择器之后添加：
```vue
<DeployVariableForm
  v-if="deployVariables.length > 0"
  ref="deployVariableFormRef"
  :variables="deployVariables"
  v-model:formData="variableFormData"
/>
```

4. 在提交方法中合并变量数据到 configInfo：
```javascript
async function handleSubmit() {
  // 校验变量表单
  if (deployVariables.value.length > 0 && deployVariableFormRef.value) {
    const valid = await deployVariableFormRef.value.validate()
    if (!valid) {
      ElMessage.error('请完成部署变量配置')
      return
    }
    // 合并变量数据到 configInfo
    const variableData = deployVariableFormRef.value.getFormData()
    form.configInfo = { ...(form.configInfo || {}), ...variableData }
  }
  // ... 继续原有的提交逻辑
}
```

注意：以上代码片段需根据 deploy.vue 实际结构适配，变量名、方法名可能与现有代码不同。实施时先完整阅读 deploy.vue，再精确插入。

- [ ] **Step 3: 启动前端验证**

Run: `cd frontend && npm run dev`

打开浏览器，进入实例部署页面，选择游戏为 L4D2，部署类型选择 docker-compose，验证：
- 动态变量表单出现，包含"游戏端口"、"服务器Tickrate"、"VAC反作弊"、"RCON密码"字段
- CONTAINER_NAME 字段不显示（hidden）
- RCON密码字段标记为必填
- 不填 RCON密码提交时显示校验错误

- [ ] **Step 4: Commit**

```bash
git add frontend/src/views/instance/deploy.vue
git commit -m "feat: deploy.vue 集成 DeployVariableForm 动态变量表单"
```

---

## Task 12: 前端集成 - detail.vue 展示运行时元数据

**Files:**
- Modify: `frontend/src/views/instance/detail.vue`

- [ ] **Step 1: 查看 detail.vue 当前结构**

Run: 使用 Read 工具查看 `frontend/src/views/instance/detail.vue` 完整内容，了解实例详情页的布局和现有数据展示方式。

- [ ] **Step 2: 在 detail.vue 中新增"运行时信息"卡片**

在 detail.vue 适当位置（如基本信息卡片之后）新增：

```vue
<!-- 运行时信息卡片（仅 docker-compose 部署类型显示） -->
<el-card v-if="instance.deployType === 'docker-compose' && instance.runtimeMetadata" class="runtime-info-card">
  <template #header>
    <div class="card-header">
      <span>运行时信息</span>
      <el-tag size="small" type="info">Docker Compose</el-tag>
    </div>
  </template>

  <el-descriptions :column="1" border>
    <!-- 工作目录 -->
    <el-descriptions-item label="工作目录">
      <code>{{ instance.runtimeMetadata.workDir || '-' }}</code>
    </el-descriptions-item>

    <!-- 项目名 -->
    <el-descriptions-item label="Compose 项目名">
      <code>{{ instance.runtimeMetadata.projectName || '-' }}</code>
    </el-descriptions-item>

    <!-- 容器ID -->
    <el-descriptions-item label="容器 ID">
      <code>{{ formatContainerId(instance.runtimeMetadata.containerId) }}</code>
    </el-descriptions-item>

    <!-- 数据卷路径 -->
    <el-descriptions-item label="数据卷宿主路径">
      <div
        v-for="(path, name) in (instance.runtimeMetadata.volumePaths || {})"
        :key="name"
        class="volume-path-row"
      >
        <div class="volume-info">
          <el-tag size="small" type="success">{{ name }}</el-tag>
          <code class="volume-path">{{ path }}</code>
        </div>
        <el-button
          size="small"
          type="primary"
          link
          @click="copyToClipboard(path)"
        >
          复制
        </el-button>
      </div>
      <span v-if="!instance.runtimeMetadata.volumePaths || Object.keys(instance.runtimeMetadata.volumePaths).length === 0">
        无命名卷
      </span>
    </el-descriptions-item>

    <!-- 生成时间 -->
    <el-descriptions-item label="元数据生成时间">
      {{ formatDateTime(instance.runtimeMetadata.generatedAt) || '-' }}
    </el-descriptions-item>
  </el-descriptions>
</el-card>
```

在 `<script setup>` 中新增辅助函数：

```javascript
import { ElMessage } from 'element-plus'

/**
 * 格式化容器 ID（截断显示前 12 位）
 */
function formatContainerId(containerId) {
  if (!containerId) return '-'
  return containerId.length > 12 ? containerId.substring(0, 12) + '...' : containerId
}

/**
 * 复制文本到剪贴板
 */
async function copyToClipboard(text) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败')
  }
}

/**
 * 格式化日期时间
 */
function formatDateTime(dateTimeStr) {
  if (!dateTimeStr) return ''
  try {
    const date = new Date(dateTimeStr)
    return date.toLocaleString('zh-CN')
  } catch {
    return dateTimeStr
  }
}
```

新增样式：

```css
.runtime-info-card {
  margin-top: 16px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.volume-path-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 4px 0;
}

.volume-info {
  display: flex;
  align-items: center;
  gap: 8px;
}

.volume-path {
  font-family: 'Courier New', monospace;
  font-size: 13px;
  color: #606266;
  word-break: break-all;
}
```

注意：以上代码需根据 detail.vue 的实际结构和现有辅助函数适配。如果 detail.vue 已有 `formatDateTime` 或 `copyToClipboard` 类似函数，则复用而非重复定义。

- [ ] **Step 3: 启动前端验证**

Run: `cd frontend && npm run dev`

打开实例详情页（docker-compose 部署的实例），验证：
- 运行时信息卡片显示
- 工作目录、项目名、容器ID、数据卷路径、生成时间正确展示
- 复制按钮功能正常

- [ ] **Step 4: Commit**

```bash
git add frontend/src/views/instance/detail.vue
git commit -m "feat: detail.vue 新增运行时元数据展示卡片"
```

---

## Task 13: 端到端集成验证

**Files:**
- 无修改，仅验证

- [ ] **Step 1: 启动后端**

Run: `cd backend && mvn -pl core spring-boot:run -am -q`

确认后端在 8080 端口启动成功，日志中看到：
- `SchemaMigrationRunner` 完成迁移
- `GameMetadataScanner` 扫描 l4d2.yml 成功

- [ ] **Step 2: 启动前端**

Run: `cd frontend && npm run dev`

确认前端在 3000/3001 端口启动。

- [ ] **Step 3: API 验证 - 获取部署配置**

调用：
```
GET http://localhost:8080/api/games/{l4d2_game_id}/deploy-config/docker-compose
```

Expected:
- 返回 200
- `data.variables` 包含 5 个变量（L4D2_PORT、L4D2_TICK、L4D2_VAC、L4D2_RCON_PASSWORD、CONTAINER_NAME）
- `data.composeTemplate` 非空
- `data.namedVolumes` 为 `["l4d2-data"]`

- [ ] **Step 4: UI 验证 - 创建实例流程**

打开浏览器进入创建实例页面：
1. 选择游戏"求生之路2"
2. 选择主机
3. 选择部署类型 "docker-compose"
4. 验证动态变量表单出现
5. 填写 RCON密码（必填项）
6. 提交创建实例

Expected: 实例创建成功，触发部署流程。

- [ ] **Step 5: 部署日志验证**

查看实例部署进度日志，验证：
- `[PRE_DEPLOY] 生成docker-compose.yml`
- `[PRE_DEPLOY] 生成 .env 文件`
- `[PRE_DEPLOY] 验证Compose配置`
- `[DEPLOY] 启动服务`
- `[DEPLOY] 获取运行时元数据`
- `[DEPLOY] 部署完成`

- [ ] **Step 6: 运行时元数据验证**

打开实例详情页：
1. 验证"运行时信息"卡片显示
2. 工作目录应为 `/opt/gameplatform/instances/{instanceId}`
3. 项目名应为 `game{instanceId}`
4. 容器ID 不为空
5. 数据卷路径应为 `/var/lib/docker/volumes/game{instanceId}_l4d2-data/_data`（或类似路径）
6. 生成时间不为空

- [ ] **Step 7: 数据库验证**

查询数据库：
```sql
SELECT id, instance_name, deploy_type, install_path, runtime_metadata
FROM game_instance
WHERE deploy_type = 'docker-compose'
ORDER BY id DESC LIMIT 1;
```

Expected:
- `install_path` 为 `/opt/gameplatform/instances/{id}`
- `runtime_metadata` 为 JSON 字符串，包含 `volumePaths`、`containerId`、`workDir`、`projectName`、`generatedAt` 字段

---

## Self-Review 自检结果

### 1. Spec 覆盖检查
- ✅ yml 配置结构 → Task 2 + Task 4
- ✅ GameYamlConfig 内部类 → Task 2
- ✅ GameMetadataScanner 增强 → Task 3
- ✅ game_instance.runtime_metadata 字段 → Task 1
- ✅ SchemaMigrationRunner → Task 1
- ✅ DockerComposeAdapter .env 生成 → Task 5
- ✅ DockerComposeAdapter 卷路径获取 → Task 6
- ✅ InstanceServiceImpl.buildDeployConfig 修改 → Task 7
- ✅ GET /games/{id}/deploy-config/{type} API → Task 8
- ✅ DeployVariableForm.vue → Task 10
- ✅ deploy.vue 集成 → Task 11
- ✅ detail.vue 运行时元数据展示 → Task 12
- ✅ L4D2 首个接入 → Task 4
- ✅ 端到端验证 → Task 13

### 2. 占位符扫描
- 无 "TBD"、"TODO"、"implement later" 等
- 所有代码步骤包含完整代码
- Task 11 和 Task 12 因依赖现有文件结构，标注"需根据实际结构适配"，但提供了具体代码片段

### 3. 类型一致性检查
- `runtimeMetadata` 字段类型 `Map<String, Object>` 在 GameInstance.java、InstanceServiceImpl、DockerComposeAdapter 中一致
- `variables` 类型 `List<Map<String, Object>>` 在 GameMetadataScanner、DeployConfigVO、DockerComposeAdapter.generateEnvFileContent 中一致
- `namedVolumes` 类型 `List<String>` 在 GameYamlConfig、DeployConfigVO、DockerComposeAdapter.getVolumeHostPaths 中一致
- `getDeployConfig(gameId, deployType)` 方法签名在 Controller、Service 接口、ServiceImpl 中一致

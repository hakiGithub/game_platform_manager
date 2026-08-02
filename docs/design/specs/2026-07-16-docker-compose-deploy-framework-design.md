# Docker Compose 部署框架设计

> 日期：2026-07-16
> 状态：已批准（待实施）
> 范围：通用 docker-compose 部署框架 + L4D2 首个接入

---

## 1. 背景与目标

### 1.1 背景

当前游戏平台支持 `docker`、`linuxgsm` 两种部署类型。`DockerComposeAdapter` 代码已完整存在但未被任何游戏配置启用。存在以下问题：

1. **L4D2 需要完整编排能力**：named volumes、networks、security_opt 等特性 `docker run` 难以表达
2. **变量管理缺失**：用户提供的 compose 脚本使用 `${L4D2_PORT:27015}` 变量语法，当前无管理机制
3. **卷宿主路径不可追溯**：部署后无法获取 named volume 的实际宿主路径，影响后续文件管理和备份

### 1.2 目标

- 构建通用的 docker-compose 部署框架，支持任意游戏通过 yml 配置接入
- 支持 `${VAR:default}` 变量语法，通过 `.env` 文件管理变量值
- 前端根据变量元信息动态渲染配置表单，必填项校验
- 部署后获取命名卷宿主路径，存入实例运行时元数据
- L4D2 作为首个接入游戏验证整个流程

### 1.3 非目标

- 不替换现有 `docker` 部署类型，三者并存
- 不引入模板引擎（FreeMarker/Thymeleaf），使用 Docker Compose 原生变量语法
- 不修改现有 docker/linuxgsm 实例的行为

---

## 2. 架构概览

### 2.1 数据流

```
l4d2.yml (源配置)
  │ dockerCompose.composeTemplate + variables + namedVolumes
  ▼
GameMetadataScanner (启动时解析)
  │
  ▼
game_metadata.deploy_config["docker-compose"] (JSON 存储)
  │
  ▼
前端 GET /api/games/{id}/deploy-config/docker-compose
  │ 渲染变量表单
  ▼
用户填写 → game_instance.config_info (用户变量值)
  │
  ▼
InstanceServiceImpl.buildDeployConfig() (合并模板+用户输入)
  │
  ▼
DockerComposeAdapter.preDeploy()
  │ 1. 生成 .env 文件（用户值+默认值）
  │ 2. 上传 compose 模板 + .env 到 workDir
  │ 3. docker compose config 校验
  │ 4. docker compose pull
  ▼
DockerComposeAdapter.deploy()
  │ 1. docker compose up -d
  │ 2. docker compose ps 验证
  │ 3. docker volume inspect 获取卷宿主路径
  │ 4. 组装 runtimeMetadata
  ▼
game_instance.runtime_metadata (JSON 存储)
  {volumePaths, containerId, workDir, projectName, generatedAt}
```

### 2.2 关键设计决策

1. **compose 模板不参与变量替换**：模板中的 `${VAR:default}` 由 Docker Compose 运行时从 `.env` 文件解析，后端只生成 `.env`，不修改模板
2. **变量元信息与模板分离**：`variables` 块仅用于前端表单渲染和后端校验
3. **runtimeMetadata 是通用容器**：存储所有运行时动态信息，不仅限于卷路径

---

## 3. 数据模型

### 3.1 yml 配置结构（以 L4D2 为例）

```yaml
game:
  code: l4d2
  name: 求生之路2
  deployTypes:
    - docker
    - linuxgsm
    - docker-compose          # 新增

  # 现有 docker/linuxgsm 配置保持不变
  docker: { ... }
  linuxgsm: { ... }

  # 新增 dockerCompose 配置块
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

### 3.2 变量元信息字段定义

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | 是 | 变量名，如 `L4D2_PORT`，对应 compose 中的 `${L4D2_PORT}` |
| `label` | string | 是 | 前端表单显示标签 |
| `type` | enum | 是 | `string` / `integer` / `boolean` / `password` |
| `defaultValue` | string | 否 | 默认值（字符串形式） |
| `required` | boolean | 否 | 是否必填，默认 false |
| `description` | string | 否 | 描述文本，前端显示为 tooltip |
| `hidden` | boolean | 否 | 是否在前端隐藏（高级选项），默认 false |

### 3.3 GameYamlConfig.java 新增内部类

```java
// GameYamlConfig.GameInfo 新增字段
private DockerComposeConfig dockerCompose;

@Data
public static class DockerComposeConfig {
    private String composeTemplate;
    private List<VariableDefinition> variables = new ArrayList<>();
    private List<String> namedVolumes = new ArrayList<>();
}

@Data
public static class VariableDefinition {
    private String name;
    private String label;
    private String type;           // string/integer/boolean/password
    private String defaultValue;
    private boolean required;
    private String description;
    private boolean hidden;
}
```

### 3.4 GameMetadataScanner.buildDeployConfig() 增强

在现有 `buildDeployConfig()` 方法中新增 docker-compose 子键的处理：

```java
if (gameInfo.getDockerCompose() != null) {
    DockerComposeConfig dc = gameInfo.getDockerCompose();
    Map<String, Object> composeConfig = new HashMap<>();
    composeConfig.put("composeTemplate", dc.getComposeTemplate());
    composeConfig.put("variables", dc.getVariables());
    composeConfig.put("namedVolumes", dc.getNamedVolumes());
    deployConfig.put("docker-compose", composeConfig);
}
```

### 3.5 game_instance 表新增字段

项目无 Flyway/Liquibase 迁移框架，`spring.sql.init.mode: never`。采用双重策略：

1. **修改 schema.sql**：在 `game_instance` 表 CREATE 语句中新增 `runtime_metadata TEXT` 列（供全新安装）
2. **启动时自动迁移**：在 `GamePlatformApplication` 启动时执行 `ALTER TABLE game_instance ADD COLUMN runtime_metadata TEXT`（SQLite 支持 `ALTER TABLE ADD COLUMN`，且对已存在该列的情况会报错，需先 PRAGMA table_info 检查或捕获异常忽略）

```sql
-- schema.sql 中 game_instance 表新增列
runtime_metadata TEXT,  -- 运行时元数据 JSON
```

### 3.6 GameInstance.java 新增字段

```java
/**
 * 运行时元数据（JSON对象）
 * 存储部署后产生的动态信息
 */
@TableField(typeHandler = JsonTypeHandler.class)
private Map<String, Object> runtimeMetadata;
```

### 3.7 runtimeMetadata JSON 结构

```json
{
  "volumePaths": {
    "l4d2-data": "/var/lib/docker/volumes/game1_l4d2-data/_data"
  },
  "containerId": "abc123def456",
  "workDir": "/opt/gameplatform/instances/1",
  "projectName": "game1",
  "generatedAt": "2026-07-16T23:50:00"
}
```

---

## 4. 后端组件设计

### 4.1 DockerComposeAdapter 增强

#### 4.1.1 preDeploy() 流程

1. 获取 InstanceHostInfo（实例+主机）
2. 创建 workDir：`mkdir -p /opt/gameplatform/instances/{instanceId}`
3. 从 config 读取 `composeTemplate`
4. 调用 `generateEnvFileContent(config)` 生成 `.env` 文件内容
5. 通过 SFTP 上传 `composeTemplate` → `{workDir}/docker-compose.yml`
6. 通过 SFTP 上传 `.env` 内容 → `{workDir}/.env`
7. 校验：`docker compose -p {projectName} config`
8. 拉取镜像：`docker compose -p {projectName} pull`（失败不阻止部署）

#### 4.1.2 deploy() 流程

1. 执行：`cd {workDir} && docker compose -p {projectName} up -d`
2. 等待 5 秒
3. 验证：`docker compose -p {projectName} ps --filter status=running`
4. **获取命名卷宿主路径**：调用 `getVolumeHostPaths(host, projectName, namedVolumes)`
5. **获取容器ID**：`docker compose -p {projectName} ps -q`
6. **组装 runtimeMetadata**：{volumePaths, containerId, workDir, projectName, generatedAt}
7. 更新实例：
   - `instance.setInstallPath(workDir)`
   - `instance.setRuntimeMetadata(metadata)`
   - `instance.setStartCommand("cd {workDir} && docker compose -p {projectName} start")`
   - `instance.setStopCommand("cd {workDir} && docker compose -p {projectName} stop")`

#### 4.1.3 .env 文件生成逻辑

新增私有方法 `generateEnvFileContent(Map<String, Object> config)`：

```java
private String generateEnvFileContent(Map<String, Object> config) {
    StringBuilder envContent = new StringBuilder();
    List<Map<String, Object>> variables = (List<Map<String, Object>>) config.get("variables");
    if (variables == null) return envContent.toString();

    for (Map<String, Object> var : variables) {
        String name = (String) var.get("name");
        String defaultValue = (String) var.getOrDefault("defaultValue", "");
        boolean required = Boolean.TRUE.equals(var.get("required"));

        Object userValue = config.get(name);
        String value = userValue != null ? userValue.toString() : defaultValue;

        if (value.isEmpty() && required) {
            throw new DeployException("必填变量 " + name + " 未提供值");
        }

        envContent.append(name).append("=").append(value).append("\n");
    }
    return envContent.toString();
}
```

#### 4.1.4 卷路径获取逻辑

新增私有方法 `getVolumeHostPaths(Host host, String projectName, List<String> namedVolumes)`：

```java
private Map<String, String> getVolumeHostPaths(Host host, String projectName,
                                                 List<String> namedVolumes) {
    Map<String, String> volumePaths = new HashMap<>();
    if (namedVolumes == null || namedVolumes.isEmpty()) {
        return volumePaths;
    }

    for (String volumeName : namedVolumes) {
        // Docker Compose 命名卷规则: {projectName}_{volumeName}
        String fullVolumeName = projectName + "_" + volumeName;
        SshUtil.CommandResult result = executeCommand(host,
                String.format("docker volume inspect %s -f '{{.Mountpoint}}'",
                        fullVolumeName));
        if (result.isSuccess() && !result.getOutput().trim().isEmpty()) {
            volumePaths.put(volumeName, result.getOutput().trim());
        } else {
            log.warn("获取卷宿主路径失败: {}", fullVolumeName);
        }
    }
    return volumePaths;
}
```

### 4.2 InstanceServiceImpl.buildDeployConfig() 修改

确保 docker-compose 子键配置被正确合并：

```java
private Map<String, Object> buildDeployConfig(GameInstance instance) {
    Map<String, Object> config = new HashMap<>();

    // 1. 注入游戏元数据部署模板
    GameMetadata game = gameMetadataMapper.selectById(instance.getGameId());
    if (game != null && game.getDeployConfig() != null) {
        String deployType = instance.getDeployType();
        Object typeConfig = game.getDeployConfig().get(deployType);
        if (typeConfig instanceof Map) {
            config.putAll((Map<String, Object>) typeConfig);
        }
    }

    // 2. 合并 configInfo（用户变量值）
    if (instance.getConfigInfo() != null) config.putAll(instance.getConfigInfo());

    // 3. 合并 portConfig
    if (instance.getPortConfig() != null) config.putAll(instance.getPortConfig());

    // 4. 注入实例元数据
    config.put("installPath", instance.getInstallPath());
    config.put("instanceId", instance.getId());
    config.put("gameCode", instance.getGameCode());
    if (instance.getRuntimeMetadata() != null) {
        config.put("runtimeMetadata", instance.getRuntimeMetadata());
    }

    return config;
}
```

### 4.3 前端 API 增强

新增接口供前端获取变量元信息：

```
GET /api/games/{gameId}/deploy-config/{deployType}
```

响应：
```json
{
  "code": 200,
  "data": {
    "composeTemplate": "...",
    "variables": [
      {"name": "L4D2_PORT", "label": "游戏端口", "type": "integer", "defaultValue": "27015", "required": false},
      {"name": "L4D2_RCON_PASSWORD", "label": "RCON密码", "type": "password", "required": true}
    ],
    "namedVolumes": ["l4d2-data"]
  }
}
```

---

## 5. 前端设计

### 5.1 创建实例流程增强

当用户选择 `docker-compose` 部署类型时：
1. 调用 `GET /api/games/{gameId}/deploy-config/docker-compose` 获取变量元信息
2. 渲染 `DeployVariableForm.vue` 组件，根据 variables 动态生成表单
3. 必填项校验通过后提交

### 5.2 DeployVariableForm.vue 组件

根据 `variables` 元信息动态渲染表单项：

| type | 渲染为 | 校验规则 |
|------|--------|----------|
| `string` | el-input | required 时必填 |
| `integer` | el-input-number | required 时必填，数值校验 |
| `boolean` | el-switch | 默认 false |
| `password` | el-input type=password | required 时必填 |

- `hidden: true` 的变量不渲染，但提交时使用 defaultValue
- `description` 显示为 tooltip 帮助图标
- `label` 作为表单标签

### 5.3 提交数据结构

```json
{
  "instanceName": "我的L4D2服务器",
  "hostId": 1,
  "gameId": 1,
  "deployType": "docker-compose",
  "portConfig": {"L4D2_PORT": 27015},
  "configInfo": {
    "L4D2_TICK": "100",
    "L4D2_VAC": "false",
    "L4D2_RCON_PASSWORD": "mysecret123",
    "CONTAINER_NAME": "l4d2-myserver"
  }
}
```

### 5.4 实例详情展示运行时元数据

新增"运行时信息"卡片：
- **数据卷路径**：列表展示 volumePaths，带"复制"按钮
- **容器ID**：截断显示前12位
- **工作目录**：显示 workDir
- **项目名**：显示 projectName
- **生成时间**：显示 generatedAt

### 5.5 文件管理集成

文件管理 API 可利用 `runtimeMetadata.volumePaths` 直接访问卷宿主路径，无需每次 `docker volume inspect`。

---

## 6. L4D2 集成

### 6.1 l4d2.yml 修改

- `deployTypes` 新增 `docker-compose`
- 新增 `dockerCompose` 配置块（composeTemplate + variables + namedVolumes）
- 现有 `docker` 和 `linuxgsm` 配置保持不变

### 6.2 完整 composeTemplate

使用用户提供的脚本，变量化为：
- `${L4D2_PORT:27015}` - 游戏端口
- `${L4D2_TICK:100}` - Tickrate
- `${L4D2_VAC:false}` - VAC 开关
- `${L4D2_RCON_PASSWORD}` - RCON 密码（必填）
- `${CONTAINER_NAME:l4d2}` - 容器名（隐藏高级选项）

---

## 7. 错误处理

| 场景 | 处理方式 |
|------|----------|
| 必填变量未填写（前端漏过） | 后端 `generateEnvFileContent()` 抛 `DeployException`，部署失败，日志提示变量名 |
| compose 模板语法错误 | `docker compose config` 校验失败，preDeploy 返回 false，日志输出错误 |
| 镜像拉取失败 | 不阻止部署（可能镜像已存在），deploy 阶段若容器启动失败再报错 |
| 卷 inspect 失败 | 记录警告日志，volumePaths 对应卷值为空，不阻止部署完成 |
| 容器启动失败 | deploy 阶段 `docker compose ps` 验证失败，获取容器日志返回给前端 |
| workDir 创建失败 | preDeploy 阶段 SSH mkdir 失败，返回 false |

---

## 8. 向后兼容性

- **现有 docker 实例**：不受影响，继续使用 DockerAdapter
- **现有 linuxgsm 实例**：不受影响
- **game_instance.runtime_metadata**：新字段，老实例为 null，不影响现有逻辑
- **l4d2.yml**：新增 dockerCompose 块，现有 docker/linuxgsm 块保持不变

---

## 9. 测试策略

### 9.1 后端单元测试

- `DockerComposeAdapterTest`：测试 `.env` 生成、卷路径获取、runtimeMetadata 组装
- `InstanceServiceImplTest`：测试 buildDeployConfig 对 docker-compose 类型的合并逻辑
- `GameMetadataScannerTest`：测试 yml 解析 dockerCompose 块

### 9.2 集成测试

- 端到端部署 L4D2 docker-compose 实例
- 验证 .env 文件内容正确
- 验证卷路径获取成功
- 验证 runtimeMetadata 正确存储

### 9.3 前端测试

- DeployVariableForm 组件渲染测试
- 必填校验测试
- 提交数据结构测试

---

## 10. 涉及文件清单

### 后端修改

| 文件 | 修改类型 | 说明 |
|------|----------|------|
| `backend/core/src/main/resources/games/l4d2.yml` | 修改 | 新增 dockerCompose 配置块 |
| `backend/core/src/main/java/com/gameplatform/config/GameYamlConfig.java` | 修改 | 新增 DockerComposeConfig、VariableDefinition 内部类 |
| `backend/core/src/main/java/com/gameplatform/util/GameMetadataScanner.java` | 修改 | buildDeployConfig() 新增 docker-compose 处理 |
| `backend/core/src/main/java/com/gameplatform/entity/GameInstance.java` | 修改 | 新增 runtimeMetadata 字段 |
| `backend/core/src/main/resources/db/schema.sql` | 修改 | game_instance 表新增 runtime_metadata 列 |
| `backend/core/src/main/java/com/gameplatform/adapter/DockerComposeAdapter.java` | 修改 | preDeploy/deploy 增强：.env 生成、卷路径获取、runtimeMetadata 组装 |
| `backend/core/src/main/java/com/gameplatform/service/impl/InstanceServiceImpl.java` | 修改 | buildDeployConfig() 注入 runtimeMetadata |
| `backend/core/src/main/java/com/gameplatform/controller/GameController.java` | 修改 | 新增 GET /games/{id}/deploy-config/{type} 端点 |
| `backend/core/src/main/java/com/gameplatform/vo/DeployConfigVO.java` | 新增 | 部署配置响应 VO |

### 前端修改

| 文件 | 修改类型 | 说明 |
|------|----------|------|
| `frontend/src/components/DeployVariableForm.vue` | 新增 | 动态变量表单组件 |
| `frontend/src/api/game.js` | 修改 | 新增 getDeployConfig 方法 |
| `frontend/src/views/instance/deploy.vue` | 修改 | 集成 DeployVariableForm |
| `frontend/src/views/instance/detail.vue` | 修改 | 新增运行时元数据展示卡片 |

### 数据库变更

| 文件 | 修改类型 | 说明 |
|------|----------|------|
| `backend/core/src/main/resources/db/schema.sql` | 修改 | game_instance 表 CREATE 语句新增 runtime_metadata 列 |
| `backend/core/src/main/java/com/gameplatform/config/SchemaMigrationRunner.java` | 新增 | ApplicationRunner，启动时执行 ALTER TABLE ADD COLUMN（幂等，已存在则跳过） |

---

## 11. 实施顺序建议

1. **数据层**：schema.sql + SchemaMigrationRunner + GameInstance.java + GameYamlConfig.java
2. **配置解析**：GameMetadataScanner.buildDeployConfig() 增强
3. **L4D2 配置**：l4d2.yml 新增 dockerCompose 块
4. **适配器增强**：DockerComposeAdapter（.env 生成、卷路径获取、runtimeMetadata）
5. **服务层**：InstanceServiceImpl.buildDeployConfig() 修改
6. **API 层**：GameController 新增端点
7. **前端组件**：DeployVariableForm.vue
8. **前端集成**：deploy.vue + detail.vue
9. **测试**：单元测试 + 集成测试

# Backend - AI Agent 协作指南

> 游戏服务器管理平台后端开发指南

---

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 17 | 开发语言 |
| Spring Boot | 3.2.5 | 核心框架 |
| Spring Security | 6.x | 安全认证 |
| Spring WebSocket | 6.x | 实时通信 |
| MyBatis-Plus | 3.5.6 | ORM框架 |
| SQLite | 3.45.2.0 | 嵌入式数据库 |
| Apache MINA SSHD | 2.12.1 | SSH连接 |
| Docker Java | 3.3.4 | Docker API |
| PF4J | 3.10.0 | 插件框架 |
| JWT | 0.12.5 | 令牌认证 |
| Hutool | 5.8.26 | 工具类库 |
| Lombok | 1.18.30 | 代码简化 |

---

## 项目结构

后端采用多模块结构：

```
backend/
├── api/                              # API 契约模块 (DTO/VO)
├── core/                             # 主应用模块
│   └── src/main/java/com/gameplatform/
│       ├── adapter/                  # 部署适配器
│       │   ├── DeployAdapter.java           # 适配器接口
│       │   ├── AbstractDeployAdapter.java   # 抽象适配器基类
│       │   ├── DeployAdapterFactory.java    # 适配器工厂
│       │   ├── LinuxGsmAdapter.java         # LinuxGSM适配器
│       │   ├── DockerAdapter.java           # Docker适配器
│       │   ├── LinuxGsmDockerAdapter.java   # LinuxGSM Docker适配器
│       │   └── DockerComposeAdapter.java    # Docker Compose适配器
│       ├── annotation/               # 自定义注解
│       ├── aspect/                   # AOP切面
│       ├── common/                   # 公共类
│       ├── config/                   # 配置类
│       ├── controller/               # 控制器层
│       ├── dto/                      # 数据传输对象
│       ├── entity/                   # 实体类
│       ├── handler/                  # 类型处理器
│       ├── listener/                 # 监听器
│       ├── mapper/                   # MyBatis Mapper
│       ├── service/                  # 服务层
│       │   └── impl/                 # 服务实现
│       ├── task/                     # 定时任务
│       ├── util/                     # 工具类
│       ├── vo/                       # 视图对象
│       ├── websocket/                # WebSocket处理器
│       └── GamePlatformApplication.java     # 启动类
│   └── src/main/resources/
│       ├── db/                       # 数据库脚本
│       ├── games/                    # 游戏元数据配置
│       ├── mapper/                   # Mapper XML
│       └── application.yml           # 主配置
├── plugin/                           # 插件 SDK 模块
│   └── src/main/java/com/gameplatform/plugin/
│       ├── extension/                # 扩展点接口
│       ├── context/                  # 插件上下文
│       ├── service/                  # 宿主能力服务接口
│       └── controller/               # 插件框架控制器
├── plugin-l4d2/                      # L4D2 游戏增强插件
│   ├── frontend/                     # 插件前端 (Vue 3 + Vite)
│   └── plugin-l4d2-core/             # 插件核心 JAR
└── scripts/                          # 重启脚本
```

---

## 代码规范

### 包命名

| 包名 | 用途 |
|------|------|
| `com.gameplatform` | 基础包 |
| `com.gameplatform.controller` | 控制器 |
| `com.gameplatform.service` | 服务接口 |
| `com.gameplatform.service.impl` | 服务实现 |
| `com.gameplatform.mapper` | Mapper接口 |
| `com.gameplatform.entity` | 实体类 |
| `com.gameplatform.dto` | 数据传输对象 |
| `com.gameplatform.vo` | 视图对象 |
| `com.gameplatform.util` | 工具类 |
| `com.gameplatform.config` | 配置类 |
| `com.gameplatform.adapter` | 部署适配器 |

### 类命名

| 类型 | 命名规则 | 示例 |
|------|----------|------|
| 控制器 | `XxxController` | `InstanceController` |
| 服务接口 | `XxxService` | `InstanceService` |
| 服务实现 | `XxxServiceImpl` | `InstanceServiceImpl` |
| Mapper | `XxxMapper` | `GameInstanceMapper` |
| 实体类 | `Xxx` (驼峰) | `GameInstance` |
| DTO | `XxxDTO` | `InstanceCreateDTO` |
| VO | `XxxVO` | `InstanceVO` |
| 工具类 | `XxxUtil` | `SshUtil` |
| 配置类 | `XxxConfig` | `JwtConfig` |
| 异常类 | `XxxException` | `BusinessException` |

### 代码风格

- 使用 **Lombok** 简化代码 (`@Data`, `@Builder`, `@RequiredArgsConstructor`)
- 使用 **MyBatis-Plus** 进行数据库操作
- 统一返回 `Result<T>` 包装响应
- 业务异常使用 `BusinessException`
- 使用 `@OperationLog` 注解记录操作日志
- 使用 `@Validated` 进行参数校验

---

## 开发指南

### 1. 新增实体

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("game_instance")
public class GameInstance extends BaseEntity {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String name;
    private String gameType;
    private String gameVersion;
    private Long hostId;
    private String deployPath;
    private Integer port;
    private Integer status;
    private Integer processId;
    private String startArgs;
    private String configPath;
    private String logPath;
    private Integer autoRestart;
    private LocalDateTime lastStartTime;
    private LocalDateTime lastStopTime;
    
    @TableField(typeHandler = JsonTypeHandler.class)
    private String extraConfig;
}
```

### 2. 新增 Controller

```java
@RestController
@RequestMapping("/api/instances")
@RequiredArgsConstructor
@Tag(name = "游戏实例管理", description = "游戏实例相关接口")
public class InstanceController {

    private final InstanceService instanceService;

    @GetMapping
    @Operation(summary = "获取实例列表")
    public Result<PageResult<InstanceVO>> list(PageQueryDTO query) {
        return Result.success(instanceService.list(query));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取实例详情")
    public Result<InstanceVO> getById(@PathVariable Long id) {
        return Result.success(instanceService.getById(id));
    }

    @PostMapping
    @Operation(summary = "创建实例")
    @OperationLog(module = "实例管理", type = "新增")
    public Result<Long> create(@Validated @RequestBody InstanceCreateDTO dto) {
        return Result.success(instanceService.create(dto));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新实例")
    @OperationLog(module = "实例管理", type = "更新")
    public Result<Void> update(@PathVariable Long id, @RequestBody InstanceUpdateDTO dto) {
        instanceService.update(id, dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除实例")
    @OperationLog(module = "实例管理", type = "删除")
    public Result<Void> delete(@PathVariable Long id) {
        instanceService.delete(id);
        return Result.success();
    }
}
```

### 3. 新增 Service

```java
public interface InstanceService {
    PageResult<InstanceVO> list(PageQueryDTO query);
    InstanceVO getById(Long id);
    Long create(InstanceCreateDTO dto);
    void update(Long id, InstanceUpdateDTO dto);
    void delete(Long id);
    void start(Long id);
    void stop(Long id);
    void restart(Long id);
}

@Service
@RequiredArgsConstructor
@Slf4j
public class InstanceServiceImpl implements InstanceService {

    private final GameInstanceMapper instanceMapper;
    private final HostMapper hostMapper;
    private final DeployAdapterFactory adapterFactory;

    @Override
    public PageResult<InstanceVO> list(PageQueryDTO query) {
        LambdaQueryWrapper<GameInstance> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(query.getName()), GameInstance::getName, query.getName())
               .eq(query.getHostId() != null, GameInstance::getHostId, query.getHostId())
               .eq(query.getStatus() != null, GameInstance::getStatus, query.getStatus())
               .orderByDesc(GameInstance::getCreateTime);
        
        Page<GameInstance> page = instanceMapper.selectPage(
            new Page<>(query.getCurrent(), query.getSize()), wrapper);
        
        return PageResult.of(page, this::convertToVO);
    }

    @Override
    @Transactional
    public Long create(InstanceCreateDTO dto) {
        GameInstance instance = GameInstance.builder()
            .name(dto.getName())
            .gameType(dto.getGameType())
            .hostId(dto.getHostId())
            .deployPath(dto.getDeployPath())
            .port(dto.getPort())
            .status(0)
            .build();
        
        instanceMapper.insert(instance);
        return instance.getId();
    }

    private InstanceVO convertToVO(GameInstance instance) {
        InstanceVO vo = new InstanceVO();
        BeanUtils.copyProperties(instance, vo);
        Host host = hostMapper.selectById(instance.getHostId());
        if (host != null) {
            vo.setHostName(host.getName());
            vo.setHostIp(host.getIp());
        }
        return vo;
    }
}
```

### 4. 新增 Mapper

```java
@Mapper
public interface GameInstanceMapper extends BaseMapper<GameInstance> {
    
    @Select("SELECT i.*, h.name as host_name, h.ip as host_ip " +
            "FROM game_instance i " +
            "LEFT JOIN host h ON i.host_id = h.id " +
            "WHERE i.deleted = 0 AND i.id = #{id}")
    InstanceVO selectByIdWithHost(@Param("id") Long id);
}
```

---

## 部署适配器开发

### 适配器接口

```java
public interface DeployAdapter {
    
    DeployType getType();
    
    boolean deploy(InstanceCreateDTO dto, DeployProgressCallback callback);
    
    boolean start(Long instanceId);
    
    boolean stop(Long instanceId);
    
    boolean restart(Long instanceId);
    
    boolean destroy(Long instanceId, boolean deleteFiles);
    
    InstanceStatus getStatus(Long instanceId);
    
    boolean healthCheck(Long instanceId);
}
```

### 新增部署方式

```java
@Component
@Slf4j
public class CustomAdapter extends AbstractDeployAdapter {

    @Override
    public DeployType getType() {
        return DeployType.CUSTOM;
    }

    @Override
    public boolean deploy(InstanceCreateDTO dto, DeployProgressCallback callback) {
        try {
            callback.onProgress("准备部署环境", 10);
            
            callback.onProgress("下载资源", 30);
            
            callback.onProgress("配置实例", 60);
            
            callback.onProgress("启动实例", 90);
            
            callback.onProgress("部署完成", 100);
            return true;
        } catch (Exception e) {
            callback.onError("部署失败: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean start(Long instanceId) {
        GameInstance instance = instanceMapper.selectById(instanceId);
        Host host = hostMapper.selectById(instance.getHostId());
        
        try (SshUtil ssh = new SshUtil(host.getIp(), host.getSshPort(), 
                                       host.getSshUsername(), host.getSshPassword())) {
            String command = buildStartCommand(instance);
            SshUtil.CommandResult result = ssh.execute(command);
            return result.getExitCode() == 0;
        }
    }

    @Override
    public boolean stop(Long instanceId) {
        // 停止逻辑
    }

    @Override
    public boolean restart(Long instanceId) {
        stop(instanceId);
        Thread.sleep(3000);
        return start(instanceId);
    }
}
```

---

## 游戏元数据配置

### 配置位置
`src/main/resources/games/`

### 配置格式

```yaml
game:
  code: minecraft                    # 游戏唯一编码
  name: Minecraft Server             # 游戏名称
  description: Minecraft 游戏服务端   # 游戏描述
  version: "1.20.1"                  # 版本
  icon: /icons/minecraft.png         # 图标

  deployTypes:                       # 支持的部署方式
    - docker
    - linuxgsm
    - native

  defaultPorts:                      # 默认端口
    game: 25565
    query: 25566
    rcon: 25575

  dependencies:                      # 环境依赖
    java: ">=17"
    memory: "2G"
    disk: "1G"

  docker:                            # Docker部署配置
    image: itzg/minecraft-server
    tag: latest
    env:
      EULA: "TRUE"
      VERSION: "1.20.1"
      MEMORY: "2G"
    volumes:
      - /data
      - /config
    ports:
      - "25565:25565"
    resources:
      memory: "3G"
    healthCheck:
      enabled: true
      test: "mc-health"
      interval: 30

  linuxgsm:                          # LinuxGSM部署配置
    script: mcserver
    gameCode: mc
    configFile: server.properties
    installDir: /home/mcserver

  configSchema:                      # 配置表单Schema
    properties:
      maxPlayers:
        type: integer
        default: 20
        minimum: 1
        maximum: 100
        label: 最大玩家数
        description: 服务器允许的最大玩家数量
        component: number
      difficulty:
        type: string
        enum: [peaceful, easy, normal, hard]
        default: normal
        label: 游戏难度
        component: select
      pvp:
        type: boolean
        default: true
        label: 启用PVP
        component: switch
      motd:
        type: string
        default: "A Minecraft Server"
        maxLength: 59
        label: 服务器描述
        component: input
    required:
      - maxPlayers
      - difficulty
    layout:
      columns: 2
      groups:
        - title: 基础设置
          fields: [maxPlayers, difficulty, motd]
        - title: 游戏玩法
          fields: [pvp, viewDistance, spawnProtection]

  customOperations:                  # 自定义操作
    - name: 备份存档
      command: backup
      description: 备份游戏存档和玩家数据
      icon: Download
      type: backup
      confirm: true
      confirmMessage: 确定要备份当前存档吗？
      async: true
      timeout: 600
    - name: 清理日志
      command: clean-logs
      description: 清理7天前的日志文件
      icon: Delete
      type: maintenance
      confirm: true
```

---

## 插件开发

### 插件模块结构

```
backend/
├── plugin/                        # 插件扩展点 SDK 模块（不含实现）
│   └── src/main/java/com/gameplatform/plugin/
│       ├── extension/
│       │   ├── GameEnhancementExtension.java  # 游戏增强扩展点接口（唯一扩展插槽）
│       │   ├── ExtensionClient.java           # 插件持久化入口接口（v2.0）
│       │   ├── ExtensionModel.java            # @ExtensionModel 注解（v2.0）
│       │   ├── Strategy.java                  # 存储策略枚举 SHARED/PLUGIN_ISOLATED/MODEL_ISOLATED（v2.0）
│       │   └── ListOptions.java               # 列表查询选项 Builder（v2.0）
│       ├── context/
│       │   ├── PluginContext.java             # 插件运行时上下文接口（v3.0 精简）
│       │   └── PluginContextHolder.java       # 上下文静态注册中心
│       ├── manager/
│       │   └── GamePlatformPluginManager.java # 插件管理器
│       ├── service/
│       │   ├── PluginFrameworkService.java    # 插件框架服务接口
│       │   └── impl/
│       │       └── PluginFrameworkServiceImpl.java
│       ├── controller/
│       │   ├── PluginFrameworkController.java # 插件API控制器
│       │   └── PluginPageController.java      # 插件页面控制器
│       ├── config/
│       │   └── PluginThymeleafConfig.java     # Thymeleaf配置
│       ├── loader/
│       │   └── PluginAutoLoader.java          # 插件自动加载器
│       └── vo/
│           ├── PluginManifestVO.java          # 插件清单VO
│           └── PluginStatusVO.java            # 插件状态VO
│
└── plugin-l4d2/                   # L4D2 插件示例
    ├── pom.xml
    └── src/main/
        ├── java/com/gameplatform/plugin/l4d2/
        │   ├── L4D2Plugin.java               # 插件主类
        │   ├── L4D2Extension.java            # 扩展点实现
        │   ├── config/
        │   │   └── L4D2Config.java           # 配置类
        │   ├── extension/                    # 扩展资源模型（v2.0）
        │   │   ├── AdminResource.java + AdminSpec.java
        │   │   ├── SystemMetricResource.java + SystemMetricSpec.java
        │   │   ├── PluginConfigResource.java + PluginConfigSpec.java
        │   │   └── DownloadTaskResource.java + DownloadTaskSpec.java
        │   ├── controller/                   # API控制器
        │   │   ├── RconController.java       # RCON控制台
        │   │   ├── MapController.java        # 地图管理
        │   │   ├── PluginManageController.java # 插件管理
        │   │   ├── MonitorController.java    # 性能监控（v2.0 已迁移到 ExtensionClient）
        │   │   ├── ServerConfigController.java # 服务器配置
        │   │   └── AdminController.java      # 管理员管理（v2.0 已迁移到 ExtensionClient）
        │   ├── service/                      # 业务服务
        │   │   ├── RconService.java          # RCON服务
        │   │   └── VpkParserService.java     # VPK解析服务
        │   ├── dto/                          # 数据传输对象
        │   ├── vo/                           # 视图对象
        │   └── util/
        │       └── VpkParser.java            # VPK解析工具
        └── resources/
            ├── plugin.properties             # 插件配置
            └── ui/                           # 前端资源
                ├── index.html                # Thymeleaf主入口
                ├── layout.html               # 布局模板
                ├── manifest.json             # 插件清单
                ├── css/styles.css            # 样式
                └── js/                       # JavaScript
                    ├── app.js                # Vue应用入口
                    ├── router.js             # 路由配置
                    ├── api.js                # API封装
                    └── views/                # 页面组件
```

> **v2.0 持久化变更**：旧的"DDL 脚本 + 表名白名单沙箱"机制已废弃。插件持久化统一通过 `@ExtensionModel` 注解 + `ExtensionClient` 接口完成（详见下方"插件数据库表"）。

### 插件主类

```java
public class MyPlugin extends Plugin {

    public MyPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    protected void start() {
        log.info("插件 {} 启动", getWrapper().getPluginId());
    }

    @Override
    protected void stop() {
        log.info("插件 {} 停止", getWrapper().getPluginId());
    }
}
```

### 扩展点实现

```java
@Extension
public class L4D2Extension implements GameEnhancementExtension {

    @Override
    public String getGameCode() {
        return "l4d2";
    }

    @Override
    public String getGameName() {
        return "求生之路2";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "L4D2 游戏服务器增强插件";
    }

    @Override
    public Map<String, Object> getManifest() {
        return Map.of(
            "gameCode", getGameCode(),
            "gameName", getGameName(),
            "version", getVersion(),
            "features", Map.of(
                "rconSupport", true,
                "mapManagement", true,
                "pluginManagement", true
            )
        );
    }

    @Override
    public void onInstanceCreate(Long instanceId, Map<String, Object> config) {
        // 实例创建时的初始化逻辑
    }

    @Override
    public void onInstanceStart(Long instanceId) {
        // 实例启动前的准备工作
    }

    @Override
    public void onInstanceStop(Long instanceId) {
        // 实例停止后的清理工作
    }
}
```

### 插件配置文件

```properties
plugin.id=plugin-l4d2
plugin.class=com.gameplatform.plugin.l4d2.L4D2Plugin
plugin.version=1.0.0
plugin.provider=GamePlatform
plugin.description=L4D2 游戏服务器增强插件
plugin.gameCode=l4d2
```

### 插件清单 (manifest.json)

```json
{
  "pluginId": "plugin-l4d2",
  "gameCode": "l4d2",
  "gameName": "求生之路2",
  "version": "1.0.0",
  "description": "L4D2 游戏服务器增强插件",
  "icon": "/plugin/l4d2/ui/assets/icon.png",
  "frontend": {
    "entry": "/plugin/l4d2/ui/index.html",
    "routes": [
      { "path": "/dashboard", "name": "仪表盘", "icon": "Odometer", "order": 1 },
      { "path": "/maps", "name": "地图管理", "icon": "Map", "order": 2 },
      { "path": "/plugins", "name": "插件管理", "icon": "Box", "order": 3 },
      { "path": "/rcon", "name": "控制台", "icon": "Monitor", "order": 4 },
      { "path": "/monitor", "name": "性能监控", "icon": "TrendCharts", "order": 5 },
      { "path": "/admins", "name": "管理员", "icon": "User", "order": 6 }
    ]
  },
  "api": {
    "basePath": "/api/plugin/l4d2"
  }
}
```

---

## 插件前端开发 (Wujie + Vue 3 + Vite)

### 技术方案

插件前端采用 **Wujie 微前端 + Vue 3 + Vite** 方式集成到主应用：

1. **Vue 3 + Vite**：插件独立构建，输出到 `plugin-l4d2-core/src/main/resources/ui/`
2. **Wujie 微前端**：主应用通过 Wujie 加载插件子应用，支持样式隔离与 JS 沙箱
3. **子应用路由**：使用 `createWebHashHistory`，通过 `${entry}#${path}` 形式被主应用加载
4. **pluginSDK**：子应用通过 `pluginSDK.ts` 获取主应用注入的 `instanceId`、`gameCode`、`token`、`apiBase` 等上下文

### 前端目录结构

```
plugin-l4d2/frontend/
├── src/
│   ├── api/                # API 封装
│   ├── assets/             # 静态资源
│   ├── components/         # 公共组件
│   ├── pages/              # 页面组件
│   ├── router/             # 路由配置
│   ├── utils/
│   │   └── pluginSDK.ts    # 插件 SDK
│   ├── App.vue             # 根组件
│   └── main.ts             # 入口文件
├── package.json
└── vite.config.ts          # Vite 配置，outDir 指向 plugin-l4d2-core/src/main/resources/ui
```

### 子应用入口 (main.ts)

```typescript
import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import pluginSDK from './utils/pluginSDK'

async function bootstrap() {
  await pluginSDK.init()

  const app = createApp(App)
  app.use(router)
  app.mount('#app')
}

bootstrap()
```

### pluginSDK 使用示例

```typescript
import pluginSDK from '@/utils/pluginSDK'

// 获取当前实例上下文
const { instanceId, gameCode, token, apiBase } = pluginSDK.getContext()

// 发起插件 API 请求
const response = await fetch(`${apiBase}/rcon/status`, {
  headers: { Authorization: `Bearer ${token}` }
})
```

### 访问路径

| 资源类型 | URL 格式 |
|----------|----------|
| 主页面 | `/plugin/{gameCode}/ui/` |
| 静态资源 | `/plugin/{gameCode}/ui/assets/xxx.js` |
| 清单文件 | `/api/plugin/{gameCode}/manifest` |

### 加载流程

```
1. 主应用扫描 plugins 目录下的插件 JAR
   ↓
2. 读取 manifest.json 获取菜单配置
   ↓
3. 用户点击插件菜单，主应用通过 Wujie 创建子应用
   ↓
4. 子应用 URL 格式：/plugin/{gameCode}/ui/#{route}
   ↓
5. PluginFrameworkController.getPluginResource 返回 index.html（no-store 缓存）
   ↓
6. 子应用初始化 pluginSDK 并渲染页面
```

---

## 插件数据库表（v2.0 Halo 风格统一宽表）

v2.0 起插件持久化废弃旧的"DDL 脚本 + 表名白名单沙箱"机制，统一采用 **Halo 风格的 JSON 宽表**。插件通过 `@ExtensionModel` 注解声明存储策略，通过子容器注入的 `ExtensionClient` 访问数据。

### 三层隔离策略

| 策略 | 物理表名 | 隔离粒度 | 适用场景 |
|------|---------|---------|---------|
| `SHARED` | `extensions` | 仅逻辑隔离（按 group_name+kind 过滤） | 跨插件共享的小数据量资源 |
| `PLUGIN_ISOLATED` | `ext_{pluginId}` | 插件间物理隔离 | 插件内多模型混居，单表足够 |
| `MODEL_ISOLATED` | `ext_{pluginId}_{kind}` | 模型级隔离 | 高频写入/大量数据的独立模型 |

### 统一宽表 DDL

所有策略共用以下结构（复合主键 `(name, group_name, kind)`，含乐观锁 `version` 列）：

```sql
CREATE TABLE {表名} (
    name                 TEXT    NOT NULL,
    group_name           TEXT    NOT NULL,         -- 框架填充 = pluginId
    kind                 TEXT    NOT NULL,         -- 框架填充 = 类名或注解 kind
    version              INTEGER NOT NULL DEFAULT 1, -- 乐观锁
    metadata             TEXT,                     -- JSON: labels/annotations/timestamps
    spec                 TEXT,                     -- JSON: 业务数据（强类型 T）
    status               TEXT,                     -- 高频过滤字段
    creation_timestamp   INTEGER,                  -- epochMilli
    update_timestamp     INTEGER,
    PRIMARY KEY (name, group_name, kind)
);
```

### L4D2 插件扩展资源（均 `Strategy.MODEL_ISOLATED`）

| Resource 类 | Spec 类 | 物理表 kind | name 规范 | 用途 |
|------------|---------|------------|----------|------|
| `AdminResource` | `AdminSpec` | `AdminResource` | `{instanceId}-{steamId}` | 管理员信息 |
| `SystemMetricResource` | `SystemMetricSpec` | `SystemMetricResource` | `{instanceId}-{timestamp}` | 监控历史数据 |
| `PluginConfigResource` | `PluginConfigSpec` | `PluginConfigResource` | `{instanceId}-{pluginName}` | SourceMod 插件配置 |
| `DownloadTaskResource` | `DownloadTaskSpec` | `DownloadTaskResource` | `{instanceId}-{timestamp}` | 下载任务 |

> 物理表名形如 `ext_plugin-l4d2_AdminResource`，由 `PluginSchemaManager.createSchemas` 在插件加载时自动建表。

### ExtensionClient 使用示例

```java
// 注入（在插件子容器中自动注册为单例）
private final ExtensionClient extensionClient;

// 创建
AdminResource resource = new AdminResource();
resource.setName(instanceId + "-" + steamId);  // name 规范
AdminSpec spec = new AdminSpec();
spec.setInstanceId(instanceId);
spec.setSteamId(steamId);
resource.setSpec(spec);
extensionClient.create(resource);

// 列表查询（按 spec 字段过滤）
ListOptions opts = ListOptions.builder()
        .specFilter("$.instanceId", "=", instanceId)
        .createdAfter(startEpochMilli)
        .orderBy("creation_timestamp")
        .limit(10000)
        .build();
List<AdminResource> list = extensionClient.list(AdminResource.class, opts);

// 更新（乐观锁）
resource.setStatus("INACTIVE");
extensionClient.update(resource);  // 校验 version，冲突抛 OptimisticLockException

// 删除
extensionClient.delete(AdminResource.class, name);
```

### 身份隔离与安全

- `ExtensionClientImpl` 构造时绑定 `pluginId`，所有方法由 `ExtensionRouter` 强制注入 `group_name = ?` 过滤，插件**无法**访问其他插件或主应用数据。
- 复合主键 `(name, group_name, kind)` 保证同表内 name 唯一，重复创建抛 `DuplicateExtensionException`。
- 乐观锁 `version` 列，并发更新冲突抛 `OptimisticLockException`。

---

## 插件 API 接口

### 统一规范

- **路径前缀**: `/api/plugin/{gameCode}`
- **认证方式**: 使用主应用的 JWT Token
- **请求头**: `Authorization: Bearer {token}`
- **响应格式**: 统一使用 `Result<T>` 包装

### L4D2 插件 API

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/plugin/l4d2/rcon/status` | POST | 获取服务器状态 |
| `/api/plugin/l4d2/rcon/execute` | POST | 执行 RCON 命令 |
| `/api/plugin/l4d2/rcon/change-map` | POST | 切换地图 |
| `/api/plugin/l4d2/maps/list` | GET | 获取地图列表 |
| `/api/plugin/l4d2/plugins/list` | GET | 获取插件列表 |
| `/api/plugin/l4d2/monitor/status` | GET | 获取监控状态 |
| `/api/plugin/l4d2/admins/list` | GET | 获取管理员列表 |

---

## 插件打包部署

### 编译命令

```bash
# 编译插件
cd backend
mvn clean package -pl plugin-l4d2 -am -DskipTests

# 生成的 JAR 文件
# plugin-l4d2/target/plugin-l4d2-1.0.0.jar
```

### 部署步骤

1. 将 JAR 文件放入主应用的 `plugins` 目录
2. 重启主应用（或使用热加载 API）
3. 插件自动加载并注册 API 路由

### 热加载 API

```bash
# 重新加载插件
POST /api/plugins/{pluginId}/reload

# 启动插件
POST /api/plugins/{pluginId}/start

# 停止插件
POST /api/plugins/{pluginId}/stop
```

---

## 数据库设计

### 核心表

| 表名 | 说明 |
|------|------|
| `user` | 用户表 |
| `host` | 主机表 |
| `game_metadata` | 游戏元数据表 |
| `game_instance` | 游戏实例表 |
| `plugin_info` | 插件信息表 |
| `backup_record` | 备份记录表 |
| `operation_log` | 操作日志表 |

### 通用字段

所有表包含以下字段:

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT | 主键ID |
| `create_time` | DATETIME | 创建时间 |
| `update_time` | DATETIME | 更新时间 |
| `create_by` | VARCHAR(64) | 创建人 |
| `update_by` | VARCHAR(64) | 更新人 |
| `deleted` | TINYINT | 逻辑删除标识 (0-未删除, 1-已删除) |
| `remark` | VARCHAR(500) | 备注 |

### 实体基类

```java
@Data
public class BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    
    @TableField(fill = FieldFill.INSERT)
    private String createBy;
    
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updateBy;
    
    @TableLogic
    private Integer deleted;
    
    private String remark;
}
```

---

## WebSocket 处理

### SSH WebSocket

```java
@Component
@Slf4j
public class SshWebSocketHandler extends TextWebSocketHandler {

    private final Map<String, SshConnection> connections = new ConcurrentHashMap<>();

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        WsMessage wsMessage = JSON.parseObject(message.getPayload(), WsMessage.class);
        
        switch (wsMessage.getType()) {
            case "connect":
                handleConnect(session, wsMessage);
                break;
            case "input":
                handleInput(session, wsMessage);
                break;
            case "resize":
                handleResize(session, wsMessage);
                break;
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SshConnection conn = connections.remove(session.getId());
        if (conn != null) {
            conn.close();
        }
    }
}
```

---

## 测试规范

### 测试类命名

- 单元测试: `XxxTest`
- 集成测试: `XxxIT`

### 测试基类

```java
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class BaseTest {
    
    @Autowired
    protected WebApplicationContext context;
    
    protected MockMvc mockMvc;
    
    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }
}
```

### 测试示例

```java
@DisplayName("实例服务测试")
class InstanceServiceTest extends BaseTest {

    @Autowired
    private InstanceService instanceService;

    @Test
    @Order(1)
    @DisplayName("创建实例")
    void testCreate() {
        InstanceCreateDTO dto = InstanceCreateDTO.builder()
            .name("test-instance")
            .gameType("minecraft")
            .hostId(1L)
            .deployPath("/opt/test")
            .port(25565)
            .build();
        
        Long id = instanceService.create(dto);
        assertNotNull(id);
    }

    @Test
    @Order(2)
    @DisplayName("查询实例列表")
    void testList() {
        PageQueryDTO query = new PageQueryDTO();
        query.setCurrent(1);
        query.setSize(10);
        
        PageResult<InstanceVO> result = instanceService.list(query);
        assertNotNull(result);
        assertTrue(result.getTotal() > 0);
    }
}
```

---

## 常用命令

```bash
# 编译
mvn clean compile

# 运行
mvn spring-boot:run

# 指定配置运行
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 测试
mvn test

# 跳过测试打包
mvn clean package -DskipTests

# 代码覆盖率
mvn jacoco:report

# 依赖分析
mvn dependency:tree

# 检查依赖更新
mvn versions:display-dependency-updates
```

---

## 范围规约（ADR-0002）

> 主应用 `core/` 与插件 `plugin-{gameCode}/` 严格隔离，详见 [ADR-0002](../docs/design/adr/0002-main-app-plugin-scope-isolation.md)。

| 维度 | 主应用 `core/` 允许 | 主应用 `core/` 禁止 |
|------|---------------------|---------------------|
| **配置文件** | `game-platform.*` 命名空间（插件框架自身配置） | `plugin.{gameCode}` 前缀的插件业务配置块 |
| **数据库迁移** | 核心表（`host`/`game_instance`/`task_record`）+ 插件框架表（`extension_resource`/`plugin_extension`） | `{gameCode}_*` 前缀的插件专属业务表 |
| **代码依赖** | 插件 SDK（`backend/plugin/` 模块的扩展点接口） | `import com.gameplatform.plugin.{gameCode}.*` 插件业务包 |
| **游戏元数据** | `resources/games/{gameCode}.yml`（部署向导输入，例外允许） | — |

**插件侧自治**：
- 插件配置由 `@ConfigurationProperties` 类的字段 Java 默认值自负；需要覆盖时由环境变量处理
- 插件表由 ExtensionClient 的 `ext_plugin_{pluginId}_{resource}` 模式通过 `DdlTemplate` 动态建表
- 插件不实现 standalone 独立运行模式（ADR-0003 已废弃）

**强制手段**：文档约定 + 代码评审，不加预提交脚本。

---

## 注意事项

1. **SSH连接**: 使用 Apache MINA SSHD，支持密码和密钥认证
2. **文件传输**: 使用 SFTP 协议
3. **WebSocket**: 用于实时日志和终端
4. **数据库**: SQLite 嵌入式数据库，无需额外安装
5. **插件热加载**: 支持插件动态加载和卸载
6. **游戏配置**: 通过 YAML 元数据自动生成表单
7. **敏感数据**: SSH密码等使用AES加密存储

---

## 相关文档

- [项目总览](../README.md)
- [AI Agent 协作指南](../AGENTS.md)
- [前端开发指南](../frontend/AGENTS.md)
- [API接口文档](../docs/api/api-doc.md)
- [架构文档](../docs/architecture/ARCHITECTURE.md)
- [ADR 决策记录](../docs/design/adr/README.md)
  - [ADR-0001 插件菜单归属](../docs/design/adr/0001-plugin-menu-ownership.md)
  - [ADR-0002 主应用与插件范围隔离规约](../docs/design/adr/0002-main-app-plugin-scope-isolation.md)
  - [ADR-0003 废弃 plugin-l4d2-standalone](../docs/design/adr/0003-deprecate-plugin-l4d2-standalone.md)
- [插件开发指南](../.trae/skills/gameplatform-plugin-dev/SKILL.md)
- [UI 测试文档](../docs/testing/ui-testing/README.md)

---

*最后更新: 2026-08-03*

# 插件开发陷阱与要点

> 对齐版本：v3.6.0（ADR-0001 菜单归属权迁移；v3.6.0 增补独立构建/子容器陷阱）

## 1. 菜单机制（ADR-0001，v3.1.0 起变更）

**菜单由插件声明，宿主仅做校验与序列化。**

- 插件通过 `GameEnhancementExtension.getMenus()` 返回 `List<PluginMenuDeclaration>` 声明菜单清单。
- 宿主 `PluginFrameworkServiceImpl.buildMenusFromDeclarations()` 校验 path 唯一性、补全 `requireInstance` 默认值（null→true），序列化为 `PluginManifestVO.MenuConfig`。
- 宿主**不预置任何默认菜单**（不再有仪表盘/系统监控/服务器信息等固定菜单），插件需显式声明完整菜单列表（参考 plugin-l4d2 的 17 项菜单）。

**ADR-0001 废弃项（v3.1.0）**：
- `getManifest()` 的 `features` 字段（`rcon`/`mapManagement`/`playerManagement`）已废弃，宿主不再读取。
- `getManifest()` 中的 `frontend.menus` 字段（如存在）会被忽略。
- 宿主 `buildDefaultMenus()` 方法已删除。
- 主应用从 `manifest.frontend.menus` 的 path 集合推导 capabilities（不再依赖 features）。
- 旧插件需将 `features` 迁移到 `getMenus()` 显式声明。

**PluginMenuDeclaration 字段约束**：
- `path` 必填，同插件内必须唯一，重复或为空抛 `IllegalStateException`。
- `requireInstance` 默认 `true`；纯资源页（如地图中心）显式设 `Boolean.FALSE`。
- 推荐显式设置 `requireInstance`，避免依赖默认值导致误判。

## 2. 前端路由 path 必须与 getMenus() 声明的 path 对齐

子应用 `frontend/src/router/index.ts` 的路由 path 必须与 `getMenus()` 声明的菜单 path **完全一致**，否则点击菜单白屏。例如 `getMenus()` 声明 `/map-center`，路由表必须有对应 `/map-center`。

## 3. 前端两运行模式（ADR-0003，v3.3.0 起变更）

`detectMode()`（`frontend/src/utils/runtime.ts`）：
- `wujie`：`window.__POWERED_BY_WUJIE__` 或 `props.mode==='wujie'` → `createWebHashHistory()`
- `dev`：其余 → `createWebHistory('/')`

`standalone` 模式已废弃（ADR-0003）。主应用可通过 Wujie `props.route` 指定子应用初始路由。

## 4. 前后端通信（Wujie）

- 初始数据：主应用经 Wujie `props` 下发（instanceId、token 等）。
- 运行时事件：`window.$wujie.bus.$on/$emit`。
- 旧 postMessage 通信仅兼容，新代码用 Wujie bus。

## 5. PluginContext 不再持数据访问

v3.0+ 起 `PluginContext` 仅持元数据（`getPluginId`/`getGameCode`/`getGameName`/`getVersion`/`getCustomProperties`）。持久化一律走子容器注入的 `ExtensionClient`。

## 6. ExtensionClient 身份隔离

- 绑定 pluginId，所有方法自动注入 `group_name = pluginId` 过滤。
- 只能访问本插件已声明的 `@ExtensionModel` 资源类。
- 插件 A 无法访问插件 B 数据，无例外。
- `update` 需带读到的 `version`（乐观锁）；并发改写抛 `OptimisticLockException`，需重新 `get`。

## 7. 文件路径安全（InstanceFileService）

- `relativePath` 相对实例"游戏数据根目录"，用正斜杠。
- Native/LinuxGSM：根目录 = `instance.installPath`
- Docker 类：根目录 = 容器内工作目录（解析链：`configInfo.containerWorkDir` → `workDir` → 类型默认值 → 游戏元数据 `deployConfig.<deployType>.workingDir` 回退，见 `host_services.md` §3.1）
- **禁止 `..`**，越界抛 `IllegalArgumentException`。
- **Docker 类文件操作坑**：
  - `docker cp` 的目标/源**必须在宿主机**（`/tmp/.gp-*`）。传本地（Windows）路径会被 docker CLI 解析成容器引用，报 `copying between containers is not supported`——下载用 `downloadFileToMemory`（内部已处理），不要自写 docker cp 到本地
  - `docker cp` 不会自动创建父目录，写入前需先 `mkdir -p`
  - compose 容器重建（宿主机重启）后容器 ID 变化：宿主对账会自动重新匹配并写回（容器名前缀/显式 container_name），插件侧无需处理；`ContainerIdResolver` 对 compose 动态查询优先，不盲信缓存的 containerId

## 8. 异常层级

```
PluginException (基类, 持 pluginId)
├── PluginLoadException          加载失败（DDL/依赖）
├── PluginConfigException        配置缺失
└── PluginPathConflictException  控制器路径冲突（含 conflictPath + existingPluginId）

ExtensionStoreException (扩展资源存储基类)
├── DuplicateExtensionException  create 时 name 冲突
├── OptimisticLockException      update 时 version 不匹配
└── ExtensionNotFoundException   get/update/delete 目标缺失
```

> ⚠️ 文档历史曾误用 `PluginDataAccessException`，该类**不存在**于 backend。数据访问异常用 `ExtensionStoreException` 体系。

## 9. 任务 Handler 约束

- Handler 必须**无状态**，状态通过 `TaskContext` 传递。
- `TaskHandlerExtension` 标 `@Component`，`getTaskHandlers()` 返回的 Map 在构造时一次性创建缓存。
- `source` 由框架自动填 gameCode 大写，插件不要手动设。
- `execute` 循环必须定期检查 `isCancelled()`/`isTimeout()`，命中即 return `TaskResult.failure`。
- 不要在 `execute` 吞 `InterruptedException`（重设中断标志退出）。
- 不要在 `finally` 调 `reportProgress`（终态已强制刷盘）。
- payload 序列化上限 64KB；TaskResult 数据上限 256KB；日志每任务最多 500 条。
- `maxRetryCount`：幂等任务=3，有副作用任务=1。

## 10. 控制器路径冲突

两个插件注册相同 URL → `PluginPathConflictException` 阻止加载。规避：路径严格按 `/api/plugin/{gameCode}/` 前缀，`gameCode` 全局唯一。

## 11. standalone 模式（ADR-0003，v3.3.0 起废弃）

`plugin-l4d2-standalone` 已物理删除。新增插件**不应**实现 standalone 独立运行模式。前端只支持 wujie + dev 两种模式。需要独立部署的用户可部署完整主应用。详见 [ADR-0003](../../../../docs/design/adr/0003-deprecate-plugin-l4d2-standalone.md)。

## 12. 范围隔离（ADR-0002，v3.2.0 起变更）

**主应用 `core/` 与插件严格隔离，插件配置和表自管。**

- **配置**：插件 `@ConfigurationProperties` 类的字段 Java 默认值即配置来源；**禁止**在主应用 `application.yml` 写 `plugin.{gameCode}` 块。需要覆盖默认值时由环境变量处理。
- **表**：插件表由 ExtensionClient 的 `ext_plugin_{pluginId}_{resource}` 模式通过 `DdlTemplate` 动态建表；**禁止**在主应用 `db/migration/` 写 `{gameCode}_*` 前缀的插件专属表。
- **代码**：主应用 `core/` 不得 `import com.gameplatform.plugin.{gameCode}.*`。
- **例外**：游戏元数据 `core/resources/games/{gameCode}.yml` 由主应用维护（部署向导输入），不属于插件业务。

详见 [ADR-0002](../../../../docs/design/adr/0002-main-app-plugin-scope-isolation.md)。

## 13. 部署与热加载陷阱（v3.5.0）

- **Windows jar 文件锁**：运行中的后端持有 `plugins/*.jar`（URLClassLoader），**覆盖/重命名都会失败**（"Device or resource busy"）。必须先 `DELETE /api/pf4j/plugins/{id}`（unload 关闭 classloader 释放锁）再覆盖，最后 `POST /api/pf4j/plugins/load?jarName=...` 重新加载。`scripts/deploy-plugin.sh` 已封装完整流程。
- **热部署保留任务历史**：卸载接口默认 `purgeTasks=true` 会物理删除插件 source 的任务记录与日志（适合真正移除插件）。热部署/重载必须传 `?purgeTasks=false`——运行中任务仍会被协作式取消、旧 Handler 仍会注销（旧 classloader 必须释放），但爬取统计等历史保留。
- **热加载后插件 API 500 / "没有 GameEnhancementExtension"**：宿主 `loadPlugin` 若在插件 STARTED **之前**查扩展点，PF4J 的 per-plugin 扩展查找返回空 → Spring 子容器不创建 → 控制器不注册。宿主 v3.5.0 已修（先 start 再查 + whichPlugin 回退）；若日志再现此行说明宿主是旧版，需重启升级宿主。
- **端口通 ≠ 应用就绪**：后端健康探测过早返回（DispatcherServlet 初始化期间插件可能尚未被启动加载器装载），脚本需轮询插件出现在 `GET /api/pf4j/plugins` 列表后再操作。
- **部署方式选择**：只改插件代码 → `deploy-plugin.sh`（免重启）；改主应用（core/api/plugin 模块）→ `start-all.sh` 重启（启动时自动加载插件）。

## 14. 版本与维护

- 本 SKILL 目录（`references/`）为插件开发文档唯一权威源（v3.1.0 起）。
- 主版本变更（破坏性 API 改动）→ 在 `references/changelog.md` 升版本号并记录。
- minor 变更只更 changelog。
- 接口签名以 `backend/plugin/` 源码为权威，新增即补登记到对应 `references/` 文件。

## 15. 独立仓库构建插件（v3.6.0，源自 plugin-dst 实战）

插件可以不放在平台仓库 `backend/` 下，用 `examples/plugin-mygame/pom.xml` 那样的**独立 pom**（无 parent）构建。这条路有四个坑：

1. **provided 依赖需先安装**：`game-platform-plugin` / `game-platform-api` 不在中央仓库，先在平台仓库 `backend/` 下执行 `mvn -pl api,plugin install -DskipTests`（注意 settings.xml 可能配置了非默认 localRepository，如 `D:\dev\maven_repo`）。
2. **必须显式开 `-parameters`**（`<parameters>true</parameters>`）。根因：插件子容器注入宿主服务（如 `InstanceQueryService`）时存在两个候选 bean——子容器注册的 `instanceQueryService` 单例与主容器的 `instanceQueryServiceImpl`——Spring 依赖**构造参数名与 bean 名匹配**（`instanceQueryService`）消歧；Spring 6.1（Boot 3.2）移除了字节码 LVT 参数名回退，只有 `-parameters` 编译出的 `MethodParameters` 可用。平台仓库内的插件经 `spring-boot-starter-parent` 默认开启，独立 pom 无 parent 必须自带。缺失症状：子容器创建抛 `UnsatisfiedDependencyException: expected single matching bean but found 2`。
3. **lombok 需自带**：平台父 pom 全局 `provided` lombok，独立 pom 要自己声明。
4. **改 pom/编译配置后必须 `clean package`**：maven-compiler-plugin 按源文件时间戳增量编译，只改 pom 不会重编译，旧 class 直接打进 jar（症状：明明加了 `-parameters` 仍报二义性，`javap -v` 查 class 无 `MethodParameters` 属性）。

## 16. 子容器创建失败会"静默继续"（v3.6.0）

宿主 `PluginSpringContextFactory` 在插件任一 **bean 创建失败**时仅记 ERROR 日志"插件 [x] Spring 上下文创建失败，继续加载"，**不阻断加载**。此时：

- 插件状态仍显示 **STARTED**，`GET /api/pf4j/plugin/{gameCode}/manifest` 正常（扩展点是 PF4J 的，不依赖子容器）；
- 但**所有控制器未注册** → 插件 API 全部 500，全局异常日志是 `NoResourceFoundException: No static resource plugin/{gameCode}/...`（请求落到静态资源处理器）。

**排查**：看到"No static resource plugin/..."先 grep 后端日志 `Spring 上下文创建失败` 拿真实堆栈，不要被插件 STARTED 迷惑。v3.5.0 的"热加载后没有 GameEnhancementExtension"是另一条路径（扩展点未发现），症状相同、根因不同。

## 17. 子容器 `@Scheduled` 疑似不生效（v3.6.0，待运行时确认）

`PluginSpringContextFactory` 创建子容器（`AnnotationConfigApplicationContext` + `scan` + `refresh`）时**未 `@EnableScheduling`、未注册 `ScheduledAnnotationBeanPostProcessor`**；`@EnableScheduling` 只在主容器（`GamePlatformApplication`）。Spring 的 BPP 不跨容器生效（`onApplicationEvent` 校验 `applicationContext` 同一性），按标准语义**插件子容器里的 `@Scheduled` 不会被调度**——但 plugin-l4d2 大量使用 `@Scheduled`（MonitorCollectorService 每秒采集、MapCrawlerScheduler 周一爬取等），仓库内无验证记录，**疑似这些周期任务从未触发**（或由其他机制意外生效）。

- 新插件需要定时能力：**自管 `ScheduledExecutorService`**（`@PostConstruct` 起、`@PreDestroy` 停，守护线程），不要依赖子容器 `@Scheduled`。
- 加载插件后实测 l4d2 的 `@Scheduled` 是否触发，把结论回填本节。

## 18. 无 RCON 游戏的控制台通道（v3.6.0，源自 plugin-dst）

DST 等游戏**没有 RCON**，游戏内命令唯一注入通道是进程 stdin。linuxgsm-docker 形态下 LGSM 控制台跑在容器内 tmux 会话（会话名 = `LGSM_GAMESERVERNAME`，如 `dstserver`）：

- **fire-and-forget 命令**（广播/关服）：`FileAccessService.executeCommand(hostId, "docker exec <容器> tmux send-keys -t <会话> '<lua>' Enter", timeoutMs)`，成功即返回。
- **需要输出的命令**（玩家列表等）：注入带唯一标记的 `print` 包裹命令，轮询 `tail` 分片日志，截取两个标记之间的行作为输出；封装为 TaskHandler（超时/取消/互斥白拿），同实例互斥避免输出交叉。
- **长命令不走适配器**：`InstanceQueryService.executeCommand` 经适配器内嵌 **60s 超时**，LGSM `update`（steamcmd，1~5 分钟）会超时；自拼 `timeout N docker exec --user linuxgsm -w /app <容器> ./<shortname> <命令>` 走 `FileAccessService` 带长超时。
- 容器名解析对齐 `LinuxGsmDockerAdapter.getContainerName`：`configInfo.containerName` → `runtimeMetadata.containerName`。

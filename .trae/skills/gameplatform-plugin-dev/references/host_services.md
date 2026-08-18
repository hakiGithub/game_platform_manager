# 宿主服务面（Host Services）

> 对齐版本：v3.7.0（ADR-0001/ADR-0006/ADR-0009）｜ 权威源：`backend/plugin/` 源码

除持久化外，宿主还通过以下 SPI 向插件暴露主机/实例/文件能力。实现由宿主核心模块提供，通过插件 Spring 子容器注入。

## 1. HostQueryService — 主机查询

```java
public interface HostQueryService {
    HostResourceVO getHostResourceInfo(Long hostId);  // CPU/内存/磁盘/网络监控
    HostVO getHostById(Long hostId);                  // IP/端口/SSH 凭据等
}
```

## 2. InstanceQueryService — 实例查询与控制

```java
public interface InstanceQueryService {
    InstanceVO getInstanceById(Long id);
    List<InstanceVO> getInstancesByHostId(Long hostId);
    List<InstanceVO> getInstancesByGameId(Long gameId);
    List<InstanceVO> listByGameCode(String gameCode);
    InstanceVO getInstanceStatus(Long id);            // 刷新并返回最新状态
    boolean startInstance(Long id);
    boolean stopInstance(Long id);
    boolean restartInstance(Long id);
    String getInstanceLogs(Long id, int lines);       // 最近日志
    String executeCommand(Long id, String command);   // 实例控制台执行命令
}
```

> 不含实例创建/更新/删除（属宿主核心职责）。

## 3. InstanceFileService — 实例感知文件 SPI

调用方传 `(instanceId, relativePath)`，实现层根据实例 `deployType` **自动路由**到 SFTP（Native/LinuxGSM）或 `docker exec/cp`（Docker 类）。`relativePath` 相对于实例"游戏数据根目录"，使用正斜杠。

```java
public interface InstanceFileService {
    // 文本读写
    String readTextFile(long instanceId, String relativePath);
    String readTextFile(long instanceId, String relativePath, Charset charset);
    void   writeTextFile(long instanceId, String relativePath, String content);

    // 二进制 / 范围
    byte[] downloadFileToMemory(long instanceId, String relativePath);
    byte[] getFileBytes(long instanceId, String relativePath, long offset, long length);

    // 上传 / 下载
    void uploadLocalFile(long instanceId, String relativePath, String localPath);
    void downloadFile(long instanceId, String relativePath, String localPath);

    // 文件管理
    void deleteFile(long instanceId, String relativePath);
    void moveFile(long instanceId, String oldRel, String newRel);
    void copyFile(long instanceId, String srcRel, String dstRel);
    boolean exists(long instanceId, String relativePath);
    FileInfo getFileInfo(long instanceId, String relativePath);

    // 目录
    List<FileInfo> listFiles(long instanceId, String relativePath);
    void createDirectory(long instanceId, String relativePath);
    void deleteDirectory(long instanceId, String relativePath, boolean recursive);
    void copyDirectory(long instanceId, String srcRel, String dstRel);  // 目标先清空

    // 流式增量（日志 tail）
    long tailFile(long instanceId, String relativePath, long offset, Charset charset, Consumer<String> lineConsumer);

    // 摘要
    String computeDigest(long instanceId, String relativePath, String algorithm); // MD5/SHA-1/SHA-256
    default String md5(long instanceId, String relativePath) { return computeDigest(instanceId, relativePath, "MD5"); }
}
```

> ⚠️ **路径安全**：禁止 `..` 跳出根目录，越界抛 `IllegalArgumentException`。
> **路径归一化**：`AbstractInstanceFileService.validateRelativePath` 必须剥离前导 `./` 与段内 `/./`，确保路径字符串归一化。

### 3.1 Docker 类部署的路由语义（docker / docker-compose / linuxgsm-docker）

Docker 类实例的文件操作在**容器内**执行（`docker exec` / `docker cp`），路由由 `InstanceFileServiceImpl.buildRoute` 解析（主应用文件管理端点与补丁安装 PatchInstallExecutor 均复用同一路由，见 ADR-0006 决策 4，避免第二套路径解析）：

- **路径根目录（resolvedPath 基准）**：`containerWorkDir` 解析链为
  1. `configInfo.containerWorkDir`（部署适配器写入）
  2. `configInfo.workDir`
  3. 部署类型默认值（docker → `/home/steam`、docker-compose → `/`、linuxgsm-docker → `/app`）
  4. 回退读取游戏元数据 `deployConfig.<deployType>.workingDir`（如 l4d2 → `/l4d2`；老实例 configInfo 未记录 workingDir 时走此路径，避免路径解析到容器根）
- **容器标识解析（ContainerIdResolver，compose 重建容错）**：
  - `docker-compose`：**动态查询优先**（`docker ps -q -f name={projectName}_`，projectName 取 runtimeMetadata.projectName 或按实例 ID 推导 `game{id}`），查询失败回退显式容器名（compose `container_name` 模板变量，如 `l4d2`），再回退缓存 containerId。宿主机重启容器重建（ID 变更）后仍能解析到新容器
  - `docker` / `linuxgsm-docker`：containerId 优先，缺失时容器名兜底（含 DockerAdapter 默认命名 `game-instance-{id}`）
- **下载到内存（downloadFileToMemory）**：docker 分支先把 `docker cp` 到**宿主机临时路径**（`/tmp/.gp-download-*`）再经 SFTP 读回。⚠️ 不要直接把 `docker cp` 的目标/源设为本地（Windows）路径——docker CLI 会把 `D:\...` 解析成容器引用，报 `copying between containers is not supported`
- **写文件（writeTextFile）**：内容先经 SFTP 流式写宿主临时文件再 `docker cp` 进容器（避免大内容内联进命令参数触发 `Argument list too long`）；`docker cp` 不会自动创建父目录，需先 `mkdir -p`

### 3.2 容器重建后的对账写回（宿主机重启场景）

`DockerInstanceSyncStrategy` 对账匹配三级：
1. 容器 ID 精确匹配（runtime_metadata.containerId，支持 12 位短 ID 与 64 位完整 ID 互为前缀）
2. 容器名精确匹配（runtime_metadata.containerName / configInfo CONTAINER_NAME 等）
3. docker-compose 项目名前缀匹配（`{projectName}_`，compose 容器名规范）
4. 多字段严格匹配（镜像 IMAGE_REPO+IMAGE_TAG / image + 容器名含 gameCode 关键字）

匹配成功即把新 containerId **写回 runtime_metadata**（`writeBackContainerId`），供删除清理/控制台/文件路由直接使用——宿主机重启、容器重建后无需人工干预即自愈。

## 4. FileAccessService — 主机级远程文件 + 命令执行

基于 SFTP 的主机级文件操作（不绑定实例）：

```java
public interface FileAccessService {
    String readTextFile(Long hostId, String remotePath);
    void   writeTextFile(Long hostId, String remotePath, String content);
    String readTextFile(Long hostId, String remotePath, Charset charset);
    byte[] downloadFileToMemory(Long hostId, String remotePath);
    byte[] getFileBytes(Long hostId, String remotePath, long offset, long length);

    void uploadFile(Long hostId, String remotePath, MultipartFile file);  // Spring multipart
    void uploadLocalFile(Long hostId, String remotePath, String localPath);
    void downloadFile(Long hostId, String remotePath, String localPath);
    void deleteFile(Long hostId, String remotePath);
    void moveFile(Long hostId, String oldPath, String newPath);

    List<FileInfo> listFiles(Long hostId, String remotePath);
    void createDirectory(Long hostId, String remotePath);
    void deleteDirectory(Long hostId, String remotePath, boolean recursive);
    boolean exists(Long hostId, String remotePath);
    FileInfo getFileInfo(Long hostId, String remotePath);

    long tailFile(Long hostId, String remotePath, long offset, Charset charset, Consumer<String> lineConsumer);

    // 远程命令执行
    CommandResult executeCommand(Long hostId, String command, long timeoutMs);
    default CommandResult executeCommand(Long hostId, String command) { return executeCommand(hostId, command, 30_000L); }

    @Data class FileInfo { String name; String path; boolean directory; long size; long lastModified; String permissions; String owner; }
    @Data class CommandResult { boolean success; int exitCode; String output; String error; }
}
```

## 5. SshTunnelService — SSH 本地端口转发隧道（v3.7.0，ADR-0009）

插件经宿主 SSH 会话建立本地端口转发：平台本地绑定回环端口，经隧道转发到 SSH 主机可达的远端目标（如实例容器内/宿主机映射的 MySQL）。典型场景——平台与目标主机仅 SSH 可达，插件需直连实例数据库。

```java
public interface SshTunnelService {
    /** 用平台已登记主机的凭据开隧道（复用 SshUtil 会话池）；hostId 不存在或 SSH 失败抛 BusinessException */
    TunnelHandle openByHost(Long hostId, String remoteHost, int remotePort);
    /** 用调用方自带凭据开隧道（专用会话，宿主不落库、不写日志——插件连接档案场景） */
    TunnelHandle openWithCredentials(SshEndpoint ssh, String remoteHost, int remotePort);
    /** 幂等关闭：引用计数减 1，减至 0 才真正关闭隧道 */
    void close(TunnelHandle handle);

    /** SSH 端点凭据（port 缺省 22 有便捷构造；toString 已脱敏禁止凭据进日志） */
    record SshEndpoint(String host, int port, String user, String password, String privateKey) {}
    /** 隧道句柄：连接 127.0.0.1:localPort 即等于连接 remoteHost:remotePort */
    record TunnelHandle(String id, int localPort, String remoteHost, int remotePort,
                        String ownerPluginId) {}
}
```

### 5.1 生命周期规则（必读）

- **去重与引用计数**：去重键 = `(ownerPluginId, 凭据来源, remoteHost, remotePort)`。同插件对同一目标重复 open 返回**同一句柄**并叠加引用计数；**跨插件不共享**（即使目标相同）。`close()` 幂等，引用计数归零才真正关闭转发。
- **本地端口安全默认**：仅绑定 `127.0.0.1`（不暴露网络），端口由 OS 随机分配（bind `:0` 取实际端口）。
- **会话钉住**：`openByHost` 复用宿主 `SshUtil` 连接池会话并钉住（空闲回收器跳过），隧道流量不算会话活跃——隧道不会因"空闲"被误杀，也**不做空闲回收**。
- **三层关闭兜底**：① `close()` 引用计数归零；② 插件 stop/unload 时宿主强制关闭该插件全部句柄（`onUnload()` 主动 close 是加速路径，忘关也有兜底）；③ 宿主删除主机时联动关闭该 hostId 开出的全部（平台凭据）隧道。
- **信任边界**：可信插件模型——任何已安装插件可对任意已登记主机开隧道（转发到该主机可达的任意 remoteHost:remotePort），与 ExtensionClient 等现有 SDK 服务同信任级别。

### 5.2 注入与典型用法（连接实例 MySQL）

```java
@Service
@RequiredArgsConstructor
public class MyDbConnectionFactory {
    private final SshTunnelService sshTunnelService;   // 子容器自动注入（绑定本插件）
    private final InstanceQueryService instanceQueryService;

    public DataSource buildDataSource(Long instanceId) {
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        // configInfo.database 由宿主按 yml database 声明组装（见 §6）
        Map<String, Object> db = (Map<String, Object>) instance.getConfigInfo().get("database");

        // remoteHost=127.0.0.1 语义：实例所在主机的回环地址（MYSQL_PORT 已映射到宿主机）
        TunnelHandle handle = sshTunnelService.openByHost(
                instance.getHostId(), "127.0.0.1", ((Number) db.get("port")).intValue());

        // 连接 127.0.0.1:handle.localPort() 即连到远端 MySQL
        String url = "jdbc:mysql://127.0.0.1:" + handle.localPort() + "/" + dbName;
        // ... 建连接池并缓存 handle，实例删除/插件卸载时 close
    }
}
```

> ⚠️ 句柄应随实例生命周期缓存与释放：`onInstanceDelete` 关闭对应池+隧道；插件 `onUnload()` 主动 close 全部句柄（宿主兜底之外的自清理）。

## 6. configInfo.database 组装（v3.7.0，ADR-0009）

带数据库的 docker-compose 游戏（如 dnf_tw）无需插件手工解析 compose 变量——宿主在**部署与更新时**按游戏元数据 yml 声明组装数据库连接信息，写入实例 `configInfo.database`（`InstanceVO` 已透出，经 `InstanceQueryService.getInstanceById` 读取）。

### 6.1 yml 声明（`games/{gameCode}.yml` 的 dockerCompose 节内，主应用维护）

```yaml
game:
  dockerCompose:
    # ... 既有 composeTemplate / variables
    database:
      type: mysql
      host: 127.0.0.1            # 语义：实例所在主机的回环地址（经 SSH 隧道访问）
      portVar: MYSQL_PORT        # 端口变量名引用：取部署变量最终值（用户输入 > 默认值）
      user: root
      passwordVar: DNF_DB_ROOT_PASSWORD   # 密码变量名引用
      databases: [cain, siroco]  # 库名列表
```

- 字段为**变量名引用式**（`portVar`/`passwordVar` 引用部署变量名，另有 `port`/`password` 字面量兜底），变量解析与 .env 生成同源同规则：**用户输入值 > variables 默认值 > 字面量兜底**。
- database 子节随部署节纳入 ADR-0008 合并体系（插件 `getDeployConfigs()` 覆盖部署节时 variables + database 一起自洽替换）。

### 6.2 组装时机与结果

`DockerComposeAdapter.deploy` 完成时与 `InstanceServiceImpl.updateInstance` 写回时走**同一条组装路径**，结果：

```json
configInfo.database = { "type": "mysql", "host": "127.0.0.1", "port": 3000,
                        "user": "root", "password": "88888888", "databases": ["cain", "siroco"] }
```

### 6.3 时序与回退（陷阱）

- **`onInstanceCreate` 在部署前触发**，收到的 configInfo **尚无 database 节**——插件按"懒建"模型在首次连接时经 `getInstanceById` 读取，勿在 Create 钩子里预读 database。
- **改密码/端口后**：消费 `onInstanceUpdate` 钩子（实参为更新后的完整新 configInfo，每次更新都触发、无平台侧 diff——配置是否真变由插件自行比对）主动失效并重建对应连接池/隧道。
- **组装机制上线前部署的老实例** configInfo 无 database 节，插件侧用裸 compose 变量回退解析（`configInfo.MYSQL_PORT` / `configInfo.DNF_DB_ROOT_PASSWORD`），无需重部署。

## 7. 注入示例

```java
@Service
@RequiredArgsConstructor
public class MyMapService {
    private final InstanceQueryService instanceQueryService;
    private final InstanceFileService instanceFileService;

    public String readServerCfg(Long instanceId) {
        return instanceFileService.readTextFile(instanceId, "cfg/server.cfg");
    }
}
```

---

## 8. 控制器开发规范

```java
@RestController
@RequestMapping("/api/plugin/mygame/rcon")  // 必须以 /api/plugin/{gameCode}/ 开头
public class RconController { /* ... */ }
```

- **路径前缀约束**：所有控制器路径必须以 `/api/plugin/{gameCode}/` 开头（框架注册时自动去掉 `/api`，因主应用 context-path 为 `/api`）。
- **路径冲突检测**：两个插件注册相同 URL 会抛 `PluginPathConflictException` 阻止加载（见 `references/exceptions.md`）。
- **统一响应**：建议返回 `{code, message, data}` 格式。

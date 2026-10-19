# 宿主服务面

> 对齐版本：v3.12.0（2026-10-19）｜ 权威源：`backend/plugin/` 源码

除持久化外，插件 SDK 还暴露主机、实例、文件、SSH 隧道、RCON、云盘和主机工具服务。实现由主应用提供，注入到插件的 Spring 子容器。每次调用自动携带调用方插件 ID 写审计日志，插件不能伪造来源。

## 1. HostQueryService

```java
public interface HostQueryService {
    HostResourceVO getHostResourceInfo(Long hostId); // CPU / 内存 / 磁盘 / 网络
    HostVO getHostById(Long hostId);                 // 地址、SSH 凭据与主机元数据
}
```

## 2. InstanceQueryService

```java
public interface InstanceQueryService {
    InstanceVO getInstanceById(Long id);
    List<InstanceVO> getInstancesByHostId(Long hostId);
    List<InstanceVO> getInstancesByGameId(Long gameId);
    List<InstanceVO> listByGameCode(String gameCode);
    InstanceVO getInstanceStatus(Long id);           // 刷新并返回当前状态
    boolean startInstance(Long id);
    boolean stopInstance(Long id);
    boolean restartInstance(Long id);
    String getInstanceLogs(Long id, int lines);
    String executeCommand(Long id, String command);  // 实例控制台命令
}
```

实例的创建、更新和删除归宿主。插件消费实例数据，调用生命周期控制命令。

## 3. InstanceFileService

`InstanceFileService` 以 `(instanceId, relativePath)` 寻址，按实例部署类型自动路由：

| 部署类型 | 存储路径 |
|---|---|
| Native / LinuxGSM | 实例安装目录，走 SFTP |
| Docker / Docker Compose / LinuxGSM Docker | 容器工作目录，走 `docker exec` 与 `docker cp` |

`relativePath` 使用正斜杠，相对游戏数据根目录。包含 `..` 会抛 `IllegalArgumentException`。

```java
public interface InstanceFileService {
    String readTextFile(long instanceId, String relativePath);
    String readTextFile(long instanceId, String relativePath, Charset charset);
    void writeTextFile(long instanceId, String relativePath, String content);
    void writeTextFile(long instanceId, String relativePath, String content, Charset charset);

    byte[] downloadFileToMemory(long instanceId, String relativePath);
    byte[] getFileBytes(long instanceId, String relativePath, long offset, long length);

    default void uploadLocalFile(long instanceId, String relativePath, String localPath);
    default void downloadFile(long instanceId, String relativePath, String localPath);
    void uploadLocalFile(long instanceId, String relativePath, String localPath,
                         FileTransferProgressCallback callback);
    void downloadFile(long instanceId, String relativePath, String localPath,
                      FileTransferProgressCallback callback);

    void deleteFile(long instanceId, String relativePath);
    void moveFile(long instanceId, String oldRelativePath, String newRelativePath);
    void copyFile(long instanceId, String srcRelativePath, String dstRelativePath);
    boolean exists(long instanceId, String relativePath);
    FileInfo getFileInfo(long instanceId, String relativePath);

    List<FileInfo> listFiles(long instanceId, String relativePath);
    void createDirectory(long instanceId, String relativePath);
    void deleteDirectory(long instanceId, String relativePath, boolean recursive);
    void copyDirectory(long instanceId, String srcRelativePath, String dstRelativePath);

    long tailFile(long instanceId, String relativePath, long offset,
                  Charset charset, Consumer<String> lineConsumer);

    String computeDigest(long instanceId, String relativePath, String algorithm);
    default String md5(long instanceId, String relativePath);
}
```

### 3.1 Docker 路由与安全规则

Docker 类部署的 `containerWorkDir` 解析顺序为：显式 `configInfo.containerWorkDir`、`configInfo.workDir`、部署类型默认值，最后回退游戏元数据中的工作目录。Compose 容器重建后，宿主对账逻辑会把新的 `containerId` 写回 `runtime_metadata`。

不要自己拼 `docker cp` 命令，尤其不要把 Windows 本地路径当容器路径使用。下载到内存时，宿主会先复制到主机临时路径；写文件时也会经主机临时文件进入容器，避免命令行参数过长。

## 4. FileAccessService

`FileAccessService` 绑定主机，不感知实例：

```java
public interface FileAccessService {
    String readTextFile(Long hostId, String remotePath);
    String readTextFile(Long hostId, String remotePath, Charset charset);
    void writeTextFile(Long hostId, String remotePath, String content);
    byte[] downloadFileToMemory(Long hostId, String remotePath);
    byte[] getFileBytes(Long hostId, String remotePath, long offset, long length);

    void uploadFile(Long hostId, String remotePath, MultipartFile file);
    default void uploadLocalFile(Long hostId, String remotePath, String localPath);
    default void downloadFile(Long hostId, String remotePath, String localPath);
    void uploadLocalFile(Long hostId, String remotePath, String localPath,
                         FileTransferProgressCallback callback);
    void downloadFile(Long hostId, String remotePath, String localPath,
                      FileTransferProgressCallback callback);

    void deleteFile(Long hostId, String remotePath);
    void moveFile(Long hostId, String oldPath, String newPath);
    List<FileInfo> listFiles(Long hostId, String remotePath);
    void createDirectory(Long hostId, String remotePath);
    void deleteDirectory(Long hostId, String remotePath, boolean recursive);
    boolean exists(Long hostId, String remotePath);
    FileInfo getFileInfo(Long hostId, String remotePath);

    long tailFile(Long hostId, String remotePath, long offset,
                  Charset charset, Consumer<String> lineConsumer);

    CommandResult executeCommand(Long hostId, String command, long timeoutMs);
    default CommandResult executeCommand(Long hostId, String command);
}
```

`FileInfo` 包含 `name`、`path`、`directory`、`size`、`lastModified`、`permissions` 和 `owner`。`CommandResult` 包含 `success`、`exitCode`、`output` 和 `error`。

## 5. SshTunnelService

SSH 本地端口转发是 ADR-0009 宿主能力。本地端口只绑定 `127.0.0.1`，由 OS 分配。句柄按插件与目标隔离，引用计数管理。

```java
public interface SshTunnelService {
    TunnelHandle openByHost(Long hostId, String remoteHost, int remotePort);
    TunnelHandle openWithCredentials(SshEndpoint ssh, String remoteHost, int remotePort);
    void close(TunnelHandle handle);

    record SshEndpoint(String host, int port, String user, String password, String privateKey) {
        public SshEndpoint(String host, String user, String password); // port 缺省 22
        public String toString(); // 凭据脱敏
    }

    record TunnelHandle(String id, int localPort, String remoteHost, int remotePort,
                        String ownerPluginId) {}
}
```

连接 `127.0.0.1:handle.localPort()`，不要缓存端口后继续假设句柄仍有效。插件应在 `onInstanceDelete` 和 `onUnload` 中释放自己的连接池与隧道；宿主卸载时也有兜底关闭。

## 6. 数据库连接元数据

带数据库的 Docker Compose 游戏，宿主在部署和实例更新时把 `configInfo.database` 组装好：

```yaml
dockerCompose:
  database:
    type: mysql
    host: 127.0.0.1
    portVar: MYSQL_PORT
    user: root
    passwordVar: DNF_DB_ROOT_PASSWORD
    databases: [cain, siroco]
```

变量解析与 `.env` 生成同源：用户输入优先于默认变量，默认变量优先于字面量兜底。`onInstanceCreate` 发生在部署前，此时还没有 `database`；插件应懒建连接。改配置后要消费 `onInstanceUpdate`，主动失效并重建连接池。老实例可能只有裸 compose 变量。

## 7. RconService

RCON 传输层是宿主能力，见 ADR-0016。端点解析只认标准键 `configInfo.rconPort`（缺省 `27015`）和 `rconPassword`；密码不能由插件指定。

```java
public interface RconService {
    String executeCommand(long instanceId, String command);
    String executeCommand(long instanceId, String command, Duration timeout);
    boolean testConnection(long instanceId);
}
```

连接、连接池、超时和审计归宿主；游戏命令语义（例如解析 `status`、踢人、封禁）归插件。

## 8. FileTransferProgressCallback

文件服务支持流式进度回调：

```java
public interface FileTransferProgressCallback {
    void onStart(long totalBytes);                          // 未知为 -1
    void onProgress(long bytesTransferred, long totalBytes);
    void onComplete();                                      // 成功时最多一次
    void onError(Throwable error);                          // 失败时最多一次
}
```

回调是同步的；从回调抛异常会中止传输，可以用于取消。Docker 部署的进度只覆盖 SFTP 段，`docker cp` 段没有进度。

## 9. CloudDriveService

云盘传输是 ADR-0024 宿主能力。账号在主应用管理，按 `accountName` 寻址；路径是账号隐式挂载点内的相对路径。SDK 只提供传输能力，业务编排归插件。

```java
public interface CloudDriveService {
    List<CloudFileInfo> list(String accountName, String path, boolean refresh);
    CloudLink link(String accountName, String path);
    long download(String accountName, String path, OutputStream target);
    List<String> transfer(String accountName, String shareUrl, String passcode,
                          String targetPath, Duration timeout);

    record CloudFileInfo(String name, String path, boolean directory,
                         long size, Long modifiedAtMillis) {}
    record CloudLink(String url, Map<String, String> headers, Long expiresAtMillis) {}
}
```

自定义 HTTP 客户端访问直链时，必须连同 `CloudLink.headers` 一起发送。`transfer` 阻塞到终态；超时或转存失败抛 `BusinessException`，返回值为转存产物路径。超时后云端任务可能仍在执行，是否重试由业务判断。

## 10. HostToolingService

主机工具是 ADR-0026 能力，只暴露语义化操作，不暴露裸容器执行。镜像选择、命令拼装、临时文件清理和审计都归宿主。

```java
public interface HostToolingService {
    List<String> extractArchive(Long hostId, String remoteArchive,
                                String destDir, String includePattern);
    String fileDigest(Long instanceId, String relativePath);
    String runToolScript(Long instanceId, String relativePath, String scriptResource);
}
```

`extractArchive` 在目标主机解压，优先原生工具，缺失时回退 `platform-tools` 镜像。`includePattern` 是逗号分隔、大小写不敏感的 glob（如 `*.vpk`）；提供时只把匹配文件平铺到 `destDir`。`fileDigest` 接受实例相对路径，返回小写 SHA-256 hex。

`runToolScript` 运行插件 JAR 内的脚本，返回原始 stdout；宿主负责实例寻址、脚本分发、临时文件清理和调用方审计。脚本必须在插件加载前由 `plugin.properties` 显式登记，运行时不能传主机路径或脚本内容：

```properties
plugin.tooling.scripts=tooling/my_analyzer.py
```

```java
String rawJson = hostToolingService.runToolScript(
        instanceId, "data/example.bin", "tooling/my_analyzer.py");
```

插件负责解释 stdout；业务结果和业务异常都不应进入 SDK。L4D2 的 VPK JSON 解析、`VpkAnalyzeResult` 与 `VpkInvalidException` 位于 `plugin-l4d2`。

## 11. 控制器规范

插件控制器路径必须以 `/api/plugin/{gameCode}/` 开头。跨插件重复路径抛 `PluginPathConflictException`，插件无法启动。REST 返回平台统一的 `{code, message, data}` 响应结构。

## 12. 注入示例

```java
@Service
@RequiredArgsConstructor
public class MyMapService {
    private final InstanceQueryService instanceQueryService;
    private final InstanceFileService instanceFileService;
    private final CloudDriveService cloudDriveService;
    private final HostToolingService hostToolingService;

    public String readServerConfig(long instanceId) {
        return instanceFileService.readTextFile(instanceId, "cfg/server.cfg");
    }
}
```

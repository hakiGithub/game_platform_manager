# 宿主服务面（Host Services）

> 对齐版本：v3.1.0（ADR-0001）｜ 权威源：`backend/plugin/` 源码

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

## 5. 注入示例

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

## 6. 控制器开发规范

```java
@RestController
@RequestMapping("/api/plugin/mygame/rcon")  // 必须以 /api/plugin/{gameCode}/ 开头
public class RconController { /* ... */ }
```

- **路径前缀约束**：所有控制器路径必须以 `/api/plugin/{gameCode}/` 开头（框架注册时自动去掉 `/api`，因主应用 context-path 为 `/api`）。
- **路径冲突检测**：两个插件注册相同 URL 会抛 `PluginPathConflictException` 阻止加载（见 `references/exceptions.md`）。
- **统一响应**：建议返回 `{code, message, data}` 格式。

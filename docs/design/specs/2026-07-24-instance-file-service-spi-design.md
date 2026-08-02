# InstanceFileService SPI 设计文档

> **日期**: 2026-07-24
> **主题**: 实例感知的文件管理 SPI（屏蔽部署类型差异）
> **状态**: 待审查

---

## 1. 背景与目标

### 1.1 问题陈述

当前 L4D2 插件通过 `FileAccessService` SPI 访问文件，该 SPI 仅基于 SFTP，方法签名为 `(hostId, remotePath)`，路径为**主机视角绝对路径**。

存在以下局限：

1. **Docker 容器内文件无法访问**：`FileAccessService` 仅支持 SFTP 主机文件操作；Docker 容器内文件访问由独立的 `DockerFileService`（`docker exec`/`docker cp`）提供，但**未通过 SPI 暴露给插件**。
2. **路径模型与部署类型耦合**：插件需感知 `installPath` 的绝对路径，不同部署类型的 `installPath` 语义不统一（DockerAdapter 存 containerId，其他存主机目录）。
3. **调用方负担重**：插件需自行拼绝对路径、判断部署类型、选择访问方式。

### 1.2 设计目标

提供一套**实例感知的文件管理 SPI**，调用方传 `(instanceId, relativePath)`，实现层根据实例 `deployType` 自动路由：

- `LINUX_GSM`（Native）→ SFTP（复用现有 `FileAccessService`）
- `DOCKER` / `DOCKER_COMPOSE` / `LINUX_GSM_DOCKER` → `docker exec`/`docker cp`（复用现有 `DockerFileService`）

**核心原则**：
- `FileAccessService` 零改动（作为底层被复用）
- 业务逻辑（MapService/PluginInstallService 等）留在插件
- 主应用不提供业务级 API（如 listMaps/installPlugin）
- 调用方只关注 `(instanceId, relativePath)`，不感知文件在主机还是容器

### 1.3 非目标

- 不统一前端文件管理界面（当前 `FileController`/`DockerFileController` 双轨制保留；如未来需要，可在本 SPI 基础上增量加 REST 层）
- 不消除插件本地 File IO（`VpkTrimService` 的 `RandomAccessFile`、MapService/PluginInstallService 的临时文件暂存/ZIP 解压保留）
- 不改造 `FileService` 复用 `SshUtil` 连接池（独立优化项，不在本次范围）

---

## 2. 架构概览

```
L4D2 插件（8 个服务）
  → InstanceFileService（新 SPI, plugin 模块）
    → AbstractInstanceFileService（抽象基类, plugin 模块）
      ├─ InstanceFileServiceImpl（core 模块）
      │   ├─ LINUX_GSM        → FileAccessService（hostId, installPath + relPath）[SFTP]
      │   └─ DOCKER/COMPOSE/
      │      LINUX_GSM_DOCKER → DockerFileService（hostId, containerId, containerWorkDir + relPath）[docker exec/cp]
      │                        + SshUtil（摘要计算：docker cp + *sum 命令）
      │
      └─ StandaloneInstanceFileService（plugin-l4d2-standalone 模块）
          ├─ LINUX_GSM        → StandaloneFileAccessService [SFTP]
          └─ DOCKER 类        → SshUtil [docker exec/cp + *sum]
```

### 2.1 关键决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 接口变更方式 | 新增 `InstanceFileService`，不改 `FileAccessService` | 零风险，向后兼容 |
| 路径模型 | `(instanceId, relativePath)`，相对实例游戏数据根目录 | 调用方不感知部署类型 |
| 暴露范围 | 仅 SPI（插件用），REST 层按需增量加 | 核心诉求是插件调用 |
| 抽象基类 | `AbstractInstanceFileService` 提取通用路由/校验/摘要逻辑 | 避免 core/standalone 重复 |
| VpkTrimService | 保留纯本地 `RandomAccessFile` | 二进制裁剪无法远程化，MapService 负责中转 |

---

## 3. 接口定义

### 3.1 InstanceFileService 接口

**位置**: `backend/plugin/src/main/java/com/gameplatform/plugin/service/InstanceFileService.java`

```java
package com.gameplatform.plugin.service;

import com.gameplatform.plugin.service.FileAccessService.FileInfo;
import java.nio.charset.Charset;
import java.util.List;
import java.util.function.Consumer;

/**
 * 实例感知的文件访问 SPI。
 *
 * 调用方传 (instanceId, relativePath)，实现层根据实例 deployType
 * 自动路由到 SFTP（Native）或 docker exec/cp（Docker 类）。
 *
 * relativePath 语义：相对于实例"游戏数据根目录"的路径，使用正斜杠。
 * - Native/LinuxGSM：根目录 = instance.installPath
 * - Docker/Compose/LinuxGsmDocker：根目录 = 容器内工作目录
 *   （由部署适配器写入 runtimeMetadata.containerWorkDir）
 *
 * 路径安全：禁止使用 .. 跳出根目录（实现层校验，越界抛 IllegalArgumentException）。
 */
public interface InstanceFileService {

    // ===== 文本读写 =====
    String readTextFile(long instanceId, String relativePath);
    String readTextFile(long instanceId, String relativePath, Charset charset);
    void   writeTextFile(long instanceId, String relativePath, String content);
    void   writeTextFile(long instanceId, String relativePath, String content, Charset charset);

    // ===== 二进制读写 =====
    byte[] downloadFileToMemory(long instanceId, String relativePath);
    byte[] getFileBytes(long instanceId, String relativePath, long offset, long length);

    // ===== 上传/下载 =====
    void uploadLocalFile(long instanceId, String relativePath, String localPath);
    void downloadFile(long instanceId, String relativePath, String localPath);

    // ===== 文件管理 =====
    void deleteFile(long instanceId, String relativePath);
    void moveFile(long instanceId, String oldRelativePath, String newRelativePath);
    void copyFile(long instanceId, String srcRelativePath, String dstRelativePath);
    boolean exists(long instanceId, String relativePath);
    FileInfo getFileInfo(long instanceId, String relativePath);

    // ===== 目录管理 =====
    List<FileInfo> listFiles(long instanceId, String relativePath);
    void createDirectory(long instanceId, String relativePath);
    void deleteDirectory(long instanceId, String relativePath, boolean recursive);

    // ===== 流式增量（SourceMod 日志用）=====
    long tailFile(long instanceId, String relativePath, long offset,
                  Charset charset, Consumer<String> lineConsumer);

    // ===== 文件摘要 =====
    /**
     * 计算远程文件摘要。
     *
     * @param algorithm 算法名，如 "MD5"、"SHA-1"、"SHA-256"（MessageDigest 支持的标准名）
     * @return 摘要的十六进制小写字符串
     */
    String computeDigest(long instanceId, String relativePath, String algorithm);

    /** MD5 快捷方法，等价于 computeDigest(instanceId, relativePath, "MD5") */
    default String md5(long instanceId, String relativePath) {
        return computeDigest(instanceId, relativePath, "MD5");
    }
}
```

### 3.2 与 FileAccessService 的差异

| 维度 | FileAccessService（现有） | InstanceFileService（新） |
|---|---|---|
| 入参 | `(hostId, remotePath)` | `(instanceId, relativePath)` |
| 路径语义 | 主机视角绝对路径 | 实例相对路径 |
| 部署类型感知 | 否（仅 SFTP） | 是（自动路由 SFTP/docker） |
| `copyFile` | 无 | 有 |
| `writeTextFile(Charset)` | 无（仅单一签名） | 有（重载） |
| `computeDigest` | 无 | 有（MD5/SHA-1/SHA-256） |

### 3.3 FileInfo 复用

直接复用 `FileAccessService.FileInfo` 内嵌类，避免新增类型：

```java
@Data
class FileInfo {
    String name;
    String path;
    boolean directory;
    long size;
    long lastModified;
    String permissions;   // 如 rwxr-xr-x
    String owner;
}
```

---

## 4. 实现层架构

### 4.1 AbstractInstanceFileService（抽象基类）

**位置**: `backend/plugin/src/main/java/com/gameplatform/plugin/service/AbstractInstanceFileService.java`

职责：
- `validateRelativePath(String)`：路径规范化 + `..` 越界校验
- `resolveRoute(instanceId, relativePath)`：调用 `InstanceQueryService` 获取实例，校验 `deployType`，委托子类 `buildContext` 构造 `RouteContext`
- 提供 `RouteContext` 值对象（封装路由结果，含 instanceId/hostId/relativePath/resolvedPath/containerId）
- 不依赖 `InstanceVO` 类型（避免 plugin 模块引入 api 模块依赖），通过 `InstanceDescriptor` 原始字段传递

```java
public abstract class AbstractInstanceFileService implements InstanceFileService {

    protected abstract InstanceQueryService getInstanceQueryService();

    /**
     * 子类实现：根据实例描述符 + 校验后的相对路径，构造路由上下文。
     * Native 子类填 resolvedPath=installPath+rel，containerId=null；
     * Docker 子类填 resolvedPath=containerWorkDir+rel，containerId=解析后的容器ID。
     */
    protected abstract RouteContext buildContext(InstanceDescriptor desc, String safeRel);

    /**
     * 子类实现：从 InstanceQueryService 返回的实例对象提取字段构造 InstanceDescriptor。
     * 由子类实现是因为 plugin 模块不依赖 api 模块的 InstanceVO 类型，
     * 子类（core/standalone）各自知道如何从其 InstanceQueryService 返回类型提取字段。
     */
    protected abstract InstanceDescriptor describeInstance(Object instance);

    protected RouteContext resolveRoute(long instanceId, String relativePath) {
        Object instance = getInstanceQueryService().getInstanceById(instanceId);
        if (instance == null) throw new IllegalArgumentException("实例不存在: " + instanceId);
        String safeRel = validateRelativePath(relativePath);
        InstanceDescriptor desc = describeInstance(instance);
        switch (desc.getDeployType()) {
            case "LINUX_GSM":
            case "DOCKER":
            case "DOCKER_COMPOSE":
            case "LINUX_GSM_DOCKER":
                return buildContext(desc, safeRel);
            default:
                throw new UnsupportedOperationException(
                    "不支持的部署类型: " + desc.getDeployType());
        }
    }

    protected String validateRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return "";
        String normalized = relativePath.replace("\\", "/").replaceAll("/+", "/");
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("相对路径禁止包含 ..: " + relativePath);
        }
        return normalized;
    }

    // ===== RouteContext：路由结果值对象 =====
    protected static class RouteContext {
        final long instanceId;
        final long hostId;
        final String relativePath;
        final String resolvedPath;
        final String containerId;  // null 表示 Native 路由

        static RouteContext nativeRoute(long instanceId, long hostId, String rel, String resolved) {
            return new RouteContext(instanceId, hostId, rel, resolved, null);
        }
        static RouteContext dockerRoute(long instanceId, long hostId, String rel, String resolved, String containerId) {
            return new RouteContext(instanceId, hostId, rel, resolved, containerId);
        }
        // 构造方法私有
    }

    // ===== InstanceDescriptor：解耦 InstanceVO，避免 plugin 模块依赖 api 模块 =====
    protected static class InstanceDescriptor {
        private final long instanceId;
        private final long hostId;
        private final String deployType;
        private final String installPath;
        private final String runtimeMetadataJson;

        public InstanceDescriptor(long instanceId, long hostId, String deployType,
                                   String installPath, String runtimeMetadataJson) {
            this.instanceId = instanceId;
            this.hostId = hostId;
            this.deployType = deployType;
            this.installPath = installPath;
            this.runtimeMetadataJson = runtimeMetadataJson;
        }
        // getter 略
    }
}
```

**设计要点**：
- `InstanceDescriptor` 是 plugin 模块内的值对象，仅含 5 个原始字段（instanceId/hostId/deployType/installPath/runtimeMetadataJson），不引用 `InstanceVO`
- `describeInstance(Object instance)` 由子类实现，负责从 `InstanceQueryService.getInstanceById` 返回的具体类型（core 是 `InstanceVO`，standalone 可能是其他类型）提取字段
- `RouteContext.containerId == null` 作为 Native/Docker 路由的判别标志，各方法实现根据此字段选择委托目标

### 4.2 InstanceFileServiceImpl（core 模块实现）

**位置**: `backend/core/src/main/java/com/gameplatform/plugin/service/InstanceFileServiceImpl.java`

**类型依赖说明**：`AbstractInstanceFileService`（plugin 模块）通过 `InstanceDescriptor` 值对象解耦 `InstanceVO`，不直接依赖 api 模块。`InstanceFileServiceImpl`（core 模块）可访问 `InstanceVO`（core 已依赖 api），在 `buildContext` 中从 `InstanceDescriptor` 取字段构造 `RouteContext`。

```java
@Service
public class InstanceFileServiceImpl extends AbstractInstanceFileService {

    private final InstanceQueryService instanceQueryService;
    private final HostQueryService hostQueryService;
    private final FileAccessService fileAccessService;
    private final DockerFileService dockerFileService;
    private final SshUtil sshUtil;
    private final ObjectMapper objectMapper;

    @Override
    protected InstanceQueryService getInstanceQueryService() { return instanceQueryService; }

    @Override
    protected InstanceDescriptor describeInstance(Object instance) {
        InstanceVO vo = (InstanceVO) instance;  // core 模块可访问 InstanceVO
        return new InstanceDescriptor(
            vo.getId(),
            vo.getHostId(),
            vo.getDeployType(),
            vo.getInstallPath(),
            vo.getRuntimeMetadata()
        );
    }

    @Override
    protected RouteContext buildContext(InstanceDescriptor desc, String safeRel) {
        if ("LINUX_GSM".equals(desc.getDeployType())) {
            String resolvedPath = joinPath(desc.getInstallPath(), safeRel);
            return RouteContext.nativeRoute(desc.getInstanceId(), desc.getHostId(),
                safeRel, resolvedPath);
        } else {
            Map<String, Object> metadata = parseRuntimeMetadata(desc.getRuntimeMetadataJson());
            String containerWorkDir = (String) metadata.getOrDefault("containerWorkDir",
                defaultContainerWorkDir(desc.getDeployType()));
            String containerId = resolveContainerId(desc, metadata);
            String resolvedPath = joinPath(containerWorkDir, safeRel);
            return RouteContext.dockerRoute(desc.getInstanceId(), desc.getHostId(),
                safeRel, resolvedPath, containerId);
        }
    }

    // ===== 各方法实现：根据 route.containerId 是否为空委托 =====
    @Override
    public String readTextFile(long instanceId, String relativePath) {
        RouteContext route = resolveRoute(instanceId, relativePath);
        if (route.containerId == null) {
            return fileAccessService.readTextFile(route.hostId, route.resolvedPath);
        } else {
            // DockerFileService.getFileContent(hostId, containerId, path, encoding, lines)
            // lines=0 表示读取全部行（按 DockerFileService 现有约定）
            return dockerFileService.getFileContent(route.hostId, route.containerId,
                route.resolvedPath, "UTF-8", 0);
        }
    }

    @Override
    public byte[] getFileBytes(long instanceId, String relativePath, long offset, long length) {
        RouteContext route = resolveRoute(instanceId, relativePath);
        if (route.containerId == null) {
            return fileAccessService.getFileBytes(route.hostId, route.resolvedPath, offset, length);
        } else {
            // DockerFileService 无 getFileBytes，用 docker exec tail -c + dd 实现
            // tail -c +{start} 取从 start 字节开始的内容；dd 限制读取长度
            long start = offset >= 0 ? offset + 1 : 0;  // tail -c 从 1 开始计数
            String cmd = String.format(
                "tail -c +%d %s 2>/dev/null | dd bs=1 count=%d 2>/dev/null | base64 -w0",
                start, route.resolvedPath, length);
            SshUtil.CommandResult r = sshUtil.executeCommand(
                resolveHost(route.hostId),
                "docker exec " + route.containerId + " sh -c '" + cmd + "'");
            if (r.getExitCode() != 0) {
                throw new IOException("读取容器文件字节失败: " + r.getStderr());
            }
            return Base64.getDecoder().decode(r.getStdout().trim());
        }
    }

    @Override
    public long tailFile(long instanceId, String relativePath, long offset,
                         Charset charset, Consumer<String> lineConsumer) {
        RouteContext route = resolveRoute(instanceId, relativePath);
        if (route.containerId == null) {
            return fileAccessService.tailFile(route.hostId, route.resolvedPath,
                offset, charset, lineConsumer);
        } else {
            // DockerFileService 无 tailFile，用 docker exec tail -n + 轮询实现
            // 实现：读取从 offset 字节开始的新内容，按行回调，返回新的文件大小
            long currentSize = getFileInfo(instanceId, relativePath).getSize();
            if (currentSize <= offset) return offset;  // 无新内容
            String cmd = String.format(
                "tail -c +%d %s 2>/dev/null", offset + 1, route.resolvedPath);
            SshUtil.CommandResult r = sshUtil.executeCommand(
                resolveHost(route.hostId),
                "docker exec " + route.containerId + " sh -c '" + cmd + "'");
            if (r.getExitCode() != 0) {
                throw new IOException("tail 容器文件失败: " + r.getStderr());
            }
            String content = new String(r.getStdout().getBytes(StandardCharsets.ISO_8859_1), charset);
            for (String line : content.split("\n", -1)) {
                lineConsumer.accept(line);
            }
            return currentSize;  // 返回新的 offset
        }
    }

    @Override
    public String computeDigest(long instanceId, String relativePath, String algorithm) {
        // 先校验算法名（防注入：只允许字母数字）
        if (!algorithm.matches("[A-Za-z0-9-]+")) {
            throw new IllegalArgumentException("非法算法名: " + algorithm);
        }
        MessageDigest.getInstance(algorithm);  // 校验算法存在，抛 NoSuchAlgorithmException
        RouteContext route = resolveRoute(instanceId, relativePath);
        Host host = hostQueryService.getHostById(route.hostId);
        if (route.containerId == null) {
            // Native: SSH 执行 {algorithm}sum
            SshUtil.CommandResult r = sshUtil.executeCommand(host, algorithm + "sum " + route.resolvedPath);
            if (r.getExitCode() == 127) {
                throw new UnsupportedOperationException("主机不支持 " + algorithm + "sum 命令");
            }
            if (r.getExitCode() != 0) {
                throw new IOException("摘要计算失败: " + r.getStderr());
            }
            return r.getStdout().trim().split("\\s+")[0];
        } else {
            // Docker: docker cp 到主机临时目录 + {algorithm}sum + 清理
            String tempHostPath = "/tmp/.gp-digest-" + UUID.randomUUID();
            try {
                SshUtil.CommandResult cp = sshUtil.executeCommand(host,
                    "docker cp " + route.containerId + ":" + route.resolvedPath + " " + tempHostPath);
                if (cp.getExitCode() != 0) {
                    throw new IOException("docker cp 失败: " + cp.getStderr());
                }
                SshUtil.CommandResult sum = sshUtil.executeCommand(host,
                    algorithm + "sum " + tempHostPath);
                if (sum.getExitCode() == 127) {
                    throw new UnsupportedOperationException("主机不支持 " + algorithm + "sum 命令");
                }
                if (sum.getExitCode() != 0) {
                    throw new IOException("摘要计算失败: " + sum.getStderr());
                }
                return sum.getStdout().trim().split("\\s+")[0];
            } finally {
                try {
                    sshUtil.executeCommand(host, "rm -f " + tempHostPath);
                } catch (Exception e) {
                    log.warn("清理临时文件失败: {}", tempHostPath, e);
                }
            }
        }
    }
    // ... 其他方法类似（委托 FileAccessService 或 DockerFileService）
}
```

**RouteContext**（替换原 FileRoute，避免 InstanceVO 耦合）：

```java
protected static class RouteContext {
    final long instanceId;
    final long hostId;
    final String relativePath;
    final String resolvedPath;
    final String containerId;  // null 表示 Native 路由

    static RouteContext nativeRoute(long instanceId, long hostId, String rel, String resolved) {
        return new RouteContext(instanceId, hostId, rel, resolved, null);
    }
    static RouteContext dockerRoute(long instanceId, long hostId, String rel, String resolved, String containerId) {
        return new RouteContext(instanceId, hostId, rel, resolved, containerId);
    }
}
```

### 4.3 路由策略详解

#### Native/LinuxGSM 路由（`LINUX_GSM`）

- `hostId` = `instance.hostId`
- `resolvedPath` = `instance.installPath + "/" + relativePath`
- 委托 `FileAccessService` 全部方法（SFTP）
- `copyFile`（FileAccessService 缺失）：实现层用 `downloadFile` 到本地 temp + `uploadLocalFile` 中转

#### Docker 类路由（`DOCKER` / `DOCKER_COMPOSE` / `LINUX_GSM_DOCKER`）

- `hostId` = `instance.hostId`
- `containerId` 解析（见 4.4）
- `resolvedPath` = `runtimeMetadata.containerWorkDir + "/" + relativePath`
- 委托 `DockerFileService` 全部方法（`docker exec`/`docker cp`）

### 4.4 containerId 解析

| 部署类型 | 解析方式 | 回退 |
|---|---|---|
| `DOCKER` | `runtimeMetadata.containerId`（直接 ID） | 无（必填） |
| `DOCKER_COMPOSE` | `docker compose -p {projectName} ps -q {serviceName}` 实时查询 | `runtimeMetadata.containerId`（如有） |
| `LINUX_GSM_DOCKER` | `docker ps -q -f name={containerName}` | `runtimeMetadata.containerId`（如有） |

**异常处理**：
- 查询返回空：抛 `IllegalStateException("无法解析容器 ID: " + instanceId)`
- 查询返回多个：抛 `IllegalStateException("容器 ID 不唯一，匹配到多个容器")`

### 4.5 containerWorkDir 默认值与回退

部署适配器需在 deploy 完成后把 `containerWorkDir` 写入 `runtimeMetadata`：

| 部署类型 | containerWorkDir | 默认值（回退） |
|---|---|---|
| `DOCKER` | DockerAdapter `-w` 参数值 | `/home/steam` |
| `DOCKER_COMPOSE` | compose 模板 `working_dir` | `/` |
| `LINUX_GSM_DOCKER` | `/app`（LinuxGSM 镜像固定 WORKDIR） | `/app` |

若 `runtimeMetadata.containerWorkDir` 为空，按部署类型取默认值，并记录 WARN 日志：`"实例 {instanceId} 的 runtimeMetadata 缺失 containerWorkDir，使用默认值 {default}"`。

### 4.6 摘要方法实现

#### Native 路径

SSH 执行 `{algorithm}sum {resolvedPath}`，解析输出第一个字段。

```java
SshUtil.CommandResult r = sshUtil.executeCommand(host, algorithm + "sum " + resolvedPath);
if (r.getExitCode() == 127) {
    throw new UnsupportedOperationException("主机不支持 " + algorithm + "sum 命令");
}
if (r.getExitCode() != 0) {
    throw new IOException("摘要计算失败: " + r.getStderr());
}
return r.getStdout().trim().split("\\s+")[0];
```

#### Docker 路径（docker cp + SSH 命令）

按用户指定方案：先 `docker cp` 拷到主机临时目录，再 SSH 执行摘要命令，最后清理。

```java
String tempHostPath = "/tmp/.gp-digest-" + UUID.randomUUID();
try {
    // 1. 容器内文件拷到主机临时路径
    SshUtil.CommandResult cp = sshUtil.executeCommand(host,
        "docker cp " + containerId + ":" + resolvedPath + " " + tempHostPath);
    if (cp.getExitCode() != 0) {
        throw new IOException("docker cp 失败: " + cp.getStderr());
    }
    // 2. 主机侧计算摘要
    SshUtil.CommandResult sum = sshUtil.executeCommand(host,
        algorithm + "sum " + tempHostPath);
    if (sum.getExitCode() == 127) {
        throw new UnsupportedOperationException("主机不支持 " + algorithm + "sum 命令");
    }
    if (sum.getExitCode() != 0) {
        throw new IOException("摘要计算失败: " + sum.getStderr());
    }
    return sum.getStdout().trim().split("\\s+")[0];
} finally {
    // 3. 清理临时文件（忽略失败）
    try {
        sshUtil.executeCommand(host, "rm -f " + tempHostPath);
    } catch (Exception e) {
        log.warn("清理临时文件失败: {}", tempHostPath, e);
    }
}
```

### 4.7 SPI 注入机制

**位置**: `backend/core/src/main/java/com/gameplatform/plugin/context/PluginSpringContextFactory.java`

在现有三个 SPI Bean 基础上新增 `InstanceFileService` 注入：

```java
// loadPluginSpringContext 方法中（约 line 88-95 附近）
InstanceFileService instanceFileService = parentContext.getBean(InstanceFileService.class);
childContext.getBeanFactory().registerSingleton("instanceFileService", instanceFileService);
```

插件侧直接 `@Autowired InstanceFileService` 即可。

---

## 5. 部署适配器改造

四个适配器在 deploy 完成后，往 `runtimeMetadata` 补充 `containerWorkDir` 字段（仅 Docker 类，Native 无需）。

### 5.1 DockerAdapter

**位置**: `backend/core/src/main/java/com/gameplatform/adapter/DockerAdapter.java`

```java
// deploy() 末尾，写入 runtimeMetadata 时
runtimeMetadata.put("containerId", containerId);
runtimeMetadata.put("containerWorkDir",
    config.getOrDefault("workingDir", "/home/steam"));
```

### 5.2 DockerComposeAdapter

**位置**: `backend/core/src/main/java/com/gameplatform/adapter/DockerComposeAdapter.java`

```java
runtimeMetadata.put("containerWorkDir",
    config.getOrDefault("workingDir", "/"));
runtimeMetadata.put("projectName", projectName);
runtimeMetadata.put("serviceName", primaryServiceName);  // 主服务名，如 "l4d2"
```

### 5.3 LinuxGsmDockerAdapter

**位置**: `backend/core/src/main/java/com/gameplatform/adapter/LinuxGsmDockerAdapter.java`

```java
runtimeMetadata.put("containerWorkDir", "/app");
runtimeMetadata.put("containerName", containerName);  // 已有，确认存在
```

### 5.4 LinuxGsmAdapter

无容器，无需写入 `containerWorkDir`。`installPath` 已存在，Native 路由直接用。

---

## 6. L4D2 插件迁移

### 6.1 L4D2PathResolver 改造

**位置**: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/resolver/L4D2PathResolver.java`

**改造方向**：所有 `getXxxPath` 方法返回**相对路径**（去掉 `installPath` 前缀，去掉 `instance` 参数）。

| 方法 | 改造前（绝对路径） | 改造后（相对路径） |
|---|---|---|
| `getGamePath(instance)` | `{installPath}/left4dead2` | `""` |
| `getAddonsPath(instance)` | `{installPath}/left4dead2/addons` | `"left4dead2/addons"` |
| `getSourceModPath(instance)` | `.../addons/sourcemod` | `"left4dead2/addons/sourcemod"` |
| `getSourceModPluginsPath(instance)` | `.../sourcemod/plugins` | `"left4dead2/addons/sourcemod/plugins"` |
| `getSourceModPluginsDisabledPath(instance)` | `.../plugins_disabled` | `"left4dead2/addons/sourcemod/plugins_disabled"` |
| `getSourceModConfigsPath(instance)` | `.../sourcemod/configs` | `"left4dead2/addons/sourcemod/configs"` |
| `getSourceModLogsPath(instance)` | `.../sourcemod/logs` | `"left4dead2/addons/sourcemod/logs"` |
| `getCfgPath(instance)` | `.../cfg` | `"left4dead2/cfg"` |
| `getSourceModCfgPath(instance)` | `.../cfg/sourcemod` | `"left4dead2/cfg/sourcemod"` |
| `getServerCfgPath(instance)` | `.../cfg/server.cfg` | `"left4dead2/cfg/server.cfg"` |
| `getMaplistPath(instance)` | `.../cfg/maplist.txt` | `"left4dead2/cfg/maplist.txt"` |
| `getMotdPath(instance)` | `.../motd.txt` | `"left4dead2/motd.txt"` |
| `getHostInfoPath(instance)` | `.../host.txt` | `"left4dead2/host.txt"` |
| `getHostnameConfigPath(instance)` | `.../hostname.txt` | `"left4dead2/hostname.txt"` |
| `getAdminsIniPath(instance)` | `.../admins_simple.ini` | `"left4dead2/addons/sourcemod/configs/admins_simple.ini"` |
| `getFileRefsPath(instance)` | `.../.file_refs.json` | `"left4dead2/addons/sourcemod/.file_refs.json"` |

### 6.2 8 个服务迁移

每个服务把 `FileAccessService` 注入替换为 `InstanceFileService`，调用点从 `(hostId, absolutePath)` 改为 `(instanceId, relativePath)`。

#### 6.2.1 MapService（混合模式保留）

仅远程部分迁移，本地 `Files.createTempFile`/`VpkParser.parse(File)`/`Files.deleteIfExists` 保留。

```java
// 改造前
fileAccessService.uploadLocalFile(hostId, targetPath, localTempPath);
fileAccessService.deleteFile(hostId, mapPath);
fileAccessService.downloadFile(hostId, remotePath, localTempPath);

// 改造后
instanceFileService.uploadLocalFile(instanceId,
    pathResolver.getAddonsPath() + "/" + filename, localTempPath);
instanceFileService.deleteFile(instanceId,
    pathResolver.getAddonsPath() + "/" + mapName);
instanceFileService.downloadFile(instanceId,
    pathResolver.getAddonsPath() + "/" + filename, localTempPath);
```

#### 6.2.2 PluginInstallService（混合模式保留）

本地 `ArchiveExtractUtil.extract*`/`Files.walk` 保留。

```java
// 改造后
instanceFileService.uploadLocalFile(instanceId,
    pathResolver.getSourceModPluginsPath() + "/" + filename, localPath);
instanceFileService.listFiles(instanceId, pathResolver.getSourceModPluginsPath());
instanceFileService.moveFile(instanceId,
    pathResolver.getSourceModPluginsPath() + "/" + name,
    pathResolver.getSourceModPluginsDisabledPath() + "/" + name);
instanceFileService.deleteFile(instanceId,
    pathResolver.getSourceModPluginsPath() + "/" + name);
instanceFileService.createDirectory(instanceId,
    pathResolver.getSourceModPluginsDisabledPath());
```

#### 6.2.3 ChunkUploadService（仅 complete 阶段迁移）

```java
// 改造后
instanceFileService.uploadLocalFile(instanceId,
    targetPath, mergedFile.getAbsolutePath());
```

#### 6.2.4 BackupService（纯 SPI 迁移）

```java
// 改造后
String adminsContent = instanceFileService.readTextFile(instanceId,
    pathResolver.getAdminsIniPath(), GbkCodecUtil.gbk());
instanceFileService.writeTextFile(instanceId,
    pathResolver.getHostnameConfigPath(), hostname, GbkCodecUtil.gbk());
List<FileInfo> plugins = instanceFileService.listFiles(instanceId,
    pathResolver.getSourceModPluginsPath());
```

#### 6.2.5 FileRefsService（纯 SPI 迁移）

```java
// 改造后
String json = instanceFileService.readTextFile(instanceId,
    pathResolver.getFileRefsPath());
instanceFileService.writeTextFile(instanceId,
    pathResolver.getFileRefsPath(), json);
```

#### 6.2.6 PluginExportService（混合模式保留）

本地 `ZipOutputStream` 保留。

```java
// 改造后
List<FileInfo> remoteFiles = instanceFileService.listFiles(instanceId,
    pathResolver.getGamePath() + "/addons/sourcemod/plugins");
instanceFileService.downloadFile(instanceId,
    relPath, localFile.getAbsolutePath());
```

#### 6.2.7 SourceModCfgService（纯 SPI 迁移）

```java
// 改造后
boolean exists = instanceFileService.exists(instanceId, absRelPath);
String content = instanceFileService.readTextFile(instanceId, absRelPath, GbkCodecUtil.gbk());
instanceFileService.writeTextFile(instanceId, absRelPath, serialized, GbkCodecUtil.gbk());
```

#### 6.2.8 SourceModLogService（纯 SPI 迁移，含 SSE 流式）

```java
// 改造后
List<FileInfo> files = instanceFileService.listFiles(instanceId,
    pathResolver.getSourceModLogsPath());
byte[] tail = instanceFileService.getFileBytes(instanceId,
    pathResolver.getSourceModLogsPath() + "/" + file,
    -MAX_CONTENT_BYTES, MAX_CONTENT_BYTES);
long newSize = instanceFileService.getFileInfo(instanceId, path).getSize();
instanceFileService.tailFile(instanceId, path, offset, GbkCodecUtil.gbk(), lineConsumer);
```

### 6.3 VpkTrimService 保留不动

`VpkTrimService` 是纯本地 `RandomAccessFile`（VPK 二进制裁剪），无法远程化。调用方 `MapService` 负责"远程下载到本地 temp → 调 VpkTrimService → 上传回远程"的中转，这个模式保留。

---

## 7. 独立部署模式实现

### 7.1 StandaloneInstanceFileService

**位置**: `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/host/StandaloneInstanceFileService.java`

```java
public class StandaloneInstanceFileService extends AbstractInstanceFileService {

    private final StandaloneFileAccessService fileAccessService;  // 复用现有 SFTP 实现
    private final StandaloneInstanceQueryService instanceQueryService;
    private final StandaloneHostQueryService hostQueryService;
    private final SshUtil sshUtil;

    @Override
    protected InstanceQueryService getInstanceQueryService() { return instanceQueryService; }

    @Override
    protected FileRoute nativeRoute(InstanceVO instance, String safeRel) {
        String resolvedPath = joinPath(instance.getInstallPath(), safeRel);
        return new FileRoute(instance.getId(), instance.getHostId(),
            instance.getDeployType(), safeRel, resolvedPath, null);
    }

    @Override
    protected FileRoute dockerRoute(InstanceVO instance, String safeRel) {
        // 与 core 的 InstanceFileServiceImpl 相同的 docker 路由逻辑
        // 委托 SshUtil 执行 docker exec/cp
    }
}
```

**实现策略**：复用 `AbstractInstanceFileService` 的路径校验、路由分发、摘要计算骨架。Native 路由委托 `StandaloneFileAccessService`（SFTP）；Docker 路由直接用 `SshUtil` 执行 `docker exec`/`docker cp`（standalone 模式无 `DockerFileService`，需自行实现等价逻辑）。

### 7.2 注入到 standalone 插件容器

在 standalone 模式的 Spring 配置中注册 `StandaloneInstanceFileService` 为 Bean，替换 core 的 `InstanceFileServiceImpl`。

---

## 8. 错误处理

### 8.1 异常体系

复用现有 `FileAccessService` 已有的异常约定，不新增异常类：

| 场景 | 异常类型 | 说明 |
|---|---|---|
| 实例不存在 | `IllegalArgumentException` | `instanceQueryService.getInstanceById` 返回 null |
| 部署类型不支持 | `UnsupportedOperationException` | 未知 `deployType` |
| 路径越界（含 `..`） | `IllegalArgumentException` | `validateRelativePath` 抛出 |
| 文件/目录不存在 | `FileNotFoundException` | SFTP/docker exec 返回 "No such file" |
| SSH/Docker 命令执行失败 | `IOException`（或 `RuntimeException` 包装） | 保留原始异常链 |
| 摘要算法不支持 | `NoSuchAlgorithmException` | `MessageDigest.getInstance` 失败 |
| `*sum` 命令不存在 | `UnsupportedOperationException` | exit code 127 |
| 容器 ID 解析失败 | `IllegalStateException` | `docker ps -q` 返回空或多个结果 |

### 8.2 异常传递

`InstanceFileServiceImpl` 不吞异常，原样向上抛。L4D2 各 Service 的 catch 块按现有模式处理（转换为 `L4D2PluginException` 或直接抛给 Controller 全局异常处理）。

---

## 9. 测试策略

### 9.1 单元测试（core 模块）

**InstanceFileServiceImplTest**：mock `InstanceQueryService`/`FileAccessService`/`DockerFileService`/`SshUtil`

- 路由测试：4 种 `deployType` 分别走 SFTP 或 docker
- 路径校验：`..` 拒绝、`./` 规范化、空路径视为根
- 摘要计算：
  - Native 路径 mock `sshUtil.executeCommand` 返回 `md5sum` 输出
  - Docker 路径 mock 三步 `docker cp` + `md5sum` + `rm`
- containerWorkDir 回退：`runtimeMetadata` 缺失时按部署类型取默认值
- containerId 解析：Compose 用 `docker compose ps -q` mock
- 异常场景：实例不存在、部署类型不支持、路径越界、命令不存在（exit 127）、文件不存在

### 9.2 集成测试（L4D2 插件）

- `MapServiceTest`、`PluginInstallServiceTest`、`SourceModLogServiceTest` 等：mock `InstanceFileService`，验证调用参数为 `(instanceId, relativePath)`
- 验证 `L4D2PathResolver` 返回相对路径（无 `installPath` 前缀）

### 9.3 适配器测试

- `DockerAdapterTest`、`DockerComposeAdapterTest`、`LinuxGsmDockerAdapterTest`：验证 deploy 后 `runtimeMetadata.containerWorkDir` 已写入

---

## 10. 迁移顺序

1. `plugin` 模块新增 `InstanceFileService` 接口 + `AbstractInstanceFileService` 基类
2. `core` 模块实现 `InstanceFileServiceImpl` + 注入到 `PluginSpringContextFactory`
3. 4 个适配器写入 `containerWorkDir`（Docker 类 3 个，Native 1 个无需改）
4. `L4D2PathResolver` 改造为相对路径（15 个方法去 instance 参数）
5. L4D2 8 个服务迁移到新 SPI（按依赖顺序）：
   1. `FileRefsService`（无外部依赖）
   2. `SourceModCfgService`
   3. `BackupService`
   4. `SourceModLogService`
   5. `MapService`（依赖 `VpkTrimService`/`VpkParserService`，本地部分保留）
   6. `PluginInstallService`（依赖 `FileRefsService`，本地部分保留）
   7. `ChunkUploadService`（仅 complete 阶段）
   8. `PluginExportService`（本地 ZIP 保留）
6. `plugin-l4d2-standalone` 模块实现 `StandaloneInstanceFileService`
7. 测试覆盖（单元 + 集成）

---

## 11. 文件清单

### 新增文件

| 路径 | 职责 |
|---|---|
| `backend/plugin/src/main/java/com/gameplatform/plugin/service/InstanceFileService.java` | SPI 接口定义 |
| `backend/plugin/src/main/java/com/gameplatform/plugin/service/AbstractInstanceFileService.java` | 抽象基类（路径校验、路由分发、摘要骨架） |
| `backend/core/src/main/java/com/gameplatform/plugin/service/InstanceFileServiceImpl.java` | core 模块实现（委托 FileAccessService + DockerFileService + SshUtil） |
| `backend/plugin-l4d2/plugin-l4d2-standalone/src/main/java/com/gameplatform/plugin/l4d2/standalone/host/StandaloneInstanceFileService.java` | standalone 模式实现 |
| `backend/core/src/test/java/com/gameplatform/plugin/service/InstanceFileServiceImplTest.java` | 单元测试 |

### 修改文件

| 路径 | 修改内容 |
|---|---|
| `backend/core/src/main/java/com/gameplatform/plugin/context/PluginSpringContextFactory.java` | 新增 InstanceFileService 注入 |
| `backend/core/src/main/java/com/gameplatform/adapter/DockerAdapter.java` | 写入 containerWorkDir |
| `backend/core/src/main/java/com/gameplatform/adapter/DockerComposeAdapter.java` | 写入 containerWorkDir + serviceName |
| `backend/core/src/main/java/com/gameplatform/adapter/LinuxGsmDockerAdapter.java` | 写入 containerWorkDir |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/resolver/L4D2PathResolver.java` | 15 个方法改为返回相对路径 |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/MapService.java` | 迁移到 InstanceFileService |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java` | 迁移到 InstanceFileService |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/ChunkUploadService.java` | 迁移到 InstanceFileService |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/BackupService.java` | 迁移到 InstanceFileService |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/FileRefsService.java` | 迁移到 InstanceFileService |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginExportService.java` | 迁移到 InstanceFileService |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModCfgService.java` | 迁移到 InstanceFileService |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/SourceModLogService.java` | 迁移到 InstanceFileService |

### 不变文件

| 路径 | 理由 |
|---|---|
| `backend/plugin/src/main/java/com/gameplatform/plugin/service/FileAccessService.java` | 零改动，作为底层被复用 |
| `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/VpkTrimService.java` | 纯本地 RandomAccessFile，无法远程化 |
| `backend/core/src/main/java/com/gameplatform/service/FileService.java` | SFTP 底层实现，不在本次范围 |
| `backend/core/src/main/java/com/gameplatform/service/docker/DockerFileService.java` | 被 InstanceFileServiceImpl 委托调用，自身不改 |

---

## 12. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| `DockerFileService` 方法签名与 `InstanceFileService` 不完全对齐 | 实现层需适配 | 在 `InstanceFileServiceImpl` 中做参数转换（如 `getFileContent` 的 encoding/lines 参数） |
| `DockerFileService` 缺 `tailFile`/`getFileBytes` | SourceModLogService 的 SSE 流式能力受影响 | 在 `InstanceFileServiceImpl` 中用 `docker exec tail -c` + 轮询实现等价能力 |
| `containerWorkDir` 未写入导致路径错误 | Docker 类实例文件访问失败 | 回退默认值 + WARN 日志 + 适配器测试覆盖 |
| Compose `containerId` 实时查询失败 | 多容器或服务名不匹配 | 抛 `IllegalStateException` 提示，前端展示明确错误 |
| `copyFile` 中转实现性能差（下载再上传） | 大文件复制慢 | 当前 YAGNI，仅在需要时优化为 `docker exec cp` 或 SFTP 远程复制 |
| standalone 模式 Docker 路由需自行实现 `docker exec/cp` 逻辑 | 代码重复 | 提取 `DockerCommandHelper` 工具类供 core 和 standalone 复用（如重复明显） |

---

## 13. 未来演进

1. **REST 层**：如前端文件管理需统一，在 `InstanceFileServiceImpl` 基础上新增 `InstanceFileController`（`/api/instances/{id}/files/...`），零额外业务逻辑
2. **FileService 复用 SshUtil 连接池**：独立优化项，消除每次 SFTP 调用 ~3s 握手开销
3. **FileInfo.owner 字段填充**：当前 `FileService.listFiles` 未 setOwner，可在本次或后续补齐
4. **copyFile 优化**：大文件场景改用 `docker exec cp`（容器内）或 SFTP 远程复制（Native）

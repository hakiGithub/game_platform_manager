package com.gameplatform.plugin.service;

import com.gameplatform.entity.GameMetadata;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameMetadataMapper;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.plugin.service.FileAccessService.FileInfo;
import com.gameplatform.util.AesUtil;
import com.gameplatform.util.SshUtil;
import com.gameplatform.vo.InstanceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * InstanceFileService 的 core 模块实现。
 *
 * <p>路由策略：
 * <ul>
 *   <li>Native（linuxgsm）→ 委托 {@link FileAccessService}（SFTP）</li>
 *   <li>Docker（docker / docker-compose / linuxgsm-docker）→ 委托 {@link SshUtil} + docker exec/cp</li>
 * </ul>
 *
 * <p>SSH 凭据：通过 {@link HostMapper} 查询 Host 实体并使用 {@link AesUtil} 解密，
 * 与 {@code FileAccessServiceImpl} / {@code AbstractDeployAdapter} 保持一致。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstanceFileServiceImpl extends AbstractInstanceFileService {

    private final InstanceQueryService instanceQueryService;
    private final FileAccessService fileAccessService;
    private final SshUtil sshUtil;
    private final HostMapper hostMapper;
    private final GameMetadataMapper gameMetadataMapper;

    @Override
    protected InstanceQueryService getInstanceQueryService() {
        return instanceQueryService;
    }

    @Override
    protected FileRoute buildRoute(InstanceVO instance, String safeRel) {
        String deployType = instance.getDeployType();
        if (deployType == null || deployType.isEmpty()) {
            deployType = "linuxgsm";
        }
        if (isNativeDeploy(deployType)) {
            String resolvedPath = joinPath(instance.getInstallPath(), safeRel);
            return FileRoute.nativeRoute(instance.getId(), instance.getHostId(),
                deployType, safeRel, resolvedPath);
        }
        if (isDockerDeploy(deployType)) {
            Map<String, Object> metadata = instance.getConfigInfo();
            if (metadata == null) {
                metadata = Map.of();
            }
            String containerWorkDir = getString(metadata, "containerWorkDir",
                getString(metadata, "workDir", defaultContainerWorkDir(deployType)));
            // 老实例 configInfo 未记录 workingDir 时，containerWorkDir 会回退到默认值 "/"，
            // 导致路径解析错误（如 /left4dead2/... 而非 /l4d2/left4dead2/...）。
            // 此时从游戏元数据 deployConfig.<deployType>.workingDir 回退读取。
            if (containerWorkDir == null || containerWorkDir.isBlank() || "/".equals(containerWorkDir)) {
                String gameWorkingDir = resolveWorkingDirFromMetadata(instance.getGameCode(), deployType);
                if (gameWorkingDir != null && !gameWorkingDir.isBlank() && !"/".equals(gameWorkingDir)) {
                    containerWorkDir = gameWorkingDir;
                }
            }
            String containerId = resolveContainerId(instance, metadata);
            String resolvedPath = joinPath(containerWorkDir, safeRel);
            return FileRoute.dockerRoute(instance.getId(), instance.getHostId(),
                deployType, safeRel, resolvedPath, containerId);
        }
        throw new UnsupportedOperationException("不支持的部署类型: " + deployType);
    }

    // ===== 路由辅助方法 =====

    private boolean isNativeDeploy(String deployType) {
        return "linuxgsm".equals(deployType) || "native".equals(deployType);
    }

    private boolean isDockerDeploy(String deployType) {
        return "docker".equals(deployType) || "docker-compose".equals(deployType)
            || "linuxgsm-docker".equals(deployType);
    }

    private String defaultContainerWorkDir(String deployType) {
        return switch (deployType) {
            case "docker" -> "/home/steam";
            case "docker-compose" -> "/";
            case "linuxgsm-docker" -> "/app";
            default -> "/";
        };
    }

    /**
     * 从游戏元数据 deployConfig.<deployType>.workingDir 回退读取容器工作目录。
     *
     * <p>用于老实例：部署时未将 workingDir 写入 configInfo，导致 buildRoute 回退到默认值 "/"，
     * 路径解析错误。此方法从 l4d2.yml 等游戏配置的 workingDir 字段获取真实路径（如 /l4d2）。
     *
     * @param gameCode 游戏代码（如 l4d2）
     * @param deployType 部署类型（docker / docker-compose / linuxgsm-docker）
     * @return workingDir 配置值；元数据不存在或字段缺失时返回 null
     */
    private String resolveWorkingDirFromMetadata(String gameCode, String deployType) {
        if (gameCode == null || gameCode.isBlank()) return null;
        try {
            GameMetadata meta = gameMetadataMapper.selectByGameCode(gameCode);
            if (meta == null || meta.getDeployConfig() == null) return null;
            Map<String, Object> deployConfig = meta.getDeployConfig();
            String configKey = switch (deployType) {
                case "docker" -> "docker";
                case "docker-compose" -> "docker-compose";
                case "linuxgsm-docker" -> "linuxgsm-docker";
                default -> null;
            };
            if (configKey == null) return null;
            Object configObj = deployConfig.get(configKey);
            if (!(configObj instanceof Map<?, ?> configMap)) return null;
            Object workingDir = configMap.get("workingDir");
            return workingDir != null ? workingDir.toString() : null;
        } catch (Exception e) {
            log.warn("从游戏元数据获取 workingDir 失败 gameCode={}, deployType={}, err={}",
                    gameCode, deployType, e.getMessage());
            return null;
        }
    }

    /**
     * 解析容器标识：优先用 metadata.containerId，其次 containerName；
     * docker-compose 优先用 projectName + serviceName 动态查询，缺失时回退到容器名查询。
     */
    private String resolveContainerId(InstanceVO instance, Map<String, Object> metadata) {
        String deployType = instance.getDeployType();

        String containerId = getString(metadata, "containerId", null);
        if (containerId != null && !containerId.isBlank()) {
            return containerId;
        }

        if ("docker".equals(deployType) || "linuxgsm-docker".equals(deployType)) {
            String containerName = resolveContainerName(metadata);
            if (containerName != null && !containerName.isBlank()) {
                return containerName;
            }
            throw new IllegalStateException(
                deployType + " 实例缺少 containerId/containerName: " + instance.getId());
        }

        if ("docker-compose".equals(deployType)) {
            String projectName = getString(metadata, "projectName", null);
            String serviceName = getString(metadata, "serviceName", null);
            HostConnection conn = getHostConnection(instance.getHostId());
            String cmd;
            if (projectName != null && serviceName != null) {
                // 优先用 projectName + serviceName 精确查询
                cmd = "docker compose -p " + projectName + " ps -q " + serviceName;
            } else {
                // 回退：用容器名查询（兼容老数据，容器名可能存储为
                // containerName / CONTAINER_NAME / container_name 多种命名风格）
                String containerName = resolveContainerName(metadata);
                if (containerName == null || containerName.isBlank()) {
                    throw new IllegalStateException(
                        "docker-compose 实例缺少 projectName/serviceName 或 containerName: "
                        + instance.getId());
                }
                cmd = "docker ps -q -f name=" + containerName;
            }
            SshUtil.CommandResult r = sshUtil.executeCommand(
                conn.host, conn.port, conn.username, conn.privateKey, conn.password, cmd);
            if (r.getExitCode() != 0) {
                throw new IllegalStateException("解析容器 ID 失败: " + r.getError());
            }
            String output = r.getOutput().trim();
            if (output.isEmpty()) {
                throw new IllegalStateException(
                    "无法解析容器 ID（容器未运行）: " + instance.getId());
            }
            String[] lines = output.split("\n");
            if (lines.length > 1) {
                throw new IllegalStateException(
                    "容器 ID 不唯一，匹配到 " + lines.length + " 个容器");
            }
            return lines[0];
        }

        throw new IllegalStateException("不支持的部署类型: " + deployType);
    }

    /**
     * 解析容器名：兼容多种命名风格。
     * containerName → CONTAINER_NAME → container_name
     */
    private String resolveContainerName(Map<String, Object> metadata) {
        String name = getString(metadata, "containerName", null);
        if (name == null || name.isBlank()) {
            name = getString(metadata, "CONTAINER_NAME", null);
        }
        if (name == null || name.isBlank()) {
            name = getString(metadata, "container_name", null);
        }
        return name;
    }

    // ===== 文本读写 =====

    @Override
    public String readTextFile(long instanceId, String relativePath) {
        return readTextFile(instanceId, relativePath, Charset.defaultCharset());
    }

    @Override
    public String readTextFile(long instanceId, String relativePath, Charset charset) {
        FileRoute route = resolveRoute(instanceId, relativePath);
        if (route.isNative()) {
            return fileAccessService.readTextFile(route.hostId, route.resolvedPath, charset);
        }
        return dockerReadTextFile(route, charset);
    }

    @Override
    public void writeTextFile(long instanceId, String relativePath, String content) {
        writeTextFile(instanceId, relativePath, content, Charset.defaultCharset());
    }

    @Override
    public void writeTextFile(long instanceId, String relativePath, String content, Charset charset) {
        FileRoute route = resolveRoute(instanceId, relativePath);
        if (route.isNative()) {
            fileAccessService.writeTextFile(route.hostId, route.resolvedPath, content);
            return;
        }
        dockerWriteTextFile(route, content, charset);
    }

    // ===== 二进制读写 =====

    @Override
    public byte[] downloadFileToMemory(long instanceId, String relativePath) {
        FileRoute route = resolveRoute(instanceId, relativePath);
        if (route.isNative()) {
            return fileAccessService.downloadFileToMemory(route.hostId, route.resolvedPath);
        }
        // Docker: docker cp 到本地 temp 再读取
        Path temp = null;
        try {
            temp = Files.createTempFile("gp-download-", ".bin");
            SshUtil.CommandResult r = executeOnHost(route.hostId,
                "docker cp " + route.containerId + ":" + shellQuote(route.resolvedPath) + " " + shellQuote(temp.toAbsolutePath().toString()));
            if (r.getExitCode() != 0) {
                throw new RuntimeException("docker cp 失败: " + r.getError());
            }
            return Files.readAllBytes(temp);
        } catch (IOException e) {
            throw new RuntimeException("读取下载文件失败", e);
        } finally {
            deleteTempQuietly(temp);
        }
    }

    @Override
    public byte[] getFileBytes(long instanceId, String relativePath, long offset, long length) {
        FileRoute route = resolveRoute(instanceId, relativePath);
        if (route.isNative()) {
            return fileAccessService.getFileBytes(route.hostId, route.resolvedPath, offset, length);
        }
        // Docker: 用 tail -c + dd 读取字节范围，base64 编码避免传输损坏
        long start = offset >= 0 ? offset + 1 : 1;
        String innerCmd = String.format(
            "tail -c +%d %s 2>/dev/null | dd bs=1 count=%d 2>/dev/null | base64 -w0",
            start, shellQuote(route.resolvedPath), length);
        SshUtil.CommandResult r = execDocker(route, innerCmd);
        if (r.getExitCode() != 0) {
            throw new RuntimeException("读取容器文件字节失败: " + r.getError());
        }
        return Base64.getDecoder().decode(r.getOutput().trim());
    }

    // ===== 上传/下载 =====

    @Override
    public void uploadLocalFile(long instanceId, String relativePath, String localPath) {
        FileRoute route = resolveRoute(instanceId, relativePath);
        if (route.isNative()) {
            fileAccessService.uploadLocalFile(route.hostId, route.resolvedPath, localPath);
            return;
        }
        // Docker: 先 SFTP 上传到主机 temp，再 docker cp 进容器
        HostConnection conn = getHostConnection(route.hostId);
        String tempHostPath = "/tmp/.gp-upload-" + UUID.randomUUID();
        try {
            boolean uploaded = sshUtil.uploadFile(
                conn.host, conn.port, conn.username, conn.privateKey, conn.password,
                localPath, tempHostPath);
            if (!uploaded) {
                throw new RuntimeException("SFTP 上传到主机临时路径失败: " + tempHostPath);
            }
            // docker cp 不会自动创建父目录，需要先 mkdir -p
            String parentDir = route.resolvedPath;
            int lastSlash = parentDir.lastIndexOf('/');
            if (lastSlash > 0) {
                parentDir = parentDir.substring(0, lastSlash);
                sshUtil.executeCommand(
                    conn.host, conn.port, conn.username, conn.privateKey, conn.password,
                    "docker exec " + route.containerId + " mkdir -p " + shellQuote(parentDir));
            }
            SshUtil.CommandResult r = sshUtil.executeCommand(
                conn.host, conn.port, conn.username, conn.privateKey, conn.password,
                "docker cp " + shellQuote(tempHostPath) + " " + route.containerId + ":" + shellQuote(route.resolvedPath));
            if (r.getExitCode() != 0) {
                throw new RuntimeException("docker cp 进容器失败: " + r.getError());
            }
        } finally {
            cleanupRemoteTemp(conn, tempHostPath);
        }
    }

    @Override
    public void downloadFile(long instanceId, String relativePath, String localPath) {
        FileRoute route = resolveRoute(instanceId, relativePath);
        if (route.isNative()) {
            fileAccessService.downloadFile(route.hostId, route.resolvedPath, localPath);
            return;
        }
        // Docker: docker cp 到主机 temp，再 SFTP 下载到本地
        HostConnection conn = getHostConnection(route.hostId);
        String tempHostPath = "/tmp/.gp-download-" + UUID.randomUUID();
        try {
            SshUtil.CommandResult r = sshUtil.executeCommand(
                conn.host, conn.port, conn.username, conn.privateKey, conn.password,
                "docker cp " + route.containerId + ":" + shellQuote(route.resolvedPath) + " " + shellQuote(tempHostPath));
            if (r.getExitCode() != 0) {
                throw new RuntimeException("docker cp 失败: " + r.getError());
            }
            boolean downloaded = sshUtil.downloadFile(
                conn.host, conn.port, conn.username, conn.privateKey, conn.password,
                tempHostPath, localPath);
            if (!downloaded) {
                throw new RuntimeException("SFTP 下载主机临时文件失败: " + tempHostPath);
            }
        } finally {
            cleanupRemoteTemp(conn, tempHostPath);
        }
    }

    // ===== 文件管理 =====

    @Override
    public void deleteFile(long instanceId, String relativePath) {
        FileRoute route = resolveRoute(instanceId, relativePath);
        if (route.isNative()) {
            fileAccessService.deleteFile(route.hostId, route.resolvedPath);
            return;
        }
        execDocker(route, "rm -f " + shellQuote(route.resolvedPath));
    }

    @Override
    public void moveFile(long instanceId, String oldRelativePath, String newRelativePath) {
        FileRoute srcRoute = resolveRoute(instanceId, oldRelativePath);
        FileRoute dstRoute = resolveRoute(instanceId, newRelativePath);
        if (srcRoute.isNative() != dstRoute.isNative()) {
            throw new UnsupportedOperationException("跨 Native/Docker 移动不支持");
        }
        if (srcRoute.isNative()) {
            fileAccessService.moveFile(srcRoute.hostId, srcRoute.resolvedPath, dstRoute.resolvedPath);
            return;
        }
        execDocker(srcRoute, "mv " + shellQuote(srcRoute.resolvedPath) + " " + shellQuote(dstRoute.resolvedPath));
    }

    @Override
    public void copyFile(long instanceId, String srcRelativePath, String dstRelativePath) {
        FileRoute srcRoute = resolveRoute(instanceId, srcRelativePath);
        FileRoute dstRoute = resolveRoute(instanceId, dstRelativePath);
        if (srcRoute.isNative() != dstRoute.isNative()) {
            throw new UnsupportedOperationException("跨 Native/Docker 复制不支持");
        }
        if (srcRoute.isNative()) {
            // FileAccessService 无 copyFile，用 download+upload 中转
            Path temp = null;
            try {
                temp = Files.createTempFile("gp-copy-", ".bin");
                fileAccessService.downloadFile(srcRoute.hostId, srcRoute.resolvedPath,
                    temp.toAbsolutePath().toString());
                fileAccessService.uploadLocalFile(dstRoute.hostId, dstRoute.resolvedPath,
                    temp.toAbsolutePath().toString());
            } catch (IOException e) {
                throw new RuntimeException("复制文件失败", e);
            } finally {
                deleteTempQuietly(temp);
            }
            return;
        }
        execDocker(srcRoute, "cp " + shellQuote(srcRoute.resolvedPath) + " " + shellQuote(dstRoute.resolvedPath));
    }

    @Override
    public boolean exists(long instanceId, String relativePath) {
        FileRoute route = resolveRoute(instanceId, relativePath);
        if (route.isNative()) {
            return fileAccessService.exists(route.hostId, route.resolvedPath);
        }
        SshUtil.CommandResult r = execDocker(route,
            "test -e " + shellQuote(route.resolvedPath) + " && echo yes || echo no");
        return r.getOutput().trim().equals("yes");
    }

    @Override
    public FileInfo getFileInfo(long instanceId, String relativePath) {
        FileRoute route = resolveRoute(instanceId, relativePath);
        if (route.isNative()) {
            return fileAccessService.getFileInfo(route.hostId, route.resolvedPath);
        }
        // Docker: docker exec stat
        SshUtil.CommandResult r = execDocker(route,
            "stat -c '%n|%s|%Y' " + shellQuote(route.resolvedPath));
        if (r.getExitCode() != 0) {
            return null;
        }
        String[] parts = r.getOutput().trim().split("\\|");
        FileInfo info = new FileInfo();
        String resolved = route.resolvedPath;
        info.setName(resolved.substring(resolved.lastIndexOf('/') + 1));
        info.setPath(resolved);
        info.setDirectory(false);
        info.setSize(parts.length > 1 ? parseLongSafe(parts[1]) : 0);
        info.setLastModified(parts.length > 2 ? parseLongSafe(parts[2]) * 1000 : 0);
        return info;
    }

    // ===== 目录管理 =====

    @Override
    public List<FileInfo> listFiles(long instanceId, String relativePath) {
        FileRoute route = resolveRoute(instanceId, relativePath);
        if (route.isNative()) {
            return fileAccessService.listFiles(route.hostId, route.resolvedPath);
        }
        // Docker: docker exec ls -la，解析输出为 FileInfo 列表
        SshUtil.CommandResult r = execDocker(route, "ls -la " + shellQuote(route.resolvedPath));
        if (r.getExitCode() != 0) {
            return List.of();
        }
        return parseLsOutput(r.getOutput(), route.resolvedPath);
    }

    @Override
    public void createDirectory(long instanceId, String relativePath) {
        FileRoute route = resolveRoute(instanceId, relativePath);
        if (route.isNative()) {
            fileAccessService.createDirectory(route.hostId, route.resolvedPath);
            return;
        }
        execDocker(route, "mkdir -p " + shellQuote(route.resolvedPath));
    }

    @Override
    public void deleteDirectory(long instanceId, String relativePath, boolean recursive) {
        FileRoute route = resolveRoute(instanceId, relativePath);
        if (route.isNative()) {
            fileAccessService.deleteDirectory(route.hostId, route.resolvedPath, recursive);
            return;
        }
        // Linux rmdir 不支持 -r/-f，删除目录（含递归）统一使用 rm -rf；
        // 非递归删除空目录可用 rmdir，但插件目录通常非空，统一按递归处理。
        execDocker(route, "rm -rf " + shellQuote(route.resolvedPath));
    }

    @Override
    public void copyDirectory(long instanceId, String srcRelativePath, String dstRelativePath) {
        FileRoute srcRoute = resolveRoute(instanceId, srcRelativePath);
        FileRoute dstRoute = resolveRoute(instanceId, dstRelativePath);
        if (srcRoute.isNative() != dstRoute.isNative()) {
            throw new UnsupportedOperationException("跨 Native/Docker 目录复制不支持");
        }
        // 注意：此处不删除目标目录本身，仅合并/覆盖内容，避免误删游戏根目录。
        // 调用方（如插件启用）如需清理应先自行处理。
        if (srcRoute.isNative()) {
            HostConnection conn = getHostConnection(srcRoute.hostId);
            String cmd = "mkdir -p " + shellQuote(dstRoute.resolvedPath)
                    + " && cp -rT " + shellQuote(srcRoute.resolvedPath)
                    + " " + shellQuote(dstRoute.resolvedPath);
            SshUtil.CommandResult r = sshUtil.executeCommand(
                    conn.host, conn.port, conn.username, conn.privateKey, conn.password, cmd);
            if (r.getExitCode() != 0) {
                throw new RuntimeException("Native 目录复制失败: " + r.getError());
            }
            return;
        }
        // Docker: 容器内 cp -rT 合并到目标目录
        execDocker(srcRoute,
                "mkdir -p " + shellQuote(dstRoute.resolvedPath)
                        + " && cp -rT " + shellQuote(srcRoute.resolvedPath)
                        + " " + shellQuote(dstRoute.resolvedPath));
    }

    // ===== 流式增量 =====

    @Override
    public long tailFile(long instanceId, String relativePath, long offset,
                         Charset charset, Consumer<String> lineConsumer) {
        FileRoute route = resolveRoute(instanceId, relativePath);
        if (route.isNative()) {
            return fileAccessService.tailFile(route.hostId, route.resolvedPath,
                offset, charset, lineConsumer);
        }
        // Docker: 读取从 offset 字节开始的新内容，用 base64 编码避免字符集损坏
        FileInfo info = getFileInfo(instanceId, relativePath);
        if (info == null) {
            return offset;
        }
        long currentSize = info.getSize();
        if (currentSize <= offset) {
            return offset;
        }
        String innerCmd = String.format(
            "tail -c +%d %s 2>/dev/null | base64 -w0", offset + 1, shellQuote(route.resolvedPath));
        SshUtil.CommandResult r = execDocker(route, innerCmd);
        if (r.getExitCode() != 0) {
            throw new RuntimeException("tail 容器文件失败: " + r.getError());
        }
        byte[] bytes = Base64.getDecoder().decode(r.getOutput().trim());
        String content = new String(bytes, charset);
        for (String line : content.split("\n", -1)) {
            lineConsumer.accept(line);
        }
        return currentSize;
    }

    // ===== 文件摘要 =====

    @Override
    public String computeDigest(long instanceId, String relativePath, String algorithm) {
        if (!algorithm.matches("[A-Za-z0-9-]+")) {
            throw new IllegalArgumentException("非法算法名: " + algorithm);
        }
        try {
            MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("不支持的摘要算法: " + algorithm, e);
        }
        FileRoute route = resolveRoute(instanceId, relativePath);
        HostConnection conn = getHostConnection(route.hostId);
        String sumCmd = algorithm.toLowerCase() + "sum";
        if (route.isNative()) {
            SshUtil.CommandResult r = sshUtil.executeCommand(
                conn.host, conn.port, conn.username, conn.privateKey, conn.password,
                sumCmd + " " + route.resolvedPath);
            if (r.getExitCode() == 127) {
                throw new UnsupportedOperationException("主机不支持 " + sumCmd + " 命令");
            }
            if (r.getExitCode() != 0) {
                throw new RuntimeException("摘要计算失败: " + r.getError());
            }
            return r.getOutput().trim().split("\\s+")[0];
        }
        // Docker: docker cp 到主机 temp + *sum + 清理
        String tempHostPath = "/tmp/.gp-digest-" + UUID.randomUUID();
        try {
            SshUtil.CommandResult cp = sshUtil.executeCommand(
                conn.host, conn.port, conn.username, conn.privateKey, conn.password,
                "docker cp " + route.containerId + ":" + shellQuote(route.resolvedPath) + " " + shellQuote(tempHostPath));
            if (cp.getExitCode() != 0) {
                throw new RuntimeException("docker cp 失败: " + cp.getError());
            }
            SshUtil.CommandResult sum = sshUtil.executeCommand(
                conn.host, conn.port, conn.username, conn.privateKey, conn.password,
                sumCmd + " " + tempHostPath);
            if (sum.getExitCode() == 127) {
                throw new UnsupportedOperationException("主机不支持 " + sumCmd + " 命令");
            }
            if (sum.getExitCode() != 0) {
                throw new RuntimeException("摘要计算失败: " + sum.getError());
            }
            return sum.getOutput().trim().split("\\s+")[0];
        } finally {
            cleanupRemoteTemp(conn, tempHostPath);
        }
    }

    // ===== Docker 辅助方法 =====

    /**
     * 在容器内执行命令（docker exec sh -c '...'）。
     * 单引号转义遵循 POSIX 规则：' → '\''
     */
    private SshUtil.CommandResult execDocker(FileRoute route, String innerCmd) {
        String escaped = innerCmd.replace("'", "'\\''");
        String cmd = "docker exec " + route.containerId + " sh -c '" + escaped + "'";
        SshUtil.CommandResult r = executeOnHost(route.hostId, cmd);
        if (r.getExitCode() != 0 && !innerCmd.startsWith("test -e")) {
            log.warn("docker exec 失败: cmd={}, exit={}, stderr={}",
                innerCmd, r.getExitCode(), r.getError());
        }
        return r;
    }

    /**
     * 读取容器内文本文件：用 base64 编码避免字符集在传输中损坏。
     */
    private String dockerReadTextFile(FileRoute route, Charset charset) {
        SshUtil.CommandResult r = execDocker(route, "base64 -w0 " + shellQuote(route.resolvedPath));
        if (r.getExitCode() != 0) {
            throw new RuntimeException("读取容器文件失败: " + r.getError());
        }
        byte[] bytes = Base64.getDecoder().decode(r.getOutput().trim());
        return new String(bytes, charset);
    }

    /**
     * 写入容器内文本文件：用 base64 编码内容后解码写入，避免特殊字符问题。
     */
    private void dockerWriteTextFile(FileRoute route, String content, Charset charset) {
        String base64 = Base64.getEncoder().encodeToString(content.getBytes(charset));
        String innerCmd = "echo '" + base64 + "' | base64 -d > " + shellQuote(route.resolvedPath);
        SshUtil.CommandResult r = execDocker(route, innerCmd);
        if (r.getExitCode() != 0) {
            throw new RuntimeException("写入容器文件失败: " + r.getError());
        }
    }

    /**
     * 解析 ls -la 输出为 FileInfo 列表（跳过 total 行与 . / .. 条目）。
     */
    private List<FileInfo> parseLsOutput(String lsOutput, String basePath) {
        return lsOutput.lines()
            .filter(line -> !line.startsWith("total"))
            .filter(line -> !line.isEmpty())
            .map(line -> {
                String[] parts = line.split("\\s+", 9);
                if (parts.length < 9) return null;
                String name = parts[8];
                if (".".equals(name) || "..".equals(name)) return null;
                FileInfo info = new FileInfo();
                info.setName(name);
                info.setPath(basePath + "/" + name);
                info.setDirectory(parts[0].startsWith("d"));
                info.setPermissions(parts[0]);
                info.setSize(parseLongSafe(parts[4]));
                return info;
            })
            .filter(Objects::nonNull)
            .toList();
    }

    // ===== SSH 连接辅助 =====

    /**
     * 在指定主机上执行 SSH 命令（解密凭据后委托 SshUtil）。
     */
    private SshUtil.CommandResult executeOnHost(long hostId, String command) {
        HostConnection conn = getHostConnection(hostId);
        return sshUtil.executeCommand(
            conn.host, conn.port, conn.username, conn.privateKey, conn.password, command);
    }

    /**
     * 获取主机 SSH 连接信息：查询 Host 实体并解密私钥/密码。
     * 与 {@code FileAccessServiceImpl} / {@code AbstractDeployAdapter} 保持一致。
     */
    private HostConnection getHostConnection(long hostId) {
        Host host = hostMapper.selectById(hostId);
        if (host == null) {
            throw new IllegalStateException("主机不存在: " + hostId);
        }
        HostConnection conn = new HostConnection();
        conn.host = host.getIpAddress();
        conn.port = host.getSshPort() != null ? host.getSshPort() : 22;
        conn.username = host.getSshUser();
        if (host.getSshPrivateKey() != null && !host.getSshPrivateKey().isEmpty()) {
            conn.privateKey = AesUtil.decrypt(host.getSshPrivateKey());
        }
        if (host.getSshPassword() != null && !host.getSshPassword().isEmpty()) {
            conn.password = AesUtil.decrypt(host.getSshPassword());
        }
        return conn;
    }

    /**
     * 对 shell 参数进行简单转义，避免路径中包含空格或特殊字符导致命令解析错误。
     */
    private String shellQuote(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * 清理远程主机临时文件（best-effort，失败仅告警）。
     */
    private void cleanupRemoteTemp(HostConnection conn, String tempHostPath) {
        try {
            sshUtil.executeCommand(
                conn.host, conn.port, conn.username, conn.privateKey, conn.password,
                "rm -f " + tempHostPath);
        } catch (Exception e) {
            log.warn("清理远程临时文件失败: {}", tempHostPath, e);
        }
    }

    // ===== 通用工具 =====

    private String getString(Map<String, Object> map, String key, String defaultVal) {
        Object v = map.get(key);
        if (v == null) return defaultVal;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? defaultVal : s;
    }

    private long parseLongSafe(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void deleteTempQuietly(Path temp) {
        if (temp != null) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 主机 SSH 连接信息（解密后的凭据）。
     */
    private static class HostConnection {
        String host;
        int port;
        String username;
        String privateKey;
        String password;
    }
}

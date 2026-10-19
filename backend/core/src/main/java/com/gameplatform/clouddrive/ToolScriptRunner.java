package com.gameplatform.clouddrive;

import com.gameplatform.hostaccess.CommandResult;
import com.gameplatform.hostaccess.RemoteHost;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.plugin.service.ContainerIdResolver;
import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.entity.Host;
import com.gameplatform.entity.GameMetadata;
import com.gameplatform.mapper.GameMetadataMapper;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.plugin.patch.HostCapabilities;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 主机侧工具脚本执行器：实例寻址 + 脚本分发 + 原始输出返回。
 *
 * <p>对外按<b>实例相对路径</b>寻址（与 InstanceFileService 同语义），内部路由：
 * <ul>
 *   <li>Native 实例：直接定位 {@code installPath/<rel>}；</li>
 *   <li>Docker 实例：脚本送入容器，直接读取容器内原文件；缺 python3 时由
 *       platform-tools 将目标卷挂到独立路径，不复制 VPK。</li>
 * </ul>
 * 摘要走主机原生 sha256sum/shasum（Docker 实例在容器内计算，容器兜底）；
 * 插件脚本由宿主登记、调用时分发、用完即删；执行通道为主机/目标容器原生
 * python3，缺失时回退 platform-tools 工具容器。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolScriptRunner {

    private static final String TOOLING_PYTHON = "/usr/bin/python3";
    private static final String TOOLING_TARGET_ROOT = "/gp-target";

    private final DeploymentAccess deployAccess;
    private final RemoteHost remoteHost;
    private final HostMapper hostMapper;
    private final GameMetadataMapper gameMetadataMapper;
    private final InstanceQueryService instanceQueryService;
    private final ContainerIdResolver containerIdResolver;
    private final com.gameplatform.patch.HostCapabilityProber prober;
    private final ToolScriptRegistry toolScriptRegistry;

    @Value("${game-platform.patch.tooling-image:registry.cn-shenzhen.aliyuncs.com/haki_hub/platform-tools:latest}")
    private String toolingImage;

    // ===== 实例寻址入口（SDK 语义） =====

    /** 实例侧文件 sha-256 摘要 */
    public String fileDigest(long instanceId, String relativePath) {
        Materialized ctx = prepare(instanceId, relativePath);
        try {
            return fileDigest(ctx);
        } finally {
            cleanup(ctx);
        }
    }

    /** 在实例上下文运行宿主已登记的插件工具脚本，返回原始 stdout。 */
    public String runToolScript(long instanceId, String relativePath, String scriptId) {
        Materialized ctx = prepare(instanceId, relativePath);
        return runToolScript(ctx, scriptId, relativePath);
    }

    // ===== 实例路径物化 =====

    private record Materialized(Host host, HostCapabilities caps, String hostPath,
                                String containerId, String containerPath) {
        boolean isDocker() {
            return containerId != null && !containerId.isBlank();
        }
    }

    private Materialized prepare(long instanceId, String relativePath) {
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            throw new BusinessException("实例不存在: " + instanceId);
        }
        Host host = hostMapper.selectById(instance.getHostId());
        if (host == null) {
            throw new BusinessException("主机不存在: " + instance.getHostId());
        }
        HostCapabilities caps = prober.probe(host.getId());
        String hostPath;
        String containerId = null;
        String containerPath = null;
        String deployType = deployAccess.classify(instance.getDeployType()).getCode();
        if (deployAccess.isNativeDeploy(deployType)) {
            hostPath = joinPath(instance.getInstallPath(), relativePath);
        } else {
            // Docker：始终在容器内执行/计算摘要，不把大文件复制到主机。
            // 工具容器把目标卷挂到独立根，避免覆盖工具镜像自身的 /usr/bin/python3。
            containerId = containerIdResolver.resolve(instance, metadataOf(instance));
            if (containerId == null || containerId.isBlank()) {
                throw new BusinessException("实例容器 ID 未记录，无法访问容器内文件（可重启实例重建元数据）");
            }
            containerPath = joinPath(containerWorkDir(instance, deployType), relativePath);
            hostPath = null;
        }
        return new Materialized(host, caps, hostPath, containerId, containerPath);
    }

    private void cleanup(Materialized ctx) {
        // 当前路径不产生主机临时文件；脚本的 finally 已负责清理。
    }

    private Map<String, Object> metadataOf(InstanceVO instance) {
        Map<String, Object> metadata = new HashMap<>();
        if (instance.getRuntimeMetadata() != null) {
            metadata.putAll(instance.getRuntimeMetadata());
        }
        if (instance.getConfigInfo() != null) {
            metadata.putAll(instance.getConfigInfo());
        }
        return metadata;
    }

    private String containerWorkDir(InstanceVO instance, String deployType) {
        Map<String, Object> metadata = metadataOf(instance);
        String workDir = stringValue(metadata, "containerWorkDir");
        if (workDir == null || workDir.isBlank() || "/".equals(workDir)) {
            workDir = stringValue(metadata, "workDir");
        }
        if (workDir == null || workDir.isBlank() || "/".equals(workDir)) {
            workDir = gameWorkingDir(instance.getGameCode(), deployType);
        }
        if (workDir == null || workDir.isBlank() || "/".equals(workDir)) {
            workDir = switch (deployType) {
                case "docker" -> "/home/steam";
                case "linuxgsm-docker" -> "/app";
                default -> "/";
            };
        }
        return workDir != null && !workDir.isBlank() && !"/".equals(workDir) ? workDir : "/";
    }

    private String gameWorkingDir(String gameCode, String deployType) {
        if (gameCode == null || gameCode.isBlank()) {
            return null;
        }
        try {
            GameMetadata metadata = gameMetadataMapper.selectByGameCode(gameCode);
            if (metadata == null || metadata.getDeployConfig() == null) {
                return null;
            }
            Object config = metadata.getDeployConfig().get(deployType);
            if (!(config instanceof Map<?, ?> map)) {
                return null;
            }
            Object workingDir = map.get("workingDir");
            return workingDir != null ? String.valueOf(workingDir) : null;
        } catch (Exception e) {
            log.warn("从游戏元数据获取容器工作目录失败 gameCode={}, deployType={}: {}",
                    gameCode, deployType, e.getMessage());
            return null;
        }
    }

    private static String stringValue(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? String.valueOf(v) : null;
    }

    // ===== 主机层原语 =====

    /** 计算实例文件 sha-256（Native 主机原生优先；Docker 容器内优先） */
    private String fileDigest(Materialized ctx) {
        return fileDigest(ctx.host(), ctx.caps(), ctx.hostPath(),
                ctx.containerId(), ctx.containerPath());
    }

    /** 计算主机侧文件 sha-256（保留给主机工具内部复用） */
    public String fileDigest(Host host, HostCapabilities caps, String remotePath) {
        return fileDigest(host, caps, remotePath, null, null);
    }

    private String fileDigest(Host host, HostCapabilities caps, String remotePath,
                              String containerId, String containerPath) {
        CommandResult r;
        boolean docker = containerId != null && !containerId.isBlank();
        if (docker) {
            if (hasContainerCommand(host, containerId, "sha256sum")) {
                r = exec(host, dockerExec(containerId, "sha256sum " + shellQuote(containerPath)
                        + " | awk '{print $1}'"));
            } else if (caps.hasDocker()) {
                r = exec(host, containerDigestCommand(containerId, containerPath));
            } else {
                throw new BusinessException("容器内无 sha256sum 且主机无 Docker，无法计算摘要");
            }
        } else if (caps.hasTool("sha256sum")) {
            r = exec(host, "sha256sum " + shellQuote(remotePath) + " | awk '{print $1}'");
        } else if (caps.hasTool("shasum")) {
            r = exec(host, "shasum -a 256 " + shellQuote(remotePath) + " | awk '{print $1}'");
        } else if (caps.hasDocker()) {
            r = exec(host, "docker run --rm -v " + shellQuote(parentOf(remotePath)) + ":/w "
                    + "--entrypoint sha256sum " + toolingImage
                    + " " + shellQuote("/w/" + basename(remotePath))
                    + " | awk '{print $1}'");
        } else {
            throw new BusinessException("主机无 sha256sum/shasum 且无 Docker，无法计算摘要");
        }
        if (r == null || !r.isSuccess() || r.getOutput() == null || r.getOutput().isBlank()) {
            throw new BusinessException("计算文件摘要失败: " + (r != null ? r.getError() : "无响应"));
        }
        String digest = r.getOutput().trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new BusinessException("摘要格式异常: " + digest);
        }
        return digest;
    }

    /** 在实例内运行脚本：Native 原生 python3 优先，Docker 容器内 python3 优先，工具容器兜底 */
    public String runToolScript(Host host, HostCapabilities caps, String remotePath,
                                String scriptId) {
        String remoteScript = String.format("/tmp/gp_tool_%d.py", System.currentTimeMillis());
        uploadScript(host, remoteScript, scriptId);
        try {
            String inner = "python3 " + shellQuote(remoteScript) + " " + shellQuote(remotePath);
            if (hasCommand(host, "python3")) {
                return execScript(host, inner, remotePath, ScriptChannel.NATIVE);
            }
            if (caps.hasDocker()) {
                String cmd = "docker run --rm --entrypoint " + TOOLING_PYTHON + " -v "
                        + shellQuote(parentOf(remoteScript)) + ":/s -v "
                        + shellQuote(parentOf(remotePath)) + ":/w " + toolingImage
                        + " " + shellQuote("/s/" + basename(remoteScript))
                        + " " + shellQuote("/w/" + basename(remotePath));
                return execScript(host, cmd, remotePath, ScriptChannel.TOOL_CONTAINER);
            }
            throw new BusinessException("主机无 python3 且无 Docker：无法执行插件工具脚本"
                    + "（可为主机安装 python3，或使用含 python3 的 platform-tools 镜像）");
        } finally {
            remoteHost.exec(host, "rm -f " + shellQuote(remoteScript));
        }
    }

    private String runToolScript(Materialized ctx, String scriptId, String relativePath) {
        if (!ctx.isDocker()) {
            return runToolScript(ctx.host(), ctx.caps(), ctx.hostPath(), scriptId);
        }
        String remoteScript = String.format("/tmp/gp_tool_%d.py", System.currentTimeMillis());
        uploadScript(ctx.host(), remoteScript, scriptId);
        boolean usesContainerPython = hasContainerCommand(ctx.host(), ctx.containerId(), "python3");
        String containerScript = usesContainerPython ? "/tmp/" + basename(remoteScript) : null;
        try {
            if (usesContainerPython) {
                CommandResult copy = exec(ctx.host(),
                        "docker cp " + shellQuote(remoteScript) + " "
                                + shellQuote(ctx.containerId() + ":" + containerScript));
                if (copy == null || !copy.isSuccess()) {
                    throw new BusinessException("分发工具脚本到目标容器失败: "
                            + (copy != null ? copy.getError() : "无响应"));
                }
                String inner = "python3 " + shellQuote(containerScript) + " "
                        + shellQuote(ctx.containerPath());
                return execScript(ctx.host(), dockerExec(ctx.containerId(), inner),
                        ctx.containerPath(), ScriptChannel.TARGET_CONTAINER);
            }
            if (ctx.caps().hasDocker()) {
                ContainerVolume volume = resolveContainerVolume(ctx.host(), ctx.containerId(), ctx.containerPath());
                String mountedRoot = volume.destination().equals("/")
                        ? TOOLING_TARGET_ROOT
                        : TOOLING_TARGET_ROOT + volume.destination();
                String cmd = "docker run --rm --entrypoint " + TOOLING_PYTHON
                        + " -e " + shellQuote("GP_TOOL_TARGET_ROOT=" + TOOLING_TARGET_ROOT)
                        + " --mount " + shellQuote("type=" + volume.type() + ",source=" + volume.source()
                        + ",destination=" + mountedRoot + ",readonly")
                        + " -v " + shellQuote(parentOf(remoteScript)) + ":/s "
                        + toolingImage + " " + shellQuote("/s/" + basename(remoteScript)) + " "
                        + shellQuote(ctx.containerPath());
                return execScript(ctx.host(), cmd, ctx.containerPath(), ScriptChannel.TOOL_CONTAINER);
            }
            throw new BusinessException("容器内无 python3 且主机无 Docker：无法执行插件工具脚本"
                    + "（可使用含 python3 的 platform-tools 镜像）");
        } finally {
            if (containerScript != null) {
                exec(ctx.host(), "docker exec " + shellQuote(ctx.containerId())
                        + " rm -f " + shellQuote(containerScript));
            }
            remoteHost.exec(ctx.host(), "rm -f " + shellQuote(remoteScript));
        }
    }

    private record ContainerVolume(String type, String destination, String source) {
    }

    private ContainerVolume resolveContainerVolume(Host host, String containerId,
                                                   String containerPath) {
        if (containerPath == null || !containerPath.startsWith("/") || isDangerousRoot(containerPath)) {
            throw new BusinessException("目标容器内文件路径不是可安全挂载的绝对路径");
        }
        CommandResult r = exec(host,
                "docker inspect " + shellQuote(containerId)
                        + " --format '{{range .Mounts}}{{.Type}}|{{.Destination}}|{{.Source}}|{{.Name}}"
                        + "{{println}}{{end}}'");
        if (r == null || !r.isSuccess() || r.getOutput() == null) {
            throw new BusinessException("读取目标容器挂载信息失败: "
                    + (r != null ? r.getError() : "无响应"));
        }
        String bestDestination = null;
        String bestSource = null;
        String bestType = null;
        for (String line : r.getOutput().split("\\R")) {
            String[] parts = line.split("\\|", -1);
            if (parts.length < 4) {
                continue;
            }
            String type = parts[0].trim();
            String destination = parts[1].trim();
            String hostSource = parts[2].trim();
            String volumeName = parts[3].trim();
            String source = "volume".equals(type) && !volumeName.isBlank() ? volumeName : hostSource;
            if (!isPathWithin(containerPath, destination)) {
                continue;
            }
            if (bestDestination == null || destination.length() > bestDestination.length()) {
                bestType = type;
                bestDestination = destination;
                bestSource = source;
            }
        }
        if (bestDestination == null || bestSource == null || bestSource.isBlank()) {
            throw new BusinessException("目标容器内文件路径不在已挂载卷中，无法安全读取");
        }
        if (!"bind".equals(bestType) && !"volume".equals(bestType)) {
            throw new BusinessException("目标容器内文件路径所在挂载类型不受支持: " + bestType);
        }
        if ("bind".equals(bestType) && isDangerousRoot(bestSource)) {
            throw new BusinessException("目标容器文件所在 bind mount 的宿主路径过宽，拒绝挂载读取");
        }
        return new ContainerVolume(bestType, bestDestination, bestSource);
    }

    private static boolean isPathWithin(String path, String root) {
        if (root == null || root.isBlank()) {
            return false;
        }
        String normalizedRoot = root.equals("/") ? "/" : root.replaceAll("/+$", "");
        return path.equals(normalizedRoot)
                || path.startsWith(normalizedRoot.equals("/") ? "/" : normalizedRoot + "/");
    }

    private static boolean isDangerousRoot(String path) {
        return path == null || path.isBlank() || "/".equals(path)
                || ".".equals(path) || "..".equals(path) || path.matches("\\/+");
    }

    private enum ScriptChannel {
        NATIVE,
        TARGET_CONTAINER,
        TOOL_CONTAINER
    }

    /** 执行脚本并返回原始 stdout；非零退出码一律作为执行失败。 */
    private String execScript(Host host, String command, String targetPath,
                              ScriptChannel channel) {
        String errFile = "/tmp/gp_tool_" + System.nanoTime() + ".err";
        CommandResult r = exec(host, command + " 2>" + shellQuote(errFile)
                + "; __rc=$?; cat " + shellQuote(errFile) + " >&2; rm -f " + shellQuote(errFile)
                + "; exit $__rc");
        if (r == null) {
            throw new BusinessException("工具脚本无响应: " + targetPath);
        }
        if (!r.isSuccess() || r.getOutput() == null || r.getOutput().isBlank()) {
            String rawError = r.getError() != null && !r.getError().isBlank()
                    ? r.getError() : "exit=" + r.getExitCode();
            log.warn("工具脚本执行失败: target={}, channel={}, image={}, exitCode={}, error={}",
                    basename(targetPath), channel, toolingImage, r.getExitCode(),
                    sanitizeLogError(rawError));
            throw new BusinessException(buildScriptFailureMessage(targetPath, channel, rawError));
        }
        return r.getOutput();
    }

    private String buildScriptFailureMessage(String targetPath, ScriptChannel channel, String rawError) {
        String lower = rawError == null ? "" : rawError.toLowerCase(Locale.ROOT);
        String targetName = basename(targetPath);
        if (isFileReadFailure(lower)) {
            return "工具脚本目标文件不可读: " + targetName
                    + "。请确认文件仍在实例内且容器可访问后重试";
        }
        if (channel == ScriptChannel.TOOL_CONTAINER) {
            if (isToolContainerLaunchFailure(lower)) {
                return "工具脚本执行失败（工具容器不可用）: " + targetName
                        + "。请在目标主机重新拉取 platform-tools 镜像，并确认镜像包含 "
                        + TOOLING_PYTHON + "，然后重试";
            }
            return "工具脚本执行失败（工具容器执行失败）: " + targetName
                    + "。请检查 platform-tools 镜像与目标容器状态后重试";
        }
        if (channel == ScriptChannel.TARGET_CONTAINER) {
            return "工具脚本执行失败（目标容器执行不可用）: " + targetName
                    + "。请确认实例容器正在运行，并可执行容器内命令后重试";
        }
        return "工具脚本执行失败（主机执行不可用）: " + targetName
                + "。请检查主机 python3 或 Docker 工具链后重试";
    }

    private boolean isFileReadFailure(String error) {
        return error.contains("no such file")
                || error.contains("cannot open")
                || error.contains("permission denied")
                || error.contains("read-only file system")
                || error.contains("failed to read");
    }

    private boolean isToolContainerLaunchFailure(String error) {
        return error.contains("failed to create task")
                || error.contains("executable file not found")
                || error.contains("no such image")
                || error.contains("manifest unknown")
                || error.contains("pull access denied")
                || error.contains("failed to resolve reference")
                || error.contains("failed to pull")
                || error.contains("error response from daemon");
    }

    private String sanitizeLogError(String error) {
        if (error == null || error.isBlank()) {
            return "无错误输出";
        }
        String sanitized = error.replaceAll(
                "(?i)(password|passwd|token|secret|passcode)=([^\\s&]+)", "$1=***");
        String oneLine = sanitized.strip().replaceAll("\\s+", " ");
        return oneLine.length() > 800 ? oneLine.substring(0, 800) + "..." : oneLine;
    }

    /** 已登记脚本 → 本地临时文件 → SFTP 上传主机 */
    private void uploadScript(Host host, String remoteScript, String scriptId) {
        java.nio.file.Path local = null;
        try {
            local = java.nio.file.Files.createTempFile("gp_tool_", ".py");
            String script = toolScriptRegistry.read(scriptId);
            java.nio.file.Files.writeString(local, script, java.nio.charset.StandardCharsets.UTF_8);
            boolean ok = remoteHost.upload(
                    host, local.toAbsolutePath().toString(), remoteScript);
            if (!ok) {
                throw new BusinessException("分发工具脚本到主机失败");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("准备工具脚本失败: " + e.getMessage(), e);
        } finally {
            if (local != null) {
                try {
                    java.nio.file.Files.deleteIfExists(local);
                } catch (Exception ignored) {
                    // 平台侧临时脚本，清理失败无副作用
                }
            }
        }
    }

    private boolean hasCommand(Host host, String command) {
        CommandResult r = exec(host, "command -v " + command + " >/dev/null 2>&1 && echo __ok__");
        return r != null && r.isSuccess() && r.getOutput() != null && r.getOutput().contains("__ok__");
    }

    private boolean hasContainerCommand(Host host, String containerId, String command) {
        CommandResult r = exec(host, dockerExec(containerId,
                "command -v " + command + " >/dev/null 2>&1 && echo __ok__"));
        return r != null && r.isSuccess() && r.getOutput() != null && r.getOutput().contains("__ok__");
    }

    private static String dockerExec(String containerId, String innerCommand) {
        return "docker exec " + shellQuote(containerId) + " sh -c " + shellQuote(innerCommand);
    }

    private String containerDigestCommand(String containerId, String containerPath) {
        return "docker run --rm --volumes-from " + shellQuote(containerId)
                + " " + toolingImage
                + " sha256sum " + shellQuote(containerPath) + " | awk '{print $1}'";
    }

    private CommandResult exec(Host host, String command) {
        return remoteHost.exec(host, command);
    }

    private static String shellQuote(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : "/";
    }

    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String joinPath(String base, String rel) {
        if (base == null || base.isBlank() || "/".equals(base)) {
            return "/" + rel;
        }
        return base.endsWith("/") ? base + rel : base + "/" + rel;
    }
}

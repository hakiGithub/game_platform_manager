package com.gameplatform.clouddrive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.plugin.service.ContainerIdResolver;
import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.deploy.HostCredentials;
import com.gameplatform.entity.Host;
import com.gameplatform.entity.GameMetadata;
import com.gameplatform.mapper.GameMetadataMapper;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.plugin.patch.HostCapabilities;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.plugin.service.VpkAnalyzeResult;
import com.gameplatform.plugin.service.VpkInvalidException;
import com.gameplatform.util.SshUtil;
import com.gameplatform.vo.InstanceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 主机侧 VPK 分析器（ADR-0027）：摘要计算 + mission 解析。
 *
 * <p>对外按<b>实例相对路径</b>寻址（与 InstanceFileService 同语义），内部路由：
 * <ul>
 *   <li>Native 实例：直接定位 {@code installPath/<rel>}；</li>
 *   <li>Docker 实例：脚本送入容器，直接读取容器内原文件；缺 python3 时由
 *       platform-tools 将目标卷挂到独立路径，不复制 VPK。</li>
 * </ul>
 * 摘要走主机原生 sha256sum/shasum（Docker 实例在容器内计算，容器兜底）；
 * 分析脚本由平台维护（classpath {@code tooling/vpk_analyze.py}），调用时分发、用完即删；
 * 执行通道主机/目标容器原生 python3 优先，platform-tools 工具容器兜底（镜像需含 python3）。
 * 只读 VPK 头部目录树与 mission 内容，不读取其余数据区。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VpkToolingAnalyzer {

    private static final String SCRIPT_RESOURCE = "tooling/vpk_analyze.py";
    private static final String TOOLING_PYTHON = "/usr/bin/python3";
    private static final String TOOLING_TARGET_ROOT = "/gp-target";

    private final DeploymentAccess deployAccess;
    private final SshUtil sshUtil;
    private final ObjectMapper objectMapper;
    private final HostMapper hostMapper;
    private final GameMetadataMapper gameMetadataMapper;
    private final InstanceQueryService instanceQueryService;
    private final ContainerIdResolver containerIdResolver;
    private final com.gameplatform.patch.HostCapabilityProber prober;

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

    /** 实例侧 VPK 分析：摘要 + 战役/章节 */
    public VpkAnalyzeResult analyzeVpk(long instanceId, String relativeVpkPath) {
        Materialized ctx = prepare(instanceId, relativeVpkPath);
        try {
            String digest = fileDigest(ctx);
            String json = runAnalyzeScript(ctx);
            VpkAnalyzeResult result = parseResult(json);
            result.setDigest(digest);
            return result;
        } finally {
            cleanup(ctx);
        }
    }

    /** 实例侧 VPK 元数据：只读头部目录树与 mission，不计算整文件摘要 */
    public VpkAnalyzeResult inspectVpk(long instanceId, String relativeVpkPath) {
        Materialized ctx = prepare(instanceId, relativeVpkPath);
        try {
            return parseResult(runAnalyzeScript(ctx));
        } finally {
            cleanup(ctx);
        }
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
            // Docker：始终在容器内分析/计算摘要，不 docker cp 整个 VPK 到主机。
            // 工具容器把目标卷挂到独立根，避免覆盖工具镜像自身的 /usr/bin/python3。
            containerId = containerIdResolver.resolve(instance, metadataOf(instance));
            if (containerId == null || containerId.isBlank()) {
                throw new BusinessException("实例容器 ID 未记录，无法分析容器内文件（可重启实例重建元数据）");
            }
            containerPath = joinPath(containerWorkDir(instance, deployType), relativePath);
            hostPath = null;
        }
        return new Materialized(host, caps, hostPath, containerId, containerPath);
    }

    private void cleanup(Materialized ctx) {
        // 当前路径不产生主机临时 VPK；脚本的 finally 已负责清理。
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
        HostCredentials conn = deployAccess.credentials(host);
        SshUtil.CommandResult r;
        boolean docker = containerId != null && !containerId.isBlank();
        if (docker) {
            if (hasContainerCommand(conn, containerId, "sha256sum")) {
                r = exec(conn, dockerExec(containerId, "sha256sum " + shellQuote(containerPath)
                        + " | awk '{print $1}'"));
            } else if (caps.hasDocker()) {
                r = exec(conn, containerDigestCommand(containerId, containerPath));
            } else {
                throw new BusinessException("容器内无 sha256sum 且主机无 Docker，无法计算摘要");
            }
        } else if (caps.hasTool("sha256sum")) {
            r = exec(conn, "sha256sum " + shellQuote(remotePath) + " | awk '{print $1}'");
        } else if (caps.hasTool("shasum")) {
            r = exec(conn, "shasum -a 256 " + shellQuote(remotePath) + " | awk '{print $1}'");
        } else if (caps.hasDocker()) {
            r = exec(conn, "docker run --rm -v " + shellQuote(parentOf(remotePath)) + ":/w "
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

    /** 分析实例内 VPK：Native 原生 python3 优先，Docker 容器内 python3 优先，工具容器兜底 */
    public String runAnalyzeScript(Host host, HostCapabilities caps, String remoteVpkPath) {
        HostCredentials conn = deployAccess.credentials(host);
        String remoteScript = String.format("/tmp/gp_vpk_analyze_%d.py", System.currentTimeMillis());
        uploadScript(conn, remoteScript);
        try {
            String inner = "python3 " + shellQuote(remoteScript) + " " + shellQuote(remoteVpkPath);
            if (hasCommand(conn, "python3")) {
                return execJson(conn, inner, remoteVpkPath, AnalyzeChannel.NATIVE);
            }
            if (caps.hasDocker()) {
                String cmd = "docker run --rm --entrypoint " + TOOLING_PYTHON + " -v "
                        + shellQuote(parentOf(remoteScript)) + ":/s -v "
                        + shellQuote(parentOf(remoteVpkPath)) + ":/w " + toolingImage
                        + " " + shellQuote("/s/" + basename(remoteScript))
                        + " " + shellQuote("/w/" + basename(remoteVpkPath));
                return execJson(conn, cmd, remoteVpkPath, AnalyzeChannel.TOOL_CONTAINER);
            }
            throw new BusinessException("主机无 python3 且无 Docker：无法执行 VPK 分析"
                    + "（可为主机安装 python3，或使用含 python3 的 platform-tools 镜像）");
        } finally {
            sshUtil.executeCommand(conn.host(), conn.port(), conn.username(),
                    conn.privateKey(), conn.password(), "rm -f " + shellQuote(remoteScript));
        }
    }

    private String runAnalyzeScript(Materialized ctx) {
        if (!ctx.isDocker()) {
            return runAnalyzeScript(ctx.host(), ctx.caps(), ctx.hostPath());
        }
        HostCredentials conn = deployAccess.credentials(ctx.host());
        String remoteScript = String.format("/tmp/gp_vpk_analyze_%d.py", System.currentTimeMillis());
        uploadScript(conn, remoteScript);
        boolean usesContainerPython = hasContainerCommand(conn, ctx.containerId(), "python3");
        String containerScript = usesContainerPython ? "/tmp/" + basename(remoteScript) : null;
        try {
            if (usesContainerPython) {
                SshUtil.CommandResult copy = exec(conn,
                        "docker cp " + shellQuote(remoteScript) + " "
                                + shellQuote(ctx.containerId() + ":" + containerScript));
                if (copy == null || !copy.isSuccess()) {
                    throw new BusinessException("分发 VPK 分析脚本到容器失败: "
                            + (copy != null ? copy.getError() : "无响应"));
                }
                String inner = "python3 " + shellQuote(containerScript) + " "
                        + shellQuote(ctx.containerPath());
                return execJson(conn, dockerExec(ctx.containerId(), inner),
                        ctx.containerPath(), AnalyzeChannel.TARGET_CONTAINER);
            }
            if (ctx.caps().hasDocker()) {
                ContainerVolume volume = resolveContainerVolume(conn, ctx.containerId(), ctx.containerPath());
                String mountedRoot = volume.destination().equals("/")
                        ? TOOLING_TARGET_ROOT
                        : TOOLING_TARGET_ROOT + volume.destination();
                String cmd = "docker run --rm --entrypoint " + TOOLING_PYTHON
                        + " -e " + shellQuote("GP_VPK_TARGET_ROOT=" + TOOLING_TARGET_ROOT)
                        + " --mount " + shellQuote("type=" + volume.type() + ",source=" + volume.source()
                        + ",destination=" + mountedRoot + ",readonly")
                        + " -v " + shellQuote(parentOf(remoteScript)) + ":/s "
                        + toolingImage + " " + shellQuote("/s/" + basename(remoteScript)) + " "
                        + shellQuote(ctx.containerPath());
                return execJson(conn, cmd, ctx.containerPath(), AnalyzeChannel.TOOL_CONTAINER);
            }
            throw new BusinessException("容器内无 python3 且主机无 Docker：无法执行 VPK 分析"
                    + "（可使用含 python3 的 platform-tools 镜像）");
        } finally {
            if (containerScript != null) {
                exec(conn, "docker exec " + shellQuote(ctx.containerId())
                        + " rm -f " + shellQuote(containerScript));
            }
            sshUtil.executeCommand(conn.host(), conn.port(), conn.username(),
                    conn.privateKey(), conn.password(), "rm -f " + shellQuote(remoteScript));
        }
    }

    private record ContainerVolume(String type, String destination, String source) {
    }

    private ContainerVolume resolveContainerVolume(HostCredentials conn, String containerId,
                                                   String containerPath) {
        if (containerPath == null || !containerPath.startsWith("/") || isDangerousRoot(containerPath)) {
            throw new BusinessException("目标容器内 VPK 路径不是可安全挂载的绝对文件路径");
        }
        SshUtil.CommandResult r = exec(conn,
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
            throw new BusinessException("目标容器内 VPK 路径不在已挂载卷中，无法安全读取");
        }
        if (!"bind".equals(bestType) && !"volume".equals(bestType)) {
            throw new BusinessException("目标容器内 VPK 路径所在挂载类型不受支持: " + bestType);
        }
        if ("bind".equals(bestType) && isDangerousRoot(bestSource)) {
            throw new BusinessException("目标容器 VPK 所在 bind mount 的宿主路径过宽，拒绝挂载读取");
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

    private enum AnalyzeChannel {
        NATIVE,
        TARGET_CONTAINER,
        TOOL_CONTAINER
    }

    /** 执行分析命令并校验退出码语义：0=成功；2=非 VPK/无 mission（INVALID）；其他=失败 */
    private String execJson(HostCredentials conn, String command, String vpkPath,
                            AnalyzeChannel channel) {
        String errFile = "/tmp/gp_vpk_analyze_" + System.nanoTime() + ".err";
        SshUtil.CommandResult r = exec(conn, command + " 2>" + shellQuote(errFile)
                + "; __rc=$?; cat " + shellQuote(errFile) + " >&2; rm -f " + shellQuote(errFile)
                + "; exit $__rc");
        if (r == null) {
            throw new BusinessException("VPK 分析无响应: " + vpkPath);
        }
        if (r.getExitCode() == 2) {
            throw new VpkInvalidException("文件不是有效的 L4D2 地图 VPK（无 mission 信息）: " + basename(vpkPath));
        }
        if (!r.isSuccess() || r.getOutput() == null || r.getOutput().isBlank()) {
            String rawError = r.getError() != null && !r.getError().isBlank()
                    ? r.getError() : "exit=" + r.getExitCode();
            log.warn("VPK 分析失败: vpk={}, channel={}, image={}, exitCode={}, error={}",
                    basename(vpkPath), channel, toolingImage, r.getExitCode(),
                    sanitizeLogError(rawError));
            throw new BusinessException(buildAnalyzeFailureMessage(vpkPath, channel, rawError));
        }
        return r.getOutput();
    }

    private String buildAnalyzeFailureMessage(String vpkPath, AnalyzeChannel channel, String rawError) {
        String lower = rawError == null ? "" : rawError.toLowerCase(Locale.ROOT);
        String vpkName = basename(vpkPath);
        if (isFileReadFailure(lower)) {
            return "VPK 文件不可读: " + vpkName
                    + "。请确认文件仍在实例内且容器可访问，可刷新地图列表后重试";
        }
        if (channel == AnalyzeChannel.TOOL_CONTAINER) {
            if (isToolContainerLaunchFailure(lower)) {
                return "VPK 分析失败（工具容器不可用）: " + vpkName
                        + "。请在目标主机重新拉取 platform-tools 镜像，并确认镜像包含 "
                        + TOOLING_PYTHON + "，然后重试";
            }
            return "VPK 分析失败（工具容器执行失败）: " + vpkName
                    + "。请检查 platform-tools 镜像与目标容器状态后重试";
        }
        if (channel == AnalyzeChannel.TARGET_CONTAINER) {
            return "VPK 分析失败（目标容器执行不可用）: " + vpkName
                    + "。请确认实例容器正在运行，并可执行容器内命令后重试";
        }
        return "VPK 分析失败（主机执行不可用）: " + vpkName
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

    private VpkAnalyzeResult parseResult(String json) {
        try {
            Map<String, Object> map = objectMapper.readValue(json.trim(), Map.class);
            VpkAnalyzeResult result = new VpkAnalyzeResult();
            result.setTitle((String) map.get("title"));
            Object chapters = map.get("chapters");
            if (chapters instanceof Iterable<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> cm)) {
                        continue;
                    }
                    VpkAnalyzeResult.Chapter chapter = new VpkAnalyzeResult.Chapter();
                    chapter.setCode(String.valueOf(cm.get("code")));
                    Object title = cm.get("title");
                    chapter.setTitle(title != null ? String.valueOf(title) : null);
                    chapter.setModes(new ArrayList<>());
                    if (cm.get("modes") instanceof Iterable<?> modes) {
                        for (Object m : modes) {
                            chapter.getModes().add(String.valueOf(m));
                        }
                    }
                    result.getChapters().add(chapter);
                }
            }
            return result;
        } catch (Exception e) {
            throw new BusinessException("VPK 分析结果解析失败: " + e.getMessage(), e);
        }
    }

    /** classpath 脚本 → 本地临时文件 → SFTP 上传主机 */
    private void uploadScript(HostCredentials conn, String remoteScript) {
        Path local = null;
        try {
            local = Files.createTempFile("gp_vpk_analyze_", ".py");
            String script = new String(new ClassPathResource(SCRIPT_RESOURCE).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            Files.writeString(local, script, StandardCharsets.UTF_8);
            boolean ok = sshUtil.uploadFile(conn.host(), conn.port(), conn.username(),
                    conn.privateKey(), conn.password(), local.toAbsolutePath().toString(), remoteScript, null);
            if (!ok) {
                throw new BusinessException("分发 VPK 分析脚本到主机失败");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("准备 VPK 分析脚本失败: " + e.getMessage(), e);
        } finally {
            if (local != null) {
                try {
                    Files.deleteIfExists(local);
                } catch (Exception ignored) {
                    // 平台侧临时脚本，清理失败无副作用
                }
            }
        }
    }

    private boolean hasCommand(HostCredentials conn, String command) {
        SshUtil.CommandResult r = exec(conn, "command -v " + command + " >/dev/null 2>&1 && echo __ok__");
        return r != null && r.isSuccess() && r.getOutput() != null && r.getOutput().contains("__ok__");
    }

    private boolean hasContainerCommand(HostCredentials conn, String containerId, String command) {
        SshUtil.CommandResult r = exec(conn, dockerExec(containerId,
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

    private SshUtil.CommandResult exec(HostCredentials conn, String command) {
        return sshUtil.executeCommand(conn.host(), conn.port(), conn.username(),
                conn.privateKey(), conn.password(), command);
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

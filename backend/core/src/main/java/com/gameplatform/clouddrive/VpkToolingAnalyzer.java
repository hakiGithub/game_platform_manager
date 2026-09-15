package com.gameplatform.clouddrive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.plugin.service.ContainerIdResolver;
import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.deploy.HostCredentials;
import com.gameplatform.entity.Host;
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
import java.util.Locale;
import java.util.Map;

/**
 * 主机侧 VPK 分析器（ADR-0027）：摘要计算 + mission 解析。
 *
 * <p>对外按<b>实例相对路径</b>寻址（与 InstanceFileService 同语义），内部路由：
 * <ul>
 *   <li>Native 实例：直接定位 {@code installPath/<rel>}；</li>
 *   <li>Docker 实例：{@code docker cp} 物化到主机临时目录（通用适配卷/绑定挂载拓扑），
 *       分析完成即清理。</li>
 * </ul>
 * 摘要走主机原生 sha256sum/shasum（容器兜底）；分析脚本由平台维护
 * （classpath {@code tooling/vpk_analyze.py}），调用时分发、用完即删；
 * 执行通道主机原生 python3 优先，platform-tools 工具容器兜底（镜像需含 python3）。
 * 只读 VPK 头部目录树，不读取文件数据区。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VpkToolingAnalyzer {

    private static final String SCRIPT_RESOURCE = "tooling/vpk_analyze.py";

    private final DeploymentAccess deployAccess;
    private final SshUtil sshUtil;
    private final ObjectMapper objectMapper;
    private final HostMapper hostMapper;
    private final InstanceQueryService instanceQueryService;
    private final ContainerIdResolver containerIdResolver;

    @Value("${game-platform.patch.tooling-image:registry.cn-shenzhen.aliyuncs.com/haki_hub/platform-tools:latest}")
    private String toolingImage;

    // ===== 实例寻址入口（SDK 语义） =====

    /** 实例侧文件 sha-256 摘要 */
    public String fileDigest(long instanceId, String relativePath) {
        Materialized ctx = prepare(instanceId, relativePath);
        try {
            return fileDigest(ctx.host(), ctx.caps(), ctx.hostPath());
        } finally {
            cleanup(ctx);
        }
    }

    /** 实例侧 VPK 分析：摘要 + 战役/章节 */
    public VpkAnalyzeResult analyzeVpk(long instanceId, String relativeVpkPath) {
        Materialized ctx = prepare(instanceId, relativeVpkPath);
        try {
            String digest = fileDigest(ctx.host(), ctx.caps(), ctx.hostPath());
            String json = runAnalyzeScript(ctx.host(), ctx.caps(), ctx.hostPath());
            VpkAnalyzeResult result = parseResult(json);
            result.setDigest(digest);
            return result;
        } finally {
            cleanup(ctx);
        }
    }

    // ===== 实例路径物化 =====

    private record Materialized(Host host, HostCapabilities caps, String hostPath, String tmpDir) {
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
        String tmpDir = null;
        String deployType = deployAccess.classify(instance.getDeployType()).getCode();
        if (deployAccess.isNativeDeploy(deployType)) {
            hostPath = joinPath(instance.getInstallPath(), relativePath);
        } else {
            // Docker：docker cp 物化到主机临时目录（卷/绑定挂载拓扑无关）
            String containerId = containerIdResolver.resolve(instance, metadataOf(instance));
            if (containerId == null || containerId.isBlank()) {
                throw new BusinessException("实例容器 ID 未记录，无法物化文件（可重启实例重建元数据）");
            }
            String containerPath = joinPath(containerWorkDir(instance), relativePath);
            tmpDir = "/tmp/gp_vpk_" + System.currentTimeMillis();
            String target = tmpDir + "/" + basename(relativePath);
            HostCredentials conn = deployAccess.credentials(host);
            SshUtil.CommandResult r = exec(conn, "mkdir -p " + shellQuote(tmpDir)
                    + " && docker cp " + shellQuote(containerId + ":" + containerPath) + " "
                    + shellQuote(target));
            if (r == null || !r.isSuccess()) {
                exec(conn, "rm -rf " + shellQuote(tmpDir));
                throw new BusinessException("从容器复制 VPK 失败: " + (r != null ? r.getError() : "无响应"));
            }
            hostPath = target;
        }
        return new Materialized(host, caps, hostPath, tmpDir);
    }

    private void cleanup(Materialized ctx) {
        if (ctx.tmpDir() == null) {
            return;
        }
        try {
            HostCredentials conn = deployAccess.credentials(ctx.host());
            exec(conn, "rm -rf " + shellQuote(ctx.tmpDir()));
        } catch (Exception e) {
            log.warn("清理临时目录失败（忽略）: {}", ctx.tmpDir(), e);
        }
    }

    private Map<String, Object> metadataOf(InstanceVO instance) {
        Map<String, Object> metadata = instance.getConfigInfo();
        return metadata != null ? metadata : Map.of();
    }

    private String containerWorkDir(InstanceVO instance) {
        Map<String, Object> metadata = metadataOf(instance);
        String workDir = stringValue(metadata, "containerWorkDir");
        if (workDir == null || workDir.isBlank() || "/".equals(workDir)) {
            workDir = stringValue(metadata, "workDir");
        }
        return workDir != null && !workDir.isBlank() && !"/".equals(workDir) ? workDir : "/";
    }

    private static String stringValue(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? String.valueOf(v) : null;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.gameplatform.patch.HostCapabilityProber prober;

    // ===== 主机层原语 =====

    /** 计算主机侧文件 sha-256（原生 sha256sum/shasum，容器兜底） */
    public String fileDigest(Host host, HostCapabilities caps, String remotePath) {
        HostCredentials conn = deployAccess.credentials(host);
        SshUtil.CommandResult r;
        if (caps.hasTool("sha256sum")) {
            r = exec(conn, "sha256sum " + shellQuote(remotePath) + " | awk '{print $1}'");
        } else if (caps.hasTool("shasum")) {
            r = exec(conn, "shasum -a 256 " + shellQuote(remotePath) + " | awk '{print $1}'");
        } else if (caps.hasDocker()) {
            r = exec(conn, "docker run --rm -v " + shellQuote(parentOf(remotePath)) + ":/w "
                    + toolingImage + " sha256sum /w/" + basename(remotePath) + " | awk '{print $1}'");
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

    /** 分析主机侧 VPK：脚本原生 python3 优先，工具容器兜底 */
    public String runAnalyzeScript(Host host, HostCapabilities caps, String remoteVpkPath) {
        HostCredentials conn = deployAccess.credentials(host);
        String remoteScript = String.format("/tmp/gp_vpk_analyze_%d.py", System.currentTimeMillis());
        uploadScript(conn, remoteScript);
        try {
            String inner = "python3 " + shellQuote(remoteScript) + " " + shellQuote(remoteVpkPath);
            if (hasCommand(conn, "python3")) {
                return execJson(conn, inner, remoteVpkPath);
            }
            if (caps.hasDocker()) {
                String cmd = "docker run --rm -v " + shellQuote(parentOf(remoteScript)) + ":/s -v "
                        + shellQuote(parentOf(remoteVpkPath)) + ":/w " + toolingImage
                        + " python3 /s/" + basename(remoteScript) + " /w/" + basename(remoteVpkPath);
                return execJson(conn, cmd, remoteVpkPath);
            }
            throw new BusinessException("主机无 python3 且无 Docker：无法执行 VPK 分析"
                    + "（可为主机安装 python3，或使用含 python3 的 platform-tools 镜像）");
        } finally {
            sshUtil.executeCommand(conn.host(), conn.port(), conn.username(),
                    conn.privateKey(), conn.password(), "rm -f " + shellQuote(remoteScript));
        }
    }

    /** 执行分析命令并校验退出码语义：0=成功；2=非 VPK/无 mission（INVALID）；其他=失败 */
    private String execJson(HostCredentials conn, String command, String vpkPath) {
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
            throw new BusinessException("VPK 分析失败: " + (r.getError() != null && !r.getError().isBlank()
                    ? r.getError() : "exit=" + r.getExitCode()));
        }
        return r.getOutput();
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

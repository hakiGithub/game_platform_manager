package com.gameplatform.clouddrive;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.plugin.service.HostToolingService;
import com.gameplatform.plugin.service.VpkAnalyzeResult;
import com.gameplatform.plugin.patch.HostCapabilities;
import com.gameplatform.patch.PatchDecisionEngine;
import com.gameplatform.patch.PatchFormat;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工具容器宿主能力工厂（ADR-0026）：为插件生成绑定 pluginId 的 {@link HostToolingService}，
 * 调用方审计标识由服务端写入。由 PluginSpringContextFactory 注册进插件子容器。
 * <p>
 * 复用 PatchInstallExecutor 的主机命令/工具容器设施（原生优先 → platform-tools 容器兜底，
 * --rm 用完即销毁）；镜像/命令对插件不可见。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HostToolingServiceFactory {

    private final HostMapper hostMapper;
    private final com.gameplatform.patch.HostCapabilityProber prober;
    private final PatchDecisionEngine decisionEngine;
    private final com.gameplatform.patch.PatchInstallExecutor executorBridge;
    private final VpkToolingAnalyzer vpkToolingAnalyzer;

    public HostToolingService forPlugin(String pluginId) {
        return new HostToolingService() {
            @Override
            public List<String> extractArchive(Long hostId, String remoteArchive,
                                               String destDir, String includePattern) {
                log.info("[HostTooling][{}] extractArchive host={} archive={} dest={} pattern={}",
                        pluginId, hostId, remoteArchive, destDir, includePattern);
                Host host = hostMapper.selectById(hostId);
                if (host == null) {
                    throw new BusinessException("主机不存在: " + hostId);
                }
                HostCapabilities caps = prober.probe(hostId);
                PatchFormat format = detectFormat(remoteArchive);
                boolean nativeOk = decisionEngine.canExtractNative(caps, format);
                if (!nativeOk && !caps.hasDocker()) {
                    throw new BusinessException("主机无 " + format + " 解压工具且无 Docker（可回退平台中转）");
                }
                // 委托 PatchInstallExecutor 的主机命令与工具容器设施执行解压
                return executorBridge.toolingExtract(host, caps, format, remoteArchive, destDir, includePattern);
            }

            @Override
            public String fileDigest(Long instanceId, String relativePath) {
                log.info("[HostTooling][{}] fileDigest instance={} path={}", pluginId, instanceId, relativePath);
                return vpkToolingAnalyzer.fileDigest(instanceId, relativePath);
            }

            @Override
            public VpkAnalyzeResult analyzeVpk(Long instanceId, String relativeVpkPath) {
                log.info("[HostTooling][{}] analyzeVpk instance={} path={}", pluginId, instanceId, relativeVpkPath);
                return vpkToolingAnalyzer.analyzeVpk(instanceId, relativeVpkPath);
            }
        };
    }

    private PatchFormat detectFormat(String archivePath) {
        String lower = archivePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) return PatchFormat.TAR_GZ;
        if (lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2")) return PatchFormat.TAR_BZ2;
        if (lower.endsWith(".tar.xz") || lower.endsWith(".txz")) return PatchFormat.TAR_XZ;
        if (lower.endsWith(".zip")) return PatchFormat.ZIP;
        if (lower.endsWith(".rar")) return PatchFormat.RAR;
        if (lower.endsWith(".7z")) return PatchFormat.SEVEN_Z;
        if (lower.endsWith(".gz")) return PatchFormat.GZ;
        if (lower.endsWith(".bz2")) return PatchFormat.BZ2;
        if (lower.endsWith(".xz")) return PatchFormat.XZ;
        throw new BusinessException("无法识别压缩包格式（支持 tar.gz/tbz2/txz/zip/rar/7z/gz/bz2/xz）: "
                + archivePath);
    }
}

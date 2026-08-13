package com.gameplatform.patch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.plugin.patch.HostCapabilities;
import com.gameplatform.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 宿主机能力探测器（ADR-0006 决策 3）
 *
 * <p>SFTP 推送内置探测脚本到宿主机临时目录并执行（推送不区分局域网），
 * 输出按返回格式契约解析为 {@link HostCapabilities}。
 * 探测只在宿主机执行，容器内不探测。</p>
 *
 * <p>结果按 hostId 短缓存（60s TTL）：同一批补丁任务对同一主机的探测只执行一次，
 * 同时避免长时间使用过期结论。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HostCapabilityProber {

    private static final String REMOTE_SCRIPT_PATH = "/tmp/patch_probe_capabilities.sh";
    /** 探测结果缓存（hostId -> 时间戳+结果） */
    private static final long CACHE_TTL_MS = 60_000L;
    private static final long PROBE_TIMEOUT_MS = 30_000L;

    private final FileService fileService;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<Long, CachedProbe> cache = new ConcurrentHashMap<>();

    /**
     * 探测宿主机能力。
     */
    public HostCapabilities probe(Long hostId) {
        CachedProbe cached = cache.get(hostId);
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            return cached.capabilities;
        }

        String script;
        try {
            script = StreamUtils.copyToString(
                    new ClassPathResource("scripts/probe_capabilities.sh").getInputStream(),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BusinessException("探测脚本加载失败: " + e.getMessage());
        }

        // SFTP 推送脚本（不区分局域网，ADR-0006 决策 3）
        fileService.writeTextFile(hostId, REMOTE_SCRIPT_PATH, script);
        // 执行探测
        com.gameplatform.util.SshUtil.CommandResult result =
                fileService.executeCommand(hostId, "sh " + REMOTE_SCRIPT_PATH, PROBE_TIMEOUT_MS);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException("能力探测执行失败: "
                    + (result != null ? result.getError() : "无响应"));
        }

        HostCapabilities capabilities = parse(result.getOutput());
        cache.put(hostId, new CachedProbe(System.currentTimeMillis(), capabilities));
        log.info("主机能力探测完成: hostId={}, tools={}", hostId, capabilities.getTools());
        return capabilities;
    }

    /**
     * 解析探测脚本输出（返回格式契约，ADR-0006 决策 3）。
     * 恶意/异常输出按容错解析：字段缺失取默认值，tools 未记录视为不存在。
     */
    HostCapabilities parse(String jsonOutput) {
        try {
            JsonNode root = objectMapper.readTree(jsonOutput);
            HostCapabilities caps = new HostCapabilities();
            caps.setOsType(textOf(root, "osType"));
            caps.setHostname(textOf(root, "hostname"));
            caps.setArch(textOf(root, "arch"));
            caps.setCurrentUser(textOf(root, "currentUser"));
            JsonNode tools = root.path("tools");
            if (tools.isObject()) {
                Map<String, Boolean> toolMap = new java.util.HashMap<>();
                tools.fields().forEachRemaining(e ->
                        toolMap.put(e.getKey(), e.getValue().asBoolean(false)));
                caps.setTools(toolMap);
            }
            if (root.hasNonNull("tmpFreeKb")) {
                caps.setTmpFreeKb(root.get("tmpFreeKb").asLong());
            }
            return caps;
        } catch (Exception e) {
            throw new BusinessException("探测结果解析失败: " + e.getMessage());
        }
    }

    private String textOf(JsonNode root, String field) {
        return root.hasNonNull(field) ? root.get(field).asText() : null;
    }

    private record CachedProbe(long timestamp, HostCapabilities capabilities) {
    }
}

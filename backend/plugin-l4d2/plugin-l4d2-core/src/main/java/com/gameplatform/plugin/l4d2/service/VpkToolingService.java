package com.gameplatform.plugin.l4d2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.plugin.l4d2.exception.VpkInvalidException;
import com.gameplatform.plugin.l4d2.vo.VpkAnalyzeResult;
import com.gameplatform.plugin.service.HostToolingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Map;

/**
 * L4D2 VPK 业务语义层。
 *
 * <p>宿主只负责在实例上下文运行插件脚本并返回原始 stdout；mission JSON 的
 * 结构与有效性判断留在插件内，避免反噬通用的 HostToolingService。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VpkToolingService {

    public static final String ANALYZE_SCRIPT = "tooling/vpk_analyze.py";
    /** 插件脚本约定：2 表示 VPK 有效但不含 L4D2 mission。 */
    private static final String NO_MISSION_PREFIX = "no valid mission found";

    private final HostToolingService hostToolingService;
    private final ObjectMapper objectMapper;

    /** 计算摘要并分析 VPK。 */
    public VpkAnalyzeResult analyzeVpk(long instanceId, String relativeVpkPath) {
        VpkAnalyzeResult result = inspectVpk(instanceId, relativeVpkPath);
        result.setDigest(hostToolingService.fileDigest(instanceId, relativeVpkPath));
        return result;
    }

    /** 只分析 VPK 元数据，不计算整文件摘要。 */
    public VpkAnalyzeResult inspectVpk(long instanceId, String relativeVpkPath) {
        try {
            String output = hostToolingService.runToolScript(
                    instanceId, relativeVpkPath, ANALYZE_SCRIPT);
            return parseResult(output);
        } catch (Exception e) {
            if (isNoMissionFailure(e)) {
                throw new VpkInvalidException("文件不是有效的 L4D2 地图 VPK（无 mission 信息）: "
                        + basename(relativeVpkPath), e);
            }
            throw e;
        }
    }

    VpkAnalyzeResult parseResult(String output) {
        try {
            Map<String, Object> map = objectMapper.readValue(output.trim(), Map.class);
            VpkAnalyzeResult result = new VpkAnalyzeResult();
            result.setTitle((String) map.get("title"));
            if (map.get("chapters") instanceof Iterable<?> chapters) {
                for (Object value : chapters) {
                    if (!(value instanceof Map<?, ?> chapterMap)) {
                        continue;
                    }
                    VpkAnalyzeResult.Chapter chapter = new VpkAnalyzeResult.Chapter();
                    chapter.setCode(String.valueOf(chapterMap.get("code")));
                    Object title = chapterMap.get("title");
                    chapter.setTitle(title != null ? String.valueOf(title) : null);
                    chapter.setModes(new ArrayList<>());
                    if (chapterMap.get("modes") instanceof Iterable<?> modes) {
                        for (Object mode : modes) {
                            chapter.getModes().add(String.valueOf(mode));
                        }
                    }
                    result.getChapters().add(chapter);
                }
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("VPK 分析结果解析失败: " + e.getMessage(), e);
        }
    }

    private boolean isNoMissionFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains(NO_MISSION_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    private String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}

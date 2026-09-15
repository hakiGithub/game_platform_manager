package com.gameplatform.plugin.l4d2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.l4d2.extension.MapFileIndexResource;
import com.gameplatform.plugin.l4d2.extension.MapFileIndexSpec;
import com.gameplatform.plugin.l4d2.extension.MapRecognitionResource;
import com.gameplatform.plugin.l4d2.extension.MapRecognitionSpec;
import com.gameplatform.plugin.l4d2.extension.MapResource;
import com.gameplatform.plugin.l4d2.extension.MapSpec;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.service.FileAccessService;
import com.gameplatform.plugin.service.HostToolingService;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.plugin.service.VpkAnalyzeResult;
import com.gameplatform.plugin.service.VpkInvalidException;
import com.gameplatform.vo.InstanceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 地图识别服务（ADR-0027）：主机侧解析三方地图 VPK，结果按摘要键控插件级共享。
 *
 * <p>两层存储：
 * <ul>
 *   <li>共享识别表（VPK sha-256 摘要为主键）：同一文件全平台只识别一次，跨实例复用</li>
 *   <li>实例文件索引（{instanceId}:{vpkName} → 摘要）：refresh 对账维护，列表 join 用</li>
 * </ul>
 * 摘要计算收拢在识别流程内（先 fileDigest，命中共享 OK 记录即跳过容器分析）；
 * 列表渲染只做 join，永远零现场解析。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MapRecognitionService {

    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_READY = "READY";
    private static final String REC_OK = "OK";
    private static final String REC_FAILED = "FAILED";
    private static final String REC_INVALID = "INVALID";

    private final HostToolingService hostToolingService;
    private final InstanceQueryService instanceQueryService;
    private final InstanceFileService instanceFileService;
    private final ExtensionClient extensionClient;
    private final L4D2PathResolver pathResolver;
    private final ObjectMapper objectMapper;

    // ===== 索引对账 =====

    /**
     * 对账实例 addons 目录与文件索引：新增文件记 PENDING，已消失文件清条目。
     *
     * @return 本次新增的 PENDING 文件数
     */
    public int reconcileIndex(long instanceId) {
        requireInstance(instanceId);
        String addonsPath = pathResolver.getAddonsPath();
        Set<String> currentVpks = new HashSet<>();
        for (FileAccessService.FileInfo file : instanceFileService.listFiles(instanceId, addonsPath)) {
            if (!file.isDirectory() && file.getName().toLowerCase(Locale.ROOT).endsWith(".vpk")) {
                currentVpks.add(file.getName());
            }
        }

        Map<String, MapFileIndexSpec> existing = indexByVpkName(instanceId);
        int added = 0;
        for (String vpkName : currentVpks) {
            if (!existing.containsKey(vpkName)) {
                putIndex(instanceId, vpkName, null, STATUS_PENDING);
                added++;
            }
        }
        for (String vpkName : new HashSet<>(existing.keySet())) {
            if (!currentVpks.contains(vpkName)) {
                extensionClient.delete(MapFileIndexResource.class, indexKey(instanceId, vpkName));
            }
        }
        if (added > 0) {
            log.info("地图索引对账完成: instanceId={}, 新增 PENDING {}, 现存 {}",
                    instanceId, added, currentVpks.size());
        }
        return added;
    }

    /** 实例索引条目（vpkName → spec），供列表 join */
    public Map<String, MapFileIndexSpec> indexByVpkName(long instanceId) {
        Map<String, MapFileIndexSpec> result = new HashMap<>();
        for (MapFileIndexResource r : extensionClient.listAll(MapFileIndexResource.class)) {
            MapFileIndexSpec spec = r.getSpec();
            if (spec != null && spec.getInstanceId() != null && spec.getInstanceId() == instanceId) {
                result.put(spec.getVpkName(), spec);
            }
        }
        return result;
    }

    // ===== 识别 =====

    /**
     * 识别实例上的索引条目：PENDING 必处理；READY 但共享记录为 FAILED/INVALID 的在
     * forceRetry 时重试（手动批量识别语义，ADR-0027 决策 4③）。
     *
     * @param taskLog 任务日志出口，可为 null（安装链路内联调用）
     * @return 识别成功的文件数
     */
    public int recognizePending(long instanceId, TaskLog taskLog, boolean forceRetry) {
        InstanceVO instance = requireInstance(instanceId);
        Map<String, MapFileIndexSpec> index = indexByVpkName(instanceId);
        int ok = 0;
        int handled = 0;
        for (MapFileIndexSpec entry : index.values()) {
            boolean retryableFailure = forceRetry && STATUS_READY.equals(entry.getStatus())
                    && isRetryableFailure(entry.getDigest());
            if (!STATUS_PENDING.equals(entry.getStatus()) && !retryableFailure) {
                continue;
            }
            handled++;
            String vpkPath = pathResolver.getAddonsPath() + "/" + entry.getVpkName();
            try {
                log(taskLog, "识别 " + entry.getVpkName());
                String digest = hostToolingService.fileDigest(instance.getId(), vpkPath);
                if (isOkRecord(digest)) {
                    // 命中共享记录：跳过容器分析（ADR-0027 摘要复用）
                    putIndex(instanceId, entry.getVpkName(), digest, STATUS_READY);
                    ok++;
                    log(taskLog, "复用已有识别结果: " + entry.getVpkName());
                    continue;
                }
                VpkAnalyzeResult analyzed = hostToolingService.analyzeVpk(instanceId, vpkPath);
                upsertRecognition(digest, analyzed, entry.getVpkName(), null);
                // 批量识别也做开图命令匹配（vpk 文件名兜底；sourceId 仅安装链路可知）
                resolveLaunchCommands(digest, null, entry.getVpkName());
                putIndex(instanceId, entry.getVpkName(), digest, STATUS_READY);
                ok++;
                log(taskLog, "识别成功: " + entry.getVpkName() + " → "
                        + (analyzed.getTitle() != null ? analyzed.getTitle() : "无标题")
                        + "（" + analyzed.getChapters().size() + " 章）");
            } catch (VpkInvalidException e) {
                // 摘要已可算出 → 记 INVALID 并置 READY（forceRetry 时可重试）
                markFailureByReanalysis(instance, entry, REC_INVALID, e.getMessage(), taskLog);
            } catch (Exception e) {
                // 分析失败：记录 FAILED；索引保持 PENDING，后续识别自动重试
                markAnalysisFailure(instance, entry, e.getMessage(), taskLog);
            }
        }
        if (handled > 0) {
            log.info("地图识别完成: instanceId={}, 处理 {}, 成功 {}", instanceId, handled, ok);
        }
        return ok;
    }

    /**
     * 安装链路内联识别（ADR-0027 决策 4①）：对刚安装的 vpk 识别并回填开图命令。
     * 任何失败只记录、不向上抛——识别失败不连坐安装成功态。
     *
     * @param sourceId 地图中心来源 ID（裸链接安装为 null）
     * @return 匹配到的开图命令（未命中/失败返回空列表）
     */
    public List<String> recognizeAfterInstall(long instanceId, List<String> vpkNames, String sourceId,
                                              TaskLog taskLog) {
        List<String> launchCommands = new ArrayList<>();
        try {
            reconcileIndex(instanceId);
            recognizePending(instanceId, taskLog, false);
        } catch (Exception e) {
            log.warn("安装后识别失败（不影响安装）: instanceId={}", instanceId, e);
            log(taskLog, "WARN", "地图识别失败（可在地图列表重试）: " + e.getMessage());
        }
        try {
            Map<String, MapFileIndexSpec> index = indexByVpkName(instanceId);
            for (String vpkName : vpkNames) {
                MapFileIndexSpec entry = index.get(vpkName);
                if (entry == null || entry.getDigest() == null) {
                    continue;
                }
                List<String> commands = resolveLaunchCommands(entry.getDigest(), sourceId, vpkName);
                launchCommands.addAll(commands);
            }
        } catch (Exception e) {
            log.warn("开图命令匹配失败（不影响安装）: instanceId={}", instanceId, e);
        }
        return launchCommands;
    }

    // ===== 查询（列表 join 用）=====

    /** 实例索引条目集合（MapService.listMaps join） */
    public Map<String, MapFileIndexSpec> indexForInstance(long instanceId) {
        return indexByVpkName(instanceId);
    }

    /** 共享识别记录（按摘要） */
    public Optional<MapRecognitionSpec> recognitionOf(String digest) {
        if (digest == null) {
            return Optional.empty();
        }
        return extensionClient.get(MapRecognitionResource.class, digest)
                .map(MapRecognitionResource::getSpec)
                .filter(spec -> spec != null);
    }

    /** 某识别记录的开图命令，无则空列表 */
    public List<String> launchCommandsOf(String digest) {
        return recognitionOf(digest)
                .map(MapRecognitionSpec::getLaunchCommandsJson)
                .filter(json -> json != null && !json.isBlank())
                .map(this::parseStringList)
                .orElse(List.of());
    }

    // ===== 开图命令匹配与回填（ADR-0027 决策 5）=====

    /**
     * 解析开图命令：sourceId 精确匹配优先，vpk 文件名对爬虫 vpkFileName 兜底；
     * 命中后回填识别记录（launchCommandsJson + sourceId）。匹配不上返回空列表。
     */
    private List<String> resolveLaunchCommands(String digest, String sourceId, String vpkName) {
        MapResource matched = null;
        if (sourceId != null && !sourceId.isBlank()) {
            matched = extensionClient.get(MapResource.class, sourceId).orElse(null);
        }
        if (matched == null) {
            String bareName = trimVpk(vpkName);
            for (MapResource r : extensionClient.listAll(MapResource.class)) {
                MapSpec spec = r.getSpec();
                if (spec != null && bareName.equals(trimVpk(spec.getVpkFileName()))) {
                    matched = r;
                    break;
                }
            }
        }
        if (matched == null || matched.getSpec() == null
                || matched.getSpec().getMapCommands() == null
                || matched.getSpec().getMapCommands().isEmpty()) {
            return List.of();
        }
        List<String> commands = matched.getSpec().getMapCommands().stream()
                .filter(c -> c != null && !c.isBlank())
                .toList();
        if (commands.isEmpty()) {
            return List.of();
        }
        MapResource matchedResource = matched;
        extensionClient.get(MapRecognitionResource.class, digest).ifPresent(rec -> {
            MapRecognitionSpec spec = rec.getSpec();
            if (spec != null) {
                spec.setSourceId(matchedResource.getName());
                spec.setLaunchCommandsJson(toJson(commands));
                extensionClient.update(rec);
            }
        });
        return commands;
    }

    // ===== 上传链路直解（ADR-0027 决策 4②）=====

    /**
     * 上传任务内直接落识别记录：文件已过平台，用平台侧解析结果写共享记录
     * （不走容器）。失败只记日志，不影响上传成功。
     *
     * @param digest 平台侧计算的上传文件摘要（可空，空则只记索引 PENDING）
     */
    public void recordFromPlatformParse(long instanceId, String vpkName, String digest,
                                        String title, List<MapListChapter> chapters) {
        try {
            if (digest == null || digest.isBlank()) {
                putIndex(instanceId, vpkName, null, STATUS_PENDING);
                return;
            }
            if (chapters.isEmpty()) {
                // 平台侧解析不出章节（mission 未内联 preload 等）：
                // 留 PENDING 交给主机侧识别（脚本会补读数据区），不误标 INVALID
                putIndex(instanceId, vpkName, digest, STATUS_PENDING);
                return;
            }
            MapRecognitionSpec spec = new MapRecognitionSpec();
            spec.setDigest(digest);
            spec.setStatus(REC_OK);
            spec.setTitle(title);
            spec.setChaptersJson(toJsonChapters(chapters));
            spec.setSampleVpkName(vpkName);
            spec.setAnalyzedAt(now());
            upsertRecordResource(spec);
            putIndex(instanceId, vpkName, digest, STATUS_READY);
            log.info("上传链路识别落库: instanceId={}, vpk={}, status={}",
                    instanceId, vpkName, spec.getStatus());
        } catch (Exception e) {
            log.warn("上传链路落识别记录失败（不影响上传）: instanceId={}, vpk={}", instanceId, vpkName, e);
        }
    }

    // ===== 内部 =====

    private void upsertRecognition(String digest, VpkAnalyzeResult analyzed, String sampleVpkName,
                                   String sourceId) {
        MapRecognitionSpec spec = new MapRecognitionSpec();
        spec.setDigest(digest);
        boolean valid = analyzed.getTitle() != null || !analyzed.getChapters().isEmpty();
        spec.setStatus(valid ? REC_OK : REC_INVALID);
        spec.setTitle(analyzed.getTitle());
        spec.setChaptersJson(toJsonChapters(analyzed.getChapters().stream()
                .map(c -> new MapListChapter(c.getCode(), c.getTitle(), c.getModes())).toList()));
        spec.setSampleVpkName(sampleVpkName);
        spec.setSourceId(sourceId);
        spec.setAnalyzedAt(now());
        upsertRecordResource(spec);
    }

    /** 分析失败：写 FAILED 记录（摘要已知），索引保持 PENDING 供后续自动重试 */
    private void markAnalysisFailure(InstanceVO instance, MapFileIndexSpec entry,
                                     String message, TaskLog taskLog) {
        log(taskLog, "WARN", "识别失败（可重试）: " + entry.getVpkName() + " - " + message);
        try {
            String vpkPath = pathResolver.getAddonsPath() + "/" + entry.getVpkName();
            String digest = hostToolingService.fileDigest(instance.getId(), vpkPath);
            Optional<MapRecognitionResource> existing =
                    extensionClient.get(MapRecognitionResource.class, digest);
            MapRecognitionSpec spec = existing.map(MapRecognitionResource::getSpec)
                    .orElseGet(() -> {
                        MapRecognitionSpec s = new MapRecognitionSpec();
                        s.setDigest(digest);
                        s.setSampleVpkName(entry.getVpkName());
                        return s;
                    });
            spec.setStatus(REC_FAILED);
            spec.setErrorMessage(message);
            spec.setAnalyzedAt(now());
            MapRecognitionResource resource = existing.orElseGet(MapRecognitionResource::new);
            resource.setName(digest);
            resource.setSpec(spec);
            if (existing.isPresent()) {
                extensionClient.update(resource);
            } else {
                extensionClient.create(resource);
            }
        } catch (Exception e) {
            log.warn("记录识别失败状态时出错（忽略）: vpk={}", entry.getVpkName(), e);
        }
    }

    /** INVALID：摘要已知且文件确认无效，索引置 READY（forceRetry 才重试） */
    private void markFailureByReanalysis(InstanceVO instance, MapFileIndexSpec entry,
                                         String status, String message, TaskLog taskLog) {
        log(taskLog, "WARN", ("INVALID".equals(status) ? "非有效地图 VPK: " : "识别失败: ")
                + entry.getVpkName());
        try {
            String vpkPath = pathResolver.getAddonsPath() + "/" + entry.getVpkName();
            String digest = hostToolingService.fileDigest(instance.getId(), vpkPath);
            upsertInvalid(digest, entry.getVpkName(), status, message);
            putIndex(instanceIdOf(entry), entry.getVpkName(), digest, STATUS_READY);
        } catch (Exception e) {
            log.warn("记录 INVALID 状态时出错（忽略）: vpk={}", entry.getVpkName(), e);
        }
    }

    private void upsertInvalid(String digest, String sampleVpkName, String status, String message) {
        MapRecognitionSpec spec = new MapRecognitionSpec();
        spec.setDigest(digest);
        spec.setStatus(status);
        spec.setErrorMessage(message);
        spec.setSampleVpkName(sampleVpkName);
        spec.setAnalyzedAt(now());
        upsertRecordResource(spec);
    }

    private long instanceIdOf(MapFileIndexSpec entry) {
        return entry.getInstanceId() != null ? entry.getInstanceId() : 0L;
    }

    private boolean isRetryableFailure(String digest) {
        return recognitionOf(digest)
                .map(MapRecognitionSpec::getStatus)
                .map(status -> REC_FAILED.equals(status) || REC_INVALID.equals(status))
                .orElse(false);
    }

    private void upsertRecordResource(MapRecognitionSpec spec) {
        Optional<MapRecognitionResource> existing =
                extensionClient.get(MapRecognitionResource.class, spec.getDigest());
        MapRecognitionResource resource = existing.orElseGet(MapRecognitionResource::new);
        resource.setName(spec.getDigest());
        resource.setSpec(spec);
        if (existing.isPresent()) {
            extensionClient.update(resource);
        } else {
            extensionClient.create(resource);
        }
    }

    private boolean isOkRecord(String digest) {
        return recognitionOf(digest)
                .map(MapRecognitionSpec::getStatus)
                .map(REC_OK::equals)
                .orElse(false);
    }

    private void putIndex(long instanceId, String vpkName, String digest, String status) {
        String key = indexKey(instanceId, vpkName);
        Optional<MapFileIndexResource> existing = extensionClient.get(MapFileIndexResource.class, key);
        MapFileIndexSpec spec = existing.map(MapFileIndexResource::getSpec)
                .orElseGet(() -> {
                    MapFileIndexSpec s = new MapFileIndexSpec();
                    s.setIndexKey(key);
                    s.setInstanceId(instanceId);
                    s.setVpkName(vpkName);
                    return s;
                });
        if (digest != null) {
            spec.setDigest(digest);
        }
        if (status != null) {
            spec.setStatus(status);
        }
        spec.setUpdatedAt(now());
        MapFileIndexResource resource = existing.orElseGet(MapFileIndexResource::new);
        resource.setName(key);
        resource.setSpec(spec);
        if (existing.isPresent()) {
            extensionClient.update(resource);
        } else {
            extensionClient.create(resource);
        }
    }

    private String indexKey(long instanceId, String vpkName) {
        return instanceId + ":" + vpkName;
    }

    private String trimVpk(String vpkFileName) {
        if (vpkFileName == null) {
            return "";
        }
        return vpkFileName.toLowerCase(Locale.ROOT).endsWith(".vpk")
                ? vpkFileName.substring(0, vpkFileName.length() - 4) : vpkFileName;
    }

    private String now() {
        return LocalDateTime.now().format(ISO_FMT);
    }

    private String toJsonChapters(List<MapListChapter> chapters) {
        try {
            return objectMapper.writeValueAsString(chapters);
        } catch (Exception e) {
            log.warn("序列化章节失败", e);
            return "[]";
        }
    }

    private String toJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            log.warn("序列化列表失败", e);
            return "[]";
        }
    }

    private List<String> parseStringList(String json) {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private InstanceVO requireInstance(long instanceId) {
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "实例不存在: " + instanceId);
        }
        return instance;
    }

    private void log(TaskLog taskLog, String message) {
        if (taskLog != null) {
            taskLog.log(message);
        }
    }

    private void log(TaskLog taskLog, String level, String message) {
        if (taskLog != null) {
            taskLog.log(level, message);
        }
    }

    /** 章节数据的内部传输形态（与前端 MapListVO.ChapterVO 对齐） */
    public record MapListChapter(String code, String title, List<String> modes) {
    }

    /** 识别过程的日志出口（任务上下文适配器或 null=仅服务日志） */
    public interface TaskLog {
        void log(String message);

        default void log(String level, String message) {
            log(message);
        }
    }
}

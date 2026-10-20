package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.extension.PluginConfigFileSpec;
import com.gameplatform.plugin.l4d2.extension.PluginConfigResource;
import com.gameplatform.plugin.l4d2.extension.PluginConfigSpec;
import com.gameplatform.plugin.l4d2.parser.SourceModCfgParser;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.util.CvarBlacklist;
import com.gameplatform.plugin.l4d2.util.GbkCodecUtil;
import com.gameplatform.plugin.l4d2.vo.PluginMeta;
import com.gameplatform.plugin.l4d2.vo.CandidatePathVO;
import com.gameplatform.plugin.l4d2.vo.config.ConfigItem;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * SourceMod cfg 配置服务：候选路径推导、读取、更新、扩展资源持久化。
 *
 * <p>候选 cfg 路径（相对 {@code left4dead2/} 目录）：
 * <ul>
 *   <li>{@code cfg/sourcemod/{pluginName}.cfg}</li>
 *   <li>{@code addons/sourcemod/plugins/{pluginName}.cfg}</li>
 *   <li>l4d2_/l4d_ 互转别名（若插件名以 l4d2_ 或 l4d_ 开头）</li>
 * </ul>
 *
 * <p>安全增强：
 * <ul>
 *   <li>{@link CvarBlacklist} 校验危险 CVAR（rcon_password/sv_cheats 等）</li>
 *   <li>{@link PluginConfigAuditService} 记录配置修改审计日志</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceModCfgService {

    private static final String CFG_SOURCEMOD_PREFIX = "cfg/sourcemod/";
    private static final String PLUGINS_PREFIX = "addons/sourcemod/plugins/";
    private static final String CONFIG_STATUS_OK = "OK";
    private static final String CONFIG_STATUS_EMPTY = "EMPTY";
    private static final String CONFIG_STATUS_MISSING = "MISSING";
    private static final String CONFIG_STATUS_ERROR = "ERROR";

    private final InstanceQueryService instanceQueryService;
    private final InstanceFileService instanceFileService;
    private final ExtensionClient extensionClient;
    private final SourceModCfgParser cfgParser;
    private final L4D2PathResolver pathResolver;
    private final L4D2RconService rconService;
    private final PluginConfigAuditService auditService;
    private final PluginMetaService pluginMetaService;
    private final Charset gbk = GbkCodecUtil.gbk();

    /**
     * 候选 cfg 路径推导（实例维度）。
     *
     * <p>主候选：插件 meta（plugin.yaml）声明的 {@code config_files}——与实际安装的
     * 文件名一致（插件的 cfg 文件名通常取自 .smx 文件名，可能与中文显示名完全不同）。
     * 声明存在时只使用声明项；无 meta 或声明为空时才按插件名推导，兼容旧场景。
     *
     * @param instanceId 实例 ID
     * @param pluginName 插件名
     * @return 候选 cfg 相对路径列表（去重、按优先级排序）
     */
    public List<String> getCandidatePaths(Long instanceId, String pluginName) {
        List<String> paths = new ArrayList<>();
        // 主候选：插件 meta（plugin.yaml）声明的 config_files。
        // 声明路径是唯一的逻辑文件；读取时再回退到插件库副本。
        try {
            PluginMeta meta = pluginMetaService.load(instanceId, pluginName);
            if (meta != null && meta.getConfigFiles() != null) {
                for (String cfg : meta.getConfigFiles()) {
                    if (cfg == null || cfg.isBlank()) {
                        continue;
                    }
                    String rel = cfg.trim();
                    if (!paths.contains(rel)) {
                        paths.add(rel);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("读取插件 meta 的 config_files 失败 instanceId={}, pluginName={}, err={}",
                    instanceId, pluginName, e.getMessage());
        }
        // 无声明时才按插件名推导，避免真实多文件插件混入同名的缺失候选。
        if (paths.isEmpty()) {
            for (String candidate : getNameBasedCandidates(pluginName)) {
                if (!paths.contains(candidate)) {
                    paths.add(candidate);
                }
            }
        }
        return paths;
    }

    /**
     * 按插件名推导候选 cfg 路径（无实例上下文/无 meta 时的兜底规则）。
     *
     * <p>对齐 l4d2-server-next getPluginConfigCandidates：
     * <ul>
     *   <li>主候选：{@code cfg/sourcemod/{pluginName}.cfg}</li>
     *   <li>次候选：{@code addons/sourcemod/plugins/{pluginName}.cfg}</li>
     *   <li>l4d2_/l4d_ 互转别名：若插件名以 l4d2_ 开头，追加 l4d_ 同名候选；反之亦然</li>
     * </ul>
     *
     * @param pluginName 插件名
     * @return 候选 cfg 相对路径列表（最多 4 个）
     */
    public List<String> getNameBasedCandidates(String pluginName) {
        if (pluginName == null || pluginName.isBlank()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>(4);
        paths.add(CFG_SOURCEMOD_PREFIX + pluginName + ".cfg");
        paths.add(PLUGINS_PREFIX + pluginName + ".cfg");

        // l4d2_ ↔ l4d_ 互转别名
        String alias = getL4dAlias(pluginName);
        if (alias != null) {
            paths.add(CFG_SOURCEMOD_PREFIX + alias + ".cfg");
            paths.add(PLUGINS_PREFIX + alias + ".cfg");
        }
        return paths;
    }

    /**
     * 获取 l4d2_/l4d_ 互转别名。
     *
     * @param pluginName 插件名
     * @return 别名（如 l4d2_xxx → l4d_xxx）；非 l4d_/l4d2_ 前缀返回 null
     */
    private String getL4dAlias(String pluginName) {
        if (pluginName == null) {
            return null;
        }
        if (pluginName.startsWith("l4d2_")) {
            return "l4d_" + pluginName.substring("l4d2_".length());
        }
        if (pluginName.startsWith("l4d_")) {
            return "l4d2_" + pluginName.substring("l4d_".length());
        }
        return null;
    }

    /**
     * 获取配置：分别读取插件声明的全部 cfg 文件 → GBK 解码 → parse。
     *
     * <p>单个文件读取失败只标记该文件，不影响其他文件。兼容字段指向首个成功读取的文件。
     *
     * @return 配置资源；没有任何存在的候选文件时返回 null
     */
    public PluginConfigResource getConfig(Long instanceId, String pluginName) {
        InstanceVO instance = requireInstance(instanceId);
        Long hostId = instance.getHostId();
        List<PluginConfigFileSpec> files = readConfigFiles(instanceId, pluginName);
        if (files.stream().noneMatch(PluginConfigFileSpec::isExists)) {
            log.info("未找到候选 cfg 文件 instanceId={}, pluginName={}", instanceId, pluginName);
            return null;
        }
        PluginConfigResource resource = upsertResource(instanceId, hostId, pluginName, files);
        log.info("加载插件配置完成 instanceId={}, pluginName={}, fileCount={}",
                instanceId, pluginName, files.size());
        return resource;
    }

    /**
     * 列出候选 cfg 文件路径（含存在性标记）。
     */
    public List<CandidatePathVO> listCandidates(Long instanceId, String pluginName) {
        requireInstance(instanceId);
        List<CandidatePathVO> result = new ArrayList<>();
        for (String candidate : getCandidatePaths(instanceId, pluginName)) {
            CandidatePathVO vo = new CandidatePathVO();
            vo.setPath(candidate);
            vo.setExists(fileExistsSafe(instanceId, toRelativePath(candidate)));
            result.add(vo);
        }
        return result;
    }

    /**
     * 更新配置：serialize → 写回 + 更新扩展资源。
     *
     * @throws L4D2PluginException 没有候选 cfg 文件存在时抛出
     */
    public void updateConfig(Long instanceId, String pluginName, List<ConfigItem> items) {
        updateConfig(instanceId, pluginName, items, null, null);
    }

    /**
     * 更新指定 cfg 文件；targetConfigPath 为空时保持旧的“首个存在候选”行为。
     */
    public void updateConfig(Long instanceId, String pluginName, List<ConfigItem> items,
                             String fileId, String targetConfigPath) {
        InstanceVO instance = requireInstance(instanceId);
        Long hostId = instance.getHostId();
        ResolvedConfigTarget target = resolveTarget(
                instanceId, pluginName, fileId, targetConfigPath, true);
        String targetRelPath = target.relativePath();
        if (targetRelPath == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "无可更新的 cfg 文件，请先确认插件已生成配置: " + pluginName);
        }

        String original;
        Charset writeCharset;
        try {
            GbkCodecUtil.DecodedText decoded = readConfigText(instanceId, targetRelPath);
            original = decoded.text();
            writeCharset = decoded.charset();
        } catch (Exception e) {
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "读取原配置文件失败: " + e.getMessage(), e);
        }
        String serialized = cfgParser.serialize(items, original);
        try {
            instanceFileService.writeTextFile(instanceId, targetRelPath, serialized, writeCharset);
        } catch (Exception e) {
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "写入配置文件失败: " + e.getMessage(), e);
        }
        refreshResource(instanceId, hostId, pluginName);
        log.info("更新插件配置成功 instanceId={}, pluginName={}, path={}", instanceId, pluginName, targetRelPath);
    }

    /**
     * 更新或创建插件 cfg 文件中的 CVAR 键值对（不调 RCON，仅写文件）。
     *
     * <p>对齐 l4d2-server-next UpdateOrCreatePluginConfig：
     * <ol>
     *   <li>推导 cfg 候选路径（cfg/sourcemod/{pluginName}.cfg 优先）</li>
     *   <li>读取现有内容（不存在则空）</li>
     *   <li>用 parser 解析为 ConfigItem 列表</li>
     *   <li>更新 values 中的 key（已存在则覆盖 value，不存在则追加新行）</li>
     *   <li>写回文件</li>
     * </ol>
     *
     * @param instanceId 实例 ID
     * @param pluginName 插件名（当前仅用于日志；Phase 7.2 将用于 l4d2↔l4d 互转路径推导）
     * @param cfgName    cfg 文件名（如 l4d2_multi_slot.cfg）
     * @param values     CVAR 键值对
     */
    public void updateOrCreateConfig(Long instanceId, String pluginName, String cfgName,
                                     java.util.Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        String cfgRelPath = resolveCfgRelPath(pluginName, cfgName);

        String original = "";
        Charset originalCharset = gbk;
        try {
            if (instanceFileService.exists(instanceId, cfgRelPath)) {
                GbkCodecUtil.DecodedText decoded = readConfigText(instanceId, cfgRelPath);
                original = decoded.text();
                originalCharset = decoded.charset();
            }
        } catch (Exception e) {
            log.warn("读取 cfg 文件失败，将创建新文件: path={}, err={}", cfgRelPath, e.getMessage());
        }

        java.util.List<ConfigItem> items = cfgParser.parse(original);
        java.util.Set<String> updatedKeys = new java.util.HashSet<>();
        for (ConfigItem item : items) {
            String key = item.getKey();
            if (values.containsKey(key)) {
                item.setValue(values.get(key));
                updatedKeys.add(key);
            }
        }
        // 追加未存在的 key
        for (java.util.Map.Entry<String, String> entry : values.entrySet()) {
            if (!updatedKeys.contains(entry.getKey())) {
                ConfigItem newItem = new ConfigItem();
                newItem.setKey(entry.getKey());
                newItem.setValue(entry.getValue());
                newItem.setLineNumber(items.size() + 1);
                items.add(newItem);
            }
        }

        String updated = cfgParser.serialize(items, original);
        instanceFileService.writeTextFile(instanceId, cfgRelPath, updated, originalCharset);
        log.info("cfg 配置已更新: instanceId={}, plugin={}, cfg={}, keys={}",
                instanceId, pluginName, cfgName, values.size());
    }

    /**
     * 推导 cfg 相对路径（相对实例根目录，已含 left4dead2/ 前缀）。
     * <p>getSourceModCfgPath 返回 "left4dead2/cfg/sourcemod"，故此处结果已是完整相对路径，
     * 可直接传给 instanceFileService，无需再拼接 gamePath。
     */
    private String resolveCfgRelPath(String pluginName, String cfgName) {
        return pathResolver.getSourceModCfgPath() + "/" + cfgName;
    }

    // ===== 内部方法 =====

    private InstanceVO requireInstance(Long instanceId) {
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "实例不存在: " + instanceId);
        }
        return instance;
    }

    /**
     * 将候选路径（相对 left4dead2/）映射为相对实例游戏数据根目录的相对路径。
     * <p>优先使用 pathResolver 的标准方法，避免硬编码 left4dead2 前缀。
     */
    private String toRelativePath(String candidatePath) {
        // 已是完整相对路径（如插件库副本 left4dead2/addons/...）则原样透传，避免二次拼接
        if (candidatePath.startsWith(pathResolver.getGamePath() + "/")) {
            return candidatePath;
        }
        if (candidatePath.startsWith(CFG_SOURCEMOD_PREFIX)) {
            String filename = candidatePath.substring(CFG_SOURCEMOD_PREFIX.length());
            return pathResolver.getSourceModCfgPath() + "/" + filename;
        }
        if (candidatePath.startsWith(PLUGINS_PREFIX)) {
            String filename = candidatePath.substring(PLUGINS_PREFIX.length());
            return pathResolver.getSourceModPluginsPath() + "/" + filename;
        }
        // 兜底：拼到 left4dead2 目录下
        return pathResolver.getGamePath() + "/" + candidatePath;
    }

    private boolean fileExistsSafe(Long instanceId, String relPath) {
        try {
            return instanceFileService.exists(instanceId, relPath);
        } catch (Exception e) {
            log.debug("检查文件存在性失败 path={}, err={}", relPath, e.getMessage());
            return false;
        }
    }

    private String buildResourceName(Long instanceId, String pluginName) {
        return instanceId + "-" + pluginName;
    }

    // ===== 多文件读取、目标解析与快照 =====

    private List<PluginConfigFileSpec> readConfigFiles(Long instanceId, String pluginName) {
        List<PluginConfigFileSpec> files = new ArrayList<>();
        String storePrefix = pathResolver.getPluginLeft4Dead2Path(pluginName) + "/";
        Set<String> seenTargets = new LinkedHashSet<>();
        for (String candidate : getCandidatePaths(instanceId, pluginName)) {
            String relPath = toRelativePath(candidate);
            String targetKey = resolvedTargetKey(candidate, relPath);
            if (!fileExistsSafe(instanceId, relPath)) {
                String storeRelPath = storePrefix + normalizeDeclaredConfigPath(candidate, relPath);
                if (fileExistsSafe(instanceId, storeRelPath)) {
                    relPath = storeRelPath;
                    targetKey = resolvedTargetKey(candidate, relPath);
                }
            }
            if (!seenTargets.add(targetKey)) {
                continue;
            }
            files.add(readConfigFile(instanceId, pluginName, candidate, relPath));
        }
        return files;
    }

    private PluginConfigFileSpec readConfigFile(Long instanceId, String pluginName,
                                                String candidate, String relPath) {
        PluginConfigFileSpec file = new PluginConfigFileSpec();
        file.setFileId(fileIdOf(candidate, relPath));
        file.setConfigName(configNameOf(relPath));
        file.setConfigPath(candidate);
        file.setItems(List.of());
        file.setLastSyncedAt(LocalDateTime.now());
        try {
            if (!instanceFileService.exists(instanceId, relPath)) {
                file.setExists(false);
                file.setStatus(CONFIG_STATUS_MISSING);
                file.setError("配置文件不存在");
                return file;
            }
            file.setExists(true);
            String content = readConfigText(instanceId, relPath).text();
            file.setRawContent(content);
            List<ConfigItem> items = cfgParser.parse(content);
            file.setItems(items);
            file.setStatus(items.isEmpty() ? CONFIG_STATUS_EMPTY : CONFIG_STATUS_OK);
            if (items.isEmpty()) {
                file.setError("配置文件中没有可编辑的配置项");
            }
        } catch (Exception e) {
            log.warn("读取 cfg 文件失败 instanceId={}, pluginName={}, path={}, err={}",
                    instanceId, pluginName, relPath, e.getMessage());
            file.setStatus(CONFIG_STATUS_ERROR);
            file.setError("读取配置文件失败: " + e.getMessage());
        }
        return file;
    }

    private GbkCodecUtil.DecodedText readConfigText(Long instanceId, String relPath) {
        byte[] bytes = instanceFileService.downloadFileToMemory(instanceId, relPath);
        return GbkCodecUtil.decodeAutoWithCharset(bytes);
    }

    private record ResolvedConfigTarget(String candidate, String relativePath) {
    }

    private ResolvedConfigTarget resolveTarget(Long instanceId, String pluginName,
                                               String fileId, String requestedPath,
                                               boolean requireExistingTarget) {
        List<ResolvedConfigTarget> candidates = resolveAvailableTargets(instanceId, pluginName);
        if (fileId != null && !fileId.isBlank()) {
            for (ResolvedConfigTarget target : candidates) {
                if (fileId.equals(fileIdOf(target.candidate(), target.relativePath()))) {
                    if (requireExistingTarget && !fileExistsSafe(instanceId, target.relativePath())) {
                        throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                                "目标配置文件不存在: " + fileId);
                    }
                    return target;
                }
            }
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "目标配置文件不属于该插件: " + fileId);
        }
        if (requestedPath != null && !requestedPath.isBlank()) {
            List<ResolvedConfigTarget> matches = candidates.stream()
                    .filter(target -> matchesConfigTarget(
                            requestedPath, target.candidate(), target.relativePath()))
                    .toList();
            if (matches.size() == 1) {
                ResolvedConfigTarget target = matches.get(0);
                if (requireExistingTarget && !fileExistsSafe(instanceId, target.relativePath())) {
                        throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                                "目标配置文件不存在: " + requestedPath);
                }
                return target;
            }
            if (matches.size() > 1) {
                throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                        "目标配置文件名称不唯一，请使用完整路径: " + requestedPath);
            }
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "目标配置文件不属于该插件: " + requestedPath);
        }
        for (ResolvedConfigTarget target : candidates) {
            if (fileExistsSafe(instanceId, target.relativePath())) {
                return target;
            }
        }
        return new ResolvedConfigTarget(null, null);
    }

    private boolean matchesConfigTarget(String requestedPath, String candidate, String relPath) {
        String normalized = requestedPath.replace('\\', '/').trim();
        if (normalized.contains("..")) {
            return false;
        }
        return normalized.equals(candidate)
                || normalized.equals(relPath)
                || normalized.equals(configNameOf(relPath));
    }

    /**
     * 枚举声明的逻辑目标，并在游戏目录缺失时映射到插件库副本。
     * 这样 fileId 始终绑定声明目标，而不会在目录回退时漂移。
     */
    private List<ResolvedConfigTarget> resolveAvailableTargets(Long instanceId, String pluginName) {
        List<ResolvedConfigTarget> targets = new ArrayList<>();
        String storePrefix = pathResolver.getPluginLeft4Dead2Path(pluginName) + "/";
        for (String candidate : getCandidatePaths(instanceId, pluginName)) {
            String gameRelPath = toRelativePath(candidate);
            String relPath = gameRelPath;
            if (!fileExistsSafe(instanceId, gameRelPath)) {
                String storeRelPath = storePrefix + normalizeDeclaredConfigPath(candidate, gameRelPath);
                if (fileExistsSafe(instanceId, storeRelPath)) {
                    relPath = storeRelPath;
                }
            }
            targets.add(new ResolvedConfigTarget(candidate, relPath));
        }
        return targets;
    }

    private String normalizeDeclaredConfigPath(String candidate, String relPath) {
        String normalizedCandidate = candidate.replace('\\', '/');
        String gamePrefix = pathResolver.getGamePath() + "/";
        if (normalizedCandidate.startsWith(gamePrefix)) {
            return normalizedCandidate.substring(gamePrefix.length());
        }
        if (normalizedCandidate.startsWith(CFG_SOURCEMOD_PREFIX)) {
            return normalizedCandidate;
        }
        if (normalizedCandidate.startsWith(PLUGINS_PREFIX)) {
            return normalizedCandidate;
        }
        String normalizedRel = relPath.replace('\\', '/');
        return normalizedRel.startsWith(gamePrefix)
                ? normalizedRel.substring(gamePrefix.length())
                : normalizedRel;
    }

    private String resolvedTargetKey(String candidate, String relPath) {
        String normalized = relPath.replace('\\', '/');
        String gamePrefix = pathResolver.getGamePath() + "/";
        return normalized.startsWith(gamePrefix) ? normalized.substring(gamePrefix.length()) : normalized;
    }

    private String configNameOf(String relPath) {
        int slash = relPath.lastIndexOf('/');
        return slash >= 0 ? relPath.substring(slash + 1) : relPath;
    }

    private String fileIdOf(String candidate, String relPath) {
        String key = candidate == null || candidate.isBlank() ? relPath : candidate;
        return Integer.toHexString((key + "|" + relPath).hashCode());
    }

    private void refreshResource(Long instanceId, Long hostId, String pluginName) {
        List<PluginConfigFileSpec> files = readConfigFiles(instanceId, pluginName);
        if (files.stream().anyMatch(PluginConfigFileSpec::isExists)) {
            upsertResource(instanceId, hostId, pluginName, files);
        }
    }

    /**
     * Upsert 扩展资源：存在则更新 spec，不存在则创建。返回最新资源对象。
     */
    private PluginConfigResource upsertResource(Long instanceId, Long hostId, String pluginName,
                                                List<PluginConfigFileSpec> files) {
        String name = buildResourceName(instanceId, pluginName);
        Optional<PluginConfigResource> existing = getStoredResource(instanceId, pluginName);
        PluginConfigSpec spec = new PluginConfigSpec();
        spec.setInstanceId(instanceId);
        spec.setHostId(hostId);
        spec.setPluginName(pluginName);
        PluginConfigFileSpec primary = files.stream()
                .filter(file -> CONFIG_STATUS_OK.equals(file.getStatus())
                        || CONFIG_STATUS_EMPTY.equals(file.getStatus()))
                .findFirst()
                .orElse(files.stream().filter(PluginConfigFileSpec::isExists).findFirst().orElse(null));
        if (primary != null) {
            spec.setConfigName(primary.getConfigName());
            spec.setConfigPath(primary.getConfigPath());
            spec.setItems(primary.getItems());
            spec.setRawContent(primary.getRawContent());
        }
        spec.setConfigFiles(files);
        spec.setLastSyncedAt(LocalDateTime.now());

        if (existing.isPresent()) {
            PluginConfigResource resource = existing.get();
            resource.setSpec(spec);
            extensionClient.update(resource);
            return resource;
        }
        PluginConfigResource resource = new PluginConfigResource();
        resource.setName(name);
        resource.setSpec(spec);
        extensionClient.create(resource);
        return resource;
    }

    private Optional<PluginConfigResource> getStoredResource(Long instanceId, String pluginName) {
        try {
            return extensionClient.get(PluginConfigResource.class, buildResourceName(instanceId, pluginName));
        } catch (Exception e) {
            log.debug("查询扩展资源失败 name={}, err={}", buildResourceName(instanceId, pluginName), e.getMessage());
            return Optional.empty();
        }
    }

    // ===== 临时应用 & 恢复默认 =====

    /**
     * 临时应用 CVAR 配置：通过 RCON sm_cvar 实时设置，不写文件，服务器重启后失效。
     *
     * <p>对齐 l4d2-server-next PluginConfigModal.applyTempConfig 实现。
     *
     * @param instanceId 实例 ID
     * @param cvarName   CVAR 名称（不可为空）
     * @param cvarValue  CVAR 值（不可为 null，原样传递，含空格需用双引号包裹由调用方决定）
     * @throws L4D2PluginException cvarName 为空 / 实例不存在 / RCON 调用失败
     */
    public void applyTempConfig(Long instanceId, String cvarName, String cvarValue) {
        InstanceVO instance = requireInstance(instanceId);
        if (cvarName == null || cvarName.isBlank()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "cvarName 不能为空");
        }
        if (cvarValue == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "cvarValue 不能为 null");
        }
        // 危险 CVAR 黑名单校验
        CvarBlacklist.check(cvarName);
        try {
            String cmd = "sm_cvar " + cvarName + " \"" + cvarValue + "\"";
            rconService.executeCommand(instanceId, cmd);
            log.info("临时配置已应用: instanceId={}, cvar={}, value={}", instanceId, cvarName, cvarValue);
            auditService.logApplyTempConfig(instanceId, instance.getHostId(), null,
                    cvarName, cvarValue, "system");
        } catch (L4D2PluginException e) {
            throw e;
        } catch (Exception e) {
            throw new L4D2PluginException(L4D2PluginException.RCON,
                    "临时应用配置失败: " + e.getMessage(), e);
        }
    }

    /**
     * 恢复插件 CVAR 配置到默认值：从 cfg 文件注释中的 Default 字段重建配置。
     *
     * <p>对齐 l4d2-server-next RestoreSourceModConfig 设计：
     * <ol>
     *   <li>从候选路径中找到第一个实际存在的 cfg 文件</li>
     *   <li>读取并解析，对每个有 defaultValue 的 item，将 value 重置为 defaultValue</li>
     *   <li>写回文件，保留注释与格式</li>
     *   <li>更新扩展资源 PluginConfigResource</li>
     * </ol>
     *
     * @param instanceId 实例 ID
     * @param pluginName 插件名（用于推导候选 cfg 路径）
     * @throws L4D2PluginException 无候选 cfg / 读写出错
     */
    public void restoreDefaults(Long instanceId, String pluginName) {
        restoreDefaults(instanceId, pluginName, null, null);
    }

    /**
     * 恢复指定 cfg 文件的默认值；targetConfigPath 为空时保持旧的首文件行为。
     */
    public void restoreDefaults(Long instanceId, String pluginName,
                                String fileId, String targetConfigPath) {
        InstanceVO instance = requireInstance(instanceId);
        if (pluginName == null || pluginName.isBlank()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "pluginName 不能为空");
        }

        // 1. 找到候选 cfg 文件
        ResolvedConfigTarget target = resolveTarget(
                instanceId, pluginName, fileId, targetConfigPath, true);
        String targetRelPath = target.relativePath();
        if (targetRelPath == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "无可恢复的 cfg 文件，请先确认插件已生成配置: " + pluginName);
        }

        // 2. 读取并解析
        String content;
        Charset writeCharset;
        try {
            GbkCodecUtil.DecodedText decoded = readConfigText(instanceId, targetRelPath);
            content = decoded.text();
            writeCharset = decoded.charset();
        } catch (Exception e) {
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "读取配置文件失败: " + e.getMessage(), e);
        }
        List<ConfigItem> items = cfgParser.parse(content);

        // 3. 对每个 item，若有 defaultValue，将 value 重置为 defaultValue
        int changed = 0;
        for (ConfigItem item : items) {
            String defaultValue = item.getDefaultValue();
            if (defaultValue != null && !defaultValue.isEmpty()) {
                if (!defaultValue.equals(item.getValue())) {
                    item.setValue(defaultValue);
                    changed++;
                }
            }
        }

        if (changed == 0) {
            log.info("无需恢复默认配置（所有 CVAR 已是默认值或无默认值）: instanceId={}, plugin={}",
                    instanceId, pluginName);
            return;
        }

        // 4. 写回
        String serialized = cfgParser.serialize(items, content);
        try {
            instanceFileService.writeTextFile(instanceId, targetRelPath, serialized, writeCharset);
        } catch (Exception e) {
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "写入配置文件失败: " + e.getMessage(), e);
        }

        // 5. 更新扩展资源
        refreshResource(instanceId, instance.getHostId(), pluginName);

        log.info("已恢复默认配置: instanceId={}, plugin={}, changed={}", instanceId, pluginName, changed);
    }
}

package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.extension.PluginConfigResource;
import com.gameplatform.plugin.l4d2.extension.PluginConfigSpec;
import com.gameplatform.plugin.l4d2.parser.SourceModCfgParser;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.util.CvarBlacklist;
import com.gameplatform.plugin.l4d2.util.GbkCodecUtil;
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
import java.util.List;
import java.util.Optional;

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

    private final InstanceQueryService instanceQueryService;
    private final InstanceFileService instanceFileService;
    private final ExtensionClient extensionClient;
    private final SourceModCfgParser cfgParser;
    private final L4D2PathResolver pathResolver;
    private final RconService rconService;
    private final PluginConfigAuditService auditService;
    private final Charset gbk = GbkCodecUtil.gbk();

    /**
     * 候选 cfg 路径推导。
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
    public List<String> getCandidatePaths(String pluginName) {
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
     * 获取配置：读第一个存在的候选文件 → GBK 解码 → parse。
     * <p>同时更新扩展资源（如存在则更新，不存在则创建）。
     *
     * @return 配置资源；不存在候选文件时返回 null
     */
    public PluginConfigResource getConfig(Long instanceId, String pluginName) {
        InstanceVO instance = requireInstance(instanceId);
        Long hostId = instance.getHostId();

        for (String candidate : getCandidatePaths(pluginName)) {
            String relPath = toRelativePath(candidate);
            if (!fileExistsSafe(instanceId, relPath)) {
                continue;
            }
            String content;
            try {
                content = instanceFileService.readTextFile(instanceId, relPath, gbk);
            } catch (Exception e) {
                log.warn("读取 cfg 文件失败 instanceId={}, pluginName={}, path={}, err={}",
                        instanceId, pluginName, relPath, e.getMessage());
                throw new L4D2PluginException(L4D2PluginException.FILE,
                        "读取配置文件失败: " + e.getMessage(), e);
            }
            List<ConfigItem> items = cfgParser.parse(content);
            String configName = pluginName + ".cfg";
            PluginConfigResource resource = upsertResource(
                    instanceId, hostId, pluginName, configName, candidate, items, content);
            log.info("加载插件配置成功 instanceId={}, pluginName={}, path={}, itemCount={}",
                    instanceId, pluginName, relPath, items.size());
            return resource;
        }
        log.info("未找到候选 cfg 文件 instanceId={}, pluginName={}", instanceId, pluginName);
        return null;
    }

    /**
     * 列出候选 cfg 文件路径（含存在性标记）。
     */
    public List<CandidatePathVO> listCandidates(Long instanceId, String pluginName) {
        requireInstance(instanceId);
        List<CandidatePathVO> result = new ArrayList<>();
        for (String candidate : getCandidatePaths(pluginName)) {
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
        InstanceVO instance = requireInstance(instanceId);
        Long hostId = instance.getHostId();

        String targetCandidate = null;
        String targetRelPath = null;
        for (String candidate : getCandidatePaths(pluginName)) {
            String relPath = toRelativePath(candidate);
            if (fileExistsSafe(instanceId, relPath)) {
                targetCandidate = candidate;
                targetRelPath = relPath;
                break;
            }
        }
        if (targetRelPath == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "无可更新的 cfg 文件，请先确认插件已生成配置: " + pluginName);
        }

        String original;
        try {
            original = instanceFileService.readTextFile(instanceId, targetRelPath, gbk);
        } catch (Exception e) {
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "读取原配置文件失败: " + e.getMessage(), e);
        }
        String serialized = cfgParser.serialize(items, original);
        try {
            instanceFileService.writeTextFile(instanceId, targetRelPath, serialized);
        } catch (Exception e) {
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "写入配置文件失败: " + e.getMessage(), e);
        }
        String configName = pluginName + ".cfg";
        upsertResource(instanceId, hostId, pluginName, configName, targetCandidate, items, serialized);
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
        try {
            if (instanceFileService.exists(instanceId, cfgRelPath)) {
                original = instanceFileService.readTextFile(instanceId, cfgRelPath, gbk);
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
        instanceFileService.writeTextFile(instanceId, cfgRelPath, updated, gbk);
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

    /**
     * Upsert 扩展资源：存在则更新 spec，不存在则创建。返回最新资源对象。
     */
    private PluginConfigResource upsertResource(Long instanceId, Long hostId, String pluginName,
                                                String configName, String configPath,
                                                List<ConfigItem> items, String rawContent) {
        String name = buildResourceName(instanceId, pluginName);
        Optional<PluginConfigResource> existing = getStoredResource(instanceId, pluginName);
        PluginConfigSpec spec = new PluginConfigSpec();
        spec.setInstanceId(instanceId);
        spec.setHostId(hostId);
        spec.setPluginName(pluginName);
        spec.setConfigName(configName);
        spec.setConfigPath(configPath);
        spec.setItems(items);
        spec.setRawContent(rawContent);
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
        InstanceVO instance = requireInstance(instanceId);
        if (pluginName == null || pluginName.isBlank()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "pluginName 不能为空");
        }

        // 1. 找到候选 cfg 文件
        String targetCandidate = null;
        String targetRelPath = null;
        for (String candidate : getCandidatePaths(pluginName)) {
            String relPath = toRelativePath(candidate);
            if (fileExistsSafe(instanceId, relPath)) {
                targetCandidate = candidate;
                targetRelPath = relPath;
                break;
            }
        }
        if (targetRelPath == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "无可恢复的 cfg 文件，请先确认插件已生成配置: " + pluginName);
        }

        // 2. 读取并解析
        String content;
        try {
            content = instanceFileService.readTextFile(instanceId, targetRelPath, gbk);
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
            instanceFileService.writeTextFile(instanceId, targetRelPath, serialized);
        } catch (Exception e) {
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "写入配置文件失败: " + e.getMessage(), e);
        }

        // 5. 更新扩展资源
        String configName = pluginName + ".cfg";
        upsertResource(instanceId, instance.getHostId(), pluginName, configName,
                targetCandidate, items, serialized);

        log.info("已恢复默认配置: instanceId={}, plugin={}, changed={}", instanceId, pluginName, changed);
    }
}

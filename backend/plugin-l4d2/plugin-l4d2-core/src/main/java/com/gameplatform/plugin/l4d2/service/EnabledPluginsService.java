package com.gameplatform.plugin.l4d2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.extension.EnabledPluginResource;
import com.gameplatform.plugin.l4d2.extension.EnabledPluginSpec;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.EnabledPlugin;
import com.gameplatform.plugin.service.FileAccessService.FileInfo;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 已启用插件管理：.enabled_plugins.yaml 远程文件 + EnabledPluginResource 扩展资源双写。
 *
 * <p>yaml 为事实来源，扩展资源用于前端快速查询。进程重启后从 yaml 重建。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
public class EnabledPluginsService {

    private static final String YAML_KEY = "enabled_plugins";

    private final InstanceFileService instanceFileService;
    private final L4D2PathResolver pathResolver;
    private final ExtensionClient extensionClient;
    private final InstanceQueryService instanceQueryService;
    private final ObjectMapper yamlMapper;

    public EnabledPluginsService(InstanceFileService instanceFileService,
                                 L4D2PathResolver pathResolver,
                                 ExtensionClient extensionClient,
                                 InstanceQueryService instanceQueryService,
                                 ObjectMapper yamlMapper) {
        this.instanceFileService = instanceFileService;
        this.pathResolver = pathResolver;
        this.extensionClient = extensionClient;
        this.instanceQueryService = instanceQueryService;
        this.yamlMapper = yamlMapper;
    }

    /** 从远程 yaml 加载已启用插件列表 */
    public List<EnabledPlugin> loadYaml(Long instanceId) {
        String path = pathResolver.getEnabledPluginsYamlPath();
        if (!existsSafe(instanceId, path)) {
            return new ArrayList<>();
        }
        try {
            String content = instanceFileService.readTextFile(instanceId, path, StandardCharsets.UTF_8);
            if (content == null || content.isBlank()) {
                return new ArrayList<>();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> root = yamlMapper.readValue(content, Map.class);
            Object pluginsNode = root.get(YAML_KEY);
            if (!(pluginsNode instanceof List<?> list)) {
                return new ArrayList<>();
            }
            List<EnabledPlugin> result = new ArrayList<>();
            Map<String, Set<String>> storeSmxIndex = null;
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) continue;
                EnabledPlugin ep = new EnabledPlugin();
                ep.setName(asString(map.get("name")));
                ep.setSource(asString(map.get("source")));
                ep.setEnabledAt(asLong(map.get("enabled_at")));
                ep.setFiles(asStringList(map.get("files")));
                if (isCorruptedName(ep.getName())) {
                    if (storeSmxIndex == null) {
                        storeSmxIndex = buildStoreSmxIndex(instanceId);
                    }
                    String restored = restoreCorruptedName(ep, storeSmxIndex);
                    if (restored == null) {
                        log.warn("跳过无法唯一恢复的已启用插件记录: instanceId={}, name={}",
                                instanceId, ep.getName());
                        continue;
                    }
                    ep.setName(restored);
                }
                result.add(ep);
            }
            return result;
        } catch (Exception e) {
            log.warn("加载 enabled_plugins.yaml 失败 instanceId={}, err={}", instanceId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 保存到远程 yaml + 同步到扩展资源 */
    public void saveYaml(Long instanceId, List<EnabledPlugin> plugins) {
        String path = pathResolver.getEnabledPluginsYamlPath();
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            List<Map<String, Object>> pluginList = new ArrayList<>();
            for (EnabledPlugin ep : plugins) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", ep.getName());
                m.put("source", ep.getSource() != null ? ep.getSource() : "upload");
                m.put("enabled_at", ep.getEnabledAt() != null ? ep.getEnabledAt() : System.currentTimeMillis());
                m.put("files", ep.getFiles() != null ? ep.getFiles() : List.of());
                pluginList.add(m);
            }
            root.put(YAML_KEY, pluginList);
            String yaml = yamlMapper.writeValueAsString(root);
            instanceFileService.writeTextFile(instanceId, path, yaml, StandardCharsets.UTF_8);
            // 同步扩展资源（先清空再重建）
            syncResources(instanceId, plugins);
        } catch (Exception e) {
            log.error("保存 enabled_plugins.yaml 失败 instanceId={}", instanceId, e);
            throw new L4D2PluginException(L4D2PluginException.FILE, "保存已启用插件清单失败: " + e.getMessage(), e);
        }
    }

    /** 添加一个已启用插件（追加到 yaml） */
    public void add(Long instanceId, EnabledPlugin plugin) {
        addAll(instanceId, List.of(plugin));
    }

    /**
     * 批量添加已启用插件：一次读取现有清单、一次写回并一次同步扩展资源。
     *
     * <p>批量启用 N 个插件若逐个调用 {@link #add}，会产生 N 次全量 YAML 读写；
     * 这里按插件名去重后合并，保证批量启用的网络往返不随插件数线性增加。
     */
    public void addAll(Long instanceId, List<EnabledPlugin> plugins) {
        if (plugins == null || plugins.isEmpty()) {
            return;
        }
        List<EnabledPlugin> current = new ArrayList<>(loadYaml(instanceId));
        current.removeIf(Objects::isNull);
        for (EnabledPlugin plugin : plugins) {
            if (plugin == null || plugin.getName() == null) {
                continue;
            }
            current.removeIf(p -> plugin.getName().equals(p.getName()));
            current.add(plugin);
        }
        saveYaml(instanceId, current);
    }

    /** 移除一个已启用插件 */
    public void remove(Long instanceId, String pluginName) {
        List<EnabledPlugin> current = loadYaml(instanceId);
        boolean removed = current.removeIf(p -> pluginName.equals(p.getName()));
        if (removed) {
            saveYaml(instanceId, current);
        }
        // 删除扩展资源（即使 yaml 中没有也尝试清理资源，保证一致性）
        String resourceName = buildResourceName(instanceId, pluginName);
        try {
            Optional<EnabledPluginResource> res = extensionClient.get(EnabledPluginResource.class, resourceName);
            res.ifPresent(r -> extensionClient.delete(EnabledPluginResource.class, r.getName()));
        } catch (Exception e) {
            log.debug("删除扩展资源失败 name={}, err={}", resourceName, e.getMessage());
        }
    }

    /** 查询插件是否已启用 */
    public boolean isEnabled(Long instanceId, String pluginName) {
        return loadYaml(instanceId).stream().anyMatch(p -> pluginName.equals(p.getName()));
    }

    /** 列出所有已启用插件（从 yaml 加载） */
    public List<EnabledPlugin> list(Long instanceId) {
        return loadYaml(instanceId);
    }

    // ===== 内部方法 =====

    private void syncResources(Long instanceId, List<EnabledPlugin> plugins) {
        Long hostId = resolveHostId(instanceId);
        // 删除不再启用的资源
        try {
            List<EnabledPluginResource> existing = extensionClient.listAll(EnabledPluginResource.class);
            if (existing != null) {
                for (EnabledPluginResource res : existing) {
                    if (res.getSpec() == null) continue;
                    if (!instanceId.equals(res.getSpec().getInstanceId())) continue;
                    boolean stillEnabled = plugins.stream()
                            .anyMatch(p -> p.getName().equals(res.getSpec().getPluginName()));
                    if (!stillEnabled) {
                        extensionClient.delete(EnabledPluginResource.class, res.getName());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("查询扩展资源列表失败: {}", e.getMessage());
        }
        // 创建/更新当前启用的资源
        for (EnabledPlugin ep : plugins) {
            String name = buildResourceName(instanceId, ep.getName());
            EnabledPluginSpec spec = new EnabledPluginSpec();
            spec.setInstanceId(instanceId);
            spec.setHostId(hostId);
            spec.setPluginName(ep.getName());
            spec.setSource(ep.getSource());
            spec.setEnabledAt(ep.getEnabledAt() != null
                    ? LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(ep.getEnabledAt()),
                        ZoneId.systemDefault())
                    : LocalDateTime.now());
            spec.setFiles(ep.getFiles() != null ? ep.getFiles() : new ArrayList<>());
            try {
                Optional<EnabledPluginResource> existing = extensionClient.get(EnabledPluginResource.class, name);
                if (existing.isPresent()) {
                    EnabledPluginResource r = existing.get();
                    r.setSpec(spec);
                    extensionClient.update(r);
                } else {
                    EnabledPluginResource r = new EnabledPluginResource();
                    r.setName(name);
                    r.setSpec(spec);
                    extensionClient.create(r);
                }
            } catch (Exception e) {
                log.warn("同步扩展资源失败 name={}, err={}", name, e.getMessage());
            }
        }
    }

    private Long resolveHostId(Long instanceId) {
        if (instanceQueryService == null) return null;
        try {
            InstanceVO vo = instanceQueryService.getInstanceById(instanceId);
            return vo != null ? vo.getHostId() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String buildResourceName(Long instanceId, String pluginName) {
        return instanceId + "-" + pluginName;
    }

    private boolean isCorruptedName(String name) {
        return name != null && name.indexOf('?') >= 0;
    }

    /**
     * 历史清单曾用平台默认字符集写回，中文插件名被替换为问号。
     *
     * <p>目录名仍是真实的 UTF-8 名称。以启用时记录的 SMX 文件名作为稳定指纹，
     * 只在唯一命中时恢复；歧义条目跳过，避免把启用状态挂到错误插件上。
     */
    private String restoreCorruptedName(EnabledPlugin plugin, Map<String, Set<String>> storeSmxIndex) {
        if (storeSmxIndex == null || storeSmxIndex.isEmpty()) {
            return null;
        }
        Set<String> enabledSmxNames = new HashSet<>();
        if (plugin.getFiles() != null) {
            for (String file : plugin.getFiles()) {
                if (file == null) continue;
                String filename = smxFileName(file);
                if (filename != null) {
                    enabledSmxNames.add(filename);
                }
            }
        }
        if (enabledSmxNames.isEmpty()) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : storeSmxIndex.entrySet()) {
            if (entry.getValue().stream().anyMatch(enabledSmxNames::contains)) {
                candidates.add(entry.getKey());
            }
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /**
     * 一次性扫描插件库，避免历史清单中的多条损坏记录重复触发远端目录读取。
     */
    private Map<String, Set<String>> buildStoreSmxIndex(Long instanceId) {
        Map<String, Set<String>> index = new LinkedHashMap<>();
        try {
            List<String> storeNames = instanceFileService.listFiles(
                            instanceId, pathResolver.getPluginsStorePath()).stream()
                    .filter(FileInfo::isDirectory)
                    .map(FileInfo::getName)
                    .filter(Objects::nonNull)
                    .filter(name -> !name.isBlank() && !isCorruptedName(name))
                    .toList();
            for (String storeName : storeNames) {
                String pluginsPath = pathResolver.getPluginLeft4Dead2Path(storeName)
                        + "/addons/sourcemod/plugins";
                Set<String> smxNames = new HashSet<>();
                try {
                    instanceFileService.listFiles(instanceId, pluginsPath).stream()
                            .filter(file -> !file.isDirectory())
                            .map(FileInfo::getName)
                            .filter(Objects::nonNull)
                            .map(this::smxFileName)
                            .filter(Objects::nonNull)
                            .forEach(smxNames::add);
                } catch (Exception e) {
                    log.debug("读取插件库文件失败，跳过候选: store={}, err={}",
                            storeName, e.getMessage());
                    continue;
                }
                if (!smxNames.isEmpty()) {
                    index.put(storeName, smxNames);
                }
            }
        } catch (Exception e) {
            log.warn("读取插件库目录失败，无法恢复已启用插件名: instanceId={}, err={}",
                    instanceId, e.getMessage());
        }
        return index;
    }

    private String smxFileName(String path) {
        if (path == null) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String filename = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return filename.toLowerCase().endsWith(".smx") ? filename : null;
    }

    private boolean existsSafe(Long instanceId, String path) {
        try {
            return instanceFileService.exists(instanceId, path);
        } catch (Exception e) {
            return false;
        }
    }

    private String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private List<String> asStringList(Object o) {
        if (!(o instanceof List<?> list)) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) result.add(item.toString());
        }
        return result;
    }
}

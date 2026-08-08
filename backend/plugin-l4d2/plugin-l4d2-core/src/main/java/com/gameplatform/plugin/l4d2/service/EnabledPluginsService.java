package com.gameplatform.plugin.l4d2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.extension.EnabledPluginResource;
import com.gameplatform.plugin.l4d2.extension.EnabledPluginSpec;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.EnabledPlugin;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) continue;
                EnabledPlugin ep = new EnabledPlugin();
                ep.setName(asString(map.get("name")));
                ep.setSource(asString(map.get("source")));
                ep.setEnabledAt(asLong(map.get("enabled_at")));
                ep.setFiles(asStringList(map.get("files")));
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
            instanceFileService.writeTextFile(instanceId, path, yaml);
            // 同步扩展资源（先清空再重建）
            syncResources(instanceId, plugins);
        } catch (Exception e) {
            log.error("保存 enabled_plugins.yaml 失败 instanceId={}", instanceId, e);
            throw new L4D2PluginException(L4D2PluginException.FILE, "保存已启用插件清单失败: " + e.getMessage(), e);
        }
    }

    /** 添加一个已启用插件（追加到 yaml） */
    public void add(Long instanceId, EnabledPlugin plugin) {
        List<EnabledPlugin> current = new ArrayList<>(loadYaml(instanceId));
        current.removeIf(p -> plugin.getName().equals(p.getName()));
        current.add(plugin);
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

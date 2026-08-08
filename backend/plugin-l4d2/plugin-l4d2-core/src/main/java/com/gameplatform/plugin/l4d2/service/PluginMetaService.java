package com.gameplatform.plugin.l4d2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.PluginMeta;
import com.gameplatform.plugin.service.InstanceFileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件元数据服务：读写 plugins_store/{name}/plugin.yaml。
 *
 * <p>对齐 l4d2-server-next plugins.yaml 中的 plugin_sources map，
 * 本项目改为每个插件独立 plugin.yaml，便于原子更新。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
public class PluginMetaService {

    private final InstanceFileService instanceFileService;
    private final L4D2PathResolver pathResolver;
    private final ObjectMapper yamlMapper;

    public PluginMetaService(InstanceFileService instanceFileService,
                             L4D2PathResolver pathResolver,
                             ObjectMapper yamlMapper) {
        this.instanceFileService = instanceFileService;
        this.pathResolver = pathResolver;
        this.yamlMapper = yamlMapper;
    }

    /** 读取插件元数据；不存在返回 null */
    public PluginMeta load(Long instanceId, String pluginName) {
        String path = pathResolver.getPluginYamlPath(pluginName);
        if (!existsSafe(instanceId, path)) {
            return null;
        }
        try {
            String content = instanceFileService.readTextFile(instanceId, path, StandardCharsets.UTF_8);
            if (content == null || content.isBlank()) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> root = yamlMapper.readValue(content, Map.class);
            PluginMeta meta = new PluginMeta();
            meta.setName(asString(root.get("name")));
            meta.setSource(asString(root.get("source")));
            meta.setVersion(asString(root.get("version")));
            meta.setAuthor(asString(root.get("author")));
            meta.setDescription(asString(root.get("description")));
            meta.setFileList(asStringList(root.get("file_list")));
            meta.setConfigFiles(asStringList(root.get("config_files")));
            meta.setCreatedAt(asLong(root.get("created_at")));
            meta.setUpdatedAt(asLong(root.get("updated_at")));
            return meta;
        } catch (Exception e) {
            log.warn("加载 plugin.yaml 失败 instanceId={}, plugin={}, err={}",
                    instanceId, pluginName, e.getMessage());
            return null;
        }
    }

    /** 保存插件元数据（覆盖写） */
    public void save(Long instanceId, PluginMeta meta) {
        if (meta == null || meta.getName() == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "PluginMeta/name 不能为空");
        }
        String path = pathResolver.getPluginYamlPath(meta.getName());
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("name", meta.getName());
            root.put("source", meta.getSource() != null ? meta.getSource() : "panel");
            root.put("version", meta.getVersion());
            root.put("author", meta.getAuthor());
            root.put("description", meta.getDescription());
            root.put("file_list", meta.getFileList() != null ? meta.getFileList() : List.of());
            root.put("config_files", meta.getConfigFiles() != null ? meta.getConfigFiles() : List.of());
            long now = System.currentTimeMillis();
            root.put("created_at", meta.getCreatedAt() != null ? meta.getCreatedAt() : now);
            root.put("updated_at", now);
            String yaml = yamlMapper.writeValueAsString(root);
            instanceFileService.writeTextFile(instanceId, path, yaml);
        } catch (Exception e) {
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "保存 plugin.yaml 失败: " + e.getMessage(), e);
        }
    }

    /** 删除插件元数据文件 */
    public void delete(Long instanceId, String pluginName) {
        String path = pathResolver.getPluginYamlPath(pluginName);
        try {
            instanceFileService.deleteFile(instanceId, path);
        } catch (Exception e) {
            log.debug("删除 plugin.yaml 失败 plugin={}, err={}", pluginName, e.getMessage());
        }
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
        if (!(o instanceof List<?> list)) return new java.util.ArrayList<>();
        List<String> result = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item != null) result.add(item.toString());
        }
        return result;
    }
}

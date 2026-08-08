package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.EnabledPlugin;
import com.gameplatform.plugin.service.InstanceFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 共享文件引用计数服务（纯内存实现）。
 *
 * <p>进程启动后首次访问时从 .enabled_plugins.yaml 重建。
 * 不再持久化到 .file_refs.json（旧版兼容方法已废弃）。
 *
 * <p>路径归一化：\ → /，去前导 ./，转小写，确保相同文件路径不会因大小写或斜杠差异被识别为不同文件。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileRefsService {

    private final InstanceFileService instanceFileService;
    private final L4D2PathResolver pathResolver;
    private final EnabledPluginsService enabledPluginsService;

    /** 实例 → (文件路径 → 引用该文件的插件名集合) */
    private final Map<Long, Map<String, Set<String>>> refsCache = new ConcurrentHashMap<>();

    /**
     * 路径标准化：\ → /，去除前导 ./，转小写。
     *
     * @param relPath 相对路径
     * @return 归一化后的路径
     */
    public String normalizeRelPath(String relPath) {
        if (relPath == null || relPath.isEmpty()) return "";
        String s = relPath.replace('\\', '/');
        // 去除前导 ./
        while (s.startsWith("./")) s = s.substring(2);
        // 去除段内 /./
        while (s.contains("/./")) s = s.replace("/./", "/");
        return s.toLowerCase();
    }

    /**
     * 加载引用映射（懒加载 + 缓存）。
     *
     * @param instanceId 实例ID
     * @return 文件路径 → 插件名集合
     */
    public Map<String, Set<String>> loadRefs(Long instanceId) {
        return refsCache.computeIfAbsent(instanceId, this::rebuildMap);
    }

    /**
     * 强制从 .enabled_plugins.yaml 重建引用映射。
     *
     * @param instanceId 实例ID
     */
    public void rebuild(Long instanceId) {
        refsCache.put(instanceId, rebuildMap(instanceId));
    }

    private Map<String, Set<String>> rebuildMap(Long instanceId) {
        Map<String, Set<String>> refs = new ConcurrentHashMap<>();
        try {
            List<EnabledPlugin> plugins = enabledPluginsService.loadYaml(instanceId);
            for (EnabledPlugin ep : plugins) {
                if (ep.getFiles() == null) continue;
                for (String file : ep.getFiles()) {
                    String norm = normalizeRelPath(file);
                    if (norm.isEmpty()) continue;
                    refs.computeIfAbsent(norm, k -> Collections.synchronizedSet(new TreeSet<>()))
                            .add(ep.getName());
                }
            }
        } catch (Exception e) {
            log.warn("重建 fileRefs 失败 instanceId={}, err={}", instanceId, e.getMessage());
        }
        return refs;
    }

    /**
     * 添加引用（仅内存操作，不写远程文件）。
     *
     * @param instanceId  实例ID
     * @param pluginName  插件名
     * @param sharedFiles 共享文件路径列表
     */
    public void addRefs(Long instanceId, String pluginName, List<String> sharedFiles) {
        if (sharedFiles == null || sharedFiles.isEmpty()) return;
        Map<String, Set<String>> refs = loadRefs(instanceId);
        for (String file : sharedFiles) {
            String norm = normalizeRelPath(file);
            if (norm.isEmpty()) continue;
            refs.computeIfAbsent(norm, k -> Collections.synchronizedSet(new TreeSet<>())).add(pluginName);
        }
    }

    /**
     * 移除引用，返回归零需删除的文件列表。
     *
     * @param instanceId 实例ID
     * @param pluginName 插件名
     * @return 归零（无插件引用）的共享文件路径列表
     */
    public List<String> removeRefs(Long instanceId, String pluginName) {
        Map<String, Set<String>> refs = loadRefs(instanceId);
        List<String> zeroed = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : refs.entrySet()) {
            Set<String> plugins = entry.getValue();
            if (plugins.remove(pluginName) && plugins.isEmpty()) {
                zeroed.add(entry.getKey());
            }
        }
        for (String path : zeroed) {
            refs.remove(path);
        }
        return zeroed;
    }
}

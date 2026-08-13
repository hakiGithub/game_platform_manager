package com.gameplatform.plugin.l4d2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.gameplatform.plugin.l4d2.config.BuiltinPluginsConfig;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.vo.BuiltinPluginVO;
import com.gameplatform.plugin.l4d2.vo.InstallResult;
import com.gameplatform.plugin.service.InstanceFileService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;

/**
 * 内置插件安装器：从 classpath:builtin-plugins.yaml 清单 + builtin-plugins/*.zip 资源
 * 安装任意内置插件到指定实例的 plugins_store 目录。
 *
 * <p>内置清单覆盖 l4d2-server-next-master/plugins 全部 62 个插件包，按分类组织：
 * <ul>
 *   <li>platform - SourceMod + Metamod 框架（Docker 用 linux 版，Native 用 windows 版）</li>
 *   <li>required - 必选插件（基础功能依赖）</li>
 *   <li>optional - 可选插件（修复类）</li>
 *   <li>custom   - 自选插件（玩法增强）</li>
 * </ul>
 *
 * <p>安装流程：从 classpath 读取 ZIP → 调用 {@link PluginInstallService#installFromLocalFile}
 * → 解压到 plugins_store/&lt;id&gt;/left4dead2/... → 用户在插件列表点"启用"或应用预设使其生效。
 *
 * <p>注意：PF4J 插件使用独立 ClassLoader，必须用本类的 ClassLoader 才能从插件 JAR 内读取资源。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuiltinPluginInstaller {

    /** 内置清单在 classpath 中的路径 */
    private static final String MANIFEST_PATH = "builtin-plugins.yaml";

    /** 内置 ZIP 在 classpath 中的目录前缀 */
    private static final String BUILTIN_PLUGINS_DIR = "builtin-plugins/";

    private final PluginInstallService pluginInstallService;
    private final InstanceFileService instanceFileService;

    /** 启动时加载的内置插件清单（不可变） */
    private volatile List<BuiltinPluginVO> manifest = Collections.emptyList();

    @PostConstruct
    public void loadManifest() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(MANIFEST_PATH)) {
            if (is == null) {
                log.warn("builtin-plugins.yaml not found in classpath, builtin plugins disabled");
                return;
            }
            BuiltinPluginsConfig config = mapper.readValue(is, BuiltinPluginsConfig.class);
            if (config.getPlugins() != null) {
                manifest = Collections.unmodifiableList(config.getPlugins());
            }
            log.info("Loaded {} builtin plugins from {}", manifest.size(), MANIFEST_PATH);
        } catch (Exception e) {
            log.error("Failed to load builtin-plugins.yaml, builtin plugins disabled", e);
            manifest = Collections.emptyList();
        }
    }

    /**
     * 列出所有内置插件（按清单顺序，platform → required → optional → custom）。
     *
     * <p>若提供 instanceId，会填充每个插件的 installed 字段（是否已安装到该实例的 plugins_store）。
     *
     * @param instanceId 实例 ID，传 null 则不查询安装状态（installed 字段为 null）
     * @return 内置插件列表（不可变）
     */
    public List<BuiltinPluginVO> list(Long instanceId) {
        if (instanceId == null) {
            return manifest;
        }
        // 一次远程目录列表 + 本地集合匹配：避免逐插件远程 exists（60+ 条目慢接口根因）
        java.util.Set<String> installedNames = pluginInstallService.listInstalledPluginNames(instanceId);
        return manifest.stream().map(vo -> {
            BuiltinPluginVO copy = new BuiltinPluginVO();
            copy.setId(vo.getId());
            copy.setName(vo.getName());
            copy.setCategory(vo.getCategory());
            copy.setFileName(vo.getFileName());
            copy.setSize(vo.getSize());
            copy.setPlatform(vo.getPlatform());
            copy.setDescription(vo.getDescription());
            copy.setInstalled(installedNames.contains(vo.getId()));
            return copy;
        }).toList();
    }

    /**
     * 根据 ID 查找内置插件清单条目；不存在返回 null。
     */
    public BuiltinPluginVO findById(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            return null;
        }
        return manifest.stream()
                .filter(vo -> pluginId.equals(vo.getId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 检查指定内置插件是否已安装到实例的 plugins_store。
     *
     * @param instanceId 实例 ID
     * @param pluginId   内置插件 ID
     * @return true 表示已安装；清单中不存在该 ID 或未安装均返回 false
     */
    public boolean isInstalled(Long instanceId, String pluginId) {
        if (findById(pluginId) == null) {
            return false;
        }
        return pluginInstallService.pluginExists(instanceId, pluginId);
    }

    /**
     * 安装指定内置插件到实例的 plugins_store 目录。
     *
     * <p>流程：
     * <ol>
     *   <li>从清单查找 pluginId，不存在抛异常</li>
     *   <li>检查是否已安装，已安装直接返回（幂等）</li>
     *   <li>从 classpath 读取 ZIP 到本地临时文件</li>
     *   <li>调用 {@link PluginInstallService#installFromLocalFile} 解压并上传到
     *       plugins_store/&lt;id&gt;/left4dead2/...</li>
     *   <li>清理本地临时文件</li>
     * </ol>
     *
     * @param instanceId 实例 ID
     * @param pluginId   内置插件 ID
     * @return 安装结果描述
     */
    public String install(Long instanceId, String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "pluginId 不能为空");
        }
        BuiltinPluginVO plugin = findById(pluginId);
        if (plugin == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "内置插件不存在: " + pluginId);
        }

        if (pluginInstallService.pluginExists(instanceId, pluginId)) {
            log.info("内置插件已安装，跳过: instanceId={}, plugin={}", instanceId, pluginId);
            return "插件已安装，无需重复安装: " + plugin.getName();
        }

        Path tempZip = null;
        try {
            tempZip = extractClasspathZipToTemp(plugin.getFileName());
            long sizeMb = tempZip.toFile().length() / 1024 / 1024;
            log.info("开始安装内置插件: instanceId={}, plugin={}, zipSize={}MB",
                    instanceId, pluginId, sizeMb);
            pluginInstallService.installFromLocalFile(instanceId, tempZip.toFile());
            log.info("内置插件安装完成: instanceId={}, plugin={}", instanceId, pluginId);
            return "插件安装成功: " + plugin.getName();
        } catch (L4D2PluginException e) {
            throw e;
        } catch (Exception e) {
            log.error("内置插件安装失败 instanceId={}, plugin={}", instanceId, pluginId, e);
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "插件安装失败: " + e.getMessage(), e);
        } finally {
            cleanupTempQuietly(tempZip);
        }
    }

    /**
     * 批量安装多个内置插件（按清单顺序执行，单个失败不影响其他）。
     *
     * @param instanceId 实例 ID
     * @param pluginIds  内置插件 ID 列表
     * @return 每个插件的安装结果（status=SUCCESS/FAILED）
     */
    public List<InstallResult> installBatch(Long instanceId, List<String> pluginIds) {
        if (pluginIds == null || pluginIds.isEmpty()) {
            return Collections.emptyList();
        }
        return pluginIds.stream()
                .map(id -> {
                    InstallResult result = new InstallResult();
                    result.setPluginId(id);
                    BuiltinPluginVO vo = findById(id);
                    if (vo == null) {
                        result.setPluginName(id);
                        result.setStatus("FAILED");
                        result.setMessage("内置插件不存在: " + id);
                        return result;
                    }
                    result.setPluginName(vo.getName());
                    try {
                        String msg = install(instanceId, id);
                        result.setStatus("SUCCESS");
                        result.setMessage(msg);
                    } catch (Exception e) {
                        result.setStatus("FAILED");
                        result.setMessage(e.getMessage());
                    }
                    return result;
                })
                .toList();
    }

    /**
     * 从 classpath 读取内置 ZIP 到本地临时文件。
     *
     * <p>PF4J 插件使用独立 ClassLoader，必须用本类的 ClassLoader 才能从插件 JAR 内读取资源。
     */
    private Path extractClasspathZipToTemp(String fileName) {
        String resourcePath = BUILTIN_PLUGINS_DIR + fileName;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new L4D2PluginException(L4D2PluginException.FILE,
                        "内置插件 ZIP 不存在: " + resourcePath);
            }
            Path temp = Files.createTempFile("builtin-plugin-", ".zip");
            Files.copy(is, temp, StandardCopyOption.REPLACE_EXISTING);
            return temp;
        } catch (L4D2PluginException e) {
            throw e;
        } catch (Exception e) {
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "读取内置插件 ZIP 失败: " + resourcePath + " - " + e.getMessage(), e);
        }
    }

    private void cleanupTempQuietly(Path temp) {
        if (temp == null) return;
        try {
            Files.deleteIfExists(temp);
        } catch (Exception e) {
            log.warn("清理本地临时 ZIP 失败: {}", temp, e);
        }
    }

    /**
     * 暴露 InstanceFileService 给上层（兼容旧 PlatformPluginInstaller 用法）。
     */
    public InstanceFileService getInstanceFileService() {
        return instanceFileService;
    }
}

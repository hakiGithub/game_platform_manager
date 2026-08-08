package com.gameplatform.plugin.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.plugin.context.PluginSpringContextFactory;
import com.gameplatform.plugin.extension.GameEnhancementExtension;
import com.gameplatform.plugin.extension.PluginMenuDeclaration;
import com.gameplatform.plugin.service.PluginFrameworkService;
import com.gameplatform.plugin.util.PluginUtils;
import com.gameplatform.plugin.vo.PluginManifestVO;
import com.gameplatform.plugin.vo.PluginStatusVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.pf4j.PluginManager;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件框架服务实现类
 *
 * @author GamePlatform
 * @version 2.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginFrameworkServiceImpl implements PluginFrameworkService {

    private final PluginManager pluginManager;
    private final ObjectMapper objectMapper;
    private final PluginSpringContextFactory springContextFactory;

    /** 清单缓存（线程安全） */
    private final Map<String, PluginManifestVO> manifestCache = new ConcurrentHashMap<>();

    @Override
    public List<PluginWrapper> getAllPlugins() {
        return pluginManager.getPlugins();
    }

    @Override
    public Optional<PluginWrapper> getPlugin(String pluginId) {
        return Optional.ofNullable(pluginManager.getPlugin(pluginId));
    }

    @Override
    public PluginStatusVO getPluginStatus(String pluginId) {
        PluginWrapper plugin = pluginManager.getPlugin(pluginId);
        if (plugin == null) {
            return null;
        }
        return buildPluginStatusVO(plugin);
    }

    @Override
    public List<PluginStatusVO> getAllPluginStatus() {
        return pluginManager.getPlugins().stream()
                .map(this::buildPluginStatusVO)
                .toList();
    }

    @Override
    public PluginManifestVO getManifestByGameCode(String gameCode) {
        // 使用 PluginUtils 查找 pluginId
        String pluginId = PluginUtils.findPluginIdByGameCode(gameCode, pluginManager);
        if (pluginId != null) {
            return getManifestByPluginId(pluginId);
        }
        return null;
    }

    @Override
    public PluginManifestVO getManifestByPluginId(String pluginId) {
        // 检查缓存
        PluginManifestVO cached = manifestCache.get(pluginId);
        if (cached != null) {
            return cached;
        }

        PluginWrapper plugin = pluginManager.getPlugin(pluginId);
        if (plugin == null) {
            return null;
        }

        // ADR-0001: 仅从扩展点 getManifest() + getMenus() 构建，不再读 JAR 内 manifest.json
        PluginManifestVO manifest = buildManifestFromExtension(pluginId);

        // 缓存结果
        if (manifest != null) {
            manifestCache.put(pluginId, manifest);
        }

        return manifest;
    }

    @Override
    public boolean startPlugin(String pluginId) {
        try {
            PluginState state = pluginManager.startPlugin(pluginId);
            boolean success = state == PluginState.STARTED;
            if (success) {
                log.info("插件 {} 启动成功", pluginId);
            } else {
                log.warn("插件 {} 启动失败，当前状态: {}", pluginId, state);
            }
            return success;
        } catch (Exception e) {
            log.error("启动插件 {} 失败", pluginId, e);
            return false;
        }
    }

    @Override
    public boolean stopPlugin(String pluginId) {
        try {
            PluginState state = pluginManager.stopPlugin(pluginId);
            boolean success = state == PluginState.STOPPED;
            if (success) {
                log.info("插件 {} 停止成功", pluginId);
                manifestCache.remove(pluginId);
            } else {
                log.warn("插件 {} 停止失败，当前状态: {}", pluginId, state);
            }
            return success;
        } catch (Exception e) {
            log.error("停止插件 {} 失败", pluginId, e);
            return false;
        }
    }

    @Override
    public boolean unloadPlugin(String pluginId) {
        try {
            // 获取扩展点（卸载前调用 onUnload 钩子）
            GameEnhancementExtension extension = getExtensionByPluginId(pluginId);

            // 先清理 Spring 子容器
            springContextFactory.unloadPluginContext(pluginId, extension);

            boolean success = pluginManager.unloadPlugin(pluginId);
            if (success) {
                log.info("插件 {} 卸载成功", pluginId);
                manifestCache.remove(pluginId);
                PluginUtils.invalidateCache(pluginId);
            } else {
                log.warn("插件 {} 卸载失败", pluginId);
            }
            return success;
        } catch (Exception e) {
            log.error("卸载插件 {} 失败", pluginId, e);
            return false;
        }
    }

    @Override
    public boolean reloadPlugin(String pluginId) {
        try {
            PluginWrapper plugin = pluginManager.getPlugin(pluginId);
            if (plugin == null) return false;
            Path pluginPath = plugin.getPluginPath();

            // 停止并卸载
            stopPlugin(pluginId);
            unloadPlugin(pluginId);

            // 重新加载
            String newPluginId = loadPlugin(pluginPath.toString());
            return newPluginId != null && startPlugin(newPluginId);
        } catch (Exception e) {
            log.error("重新加载插件 {} 失败", pluginId, e);
            return false;
        }
    }

    @Override
    public String loadPlugin(String pluginPath) {
        try {
            Path path = Path.of(pluginPath);
            String pluginId = pluginManager.loadPlugin(path);
            log.info("PF4J 加载插件成功: {} -> {}", pluginPath, pluginId);

            PluginWrapper wrapper = pluginManager.getPlugin(pluginId);
            if (wrapper != null) {
                List<GameEnhancementExtension> extensions =
                        pluginManager.getExtensions(GameEnhancementExtension.class, pluginId);

                if (!extensions.isEmpty()) {
                    GameEnhancementExtension ext = extensions.get(0);
                    Properties props = PluginUtils.loadPluginProperties(wrapper);

                    try {
                        springContextFactory.loadPluginSpringContext(wrapper, ext, props);
                    } catch (Exception e) {
                        log.error("插件 [{}] Spring 上下文创建失败，继续加载", pluginId, e);
                        // 调用扩展点的错误处理钩子
                        ext.onLoadError(null, e);
                    }
                } else {
                    log.info("插件 [{}] 没有 GameEnhancementExtension 扩展点，跳过 Spring 集成", pluginId);
                }
            }

            return pluginId;
        } catch (Exception e) {
            log.error("加载插件失败: {}", pluginPath, e);
            return null;
        }
    }

    @Override
    public byte[] getPluginResource(String pluginId, String resourcePath) {
        try {
            PluginWrapper plugin = pluginManager.getPlugin(pluginId);
            if (plugin == null) {
                log.warn("插件不存在: {}", pluginId);
                return null;
            }

            String fullPath = "ui/" + resourcePath;
            InputStream inputStream = plugin.getPluginClassLoader().getResourceAsStream(fullPath);
            if (inputStream == null) {
                log.warn("插件资源不存在: {} -> {}", pluginId, fullPath);
                return null;
            }

            return inputStream.readAllBytes();
        } catch (IOException e) {
            log.error("读取插件资源失败: {} -> {}", pluginId, resourcePath, e);
            return null;
        }
    }

    @Override
    public String getContentType(String resourcePath) {
        String extension = getFileExtension(resourcePath);
        return switch (extension.toLowerCase()) {
            case "html", "htm" -> "text/html; charset=UTF-8";
            case "css" -> "text/css; charset=UTF-8";
            case "js" -> "application/javascript; charset=UTF-8";
            case "json" -> "application/json; charset=UTF-8";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "svg" -> "image/svg+xml";
            case "ico" -> "image/x-icon";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            case "ttf" -> "font/ttf";
            case "eot" -> "application/vnd.ms-fontobject";
            case "xml" -> "application/xml; charset=UTF-8";
            case "txt" -> "text/plain; charset=UTF-8";
            default -> "application/octet-stream";
        };
    }

    @Override
    public boolean pluginExists(String pluginId) {
        return pluginManager.getPlugin(pluginId) != null;
    }

    @Override
    public Optional<String> getPluginIdByGameCode(String gameCode) {
        return Optional.ofNullable(PluginUtils.findPluginIdByGameCode(gameCode, pluginManager));
    }

    // ==================== 私有方法 ====================

    /**
     * 根据插件ID获取扩展点实例
     */
    private GameEnhancementExtension getExtensionByPluginId(String pluginId) {
        try {
            return pluginManager.getExtensions(GameEnhancementExtension.class, pluginId)
                    .stream()
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private PluginStatusVO buildPluginStatusVO(PluginWrapper plugin) {
        PluginState state = plugin.getPluginState();
        // MANIFEST.MF 由 maven-jar-plugin 用 UTF-8 写入，但 Java Manifest 类按 ISO-8859-1 读取，
        // 导致中文 Plugin-Description 双重编码。优先使用扩展点（Java 源码已用 UTF-8 编译）的值。
        String description = plugin.getDescriptor().getPluginDescription();
        String provider = plugin.getDescriptor().getProvider();
        String pluginName = description;
        try {
            PluginManifestVO manifest = buildManifestFromExtension(plugin.getPluginId());
            if (manifest != null) {
                if (manifest.getGameName() != null) {
                    pluginName = manifest.getGameName();
                }
                if (manifest.getDescription() != null) {
                    description = manifest.getDescription();
                }
            }
        } catch (Exception ignore) {
            // 回退到 descriptor 值
        }
        return PluginStatusVO.builder()
                .pluginId(plugin.getPluginId())
                .pluginName(pluginName)
                .version(plugin.getDescriptor().getVersion().toString())
                .state(state.name())
                .enabled(state != PluginState.DISABLED)
                .running(state == PluginState.STARTED)
                .provider(provider)
                .description(description)
                .dependencies(plugin.getDescriptor().getDependencies().toString())
                .pluginPath(plugin.getPluginPath().toString())
                .build();
    }

    private PluginManifestVO buildManifestFromExtension(String pluginId) {
        List<GameEnhancementExtension> extensions = pluginManager.getExtensions(GameEnhancementExtension.class);

        for (GameEnhancementExtension extension : extensions) {
            String extPluginId = PluginUtils.findPluginIdByExtension(extension, pluginManager);
            if (pluginId.equals(extPluginId)) {
                String gameCode = extension.getGameCode();

                // ADR-0001: 菜单清单由插件 getMenus() 提供，主应用不再硬编码任何插件菜单
                List<PluginMenuDeclaration> declarations = extension.getMenus();
                List<PluginManifestVO.MenuConfig> menus = buildMenusFromDeclarations(pluginId, declarations);
                List<String> capabilities = menus.stream()
                        .map(PluginManifestVO.MenuConfig::getPath)
                        .toList();

                // 将 capabilities 注入 manifest Map（替代已删除的 features 字段）
                // 防御性拷贝：扩展点返回的 Map 可能是不可变的（如 Map.of），禁止原地把写
                Map<String, Object> extensionManifest = extension.getManifest();
                Map<String, Object> manifestWithCapabilities = new HashMap<>();
                if (extensionManifest != null) {
                    manifestWithCapabilities.putAll(extensionManifest);
                }
                manifestWithCapabilities.put("capabilities", capabilities);

                return PluginManifestVO.builder()
                        .pluginId(pluginId)
                        .gameCode(gameCode)
                        .gameName(extension.getGameName())
                        .version(extension.getVersion())
                        .description(extension.getDescription())
                        .icon("/plugin/" + gameCode + "/ui/" + extension.getIcon())
                        .frontendEntry(PluginUtils.buildFrontendEntryUrl(gameCode, extension.getFrontendEntry()))
                        .frontend(PluginManifestVO.FrontendConfig.builder()
                                .entry(PluginUtils.buildFrontendEntryUrl(gameCode, extension.getFrontendEntry()))
                                .menus(menus)
                                .build())
                        .api(PluginManifestVO.ApiConfig.builder()
                                .basePath(PluginUtils.buildApiBasePath(gameCode))
                                .build())
                        .extensions(manifestWithCapabilities)
                        .build();
            }
        }
        return null;
    }

    /**
     * 将插件 PluginMenuDeclaration 列表转换为 VO，并执行 path 唯一性校验（ADR-0001）。
     * <p>
     * 同插件内 path 重复时抛 IllegalStateException，manifest 不缓存。
     * requireInstance 为 null 时填补为 Boolean.TRUE（与 PluginMenuDeclaration 默认值一致）。
     *
     * @param pluginId     插件ID（仅用于错误信息）
     * @param declarations 插件声明的菜单列表
     * @return 转换后的 MenuConfig VO 列表
     * @throws IllegalStateException 同插件内 path 重复
     */
    private List<PluginManifestVO.MenuConfig> buildMenusFromDeclarations(
            String pluginId, List<PluginMenuDeclaration> declarations) {
        if (declarations == null || declarations.isEmpty()) {
            return List.of();
        }
        Set<String> seenPaths = new HashSet<>();
        List<PluginManifestVO.MenuConfig> menus = new ArrayList<>(declarations.size());
        for (PluginMenuDeclaration d : declarations) {
            String path = d.getPath();
            if (path == null || path.isBlank()) {
                throw new IllegalStateException(
                        "插件 " + pluginId + " 菜单 path 为空: " + d.getTitle());
            }
            if (!seenPaths.add(path)) {
                throw new IllegalStateException(
                        "插件 " + pluginId + " 菜单 path 重复: " + path);
            }
            Boolean requireInstance = d.getRequireInstance();
            menus.add(PluginManifestVO.MenuConfig.builder()
                    .title(d.getTitle())
                    .path(path)
                    .icon(d.getIcon())
                    .order(d.getOrder())
                    .parent(d.getParent())
                    .requireInstance(requireInstance == null ? Boolean.TRUE : requireInstance)
                    .build());
        }
        return menus;
    }

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0 && lastDot < filename.length() - 1) {
            return filename.substring(lastDot + 1);
        }
        return "";
    }
}

package com.gameplatform.plugin.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.config.PluginConfig;
import com.gameplatform.plugin.service.PluginFrameworkService;
import com.gameplatform.plugin.vo.PluginManifestVO;
import com.gameplatform.plugin.vo.PluginStatusVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 插件框架控制器
 * 提供插件管理和资源访问的REST API
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Tag(name = "插件框架", description = "插件框架相关接口")
@RestController
@RequestMapping("/pf4j")
@RequiredArgsConstructor
public class PluginFrameworkController {

    private final PluginFrameworkService pluginFrameworkService;
    private final PluginConfig pluginConfig;

    // ==================== 插件管理API ====================

    /**
     * 获取所有插件列表
     */
    @Operation(summary = "获取所有插件列表", description = "获取所有已加载插件的列表")
    @GetMapping("/plugins")
    public Result<List<PluginStatusVO>> listPlugins() {
        List<PluginStatusVO> plugins = pluginFrameworkService.getAllPluginStatus();
        return Result.success(plugins);
    }

    /**
     * 获取插件状态
     */
    @Operation(summary = "获取插件状态", description = "根据插件ID获取插件状态")
    @GetMapping("/plugins/{pluginId}/status")
    public Result<PluginStatusVO> getPluginStatus(
            @Parameter(description = "插件ID") @PathVariable String pluginId) {
        PluginStatusVO status = pluginFrameworkService.getPluginStatus(pluginId);
        if (status == null) {
            return Result.fail("插件不存在");
        }
        return Result.success(status);
    }

    /**
     * 获取插件清单（通过游戏编码）
     */
    @Operation(summary = "获取插件清单", description = "根据游戏编码获取插件清单信息")
    @GetMapping("/plugin/{gameCode}/manifest")
    public Result<PluginManifestVO> getManifest(
            @Parameter(description = "游戏编码") @PathVariable String gameCode) {
        PluginManifestVO manifest = pluginFrameworkService.getManifestByGameCode(gameCode);
        if (manifest == null) {
            return Result.fail("未找到游戏对应的插件: " + gameCode);
        }
        return Result.success(manifest);
    }

    /**
     * 获取插件清单（通过插件ID）
     */
    @Operation(summary = "获取插件清单(通过插件ID)", description = "根据插件ID获取插件清单信息")
    @GetMapping("/plugins/{pluginId}/manifest")
    public Result<PluginManifestVO> getManifestByPluginId(
            @Parameter(description = "插件ID") @PathVariable String pluginId) {
        PluginManifestVO manifest = pluginFrameworkService.getManifestByPluginId(pluginId);
        if (manifest == null) {
            return Result.fail("插件不存在: " + pluginId);
        }
        return Result.success(manifest);
    }

    /**
     * 启动插件
     */
    @Operation(summary = "启动插件", description = "启动指定的插件")
    @PostMapping("/plugins/{pluginId}/start")
    public Result<Void> startPlugin(
            @Parameter(description = "插件ID") @PathVariable String pluginId) {
        boolean success = pluginFrameworkService.startPlugin(pluginId);
        return success ? Result.success() : Result.fail("启动插件失败");
    }

    /**
     * 停止插件
     */
    @Operation(summary = "停止插件", description = "停止指定的插件")
    @PostMapping("/plugins/{pluginId}/stop")
    public Result<Void> stopPlugin(
            @Parameter(description = "插件ID") @PathVariable String pluginId) {
        boolean success = pluginFrameworkService.stopPlugin(pluginId);
        return success ? Result.success() : Result.fail("停止插件失败");
    }

    /**
     * 重新加载插件
     */
    @Operation(summary = "重新加载插件", description = "重新加载指定的插件")
    @PostMapping("/plugins/{pluginId}/reload")
    public Result<Void> reloadPlugin(
            @Parameter(description = "插件ID") @PathVariable String pluginId) {
        boolean success = pluginFrameworkService.reloadPlugin(pluginId);
        return success ? Result.success() : Result.fail("重新加载插件失败");
    }

    /**
     * 卸载插件
     */
    @Operation(summary = "卸载插件", description = "卸载指定的插件；purgeTasks=false 保留任务历史（热部署用）")
    @DeleteMapping("/plugins/{pluginId}")
    public Result<Void> unloadPlugin(
            @Parameter(description = "插件ID") @PathVariable String pluginId,
            @Parameter(description = "是否物理删除任务记录（默认 true）") @RequestParam(defaultValue = "true") boolean purgeTasks) {
        boolean success = pluginFrameworkService.unloadPlugin(pluginId, purgeTasks);
        return success ? Result.success() : Result.fail("卸载插件失败");
    }

    /**
     * 从插件目录加载插件
     *
     * <p>配合 unload 使用实现免重启热部署：unload 释放 jar 文件锁后
     * 覆盖 jar，再调用本接口加载并启动。jarName 只允许插件目录内的
     * 文件名（拒绝路径穿越）。
     */
    @Operation(summary = "加载插件", description = "从插件目录加载并启动指定 jar（配合卸载实现热部署）")
    @PostMapping("/plugins/load")
    public Result<String> loadPlugin(
            @Parameter(description = "插件目录内的 jar 文件名") @RequestParam String jarName) {
        Path pluginsPath = Path.of(pluginConfig.getPluginsDir()).toAbsolutePath().normalize();
        Path jarPath = pluginsPath.resolve(jarName).normalize();
        // 只允许加载插件目录内的文件，防止任意路径类加载
        if (!jarPath.startsWith(pluginsPath)) {
            return Result.fail("jarName 必须是插件目录内的文件名");
        }
        File jarFile = jarPath.toFile();
        if (!jarFile.isFile()) {
            return Result.fail("插件文件不存在: " + jarName);
        }
        String pluginId = pluginFrameworkService.loadPlugin(jarPath.toString());
        if (pluginId == null) {
            return Result.fail("加载插件失败");
        }
        boolean started = pluginFrameworkService.startPlugin(pluginId);
        return started ? Result.success(pluginId) : Result.fail("插件已加载但启动失败: " + pluginId);
    }

    // ==================== 插件静态资源服务 ====================

    /**
     * 获取插件静态资源
     * 支持缓存7天
     * 使用 /** 匹配多级路径（如 js/views/dashboard.js）
     */
    @Operation(summary = "获取插件静态资源", description = "获取插件JAR包内的静态资源文件")
    @GetMapping("/plugin/{gameCode}/ui/**")
    public ResponseEntity<byte[]> getPluginResource(
            @Parameter(description = "游戏编码") @PathVariable String gameCode,
            HttpServletRequest request) {
        
        // 提取完整的资源路径
        String path = request.getRequestURI();
        String prefix = "/plugin/" + gameCode + "/ui/";
        String resourcePath = path.substring(path.indexOf(prefix) + prefix.length());
        
        // 根据游戏编码获取插件ID
        var pluginIdOpt = pluginFrameworkService.getPluginIdByGameCode(gameCode);
        if (pluginIdOpt.isEmpty()) {
            log.warn("未找到游戏对应的插件: {}", gameCode);
            return ResponseEntity.notFound().build();
        }

        String pluginId = pluginIdOpt.get();
        byte[] content = pluginFrameworkService.getPluginResource(pluginId, resourcePath);
        
        if (content == null) {
            log.warn("插件资源不存在: {} -> {}", pluginId, resourcePath);
            return ResponseEntity.notFound().build();
        }

        String contentType = pluginFrameworkService.getContentType(resourcePath);

        // index.html 入口禁用缓存，确保部署后浏览器总能拿到最新的资源引用；
        // JS/CSS 等资源文件名已带 hash，可长期缓存。
        boolean isEntryHtml = "index.html".equals(resourcePath) || resourcePath.endsWith("/index.html");
        CacheControl cacheControl = isEntryHtml
                ? CacheControl.noStore()
                : CacheControl.maxAge(7, TimeUnit.DAYS);
        String cacheHeader = isEntryHtml
                ? "no-store, no-cache, must-revalidate, proxy-revalidate"
                : "public, max-age=604800";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .cacheControl(cacheControl)
                .header(HttpHeaders.CACHE_CONTROL, cacheHeader)
                .body(content);
    }

    /**
     * 获取插件图标
     */
    @Operation(summary = "获取插件图标", description = "获取插件的图标文件")
    @GetMapping("/plugin/{gameCode}/icon")
    public ResponseEntity<byte[]> getPluginIcon(
            @Parameter(description = "游戏编码") @PathVariable String gameCode) {
        
        // 获取清单以获取图标路径
        PluginManifestVO manifest = pluginFrameworkService.getManifestByGameCode(gameCode);
        if (manifest == null || manifest.getIcon() == null) {
            return ResponseEntity.notFound().build();
        }

        // 从图标URL提取资源路径
        String iconUrl = manifest.getIcon();
        String resourcePath = extractResourcePath(iconUrl, gameCode);
        
        // 根据游戏编码获取插件ID
        var pluginIdOpt = pluginFrameworkService.getPluginIdByGameCode(gameCode);
        if (pluginIdOpt.isEmpty()) {
            log.warn("未找到游戏对应的插件: {}", gameCode);
            return ResponseEntity.notFound().build();
        }

        String pluginId = pluginIdOpt.get();
        byte[] content = pluginFrameworkService.getPluginResource(pluginId, resourcePath);
        
        if (content == null) {
            log.warn("插件图标不存在: {} -> {}", pluginId, resourcePath);
            return ResponseEntity.notFound().build();
        }

        String contentType = pluginFrameworkService.getContentType(resourcePath);
        
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS))
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=604800")
                .body(content);
    }

    /**
     * 从图标URL提取资源路径
     */
    private String extractResourcePath(String url, String gameCode) {
        // URL格式: /plugin/{gameCode}/ui/{resourcePath}
        String prefix = "/plugin/" + gameCode + "/ui/";
        if (url.startsWith(prefix)) {
            return url.substring(prefix.length());
        }
        return "assets/icon.png"; // 默认图标路径
    }

}

package com.gameplatform.plugin.controller;

import com.gameplatform.plugin.service.PluginFrameworkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * 插件静态资源控制器
 * 为 Wujie 微前端提供插件子应用入口及静态资源访问服务
 *
 * <p>访问路径: /api/plugins/{gameCode}/ui/**</p>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Tag(name = "插件静态资源", description = "为 Wujie 微前端提供插件子应用静态资源")
@RestController
@RequestMapping("/plugins/{gameCode}/ui")
@RequiredArgsConstructor
public class PluginResourceController {

    private final PluginFrameworkService pluginFrameworkService;

    /**
     * 获取插件静态资源
     * 支持从插件 JAR 包的 ui 目录读取 index.html、JS、CSS、图片等资源
     *
     * @param gameCode 游戏编码
     * @param request  HTTP 请求
     * @return 资源字节流
     */
    @Operation(summary = "获取插件静态资源", description = "从插件 JAR 包的 ui 目录读取静态资源，支持 Wujie 加载")
    @GetMapping("/**")
    public ResponseEntity<byte[]> getPluginResource(
            @Parameter(description = "游戏编码") @PathVariable String gameCode,
            HttpServletRequest request) {

        // 1. 从请求 URI 中提取资源路径
        String resourcePath = extractResourcePath(request, gameCode);
        if (resourcePath == null) {
            log.warn("非法的资源请求路径: gameCode={}, uri={}", gameCode, request.getRequestURI());
            return ResponseEntity.badRequest().build();
        }
        // 默认返回入口文件
        if (resourcePath.isEmpty() || "/".equals(resourcePath)) {
            resourcePath = "index.html";
        }

        // 2. 根据游戏编码查找插件 ID
        var pluginIdOpt = pluginFrameworkService.getPluginIdByGameCode(gameCode);
        if (pluginIdOpt.isEmpty()) {
            log.warn("未找到游戏对应的插件: {}", gameCode);
            return ResponseEntity.notFound().build();
        }

        // 3. 读取插件资源
        String pluginId = pluginIdOpt.get();
        byte[] content = pluginFrameworkService.getPluginResource(pluginId, resourcePath);
        if (content == null) {
            log.warn("插件资源不存在: pluginId={}, resourcePath={}", pluginId, resourcePath);
            return ResponseEntity.notFound().build();
        }

        // 4. 设置响应头: Content-Type、缓存、CORS
        String contentType = pluginFrameworkService.getContentType(resourcePath);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS))
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=604800")
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, getAllowedOrigin(request))
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET, HEAD, OPTIONS")
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "*")
                .body(content);
    }

    /**
     * 从请求 URI 中提取相对于 ui 目录的资源路径
     *
     * <p>兼容带 context-path (/api/plugins/...) 与不带 context-path (/plugins/...) 两种情况</p>
     *
     * @param request  HTTP 请求
     * @param gameCode 游戏编码
     * @return 资源路径，若路径包含 .. 等非法字符则返回 null
     */
    private String extractResourcePath(HttpServletRequest request, String gameCode) {
        String uri = request.getRequestURI();
        String marker = "/plugins/" + gameCode + "/ui/";
        int idx = uri.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        String path = uri.substring(idx + marker.length());
        // 防止路径遍历攻击
        if (path.contains("..") || path.contains(":") || path.contains("//")) {
            return null;
        }
        return path;
    }

    /**
     * 获取允许的请求来源
     * 优先回显请求中的 Origin 头，不存在则使用通配符
     *
     * @param request HTTP 请求
     * @return 允许的来源
     */
    private String getAllowedOrigin(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !origin.isEmpty()) {
            return origin;
        }
        return "*";
    }

}

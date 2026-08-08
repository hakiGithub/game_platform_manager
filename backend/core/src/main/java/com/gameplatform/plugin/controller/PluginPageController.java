package com.gameplatform.plugin.controller;

import com.gameplatform.plugin.service.PluginFrameworkService;
import com.gameplatform.plugin.vo.PluginManifestVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 插件页面控制器
 * 处理插件页面的 Thymeleaf 渲染
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Tag(name = "插件页面", description = "插件页面渲染接口")
@Controller
@RequestMapping("/plugin/{gameCode}")
@RequiredArgsConstructor
public class PluginPageController {

    private final PluginFrameworkService pluginFrameworkService;

    /**
     * 渲染插件主页面
     * 
     * @param gameCode 游戏编码
     * @param instanceId 实例ID（可选）
     * @param token 认证令牌（可选）
     * @param model Spring MVC Model
     * @param request HTTP请求
     * @return Thymeleaf 模板路径
     */
    @Operation(summary = "渲染插件主页面", description = "渲染插件的 Thymeleaf 主页面")
    @GetMapping("/ui")
    public String pluginPage(
            @Parameter(description = "游戏编码") @PathVariable String gameCode,
            @Parameter(description = "实例ID") @RequestParam(required = false) Long instanceId,
            @Parameter(description = "认证令牌") @RequestParam(required = false) String token,
            Model model,
            HttpServletRequest request) {
        
        log.info("渲染插件页面: gameCode={}, instanceId={}", gameCode, instanceId);
        
        // 1. 获取插件清单
        PluginManifestVO manifest = pluginFrameworkService.getManifestByGameCode(gameCode);
        if (manifest == null) {
            log.error("插件不存在: {}", gameCode);
            throw new RuntimeException("插件不存在: " + gameCode);
        }
        
        // 2. 注入数据到模板
        model.addAttribute("pluginId", manifest.getPluginId());
        model.addAttribute("gameCode", gameCode);
        model.addAttribute("gameName", manifest.getGameName());
        model.addAttribute("instanceId", instanceId);
        model.addAttribute("token", token);
        model.addAttribute("apiBase", "/api/plugin/" + gameCode);
        
        // 3. 从请求头获取用户信息（如果有）
        String authorization = request.getHeader("Authorization");
        if (authorization != null) {
            model.addAttribute("authorization", authorization);
        }
        
        // 4. 添加清单信息
        model.addAttribute("manifest", manifest);
        
        // 5. 返回 Thymeleaf 模板路径
        // 模板路径格式: plugin/{gameCode}/index
        // Thymeleaf 会从插件的 ClassLoader 中加载 ui/{gameCode}/index.html
        return "plugin/" + gameCode + "/index";
    }
    
    /**
     * 渲染插件子页面
     * 支持多级路径，如 /plugin/l4d2/ui/views/dashboard
     * 
     * @param gameCode 游戏编码
     * @param subPath 子页面路径
     * @param instanceId 实例ID（可选）
     * @param token 认证令牌（可选）
     * @param model Spring MVC Model
     * @param request HTTP请求
     * @return Thymeleaf 模板路径
     */
    @Operation(summary = "渲染插件子页面", description = "渲染插件的 Thymeleaf 子页面")
    @GetMapping("/ui/views/**")
    public String pluginSubPage(
            @Parameter(description = "游戏编码") @PathVariable String gameCode,
            @Parameter(description = "实例ID") @RequestParam(required = false) Long instanceId,
            @Parameter(description = "认证令牌") @RequestParam(required = false) String token,
            Model model,
            HttpServletRequest request) {
        
        // 提取子页面路径
        String path = request.getRequestURI();
        String prefix = "/plugin/" + gameCode + "/ui/views/";
        String subPath = path.substring(path.indexOf(prefix) + prefix.length());
        
        log.info("渲染插件子页面: gameCode={}, subPath={}, instanceId={}", gameCode, subPath, instanceId);
        
        // 1. 获取插件清单
        PluginManifestVO manifest = pluginFrameworkService.getManifestByGameCode(gameCode);
        if (manifest == null) {
            log.error("插件不存在: {}", gameCode);
            throw new RuntimeException("插件不存在: " + gameCode);
        }
        
        // 2. 注入数据到模板
        model.addAttribute("pluginId", manifest.getPluginId());
        model.addAttribute("gameCode", gameCode);
        model.addAttribute("gameName", manifest.getGameName());
        model.addAttribute("instanceId", instanceId);
        model.addAttribute("token", token);
        model.addAttribute("apiBase", "/api/plugin/" + gameCode);
        
        // 3. 从请求头获取用户信息（如果有）
        String authorization = request.getHeader("Authorization");
        if (authorization != null) {
            model.addAttribute("authorization", authorization);
        }
        
        // 4. 添加清单信息
        model.addAttribute("manifest", manifest);
        
        // 5. 返回 Thymeleaf 模板路径
        // 模板路径格式: plugin/{gameCode}/views/{subPath}
        return "plugin/" + gameCode + "/views/" + subPath;
    }
}

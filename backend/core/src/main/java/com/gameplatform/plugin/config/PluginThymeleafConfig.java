package com.gameplatform.plugin.config;

import com.gameplatform.plugin.service.PluginFrameworkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.PluginManager;
import org.pf4j.PluginWrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.IEngineConfiguration;
import org.thymeleaf.templateresolver.AbstractConfigurableTemplateResolver;
import org.thymeleaf.templateresolver.ITemplateResolver;
import org.thymeleaf.templateresource.ITemplateResource;
import org.thymeleaf.templateresource.StringTemplateResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 插件 Thymeleaf 配置类
 * 配置 Thymeleaf 从插件 JAR 包中加载模板
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class PluginThymeleafConfig {

    private final PluginManager pluginManager;
    private final PluginFrameworkService pluginFrameworkService;

    /**
     * 创建插件模板解析器
     * 支持从多个插件的 ClassLoader 中加载模板
     *
     * @return 插件模板解析器
     */
    @Bean
    public ITemplateResolver pluginTemplateResolver() {
        log.info("初始化插件模板解析器");
        
        PluginClassLoaderTemplateResolver resolver = new PluginClassLoaderTemplateResolver(
                pluginManager, 
                pluginFrameworkService
        );
        
        // 设置模板前缀和后缀
        resolver.setPrefix("ui/");
        resolver.setSuffix(".html");
        
        // 设置模板模式
        resolver.setTemplateMode("HTML");
        
        // 设置字符编码
        resolver.setCharacterEncoding("UTF-8");
        
        // 设置优先级，高于默认解析器
        resolver.setOrder(1);
        
        // 检查模板是否存在
        resolver.setCheckExistence(true);
        
        // 启用缓存（生产环境建议启用）
        resolver.setCacheable(true);
        
        return resolver;
    }

    /**
     * 插件 ClassLoader 模板解析器
     * 支持从多个插件的 ClassLoader 中查找和加载模板
     */
    public static class PluginClassLoaderTemplateResolver extends AbstractConfigurableTemplateResolver {

        private final PluginManager pluginManager;
        private final PluginFrameworkService pluginFrameworkService;

        public PluginClassLoaderTemplateResolver(
                PluginManager pluginManager,
                PluginFrameworkService pluginFrameworkService) {
            this.pluginManager = pluginManager;
            this.pluginFrameworkService = pluginFrameworkService;
        }

        @Override
        protected ITemplateResource computeTemplateResource(
                IEngineConfiguration configuration,
                String ownerTemplate,
                String template,
                String resourceName,
                String characterEncoding,
                Map<String, Object> templateResolutionAttributes) {
            
            log.debug("计算模板资源: template={}, resourceName={}", template, resourceName);
            
            // 解析模板名称
            // 模板名称格式: plugin/{gameCode}/index 或 plugin/{gameCode}/views/dashboard
            if (!template.startsWith("plugin/")) {
                return null;
            }
            
            String[] parts = template.split("/", 3);
            if (parts.length < 3) {
                log.debug("模板名称格式不正确: {}", template);
                return null;
            }
            
            String gameCode = parts[1];
            String templatePath = parts[2];
            
            // 根据 gameCode 获取插件ID
            var pluginIdOpt = pluginFrameworkService.getPluginIdByGameCode(gameCode);
            if (pluginIdOpt.isEmpty()) {
                log.debug("未找到游戏对应的插件: {}", gameCode);
                return null;
            }
            
            String pluginId = pluginIdOpt.get();
            PluginWrapper plugin = pluginManager.getPlugin(pluginId);
            if (plugin == null) {
                log.debug("插件不存在: {}", pluginId);
                return null;
            }
            
            // 构建资源路径: ui/{templatePath}.html
            String resourcePath = "ui/" + templatePath + ".html";
            
            log.debug("尝试从插件 {} 加载模板: {}", pluginId, resourcePath);
            
            // 从插件的 ClassLoader 加载模板
            try {
                InputStream inputStream = plugin.getPluginClassLoader().getResourceAsStream(resourcePath);
                if (inputStream == null) {
                    log.debug("模板不存在: {} -> {}", pluginId, resourcePath);
                    return null;
                }
                
                // 读取模板内容
                String content = readStream(inputStream, characterEncoding);
                
                log.info("成功加载模板: {} -> {}", template, resourcePath);
                
                // 返回字符串模板资源
                return new StringTemplateResource(content);
                
            } catch (IOException e) {
                log.error("读取模板失败: {} -> {}", pluginId, resourcePath, e);
                return null;
            }
        }
        
        /**
         * 读取输入流内容
         */
        private String readStream(InputStream inputStream, String characterEncoding) throws IOException {
            try (Reader reader = new InputStreamReader(inputStream, 
                    characterEncoding != null ? characterEncoding : StandardCharsets.UTF_8.name())) {
                StringBuilder builder = new StringBuilder();
                char[] buffer = new char[4096];
                int len;
                while ((len = reader.read(buffer)) != -1) {
                    builder.append(buffer, 0, len);
                }
                return builder.toString();
            }
        }
    }
}

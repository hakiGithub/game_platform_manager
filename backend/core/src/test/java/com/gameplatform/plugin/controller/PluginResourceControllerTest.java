package com.gameplatform.plugin.controller;

import com.gameplatform.plugin.service.PluginFrameworkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 插件静态资源控制器测试类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("插件静态资源控制器测试")
class PluginResourceControllerTest {

    @Mock
    private PluginFrameworkService pluginFrameworkService;

    @InjectMocks
    private PluginResourceController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Nested
    @DisplayName("getPluginResource 方法测试")
    class GetPluginResourceTests {

        @Test
        @DisplayName("获取 Wujie 子应用入口 index.html-成功")
        void testGetIndexHtmlSuccess() throws Exception {
            // Given
            byte[] content = "<!DOCTYPE html><html></html>".getBytes(StandardCharsets.UTF_8);
            when(pluginFrameworkService.getPluginIdByGameCode("l4d2")).thenReturn(Optional.of("plugin-l4d2"));
            when(pluginFrameworkService.getPluginResource("plugin-l4d2", "index.html")).thenReturn(content);
            when(pluginFrameworkService.getContentType("index.html")).thenReturn("text/html; charset=UTF-8");

            // When & Then
            mockMvc.perform(get("/plugins/l4d2/ui/index.html"))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(content))
                    .andExpect(content().contentType(MediaType.parseMediaType("text/html; charset=UTF-8")))
                    .andExpect(header().string("Access-Control-Allow-Origin", "*"))
                    .andExpect(header().string("Cache-Control", "max-age=604800"));
        }

        @Test
        @DisplayName("获取 JS 静态资源-成功")
        void testGetJsResourceSuccess() throws Exception {
            // Given
            byte[] content = "console.log('wujie')".getBytes(StandardCharsets.UTF_8);
            when(pluginFrameworkService.getPluginIdByGameCode("l4d2")).thenReturn(Optional.of("plugin-l4d2"));
            when(pluginFrameworkService.getPluginResource("plugin-l4d2", "js/app.js")).thenReturn(content);
            when(pluginFrameworkService.getContentType("js/app.js")).thenReturn("application/javascript; charset=UTF-8");

            // When & Then
            mockMvc.perform(get("/plugins/l4d2/ui/js/app.js"))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(content))
                    .andExpect(content().contentType(MediaType.parseMediaType("application/javascript; charset=UTF-8")));
        }

        @Test
        @DisplayName("默认返回入口文件")
        void testDefaultEntry() throws Exception {
            // Given
            byte[] content = "<html></html>".getBytes(StandardCharsets.UTF_8);
            when(pluginFrameworkService.getPluginIdByGameCode("l4d2")).thenReturn(Optional.of("plugin-l4d2"));
            when(pluginFrameworkService.getPluginResource("plugin-l4d2", "index.html")).thenReturn(content);
            when(pluginFrameworkService.getContentType("index.html")).thenReturn("text/html; charset=UTF-8");

            // When & Then
            mockMvc.perform(get("/plugins/l4d2/ui/"))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(content));
        }

        @Test
        @DisplayName("插件不存在-返回 404")
        void testPluginNotFound() throws Exception {
            // Given
            when(pluginFrameworkService.getPluginIdByGameCode("unknown")).thenReturn(Optional.empty());

            // When & Then
            mockMvc.perform(get("/plugins/unknown/ui/index.html"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("资源不存在-返回 404")
        void testResourceNotFound() throws Exception {
            // Given
            when(pluginFrameworkService.getPluginIdByGameCode("l4d2")).thenReturn(Optional.of("plugin-l4d2"));
            when(pluginFrameworkService.getPluginResource("plugin-l4d2", "missing.js")).thenReturn(null);

            // When & Then
            mockMvc.perform(get("/plugins/l4d2/ui/missing.js"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("路径包含非法字符-返回 400")
        void testIllegalPath() throws Exception {
            // When & Then
            mockMvc.perform(get("/plugins/l4d2/ui/../secret.txt"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("回显 Origin 头")
        void testEchoOrigin() throws Exception {
            // Given
            byte[] content = "body{}".getBytes(StandardCharsets.UTF_8);
            when(pluginFrameworkService.getPluginIdByGameCode("l4d2")).thenReturn(Optional.of("plugin-l4d2"));
            when(pluginFrameworkService.getPluginResource(eq("plugin-l4d2"), eq("css/style.css"))).thenReturn(content);
            when(pluginFrameworkService.getContentType("css/style.css")).thenReturn("text/css; charset=UTF-8");

            // When & Then
            mockMvc.perform(get("/plugins/l4d2/ui/css/style.css")
                            .header("Origin", "http://localhost:5173"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        }
    }

}

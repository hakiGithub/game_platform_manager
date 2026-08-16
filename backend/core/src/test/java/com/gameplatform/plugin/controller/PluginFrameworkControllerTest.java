package com.gameplatform.plugin.controller;

import com.gameplatform.plugin.service.PluginFrameworkService;
import com.gameplatform.plugin.vo.PluginManifestVO;
import com.gameplatform.plugin.vo.PluginStatusVO;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 插件框架控制器测试类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("插件框架控制器测试")
class PluginFrameworkControllerTest {

    @Mock
    private PluginFrameworkService pluginFrameworkService;

    @Mock
    private com.gameplatform.plugin.config.PluginConfig pluginConfig;

    @InjectMocks
    private PluginFrameworkController controller;

    private MockMvc mockMvc;

    private PluginStatusVO testPluginStatus;
    private PluginManifestVO testManifest;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        // 创建测试插件状态
        testPluginStatus = PluginStatusVO.builder()
                .pluginId("test-plugin")
                .pluginName("测试插件")
                .version("1.0.0")
                .state("STARTED")
                .enabled(true)
                .running(true)
                .provider("GamePlatform")
                .description("测试插件描述")
                .pluginPath("/plugins/test-plugin.jar")
                .build();

        // 创建测试清单
        testManifest = PluginManifestVO.builder()
                .pluginId("test-plugin")
                .gameCode("test-game")
                .gameName("测试游戏")
                .version("1.0.0")
                .description("测试游戏插件")
                .icon("/plugin/test-game/ui/assets/icon.png")
                .frontend(PluginManifestVO.FrontendConfig.builder()
                        .entry("/plugin/test-game/ui/index.html")
                        .build())
                .api(PluginManifestVO.ApiConfig.builder()
                        .basePath("/pf4j/plugin/test-game")
                        .build())
                .build();
    }

    // ==================== listPlugins 测试 ====================

    @Nested
    @DisplayName("listPlugins 方法测试")
    class ListPluginsTests {

        @Test
        @DisplayName("获取所有插件列表-成功")
        void testListPluginsSuccess() throws Exception {
            // Given
            List<PluginStatusVO> plugins = List.of(testPluginStatus);
            when(pluginFrameworkService.getAllPluginStatus()).thenReturn(plugins);

            // When & Then
            mockMvc.perform(get("/pf4j/plugins"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data[0].pluginId").value("test-plugin"))
                    .andExpect(jsonPath("$.data[0].pluginName").value("测试插件"));

            verify(pluginFrameworkService).getAllPluginStatus();
        }

        @Test
        @DisplayName("获取所有插件列表-空列表")
        void testListPluginsEmpty() throws Exception {
            // Given
            when(pluginFrameworkService.getAllPluginStatus()).thenReturn(Collections.emptyList());

            // When & Then
            mockMvc.perform(get("/pf4j/plugins"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }

    // ==================== getPluginStatus 测试 ====================

    @Nested
    @DisplayName("getPluginStatus 方法测试")
    class GetPluginStatusTests {

        @Test
        @DisplayName("获取插件状态-成功")
        void testGetPluginStatusSuccess() throws Exception {
            // Given
            when(pluginFrameworkService.getPluginStatus("test-plugin")).thenReturn(testPluginStatus);

            // When & Then
            mockMvc.perform(get("/pf4j/plugins/test-plugin/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.pluginId").value("test-plugin"))
                    .andExpect(jsonPath("$.data.state").value("STARTED"));

            verify(pluginFrameworkService).getPluginStatus("test-plugin");
        }

        @Test
        @DisplayName("获取插件状态-插件不存在")
        void testGetPluginStatusNotFound() throws Exception {
            // Given
            when(pluginFrameworkService.getPluginStatus("nonexistent")).thenReturn(null);

            // When & Then
            mockMvc.perform(get("/pf4j/plugins/nonexistent/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("插件不存在"));
        }
    }

    // ==================== getManifest 测试 ====================

    @Nested
    @DisplayName("getManifest 方法测试")
    class GetManifestTests {

        @Test
        @DisplayName("根据游戏编码获取清单-成功")
        void testGetManifestSuccess() throws Exception {
            // Given
            when(pluginFrameworkService.getManifestByGameCode("test-game")).thenReturn(testManifest);

            // When & Then
            mockMvc.perform(get("/pf4j/plugin/test-game/manifest"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.gameCode").value("test-game"))
                    .andExpect(jsonPath("$.data.gameName").value("测试游戏"));

            verify(pluginFrameworkService).getManifestByGameCode("test-game");
        }

        @Test
        @DisplayName("根据游戏编码获取清单-游戏不存在")
        void testGetManifestNotFound() throws Exception {
            // Given
            when(pluginFrameworkService.getManifestByGameCode("nonexistent")).thenReturn(null);

            // When & Then
            mockMvc.perform(get("/pf4j/plugin/nonexistent/manifest"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("未找到游戏对应的插件: nonexistent"));
        }
    }

    // ==================== getManifestByPluginId 测试 ====================

    @Nested
    @DisplayName("getManifestByPluginId 方法测试")
    class GetManifestByPluginIdTests {

        @Test
        @DisplayName("根据插件ID获取清单-成功")
        void testGetManifestByPluginIdSuccess() throws Exception {
            // Given
            when(pluginFrameworkService.getManifestByPluginId("test-plugin")).thenReturn(testManifest);

            // When & Then
            mockMvc.perform(get("/pf4j/plugins/test-plugin/manifest"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.pluginId").value("test-plugin"));

            verify(pluginFrameworkService).getManifestByPluginId("test-plugin");
        }

        @Test
        @DisplayName("根据插件ID获取清单-插件不存在")
        void testGetManifestByPluginIdNotFound() throws Exception {
            // Given
            when(pluginFrameworkService.getManifestByPluginId("nonexistent")).thenReturn(null);

            // When & Then
            mockMvc.perform(get("/pf4j/plugins/nonexistent/manifest"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("插件不存在: nonexistent"));
        }
    }

    // ==================== startPlugin 测试 ====================

    @Nested
    @DisplayName("startPlugin 方法测试")
    class StartPluginTests {

        @Test
        @DisplayName("启动插件-成功")
        void testStartPluginSuccess() throws Exception {
            // Given
            when(pluginFrameworkService.startPlugin("test-plugin")).thenReturn(true);

            // When & Then
            mockMvc.perform(post("/pf4j/plugins/test-plugin/start"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            verify(pluginFrameworkService).startPlugin("test-plugin");
        }

        @Test
        @DisplayName("启动插件-失败")
        void testStartPluginFail() throws Exception {
            // Given
            when(pluginFrameworkService.startPlugin("test-plugin")).thenReturn(false);

            // When & Then
            mockMvc.perform(post("/pf4j/plugins/test-plugin/start"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("启动插件失败"));
        }
    }

    // ==================== stopPlugin 测试 ====================

    @Nested
    @DisplayName("stopPlugin 方法测试")
    class StopPluginTests {

        @Test
        @DisplayName("停止插件-成功")
        void testStopPluginSuccess() throws Exception {
            // Given
            when(pluginFrameworkService.stopPlugin("test-plugin")).thenReturn(true);

            // When & Then
            mockMvc.perform(post("/pf4j/plugins/test-plugin/stop"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            verify(pluginFrameworkService).stopPlugin("test-plugin");
        }

        @Test
        @DisplayName("停止插件-失败")
        void testStopPluginFail() throws Exception {
            // Given
            when(pluginFrameworkService.stopPlugin("test-plugin")).thenReturn(false);

            // When & Then
            mockMvc.perform(post("/pf4j/plugins/test-plugin/stop"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("停止插件失败"));
        }
    }

    // ==================== reloadPlugin 测试 ====================

    @Nested
    @DisplayName("reloadPlugin 方法测试")
    class ReloadPluginTests {

        @Test
        @DisplayName("重新加载插件-成功")
        void testReloadPluginSuccess() throws Exception {
            // Given
            when(pluginFrameworkService.reloadPlugin("test-plugin")).thenReturn(true);

            // When & Then
            mockMvc.perform(post("/pf4j/plugins/test-plugin/reload"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            verify(pluginFrameworkService).reloadPlugin("test-plugin");
        }

        @Test
        @DisplayName("重新加载插件-失败")
        void testReloadPluginFail() throws Exception {
            // Given
            when(pluginFrameworkService.reloadPlugin("test-plugin")).thenReturn(false);

            // When & Then
            mockMvc.perform(post("/pf4j/plugins/test-plugin/reload"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("重新加载插件失败"));
        }
    }

    // ==================== unloadPlugin 测试 ====================

    @Nested
    @DisplayName("unloadPlugin 方法测试")
    class UnloadPluginTests {

        @Test
        @DisplayName("卸载插件-成功")
        void testUnloadPluginSuccess() throws Exception {
            // Given（purgeTasks 默认 true）
            when(pluginFrameworkService.unloadPlugin("test-plugin", true)).thenReturn(true);

            // When & Then
            mockMvc.perform(delete("/pf4j/plugins/test-plugin"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            verify(pluginFrameworkService).unloadPlugin("test-plugin", true);
        }

        @Test
        @DisplayName("卸载插件-失败")
        void testUnloadPluginFail() throws Exception {
            // Given
            when(pluginFrameworkService.unloadPlugin("test-plugin", true)).thenReturn(false);

            // When & Then
            mockMvc.perform(delete("/pf4j/plugins/test-plugin"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("卸载插件失败"));
        }

        @Test
        @DisplayName("卸载插件-热部署保留任务历史")
        void testUnloadPluginKeepTasks() throws Exception {
            // Given（deploy-plugin.sh 热部署传 purgeTasks=false）
            when(pluginFrameworkService.unloadPlugin("test-plugin", false)).thenReturn(true);

            // When & Then
            mockMvc.perform(delete("/pf4j/plugins/test-plugin").param("purgeTasks", "false"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            verify(pluginFrameworkService).unloadPlugin("test-plugin", false);
        }
    }

    // ==================== loadPlugin 测试 ====================

    @Nested
    @DisplayName("loadPlugin 方法测试")
    class LoadPluginTests {

        @TempDir
        Path pluginsDir;

        @Test
        @DisplayName("加载插件-成功")
        void testLoadPluginSuccess() throws Exception {
            // Given
            Path jar = pluginsDir.resolve("test-plugin-1.0.0.jar");
            java.nio.file.Files.writeString(jar, "dummy");
            when(pluginConfig.getPluginsDir()).thenReturn(pluginsDir.toString());
            when(pluginFrameworkService.loadPlugin(jar.toString())).thenReturn("test-plugin");
            when(pluginFrameworkService.startPlugin("test-plugin")).thenReturn(true);

            // When & Then
            mockMvc.perform(post("/pf4j/plugins/load").param("jarName", "test-plugin-1.0.0.jar"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("test-plugin"));
        }

        @Test
        @DisplayName("加载插件-拒绝路径穿越")
        void testLoadPluginPathTraversal() throws Exception {
            // Given
            when(pluginConfig.getPluginsDir()).thenReturn(pluginsDir.toString());

            // When & Then
            mockMvc.perform(post("/pf4j/plugins/load").param("jarName", "../evil.jar"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("jarName 必须是插件目录内的文件名"));
        }

        @Test
        @DisplayName("加载插件-文件不存在")
        void testLoadPluginFileMissing() throws Exception {
            // Given
            when(pluginConfig.getPluginsDir()).thenReturn(pluginsDir.toString());

            // When & Then
            mockMvc.perform(post("/pf4j/plugins/load").param("jarName", "not-exist.jar"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("插件文件不存在: not-exist.jar"));
        }

        @Test
        @DisplayName("加载插件-已加载但启动失败")
        void testLoadPluginStartFail() throws Exception {
            // Given
            Path jar = pluginsDir.resolve("test-plugin-1.0.0.jar");
            java.nio.file.Files.writeString(jar, "dummy");
            when(pluginConfig.getPluginsDir()).thenReturn(pluginsDir.toString());
            when(pluginFrameworkService.loadPlugin(jar.toString())).thenReturn("test-plugin");
            when(pluginFrameworkService.startPlugin("test-plugin")).thenReturn(false);

            // When & Then
            mockMvc.perform(post("/pf4j/plugins/load").param("jarName", "test-plugin-1.0.0.jar"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("插件已加载但启动失败: test-plugin"));
        }
    }

    // ==================== getPluginResource 测试 ====================

    @Nested
    @DisplayName("getPluginResource 方法测试")
    class GetPluginResourceTests {

        @Test
        @DisplayName("获取插件静态资源-成功")
        void testGetPluginResourceSuccess() {
            // Given
            byte[] content = "<html>test</html>".getBytes(StandardCharsets.UTF_8);
            when(pluginFrameworkService.getPluginIdByGameCode("test-game"))
                    .thenReturn(Optional.of("test-plugin"));
            when(pluginFrameworkService.getPluginResource("test-plugin", "index.html"))
                    .thenReturn(content);
            when(pluginFrameworkService.getContentType("index.html"))
                    .thenReturn("text/html; charset=UTF-8");

            // When
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRequestURI("/plugin/test-game/ui/index.html");
            ResponseEntity<byte[]> result = controller.getPluginResource("test-game", request);

            // Then
            assertNotNull(result);
            assertEquals(200, result.getStatusCode().value());
            assertArrayEquals(content, result.getBody());
            assertEquals(MediaType.parseMediaType("text/html; charset=UTF-8"), result.getHeaders().getContentType());
        }

        @Test
        @DisplayName("获取插件静态资源-插件不存在")
        void testGetPluginResourcePluginNotFound() {
            // Given
            when(pluginFrameworkService.getPluginIdByGameCode("nonexistent"))
                    .thenReturn(Optional.empty());

            // When
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRequestURI("/plugin/nonexistent/ui/index.html");
            ResponseEntity<byte[]> result = controller.getPluginResource("nonexistent", request);

            // Then
            assertNotNull(result);
            assertEquals(404, result.getStatusCode().value());
        }

        @Test
        @DisplayName("获取插件静态资源-资源不存在")
        void testGetPluginResourceNotFound() {
            // Given
            when(pluginFrameworkService.getPluginIdByGameCode("test-game"))
                    .thenReturn(Optional.of("test-plugin"));
            when(pluginFrameworkService.getPluginResource("test-plugin", "nonexistent.html"))
                    .thenReturn(null);

            // When
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRequestURI("/plugin/test-game/ui/nonexistent.html");
            ResponseEntity<byte[]> result = controller.getPluginResource("test-game", request);

            // Then
            assertNotNull(result);
            assertEquals(404, result.getStatusCode().value());
        }

        @Test
        @DisplayName("获取插件静态资源-验证缓存头")
        void testGetPluginResourceCacheHeaders() {
            // Given
            byte[] content = "body { color: red; }".getBytes(StandardCharsets.UTF_8);
            when(pluginFrameworkService.getPluginIdByGameCode("test-game"))
                    .thenReturn(Optional.of("test-plugin"));
            when(pluginFrameworkService.getPluginResource("test-plugin", "style.css"))
                    .thenReturn(content);
            when(pluginFrameworkService.getContentType("style.css"))
                    .thenReturn("text/css; charset=UTF-8");

            // When
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRequestURI("/plugin/test-game/ui/style.css");
            ResponseEntity<byte[]> result = controller.getPluginResource("test-game", request);

            // Then
            assertNotNull(result);
            assertEquals(200, result.getStatusCode().value());
            // 验证缓存控制头
            String cacheControl = result.getHeaders().getCacheControl();
            assertNotNull(cacheControl);
            assertTrue(cacheControl.contains("max-age"));
        }
    }

    // ==================== getPluginIcon 测试 ====================

    @Nested
    @DisplayName("getPluginIcon 方法测试")
    class GetPluginIconTests {

        @Test
        @DisplayName("获取插件图标-成功")
        void testGetPluginIconSuccess() {
            // Given
            byte[] iconContent = "fake-icon-data".getBytes(StandardCharsets.UTF_8);
            when(pluginFrameworkService.getManifestByGameCode("test-game"))
                    .thenReturn(testManifest);
            when(pluginFrameworkService.getPluginIdByGameCode("test-game"))
                    .thenReturn(Optional.of("test-plugin"));
            when(pluginFrameworkService.getPluginResource(eq("test-plugin"), anyString()))
                    .thenReturn(iconContent);
            when(pluginFrameworkService.getContentType(anyString()))
                    .thenReturn("image/png");

            // When
            ResponseEntity<byte[]> result = controller.getPluginIcon("test-game");

            // Then
            assertNotNull(result);
            assertEquals(200, result.getStatusCode().value());
        }

        @Test
        @DisplayName("获取插件图标-清单不存在")
        void testGetPluginIconManifestNotFound() {
            // Given
            when(pluginFrameworkService.getManifestByGameCode("nonexistent"))
                    .thenReturn(null);

            // When
            ResponseEntity<byte[]> result = controller.getPluginIcon("nonexistent");

            // Then
            assertNotNull(result);
            assertEquals(404, result.getStatusCode().value());
        }

        @Test
        @DisplayName("获取插件图标-图标路径为空")
        void testGetPluginIconNullIcon() {
            // Given
            PluginManifestVO manifestWithoutIcon = PluginManifestVO.builder()
                    .pluginId("test-plugin")
                    .gameCode("test-game")
                    .icon(null)
                    .build();
            when(pluginFrameworkService.getManifestByGameCode("test-game"))
                    .thenReturn(manifestWithoutIcon);

            // When
            ResponseEntity<byte[]> result = controller.getPluginIcon("test-game");

            // Then
            assertNotNull(result);
            assertEquals(404, result.getStatusCode().value());
        }
    }

}

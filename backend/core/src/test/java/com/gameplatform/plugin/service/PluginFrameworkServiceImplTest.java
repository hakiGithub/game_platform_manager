package com.gameplatform.plugin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.plugin.context.PluginSpringContextFactory;
import com.gameplatform.plugin.extension.GameEnhancementExtension;
import com.gameplatform.plugin.service.impl.PluginFrameworkServiceImpl;
import com.gameplatform.plugin.vo.PluginManifestVO;
import com.gameplatform.plugin.vo.PluginStatusVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.pf4j.PluginManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 插件框架服务测试类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("插件框架服务测试")
class PluginFrameworkServiceImplTest {

    @Mock
    private PluginManager pluginManager;

    @Mock
    private PluginSpringContextFactory springContextFactory;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PluginFrameworkServiceImpl pluginFrameworkService;

    private PluginWrapper testPlugin;
    private PluginDescriptor testDescriptor;
    private GameEnhancementExtension testExtension;

    @BeforeEach
    void setUp() {
        // 创建测试插件描述符
        testDescriptor = mock(PluginDescriptor.class);
        when(testDescriptor.getPluginDescription()).thenReturn("测试插件");
        // 直接返回字符串 "1.0.0"
        when(testDescriptor.getVersion()).thenReturn("1.0.0");
        when(testDescriptor.getProvider()).thenReturn("GamePlatform");
        when(testDescriptor.getDependencies()).thenReturn(Collections.emptyList());

        // 创建测试插件包装器
        testPlugin = mock(PluginWrapper.class);
        when(testPlugin.getPluginId()).thenReturn("test-plugin");
        when(testPlugin.getDescriptor()).thenReturn(testDescriptor);
        when(testPlugin.getPluginState()).thenReturn(PluginState.STARTED);
        when(testPlugin.getPluginPath()).thenReturn(Path.of("/plugins/test-plugin.jar"));

        // 创建测试扩展点
        testExtension = mock(GameEnhancementExtension.class);
        when(testExtension.getGameCode()).thenReturn("test-game");
        when(testExtension.getGameName()).thenReturn("测试游戏");
        when(testExtension.getVersion()).thenReturn("1.0.0");
        when(testExtension.getDescription()).thenReturn("测试游戏插件");
        when(testExtension.getIcon()).thenReturn("assets/icon.png");
        when(testExtension.getFrontendEntry()).thenReturn("index.html");
        when(testExtension.getManifest()).thenReturn(java.util.Map.of(
                "gameCode", "test-game",
                "gameName", "测试游戏"
        ));
    }

    // ==================== getAllPlugins 测试 ====================

    @Nested
    @DisplayName("getAllPlugins 方法测试")
    class GetAllPluginsTests {

        @Test
        @DisplayName("获取所有插件列表-成功")
        void testGetAllPluginsSuccess() {
            // Given
            List<PluginWrapper> expectedPlugins = List.of(testPlugin);
            when(pluginManager.getPlugins()).thenReturn(expectedPlugins);

            // When
            List<PluginWrapper> result = pluginFrameworkService.getAllPlugins();

            // Then
            assertNotNull(result);
            assertEquals(1, result.size());
            assertEquals("test-plugin", result.get(0).getPluginId());
            verify(pluginManager).getPlugins();
        }

        @Test
        @DisplayName("获取所有插件列表-空列表")
        void testGetAllPluginsEmpty() {
            // Given
            when(pluginManager.getPlugins()).thenReturn(Collections.emptyList());

            // When
            List<PluginWrapper> result = pluginFrameworkService.getAllPlugins();

            // Then
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    // ==================== getPlugin 测试 ====================

    @Nested
    @DisplayName("getPlugin 方法测试")
    class GetPluginTests {

        @Test
        @DisplayName("根据ID获取插件-成功")
        void testGetPluginSuccess() {
            // Given
            when(pluginManager.getPlugin("test-plugin")).thenReturn(testPlugin);

            // When
            Optional<PluginWrapper> result = pluginFrameworkService.getPlugin("test-plugin");

            // Then
            assertTrue(result.isPresent());
            assertEquals("test-plugin", result.get().getPluginId());
        }

        @Test
        @DisplayName("根据ID获取插件-插件不存在")
        void testGetPluginNotFound() {
            // Given
            when(pluginManager.getPlugin("nonexistent")).thenReturn(null);

            // When
            Optional<PluginWrapper> result = pluginFrameworkService.getPlugin("nonexistent");

            // Then
            assertFalse(result.isPresent());
        }
    }

    // ==================== getPluginStatus 测试 ====================

    @Nested
    @DisplayName("getPluginStatus 方法测试")
    class GetPluginStatusTests {

        @Test
        @DisplayName("获取插件状态-成功")
        void testGetPluginStatusSuccess() {
            // Given
            when(pluginManager.getPlugin("test-plugin")).thenReturn(testPlugin);

            // When
            PluginStatusVO result = pluginFrameworkService.getPluginStatus("test-plugin");

            // Then
            assertNotNull(result);
            assertEquals("test-plugin", result.getPluginId());
            assertEquals("测试插件", result.getPluginName());
            assertEquals("1.0.0", result.getVersion());
            assertEquals("STARTED", result.getState());
            assertTrue(result.getEnabled());
            assertTrue(result.getRunning());
        }

        @Test
        @DisplayName("获取插件状态-插件不存在")
        void testGetPluginStatusNotFound() {
            // Given
            when(pluginManager.getPlugin("nonexistent")).thenReturn(null);

            // When
            PluginStatusVO result = pluginFrameworkService.getPluginStatus("nonexistent");

            // Then
            assertNull(result);
        }

        @Test
        @DisplayName("获取插件状态-已停止状态")
        void testGetPluginStatusStopped() {
            // Given
            when(testPlugin.getPluginState()).thenReturn(PluginState.STOPPED);
            when(pluginManager.getPlugin("test-plugin")).thenReturn(testPlugin);

            // When
            PluginStatusVO result = pluginFrameworkService.getPluginStatus("test-plugin");

            // Then
            assertNotNull(result);
            assertEquals("STOPPED", result.getState());
            assertTrue(result.getEnabled());
            assertFalse(result.getRunning());
        }

        @Test
        @DisplayName("获取插件状态-已禁用状态")
        void testGetPluginStatusDisabled() {
            // Given
            when(testPlugin.getPluginState()).thenReturn(PluginState.DISABLED);
            when(pluginManager.getPlugin("test-plugin")).thenReturn(testPlugin);

            // When
            PluginStatusVO result = pluginFrameworkService.getPluginStatus("test-plugin");

            // Then
            assertNotNull(result);
            assertEquals("DISABLED", result.getState());
            assertFalse(result.getEnabled());
            assertFalse(result.getRunning());
        }
    }

    // ==================== getAllPluginStatus 测试 ====================

    @Nested
    @DisplayName("getAllPluginStatus 方法测试")
    class GetAllPluginStatusTests {

        @Test
        @DisplayName("获取所有插件状态列表-成功")
        void testGetAllPluginStatusSuccess() {
            // Given
            PluginWrapper plugin2 = mock(PluginWrapper.class);
            PluginDescriptor descriptor2 = mock(PluginDescriptor.class);
            when(descriptor2.getVersion()).thenReturn("2.0.0");
            when(plugin2.getPluginId()).thenReturn("plugin-2");
            when(plugin2.getDescriptor()).thenReturn(descriptor2);
            when(plugin2.getPluginState()).thenReturn(PluginState.STOPPED);
            when(plugin2.getPluginPath()).thenReturn(Path.of("/plugins/plugin-2.jar"));
            when(descriptor2.getPluginDescription()).thenReturn("插件2");
            when(descriptor2.getProvider()).thenReturn("Provider2");
            when(descriptor2.getDependencies()).thenReturn(Collections.emptyList());

            when(pluginManager.getPlugins()).thenReturn(List.of(testPlugin, plugin2));

            // When
            List<PluginStatusVO> result = pluginFrameworkService.getAllPluginStatus();

            // Then
            assertNotNull(result);
            assertEquals(2, result.size());
            assertEquals("test-plugin", result.get(0).getPluginId());
            assertEquals("plugin-2", result.get(1).getPluginId());
        }

        @Test
        @DisplayName("获取所有插件状态列表-空列表")
        void testGetAllPluginStatusEmpty() {
            // Given
            when(pluginManager.getPlugins()).thenReturn(Collections.emptyList());

            // When
            List<PluginStatusVO> result = pluginFrameworkService.getAllPluginStatus();

            // Then
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    // ==================== getManifestByGameCode 测试 ====================

    @Nested
    @DisplayName("getManifestByGameCode 方法测试")
    class GetManifestByGameCodeTests {

        @Test
        @DisplayName("根据游戏编码获取清单-游戏不存在")
        void testGetManifestByGameCodeNotFound() {
            // Given
            when(pluginManager.getExtensions(GameEnhancementExtension.class)).thenReturn(Collections.emptyList());

            // When
            PluginManifestVO result = pluginFrameworkService.getManifestByGameCode("nonexistent-game");

            // Then
            assertNull(result);
        }
    }

    // ==================== getManifestByPluginId 测试 ====================

    @Nested
    @DisplayName("getManifestByPluginId 方法测试")
    class GetManifestByPluginIdTests {

        @Test
        @DisplayName("根据插件ID获取清单-插件不存在")
        void testGetManifestByPluginIdNotFound() {
            // Given
            when(pluginManager.getPlugin("nonexistent")).thenReturn(null);

            // When
            PluginManifestVO result = pluginFrameworkService.getManifestByPluginId("nonexistent");

            // Then
            assertNull(result);
        }
    }

    // ==================== startPlugin 测试 ====================

    @Nested
    @DisplayName("startPlugin 方法测试")
    class StartPluginTests {

        @Test
        @DisplayName("启动插件-成功")
        void testStartPluginSuccess() {
            // Given
            when(pluginManager.startPlugin("test-plugin")).thenReturn(PluginState.STARTED);

            // When
            boolean result = pluginFrameworkService.startPlugin("test-plugin");

            // Then
            assertTrue(result);
            verify(pluginManager).startPlugin("test-plugin");
        }

        @Test
        @DisplayName("启动插件-失败")
        void testStartPluginFail() {
            // Given
            when(pluginManager.startPlugin("test-plugin")).thenReturn(PluginState.DISABLED);

            // When
            boolean result = pluginFrameworkService.startPlugin("test-plugin");

            // Then
            assertFalse(result);
        }

        @Test
        @DisplayName("启动插件-异常处理")
        void testStartPluginException() {
            // Given
            when(pluginManager.startPlugin("test-plugin")).thenThrow(new RuntimeException("启动失败"));

            // When
            boolean result = pluginFrameworkService.startPlugin("test-plugin");

            // Then
            assertFalse(result);
        }
    }

    // ==================== stopPlugin 测试 ====================

    @Nested
    @DisplayName("stopPlugin 方法测试")
    class StopPluginTests {

        @Test
        @DisplayName("停止插件-成功")
        void testStopPluginSuccess() {
            // Given
            when(pluginManager.stopPlugin("test-plugin")).thenReturn(PluginState.STOPPED);

            // When
            boolean result = pluginFrameworkService.stopPlugin("test-plugin");

            // Then
            assertTrue(result);
            verify(pluginManager).stopPlugin("test-plugin");
        }

        @Test
        @DisplayName("停止插件-失败")
        void testStopPluginFail() {
            // Given
            when(pluginManager.stopPlugin("test-plugin")).thenReturn(PluginState.STARTED);

            // When
            boolean result = pluginFrameworkService.stopPlugin("test-plugin");

            // Then
            assertFalse(result);
        }

        @Test
        @DisplayName("停止插件-异常处理")
        void testStopPluginException() {
            // Given
            when(pluginManager.stopPlugin("test-plugin")).thenThrow(new RuntimeException("停止失败"));

            // When
            boolean result = pluginFrameworkService.stopPlugin("test-plugin");

            // Then
            assertFalse(result);
        }
    }

    // ==================== unloadPlugin 测试 ====================

    @Nested
    @DisplayName("unloadPlugin 方法测试")
    class UnloadPluginTests {

        @Test
        @DisplayName("卸载插件-成功")
        void testUnloadPluginSuccess() {
            // Given
            when(pluginManager.unloadPlugin("test-plugin")).thenReturn(true);

            // When
            boolean result = pluginFrameworkService.unloadPlugin("test-plugin");

            // Then
            assertTrue(result);
            verify(pluginManager).unloadPlugin("test-plugin");
        }

        @Test
        @DisplayName("卸载插件-失败")
        void testUnloadPluginFail() {
            // Given
            when(pluginManager.unloadPlugin("test-plugin")).thenReturn(false);

            // When
            boolean result = pluginFrameworkService.unloadPlugin("test-plugin");

            // Then
            assertFalse(result);
        }

        @Test
        @DisplayName("卸载插件-异常处理")
        void testUnloadPluginException() {
            // Given
            when(pluginManager.unloadPlugin("test-plugin")).thenThrow(new RuntimeException("卸载失败"));

            // When
            boolean result = pluginFrameworkService.unloadPlugin("test-plugin");

            // Then
            assertFalse(result);
        }
    }

    // ==================== reloadPlugin 测试 ====================

    @Nested
    @DisplayName("reloadPlugin 方法测试")
    class ReloadPluginTests {

        @Test
        @DisplayName("重新加载插件-插件不存在")
        void testReloadPluginNotFound() {
            // Given
            when(pluginManager.stopPlugin("test-plugin")).thenReturn(PluginState.STOPPED);
            when(pluginManager.unloadPlugin("test-plugin")).thenReturn(true);
            when(pluginManager.getPlugin("test-plugin")).thenReturn(null);

            // When
            boolean result = pluginFrameworkService.reloadPlugin("test-plugin");

            // Then
            assertFalse(result);
        }

        @Test
        @DisplayName("重新加载插件-异常处理")
        void testReloadPluginException() {
            // Given
            when(pluginManager.stopPlugin("test-plugin")).thenThrow(new RuntimeException("重载失败"));

            // When
            boolean result = pluginFrameworkService.reloadPlugin("test-plugin");

            // Then
            assertFalse(result);
        }
    }

    // ==================== loadPlugin 测试 ====================

    @Nested
    @DisplayName("loadPlugin 方法测试")
    class LoadPluginTests {

        @Test
        @DisplayName("加载插件-成功")
        void testLoadPluginSuccess() {
            // Given
            when(pluginManager.loadPlugin(any(Path.class))).thenReturn("new-plugin");

            // When
            String result = pluginFrameworkService.loadPlugin("/plugins/new-plugin.jar");

            // Then
            assertEquals("new-plugin", result);
        }

        @Test
        @DisplayName("加载插件-失败")
        void testLoadPluginFail() {
            // Given
            when(pluginManager.loadPlugin(any(Path.class))).thenReturn(null);

            // When
            String result = pluginFrameworkService.loadPlugin("/plugins/new-plugin.jar");

            // Then
            assertNull(result);
        }

        @Test
        @DisplayName("加载插件-异常处理")
        void testLoadPluginException() {
            // Given
            when(pluginManager.loadPlugin(any(Path.class))).thenThrow(new RuntimeException("加载失败"));

            // When
            String result = pluginFrameworkService.loadPlugin("/plugins/new-plugin.jar");

            // Then
            assertNull(result);
        }
    }

    // ==================== getPluginResource 测试 ====================

    @Nested
    @DisplayName("getPluginResource 方法测试")
    class GetPluginResourceTests {

        @Test
        @DisplayName("获取插件资源-插件不存在")
        void testGetPluginResourcePluginNotFound() {
            // Given
            when(pluginManager.getPlugin("nonexistent")).thenReturn(null);

            // When
            byte[] result = pluginFrameworkService.getPluginResource("nonexistent", "index.html");

            // Then
            assertNull(result);
        }

        @Test
        @DisplayName("获取插件资源-资源不存在")
        void testGetPluginResourceNotFound() throws IOException {
            // Given
            ClassLoader mockClassLoader = mock(ClassLoader.class);
            when(mockClassLoader.getResourceAsStream(anyString())).thenReturn(null);
            when(testPlugin.getPluginClassLoader()).thenReturn(mockClassLoader);
            when(pluginManager.getPlugin("test-plugin")).thenReturn(testPlugin);

            // When
            byte[] result = pluginFrameworkService.getPluginResource("test-plugin", "nonexistent.html");

            // Then
            assertNull(result);
        }

        @Test
        @DisplayName("获取插件资源-成功")
        void testGetPluginResourceSuccess() throws IOException {
            // Given
            byte[] expectedContent = "<html>test</html>".getBytes();
            ClassLoader mockClassLoader = mock(ClassLoader.class);
            when(mockClassLoader.getResourceAsStream("ui/index.html"))
                    .thenReturn(new ByteArrayInputStream(expectedContent));
            when(testPlugin.getPluginClassLoader()).thenReturn(mockClassLoader);
            when(pluginManager.getPlugin("test-plugin")).thenReturn(testPlugin);

            // When
            byte[] result = pluginFrameworkService.getPluginResource("test-plugin", "index.html");

            // Then
            assertNotNull(result);
            assertArrayEquals(expectedContent, result);
        }
    }

    // ==================== getContentType 测试 ====================

    @Nested
    @DisplayName("getContentType 方法测试")
    class GetContentTypeTests {

        @Test
        @DisplayName("获取Content-Type-HTML")
        void testGetContentTypeHtml() {
            assertEquals("text/html; charset=UTF-8", pluginFrameworkService.getContentType("index.html"));
        }

        @Test
        @DisplayName("获取Content-Type-CSS")
        void testGetContentTypeCss() {
            assertEquals("text/css; charset=UTF-8", pluginFrameworkService.getContentType("style.css"));
        }

        @Test
        @DisplayName("获取Content-Type-JavaScript")
        void testGetContentTypeJs() {
            assertEquals("application/javascript; charset=UTF-8", pluginFrameworkService.getContentType("app.js"));
        }

        @Test
        @DisplayName("获取Content-Type-JSON")
        void testGetContentTypeJson() {
            assertEquals("application/json; charset=UTF-8", pluginFrameworkService.getContentType("data.json"));
        }

        @Test
        @DisplayName("获取Content-Type-PNG")
        void testGetContentTypePng() {
            assertEquals("image/png", pluginFrameworkService.getContentType("icon.png"));
        }

        @Test
        @DisplayName("获取Content-Type-JPEG")
        void testGetContentTypeJpeg() {
            assertEquals("image/jpeg", pluginFrameworkService.getContentType("photo.jpg"));
            assertEquals("image/jpeg", pluginFrameworkService.getContentType("photo.jpeg"));
        }

        @Test
        @DisplayName("获取Content-Type-GIF")
        void testGetContentTypeGif() {
            assertEquals("image/gif", pluginFrameworkService.getContentType("animation.gif"));
        }

        @Test
        @DisplayName("获取Content-Type-SVG")
        void testGetContentTypeSvg() {
            assertEquals("image/svg+xml", pluginFrameworkService.getContentType("logo.svg"));
        }

        @Test
        @DisplayName("获取Content-Type-ICO")
        void testGetContentTypeIco() {
            assertEquals("image/x-icon", pluginFrameworkService.getContentType("favicon.ico"));
        }

        @Test
        @DisplayName("获取Content-Type-WOFF")
        void testGetContentTypeWoff() {
            assertEquals("font/woff", pluginFrameworkService.getContentType("font.woff"));
        }

        @Test
        @DisplayName("获取Content-Type-WOFF2")
        void testGetContentTypeWoff2() {
            assertEquals("font/woff2", pluginFrameworkService.getContentType("font.woff2"));
        }

        @Test
        @DisplayName("获取Content-Type-TTF")
        void testGetContentTypeTtf() {
            assertEquals("font/ttf", pluginFrameworkService.getContentType("font.ttf"));
        }

        @Test
        @DisplayName("获取Content-Type-EOT")
        void testGetContentTypeEot() {
            assertEquals("application/vnd.ms-fontobject", pluginFrameworkService.getContentType("font.eot"));
        }

        @Test
        @DisplayName("获取Content-Type-XML")
        void testGetContentTypeXml() {
            assertEquals("application/xml; charset=UTF-8", pluginFrameworkService.getContentType("config.xml"));
        }

        @Test
        @DisplayName("获取Content-Type-TXT")
        void testGetContentTypeTxt() {
            assertEquals("text/plain; charset=UTF-8", pluginFrameworkService.getContentType("readme.txt"));
        }

        @Test
        @DisplayName("获取Content-Type-未知类型")
        void testGetContentTypeUnknown() {
            assertEquals("application/octet-stream", pluginFrameworkService.getContentType("file.unknown"));
        }

        @Test
        @DisplayName("获取Content-Type-无扩展名")
        void testGetContentTypeNoExtension() {
            assertEquals("application/octet-stream", pluginFrameworkService.getContentType("filename"));
        }

        @Test
        @DisplayName("获取Content-Type-大写扩展名")
        void testGetContentTypeUpperCase() {
            assertEquals("text/html; charset=UTF-8", pluginFrameworkService.getContentType("INDEX.HTML"));
        }
    }

    // ==================== pluginExists 测试 ====================

    @Nested
    @DisplayName("pluginExists 方法测试")
    class PluginExistsTests {

        @Test
        @DisplayName("检查插件存在-存在")
        void testPluginExistsTrue() {
            // Given
            when(pluginManager.getPlugin("test-plugin")).thenReturn(testPlugin);

            // When
            boolean result = pluginFrameworkService.pluginExists("test-plugin");

            // Then
            assertTrue(result);
        }

        @Test
        @DisplayName("检查插件存在-不存在")
        void testPluginExistsFalse() {
            // Given
            when(pluginManager.getPlugin("nonexistent")).thenReturn(null);

            // When
            boolean result = pluginFrameworkService.pluginExists("nonexistent");

            // Then
            assertFalse(result);
        }
    }

    // ==================== getPluginIdByGameCode 测试 ====================

    @Nested
    @DisplayName("getPluginIdByGameCode 方法测试")
    class GetPluginIdByGameCodeTests {

        @Test
        @DisplayName("根据游戏编码获取插件ID-游戏不存在")
        void testGetPluginIdByGameCodeNotFound() {
            // Given
            when(pluginManager.getExtensions(GameEnhancementExtension.class)).thenReturn(Collections.emptyList());

            // When
            Optional<String> result = pluginFrameworkService.getPluginIdByGameCode("nonexistent-game");

            // Then
            assertFalse(result.isPresent());
        }
    }

}

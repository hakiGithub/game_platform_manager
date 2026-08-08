package com.gameplatform.plugin.loader;

import com.gameplatform.plugin.config.PluginConfig;
import com.gameplatform.plugin.extension.GameEnhancementExtension;
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
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.pf4j.PluginManager;
import org.springframework.boot.ApplicationArguments;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 插件自动加载器测试类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("插件自动加载器测试")
class PluginAutoLoaderTest {

    @Mock
    private PluginManager pluginManager;

    @Mock
    private PluginConfig pluginConfig;

    @Mock
    private ApplicationArguments applicationArguments;

    @InjectMocks
    private PluginAutoLoader autoLoader;

    private PluginWrapper testPlugin;
    private PluginDescriptor testDescriptor;
    private GameEnhancementExtension testExtension;

    @BeforeEach
    void setUp() {
        // 配置插件配置
        when(pluginConfig.getPluginsDir()).thenReturn("/plugins");

        // 创建测试插件描述符
        testDescriptor = mock(PluginDescriptor.class);
        when(testDescriptor.getPluginDescription()).thenReturn("测试插件");
        // 直接返回字符串
        when(testDescriptor.getVersion()).thenReturn("1.0.0");

        // 创建测试插件包装器
        testPlugin = mock(PluginWrapper.class);
        when(testPlugin.getPluginId()).thenReturn("test-plugin");
        when(testPlugin.getDescriptor()).thenReturn(testDescriptor);
        when(testPlugin.getPluginState()).thenReturn(PluginState.STARTED);

        // 创建测试扩展点
        testExtension = mock(GameEnhancementExtension.class);
        when(testExtension.getGameCode()).thenReturn("test-game");
        when(testExtension.getGameName()).thenReturn("测试游戏");
        when(testExtension.getVersion()).thenReturn("1.0.0");
    }

    // ==================== run 方法测试 ====================

    @Nested
    @DisplayName("run 方法测试")
    class RunTests {

        @Test
        @DisplayName("插件自动加载-成功")
        void testRunSuccess() {
            // Given
            when(pluginManager.getPlugins()).thenReturn(List.of(testPlugin));
            when(pluginManager.getExtensions(GameEnhancementExtension.class))
                    .thenReturn(List.of(testExtension));

            // When
            autoLoader.run(applicationArguments);

            // Then
            verify(pluginManager).loadPlugins();
            verify(pluginManager).startPlugins();
            verify(pluginManager, times(4)).getPlugins();
        }

        @Test
        @DisplayName("插件自动加载-无插件")
        void testRunNoPlugins() {
            // Given
            when(pluginManager.getPlugins()).thenReturn(Collections.emptyList());
            when(pluginManager.getExtensions(GameEnhancementExtension.class))
                    .thenReturn(Collections.emptyList());

            // When
            autoLoader.run(applicationArguments);

            // Then
            verify(pluginManager).loadPlugins();
            verify(pluginManager).startPlugins();
        }

        @Test
        @DisplayName("插件自动加载-部分插件启动")
        void testRunPartialPluginsStarted() {
            // Given
            PluginWrapper stoppedPlugin = mock(PluginWrapper.class);
            PluginDescriptor stoppedDescriptor = mock(PluginDescriptor.class);
            when(stoppedDescriptor.getVersion()).thenReturn("1.0.0");
            when(stoppedPlugin.getPluginId()).thenReturn("stopped-plugin");
            when(stoppedPlugin.getDescriptor()).thenReturn(stoppedDescriptor);
            when(stoppedPlugin.getPluginState()).thenReturn(PluginState.STOPPED);
            when(stoppedDescriptor.getPluginDescription()).thenReturn("已停止插件");

            when(pluginManager.getPlugins()).thenReturn(List.of(testPlugin, stoppedPlugin));
            when(pluginManager.getExtensions(GameEnhancementExtension.class))
                    .thenReturn(List.of(testExtension));

            // When
            autoLoader.run(applicationArguments);

            // Then
            verify(pluginManager).loadPlugins();
            verify(pluginManager).startPlugins();
        }

        @Test
        @DisplayName("插件自动加载-异常处理")
        void testRunException() {
            // Given
            doThrow(new RuntimeException("加载失败")).when(pluginManager).loadPlugins();

            // When
            autoLoader.run(applicationArguments);

            // Then - 不应抛出异常
            verify(pluginManager).loadPlugins();
        }

        @Test
        @DisplayName("插件自动加载-启动异常")
        void testRunStartException() {
            // Given
            when(pluginManager.getPlugins()).thenReturn(List.of(testPlugin));
            doThrow(new RuntimeException("启动失败")).when(pluginManager).startPlugins();

            // When
            autoLoader.run(applicationArguments);

            // Then - 不应抛出异常
            verify(pluginManager).loadPlugins();
            verify(pluginManager).startPlugins();
        }
    }

    // ==================== 配置读取测试 ====================

    @Nested
    @DisplayName("配置读取测试")
    class ConfigTests {

        @Test
        @DisplayName("读取插件目录配置")
        void testReadPluginsDir() {
            // Given
            when(pluginConfig.getPluginsDir()).thenReturn("/custom/plugins");
            when(pluginManager.getPlugins()).thenReturn(Collections.emptyList());
            when(pluginManager.getExtensions(GameEnhancementExtension.class))
                    .thenReturn(Collections.emptyList());

            // When
            autoLoader.run(applicationArguments);

            // Then
            verify(pluginConfig).getPluginsDir();
        }
    }

}

package com.gameplatform.plugin.listener;

import com.gameplatform.entity.PluginInfo;
import com.gameplatform.mapper.PluginInfoMapper;
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
import org.pf4j.PluginWrapper;
import org.pf4j.PluginManager;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 插件生命周期钩子测试类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("插件生命周期钩子测试")
class PluginLifecycleHookTest {

    @Mock
    private PluginManager pluginManager;

    @Mock
    private PluginInfoMapper pluginInfoMapper;

    @InjectMocks
    private PluginLifecycleHook lifecycleHook;

    private PluginWrapper testPlugin;
    private PluginDescriptor testDescriptor;
    private PluginInfo testPluginInfo;
    private GameEnhancementExtension testExtension;

    @BeforeEach
    void setUp() {
        // 手动注入 PluginManager，因为使用了 @Lazy setter 注入
        lifecycleHook.setPluginManager(pluginManager);

        // 创建测试插件描述符
        testDescriptor = mock(PluginDescriptor.class);
        when(testDescriptor.getPluginDescription()).thenReturn("测试插件");
        // 直接返回字符串
        when(testDescriptor.getVersion()).thenReturn("1.0.0");
        when(testDescriptor.getProvider()).thenReturn("GamePlatform");
        when(testDescriptor.getDependencies()).thenReturn(Collections.emptyList());

        // 创建测试插件包装器
        testPlugin = mock(PluginWrapper.class);
        when(testPlugin.getPluginId()).thenReturn("test-plugin");
        when(testPlugin.getDescriptor()).thenReturn(testDescriptor);
        when(testPlugin.getPluginPath()).thenReturn(Path.of("/plugins/test-plugin.jar"));

        // 创建测试插件信息
        testPluginInfo = new PluginInfo();
        testPluginInfo.setId(1L);
        testPluginInfo.setPluginId("test-plugin");
        testPluginInfo.setPluginName("测试插件");
        testPluginInfo.setVersion("1.0.0");
        testPluginInfo.setStatus(0);
        testPluginInfo.setRuntimeState("CREATED");

        // 创建测试扩展点
        testExtension = mock(GameEnhancementExtension.class);
        when(testExtension.getGameCode()).thenReturn("test-game");
        when(testExtension.getGameName()).thenReturn("测试游戏");
    }

    // ==================== onPluginCreate 测试 ====================

    @Nested
    @DisplayName("onPluginCreate 方法测试")
    class OnPluginCreateTests {

        @Test
        @DisplayName("插件创建钩子-成功")
        void testOnPluginCreateSuccess() {
            // Given
            when(pluginInfoMapper.selectByPluginId("test-plugin")).thenReturn(testPluginInfo);

            // When
            lifecycleHook.onPluginCreate(testPlugin);

            // Then
            verify(pluginInfoMapper).selectByPluginId("test-plugin");
            verify(pluginInfoMapper).updateRuntimeState(1L, "CREATED");
        }
    }

    // ==================== onPluginStart 测试 ====================

    @Nested
    @DisplayName("onPluginStart 方法测试")
    class OnPluginStartTests {

        @Test
        @DisplayName("插件启动钩子-成功")
        void testOnPluginStartSuccess() {
            // Given
            when(pluginInfoMapper.selectByPluginId("test-plugin")).thenReturn(testPluginInfo);
            when(pluginManager.getExtensions(GameEnhancementExtension.class, "test-plugin"))
                    .thenReturn(Collections.emptyList());

            // When
            lifecycleHook.onPluginStart(testPlugin);

            // Then
            verify(pluginInfoMapper, times(3)).selectByPluginId("test-plugin");
            verify(pluginInfoMapper).updateStatus(1L, 1);
            verify(pluginInfoMapper).updateRuntimeState(1L, "STARTED");
            verify(pluginInfoMapper).updateStartTime(eq(1L), anyString());
        }

        @Test
        @DisplayName("插件启动钩子-不再触发实例钩子（实例钩子改由实例生命周期触发）")
        void testOnPluginStartDoesNotTriggerInstanceHooks() {
            // Given
            when(pluginInfoMapper.selectByPluginId("test-plugin")).thenReturn(testPluginInfo);
            when(pluginManager.getExtensions(GameEnhancementExtension.class, "test-plugin"))
                    .thenReturn(List.of(testExtension));

            // When
            lifecycleHook.onPluginStart(testPlugin);

            // Then: onPluginStart 不再调用 getExtensions 触发实例钩子
            verify(pluginInfoMapper).updateStatus(1L, 1);
            verify(pluginInfoMapper).updateRuntimeState(1L, "STARTED");
        }
    }

    // ==================== onPluginStop 测试 ====================

    @Nested
    @DisplayName("onPluginStop 方法测试")
    class OnPluginStopTests {

        @Test
        @DisplayName("插件停止钩子-成功")
        void testOnPluginStopSuccess() {
            // Given
            when(pluginInfoMapper.selectByPluginId("test-plugin")).thenReturn(testPluginInfo);
            when(pluginManager.getExtensions(GameEnhancementExtension.class, "test-plugin"))
                    .thenReturn(Collections.emptyList());

            // When
            lifecycleHook.onPluginStop(testPlugin);

            // Then
            verify(pluginInfoMapper, times(2)).selectByPluginId("test-plugin");
            verify(pluginInfoMapper).updateStatus(1L, 0);
            verify(pluginInfoMapper).updateRuntimeState(1L, "STOPPED");
        }

        @Test
        @DisplayName("插件停止钩子-不再触发实例钩子（实例钩子改由实例生命周期触发）")
        void testOnPluginStopDoesNotTriggerInstanceHooks() {
            // Given
            when(pluginInfoMapper.selectByPluginId("test-plugin")).thenReturn(testPluginInfo);
            when(pluginManager.getExtensions(GameEnhancementExtension.class, "test-plugin"))
                    .thenReturn(List.of(testExtension));

            // When
            lifecycleHook.onPluginStop(testPlugin);

            // Then: onPluginStop 不再调用 getExtensions 触发实例钩子
            verify(pluginInfoMapper).updateStatus(1L, 0);
            verify(pluginInfoMapper).updateRuntimeState(1L, "STOPPED");
        }
    }

    // ==================== 实例生命周期钩子测试 ====================

    @Nested
    @DisplayName("实例生命周期钩子方法测试")
    class InstanceLifecycleHooksTests {

        @Test
        @DisplayName("executeInstanceCreateHooks-通知 gameCode 匹配的扩展点")
        void testExecuteInstanceCreateHooks() {
            // Given
            when(pluginManager.getExtensions(GameEnhancementExtension.class))
                    .thenReturn(List.of(testExtension));

            // When
            lifecycleHook.executeInstanceCreateHooks(1L, "test-game", Collections.emptyMap());

            // Then
            verify(pluginManager).getExtensions(GameEnhancementExtension.class);
            verify(testExtension).onInstanceCreate(eq(1L), anyMap());
        }

        @Test
        @DisplayName("executeInstanceStartHooks-通知 gameCode 匹配的扩展点")
        void testExecuteInstanceStartHooks() {
            // Given
            when(pluginManager.getExtensions(GameEnhancementExtension.class))
                    .thenReturn(List.of(testExtension));

            // When
            lifecycleHook.executeInstanceStartHooks(1L, "test-game");

            // Then
            verify(pluginManager).getExtensions(GameEnhancementExtension.class);
            verify(testExtension).onInstanceStart(1L);
        }

        @Test
        @DisplayName("executeInstanceStopHooks-通知 gameCode 匹配的扩展点")
        void testExecuteInstanceStopHooks() {
            // Given
            when(pluginManager.getExtensions(GameEnhancementExtension.class))
                    .thenReturn(List.of(testExtension));

            // When
            lifecycleHook.executeInstanceStopHooks(1L, "test-game");

            // Then
            verify(pluginManager).getExtensions(GameEnhancementExtension.class);
            verify(testExtension).onInstanceStop(1L);
        }

        @Test
        @DisplayName("executeInstanceDeleteHooks-通知 gameCode 匹配的扩展点")
        void testExecuteInstanceDeleteHooks() {
            // Given
            when(pluginManager.getExtensions(GameEnhancementExtension.class))
                    .thenReturn(List.of(testExtension));

            // When
            lifecycleHook.executeInstanceDeleteHooks(1L, "test-game");

            // Then
            verify(pluginManager).getExtensions(GameEnhancementExtension.class);
            verify(testExtension).onInstanceDelete(1L);
        }

        @Test
        @DisplayName("gameCode 不匹配时不通知扩展点")
        void testGameCodeMismatchSkipsExtension() {
            // Given
            when(pluginManager.getExtensions(GameEnhancementExtension.class))
                    .thenReturn(List.of(testExtension));

            // When: gameCode 不匹配
            lifecycleHook.executeInstanceStartHooks(1L, "other-game");

            // Then
            verify(pluginManager).getExtensions(GameEnhancementExtension.class);
            verify(testExtension, never()).onInstanceStart(anyLong());
        }
    }

    // ==================== onPluginDisable 测试 ====================

    @Nested
    @DisplayName("onPluginDisable 方法测试")
    class OnPluginDisableTests {

        @Test
        @DisplayName("插件禁用钩子-成功")
        void testOnPluginDisableSuccess() {
            // Given
            when(pluginInfoMapper.selectByPluginId("test-plugin")).thenReturn(testPluginInfo);

            // When
            lifecycleHook.onPluginDisable(testPlugin);

            // Then
            verify(pluginInfoMapper, times(2)).selectByPluginId("test-plugin");
            verify(pluginInfoMapper).updateStatus(1L, 0);
            verify(pluginInfoMapper).updateRuntimeState(1L, "DISABLED");
        }
    }

    // ==================== onPluginResolve 测试 ====================

    @Nested
    @DisplayName("onPluginResolve 方法测试")
    class OnPluginResolveTests {

        @Test
        @DisplayName("插件解析钩子-成功")
        void testOnPluginResolveSuccess() {
            // Given
            when(pluginInfoMapper.selectByPluginId("test-plugin")).thenReturn(testPluginInfo);

            // When
            lifecycleHook.onPluginResolve(testPlugin);

            // Then
            verify(pluginInfoMapper, times(2)).selectByPluginId("test-plugin");
            verify(pluginInfoMapper).updateRuntimeState(1L, "RESOLVED");
            verify(pluginInfoMapper).updateLoadTime(eq(1L), anyString());
        }
    }

}

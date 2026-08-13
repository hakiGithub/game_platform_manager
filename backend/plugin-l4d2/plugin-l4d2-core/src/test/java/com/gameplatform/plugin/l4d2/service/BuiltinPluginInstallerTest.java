package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.vo.BuiltinPluginVO;
import com.gameplatform.plugin.service.InstanceFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 内置插件清单回归测试（性能缺陷：list 对每个内置插件调用一次远程 pluginExists，
 * 60+ 条目 → 每次 ~0.2-0.7s → 总耗时 11-13s）。
 *
 * <p>锁定：list(instanceId) 只做一次远程目录列表，按集合成员判定 installed，
 * 绝不逐插件远程检查。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("内置插件清单测试")
class BuiltinPluginInstallerTest {

    @Mock
    private PluginInstallService pluginInstallService;

    @Mock
    private InstanceFileService instanceFileService;

    private BuiltinPluginInstaller installer;

    @BeforeEach
    void setUp() {
        installer = new BuiltinPluginInstaller(pluginInstallService, instanceFileService);
        installer.loadManifest();
    }

    @Test
    @DisplayName("list(instanceId) 只做一次目录列表，不逐插件远程检查")
    void listDoesNotCallPluginExistsPerPlugin() {
        when(pluginInstallService.listInstalledPluginNames(55L))
                .thenReturn(Set.of("1.11插件平台linux版"));

        List<BuiltinPluginVO> result = installer.list(55L);

        assertFalse(result.isEmpty());
        // 逐插件远程检查绝不被调用（性能缺陷回归锁）
        verify(pluginInstallService, never()).pluginExists(anyLong(), anyString());
        verify(pluginInstallService, times(1)).listInstalledPluginNames(55L);
        // installed 按集合成员标记
        BuiltinPluginVO installed = result.stream()
                .filter(vo -> "1.11插件平台linux版".equals(vo.getId()))
                .findFirst().orElseThrow();
        assertTrue(installed.getInstalled());
        long notInstalledCount = result.stream()
                .filter(vo -> !"1.11插件平台linux版".equals(vo.getId()))
                .filter(vo -> Boolean.TRUE.equals(vo.getInstalled()))
                .count();
        assertEquals(0L, notInstalledCount);
    }

    @Test
    @DisplayName("list(null) 不查安装状态（installed 为 null）")
    void listWithoutInstanceSkipsInstalled() {
        List<BuiltinPluginVO> result = installer.list(null);

        assertFalse(result.isEmpty());
        verify(pluginInstallService, never()).pluginExists(anyLong(), anyString());
        verify(pluginInstallService, never()).listInstalledPluginNames(anyLong());
        assertTrue(result.stream().allMatch(vo -> vo.getInstalled() == null));
    }
}

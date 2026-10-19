package com.gameplatform.plugin.l4d2.task;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.service.BuiltinPluginInstaller;
import com.gameplatform.plugin.l4d2.service.InstallProgressListener;
import com.gameplatform.plugin.l4d2.service.PluginInstallService;
import com.gameplatform.plugin.l4d2.vo.BuiltinPluginVO;
import com.gameplatform.plugin.task.TaskContext;
import com.gameplatform.plugin.task.TaskPayload;
import com.gameplatform.plugin.task.TaskResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内置插件安装任务处理器测试：进度桥接与协作式取消语义。
 *
 * <p>回归背景：安装链路曾无进度上报（任务详情页长时间停在 10%，
 * 表现为"卡死"），且取消标志仅在任务开始检查一次，安装中途取消无效。
 */
@ExtendWith(MockitoExtension.class)
class BuiltinPluginInstallTaskHandlerTest {

    @Mock
    private BuiltinPluginInstaller builtinPluginInstaller;

    @Mock
    private TaskContext taskContext;

    private PluginInstallService.EnableSession enableSession;

    private BuiltinPluginInstallTaskHandler handler;

    @BeforeEach
    void setUp() {
        enableSession = mock(PluginInstallService.EnableSession.class);
        handler = new BuiltinPluginInstallTaskHandler(builtinPluginInstaller);
    }

    private TaskPayload singlePayload() {
        return new TaskPayload(Map.of(
                "instanceId", "9",
                "pluginId", "sourcemod-platform",
                "pluginName", "SourceMod 1.11 + Metamod (Linux)",
                "enableAfterInstall", true));
    }

    @Test
    void 单个安装_安装链路进度应桥接到任务上下文() throws Exception {
        when(taskContext.isCancelled()).thenReturn(false);
        when(builtinPluginInstaller.install(eq(9L), eq("sourcemod-platform"), any()))
                .thenAnswer(inv -> {
                    InstallProgressListener listener = inv.getArgument(2);
                    listener.onProgress(55, "已上传 35/63 MB");
                    return "插件安装成功: SourceMod";
                });
        when(builtinPluginInstaller.enableAfterInstall(9L, "sourcemod-platform"))
                .thenReturn("插件已启用（重启服务器后生效）: sourcemod-platform");

        TaskResult result = handler.execute(taskContext, singlePayload());

        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("插件安装成功: SourceMod"));
        assertTrue(result.getMessage().contains("自动启用成功"));
        // 安装链路字节级进度直达任务上下文
        verify(taskContext).reportProgress(eq(55), contains("已上传"));
        verify(taskContext).reportProgress(eq(100), anyString());
        verify(builtinPluginInstaller).enableAfterInstall(9L, "sourcemod-platform");
    }

    @Test
    void 单个安装_显式关闭自动启用时只安装() throws Exception {
        when(taskContext.isCancelled()).thenReturn(false);
        when(builtinPluginInstaller.install(eq(9L), eq("sourcemod-platform"), any()))
                .thenReturn("插件安装成功: SourceMod");

        TaskPayload payload = new TaskPayload(Map.of(
                "instanceId", "9",
                "pluginId", "sourcemod-platform",
                "pluginName", "SourceMod 1.11 + Metamod (Linux)",
                "enableAfterInstall", false));
        TaskResult result = handler.execute(taskContext, payload);

        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("插件安装成功: SourceMod"));
        assertTrue(result.getMessage().contains("已跳过自动启用"));
        verify(builtinPluginInstaller, never()).enableAfterInstall(anyLong(), anyString());
    }

    @Test
    void 单个安装_中途取消应正常返回由框架落CANCELLED() throws Exception {
        // 取消标志在安装进行中置位：安装链路监听检查到 → 抛取消异常
        java.util.concurrent.atomic.AtomicBoolean cancelFlag =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        when(taskContext.isCancelled()).thenAnswer(inv -> cancelFlag.get());
        when(builtinPluginInstaller.install(eq(9L), eq("sourcemod-platform"), any()))
                .thenAnswer(inv -> {
                    cancelFlag.set(true); // 模拟上传中途用户点取消
                    InstallProgressListener listener = inv.getArgument(2);
                    if (listener.isCancelled()) {
                        throw new L4D2PluginException(L4D2PluginException.BUSINESS, "任务已取消，中止安装");
                    }
                    return "插件安装成功: SourceMod";
                });

        // 关键语义：不向框架抛异常（否则落 FAILED），正常返回 + ctx.isCancelled → CANCELLED
        TaskResult result = handler.execute(taskContext, singlePayload());

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("取消"));
        verify(taskContext).log(eq("WARN"), anyString());
    }

    @Test
    void 批量安装_第二项中途取消应记部分成功并注明取消() throws Exception {
        TaskPayload batchPayload = new TaskPayload(Map.of(
                "instanceId", "9",
                "pluginIds", List.of("plugin-a", "plugin-b"),
                "enableAfterInstall", true));

        // 取消标志在 plugin-b 安装进行中置位：安装链路监听器抛异常中止该项
        java.util.concurrent.atomic.AtomicBoolean cancelFlag =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        when(taskContext.isCancelled()).thenAnswer(inv -> cancelFlag.get());
        when(taskContext.isTimeout()).thenReturn(false);
        when(builtinPluginInstaller.findById("plugin-a")).thenReturn(voOf("plugin-a", "A"));
        when(builtinPluginInstaller.findById("plugin-b")).thenReturn(voOf("plugin-b", "B"));
        when(builtinPluginInstaller.install(eq(9L), eq("plugin-a"), any()))
                .thenReturn("插件安装成功: A");
        when(builtinPluginInstaller.newEnableSession(9L)).thenReturn(enableSession);
        when(enableSession.enable("plugin-a"))
                .thenReturn(new PluginInstallService.EnableTiming("plugin-a", 12L, true, null));
        when(enableSession.finish()).thenReturn(new PluginInstallService.EnableBatchResult(
                List.of(),
                List.of(new PluginInstallService.EnableTiming("plugin-a", 12L, true, null)),
                20L, 32L, 1));
        when(builtinPluginInstaller.install(eq(9L), eq("plugin-b"), any()))
                .thenAnswer(inv -> {
                    cancelFlag.set(true);
                    InstallProgressListener listener = inv.getArgument(2);
                    if (listener.isCancelled()) {
                        throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                                "任务已取消，中止安装");
                    }
                    return "插件安装成功: B";
                });

        TaskResult result = handler.execute(taskContext, batchPayload);

        assertTrue(result.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(1, data.get("success"));
        assertEquals(2, data.get("processed"));
        assertEquals(1, data.get("enabled"));
        assertEquals(0, data.get("enableFailed"));
        assertTrue(((Number) data.get("enableMs")).longValue() >= 0L);
        assertTrue(((Number) data.get("totalMs")).longValue() >= 0L);
        assertTrue(result.getMessage().contains("取消"));
        verify(builtinPluginInstaller, never()).enableAfterInstall(anyLong(), anyString());
    }

    @Test
    void 批量安装_每项落盘后立即启用且全部结束后只登记一次() throws Exception {
        when(taskContext.isCancelled()).thenReturn(false);
        when(taskContext.isTimeout()).thenReturn(false);
        when(builtinPluginInstaller.findById("plugin-a")).thenReturn(voOf("plugin-a", "A"));
        when(builtinPluginInstaller.findById("plugin-b")).thenReturn(voOf("plugin-b", "B"));
        java.util.List<String> calls = new java.util.ArrayList<>();
        when(builtinPluginInstaller.install(eq(9L), anyString(), any()))
                .thenAnswer(inv -> {
                    String id = inv.getArgument(1);
                    calls.add("install:" + id);
                    return "插件安装成功: " + id;
                });
        when(builtinPluginInstaller.newEnableSession(9L)).thenReturn(enableSession);
        when(enableSession.enable(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            calls.add("enable:" + id);
            return new PluginInstallService.EnableTiming(id, 8L, true, null);
        });
        when(enableSession.finish()).thenAnswer(inv -> {
            calls.add("finish");
            return new PluginInstallService.EnableBatchResult(
                    List.of(),
                    List.of(
                            new PluginInstallService.EnableTiming("plugin-a", 8L, true, null),
                            new PluginInstallService.EnableTiming("plugin-b", 8L, true, null)),
                    21L, 37L, 2);
        });

        TaskPayload payload = new TaskPayload(Map.of(
                "instanceId", "9",
                "pluginIds", List.of("plugin-a", "plugin-b"),
                "enableAfterInstall", true));
        TaskResult result = handler.execute(taskContext, payload);

        assertTrue(result.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(2, data.get("enabled"));
        assertEquals(0, data.get("enableFailed"));
        assertEquals(37L, ((Number) data.get("enableMs")).longValue());
        assertTrue(result.getMessage().contains("耗时"));
        assertEquals(
                List.of("install:plugin-a", "enable:plugin-a", "install:plugin-b",
                        "enable:plugin-b", "finish"),
                calls);
        verify(enableSession, times(1)).finish();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
        assertAll(
                () -> assertEquals("插件安装成功: plugin-a", results.get(0).get("message")),
                () -> assertEquals("插件安装成功: plugin-b", results.get(1).get("message")),
                () -> assertEquals(true, results.get(0).get("enabled")),
                () -> assertEquals(8L, ((Number) results.get(0).get("enableMs")).longValue()));
    }

    @Test
    void 批量安装_启用失败不改变安装成功数() throws Exception {
        when(taskContext.isCancelled()).thenReturn(false);
        when(taskContext.isTimeout()).thenReturn(false);
        when(builtinPluginInstaller.findById("plugin-a")).thenReturn(voOf("plugin-a", "A"));
        when(builtinPluginInstaller.install(eq(9L), eq("plugin-a"), any()))
                .thenReturn("插件安装成功");
        when(builtinPluginInstaller.newEnableSession(9L)).thenReturn(enableSession);
        when(enableSession.enable("plugin-a"))
                .thenReturn(new PluginInstallService.EnableTiming(
                        "plugin-a", 7L, false, "copy failed"));
        when(enableSession.finish()).thenReturn(new PluginInstallService.EnableBatchResult(
                List.of("plugin-a: copy failed"),
                List.of(new PluginInstallService.EnableTiming(
                        "plugin-a", 7L, false, "copy failed")),
                10L, 17L, 0));

        TaskResult result = handler.execute(taskContext, new TaskPayload(Map.of(
                "instanceId", "9",
                "pluginIds", List.of("plugin-a"),
                "enableAfterInstall", true)));

        assertTrue(result.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(1, data.get("success"));
        assertEquals(0, data.get("enabled"));
        assertEquals(1, data.get("enableFailed"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
        assertEquals(false, results.get(0).get("enabled"));
        assertTrue(String.valueOf(results.get(0).get("message")).contains("自动启用失败"));
    }

    @Test
    void 桥接监听_取消检查应直达任务上下文() throws Exception {
        when(taskContext.isCancelled()).thenReturn(false);
        when(builtinPluginInstaller.install(eq(9L), eq("sourcemod-platform"), any()))
                .thenAnswer(inv -> {
                    InstallProgressListener listener = inv.getArgument(2);
                    // 桥接监听的 isCancelled 必须来自 TaskContext（协作式取消链路）
                    return listener.isCancelled() ? "cancelled" : "插件安装成功: SourceMod";
                });
        when(builtinPluginInstaller.enableAfterInstall(9L, "sourcemod-platform"))
                .thenReturn("插件已启用（重启服务器后生效）: sourcemod-platform");

        TaskResult result = handler.execute(taskContext, singlePayload());
        assertTrue(result.isSuccess());
    }

    @Test
    void 任务开始前已取消_不进入安装() throws Exception {
        when(taskContext.isCancelled()).thenReturn(true);

        TaskResult result = handler.execute(taskContext, singlePayload());

        assertFalse(result.isSuccess());
        verify(builtinPluginInstaller, org.mockito.Mockito.never())
                .install(org.mockito.ArgumentMatchers.anyLong(), anyString(), any());
    }

    private BuiltinPluginVO voOf(String id, String name) {
        BuiltinPluginVO vo = new BuiltinPluginVO();
        vo.setId(id);
        vo.setName(name);
        return vo;
    }
}

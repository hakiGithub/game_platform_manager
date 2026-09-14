package com.gameplatform.plugin.l4d2.task;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.service.BuiltinPluginInstaller;
import com.gameplatform.plugin.l4d2.service.InstallProgressListener;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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

    private BuiltinPluginInstallTaskHandler handler;

    @BeforeEach
    void setUp() {
        handler = new BuiltinPluginInstallTaskHandler(builtinPluginInstaller);
    }

    private TaskPayload singlePayload() {
        return new TaskPayload(Map.of(
                "instanceId", "9",
                "pluginId", "sourcemod-platform",
                "pluginName", "SourceMod 1.11 + Metamod (Linux)"));
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

        TaskResult result = handler.execute(taskContext, singlePayload());

        assertTrue(result.isSuccess());
        assertEquals("插件安装成功: SourceMod", result.getMessage());
        // 安装链路字节级进度直达任务上下文
        verify(taskContext).reportProgress(eq(55), contains("已上传"));
        verify(taskContext).reportProgress(eq(100), anyString());
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
                "pluginIds", List.of("plugin-a", "plugin-b")));

        // 取消标志在 plugin-b 安装进行中置位（循环预检查已过、监听检查触发中止）
        java.util.concurrent.atomic.AtomicBoolean cancelFlag =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        when(taskContext.isCancelled()).thenAnswer(inv -> cancelFlag.get());
        when(taskContext.isTimeout()).thenReturn(false);
        when(builtinPluginInstaller.findById("plugin-a")).thenReturn(voOf("plugin-a", "A"));
        when(builtinPluginInstaller.findById("plugin-b")).thenReturn(voOf("plugin-b", "B"));
        when(builtinPluginInstaller.install(eq(9L), eq("plugin-a"), any()))
                .thenReturn("插件安装成功: A");
        when(builtinPluginInstaller.install(eq(9L), eq("plugin-b"), any()))
                .thenAnswer(inv -> {
                    cancelFlag.set(true); // 模拟上传中途用户点取消
                    InstallProgressListener listener = inv.getArgument(2);
                    if (listener.isCancelled()) {
                        throw new L4D2PluginException(L4D2PluginException.BUSINESS, "任务已取消，中止安装");
                    }
                    return "插件安装成功: B";
                });

        TaskResult result = handler.execute(taskContext, batchPayload);

        assertTrue(result.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(1, data.get("success"));
        assertEquals(2, data.get("processed"));
        assertTrue(result.getMessage().contains("取消"));
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

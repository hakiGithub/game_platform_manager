package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.PluginExportTaskVO;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PluginExportService 单元测试。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PluginExportServiceTest {

    @Mock
    private InstanceQueryService instanceQueryService;

    @Mock
    private InstanceFileService instanceFileService;

    private final L4D2PathResolver pathResolver = new L4D2PathResolver();

    private PluginExportService pluginExportService;

    private InstanceVO instance;

    @BeforeEach
    void setUp() {
        pluginExportService = new PluginExportService(
                instanceQueryService, instanceFileService, pathResolver);
        instance = new InstanceVO();
        instance.setId(1L);
        instance.setHostId(10L);
        instance.setInstallPath("/home/l4d2");
        // 默认 listFiles 返回空列表
        lenient().when(instanceFileService.listFiles(anyLong(), anyString()))
                .thenReturn(Collections.emptyList());
    }

    @Test
    void startExport_shouldReturnTaskIdAndCreateRunningTask() {
        when(instanceQueryService.getInstanceById(1L)).thenReturn(instance);

        String taskId = pluginExportService.startExport(1L);

        assertNotNull(taskId);
        assertFalse(taskId.isBlank());

        PluginExportTaskVO task = pluginExportService.getStatus(1L);
        assertNotNull(task);
        assertEquals(taskId, task.getTaskId());
        assertEquals(1L, task.getInstanceId());
        assertNotNull(task.getStartedAt());
        // 状态可能是 RUNNING 或已转换为 COMPLETED（异步任务完成）
        assertTrue(task.getStatus().equals(PluginExportService.STATUS_RUNNING)
                || task.getStatus().equals(PluginExportService.STATUS_COMPLETED)
                || task.getStatus().equals(PluginExportService.STATUS_FAILED));
    }

    @Test
    void startExport_shouldRejectDuplicateRunningTask() throws Exception {
        // 让 getInstanceById 阻塞，确保任务保持 RUNNING
        AtomicBoolean keepBlocking = new AtomicBoolean(true);
        when(instanceQueryService.getInstanceById(1L)).thenAnswer(invocation -> {
            long deadline = System.currentTimeMillis() + 5000;
            while (keepBlocking.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            return instance;
        });

        String taskId = pluginExportService.startExport(1L);
        assertNotNull(taskId);

        // 再次启动应抛异常
        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> pluginExportService.startExport(1L));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());

        // 释放阻塞
        keepBlocking.set(false);
        waitForTerminalStatus(1L, 3000);
    }

    @Test
    void cancel_shouldMarkRunningTaskAsCancelled() throws Exception {
        // 让 getInstanceById 阻塞，确保 cancel 时任务仍 RUNNING
        AtomicBoolean keepBlocking = new AtomicBoolean(true);
        when(instanceQueryService.getInstanceById(1L)).thenAnswer(invocation -> {
            long deadline = System.currentTimeMillis() + 5000;
            while (keepBlocking.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            return null;  // 返回 null 触发失败路径
        });

        pluginExportService.startExport(1L);

        // 立即取消
        pluginExportService.cancel(1L);

        // 释放阻塞
        keepBlocking.set(false);

        // 等待异步任务结束
        PluginExportTaskVO task = waitForTerminalStatus(1L, 3000);
        assertNotNull(task);
        assertEquals(PluginExportService.STATUS_CANCELLED, task.getStatus(),
                "取消后状态应为 CANCELLED，实际: " + task.getStatus());
        assertNotNull(task.getFinishedAt());
    }

    @Test
    void cancel_shouldBeNoOpWhenTaskNotExists() {
        // 不存在的实例，调用 cancel 不应抛异常
        assertDoesNotThrow(() -> pluginExportService.cancel(999L));
    }

    @Test
    void download_shouldThrowWhenTaskNotExists() {
        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> pluginExportService.download(999L));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
    }

    @Test
    void download_shouldThrowWhenTaskNotCompleted() throws Exception {
        // 让 getInstanceById 阻塞
        AtomicBoolean keepBlocking = new AtomicBoolean(true);
        when(instanceQueryService.getInstanceById(1L)).thenAnswer(invocation -> {
            long deadline = System.currentTimeMillis() + 5000;
            while (keepBlocking.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            return null;
        });

        pluginExportService.startExport(1L);

        // 任务运行中，download 应抛异常
        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> pluginExportService.download(1L));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());

        keepBlocking.set(false);
        waitForTerminalStatus(1L, 3000);
    }

    @Test
    void startExport_shouldReachFailedWhenInstanceNull() throws Exception {
        when(instanceQueryService.getInstanceById(1L)).thenReturn(null);

        pluginExportService.startExport(1L);

        PluginExportTaskVO task = waitForTerminalStatus(1L, 3000);
        assertNotNull(task);
        assertEquals(PluginExportService.STATUS_FAILED, task.getStatus());
        assertNotNull(task.getError());
        assertNotNull(task.getFinishedAt());
    }

    @Test
    void startExport_shouldReachCompletedWhenNoFiles() throws Exception {
        when(instanceQueryService.getInstanceById(1L)).thenReturn(instance);
        // listFiles 默认返回空列表（见 setUp）

        pluginExportService.startExport(1L);

        PluginExportTaskVO task = waitForTerminalStatus(1L, 5000);
        assertNotNull(task);
        assertEquals(PluginExportService.STATUS_COMPLETED, task.getStatus());
        assertEquals(0, task.getTotalFiles());
        assertNotNull(task.getDownloadUrl());
    }

    /**
     * 轮询等待任务进入终态（非 RUNNING）。
     */
    private PluginExportTaskVO waitForTerminalStatus(Long instanceId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            PluginExportTaskVO task = pluginExportService.getStatus(instanceId);
            if (task != null && !PluginExportService.STATUS_RUNNING.equals(task.getStatus())) {
                return task;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        return pluginExportService.getStatus(instanceId);
    }
}

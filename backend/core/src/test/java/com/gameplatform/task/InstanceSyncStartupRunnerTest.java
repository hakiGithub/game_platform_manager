package com.gameplatform.task;

import com.gameplatform.config.InstanceSyncProperties;
import com.gameplatform.service.sync.InstanceSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * InstanceSyncStartupRunner 单元测试
 * 覆盖：禁用、启用、异常隔离、延迟参数
 *
 * <p>注意：triggerAsyncSync 上的 @Async 不会在单元测试生效（无 Spring 代理），
 * 直接调用方法可同步执行，便于验证。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
class InstanceSyncStartupRunnerTest {

    @Mock
    private InstanceSyncService syncService;

    private InstanceSyncProperties properties;
    private InstanceSyncStartupRunner runner;

    @BeforeEach
    void setUp() {
        properties = new InstanceSyncProperties();
        properties.setEnabled(true);
        properties.setStartupSyncDelayMs(0L); // 测试中不延迟
        runner = new InstanceSyncStartupRunner(syncService, properties);
    }

    @Test
    void run_disabled_skipsSync() {
        properties.setEnabled(false);

        runner.run(new DefaultApplicationArguments());

        verifyNoInteractions(syncService);
    }

    @Test
    void run_enabled_triggersAsyncSync() {
        when(syncService.syncAll()).thenReturn(new InstanceSyncService.SyncSummary(1, 1, 0, 1));

        runner.run(new DefaultApplicationArguments());

        // triggerAsyncSync 在测试中被直接同步调用（@Async 不生效）
        verify(syncService).syncAll();
    }

    @Test
    void triggerAsyncSync_syncServiceThrows_doesNotPropagate() {
        when(syncService.syncAll()).thenThrow(new RuntimeException("DB error"));

        // 不应抛出异常
        runner.triggerAsyncSync(0L);

        verify(syncService).syncAll();
    }

    @Test
    void triggerAsyncSync_interrupted_setsInterruptFlag() {
        // 模拟 sleep 被中断
        Thread current = Thread.currentThread();
        Thread interrupter = new Thread(() -> {
            try {
                Thread.sleep(50);
                current.interrupt();
            } catch (InterruptedException e) {
                // ignore
            }
        });
        interrupter.start();

        runner.triggerAsyncSync(5000L); // 长延迟，会被中断

        // 不应抛出异常，且中断标志被设置
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        // 清除中断标志以避免影响后续测试
        Thread.interrupted();
        verifyNoInteractions(syncService);
    }

    @Test
    void triggerAsyncSync_zeroDelay_callsSyncImmediately() {
        when(syncService.syncAll()).thenReturn(InstanceSyncService.SyncSummary.empty());

        runner.triggerAsyncSync(0L);

        verify(syncService).syncAll();
    }

    @Test
    void triggerAsyncSync_negativeDelay_callsSyncImmediately() {
        when(syncService.syncAll()).thenReturn(InstanceSyncService.SyncSummary.empty());

        runner.triggerAsyncSync(-1L);

        verify(syncService).syncAll();
    }

    @Test
    void run_withDelay_logsScheduledSync() {
        properties.setStartupSyncDelayMs(100L);

        // 这里仅验证 run 方法本身不抛异常，且不会立即调用 syncAll（@Async 代理时为异步）
        // 由于测试中 @Async 不生效，triggerAsyncSync 会被同步调用
        // 但 100ms 延迟会被 sleep
        when(syncService.syncAll()).thenReturn(InstanceSyncService.SyncSummary.empty());

        runner.run(new DefaultApplicationArguments());

        verify(syncService).syncAll();
    }
}

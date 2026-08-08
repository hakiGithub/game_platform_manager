package com.gameplatform.task;

import com.gameplatform.service.HostService;
import com.gameplatform.service.sync.InstanceSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * HostMonitorTask 单元测试
 * 验证主机状态刷新 + 实例状态同步的编排逻辑和异常隔离
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
class HostMonitorTaskTest {

    @Mock
    private HostService hostService;

    @Mock
    private InstanceSyncService instanceSyncService;

    private HostMonitorTask task;

    @BeforeEach
    void setUp() {
        task = new HostMonitorTask(hostService, instanceSyncService);
    }

    @Test
    void refreshHostsStatus_bothSucceed_callsBoth() {
        when(instanceSyncService.syncAll())
                .thenReturn(new InstanceSyncService.SyncSummary(2, 2, 0, 3));

        task.refreshHostsStatus();

        verify(hostService).refreshAllHostsStatus();
        verify(instanceSyncService).syncAll();
    }

    @Test
    void refreshHostsStatus_hostServiceThrows_stillCallsInstanceSync() {
        doThrow(new RuntimeException("host refresh failed"))
                .when(hostService).refreshAllHostsStatus();
        when(instanceSyncService.syncAll())
                .thenReturn(InstanceSyncService.SyncSummary.empty());

        task.refreshHostsStatus();

        verify(hostService).refreshAllHostsStatus();
        verify(instanceSyncService).syncAll();
    }

    @Test
    void refreshHostsStatus_instanceSyncThrows_doesNotAffectHostRefresh() {
        // hostService 已成功执行
        doNothing().when(hostService).refreshAllHostsStatus();
        when(instanceSyncService.syncAll())
                .thenThrow(new RuntimeException("sync failed"));

        task.refreshHostsStatus();

        verify(hostService).refreshAllHostsStatus();
        verify(instanceSyncService).syncAll();
    }

    @Test
    void refreshHostsStatus_bothThrow_doesNotPropagate() {
        doThrow(new RuntimeException("host refresh failed"))
                .when(hostService).refreshAllHostsStatus();
        when(instanceSyncService.syncAll())
                .thenThrow(new RuntimeException("sync failed"));

        // 不应抛出异常
        task.refreshHostsStatus();

        verify(hostService).refreshAllHostsStatus();
        verify(instanceSyncService).syncAll();
    }

    @Test
    void refreshHostsStatus_emptySummary_doesNotLogSummary() {
        when(instanceSyncService.syncAll())
                .thenReturn(InstanceSyncService.SyncSummary.empty());

        task.refreshHostsStatus();

        verify(instanceSyncService).syncAll();
    }
}

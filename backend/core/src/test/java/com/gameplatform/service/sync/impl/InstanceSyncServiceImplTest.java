package com.gameplatform.service.sync.impl;

import com.gameplatform.config.InstanceSyncProperties;
import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.service.sync.DockerInstanceSyncStrategy;
import com.gameplatform.service.sync.InstanceSyncService;
import com.gameplatform.service.sync.NativeInstanceSyncStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * InstanceSyncServiceImpl 单元测试
 * 覆盖：禁用、空主机、主机异常隔离、Docker/Native 分组调度、变更统计
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
class InstanceSyncServiceImplTest {

    @Mock
    private HostMapper hostMapper;

    @Mock
    private GameInstanceMapper instanceMapper;

    @Mock
    private DockerInstanceSyncStrategy dockerStrategy;

    @Mock
    private NativeInstanceSyncStrategy nativeStrategy;

    @Mock
    private DeploymentAccess deployAccess;

    private InstanceSyncProperties properties;
    private InstanceSyncServiceImpl service;

    private Host host1;
    private Host host2;

    @BeforeEach
    void setUp() {
        properties = new InstanceSyncProperties();
        properties.setEnabled(true);
        service = new InstanceSyncServiceImpl(hostMapper, instanceMapper,
                dockerStrategy, nativeStrategy, properties, deployAccess);

        // classify 语义由 DeploymentAccessTest 锁定；此处按真实语义模拟 isNativeDeploy
        lenient().when(deployAccess.isNativeDeploy(any())).thenAnswer(inv -> {
            String t = inv.getArgument(0);
            return t == null || !List.of("docker", "docker-compose", "linuxgsm-docker").contains(t);
        });

        host1 = new Host();
        host1.setId(1L);
        host1.setHostName("host1");
        host1.setIpAddress("10.0.0.1");

        host2 = new Host();
        host2.setId(2L);
        host2.setHostName("host2");
        host2.setIpAddress("10.0.0.2");
    }

    private GameInstance instance(Long id, String deployType, int runStatus) {
        GameInstance i = new GameInstance();
        i.setId(id);
        i.setDeployType(deployType);
        i.setRunStatus(runStatus);
        i.setInstanceName("inst-" + id);
        return i;
    }

    @Test
    void syncAll_disabled_returnsEmpty() {
        properties.setEnabled(false);

        InstanceSyncService.SyncSummary summary = service.syncAll();

        assertThat(summary).isEqualTo(InstanceSyncService.SyncSummary.empty());
        verifyNoInteractions(hostMapper, instanceMapper, dockerStrategy, nativeStrategy);
    }

    @Test
    void syncAll_noOnlineHosts_returnsEmpty() {
        when(hostMapper.selectOnlineHosts()).thenReturn(List.of());

        InstanceSyncService.SyncSummary summary = service.syncAll();

        assertThat(summary.totalHosts()).isEqualTo(0);
        verify(instanceMapper, never()).selectByHostId(anyLong());
    }

    @Test
    void syncAll_nullOnlineHosts_returnsEmpty() {
        when(hostMapper.selectOnlineHosts()).thenReturn(null);

        InstanceSyncService.SyncSummary summary = service.syncAll();

        assertThat(summary).isEqualTo(InstanceSyncService.SyncSummary.empty());
    }

    @Test
    void syncAll_selectOnlineHostsThrows_returnsEmpty() {
        when(hostMapper.selectOnlineHosts()).thenThrow(new RuntimeException("DB down"));

        InstanceSyncService.SyncSummary summary = service.syncAll();

        assertThat(summary).isEqualTo(InstanceSyncService.SyncSummary.empty());
    }

    @Test
    void syncAll_singleHostWithDockerInstances_callsDockerStrategy() {
        when(hostMapper.selectOnlineHosts()).thenReturn(List.of(host1));
        List<GameInstance> dockerInstances = new ArrayList<>(List.of(instance(100L, "docker", 0)));
        when(instanceMapper.selectByHostIdAndDeployTypes(eq(1L), anyList())).thenReturn(dockerInstances);
        when(instanceMapper.selectByHostId(1L)).thenReturn(dockerInstances); // 全部实例也是 docker

        // 模拟策略修改 run_status：0 → 1
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<GameInstance> list = inv.getArgument(1);
            for (GameInstance i : list) {
                i.setRunStatus(1); // 模拟同步后变为 RUNNING
            }
            return null;
        }).when(dockerStrategy).syncHost(eq(host1), anyList());

        InstanceSyncService.SyncSummary summary = service.syncAll();

        assertThat(summary.totalHosts()).isEqualTo(1);
        assertThat(summary.successHosts()).isEqualTo(1);
        assertThat(summary.failedHosts()).isEqualTo(0);
        assertThat(summary.totalUpdated()).isEqualTo(1);
    }

    @Test
    void syncAll_singleHostWithNativeInstances_callsNativeStrategy() {
        when(hostMapper.selectOnlineHosts()).thenReturn(List.of(host1));
        List<GameInstance> nativeInstances = List.of(instance(100L, "linuxgsm", 1));
        when(instanceMapper.selectByHostIdAndDeployTypes(eq(1L), anyList())).thenReturn(List.of());
        when(instanceMapper.selectByHostId(1L)).thenReturn(nativeInstances);

        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<GameInstance> list = inv.getArgument(1);
            for (GameInstance i : list) {
                i.setRunStatus(0); // 模拟同步后变为 STOPPED
            }
            return null;
        }).when(nativeStrategy).syncHost(eq(host1), anyList());

        InstanceSyncService.SyncSummary summary = service.syncAll();

        assertThat(summary.totalHosts()).isEqualTo(1);
        assertThat(summary.totalUpdated()).isEqualTo(1);
    }

    @Test
    void syncAll_dockerAndNativeMixed_bothStrategiesCalled() {
        when(hostMapper.selectOnlineHosts()).thenReturn(List.of(host1));
        GameInstance dockerInst = instance(100L, "docker", 0);
        GameInstance nativeInst = instance(200L, "linuxgsm", 1);
        when(instanceMapper.selectByHostIdAndDeployTypes(eq(1L), anyList()))
                .thenReturn(new ArrayList<>(List.of(dockerInst)));
        when(instanceMapper.selectByHostId(1L))
                .thenReturn(new ArrayList<>(List.of(dockerInst, nativeInst)));

        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<GameInstance> list = inv.getArgument(1);
            for (GameInstance i : list) {
                i.setRunStatus(1);
            }
            return null;
        }).when(dockerStrategy).syncHost(eq(host1), anyList());

        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<GameInstance> list = inv.getArgument(1);
            for (GameInstance i : list) {
                i.setRunStatus(0);
            }
            return null;
        }).when(nativeStrategy).syncHost(eq(host1), anyList());

        InstanceSyncService.SyncSummary summary = service.syncAll();

        assertThat(summary.totalUpdated()).isEqualTo(2);
        verify(dockerStrategy).syncHost(eq(host1), anyList());
        verify(nativeStrategy).syncHost(eq(host1), anyList());
    }

    @Test
    void syncAll_multipleHosts_oneFailsOthersContinue() {
        when(hostMapper.selectOnlineHosts()).thenReturn(List.of(host1, host2));
        // host1 查询实例时抛异常，host2 正常
        when(instanceMapper.selectByHostIdAndDeployTypes(eq(1L), anyList()))
                .thenThrow(new RuntimeException("DB error on host1"));
        when(instanceMapper.selectByHostId(1L))
                .thenThrow(new RuntimeException("DB error on host1"));
        when(instanceMapper.selectByHostIdAndDeployTypes(eq(2L), anyList()))
                .thenReturn(List.of());
        when(instanceMapper.selectByHostId(2L))
                .thenReturn(List.of());

        InstanceSyncService.SyncSummary summary = service.syncAll();

        // host1 在 selectByHostIdAndDeployTypes 抛异常时被 catch，dockerInstances=[]，然后 selectByHostId 也抛异常，被 catch，nativeInstances=[]
        // 所以 host1 实际不算失败（异常被 catch 后转为空列表），仍然记为成功
        assertThat(summary.successHosts()).isEqualTo(2);
        assertThat(summary.failedHosts()).isEqualTo(0);
    }

    @Test
    void syncAll_multipleHosts_strategyThrows_countsAsFailed() {
        when(hostMapper.selectOnlineHosts()).thenReturn(List.of(host1, host2));
        // host1 实例存在但策略抛异常
        when(instanceMapper.selectByHostIdAndDeployTypes(eq(1L), anyList()))
                .thenReturn(new ArrayList<>(List.of(instance(100L, "docker", 0))));
        when(instanceMapper.selectByHostId(1L))
                .thenReturn(new ArrayList<>(List.of(instance(100L, "docker", 0))));
        doThrow(new RuntimeException("SSH timeout"))
                .when(dockerStrategy).syncHost(eq(host1), anyList());

        // host2 无实例
        when(instanceMapper.selectByHostIdAndDeployTypes(eq(2L), anyList()))
                .thenReturn(List.of());
        when(instanceMapper.selectByHostId(2L))
                .thenReturn(List.of());

        InstanceSyncService.SyncSummary summary = service.syncAll();

        assertThat(summary.totalHosts()).isEqualTo(2);
        assertThat(summary.successHosts()).isEqualTo(1); // host2 成功
        assertThat(summary.failedHosts()).isEqualTo(1); // host1 失败
    }

    @Test
    void syncHost_nullHost_returnsZero() {
        int updated = service.syncHost(null);
        assertThat(updated).isEqualTo(0);
    }

    @Test
    void syncHost_hostWithoutId_returnsZero() {
        Host h = new Host();
        int updated = service.syncHost(h);
        assertThat(updated).isEqualTo(0);
    }

    @Test
    void syncHost_noInstances_returnsZero() {
        when(instanceMapper.selectByHostIdAndDeployTypes(eq(1L), anyList())).thenReturn(List.of());
        when(instanceMapper.selectByHostId(1L)).thenReturn(List.of());

        int updated = service.syncHost(host1);

        assertThat(updated).isEqualTo(0);
        verify(dockerStrategy, never()).syncHost(any(), anyList());
        verify(nativeStrategy, never()).syncHost(any(), anyList());
    }

    @Test
    void syncHost_statusUnchanged_returnsZero() {
        GameInstance dockerInst = instance(100L, "docker", 1); // 同步前 RUNNING
        when(instanceMapper.selectByHostIdAndDeployTypes(eq(1L), anyList()))
                .thenReturn(new ArrayList<>(List.of(dockerInst)));
        when(instanceMapper.selectByHostId(1L))
                .thenReturn(new ArrayList<>(List.of(dockerInst)));

        // 策略不修改 run_status（认为状态未变化）
        doNothing().when(dockerStrategy).syncHost(eq(host1), anyList());

        int updated = service.syncHost(host1);
        assertThat(updated).isEqualTo(0);
    }

    @Test
    void syncHost_dockerQueryFails_fallsBackToEmptyDockerList() {
        when(instanceMapper.selectByHostIdAndDeployTypes(eq(1L), anyList()))
                .thenThrow(new RuntimeException("query failed"));
        when(instanceMapper.selectByHostId(1L))
                .thenReturn(new ArrayList<>(List.of(instance(200L, "linuxgsm", 1))));

        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<GameInstance> list = inv.getArgument(1);
            for (GameInstance i : list) {
                i.setRunStatus(0);
            }
            return null;
        }).when(nativeStrategy).syncHost(eq(host1), anyList());

        int updated = service.syncHost(host1);

        assertThat(updated).isEqualTo(1); // Native 实例状态变更
        verify(dockerStrategy, never()).syncHost(any(), anyList());
    }

    @Test
    void syncHost_linuxgsmDockerClassifiedAsDocker() {
        GameInstance linuxgsmDocker = instance(100L, "linuxgsm-docker", 0);
        when(instanceMapper.selectByHostIdAndDeployTypes(eq(1L), anyList()))
                .thenReturn(new ArrayList<>(List.of(linuxgsmDocker)));
        when(instanceMapper.selectByHostId(1L))
                .thenReturn(new ArrayList<>(List.of(linuxgsmDocker)));

        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<GameInstance> list = inv.getArgument(1);
            for (GameInstance i : list) {
                i.setRunStatus(1);
            }
            return null;
        }).when(dockerStrategy).syncHost(eq(host1), anyList());

        int updated = service.syncHost(host1);

        assertThat(updated).isEqualTo(1);
        verify(dockerStrategy).syncHost(eq(host1), anyList());
        verify(nativeStrategy, never()).syncHost(any(), anyList());
    }

    @Test
    void syncHost_composeClassifiedAsDocker() {
        GameInstance composeInst = instance(100L, "docker-compose", 0);
        when(instanceMapper.selectByHostIdAndDeployTypes(eq(1L), anyList()))
                .thenReturn(new ArrayList<>(List.of(composeInst)));
        when(instanceMapper.selectByHostId(1L))
                .thenReturn(new ArrayList<>(List.of(composeInst)));

        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<GameInstance> list = inv.getArgument(1);
            for (GameInstance i : list) {
                i.setRunStatus(1);
            }
            return null;
        }).when(dockerStrategy).syncHost(eq(host1), anyList());

        int updated = service.syncHost(host1);

        assertThat(updated).isEqualTo(1);
        verify(dockerStrategy).syncHost(eq(host1), anyList());
    }
}

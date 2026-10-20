package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.vo.SystemMetricVO;
import com.gameplatform.plugin.service.HostQueryService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.HostResourceVO;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitorCollectorServiceTest {

    @Mock private InstanceQueryService instanceQueryService;
    @Mock private HostQueryService hostQueryService;
    @Mock private MonitorMetricFileStore metricFileStore;
    @Mock private ApplicationContext pluginContext;

    private L4D2Config config;
    private MonitorCollectorService service;

    @BeforeEach
    void setUp() {
        config = new L4D2Config();
        config.getMonitor().setCollectEnabled(true);
        config.getMonitor().setHistoryEnabled(true);
        config.getMonitor().setCollectIntervalMs(3_600_000L);
        config.getMonitor().setRetentionMs(3L * 24 * 3600 * 1000);
        service = new MonitorCollectorService(instanceQueryService, hostQueryService,
                metricFileStore, config, pluginContext);
    }

    @Test
    void collect_metrics_appends_for_each_instance() {
        when(instanceQueryService.listByGameCode("l4d2"))
                .thenReturn(List.of(buildInstance(1L, 10L), buildInstance(2L, 20L)));
        when(hostQueryService.getHostResourceInfo(10L)).thenReturn(buildHostResource(10.0));
        when(hostQueryService.getHostResourceInfo(20L)).thenReturn(buildHostResource(20.0));

        service.collectMetrics();

        verify(instanceQueryService).listByGameCode("l4d2");
        verify(instanceQueryService, never()).getInstancesByGameId(anyLong());
        verify(metricFileStore, times(2)).append(anyLong(), any(SystemMetricVO.class));
        assertNotNull(service.getLatestMetric(1L));
        assertNotNull(service.getLatestMetric(2L));
    }

    @Test
    void collect_metrics_skips_failed_instance() {
        when(instanceQueryService.listByGameCode("l4d2"))
                .thenReturn(List.of(buildInstance(1L, 10L), buildInstance(2L, 20L)));
        when(hostQueryService.getHostResourceInfo(10L)).thenThrow(new RuntimeException("down"));
        when(hostQueryService.getHostResourceInfo(20L)).thenReturn(buildHostResource(20.0));

        service.collectMetrics();

        verify(metricFileStore, times(1)).append(eq(2L), any(SystemMetricVO.class));
        assertNull(service.getLatestMetric(1L));
        assertNotNull(service.getLatestMetric(2L));
    }

    @Test
    void collect_metrics_disabled_skips_all() {
        config.getMonitor().setCollectEnabled(false);

        service.collectMetrics();

        verify(metricFileStore, never()).append(anyLong(), any());
        verify(hostQueryService, never()).getHostResourceInfo(anyLong());
    }

    @Test
    void history_disabled_keeps_latest_without_file_append() {
        config.getMonitor().setHistoryEnabled(false);
        when(instanceQueryService.listByGameCode("l4d2"))
                .thenReturn(List.of(buildInstance(1L, 10L)));
        when(hostQueryService.getHostResourceInfo(10L)).thenReturn(buildHostResource(10.0));

        service.collectMetrics();

        verify(metricFileStore, never()).append(anyLong(), any());
        assertNotNull(service.getLatestMetric(1L));
    }

    @Test
    void get_latest_metric_falls_back_to_file_store() {
        SystemMetricVO stored = new SystemMetricVO();
        stored.setTimestamp(123L);
        when(metricFileStore.latest(1L)).thenReturn(stored);

        assertSame(stored, service.getLatestMetric(1L));
        verify(metricFileStore).latest(1L);
    }

    @Test
    void cleanup_delegates_to_file_store() {
        when(metricFileStore.cleanupExpired()).thenReturn(2);

        service.cleanupExpired();

        verify(metricFileStore).cleanupExpired();
    }

    @Test
    void delete_instance_clears_cache_and_files() {
        when(instanceQueryService.listByGameCode("l4d2"))
                .thenReturn(List.of(buildInstance(1L, 10L)));
        when(hostQueryService.getHostResourceInfo(10L)).thenReturn(buildHostResource(10.0));
        service.collectMetrics();

        service.deleteInstance(1L);

        verify(metricFileStore).deleteInstance(1L);
        assertNull(service.getLatestMetric(1L));
    }

    @Test
    void plugin_lifecycle_delete_delegates_to_context_bean() {
        when(pluginContext.getBean(MonitorCollectorService.class)).thenReturn(service);

        MonitorCollectorService.deleteInstanceFromPluginLifecycle(7L);

        verify(metricFileStore).deleteInstance(7L);
    }

    @Test
    void scheduling_start_is_idempotent_and_stop_is_idempotent() {
        service.startScheduling();
        service.startScheduling();

        assertTrue(service.isScheduling());

        service.stopScheduling();
        service.stopScheduling();

        assertFalse(service.isScheduling());
    }

    @Test
    void plugin_lifecycle_starts_and_stops_scheduling() {
        when(pluginContext.getBean(MonitorCollectorService.class)).thenReturn(service);

        MonitorCollectorService.startSchedulingFromPluginLifecycle();
        assertTrue(service.isScheduling());

        MonitorCollectorService.stopSchedulingFromPluginLifecycle();
        assertFalse(service.isScheduling());
    }

    @Test
    void set_collect_enabled_updates_flag() {
        service.setCollectEnabled(false);
        assertFalse(service.isCollectEnabled());
        service.setCollectEnabled(true);
        assertTrue(service.isCollectEnabled());
    }

    private InstanceVO buildInstance(Long id, Long hostId) {
        InstanceVO vo = new InstanceVO();
        vo.setId(id);
        vo.setHostId(hostId);
        vo.setGameId(1L);
        vo.setGameCode("l4d2");
        return vo;
    }

    private HostResourceVO buildHostResource(double cpuUsage) {
        HostResourceVO r = new HostResourceVO();
        HostResourceVO.CpuInfo cpu = new HostResourceVO.CpuInfo();
        cpu.setUsage(cpuUsage);
        r.setCpu(cpu);
        HostResourceVO.MemoryInfo mem = new HostResourceVO.MemoryInfo();
        mem.setUsed(100L);
        mem.setTotal(200L);
        r.setMemory(mem);
        HostResourceVO.DiskInfo disk = new HostResourceVO.DiskInfo();
        disk.setUsed(50L);
        disk.setTotal(100L);
        r.setDisk(disk);
        HostResourceVO.NetworkInfo net = new HostResourceVO.NetworkInfo();
        net.setRxBytes(1024L);
        net.setTxBytes(2048L);
        r.setNetwork(net);
        return r;
    }
}

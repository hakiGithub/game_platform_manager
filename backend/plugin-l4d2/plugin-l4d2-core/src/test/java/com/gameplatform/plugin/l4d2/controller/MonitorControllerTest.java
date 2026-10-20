package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.dto.MonitorHistoryQueryDTO;
import com.gameplatform.plugin.l4d2.service.MonitorCollectorService;
import com.gameplatform.plugin.l4d2.service.MonitorMetricFileStore;
import com.gameplatform.plugin.l4d2.vo.MonitorConfigVO;
import com.gameplatform.plugin.l4d2.vo.SystemMetricVO;
import com.gameplatform.plugin.service.HostQueryService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitorControllerTest {

    @Mock private InstanceQueryService instanceQueryService;
    @Mock private HostQueryService hostQueryService;
    @Mock private MonitorCollectorService monitorCollectorService;
    @Mock private MonitorMetricFileStore metricFileStore;

    private L4D2Config config;
    private MonitorController controller;

    @BeforeEach
    void setUp() {
        config = new L4D2Config();
        config.getMonitor().setCollectEnabled(true);
        config.getMonitor().setHistoryEnabled(true);
        config.getMonitor().setMaxPoints(2000);
        config.getMonitor().setDownsampleTo(720);
        config.getMonitor().setRetentionMs(3L * 24 * 3600 * 1000);
        config.getMonitor().setCollectIntervalMs(1000L);

        controller = new MonitorController(instanceQueryService, hostQueryService,
                monitorCollectorService, metricFileStore, config);

        InstanceVO instance = new InstanceVO();
        instance.setId(1L);
        instance.setHostId(10L);
        when(instanceQueryService.getInstanceById(1L)).thenReturn(instance);
        when(monitorCollectorService.getLatestMetric(anyLong())).thenReturn(null);
        when(monitorCollectorService.isCollectEnabled()).thenReturn(true);
    }

    @Test
    void history_uses_file_store() {
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusMinutes(60);
        List<SystemMetricVO> points = List.of(buildMetric(start.toInstant(ZoneId.systemDefault().getRules().getOffset(start)).toEpochMilli(), 12.0));
        when(metricFileStore.query(eq(1L), anyLong(), anyLong())).thenReturn(points);

        MonitorHistoryQueryDTO dto = new MonitorHistoryQueryDTO();
        dto.setInstanceId(1L);
        dto.setStartTime(start);
        dto.setEndTime(end);

        Result<List<SystemMetricVO>> result = controller.getHistory(dto);

        assertNotNull(result.getData());
        assertEquals(1, result.getData().size());
        verify(metricFileStore).query(eq(1L), anyLong(), anyLong());
    }

    @Test
    void get_config_returns_current_settings() {
        Result<MonitorConfigVO> result = controller.getConfig();

        MonitorConfigVO vo = result.getData();
        assertTrue(vo.isHistoryEnabled());
        assertEquals(1000L, vo.getCollectIntervalMs());
        assertEquals(3L * 24 * 3600 * 1000, vo.getRetentionMs());
        assertEquals(2000, vo.getMaxPoints());
        assertEquals(720, vo.getDownsampleTo());
        assertTrue(vo.isCollectEnabled());
    }

    @Test
    void set_config_updates_enabled_flag() {
        Map<String, Object> body = new HashMap<>();
        body.put("enable", false);

        controller.updateConfig(body);

        verify(monitorCollectorService).setCollectEnabled(false);
    }

    @Test
    void set_config_missing_enable_returns_fail() {
        controller.updateConfig(new HashMap<>());

        verify(monitorCollectorService, never()).setCollectEnabled(any(Boolean.class));
    }

    @Test
    void status_returns_from_cache() {
        SystemMetricVO cached = buildMetric(System.currentTimeMillis(), 42.0);
        when(monitorCollectorService.getLatestMetric(1L)).thenReturn(cached);

        Result<?> result = controller.getStatus(1L);

        assertNotNull(result.getData());
        verify(hostQueryService, never()).getHostResourceInfo(anyLong());
        verify(monitorCollectorService, never()).persistMetric(anyLong(), any());
    }

    private SystemMetricVO buildMetric(Long timestamp, Double cpu) {
        SystemMetricVO metric = new SystemMetricVO();
        metric.setTimestamp(timestamp);
        metric.setCpuPercent(cpu);
        metric.setCpuMaxCore(cpu);
        metric.setMemUsed(4.0);
        metric.setMemTotal(16.0);
        metric.setSwapUsed(0.0);
        metric.setNetUpSpeed(10.0);
        metric.setNetDownSpeed(20.0);
        metric.setDiskUsed(50.0);
        metric.setDiskTotal(100.0);
        return metric;
    }
}

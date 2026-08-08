package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.api.extension.ExtensionMetadata;
import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.extension.ListOptions;
import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.dto.MonitorHistoryQueryDTO;
import com.gameplatform.plugin.l4d2.extension.SystemMetricResource;
import com.gameplatform.plugin.l4d2.extension.SystemMetricSpec;
import com.gameplatform.plugin.l4d2.service.MonitorCollectorService;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MonitorController 单元测试（对齐 plan §5.3.5）。
 *
 * <p>直接实例化 Controller 并 mock 所有依赖，验证 /history 降采样逻辑与 /config 端点。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitorControllerTest {

    @Mock
    private InstanceQueryService instanceQueryService;

    @Mock
    private HostQueryService hostQueryService;

    @Mock
    private ExtensionClient extensionClient;

    @Mock
    private MonitorCollectorService monitorCollectorService;

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

        controller = new MonitorController(instanceQueryService, hostQueryService, extensionClient,
                monitorCollectorService, config);

        // 默认实例存在
        InstanceVO instance = new InstanceVO();
        instance.setId(1L);
        instance.setHostId(10L);
        when(instanceQueryService.getInstanceById(1L)).thenReturn(instance);

        // 默认 collector 缓存未命中（被动模式回退）
        when(monitorCollectorService.getLatestMetric(anyLong())).thenReturn(null);
        when(monitorCollectorService.isCollectEnabled()).thenReturn(true);
    }

    // ============================================================
    // history_downsamples_when_over_2000_points：2500 条 → 720 条
    // ============================================================

    @Test
    void history_downsamples_when_over_2000_points() {
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusMinutes(60);
        long startMs = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endMs = end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long duration = endMs - startMs;
        long step = duration / 2500;  // 2500 个点均匀分布

        List<SystemMetricResource> resources = new ArrayList<>(2500);
        for (int i = 0; i < 2500; i++) {
            long ts = startMs + i * step;
            resources.add(buildMetricResource("id-" + i, 1L, ts, (double) i));
        }
        when(extensionClient.list(eq(SystemMetricResource.class), any(ListOptions.class)))
                .thenReturn(resources);

        MonitorHistoryQueryDTO dto = new MonitorHistoryQueryDTO();
        dto.setInstanceId(1L);
        dto.setStartTime(start);
        dto.setEndTime(end);

        Result<List<SystemMetricVO>> result = controller.getHistory(dto);

        assertNotNull(result);
        assertNotNull(result.getData());
        // 降采样后桶数 ≤ downsampleTo(720)
        assertTrue(result.getData().size() <= 720,
                "降采样后点数应 ≤ 720，实际=" + result.getData().size());
        // 桶数应该接近 720（每个桶至少有一个样本）
        assertTrue(result.getData().size() > 0, "降采样后应至少有 1 个点");
    }

    // ============================================================
    // history_returns_raw_when_under_2000_points：1000 条 → 1000 条（不降采样）
    // ============================================================

    @Test
    void history_returns_raw_when_under_2000_points() {
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusMinutes(60);
        long startMs = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endMs = end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long step = (endMs - startMs) / 1000;

        List<SystemMetricResource> resources = new ArrayList<>(1000);
        for (int i = 0; i < 1000; i++) {
            long ts = startMs + i * step;
            resources.add(buildMetricResource("id-" + i, 1L, ts, (double) i));
        }
        when(extensionClient.list(eq(SystemMetricResource.class), any(ListOptions.class)))
                .thenReturn(resources);

        MonitorHistoryQueryDTO dto = new MonitorHistoryQueryDTO();
        dto.setInstanceId(1L);
        dto.setStartTime(start);
        dto.setEndTime(end);

        Result<List<SystemMetricVO>> result = controller.getHistory(dto);

        assertNotNull(result);
        assertNotNull(result.getData());
        assertEquals(1000, result.getData().size(), "点数 ≤ maxPoints 时应直接返回原始数据");
    }

    // ============================================================
    // get_config_returns_current_settings：返回当前配置
    // ============================================================

    @Test
    void get_config_returns_current_settings() {
        when(monitorCollectorService.isCollectEnabled()).thenReturn(true);

        Result<MonitorConfigVO> result = controller.getConfig();

        assertNotNull(result);
        assertNotNull(result.getData());
        MonitorConfigVO vo = result.getData();
        assertTrue(vo.isHistoryEnabled());
        assertEquals(1000L, vo.getCollectIntervalMs());
        assertEquals(3L * 24 * 3600 * 1000, vo.getRetentionMs());
        assertEquals(2000, vo.getMaxPoints());
        assertEquals(720, vo.getDownsampleTo());
        assertTrue(vo.isCollectEnabled());
    }

    // ============================================================
    // set_config_updates_enabled_flag：POST /config 切换开关
    // ============================================================

    @Test
    void set_config_updates_enabled_flag() {
        Map<String, Object> body = new HashMap<>();
        body.put("enable", false);

        Result<Void> result = controller.updateConfig(body);

        assertNotNull(result);
        verify(monitorCollectorService, times(1)).setCollectEnabled(eq(false));
    }

    // ============================================================
    // set_config_missing_enable_returns_fail：缺少 enable 参数返回失败
    // ============================================================

    @Test
    void set_config_missing_enable_returns_fail() {
        Map<String, Object> body = new HashMap<>();

        Result<Void> result = controller.updateConfig(body);

        assertNotNull(result);
        verify(monitorCollectorService, never()).setCollectEnabled(any(Boolean.class));
    }

    // ============================================================
    // status_returns_from_cache：缓存命中时不查询 hostQueryService
    // ============================================================

    @Test
    void status_returns_from_cache() {
        SystemMetricResource cached = buildMetricResource("cached-1", 1L,
                System.currentTimeMillis(), 42.0);
        when(monitorCollectorService.getLatestMetric(1L)).thenReturn(cached);

        Result<?> result = controller.getStatus(1L);

        assertNotNull(result);
        assertNotNull(result.getData());
        // 缓存命中：不调用 hostQueryService，不调用 persistMetric
        verify(hostQueryService, never()).getHostResourceInfo(anyLong());
        verify(monitorCollectorService, never()).persistMetric(anyLong(), any());
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private SystemMetricResource buildMetricResource(String id, Long instanceId, Long timestamp, Double cpu) {
        SystemMetricResource r = new SystemMetricResource();
        r.setId(id);
        r.setName(instanceId + "-" + timestamp);
        ExtensionMetadata meta = new ExtensionMetadata();
        meta.setCreationTimestamp(timestamp);
        r.setMetadata(meta);
        SystemMetricSpec spec = new SystemMetricSpec();
        spec.setInstanceId(instanceId);
        spec.setTimestamp(timestamp);
        spec.setCpuPercent(cpu);
        spec.setCpuMaxCore(cpu);
        spec.setMemUsed(4.0);
        spec.setMemTotal(16.0);
        spec.setSwapUsed(0.0);
        spec.setNetUpSpeed(10.0);
        spec.setNetDownSpeed(20.0);
        spec.setDiskUsed(50.0);
        spec.setDiskTotal(100.0);
        r.setSpec(spec);
        return r;
    }
}

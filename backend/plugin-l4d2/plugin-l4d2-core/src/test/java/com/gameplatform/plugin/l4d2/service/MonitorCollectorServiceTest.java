package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.api.extension.ExtensionMetadata;
import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.extension.ListOptions;
import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.extension.SystemMetricResource;
import com.gameplatform.plugin.l4d2.extension.SystemMetricSpec;
import com.gameplatform.plugin.service.HostQueryService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.HostResourceVO;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MonitorCollectorService 单元测试（对齐 plan §5.3.5）。
 *
 * <p>所有外部依赖（InstanceQueryService/HostQueryService/ExtensionClient）均被 mock，
 * 仅测试采集调度逻辑、缓存逻辑、清理逻辑。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitorCollectorServiceTest {

    @Mock
    private InstanceQueryService instanceQueryService;

    @Mock
    private HostQueryService hostQueryService;

    @Mock
    private ExtensionClient extensionClient;

    private L4D2Config config;

    private MonitorCollectorService service;

    @BeforeEach
    void setUp() {
        config = new L4D2Config();
        config.getMonitor().setCollectEnabled(true);
        config.getMonitor().setHistoryEnabled(true);
        config.getMonitor().setGameId(1L);
        config.getMonitor().setRetentionMs(3L * 24 * 3600 * 1000);
        service = new MonitorCollectorService(instanceQueryService, hostQueryService, extensionClient, config);
    }

    // ============================================================
    // collect_metrics_persists_for_each_instance：mock 2 个实例，验证 create 被调用 2 次
    // ============================================================

    @Test
    void collect_metrics_persists_for_each_instance() {
        InstanceVO i1 = buildInstance(1L, 10L);
        InstanceVO i2 = buildInstance(2L, 20L);
        when(instanceQueryService.getInstancesByGameId(1L)).thenReturn(List.of(i1, i2));
        when(hostQueryService.getHostResourceInfo(10L)).thenReturn(buildHostResource(10.0, 100L, 200L));
        when(hostQueryService.getHostResourceInfo(20L)).thenReturn(buildHostResource(20.0, 50L, 100L));

        service.collectMetrics();

        verify(extensionClient, times(2)).create(any(SystemMetricResource.class));

        // 验证缓存已填充
        assertNotNull(service.getLatestMetric(1L));
        assertNotNull(service.getLatestMetric(2L));
    }

    // ============================================================
    // collect_metrics_skips_failed_instance：一个实例抛异常，另一个仍被采集
    // ============================================================

    @Test
    void collect_metrics_skips_failed_instance() {
        InstanceVO i1 = buildInstance(1L, 10L);
        InstanceVO i2 = buildInstance(2L, 20L);
        when(instanceQueryService.getInstancesByGameId(1L)).thenReturn(List.of(i1, i2));
        when(hostQueryService.getHostResourceInfo(10L)).thenThrow(new RuntimeException("host 10 down"));
        when(hostQueryService.getHostResourceInfo(20L)).thenReturn(buildHostResource(20.0, 50L, 100L));

        service.collectMetrics();

        // i1 失败 → 仅 i2 持久化
        verify(extensionClient, times(1)).create(any(SystemMetricResource.class));
        assertNull(service.getLatestMetric(1L));
        // i2 已缓存（注意：getLatestMetric 不会触发 DB 查询因为缓存命中）
        assertNotNull(service.getLatestMetric(2L));
    }

    // ============================================================
    // collect_metrics_disabled_skips_all：collectEnabled=false 时不采集
    // ============================================================

    @Test
    void collect_metrics_disabled_skips_all() {
        config.getMonitor().setCollectEnabled(false);

        InstanceVO i1 = buildInstance(1L, 10L);
        when(instanceQueryService.getInstancesByGameId(1L)).thenReturn(List.of(i1));

        service.collectMetrics();

        verify(extensionClient, never()).create(any(SystemMetricResource.class));
        verify(hostQueryService, never()).getHostResourceInfo(anyLong());
    }

    // ============================================================
    // cleanup_removes_expired_metrics：过期数据被 deleteById 删除
    // ============================================================

    @Test
    void cleanup_removes_expired_metrics() {
        // retentionMs = 3 天，过期时间 = now - 3 天
        long now = System.currentTimeMillis();
        long retentionMs = config.getMonitor().getRetentionMs();
        long expired = now - retentionMs - 60_000;  // 过期
        long fresh = now - 1000;  // 新鲜

        SystemMetricResource expiredR = buildMetricResource("id-1", 1L, expired);
        SystemMetricResource freshR = buildMetricResource("id-2", 1L, fresh);
        when(extensionClient.listAll(SystemMetricResource.class))
                .thenReturn(List.of(expiredR, freshR));

        service.cleanupExpired();

        // 仅 expiredR 被 deleteById
        verify(extensionClient, times(1)).deleteById(SystemMetricResource.class, "id-1");
        verify(extensionClient, never()).deleteById(SystemMetricResource.class, "id-2");
    }

    // ============================================================
    // get_latest_metric_returns_from_cache：先 collect 一次填充缓存，再 getLatestMetric 不查 DB
    // ============================================================

    @Test
    void get_latest_metric_returns_from_cache() {
        InstanceVO i1 = buildInstance(1L, 10L);
        when(instanceQueryService.getInstancesByGameId(1L)).thenReturn(List.of(i1));
        when(hostQueryService.getHostResourceInfo(10L)).thenReturn(buildHostResource(15.0, 100L, 200L));

        service.collectMetrics();

        // 缓存命中：不调用 extensionClient.list
        SystemMetricResource latest = service.getLatestMetric(1L);
        assertNotNull(latest);
        assertNotNull(latest.getSpec());
        assertEquals(1L, latest.getSpec().getInstanceId());
        // 验证未触发 DB 查询
        verify(extensionClient, never()).list(eq(SystemMetricResource.class), any());
    }

    // ============================================================
    // get_latest_metric_falls_back_to_db：缓存为空时，调用 extensionClient.list
    // ============================================================

    @Test
    void get_latest_metric_falls_back_to_db() {
        // 缓存未填充；DB 返回一条
        SystemMetricResource dbMetric = buildMetricResource("id-from-db", 1L, System.currentTimeMillis());
        when(extensionClient.list(eq(SystemMetricResource.class), any(ListOptions.class)))
                .thenReturn(List.of(dbMetric));

        SystemMetricResource latest = service.getLatestMetric(1L);
        assertNotNull(latest);
        assertEquals("id-from-db", latest.getId());

        // 验证调用了 list
        verify(extensionClient, times(1)).list(eq(SystemMetricResource.class), any(ListOptions.class));
    }

    // ============================================================
    // set_collect_enabled_updates_flag：开关切换并持久化到 config
    // ============================================================

    @Test
    void set_collect_enabled_updates_flag() {
        assertTrue(service.isCollectEnabled());

        service.setCollectEnabled(false);
        assertFalse(service.isCollectEnabled());
        assertFalse(config.getMonitor().isCollectEnabled());

        service.setCollectEnabled(true);
        assertTrue(service.isCollectEnabled());
        assertTrue(config.getMonitor().isCollectEnabled());
    }

    // ============================================================
    // collect_metrics_no_game_id_skips：gameId 为 null 时跳过
    // ============================================================

    @Test
    void collect_metrics_no_game_id_skips() {
        config.getMonitor().setGameId(null);

        service.collectMetrics();

        verify(extensionClient, never()).create(any(SystemMetricResource.class));
        verify(instanceQueryService, never()).getInstancesByGameId(anyLong());
    }

    // ============================================================
    // collect_metrics_history_disabled_skips_persistence：historyEnabled=false 时仅刷新缓存
    // ============================================================

    @Test
    void collect_metrics_history_disabled_skips_persistence() {
        config.getMonitor().setHistoryEnabled(false);

        InstanceVO i1 = buildInstance(1L, 10L);
        when(instanceQueryService.getInstancesByGameId(1L)).thenReturn(List.of(i1));
        when(hostQueryService.getHostResourceInfo(10L)).thenReturn(buildHostResource(10.0, 100L, 200L));

        service.collectMetrics();

        verify(extensionClient, never()).create(any(SystemMetricResource.class));
        // 缓存应被填充
        assertNotNull(service.getLatestMetric(1L));
    }

    // ============================================================
    // cleanup_removes_all_when_all_expired：全部过期时全部删除
    // ============================================================

    @Test
    void cleanup_removes_all_when_all_expired() {
        long now = System.currentTimeMillis();
        long retentionMs = config.getMonitor().getRetentionMs();
        List<SystemMetricResource> all = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            all.add(buildMetricResource("id-" + i, (long) i, now - retentionMs - 60_000 * (i + 1)));
        }
        when(extensionClient.listAll(SystemMetricResource.class)).thenReturn(all);

        service.cleanupExpired();

        for (int i = 0; i < 5; i++) {
            verify(extensionClient, times(1)).deleteById(SystemMetricResource.class, "id-" + i);
        }
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private InstanceVO buildInstance(Long id, Long hostId) {
        InstanceVO vo = new InstanceVO();
        vo.setId(id);
        vo.setHostId(hostId);
        vo.setGameId(1L);
        vo.setGameCode("l4d2");
        return vo;
    }

    private HostResourceVO buildHostResource(double cpuUsage, long memUsedMb, long memTotalMb) {
        HostResourceVO r = new HostResourceVO();
        HostResourceVO.CpuInfo cpu = new HostResourceVO.CpuInfo();
        cpu.setUsage(cpuUsage);
        r.setCpu(cpu);
        HostResourceVO.MemoryInfo mem = new HostResourceVO.MemoryInfo();
        mem.setUsed(memUsedMb);
        mem.setTotal(memTotalMb);
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

    private SystemMetricResource buildMetricResource(String id, Long instanceId, Long timestamp) {
        SystemMetricResource r = new SystemMetricResource();
        r.setId(id);
        r.setName(instanceId + "-" + timestamp);
        ExtensionMetadata meta = new ExtensionMetadata();
        meta.setCreationTimestamp(timestamp);
        r.setMetadata(meta);
        SystemMetricSpec spec = new SystemMetricSpec();
        spec.setInstanceId(instanceId);
        spec.setTimestamp(timestamp);
        spec.setCpuPercent(10.0);
        r.setSpec(spec);
        return r;
    }

    /** 验证 ArgumentCaptor 工具方法（保留以便扩展）。 */
    @SuppressWarnings("unused")
    private <T> ArgumentCaptor<T> captor(Class<T> cls) {
        return ArgumentCaptor.forClass(cls);
    }
}

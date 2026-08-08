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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 监控采集服务：定时拉取 L4D2 实例所在主机的 CPU/内存/磁盘/网络指标，
 * 持久化到 ExtensionClient 并缓存最新一条；同时负责过期数据清理。
 *
 * <p>对齐源项目 {@code monitor.go}：
 * <ul>
 *   <li>1 秒采集间隔（可配置 {@code plugin.l4d2.monitor.collect-interval-ms}）</li>
 *   <li>内存缓存最新指标，避免 GET /status 每次查 DB</li>
 *   <li>异常时跳过当前实例，不中断采集循环</li>
 *   <li>每小时清理超过 retentionMs 的历史数据</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorCollectorService {

    private final InstanceQueryService instanceQueryService;
    private final HostQueryService hostQueryService;
    private final ExtensionClient extensionClient;
    private final L4D2Config config;

    /** 实例ID → 最新一条指标（内存缓存） */
    private final ConcurrentHashMap<Long, SystemMetricResource> latestByInstance = new ConcurrentHashMap<>();

    /**
     * 定时采集：每 {@code plugin.l4d2.monitor.collect-interval-ms}（默认 1s）执行一次。
     *
     * <p>对每个 L4D2 实例：
     * <ol>
     *   <li>调用 {@link HostQueryService#getHostResourceInfo} 获取主机资源</li>
     *   <li>转换为 {@link SystemMetricResource} 持久化</li>
     *   <li>更新内存缓存</li>
     * </ol>
     * 单个实例失败不影响其他实例。
     */
    @Scheduled(fixedRateString = "${plugin.l4d2.monitor.collect-interval-ms:1000}")
    public void collectMetrics() {
        if (!config.getMonitor().isCollectEnabled()) {
            return;
        }
        if (!config.getMonitor().isHistoryEnabled()) {
            // 历史持久化关闭时仍刷新缓存，但不写 DB
            collectWithoutPersistence();
            return;
        }
        Long gameId = config.getMonitor().getGameId();
        if (gameId == null) {
            return;
        }
        List<InstanceVO> instances;
        try {
            instances = instanceQueryService.getInstancesByGameId(gameId);
        } catch (Exception e) {
            log.warn("拉取 L4D2 实例列表失败, gameId={}", gameId, e);
            return;
        }
        if (instances == null || instances.isEmpty()) {
            return;
        }
        for (InstanceVO instance : instances) {
            if (instance == null || instance.getId() == null || instance.getHostId() == null) {
                continue;
            }
            try {
                HostResourceVO hostResource = hostQueryService.getHostResourceInfo(instance.getHostId());
                SystemMetricResource resource = buildResource(instance.getId(), hostResource);
                if (resource == null) {
                    continue;
                }
                extensionClient.create(resource);
                latestByInstance.put(instance.getId(), resource);
            } catch (Exception e) {
                log.warn("采集实例 {} 监控指标失败: {}", instance.getId(), e.getMessage());
            }
        }
    }

    /**
     * 历史持久化关闭时的采集：仅刷新缓存，不调用 extensionClient.create。
     */
    private void collectWithoutPersistence() {
        Long gameId = config.getMonitor().getGameId();
        if (gameId == null) {
            return;
        }
        List<InstanceVO> instances;
        try {
            instances = instanceQueryService.getInstancesByGameId(gameId);
        } catch (Exception e) {
            log.warn("拉取 L4D2 实例列表失败（historyDisabled 模式）, gameId={}", gameId, e);
            return;
        }
        if (instances == null || instances.isEmpty()) {
            return;
        }
        for (InstanceVO instance : instances) {
            if (instance == null || instance.getId() == null || instance.getHostId() == null) {
                continue;
            }
            try {
                HostResourceVO hostResource = hostQueryService.getHostResourceInfo(instance.getHostId());
                SystemMetricResource resource = buildResource(instance.getId(), hostResource);
                if (resource != null) {
                    latestByInstance.put(instance.getId(), resource);
                }
            } catch (Exception e) {
                log.warn("刷新实例 {} 监控缓存失败: {}", instance.getId(), e.getMessage());
            }
        }
    }

    /**
     * 定时清理：每小时执行，删除超过 retentionMs 的历史数据。
     *
     * <p>ExtensionClient 无"按范围删除"接口，需先 list 再逐个 deleteById。
     */
    @Scheduled(fixedRate = 3600_000)
    public void cleanupExpired() {
        long retentionMs = config.getMonitor().getRetentionMs();
        long expireBefore = System.currentTimeMillis() - retentionMs;
        List<SystemMetricResource> all;
        try {
            all = extensionClient.listAll(SystemMetricResource.class);
        } catch (Exception e) {
            log.warn("拉取 SystemMetricResource 列表失败", e);
            return;
        }
        if (all == null || all.isEmpty()) {
            return;
        }
        int deleted = 0;
        for (SystemMetricResource resource : all) {
            Long ts = extractTimestamp(resource);
            if (ts == null || ts < expireBefore) {
                try {
                    if (resource.getId() != null) {
                        extensionClient.deleteById(SystemMetricResource.class, resource.getId());
                        deleted++;
                    }
                } catch (Exception e) {
                    log.warn("删除过期 SystemMetricResource 失败 id={}, err={}",
                            resource.getId(), e.getMessage());
                }
            }
        }
        if (deleted > 0) {
            log.info("清理过期监控指标完成, 删除 {} 条", deleted);
        }
    }

    /**
     * 获取指定实例的最新一条监控指标。
     *
     * <p>优先从内存缓存读取，缓存未命中时回退到 DB 查询（DESC + limit 1）。
     *
     * @param instanceId 实例 ID
     * @return 最新指标；不存在返回 null
     */
    public SystemMetricResource getLatestMetric(Long instanceId) {
        if (instanceId == null) {
            return null;
        }
        SystemMetricResource cached = latestByInstance.get(instanceId);
        if (cached != null) {
            return cached;
        }
        try {
            ListOptions opts = ListOptions.builder()
                    .specFilter("$.instanceId", "=", instanceId)
                    .orderBy("creation_timestamp")
                    .limit(1)
                    .build();
            List<SystemMetricResource> list = extensionClient.list(SystemMetricResource.class, opts);
            if (list != null && !list.isEmpty()) {
                SystemMetricResource latest = list.get(0);
                latestByInstance.put(instanceId, latest);
                return latest;
            }
        } catch (Exception e) {
            log.warn("查询实例 {} 最新监控指标失败: {}", instanceId, e.getMessage());
        }
        return null;
    }

    /**
     * 运行时开关：是否启用主动采集（持久化到 {@link L4D2Config.Monitor#setCollectEnabled}）。
     */
    public void setCollectEnabled(boolean enabled) {
        config.getMonitor().setCollectEnabled(enabled);
        log.info("监控采集开关已设置为: {}", enabled);
    }

    /**
     * 当前是否启用主动采集。
     */
    public boolean isCollectEnabled() {
        return config.getMonitor().isCollectEnabled();
    }

    /**
     * 主动持久化一条指标（兼容被动模式，由 MonitorController 调用）。
     *
     * @param instanceId 实例 ID
     * @param hostResource 主机资源
     * @return 持久化后的 Resource（含框架填充的 id/metadata），失败返回 null
     */
    public SystemMetricResource persistMetric(Long instanceId, HostResourceVO hostResource) {
        if (!config.getMonitor().isHistoryEnabled()) {
            // 历史关闭：仅刷新缓存
            SystemMetricResource resource = buildResource(instanceId, hostResource);
            if (resource != null) {
                latestByInstance.put(instanceId, resource);
            }
            return resource;
        }
        try {
            SystemMetricResource resource = buildResource(instanceId, hostResource);
            if (resource == null) {
                return null;
            }
            extensionClient.create(resource);
            latestByInstance.put(instanceId, resource);
            return resource;
        } catch (Exception e) {
            log.warn("持久化实例 {} 监控指标失败: {}", instanceId, e.getMessage());
            return null;
        }
    }

    // ===== 私有方法 =====

    /**
     * 构造 SystemMetricResource（不持久化）。
     */
    private SystemMetricResource buildResource(Long instanceId, HostResourceVO hostResource) {
        long now = System.currentTimeMillis();
        SystemMetricResource resource = new SystemMetricResource();
        resource.setName(instanceId + "-" + now);

        SystemMetricSpec spec = new SystemMetricSpec();
        spec.setInstanceId(instanceId);
        spec.setTimestamp(now);
        if (hostResource != null) {
            // CPU
            if (hostResource.getCpu() != null) {
                spec.setCpuPercent(hostResource.getCpu().getUsage());
                spec.setCpuMaxCore(hostResource.getCpu().getUsage());
            }
            // 内存（HostResourceVO.MemoryInfo 单位是 MB → GB）
            if (hostResource.getMemory() != null) {
                spec.setMemUsed(hostResource.getMemory().getUsed() != null
                        ? hostResource.getMemory().getUsed().doubleValue() / 1024.0 : 0.0);
                spec.setMemTotal(hostResource.getMemory().getTotal() != null
                        ? hostResource.getMemory().getTotal().doubleValue() / 1024.0 : 0.0);
                spec.setSwapUsed(0.0);
            }
            // 磁盘（HostResourceVO.DiskInfo 单位是 GB）
            if (hostResource.getDisk() != null) {
                spec.setDiskUsed(hostResource.getDisk().getUsed() != null
                        ? hostResource.getDisk().getUsed().doubleValue() : 0.0);
                spec.setDiskTotal(hostResource.getDisk().getTotal() != null
                        ? hostResource.getDisk().getTotal().doubleValue() : 0.0);
            }
            // 网络（rxBytes/txBytes 累计字节 → 此处按 KB 估算瞬时速度，对齐原 MonitorController 简化处理）
            if (hostResource.getNetwork() != null) {
                spec.setNetDownSpeed(hostResource.getNetwork().getRxBytes() != null
                        ? hostResource.getNetwork().getRxBytes().doubleValue() / 1024.0 : 0.0);
                spec.setNetUpSpeed(hostResource.getNetwork().getTxBytes() != null
                        ? hostResource.getNetwork().getTxBytes().doubleValue() / 1024.0 : 0.0);
            }
        }
        resource.setSpec(spec);
        return resource;
    }

    /**
     * 提取 Resource 的有效时间戳：优先 spec.timestamp，其次 metadata.creationTimestamp。
     */
    private Long extractTimestamp(SystemMetricResource resource) {
        if (resource.getSpec() != null && resource.getSpec().getTimestamp() != null) {
            return resource.getSpec().getTimestamp();
        }
        ExtensionMetadata meta = resource.getMetadata();
        if (meta != null && meta.getCreationTimestamp() != null) {
            return meta.getCreationTimestamp();
        }
        return null;
    }
}

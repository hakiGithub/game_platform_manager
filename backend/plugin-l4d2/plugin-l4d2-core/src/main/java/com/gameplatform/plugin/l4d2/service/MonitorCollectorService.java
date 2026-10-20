package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.vo.SystemMetricVO;
import com.gameplatform.plugin.service.HostQueryService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.HostResourceVO;
import com.gameplatform.vo.InstanceVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 监控采集服务：定时拉取 L4D2 实例所在主机的资源指标。
 *
 * <p>最新值保存在内存中，历史值追加到 {@link MonitorMetricFileStore} 的实例隔离临时文件，
 * 不写入扩展资源表。单个实例采集失败不会中断其他实例。
 */
@Slf4j
@Service
public class MonitorCollectorService {

    private final InstanceQueryService instanceQueryService;
    private final HostQueryService hostQueryService;
    private final MonitorMetricFileStore metricFileStore;
    private final L4D2Config config;

    /**
     * PF4J 扩展点由宿主实例化，不在 Spring 子容器内；这里保留一个仅限插件子容器使用的
     * 生命周期桥，供 L4D2Extension.onInstanceDelete 委托实例目录清理。
     */
    private static volatile ApplicationContext pluginContext;

    /** 实例 ID -> 最新指标；每个实例仅保留一个对象。 */
    private final ConcurrentHashMap<Long, SystemMetricVO> latestByInstance = new ConcurrentHashMap<>();

    public MonitorCollectorService(InstanceQueryService instanceQueryService,
                                   HostQueryService hostQueryService,
                                   MonitorMetricFileStore metricFileStore,
                                   L4D2Config config,
                                   ApplicationContext pluginContext) {
        this.instanceQueryService = instanceQueryService;
        this.hostQueryService = hostQueryService;
        this.metricFileStore = metricFileStore;
        this.config = config;
        MonitorCollectorService.pluginContext = pluginContext;
    }

    /**
     * 定时采集：每 {@code plugin.l4d2.monitor.collect-interval-ms}（默认 1s）执行一次。
     */
    @Scheduled(fixedRateString = "${plugin.l4d2.monitor.collect-interval-ms:1000}")
    public void collectMetrics() {
        if (!config.getMonitor().isCollectEnabled()) {
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
                SystemMetricVO metric = buildMetric(instance.getId(), hostResource);
                if (metric == null) {
                    continue;
                }
                latestByInstance.put(instance.getId(), metric);
                if (config.getMonitor().isHistoryEnabled()) {
                    metricFileStore.append(instance.getId(), metric);
                }
            } catch (Exception e) {
                log.warn("采集实例 {} 监控指标失败: {}", instance.getId(), e.getMessage());
            }
        }
    }

    /** 每小时清理超过保留期的临时文件。 */
    @Scheduled(fixedRate = 3600_000)
    public void cleanupExpired() {
        int deleted = metricFileStore.cleanupExpired();
        if (deleted > 0) {
            log.info("清理过期监控临时文件完成, 删除 {} 个", deleted);
        }
    }

    /**
     * 获取指定实例的最新指标。仅读内存缓存；缓存未命中时流式读取最新临时文件。
     */
    public SystemMetricVO getLatestMetric(Long instanceId) {
        if (instanceId == null) {
            return null;
        }
        SystemMetricVO cached = latestByInstance.get(instanceId);
        if (cached != null) {
            return cached;
        }
        SystemMetricVO latest = metricFileStore.latest(instanceId);
        if (latest != null) {
            latestByInstance.put(instanceId, latest);
        }
        return latest;
    }

    /** 运行时开关：是否启用主动采集。 */
    public void setCollectEnabled(boolean enabled) {
        config.getMonitor().setCollectEnabled(enabled);
        log.info("监控采集开关已设置为: {}", enabled);
    }

    public boolean isCollectEnabled() {
        return config.getMonitor().isCollectEnabled();
    }

    /**
     * 被动模式采集一条指标：刷新缓存，并在历史开启时追加临时文件。
     */
    public SystemMetricVO persistMetric(Long instanceId, HostResourceVO hostResource) {
        SystemMetricVO metric = buildMetric(instanceId, hostResource);
        if (metric == null) {
            return null;
        }
        latestByInstance.put(instanceId, metric);
        if (config.getMonitor().isHistoryEnabled()) {
            metricFileStore.append(instanceId, metric);
        }
        return metric;
    }

    /** 实例删除时清理对应临时文件与内存缓存。 */
    public void deleteInstance(long instanceId) {
        latestByInstance.remove(instanceId);
        metricFileStore.deleteInstance(instanceId);
    }

    /** 删除实例监控数据；插件上下文不可用时静默跳过，保留期清理仍会兜底。 */
    public static void deleteInstanceFromPluginLifecycle(long instanceId) {
        ApplicationContext context = pluginContext;
        if (context == null) {
            return;
        }
        try {
            context.getBean(MonitorCollectorService.class).deleteInstance(instanceId);
        } catch (Exception e) {
            log.warn("插件生命周期清理实例监控数据失败 instanceId={}, err={}",
                    instanceId, e.getMessage());
        }
    }

    private SystemMetricVO buildMetric(Long instanceId, HostResourceVO hostResource) {
        if (instanceId == null) {
            return null;
        }
        SystemMetricVO metric = new SystemMetricVO();
        metric.setTimestamp(System.currentTimeMillis());
        if (hostResource == null) {
            return metric;
        }
        if (hostResource.getCpu() != null) {
            metric.setCpuPercent(hostResource.getCpu().getUsage());
            metric.setCpuMaxCore(hostResource.getCpu().getUsage());
        }
        if (hostResource.getMemory() != null) {
            metric.setMemUsed(hostResource.getMemory().getUsed() != null
                    ? hostResource.getMemory().getUsed().doubleValue() / 1024.0 : 0.0);
            metric.setMemTotal(hostResource.getMemory().getTotal() != null
                    ? hostResource.getMemory().getTotal().doubleValue() / 1024.0 : 0.0);
            metric.setSwapUsed(0.0);
        }
        if (hostResource.getDisk() != null) {
            metric.setDiskUsed(hostResource.getDisk().getUsed() != null
                    ? hostResource.getDisk().getUsed().doubleValue() : 0.0);
            metric.setDiskTotal(hostResource.getDisk().getTotal() != null
                    ? hostResource.getDisk().getTotal().doubleValue() : 0.0);
        }
        if (hostResource.getNetwork() != null) {
            metric.setNetDownSpeed(hostResource.getNetwork().getRxBytes() != null
                    ? hostResource.getNetwork().getRxBytes().doubleValue() / 1024.0 : 0.0);
            metric.setNetUpSpeed(hostResource.getNetwork().getTxBytes() != null
                    ? hostResource.getNetwork().getTxBytes().doubleValue() / 1024.0 : 0.0);
        }
        return metric;
    }
}

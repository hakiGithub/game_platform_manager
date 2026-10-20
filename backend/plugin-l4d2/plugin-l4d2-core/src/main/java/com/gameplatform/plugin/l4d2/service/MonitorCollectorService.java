package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.vo.SystemMetricVO;
import com.gameplatform.plugin.service.HostQueryService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.HostResourceVO;
import com.gameplatform.vo.InstanceVO;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 监控采集服务：定时拉取 L4D2 实例所在主机的资源指标。
 *
 * <p>最新值保存在内存中，历史值追加到 {@link MonitorMetricFileStore} 的实例隔离临时文件，
 * 不写入扩展资源表。单个实例采集失败不会中断其他实例。
 */
@Slf4j
@Service
public class MonitorCollectorService {

    private static final String GAME_CODE = "l4d2";

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

    /** 插件子容器未启用 @Scheduled，主动采集由插件生命周期自管。 */
    private ScheduledExecutorService scheduler;

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
     * 启动插件生命周期调度。单线程固定延迟执行，避免慢采集导致任务堆积。
     */
    public synchronized void startScheduling() {
        if (scheduler != null && !scheduler.isShutdown()) {
            return;
        }
        long intervalMs = Math.max(100L, config.getMonitor().getCollectIntervalMs());
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "l4d2-monitor-collector");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::runCollectionSafely,
                0L, intervalMs, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::runCleanupSafely,
                1L, 1L, TimeUnit.HOURS);
        log.info("L4D2 监控主动采集已启动, intervalMs={}", intervalMs);
    }

    /** 停止插件生命周期调度，可由 onUnload 与子容器销毁重复调用。 */
    public synchronized void stopScheduling() {
        if (scheduler == null) {
            return;
        }
        scheduler.shutdownNow();
        scheduler = null;
        log.info("L4D2 监控主动采集已停止");
    }

    /** 插件子容器销毁时兜底停止调度。 */
    @PreDestroy
    public void destroy() {
        stopScheduling();
    }

    public synchronized boolean isScheduling() {
        return scheduler != null && !scheduler.isShutdown();
    }

    /**
     * 采集一轮指标。按 gameCode 查询实例，避免依赖主应用写入插件配置的数据库 gameId。
     */
    public void collectMetrics() {
        if (!config.getMonitor().isCollectEnabled()) {
            return;
        }
        List<InstanceVO> instances;
        try {
            instances = instanceQueryService.listByGameCode(GAME_CODE);
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            log.warn("拉取 L4D2 实例列表失败, gameCode={}", GAME_CODE, e);
            return;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
            log.warn("拉取 L4D2 实例列表失败, gameCode={}", GAME_CODE, e);
            return;
        }
        if (instances == null || instances.isEmpty()) {
            return;
        }
        for (InstanceVO instance : instances) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
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

    /** 清理超过保留期的临时文件。 */
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

    /** 插件 onLoad 后启动主动采集。 */
    public static void startSchedulingFromPluginLifecycle() {
        withPluginContext("启动主动采集", MonitorCollectorService::startScheduling);
    }

    /** 插件 onUnload 前停止主动采集。 */
    public static void stopSchedulingFromPluginLifecycle() {
        withPluginContext("停止主动采集", MonitorCollectorService::stopScheduling);
    }

    /** 删除实例监控数据；插件上下文不可用时静默跳过，保留期清理仍会兜底。 */
    public static void deleteInstanceFromPluginLifecycle(long instanceId) {
        withPluginContext("清理实例监控数据", service -> service.deleteInstance(instanceId));
    }

    private static void withPluginContext(String action,
                                          java.util.function.Consumer<MonitorCollectorService> callback) {
        ApplicationContext context = pluginContext;
        if (context == null) {
            log.warn("{}失败: 插件上下文不可用", action);
            return;
        }
        try {
            callback.accept(context.getBean(MonitorCollectorService.class));
        } catch (Exception e) {
            log.warn("{}失败: {}", action, e.getMessage());
        }
    }

    private void runCollectionSafely() {
        try {
            collectMetrics();
        } catch (Exception e) {
            log.error("L4D2 监控主动采集任务异常: {}", e.getMessage(), e);
        }
    }

    private void runCleanupSafely() {
        try {
            cleanupExpired();
        } catch (Exception e) {
            log.error("L4D2 监控临时文件清理任务异常: {}", e.getMessage(), e);
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

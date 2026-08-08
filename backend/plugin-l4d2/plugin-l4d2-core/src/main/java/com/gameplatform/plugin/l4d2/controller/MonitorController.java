package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.extension.ListOptions;
import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.dto.MonitorHistoryQueryDTO;
import com.gameplatform.plugin.l4d2.extension.SystemMetricResource;
import com.gameplatform.plugin.l4d2.extension.SystemMetricSpec;
import com.gameplatform.plugin.l4d2.service.MonitorCollectorService;
import com.gameplatform.plugin.l4d2.vo.MonitorConfigVO;
import com.gameplatform.plugin.l4d2.vo.MonitorHistoryVO;
import com.gameplatform.plugin.l4d2.vo.MonitorStatusVO;
import com.gameplatform.plugin.l4d2.vo.SystemMetricVO;
import com.gameplatform.plugin.service.HostQueryService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.HostResourceVO;
import com.gameplatform.vo.InstanceVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 监控控制器
 * 提供 L4D2 服务器的系统监控功能
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Tag(name = "L4D2 监控管理", description = "L4D2 服务器监控接口")
@RestController
@RequestMapping("/api/plugin/l4d2/monitor")
@RequiredArgsConstructor
@Validated
public class MonitorController {

    private final InstanceQueryService instanceQueryService;
    private final HostQueryService hostQueryService;
    private final ExtensionClient extensionClient;
    private final MonitorCollectorService monitorCollectorService;
    private final L4D2Config config;

    /**
     * 获取当前系统状态
     */
    @Operation(summary = "获取当前系统状态", description = "获取服务器当前的系统资源使用情况")
    @GetMapping("/status")
    public Result<MonitorStatusVO> getStatus(@Parameter(description = "实例ID") @RequestParam Long instanceId) {
        log.info("获取系统状态, instanceId: {}", instanceId);

        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            return Result.fail("实例不存在");
        }

        // 优先从采集服务缓存读取最新指标
        SystemMetricResource cached = monitorCollectorService.getLatestMetric(instanceId);
        if (cached != null && cached.getSpec() != null) {
            MonitorStatusVO vo = toStatusVOFromSpec(instanceId, cached.getSpec());
            return Result.success(vo);
        }

        // 缓存未命中：调用主机资源接口实时获取（被动模式向后兼容）
        HostResourceVO hostResource = hostQueryService.getHostResourceInfo(instance.getHostId());
        MonitorStatusVO vo = convertToMonitorStatusVO(instanceId, hostResource);

        // 持久化一条历史记录（委托给采集服务，受 historyEnabled 控制）
        monitorCollectorService.persistMetric(instanceId, hostResource);

        return Result.success(vo);
    }

    /**
     * 获取历史数据（支持降采样：> maxPoints 条时降至 downsampleTo 条）。
     */
    @Operation(summary = "获取历史数据", description = "获取指定时间范围内的监控历史数据（自动降采样）")
    @GetMapping("/history")
    public Result<List<SystemMetricVO>> getHistory(@Valid MonitorHistoryQueryDTO queryDTO) {
        log.info("获取监控历史数据, instanceId: {}, startTime: {}, endTime: {}",
                queryDTO.getInstanceId(), queryDTO.getStartTime(), queryDTO.getEndTime());

        InstanceVO instance = instanceQueryService.getInstanceById(queryDTO.getInstanceId());
        if (instance == null) {
            return Result.fail("实例不存在");
        }

        LocalDateTime endTime = queryDTO.getEndTime();
        LocalDateTime startTime = queryDTO.getStartTime();
        if (endTime == null) {
            endTime = LocalDateTime.now();
        }
        if (startTime == null) {
            startTime = endTime.minusMinutes(queryDTO.getTimeRangeMinutes());
        }

        List<SystemMetricResource> resources = queryResourcesFromDatabase(
                queryDTO.getInstanceId(), startTime, endTime);
        List<SystemMetricVO> raw = resources.stream()
                .map(SystemMetricVO::from)
                .collect(Collectors.toList());

        // 降采样
        long startMs = startTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endMs = endTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        List<SystemMetricVO> result = downsampleIfNecessary(raw, startMs, endMs);
        return Result.success(result);
    }

    /**
     * 获取实时监控数据（WebSocket 推送的 HTTP 轮询备用接口）
     */
    @Operation(summary = "获取实时监控数据", description = "获取最近几分钟的监控数据")
    @GetMapping("/realtime")
    public Result<List<MonitorHistoryVO>> getRealtime(
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "5") Integer minutes) {
        log.info("获取实时监控数据, instanceId: {}, minutes: {}", instanceId, minutes);

        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            return Result.fail("实例不存在");
        }

        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusMinutes(minutes);

        List<MonitorHistoryVO> historyList = queryHistoryFromDatabase(instanceId, startTime, endTime);
        return Result.success(historyList);
    }

    /**
     * 获取 CPU 使用率趋势
     */
    @Operation(summary = "获取 CPU 使用率趋势", description = "获取指定时间范围内的 CPU 使用率变化趋势")
    @GetMapping("/cpu-trend")
    public Result<List<MonitorHistoryVO>> getCpuTrend(
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") Integer minutes) {
        log.info("获取 CPU 使用率趋势, instanceId: {}, minutes: {}", instanceId, minutes);

        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusMinutes(minutes);

        List<MonitorHistoryVO> historyList = queryHistoryFromDatabase(instanceId, startTime, endTime);
        return Result.success(historyList);
    }

    /**
     * 获取内存使用率趋势
     */
    @Operation(summary = "获取内存使用率趋势", description = "获取指定时间范围内的内存使用率变化趋势")
    @GetMapping("/memory-trend")
    public Result<List<MonitorHistoryVO>> getMemoryTrend(
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") Integer minutes) {
        log.info("获取内存使用率趋势, instanceId: {}, minutes: {}", instanceId, minutes);

        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusMinutes(minutes);

        List<MonitorHistoryVO> historyList = queryHistoryFromDatabase(instanceId, startTime, endTime);
        return Result.success(historyList);
    }

    /**
     * 获取网络流量趋势
     */
    @Operation(summary = "获取网络流量趋势", description = "获取指定时间范围内的网络流量变化趋势")
    @GetMapping("/network-trend")
    public Result<List<MonitorHistoryVO>> getNetworkTrend(
            @Parameter(description = "实例ID") @RequestParam Long instanceId,
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") Integer minutes) {
        log.info("获取网络流量趋势, instanceId: {}, minutes: {}", instanceId, minutes);

        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusMinutes(minutes);

        List<MonitorHistoryVO> historyList = queryHistoryFromDatabase(instanceId, startTime, endTime);
        return Result.success(historyList);
    }

    /**
     * 获取监控配置。
     */
    @Operation(summary = "获取监控配置", description = "返回当前监控采集与历史的配置信息")
    @GetMapping("/config")
    public Result<MonitorConfigVO> getConfig() {
        L4D2Config.Monitor m = config.getMonitor();
        MonitorConfigVO vo = new MonitorConfigVO();
        vo.setHistoryEnabled(m.isHistoryEnabled());
        vo.setCollectIntervalMs(m.getCollectIntervalMs());
        vo.setRetentionMs(m.getRetentionMs());
        vo.setMaxPoints(m.getMaxPoints());
        vo.setDownsampleTo(m.getDownsampleTo());
        vo.setCollectEnabled(monitorCollectorService.isCollectEnabled());
        return Result.success(vo);
    }

    /**
     * 更新监控配置（目前仅支持切换 collectEnabled 开关）。
     */
    @Operation(summary = "更新监控配置", description = "切换主动采集开关")
    @PostMapping("/config")
    public Result<Void> updateConfig(@RequestBody Map<String, Object> body) {
        Object enableObj = body == null ? null : body.get("enable");
        if (enableObj == null) {
            return Result.fail("缺少 enable 参数");
        }
        boolean enable = Boolean.parseBoolean(String.valueOf(enableObj));
        monitorCollectorService.setCollectEnabled(enable);
        return Result.success();
    }

    // ========== 私有方法 ==========

    /**
     * 转换为监控状态 VO
     */
    private MonitorStatusVO convertToMonitorStatusVO(Long instanceId, HostResourceVO hostResource) {
        MonitorStatusVO vo = new MonitorStatusVO();
        vo.setInstanceId(instanceId);
        vo.setTimestamp(System.currentTimeMillis());

        if (hostResource != null) {
            if (hostResource.getCpu() != null) {
                vo.setCpuPercent(hostResource.getCpu().getUsage());
                vo.setCpuMaxCore(hostResource.getCpu().getUsage());
            }
            if (hostResource.getMemory() != null) {
                vo.setMemUsed(hostResource.getMemory().getUsed() != null
                    ? hostResource.getMemory().getUsed().doubleValue() / 1024.0 : 0.0);
                vo.setMemTotal(hostResource.getMemory().getTotal() != null
                    ? hostResource.getMemory().getTotal().doubleValue() / 1024.0 : 0.0);
                vo.setSwapUsed(0.0);
            }
            if (hostResource.getDisk() != null) {
                vo.setDiskUsed(hostResource.getDisk().getUsed() != null
                    ? hostResource.getDisk().getUsed().doubleValue() : 0.0);
                vo.setDiskTotal(hostResource.getDisk().getTotal() != null
                    ? hostResource.getDisk().getTotal().doubleValue() : 0.0);
            }
            if (hostResource.getNetwork() != null) {
                vo.setNetDownSpeed(hostResource.getNetwork().getRxBytes() != null
                    ? hostResource.getNetwork().getRxBytes().doubleValue() / 1024.0 : 0.0);
                vo.setNetUpSpeed(hostResource.getNetwork().getTxBytes() != null
                    ? hostResource.getNetwork().getTxBytes().doubleValue() / 1024.0 : 0.0);
            }
        }
        return vo;
    }

    /**
     * 从 SystemMetricSpec 还原 MonitorStatusVO（缓存命中时使用）。
     */
    private MonitorStatusVO toStatusVOFromSpec(Long instanceId, SystemMetricSpec spec) {
        MonitorStatusVO vo = new MonitorStatusVO();
        vo.setInstanceId(instanceId);
        vo.setTimestamp(spec.getTimestamp());
        vo.setCpuPercent(spec.getCpuPercent());
        vo.setCpuMaxCore(spec.getCpuMaxCore());
        vo.setMemUsed(spec.getMemUsed());
        vo.setMemTotal(spec.getMemTotal());
        vo.setSwapUsed(spec.getSwapUsed());
        vo.setNetUpSpeed(spec.getNetUpSpeed());
        vo.setNetDownSpeed(spec.getNetDownSpeed());
        vo.setDiskUsed(spec.getDiskUsed());
        vo.setDiskTotal(spec.getDiskTotal());
        return vo;
    }

    /**
     * 从扩展存储查询历史 SystemMetricResource（用于 /history，按时间范围过滤）。
     */
    private List<SystemMetricResource> queryResourcesFromDatabase(Long instanceId,
                                                                   LocalDateTime startTime,
                                                                   LocalDateTime endTime) {
        long startEpochMilli = startTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endEpochMilli = endTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        ListOptions opts = ListOptions.builder()
                .specFilter("$.instanceId", "=", instanceId)
                .createdAfter(startEpochMilli)
                .orderBy("creation_timestamp")
                .limit(10000)
                .build();

        List<SystemMetricResource> resources = extensionClient.list(SystemMetricResource.class, opts);
        return resources.stream()
                .filter(r -> r.getMetadata() != null
                        && r.getMetadata().getCreationTimestamp() != null
                        && r.getMetadata().getCreationTimestamp() <= endEpochMilli)
                .collect(Collectors.toList());
    }

    /**
     * 从扩展存储查询历史数据（保持 realtime/trend 端点契约不变，返回 MonitorHistoryVO）。
     */
    private List<MonitorHistoryVO> queryHistoryFromDatabase(Long instanceId,
                                                             LocalDateTime startTime,
                                                             LocalDateTime endTime) {
        long startEpochMilli = startTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endEpochMilli = endTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        ListOptions opts = ListOptions.builder()
                .specFilter("$.instanceId", "=", instanceId)
                .createdAfter(startEpochMilli)
                .orderBy("creation_timestamp")
                .limit(10000)
                .build();

        List<SystemMetricResource> resources = extensionClient.list(SystemMetricResource.class, opts);

        return resources.stream()
                .filter(r -> r.getMetadata() != null
                        && r.getMetadata().getCreationTimestamp() != null
                        && r.getMetadata().getCreationTimestamp() <= endEpochMilli)
                .map(this::toHistoryVO)
                .collect(Collectors.toList());
    }

    /**
     * Resource 转 MonitorHistoryVO
     */
    private MonitorHistoryVO toHistoryVO(SystemMetricResource resource) {
        MonitorHistoryVO vo = new MonitorHistoryVO();
        if (resource.getMetadata() != null && resource.getMetadata().getCreationTimestamp() != null) {
            vo.setId(resource.getMetadata().getCreationTimestamp());
            vo.setCreateTime(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(resource.getMetadata().getCreationTimestamp()),
                    ZoneId.systemDefault()));
        }
        SystemMetricSpec spec = resource.getSpec();
        if (spec != null) {
            vo.setInstanceId(spec.getInstanceId());
            vo.setTimestamp(spec.getTimestamp());
            vo.setCpuPercent(spec.getCpuPercent());
            vo.setCpuMaxCore(spec.getCpuMaxCore());
            vo.setMemUsed(spec.getMemUsed());
            vo.setMemTotal(spec.getMemTotal());
            vo.setSwapUsed(spec.getSwapUsed());
            vo.setNetUpSpeed(spec.getNetUpSpeed());
            vo.setNetDownSpeed(spec.getNetDownSpeed());
            vo.setDiskUsed(spec.getDiskUsed());
            vo.setDiskTotal(spec.getDiskTotal());
        }
        return vo;
    }

    /**
     * 降采样：如果点数超过 maxPoints，按 bucketSize = duration / downsampleTo 分桶，
     * 每桶取各字段 MAX（对齐源项目 monitor.go:366-371）。
     */
    private List<SystemMetricVO> downsampleIfNecessary(List<SystemMetricVO> raw, long startMs, long endMs) {
        int maxPoints = config.getMonitor().getMaxPoints();
        int downsampleTo = config.getMonitor().getDownsampleTo();
        if (raw.size() <= maxPoints || downsampleTo <= 0) {
            return raw;
        }
        long duration = Math.max(1L, endMs - startMs);
        long bucketSize = Math.max(1L, duration / downsampleTo);

        // 按桶聚合：bucketIndex → 聚合结果
        Map<Long, SystemMetricVO> buckets = new LinkedHashMap<>();
        for (SystemMetricVO vo : raw) {
            Long ts = vo.getTimestamp();
            if (ts == null) {
                continue;
            }
            long bucketIndex = (ts - startMs) / bucketSize;
            if (bucketIndex < 0) {
                bucketIndex = 0;
            }
            SystemMetricVO existing = buckets.get(bucketIndex);
            if (existing == null) {
                SystemMetricVO bucket = new SystemMetricVO();
                bucket.setTimestamp(startMs + bucketIndex * bucketSize);
                copyFieldsForMax(bucket, vo);
                buckets.put(bucketIndex, bucket);
            } else {
                mergeMax(existing, vo);
            }
        }
        List<SystemMetricVO> result = new ArrayList<>(buckets.values());
        result.sort(Comparator.comparing(SystemMetricVO::getTimestamp));
        return result;
    }

    /**
     * 初始化桶：直接复制首个样本的字段。
     */
    private void copyFieldsForMax(SystemMetricVO target, SystemMetricVO source) {
        target.setCpuPercent(source.getCpuPercent());
        target.setCpuMaxCore(source.getCpuMaxCore());
        target.setMemUsed(source.getMemUsed());
        target.setMemTotal(source.getMemTotal());
        target.setSwapUsed(source.getSwapUsed());
        target.setNetUpSpeed(source.getNetUpSpeed());
        target.setNetDownSpeed(source.getNetDownSpeed());
        target.setDiskUsed(source.getDiskUsed());
        target.setDiskTotal(source.getDiskTotal());
    }

    /**
     * 合并桶：每字段取 MAX（null 视为 0）。
     */
    private void mergeMax(SystemMetricVO target, SystemMetricVO source) {
        target.setCpuPercent(maxOrNull(target.getCpuPercent(), source.getCpuPercent()));
        target.setCpuMaxCore(maxOrNull(target.getCpuMaxCore(), source.getCpuMaxCore()));
        target.setMemUsed(maxOrNull(target.getMemUsed(), source.getMemUsed()));
        target.setMemTotal(maxOrNull(target.getMemTotal(), source.getMemTotal()));
        target.setSwapUsed(maxOrNull(target.getSwapUsed(), source.getSwapUsed()));
        target.setNetUpSpeed(maxOrNull(target.getNetUpSpeed(), source.getNetUpSpeed()));
        target.setNetDownSpeed(maxOrNull(target.getNetDownSpeed(), source.getNetDownSpeed()));
        target.setDiskUsed(maxOrNull(target.getDiskUsed(), source.getDiskUsed()));
        target.setDiskTotal(maxOrNull(target.getDiskTotal(), source.getDiskTotal()));
    }

    private Double maxOrNull(Double a, Double b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.max(a, b);
    }
}

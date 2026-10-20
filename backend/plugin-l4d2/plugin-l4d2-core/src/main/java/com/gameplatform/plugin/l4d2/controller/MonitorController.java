package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.dto.MonitorHistoryQueryDTO;
import com.gameplatform.plugin.l4d2.service.MonitorCollectorService;
import com.gameplatform.plugin.l4d2.service.MonitorMetricFileStore;
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
import java.util.List;
import java.util.Map;

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
    private final MonitorCollectorService monitorCollectorService;
    private final MonitorMetricFileStore metricFileStore;
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
        SystemMetricVO cached = monitorCollectorService.getLatestMetric(instanceId);
        if (cached != null) {
            MonitorStatusVO vo = toStatusVO(instanceId, cached);
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

        long startMs = startTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endMs = endTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        List<SystemMetricVO> result = metricFileStore.query(queryDTO.getInstanceId(), startMs, endMs);
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

        List<MonitorHistoryVO> historyList = queryHistoryFromFiles(instanceId, startTime, endTime);
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

        List<MonitorHistoryVO> historyList = queryHistoryFromFiles(instanceId, startTime, endTime);
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

        List<MonitorHistoryVO> historyList = queryHistoryFromFiles(instanceId, startTime, endTime);
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

        List<MonitorHistoryVO> historyList = queryHistoryFromFiles(instanceId, startTime, endTime);
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

    /** 从最新指标还原 MonitorStatusVO。 */
    private MonitorStatusVO toStatusVO(Long instanceId, SystemMetricVO metric) {
        MonitorStatusVO vo = new MonitorStatusVO();
        vo.setInstanceId(instanceId);
        vo.setTimestamp(metric.getTimestamp());
        vo.setCpuPercent(metric.getCpuPercent());
        vo.setCpuMaxCore(metric.getCpuMaxCore());
        vo.setMemUsed(metric.getMemUsed());
        vo.setMemTotal(metric.getMemTotal());
        vo.setSwapUsed(metric.getSwapUsed());
        vo.setNetUpSpeed(metric.getNetUpSpeed());
        vo.setNetDownSpeed(metric.getNetDownSpeed());
        vo.setDiskUsed(metric.getDiskUsed());
        vo.setDiskTotal(metric.getDiskTotal());
        return vo;
    }

    /** 从临时文件查询历史数据，保持 realtime/trend 端点契约不变。 */
    private List<MonitorHistoryVO> queryHistoryFromFiles(Long instanceId,
                                                          LocalDateTime startTime,
                                                          LocalDateTime endTime) {
        long startEpochMilli = startTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endEpochMilli = endTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return metricFileStore.query(instanceId, startEpochMilli, endEpochMilli).stream()
                .map(metric -> toHistoryVO(instanceId, metric))
                .toList();
    }

    /** 临时文件指标转 MonitorHistoryVO。 */
    private MonitorHistoryVO toHistoryVO(Long instanceId, SystemMetricVO metric) {
        MonitorHistoryVO vo = new MonitorHistoryVO();
        if (metric.getTimestamp() != null) {
            vo.setId(metric.getTimestamp());
            vo.setCreateTime(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(metric.getTimestamp()),
                    ZoneId.systemDefault()));
        }
        vo.setInstanceId(instanceId);
        vo.setTimestamp(metric.getTimestamp());
        vo.setCpuPercent(metric.getCpuPercent());
        vo.setCpuMaxCore(metric.getCpuMaxCore());
        vo.setMemUsed(metric.getMemUsed());
        vo.setMemTotal(metric.getMemTotal());
        vo.setSwapUsed(metric.getSwapUsed());
        vo.setNetUpSpeed(metric.getNetUpSpeed());
        vo.setNetDownSpeed(metric.getNetDownSpeed());
        vo.setDiskUsed(metric.getDiskUsed());
        vo.setDiskTotal(metric.getDiskTotal());
        return vo;
    }
}

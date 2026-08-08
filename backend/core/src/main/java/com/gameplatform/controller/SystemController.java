package com.gameplatform.controller;

import com.gameplatform.annotation.OperationLog;
import com.gameplatform.common.result.PageResult;
import com.gameplatform.common.result.Result;
import com.gameplatform.dto.PageQueryDTO;
import com.gameplatform.service.LogService;
import com.gameplatform.vo.LogVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 系统设置控制器
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Tag(name = "系统管理", description = "系统相关接口")
@RestController
@RequestMapping("/system")
@RequiredArgsConstructor
@Validated
public class SystemController {

    private final LogService logService;

    /**
     * 健康检查
     */
    @Operation(summary = "健康检查", description = "系统健康检查接口")
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> data = new HashMap<>();
        data.put("status", "UP");
        data.put("timestamp", LocalDateTime.now());
        data.put("version", "1.0.0");
        return Result.success(data);
    }

    /**
     * 获取系统信息
     */
    @Operation(summary = "获取系统信息", description = "获取系统基本信息")
    @GetMapping("/info")
    public Result<Map<String, Object>> info() {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "Game Platform Manager");
        data.put("version", "1.0.0");
        data.put("description", "游戏服务器统一管理平台");
        data.put("javaVersion", System.getProperty("java.version"));
        data.put("javaVendor", System.getProperty("java.vendor"));
        data.put("osName", System.getProperty("os.name"));
        data.put("osVersion", System.getProperty("os.version"));
        data.put("osArch", System.getProperty("os.arch"));
        data.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        data.put("maxMemory", Runtime.getRuntime().maxMemory() / 1024 / 1024 + "MB");
        data.put("totalMemory", Runtime.getRuntime().totalMemory() / 1024 / 1024 + "MB");
        data.put("freeMemory", Runtime.getRuntime().freeMemory() / 1024 / 1024 + "MB");
        return Result.success(data);
    }

    /**
     * 获取系统设置
     */
    @Operation(summary = "获取系统设置", description = "获取系统配置信息")
    @GetMapping("/settings")
    public Result<SystemSettingsVO> getSettings() {
        SystemSettingsVO settings = new SystemSettingsVO();
        // 实际应从配置文件或数据库读取
        settings.setSiteName("Game Platform Manager");
        settings.setSiteDescription("游戏服务器统一管理平台");
        settings.setLogLevel("INFO");
        settings.setSessionTimeout(30);
        settings.setMaxUploadSize(100);
        settings.setBackupEnabled(true);
        settings.setBackupRetentionDays(30);
        return Result.success(settings);
    }

    /**
     * 更新系统设置
     */
    @Operation(summary = "更新系统设置", description = "更新系统配置信息")
    @PutMapping("/settings")
    @OperationLog(type = "UPDATE", target = "SYSTEM", description = "更新系统设置")
    public Result<Void> updateSettings(@RequestBody SystemSettingsVO settings) {
        // 实际应保存到配置文件或数据库
        return Result.success();
    }

    /**
     * 获取操作日志(分页)
     */
    @Operation(summary = "获取操作日志", description = "分页获取操作日志")
    @GetMapping("/logs")
    public Result<PageResult<LogVO>> getLogs(PageQueryDTO queryDTO) {
        PageResult<LogVO> result = logService.pageLogs(queryDTO);
        return Result.success(result);
    }

    /**
     * 导出操作日志(CSV)
     */
    @Operation(summary = "导出操作日志", description = "按查询条件导出操作日志为CSV")
    @GetMapping("/logs/export")
    public ResponseEntity<byte[]> exportLogs(PageQueryDTO queryDTO) {
        PageQueryDTO exportQuery = new PageQueryDTO();
        exportQuery.setCurrent(1);
        exportQuery.setSize(10000);
        exportQuery.setKeyword(queryDTO.getKeyword());

        PageResult<LogVO> result = logService.pageLogs(exportQuery);
        byte[] csv = buildCsv(result.getRecords());

        String filename = "operation-logs-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=utf-8"))
                .body(csv);
    }

    /**
     * 获取最近操作日志
     */
    @Operation(summary = "获取最近操作日志", description = "获取最近N条操作日志")
    @GetMapping("/logs/recent")
    public Result<List<LogVO>> getRecentLogs(@RequestParam(defaultValue = "10") Integer limit) {
        List<LogVO> logs = logService.getRecentLogs(limit);
        return Result.success(logs);
    }

    /**
     * 根据操作人获取日志
     */
    @Operation(summary = "根据操作人获取日志", description = "根据操作人获取操作日志")
    @GetMapping("/logs/operator/{operator}")
    public Result<List<LogVO>> getLogsByOperator(@PathVariable String operator) {
        List<LogVO> logs = logService.getLogsByOperator(operator);
        return Result.success(logs);
    }

    /**
     * 根据操作类型获取日志
     */
    @Operation(summary = "根据操作类型获取日志", description = "根据操作类型获取操作日志")
    @GetMapping("/logs/type/{type}")
    public Result<List<LogVO>> getLogsByType(@PathVariable String type) {
        List<LogVO> logs = logService.getLogsByOperationType(type);
        return Result.success(logs);
    }

    /**
     * 清理系统缓存
     */
    @Operation(summary = "清理系统缓存", description = "清理系统缓存")
    @PostMapping("/cache/clear")
    @OperationLog(type = "CLEAR", target = "SYSTEM", description = "清理系统缓存")
    public Result<Void> clearCache() {
        // 清理缓存逻辑
        return Result.success();
    }

    /**
     * 获取系统统计信息
     */
    @Operation(summary = "获取系统统计信息", description = "获取系统统计数据")
    @GetMapping("/statistics")
    public Result<SystemStatisticsVO> getStatistics() {
        SystemStatisticsVO statistics = new SystemStatisticsVO();
        // 实际应从数据库统计
        statistics.setTotalHosts(0L);
        statistics.setOnlineHosts(0L);
        statistics.setTotalInstances(0L);
        statistics.setRunningInstances(0L);
        statistics.setTotalGames(0L);
        statistics.setTotalPlugins(0L);
        statistics.setEnabledPlugins(0L);
        statistics.setTodayLogins(0L);
        statistics.setTodayOperations(0L);
        return Result.success(statistics);
    }

    private byte[] buildCsv(List<LogVO> logs) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append("操作时间,操作人,操作类型,操作对象,操作内容,结果,IP地址,错误信息\n");

        for (LogVO log : logs) {
            sb.append(escapeCsv(log.getCreateTime() != null ? log.getCreateTime().format(formatter) : ""))
                    .append(",")
                    .append(escapeCsv(log.getOperator()))
                    .append(",")
                    .append(escapeCsv(log.getOperationType()))
                    .append(",")
                    .append(escapeCsv(log.getOperationTarget()))
                    .append(",")
                    .append(escapeCsv(log.getOperationContent()))
                    .append(",")
                    .append(escapeCsv(log.getOperationResult()))
                    .append(",")
                    .append(escapeCsv(log.getIpAddress()))
                    .append(",")
                    .append(escapeCsv(log.getErrorMessage()))
                    .append("\n");
        }

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // ========== VO ==========

    /**
     * 系统设置VO
     */
    @Data
    public static class SystemSettingsVO {
        /**
         * 站点名称
         */
        private String siteName;

        /**
         * 站点描述
         */
        private String siteDescription;

        /**
         * 日志级别
         */
        private String logLevel;

        /**
         * 会话超时时间(分钟)
         */
        private Integer sessionTimeout;

        /**
         * 最大上传大小(MB)
         */
        private Integer maxUploadSize;

        /**
         * 是否启用备份
         */
        private Boolean backupEnabled;

        /**
         * 备份保留天数
         */
        private Integer backupRetentionDays;
    }

    /**
     * 系统统计VO
     */
    @Data
    public static class SystemStatisticsVO {
        /**
         * 主机总数
         */
        private Long totalHosts;

        /**
         * 在线主机数
         */
        private Long onlineHosts;

        /**
         * 实例总数
         */
        private Long totalInstances;

        /**
         * 运行中实例数
         */
        private Long runningInstances;

        /**
         * 游戏总数
         */
        private Long totalGames;

        /**
         * 插件总数
         */
        private Long totalPlugins;

        /**
         * 启用插件数
         */
        private Long enabledPlugins;

        /**
         * 今日登录次数
         */
        private Long todayLogins;

        /**
         * 今日操作次数
         */
        private Long todayOperations;
    }

}

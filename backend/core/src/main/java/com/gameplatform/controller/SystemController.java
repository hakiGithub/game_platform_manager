package com.gameplatform.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.common.result.Result;
import com.gameplatform.dto.PageQueryDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
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
@Slf4j
@RestController
@RequestMapping("/system")
@RequiredArgsConstructor
@Validated
public class SystemController {

    private static final List<String> SETTING_GROUPS = List.of("platform", "ssh", "docker");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 读取一个设置分组的持久化 JSON（表/行不存在时返回空 Map，由前端表单默认值兜底）
     */
    private Map<String, Object> readSettingGroup(String group) {
        try {
            String json = jdbcTemplate.queryForObject(
                    "SELECT setting_value FROM sys_setting WHERE setting_group = ?",
                    String.class, group);
            if (json == null || json.isEmpty()) {
                return new HashMap<>();
            }
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("读取系统设置分组失败（按未持久化处理）: group={}, err={}", group, e.getMessage());
            return new HashMap<>();
        }
    }

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
     *
     * <p>按分组（platform/ssh/docker）返回持久化配置，组内为前端表单字段的原样 JSON；
     * 未持久化过的分组返回空对象，由前端表单本地默认值兜底。
     */
    @Operation(summary = "获取系统设置", description = "获取系统配置信息")
    @GetMapping("/settings")
    public Result<Map<String, Object>> getSettings() {
        Map<String, Object> result = new HashMap<>();
        for (String group : SETTING_GROUPS) {
            result.put(group, readSettingGroup(group));
        }
        return Result.success(result);
    }

    /**
     * 更新系统设置
     *
     * <p>请求体携带 type（platform/ssh/docker）与该分组的表单字段，按分组整体持久化。
     */
    @Operation(summary = "更新系统设置", description = "更新系统配置信息")
    @PutMapping("/settings")
    public Result<Void> updateSettings(@RequestBody Map<String, Object> body) {
        Object typeObj = body.remove("type");
        String type = typeObj == null ? null : typeObj.toString();
        if (type == null || !SETTING_GROUPS.contains(type)) {
            throw new BusinessException("未知的设置分组: " + type);
        }
        Map<String, Object> merged = new HashMap<>(readSettingGroup(type));
        merged.putAll(body);
        try {
            String json = objectMapper.writeValueAsString(merged);
            int exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_setting WHERE setting_group = ?",
                    Integer.class, type);
            if (exists > 0) {
                jdbcTemplate.update(
                        "UPDATE sys_setting SET setting_value = ? WHERE setting_group = ?",
                        json, type);
            } else {
                jdbcTemplate.update(
                        "INSERT INTO sys_setting (setting_group, setting_value) VALUES (?, ?)",
                        type, json);
            }
            log.info("系统设置已持久化: 分组={}, 字段数={}", type, merged.size());
            return Result.success();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("系统设置持久化失败: 分组={}, err={}", type, e.getMessage(), e);
            throw new BusinessException("系统设置保存失败: " + e.getMessage());
        }
    }

    /**
     * 清理系统缓存
     */
    @Operation(summary = "清理系统缓存", description = "清理系统缓存")
    @PostMapping("/cache/clear")
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

    // ========== VO ==========

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

package com.gameplatform.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 任务统计 VO（ADR-015 多维聚合）
 *
 * <p>用于 {@code GET /api/tasks/stats} 接口返回任务分布统计。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class TaskStatsVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 按状态聚合：{PENDING: 3, RUNNING: 2, COMPLETED: 150, FAILED: 12, CANCELLED: 5}
     */
    private Map<String, Long> statusCounts;

    /**
     * 按来源聚合：{MAIN: 80, L4D2: 92}
     */
    private Map<String, Long> sourceCounts;

    /**
     * 按类型聚合：{crawl: 50, deploy: 80, backup: 30, restart: 12}
     */
    private Map<String, Long> typeCounts;

    /**
     * 任务总数
     */
    private Long total;
}

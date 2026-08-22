package com.gameplatform.plugin.schedule;

import lombok.Data;

import java.util.Map;

/**
 * 定时计划更新请求（编程式，ADR-0011 D5）
 *
 * <p>仅允许修改 name / cron / payload；enabled 走独立启停接口，
 * handlerKey 创建后不可变（变更处理器语义应删除重建）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class ScheduleUpdateRequest {

    /**
     * 计划名称（null 表示不修改）
     */
    private String name;

    /**
     * cron 表达式（null 表示不修改）
     */
    private String cron;

    /**
     * payload 模板（null 表示不修改）
     */
    private Map<String, Object> payload;
}

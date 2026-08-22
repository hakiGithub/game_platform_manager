package com.gameplatform.plugin.schedule;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 定时计划创建请求（编程式，ADR-0011 D5）
 *
 * <p>source 与 pluginId 由 {@link ScheduleService} 实现按调用上下文自动填充，
 * 插件无法伪造来源。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleCreateRequest {

    /**
     * 计划名称（必填）
     */
    private String name;

    /**
     * 处理器 key（必填；创建时不校验注册表，触发时才解析）
     */
    private String handlerKey;

    /**
     * cron 表达式（必填，标准 6 位，Spring 语法）
     */
    private String cron;

    /**
     * payload 模板（可选）
     */
    private Map<String, Object> payload;

    /**
     * 是否启用（缺省 true）
     */
    private Boolean enabled;
}

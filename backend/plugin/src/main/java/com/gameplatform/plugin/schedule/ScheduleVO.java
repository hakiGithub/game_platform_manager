package com.gameplatform.plugin.schedule;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 定时计划 VO（ADR-0011）
 *
 * <p>由 {@link ScheduleService} 的查询接口返回。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class ScheduleVO {

    private String id;

    /**
     * 计划名称
     */
    private String name;

    /**
     * 处理器 key
     */
    private String handlerKey;

    /**
     * 处理器显示名称（从 Handler 注册表实时解析；未注册为 null）
     */
    private String handlerName;

    /**
     * cron 表达式
     */
    private String cron;

    /**
     * payload 模板（反序列化后的 JSON 对象）
     */
    private Map<String, Object> payload;

    /**
     * 用户启用意图
     */
    private Boolean enabled;

    /**
     * 系统暂停（如插件停用）
     */
    private Boolean paused;

    /**
     * 暂停原因
     */
    private String pauseReason;

    /**
     * 来源（MAIN / {gameCode} 大写）
     */
    private String source;

    /**
     * 插件ID（MAIN 来源为 null）
     */
    private String pluginId;

    /**
     * 声明稳定键（pluginId:key，声明式计划才有）
     */
    private String declarationKey;

    /**
     * 下次触发时间（禁用/暂停时为 null；按 cron 实时计算）
     */
    private LocalDateTime nextFireTime;

    /**
     * 上次触发结果状态（SUCCEEDED/FAILED/CANCELLED/SKIPPED；从未触发为 null）
     */
    private String lastRunStatus;

    /**
     * 上次触发时间
     */
    private LocalDateTime lastRunTime;

    /**
     * 用户是否修改过（声明式计划被 API 改过后，插件声明 upsert 跳过）
     */
    private Boolean userModified;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

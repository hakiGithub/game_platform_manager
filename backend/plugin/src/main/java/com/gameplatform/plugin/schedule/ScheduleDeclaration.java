package com.gameplatform.plugin.schedule;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 定时计划声明（ADR-0011 D5）
 *
 * <p>插件通过 {@link ScheduledTaskDeclarationExtension#getScheduleDeclarations()}
 * 返回声明列表，主应用在插件加载时按稳定键 {@code pluginId:key} upsert 进
 * {@code scheduled_task} 表（作为插件的默认计划）。
 *
 * <p>upsert 冲突语义（防"插件重启覆盖用户修改"）：
 * <ul>
 *   <li>用户改过的计划（cron / enabled 等被 API 修改，user_modified=1）整体跳过</li>
 *   <li>用户删除的计划不复活（检测逻辑删除墓碑）</li>
 *   <li>未修改过的计划随声明演进更新（name / cron / payload / handlerKey）</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleDeclaration {

    /**
     * 声明稳定键（同插件内唯一）。upsert 主键 = pluginId:key
     */
    private String key;

    /**
     * 计划显示名称
     */
    private String name;

    /**
     * 引用的处理器 key（须与本插件注册的 {@link ScheduledTaskHandler#getKey()} 一致；
     * 创建时不校验，触发时才解析，未注册则记 FAILED run）
     */
    private String handlerKey;

    /**
     * cron 表达式（标准 6 位，Spring CronTrigger 语法，服务器时区）
     */
    private String cron;

    /**
     * payload 模板（每次触发以快照形式传给 Handler）
     */
    private Map<String, Object> payload;

    /**
     * 默认是否启用（缺省 true）
     */
    @Builder.Default
    private Boolean enabled = Boolean.TRUE;
}

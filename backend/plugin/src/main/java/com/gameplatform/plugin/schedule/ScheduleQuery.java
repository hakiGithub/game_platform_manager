package com.gameplatform.plugin.schedule;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 定时计划查询条件（ADR-0011）
 *
 * <p>插件侧查询自动限定本插件 source；管理侧（REST）可跨来源查询。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleQuery {

    /**
     * 来源筛选（大写；插件侧忽略此字段强制绑定本插件 source）
     */
    private String source;

    /**
     * 处理器 key 筛选
     */
    private String handlerKey;

    /**
     * 名称模糊筛选
     */
    private String keyword;

    /**
     * 启用状态筛选：true / false / null（全部）
     */
    private Boolean enabled;

    private Integer page;

    private Integer size;
}

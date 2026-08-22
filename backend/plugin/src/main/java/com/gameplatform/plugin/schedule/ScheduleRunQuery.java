package com.gameplatform.plugin.schedule;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 触发记录查询条件（ADR-0011）
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleRunQuery {

    /**
     * 计划ID（必填）
     */
    private String scheduleId;

    /**
     * 状态筛选：RUNNING / SUCCEEDED / FAILED / CANCELLED / SKIPPED
     */
    private String status;

    private Integer page;

    private Integer size;
}

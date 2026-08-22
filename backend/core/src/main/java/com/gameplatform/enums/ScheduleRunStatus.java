package com.gameplatform.enums;

/**
 * 定时触发记录状态枚举（ADR-0011）
 *
 * <p>状态机流转规则：RUNNING → SUCCEEDED / FAILED / CANCELLED / SKIPPED，
 * 终态不可变。
 *
 * <p>与任务中心 {@link TaskStatus} 的差异：无 PENDING（触发即执行或跳过）；
 * SKIPPED 表示计划级重叠跳过（上一轮仍在执行）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public enum ScheduleRunStatus {

    /** 执行中 */
    RUNNING,

    /** 成功（终态） */
    SUCCEEDED,

    /** 失败（终态） */
    FAILED,

    /** 已取消（终态） */
    CANCELLED,

    /** 重叠跳过（终态，原因见 error_message） */
    SKIPPED;

    /**
     * 判断是否为终态（不可变更）
     */
    public boolean isTerminal() {
        return this != RUNNING;
    }

    /**
     * 解析状态字符串（容错：未知值返回 null）
     */
    public static ScheduleRunStatus parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

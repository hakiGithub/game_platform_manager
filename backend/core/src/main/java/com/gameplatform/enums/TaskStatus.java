package com.gameplatform.enums;

/**
 * 任务状态枚举
 *
 * <p>状态机流转规则（{@link #canTransitionTo(TaskStatus)}）：
 * <ul>
 *   <li>PENDING → RUNNING / CANCELLED / FAILED</li>
 *   <li>RUNNING → COMPLETED / FAILED / CANCELLED</li>
 *   <li>COMPLETED / FAILED / CANCELLED → 不可变</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public enum TaskStatus {

    /** 待执行（已提交，未开始） */
    PENDING,

    /** 执行中 */
    RUNNING,

    /** 已完成（终态） */
    COMPLETED,

    /** 已失败（终态） */
    FAILED,

    /** 已取消（终态） */
    CANCELLED;

    /**
     * 判断是否为终态（不可变更）
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /**
     * 校验状态机流转是否合法
     *
     * @param next 下一状态
     * @return true 表示允许流转
     */
    public boolean canTransitionTo(TaskStatus next) {
        switch (this) {
            case PENDING:
                return next == RUNNING || next == CANCELLED || next == FAILED;
            case RUNNING:
                return next == COMPLETED || next == FAILED || next == CANCELLED;
            default:
                return false; // 终态不可变更
        }
    }
}

package com.gameplatform.plugin.task;

/**
 * 任务执行上下文
 *
 * <p>由主应用 core 模块创建（{@code TaskContextImpl}），传递给 {@link TaskHandler#execute}。
 * 提供进度上报、取消/超时检查、日志记录能力。
 *
 * <p>实现要点（ADR-019 线程安全策略）：
 * <ul>
 *   <li>{@code cancelled} / {@code timeout} 使用 volatile 标志位</li>
 *   <li>{@code reportProgress} 内部 1s 节流（ADR-014）</li>
 *   <li>{@code log} 加入内部 ConcurrentLinkedQueue 缓冲，每 1s 批量刷盘</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface TaskContext {

    /** 任务ID */
    String getTaskId();

    /** 任务类型 */
    String getTaskType();

    /** 任务来源（MAIN / L4D2 / {gameCode}） */
    String getSource();

    /** 作用域键（如 instanceId） */
    String getScopeKey();

    /**
     * 上报进度。
     *
     * <p>实现内部已节流（ADR-014）：
     * <ul>
     *   <li>相同 percent 不重复写 DB</li>
     *   <li>不同 percent 距上次写入 < 1s 仅更新内存</li>
     *   <li>percent=100 或任务终态强制刷盘</li>
     * </ul>
     * Handler 可放心高频调用，无需自行节流。
     *
     * @param percent 完成百分比 0-100
     * @param message 进度描述文本（如 "已处理 20/40 页"）
     */
    void reportProgress(int percent, String message);

    /**
     * 检查任务是否被取消。
     *
     * <p>Handler 应在循环中定期调用此方法，返回 true 时应清理资源并退出。
     *
     * @return true 表示用户已请求取消
     */
    boolean isCancelled();

    /**
     * 检查任务是否超时（ADR-009 协作式超时）。
     *
     * <p>任务执行时间超过 {@link TaskHandler#getDefaultTimeoutMs()} 后返回 true。
     * Handler 应在循环中调用此方法，返回 true 时应清理资源并退出。
     * 退出后还有 30s grace period 由 TaskServiceImpl 兜底强制中断。
     *
     * @return true 表示任务已超时
     */
    boolean isTimeout();

    /**
     * 记录任务日志（INFO 级别）。
     *
     * <p>实现内部缓冲日志，每 1s 批量刷盘（ADR-023）。
     * 每个任务最多保留 500 条，超出按时间倒序保留最新 500 条（ADR-010）。
     *
     * @param message 日志消息
     */
    void log(String message);

    /**
     * 记录任务日志（指定级别）。
     *
     * @param level   日志级别：INFO / WARN / ERROR
     * @param message 日志消息
     */
    void log(String level, String message);
}

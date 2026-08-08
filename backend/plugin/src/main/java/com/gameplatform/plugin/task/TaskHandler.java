package com.gameplatform.plugin.task;

/**
 * 任务处理器接口
 *
 * <p>由各业务方实现，定义任务的具体执行逻辑和生命周期钩子。
 * <p>插件通过 {@link TaskHandlerExtension} 注册；主应用通过 Spring Bean 注册。
 *
 * <p>实现要求（ADR-032）：
 * <ul>
 *   <li>Handler 必须无状态（依赖通过构造注入，状态通过 {@link TaskContext} 传递）</li>
 *   <li>不要在 execute 中捕获 InterruptedException 后吞掉，应重新设置中断标志并退出</li>
 *   <li>不要在 finally 中调用 ctx.reportProgress（终态已强制刷盘）</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface TaskHandler {

    /**
     * 任务类型标识（同一 source 内唯一）。如 "crawl"、"deploy"、"backup"。
     */
    String getType();

    /**
     * 任务类型显示名称（固定中文，ADR-031）。如 "地图爬取"、"实例部署"。
     */
    String getDisplayName();

    /**
     * 是否允许重试。返回 false 时任务中心不显示重试按钮。
     */
    boolean isRetryable();

    /**
     * 最大重试次数（ADR-012）。默认 3，超过后任务中心不再显示重试按钮，后端 retry API 抛异常。
     * <p>部署类有副作用的任务建议返回 1；爬取类幂等任务可适当提高。
     */
    default int getMaxRetryCount() {
        return 3;
    }

    /**
     * 默认超时时间（毫秒）。0 表示不超时。
     * <p>
     * 超时后流程（ADR-009 混合模式）：
     * <ol>
     *   <li>TaskContext 设置 timeoutFlag=true，Handler 应在循环中检查 ctx.isTimeout() 主动退出</li>
     *   <li>超时阈值后再等 30s grace period，仍不结束则 Future.cancel(true) 强制中断</li>
     *   <li>状态置 FAILED + "任务执行超时"</li>
     * </ol>
     */
    long getDefaultTimeoutMs();

    /**
     * 计算任务的互斥键（ADR-011）。返回 null 表示按默认规则推导：
     * <ul>
     *   <li>scopeKey 非空：默认按 (taskType, scopeKey) 互斥</li>
     *   <li>scopeKey 为空：默认按 (source, taskType) 互斥</li>
     * </ul>
     * <p>返回非 null 字符串时覆盖默认逻辑，相同 mutexKey 的任务互斥。
     * 典型用途：备份任务按 "hostId+instanceId" 组合互斥。
     * <p>返回空字符串 "" 表示完全不互斥（允许多个并发）。
     */
    default String getMutexKey(TaskPayload payload) {
        return null;
    }

    /**
     * 执行任务（核心方法）。
     *
     * <p>实现要点：
     * <ol>
     *   <li>通过 {@link TaskContext#reportProgress} 上报进度（内部已节流，可放心调用）</li>
     *   <li>在循环中调用 {@link TaskContext#isCancelled()} 检查取消</li>
     *   <li>在循环中调用 {@link TaskContext#isTimeout()} 检查超时</li>
     *   <li>通过 {@link TaskContext#log} 记录关键节点日志</li>
     *   <li>返回 {@link TaskResult} 包装执行结果</li>
     * </ol>
     *
     * @param context 任务上下文
     * @param payload  任务参数
     * @return 执行结果
     * @throws Exception 执行异常（将自动记录到 error_message）
     */
    TaskResult execute(TaskContext context, TaskPayload payload) throws Exception;

    /**
     * 生成任务结果摘要（一句话，列表页展示，ADR-016）。
     * 默认实现返回 null，前端回退到 result JSON 折叠展示。
     * <p>
     * 示例：
     * <ul>
     *   <li>爬虫：return "成功爬取 805 张地图"</li>
     *   <li>部署：return "实例已启动，containerId=abc123"</li>
     *   <li>备份：return "备份文件 backup-20260802.tar.gz 已生成"</li>
     * </ul>
     */
    default String getResultSummary(TaskResult result) {
        return null;
    }

    // ========== 生命周期钩子（默认空实现，按需覆写）==========

    /**
     * 提交前钩子（同步调用，在 submit 线程中执行）。
     * 用于参数校验、payload 改写。抛异常将阻止任务提交。
     */
    default void onSubmit(TaskSubmitContext ctx) {
    }

    /**
     * 执行前钩子（在异步执行线程中，execute 之前调用）。
     * 用于资源准备、权限检查。
     */
    default void onBeforeExecute(TaskContext context, TaskPayload payload) {
    }

    /**
     * 执行后钩子（在异步执行线程中，execute 之后调用，无论成功失败）。
     * 用于资源清理。
     */
    default void onAfterExecute(TaskContext context, TaskPayload payload, TaskResult result) {
    }

    /**
     * 成功后钩子。
     * 用于触发后续操作、通知。
     */
    default void onSuccess(TaskContext context, TaskPayload payload, TaskResult result) {
    }

    /**
     * 失败后钩子。
     * 用于告警、记录详情。
     */
    default void onFailure(TaskContext context, TaskPayload payload, Throwable error) {
    }

    /**
     * 取消后钩子。
     * 用于释放资源。
     */
    default void onCancel(TaskContext context, TaskPayload payload) {
    }

    /**
     * 重试提交前钩子（同步调用）。
     * 用于状态检查、参数修正。抛异常将阻止重试。
     */
    default void onRetry(TaskContext context, TaskPayload payload) {
    }
}

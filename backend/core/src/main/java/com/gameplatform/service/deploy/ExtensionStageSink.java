package com.gameplatform.service.deploy;

/**
 * 扩展阶段向部署主流程回传的接缝（design.md §14.6 / §14.7，B-09 ↔ B-10 的边界）。
 *
 * <p>执行器不持有 {@code DeployService} 的内存态任务对象：它只经本接口产行、报进度。
 * 两条通道按 §14.7 v0.3 的订正分开——{@link #reportProgress} 只动顶层
 * {@code stage}/{@code progress}，<b>不产生日志行</b>（{@code updateTaskStatus} 一路的既有语义），
 * V-10 的核对对象正是这个顶层 {@code progress} 值。</p>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface ExtensionStageSink {

    /**
     * 产一行。行的 {@code stage} 由执行器按 §14.6 钉值填写：
     * 扩展阶段行取常量 {@code "EXTENSION"}，「目录不合法说明行」填它实际发生所在的既有阶段。
     */
    void append(ExtensionLogLine line);

    /**
     * 报扩展阶段的顶层进度（§14.7 的条件分配：起点 80，其后 {@code 80 + floor(4 × i / total)}，上限 84）。
     *
     * <p>只更新 {@code DeployTaskStatus} 的 {@code stage}/{@code progress}，不产日志行、
     * 不改 {@code completed}/{@code success}/{@code error}。</p>
     */
    void reportProgress(int progress);
}

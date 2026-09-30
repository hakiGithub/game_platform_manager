package com.gameplatform.plugin.patch;

/**
 * 补丁安装服务 SPI（ADR-0006）
 *
 * <p>接口在插件 SDK 模块（供插件子容器注入），实现在 core 模块。
 * 把资源 URL 推送到目标实例指定位置：压缩包解压后推送，非压缩包直接推送。
 * 决策树（探测 + isLanHost 门控 + 宿主机/容器路由）见 ADR-0006。</p>
 *
 * <p>执行模型：{@link #install} 异步提交任务中心任务（source=MAIN、taskType=PATCH_INSTALL）
 * 返回 taskId；插件经 {@code TaskService.getTask/getTaskLogs} 轮询进度，
 * {@code cancelMyOwn} 取消。{@link #installSync} 是同一执行器的阻塞入口，
 * 供部署扩展阶段按声明序逐步执行（ADR-0029）。</p>
 */
public interface PatchInstallService {

    /**
     * 提交补丁安装任务。
     *
     * <p><b>异步提交路径丢字段（RISK-D05，本期不修）</b>：本方法经任务中心载荷重建请求，
     * 只透传 {@code instanceId/url/targetPath/format/sha256} 五项，因此
     * {@code includePattern} 与 {@code headers} 在此路径上<b>不生效</b>
     * （design.md §14.2）。需要 {@code includePattern}（限定压缩包内落位范围）时请用
     * {@link #installSync(PatchInstallRequest, PatchInstallProgressListener)}——它把请求
     * <b>对象引用</b>直接交给执行器，不经 JSON 往返。{@code headers} 本期不在声明模型内，
     * 两条路径都不承载（PRD §8.2 硬约束）。</p>
     *
     * @param request 安装请求
     * @return 任务 ID（任务中心）
     * @throws com.gameplatform.common.exception.BusinessException 参数非法
     */
    String install(PatchInstallRequest request);

    /**
     * 同步安装补丁：调用方线程内阻塞到该次安装完成或失败（ADR-0029、design.md §14.1）。
     *
     * <p>为部署扩展阶段而设——补丁步骤必须在上一步判定完成前阻塞返回（FR-12），
     * 且失败语义由步骤致命性决定，不能靠轮询任务中心（部署主流程本就不在任务中心，D-N05）。
     * 与 {@link #install} 走<b>同一个</b>宿主执行器、同一份代码路径，只是从「提交给任务中心」
     * 换成「调用方等待」，不是第二套补丁实现（FR-14 红线）。</p>
     *
     * <p>资源约束（PRD §14.2 行 1 / design.md §8.2、§14.14）：全局并发闸 3、
     * 可重试错误自动重试 2 次（退避 5s/20s）、SSH 600 s 随执行器一并继承；
     * <b>每宿主机互斥</b>由本方法的宿主实现承任务中心同一个内存键 {@code PATCH_INSTALL:<hostId>}
     * （等待预算 600 s、{@code finally} 释放）。调用方<b>不得</b>在外面再套一层重试。</p>
     *
     * <p>默认实现抛异常而非返回成功：静默假装装完会让「补丁没打上却交付一个能跑的错版本」
     * 成为可达状态（ADR-0029 决策 5 要消除的正是它）。宿主未提供同步通道时属实现缺失，
     * 由调用方按致命失败处置。</p>
     *
     * @param request  安装请求（对象引用透传，{@code includePattern} 生效）
     * @param listener 进度回调，形状照执行器既有回调
     * @throws UnsupportedOperationException 宿主未提供同步补丁通道
     * @throws com.gameplatform.common.exception.BusinessException 参数非法或安装失败
     */
    default void installSync(PatchInstallRequest request, PatchInstallProgressListener listener) {
        throw new UnsupportedOperationException("该宿主未提供同步补丁通道");
    }

    /**
     * 探测宿主机能力（SFTP 推送探测脚本执行，不区分局域网），供 UI 安装前预检。
     *
     * @param hostId 主机 ID
     * @return 能力探测结果
     */
    HostCapabilities probeHost(Long hostId);
}

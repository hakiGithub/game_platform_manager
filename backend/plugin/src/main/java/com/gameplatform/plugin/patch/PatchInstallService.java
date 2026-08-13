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
 * {@code cancelMyOwn} 取消。</p>
 */
public interface PatchInstallService {

    /**
     * 提交补丁安装任务。
     *
     * @param request 安装请求
     * @return 任务 ID（任务中心）
     * @throws com.gameplatform.common.exception.BusinessException 参数非法
     */
    String install(PatchInstallRequest request);

    /**
     * 探测宿主机能力（SFTP 推送探测脚本执行，不区分局域网），供 UI 安装前预检。
     *
     * @param hostId 主机 ID
     * @return 能力探测结果
     */
    HostCapabilities probeHost(Long hostId);
}

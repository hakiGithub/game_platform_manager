package com.gameplatform.plugin.patch;

/**
 * 补丁安装的同步进度回调（design.md §14.1）。
 *
 * <p>三个方法照宿主侧执行器既有回调形状（{@code PatchInstallExecutor.ProgressListener}）：
 * 扩展阶段经 {@link PatchInstallService#installSync} 阻塞调用，过程可见性由宿主按
 * design.md §14.6 的日志呈现契约落到部署日志流，插件侧不参与呈现。</p>
 */
public interface PatchInstallProgressListener {

    /**
     * 进度百分比与说明。
     *
     * @param percent 0～100
     * @param message 进度说明文本
     */
    void onProgress(int percent, String message);

    /**
     * 一行过程日志。
     *
     * @param message 日志文本
     */
    void onLog(String message);

    /**
     * 调用方是否已请求取消。
     *
     * <p>部署扩展阶段没有取消入口（部署主流程不经任务中心），该路径固定传 {@code () -> false}；
     * 保留本方法是为了不与执行器回调形状分叉，造取消通道属扩范围（design.md §14.1）。</p>
     *
     * @return true 表示应尽快中止
     */
    boolean isCancelled();
}

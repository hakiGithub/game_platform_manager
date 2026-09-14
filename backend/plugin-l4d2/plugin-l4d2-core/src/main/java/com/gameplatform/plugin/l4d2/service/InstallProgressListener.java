package com.gameplatform.plugin.l4d2.service;

/**
 * 内置插件安装进度监听：任务中心协作式取消与细粒度进度的桥接通道。
 *
 * <p>异步安装任务（BuiltinPluginInstallTaskHandler）传入实现，把安装链路的
 * 阶段进度与日志映射到 TaskContext；同步调用方（平台框架安装、预设应用、
 * 上传安装接口）传 null，行为与旧版一致。
 *
 * <p>取消语义：{@link #isCancelled()} 在上传循环每个文件前与单文件传输回调中
 * 被检查；返回 true 后安装链路抛出取消异常即刻中止（传输回调抛异常即中止传输
 * 是 SDK FileTransferProgressCallback 的契约）。是否落 CANCELLED 终态由
 * 调用方（Handler 检查 TaskContext.isCancelled 后正常返回）决定。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface InstallProgressListener {

    /**
     * 任务是否已请求取消（协作式检查点，上传循环逐文件调用）。
     */
    boolean isCancelled();

    /**
     * 阶段/整体进度上报。
     *
     * @param percent 0-100
     * @param message 展示文本（任务详情页可见）
     */
    default void onProgress(int percent, String message) {}

    /**
     * 安装过程日志（INFO/WARN），落任务日志供详情页查看。
     */
    default void onLog(String level, String message) {}
}

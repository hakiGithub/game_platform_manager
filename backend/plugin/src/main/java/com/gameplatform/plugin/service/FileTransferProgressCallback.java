package com.gameplatform.plugin.service;

/**
 * 文件传输进度回调（上传/下载）。
 *
 * <p>回调契约：
 * <ul>
 *   <li><b>同步回调</b>：所有方法在传输线程上同步触发，回调内不应执行耗时操作，
 *       否则会拖慢传输速度。</li>
 *   <li><b>回调频率不作保证</b>：实现方可按块（如每 64KB）或按百分比变化节流，
 *       调用方不应假设 {@link #onProgress} 的调用次数；但 {@link #onComplete} 与
 *       {@link #onError} 保证各至多调用一次。</li>
 *   <li><b>异常即中止</b>：回调方法抛出的异常会中止传输并向上传播，
 *       调用方可借此实现"取消传输"。</li>
 *   <li><b>totalBytes 未知语义</b>：无法预先获取大小时传 {@code -1}，
 *       此时 {@link #onProgress} 的 {@code totalBytes} 同样为 {@code -1}，
 *       调用方不应据此计算百分比。</li>
 *   <li><b>覆盖率</b>：对 Docker 类部署实例，传输分两段（SFTP 到宿主临时文件 +
 *       docker cp 进/出容器），进度仅覆盖 SFTP 段；docker cp 段无回调反馈，
 *       进度可能停在 100% 一段时间。</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * instanceFileService.uploadLocalFile(instanceId, "maps/map.vpk", localPath, new FileTransferProgressCallback() {
 *     @Override public void onStart(long totalBytes) { log.info("开始上传, 总大小: {}", totalBytes); }
 *     @Override public void onProgress(long bytesTransferred, long totalBytes) { }
 *     // 更新进度
 *     @Override public void onComplete() { log.info("上传完成"); }
 *     @Override public void onError(Throwable error) { log.error("上传失败", error); }
 * });
 * }</pre>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface FileTransferProgressCallback {

    /**
     * 传输开始时回调（流打开之前）。
     *
     * @param totalBytes 传输总字节数；无法预知时为 {@code -1}
     */
    void onStart(long totalBytes);

    /**
     * 传输进度回调（可能被实现节流，频率不作保证）。
     *
     * @param bytesTransferred 已传输字节数
     * @param totalBytes       传输总字节数；未知时为 {@code -1}
     */
    void onProgress(long bytesTransferred, long totalBytes);

    /** 传输成功完成时回调（保证最终调用一次）。 */
    void onComplete();

    /**
     * 传输失败时回调（保证最终调用一次后异常向上传播）。
     * 注意：若回调自身抛出异常导致传输中止，本方法可能不会被调用。
     *
     * @param error 传输失败原因
     */
    void onError(Throwable error);
}

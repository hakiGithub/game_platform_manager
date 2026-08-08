package com.gameplatform.adapter;

/**
 * 部署进度回调接口
 * 用于实时反馈部署过程中的进度和状态
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface DeployProgressCallback {

    /**
     * 进度更新
     *
     * @param percent 进度百分比 (0-100)
     * @param stage   当前阶段
     * @param message 进度消息
     */
    void onProgress(int percent, String stage, String message);

    /**
     * 部署完成
     *
     * @param success 是否成功
     * @param message 完成消息
     */
    void onComplete(boolean success, String message);

    /**
     * 发生错误
     *
     * @param error   错误信息
     * @param stage   发生错误的阶段
     * @param recoverable 是否可恢复
     */
    void onError(String error, String stage, boolean recoverable);

    /**
     * 日志输出
     *
     * @param level   日志级别 (INFO, WARN, ERROR)
     * @param message 日志消息
     */
    void onLog(String level, String message);

    /**
     * 阶段开始
     *
     * @param stage       阶段名称
     * @param description 阶段描述
     */
    void onStageStart(String stage, String description);

    /**
     * 阶段完成
     *
     * @param stage   阶段名称
     * @param success 是否成功
     * @param message 完成消息
     */
    void onStageComplete(String stage, boolean success, String message);

    /**
     * 默认回调实现（空实现）
     */
    DeployProgressCallback NO_OP = new DeployProgressCallback() {
        @Override
        public void onProgress(int percent, String stage, String message) {
            // 空实现
        }

        @Override
        public void onComplete(boolean success, String message) {
            // 空实现
        }

        @Override
        public void onError(String error, String stage, boolean recoverable) {
            // 空实现
        }

        @Override
        public void onLog(String level, String message) {
            // 空实现
        }

        @Override
        public void onStageStart(String stage, String description) {
            // 空实现
        }

        @Override
        public void onStageComplete(String stage, boolean success, String message) {
            // 空实现
        }
    };
}

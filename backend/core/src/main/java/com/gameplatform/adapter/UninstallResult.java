package com.gameplatform.adapter;

/**
 * 卸载结果。
 *
 * <p>实例删除允许记录被删除但远端资源清理不完整，因此不能再用单一 boolean
 * 表达“服务已删除、工作目录仍残留”这类部分成功。</p>
 */
public record UninstallResult(Status status, String message, String residualPath) {

    public enum Status {
        SUCCESS("success"),
        PARTIAL_SUCCESS("partial_success"),
        FAILED("failed");

        private final String wireKey;

        Status(String wireKey) {
            this.wireKey = wireKey;
        }

        public String getWireKey() {
            return wireKey;
        }
    }

    public static UninstallResult success(String message) {
        return new UninstallResult(Status.SUCCESS, message, null);
    }

    public static UninstallResult partialSuccess(String message, String residualPath) {
        return new UninstallResult(Status.PARTIAL_SUCCESS, message, residualPath);
    }

    public static UninstallResult failed(String message, String residualPath) {
        return new UninstallResult(Status.FAILED, message, residualPath);
    }

    public boolean isFullySuccessful() {
        return status == Status.SUCCESS;
    }
}

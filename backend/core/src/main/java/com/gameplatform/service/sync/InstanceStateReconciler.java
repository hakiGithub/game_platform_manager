package com.gameplatform.service.sync;

import com.gameplatform.adapter.DeployAdapter.InstanceStatus;

import java.util.Objects;

/**
 * 将一次实例状态观测转换为 {@code run_status} 写入决策。
 *
 * <p>观测不到（SSH/Docker/适配器故障）与真实停止是两种事实。这里只处理已成功的观测；
 * 无法观测时上游必须保留数据库中的状态。
 */
public final class InstanceStateReconciler {

    private InstanceStateReconciler() {
    }

    public static Decision reconcile(int dbStatus, InstanceStatus observedStatus) {
        Objects.requireNonNull(observedStatus, "observedStatus");
        if (dbStatus == observedStatus.getCode()) {
            return Decision.unchanged(dbStatus);
        }
        return Decision.update(observedStatus.getCode(), defaultRemark(observedStatus));
    }

    public static Decision reconcile(int dbStatus, InstanceStatus observedStatus, String remark) {
        Objects.requireNonNull(observedStatus, "observedStatus");
        if (dbStatus == observedStatus.getCode()) {
            return Decision.unchanged(dbStatus);
        }
        return Decision.update(observedStatus.getCode(), remark);
    }

    private static String defaultRemark(InstanceStatus status) {
        return switch (status) {
            case RUNNING -> null;
            case STOPPED -> "进程未运行";
            case ERROR -> "实例异常";
            default -> null;
        };
    }

    public record Decision(boolean shouldUpdate, int targetStatus, String remark) {
        public Decision {
            if (!shouldUpdate && targetStatus < 0) {
                throw new IllegalArgumentException("未更新决策必须携带原状态码");
            }
        }

        public static Decision unchanged(int currentStatus) {
            return new Decision(false, currentStatus, null);
        }

        public static Decision update(int targetStatus, String remark) {
            return new Decision(true, targetStatus, remark);
        }
    }
}

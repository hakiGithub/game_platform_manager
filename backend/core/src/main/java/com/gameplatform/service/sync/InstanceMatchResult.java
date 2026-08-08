package com.gameplatform.service.sync;

import com.gameplatform.adapter.DeployAdapter.InstanceStatus;

/**
 * 实例匹配结果值对象
 * 用于 DockerInstanceSyncStrategy / NativeInstanceSyncStrategy 在匹配容器或进程后返回结果
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public record InstanceMatchResult(
        /** 是否匹配到容器/进程 */
        boolean matched,
        /** 目标状态（matched=true 时为运行中；matched=false 时为 STOPPED） */
        InstanceStatus targetStatus,
        /** 备注（容器已退出/容器不存在/进程未运行） */
        String remark
) {
    /**
     * 匹配成功工厂方法（默认目标状态为 RUNNING）
     */
    public static InstanceMatchResult matched(InstanceStatus status) {
        return new InstanceMatchResult(true, status, null);
    }

    /**
     * 未匹配工厂方法（目标状态为 STOPPED）
     */
    public static InstanceMatchResult notFound(String remark) {
        return new InstanceMatchResult(false, InstanceStatus.STOPPED, remark);
    }
}

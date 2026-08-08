package com.gameplatform.service.sync;

import com.gameplatform.entity.Host;

/**
 * 实例状态同步服务
 * 负责调度 Docker / Native 两种策略对在线主机的实例状态进行对账
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface InstanceSyncService {

    /**
     * 同步所有在线主机上的游戏实例状态
     *
     * @return 同步结果摘要（成功主机数 / 失败主机数 / 总实例变更数）
     */
    SyncSummary syncAll();

    /**
     * 同步单台主机上的游戏实例状态
     *
     * @param host 主机信息（需含 SSH 凭据）
     * @return 该主机的实例变更数
     */
    int syncHost(Host host);

    /**
     * 同步结果摘要
     */
    record SyncSummary(int totalHosts, int successHosts, int failedHosts, int totalUpdated) {
        public static SyncSummary empty() {
            return new SyncSummary(0, 0, 0, 0);
        }
    }
}

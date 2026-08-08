package com.gameplatform.task;

import com.gameplatform.service.HostService;
import com.gameplatform.service.sync.InstanceSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 主机监控定时任务
 * 定期刷新所有主机的资源状态，并同步实例运行状态
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HostMonitorTask {

    private final HostService hostService;
    private final InstanceSyncService instanceSyncService;

    /**
     * 每5分钟刷新一次所有主机状态
     * 更新CPU、内存、磁盘使用率和在线状态
     * 同时同步实例运行状态（容器/进程实际状态对账到 game_instance.run_status）
     */
    @Scheduled(fixedRate = 5 * 60 * 1000)
    public void refreshHostsStatus() {
        try {
            log.debug("开始执行主机状态刷新任务");
            hostService.refreshAllHostsStatus();
        } catch (Exception e) {
            log.error("主机状态刷新任务执行失败", e);
        }

        try {
            log.debug("开始执行实例状态同步任务");
            InstanceSyncService.SyncSummary summary = instanceSyncService.syncAll();
            if (summary.totalHosts() > 0) {
                log.info("实例状态同步完成: {}", summary);
            }
        } catch (Exception e) {
            log.error("实例状态同步任务执行失败", e);
        }
    }

}

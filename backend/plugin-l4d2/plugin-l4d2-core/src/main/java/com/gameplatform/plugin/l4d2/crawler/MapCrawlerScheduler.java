package com.gameplatform.plugin.l4d2.crawler;

import com.gameplatform.plugin.l4d2.service.MapCenterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 地图中心定时爬取任务。
 * <p>
 * 每周一凌晨 3:00 执行增量爬取，可通过配置开关关闭。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MapCrawlerScheduler {

    private final MapCenterService mapCenterService;

    /**
     * 每周一凌晨 3:00 增量爬取。
     * <p>
     * cron: 秒 分 时 日 月 周
     * 0 0 3 ? * MON = 每周一 03:00:00
     */
    @Scheduled(cron = "0 0 3 ? * MON")
    public void scheduledIncrementalCrawl() {
        log.info("[MapCenter] 定时增量爬取任务启动");
        try {
            mapCenterService.triggerCrawl("INCREMENTAL");
        } catch (Exception e) {
            log.error("[MapCenter] 定时增量爬取任务失败", e);
        }
    }
}

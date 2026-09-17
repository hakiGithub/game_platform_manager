package com.gameplatform.plugin.l4d2.crawler;

import com.gameplatform.plugin.l4d2.service.MapCenterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 地图中心定时爬取任务（ADR-0028）。
 * <p>
 * 每日凌晨 3:00 增量爬取（水位线早停后成本 ≈ 1-3 页）；每周日 3:10 全量对账；
 * 均可通过配置开关关闭。
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MapCrawlerScheduler {

    private final MapCenterService mapCenterService;

    /**
     * 每日凌晨 3:00 增量爬取。
     * <p>
     * cron: 秒 分 时 日 月 周
     * 0 0 3 * * ? = 每日 03:00:00
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void scheduledIncrementalCrawl() {
        log.info("[MapCenter] 定时增量爬取任务启动");
        try {
            mapCenterService.triggerCrawl("INCREMENTAL");
        } catch (Exception e) {
            log.error("[MapCenter] 定时增量爬取任务失败", e);
        }
    }

    /**
     * 每周日凌晨 3:10 全量对账（兜底早停可能漏掉的乱序更新）。
     */
    @Scheduled(cron = "0 10 3 ? * SUN")
    public void scheduledFullCrawl() {
        log.info("[MapCenter] 定时全量对账任务启动");
        try {
            mapCenterService.triggerCrawl("FULL");
        } catch (Exception e) {
            log.error("[MapCenter] 定时全量对账任务失败", e);
        }
    }
}

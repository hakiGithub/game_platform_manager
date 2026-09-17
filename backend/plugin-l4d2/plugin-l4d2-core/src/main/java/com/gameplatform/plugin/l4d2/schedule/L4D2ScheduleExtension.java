package com.gameplatform.plugin.l4d2.schedule;

import com.gameplatform.plugin.l4d2.L4D2Constants;
import com.gameplatform.plugin.schedule.ScheduleDeclaration;
import com.gameplatform.plugin.schedule.ScheduledTaskDeclarationExtension;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * L4D2 定时计划声明（ADR-0028）：随插件分发的默认爬取计划，
 * 按 {@code pluginId:key} upsert（用户改过跳过、删过不复活），
 * 在「定时任务」页可管理。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Component
public class L4D2ScheduleExtension implements ScheduledTaskDeclarationExtension {

    @Override
    public List<ScheduleDeclaration> getScheduleDeclarations() {
        return List.of(
                ScheduleDeclaration.builder()
                        .key("dailyIncrementalCrawl")
                        .name("每日地图增量爬取")
                        .handlerKey("mapCrawl")
                        .cron("0 0 3 * * ?")
                        .payload(Map.of(
                                L4D2Constants.FIELD_CRAWL_TYPE, L4D2Constants.CRAWL_TYPE_INCREMENTAL))
                        .enabled(true)
                        .build(),
                ScheduleDeclaration.builder()
                        .key("weeklyFullCrawl")
                        .name("每周地图全量对账")
                        .handlerKey("mapCrawl")
                        .cron("0 10 3 ? * SUN")
                        .payload(Map.of(
                                L4D2Constants.FIELD_CRAWL_TYPE, L4D2Constants.CRAWL_TYPE_FULL))
                        .enabled(true)
                        .build());
    }
}

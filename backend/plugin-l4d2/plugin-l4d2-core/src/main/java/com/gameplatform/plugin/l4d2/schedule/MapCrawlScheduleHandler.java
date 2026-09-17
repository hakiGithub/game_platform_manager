package com.gameplatform.plugin.l4d2.schedule;

import com.gameplatform.plugin.l4d2.task.CrawlTaskHandler;
import com.gameplatform.plugin.schedule.ScheduledTaskHandler;
import com.gameplatform.plugin.task.TaskContext;
import com.gameplatform.plugin.task.TaskPayload;
import com.gameplatform.plugin.task.TaskResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 地图爬取定时任务处理器（ADR-0028）：接入 ADR-0011 定时计划体系，
 * 在「定时任务」页可见、可改 cron/payload、可启用禁用与手动触发。
 *
 * <p>执行直接委托 {@link CrawlTaskHandler}（增量水位线早停/全量逻辑复用同一实现），
 * payload 约定：{@code crawlType}（INCREMENTAL / FULL，缺省 INCREMENTAL）。
 * 默认计划由 {@link L4D2ScheduleExtension} 声明（每日增量 + 每周全量对账）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MapCrawlScheduleHandler implements ScheduledTaskHandler {

    private final CrawlTaskHandler crawlTaskHandler;

    @Override
    public String getKey() {
        return "mapCrawl";
    }

    @Override
    public String getDisplayName() {
        return "地图爬取";
    }

    @Override
    public TaskResult execute(TaskContext context, TaskPayload payload) throws Exception {
        return crawlTaskHandler.execute(context, payload);
    }
}

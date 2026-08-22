package com.gameplatform.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gameplatform.config.ScheduledTaskProperties;
import com.gameplatform.entity.ScheduledTask;
import com.gameplatform.mapper.ScheduledTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 定时计划调度注册表（ADR-0011 D7）
 *
 * <p>统一管理计划的 cron 生命周期：注册 / 取消 / 重调度。
 * 计划增删改时对 {@link ThreadPoolTaskScheduler} 做 cancel + reschedule；
 * 应用启动时（ApplicationReadyEvent）先做崩溃恢复（遗留 RUNNING run 置 FAILED），
 * 再全量重载 enabled 且未暂停的计划。
 *
 * <p>线程模型：专用调度线程池（2 线程，仅负责到点触发——触发动作本身很轻，
 * 实际执行由 {@link ScheduleTriggerEngine} 的执行池承接）。单机 SQLite 无需分布式锁。
 *
 * <p>停机不补跑：内存调度天然满足（重启后从下一个到点开始，D6）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
public class ScheduledTaskRegistry {

    private final ScheduledTaskMapper scheduleMapper;
    private final ScheduleTriggerEngine triggerEngine;
    private final ScheduledTaskProperties properties;

    /** 专用调度线程池（cron 到点触发） */
    private final ThreadPoolTaskScheduler scheduler;

    /** 已注册的调度任务（scheduleId -> ScheduledFuture） */
    private final Map<String, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();

    public ScheduledTaskRegistry(ScheduledTaskMapper scheduleMapper,
                                 ScheduleTriggerEngine triggerEngine,
                                 ScheduledTaskProperties properties) {
        this.scheduleMapper = scheduleMapper;
        this.triggerEngine = triggerEngine;
        this.properties = properties;

        this.scheduler = new ThreadPoolTaskScheduler();
        this.scheduler.setPoolSize(2);
        this.scheduler.setThreadNamePrefix("schedule-cron-");
        this.scheduler.setDaemon(true);
        this.scheduler.setRemoveOnCancelPolicy(true);
        this.scheduler.initialize();
        log.info("[Schedule] cron 调度注册表已初始化");
    }

    /**
     * 应用启动后全量重载计划
     *
     * <p>时机选在 {@link ApplicationReadyEvent}：所有 Bean（含插件自动加载器）
     * 就绪后执行，避免与插件加载顺序竞态。插件来源的计划在插件加载钩子中
     * 恢复（resumeByPlugin），此处仅注册 paused=0 的存量计划。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reloadAll() {
        // 1. 崩溃恢复：遗留 RUNNING run 置 FAILED（停机不补跑）
        triggerEngine.recoverStaleRuns();

        if (!properties.isEnabled()) {
            log.info("[Schedule] 定时任务总开关关闭，跳过计划重载（手动触发仍可用）");
            return;
        }

        // 3. 全量注册 enabled 且未暂停的计划
        List<ScheduledTask> active = scheduleMapper.selectList(new LambdaQueryWrapper<ScheduledTask>()
                .eq(ScheduledTask::getEnabled, 1)
                .eq(ScheduledTask::getPaused, 0));
        for (ScheduledTask schedule : active) {
            try {
                register(schedule);
            } catch (Exception e) {
                log.error("[Schedule] 启动注册计划 [{}] 失败（cron={}）: {}",
                        schedule.getName(), schedule.getCron(), e.getMessage());
            }
        }
        log.info("[Schedule] 启动重载完成：{} 个计划已注册调度", active.size());
    }

    /**
     * 注册（或重调度）一个计划
     *
     * <p>幂等：先取消已有调度再注册新调度。计划处于禁用/暂停状态时仅取消不注册。
     * cron 非法时抛 IllegalArgumentException（调用方应在创建/更新时提前校验）。
     *
     * @param schedule 计划实体（最新快照）
     */
    public void register(ScheduledTask schedule) {
        cancelInternal(schedule.getId());
        if (schedule.getEnabled() != 1 || schedule.getPaused() == 1) {
            return;
        }
        if (!properties.isEnabled()) {
            return;
        }
        CronTrigger cronTrigger = new CronTrigger(schedule.getCron());
        ScheduledFuture<?> future = scheduler.schedule(
                () -> triggerEngine.trigger(schedule, false), cronTrigger);
        scheduledFutures.put(schedule.getId(), future);
        log.info("[Schedule] 计划 [{}] 已注册调度（cron={}，下次触发 {}）",
                schedule.getName(), schedule.getCron(), computeNextFireTime(schedule.getCron()));
    }

    /**
     * 取消计划的调度（禁用/暂停/删除时调用；只停未来触发）
     */
    public void cancel(String scheduleId) {
        cancelInternal(scheduleId);
    }

    private void cancelInternal(String scheduleId) {
        ScheduledFuture<?> future = scheduledFutures.remove(scheduleId);
        if (future != null) {
            future.cancel(false);
            log.info("[Schedule] 计划 {} 的调度已取消", scheduleId);
        }
    }

    /**
     * 计算 cron 的下次触发时间（服务器时区；禁用/暂停计划由调用方决定是否展示）
     *
     * @param cron cron 表达式
     * @return 下次触发时间；表达式非法返回 null
     */
    public LocalDateTime computeNextFireTime(String cron) {
        try {
            Instant next = new CronTrigger(cron).nextExecution(new SimpleTriggerContext());
            return next != null ? LocalDateTime.ofInstant(next, ZoneId.systemDefault()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 当前已注册调度的计划数（运行状态展示用）
     */
    public int activeCount() {
        return scheduledFutures.size();
    }

    @PreDestroy
    public void shutdown() {
        log.info("[Schedule] 关闭 cron 调度注册表（{} 个计划）", scheduledFutures.size());
        scheduledFutures.values().forEach(f -> f.cancel(false));
        scheduledFutures.clear();
        scheduler.shutdown();
    }
}

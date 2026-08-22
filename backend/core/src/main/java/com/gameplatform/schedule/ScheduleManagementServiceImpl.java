package com.gameplatform.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.common.result.PageResult;
import com.gameplatform.entity.ScheduledTask;
import com.gameplatform.entity.ScheduledTaskRun;
import com.gameplatform.entity.ScheduledTaskRunLog;
import com.gameplatform.enums.ScheduleRunStatus;
import com.gameplatform.mapper.ScheduledTaskMapper;
import com.gameplatform.mapper.ScheduledTaskRunLogMapper;
import com.gameplatform.mapper.ScheduledTaskRunMapper;
import com.gameplatform.plugin.extension.ExtensionIdGenerator;
import com.gameplatform.plugin.schedule.*;
import com.gameplatform.plugin.task.TaskLog;
import com.gameplatform.vo.TaskTypeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 定时计划管理服务实现（ADR-0011）
 *
 * <p>核心职责：
 * <ul>
 *   <li>管理侧 CRUD（create/update/enable/disable/delete/trigger），操作后同步重调度</li>
 *   <li>插件侧 Scoped 操作：强制来源归属校验（插件只能操作自己 source 的计划）</li>
 *   <li>声明式默认计划 upsert（稳定键 pluginId:key；用户改过跳过、删过不复活）</li>
 *   <li>插件生命周期联动：pauseByPlugin / resumeByPlugin / purgeByPlugin</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleManagementServiceImpl implements ScheduleManagementService {

    /** payload 序列化上限：64KB（对齐任务中心） */
    private static final int PAYLOAD_MAX_BYTES = 64 * 1024;

    private final ScheduledTaskMapper scheduleMapper;
    private final ScheduledTaskRunMapper runMapper;
    private final ScheduledTaskRunLogMapper runLogMapper;
    private final ScheduledTaskRegistry registry;
    private final ScheduleTriggerEngine triggerEngine;
    private final ScheduledTaskHandlerRegistry handlerRegistry;
    private final ExtensionIdGenerator idGenerator;
    private final ObjectMapper objectMapper;

    // ==================== 管理侧（REST） ====================

    @Override
    public String create(String source, ScheduleCreateRequest request, String operator) {
        validateCreateRequest(request);
        if (!hasText(source)) {
            throw new BusinessException("计划来源不能为空");
        }
        ScheduledTask entity = buildEntity(normalizeSource(source), null, request);
        entity.setCreateBy(operator);
        entity.setUpdateBy(operator);
        scheduleMapper.insert(entity);
        registry.register(entity);
        log.info("[Schedule] 创建计划 [{}]（source={}:{}, cron={}, operator={}）",
                entity.getName(), source, entity.getHandlerKey(), entity.getCron(), operator);
        return entity.getId();
    }

    @Override
    public void update(String id, ScheduleUpdateRequest request, String operator) {
        ScheduledTask entity = requireExists(id);
        applyUpdate(entity, request);
        entity.setUserModified(1);
        entity.setUpdateBy(operator);
        scheduleMapper.updateById(entity);
        registry.register(entity);
        log.info("[Schedule] 更新计划 [{}]（operator={}）", entity.getName(), operator);
    }

    @Override
    public void enable(String id) {
        ScheduledTask entity = requireExists(id);
        entity.setEnabled(1);
        entity.setUpdateBy("admin");
        scheduleMapper.updateById(entity);
        // 系统暂停状态由插件生命周期管理，启用不清除 paused
        registry.register(entity);
        log.info("[Schedule] 启用计划 [{}]", entity.getName());
    }

    @Override
    public void disable(String id) {
        ScheduledTask entity = requireExists(id);
        entity.setEnabled(0);
        entity.setUpdateBy("admin");
        scheduleMapper.updateById(entity);
        registry.cancel(id); // 只停未来触发，进行中的 run 跑完
        log.info("[Schedule] 禁用计划 [{}]", entity.getName());
    }

    @Override
    public void delete(String id) {
        ScheduledTask entity = requireExists(id);
        triggerEngine.cancelRunsBySchedule(id); // 取消进行中的 run
        scheduleMapper.deleteById(id);          // 逻辑删除 = 声明复活墓碑
        registry.cancel(id);
        log.info("[Schedule] 删除计划 [{}]（{}，触发记录保留）", entity.getName(), id);
    }

    @Override
    public String trigger(String id) {
        ScheduledTask entity = requireExists(id);
        return triggerEngine.trigger(entity, true);
    }

    @Override
    public void cancelRun(String runId) {
        if (!triggerEngine.cancelRun(runId)) {
            throw new BusinessException("触发记录不存在或已结束: " + runId);
        }
    }

    @Override
    public ScheduleVO get(String id) {
        ScheduledTask entity = requireExists(id);
        return toVO(entity, latestRunsOf(List.of(entity.getId())).get(entity.getId()));
    }

    @Override
    public PageResult<ScheduleVO> list(ScheduleQuery query) {
        int page = query.getPage() != null ? query.getPage() : 1;
        int size = query.getSize() != null ? query.getSize() : 20;

        LambdaQueryWrapper<ScheduledTask> wrapper = new LambdaQueryWrapper<ScheduledTask>()
                .eq(hasText(query.getSource()), ScheduledTask::getSource, normalizeSource(query.getSource()))
                .eq(hasText(query.getHandlerKey()), ScheduledTask::getHandlerKey, query.getHandlerKey())
                .like(hasText(query.getKeyword()), ScheduledTask::getName, query.getKeyword())
                .eq(query.getEnabled() != null, ScheduledTask::getEnabled, query.getEnabled() ? 1 : 0)
                .orderByDesc(ScheduledTask::getCreateTime);

        Page<ScheduledTask> result = scheduleMapper.selectPage(new Page<>(page, size), wrapper);
        Map<String, ScheduledTaskRun> latestRuns = latestRunsOf(
                result.getRecords().stream().map(ScheduledTask::getId).toList());
        List<ScheduleVO> vos = result.getRecords().stream()
                .map(e -> toVO(e, latestRuns.get(e.getId())))
                .collect(Collectors.toList());
        return new PageResult<>(vos, result.getTotal(), page, size);
    }

    @Override
    public PageResult<ScheduleRunVO> listRuns(ScheduleRunQuery query) {
        if (!hasText(query.getScheduleId())) {
            throw new BusinessException("scheduleId 不能为空");
        }
        requireExists(query.getScheduleId());
        return doListRuns(query);
    }

    @Override
    public ScheduleRunVO getRun(String runId) {
        ScheduledTaskRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BusinessException("触发记录不存在: " + runId);
        }
        return toRunVO(run);
    }

    @Override
    public List<TaskLog> getRunLogs(String runId) {
        return doRunLogs(runId);
    }

    @Override
    public List<TaskTypeVO> listHandlers() {
        return handlerRegistry.listHandlers();
    }

    // ==================== 插件侧（来源隔离） ====================

    @Override
    public String createScoped(String source, String pluginId, ScheduleCreateRequest request) {
        validateCreateRequest(request);
        // source 由适配器绑定（PluginSpringContextFactory 传入 gameCode 大写），不取请求值
        ScheduledTask entity = buildEntity(source, pluginId, request);
        entity.setCreateBy("plugin:" + pluginId);
        entity.setUpdateBy("plugin:" + pluginId);
        scheduleMapper.insert(entity);
        registry.register(entity);
        log.info("[Schedule] 插件 [{}] 创建计划 [{}]（cron={}）", pluginId, entity.getName(), entity.getCron());
        return entity.getId();
    }

    @Override
    public void updateScoped(String id, String source, ScheduleUpdateRequest request) {
        ScheduledTask entity = requireOwned(id, source);
        applyUpdate(entity, request);
        entity.setUserModified(1);
        scheduleMapper.updateById(entity);
        registry.register(entity);
    }

    @Override
    public void enableScoped(String id, String source) {
        ScheduledTask entity = requireOwned(id, source);
        entity.setEnabled(1);
        scheduleMapper.updateById(entity);
        registry.register(entity);
    }

    @Override
    public void disableScoped(String id, String source) {
        ScheduledTask entity = requireOwned(id, source);
        entity.setEnabled(0);
        scheduleMapper.updateById(entity);
        registry.cancel(id);
    }

    @Override
    public void deleteScoped(String id, String source) {
        ScheduledTask entity = requireOwned(id, source);
        triggerEngine.cancelRunsBySchedule(id);
        scheduleMapper.deleteById(id);
        registry.cancel(id);
    }

    @Override
    public String triggerScoped(String id, String source) {
        ScheduledTask entity = requireOwned(id, source);
        return triggerEngine.trigger(entity, true);
    }

    @Override
    public ScheduleVO getScoped(String id, String source) {
        return toVO(requireOwned(id, source), latestRunsOf(List.of(id)).get(id));
    }

    @Override
    public PageResult<ScheduleVO> listScoped(ScheduleQuery query, String source) {
        ScheduleQuery scoped = query != null ? query : new ScheduleQuery();
        scoped.setSource(source); // 强制绑定本插件来源
        scoped.setKeyword(null);
        return list(scoped);
    }

    @Override
    public PageResult<ScheduleRunVO> listRunsScoped(ScheduleRunQuery query, String source) {
        if (query == null || !hasText(query.getScheduleId())) {
            throw new BusinessException("scheduleId 不能为空");
        }
        requireOwned(query.getScheduleId(), source);
        return doListRuns(query);
    }

    @Override
    public List<TaskLog> getRunLogsScoped(String runId, String source) {
        ScheduledTaskRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BusinessException("触发记录不存在: " + runId);
        }
        requireOwned(run.getScheduleId(), source);
        return doRunLogs(runId);
    }

    // ==================== 插件生命周期联动 ====================

    @Override
    public void upsertDeclarations(String pluginId, String source, List<ScheduleDeclaration> declarations) {
        if (declarations == null || declarations.isEmpty()) {
            return;
        }
        // 声明 key 去重校验
        Set<String> seen = new HashSet<>();
        for (ScheduleDeclaration decl : declarations) {
            if (decl == null || !hasText(decl.getKey())) {
                log.warn("[Schedule] 插件 [{}] 声明缺少 key，跳过", pluginId);
                continue;
            }
            if (!seen.add(decl.getKey())) {
                throw new IllegalStateException(
                        "插件 " + pluginId + " 的定时计划声明 key 重复: " + decl.getKey());
            }
            if (!hasText(decl.getHandlerKey()) || !hasText(decl.getCron()) || !hasText(decl.getName())) {
                log.warn("[Schedule] 插件 [{}] 声明 {} 缺少必填字段（name/handlerKey/cron），跳过",
                        pluginId, decl.getKey());
                continue;
            }
            try {
                new CronTrigger(decl.getCron());
            } catch (IllegalArgumentException e) {
                log.error("[Schedule] 插件 [{}] 声明 {} 的 cron 非法（{}），跳过",
                        pluginId, decl.getKey(), decl.getCron());
                continue;
            }
            upsertSingleDeclaration(pluginId, source, decl);
        }
    }

    /**
     * 单条声明 upsert（ADR-0011 D5 冲突语义）
     */
    private void upsertSingleDeclaration(String pluginId, String source, ScheduleDeclaration decl) {
        String declarationKey = pluginId + ":" + decl.getKey();
        ScheduledTask existing = scheduleMapper.selectByDeclarationKeyIncludingDeleted(declarationKey);

        if (existing == null) {
            // 全新声明：插入
            ScheduledTask entity = new ScheduledTask();
            entity.setId(idGenerator.nextId());
            entity.setName(decl.getName());
            entity.setHandlerKey(decl.getHandlerKey());
            entity.setCron(decl.getCron());
            entity.setPayload(serializePayload(decl.getPayload()));
            entity.setEnabled(Boolean.FALSE.equals(decl.getEnabled()) ? 0 : 1);
            entity.setPaused(0);
            entity.setSource(source);
            entity.setPluginId(pluginId);
            entity.setDeclarationKey(declarationKey);
            entity.setUserModified(0);
            entity.setCreateBy("plugin:" + pluginId);
            entity.setUpdateBy("plugin:" + pluginId);
            scheduleMapper.insert(entity);
            registry.register(entity);
            log.info("[Schedule] 插件 [{}] 声明计划 [{}] 已创建（cron={}）", pluginId, decl.getName(), decl.getCron());
            return;
        }

        if (existing.getDeleted() != null && existing.getDeleted() == 1) {
            // 用户删除过的计划不复活（墓碑检测）
            log.info("[Schedule] 插件 [{}] 声明 {} 已被用户删除，跳过（不复活）", pluginId, declarationKey);
            return;
        }

        if (existing.getUserModified() != null && existing.getUserModified() == 1) {
            // 用户改过的计划不被插件重启覆盖
            log.info("[Schedule] 插件 [{}] 声明 {} 已被用户修改，跳过 upsert", pluginId, declarationKey);
            // 声明未变仍需恢复调度（插件重载路径：resumeByPlugin 已处理 paused）
            registry.register(existing);
            return;
        }

        // 未修改过：随声明演进更新（enabled 保持用户意图，仅同步 name/cron/payload/handlerKey）
        existing.setName(decl.getName());
        existing.setHandlerKey(decl.getHandlerKey());
        existing.setCron(decl.getCron());
        existing.setPayload(serializePayload(decl.getPayload()));
        existing.setUpdateBy("plugin:" + pluginId);
        scheduleMapper.updateById(existing);
        registry.register(existing);
        log.info("[Schedule] 插件 [{}] 声明计划 [{}] 已同步（cron={}）", pluginId, decl.getName(), decl.getCron());
    }

    @Override
    public void pauseByPlugin(String pluginId, String reason) {
        List<ScheduledTask> schedules = scheduleMapper.selectByPluginId(pluginId);
        for (ScheduledTask schedule : schedules) {
            if (schedule.getPaused() != null && schedule.getPaused() == 1) {
                continue; // 已暂停（幂等）
            }
            schedule.setPaused(1);
            schedule.setPauseReason(reason);
            scheduleMapper.updateById(schedule);
            registry.cancel(schedule.getId());
        }
        if (!schedules.isEmpty()) {
            log.info("[Schedule] 插件 [{}] 已暂停 {} 个计划（{}）", pluginId, schedules.size(), reason);
        }
    }

    @Override
    public void resumeByPlugin(String pluginId) {
        List<ScheduledTask> schedules = scheduleMapper.selectByPluginId(pluginId);
        for (ScheduledTask schedule : schedules) {
            if (schedule.getPaused() == null || schedule.getPaused() == 0) {
                continue; // 未暂停（可能是启动重载前的新计划）
            }
            schedule.setPaused(0);
            schedule.setPauseReason(null);
            scheduleMapper.updateById(schedule);
            registry.register(schedule); // enabled=1 的重新注册调度
        }
        if (!schedules.isEmpty()) {
            log.info("[Schedule] 插件 [{}] 已恢复 {} 个计划", pluginId, schedules.size());
        }
    }

    @Override
    public void purgeByPlugin(String pluginId) {
        List<ScheduledTask> schedules = scheduleMapper.selectByPluginId(pluginId);
        if (schedules.isEmpty()) {
            return;
        }
        List<String> scheduleIds = schedules.stream().map(ScheduledTask::getId).toList();
        // 1. 取消进行中的 run
        for (String scheduleId : scheduleIds) {
            triggerEngine.cancelRunsBySchedule(scheduleId);
            registry.cancel(scheduleId);
        }
        // 2. 物理删除触发记录 + 日志（先查 runId 级联删日志）
        List<String> runIds = scheduleIds.stream()
                .flatMap(sid -> runMapper.selectList(new LambdaQueryWrapper<ScheduledTaskRun>()
                                .eq(ScheduledTaskRun::getScheduleId, sid)).stream())
                .map(ScheduledTaskRun::getId)
                .toList();
        if (!runIds.isEmpty()) {
            runLogMapper.deleteByRunIds(runIds);
        }
        runMapper.deleteByScheduleIds(scheduleIds);
        // 3. 物理删除计划（含声明墓碑——插件都没了，墓碑无意义）
        scheduleMapper.physicalDeleteByPluginId(pluginId);
        log.info("[Schedule] 插件 [{}] 卸载：已清理 {} 个计划、{} 条触发记录", pluginId, scheduleIds.size(), runIds.size());
    }

    // ==================== 私有方法 ====================

    private ScheduledTask requireExists(String id) {
        ScheduledTask entity = scheduleMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("定时计划不存在: " + id);
        }
        return entity;
    }

    /**
     * 来源归属校验：计划不存在或来源不匹配均拒绝（插件隔离）
     */
    private ScheduledTask requireOwned(String id, String source) {
        ScheduledTask entity = requireExists(id);
        if (!entity.getSource().equals(normalizeSource(source))) {
            throw new BusinessException("无权操作其他来源的定时计划");
        }
        return entity;
    }

    private void validateCreateRequest(ScheduleCreateRequest request) {
        if (request == null) {
            throw new BusinessException("请求不能为空");
        }
        if (!hasText(request.getName())) {
            throw new BusinessException("计划名称不能为空");
        }
        if (!hasText(request.getHandlerKey())) {
            throw new BusinessException("处理器 key 不能为空");
        }
        if (!hasText(request.getCron())) {
            throw new BusinessException("cron 表达式不能为空");
        }
        try {
            new CronTrigger(request.getCron());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("cron 表达式非法: " + request.getCron());
        }
    }

    private ScheduledTask buildEntity(String source, String pluginId, ScheduleCreateRequest request) {
        ScheduledTask entity = new ScheduledTask();
        entity.setId(idGenerator.nextId());
        entity.setName(request.getName().trim());
        entity.setHandlerKey(request.getHandlerKey().trim());
        entity.setCron(request.getCron().trim());
        entity.setPayload(serializePayload(request.getPayload()));
        entity.setEnabled(Boolean.FALSE.equals(request.getEnabled()) ? 0 : 1);
        entity.setPaused(0);
        entity.setSource(source);
        entity.setPluginId(pluginId);
        entity.setUserModified(0);
        return entity;
    }

    private void applyUpdate(ScheduledTask entity, ScheduleUpdateRequest request) {
        if (request == null) {
            return;
        }
        if (hasText(request.getName())) {
            entity.setName(request.getName().trim());
        }
        if (hasText(request.getCron())) {
            try {
                new CronTrigger(request.getCron());
            } catch (IllegalArgumentException e) {
                throw new BusinessException("cron 表达式非法: " + request.getCron());
            }
            entity.setCron(request.getCron().trim());
        }
        if (request.getPayload() != null) {
            entity.setPayload(serializePayload(request.getPayload()));
        }
    }

    /**
     * 批量取计划各自的最近一次触发记录（列表页"上次结果"列）
     */
    private Map<String, ScheduledTaskRun> latestRunsOf(List<String> scheduleIds) {
        if (scheduleIds == null || scheduleIds.isEmpty()) {
            return Map.of();
        }
        List<ScheduledTaskRun> runs = runMapper.selectByScheduleIds(scheduleIds);
        Map<String, ScheduledTaskRun> latest = new HashMap<>();
        for (ScheduledTaskRun run : runs) { // selectByScheduleIds 已按时间倒序
            latest.putIfAbsent(run.getScheduleId(), run);
        }
        return latest;
    }

    private ScheduleVO toVO(ScheduledTask entity, ScheduledTaskRun latestRun) {
        ScheduleVO vo = new ScheduleVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setHandlerKey(entity.getHandlerKey());
        ScheduledTaskHandler handler = handlerRegistry.get(entity.getSource(), entity.getHandlerKey());
        vo.setHandlerName(handler != null ? handler.getDisplayName() : null);
        vo.setCron(entity.getCron());
        vo.setPayload(deserializeJson(entity.getPayload()));
        vo.setEnabled(entity.getEnabled() != null && entity.getEnabled() == 1);
        vo.setPaused(entity.getPaused() != null && entity.getPaused() == 1);
        vo.setPauseReason(entity.getPauseReason());
        vo.setSource(entity.getSource());
        vo.setPluginId(entity.getPluginId());
        vo.setDeclarationKey(entity.getDeclarationKey());
        vo.setUserModified(entity.getUserModified() != null && entity.getUserModified() == 1);
        // 下次触发时间：仅启用且未暂停的计划计算
        if (Boolean.TRUE.equals(vo.getEnabled()) && !Boolean.TRUE.equals(vo.getPaused())) {
            vo.setNextFireTime(registry.computeNextFireTime(entity.getCron()));
        }
        if (latestRun != null) {
            vo.setLastRunStatus(latestRun.getStatus());
            vo.setLastRunTime(latestRun.getCreateTime());
        }
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        return vo;
    }

    private PageResult<ScheduleRunVO> doListRuns(ScheduleRunQuery query) {
        int page = query.getPage() != null ? query.getPage() : 1;
        int size = query.getSize() != null ? query.getSize() : 20;
        LambdaQueryWrapper<ScheduledTaskRun> wrapper = new LambdaQueryWrapper<ScheduledTaskRun>()
                .eq(ScheduledTaskRun::getScheduleId, query.getScheduleId())
                .eq(hasText(query.getStatus()), ScheduledTaskRun::getStatus,
                        hasText(query.getStatus()) ? ScheduleRunStatus.parse(query.getStatus()) != null
                                ? query.getStatus() : "RUNNING" : null)
                .orderByDesc(ScheduledTaskRun::getCreateTime);
        Page<ScheduledTaskRun> result = runMapper.selectPage(new Page<>(page, size), wrapper);
        List<ScheduleRunVO> vos = result.getRecords().stream().map(this::toRunVO).collect(Collectors.toList());
        return new PageResult<>(vos, result.getTotal(), page, size);
    }

    private ScheduleRunVO toRunVO(ScheduledTaskRun run) {
        ScheduleRunVO vo = new ScheduleRunVO();
        vo.setId(run.getId());
        vo.setScheduleId(run.getScheduleId());
        vo.setScheduleName(run.getScheduleName());
        vo.setTriggerType(run.getTriggerType());
        vo.setStatus(run.getStatus());
        vo.setPayload(deserializeJson(run.getPayload()));
        vo.setResult(deserializeJson(run.getResult()));
        vo.setErrorMessage(run.getErrorMessage());
        vo.setProgress(run.getProgress());
        vo.setProgressMessage(run.getProgressMessage());
        vo.setStartedAt(run.getStartedAt());
        vo.setCompletedAt(run.getCompletedAt());
        vo.setDurationMs(run.getDurationMs());
        vo.setCreateTime(run.getCreateTime());
        return vo;
    }

    private List<TaskLog> doRunLogs(String runId) {
        ScheduledTaskRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BusinessException("触发记录不存在: " + runId);
        }
        return runLogMapper.selectByRunId(runId, 500).stream().map(l -> {
            TaskLog vo = new TaskLog();
            vo.setId(l.getId());
            vo.setTaskId(l.getRunId()); // runId 放入 taskId 字段，复用前端日志展示结构
            vo.setLevel(l.getLevel());
            vo.setMessage(l.getMessage());
            vo.setCreateTime(l.getCreateTime());
            return vo;
        }).collect(Collectors.toList());
    }

    private String serializePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(payload);
            if (json.length() > PAYLOAD_MAX_BYTES) {
                throw new BusinessException("payload 超过 64KB 上限");
            }
            return json;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("payload 序列化失败: " + e.getMessage());
        }
    }

    private Map<String, Object> deserializeJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of("raw", json);
        }
    }

    private String normalizeSource(String source) {
        if (!hasText(source)) {
            return "MAIN";
        }
        return source.trim().toUpperCase();
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}

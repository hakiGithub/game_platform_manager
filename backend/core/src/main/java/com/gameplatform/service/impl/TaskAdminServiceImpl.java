package com.gameplatform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gameplatform.common.result.PageResult;
import com.gameplatform.entity.TaskRecord;
import com.gameplatform.enums.TaskStatus;
import com.gameplatform.mapper.TaskLogMapper;
import com.gameplatform.mapper.TaskRecordMapper;
import com.gameplatform.plugin.task.TaskHandler;
import com.gameplatform.plugin.task.TaskLog;
import com.gameplatform.plugin.task.TaskPayload;
import com.gameplatform.plugin.task.TaskQuery;
import com.gameplatform.plugin.task.TaskResult;
import com.gameplatform.plugin.task.TaskSubmitRequest;
import com.gameplatform.plugin.task.TaskVO;
import com.gameplatform.service.TaskAdminService;
import com.gameplatform.task.TaskHandlerRegistry;
import com.gameplatform.task.TaskMutexManager;
import com.gameplatform.task.exception.TaskException;
import com.gameplatform.task.exception.TaskMaxRetryExceededException;
import com.gameplatform.task.exception.TaskNotFoundException;
import com.gameplatform.task.exception.TaskNotCancellableException;
import com.gameplatform.task.exception.TaskNotRetryableException;
import com.gameplatform.vo.TaskStatsVO;
import com.gameplatform.vo.TaskTypeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 任务管理服务实现（{@link TaskAdminService}）
 *
 * <p>仅主应用 core 使用，由 {@link com.gameplatform.controller.TaskController} 调用。
 * 共享方法（getTask/listTasks/getTaskLogs）委托给 {@link TaskServiceImpl}。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskAdminServiceImpl implements TaskAdminService {

    private final TaskRecordMapper taskRecordMapper;
    private final TaskLogMapper taskLogMapper;
    private final TaskHandlerRegistry handlerRegistry;
    private final TaskMutexManager taskMutexManager;
    private final TaskServiceImpl taskService;

    @Override
    public boolean cancel(String taskId) {
        TaskRecord record = taskRecordMapper.selectById(taskId);
        if (record == null) {
            throw new TaskNotFoundException(taskId);
        }
        return taskService.doCancel(taskId, record);
    }

    @Override
    public int cancelBySource(String source) {
        List<TaskRecord> unfinished = taskRecordMapper.selectUnfinishedBySource(source);
        if (unfinished.isEmpty()) {
            return 0;
        }
        int cancelled = 0;
        for (TaskRecord task : unfinished) {
            try {
                if (taskService.doCancel(task.getId(), task)) {
                    cancelled++;
                }
            } catch (Exception e) {
                log.warn("[TaskCenter] cancelBySource: 任务 {} 取消失败: {}", task.getId(), e.getMessage());
            }
        }
        log.info("[TaskCenter] 来源 {} 取消 {} 个任务（共 {} 个未完成）", source, cancelled, unfinished.size());
        return cancelled;
    }

    @Override
    public String retry(String taskId) {
        TaskRecord original = taskRecordMapper.selectById(taskId);
        if (original == null) {
            throw new TaskNotFoundException(taskId);
        }

        // 1. 状态校验：仅 FAILED/CANCELLED 可重试
        TaskStatus status = TaskStatus.valueOf(original.getStatus());
        if (status != TaskStatus.FAILED && status != TaskStatus.CANCELLED) {
            throw new TaskNotRetryableException(taskId, "任务非终态（当前: " + status + "）");
        }

        // 2. 查找 Handler
        TaskHandler handler = handlerRegistry.get(original.getSource(), original.getTaskType());
        if (handler == null) {
            throw new TaskNotRetryableException(taskId, "Handler 已注销");
        }

        // 3. 检查 isRetryable
        if (!handler.isRetryable()) {
            throw new TaskNotRetryableException(taskId, "Handler 声明不可重试");
        }

        // 4. 检查重试次数上限（ADR-012）
        int retryCount = original.getRetryCount() != null ? original.getRetryCount() : 0;
        if (retryCount >= handler.getMaxRetryCount()) {
            throw new TaskMaxRetryExceededException(taskId, handler.getMaxRetryCount());
        }

        // 5. 反序列化 payload 调用 onRetry 钩子
        TaskPayload payload = taskService.deserializePayloadForRetry(original.getPayload());
        try {
            // 创建一个简单的只读上下文（不带 progress/log 能力）
            RetryTaskContext retryCtx = new RetryTaskContext(original);
            handler.onRetry(retryCtx, payload);
        } catch (Exception e) {
            throw new TaskException("onRetry 钩子失败: " + e.getMessage());
        }

        // 6. 构造新任务提交请求
        TaskSubmitRequest newRequest = TaskSubmitRequest.builder()
                .taskType(original.getTaskType())
                .source(original.getSource())
                .scopeType(original.getScopeType())
                .scopeKey(original.getScopeKey())
                .scopeName(original.getScopeName())
                .submitter(original.getSubmitter())
                .payload(payload.asMap())
                .build();

        // 7. 调用 submit 创建新任务（不传 parentTaskId，由 submit 内部生成新 ID）
        String newTaskId = taskService.submit(newRequest);

        // 8. 更新原任务 retryCount + 关联 parentTaskId（ADR-027 不修改原任务状态）
        taskRecordMapper.incrementRetryCount(taskId);
        // 更新新任务的 parent_task_id 字段
        TaskRecord newRecord = taskRecordMapper.selectById(newTaskId);
        if (newRecord != null) {
            newRecord.setParentTaskId(taskId);
            taskRecordMapper.updateById(newRecord);
        }

        log.info("[TaskCenter] 任务重试: 原={}, 新={}, retryCount={}", taskId, newTaskId, retryCount + 1);
        return newTaskId;
    }

    @Override
    public boolean delete(String taskId) {
        TaskRecord record = taskRecordMapper.selectById(taskId);
        if (record == null) {
            throw new TaskNotFoundException(taskId);
        }
        // 仅终态可删除
        TaskStatus status = TaskStatus.valueOf(record.getStatus());
        if (!status.isTerminal()) {
            throw new TaskException("仅终态任务可删除，当前状态: " + status);
        }
        int rows = taskRecordMapper.deleteById(taskId);
        if (rows > 0) {
            handlerRegistry.removeTaskSourceIndex(taskId);
            log.info("[TaskCenter] 任务已删除（软删除）: id={}", taskId);
        }
        return rows > 0;
    }

    @Override
    public int purgeBySource(String source) {
        // 先删除 task_log（物理）
        int logsDeleted = taskLogMapper.deleteBySource(source);
        // 再物理删除 task_record
        int recordsDeleted = taskRecordMapper.physicalDeleteBySource(source);
        log.info("[TaskCenter] 物理清理来源 {} 的任务: {} 条记录, {} 条日志",
                source, recordsDeleted, logsDeleted);
        return recordsDeleted;
    }

    @Override
    public List<TaskTypeVO> listTypes() {
        return handlerRegistry.listTypes();
    }

    @Override
    public TaskVO getTask(String taskId) {
        return taskService.getTask(taskId);
    }

    @Override
    public PageResult<TaskVO> listTasks(TaskQuery query) {
        return taskService.listTasks(query);
    }

    @Override
    public List<TaskLog> getTaskLogs(String taskId) {
        return taskService.getTaskLogs(taskId);
    }

    @Override
    public List<TaskLog> getTaskLogsAfter(String taskId, String afterId) {
        if (afterId == null || afterId.isBlank()) {
            // 首次拉取，返回最近 100 条
            List<com.gameplatform.entity.TaskLog> logs = taskLogMapper.selectByTaskId(taskId, 100);
            return logs.stream().map(this::convertLog).toList();
        }
        List<com.gameplatform.entity.TaskLog> logs = taskLogMapper.selectAfterId(taskId, afterId);
        return logs.stream().map(this::convertLog).toList();
    }

    @Override
    public TaskStatsVO getStats(LocalDateTime startTime, LocalDateTime endTime) {
        LambdaQueryWrapper<TaskRecord> baseWrapper = new LambdaQueryWrapper<>();
        baseWrapper.eq(TaskRecord::getDeleted, 0);
        if (startTime != null) {
            baseWrapper.ge(TaskRecord::getCreateTime, startTime);
        }
        if (endTime != null) {
            baseWrapper.le(TaskRecord::getCreateTime, endTime);
        }
        List<TaskRecord> all = taskRecordMapper.selectList(baseWrapper);

        TaskStatsVO stats = new TaskStatsVO();
        stats.setTotal((long) all.size());
        stats.setStatusCounts(all.stream()
                .collect(Collectors.groupingBy(TaskRecord::getStatus, Collectors.counting())));
        stats.setSourceCounts(all.stream()
                .collect(Collectors.groupingBy(TaskRecord::getSource, Collectors.counting())));
        stats.setTypeCounts(all.stream()
                .collect(Collectors.groupingBy(TaskRecord::getTaskType, Collectors.counting())));
        return stats;
    }

    // ==================== 私有辅助方法 ====================

    private TaskLog convertLog(com.gameplatform.entity.TaskLog entity) {
        TaskLog vo = new TaskLog();
        vo.setId(entity.getId());
        vo.setTaskId(entity.getTaskId());
        vo.setLevel(entity.getLevel());
        vo.setMessage(entity.getMessage());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }

    /**
     * 重试场景的只读 TaskContext 实现
     *
     * <p>仅提供元数据访问能力（taskId/taskType/source/scopeKey），
     * reportProgress/log/isCancelled/isTimeout 为空实现。
     */
    private static class RetryTaskContext implements com.gameplatform.plugin.task.TaskContext {
        private final TaskRecord original;

        RetryTaskContext(TaskRecord original) {
            this.original = original;
        }

        @Override
        public String getTaskId() {
            return original.getId();
        }

        @Override
        public String getTaskType() {
            return original.getTaskType();
        }

        @Override
        public String getSource() {
            return original.getSource();
        }

        @Override
        public String getScopeKey() {
            return original.getScopeKey();
        }

        @Override
        public void reportProgress(int percent, String message) {
            // 重试场景不支持
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isTimeout() {
            return false;
        }

        @Override
        public void log(String message) {
            // 重试场景不支持
        }

        @Override
        public void log(String level, String message) {
            // 重试场景不支持
        }
    }
}

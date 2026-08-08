package com.gameplatform.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务互斥键内存管理器（ADR-018 内存 ConcurrentHashMap）
 *
 * <p>互斥键不持久化到数据库，仅在内存维护 {@code ConcurrentHashMap<mutexKey, taskId>}。
 *
 * <ul>
 *   <li>提交时：{@link #putIfAbsent} 成功才允许创建任务，失败抛 TaskAlreadyRunningException</li>
 *   <li>任务流转到终态（COMPLETED/FAILED/CANCELLED）：{@link #remove}</li>
 *   <li>应用启动时（崩溃恢复后）：{@link #clear} 全量清空</li>
 *   <li>PENDING 超时被标记 FAILED 时：{@link #removeByTaskId}</li>
 * </ul>
 *
 * <p><b>风险</b>：应用崩溃后内存互斥键丢失，等同于"全部释放"，符合用户预期。
 * 崩溃恢复机制会将遗留 RUNNING/PENDING 任务标记为 FAILED，无残留互斥键问题。
 *
 * <p><b>线程安全</b>：所有操作均委托 {@link ConcurrentHashMap} 原子方法。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
public class TaskMutexManager {

    /** mutexKey -> taskId（已占用该互斥键的任务ID） */
    private final ConcurrentHashMap<String, String> mutexMap = new ConcurrentHashMap<>();

    /** taskId -> mutexKey（反向索引，便于按 taskId 释放） */
    private final ConcurrentHashMap<String, String> taskMutexIndex = new ConcurrentHashMap<>();

    /**
     * 尝试占用互斥键
     *
     * @param mutexKey 互斥键（非空）
     * @param taskId   任务ID
     * @return true 表示占用成功，false 表示已被其他任务占用
     */
    public boolean putIfAbsent(String mutexKey, String taskId) {
        String existing = mutexMap.putIfAbsent(mutexKey, taskId);
        if (existing == null) {
            taskMutexIndex.put(taskId, mutexKey);
            return true;
        }
        // 同一任务重复提交场景（理论不发生，防御性处理）
        return existing.equals(taskId);
    }

    /**
     * 释放互斥键（按 mutexKey）
     *
     * <p>仅当当前占用者为 taskId 时才释放，避免误删其他任务的互斥键。
     *
     * @param mutexKey 互斥键
     * @param taskId   任务ID
     */
    public void remove(String mutexKey, String taskId) {
        if (mutexKey == null || taskId == null) {
            return;
        }
        // CAS 删除：仅当值为 taskId 时才删除
        mutexMap.remove(mutexKey, taskId);
        taskMutexIndex.remove(taskId);
    }

    /**
     * 释放互斥键（按 taskId 反查 mutexKey）
     *
     * <p>用于崩溃恢复/PENDING 超时场景，仅知道 taskId。
     *
     * @param taskId 任务ID
     */
    public void removeByTaskId(String taskId) {
        if (taskId == null) {
            return;
        }
        String mutexKey = taskMutexIndex.remove(taskId);
        if (mutexKey != null) {
            mutexMap.remove(mutexKey, taskId);
        }
    }

    /**
     * 清空所有互斥键（应用启动崩溃恢复时调用）
     */
    public void clear() {
        int size = mutexMap.size();
        mutexMap.clear();
        taskMutexIndex.clear();
        if (size > 0) {
            log.warn("[TaskCenter] 清空所有内存互斥键: {} 个", size);
        }
    }

    /**
     * 查询当前互斥键占用情况（调试/监控用）
     *
     * @return 互斥键数量
     */
    public int size() {
        return mutexMap.size();
    }

    /**
     * 判断指定互斥键是否被占用（调试/测试用）
     */
    public boolean isHeld(String mutexKey) {
        return mutexKey != null && mutexMap.containsKey(mutexKey);
    }
}

package com.gameplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gameplatform.entity.TaskRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务记录 Mapper
 *
 * <p>SQL 遵循 ADR-028：不使用 SQLite 特定函数，时间由应用层传入。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Mapper
public interface TaskRecordMapper extends BaseMapper<TaskRecord> {

    /**
     * 乐观锁更新：PENDING → RUNNING（ADR-020）
     *
     * <p>用于 executeAsync 开始时校验任务是否仍为 PENDING。
     * 影响行数 = 0 表示任务已被取消或被崩溃恢复标记，应退出执行。
     *
     * @param taskId     任务ID
     * @param startedAt  开始执行时间
     * @return 影响行数（1 表示成功，0 表示状态已变更）
     */
    @Update("UPDATE task_record SET status = 'RUNNING', started_at = #{startedAt}, " +
            "update_time = #{startedAt} WHERE id = #{taskId} AND status = 'PENDING' AND is_deleted = 0")
    int updateToRunning(@Param("taskId") String taskId, @Param("startedAt") LocalDateTime startedAt);

    /**
     * 乐观锁取消：PENDING → CANCELLED（取消未开始的任务）
     *
     * @param taskId       任务ID
     * @param completedAt  完成时间
     * @return 影响行数（1 表示成功，0 表示任务已被取出执行）
     */
    @Update("UPDATE task_record SET status = 'CANCELLED', completed_at = #{completedAt}, " +
            "update_time = #{completedAt} WHERE id = #{taskId} AND status = 'PENDING' AND is_deleted = 0")
    int updateToCancelledFromPending(@Param("taskId") String taskId, @Param("completedAt") LocalDateTime completedAt);

    /**
     * 更新任务进度（节流后的最终写入）
     *
     * @param taskId    任务ID
     * @param progress  进度百分比 0-100
     * @param message   进度描述
     * @param updateTime 更新时间
     * @return 影响行数
     */
    @Update("UPDATE task_record SET progress = #{progress}, progress_message = #{message}, " +
            "update_time = #{updateTime} WHERE id = #{taskId}")
    int updateProgress(@Param("taskId") String taskId, @Param("progress") Integer progress,
                       @Param("message") String message, @Param("updateTime") LocalDateTime updateTime);

    /**
     * 任务完成：更新为 COMPLETED 状态
     *
     * @param taskId       任务ID
     * @param result       输出结果 JSON
     * @param resultSummary 结果摘要
     * @param completedAt  完成时间
     * @param durationMs   耗时毫秒
     * @param updateTime   更新时间
     * @return 影响行数
     */
    @Update("UPDATE task_record SET status = 'COMPLETED', result = #{result}, result_summary = #{resultSummary}, " +
            "progress = 100, completed_at = #{completedAt}, duration_ms = #{durationMs}, " +
            "update_time = #{updateTime} WHERE id = #{taskId}")
    int updateToCompleted(@Param("taskId") String taskId, @Param("result") String result,
                          @Param("resultSummary") String resultSummary,
                          @Param("completedAt") LocalDateTime completedAt,
                          @Param("durationMs") Long durationMs,
                          @Param("updateTime") LocalDateTime updateTime);

    /**
     * 任务失败：更新为 FAILED 状态
     *
     * @param taskId       任务ID
     * @param errorMessage 错误信息
     * @param stackTrace   堆栈
     * @param completedAt  完成时间
     * @param durationMs   耗时毫秒
     * @param updateTime   更新时间
     * @return 影响行数
     */
    @Update("UPDATE task_record SET status = 'FAILED', error_message = #{errorMessage}, " +
            "stack_trace = #{stackTrace}, completed_at = #{completedAt}, duration_ms = #{durationMs}, " +
            "update_time = #{updateTime} WHERE id = #{taskId}")
    int updateToFailed(@Param("taskId") String taskId, @Param("errorMessage") String errorMessage,
                       @Param("stackTrace") String stackTrace,
                       @Param("completedAt") LocalDateTime completedAt,
                       @Param("durationMs") Long durationMs,
                       @Param("updateTime") LocalDateTime updateTime);

    /**
     * 任务取消（RUNNING 状态的协作式取消，Handler 退出后调用）
     *
     * @param taskId       任务ID
     * @param completedAt  完成时间
     * @param durationMs   耗时毫秒
     * @param updateTime   更新时间
     * @return 影响行数
     */
    @Update("UPDATE task_record SET status = 'CANCELLED', completed_at = #{completedAt}, " +
            "duration_ms = #{durationMs}, update_time = #{updateTime} WHERE id = #{taskId} AND status = 'RUNNING'")
    int updateToCancelledFromRunning(@Param("taskId") String taskId,
                                     @Param("completedAt") LocalDateTime completedAt,
                                     @Param("durationMs") Long durationMs,
                                     @Param("updateTime") LocalDateTime updateTime);

    /**
     * 增加重试次数
     *
     * @param taskId 原任务ID
     * @return 影响行数
     */
    @Update("UPDATE task_record SET retry_count = retry_count + 1 WHERE id = #{taskId}")
    int incrementRetryCount(@Param("taskId") String taskId);

    /**
     * 查询所有未完成的任务（PENDING 或 RUNNING）——崩溃恢复使用
     *
     * @return 未完成任务列表
     */
    @Select("SELECT * FROM task_record WHERE status IN ('PENDING', 'RUNNING') AND is_deleted = 0")
    List<TaskRecord> selectUnfinishedTasks();

    /**
     * 查询 PENDING 超时的任务——PENDING 超时检查使用
     *
     * @param cutoff 超时阈值（创建时间早于此值）
     * @return 超时的 PENDING 任务列表
     */
    @Select("SELECT * FROM task_record WHERE status = 'PENDING' AND create_time < #{cutoff} AND is_deleted = 0")
    List<TaskRecord> selectPendingTimeoutTasks(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 按来源查询任务（用于插件卸载时取消和清理）
     *
     * @param source 任务来源
     * @return 该来源的任务列表
     */
    @Select("SELECT * FROM task_record WHERE source = #{source} AND is_deleted = 0")
    List<TaskRecord> selectBySource(@Param("source") String source);

    /**
     * 按来源查询未完成的任务（用于插件卸载时取消）
     *
     * @param source 任务来源
     * @return 未完成任务列表
     */
    @Select("SELECT * FROM task_record WHERE source = #{source} AND status IN ('PENDING', 'RUNNING') AND is_deleted = 0")
    List<TaskRecord> selectUnfinishedBySource(@Param("source") String source);

    /**
     * 物理删除指定来源的所有任务记录（插件卸载时调用）
     *
     * <p>注意：物理删除，不走逻辑删除字段。
     *
     * @param source 任务来源
     * @return 影响行数
     */
    @Delete("DELETE FROM task_record WHERE source = #{source}")
    int physicalDeleteBySource(@Param("source") String source);

    /**
     * 物理删除指定状态 + 创建时间阈值之前的任务记录（清理调度器使用）
     *
     * <p>注意：物理删除，不走逻辑删除字段。配合 {@link #selectIdsByStatusAndTime} 使用，
     * 先查 ID 再级联删除 task_log，最后调用此方法物理删除 task_record。
     *
     * @param statuses 任务状态列表（如 COMPLETED/CANCELLED/FAILED）
     * @param cutoff   创建时间阈值（创建时间早于此值的任务将被删除）
     * @return 影响行数
     */
    @Delete("<script>" +
            "DELETE FROM task_record WHERE status IN " +
            "<foreach collection='statuses' item='s' open='(' separator=',' close=')'>" +
            "#{s}" +
            "</foreach>" +
            " AND create_time &lt; #{cutoff}" +
            "</script>")
    int physicalDeleteByStatusAndTime(@Param("statuses") java.util.List<String> statuses,
                                       @Param("cutoff") LocalDateTime cutoff);

    /**
     * 查询指定状态 + 创建时间阈值之前的任务 ID 列表（清理调度器使用）
     *
     * <p>仅查询 ID 字段，配合 {@link #physicalDeleteByStatusAndTime} 使用，
     * 中间先按 ID 级联删除 task_log。
     *
     * @param statuses 任务状态列表
     * @param cutoff   创建时间阈值
     * @return 任务记录列表（仅 id 字段有值）
     */
    @Select("<script>" +
            "SELECT id FROM task_record WHERE status IN " +
            "<foreach collection='statuses' item='s' open='(' separator=',' close=')'>" +
            "#{s}" +
            "</foreach>" +
            " AND create_time &lt; #{cutoff}" +
            "</script>")
    java.util.List<TaskRecord> selectIdsByStatusAndTime(@Param("statuses") java.util.List<String> statuses,
                                                          @Param("cutoff") LocalDateTime cutoff);

    /**
     * 统计指定状态的任务数量
     *
     * @param status 任务状态
     * @return 数量
     */
    @Select("SELECT COUNT(*) FROM task_record WHERE status = #{status} AND is_deleted = 0")
    long countByStatus(@Param("status") String status);

    /**
     * 统计指定来源的任务数量
     *
     * @param source 任务来源
     * @return 数量
     */
    @Select("SELECT COUNT(*) FROM task_record WHERE source = #{source} AND is_deleted = 0")
    long countBySource(@Param("source") String source);

    /**
     * 统计指定类型的任务数量
     *
     * @param taskType 任务类型
     * @return 数量
     */
    @Select("SELECT COUNT(*) FROM task_record WHERE task_type = #{taskType} AND is_deleted = 0")
    long countByTaskType(@Param("taskType") String taskType);
}

package com.gameplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gameplatform.entity.TaskLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 任务日志 Mapper
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Mapper
public interface TaskLogMapper extends BaseMapper<TaskLog> {

    /**
     * 按任务ID查询日志（按时间正序，最近 N 条）
     *
     * <p>用于详情页首次拉取日志。
     *
     * @param taskId 任务ID
     * @param limit  最大条数
     * @return 日志列表
     */
    @Select("SELECT * FROM task_log WHERE task_id = #{taskId} ORDER BY create_time ASC LIMIT #{limit}")
    List<TaskLog> selectByTaskId(@Param("taskId") String taskId, @Param("limit") int limit);

    /**
     * 增量查询日志（id > afterId，按时间正序）
     *
     * <p>用于详情页每 3s 轮询增量拉取日志（ADR-037）。
     *
     * @param taskId  任务ID
     * @param afterId 上次最后一条日志的ID
     * @return 增量日志列表
     */
    @Select("SELECT * FROM task_log WHERE task_id = #{taskId} AND id > #{afterId} ORDER BY create_time ASC")
    List<TaskLog> selectAfterId(@Param("taskId") String taskId, @Param("afterId") String afterId);

    /**
     * 物理删除指定任务的所有日志
     *
     * @param taskId 任务ID
     * @return 影响行数
     */
    @Delete("DELETE FROM task_log WHERE task_id = #{taskId}")
    int deleteByTaskId(@Param("taskId") String taskId);

    /**
     * 物理删除指定来源任务的所有日志（插件卸载时调用）
     *
     * <p>通过子查询关联 task_record 找到对应任务的日志。
     *
     * @param source 任务来源
     * @return 影响行数
     */
    @Delete("DELETE FROM task_log WHERE task_id IN (SELECT id FROM task_record WHERE source = #{source})")
    int deleteBySource(@Param("source") String source);

    /**
     * 统计任务日志数量
     *
     * @param taskId 任务ID
     * @return 数量
     */
    @Select("SELECT COUNT(*) FROM task_log WHERE task_id = #{taskId}")
    long countByTaskId(@Param("taskId") String taskId);

    /**
     * 删除任务最早的日志（保留最近 N 条，ADR-010 500 条上限）
     *
     * <p>用于任务结束后异步清理。
     *
     * @param taskId 任务ID
     * @param keepCount 保留条数
     * @return 影响行数
     */
    @Delete("DELETE FROM task_log WHERE task_id = #{taskId} AND id NOT IN " +
            "(SELECT id FROM task_log WHERE task_id = #{taskId} ORDER BY create_time DESC LIMIT #{keepCount})")
    int deleteOldLogs(@Param("taskId") String taskId, @Param("keepCount") int keepCount);
}

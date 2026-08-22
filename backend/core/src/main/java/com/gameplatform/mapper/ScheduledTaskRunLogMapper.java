package com.gameplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gameplatform.entity.ScheduledTaskRunLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 定时触发日志 Mapper（ADR-0011）
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Mapper
public interface ScheduledTaskRunLogMapper extends BaseMapper<ScheduledTaskRunLog> {

    /**
     * 按触发记录ID查询日志（按时间正序，最近 N 条）
     *
     * @param runId 触发记录ID
     * @param limit 最大条数
     * @return 日志列表
     */
    @Select("SELECT * FROM scheduled_task_run_log WHERE run_id = #{runId} ORDER BY create_time ASC LIMIT #{limit}")
    List<ScheduledTaskRunLog> selectByRunId(@Param("runId") String runId, @Param("limit") int limit);

    /**
     * 物理删除指定触发记录的全部日志
     *
     * @param runId 触发记录ID
     * @return 影响行数
     */
    @Delete("DELETE FROM scheduled_task_run_log WHERE run_id = #{runId}")
    int deleteByRunId(@Param("runId") String runId);

    /**
     * 按触发记录ID列表物理删除日志（级联删除）
     *
     * @param runIds 触发记录ID列表
     * @return 影响行数
     */
    @Delete("<script>DELETE FROM scheduled_task_run_log WHERE run_id IN " +
            "<foreach collection='runIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteByRunIds(@Param("runIds") List<String> runIds);

    /**
     * 删除触发记录最早的日志（保留最近 N 条，每 run 上限 500）
     *
     * @param runId     触发记录ID
     * @param keepCount 保留条数
     * @return 影响行数
     */
    @Delete("DELETE FROM scheduled_task_run_log WHERE run_id = #{runId} AND id NOT IN " +
            "(SELECT id FROM scheduled_task_run_log WHERE run_id = #{runId} ORDER BY create_time DESC LIMIT #{keepCount})")
    int deleteOldLogs(@Param("runId") String runId, @Param("keepCount") int keepCount);
}

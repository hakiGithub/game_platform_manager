package com.gameplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gameplatform.entity.ScheduledTaskRun;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时触发记录 Mapper（ADR-0011）
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Mapper
public interface ScheduledTaskRunMapper extends BaseMapper<ScheduledTaskRun> {

    /**
     * 统计计划的 RUNNING 记录数（重叠跳过判断，ADR-0011 D6）
     *
     * @param scheduleId 计划ID
     * @return 运行中数量（>0 表示上一轮仍在执行）
     */
    @Select("SELECT COUNT(*) FROM scheduled_task_run WHERE schedule_id = #{scheduleId} AND status = 'RUNNING'")
    int countRunningByScheduleId(@Param("scheduleId") String scheduleId);

    /**
     * 查询多个计划各自的最近一次触发记录（按时间倒序取整后由调用方按 scheduleId 分组取首条）
     *
     * @param scheduleIds 计划ID列表
     * @return 触发记录列表（时间倒序）
     */
    @Select("<script>SELECT * FROM scheduled_task_run WHERE schedule_id IN " +
            "<foreach collection='scheduleIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "ORDER BY create_time DESC</script>")
    List<ScheduledTaskRun> selectByScheduleIds(@Param("scheduleIds") List<String> scheduleIds);

    /**
     * 按计划ID列表物理删除触发记录（计划物理删除/插件卸载时级联）
     *
     * @param scheduleIds 计划ID列表
     * @return 影响行数
     */
    @Delete("<script>DELETE FROM scheduled_task_run WHERE schedule_id IN " +
            "<foreach collection='scheduleIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteByScheduleIds(@Param("scheduleIds") List<String> scheduleIds);

    /**
     * 物理删除指定时间之前的触发记录（保留期清理，默认 30 天）
     *
     * @param cutoff 创建时间阈值（早于此值的记录将被清理）
     * @return 影响行数
     */
    @Delete("DELETE FROM scheduled_task_run WHERE create_time &lt; #{cutoff}")
    int physicalDeleteBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 查询早于阈值的触发记录ID列表（级联删除日志用）
     *
     * @param cutoff 创建时间阈值
     * @return runId 列表
     */
    @Select("SELECT id FROM scheduled_task_run WHERE create_time &lt; #{cutoff}")
    List<String> selectIdsBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 查询计划当前 RUNNING 记录（删除计划时取消用）
     *
     * @param scheduleId 计划ID
     * @return RUNNING 记录列表
     */
    @Select("SELECT * FROM scheduled_task_run WHERE schedule_id = #{scheduleId} AND status = 'RUNNING'")
    List<ScheduledTaskRun> selectRunningByScheduleId(@Param("scheduleId") String scheduleId);
}

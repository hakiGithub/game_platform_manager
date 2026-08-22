package com.gameplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gameplatform.entity.ScheduledTask;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 定时计划 Mapper（ADR-0011）
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Mapper
public interface ScheduledTaskMapper extends BaseMapper<ScheduledTask> {

    /**
     * 按声明稳定键查询（含已逻辑删除的墓碑行）
     *
     * <p>用于声明式计划 upsert：用户删除的计划（is_deleted=1）检测到墓碑后
     * 不再复活。绕过 MyBatis-Plus 逻辑删除过滤，直接原生 SQL。
     *
     * @param declarationKey 声明稳定键（pluginId:key）
     * @return 计划实体（含墓碑行），不存在返回 null
     */
    @Select("SELECT * FROM scheduled_task WHERE declaration_key = #{declarationKey} LIMIT 1")
    ScheduledTask selectByDeclarationKeyIncludingDeleted(@Param("declarationKey") String declarationKey);

    /**
     * 按插件ID物理删除全部计划（插件卸载移除时调用，ADR-0011 D8）
     *
     * @param pluginId 插件ID
     * @return 影响行数
     */
    @Delete("DELETE FROM scheduled_task WHERE plugin_id = #{pluginId}")
    int physicalDeleteByPluginId(@Param("pluginId") String pluginId);

    /**
     * 按插件ID查询全部计划（含禁用/暂停，不含已删除）
     *
     * @param pluginId 插件ID
     * @return 计划列表
     */
    @Select("SELECT * FROM scheduled_task WHERE plugin_id = #{pluginId} AND is_deleted = 0")
    List<ScheduledTask> selectByPluginId(@Param("pluginId") String pluginId);
}

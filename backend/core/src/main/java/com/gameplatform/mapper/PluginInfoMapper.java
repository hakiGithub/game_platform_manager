package com.gameplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gameplatform.entity.PluginInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 插件信息Mapper接口
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@Mapper
public interface PluginInfoMapper extends BaseMapper<PluginInfo> {

    /**
     * 根据插件ID查询
     *
     * @param pluginId 插件ID
     * @return 插件信息
     */
    @Select("SELECT * FROM plugin_info WHERE plugin_id = #{pluginId} AND is_deleted = 0")
    PluginInfo selectByPluginId(@Param("pluginId") String pluginId);

    /**
     * 根据游戏编码查询插件
     *
     * @param gameCode 游戏编码
     * @return 插件信息
     */
    @Select("SELECT * FROM plugin_info WHERE game_code = #{gameCode} AND is_deleted = 0")
    PluginInfo selectByGameCode(@Param("gameCode") String gameCode);

    /**
     * 查询所有启用的插件
     *
     * @return 插件列表
     */
    @Select("SELECT * FROM plugin_info WHERE status = 1 AND is_deleted = 0")
    List<PluginInfo> selectEnabledPlugins();

    /**
     * 更新插件状态
     *
     * @param id     插件主键ID
     * @param status 状态
     * @return 影响行数
     */
    @Update("UPDATE plugin_info SET status = #{status}, update_time = datetime('now', 'localtime') WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    /**
     * 更新运行时状态
     *
     * @param id           插件主键ID
     * @param runtimeState 运行时状态
     * @return 影响行数
     */
    @Update("UPDATE plugin_info SET runtime_state = #{runtimeState}, update_time = datetime('now', 'localtime') WHERE id = #{id}")
    int updateRuntimeState(@Param("id") Long id, @Param("runtimeState") String runtimeState);

    /**
     * 根据插件名称模糊查询
     *
     * @param pluginName 插件名称
     * @return 插件列表
     */
    @Select("SELECT * FROM plugin_info WHERE plugin_name LIKE CONCAT('%', #{pluginName}, '%') AND is_deleted = 0")
    List<PluginInfo> selectByPluginNameLike(@Param("pluginName") String pluginName);

    /**
     * 根据插件类型查询
     *
     * @param pluginType 插件类型
     * @return 插件列表
     */
    @Select("SELECT * FROM plugin_info WHERE plugin_type = #{pluginType} AND is_deleted = 0")
    List<PluginInfo> selectByPluginType(@Param("pluginType") String pluginType);

    /**
     * 查询所有游戏增强插件
     *
     * @return 插件列表
     */
    @Select("SELECT * FROM plugin_info WHERE plugin_type = 'game_enhancement' AND is_deleted = 0")
    List<PluginInfo> selectGameEnhancementPlugins();

    /**
     * 更新插件启动时间
     *
     * @param id        插件主键ID
     * @param startTime 启动时间
     * @return 影响行数
     */
    @Update("UPDATE plugin_info SET start_time = #{startTime}, runtime_state = 'STARTED', update_time = datetime('now', 'localtime') WHERE id = #{id}")
    int updateStartTime(@Param("id") Long id, @Param("startTime") String startTime);

    /**
     * 更新插件加载时间
     *
     * @param id        插件主键ID
     * @param loadTime 加载时间
     * @return 影响行数
     */
    @Update("UPDATE plugin_info SET load_time = #{loadTime}, update_time = datetime('now', 'localtime') WHERE id = #{id}")
    int updateLoadTime(@Param("id") Long id, @Param("loadTime") String loadTime);

}

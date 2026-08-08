package com.gameplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gameplatform.entity.GameInstance;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 游戏实例Mapper接口
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Mapper
public interface GameInstanceMapper extends BaseMapper<GameInstance> {

    /**
     * 根据主机ID查询实例列表
     *
     * @param hostId 主机ID
     * @return 实例列表
     */
    @Select("SELECT * FROM game_instance WHERE host_id = #{hostId} AND is_deleted = 0")
    List<GameInstance> selectByHostId(@Param("hostId") Long hostId);

    /**
     * 根据游戏ID查询实例列表
     *
     * @param gameId 游戏ID
     * @return 实例列表
     */
    @Select("SELECT * FROM game_instance WHERE game_id = #{gameId} AND is_deleted = 0")
    List<GameInstance> selectByGameId(@Param("gameId") Long gameId);

    /**
     * 根据游戏编码查询实例列表
     *
     * @param gameCode 游戏编码（如 l4d2）
     * @return 实例列表
     */
    @Select("SELECT * FROM game_instance WHERE game_code = #{gameCode} AND is_deleted = 0")
    List<GameInstance> selectByGameCode(@Param("gameCode") String gameCode);

    /**
     * 查询运行中的实例
     *
     * @return 运行中的实例列表
     */
    @Select("SELECT * FROM game_instance WHERE run_status = 1 AND is_deleted = 0")
    List<GameInstance> selectRunningInstances();

    /**
     * 更新实例运行状态
     *
     * @param instanceId 实例ID
     * @param runStatus  运行状态
     * @return 影响行数
     */
    @Update("UPDATE game_instance SET run_status = #{runStatus}, update_time = datetime('now', 'localtime') WHERE id = #{instanceId}")
    int updateRunStatus(@Param("instanceId") Long instanceId, @Param("runStatus") Integer runStatus);

    /**
     * 更新在线玩家数
     *
     * @param instanceId    实例ID
     * @param onlinePlayers 在线玩家数
     * @return 影响行数
     */
    @Update("UPDATE game_instance SET online_players = #{onlinePlayers}, update_time = datetime('now', 'localtime') WHERE id = #{instanceId}")
    int updateOnlinePlayers(@Param("instanceId") Long instanceId, @Param("onlinePlayers") Integer onlinePlayers);

    /**
     * 根据实例名称查询
     * 注意：instance_name 不再是全局唯一，而是 (host_id, instance_name) 联合唯一。
     * 仅当确认实例名全局唯一时使用此方法，否则应使用 selectByHostIdAndInstanceName。
     *
     * @param instanceName 实例名称
     * @return 实例实体（可能多条，返回第一条）
     */
    @Select("SELECT * FROM game_instance WHERE instance_name = #{instanceName} AND is_deleted = 0 LIMIT 1")
    GameInstance selectByInstanceName(@Param("instanceName") String instanceName);

    /**
     * 根据主机ID和实例名称查询（联合唯一约束查询）
     *
     * <p>注意：本查询故意不带 is_deleted = 0 过滤。因为 (host_id, instance_name) 上的
     * UNIQUE 约束在物理层面对所有记录生效（包括 is_deleted=1 的逻辑删除记录），
     * 创建实例前必须能检测到逻辑删除残留，否则会触发 SQLITE_CONSTRAINT_UNIQUE。
     * 删除实例走物理删除，正常情况下不会有 is_deleted=1 的残留。
     *
     * @param hostId       主机ID
     * @param instanceName 实例名称
     * @return 实例实体（含已逻辑删除的记录）
     */
    @Select("SELECT * FROM game_instance WHERE host_id = #{hostId} AND instance_name = #{instanceName}")
    GameInstance selectByHostIdAndInstanceName(@Param("hostId") Long hostId, @Param("instanceName") String instanceName);

    /**
     * 物理删除实例（绕过 MyBatis-Plus 逻辑删除，真正从表中移除记录）
     * 用于避免 (host_id, instance_name) UNIQUE 约束在逻辑删除后仍然冲突的问题
     *
     * @param instanceId 实例ID
     * @return 影响行数
     */
    @Delete("DELETE FROM game_instance WHERE id = #{instanceId}")
    int physicalDeleteById(@Param("instanceId") Long instanceId);

    /**
     * 按主机ID和部署类型列表查询未删除的实例
     * 用于实例状态同步：分别查询 Docker 类（docker/docker-compose/linuxgsm-docker）和 Native 类实例
     *
     * @param hostId      主机ID
     * @param deployTypes 部署类型列表（如 ["docker", "docker-compose", "linuxgsm-docker"]）
     * @return 实例列表
     */
    @Select({"<script>",
            "SELECT * FROM game_instance",
            "WHERE host_id = #{hostId} AND is_deleted = 0",
            "AND deploy_type IN",
            "<foreach item='type' collection='deployTypes' open='(' separator=',' close=')'>#{type}</foreach>",
            "</script>"})
    List<GameInstance> selectByHostIdAndDeployTypes(@Param("hostId") Long hostId,
                                                     @Param("deployTypes") List<String> deployTypes);

}

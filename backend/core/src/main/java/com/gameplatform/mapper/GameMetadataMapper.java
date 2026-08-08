package com.gameplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gameplatform.entity.GameMetadata;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 游戏元数据Mapper接口
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Mapper
public interface GameMetadataMapper extends BaseMapper<GameMetadata> {

    /**
     * 根据游戏代码查询
     *
     * @param gameCode 游戏代码
     * @return 游戏元数据
     */
    @Select("SELECT * FROM game_metadata WHERE game_code = #{gameCode} AND is_deleted = 0")
    GameMetadata selectByGameCode(@Param("gameCode") String gameCode);

    /**
     * 根据游戏名称模糊查询
     *
     * @param gameName 游戏名称
     * @return 游戏元数据列表
     */
    @Select("SELECT * FROM game_metadata WHERE game_name LIKE CONCAT('%', #{gameName}, '%') AND is_deleted = 0")
    List<GameMetadata> selectByGameNameLike(@Param("gameName") String gameName);

    /**
     * 查询所有启用的游戏
     *
     * @return 游戏元数据列表
     */
    @Select("SELECT * FROM game_metadata WHERE is_deleted = 0 ORDER BY create_time DESC")
    List<GameMetadata> selectAllGames();

}

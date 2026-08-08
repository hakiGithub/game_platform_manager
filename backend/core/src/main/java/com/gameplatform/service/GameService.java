package com.gameplatform.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gameplatform.common.result.PageResult;
import com.gameplatform.dto.*;
import com.gameplatform.vo.DeployConfigVO;
import com.gameplatform.vo.GameVO;

import java.util.List;

/**
 * 游戏元数据服务接口
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface GameService {

    /**
     * 创建游戏
     *
     * @param dto 游戏创建DTO
     * @return 游戏VO
     */
    GameVO createGame(GameCreateDTO dto);

    /**
     * 更新游戏
     *
     * @param dto 游戏更新DTO
     * @return 游戏VO
     */
    GameVO updateGame(GameUpdateDTO dto);

    /**
     * 删除游戏
     *
     * @param id 游戏ID
     */
    void deleteGame(Long id);

    /**
     * 根据ID查询游戏
     *
     * @param id 游戏ID
     * @return 游戏VO
     */
    GameVO getGameById(Long id);

    /**
     * 根据游戏代码查询
     *
     * @param gameCode 游戏代码
     * @return 游戏VO
     */
    GameVO getGameByCode(String gameCode);

    /**
     * 分页查询游戏
     *
     * @param queryDTO 查询DTO
     * @return 分页结果
     */
    PageResult<GameVO> pageGames(PageQueryDTO queryDTO);

    /**
     * 查询所有游戏
     *
     * @return 游戏列表
     */
    List<GameVO> getAllGames();

    /**
     * 按关键词搜索游戏（不分页）
     * 关键词为空时返回全部，否则按游戏名称或编码模糊匹配
     *
     * @param keyword 关键词（可选）
     * @return 游戏列表
     */
    List<GameVO> searchGames(String keyword);

    /**
     * 获取指定游戏的部署配置
     *
     * @param gameId     游戏ID
     * @param deployType 部署类型
     * @return 部署配置VO
     */
    DeployConfigVO getDeployConfig(Long gameId, String deployType);

}

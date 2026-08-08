package com.gameplatform.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.common.result.PageResult;
import com.gameplatform.dto.GameCreateDTO;
import com.gameplatform.dto.GameUpdateDTO;
import com.gameplatform.dto.PageQueryDTO;
import com.gameplatform.entity.GameMetadata;
import com.gameplatform.mapper.GameMetadataMapper;
import com.gameplatform.service.GameService;
import com.gameplatform.service.LogService;
import com.gameplatform.vo.DeployConfigVO;
import com.gameplatform.vo.GameVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 游戏元数据服务实现类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameServiceImpl implements GameService {

    private final GameMetadataMapper gameMetadataMapper;
    private final LogService logService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GameVO createGame(GameCreateDTO dto) {
        // 检查游戏代码是否已存在
        GameMetadata existGame = gameMetadataMapper.selectByGameCode(dto.getGameCode());
        if (existGame != null) {
            throw new BusinessException("游戏代码已存在");
        }

        GameMetadata game = new GameMetadata();
        BeanUtil.copyProperties(dto, game);
        
        gameMetadataMapper.insert(game);
        
        logService.log(getCurrentUser(), "CREATE", "GAME", 
                "创建游戏: " + game.getGameName(), "success", null, null);
        
        return convertToVO(game);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GameVO updateGame(GameUpdateDTO dto) {
        GameMetadata game = gameMetadataMapper.selectById(dto.getId());
        if (game == null) {
            throw new BusinessException("游戏不存在");
        }

        BeanUtil.copyProperties(dto, game, "id", "gameCode");
        gameMetadataMapper.updateById(game);
        
        logService.log(getCurrentUser(), "UPDATE", "GAME", 
                "更新游戏: " + game.getGameName(), "success", null, null);
        
        return convertToVO(game);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteGame(Long id) {
        GameMetadata game = gameMetadataMapper.selectById(id);
        if (game == null) {
            throw new BusinessException("游戏不存在");
        }
        
        gameMetadataMapper.deleteById(id);
        
        logService.log(getCurrentUser(), "DELETE", "GAME", 
                "删除游戏: " + game.getGameName(), "success", null, null);
    }

    @Override
    public GameVO getGameById(Long id) {
        GameMetadata game = gameMetadataMapper.selectById(id);
        if (game == null) {
            throw new BusinessException("游戏不存在");
        }
        return convertToVO(game);
    }

    @Override
    public GameVO getGameByCode(String gameCode) {
        GameMetadata game = gameMetadataMapper.selectByGameCode(gameCode);
        if (game == null) {
            throw new BusinessException("游戏不存在");
        }
        return convertToVO(game);
    }

    @Override
    public PageResult<GameVO> pageGames(PageQueryDTO queryDTO) {
        LambdaQueryWrapper<GameMetadata> wrapper = new LambdaQueryWrapper<>();
        
        // 关键词搜索
        if (queryDTO.getKeyword() != null && !queryDTO.getKeyword().isEmpty()) {
            wrapper.like(GameMetadata::getGameName, queryDTO.getKeyword())
                    .or()
                    .like(GameMetadata::getGameCode, queryDTO.getKeyword());
        }
        
        // 排序
        wrapper.orderByDesc(GameMetadata::getCreateTime);
        
        Page<GameMetadata> page = new Page<>(queryDTO.getCurrent(), queryDTO.getSize());
        Page<GameMetadata> result = gameMetadataMapper.selectPage(page, wrapper);
        
        List<GameVO> voList = result.getRecords().stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        
        return new PageResult<>(voList, result.getTotal(), queryDTO.getCurrent(), queryDTO.getSize());
    }

    @Override
    public List<GameVO> getAllGames() {
        List<GameMetadata> games = gameMetadataMapper.selectAllGames();
        return games.stream().map(this::convertToVO).collect(Collectors.toList());
    }

    @Override
    public List<GameVO> searchGames(String keyword) {
        LambdaQueryWrapper<GameMetadata> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.trim().isEmpty()) {
            String trimmed = keyword.trim();
            wrapper.like(GameMetadata::getGameName, trimmed)
                    .or()
                    .like(GameMetadata::getGameCode, trimmed);
        }
        wrapper.orderByDesc(GameMetadata::getCreateTime);
        List<GameMetadata> games = gameMetadataMapper.selectList(wrapper);
        return games.stream().map(this::convertToVO).collect(Collectors.toList());
    }

    @Override
    public DeployConfigVO getDeployConfig(Long gameId, String deployType) {
        GameMetadata game = gameMetadataMapper.selectById(gameId);
        if (game == null) {
            throw new BusinessException("游戏不存在: " + gameId);
        }
        if (game.getDeployConfig() == null) {
            throw new BusinessException("游戏部署配置为空");
        }

        Object typeConfig = game.getDeployConfig().get(deployType);
        if (!(typeConfig instanceof Map)) {
            throw new BusinessException("不支持的部署类型: " + deployType);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) typeConfig;

        DeployConfigVO vo = new DeployConfigVO();
        vo.setDeployType(deployType);
        vo.setConfig(config);

        // docker-compose 和 linuxgsm-docker 类型特殊处理：提取 composeTemplate/variables/namedVolumes 到顶层
        if ("docker-compose".equals(deployType) || "linuxgsm-docker".equals(deployType)) {
            vo.setComposeTemplate((String) config.get("composeTemplate"));

            Object variablesObj = config.get("variables");
            if (variablesObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> variables = (List<Map<String, Object>>) variablesObj;
                vo.setVariables(variables);
            }

            Object namedVolumesObj = config.get("namedVolumes");
            if (namedVolumesObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> namedVolumes = (List<String>) namedVolumesObj;
                vo.setNamedVolumes(namedVolumes);
            }
        }

        return vo;
    }

    /**
     * 转换为VO
     */
    private GameVO convertToVO(GameMetadata game) {
        GameVO vo = new GameVO();
        BeanUtil.copyProperties(game, vo);
        return vo;
    }

    /**
     * 获取当前用户
     */
    private String getCurrentUser() {
        // TODO: 从SecurityContext获取当前用户
        return "admin";
    }

}

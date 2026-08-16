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
import com.gameplatform.entity.GameInstance;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.mapper.GameMetadataMapper;
import com.gameplatform.plugin.extension.DeployConfigDeclaration;
import com.gameplatform.plugin.extension.GameEnhancementExtension;
import com.gameplatform.plugin.service.PluginFrameworkService;
import com.gameplatform.service.GameService;
import com.gameplatform.vo.DeployConfigVO;
import com.gameplatform.vo.GameVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final GameInstanceMapper gameInstanceMapper;
    private final PluginFrameworkService pluginFrameworkService;

    /** 主应用支持的部署类型 code（与 DeployAdapter.DeployType 一致） */
    private static final Set<String> SUPPORTED_DEPLOY_TYPES =
            Set.of("linuxgsm", "docker", "docker-compose", "linuxgsm-docker");

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

        // 游戏元数据量小，全量查询后在内存中排序：
        // 有运行中实例的游戏优先（run_status=1），组内再按创建时间倒序
        List<GameMetadata> games = gameMetadataMapper.selectList(wrapper);

        java.util.Set<String> runningGameCodes = gameInstanceMapper.selectRunningInstances()
                .stream()
                .map(GameInstance::getGameCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        games.sort(java.util.Comparator
                .comparing((GameMetadata g) -> runningGameCodes.contains(g.getGameCode()) ? 0 : 1)
                .thenComparing(GameMetadata::getCreateTime,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));

        int total = games.size();
        int pageNum = (queryDTO.getCurrent() == null || queryDTO.getCurrent() < 1) ? 1 : queryDTO.getCurrent();
        int pageSize = (queryDTO.getSize() == null || queryDTO.getSize() < 1) ? 10 : queryDTO.getSize();
        int from = (pageNum - 1) * pageSize;
        int to = Math.min(from + pageSize, total);
        List<GameMetadata> pageRecords = (from >= total)
                ? new java.util.ArrayList<>()
                : new java.util.ArrayList<>(games.subList(from, to));

        List<GameVO> voList = pageRecords.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());

        return new PageResult<>(voList, (long) total, pageNum, pageSize);
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

        // 插件声明的部署配置整节替换（插件优先，读取时合并）
        for (DeployConfigDeclaration decl : getPluginDeployConfigs(game)) {
            if (deployType.equals(decl.deployType()) && decl.config() != null) {
                config = decl.config();
                break;
            }
        }

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
        // 合并插件声明的部署方式选项（v3.6.0）：插件声明即选项，
        // 仅合并主应用支持的部署类型 code，未知 code 忽略并告警
        List<DeployConfigDeclaration> decls = getPluginDeployConfigs(game);
        if (!decls.isEmpty()) {
            List<String> merged = new ArrayList<>();
            if (vo.getSupportedDeployTypes() != null) {
                merged.addAll(vo.getSupportedDeployTypes());
            }
            for (DeployConfigDeclaration decl : decls) {
                String type = decl.deployType();
                if (SUPPORTED_DEPLOY_TYPES.contains(type)) {
                    if (!merged.contains(type)) {
                        merged.add(type);
                    }
                } else {
                    log.warn("插件 [{}] 声明了主应用不支持的部署类型: {}（已忽略，执行体系仅支持 {}）",
                            game.getGameCode(), type, SUPPORTED_DEPLOY_TYPES);
                }
            }
            vo.setSupportedDeployTypes(merged);
        }
        return vo;
    }

    /**
     * 获取插件声明的部署方式配置（读取时合并，插件热部署后立即生效）。
     */
    private List<DeployConfigDeclaration> getPluginDeployConfigs(GameMetadata game) {
        if (game == null || game.getGameCode() == null || game.getGameCode().isBlank()) {
            return List.of();
        }
        GameEnhancementExtension ext = pluginFrameworkService.getExtensionByGameCode(game.getGameCode());
        if (ext == null) {
            return List.of();
        }
        List<DeployConfigDeclaration> decls = ext.getDeployConfigs();
        return decls == null ? List.of() : decls;
    }

    /**
     * 获取当前用户
     */

}

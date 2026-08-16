package com.gameplatform.service;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.common.result.PageResult;
import com.gameplatform.dto.GameCreateDTO;
import com.gameplatform.dto.GameUpdateDTO;
import com.gameplatform.dto.PageQueryDTO;
import com.gameplatform.entity.GameMetadata;
import com.gameplatform.mapper.GameMetadataMapper;
import com.gameplatform.service.impl.GameServiceImpl;
import com.gameplatform.vo.GameVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 游戏元数据服务测试类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("游戏元数据服务测试")
class GameServiceTest {

    @Mock
    private GameMetadataMapper gameMetadataMapper;

    @Mock
    private com.gameplatform.mapper.GameInstanceMapper gameInstanceMapper;

    @Mock
    private LogService logService;

    @InjectMocks
    private GameServiceImpl gameService;

    private GameMetadata testGame;
    private GameCreateDTO createDTO;
    private GameUpdateDTO updateDTO;

    @BeforeEach
    void setUp() {
        // Given: 初始化测试数据
        testGame = new GameMetadata();
        testGame.setId(1L);
        testGame.setGameName("Minecraft");
        testGame.setGameCode("minecraft");
        testGame.setDescription("Minecraft游戏服务器");
        testGame.setSupportedDeployTypes(Arrays.asList("docker", "native"));
        testGame.setDefaultPort(25565);
        testGame.setCreateTime(LocalDateTime.now());
        testGame.setUpdateTime(LocalDateTime.now());

        // 默认无运行中实例（pageGames 排序用）
        when(gameInstanceMapper.selectRunningInstances()).thenReturn(Collections.emptyList());

        Map<String, Object> envDeps = new HashMap<>();
        envDeps.put("java", "17");
        envDeps.put("memory", "4G");
        testGame.setEnvironmentDeps(envDeps);

        Map<String, Object> deployConfig = new HashMap<>();
        deployConfig.put("image", "itzg/minecraft-server");
        testGame.setDeployConfig(deployConfig);

        createDTO = new GameCreateDTO();
        createDTO.setGameName("Valheim");
        createDTO.setGameCode("valheim");
        createDTO.setDescription("英灵神殿服务器");
        createDTO.setSupportedDeployTypes(Arrays.asList("docker", "native"));
        createDTO.setDefaultPort(2456);

        updateDTO = new GameUpdateDTO();
        updateDTO.setId(1L);
        updateDTO.setGameName("Minecraft Updated");
        updateDTO.setDescription("更新后的描述");
    }

    @Test
    @DisplayName("创建游戏-成功")
    void testCreateGameSuccess() {
        // Given
        when(gameMetadataMapper.selectByGameCode(createDTO.getGameCode())).thenReturn(null);
        when(gameMetadataMapper.insert(any(GameMetadata.class))).thenAnswer(invocation -> {
            GameMetadata game = invocation.getArgument(0);
            game.setId(2L);
            return 1;
        });
        doNothing().when(logService).log(anyString(), anyString(), anyString(), anyString(), anyString(), isNull(), isNull());

        // When
        GameVO result = gameService.createGame(createDTO);

        // Then
        assertNotNull(result);
        assertEquals(createDTO.getGameName(), result.getGameName());
        assertEquals(createDTO.getGameCode(), result.getGameCode());
        assertEquals(createDTO.getDescription(), result.getDescription());
        assertEquals(createDTO.getDefaultPort(), result.getDefaultPort());
        verify(gameMetadataMapper).selectByGameCode(createDTO.getGameCode());
        verify(gameMetadataMapper).insert(any(GameMetadata.class));
        verify(logService).log(anyString(), eq("CREATE"), eq("GAME"), anyString(), eq("success"), isNull(), isNull());
    }

    @Test
    @DisplayName("创建游戏-游戏代码已存在")
    void testCreateGameCodeExists() {
        // Given
        when(gameMetadataMapper.selectByGameCode(createDTO.getGameCode())).thenReturn(testGame);
        doNothing().when(logService).log(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            gameService.createGame(createDTO);
        });
        assertEquals("游戏代码已存在", exception.getMessage());
        verify(gameMetadataMapper).selectByGameCode(createDTO.getGameCode());
        verify(gameMetadataMapper, never()).insert(any(GameMetadata.class));
    }

    @Test
    @DisplayName("更新游戏-成功")
    void testUpdateGameSuccess() {
        // Given
        when(gameMetadataMapper.selectById(1L)).thenReturn(testGame);
        when(gameMetadataMapper.updateById(any(GameMetadata.class))).thenReturn(1);
        doNothing().when(logService).log(anyString(), anyString(), anyString(), anyString(), anyString(), isNull(), isNull());

        // When
        GameVO result = gameService.updateGame(updateDTO);

        // Then
        assertNotNull(result);
        assertEquals(updateDTO.getGameName(), result.getGameName());
        assertEquals(updateDTO.getDescription(), result.getDescription());
        // gameCode不应该被更新
        assertEquals(testGame.getGameCode(), result.getGameCode());
        verify(gameMetadataMapper).selectById(1L);
        verify(gameMetadataMapper).updateById(any(GameMetadata.class));
        verify(logService).log(anyString(), eq("UPDATE"), eq("GAME"), anyString(), eq("success"), isNull(), isNull());
    }

    @Test
    @DisplayName("更新游戏-游戏不存在")
    void testUpdateGameNotFound() {
        // Given
        when(gameMetadataMapper.selectById(1L)).thenReturn(null);
        doNothing().when(logService).log(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            gameService.updateGame(updateDTO);
        });
        assertEquals("游戏不存在", exception.getMessage());
        verify(gameMetadataMapper).selectById(1L);
        verify(gameMetadataMapper, never()).updateById(any(GameMetadata.class));
    }

    @Test
    @DisplayName("删除游戏-成功")
    void testDeleteGameSuccess() {
        // Given
        when(gameMetadataMapper.selectById(1L)).thenReturn(testGame);
        when(gameMetadataMapper.deleteById(1L)).thenReturn(1);
        doNothing().when(logService).log(anyString(), anyString(), anyString(), anyString(), anyString(), isNull(), isNull());

        // When
        gameService.deleteGame(1L);

        // Then
        verify(gameMetadataMapper).selectById(1L);
        verify(gameMetadataMapper).deleteById(1L);
        verify(logService).log(anyString(), eq("DELETE"), eq("GAME"), anyString(), eq("success"), isNull(), isNull());
    }

    @Test
    @DisplayName("删除游戏-游戏不存在")
    void testDeleteGameNotFound() {
        // Given
        when(gameMetadataMapper.selectById(1L)).thenReturn(null);
        doNothing().when(logService).log(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            gameService.deleteGame(1L);
        });
        assertEquals("游戏不存在", exception.getMessage());
        verify(gameMetadataMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("根据ID查询游戏-成功")
    void testGetGameByIdSuccess() {
        // Given
        when(gameMetadataMapper.selectById(1L)).thenReturn(testGame);

        // When
        GameVO result = gameService.getGameById(1L);

        // Then
        assertNotNull(result);
        assertEquals(testGame.getId(), result.getId());
        assertEquals(testGame.getGameName(), result.getGameName());
        assertEquals(testGame.getGameCode(), result.getGameCode());
        assertEquals(testGame.getDescription(), result.getDescription());
        assertEquals(testGame.getDefaultPort(), result.getDefaultPort());
    }

    @Test
    @DisplayName("根据ID查询游戏-游戏不存在")
    void testGetGameByIdNotFound() {
        // Given
        when(gameMetadataMapper.selectById(1L)).thenReturn(null);

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            gameService.getGameById(1L);
        });
        assertEquals("游戏不存在", exception.getMessage());
    }

    @Test
    @DisplayName("根据游戏代码查询-成功")
    void testGetGameByCodeSuccess() {
        // Given
        when(gameMetadataMapper.selectByGameCode("minecraft")).thenReturn(testGame);

        // When
        GameVO result = gameService.getGameByCode("minecraft");

        // Then
        assertNotNull(result);
        assertEquals(testGame.getGameCode(), result.getGameCode());
        assertEquals(testGame.getGameName(), result.getGameName());
    }

    @Test
    @DisplayName("根据游戏代码查询-游戏不存在")
    void testGetGameByCodeNotFound() {
        // Given
        when(gameMetadataMapper.selectByGameCode("nonexistent")).thenReturn(null);

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            gameService.getGameByCode("nonexistent");
        });
        assertEquals("游戏不存在", exception.getMessage());
    }

    @Test
    @DisplayName("分页查询游戏")
    void testPageGames() {
        // Given
        PageQueryDTO queryDTO = new PageQueryDTO();
        queryDTO.setCurrent(1);
        queryDTO.setSize(10);
        queryDTO.setKeyword("Mine");

        GameMetadata game2 = new GameMetadata();
        game2.setId(2L);
        game2.setGameName("Minecraft Forge");
        game2.setGameCode("minecraft-forge");

        List<GameMetadata> gameList = Arrays.asList(testGame, game2);

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<GameMetadata> pageResult = 
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10, 2);
        pageResult.setRecords(gameList);

        when(gameMetadataMapper.selectPage(any(), any())).thenReturn(pageResult);
        // pageGames 现为全量查询 + 内存排序分页（selectList 是数据源）
        when(gameMetadataMapper.selectList(any())).thenReturn(gameList);

        // When
        PageResult<GameVO> result = gameService.pageGames(queryDTO);

        // Then
        assertNotNull(result);
        assertEquals(2, result.getTotal());
        assertEquals(2, result.getRecords().size());
        assertEquals(1, result.getCurrent());
        assertEquals(10, result.getSize());
    }

    @Test
    @DisplayName("分页查询游戏-无关键词")
    void testPageGamesWithoutKeyword() {
        // Given
        PageQueryDTO queryDTO = new PageQueryDTO();
        queryDTO.setCurrent(1);
        queryDTO.setSize(10);

        List<GameMetadata> gameList = Collections.singletonList(testGame);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<GameMetadata> pageResult = 
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10, 1);
        pageResult.setRecords(gameList);

        when(gameMetadataMapper.selectPage(any(), any())).thenReturn(pageResult);
        // pageGames 现为全量查询 + 内存排序分页（selectList 是数据源）
        when(gameMetadataMapper.selectList(any())).thenReturn(gameList);

        // When
        PageResult<GameVO> result = gameService.pageGames(queryDTO);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getTotal());
        assertEquals(1, result.getRecords().size());
    }

    @Test
    @DisplayName("查询所有游戏")
    void testGetAllGames() {
        // Given
        GameMetadata game2 = new GameMetadata();
        game2.setId(2L);
        game2.setGameName("Valheim");
        game2.setGameCode("valheim");

        when(gameMetadataMapper.selectAllGames()).thenReturn(Arrays.asList(testGame, game2));

        // When
        List<GameVO> result = gameService.getAllGames();

        // Then
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("Minecraft", result.get(0).getGameName());
        assertEquals("Valheim", result.get(1).getGameName());
    }

    @Test
    @DisplayName("查询所有游戏-空列表")
    void testGetAllGamesEmpty() {
        // Given
        when(gameMetadataMapper.selectAllGames()).thenReturn(Collections.emptyList());

        // When
        List<GameVO> result = gameService.getAllGames();

        // Then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("VO转换-支持部署类型列表")
    void testGameVOSupportedDeployTypes() {
        // Given
        when(gameMetadataMapper.selectById(1L)).thenReturn(testGame);

        // When
        GameVO result = gameService.getGameById(1L);

        // Then
        assertNotNull(result.getSupportedDeployTypes());
        assertEquals(2, result.getSupportedDeployTypes().size());
        assertTrue(result.getSupportedDeployTypes().contains("docker"));
        assertTrue(result.getSupportedDeployTypes().contains("native"));
    }

    @Test
    @DisplayName("VO转换-环境依赖和部署配置")
    void testGameVOConfigMaps() {
        // Given
        when(gameMetadataMapper.selectById(1L)).thenReturn(testGame);

        // When
        GameVO result = gameService.getGameById(1L);

        // Then
        assertNotNull(result.getEnvironmentDeps());
        assertNotNull(result.getDeployConfig());
        assertEquals("17", result.getEnvironmentDeps().get("java"));
        assertEquals("4G", result.getEnvironmentDeps().get("memory"));
        assertEquals("itzg/minecraft-server", result.getDeployConfig().get("image"));
    }

    @Test
    @DisplayName("VO转换-空配置处理")
    void testGameVOEmptyConfig() {
        // Given
        GameMetadata gameWithEmptyConfig = new GameMetadata();
        gameWithEmptyConfig.setId(2L);
        gameWithEmptyConfig.setGameName("Test Game");
        gameWithEmptyConfig.setGameCode("test");
        // 不设置可选配置

        when(gameMetadataMapper.selectById(2L)).thenReturn(gameWithEmptyConfig);

        // When
        GameVO result = gameService.getGameById(2L);

        // Then
        assertNotNull(result);
        assertEquals("Test Game", result.getGameName());
        assertNull(result.getEnvironmentDeps());
        assertNull(result.getDeployConfig());
        assertNull(result.getCustomOperations());
    }
}

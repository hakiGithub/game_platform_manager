package com.gameplatform.service;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.common.result.PageResult;
import com.gameplatform.deploy.CatalogView;
import com.gameplatform.deploy.VersionEntry;
import com.gameplatform.dto.GameCreateDTO;
import com.gameplatform.dto.GameUpdateDTO;
import com.gameplatform.dto.PageQueryDTO;
import com.gameplatform.entity.GameMetadata;
import com.gameplatform.mapper.GameMetadataMapper;
import com.gameplatform.plugin.extension.DeployConfigDeclaration;
import com.gameplatform.plugin.extension.GameEnhancementExtension;
import com.gameplatform.plugin.extension.deploy.DeployVersionDeclaration;
import com.gameplatform.plugin.extension.deploy.PatchStepDeclaration;
import com.gameplatform.plugin.extension.deploy.StepKind;
import com.gameplatform.service.impl.GameServiceImpl;
import com.gameplatform.vo.DeployConfigVO;
import com.gameplatform.vo.GameVO;
import com.gameplatform.vo.VersionEntryVO;
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
import java.util.ArrayList;
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
    private com.gameplatform.plugin.service.PluginFrameworkService pluginFrameworkService;

    @Mock
    private com.gameplatform.deploy.DeployVersionCatalogService deployVersionCatalogService;

    @InjectMocks
    private GameServiceImpl gameService;

    private GameMetadata testGame;
    private GameCreateDTO createDTO;
    private GameUpdateDTO updateDTO;

    @BeforeEach
    void setUp() {
        // 被测对象与本用例集无关的目录读者：默认「无插件声明目录」，即现状响应
        lenient().when(deployVersionCatalogService.read(any(), any()))
                .thenReturn(com.gameplatform.deploy.CatalogView.absent());

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
    }

    @Test
    @DisplayName("创建游戏-游戏代码已存在")
    void testCreateGameCodeExists() {
        // Given
        when(gameMetadataMapper.selectByGameCode(createDTO.getGameCode())).thenReturn(testGame);

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
    }

    @Test
    @DisplayName("更新游戏-游戏不存在")
    void testUpdateGameNotFound() {
        // Given
        when(gameMetadataMapper.selectById(1L)).thenReturn(null);

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

        // When
        gameService.deleteGame(1L);

        // Then
        verify(gameMetadataMapper).selectById(1L);
        verify(gameMetadataMapper).deleteById(1L);
    }

    @Test
    @DisplayName("删除游戏-游戏不存在")
    void testDeleteGameNotFound() {
        // Given
        when(gameMetadataMapper.selectById(1L)).thenReturn(null);

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
    @DisplayName("插件声明的部署方式合并进部署选项（插件优先，未知 code 忽略）")
    void testGetAllGames_mergesPluginDeployConfigs() {
        // Given：插件声明 linuxgsm-docker（主应用支持）与 unknown-type（不支持）
        GameEnhancementExtension ext = mock(GameEnhancementExtension.class);
        when(ext.getDeployConfigs()).thenReturn(List.of(
                new DeployConfigDeclaration("linuxgsm-docker", Map.of("imageRepo", "gameservermanagers/gameserver")),
                new DeployConfigDeclaration("unknown-type", Map.of("x", "y"))));
        when(pluginFrameworkService.getExtensionByGameCode("minecraft")).thenReturn(ext);
        testGame.setSupportedDeployTypes(Arrays.asList("docker"));
        when(gameMetadataMapper.selectAllGames()).thenReturn(Collections.singletonList(testGame));

        // When
        List<GameVO> result = gameService.getAllGames();

        // Then：插件类型追加、原类型保留、未知类型忽略
        assertEquals(Arrays.asList("docker", "linuxgsm-docker"), result.get(0).getSupportedDeployTypes());
    }

    @Test
    @DisplayName("getDeployConfig 插件声明的配置节整节替换（插件优先）")
    void testGetDeployConfig_pluginDeclarationReplacesSection() {
        // Given：主应用 yml 有 linuxgsm-docker 节（旧镜像），插件声明同类型新配置
        GameMetadata game = new GameMetadata();
        game.setId(1L);
        game.setGameCode("minecraft");
        game.setDeployConfig(Map.of(
                "linuxgsm-docker", Map.of("imageRepo", "old/repo", "imageTag", "old"),
                "docker", Map.of("image", "docker-image")));
        when(gameMetadataMapper.selectById(1L)).thenReturn(game);

        GameEnhancementExtension ext = mock(GameEnhancementExtension.class);
        when(ext.getDeployConfigs()).thenReturn(List.of(
                new DeployConfigDeclaration("linuxgsm-docker",
                        Map.of("imageRepo", "gameservermanagers/gameserver", "imageTag", "l4d2"))));
        when(pluginFrameworkService.getExtensionByGameCode("minecraft")).thenReturn(ext);

        // When
        DeployConfigVO vo = gameService.getDeployConfig(1L, "linuxgsm-docker");

        // Then：整节替换为插件配置
        assertEquals("gameservermanagers/gameserver", vo.getConfig().get("imageRepo"));
        assertEquals("l4d2", vo.getConfig().get("imageTag"));
        assertFalse(vo.getConfig().containsKey("old"));
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

    // ============================================================
    // B-06 / design.md §16.4：GET 侧版本目录契约
    // ============================================================

    @Test
    @DisplayName("deploy-config 携带版本目录：versionId 与声明精确相等且同序（AC-01 接口侧 / V-21）")
    void testGetDeployConfig_carriesCatalogInDeclarationOrder() {
        stubCatalogGame();
        when(deployVersionCatalogService.read("minecraft", "docker")).thenReturn(CatalogView.available(
                List.of(versionEntry("1.0.0", "初版", true), versionEntry("2.0.0", null, false, 2))));

        DeployConfigVO vo = gameService.getDeployConfig(1L, "docker");

        assertEquals("AVAILABLE", vo.getVersionCatalogState());
        assertNull(vo.getVersionCatalogReason());
        assertEquals(List.of("1.0.0", "2.0.0"),
                vo.getDeployVersions().stream().map(VersionEntryVO::getVersionId).toList());
        assertEquals(List.of("初版", "2.0.0"),
                vo.getDeployVersions().stream().map(VersionEntryVO::getDisplayName).toList());
        assertEquals(List.of(true, false),
                vo.getDeployVersions().stream().map(VersionEntryVO::getIsDefault).toList());
        // 步骤预览只能在服务端算（步骤集解析顺序在 core 侧）
        assertEquals(List.of(1, 2), vo.getDeployVersions().get(1).getStepSummary().stream()
                .map(VersionEntryVO.StepSummaryVO::getIndex).toList());
        assertEquals("PATCH", vo.getDeployVersions().get(1).getStepSummary().get(0).getType());
    }

    @Test
    @DisplayName("ABSENT / EMPTY / INVALID 三态的 deployVersions 都是空数组而非 null，且状态可区分（RISK-13）")
    void testGetDeployConfig_nonAvailableStatesYieldEmptyArrayNotNull() {
        stubCatalogGame();

        for (com.gameplatform.deploy.CatalogView catalog : List.of(
                com.gameplatform.deploy.CatalogView.absent(),
                com.gameplatform.deploy.CatalogView.empty(),
                com.gameplatform.deploy.CatalogView.invalid("versionId 字符集外"))) {
            when(deployVersionCatalogService.read("minecraft", "docker")).thenReturn(catalog);

            DeployConfigVO vo = gameService.getDeployConfig(1L, "docker");

            assertEquals(catalog.state().name(), vo.getVersionCatalogState());
            assertNotNull(vo.getDeployVersions(), catalog.state().name());
            assertTrue(vo.getDeployVersions().isEmpty(), catalog.state().name());
        }
    }

    @Test
    @DisplayName("EMPTY 不产生任何「不合法」说明（AC-24 ③）；仅 INVALID 携带归因")
    void testGetDeployConfig_emptyCatalogCarriesNoReason() {
        stubCatalogGame();
        when(deployVersionCatalogService.read("minecraft", "docker"))
                .thenReturn(com.gameplatform.deploy.CatalogView.empty());
        assertEquals(null, gameService.getDeployConfig(1L, "docker").getVersionCatalogReason());

        when(deployVersionCatalogService.read("minecraft", "docker"))
                .thenReturn(com.gameplatform.deploy.CatalogView.invalid("N2 表侧模板缺占位符"));
        assertEquals("N2 表侧模板缺占位符", gameService.getDeployConfig(1L, "docker").getVersionCatalogReason());
    }

    @Test
    @DisplayName("目录字段是加法：既有 config/variables 的读取结果不因汇入而变")
    void testGetDeployConfig_catalogFieldsDoNotDisturbExistingFields() {
        stubCatalogGame();
        when(deployVersionCatalogService.read("minecraft", "docker"))
                .thenReturn(com.gameplatform.deploy.CatalogView.absent());

        DeployConfigVO vo = gameService.getDeployConfig(1L, "docker");

        assertEquals("docker-image", vo.getConfig().get("image"));
        assertEquals("docker", vo.getDeployType());
    }

    private void stubCatalogGame() {
        GameMetadata game = new GameMetadata();
        game.setId(1L);
        game.setGameCode("minecraft");
        game.setDeployConfig(Map.of("docker", Map.of("image", "docker-image")));
        when(gameMetadataMapper.selectById(1L)).thenReturn(game);
    }

    private static VersionEntry versionEntry(String versionId, String displayName, boolean defaultEntry) {
        return versionEntry(versionId, displayName, defaultEntry, 0);
    }

    private static VersionEntry versionEntry(String versionId, String displayName, boolean defaultEntry,
                                             int patchStepCount) {
        List<PatchStepDeclaration> patches = new ArrayList<>();
        List<VersionEntry.StepSummary> summary = new ArrayList<>();
        for (int i = 1; i <= patchStepCount; i++) {
            patches.add(new PatchStepDeclaration("补丁" + i, "http://127.0.0.1:8099/p" + i + ".zip",
                    "target" + i, null, null, null, true));
            summary.add(new VersionEntry.StepSummary(i, "补丁" + i, StepKind.PATCH, true));
        }
        DeployVersionDeclaration declaration = new DeployVersionDeclaration(
                versionId, displayName, null, defaultEntry, patches, List.of());
        return new VersionEntry(declaration, displayName == null ? versionId : displayName, defaultEntry, summary);
    }
}

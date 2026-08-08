package com.gameplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.config.GameYamlConfig;
import com.gameplatform.entity.GameMetadata;
import com.gameplatform.mapper.GameMetadataMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 游戏元数据扫描服务单元测试
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@DisplayName("游戏元数据扫描服务测试")
class GameMetadataScannerTest {

    @Mock
    private GameMetadataMapper gameMetadataMapper;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private GameMetadataScanner gameMetadataScanner;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    @DisplayName("测试解析有效的YAML配置")
    void testParseValidYaml() {
        String yamlContent = """
            game:
              code: test-game
              name: Test Game
              description: A test game for unit testing
              version: "1.0.0"
              icon: /icons/test.png
              deployTypes:
                - docker
                - linuxgsm
              defaultPorts:
                game: 25565
                query: 25566
              dependencies:
                memory: "2G"
                disk: "1G"
            """;

        GameYamlConfig config = gameMetadataScanner.parseYamlString(yamlContent);

        assertNotNull(config);
        assertNotNull(config.getGame());
        assertEquals("test-game", config.getGame().getCode());
        assertEquals("Test Game", config.getGame().getName());
        assertEquals("A test game for unit testing", config.getGame().getDescription());
        assertEquals("1.0.0", config.getGame().getVersion());
        assertEquals("/icons/test.png", config.getGame().getIcon());
        assertTrue(config.getGame().getDeployTypes().contains("docker"));
        assertTrue(config.getGame().getDeployTypes().contains("linuxgsm"));
        assertEquals(25565, config.getGame().getDefaultPorts().get("game"));
        assertEquals(25566, config.getGame().getDefaultPorts().get("query"));
    }

    @Test
    @DisplayName("测试验证有效的YAML配置")
    void testValidateValidYaml() {
        String yamlContent = """
            game:
              code: valid-game
              name: Valid Game
              description: Valid description
            """;

        GameMetadataScanner.ValidationResult result = gameMetadataScanner.validateYaml(yamlContent);

        assertTrue(result.isValid());
        assertEquals("valid-game", result.getGameCode());
        assertEquals("Valid Game", result.getGameName());
        assertNull(result.getError());
    }

    @Test
    @DisplayName("测试验证无效的YAML配置 - 缺少游戏代码")
    void testValidateInvalidYamlMissingCode() {
        String yamlContent = """
            game:
              name: Invalid Game
              description: Missing code
            """;

        GameMetadataScanner.ValidationResult result = gameMetadataScanner.validateYaml(yamlContent);

        assertFalse(result.isValid());
        assertNotNull(result.getError());
        assertTrue(result.getError().contains("游戏代码不能为空"));
    }

    @Test
    @DisplayName("测试验证无效的YAML配置 - 缺少游戏名称")
    void testValidateInvalidYamlMissingName() {
        String yamlContent = """
            game:
              code: invalid-game
              description: Missing name
            """;

        GameMetadataScanner.ValidationResult result = gameMetadataScanner.validateYaml(yamlContent);

        assertFalse(result.isValid());
        assertNotNull(result.getError());
        assertTrue(result.getError().contains("游戏名称不能为空"));
    }

    @Test
    @DisplayName("测试验证无效的YAML格式")
    void testValidateInvalidYamlFormat() {
        String yamlContent = """
            invalid yaml content: [{
            """;

        GameMetadataScanner.ValidationResult result = gameMetadataScanner.validateYaml(yamlContent);

        assertFalse(result.isValid());
        assertNotNull(result.getError());
        assertTrue(result.getError().contains("YAML解析错误"));
    }

    @Test
    @DisplayName("测试解析完整的YAML配置包含Docker配置")
    void testParseYamlWithDockerConfig() {
        String yamlContent = """
            game:
              code: docker-game
              name: Docker Game
              description: Game with Docker config
              docker:
                image: test/image
                tag: latest
                env:
                  KEY1: value1
                  KEY2: value2
                volumes:
                  - /data
                  - /config
                ports:
                  - "25565:25565"
                  - "25575:25575"
                restartPolicy: unless-stopped
            """;

        GameYamlConfig config = gameMetadataScanner.parseYamlString(yamlContent);

        assertNotNull(config);
        assertNotNull(config.getGame().getDocker());
        assertEquals("test/image", config.getGame().getDocker().getImage());
        assertEquals("latest", config.getGame().getDocker().getTag());
        assertEquals("value1", config.getGame().getDocker().getEnv().get("KEY1"));
        assertEquals(2, config.getGame().getDocker().getVolumes().size());
        assertEquals(2, config.getGame().getDocker().getPorts().size());
        assertEquals("unless-stopped", config.getGame().getDocker().getRestartPolicy());
    }

    @Test
    @DisplayName("测试解析完整的YAML配置包含LinuxGSM配置")
    void testParseYamlWithLinuxGsmConfig() {
        String yamlContent = """
            game:
              code: linuxgsm-game
              name: LinuxGSM Game
              description: Game with LinuxGSM config
              linuxgsm:
                script: testserver
                gameCode: tg
                configFile: server.cfg
                installDir: /home/testserver
                startParams: -param1 -param2
                ports:
                  - "25565/tcp"
                  - "25566/udp"
            """;

        GameYamlConfig config = gameMetadataScanner.parseYamlString(yamlContent);

        assertNotNull(config);
        assertNotNull(config.getGame().getLinuxgsm());
        assertEquals("testserver", config.getGame().getLinuxgsm().getScript());
        assertEquals("tg", config.getGame().getLinuxgsm().getGameCode());
        assertEquals("server.cfg", config.getGame().getLinuxgsm().getConfigFile());
        assertEquals("/home/testserver", config.getGame().getLinuxgsm().getInstallDir());
        assertEquals("-param1 -param2", config.getGame().getLinuxgsm().getStartParams());
        assertEquals(2, config.getGame().getLinuxgsm().getPorts().size());
    }

    @Test
    @DisplayName("测试解析完整的YAML配置包含ConfigSchema")
    void testParseYamlWithConfigSchema() {
        String yamlContent = """
            game:
              code: schema-game
              name: Schema Game
              description: Game with config schema
              configSchema:
                properties:
                  maxPlayers:
                    type: integer
                    default: 20
                    minimum: 1
                    maximum: 100
                    label: 最大玩家数
                  difficulty:
                    type: string
                    enum:
                      - easy
                      - normal
                      - hard
                    default: normal
                    label: 游戏难度
                required:
                  - maxPlayers
                layout:
                  columns: 2
            """;

        GameYamlConfig config = gameMetadataScanner.parseYamlString(yamlContent);

        assertNotNull(config);
        assertNotNull(config.getGame().getConfigSchema());
        assertNotNull(config.getGame().getConfigSchema().getProperties());
        assertTrue(config.getGame().getConfigSchema().getProperties().containsKey("maxPlayers"));
        assertTrue(config.getGame().getConfigSchema().getProperties().containsKey("difficulty"));
        assertEquals(2, config.getGame().getConfigSchema().getLayout().getColumns());
    }

    @Test
    @DisplayName("测试解析完整的YAML配置包含自定义操作")
    void testParseYamlWithCustomOperations() {
        String yamlContent = """
            game:
              code: ops-game
              name: Operations Game
              description: Game with custom operations
              customOperations:
                - name: 备份存档
                  command: backup
                  description: 备份游戏存档
                  icon: Download
                  type: backup
                  confirm: true
                  async: true
                  timeout: 300
                - name: 清理日志
                  command: clean-logs
                  description: 清理日志文件
                  icon: Delete
                  type: maintenance
            """;

        GameYamlConfig config = gameMetadataScanner.parseYamlString(yamlContent);

        assertNotNull(config);
        assertNotNull(config.getGame().getCustomOperations());
        assertEquals(2, config.getGame().getCustomOperations().size());

        GameYamlConfig.CustomOperation backupOp = config.getGame().getCustomOperations().get(0);
        assertEquals("备份存档", backupOp.getName());
        assertEquals("backup", backupOp.getCommand());
        assertEquals("Download", backupOp.getIcon());
        assertTrue(backupOp.getConfirm());
        assertTrue(backupOp.getAsync());
        assertEquals(300, backupOp.getTimeout());
    }

    @Test
    @DisplayName("测试配置验证方法")
    void testConfigValidation() {
        GameYamlConfig config = new GameYamlConfig();

        // 空配置应该无效
        assertFalse(config.isValid());
        assertNotNull(config.getValidationError());

        // 设置游戏信息
        GameYamlConfig.GameInfo gameInfo = new GameYamlConfig.GameInfo();
        config.setGame(gameInfo);

        // 缺少代码和名称应该无效
        assertFalse(config.isValid());

        // 设置代码
        gameInfo.setCode("test-code");
        assertFalse(config.isValid());

        // 设置名称
        gameInfo.setName("Test Name");
        assertTrue(config.isValid());
        assertNull(config.getValidationError());
    }

    @Test
    @DisplayName("测试检查部署类型支持")
    void testSupportsDeployType() {
        GameYamlConfig config = new GameYamlConfig();
        GameYamlConfig.GameInfo gameInfo = new GameYamlConfig.GameInfo();
        gameInfo.setDeployTypes(Arrays.asList("docker", "linuxgsm"));
        config.setGame(gameInfo);

        assertTrue(config.supportsDeployType("docker"));
        assertTrue(config.supportsDeployType("linuxgsm"));
        assertFalse(config.supportsDeployType("native"));
        assertFalse(config.supportsDeployType("unknown"));
    }

    @Test
    @DisplayName("测试获取主端口")
    void testGetMainPort() {
        GameYamlConfig config = new GameYamlConfig();
        GameYamlConfig.GameInfo gameInfo = new GameYamlConfig.GameInfo();

        // 没有端口配置
        config.setGame(gameInfo);
        assertNull(config.getMainPort());

        // 设置端口
        Map<String, Integer> ports = new HashMap<>();
        ports.put("game", 25565);
        ports.put("query", 25566);
        gameInfo.setDefaultPorts(ports);

        assertEquals(25565, config.getMainPort());

        // 移除game端口，应该返回第一个可用端口
        ports.remove("game");
        assertNotNull(config.getMainPort());
    }

    @Test
    @DisplayName("测试扫描外部目录")
    void testScanExternalDirectory() throws IOException {
        // 创建测试YAML文件
        File yamlFile = tempDir.resolve("test-game.yml").toFile();
        try (FileWriter writer = new FileWriter(yamlFile)) {
            writer.write("""
                game:
                  code: external-game
                  name: External Game
                  description: Game from external directory
                """);
        }

        // 模拟数据库返回null（表示新游戏）
        when(gameMetadataMapper.selectByGameCode(any())).thenReturn(null);
        when(objectMapper.convertValue(any(), eq(Map.class))).thenReturn(new HashMap<>());

        GameMetadataScanner.ScanResult result = gameMetadataScanner.scanExternalDirectory(tempDir.toString());

        assertNotNull(result);
        assertEquals(1, result.getTotalFiles());
        assertEquals(1, result.getSuccessCount());
        assertTrue(result.getLoadedGames().contains("external-game"));

        // 验证数据库操作被调用
        verify(gameMetadataMapper, times(1)).insert(any(GameMetadata.class));
    }

    @Test
    @DisplayName("测试扫描外部目录 - 更新已存在的游戏")
    void testScanExternalDirectoryUpdateExisting() throws IOException {
        // 创建测试YAML文件
        File yamlFile = tempDir.resolve("existing-game.yml").toFile();
        try (FileWriter writer = new FileWriter(yamlFile)) {
            writer.write("""
                game:
                  code: existing-game
                  name: Existing Game
                  description: Updated description
                """);
        }

        // 模拟数据库返回已存在的游戏
        GameMetadata existingGame = new GameMetadata();
        existingGame.setId(1L);
        existingGame.setGameCode("existing-game");
        existingGame.setGameName("Old Name");

        when(gameMetadataMapper.selectByGameCode("existing-game")).thenReturn(existingGame);
        when(objectMapper.convertValue(any(), eq(Map.class))).thenReturn(new HashMap<>());

        GameMetadataScanner.ScanResult result = gameMetadataScanner.scanExternalDirectory(tempDir.toString());

        assertNotNull(result);
        assertEquals(1, result.getTotalFiles());
        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getUpdateCount());

        // 验证数据库更新操作被调用
        verify(gameMetadataMapper, times(1)).updateById(any(GameMetadata.class));
        verify(gameMetadataMapper, never()).insert(any(GameMetadata.class));
    }

    @Test
    @DisplayName("测试扫描外部目录 - 无效的配置文件")
    void testScanExternalDirectoryInvalidConfig() throws IOException {
        // 创建无效的YAML文件
        File yamlFile = tempDir.resolve("invalid-game.yml").toFile();
        try (FileWriter writer = new FileWriter(yamlFile)) {
            writer.write("""
                game:
                  name: Invalid Game
                  # 缺少 code
                """);
        }

        GameMetadataScanner.ScanResult result = gameMetadataScanner.scanExternalDirectory(tempDir.toString());

        assertNotNull(result);
        assertEquals(1, result.getTotalFiles());
        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getErrorCount());
        assertFalse(result.getErrors().isEmpty());
    }

    @Test
    @DisplayName("测试扫描不存在的目录")
    void testScanNonExistentDirectory() {
        GameMetadataScanner.ScanResult result = gameMetadataScanner.scanExternalDirectory("/non/existent/path");

        assertNotNull(result);
        assertEquals(0, result.getTotalFiles());
        assertEquals(1, result.getErrorCount());
        assertFalse(result.getErrors().isEmpty());
    }

    @Test
    @DisplayName("测试导出游戏配置")
    void testExportGameConfig() {
        // 准备测试数据
        GameMetadata metadata = new GameMetadata();
        metadata.setId(1L);
        metadata.setGameCode("export-game");
        metadata.setGameName("Export Game");
        metadata.setDescription("Game for export test");
        metadata.setIconUrl("/icons/export.png");
        metadata.setSupportedDeployTypes(Arrays.asList("docker", "linuxgsm"));
        metadata.setDefaultPort(25565);

        Map<String, Object> deployConfig = new HashMap<>();
        deployConfig.put("version", "1.0.0");
        Map<String, Integer> ports = new HashMap<>();
        ports.put("game", 25565);
        deployConfig.put("defaultPorts", ports);
        metadata.setDeployConfig(deployConfig);

        when(gameMetadataMapper.selectByGameCode("export-game")).thenReturn(metadata);

        String yamlContent = gameMetadataScanner.exportGameConfig("export-game");

        assertNotNull(yamlContent);
        assertTrue(yamlContent.contains("game:"));
        assertTrue(yamlContent.contains("code: export-game"));
        assertTrue(yamlContent.contains("name: Export Game"));

        verify(gameMetadataMapper, times(1)).selectByGameCode("export-game");
    }

    @Test
    @DisplayName("测试导出不存在的游戏配置")
    void testExportNonExistentGameConfig() {
        when(gameMetadataMapper.selectByGameCode("non-existent")).thenReturn(null);

        Exception exception = assertThrows(RuntimeException.class, () -> {
            gameMetadataScanner.exportGameConfig("non-existent");
        });

        assertTrue(exception.getMessage().contains("游戏不存在"));
    }
}

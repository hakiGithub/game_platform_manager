package com.gameplatform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.config.GameYamlConfig;
import com.gameplatform.entity.GameMetadata;
import com.gameplatform.mapper.GameMetadataMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 游戏元数据扫描服务
 * 负责扫描YAML配置文件并加载到数据库
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameMetadataScanner {

    private final GameMetadataMapper gameMetadataMapper;
    private final ObjectMapper objectMapper;

    @Value("${game-platform.metadata.scan-path:classpath:games/}")
    private String scanPath;

    @Value("${game-platform.metadata.hot-reload:false}")
    private Boolean hotReload;

    private final PathMatchingResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();

    /**
     * 扫描结果统计
     */
    @Data
    public static class ScanResult {
        private int totalFiles = 0;
        private int successCount = 0;
        private int updateCount = 0;
        private int errorCount = 0;
        private List<String> errors = new ArrayList<>();
        private List<String> loadedGames = new ArrayList<>();

        public void addError(String error) {
            errors.add(error);
            errorCount++;
        }

        public void addSuccess(String gameCode) {
            successCount++;
            loadedGames.add(gameCode);
        }

        public void addUpdate(String gameCode) {
            updateCount++;
            loadedGames.add(gameCode);
        }
    }

    /**
     * 应用启动时扫描
     */
    @PostConstruct
    public void init() {
        log.info("开始初始化游戏元数据扫描...");
        ScanResult result = scanAndLoad();
        log.info("游戏元数据扫描完成: 总计={}, 成功={}, 更新={}, 失败={}",
                result.getTotalFiles(), result.getSuccessCount(),
                result.getUpdateCount(), result.getErrorCount());
        if (!result.getErrors().isEmpty()) {
            result.getErrors().forEach(error -> log.warn("扫描错误: {}", error));
        }
    }

    /**
     * 扫描并加载游戏元数据
     *
     * @return 扫描结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ScanResult scanAndLoad() {
        ScanResult result = new ScanResult();

        try {
            // 获取YAML文件
            List<File> yamlFiles = getYamlFiles();
            result.setTotalFiles(yamlFiles.size());

            log.info("发现 {} 个游戏元数据配置文件", yamlFiles.size());

            for (File file : yamlFiles) {
                try {
                    processYamlFile(file, result);
                } catch (Exception e) {
                    String errorMsg = String.format("处理文件 %s 失败: %s", file.getName(), e.getMessage());
                    log.error(errorMsg, e);
                    result.addError(errorMsg);
                }
            }

        } catch (Exception e) {
            log.error("扫描游戏元数据失败", e);
            result.addError("扫描失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 从外部目录扫描
     *
     * @param externalPath 外部目录路径
     * @return 扫描结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ScanResult scanExternalDirectory(String externalPath) {
        ScanResult result = new ScanResult();

        try {
            Path path = Paths.get(externalPath);
            if (!Files.exists(path) || !Files.isDirectory(path)) {
                result.addError("目录不存在: " + externalPath);
                return result;
            }

            List<File> yamlFiles = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path, "*.yml")) {
                for (Path entry : stream) {
                    yamlFiles.add(entry.toFile());
                }
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path, "*.yaml")) {
                for (Path entry : stream) {
                    yamlFiles.add(entry.toFile());
                }
            }

            result.setTotalFiles(yamlFiles.size());

            for (File file : yamlFiles) {
                try {
                    processYamlFile(file, result);
                } catch (Exception e) {
                    String errorMsg = String.format("处理文件 %s 失败: %s", file.getName(), e.getMessage());
                    log.error(errorMsg, e);
                    result.addError(errorMsg);
                }
            }

        } catch (Exception e) {
            log.error("扫描外部目录失败", e);
            result.addError("扫描失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 获取YAML文件列表
     */
    private List<File> getYamlFiles() throws IOException {
        List<File> files = new ArrayList<>();

        // 扫描classpath下的games目录
        try {
            Resource[] resources = resourceResolver.getResources(scanPath + "*.yml");
            for (Resource resource : resources) {
                if (resource.exists()) {
                    files.add(resource.getFile());
                }
            }
        } catch (Exception e) {
            log.debug("扫描yml文件失败: {}", e.getMessage());
        }

        try {
            Resource[] resources = resourceResolver.getResources(scanPath + "*.yaml");
            for (Resource resource : resources) {
                if (resource.exists()) {
                    files.add(resource.getFile());
                }
            }
        } catch (Exception e) {
            log.debug("扫描yaml文件失败: {}", e.getMessage());
        }

        return files;
    }

    /**
     * 处理单个YAML文件
     */
    private void processYamlFile(File file, ScanResult result) {
        String filename = file.getName();
        log.info("正在处理游戏配置文件: {}", filename);

        try {
            // 解析YAML
            GameYamlConfig config = parseYamlFile(file);

            // 验证配置
            if (!config.isValid()) {
                String error = String.format("文件 %s 验证失败: %s", filename, config.getValidationError());
                result.addError(error);
                return;
            }

            // 转换为实体并保存
            boolean isUpdate = saveOrUpdateGameMetadata(config);

            if (isUpdate) {
                result.addUpdate(config.getGame().getCode());
                log.info("更新游戏元数据: {} ({})", config.getGame().getName(), config.getGame().getCode());
            } else {
                result.addSuccess(config.getGame().getCode());
                log.info("新增游戏元数据: {} ({})", config.getGame().getName(), config.getGame().getCode());
            }

        } catch (Exception e) {
            String error = String.format("解析文件 %s 失败: %s", filename, e.getMessage());
            log.error(error, e);
            result.addError(error);
        }
    }

    /**
     * 解析YAML文件
     */
    private GameYamlConfig parseYamlFile(File file) throws FileNotFoundException {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setCodePointLimit(10 * 1024 * 1024); // 10MB限制

        Constructor constructor = new Constructor(GameYamlConfig.class, loaderOptions);
        Yaml yaml = new Yaml(constructor);

        try (InputStream inputStream = new FileInputStream(file);
             Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            return yaml.load(reader);
        } catch (IOException e) {
            throw new RuntimeException("读取YAML文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 保存或更新游戏元数据
     *
     * @param config YAML配置
     * @return true表示更新，false表示新增
     */
    private boolean saveOrUpdateGameMetadata(GameYamlConfig config) throws JsonProcessingException {
        GameYamlConfig.GameInfo gameInfo = config.getGame();
        String gameCode = gameInfo.getCode();

        // 查询是否已存在
        GameMetadata existMetadata = gameMetadataMapper.selectByGameCode(gameCode);

        GameMetadata metadata = new GameMetadata();
        metadata.setGameCode(gameCode);
        metadata.setGameName(gameInfo.getName());
        metadata.setDescription(gameInfo.getDescription());
        metadata.setIconUrl(gameInfo.getIcon());

        // 支持的部署类型
        metadata.setSupportedDeployTypes(gameInfo.getDeployTypes());

        // 默认端口
        Integer mainPort = config.getMainPort();
        metadata.setDefaultPort(mainPort);

        // 环境依赖
        if (gameInfo.getDependencies() != null && !gameInfo.getDependencies().isEmpty()) {
            metadata.setEnvironmentDeps(objectMapper.convertValue(gameInfo.getDependencies(), Map.class));
        }

        // 部署配置
        Map<String, Object> deployConfig = buildDeployConfig(config);
        metadata.setDeployConfig(deployConfig);

        // 自定义操作
        if (gameInfo.getCustomOperations() != null && !gameInfo.getCustomOperations().isEmpty()) {
            Map<String, Object> operationsMap = new HashMap<>();
            for (GameYamlConfig.CustomOperation operation : gameInfo.getCustomOperations()) {
                operationsMap.put(operation.getCommand(), objectMapper.convertValue(operation, Map.class));
            }
            metadata.setCustomOperations(operationsMap);
        }

        if (existMetadata != null) {
            // 更新
            metadata.setId(existMetadata.getId());
            gameMetadataMapper.updateById(metadata);
            return true;
        } else {
            // 新增
            gameMetadataMapper.insert(metadata);
            return false;
        }
    }

    /**
     * 构建部署配置
     */
    private Map<String, Object> buildDeployConfig(GameYamlConfig config) {
        Map<String, Object> deployConfig = new HashMap<>();
        GameYamlConfig.GameInfo gameInfo = config.getGame();

        // 基础配置
        deployConfig.put("version", gameInfo.getVersion());
        deployConfig.put("defaultPorts", gameInfo.getDefaultPorts());

        // Docker配置
        if (gameInfo.getDocker() != null) {
            Map<String, Object> dockerConfig = new HashMap<>();
            GameYamlConfig.DockerConfig docker = gameInfo.getDocker();

            dockerConfig.put("image", docker.getImage());
            dockerConfig.put("tag", docker.getTag());
            dockerConfig.put("env", docker.getEnv());
            dockerConfig.put("volumes", docker.getVolumes());
            dockerConfig.put("ports", docker.getPorts());
            dockerConfig.put("restartPolicy", docker.getRestartPolicy());
            dockerConfig.put("networkMode", docker.getNetworkMode());

            if (docker.getResources() != null) {
                dockerConfig.put("resources", objectMapper.convertValue(docker.getResources(), Map.class));
            }

            if (docker.getHealthCheck() != null) {
                dockerConfig.put("healthCheck", objectMapper.convertValue(docker.getHealthCheck(), Map.class));
            }

            deployConfig.put("docker", dockerConfig);
        }

        // LinuxGSM配置
        if (gameInfo.getLinuxgsm() != null) {
            Map<String, Object> linuxgsmConfig = new HashMap<>();
            GameYamlConfig.LinuxGsmConfig linuxgsm = gameInfo.getLinuxgsm();

            linuxgsmConfig.put("script", linuxgsm.getScript());
            linuxgsmConfig.put("gameCode", linuxgsm.getGameCode());
            linuxgsmConfig.put("configFile", linuxgsm.getConfigFile());
            linuxgsmConfig.put("installDir", linuxgsm.getInstallDir());
            linuxgsmConfig.put("startParams", linuxgsm.getStartParams());
            linuxgsmConfig.put("ports", linuxgsm.getPorts());

            deployConfig.put("linuxgsm", linuxgsmConfig);
        }

        // Docker Compose配置
        if (gameInfo.getDockerCompose() != null) {
            GameYamlConfig.DockerComposeConfig dc = gameInfo.getDockerCompose();
            Map<String, Object> composeConfig = new HashMap<>();
            composeConfig.put("composeTemplate", dc.getComposeTemplate());
            composeConfig.put("variables", objectMapper.convertValue(dc.getVariables(),
                    new TypeReference<List<Map<String, Object>>>() {}));
            composeConfig.put("namedVolumes", dc.getNamedVolumes());
            // 容器内游戏数据根目录（InstanceFileService 解析相对路径时回退使用）
            composeConfig.put("workingDir", dc.getWorkingDir());
            // 宿主机证书挂载选项（用于反向代理场景，前端用户可覆盖此默认值）
            composeConfig.put("mountHostCerts", dc.isMountHostCerts());
            composeConfig.put("hostCertPath", dc.getHostCertPath());
            // 数据库连接声明（ADR-0009）：部署/更新时按此组装 configInfo.database
            if (dc.getDatabase() != null) {
                composeConfig.put("database",
                        objectMapper.convertValue(dc.getDatabase(), new TypeReference<Map<String, Object>>() {}));
            }
            deployConfig.put("docker-compose", composeConfig);
        }

        // LinuxGSM Docker配置（基于 gameservermanagers/gameserver 镜像）
        if (gameInfo.getLinuxgsmDocker() != null) {
            GameYamlConfig.LinuxGsmDockerConfig lgsmDocker = gameInfo.getLinuxgsmDocker();
            Map<String, Object> lgsmDockerConfig = new HashMap<>();
            lgsmDockerConfig.put("shortname", lgsmDocker.getShortname());
            lgsmDockerConfig.put("imageTag", lgsmDocker.getImageTag());
            lgsmDockerConfig.put("imageRepo", lgsmDocker.getImageRepo());
            lgsmDockerConfig.put("composeTemplate", lgsmDocker.getComposeTemplate());
            lgsmDockerConfig.put("variables", objectMapper.convertValue(lgsmDocker.getVariables(),
                    new TypeReference<List<Map<String, Object>>>() {}));
            lgsmDockerConfig.put("namedVolumes", lgsmDocker.getNamedVolumes());
            // 宿主机证书挂载选项（用于反向代理场景）
            lgsmDockerConfig.put("mountHostCerts", lgsmDocker.isMountHostCerts());
            lgsmDockerConfig.put("hostCertPath", lgsmDocker.getHostCertPath());
            deployConfig.put("linuxgsm-docker", lgsmDockerConfig);
        }

        // 配置Schema
        if (gameInfo.getConfigSchema() != null) {
            deployConfig.put("configSchema", objectMapper.convertValue(gameInfo.getConfigSchema(), Map.class));
        }

        return deployConfig;
    }

    /**
     * 从YAML字符串加载配置
     *
     * @param yamlContent YAML内容
     * @return 配置对象
     */
    public GameYamlConfig parseYamlString(String yamlContent) {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setCodePointLimit(10 * 1024 * 1024);

        Constructor constructor = new Constructor(GameYamlConfig.class, loaderOptions);
        Yaml yaml = new Yaml(constructor);

        return yaml.load(yamlContent);
    }

    /**
     * 验证YAML配置
     *
     * @param yamlContent YAML内容
     * @return 验证结果
     */
    public ValidationResult validateYaml(String yamlContent) {
        ValidationResult result = new ValidationResult();

        try {
            GameYamlConfig config = parseYamlString(yamlContent);

            if (!config.isValid()) {
                result.setValid(false);
                result.setError(config.getValidationError());
                return result;
            }

            result.setValid(true);
            result.setGameCode(config.getGame().getCode());
            result.setGameName(config.getGame().getName());

        } catch (Exception e) {
            result.setValid(false);
            result.setError("YAML解析错误: " + e.getMessage());
        }

        return result;
    }

    /**
     * 验证结果
     */
    @Data
    public static class ValidationResult {
        private boolean valid;
        private String error;
        private String gameCode;
        private String gameName;
    }

    /**
     * 导出游戏配置为YAML
     *
     * @param gameCode 游戏代码
     * @return YAML内容
     */
    public String exportGameConfig(String gameCode) {
        GameMetadata metadata = gameMetadataMapper.selectByGameCode(gameCode);
        if (metadata == null) {
            throw new RuntimeException("游戏不存在: " + gameCode);
        }

        GameYamlConfig config = convertToYamlConfig(metadata);

        // 使用SnakeYAML导出
        org.yaml.snakeyaml.DumperOptions dumperOptions = new org.yaml.snakeyaml.DumperOptions();
        dumperOptions.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
        dumperOptions.setIndent(2);

        Yaml yaml = new Yaml(dumperOptions);
        return yaml.dump(config);
    }

    /**
     * 将实体转换为YAML配置
     */
    private GameYamlConfig convertToYamlConfig(GameMetadata metadata) {
        GameYamlConfig config = new GameYamlConfig();
        GameYamlConfig.GameInfo gameInfo = new GameYamlConfig.GameInfo();

        gameInfo.setCode(metadata.getGameCode());
        gameInfo.setName(metadata.getGameName());
        gameInfo.setDescription(metadata.getDescription());
        gameInfo.setIcon(metadata.getIconUrl());
        gameInfo.setDeployTypes(metadata.getSupportedDeployTypes());

        if (metadata.getDeployConfig() != null) {
            Map<String, Object> deployConfig = metadata.getDeployConfig();

            gameInfo.setVersion((String) deployConfig.get("version"));

            @SuppressWarnings("unchecked")
            Map<String, Integer> ports = (Map<String, Integer>) deployConfig.get("defaultPorts");
            if (ports != null) {
                gameInfo.setDefaultPorts(ports);
            }

            // Docker配置
            @SuppressWarnings("unchecked")
            Map<String, Object> dockerConfig = (Map<String, Object>) deployConfig.get("docker");
            if (dockerConfig != null) {
                GameYamlConfig.DockerConfig docker = objectMapper.convertValue(dockerConfig, GameYamlConfig.DockerConfig.class);
                gameInfo.setDocker(docker);
            }

            // LinuxGSM配置
            @SuppressWarnings("unchecked")
            Map<String, Object> linuxgsmConfig = (Map<String, Object>) deployConfig.get("linuxgsm");
            if (linuxgsmConfig != null) {
                GameYamlConfig.LinuxGsmConfig linuxgsm = objectMapper.convertValue(linuxgsmConfig, GameYamlConfig.LinuxGsmConfig.class);
                gameInfo.setLinuxgsm(linuxgsm);
            }

            // LinuxGSM Docker配置
            @SuppressWarnings("unchecked")
            Map<String, Object> lgsmDockerConfig = (Map<String, Object>) deployConfig.get("linuxgsm-docker");
            if (lgsmDockerConfig != null) {
                GameYamlConfig.LinuxGsmDockerConfig lgsmDocker = objectMapper.convertValue(lgsmDockerConfig, GameYamlConfig.LinuxGsmDockerConfig.class);
                gameInfo.setLinuxgsmDocker(lgsmDocker);
            }

            // ConfigSchema
            @SuppressWarnings("unchecked")
            Map<String, Object> configSchemaMap = (Map<String, Object>) deployConfig.get("configSchema");
            if (configSchemaMap != null) {
                GameYamlConfig.ConfigSchema configSchema = objectMapper.convertValue(configSchemaMap, GameYamlConfig.ConfigSchema.class);
                gameInfo.setConfigSchema(configSchema);
            }
        }

        if (metadata.getEnvironmentDeps() != null) {
            @SuppressWarnings("unchecked")
            Map<String, String> deps = new HashMap<>();
            for (Map.Entry<String, Object> entry : metadata.getEnvironmentDeps().entrySet()) {
                deps.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
            gameInfo.setDependencies(deps);
        }

        if (metadata.getCustomOperations() != null) {
            List<GameYamlConfig.CustomOperation> operations = new ArrayList<>();
            for (Map.Entry<String, Object> entry : metadata.getCustomOperations().entrySet()) {
                @SuppressWarnings("unchecked")
                GameYamlConfig.CustomOperation op = objectMapper.convertValue(entry.getValue(), GameYamlConfig.CustomOperation.class);
                operations.add(op);
            }
            gameInfo.setCustomOperations(operations);
        }

        config.setGame(gameInfo);
        return config;
    }
}

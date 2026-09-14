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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

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

    /** 外部扩展目录（Docker 部署挂载 ./games → /app/games），同 game_code 覆盖内置配置 */
    @Value("${game-platform.metadata.external-dir:}")
    private String externalDir;

    private final PathMatchingResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();

    /**
     * 扫描结果统计
     */
    @Data
    public static class ScanResult {
        private int totalFiles = 0;
        private int successCount = 0;
        private int updateCount = 0;
        private int skippedCount = 0;
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

        /** 内容与库中一致，跳过写库（热扫描周期内免无谓 UPDATE） */
        public void addSkipped(String gameCode) {
            skippedCount++;
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
     * 扫描并加载游戏元数据：classpath 内置配置 + 外部扩展目录（同 game_code 外部覆盖内置）
     *
     * @return 扫描结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ScanResult scanAndLoad() {
        ScanResult result = new ScanResult();

        // classpath 内置游戏（fat jar 下资源在嵌套 jar 内，必须走流读取，getFile() 不可用）
        int classpathFiles = scanClasspath(result);
        // 外部扩展目录（类似 plugins 目录的 drop-in 语义，热重载周期性重扫）
        int externalFiles = scanConfiguredExternalDir(result);
        result.setTotalFiles(classpathFiles + externalFiles);

        return result;
    }

    /**
     * 扫描 classpath 下的内置游戏配置
     *
     * @return 发现的配置文件数
     */
    private int scanClasspath(ScanResult result) {
        List<Resource> resources = new ArrayList<>();
        for (String suffix : new String[]{"*.yml", "*.yaml"}) {
            try {
                for (Resource resource : resourceResolver.getResources(scanPath + suffix)) {
                    if (resource.exists()) {
                        resources.add(resource);
                    }
                }
            } catch (Exception e) {
                log.debug("扫描classpath游戏配置失败: {}", e.getMessage());
            }
        }

        for (Resource resource : resources) {
            try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                processYamlSource(resource.getFilename(), reader, result);
            } catch (Exception e) {
                String errorMsg = String.format("处理资源 %s 失败: %s", resource.getFilename(), e.getMessage());
                log.error(errorMsg, e);
                result.addError(errorMsg);
            }
        }
        return resources.size();
    }

    /** 扫描配置声明的外部目录（未配置或目录不存在时静默跳过） */
    private int scanConfiguredExternalDir(ScanResult result) {
        if (externalDir == null || externalDir.isBlank()) {
            return 0;
        }
        return doScanExternalDirectory(externalDir, result);
    }

    /**
     * 外部游戏目录热扫描（类似插件目录热加载）：drop-in yml 周期入库，内容无变化不写库
     */
    @Scheduled(fixedDelayString = "${game-platform.metadata.scan-interval:300}", timeUnit = TimeUnit.SECONDS)
    public void hotReloadExternalGames() {
        if (!Boolean.TRUE.equals(hotReload) || externalDir == null || externalDir.isBlank()) {
            return;
        }
        ScanResult result = scanExternalDirectory(externalDir);
        if (result.getSuccessCount() + result.getUpdateCount() > 0) {
            log.info("外部游戏目录热扫描: 总计={}, 新增={}, 更新={}, 跳过(无变化)={}, 失败={}",
                    result.getTotalFiles(), result.getSuccessCount(), result.getUpdateCount(),
                    result.getSkippedCount(), result.getErrorCount());
        }
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
        result.setTotalFiles(doScanExternalDirectory(externalPath, result));
        return result;
    }

    /**
     * @return 发现的配置文件数
     */
    private int doScanExternalDirectory(String externalPath, ScanResult result) {
        Path path = Paths.get(externalPath);
        if (!Files.exists(path) || !Files.isDirectory(path)) {
            result.addError("目录不存在: " + externalPath);
            return 0;
        }

        List<File> yamlFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path, "*.{yml,yaml}")) {
            for (Path entry : stream) {
                yamlFiles.add(entry.toFile());
            }
        } catch (IOException e) {
            result.addError("读取目录失败: " + externalPath + ": " + e.getMessage());
            return 0;
        }

        for (File file : yamlFiles) {
            try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                processYamlSource(file.getName(), reader, result);
            } catch (Exception e) {
                String errorMsg = String.format("处理文件 %s 失败: %s", file.getName(), e.getMessage());
                log.error(errorMsg, e);
                result.addError(errorMsg);
            }
        }
        return yamlFiles.size();
    }

    /**
     * 解析并入库单个 YAML 配置源（classpath 资源与外部文件共用）
     */
    private void processYamlSource(String filename, Reader reader, ScanResult result) throws JsonProcessingException {
        log.info("正在处理游戏配置文件: {}", filename);

        GameYamlConfig config = parseYaml(reader);

        // 验证配置
        if (!config.isValid()) {
            String error = String.format("文件 %s 验证失败: %s", filename, config.getValidationError());
            result.addError(error);
            return;
        }

        SaveOutcome outcome = saveOrUpdateGameMetadata(config);
        switch (outcome) {
            case CREATED -> {
                result.addSuccess(config.getGame().getCode());
                log.info("新增游戏元数据: {} ({})", config.getGame().getName(), config.getGame().getCode());
            }
            case UPDATED -> {
                result.addUpdate(config.getGame().getCode());
                log.info("更新游戏元数据: {} ({})", config.getGame().getName(), config.getGame().getCode());
            }
            case UNCHANGED -> result.addSkipped(config.getGame().getCode());
        }
    }

    /** 解析 YAML（fat jar 内以流方式读取，不依赖 File） */
    private GameYamlConfig parseYaml(Reader reader) {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setCodePointLimit(10 * 1024 * 1024); // 10MB限制

        Constructor constructor = new Constructor(GameYamlConfig.class, loaderOptions);
        Yaml yaml = new Yaml(constructor);
        return yaml.load(reader);
    }

    /** 单个游戏配置的入库结果 */
    private enum SaveOutcome {
        /** 新增 */
        CREATED,
        /** 内容有变化，已更新 */
        UPDATED,
        /** 内容与库中一致，跳过 */
        UNCHANGED
    }

    /**
     * 保存或更新游戏元数据
     *
     * @param config YAML配置
     * @return 入库结果
     */
    private SaveOutcome saveOrUpdateGameMetadata(GameYamlConfig config) throws JsonProcessingException {
        GameYamlConfig.GameInfo gameInfo = config.getGame();
        String gameCode = gameInfo.getCode();

        // 查询是否已存在
        GameMetadata existMetadata = gameMetadataMapper.selectByGameCode(gameCode);

        // game_code 有跨逻辑删除的物理 UNIQUE 约束，逻辑删除残留会挡住 INSERT。
        // 注意不能依赖"查出残留行再判断 is_deleted"——auto-mapping 把 is_deleted 映射到
        // isDeleted 而非 deleted 属性，实体字段恒为 null；直接用 DELETE 语句按条件清理。
        if (existMetadata == null) {
            int purged = gameMetadataMapper.physicalDeleteDeletedByGameCode(gameCode);
            if (purged > 0) {
                log.warn("检测到逻辑删除残留游戏元数据，物理清除以释放 game_code: code={}, rows={}",
                        gameCode, purged);
            }
        }

        GameMetadata metadata = new GameMetadata();
        metadata.setGameCode(gameCode);
        metadata.setGameName(gameInfo.getName());
        metadata.setDescription(gameInfo.getDescription());
        metadata.setIconUrl(gameInfo.getIcon());

        // 支持的部署类型（GameInfo 默认空列表，归一为 null，避免热扫描时空集合与 NULL 反复互刷）
        List<String> deployTypes = gameInfo.getDeployTypes();
        metadata.setSupportedDeployTypes(
                deployTypes == null || deployTypes.isEmpty() ? null : deployTypes);

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
            if (contentEquals(existMetadata, mergedWithExisting(existMetadata, metadata))) {
                return SaveOutcome.UNCHANGED;
            }
            // 更新
            metadata.setId(existMetadata.getId());
            gameMetadataMapper.updateById(metadata);
            return SaveOutcome.UPDATED;
        } else {
            // 新增
            gameMetadataMapper.insert(metadata);
            return SaveOutcome.CREATED;
        }
    }

    /**
     * 逐字段比较游戏元数据内容
     */
    private boolean contentEquals(GameMetadata existing, GameMetadata incoming) {
        return Objects.equals(existing.getGameName(), incoming.getGameName())
                && Objects.equals(existing.getDescription(), incoming.getDescription())
                && Objects.equals(existing.getIconUrl(), incoming.getIconUrl())
                && Objects.equals(existing.getDefaultPort(), incoming.getDefaultPort())
                && Objects.equals(existing.getSupportedDeployTypes(), incoming.getSupportedDeployTypes())
                && Objects.equals(existing.getEnvironmentDeps(), incoming.getEnvironmentDeps())
                && Objects.equals(existing.getDeployConfig(), incoming.getDeployConfig())
                && Objects.equals(existing.getCustomOperations(), incoming.getCustomOperations());
    }

    /**
     * incoming 的 null 字段按 updateById 实际落库状态回填 existing 旧值后再比较
     * （MyBatis-Plus 默认策略 null 字段不落库，否则 yml 缺省键会导致每轮空转 UPDATE）
     */
    private GameMetadata mergedWithExisting(GameMetadata existing, GameMetadata incoming) {
        GameMetadata effective = new GameMetadata();
        effective.setGameName(incoming.getGameName() != null ? incoming.getGameName() : existing.getGameName());
        effective.setDescription(incoming.getDescription() != null ? incoming.getDescription() : existing.getDescription());
        effective.setIconUrl(incoming.getIconUrl() != null ? incoming.getIconUrl() : existing.getIconUrl());
        effective.setDefaultPort(incoming.getDefaultPort() != null ? incoming.getDefaultPort() : existing.getDefaultPort());
        effective.setSupportedDeployTypes(incoming.getSupportedDeployTypes() != null
                ? incoming.getSupportedDeployTypes() : existing.getSupportedDeployTypes());
        effective.setEnvironmentDeps(incoming.getEnvironmentDeps() != null
                ? incoming.getEnvironmentDeps() : existing.getEnvironmentDeps());
        effective.setDeployConfig(incoming.getDeployConfig() != null
                ? incoming.getDeployConfig() : existing.getDeployConfig());
        effective.setCustomOperations(incoming.getCustomOperations() != null
                ? incoming.getCustomOperations() : existing.getCustomOperations());
        return effective;
    }

    /**
     * 构建部署配置
     */
    private Map<String, Object> buildDeployConfig(GameYamlConfig config) {
        Map<String, Object> deployConfig = new HashMap<>();
        GameYamlConfig.GameInfo gameInfo = config.getGame();

        // 基础配置（GameInfo 的 defaultPorts 默认空 map，空值不写入，保证内容比对稳定）
        deployConfig.put("version", gameInfo.getVersion());
        Map<String, Integer> ports = gameInfo.getDefaultPorts();
        if (ports != null && !ports.isEmpty()) {
            deployConfig.put("defaultPorts", ports);
        }

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

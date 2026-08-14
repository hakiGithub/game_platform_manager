package com.gameplatform.adapter;

import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.util.SshUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.yaml.snakeyaml.Yaml;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Docker Compose适配器测试类
 * 使用 Mockito 进行单元测试
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
class DockerComposeAdapterTest {

    @Mock
    private SshUtil sshUtil;

    @Mock
    private HostMapper hostMapper;

    @Mock
    private GameInstanceMapper instanceMapper;

    @Mock
    private DeploymentAccess deployAccess;

    private DockerComposeAdapter dockerComposeAdapter;

    @BeforeEach
    void setUp() {
        dockerComposeAdapter = new DockerComposeAdapter();
        // 手动注入依赖
        injectField(dockerComposeAdapter, "sshUtil", sshUtil);
        injectField(dockerComposeAdapter, "hostMapper", hostMapper);
        injectField(dockerComposeAdapter, "instanceMapper", instanceMapper);
        injectField(dockerComposeAdapter, "deployAccess", deployAccess);
    }

    /**
     * 通过反射注入字段
     */
    private void injectField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getSuperclass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject field: " + fieldName, e);
        }
    }

    @Test
    void testGetDeployType() {
        assertEquals(DeployAdapter.DeployType.DOCKER_COMPOSE, dockerComposeAdapter.getDeployType());
    }

    @Test
    void deploy_upCommandSetsComposeHttpTimeout() {
        // 回归：docker-compose 对 daemon 的内部 60s HTTP 读超时
        // 在慢 create（大镜像/冷缓存）时会中断 up -d，须注入 COMPOSE_HTTP_TIMEOUT
        com.gameplatform.entity.GameInstance instance = new com.gameplatform.entity.GameInstance();
        instance.setId(1L);
        instance.setHostId(1L);
        instance.setDeployType("docker-compose");
        when(instanceMapper.selectById(1L)).thenReturn(instance);

        com.gameplatform.entity.Host host = new com.gameplatform.entity.Host();
        host.setId(1L);
        host.setIpAddress("192.168.1.10");
        host.setSshPort(22);
        host.setSshUser("u");
        when(hostMapper.selectById(1L)).thenReturn(host);

        when(deployAccess.credentials(host)).thenReturn(
                new com.gameplatform.deploy.HostCredentials("192.168.1.10", 22, "u", null, null));

        Map<String, Object> config = new HashMap<>();
        config.put("composeTemplate", "services:\n  l4d2:\n    image: example/l4d2:latest\n");
        config.put("workDir", "/home/u/games/l4d2");

        SshUtil.CommandResult ok = new SshUtil.CommandResult();
        ok.setSuccess(true);
        ok.setOutput("0e42358fc43b");
        ok.setExitCode(0);
        when(sshUtil.executeCommand(anyString(), anyInt(), anyString(),
                isNull(), isNull(), anyString(), anyLong())).thenReturn(ok);
        lenient().when(sshUtil.uploadFile(anyString(), anyInt(), anyString(),
                isNull(), isNull(), anyString(), anyString())).thenReturn(true);

        dockerComposeAdapter.deploy(1L, config, DeployProgressCallback.NO_OP);

        verify(sshUtil, atLeastOnce()).executeCommand(anyString(), anyInt(), anyString(),
                isNull(), isNull(), contains("COMPOSE_HTTP_TIMEOUT=300"), anyLong());
    }

    @Test
    void testValidateEnvironmentWithInvalidHost() {
        // Mock: 主机不存在
        when(hostMapper.selectById(-1L)).thenReturn(null);

        // 测试无效的主机ID
        Map<String, Object> config = new HashMap<>();
        config.put("projectName", "test-project");

        boolean result = dockerComposeAdapter.validateEnvironment(-1L, config);
        assertFalse(result);

        // 验证 hostMapper 被调用
        verify(hostMapper, times(1)).selectById(-1L);
    }

    @Test
    void testValidateComposeContent() {
        // 测试有效的Compose内容
        String validCompose = """
                version: '3.8'
                services:
                  web:
                    image: nginx:latest
                    ports:
                      - "80:80"
                  db:
                    image: mysql:8.0
                    environment:
                      MYSQL_ROOT_PASSWORD: password
                """;

        assertTrue(dockerComposeAdapter.validateComposeContent(validCompose));

        // 测试无效的Compose内容
        String invalidCompose = """
                invalid yaml content
                """;

        assertFalse(dockerComposeAdapter.validateComposeContent(invalidCompose));

        // 测试缺少services的Compose内容
        String noServicesCompose = """
                version: '3.8'
                networks:
                  default:
                """;

        assertFalse(dockerComposeAdapter.validateComposeContent(noServicesCompose));
    }

    @Test
    void testGenerateComposeFile() {
        // 测试生成Compose文件
        Map<String, Object> config = new HashMap<>();

        // 服务配置
        List<Map<String, Object>> services = new ArrayList<>();

        Map<String, Object> webService = new HashMap<>();
        webService.put("name", "web");
        webService.put("image", "nginx:latest");
        webService.put("containerName", "web-server");
        webService.put("restart", "unless-stopped");

        // 端口
        List<Map<String, Object>> ports = new ArrayList<>();
        Map<String, Object> port1 = new HashMap<>();
        port1.put("hostPort", 80);
        port1.put("containerPort", 80);
        port1.put("protocol", "tcp");
        ports.add(port1);
        webService.put("ports", ports);

        // 卷
        List<Map<String, Object>> volumes = new ArrayList<>();
        Map<String, Object> volume1 = new HashMap<>();
        volume1.put("hostPath", "/data/nginx");
        volume1.put("containerPath", "/usr/share/nginx/html");
        volume1.put("mode", "ro");
        volumes.add(volume1);
        webService.put("volumes", volumes);

        // 环境变量
        Map<String, Object> env = new HashMap<>();
        env.put("NGINX_HOST", "localhost");
        env.put("NGINX_PORT", "80");
        webService.put("environment", env);

        services.add(webService);
        config.put("services", services);

        // 生成Compose文件
        String composeContent = dockerComposeAdapter.generateComposeFile(config);
        assertNotNull(composeContent);
        assertTrue(composeContent.contains("version"));
        assertTrue(composeContent.contains("services"));
        assertTrue(composeContent.contains("web"));

        // 验证生成的YAML
        Yaml yaml = new Yaml();
        Map<String, Object> parsed = yaml.load(composeContent);
        assertNotNull(parsed);
        assertTrue(parsed.containsKey("services"));
    }

    @Test
    void testComposeConfigStructure() {
        // 测试Compose配置结构
        Map<String, Object> config = new HashMap<>();

        // 项目配置
        config.put("projectName", "game-stack");
        config.put("workDir", "/opt/games");

        // 服务配置
        List<Map<String, Object>> services = new ArrayList<>();

        Map<String, Object> gameService = new HashMap<>();
        gameService.put("name", "minecraft");
        gameService.put("image", "itzg/minecraft-server:latest");
        gameService.put("containerName", "minecraft-server");
        gameService.put("restart", "unless-stopped");

        // 端口映射
        List<Map<String, Object>> ports = new ArrayList<>();
        Map<String, Object> gamePort = new HashMap<>();
        gamePort.put("hostPort", 25565);
        gamePort.put("containerPort", 25565);
        gamePort.put("protocol", "tcp");
        ports.add(gamePort);
        gameService.put("ports", ports);

        // 卷挂载
        List<Map<String, Object>> volumes = new ArrayList<>();
        Map<String, Object> dataVolume = new HashMap<>();
        dataVolume.put("hostPath", "/data/minecraft");
        dataVolume.put("containerPath", "/data");
        dataVolume.put("mode", "rw");
        volumes.add(dataVolume);
        gameService.put("volumes", volumes);

        // 环境变量
        Map<String, Object> environment = new HashMap<>();
        environment.put("EULA", "TRUE");
        environment.put("MEMORY", "2G");
        gameService.put("environment", environment);

        // 依赖
        List<String> dependsOn = Arrays.asList("db");
        gameService.put("dependsOn", dependsOn);

        services.add(gameService);
        config.put("services", services);

        assertNotNull(config.get("projectName"));
        assertNotNull(config.get("services"));
        assertEquals(1, ((List<?>) config.get("services")).size());
    }

    @Test
    void testDeployTypeEnum() {
        // 测试部署类型枚举
        assertEquals("docker-compose", DeployAdapter.DeployType.DOCKER_COMPOSE.getCode());
        assertEquals("Docker Compose部署", DeployAdapter.DeployType.DOCKER_COMPOSE.getDescription());

        // 测试从代码获取枚举
        DeployAdapter.DeployType type = DeployAdapter.DeployType.fromCode("docker-compose");
        assertEquals(DeployAdapter.DeployType.DOCKER_COMPOSE, type);
    }
}

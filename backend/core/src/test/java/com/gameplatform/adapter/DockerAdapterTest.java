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

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Docker适配器测试类
 * 使用 Mockito 进行单元测试
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
class DockerAdapterTest {

    @Mock
    private SshUtil sshUtil;

    @Mock
    private HostMapper hostMapper;

    @Mock
    private GameInstanceMapper instanceMapper;

    @Mock
    private DeploymentAccess deployAccess;

    private DockerAdapter dockerAdapter;

    @BeforeEach
    void setUp() {
        dockerAdapter = new DockerAdapter();
        // 手动注入依赖
        injectField(dockerAdapter, "sshUtil", sshUtil);
        injectField(dockerAdapter, "hostMapper", hostMapper);
        injectField(dockerAdapter, "instanceMapper", instanceMapper);
        injectField(dockerAdapter, "deployAccess", deployAccess);
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
        assertEquals(DeployAdapter.DeployType.DOCKER, dockerAdapter.getDeployType());
    }

    @Test
    void testValidateEnvironmentWithInvalidHost() {
        // Mock: 主机不存在
        when(hostMapper.selectById(-1L)).thenReturn(null);

        // 测试无效的主机ID
        Map<String, Object> config = new HashMap<>();
        config.put("image", "nginx:latest");
        config.put("containerName", "test-nginx");

        boolean result = dockerAdapter.validateEnvironment(-1L, config);
        assertFalse(result);

        // 验证 hostMapper 被调用
        verify(hostMapper, times(1)).selectById(-1L);
    }

    @Test
    void testDockerConfigStructure() {
        // 测试Docker配置结构
        Map<String, Object> config = new HashMap<>();

        // 基本配置
        config.put("image", "itzg/minecraft-server:latest");
        config.put("containerName", "minecraft-server");
        config.put("restartPolicy", "unless-stopped");

        // 端口映射
        List<Map<String, Object>> ports = new ArrayList<>();
        Map<String, Object> port1 = new HashMap<>();
        port1.put("hostPort", 25565);
        port1.put("containerPort", 25565);
        port1.put("protocol", "tcp");
        ports.add(port1);
        config.put("ports", ports);

        // 卷挂载
        List<Map<String, Object>> volumes = new ArrayList<>();
        Map<String, Object> volume1 = new HashMap<>();
        volume1.put("hostPath", "/data/minecraft");
        volume1.put("containerPath", "/data");
        volume1.put("mode", "rw");
        volumes.add(volume1);
        config.put("volumes", volumes);

        // 环境变量
        Map<String, Object> environment = new HashMap<>();
        environment.put("EULA", "TRUE");
        environment.put("MEMORY", "2G");
        config.put("environment", environment);

        // 资源限制
        config.put("memoryLimit", "3G");
        config.put("cpuLimit", "2");

        assertNotNull(config.get("image"));
        assertNotNull(config.get("ports"));
        assertNotNull(config.get("volumes"));
        assertNotNull(config.get("environment"));
    }

    @Test
    void testDeployTypeEnum() {
        // 测试部署类型枚举
        assertEquals("docker", DeployAdapter.DeployType.DOCKER.getCode());
        assertEquals("Docker部署", DeployAdapter.DeployType.DOCKER.getDescription());

        // 测试从代码获取枚举
        DeployAdapter.DeployType type = DeployAdapter.DeployType.fromCode("docker");
        assertEquals(DeployAdapter.DeployType.DOCKER, type);
    }
}

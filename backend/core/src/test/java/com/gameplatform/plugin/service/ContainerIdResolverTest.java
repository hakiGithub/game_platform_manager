package com.gameplatform.plugin.service;

import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.deploy.HostCredentials;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.util.SshUtil;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link ContainerIdResolver} 单元测试。
 *
 * <p>回归场景：docker-compose 实例显式 container_name（如 l4d2），
 * 项目名前缀动态查询（game{id}_）无匹配 → 必须回退容器名，
 * 否则控制台/文件管理在宿主机重启后（同步写回前）全部失败。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContainerIdResolverTest {

    @Mock private SshUtil sshUtil;
    @Mock private HostMapper hostMapper;

    private ContainerIdResolver resolver;

    private InstanceVO composeInstance;

    @BeforeEach
    void setUp() {
        // 凭据解析走真实 DeploymentAccess（host 表 mock 返回测试主机）
        DeploymentAccess deployAccess = new DeploymentAccess(hostMapper);
        resolver = new ContainerIdResolver(deployAccess, sshUtil);

        Host host = new Host();
        host.setId(10L);
        host.setIpAddress("192.168.1.100");
        host.setSshPort(22);
        host.setSshUser("gameserver");
        when(hostMapper.selectById(10L)).thenReturn(host);

        composeInstance = new InstanceVO();
        composeInstance.setId(56L);
        composeInstance.setHostId(10L);
        composeInstance.setDeployType("docker-compose");
        // 与实例 56 一致：CONTAINER_NAME 是 compose 显式容器名（模板变量），
        // 镜像走 IMAGE_REPO/IMAGE_TAG（旧模板）；容器名带 game{id}_ 前缀的假设在此不成立
        composeInstance.setConfigInfo(Map.of(
                "CONTAINER_NAME", "l4d2",
                "IMAGE_REPO", "gameservermanagers/gameserver",
                "IMAGE_TAG", "l4d2"
        ));
    }

    @Test
    @DisplayName("compose 前缀查询无匹配时回退容器名（显式 container_name=l4d2）")
    void resolve_composePrefixQueryEmpty_fallsBackToContainerName() {
        SshUtil.CommandResult empty = mock(SshUtil.CommandResult.class);
        when(empty.getExitCode()).thenReturn(0);
        when(empty.getOutput()).thenReturn("");
        when(sshUtil.executeCommand(anyString(), anyInt(), anyString(),
                any(), any(), contains("name=game56_")))
            .thenReturn(empty);

        String result = resolver.resolve(composeInstance, composeInstance.getConfigInfo());

        assertEquals("l4d2", result, "前缀查询空时应回退显式容器名，而不是抛异常");
    }

    @Test
    @DisplayName("compose 前缀查询命中时返回动态查询结果")
    void resolve_composePrefixQueryHit_returnsDynamicId() {
        SshUtil.CommandResult hit = mock(SshUtil.CommandResult.class);
        when(hit.getExitCode()).thenReturn(0);
        when(hit.getOutput()).thenReturn("9f3c21be77d5e4a8b02c6d91e4fa8b31");
        when(sshUtil.executeCommand(anyString(), anyInt(), anyString(),
                any(), any(), contains("name=game56_")))
            .thenReturn(hit);

        String result = resolver.resolve(composeInstance, composeInstance.getConfigInfo());

        assertEquals("9f3c21be77d5e4a8b02c6d91e4fa8b31", result);
    }

    @Test
    @DisplayName("compose 无容器名且查询失败时抛 IllegalStateException")
    void resolve_composeNoNameAndQueryFails_throws() {
        SshUtil.CommandResult fail = mock(SshUtil.CommandResult.class);
        when(fail.getExitCode()).thenReturn(1);
        when(sshUtil.executeCommand(anyString(), anyInt(), anyString(),
                any(), any(), anyString()))
            .thenReturn(fail);
        InstanceVO noName = new InstanceVO();
        noName.setId(57L);
        noName.setHostId(10L);
        noName.setDeployType("docker-compose");
        noName.setConfigInfo(Map.of());

        assertThrows(IllegalStateException.class, () ->
            resolver.resolve(noName, noName.getConfigInfo()));
    }

    @Test
    @DisplayName("docker 类型：containerId 优先，缺失时回退默认容器名 game-instance-{id}")
    void resolve_docker_noContainerId_fallsBackToDefaultName() {
        InstanceVO dockerInstance = new InstanceVO();
        dockerInstance.setId(7L);
        dockerInstance.setHostId(10L);
        dockerInstance.setDeployType("docker");
        dockerInstance.setConfigInfo(Map.of());

        String result = resolver.resolve(dockerInstance, dockerInstance.getConfigInfo());

        assertEquals("game-instance-7", result, "docker 类型容器名兜底应使用 DockerAdapter 默认命名");
    }
}

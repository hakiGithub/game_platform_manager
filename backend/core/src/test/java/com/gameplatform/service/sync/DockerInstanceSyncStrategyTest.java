package com.gameplatform.service.sync;

import com.gameplatform.adapter.DeployAdapter.InstanceStatus;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.service.docker.DockerContainerLinkService;
import com.gameplatform.service.docker.dto.ContainerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * DockerInstanceSyncStrategy 单元测试
 * 覆盖三级匹配（容器ID → 容器名 → 多字段严格匹配）和状态对账规则
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
class DockerInstanceSyncStrategyTest {

    @Mock
    private DockerContainerLinkService dockerContainerLinkService;

    @Mock
    private GameInstanceMapper instanceMapper;

    @InjectMocks
    private DockerInstanceSyncStrategy strategy;

    private Host host;
    private GameInstance instance;

    @BeforeEach
    void setUp() {
        host = new Host();
        host.setId(1L);
        host.setHostName("test-host");
        host.setIpAddress("192.168.1.100");

        instance = new GameInstance();
        instance.setId(100L);
        instance.setInstanceName("l4d2-server");
        instance.setGameCode("l4d2");
        instance.setHostId(1L);
        instance.setDeployType("docker-compose");
        instance.setRunStatus(1);
        Map<String, Object> runtimeMeta = new HashMap<>();
        runtimeMeta.put("containerId", "abc123def456");
        runtimeMeta.put("containerName", "l4d2-server-container");
        instance.setRuntimeMetadata(runtimeMeta);
        Map<String, Object> portConfig = new HashMap<>();
        portConfig.put("game", 27015);
        instance.setPortConfig(portConfig);
    }

    // ===== 容器ID 精确匹配 =====

    @Test
    void matchByContainerId_fromRuntimeMetadata_statusUnchanged_noUpdate() {
        // 容器ID 来自 runtime_metadata.containerId
        ContainerInfo container = new ContainerInfo("abc123def456", "l4d2-server-container", "l4d2-pure", "latest");
        when(dockerContainerLinkService.getContainers(any())).thenReturn(List.of(container));
        when(dockerContainerLinkService.getContainerStatus(any(), eq("abc123def456")))
                .thenReturn("running");

        // 状态 1 + 容器运行中 = 不更新
        strategy.syncHost(host, List.of(instance));

        verify(instanceMapper, never()).updateById(any());
    }

    @Test
    void matchByContainerId_fromInstallPath_statusChanged() {
        // 容器ID 来自 install_path（DockerAdapter 写入，必须是合法 12/64 位十六进制）
        instance.getRuntimeMetadata().remove("containerId");
        instance.setInstallPath("abcdef123456");

        ContainerInfo container = new ContainerInfo("abcdef123456", "any-name", "any-image", "latest");
        when(dockerContainerLinkService.getContainers(any())).thenReturn(List.of(container));
        when(dockerContainerLinkService.getContainerStatus(any(), eq("abcdef123456")))
                .thenReturn("running");

        instance.setRunStatus(0); // 平台认为已停止，但容器实际运行中
        strategy.syncHost(host, List.of(instance));

        assertThat(instance.getRunStatus()).isEqualTo(1);
        // 状态更新 + 容器 ID 回写 runtime_metadata 各一次
        verify(instanceMapper, times(2)).updateById(any());
        assertThat(instance.getRuntimeMetadata()).containsEntry("containerId", "abcdef123456");
    }

    // ===== 容器名精确匹配 =====

    @Test
    void matchByContainerName_whenContainerIdNotMatch_success() {
        // runtime_metadata.containerId 与实际容器 ID 不一致，回退到容器名匹配
        instance.getRuntimeMetadata().put("containerId", "wrong-id");
        instance.setInstallPath(null);

        ContainerInfo container = new ContainerInfo("real-id", "l4d2-server-container", "l4d2-pure", "latest");
        when(dockerContainerLinkService.getContainers(any())).thenReturn(List.of(container));
        when(dockerContainerLinkService.getContainerStatus(any(), eq("real-id")))
                .thenReturn("running");

        instance.setRunStatus(0);
        strategy.syncHost(host, List.of(instance));

        assertThat(instance.getRunStatus()).isEqualTo(1);
    }

    // ===== 多字段严格匹配 =====

    @Test
    void matchByMultipleFields_allMatch_success() {
        // 清除容器ID 和容器名，强制走多字段匹配
        instance.getRuntimeMetadata().clear();
        instance.setInstallPath(null);
        instance.setInstanceName("l4d2-server");
        Map<String, Object> configInfo = new HashMap<>();
        configInfo.put("image", "l4d2-pure");
        instance.setConfigInfo(configInfo);

        ContainerInfo container = new ContainerInfo("any-id", "my-l4d2-server-001", "l4d2-pure", "latest");
        when(dockerContainerLinkService.getContainers(any())).thenReturn(List.of(container));
        // 多字段匹配时无法确定容器ID，策略走默认路径返回 "running"

        instance.setRunStatus(0);
        strategy.syncHost(host, List.of(instance));

        assertThat(instance.getRunStatus()).isEqualTo(1);
    }

    @Test
    void matchByMultipleFields_imageMismatch_notMatched() {
        instance.getRuntimeMetadata().clear();
        instance.setInstallPath(null);
        Map<String, Object> configInfo = new HashMap<>();
        configInfo.put("image", "l4d2-pure");
        instance.setConfigInfo(configInfo);

        // 镜像不一致，名称匹配也不应成立
        ContainerInfo container = new ContainerInfo("any-id", "my-l4d2-server-001", "wrong-image", "latest");
        when(dockerContainerLinkService.getContainers(any())).thenReturn(List.of(container));

        instance.setRunStatus(1);
        strategy.syncHost(host, List.of(instance));

        // 找不到匹配容器 → 置 0 + remark
        assertThat(instance.getRunStatus()).isEqualTo(0);
        assertThat(instance.getRemark()).contains("容器不存在");
    }

    @Test
    void matchByMultipleFields_nameKeywordMismatch_notMatched() {
        instance.getRuntimeMetadata().clear();
        instance.setInstallPath(null);
        Map<String, Object> configInfo = new HashMap<>();
        configInfo.put("image", "l4d2-pure");
        instance.setConfigInfo(configInfo);

        // 镜像匹配但容器名不含 l4d2 关键字
        ContainerInfo container = new ContainerInfo("any-id", "my-minecraft-server", "l4d2-pure", "latest");
        when(dockerContainerLinkService.getContainers(any())).thenReturn(List.of(container));

        instance.setRunStatus(1);
        strategy.syncHost(host, List.of(instance));

        assertThat(instance.getRunStatus()).isEqualTo(0);
    }

    // ===== 状态对账 =====

    @Test
    void reconcile_containerRunning_statusUnchanged_noUpdate() {
        ContainerInfo container = new ContainerInfo("abc123def456", "l4d2-server-container", "any", "latest");
        when(dockerContainerLinkService.getContainers(any())).thenReturn(List.of(container));
        when(dockerContainerLinkService.getContainerStatus(any(), eq("abc123def456")))
                .thenReturn("running");

        instance.setRunStatus(1); // 已运行
        strategy.syncHost(host, List.of(instance));

        verify(instanceMapper, never()).updateById(any());
    }

    @Test
    void reconcile_containerExited_statusChangedToStopped() {
        ContainerInfo container = new ContainerInfo("abc123def456", "l4d2-server-container", "any", "latest");
        when(dockerContainerLinkService.getContainers(any())).thenReturn(List.of(container));
        when(dockerContainerLinkService.getContainerStatus(any(), eq("abc123def456")))
                .thenReturn("exited");

        instance.setRunStatus(1);
        strategy.syncHost(host, List.of(instance));

        assertThat(instance.getRunStatus()).isEqualTo(0);
        assertThat(instance.getRemark()).contains("容器已退出");
    }

    @Test
    void reconcile_containerNotExists_statusChangedToStopped() {
        when(dockerContainerLinkService.getContainers(any())).thenReturn(List.of());

        instance.setRunStatus(1);
        strategy.syncHost(host, List.of(instance));

        assertThat(instance.getRunStatus()).isEqualTo(0);
        assertThat(instance.getRemark()).contains("容器不存在");
    }

    @Test
    void reconcile_deployingStatus_withRunningContainer_updatesToRunning() {
        ContainerInfo container = new ContainerInfo("abc123def456", "l4d2-server-container", "any", "latest");
        when(dockerContainerLinkService.getContainers(any())).thenReturn(List.of(container));
        when(dockerContainerLinkService.getContainerStatus(any(), eq("abc123def456")))
                .thenReturn("running");

        instance.setRunStatus(5); // 部署中（INSTALLING）
        strategy.syncHost(host, List.of(instance));

        assertThat(instance.getRunStatus()).isEqualTo(1);
    }

    // ===== 异常隔离 =====

    @Test
    void unknownContainer_notProcessed_notRecorded() {
        // 主机有未知容器（不匹配任何实例），实例列表为空时策略直接返回
        // 这里验证策略不会触发任何新增/更新操作
        strategy.syncHost(host, List.of());

        verifyNoInteractions(dockerContainerLinkService);
        verify(instanceMapper, never()).insert(any());
        verify(instanceMapper, never()).updateById(any());
    }

    @Test
    void sshException_skipsInstance_noUpdate() {
        when(dockerContainerLinkService.getContainers(any()))
                .thenThrow(new RuntimeException("SSH connection failed"));

        strategy.syncHost(host, List.of(instance));

        verify(instanceMapper, never()).updateById(any());
    }

    @Test
    void singleInstanceException_doesNotAffectOthers() {
        // badInstance 的 runtimeMetadata 为 null，会触发 NPE，但不应影响 goodInstance
        GameInstance badInstance = new GameInstance();
        badInstance.setId(99L);
        badInstance.setDeployType("docker");
        badInstance.setRunStatus(1);
        badInstance.setRuntimeMetadata(null);

        GameInstance goodInstance = new GameInstance();
        goodInstance.setId(100L);
        goodInstance.setDeployType("docker");
        goodInstance.setRunStatus(0);
        Map<String, Object> goodMeta = new HashMap<>();
        goodMeta.put("containerId", "good-id");
        goodInstance.setRuntimeMetadata(goodMeta);

        ContainerInfo goodContainer = new ContainerInfo("good-id", "good-name", "any", "latest");
        when(dockerContainerLinkService.getContainers(any())).thenReturn(List.of(goodContainer));
        when(dockerContainerLinkService.getContainerStatus(any(), eq("good-id")))
                .thenReturn("running");
        when(instanceMapper.updateById(any())).thenReturn(1);

        strategy.syncHost(host, List.of(badInstance, goodInstance));

        assertThat(goodInstance.getRunStatus()).isEqualTo(1);
    }

    // ===== 空列表处理 =====

    @Test
    void emptyInstanceList_doesNothing() {
        strategy.syncHost(host, List.of());

        verifyNoInteractions(dockerContainerLinkService);
        verify(instanceMapper, never()).updateById(any());
    }

    @Test
    void nullInstanceList_doesNothing() {
        strategy.syncHost(host, null);

        verifyNoInteractions(dockerContainerLinkService);
    }
}

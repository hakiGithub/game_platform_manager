package com.gameplatform.service.docker;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.dto.docker.ContainerAdoptDTO;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.GameMetadata;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.mapper.GameMetadataMapper;
import com.gameplatform.plugin.listener.PluginLifecycleHook;
import com.gameplatform.service.docker.impl.ContainerAdoptionServiceImpl;
import com.gameplatform.vo.docker.ContainerDetailVO;
import com.gameplatform.vo.docker.ContainerListVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContainerAdoptionServiceImpl 单元测试（ADR-0023）
 * 覆盖 deployType 探测、compose labels 预填、跳过部署的初始状态与 adopted 标记
 */
@ExtendWith(MockitoExtension.class)
class ContainerAdoptionServiceTest {

    @Mock
    private DockerContainerService containerService;

    @Mock
    private GameInstanceMapper instanceMapper;

    @Mock
    private GameMetadataMapper gameMetadataMapper;

    @Mock
    private PluginLifecycleHook pluginLifecycleHook;

    @InjectMocks
    private ContainerAdoptionServiceImpl service;

    private ContainerAdoptDTO dto;
    private ContainerDetailVO runningDetail;

    @BeforeEach
    void setUp() {
        dto = new ContainerAdoptDTO();
        dto.setInstanceName("l4d2");
        dto.setGameId(5L);

        runningDetail = new ContainerDetailVO();
        runningDetail.setContainerId("27e77bb5760aFULL");
        runningDetail.setContainerName("l4d2");
        runningDetail.setStatus("running");

        // 模拟 MyBatis-Plus insert 回填主键（部分用例在 insert 前即抛错，故 lenient）
        lenient().doAnswer(inv -> {
            GameInstance gi = inv.getArgument(0);
            gi.setId(100L);
            return 1;
        }).when(instanceMapper).insert(any(GameInstance.class));
    }

    private GameMetadata game(List<String> deployTypes) {
        GameMetadata g = new GameMetadata();
        g.setId(5L);
        g.setGameCode("l4d2");
        g.setGameName("L4D2");
        g.setSupportedDeployTypes(deployTypes);
        g.setDefaultPort(27015);
        return g;
    }

    @Test
    void adoptSupportsDockerPriorityAndWritesAdoptedMetadata() {
        when(containerService.getContainerDetail(2L, "abc")).thenReturn(runningDetail);
        when(gameMetadataMapper.selectById(5L)).thenReturn(game(List.of("docker", "docker-compose")));
        when(instanceMapper.selectByHostIdAndInstanceName(2L, "l4d2")).thenReturn(null);

        Long id = service.adoptContainer(2L, "abc", dto);

        ArgumentCaptor<GameInstance> captor = ArgumentCaptor.forClass(GameInstance.class);
        verify(instanceMapper).insert(captor.capture());
        GameInstance inserted = captor.getValue();

        assertThat(id).isEqualTo(inserted.getId());
        assertThat(inserted.getDeployType()).isEqualTo("docker");
        assertThat(inserted.getRunStatus()).isEqualTo(1); // RUNNING：容器 running
        assertThat(inserted.getGameCode()).isEqualTo("l4d2");
        assertThat(inserted.getRuntimeMetadata())
                .containsEntry("containerId", "27e77bb5760aFULL")
                .containsEntry("containerName", "l4d2")
                .containsEntry(ContainerAdoptionService.ADOPTED_FLAG_KEY, true);
        verify(pluginLifecycleHook).executeInstanceCreateHooks(anyLong(), anyString(), any());
    }

    @Test
    void stoppedContainerAdoptsAsStopped() {
        runningDetail.setStatus("exited");
        when(containerService.getContainerDetail(2L, "abc")).thenReturn(runningDetail);
        when(gameMetadataMapper.selectById(5L)).thenReturn(game(List.of("docker")));
        when(instanceMapper.selectByHostIdAndInstanceName(2L, "l4d2")).thenReturn(null);

        service.adoptContainer(2L, "abc", dto);

        ArgumentCaptor<GameInstance> captor = ArgumentCaptor.forClass(GameInstance.class);
        verify(instanceMapper).insert(captor.capture());
        assertThat(captor.getValue().getRunStatus()).isEqualTo(0); // STOPPED
    }

    @Test
    void composeOnlyGameFallsBackAndPrefillsFromLabels() {
        when(containerService.getContainerDetail(2L, "abc")).thenReturn(runningDetail);
        when(gameMetadataMapper.selectById(5L)).thenReturn(game(List.of("docker-compose")));
        when(instanceMapper.selectByHostIdAndInstanceName(2L, "l4d2")).thenReturn(null);
        runningDetail.setLabels(Map.of(
                "com.docker.compose.project", "game64",
                "com.docker.compose.project.working_dir", "/home/haki/games/l4d2",
                "com.docker.compose.service", "l4d2"));

        service.adoptContainer(2L, "abc", dto);

        ArgumentCaptor<GameInstance> captor = ArgumentCaptor.forClass(GameInstance.class);
        verify(instanceMapper).insert(captor.capture());
        GameInstance inserted = captor.getValue();
        assertThat(inserted.getDeployType()).isEqualTo("docker-compose");
        assertThat(inserted.getRuntimeMetadata())
                .containsEntry("projectName", "game64")
                .containsEntry("workDir", "/home/haki/games/l4d2")
                .containsEntry("serviceName", "l4d2");
    }

    @Test
    void composeOnlyWithoutLabelsAndDtoFails() {
        when(containerService.getContainerDetail(2L, "abc")).thenReturn(runningDetail);
        when(gameMetadataMapper.selectById(5L)).thenReturn(game(List.of("docker-compose")));
        when(instanceMapper.selectByHostIdAndInstanceName(2L, "l4d2")).thenReturn(null);

        assertThatThrownBy(() -> service.adoptContainer(2L, "abc", dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("compose 项目名与工作目录");
        verify(instanceMapper, never()).insert(any(GameInstance.class));
    }

    @Test
    void explicitDeployTypeOutsideGameSupportFails() {
        when(containerService.getContainerDetail(2L, "abc")).thenReturn(runningDetail);
        when(gameMetadataMapper.selectById(5L)).thenReturn(game(List.of("docker-compose")));
        dto.setDeployType("docker");

        assertThatThrownBy(() -> service.adoptContainer(2L, "abc", dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持部署方式");
    }

    @Test
    void gameWithoutDockerDeployTypeFails() {
        when(containerService.getContainerDetail(2L, "abc")).thenReturn(runningDetail);
        when(gameMetadataMapper.selectById(5L)).thenReturn(game(List.of("linuxgsm")));

        assertThatThrownBy(() -> service.adoptContainer(2L, "abc", dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无法认领");
    }

    @Test
    void duplicateInstanceNameFails() {
        when(containerService.getContainerDetail(2L, "abc")).thenReturn(runningDetail);
        when(gameMetadataMapper.selectById(5L)).thenReturn(game(List.of("docker")));
        GameInstance exist = new GameInstance();
        exist.setId(99L);
        exist.setDeleted(0);
        when(instanceMapper.selectByHostIdAndInstanceName(2L, "l4d2")).thenReturn(exist);

        assertThatThrownBy(() -> service.adoptContainer(2L, "abc", dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void portConfigPrefersActualHostPort() {
        ContainerListVO.PortMapping pm = new ContainerListVO.PortMapping();
        pm.setHostPort(27016);
        pm.setContainerPort(27015);
        pm.setProtocol("tcp");
        runningDetail.setPorts(List.of(pm));
        when(containerService.getContainerDetail(2L, "abc")).thenReturn(runningDetail);
        when(gameMetadataMapper.selectById(5L)).thenReturn(game(List.of("docker")));
        when(instanceMapper.selectByHostIdAndInstanceName(2L, "l4d2")).thenReturn(null);

        service.adoptContainer(2L, "abc", dto);

        ArgumentCaptor<GameInstance> captor = ArgumentCaptor.forClass(GameInstance.class);
        verify(instanceMapper).insert(captor.capture());
        assertThat(captor.getValue().getPortConfig()).containsEntry("game", 27016);
    }

    @Test
    void adoptedFlagDetection() {
        GameInstance adopted = new GameInstance();
        adopted.setRuntimeMetadata(Map.of(ContainerAdoptionService.ADOPTED_FLAG_KEY, true));
        GameInstance plain = new GameInstance();
        plain.setRuntimeMetadata(Map.of("containerId", "x"));

        assertThat(ContainerAdoptionService.isAdopted(adopted)).isTrue();
        assertThat(ContainerAdoptionService.isAdopted(plain)).isFalse();
        assertThat(ContainerAdoptionService.isAdopted(null)).isFalse();
    }
}

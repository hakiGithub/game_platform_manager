package com.gameplatform.service;

import com.gameplatform.adapter.DeployAdapter;
import com.gameplatform.adapter.DeployAdapterFactory;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.dto.InstanceCreateDTO;
import com.gameplatform.dto.InstanceUpdateDTO;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.GameMetadata;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.mapper.GameMetadataMapper;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.plugin.listener.PluginLifecycleHook;
import com.gameplatform.service.impl.InstanceServiceImpl;
import com.gameplatform.util.SshUtil;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 实例服务实现类测试
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
class InstanceServiceImplTest {

    @Mock
    private GameInstanceMapper instanceMapper;

    @Mock
    private GameMetadataMapper gameMetadataMapper;

    @Mock
    private HostMapper hostMapper;


    @Mock
    private DeployAdapterFactory adapterFactory;

    @Mock
    private SshUtil sshUtil;

    @Mock
    private DeployAdapter deployAdapter;

    @Mock
    private PluginLifecycleHook pluginLifecycleHook;

    @Mock
    private DeploymentAccess deployAccess;

    @InjectMocks
    private InstanceServiceImpl instanceService;

    @BeforeEach
    void setUp() {
        // classify 真实语义由 DeploymentAccessTest 锁定；测试数据 deployType 均为 "docker"
        lenient().when(deployAccess.classify(any()))
                .thenReturn(DeployAdapter.DeployType.DOCKER);

        // 设置安全上下文
        Authentication auth = new UsernamePasswordAuthenticationToken("testUser", "password");
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private GameInstance createInstance(Long id, String name, int status) {
        GameInstance instance = new GameInstance();
        instance.setId(id);
        instance.setInstanceName(name);
        instance.setHostId(1L);
        instance.setGameId(1L);
        instance.setRunStatus(status);
        instance.setDeployType("docker");
        instance.setInstallPath("/opt/game");
        return instance;
    }

    private Host createHost() {
        Host host = new Host();
        host.setId(1L);
        host.setHostName("test-host");
        host.setIpAddress("192.168.1.100");
        return host;
    }

    private GameMetadata createGame() {
        GameMetadata game = new GameMetadata();
        game.setId(1L);
        game.setGameName("Test Game");
        return game;
    }

    @Test
    void testCreateInstance() {
        InstanceCreateDTO dto = new InstanceCreateDTO();
        dto.setInstanceName("test-instance");
        dto.setHostId(1L);
        dto.setGameId(1L);

        when(instanceMapper.selectByHostIdAndInstanceName(1L, "test-instance")).thenReturn(null);
        when(hostMapper.selectById(1L)).thenReturn(createHost());
        when(gameMetadataMapper.selectById(1L)).thenReturn(createGame());

        InstanceVO result = instanceService.createInstance(dto);

        assertNotNull(result);
        assertEquals("test-instance", result.getInstanceName());
        verify(instanceMapper).insert(any(GameInstance.class));
    }

    @Test
    void testCreateInstanceDuplicateName() {
        InstanceCreateDTO dto = new InstanceCreateDTO();
        dto.setInstanceName("existing-instance");
        dto.setHostId(1L);

        when(instanceMapper.selectByHostIdAndInstanceName(1L, "existing-instance")).thenReturn(new GameInstance());

        assertThrows(BusinessException.class, () -> {
            instanceService.createInstance(dto);
        });
    }

    @Test
    void testStartInstance() {
        GameInstance instance = createInstance(1L, "test-instance", 0);
        Map<String, Object> config = new HashMap<>();
        instance.setConfigInfo(config);

        when(instanceMapper.selectById(1L)).thenReturn(instance);
        when(adapterFactory.getAdapter(DeployAdapter.DeployType.DOCKER)).thenReturn(deployAdapter);
        when(deployAdapter.start(eq(1L), anyMap())).thenReturn(true);

        boolean result = instanceService.startInstance(1L);

        assertTrue(result);
        verify(instanceMapper).updateRunStatus(1L, 2); // 启动中状态
        verify(instanceMapper).updateRunStatus(1L, 1); // 运行中状态
    }

    @Test
    void testStartInstanceAlreadyRunning() {
        GameInstance instance = createInstance(1L, "test-instance", 1);

        when(instanceMapper.selectById(1L)).thenReturn(instance);

        assertThrows(BusinessException.class, () -> {
            instanceService.startInstance(1L);
        });
    }

    @Test
    void testStopInstance() {
        GameInstance instance = createInstance(1L, "test-instance", 1);
        Map<String, Object> config = new HashMap<>();
        instance.setConfigInfo(config);

        when(instanceMapper.selectById(1L)).thenReturn(instance);
        when(adapterFactory.getAdapter(DeployAdapter.DeployType.DOCKER)).thenReturn(deployAdapter);
        when(deployAdapter.stop(eq(1L), anyMap())).thenReturn(true);

        boolean result = instanceService.stopInstance(1L);

        assertTrue(result);
        verify(instanceMapper).updateRunStatus(1L, 3); // 停止中状态
        verify(instanceMapper).updateRunStatus(1L, 0); // 已停止状态
        verify(instanceMapper).updateOnlinePlayers(1L, 0);
    }

    @Test
    void testStopInstanceAlreadyStopped() {
        GameInstance instance = createInstance(1L, "test-instance", 0);

        when(instanceMapper.selectById(1L)).thenReturn(instance);

        assertThrows(BusinessException.class, () -> {
            instanceService.stopInstance(1L);
        });
    }

    @Test
    void testRestartInstance() {
        GameInstance instance = createInstance(1L, "test-instance", 1);
        Map<String, Object> config = new HashMap<>();
        instance.setConfigInfo(config);

        when(instanceMapper.selectById(1L)).thenReturn(instance);
        when(adapterFactory.getAdapter(DeployAdapter.DeployType.DOCKER)).thenReturn(deployAdapter);
        when(deployAdapter.restart(eq(1L), anyMap())).thenReturn(true);

        boolean result = instanceService.restartInstance(1L);

        assertTrue(result);
        verify(instanceMapper).updateRunStatus(1L, 1);
    }

    @Test
    void testGetInstanceStatus() {
        GameInstance instance = createInstance(1L, "test-instance", 0);
        Map<String, Object> config = new HashMap<>();
        instance.setConfigInfo(config);

        when(instanceMapper.selectById(1L)).thenReturn(instance);
        when(adapterFactory.getAdapter(DeployAdapter.DeployType.DOCKER)).thenReturn(deployAdapter);
        when(deployAdapter.getStatus(eq(1L), anyMap())).thenReturn(DeployAdapter.InstanceStatus.RUNNING);
        when(hostMapper.selectById(1L)).thenReturn(createHost());
        when(gameMetadataMapper.selectById(1L)).thenReturn(createGame());

        InstanceVO result = instanceService.getInstanceStatus(1L);

        assertNotNull(result);
        verify(instanceMapper).updateRunStatus(1L, 1);
    }

    @Test
    void testGetInstanceLogs() {
        GameInstance instance = createInstance(1L, "test-instance", 1);
        Map<String, Object> config = new HashMap<>();
        instance.setConfigInfo(config);

        when(instanceMapper.selectById(1L)).thenReturn(instance);
        when(adapterFactory.getAdapter(DeployAdapter.DeployType.DOCKER)).thenReturn(deployAdapter);
        when(deployAdapter.getLogs(eq(1L), anyMap(), eq(100))).thenReturn("log content");

        String result = instanceService.getInstanceLogs(1L, 100);

        assertEquals("log content", result);
    }

    @Test
    void testExecuteCommand() {
        GameInstance instance = createInstance(1L, "test-instance", 1);
        Map<String, Object> config = new HashMap<>();
        instance.setConfigInfo(config);

        when(instanceMapper.selectById(1L)).thenReturn(instance);
        when(adapterFactory.getAdapter(DeployAdapter.DeployType.DOCKER)).thenReturn(deployAdapter);
        when(deployAdapter.executeCommand(eq(1L), anyMap(), eq("help"))).thenReturn("command output");

        String result = instanceService.executeCommand(1L, "help");

        assertEquals("command output", result);
    }

    @Test
    void testDeleteInstance() {
        GameInstance instance = createInstance(1L, "test-instance", 0);

        when(instanceMapper.selectById(1L)).thenReturn(instance);
        when(adapterFactory.getAdapter(DeployAdapter.DeployType.DOCKER)).thenReturn(deployAdapter);
        when(instanceMapper.physicalDeleteById(1L)).thenReturn(1);

        instanceService.deleteInstance(1L);

        verify(instanceMapper).physicalDeleteById(1L);
    }

    @Test
    void testDeleteRunningInstance() {
        GameInstance instance = createInstance(1L, "test-instance", 1);
        Map<String, Object> config = new HashMap<>();
        instance.setConfigInfo(config);

        when(instanceMapper.selectById(1L)).thenReturn(instance);
        when(adapterFactory.getAdapter(DeployAdapter.DeployType.DOCKER)).thenReturn(deployAdapter);
        when(deployAdapter.uninstall(eq(1L), anyMap(), any())).thenReturn(true);
        when(instanceMapper.physicalDeleteById(1L)).thenReturn(1);

        instanceService.deleteInstance(1L);

        verify(deployAdapter).uninstall(eq(1L), anyMap(), any());
        verify(instanceMapper).physicalDeleteById(1L);
    }

    @Test
    void testUpdateInstance() {
        InstanceUpdateDTO dto = new InstanceUpdateDTO();
        dto.setId(1L);
        dto.setInstanceName("updated-instance");

        GameInstance existing = createInstance(1L, "old-instance", 0);

        when(instanceMapper.selectById(1L)).thenReturn(existing);
        when(instanceMapper.selectByHostIdAndInstanceName(existing.getHostId(), "updated-instance")).thenReturn(null);

        InstanceVO result = instanceService.updateInstance(dto);

        assertNotNull(result);
        verify(instanceMapper).updateById(any(GameInstance.class));
    }
}

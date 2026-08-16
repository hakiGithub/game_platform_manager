package com.gameplatform.service;

import com.gameplatform.adapter.DeployAdapter;
import com.gameplatform.adapter.DeployAdapterFactory;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.common.result.PageResult;
import com.gameplatform.dto.InstanceCreateDTO;
import com.gameplatform.dto.InstanceUpdateDTO;
import com.gameplatform.dto.PageQueryDTO;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 游戏实例服务测试类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("游戏实例服务测试")
class InstanceServiceTest {

    @Mock
    private GameInstanceMapper instanceMapper;

    @Mock
    private GameMetadataMapper gameMetadataMapper;

    @Mock
    private HostMapper hostMapper;


    @Mock
    private DeployAdapterFactory adapterFactory;

    @Mock
    private DeployAdapter deployAdapter;

    @Mock
    private SshUtil sshUtil;

    @Mock
    private PluginLifecycleHook pluginLifecycleHook;

    @Mock
    private com.gameplatform.service.DeployService deployService;

    @Mock
    private com.gameplatform.deploy.DeploymentAccess deployAccess;

    @InjectMocks
    private InstanceServiceImpl instanceService;

    private GameInstance testInstance;
    private Host testHost;
    private GameMetadata testGame;
    private InstanceCreateDTO createDTO;
    private InstanceUpdateDTO updateDTO;

    @BeforeEach
    void setUp() {
        // classify 真实语义由 DeploymentAccessTest 锁定，此处按实例 deployType 原样返回
        lenient().when(deployAccess.classify(any()))
                .thenAnswer(inv -> DeployAdapter.DeployType.fromCode(inv.getArgument(0)));

        // Given: 初始化测试数据
        testHost = new Host();
        testHost.setId(1L);
        testHost.setHostName("测试主机");
        testHost.setIpAddress("192.168.1.100");

        testGame = new GameMetadata();
        testGame.setId(1L);
        testGame.setGameName("Minecraft");
        testGame.setGameCode("minecraft");

        testInstance = new GameInstance();
        testInstance.setId(1L);
        testInstance.setInstanceName("Minecraft服务器1");
        testInstance.setHostId(1L);
        testInstance.setGameId(1L);
        testInstance.setDeployType("docker");
        testInstance.setRunStatus(0);
        testInstance.setOnlinePlayers(0);
        testInstance.setInstallPath("/opt/games/minecraft");
        testInstance.setStartCommand("docker start minecraft");
        testInstance.setStopCommand("docker stop minecraft");
        testInstance.setCreateTime(LocalDateTime.now());
        testInstance.setUpdateTime(LocalDateTime.now());

        Map<String, Object> portConfig = new HashMap<>();
        portConfig.put("game", 25565);
        portConfig.put("rcon", 25575);
        testInstance.setPortConfig(portConfig);

        Map<String, Object> configInfo = new HashMap<>();
        configInfo.put("maxPlayers", 20);
        configInfo.put("difficulty", "normal");
        testInstance.setConfigInfo(configInfo);

        createDTO = new InstanceCreateDTO();
        createDTO.setInstanceName("新实例");
        createDTO.setHostId(1L);
        createDTO.setGameId(1L);
        createDTO.setDeployType("docker");
        createDTO.setInstallPath("/opt/games/new");
        createDTO.setStartCommand("docker run new");
        createDTO.setStopCommand("docker stop new");

        updateDTO = new InstanceUpdateDTO();
        updateDTO.setId(1L);
        updateDTO.setInstanceName("更新后的实例");
        updateDTO.setInstallPath("/opt/games/updated");
    }

    @Test
    @DisplayName("创建实例-成功")
    void testCreateInstanceSuccess() {
        // Given
        when(instanceMapper.selectByHostIdAndInstanceName(createDTO.getHostId(), createDTO.getInstanceName())).thenReturn(null);
        when(hostMapper.selectById(1L)).thenReturn(testHost);
        when(gameMetadataMapper.selectById(1L)).thenReturn(testGame);
        when(instanceMapper.insert(any(GameInstance.class))).thenAnswer(invocation -> {
            GameInstance instance = invocation.getArgument(0);
            instance.setId(2L);
            return 1;
        });

        // When
        InstanceVO result = instanceService.createInstance(createDTO);

        // Then
        assertNotNull(result);
        assertEquals(createDTO.getInstanceName(), result.getInstanceName());
        assertEquals(createDTO.getHostId(), result.getHostId());
        assertEquals(createDTO.getGameId(), result.getGameId());
        assertEquals(5, result.getRunStatus()); // 初始状态为部署中
        assertEquals(0, result.getOnlinePlayers());
        verify(instanceMapper).selectByHostIdAndInstanceName(createDTO.getHostId(), createDTO.getInstanceName());
        verify(hostMapper, atLeast(1)).selectById(1L);
        verify(gameMetadataMapper, atLeast(1)).selectById(1L);
        verify(instanceMapper).insert(any(GameInstance.class));
    }

    @Test
    @DisplayName("创建实例-波浪号 installPath 展开为绝对路径")
    void testCreateInstanceExpandsTildeInInstallPath() {
        // Given: 主机 sshUser=haki，用户输入 ~/games/l4d2
        testHost.setSshUser("haki");
        when(instanceMapper.selectByHostIdAndInstanceName(any(), any())).thenReturn(null);
        when(hostMapper.selectById(1L)).thenReturn(testHost);
        when(gameMetadataMapper.selectById(1L)).thenReturn(testGame);
        when(instanceMapper.insert(any(GameInstance.class))).thenAnswer(inv -> {
            GameInstance g = inv.getArgument(0);
            g.setId(2L);
            return 1;
        });
        createDTO.setInstallPath("~/games/l4d2");

        // When
        instanceService.createInstance(createDTO);

        // Then: ~ 被展开为 /home/haki/...（否则文件管理 SFTP 报 NO_SUCH_FILE）
        ArgumentCaptor<GameInstance> captor = ArgumentCaptor.forClass(GameInstance.class);
        verify(instanceMapper).insert(captor.capture());
        assertEquals("/home/haki/games/l4d2", captor.getValue().getInstallPath());
    }

    @Test
    @DisplayName("创建实例-实例名称已存在")
    void testCreateInstanceNameExists() {
        // Given
        when(instanceMapper.selectByHostIdAndInstanceName(createDTO.getHostId(), createDTO.getInstanceName())).thenReturn(testInstance);

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            instanceService.createInstance(createDTO);
        });
        assertEquals("该主机下实例名称「" + createDTO.getInstanceName() + "」已存在，请更换名称后重试",
                exception.getMessage());
        verify(instanceMapper).selectByHostIdAndInstanceName(createDTO.getHostId(), createDTO.getInstanceName());
        verify(instanceMapper, never()).insert(any(GameInstance.class));
    }

    @Test
    @DisplayName("创建实例-主机不存在")
    void testCreateInstanceHostNotFound() {
        // Given
        when(instanceMapper.selectByHostIdAndInstanceName(createDTO.getHostId(), createDTO.getInstanceName())).thenReturn(null);
        when(hostMapper.selectById(1L)).thenReturn(null);

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            instanceService.createInstance(createDTO);
        });
        assertEquals("主机不存在", exception.getMessage());
    }

    @Test
    @DisplayName("创建实例-游戏不存在")
    void testCreateInstanceGameNotFound() {
        // Given
        when(instanceMapper.selectByHostIdAndInstanceName(createDTO.getHostId(), createDTO.getInstanceName())).thenReturn(null);
        when(hostMapper.selectById(1L)).thenReturn(testHost);
        when(gameMetadataMapper.selectById(1L)).thenReturn(null);

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            instanceService.createInstance(createDTO);
        });
        assertEquals("游戏不存在", exception.getMessage());
    }

    @Test
    @DisplayName("更新实例-成功")
    void testUpdateInstanceSuccess() {
        // Given
        when(instanceMapper.selectById(1L)).thenReturn(testInstance);
        when(instanceMapper.updateById(any(GameInstance.class))).thenReturn(1);
        when(hostMapper.selectById(1L)).thenReturn(testHost);
        when(gameMetadataMapper.selectById(1L)).thenReturn(testGame);

        // When
        InstanceVO result = instanceService.updateInstance(updateDTO);

        // Then
        assertNotNull(result);
        assertEquals(updateDTO.getInstanceName(), result.getInstanceName());
        assertEquals(updateDTO.getInstallPath(), result.getInstallPath());
        verify(instanceMapper).selectById(1L);
        verify(instanceMapper).updateById(any(GameInstance.class));
    }

    @Test
    @DisplayName("更新实例-实例不存在")
    void testUpdateInstanceNotFound() {
        // Given
        when(instanceMapper.selectById(1L)).thenReturn(null);

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            instanceService.updateInstance(updateDTO);
        });
        assertEquals("实例不存在", exception.getMessage());
    }

    @Test
    @DisplayName("更新实例-实例名称已被使用")
    void testUpdateInstanceNameUsedByOther() {
        // Given
        GameInstance otherInstance = new GameInstance();
        otherInstance.setId(2L);
        otherInstance.setInstanceName("其他实例");

        InstanceUpdateDTO dto = new InstanceUpdateDTO();
        dto.setId(1L);
        dto.setInstanceName("其他实例");

        when(instanceMapper.selectById(1L)).thenReturn(testInstance);
        when(instanceMapper.selectByHostIdAndInstanceName(testInstance.getHostId(), "其他实例")).thenReturn(otherInstance);

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            instanceService.updateInstance(dto);
        });
        assertEquals("该主机下实例名称「其他实例」已被使用，请更换名称",
                exception.getMessage());
    }

    @Test
    @DisplayName("删除实例-成功（已停止状态）")
    void testDeleteInstanceSuccessStopped() {
        // Given
        testInstance.setRunStatus(0); // 已停止
        testInstance.setDeployType("docker");
        when(instanceMapper.selectById(1L)).thenReturn(testInstance);
        when(gameMetadataMapper.selectById(1L)).thenReturn(testGame);
        when(adapterFactory.getAdapter(any(DeployAdapter.DeployType.class))).thenReturn(deployAdapter);
        when(deployAdapter.uninstall(eq(1L), anyMap(), any())).thenReturn(true);
        when(instanceMapper.physicalDeleteById(1L)).thenReturn(1);

        // When
        instanceService.deleteInstance(1L);

        // Then
        verify(instanceMapper).selectById(1L);
        verify(deployAdapter).uninstall(eq(1L), anyMap(), any());
        verify(instanceMapper).physicalDeleteById(1L);
    }

    @Test
    @DisplayName("删除实例-成功（运行中状态）")
    void testDeleteInstanceSuccessRunning() {
        // Given
        testInstance.setRunStatus(1); // 运行中
        testInstance.setDeployType("docker");
        when(instanceMapper.selectById(1L)).thenReturn(testInstance);
        when(gameMetadataMapper.selectById(1L)).thenReturn(testGame);
        when(adapterFactory.getAdapter(any(DeployAdapter.DeployType.class))).thenReturn(deployAdapter);
        when(deployAdapter.uninstall(eq(1L), anyMap(), any())).thenReturn(true);
        when(instanceMapper.physicalDeleteById(1L)).thenReturn(1);

        // When
        instanceService.deleteInstance(1L);

        // Then
        verify(instanceMapper, atLeast(1)).selectById(1L);
        verify(deployAdapter).uninstall(eq(1L), anyMap(), any());
        verify(instanceMapper).physicalDeleteById(1L);
    }

    @Test
    @DisplayName("删除实例-实例不存在")
    void testDeleteInstanceNotFound() {
        // Given
        when(instanceMapper.selectById(1L)).thenReturn(null);

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            instanceService.deleteInstance(1L);
        });
        assertEquals("实例不存在", exception.getMessage());
    }

    @Test
    @DisplayName("根据ID查询实例-成功")
    void testGetInstanceByIdSuccess() {
        // Given
        when(instanceMapper.selectById(1L)).thenReturn(testInstance);
        when(hostMapper.selectById(1L)).thenReturn(testHost);
        when(gameMetadataMapper.selectById(1L)).thenReturn(testGame);

        // When
        InstanceVO result = instanceService.getInstanceById(1L);

        // Then
        assertNotNull(result);
        assertEquals(testInstance.getId(), result.getId());
        assertEquals(testInstance.getInstanceName(), result.getInstanceName());
        assertEquals(testHost.getHostName(), result.getHostName());
        assertEquals(testGame.getGameName(), result.getGameName());
    }

    @Test
    @DisplayName("根据ID查询实例-实例不存在")
    void testGetInstanceByIdNotFound() {
        // Given
        when(instanceMapper.selectById(1L)).thenReturn(null);

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            instanceService.getInstanceById(1L);
        });
        assertEquals("实例不存在", exception.getMessage());
    }

    @Test
    @DisplayName("分页查询实例")
    void testPageInstances() {
        // Given
        PageQueryDTO queryDTO = new PageQueryDTO();
        queryDTO.setCurrent(1);
        queryDTO.setSize(10);
        queryDTO.setKeyword("Minecraft");

        GameInstance instance2 = new GameInstance();
        instance2.setId(2L);
        instance2.setInstanceName("Minecraft服务器2");
        instance2.setHostId(1L);
        instance2.setGameId(1L);
        instance2.setRunStatus(1);

        List<GameInstance> instanceList = Arrays.asList(testInstance, instance2);

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<GameInstance> pageResult = 
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10, 2);
        pageResult.setRecords(instanceList);

        when(instanceMapper.selectPage(any(), any())).thenReturn(pageResult);

        // When
        PageResult<InstanceVO> result = instanceService.pageInstances(queryDTO);

        // Then
        assertNotNull(result);
        assertEquals(2, result.getTotal());
        assertEquals(2, result.getRecords().size());
    }

    @Test
    @DisplayName("根据主机ID查询实例列表")
    void testGetInstancesByHostId() {
        // Given
        when(instanceMapper.selectByHostId(1L)).thenReturn(Collections.singletonList(testInstance));
        when(hostMapper.selectById(1L)).thenReturn(testHost);
        when(gameMetadataMapper.selectById(1L)).thenReturn(testGame);

        // When
        List<InstanceVO> result = instanceService.getInstancesByHostId(1L);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(testInstance.getInstanceName(), result.get(0).getInstanceName());
    }

    @Test
    @DisplayName("根据游戏ID查询实例列表")
    void testGetInstancesByGameId() {
        // Given
        when(instanceMapper.selectByGameId(1L)).thenReturn(Collections.singletonList(testInstance));
        when(hostMapper.selectById(1L)).thenReturn(testHost);
        when(gameMetadataMapper.selectById(1L)).thenReturn(testGame);

        // When
        List<InstanceVO> result = instanceService.getInstancesByGameId(1L);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(testInstance.getInstanceName(), result.get(0).getInstanceName());
    }

    @Test
    @DisplayName("启动实例-成功")
    void testStartInstanceSuccess() {
        // Given
        testInstance.setRunStatus(0); // 已停止
        when(instanceMapper.selectById(1L)).thenReturn(testInstance);
        when(instanceMapper.updateRunStatus(1L, 2)).thenReturn(1);
        when(instanceMapper.updateRunStatus(1L, 1)).thenReturn(1);
        when(adapterFactory.getAdapter(any(DeployAdapter.DeployType.class))).thenReturn(deployAdapter);
        when(deployAdapter.start(eq(1L), anyMap())).thenReturn(true);

        // When
        boolean result = instanceService.startInstance(1L);

        // Then
        assertTrue(result);
        verify(instanceMapper, times(2)).updateRunStatus(eq(1L), anyInt());
    }

    @Test
    @DisplayName("启动实例-实例不存在")
    void testStartInstanceNotFound() {
        // Given
        when(instanceMapper.selectById(1L)).thenReturn(null);

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            instanceService.startInstance(1L);
        });
        assertEquals("实例不存在", exception.getMessage());
    }

    @Test
    @DisplayName("启动实例-已在运行中")
    void testStartInstanceAlreadyRunning() {
        // Given
        testInstance.setRunStatus(1); // 运行中
        when(instanceMapper.selectById(1L)).thenReturn(testInstance);

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            instanceService.startInstance(1L);
        });
        assertEquals("实例已在运行中", exception.getMessage());
    }

    @Test
    @DisplayName("停止实例-成功")
    void testStopInstanceSuccess() {
        // Given
        testInstance.setRunStatus(1); // 运行中
        testInstance.setOnlinePlayers(5);
        when(instanceMapper.selectById(1L)).thenReturn(testInstance);
        when(instanceMapper.updateRunStatus(1L, 3)).thenReturn(1);
        when(instanceMapper.updateRunStatus(1L, 0)).thenReturn(1);
        when(instanceMapper.updateOnlinePlayers(1L, 0)).thenReturn(1);
        when(adapterFactory.getAdapter(any(DeployAdapter.DeployType.class))).thenReturn(deployAdapter);
        when(deployAdapter.stop(eq(1L), anyMap())).thenReturn(true);

        // When
        boolean result = instanceService.stopInstance(1L);

        // Then
        assertTrue(result);
        verify(instanceMapper, times(2)).updateRunStatus(eq(1L), anyInt());
        verify(instanceMapper).updateOnlinePlayers(1L, 0);
    }

    @Test
    @DisplayName("停止实例-实例不存在")
    void testStopInstanceNotFound() {
        // Given
        when(instanceMapper.selectById(1L)).thenReturn(null);

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            instanceService.stopInstance(1L);
        });
        assertEquals("实例不存在", exception.getMessage());
    }

    @Test
    @DisplayName("停止实例-已停止")
    void testStopInstanceAlreadyStopped() {
        // Given
        testInstance.setRunStatus(0); // 已停止
        when(instanceMapper.selectById(1L)).thenReturn(testInstance);

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            instanceService.stopInstance(1L);
        });
        assertEquals("实例已停止", exception.getMessage());
    }

    @Test
    @DisplayName("重启实例-成功")
    void testRestartInstanceSuccess() {
        // Given
        // 创建一个可变的实例用于测试
        GameInstance runningInstance = new GameInstance();
        runningInstance.setId(1L);
        runningInstance.setInstanceName("Minecraft服务器1");
        runningInstance.setHostId(1L);
        runningInstance.setGameId(1L);
        runningInstance.setRunStatus(1); // 运行中
        runningInstance.setDeployType("docker");
        runningInstance.setConfigInfo(new HashMap<>()); // 设置空配置
        
        when(instanceMapper.selectById(1L)).thenReturn(runningInstance);
        when(instanceMapper.updateRunStatus(eq(1L), anyInt())).thenReturn(1);
        when(adapterFactory.getAdapter(any(DeployAdapter.DeployType.class))).thenReturn(deployAdapter);
        when(deployAdapter.restart(eq(1L), anyMap())).thenReturn(true);

        // When
        boolean result = instanceService.restartInstance(1L);

        // Then
        assertTrue(result);
        verify(instanceMapper).updateRunStatus(eq(1L), eq(1));
        verify(deployAdapter).restart(eq(1L), anyMap());
    }

    @Test
    @DisplayName("获取实例状态-成功")
    void testGetInstanceStatusSuccess() {
        // Given
        when(instanceMapper.selectById(1L)).thenReturn(testInstance);
        when(hostMapper.selectById(1L)).thenReturn(testHost);
        when(gameMetadataMapper.selectById(1L)).thenReturn(testGame);
        when(adapterFactory.getAdapter(any(DeployAdapter.DeployType.class))).thenReturn(deployAdapter);
        when(deployAdapter.getStatus(eq(1L), anyMap())).thenReturn(DeployAdapter.InstanceStatus.RUNNING);

        // When
        InstanceVO result = instanceService.getInstanceStatus(1L);

        // Then
        assertNotNull(result);
        assertEquals(testInstance.getRunStatus(), result.getRunStatus());
    }

    @Test
    @DisplayName("获取实例状态-实例不存在")
    void testGetInstanceStatusNotFound() {
        // Given
        when(instanceMapper.selectById(1L)).thenReturn(null);

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            instanceService.getInstanceStatus(1L);
        });
        assertEquals("实例不存在", exception.getMessage());
    }

    @Test
    @DisplayName("VO转换-运行状态描述")
    void testInstanceVOStatusDesc() {
        // 测试已停止状态
        testInstance.setRunStatus(0);
        when(instanceMapper.selectById(1L)).thenReturn(testInstance);
        when(hostMapper.selectById(1L)).thenReturn(testHost);
        when(gameMetadataMapper.selectById(1L)).thenReturn(testGame);

        InstanceVO result = instanceService.getInstanceById(1L);
        assertEquals("已停止", result.getRunStatusDesc());

        // 测试运行中状态
        testInstance.setRunStatus(1);
        result = instanceService.getInstanceById(1L);
        assertEquals("运行中", result.getRunStatusDesc());

        // 测试异常状态（ADR-0005：ERROR=4）
        testInstance.setRunStatus(DeployAdapter.InstanceStatus.ERROR.getCode());
        result = instanceService.getInstanceById(1L);
        assertEquals("异常", result.getRunStatusDesc());

        // 测试启动中状态（ADR-0005：STARTING=2）
        testInstance.setRunStatus(DeployAdapter.InstanceStatus.STARTING.getCode());
        result = instanceService.getInstanceById(1L);
        assertEquals("启动中", result.getRunStatusDesc());

        // 测试安装中/更新中状态（ADR-0005：INSTALLING=5 / UPDATING=6）
        testInstance.setRunStatus(DeployAdapter.InstanceStatus.INSTALLING.getCode());
        result = instanceService.getInstanceById(1L);
        assertEquals("安装中", result.getRunStatusDesc());
        testInstance.setRunStatus(DeployAdapter.InstanceStatus.UPDATING.getCode());
        result = instanceService.getInstanceById(1L);
        assertEquals("更新中", result.getRunStatusDesc());
    }
}

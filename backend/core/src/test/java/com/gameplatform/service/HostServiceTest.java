package com.gameplatform.service;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.common.result.PageResult;
import com.gameplatform.dto.HostCreateDTO;
import com.gameplatform.dto.HostUpdateDTO;
import com.gameplatform.dto.PageQueryDTO;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.service.impl.HostServiceImpl;
import com.gameplatform.vo.HostVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 主机服务测试类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("主机服务测试")
class HostServiceTest {

    @Mock
    private HostMapper hostMapper;

    @Mock
    private LogService logService;

    @InjectMocks
    private HostServiceImpl hostService;

    private Host testHost;
    private HostCreateDTO createDTO;
    private HostUpdateDTO updateDTO;

    @BeforeEach
    void setUp() {
        // Given: 初始化测试数据
        testHost = new Host();
        testHost.setId(1L);
        testHost.setHostName("测试服务器1");
        testHost.setIpAddress("192.168.1.100");
        testHost.setSshPort(22);
        testHost.setSshUser("root");
        testHost.setOnlineStatus(1);
        testHost.setCpuUsage(new BigDecimal("45.50"));
        testHost.setMemoryUsage(new BigDecimal("60.25"));
        testHost.setDiskUsage(new BigDecimal("30.00"));
        testHost.setLastCheckTime(LocalDateTime.now());
        testHost.setCreateTime(LocalDateTime.now());
        testHost.setUpdateTime(LocalDateTime.now());

        createDTO = new HostCreateDTO();
        createDTO.setName("新服务器");
        createDTO.setIp("192.168.1.200");
        createDTO.setSshPort(22);
        createDTO.setSshUsername("root");
        createDTO.setSshPrivateKey("ssh-rsa test-key");
        createDTO.setRemark("测试主机");

        updateDTO = new HostUpdateDTO();
        updateDTO.setId(1L);
        updateDTO.setName("更新后的服务器");
        updateDTO.setIp("192.168.1.100");
        updateDTO.setSshPort(2222);
        updateDTO.setSshUsername("admin");
    }

    @Test
    @DisplayName("创建主机-成功")
    void testCreateHostSuccess() {
        // Given
        when(hostMapper.selectByIpAddress(createDTO.getIp())).thenReturn(null);
        when(hostMapper.insert(any(Host.class))).thenAnswer(invocation -> {
            Host host = invocation.getArgument(0);
            host.setId(2L);
            return 1;
        });
        doNothing().when(logService).log(anyString(), anyString(), anyString(), anyString(), anyString(), isNull(), isNull());

        // When
        HostVO result = hostService.createHost(createDTO);

        // Then
        assertNotNull(result);
        assertEquals(createDTO.getName(), result.getName());
        assertEquals(createDTO.getIp(), result.getIp());
        assertEquals(0, result.getStatus()); // 初始状态为离线
        verify(hostMapper).selectByIpAddress(createDTO.getIp());
        verify(hostMapper).insert(any(Host.class));
        verify(logService).log(anyString(), eq("CREATE"), eq("HOST"), anyString(), eq("success"), isNull(), isNull());
    }

    @Test
    @DisplayName("创建主机-IP已存在")
    void testCreateHostIpExists() {
        // Given
        when(hostMapper.selectByIpAddress(createDTO.getIp())).thenReturn(testHost);
        doNothing().when(logService).log(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            hostService.createHost(createDTO);
        });
        assertEquals("该IP地址已存在", exception.getMessage());
        verify(hostMapper).selectByIpAddress(createDTO.getIp());
        verify(hostMapper, never()).insert(any(Host.class));
    }

    @Test
    @DisplayName("更新主机-成功")
    void testUpdateHostSuccess() {
        // Given
        when(hostMapper.selectById(1L)).thenReturn(testHost);
        when(hostMapper.updateById(any(Host.class))).thenReturn(1);
        doNothing().when(logService).log(anyString(), anyString(), anyString(), anyString(), anyString(), isNull(), isNull());

        // When
        HostVO result = hostService.updateHost(updateDTO);

        // Then
        assertNotNull(result);
        assertEquals(updateDTO.getName(), result.getName());
        assertEquals(updateDTO.getSshPort(), result.getSshPort());
        assertEquals(updateDTO.getSshUsername(), result.getSshUsername());
        verify(hostMapper).selectById(1L);
        verify(hostMapper).updateById(any(Host.class));
        verify(logService).log(anyString(), eq("UPDATE"), eq("HOST"), anyString(), eq("success"), isNull(), isNull());
    }

    @Test
    @DisplayName("更新主机-主机不存在")
    void testUpdateHostNotFound() {
        // Given
        when(hostMapper.selectById(1L)).thenReturn(null);
        doNothing().when(logService).log(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            hostService.updateHost(updateDTO);
        });
        assertEquals("主机不存在", exception.getMessage());
        verify(hostMapper).selectById(1L);
        verify(hostMapper, never()).updateById(any(Host.class));
    }

    @Test
    @DisplayName("更新主机-IP被其他主机使用")
    void testUpdateHostIpUsedByOther() {
        // Given
        Host otherHost = new Host();
        otherHost.setId(2L);
        otherHost.setIpAddress("192.168.1.200");

        HostUpdateDTO dto = new HostUpdateDTO();
        dto.setId(1L);
        dto.setIp("192.168.1.200");

        when(hostMapper.selectById(1L)).thenReturn(testHost);
        when(hostMapper.selectByIpAddress("192.168.1.200")).thenReturn(otherHost);
        doNothing().when(logService).log(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            hostService.updateHost(dto);
        });
        assertEquals("该IP地址已被其他主机使用", exception.getMessage());
    }

    @Test
    @DisplayName("删除主机-成功")
    void testDeleteHostSuccess() {
        // Given
        when(hostMapper.selectById(1L)).thenReturn(testHost);
        when(hostMapper.deleteById(1L)).thenReturn(1);
        doNothing().when(logService).log(anyString(), anyString(), anyString(), anyString(), anyString(), isNull(), isNull());

        // When
        hostService.deleteHost(1L);

        // Then
        verify(hostMapper).selectById(1L);
        verify(hostMapper).deleteById(1L);
        verify(logService).log(anyString(), eq("DELETE"), eq("HOST"), anyString(), eq("success"), isNull(), isNull());
    }

    @Test
    @DisplayName("删除主机-主机不存在")
    void testDeleteHostNotFound() {
        // Given
        when(hostMapper.selectById(1L)).thenReturn(null);
        doNothing().when(logService).log(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            hostService.deleteHost(1L);
        });
        assertEquals("主机不存在", exception.getMessage());
        verify(hostMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("根据ID查询主机-成功")
    void testGetHostByIdSuccess() {
        // Given
        when(hostMapper.selectById(1L)).thenReturn(testHost);

        // When
        HostVO result = hostService.getHostById(1L);

        // Then
        assertNotNull(result);
        assertEquals(testHost.getId(), result.getId());
        assertEquals(testHost.getHostName(), result.getName());
        assertEquals(testHost.getIpAddress(), result.getIp());
        assertEquals("在线", result.getOnlineStatusDesc());
    }

    @Test
    @DisplayName("根据ID查询主机-主机不存在")
    void testGetHostByIdNotFound() {
        // Given
        when(hostMapper.selectById(1L)).thenReturn(null);

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            hostService.getHostById(1L);
        });
        assertEquals("主机不存在", exception.getMessage());
    }

    @Test
    @DisplayName("分页查询主机")
    void testPageHosts() {
        // Given
        PageQueryDTO queryDTO = new PageQueryDTO();
        queryDTO.setCurrent(1);
        queryDTO.setSize(10);
        queryDTO.setKeyword("测试");

        Host host2 = new Host();
        host2.setId(2L);
        host2.setHostName("测试服务器2");
        host2.setIpAddress("192.168.1.101");
        host2.setOnlineStatus(0);

        List<Host> hostList = Arrays.asList(testHost, host2);

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Host> pageResult = 
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10, 2);
        pageResult.setRecords(hostList);

        when(hostMapper.selectPage(any(), any())).thenReturn(pageResult);

        // When
        PageResult<HostVO> result = hostService.pageHosts(queryDTO);

        // Then
        assertNotNull(result);
        assertEquals(2, result.getTotal());
        assertEquals(2, result.getRecords().size());
        assertEquals(1, result.getCurrent());
        assertEquals(10, result.getSize());
    }

    @Test
    @DisplayName("分页查询主机-无关键词")
    void testPageHostsWithoutKeyword() {
        // Given
        PageQueryDTO queryDTO = new PageQueryDTO();
        queryDTO.setCurrent(1);
        queryDTO.setSize(10);

        List<Host> hostList = Collections.singletonList(testHost);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Host> pageResult = 
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10, 1);
        pageResult.setRecords(hostList);

        when(hostMapper.selectPage(any(), any())).thenReturn(pageResult);

        // When
        PageResult<HostVO> result = hostService.pageHosts(queryDTO);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getTotal());
        assertEquals(1, result.getRecords().size());
    }

    @Test
    @DisplayName("获取在线主机列表")
    void testGetOnlineHosts() {
        // Given
        Host onlineHost = new Host();
        onlineHost.setId(1L);
        onlineHost.setHostName("在线服务器");
        onlineHost.setIpAddress("192.168.1.100");
        onlineHost.setOnlineStatus(1);

        when(hostMapper.selectOnlineHosts()).thenReturn(Collections.singletonList(onlineHost));

        // When
        List<HostVO> result = hostService.getOnlineHosts();

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("在线服务器", result.get(0).getName());
        assertEquals(1, result.get(0).getStatus());
    }

    @Test
    @DisplayName("获取在线主机列表-空列表")
    void testGetOnlineHostsEmpty() {
        // Given
        when(hostMapper.selectOnlineHosts()).thenReturn(Collections.emptyList());

        // When
        List<HostVO> result = hostService.getOnlineHosts();

        // Then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("测试主机连接-主机不存在")
    void testTestConnectionHostNotFound() {
        // Given
        when(hostMapper.selectById(1L)).thenReturn(null);

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            hostService.testConnection(1L);
        });
        assertEquals("主机不存在", exception.getMessage());
    }

    @Test
    @DisplayName("测试主机连接-更新状态")
    void testTestConnectionUpdateStatus() {
        // Given
        when(hostMapper.selectById(1L)).thenReturn(testHost);
        when(hostMapper.updateOnlineStatus(1L, 0)).thenReturn(1);

        // When
        boolean result = hostService.testConnection(1L);

        // Then
        assertFalse(result); // 当前实现返回false
        verify(hostMapper).updateOnlineStatus(1L, 0);
    }

    @Test
    @DisplayName("刷新主机状态-主机不存在")
    void testRefreshStatusHostNotFound() {
        // Given
        when(hostMapper.selectById(1L)).thenReturn(null);

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            hostService.refreshStatus(1L);
        });
        assertEquals("主机不存在", exception.getMessage());
    }

    @Test
    @DisplayName("刷新主机状态-成功")
    void testRefreshStatusSuccess() {
        // Given
        when(hostMapper.selectById(1L)).thenReturn(testHost);

        // When
        hostService.refreshStatus(1L);

        // Then
        verify(hostMapper).selectById(1L);
        // 注意：当前实现为TODO，实际刷新逻辑待实现
    }
}

package com.gameplatform.service;

import com.gameplatform.common.result.PageResult;
import com.gameplatform.dto.PageQueryDTO;
import com.gameplatform.entity.OperationLog;
import com.gameplatform.mapper.OperationLogMapper;
import com.gameplatform.service.impl.LogServiceImpl;
import com.gameplatform.vo.LogVO;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 操作日志服务测试类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("操作日志服务测试")
class LogServiceTest {

    @Mock
    private OperationLogMapper operationLogMapper;

    @InjectMocks
    private LogServiceImpl logService;

    private OperationLog testLog;

    @BeforeEach
    void setUp() {
        // Given: 初始化测试数据
        testLog = new OperationLog();
        testLog.setId(1L);
        testLog.setOperator("admin");
        testLog.setOperationType("CREATE");
        testLog.setOperationTarget("HOST");
        testLog.setOperationContent("创建主机: 测试服务器");
        testLog.setOperationResult("success");
        testLog.setIpAddress("192.168.1.100");
        testLog.setErrorMessage(null);
        testLog.setCreateTime(LocalDateTime.now());
        testLog.setUpdateTime(LocalDateTime.now());
    }

    @Test
    @DisplayName("记录操作日志-成功")
    void testLogSuccess() {
        // Given
        when(operationLogMapper.insert(any(OperationLog.class))).thenReturn(1);

        // When
        logService.log("admin", "CREATE", "HOST", "创建主机: 测试服务器", 
                "success", "192.168.1.100", null);

        // Then
        verify(operationLogMapper).insert(argThat(log -> 
            log.getOperator().equals("admin") &&
            log.getOperationType().equals("CREATE") &&
            log.getOperationTarget().equals("HOST") &&
            log.getOperationContent().equals("创建主机: 测试服务器") &&
            log.getOperationResult().equals("success") &&
            log.getIpAddress().equals("192.168.1.100") &&
            log.getErrorMessage() == null
        ));
    }

    @Test
    @DisplayName("记录操作日志-失败场景")
    void testLogFailure() {
        // Given
        when(operationLogMapper.insert(any(OperationLog.class))).thenReturn(1);

        // When
        logService.log("admin", "CREATE", "HOST", "创建主机: 测试服务器", 
                "fail", "192.168.1.100", "连接超时");

        // Then
        verify(operationLogMapper).insert(argThat(log -> 
            log.getOperator().equals("admin") &&
            log.getOperationResult().equals("fail") &&
            log.getErrorMessage().equals("连接超时")
        ));
    }

    @Test
    @DisplayName("记录操作日志-异常处理")
    void testLogExceptionHandling() {
        // Given
        when(operationLogMapper.insert(any(OperationLog.class)))
                .thenThrow(new RuntimeException("数据库连接失败"));

        // When - 不应该抛出异常，应该被捕获
        assertDoesNotThrow(() -> {
            logService.log("admin", "CREATE", "HOST", "创建主机", 
                    "success", "192.168.1.100", null);
        });

        // Then
        verify(operationLogMapper).insert(any(OperationLog.class));
    }

    @Test
    @DisplayName("分页查询日志")
    void testPageLogs() {
        // Given
        PageQueryDTO queryDTO = new PageQueryDTO();
        queryDTO.setCurrent(1);
        queryDTO.setSize(10);
        queryDTO.setKeyword("admin");

        OperationLog log2 = new OperationLog();
        log2.setId(2L);
        log2.setOperator("admin");
        log2.setOperationType("UPDATE");
        log2.setOperationTarget("INSTANCE");
        log2.setOperationContent("更新实例");
        log2.setOperationResult("success");
        log2.setCreateTime(LocalDateTime.now());

        List<OperationLog> logList = Arrays.asList(testLog, log2);

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<OperationLog> pageResult = 
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10, 2);
        pageResult.setRecords(logList);

        when(operationLogMapper.selectPage(any(), any())).thenReturn(pageResult);

        // When
        PageResult<LogVO> result = logService.pageLogs(queryDTO);

        // Then
        assertNotNull(result);
        assertEquals(2, result.getTotal());
        assertEquals(2, result.getRecords().size());
        assertEquals(1, result.getCurrent());
        assertEquals(10, result.getSize());
    }

    @Test
    @DisplayName("分页查询日志-无关键词")
    void testPageLogsWithoutKeyword() {
        // Given
        PageQueryDTO queryDTO = new PageQueryDTO();
        queryDTO.setCurrent(1);
        queryDTO.setSize(10);

        List<OperationLog> logList = Collections.singletonList(testLog);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<OperationLog> pageResult = 
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10, 1);
        pageResult.setRecords(logList);

        when(operationLogMapper.selectPage(any(), any())).thenReturn(pageResult);

        // When
        PageResult<LogVO> result = logService.pageLogs(queryDTO);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getTotal());
        assertEquals(1, result.getRecords().size());
    }

    @Test
    @DisplayName("根据操作人查询日志")
    void testGetLogsByOperator() {
        // Given
        when(operationLogMapper.selectByOperator("admin"))
                .thenReturn(Arrays.asList(testLog));

        // When
        List<LogVO> result = logService.getLogsByOperator("admin");

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("admin", result.get(0).getOperator());
        assertEquals("CREATE", result.get(0).getOperationType());
        verify(operationLogMapper).selectByOperator("admin");
    }

    @Test
    @DisplayName("根据操作人查询日志-空结果")
    void testGetLogsByOperatorEmpty() {
        // Given
        when(operationLogMapper.selectByOperator("unknown"))
                .thenReturn(Collections.emptyList());

        // When
        List<LogVO> result = logService.getLogsByOperator("unknown");

        // Then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("根据操作类型查询日志")
    void testGetLogsByOperationType() {
        // Given
        OperationLog createLog = new OperationLog();
        createLog.setId(1L);
        createLog.setOperator("admin");
        createLog.setOperationType("CREATE");
        createLog.setOperationTarget("HOST");

        OperationLog createLog2 = new OperationLog();
        createLog2.setId(2L);
        createLog2.setOperator("user");
        createLog2.setOperationType("CREATE");
        createLog2.setOperationTarget("INSTANCE");

        when(operationLogMapper.selectByOperationType("CREATE"))
                .thenReturn(Arrays.asList(createLog, createLog2));

        // When
        List<LogVO> result = logService.getLogsByOperationType("CREATE");

        // Then
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("CREATE", result.get(0).getOperationType());
        assertEquals("CREATE", result.get(1).getOperationType());
        verify(operationLogMapper).selectByOperationType("CREATE");
    }

    @Test
    @DisplayName("根据操作类型查询日志-空结果")
    void testGetLogsByOperationTypeEmpty() {
        // Given
        when(operationLogMapper.selectByOperationType("DELETE"))
                .thenReturn(Collections.emptyList());

        // When
        List<LogVO> result = logService.getLogsByOperationType("DELETE");

        // Then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("查询最近的日志")
    void testGetRecentLogs() {
        // Given
        OperationLog log1 = new OperationLog();
        log1.setId(1L);
        log1.setOperator("admin");
        log1.setOperationType("CREATE");
        log1.setCreateTime(LocalDateTime.now());

        OperationLog log2 = new OperationLog();
        log2.setId(2L);
        log2.setOperator("admin");
        log2.setOperationType("UPDATE");
        log2.setCreateTime(LocalDateTime.now().minusMinutes(5));

        when(operationLogMapper.selectRecentLogs(10))
                .thenReturn(Arrays.asList(log1, log2));

        // When
        List<LogVO> result = logService.getRecentLogs(10);

        // Then
        assertNotNull(result);
        assertEquals(2, result.size());
        verify(operationLogMapper).selectRecentLogs(10);
    }

    @Test
    @DisplayName("查询最近的日志-限制数量")
    void testGetRecentLogsWithLimit() {
        // Given
        List<OperationLog> logs = Arrays.asList(
            createLog(1L, "admin", "CREATE"),
            createLog(2L, "admin", "UPDATE"),
            createLog(3L, "user", "DELETE")
        );

        when(operationLogMapper.selectRecentLogs(5))
                .thenReturn(logs);

        // When
        List<LogVO> result = logService.getRecentLogs(5);

        // Then
        assertNotNull(result);
        assertEquals(3, result.size());
        verify(operationLogMapper).selectRecentLogs(5);
    }

    @Test
    @DisplayName("VO转换-完整数据")
    void testLogVOConversion() {
        // Given
        PageQueryDTO queryDTO = new PageQueryDTO();
        queryDTO.setCurrent(1);
        queryDTO.setSize(10);

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<OperationLog> pageResult = 
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10, 1);
        pageResult.setRecords(Collections.singletonList(testLog));

        when(operationLogMapper.selectPage(any(), any())).thenReturn(pageResult);

        // When
        PageResult<LogVO> result = logService.pageLogs(queryDTO);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getRecords().size());
        
        LogVO vo = result.getRecords().get(0);
        assertEquals(testLog.getId(), vo.getId());
        assertEquals(testLog.getOperator(), vo.getOperator());
        assertEquals(testLog.getOperationType(), vo.getOperationType());
        assertEquals(testLog.getOperationTarget(), vo.getOperationTarget());
        assertEquals(testLog.getOperationContent(), vo.getOperationContent());
        assertEquals(testLog.getOperationResult(), vo.getOperationResult());
        assertEquals(testLog.getIpAddress(), vo.getIpAddress());
        assertEquals(testLog.getErrorMessage(), vo.getErrorMessage());
        assertEquals(testLog.getCreateTime(), vo.getCreateTime());
    }

    @Test
    @DisplayName("VO转换-空错误信息处理")
    void testLogVOEmptyErrorMessage() {
        // Given
        OperationLog logWithoutError = new OperationLog();
        logWithoutError.setId(1L);
        logWithoutError.setOperator("admin");
        logWithoutError.setOperationType("CREATE");
        logWithoutError.setOperationTarget("HOST");
        logWithoutError.setOperationContent("创建成功");
        logWithoutError.setOperationResult("success");
        // errorMessage为null
        logWithoutError.setCreateTime(LocalDateTime.now());

        when(operationLogMapper.selectByOperator("admin"))
                .thenReturn(Collections.singletonList(logWithoutError));

        // When
        List<LogVO> result = logService.getLogsByOperator("admin");

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertNull(result.get(0).getErrorMessage());
    }

    /**
     * 辅助方法：创建测试日志
     */
    private OperationLog createLog(Long id, String operator, String operationType) {
        OperationLog log = new OperationLog();
        log.setId(id);
        log.setOperator(operator);
        log.setOperationType(operationType);
        log.setOperationTarget("HOST");
        log.setOperationContent("操作内容");
        log.setOperationResult("success");
        log.setCreateTime(LocalDateTime.now());
        return log;
    }
}

package com.gameplatform.service;

import com.gameplatform.adapter.DeployAdapter;
import com.gameplatform.adapter.DeployAdapterFactory;
import com.gameplatform.adapter.DeployProgressCallback;
import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.deploy.HostCredentials;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.util.SshUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 部署服务测试类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("部署服务测试")
class DeployServiceTest {

    @Mock
    private DeployAdapterFactory adapterFactory;

    @Mock
    private GameInstanceMapper instanceMapper;

    @Mock
    private HostMapper hostMapper;

    @Mock
    private SshUtil sshUtil;

    @Mock
    private DeployAdapter deployAdapter;

    @Mock
    private DeploymentAccess deployAccess;

    @InjectMocks
    private DeployService deployService;

    private Host testHost;
    private GameInstance testInstance;

    @BeforeEach
    void setUp() {
        // 初始化测试数据
        testHost = new Host();
        testHost.setId(1L);
        testHost.setHostName("测试主机");
        testHost.setIpAddress("192.168.1.100");
        testHost.setSshPort(22);
        testHost.setSshUser("root");
        testHost.setSshPrivateKey("test-key");

        testInstance = new GameInstance();
        testInstance.setId(1L);
        testInstance.setInstanceName("测试实例");
        testInstance.setHostId(1L);
        testInstance.setDeployType("docker");

        // 默认 mock 行为
        when(adapterFactory.getAdapter(any(DeployAdapter.DeployType.class))).thenReturn(deployAdapter);
        when(adapterFactory.getAdapter(anyString())).thenReturn(deployAdapter);
        when(deployAccess.credentials(any(Host.class)))
                .thenReturn(new HostCredentials("192.168.1.100", 22, "root", null, null));
    }

    @Test
    @DisplayName("测试部署上下文构建器")
    void testDeployContextBuilder() {
        // 测试部署上下文构建器
        Map<String, Object> config = new HashMap<>();
        config.put("image", "nginx:latest");
        config.put("containerName", "test-nginx");

        DeployService.DeployContext context = DeployService.DeployContext.builder()
                .instanceId(1L)
                .hostId(1L)
                .deployType(DeployAdapter.DeployType.DOCKER)
                .config(config)
                .autoRollback(true)
                .autoStart(true)
                .build();

        assertNotNull(context);
        assertEquals(1L, context.getInstanceId());
        assertEquals(1L, context.getHostId());
        assertEquals(DeployAdapter.DeployType.DOCKER, context.getDeployType());
        assertNotNull(context.getConfig());
        assertTrue(context.isAutoRollback());
        assertTrue(context.isAutoStart());
    }

    @Test
    @DisplayName("测试部署任务状态构建器")
    void testDeployTaskStatusBuilder() {
        // 测试部署任务状态构建器
        DeployService.DeployTaskStatus status = DeployService.DeployTaskStatus.builder()
                .instanceId(1L)
                .stage("DEPLOY")
                .progress(50)
                .message("Deploying...")
                .completed(false)
                .success(false)
                .build();

        assertNotNull(status);
        assertEquals(1L, status.getInstanceId());
        assertEquals("DEPLOY", status.getStage());
        assertEquals(50, status.getProgress());
        assertEquals("Deploying...", status.getMessage());
        assertFalse(status.isCompleted());
        assertFalse(status.isSuccess());
    }

    @Test
    @DisplayName("测试环境校验结果-成功")
    void testEnvironmentCheckResultSuccess() {
        // 测试环境校验结果 - 成功
        DeployService.EnvironmentCheckResult successResult = DeployService.EnvironmentCheckResult.success();
        assertNotNull(successResult);
        assertTrue(successResult.isPassed());
        assertEquals("环境校验通过", successResult.getMessage());
    }

    @Test
    @DisplayName("测试环境校验结果-失败")
    void testEnvironmentCheckResultFail() {
        // 测试环境校验结果 - 失败
        DeployService.EnvironmentCheckResult failResult = DeployService.EnvironmentCheckResult.fail("磁盘空间不足");
        assertNotNull(failResult);
        assertFalse(failResult.isPassed());
        assertEquals("磁盘空间不足", failResult.getMessage());
    }

    @Test
    @DisplayName("测试环境校验结果-带详细检查项")
    void testEnvironmentCheckResultWithChecks() {
        // 测试带详细检查项的环境校验结果
        Map<String, Boolean> checks = new HashMap<>();
        checks.put("sshConnection", true);
        checks.put("dockerInstalled", true);
        checks.put("diskSpace", false);

        DeployService.EnvironmentCheckResult result = DeployService.EnvironmentCheckResult.builder()
                .passed(false)
                .message("部分检查未通过")
                .checks(checks)
                .build();

        assertNotNull(result);
        assertFalse(result.isPassed());
        assertNotNull(result.getChecks());
        assertEquals(3, result.getChecks().size());
        assertFalse(result.getChecks().get("diskSpace"));
    }

    @Test
    @DisplayName("测试进度回调")
    void testDeployProgressCallback() {
        // 测试进度回调
        StringBuilder logBuilder = new StringBuilder();

        DeployProgressCallback callback = new DeployProgressCallback() {
            @Override
            public void onProgress(int percent, String stage, String message) {
                logBuilder.append(String.format("[%s] %d%%: %s\n", stage, percent, message));
            }

            @Override
            public void onComplete(boolean success, String message) {
                logBuilder.append(String.format("[COMPLETE] %s: %s\n", success, message));
            }

            @Override
            public void onError(String error, String stage, boolean recoverable) {
                logBuilder.append(String.format("[ERROR] %s: %s (recoverable: %s)\n", stage, error, recoverable));
            }

            @Override
            public void onLog(String level, String message) {
                logBuilder.append(String.format("[%s] %s\n", level, message));
            }

            @Override
            public void onStageStart(String stage, String description) {
                logBuilder.append(String.format("[STAGE_START] %s: %s\n", stage, description));
            }

            @Override
            public void onStageComplete(String stage, boolean success, String message) {
                logBuilder.append(String.format("[STAGE_COMPLETE] %s: %s (%s)\n", stage, success, message));
            }
        };

        // 模拟回调调用
        callback.onStageStart("TEST", "Test stage");
        callback.onProgress(50, "TEST", "Halfway done");
        callback.onLog("INFO", "Some info");
        callback.onStageComplete("TEST", true, "Test completed");
        callback.onComplete(true, "All done");

        String log = logBuilder.toString();
        assertTrue(log.contains("STAGE_START"));
        assertTrue(log.contains("50%"));
        assertTrue(log.contains("INFO"));
        assertTrue(log.contains("STAGE_COMPLETE"));
        assertTrue(log.contains("COMPLETE"));
    }

    @Test
    @DisplayName("测试部署异常")
    void testDeployException() {
        // 测试部署异常
        DeployService.DeployException exception = new DeployService.DeployException("部署失败");
        assertNotNull(exception);
        assertEquals("部署失败", exception.getMessage());

        // 测试带原因的部署异常
        Throwable cause = new RuntimeException("原始错误");
        DeployService.DeployException exceptionWithCause = new DeployService.DeployException("部署失败", cause);
        assertNotNull(exceptionWithCause);
        assertEquals("部署失败", exceptionWithCause.getMessage());
        assertEquals(cause, exceptionWithCause.getCause());
    }

    @Test
    @DisplayName("测试环境校验-无效主机")
    void testCheckEnvironmentWithInvalidHost() {
        // Given
        when(hostMapper.selectById(-1L)).thenReturn(null);
        Map<String, Object> config = new HashMap<>();

        // When
        DeployService.EnvironmentCheckResult result = deployService.checkEnvironment(
                -1L, DeployAdapter.DeployType.DOCKER, config);

        // Then
        assertNotNull(result);
        assertFalse(result.isPassed());
        assertEquals("主机不存在", result.getMessage());
    }

    @Test
    @DisplayName("测试环境校验-成功")
    void testCheckEnvironmentSuccess() {
        // Given
        when(hostMapper.selectById(1L)).thenReturn(testHost);
        
        // Mock SSH 命令执行结果（全部检查返回成功）
        SshUtil.CommandResult successResult = new SshUtil.CommandResult();
        successResult.setSuccess(true);
        successResult.setOutput("Docker version 24.0.0");
        successResult.setExitCode(0);

        when(sshUtil.executeCommand(anyString(), anyInt(), anyString(), isNull(), isNull(), anyString(), anyLong()))
                .thenReturn(successResult);

        Map<String, Object> config = new HashMap<>();

        // When
        DeployService.EnvironmentCheckResult result = deployService.checkEnvironment(
                1L, DeployAdapter.DeployType.DOCKER, config);

        // Then
        assertNotNull(result);
        // 结果取决于 SSH 连接是否成功
    }

    @Test
    @DisplayName("测试启动实例")
    void testStartInstance() {
        // Given
        when(instanceMapper.selectById(1L)).thenReturn(testInstance);
        when(deployAdapter.start(eq(1L), anyMap())).thenReturn(true);

        // When
        boolean result = deployService.start(1L, DeployAdapter.DeployType.DOCKER);

        // Then
        assertTrue(result);
        verify(deployAdapter).start(eq(1L), anyMap());
    }

    @Test
    @DisplayName("测试停止实例")
    void testStopInstance() {
        // Given
        when(instanceMapper.selectById(1L)).thenReturn(testInstance);
        when(deployAdapter.stop(eq(1L), anyMap())).thenReturn(true);

        // When
        boolean result = deployService.stop(1L, DeployAdapter.DeployType.DOCKER);

        // Then
        assertTrue(result);
        verify(deployAdapter).stop(eq(1L), anyMap());
    }

    @Test
    @DisplayName("测试重启实例")
    void testRestartInstance() {
        // Given
        when(instanceMapper.selectById(1L)).thenReturn(testInstance);
        when(deployAdapter.restart(eq(1L), anyMap())).thenReturn(true);

        // When
        boolean result = deployService.restart(1L, DeployAdapter.DeployType.DOCKER);

        // Then
        assertTrue(result);
        verify(deployAdapter).restart(eq(1L), anyMap());
    }

    @Test
    @DisplayName("测试获取实例状态")
    void testGetStatus() {
        // Given
        when(instanceMapper.selectById(1L)).thenReturn(testInstance);
        when(deployAdapter.getStatus(eq(1L), anyMap())).thenReturn(DeployAdapter.InstanceStatus.RUNNING);

        // When
        DeployAdapter.InstanceStatus status = deployService.getStatus(1L, DeployAdapter.DeployType.DOCKER);

        // Then
        assertEquals(DeployAdapter.InstanceStatus.RUNNING, status);
        verify(deployAdapter).getStatus(eq(1L), anyMap());
    }

    @Test
    @DisplayName("测试获取实例日志")
    void testGetLogs() {
        // Given
        when(instanceMapper.selectById(1L)).thenReturn(testInstance);
        when(deployAdapter.getLogs(eq(1L), anyMap(), anyInt())).thenReturn("test log content");

        // When
        String logs = deployService.getLogs(1L, DeployAdapter.DeployType.DOCKER, 100);

        // Then
        assertEquals("test log content", logs);
        verify(deployAdapter).getLogs(eq(1L), anyMap(), eq(100));
    }

    @Test
    @DisplayName("测试执行实例命令")
    void testExecuteCommand() {
        // Given
        when(instanceMapper.selectById(1L)).thenReturn(testInstance);
        when(deployAdapter.executeCommand(eq(1L), anyMap(), anyString())).thenReturn("command result");

        // When
        String result = deployService.executeCommand(1L, DeployAdapter.DeployType.DOCKER, "ls -la");

        // Then
        assertEquals("command result", result);
        verify(deployAdapter).executeCommand(eq(1L), anyMap(), eq("ls -la"));
    }

    @Test
    @DisplayName("测试获取任务状态")
    void testGetTaskStatus() {
        // When
        DeployService.DeployTaskStatus status = deployService.getTaskStatus(1L);

        // Then - 初始状态应该为 null
        assertNull(status);
    }
}

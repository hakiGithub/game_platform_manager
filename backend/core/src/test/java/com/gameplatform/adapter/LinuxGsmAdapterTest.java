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

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * LinuxGSM适配器测试类
 * 使用 Mockito 进行单元测试
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
class LinuxGsmAdapterTest {

    @Mock
    private SshUtil sshUtil;

    @Mock
    private HostMapper hostMapper;

    @Mock
    private GameInstanceMapper instanceMapper;

    @Mock
    private DeploymentAccess deployAccess;

    private LinuxGsmAdapter linuxGsmAdapter;

    @BeforeEach
    void setUp() {
        linuxGsmAdapter = new LinuxGsmAdapter();
        // 手动注入依赖
        injectField(linuxGsmAdapter, "sshUtil", sshUtil);
        injectField(linuxGsmAdapter, "hostMapper", hostMapper);
        injectField(linuxGsmAdapter, "instanceMapper", instanceMapper);
        injectField(linuxGsmAdapter, "deployAccess", deployAccess);
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
        assertEquals(DeployAdapter.DeployType.LINUX_GSM, linuxGsmAdapter.getDeployType());
    }

    @Test
    void testValidateEnvironmentWithInvalidHost() {
        // Mock: 主机不存在
        when(hostMapper.selectById(-1L)).thenReturn(null);

        // 测试无效的主机ID
        Map<String, Object> config = new HashMap<>();
        boolean result = linuxGsmAdapter.validateEnvironment(-1L, config);
        assertFalse(result);

        // 验证 hostMapper 被调用
        verify(hostMapper, times(1)).selectById(-1L);
    }

    @Test
    void testDeployTypeEnum() {
        // 测试部署类型枚举
        assertEquals("linuxgsm", DeployAdapter.DeployType.LINUX_GSM.getCode());
        assertEquals("LinuxGSM部署", DeployAdapter.DeployType.LINUX_GSM.getDescription());

        // 测试从代码获取枚举
        DeployAdapter.DeployType type = DeployAdapter.DeployType.fromCode("linuxgsm");
        assertEquals(DeployAdapter.DeployType.LINUX_GSM, type);

        // 测试无效的代码
        assertNull(DeployAdapter.DeployType.fromCode("invalid"));
    }

    @Test
    void testInstanceStatusEnum() {
        // 测试实例状态枚举
        assertEquals(0, DeployAdapter.InstanceStatus.STOPPED.getCode());
        assertEquals("已停止", DeployAdapter.InstanceStatus.STOPPED.getDescription());

        assertEquals(1, DeployAdapter.InstanceStatus.RUNNING.getCode());
        assertEquals("运行中", DeployAdapter.InstanceStatus.RUNNING.getDescription());

        // 测试从代码获取枚举
        DeployAdapter.InstanceStatus status = DeployAdapter.InstanceStatus.fromCode(1);
        assertEquals(DeployAdapter.InstanceStatus.RUNNING, status);

        // 测试无效的代码（ADR-0005：fromCode 未知码返回 null，由调用方决定回退语义）
        DeployAdapter.InstanceStatus invalidStatus = DeployAdapter.InstanceStatus.fromCode(999);
        assertNull(invalidStatus);
    }

    @Test
    void testDeployProgressCallbackNoOp() {
        // 测试空回调实现
        DeployProgressCallback callback = DeployProgressCallback.NO_OP;

        // 这些方法不应该抛出异常
        assertDoesNotThrow(() -> {
            callback.onProgress(50, "TEST", "Test message");
            callback.onComplete(true, "Test complete");
            callback.onError("Test error", "TEST", false);
            callback.onLog("INFO", "Test log");
            callback.onStageStart("TEST", "Test stage");
            callback.onStageComplete("TEST", true, "Stage complete");
        });
    }
}

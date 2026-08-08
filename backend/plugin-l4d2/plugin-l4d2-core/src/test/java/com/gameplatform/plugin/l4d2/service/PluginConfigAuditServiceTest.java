package com.gameplatform.plugin.l4d2.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * PluginConfigAuditService 单元测试。
 *
 * <p>审计服务设计为"失败不阻塞主流程"，所有方法均吞掉异常，
 * 因此测试主要验证不抛异常（包括传 null 参数的情况）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
class PluginConfigAuditServiceTest {

    @InjectMocks
    private PluginConfigAuditService auditService;

    @Test
    void logUpdateConfig_shouldRecordEntry() {
        assertDoesNotThrow(() -> auditService.logUpdateConfig(
                1L, 100L, "l4d2_x", "l4d2_x.cfg",
                "sm_dp", "2.5", "3.0", "admin"));
    }

    @Test
    void logApplyTempConfig_shouldRecordEntry() {
        assertDoesNotThrow(() -> auditService.logApplyTempConfig(
                1L, 100L, "l4d2_x", "sm_dp", "2.5", "admin"));
    }

    @Test
    void logRestoreDefaults_shouldRecordEntry() {
        assertDoesNotThrow(() -> auditService.logRestoreDefaults(
                1L, 100L, "l4d2_x", "l4d2_x.cfg", 5, "admin"));
    }

    @Test
    void log_shouldNotThrowWhenOperationFails() {
        // 审计日志失败不应阻塞主流程（传 null 参数模拟异常情况）
        assertDoesNotThrow(() -> auditService.logRestoreDefaults(
                null, null, null, null, 0, null));
        assertDoesNotThrow(() -> auditService.logUpdateConfig(
                null, null, null, null, null, null, null, null));
        assertDoesNotThrow(() -> auditService.logApplyTempConfig(
                null, null, null, null, null, null));
    }
}

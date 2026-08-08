package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.dto.RestartConfigUpdateDTO;
import com.gameplatform.plugin.l4d2.dto.RestartDTO;
import com.gameplatform.plugin.l4d2.enums.RestartMode;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.service.RestartService;
import com.gameplatform.plugin.l4d2.vo.RestartConfigVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RestartController 单元测试（对齐 plan §6.2.5）。
 *
 * <p>直接实例化 Controller 并 mock RestartService，验证参数解析与端点委派。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RestartControllerTest {

    @Mock
    private RestartService restartService;

    private RestartController controller;

    @BeforeEach
    void setUp() {
        controller = new RestartController(restartService);
        // 默认 service 启用
        when(restartService.isEnabled()).thenReturn(true);
    }

    // ============================================================
    // restart_default_uses_auto_mode
    // ============================================================
    @Test
    void restart_default_uses_auto_mode() {
        RestartDTO dto = new RestartDTO();
        dto.setInstanceId(1L);
        // mode 留空 → AUTO

        Result<Void> result = controller.restart(dto);

        assertNotNull(result);
        ArgumentCaptor<RestartMode> modeCaptor = ArgumentCaptor.forClass(RestartMode.class);
        verify(restartService).restart(eq(1L), modeCaptor.capture());
        assertEquals(RestartMode.AUTO, modeCaptor.getValue(),
                "mode 省略时应使用 AUTO");
    }

    // ============================================================
    // restart_explicit_command_mode_passes_through
    // ============================================================
    @Test
    void restart_explicit_command_mode_passes_through() {
        RestartDTO dto = new RestartDTO();
        dto.setInstanceId(7L);
        dto.setMode("COMMAND");

        Result<Void> result = controller.restart(dto);

        assertNotNull(result);
        verify(restartService).restart(eq(7L), eq(RestartMode.COMMAND));
    }

    // ============================================================
    // restart_invalid_mode_returns_fail
    // ============================================================
    @Test
    void restart_invalid_mode_returns_fail() {
        RestartDTO dto = new RestartDTO();
        dto.setInstanceId(1L);
        dto.setMode("UNKNOWN");

        Result<Void> result = controller.restart(dto);

        assertNotNull(result);
        // 失败响应码非 200
        assertTrue(result.getCode() != 200, "无效 mode 应返回失败");
        // 不应调用 service.restart
        verify(restartService, never()).restart(any(), any());
    }

    // ============================================================
    // restart_rcon_endpoint_calls_rcon_mode
    // ============================================================
    @Test
    void restart_rcon_endpoint_calls_rcon_mode() {
        Result<Void> result = controller.restartByRcon(42L);

        assertNotNull(result);
        verify(restartService).restartByRcon(eq(42L));
    }

    // ============================================================
    // restart_command_endpoint_calls_command_mode
    // ============================================================
    @Test
    void restart_command_endpoint_calls_command_mode() {
        Result<Void> result = controller.restartByCommand(42L);

        assertNotNull(result);
        verify(restartService).restartByCommand(eq(42L));
    }

    // ============================================================
    // restart_rcon_failure_returns_fail_message
    // ============================================================
    @Test
    void restart_rcon_failure_returns_fail_message() {
        L4D2PluginException ex = new L4D2PluginException(L4D2PluginException.RCON, "conn refused");
        doThrow(ex).when(restartService).restartByRcon(42L);

        Result<Void> result = controller.restartByRcon(42L);

        assertNotNull(result);
        assertTrue(result.getCode() != 200);
        assertTrue(result.getMessage().contains("conn refused"));
    }

    // ============================================================
    // restart_disabled_returns_fail_message
    // ============================================================
    @Test
    void restart_disabled_returns_fail_message() {
        doThrow(new IllegalStateException("重启功能已禁用"))
                .when(restartService).restartByRcon(42L);

        Result<Void> result = controller.restartByRcon(42L);

        assertNotNull(result);
        assertTrue(result.getCode() != 200);
        assertTrue(result.getMessage().contains("禁用"));
    }

    // ============================================================
    // get_config_returns_current_settings
    // ============================================================
    @Test
    void get_config_returns_current_settings() {
        RestartConfigVO vo = new RestartConfigVO();
        vo.setByRcon(true);
        vo.setContainerName("l4d2-prod");
        vo.setCustomCmd("echo hi");
        vo.setEnabled(true);
        vo.setAvailableModes(List.of("AUTO", "RCON", "COMMAND"));
        when(restartService.getConfig()).thenReturn(vo);

        Result<RestartConfigVO> result = controller.getConfig();

        assertNotNull(result);
        assertNotNull(result.getData());
        RestartConfigVO data = result.getData();
        assertTrue(data.getByRcon());
        assertEquals("l4d2-prod", data.getContainerName());
        assertEquals("echo hi", data.getCustomCmd());
        assertTrue(data.getAvailableModes().contains("COMMAND"));
    }

    // ============================================================
    // set_config_updates_byRcon
    // ============================================================
    @Test
    void set_config_updates_byRcon() {
        RestartConfigUpdateDTO dto = new RestartConfigUpdateDTO();
        dto.setByRcon(true);
        dto.setContainerName("new-ctn");
        dto.setCustomCmd("docker compose restart");

        Result<Void> result = controller.updateConfig(dto);

        assertNotNull(result);
        ArgumentCaptor<RestartConfigUpdateDTO> captor = ArgumentCaptor.forClass(RestartConfigUpdateDTO.class);
        verify(restartService).setConfig(captor.capture());
        RestartConfigUpdateDTO captured = captor.getValue();
        assertTrue(captured.getByRcon());
        assertEquals("new-ctn", captured.getContainerName());
        assertEquals("docker compose restart", captured.getCustomCmd());
    }
}

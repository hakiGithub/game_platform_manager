package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.dto.RestartConfigUpdateDTO;
import com.gameplatform.plugin.l4d2.enums.RestartMode;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.vo.RestartConfigVO;
import com.gameplatform.plugin.service.FileAccessService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RestartService 单元测试。
 *
 * <p>覆盖 AUTO/RCON/COMMAND 三种模式选择、自定义命令、docker restart 回退、
 * 非零 exit code、超时与禁用开关等场景。命令执行通过 {@link FileAccessService}
 * 委托到远程主机，测试中对其进行 mock。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RestartServiceTest {

    @Mock
    private InstanceQueryService instanceQueryService;

    @Mock
    private FileAccessService fileAccessService;

    @Mock
    private RconService rconService;

    private L4D2Config config;

    private RestartService service;

    @BeforeEach
    void setUp() {
        config = new L4D2Config();
        config.getRestart().setEnabled(true);
        config.getRestart().setByRcon(false);
        config.getRestart().setContainerName("l4d2");
        config.getRestart().setCustomCmd("");
        config.getRestart().setCommandTimeoutMs(30_000L);
        service = new RestartService(instanceQueryService, fileAccessService, rconService, config);

        // 默认实例
        InstanceVO instance = new InstanceVO();
        instance.setId(1L);
        instance.setHostId(10L);
        Map<String, Object> configInfo = new HashMap<>();
        configInfo.put("rconPort", 27020);
        configInfo.put("rconPassword", "test-pwd");
        configInfo.put("containerName", "l4d2-prod");
        instance.setConfigInfo(configInfo);
        when(instanceQueryService.getInstanceById(1L)).thenReturn(instance);
    }

    // ============================================================
    // restart_auto_uses_rcon_when_byRcon_true
    // ============================================================
    @Test
    void restart_auto_uses_rcon_when_byRcon_true() {
        config.getRestart().setByRcon(true);
        when(rconService.executeCommand(anyLong(), eq("_restart")))
                .thenReturn("");

        service.restart(1L, RestartMode.AUTO);

        verify(rconService).executeCommand(eq(1L), eq("_restart"));
        verify(fileAccessService, never()).executeCommand(anyLong(), anyString(), anyLong());
    }

    // ============================================================
    // restart_auto_uses_command_when_byRcon_false
    // ============================================================
    @Test
    void restart_auto_uses_command_when_byRcon_false() {
        // byRcon 默认 false，AUTO 模式应走命令路径
        when(fileAccessService.executeCommand(eq(10L), anyString(), eq(30_000L)))
                .thenReturn(successResult());

        service.restart(1L, RestartMode.AUTO);

        verify(fileAccessService).executeCommand(eq(10L), anyString(), eq(30_000L));
        verify(rconService, never()).executeCommand(anyLong(), anyString());
    }

    // ============================================================
    // restart_rcon_mode_calls_execute_command_with_restart
    // ============================================================
    @Test
    void restart_rcon_mode_calls_execute_command_with_restart() {
        when(rconService.executeCommand(anyLong(), eq("_restart")))
                .thenReturn("");

        service.restart(1L, RestartMode.RCON);

        // 验证 RCON 命令固定为 "_restart"
        verify(rconService).executeCommand(eq(1L), eq("_restart"));
        verify(fileAccessService, never()).executeCommand(anyLong(), anyString(), anyLong());
    }

    // ============================================================
    // restart_command_mode_uses_custom_cmd_when_set
    // ============================================================
    @Test
    void restart_command_mode_uses_custom_cmd_when_set() {
        config.getRestart().setCustomCmd("systemctl restart l4d2");
        when(fileAccessService.executeCommand(eq(10L), anyString(), eq(30_000L)))
                .thenAnswer(inv -> {
                    String cmd = inv.getArgument(1);
                    assertTrue(cmd.contains("systemctl restart l4d2"),
                            "命令应包含 customCmd: " + cmd);
                    assertFalse(cmd.contains("docker restart"),
                            "设置了 customCmd 时不应回退到 docker restart: " + cmd);
                    return successResult();
                });

        service.restart(1L, RestartMode.COMMAND);

        verify(fileAccessService).executeCommand(eq(10L), anyString(), eq(30_000L));
    }

    // ============================================================
    // restart_command_mode_uses_docker_restart_when_no_custom_cmd
    // ============================================================
    @Test
    void restart_command_mode_uses_docker_restart_when_no_custom_cmd() {
        // 默认 customCmd 为空，应回退到 docker restart
        when(fileAccessService.executeCommand(eq(10L), anyString(), eq(30_000L)))
                .thenAnswer(inv -> {
                    String cmd = inv.getArgument(1);
                    assertTrue(cmd.contains("docker restart"),
                            "命令应包含 docker restart: " + cmd);
                    assertTrue(cmd.contains("l4d2-prod"),
                            "命令应使用实例 containerName: " + cmd);
                    return successResult();
                });

        service.restart(1L, RestartMode.COMMAND);

        verify(fileAccessService).executeCommand(eq(10L), anyString(), eq(30_000L));
    }

    // ============================================================
    // restart_command_mode_uses_runtime_container_id_when_available
    // ============================================================
    @Test
    void restart_command_mode_uses_runtime_container_id_when_available() {
        InstanceVO instance = new InstanceVO();
        instance.setId(3L);
        instance.setHostId(30L);
        Map<String, Object> configInfo = new HashMap<>();
        configInfo.put("CONTAINER_NAME", "l4d2-ctn");
        instance.setConfigInfo(configInfo);
        Map<String, Object> runtimeMetadata = new HashMap<>();
        runtimeMetadata.put("containerId", "abc123def456");
        instance.setRuntimeMetadata(runtimeMetadata);
        when(instanceQueryService.getInstanceById(3L)).thenReturn(instance);

        when(fileAccessService.executeCommand(eq(30L), anyString(), eq(30_000L)))
                .thenAnswer(inv -> {
                    String cmd = inv.getArgument(1);
                    assertTrue(cmd.contains("abc123def456"),
                            "命令应优先使用 runtime_metadata.containerId: " + cmd);
                    assertFalse(cmd.contains("l4d2-ctn"),
                            "存在 containerId 时不应再使用 configInfo 容器名: " + cmd);
                    return successResult();
                });

        service.restart(3L, RestartMode.COMMAND);

        verify(fileAccessService).executeCommand(eq(30L), anyString(), eq(30_000L));
    }

    // ============================================================
    // restart_command_mode_uses_uppercase_container_name_key
    // ============================================================
    @Test
    void restart_command_mode_uses_uppercase_container_name_key() {
        InstanceVO instance = new InstanceVO();
        instance.setId(2L);
        instance.setHostId(20L);
        Map<String, Object> configInfo = new HashMap<>();
        configInfo.put("CONTAINER_NAME", "l4d2-ctn");
        instance.setConfigInfo(configInfo);
        when(instanceQueryService.getInstanceById(2L)).thenReturn(instance);

        when(fileAccessService.executeCommand(eq(20L), anyString(), eq(30_000L)))
                .thenAnswer(inv -> {
                    String cmd = inv.getArgument(1);
                    assertTrue(cmd.contains("l4d2-ctn"),
                            "命令应使用 CONTAINER_NAME 值: " + cmd);
                    return successResult();
                });

        service.restart(2L, RestartMode.COMMAND);

        verify(fileAccessService).executeCommand(eq(20L), anyString(), eq(30_000L));
    }

    // ============================================================
    // restart_command_mode_uses_default_container_name_when_instance_missing
    // ============================================================
    @Test
    void restart_command_mode_uses_default_container_name_when_instance_missing() {
        // 实例未配置 containerName → 回退到 config.restart.containerName
        InstanceVO instance = new InstanceVO();
        instance.setId(99L);
        instance.setHostId(99L);
        Map<String, Object> configInfo = new HashMap<>();
        configInfo.put("rconPort", 27020);
        configInfo.put("rconPassword", "test-pwd");
        instance.setConfigInfo(configInfo); // 无容器名
        when(instanceQueryService.getInstanceById(99L)).thenReturn(instance);

        config.getRestart().setContainerName("fallback-ctn");
        when(fileAccessService.executeCommand(eq(99L), anyString(), eq(30_000L)))
                .thenAnswer(inv -> {
                    String cmd = inv.getArgument(1);
                    assertTrue(cmd.contains("fallback-ctn"),
                            "应回退到 config.containerName: " + cmd);
                    return successResult();
                });

        service.restart(99L, RestartMode.COMMAND);

        verify(fileAccessService).executeCommand(eq(99L), anyString(), eq(30_000L));
    }

    // ============================================================
    // restart_command_mode_handles_nonzero_exit_code
    // ============================================================
    @Test
    void restart_command_mode_handles_nonzero_exit_code() {
        when(fileAccessService.executeCommand(eq(10L), anyString(), eq(30_000L)))
                .thenReturn(failResult(1, "Error: No such container"));

        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> service.restart(1L, RestartMode.COMMAND));

        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
        assertTrue(ex.getMessage().contains("exitCode=1"),
                "异常消息应包含 exitCode=1: " + ex.getMessage());
    }

    // ============================================================
    // restart_disabled_throws_exception
    // ============================================================
    @Test
    void restart_disabled_throws_exception() {
        config.getRestart().setEnabled(false);

        assertThrows(IllegalStateException.class, () -> service.restart(1L, RestartMode.AUTO));
        assertThrows(IllegalStateException.class, () -> service.restartByRcon(1L));
        assertThrows(IllegalStateException.class, () -> service.restartByCommand(1L));

        // 不应触发任何 RCON/命令操作
        verify(rconService, never()).executeCommand(anyLong(), anyString());
        verify(fileAccessService, never()).executeCommand(anyLong(), anyString(), anyLong());
    }

    // ============================================================
    // restart_rcon_failure_wrapped_as_l4d2_exception
    // ============================================================
    @Test
    void restart_rcon_failure_wrapped_as_l4d2_exception() {
        when(rconService.executeCommand(anyLong(), eq("_restart")))
                .thenThrow(new RuntimeException("connection refused"));

        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> service.restart(1L, RestartMode.RCON));

        assertEquals(L4D2PluginException.RCON, ex.getCode());
        assertTrue(ex.getMessage().contains("RCON 重启失败"));
        assertNotNull(ex.getCause());
    }

    // ============================================================
    // get_config_returns_current_settings
    // ============================================================
    @Test
    void get_config_returns_current_settings() {
        config.getRestart().setByRcon(true);
        config.getRestart().setContainerName("ctn-x");
        config.getRestart().setCustomCmd("echo restart");

        RestartConfigVO vo = service.getConfig();

        assertNotNull(vo);
        assertTrue(vo.getByRcon());
        assertEquals("ctn-x", vo.getContainerName());
        assertEquals("echo restart", vo.getCustomCmd());
        assertTrue(vo.getEnabled());
        assertNotNull(vo.getAvailableModes());
        assertTrue(vo.getAvailableModes().contains("AUTO"));
        assertTrue(vo.getAvailableModes().contains("RCON"));
        assertTrue(vo.getAvailableModes().contains("COMMAND"));
    }

    // ============================================================
    // set_config_updates_provided_fields_only
    // ============================================================
    @Test
    void set_config_updates_provided_fields_only() {
        // 初始：byRcon=false, containerName=l4d2, customCmd=""
        RestartConfigUpdateDTO dto = new RestartConfigUpdateDTO();
        dto.setByRcon(true);
        // containerName/customCmd 不传 → 保持原值

        service.setConfig(dto);

        assertTrue(config.getRestart().isByRcon());
        assertEquals("l4d2", config.getRestart().getContainerName());
        assertEquals("", config.getRestart().getCustomCmd());
    }

    // ============================================================
    // set_enabled_toggles_runtime_flag
    // ============================================================
    @Test
    void set_enabled_toggles_runtime_flag() {
        assertTrue(service.isEnabled());
        service.setEnabled(false);
        assertFalse(service.isEnabled());
        assertFalse(config.getRestart().isEnabled());
        service.setEnabled(true);
        assertTrue(service.isEnabled());
    }

    // ============================================================
    // restart_rcon_missing_password_throws_business_exception
    // ============================================================
    @Test
    void restart_rcon_missing_password_throws_business_exception() {
        InstanceVO instance = new InstanceVO();
        instance.setId(2L);
        instance.setHostId(2L);
        Map<String, Object> configInfo = new HashMap<>();
        configInfo.put("rconPort", 27020);
        // 不设置 rconPassword
        instance.setConfigInfo(configInfo);
        when(instanceQueryService.getInstanceById(2L)).thenReturn(instance);
        // 新签名下密码校验由 RconService 内部完成，模拟其抛出异常
        when(rconService.executeCommand(eq(2L), eq("_restart")))
                .thenThrow(new RuntimeException("RCON 密码未配置"));

        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> service.restartByRcon(2L));

        assertEquals(L4D2PluginException.RCON, ex.getCode());
        assertTrue(ex.getMessage().contains("RCON 密码未配置"));
    }

    // ============================================================
    // restart_auto_with_null_mode_treated_as_auto
    // ============================================================
    @Test
    void restart_auto_with_null_mode_treated_as_auto() {
        config.getRestart().setByRcon(true);
        when(rconService.executeCommand(anyLong(), eq("_restart")))
                .thenReturn("");

        // 传 null mode 应被当作 AUTO 处理
        service.restart(1L, null);

        verify(rconService).executeCommand(eq(1L), eq("_restart"));
    }

    // ===== 辅助方法 =====

    private FileAccessService.CommandResult successResult() {
        FileAccessService.CommandResult result = new FileAccessService.CommandResult();
        result.setSuccess(true);
        result.setExitCode(0);
        result.setOutput("");
        result.setError("");
        return result;
    }

    private FileAccessService.CommandResult failResult(int exitCode, String error) {
        FileAccessService.CommandResult result = new FileAccessService.CommandResult();
        result.setSuccess(false);
        result.setExitCode(exitCode);
        result.setOutput("");
        result.setError(error);
        return result;
    }
}

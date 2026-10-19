package com.gameplatform.adapter;

import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.hostaccess.CommandResult;
import com.gameplatform.hostaccess.RemoteHost;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.mapper.HostMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinuxGsmDockerAdapterTest {

    @Mock
    private RemoteHost remoteHost;

    @Mock
    private HostMapper hostMapper;

    @Mock
    private GameInstanceMapper instanceMapper;

    private LinuxGsmDockerAdapter adapter;
    private Host host;

    @BeforeEach
    void setUp() {
        adapter = new LinuxGsmDockerAdapter();
        injectField(adapter, "remoteHost", remoteHost);
        injectField(adapter, "hostMapper", hostMapper);
        injectField(adapter, "instanceMapper", instanceMapper);

        GameInstance instance = new GameInstance();
        instance.setId(1L);
        instance.setHostId(1L);
        instance.setDeployType("linuxgsm-docker");
        when(instanceMapper.selectById(1L)).thenReturn(instance);

        host = new Host();
        host.setId(1L);
        host.setIpAddress("192.168.1.10");
        host.setSshPort(22);
        host.setSshUser("u");
        when(hostMapper.selectById(1L)).thenReturn(host);
        when(remoteHost.exec(eq(host), contains("printf %s"), eq(10000L))).thenReturn(okResult("/home/u"));
        when(remoteHost.exec(eq(host), contains("docker compose version"), eq(10000L)))
                .thenReturn(okResult("Docker Compose version v2"));
    }

    @Test
    void uninstall_successRemovesQuotedWorkDir() {
        when(remoteHost.exec(eq(host), contains("-p 'lgsm1' down"), eq(120000L))).thenReturn(okResult(""));
        when(remoteHost.exec(eq(host), contains("rm -rf"), eq(60000L))).thenReturn(okResult(""));

        Map<String, Object> config = new HashMap<>();
        config.put("workDir", "/home/u/games/lgsm 1");

        UninstallResult result = adapter.uninstallWithResult(
                1L, config, DeployProgressCallback.NO_OP);

        assertEquals(UninstallResult.Status.SUCCESS, result.status());
        assertNull(result.residualPath());
        verify(remoteHost).exec(eq(host), contains("rm -rf '/home/u/games/lgsm 1'"), eq(60000L));
    }

    @Test
    void uninstall_cleanupFailureReturnsPartialSuccessAndResidualPath() {
        when(remoteHost.exec(eq(host), contains("-p 'lgsm1' down"), eq(120000L))).thenReturn(okResult(""));
        when(remoteHost.exec(eq(host), contains("rm -rf"), eq(60000L)))
                .thenReturn(failedResult(1, "rm: cannot remove 'data': Permission denied"));

        Map<String, Object> config = new HashMap<>();
        config.put("workDir", "/home/u/games/lgsm-1");

        UninstallResult result = adapter.uninstallWithResult(
                1L, config, DeployProgressCallback.NO_OP);

        assertEquals(UninstallResult.Status.PARTIAL_SUCCESS, result.status());
        assertEquals("/home/u/games/lgsm-1", result.residualPath());
        assertTrue(result.message().contains("Permission denied"));
        verify(remoteHost, never()).exec(eq(host), contains("sudo"), anyLong());
    }

    private void injectField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getSuperclass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject field: " + fieldName, e);
        }
    }

    private CommandResult okResult(String output) {
        return result(0, output, null);
    }

    private CommandResult failedResult(int exitCode, String error) {
        return result(exitCode, null, error);
    }

    private CommandResult result(int exitCode, String output, String error) {
        CommandResult result = new CommandResult();
        result.setSuccess(exitCode == 0);
        result.setExitCode(exitCode);
        result.setOutput(output);
        result.setError(error);
        return result;
    }
}

package com.gameplatform.service.impl;

import com.gameplatform.config.Steam302Properties;
import com.gameplatform.hostaccess.CommandResult;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.plugin.task.TaskService;
import com.gameplatform.steam302.Steam302HostsSync;
import com.gameplatform.util.SudoAwareSshRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Steam302 平台侧 hosts 时序契约。
 *
 * <p>真实环境已复现：运行中的 Steam302 CLI 会把宿主 /etc/hosts 覆盖回本机模板，
 * 因此平台必须停容器写入，再启动。start() 还必须覆盖“原本已停止”的场景，
 * 不能只写 hosts 而忘记拉起容器。</p>
 */
@ExtendWith(MockitoExtension.class)
class Steam302ServiceImplTest {

    @Mock
    private SudoAwareSshRunner runner;
    @Mock
    private Steam302HostsSync hostsSync;
    @Mock
    private TaskService taskService;
    @Mock
    private HostMapper hostMapper;

    private Steam302Properties props;
    private Steam302ServiceImpl service;

    @BeforeEach
    void setUp() {
        props = new Steam302Properties();
        props.setContainerName("steam302");
        props.setDataDir("/opt/steam302");
        service = new Steam302ServiceImpl(props, runner, hostsSync, taskService, hostMapper);
    }

    @Test
    @DisplayName("start 在容器原本停止时仍会写入 hosts 并重新启动容器")
    void start_shouldWriteHostsAndStartWhenContainerWasStopped() {
        when(runner.run(anyLong(), anyString(), anyLong()))
                .thenAnswer(invocation -> result(true, "Auto_Modify_Hosts = 0\n", ""));
        when(hostsSync.syncFromCaddyfile(anyLong())).thenReturn(12);

        service.start(1L);

        ArgumentCaptor<String> commands = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(runner, org.mockito.Mockito.atLeastOnce())
                .run(anyLong(), commands.capture(), anyLong());
        assertThat(commands.getAllValues()).anySatisfy(cmd ->
                assertThat(cmd).contains("docker start steam302"));
        org.mockito.Mockito.verify(hostsSync).syncFromCaddyfile(1L);
    }

    @Test
    @DisplayName("start 顺序为先停止容器、写 hosts、再启动容器")
    void start_shouldStopBeforeWritingHosts() {
        List<String> calls = new java.util.ArrayList<>();
        when(runner.run(anyLong(), anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    String command = invocation.getArgument(1, String.class);
                    calls.add(command);
                    if (command.contains("docker inspect")) {
                        return result(true, "true", "");
                    }
                    return result(true, "Auto_Modify_Hosts = 0\n", "");
                });
        when(hostsSync.syncFromCaddyfile(1L)).thenAnswer(invocation -> {
            calls.add("SYNC");
            return 5;
        });

        service.start(1L);

        int stopIndex = calls.indexOf("docker stop steam302");
        int syncIndex = calls.indexOf("SYNC");
        int startIndex = calls.indexOf("docker start steam302");
        assertThat(stopIndex).isGreaterThanOrEqualTo(0);
        assertThat(syncIndex).isGreaterThan(stopIndex);
        assertThat(startIndex).isGreaterThan(syncIndex);
    }

    private static CommandResult result(boolean success, String output, String error) {
        CommandResult r = new CommandResult();
        r.setSuccess(success);
        r.setExitCode(success ? 0 : 1);
        r.setOutput(output);
        r.setError(error);
        return r;
    }
}

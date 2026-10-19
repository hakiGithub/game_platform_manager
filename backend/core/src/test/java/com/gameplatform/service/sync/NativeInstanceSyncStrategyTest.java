package com.gameplatform.service.sync;

import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.hostaccess.CommandResult;
import com.gameplatform.hostaccess.RemoteHost;
import com.gameplatform.mapper.GameInstanceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NativeInstanceSyncStrategy 单元测试
 * 覆盖 pgrep 进程检测、状态对账、异常隔离
 *
 * <p>远程命令执行统一走 RemoteHost。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
class NativeInstanceSyncStrategyTest {

    @Mock
    private RemoteHost remoteHost;

    @Mock
    private GameInstanceMapper instanceMapper;

    private NativeInstanceSyncStrategy strategy;

    private Host host;
    private GameInstance instance;

    @BeforeEach
    void setUp() {
        strategy = new NativeInstanceSyncStrategy(remoteHost, instanceMapper);

        host = new Host();
        host.setId(1L);
        host.setHostName("test-host");
        host.setIpAddress("192.168.1.100");
        host.setSshPort(22);
        host.setSshUser("root");
        // 不设置密码和私钥，getDecryptedPassword/PrivateKey 返回 null

        instance = new GameInstance();
        instance.setId(100L);
        instance.setInstanceName("mc-server");
        instance.setHostId(1L);
        instance.setDeployType("native");
        instance.setRunStatus(0);
        instance.setStartCommand("./start.sh -game left4dead2");
    }

    private CommandResult mockResult(int exitCode, String output) {
        CommandResult result = new CommandResult();
        result.setExitCode(exitCode);
        result.setOutput(output);
        result.setSuccess(exitCode == 0);
        return result;
    }

    @Test
    void pgrepFound_processRunning_updatesToRunning() {
        when(remoteHost.exec(eq(host), contains("pgrep"), eq(10_000L)))
                .thenReturn(mockResult(0, "12345"));
        when(instanceMapper.updateById(any())).thenReturn(1);

        strategy.syncHost(host, List.of(instance));

        assertThat(instance.getRunStatus()).isEqualTo(1);
        verify(instanceMapper).updateById(any());
    }

    @Test
    void pgrepNotFound_processNotRunning_updatesToStopped() {
        when(remoteHost.exec(any(Host.class), contains("pgrep"), eq(10_000L)))
                .thenReturn(mockResult(1, ""));
        when(instanceMapper.updateById(any())).thenReturn(1);

        instance.setRunStatus(1); // 当前认为运行中
        strategy.syncHost(host, List.of(instance));

        assertThat(instance.getRunStatus()).isEqualTo(0);
        assertThat(instance.getRemark()).contains("进程未运行");
    }

    @Test
    void pgrepCommandException_skipsInstance() {
        when(remoteHost.exec(any(Host.class), anyString(), eq(10_000L)))
                .thenThrow(new RuntimeException("SSH timeout"));

        strategy.syncHost(host, List.of(instance));

        verify(instanceMapper, never()).updateById(any());
    }

    @Test
    void pgrepUnexpectedExitCode_keepsDatabaseStatus() {
        when(remoteHost.exec(any(Host.class), anyString(), eq(10_000L)))
                .thenReturn(mockResult(2, "pgrep: permission denied"));

        instance.setRunStatus(1);
        strategy.syncHost(host, List.of(instance));

        assertThat(instance.getRunStatus()).isEqualTo(1);
        verify(instanceMapper, never()).updateById(any());
    }

    @Test
    void startCommandEmpty_skipsInstance() {
        instance.setStartCommand("");

        strategy.syncHost(host, List.of(instance));

        verifyNoInteractions(remoteHost);
        verify(instanceMapper, never()).updateById(any());
    }

    @Test
    void startCommandNull_skipsInstance() {
        instance.setStartCommand(null);

        strategy.syncHost(host, List.of(instance));

        verifyNoInteractions(remoteHost);
    }

    @Test
    void statusUnchanged_noUpdate() {
        when(remoteHost.exec(any(Host.class), contains("pgrep"), eq(10_000L)))
                .thenReturn(mockResult(0, "12345"));

        instance.setRunStatus(1); // 当前运行中，pgrep 也找到进程
        strategy.syncHost(host, List.of(instance));

        verify(instanceMapper, never()).updateById(any());
    }

    @Test
    void startCommandWithComplexEscaping_parsesGameParam() {
        instance.setStartCommand("bash -lc 'cd /opt/server && ./srcds_run -game left4dead2 +map c1m1_hotel'");

        when(remoteHost.exec(any(Host.class), contains("left4dead2"), eq(10_000L)))
                .thenReturn(mockResult(0, "12345"));
        when(instanceMapper.updateById(any())).thenReturn(1);

        instance.setRunStatus(0);
        strategy.syncHost(host, List.of(instance));

        assertThat(instance.getRunStatus()).isEqualTo(1);
    }

    @Test
    void startCommandWithoutGameParam_usesExecutableName() {
        // 无 -game 参数，应取可执行文件名作为关键字
        instance.setStartCommand("/opt/minecraft/server.jar nogui");

        when(remoteHost.exec(any(Host.class), contains("server.jar"), eq(10_000L)))
                .thenReturn(mockResult(0, "12345"));
        when(instanceMapper.updateById(any())).thenReturn(1);

        instance.setRunStatus(0);
        strategy.syncHost(host, List.of(instance));

        assertThat(instance.getRunStatus()).isEqualTo(1);
    }

    @Test
    void emptyInstanceList_doesNothing() {
        strategy.syncHost(host, List.of());

        verifyNoInteractions(remoteHost);
        verify(instanceMapper, never()).updateById(any());
    }

    @Test
    void nullInstanceList_doesNothing() {
        strategy.syncHost(host, null);

        verifyNoInteractions(remoteHost);
    }

    @Test
    void singleInstanceException_doesNotAffectOthers() {
        // badInstance 的 startCommand 为 null，会跳过
        GameInstance badInstance = new GameInstance();
        badInstance.setId(99L);
        badInstance.setDeployType("native");
        badInstance.setRunStatus(1);
        badInstance.setStartCommand(null);

        GameInstance goodInstance = new GameInstance();
        goodInstance.setId(100L);
        goodInstance.setDeployType("native");
        goodInstance.setRunStatus(0);
        goodInstance.setStartCommand("./start.sh -game left4dead2");

        when(remoteHost.exec(any(Host.class), contains("left4dead2"), eq(10_000L)))
                .thenReturn(mockResult(0, "12345"));
        when(instanceMapper.updateById(any())).thenReturn(1);

        strategy.syncHost(host, List.of(badInstance, goodInstance));

        assertThat(goodInstance.getRunStatus()).isEqualTo(1);
    }
}

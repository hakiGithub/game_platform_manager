package com.gameplatform.util;

import com.gameplatform.hostaccess.CommandResult;
import com.gameplatform.hostaccess.RemoteHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SudoAwareSshRunner 的委托契约。
 *
 * <p>提权与文件覆盖命令形态由 {@link RemoteHost} 自身测试锁定，
 * 本测试只防止适配层重新长出第二套实现。</p>
 */
@ExtendWith(MockitoExtension.class)
class SudoAwareSshRunnerCommandShapeTest {

    @Mock
    private RemoteHost remoteHost;

    @Test
    @DisplayName("writeFilePrivileged 委托 RemoteHost")
    void writeFilePrivileged_delegatesToRemoteHost() {
        SudoAwareSshRunner runner = new SudoAwareSshRunner(remoteHost);
        runner.writeFilePrivileged(1L, "/etc/hosts", "127.0.0.1 example.com #S302\n", 30_000);

        verify(remoteHost).writeFilePrivileged(
                1L, "/etc/hosts", "127.0.0.1 example.com #S302\n", 30_000);
    }

    @Test
    @DisplayName("runPrivileged 委托 RemoteHost")
    void runPrivileged_delegatesToRemoteHost() {
        CommandResult expected = success();
        when(remoteHost.execPrivileged(1L, "printf ok > /etc/hosts", 30_000)).thenReturn(expected);

        SudoAwareSshRunner runner = new SudoAwareSshRunner(remoteHost);

        org.junit.jupiter.api.Assertions.assertSame(
                expected, runner.runPrivileged(1L, "printf ok > /etc/hosts", 30_000));
        verify(remoteHost).execPrivileged(1L, "printf ok > /etc/hosts", 30_000);
    }

    private static CommandResult success() {
        CommandResult result = new CommandResult();
        result.setSuccess(true);
        result.setExitCode(0);
        result.setOutput("");
        result.setError("");
        return result;
    }
}

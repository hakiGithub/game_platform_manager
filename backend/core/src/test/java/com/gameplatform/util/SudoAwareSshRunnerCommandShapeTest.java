package com.gameplatform.util;

import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.deploy.HostCredentials;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 远程提权写入的命令形态契约。
 *
 * <p>这些断言防止两个已经在 WSL + /etc/hosts 绑定挂载上真实出现过的回归：
 * 用 mv 覆盖挂载点必然 Resource busy；sudo sh -c 只回传最后一条命令状态。</p>
 */
@ExtendWith(MockitoExtension.class)
class SudoAwareSshRunnerCommandShapeTest {

    @Mock
    private SshUtil sshUtil;
    @Mock
    private DeploymentAccess deployAccess;

    @Test
    @DisplayName("writeFilePrivileged 用 tee 覆盖并做 sha256 回读校验，不使用 mv/cp")
    void writeFilePrivileged_shouldUseCopyAndVerifyHash() {
        when(deployAccess.credentials(1L))
                .thenReturn(new HostCredentials("192.168.111.253", 22, "haki", null, "secret"));
        when(sshUtil.executeCommand(anyString(), any(Integer.class), anyString(),
                any(), anyString(), anyString(), anyLong()))
                .thenReturn(success());

        SudoAwareSshRunner runner = new SudoAwareSshRunner(sshUtil, deployAccess);
        runner.writeFilePrivileged(1L, "/etc/hosts", "127.0.0.1 example.com #S302\n", 30_000);

        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(sshUtil, org.mockito.Mockito.atLeastOnce())
                .executeCommand(anyString(), any(Integer.class), anyString(),
                        any(), anyString(), command.capture(), anyLong());
        assertThat(command.getAllValues()).anySatisfy(cmd -> {
            assertThat(cmd).contains("tee /etc/hosts <");
            assertThat(cmd).contains("> /dev/null");
            assertThat(cmd).contains(".platform-write.tmp");
            assertThat(cmd).contains("sha256sum");
            assertThat(cmd).doesNotContain("mv ");
            assertThat(cmd).doesNotContain("cp ");
        });
    }

    @Test
    @DisplayName("非 root 提权包装显式回传原命令退出码")
    void runPrivileged_shouldPreserveExitCodeInSudoWrapper() {
        when(deployAccess.credentials(1L))
                .thenReturn(new HostCredentials("192.168.111.253", 22, "haki", null, "secret"));
        when(sshUtil.executeCommand(anyString(), any(Integer.class), anyString(),
                any(), anyString(), anyString(), anyLong()))
                .thenReturn(success());

        SudoAwareSshRunner runner = new SudoAwareSshRunner(sshUtil, deployAccess);
        runner.runPrivileged(1L, "printf ok > /etc/hosts", 30_000);

        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(sshUtil, org.mockito.Mockito.atLeastOnce())
                .executeCommand(anyString(), any(Integer.class), anyString(),
                        any(), anyString(), command.capture(), anyLong());
        assertThat(command.getAllValues()).anySatisfy(cmd -> {
            assertThat(cmd).contains("sudo -S -p '' sh -c");
            assertThat(cmd).contains("; exit $?");
        });
    }

    private static SshUtil.CommandResult success() {
        SshUtil.CommandResult result = new SshUtil.CommandResult();
        result.setSuccess(true);
        result.setExitCode(0);
        result.setOutput("");
        result.setError("");
        return result;
    }
}

package com.gameplatform.hostaccess;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.deploy.HostCredentials;
import com.gameplatform.entity.Host;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RemoteHostTest {

    @Mock
    private DeploymentAccess deploymentAccess;

    @Mock
    private HostAccessTransport transport;

    private RemoteHost remoteHost;
    private Host host;

    @BeforeEach
    void setUp() {
        remoteHost = new RemoteHost(deploymentAccess, transport);
        host = new Host();
        host.setId(1L);
    }

    @Test
    @DisplayName("PASSWORD_IF_PRESENT 有密码时丢弃私钥，无密码时保留私钥")
    void passwordPreference_onlyDropsKeyWhenPasswordExists() {
        HostCredentials keyOnly = credentials(null, "key");
        when(deploymentAccess.credentials(host)).thenReturn(keyOnly);

        remoteHost.exec(host, "id", 1000,
                RemoteHost.AuthenticationPreference.PASSWORD_IF_PRESENT);

        verify(transport).execute(eq(keyOnly), eq("id"), eq(1000L));

        HostCredentials withPassword = credentials("pw", "key");
        when(deploymentAccess.credentials(host)).thenReturn(withPassword);

        remoteHost.exec(host, "id", 1000,
                RemoteHost.AuthenticationPreference.PASSWORD_IF_PRESENT);

        ArgumentCaptor<HostCredentials> captured = ArgumentCaptor.forClass(HostCredentials.class);
        verify(transport, org.mockito.Mockito.times(2))
                .execute(captured.capture(), eq("id"), eq(1000L));
        assertThat(captured.getAllValues().get(1).privateKey()).isNull();
        assertThat(captured.getAllValues().get(1).password()).isEqualTo("pw");
    }

    @Test
    @DisplayName("默认认证偏好保留私钥和密码，不修改解析后的凭据")
    void defaultPreference_preservesResolvedCredentials() {
        HostCredentials credentials = credentials("pw", "key");
        when(deploymentAccess.credentials(host)).thenReturn(credentials);

        remoteHost.exec(host, "id", 1000);

        verify(transport).execute(credentials, "id", 1000L);
    }

    @Test
    @DisplayName("root 账号直接执行提权命令，不包装 sudo")
    void privileged_rootRunsDirectly() {
        HostCredentials root = new HostCredentials("10.0.0.1", 22, "root", null, "pw");
        when(deploymentAccess.credentials(1L)).thenReturn(root);
        when(transport.execute(eq(root), eq("id -u"), eq(10_000L)))
                .thenReturn(command(0, "0\n", ""));
        when(transport.execute(eq(root), eq("systemctl restart demo"), eq(30_000L)))
                .thenReturn(command(0, "", ""));

        remoteHost.execPrivileged(1L, "systemctl restart demo", 30_000);

        verify(transport).execute(root, "systemctl restart demo", 30_000);
        verify(transport, never()).execute(eq(root),
                org.mockito.ArgumentMatchers.contains("sudo -S"), anyLong());
    }

    @Test
    @DisplayName("非 root 有密码时用 sudo -S 并显式保留原命令退出码")
    void privileged_nonRootWrapsSudoAndPreservesExitCode() {
        HostCredentials user = new HostCredentials("10.0.0.1", 22, "deploy", null, "pa'ss");
        when(deploymentAccess.credentials(1L)).thenReturn(user);
        when(transport.execute(eq(user), eq("id -u"), eq(10_000L)))
                .thenReturn(command(0, "1000\n", ""));
        when(transport.execute(eq(user), anyString(), eq(30_000L)))
                .thenReturn(command(0, "", ""));

        remoteHost.execPrivileged(1L, "printf ok > /etc/hosts", 30_000);

        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(transport, org.mockito.Mockito.times(2))
                .execute(eq(user), command.capture(), anyLong());
        assertThat(command.getAllValues().get(1))
                .contains("sudo -S -p '' sh -c")
                .contains("; exit $?")
                .contains("pa'\\''ss");
    }

    @Test
    @DisplayName("非 root 仅密钥登录时拒绝提权并给出配置指引")
    void privileged_nonRootKeyOnlyFailsFast() {
        HostCredentials keyOnly = new HostCredentials("10.0.0.1", 22, "deploy", "key", null);
        when(deploymentAccess.credentials(1L)).thenReturn(keyOnly);
        when(transport.execute(eq(keyOnly), eq("id -u"), eq(10_000L)))
                .thenReturn(command(0, "1000\n", ""));

        assertThatThrownBy(() ->
                remoteHost.execPrivileged(1L, "systemctl restart demo", 30_000))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("补充密码");
    }

    @Test
    @DisplayName("writeFilePrivileged 用 tee 覆盖并做 sha256 回读校验，不使用 mv/cp")
    void writeFilePrivileged_usesTeeAndHashVerification() {
        HostCredentials user = new HostCredentials("10.0.0.1", 22, "deploy", null, "pw");
        when(deploymentAccess.credentials(1L)).thenReturn(user);
        when(transport.execute(eq(user), eq("id -u"), eq(10_000L)))
                .thenReturn(command(0, "1000\n", ""));
        when(transport.execute(eq(user), anyString(), eq(30_000L)))
                .thenReturn(command(0, "ok", ""));

        remoteHost.writeFilePrivileged(1L, "/etc/hosts", "127.0.0.1 example.com\n", 30_000);

        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(transport, org.mockito.Mockito.times(2))
                .execute(eq(user), command.capture(), anyLong());
        assertThat(command.getAllValues().get(1))
                .contains("tee /etc/hosts <")
                .contains("> /dev/null")
                .contains(".platform-write.tmp")
                .contains("sha256sum")
                .doesNotContain("mv ")
                .doesNotContain("cp ");
    }

    @Test
    @DisplayName("传输异常统一包装为 HostAccessException")
    void transportRuntimeFailure_isWrappedAsHostAccessException() {
        HostCredentials credentials = credentials(null, null);
        when(deploymentAccess.credentials(1L)).thenReturn(credentials);
        when(transport.execute(eq(credentials), eq("id"), eq(1000L)))
                .thenThrow(new IllegalStateException("socket closed"));

        assertThatThrownBy(() -> remoteHost.exec(1L, "id", 1000))
                .isInstanceOf(HostAccessException.class)
                .hasMessageContaining("socket closed");
    }

    @Test
    @DisplayName("资源与端口查询经解析凭据转发到传输接缝")
    void resourceAndPortQueries_forwardThroughTransport() {
        HostCredentials credentials = credentials(null, "key");
        ResourceInfo resourceInfo = new ResourceInfo();
        PortCheckResult portResult = new PortCheckResult();
        when(deploymentAccess.credentials(1L)).thenReturn(credentials);
        when(transport.getResourceInfo(credentials)).thenReturn(resourceInfo);
        when(transport.checkPort(credentials, 25565)).thenReturn(portResult);

        assertThat(remoteHost.getResourceInfo(1L)).isSameAs(resourceInfo);
        assertThat(remoteHost.checkPort(1L, 25565)).isSameAs(portResult);
    }

    @Test
    @DisplayName("上传、下载和 SFTP 操作经解析凭据转发到传输接缝")
    void transferOperations_forwardThroughTransport() throws Exception {
        HostCredentials credentials = credentials(null, "key");
        SftpClient sftp = mock(SftpClient.class);
        when(deploymentAccess.credentials(host)).thenReturn(credentials);
        when(deploymentAccess.credentials(1L)).thenReturn(credentials);
        when(transport.uploadFile(credentials, "/local/upload", "/remote/upload", null))
                .thenReturn(true);
        when(transport.downloadFile(credentials, "/remote/download", "/local/download", null))
                .thenReturn(true);
        when(transport.withSftp(eq(credentials), any())).thenAnswer(invocation -> {
            SftpOperation<?> operation = invocation.getArgument(1);
            return operation.execute(sftp);
        });

        assertThat(remoteHost.upload(host, "/local/upload", "/remote/upload")).isTrue();
        assertThat(remoteHost.download(host, "/remote/download", "/local/download")).isTrue();
        String sftpResult = remoteHost.withSftp(host.getId(), (SftpClient operation) -> {
            assertThat(operation).isSameAs(sftp);
            return "sftp-ok";
        });
        assertThat(sftpResult).isEqualTo("sftp-ok");

        verify(transport).uploadFile(credentials, "/local/upload", "/remote/upload", null);
        verify(transport).downloadFile(credentials, "/remote/download", "/local/download", null);
        verify(transport).withSftp(eq(credentials), any());
    }

    @Test
    @DisplayName("钉住会话的获取和释放经传输接缝转发")
    void pinnedSession_forwardThroughTransport() throws Exception {
        HostCredentials credentials = credentials(null, "key");
        ClientSession session = mock(ClientSession.class);
        when(deploymentAccess.credentials(1L)).thenReturn(credentials);
        when(transport.acquirePinnedSession(credentials, 1000L)).thenReturn(session);

        ClientSession acquired = remoteHost.acquirePinnedSession(1L, 1000L);
        remoteHost.releasePinnedSession(acquired);

        assertThat(acquired).isSameAs(session);
        verify(transport).acquirePinnedSession(credentials, 1000L);
        verify(transport).releasePinnedSession(session);
    }

    private HostCredentials credentials(String password, String privateKey) {
        return new HostCredentials("10.0.0.1", 22, "deploy", privateKey, password);
    }

    private CommandResult command(int exitCode, String output, String error) {
        CommandResult result = new CommandResult();
        result.setSuccess(exitCode == 0);
        result.setExitCode(exitCode);
        result.setOutput(output);
        result.setError(error);
        return result;
    }
}

package com.gameplatform.service;

import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.util.SshUtil;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FileService 同主机连接复用回归测试（性能缺陷：每次远程操作重建 SSH 连接，
 * 握手 ~0.4s/次，list 5 次操作 → ~2.3s）。
 *
 * <p>锁定：同一主机的多次操作只建立一次 SSH 连接（deployAccess.connect 只调一次），
 * 后续操作复用连接，且操作间不关闭共享连接。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FileService 连接复用测试")
class FileServiceConnectionReuseTest {

    @Mock
    private HostMapper hostMapper;

    @Mock
    private SshUtil sshUtil;

    @Mock
    private DeploymentAccess deployAccess;

    @Mock
    private ClientSession clientSession;

    @Test
    @DisplayName("同一主机的两次 listFiles 只建立一次 SSH 连接")
    void listFilesReusesConnection() throws Exception {
        Host host = new Host();
        host.setId(10L);
        host.setIpAddress("192.168.1.10");
        when(hostMapper.selectById(10L)).thenReturn(host);

        DeploymentAccess.SshConnection shared = new DeploymentAccess.SshConnection(
                mock(SshClient.class), clientSession);
        when(clientSession.isOpen()).thenReturn(true);
        when(deployAccess.connect(host)).thenReturn(shared);

        SftpClient sftp = mock(SftpClient.class);
        when(sftp.readDir(anyString())).thenReturn(List.of());

        try (MockedStatic<SftpClientFactory> mockedFactory = mockStatic(SftpClientFactory.class)) {
            SftpClientFactory factory = mock(SftpClientFactory.class);
            mockedFactory.when(SftpClientFactory::instance).thenReturn(factory);
            when(factory.createSftpClient(clientSession)).thenReturn(sftp);

            FileService fileService = new FileService(hostMapper, sshUtil, deployAccess);

            fileService.listFiles(10L, "/a");
            fileService.listFiles(10L, "/a");

            // 连接只建一次，两次操作复用同一会话
            verify(deployAccess, times(1)).connect(any(Host.class));
            verify(sftp, times(2)).readDir(anyString());
        }
    }
}

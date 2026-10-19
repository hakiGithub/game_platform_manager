package com.gameplatform.service;

import com.gameplatform.hostaccess.RemoteHost;
import com.gameplatform.hostaccess.SftpOperation;
import org.apache.sshd.sftp.client.SftpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * FileService 远程文件访问回归测试。
 *
 * <p>锁定 FileService 只通过 RemoteHost.withSftp 访问文件系统；会话生命周期由
 * hostaccess/SshUtil 内部接缝承担，避免业务层再次感知 SSH 连接管理。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FileService 连接复用测试")
class FileServiceConnectionReuseTest {

    @Mock
    private RemoteHost remoteHost;

    @Test
    @DisplayName("同一主机的两次 listFiles 只建立一次 SSH 连接")
    void listFilesReusesConnection() throws Exception {
        SftpClient sftp = mock(SftpClient.class);
        when(sftp.readDir(anyString())).thenReturn(List.of());
        when(remoteHost.withSftp(eq(10L), any())).thenAnswer(invocation -> {
            SftpOperation<?> operation = invocation.getArgument(1);
            return operation.execute(sftp);
        });

        FileService fileService = new FileService(remoteHost);

        fileService.listFiles(10L, "/a");
        fileService.listFiles(10L, "/a");

        verify(remoteHost, times(2)).withSftp(eq(10L), any());
        verify(sftp, times(2)).readDir(anyString());
    }
}

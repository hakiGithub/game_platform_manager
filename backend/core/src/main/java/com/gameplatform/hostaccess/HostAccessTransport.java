package com.gameplatform.hostaccess;

import com.gameplatform.deploy.HostCredentials;
import com.gameplatform.plugin.service.FileTransferProgressCallback;
import org.apache.sshd.client.session.ClientSession;

/** 主机传输接缝。生产实现只包装 SSH/SFTP；测试可注入内存假传输。 */
public interface HostAccessTransport {

    boolean testConnection(HostCredentials credentials, long timeoutMs);

    CommandResult execute(HostCredentials credentials, String command, long timeoutMs);

    ResourceInfo getResourceInfo(HostCredentials credentials);

    PortCheckResult checkPort(HostCredentials credentials, int targetPort);

    boolean uploadFile(HostCredentials credentials, String localPath, String remotePath);

    boolean uploadFile(HostCredentials credentials, String localPath, String remotePath,
                       FileTransferProgressCallback callback);

    boolean downloadFile(HostCredentials credentials, String remotePath, String localPath);

    boolean downloadFile(HostCredentials credentials, String remotePath, String localPath,
                         FileTransferProgressCallback callback);

    <T> T withSftp(HostCredentials credentials, SftpOperation<T> operation) throws Exception;

    ClientSession acquirePinnedSession(HostCredentials credentials, long timeoutMs) throws Exception;

    void releasePinnedSession(ClientSession session);
}

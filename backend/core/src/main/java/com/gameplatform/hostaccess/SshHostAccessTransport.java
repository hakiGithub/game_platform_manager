package com.gameplatform.hostaccess;

import com.gameplatform.deploy.HostCredentials;
import com.gameplatform.plugin.service.FileTransferProgressCallback;
import com.gameplatform.util.SshUtil;
import lombok.RequiredArgsConstructor;
import org.apache.sshd.client.session.ClientSession;
import org.springframework.stereotype.Component;

/** SSH 传输适配器。{@link SshUtil} 只允许在这里与自身测试中出现。 */
@Component
@RequiredArgsConstructor
public class SshHostAccessTransport implements HostAccessTransport {

    private final SshUtil sshUtil;

    @Override
    public boolean testConnection(HostCredentials credentials, long timeoutMs) {
        return sshUtil.testConnection(credentials.host(), credentials.port(),
                credentials.username(), credentials.privateKey(),
                credentials.password(), timeoutMs);
    }

    @Override
    public CommandResult execute(HostCredentials credentials, String command, long timeoutMs) {
        SshUtil.CommandResult raw = sshUtil.executeCommand(
                credentials.host(), credentials.port(), credentials.username(),
                credentials.privateKey(), credentials.password(), command, timeoutMs);
        CommandResult result = new CommandResult();
        result.setSuccess(raw.isSuccess());
        result.setExitCode(raw.getExitCode());
        result.setOutput(raw.getOutput());
        result.setError(raw.getError());

        // 负退出码表示没有拿到远端退出码，属于连接/会话故障；
        // 非零远端退出码仍是可达主机的普通命令结果。
        if (!raw.isSuccess() && raw.getExitCode() < 0) {
            throw new HostAccessException("SSH 传输失败: "
                    + (raw.getError() == null || raw.getError().isBlank()
                    ? "连接或会话不可用" : raw.getError()));
        }
        return result;
    }

    @Override
    public ResourceInfo getResourceInfo(HostCredentials credentials) {
        SshUtil.ResourceInfo raw = sshUtil.getResourceInfo(
                credentials.host(), credentials.port(), credentials.username(),
                credentials.privateKey(), credentials.password());
        ResourceInfo info = new ResourceInfo();
        info.setSuccess(raw.isSuccess());
        info.setCpuCores(raw.getCpuCores());
        info.setCpuUsage(raw.getCpuUsage());
        info.setCpuModel(raw.getCpuModel());
        info.setMemoryTotal(raw.getMemoryTotal());
        info.setMemoryUsed(raw.getMemoryUsed());
        info.setMemoryFree(raw.getMemoryFree());
        info.setMemoryUsage(raw.getMemoryUsage());
        info.setDiskTotal(raw.getDiskTotal());
        info.setDiskUsed(raw.getDiskUsed());
        info.setDiskFree(raw.getDiskFree());
        info.setDiskUsage(raw.getDiskUsage());
        info.setNetworkRxBytes(raw.getNetworkRxBytes());
        info.setNetworkTxBytes(raw.getNetworkTxBytes());
        info.setError(raw.getError());
        if (!raw.isSuccess()) {
            throw new HostAccessException("获取主机资源失败: "
                    + (raw.getError() == null || raw.getError().isBlank()
                    ? "主机不可达" : raw.getError()));
        }
        return info;
    }

    @Override
    public PortCheckResult checkPort(HostCredentials credentials, int targetPort) {
        SshUtil.PortCheckResult raw = sshUtil.checkPort(
                credentials.host(), credentials.port(), credentials.username(),
                credentials.privateKey(), credentials.password(), targetPort);
        PortCheckResult result = new PortCheckResult();
        result.setPort(raw.getPort());
        result.setAvailable(raw.isAvailable());
        result.setUsedBy(raw.getUsedBy());
        result.setError(raw.getError());
        if (!raw.isAvailable() && raw.getUsedBy() == null
                && raw.getError() != null && !raw.getError().isBlank()) {
            throw new HostAccessException("检查主机端口失败: " + raw.getError());
        }
        return result;
    }

    @Override
    public boolean uploadFile(HostCredentials credentials, String localPath, String remotePath) {
        return sshUtil.uploadFile(credentials.host(), credentials.port(),
                credentials.username(), credentials.privateKey(), credentials.password(),
                localPath, remotePath);
    }

    @Override
    public boolean uploadFile(HostCredentials credentials, String localPath, String remotePath,
                              FileTransferProgressCallback callback) {
        return sshUtil.uploadFile(credentials.host(), credentials.port(),
                credentials.username(), credentials.privateKey(), credentials.password(),
                localPath, remotePath, callback);
    }

    @Override
    public boolean downloadFile(HostCredentials credentials, String remotePath, String localPath) {
        return sshUtil.downloadFile(credentials.host(), credentials.port(),
                credentials.username(), credentials.privateKey(), credentials.password(),
                remotePath, localPath);
    }

    @Override
    public boolean downloadFile(HostCredentials credentials, String remotePath, String localPath,
                                FileTransferProgressCallback callback) {
        return sshUtil.downloadFile(credentials.host(), credentials.port(),
                credentials.username(), credentials.privateKey(), credentials.password(),
                remotePath, localPath, callback);
    }

    @Override
    public <T> T withSftp(HostCredentials credentials, SftpOperation<T> operation) throws Exception {
        return sshUtil.withSftp(credentials.host(), credentials.port(), credentials.username(),
                credentials.privateKey(), credentials.password(), operation::execute);
    }

    @Override
    public ClientSession acquirePinnedSession(HostCredentials credentials, long timeoutMs) throws Exception {
        return sshUtil.acquirePinnedSession(credentials.host(), credentials.port(),
                credentials.username(), credentials.privateKey(), credentials.password());
    }

    @Override
    public void releasePinnedSession(ClientSession session) {
        sshUtil.releasePinnedSession(session);
    }
}

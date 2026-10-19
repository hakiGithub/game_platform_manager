package com.gameplatform.hostaccess;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.deploy.HostCredentials;
import com.gameplatform.entity.Host;
import com.gameplatform.plugin.service.FileTransferProgressCallback;
import lombok.RequiredArgsConstructor;
import org.apache.sshd.client.session.ClientSession;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 主机访问深模块。调用方只提供 hostId（或已加载的 Host）和操作，不再接触凭据七元组、
 * 认证优先顺序或 sudo 包装。
 */
@Component
@RequiredArgsConstructor
public class RemoteHost {

    private static final long DEFAULT_TIMEOUT_MS = 10_000;
    private static final long PRIVILEGE_CHECK_TIMEOUT_MS = 10_000;

    private final DeploymentAccess deploymentAccess;
    private final HostAccessTransport transport;

    /** 凭据认证偏好。默认遵循 ADR：私钥优先、密码回退。 */
    public enum AuthenticationPreference {
        KEY_FIRST,
        PASSWORD_IF_PRESENT
    }

    public HostCredentials credentials(Host host) {
        return deploymentAccess.credentials(host);
    }

    public HostCredentials credentials(Long hostId) {
        return deploymentAccess.credentials(hostId);
    }

    public CommandResult exec(Long hostId, String command) {
        return exec(hostId, command, DEFAULT_TIMEOUT_MS);
    }

    public CommandResult exec(Long hostId, String command, long timeoutMs) {
        return exec(credentials(hostId), command, timeoutMs, AuthenticationPreference.KEY_FIRST);
    }

    public CommandResult exec(Host host, String command) {
        return exec(host, command, DEFAULT_TIMEOUT_MS);
    }

    public CommandResult exec(Host host, String command, long timeoutMs) {
        return exec(host, command, timeoutMs, AuthenticationPreference.KEY_FIRST);
    }

    public CommandResult exec(Host host, String command, long timeoutMs,
                              AuthenticationPreference preference) {
        return exec(credentials(host), command, timeoutMs, preference);
    }

    public CommandResult exec(HostCredentials credentials, String command, long timeoutMs,
                              AuthenticationPreference preference) {
        try {
            return transport.execute(applyPreference(credentials, preference), command, timeoutMs);
        } catch (HostAccessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new HostAccessException("SSH 命令执行失败: " + safeMessage(e), e);
        }
    }

    public CommandResult execPrivileged(Long hostId, String command, long timeoutMs) {
        HostCredentials credentials = credentials(hostId);
        if (isRoot(credentials)) {
            return exec(credentials, command, timeoutMs, AuthenticationPreference.KEY_FIRST);
        }
        if (credentials.password() == null || credentials.password().isBlank()) {
            throw new BusinessException("主机 " + credentials.host() + " 使用密钥登录且账号非 root，"
                    + "无法执行需要 root 权限的操作；请在主机配置中补充密码（sudo 提权需要）");
        }
        String wrapped = "printf '%s\\n' '" + escape(credentials.password()) + "'"
                + " | sudo -S -p '' sh -c '" + escape(command) + "; exit $?'";
        return exec(credentials, wrapped, timeoutMs, AuthenticationPreference.KEY_FIRST);
    }

    public void writeFilePrivileged(Long hostId, String path, String content, long timeoutMs) {
        String b64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        String expectedSha = sha256(content);
        String tmpPath = path + ".platform-write.tmp";
        String command = "printf %s " + b64 + " | base64 -d > " + tmpPath
                + " && sha256sum " + tmpPath
                + " && tee " + path + " < " + tmpPath + " > /dev/null"
                + " && sha256sum " + path
                + " && rm -f " + tmpPath
                + " && test \"$(sha256sum " + path + " | awk '{print $1}')\" = \""
                + expectedSha + "\"";
        CommandResult result = execPrivileged(hostId, command, timeoutMs);
        if (!result.isSuccess()) {
            throw new BusinessException("写入远程文件失败: " + path
                    + " - " + firstLine(result.getError())
                    + "，expectedSha=" + expectedSha
                    + "，stdout=" + summarize(result.getOutput())
                    + "（已尝试 tee 覆盖并回读校验）");
        }
    }

    public boolean testConnection(Host host) {
        return testConnection(credentials(host), DEFAULT_TIMEOUT_MS);
    }

    public boolean testConnection(Long hostId) {
        return testConnection(credentials(hostId));
    }

    public boolean testConnection(HostCredentials credentials) {
        return testConnection(credentials, DEFAULT_TIMEOUT_MS);
    }

    public boolean testConnection(String host, int port, String username,
                                  String privateKey, String password, long timeoutMs) {
        return testConnection(new HostCredentials(host, port, username, privateKey, password), timeoutMs);
    }

    public boolean testConnection(HostCredentials credentials, long timeoutMs) {
        try {
            return transport.testConnection(
                    applyPreference(credentials, AuthenticationPreference.KEY_FIRST), timeoutMs);
        } catch (HostAccessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new HostAccessException("SSH 连接测试失败: " + safeMessage(e), e);
        }
    }

    public ResourceInfo getResourceInfo(Long hostId) {
        return getResourceInfo(credentials(hostId));
    }

    public ResourceInfo getResourceInfo(Host host) {
        return getResourceInfo(credentials(host));
    }

    public ResourceInfo getResourceInfo(HostCredentials credentials) {
        try {
            return transport.getResourceInfo(
                    applyPreference(credentials, AuthenticationPreference.KEY_FIRST));
        } catch (HostAccessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new HostAccessException("获取主机资源失败: " + safeMessage(e), e);
        }
    }

    public PortCheckResult checkPort(Long hostId, int targetPort) {
        HostCredentials credentials = credentials(hostId);
        try {
            return transport.checkPort(
                    applyPreference(credentials, AuthenticationPreference.KEY_FIRST), targetPort);
        } catch (HostAccessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new HostAccessException("检查主机端口失败: " + safeMessage(e), e);
        }
    }

    public boolean upload(Host host, String localPath, String remotePath) {
        return upload(credentials(host), localPath, remotePath);
    }

    public boolean upload(Long hostId, String localPath, String remotePath) {
        return upload(credentials(hostId), localPath, remotePath);
    }

    public boolean upload(HostCredentials credentials, String localPath, String remotePath) {
        return upload(credentials, localPath, remotePath, null);
    }

    public boolean upload(Host host, String localPath, String remotePath,
                          FileTransferProgressCallback callback) {
        return upload(credentials(host), localPath, remotePath, callback);
    }

    public boolean upload(Long hostId, String localPath, String remotePath,
                          FileTransferProgressCallback callback) {
        return upload(credentials(hostId), localPath, remotePath, callback);
    }

    public boolean upload(HostCredentials credentials, String localPath, String remotePath,
                          FileTransferProgressCallback callback) {
        return transport.uploadFile(
                applyPreference(credentials, AuthenticationPreference.KEY_FIRST),
                localPath, remotePath, callback);
    }

    public boolean download(Host host, String remotePath, String localPath) {
        return download(credentials(host), remotePath, localPath);
    }

    public boolean download(Long hostId, String remotePath, String localPath) {
        return download(credentials(hostId), remotePath, localPath);
    }

    public boolean download(HostCredentials credentials, String remotePath, String localPath) {
        return download(credentials, remotePath, localPath, null);
    }

    public boolean download(Host host, String remotePath, String localPath,
                            FileTransferProgressCallback callback) {
        return download(credentials(host), remotePath, localPath, callback);
    }

    public boolean download(Long hostId, String remotePath, String localPath,
                            FileTransferProgressCallback callback) {
        return download(credentials(hostId), remotePath, localPath, callback);
    }

    public boolean download(HostCredentials credentials, String remotePath, String localPath,
                            FileTransferProgressCallback callback) {
        return transport.downloadFile(
                applyPreference(credentials, AuthenticationPreference.KEY_FIRST),
                remotePath, localPath, callback);
    }

    public <T> T withSftp(Long hostId, SftpOperation<T> operation) {
        try {
            return transport.withSftp(
                    applyPreference(credentials(hostId), AuthenticationPreference.KEY_FIRST),
                    operation);
        } catch (HostAccessException e) {
            throw e;
        } catch (Exception e) {
            throw new HostAccessException("SFTP 操作失败: " + safeMessage(e), e);
        }
    }

    public ClientSession acquirePinnedSession(Long hostId) {
        return acquirePinnedSession(hostId, DEFAULT_TIMEOUT_MS);
    }

    public ClientSession acquirePinnedSession(Long hostId, long timeoutMs) {
        try {
            return transport.acquirePinnedSession(credentials(hostId), timeoutMs);
        } catch (HostAccessException e) {
            throw e;
        } catch (Exception e) {
            throw new HostAccessException("SSH 隧道会话建立失败: " + safeMessage(e), e);
        }
    }

    public void releasePinnedSession(ClientSession session) {
        transport.releasePinnedSession(session);
    }

    public KeyPair parsePrivateKey(String privateKey) {
        return com.gameplatform.util.SshUtil.parsePrivateKey(privateKey);
    }

    private HostCredentials applyPreference(HostCredentials credentials,
                                            AuthenticationPreference preference) {
        if (preference == AuthenticationPreference.PASSWORD_IF_PRESENT
                && credentials.password() != null && !credentials.password().isBlank()) {
            return new HostCredentials(credentials.host(), credentials.port(),
                    credentials.username(), null, credentials.password());
        }
        return credentials;
    }

    private boolean isRoot(HostCredentials credentials) {
        CommandResult result = exec(credentials, "id -u", PRIVILEGE_CHECK_TIMEOUT_MS,
                AuthenticationPreference.KEY_FIRST);
        return result != null
                && result.isSuccess()
                && result.getOutput() != null
                && "0".equals(result.getOutput().trim());
    }

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    private String escape(String value) {
        return value.replace("'", "'\\''");
    }

    private String firstLine(String value) {
        if (value == null || value.isBlank()) {
            return "未知错误";
        }
        return value.strip().split("\\R", 2)[0];
    }

    private String summarize(String value) {
        if (value == null || value.isBlank()) {
            return "(empty)";
        }
        String oneLine = value.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= 240 ? oneLine : oneLine.substring(0, 240) + "...";
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}

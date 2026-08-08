package com.gameplatform.plugin.service;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.service.FileService;
import com.gameplatform.util.AesUtil;
import com.gameplatform.util.SshUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 远程文件访问服务实现。
 * <p>
 * 委托转发至 {@link FileService}，不重复业务逻辑。
 * 通过 @Service 注册到主容器，再由 {@code PluginSpringContextFactory}
 * 注入到插件子容器供插件使用。
 * <p>
 * 由于 {@code FileService.FileInfo} 是 core 模块的内部类，
 * 本实现负责将其转换为插件可见的 {@link FileAccessService.FileInfo}。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileAccessServiceImpl implements FileAccessService {

    private final FileService fileService;
    private final HostMapper hostMapper;
    private final SshUtil sshUtil;

    @Override
    public String readTextFile(Long hostId, String remotePath) {
        return fileService.readTextFile(hostId, remotePath);
    }

    @Override
    public void writeTextFile(Long hostId, String remotePath, String content) {
        fileService.writeTextFile(hostId, remotePath, content);
    }

    @Override
    public byte[] downloadFileToMemory(Long hostId, String remotePath) {
        return fileService.downloadFileToMemory(hostId, remotePath);
    }

    @Override
    public void uploadFile(Long hostId, String remotePath, MultipartFile file) {
        fileService.uploadFile(hostId, remotePath, file);
    }

    @Override
    public void uploadLocalFile(Long hostId, String remotePath, String localPath) {
        fileService.uploadLocalFile(hostId, remotePath, localPath);
    }

    @Override
    public void downloadFile(Long hostId, String remotePath, String localPath) {
        fileService.downloadFile(hostId, remotePath, localPath);
    }

    @Override
    public void deleteFile(Long hostId, String remotePath) {
        fileService.deleteFile(hostId, remotePath);
    }

    @Override
    public void moveFile(Long hostId, String oldPath, String newPath) {
        fileService.moveFile(hostId, oldPath, newPath);
    }

    @Override
    public List<FileInfo> listFiles(Long hostId, String remotePath) {
        List<FileService.FileInfo> source = fileService.listFiles(hostId, remotePath);
        List<FileInfo> result = new ArrayList<>(source.size());
        for (FileService.FileInfo src : source) {
            result.add(convertFileInfo(src));
        }
        return result;
    }

    @Override
    public void createDirectory(Long hostId, String remotePath) {
        fileService.createDirectory(hostId, remotePath);
    }

    @Override
    public void deleteDirectory(Long hostId, String remotePath, boolean recursive) {
        fileService.deleteDirectory(hostId, remotePath, recursive);
    }

    @Override
    public boolean exists(Long hostId, String remotePath) {
        return fileService.exists(hostId, remotePath);
    }

    @Override
    public FileInfo getFileInfo(Long hostId, String remotePath) {
        FileService.FileInfo src = fileService.getFileInfo(hostId, remotePath);
        return src == null ? null : convertFileInfo(src);
    }

    @Override
    public String readTextFile(Long hostId, String remotePath, Charset charset) {
        byte[] bytes = downloadFileToMemory(hostId, remotePath);
        return new String(bytes, charset);
    }

    @Override
    public byte[] getFileBytes(Long hostId, String remotePath, long offset, long length) {
        log.info("读取文件字节范围: hostId={}, path={}, offset={}, length={}", hostId, remotePath, offset, length);
        HostConnection conn = getHostConnection(hostId);
        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();
            try (ClientSession session = connect(client, conn)) {
                SftpClientFactory factory = SftpClientFactory.instance();
                try (SftpClient sftp = factory.createSftpClient(session)) {
                    SftpClient.Attributes attrs = sftp.stat(remotePath);
                    long fileSize = attrs.getSize();
                    long start = offset < 0 ? Math.max(0, fileSize + offset) : offset;
                    long toRead = length <= 0 ? (fileSize - start) : Math.min(length, fileSize - start);
                    if (toRead <= 0) {
                        return new byte[0];
                    }
                    try (InputStream is = sftp.read(remotePath)) {
                        is.skip(start);
                        byte[] buf = new byte[(int) toRead];
                        int read = 0;
                        while (read < buf.length) {
                            int n = is.read(buf, read, buf.length - read);
                            if (n < 0) {
                                break;
                            }
                            read += n;
                        }
                        return read == buf.length ? buf : Arrays.copyOf(buf, read);
                    }
                }
            }
        } catch (Exception e) {
            log.error("读取文件字节范围失败: {}", remotePath, e);
            throw new BusinessException("读取文件字节范围失败: " + e.getMessage());
        }
    }

    @Override
    public long tailFile(Long hostId, String remotePath, long offset, Charset charset,
                         Consumer<String> lineConsumer) {
        log.info("Tail 文件: hostId={}, path={}, offset={}", hostId, remotePath, offset);
        HostConnection conn = getHostConnection(hostId);
        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();
            try (ClientSession session = connect(client, conn)) {
                SftpClientFactory factory = SftpClientFactory.instance();
                try (SftpClient sftp = factory.createSftpClient(session)) {
                    SftpClient.Attributes attrs = sftp.stat(remotePath);
                    long fileSize = attrs.getSize();
                    if (fileSize <= offset) {
                        return offset;
                    }
                    try (InputStream is = sftp.read(remotePath)) {
                        is.skip(offset);
                        ByteArrayOutputStream buf = new ByteArrayOutputStream();
                        byte[] tmp = new byte[8192];
                        int n;
                        while ((n = is.read(tmp)) > 0) {
                            buf.write(tmp, 0, n);
                        }
                        byte[] all = buf.toByteArray();
                        String text = new String(all, charset);
                        for (String line : text.split("\n", -1)) {
                            if (!line.isEmpty()) {
                                lineConsumer.accept(line);
                            }
                        }
                        return offset + all.length;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Tail 文件失败: {}", remotePath, e);
            throw new BusinessException("Tail 文件失败: " + e.getMessage());
        }
    }

    @Override
    public CommandResult executeCommand(Long hostId, String command, long timeoutMs) {
        log.info("远程执行命令: hostId={}, command={}", hostId, command);
        HostConnection conn = getHostConnection(hostId);
        SshUtil.CommandResult coreResult = sshUtil.executeCommand(
                conn.getHost(), conn.getPort(), conn.getUsername(),
                conn.getPrivateKey(), conn.getPassword(), command, timeoutMs);
        CommandResult result = new CommandResult();
        result.setSuccess(coreResult.isSuccess());
        result.setExitCode(coreResult.getExitCode());
        result.setOutput(coreResult.getOutput());
        result.setError(coreResult.getError());
        return result;
    }

    /**
     * 将 core 内部的 {@link FileService.FileInfo} 转换为插件可见的 {@link FileInfo}。
     *
     * @param src core 内部文件信息
     * @return 插件可见的文件信息
     */
    private static FileInfo convertFileInfo(FileService.FileInfo src) {
        FileInfo dst = new FileInfo();
        dst.setName(src.getName());
        dst.setPath(src.getPath());
        dst.setDirectory(src.isDirectory());
        dst.setSize(src.getSize());
        dst.setLastModified(src.getLastModified());
        dst.setPermissions(src.getPermissions());
        dst.setOwner(src.getOwner());
        return dst;
    }

    /**
     * 获取主机连接信息：解析 hostId 为 SSH 连接所需参数，并解密私钥/密码。
     * 与 {@link FileService} 中的实现保持一致。
     */
    private HostConnection getHostConnection(Long hostId) {
        Host host = hostMapper.selectById(hostId);
        if (host == null) {
            throw new BusinessException("主机不存在");
        }

        HostConnection conn = new HostConnection();
        conn.setHost(host.getIpAddress());
        conn.setPort(host.getSshPort() != null ? host.getSshPort() : 22);
        conn.setUsername(host.getSshUser());

        if (host.getSshPrivateKey() != null && !host.getSshPrivateKey().isEmpty()) {
            conn.setPrivateKey(AesUtil.decrypt(host.getSshPrivateKey()));
        }

        if (host.getSshPassword() != null && !host.getSshPassword().isEmpty()) {
            conn.setPassword(AesUtil.decrypt(host.getSshPassword()));
        }

        return conn;
    }

    /**
     * 建立 SSH 连接：优先私钥认证，其次密码认证。
     * 与 {@link FileService} 中的实现保持一致。
     */
    private ClientSession connect(SshClient client, HostConnection conn) throws Exception {
        ClientSession session = client.connect(conn.getUsername(), conn.getHost(), conn.getPort())
                .verify(10000, TimeUnit.MILLISECONDS)
                .getSession();

        if (conn.getPrivateKey() != null && !conn.getPrivateKey().isEmpty()) {
            try {
                java.security.KeyPair keyPair = parsePrivateKey(conn.getPrivateKey());
                if (keyPair != null) {
                    session.addPublicKeyIdentity(keyPair);
                }
            } catch (Exception e) {
                log.warn("私钥解析失败，回退到密码认证: {}", e.getMessage());
            }
        }
        if (conn.getPassword() != null && !conn.getPassword().isEmpty()) {
            session.addPasswordIdentity(conn.getPassword());
        }

        if (!session.auth().verify(10000, TimeUnit.MILLISECONDS).isSuccess()) {
            throw new RuntimeException("SSH认证失败：用户名=" + conn.getUsername()
                    + "，主机=" + conn.getHost() + ":" + conn.getPort()
                    + "，请检查主机配置的密码或私钥");
        }

        return session;
    }

    /**
     * 解析私钥字符串为 KeyPair（Apache MINA SSHD KeyPairResourceParser）。
     * 与 {@link FileService} 中的实现保持一致。
     */
    private java.security.KeyPair parsePrivateKey(String privateKey) throws Exception {
        if (privateKey == null || privateKey.isEmpty()) {
            return null;
        }
        org.apache.sshd.common.config.keys.loader.KeyPairResourceParser parser =
                org.apache.sshd.common.config.keys.loader.KeyPairResourceParser.aggregate(
                        org.apache.sshd.common.config.keys.loader.openssh.OpenSSHKeyPairResourceParser.INSTANCE,
                        org.apache.sshd.common.config.keys.loader.pem.PEMResourceParserUtils.PROXY
                );
        org.apache.sshd.common.NamedResource resourceKey = org.apache.sshd.common.NamedResource.ofName("private-key");
        java.util.Collection<java.security.KeyPair> keyPairs = parser.loadKeyPairs(
                null, resourceKey, null, privateKey);
        if (keyPairs == null || keyPairs.isEmpty()) {
            return null;
        }
        return keyPairs.iterator().next();
    }

    /**
     * 主机连接信息（与 {@link FileService} 中的内部类保持一致）。
     */
    @Data
    private static class HostConnection {
        private String host;
        private int port;
        private String username;
        private String privateKey;
        private String password;
    }
}

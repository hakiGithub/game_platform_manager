package com.gameplatform.service;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 文件管理服务
 * 使用SFTP实现远程文件操作
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private final HostMapper hostMapper;
    private final SshUtil sshUtil;

    /**
     * 文件信息DTO
     */
    @Data
    public static class FileInfo {
        /**
         * 文件名
         */
        private String name;

        /**
         * 完整路径
         */
        private String path;

        /**
         * 是否为目录
         */
        private boolean directory;

        /**
         * 文件大小（字节）
         */
        private long size;

        /**
         * 最后修改时间（毫秒）
         */
        private long lastModified;

        /**
         * 文件权限
         */
        private String permissions;

        /**
         * 文件所有者
         */
        private String owner;
    }

    /**
     * 获取文件列表
     *
     * @param hostId     主机ID
     * @param remotePath 远程目录路径
     * @return 文件列表
     */
    public List<FileInfo> listFiles(Long hostId, String remotePath) {
        log.info("获取文件列表: hostId={}, path={}", hostId, remotePath);

        HostConnection conn = getHostConnection(hostId);
        List<FileInfo> files = new ArrayList<>();

        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();

            try (ClientSession session = connect(client, conn)) {
                SftpClientFactory factory = SftpClientFactory.instance();
                try (SftpClient sftp = factory.createSftpClient(session)) {
                    Iterable<SftpClient.DirEntry> entries = sftp.readDir(remotePath);

                    for (SftpClient.DirEntry entry : entries) {
                        // 跳过当前目录和父目录
                        if (".".equals(entry.getFilename()) || "..".equals(entry.getFilename())) {
                            continue;
                        }

                        FileInfo info = new FileInfo();
                        info.setName(entry.getFilename());
                        info.setPath(remotePath + "/" + entry.getFilename());
                        info.setDirectory(entry.getAttributes().isDirectory());
                        info.setSize(entry.getAttributes().getSize());
                        info.setLastModified(entry.getAttributes().getModifyTime().toMillis());

                        // 获取权限信息
                        int perms = entry.getAttributes().getPermissions();
                        info.setPermissions(formatPermissions(perms));

                        files.add(info);
                    }
                }
            }
        } catch (Exception e) {
            log.error("获取文件列表失败: {}", remotePath, e);
            throw new BusinessException("获取文件列表失败: " + e.getMessage());
        }

        return files;
    }

    /**
     * 上传文件
     *
     * @param hostId     主机ID
     * @param remotePath 远程文件路径
     * @param file       要上传的文件
     */
    public void uploadFile(Long hostId, String remotePath, MultipartFile file) {
        log.info("上传文件: hostId={}, path={}, file={}", hostId, remotePath, file.getOriginalFilename());

        HostConnection conn = getHostConnection(hostId);

        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();

            try (ClientSession session = connect(client, conn)) {
                SftpClientFactory factory = SftpClientFactory.instance();
                try (SftpClient sftp = factory.createSftpClient(session)) {
                    // 确保远程目录存在
                    Path remoteParent = Paths.get(remotePath).getParent();
                    if (remoteParent != null) {
                        try {
                            sftp.mkdir(remoteParent.toString());
                        } catch (Exception e) {
                            log.debug("创建远程目录失败或已存在: {}", remoteParent);
                        }
                    }

                    // 上传文件
                    try (InputStream is = file.getInputStream();
                         OutputStream os = sftp.write(remotePath)) {
                        is.transferTo(os);
                    }

                    log.info("文件上传成功: {}", remotePath);
                }
            }
        } catch (Exception e) {
            log.error("上传文件失败: {}", remotePath, e);
            throw new BusinessException("上传文件失败: " + e.getMessage());
        }
    }

    /**
     * 上传本地文件
     *
     * @param hostId      主机ID
     * @param remotePath  远程文件路径
     * @param localPath   本地文件路径
     */
    public void uploadLocalFile(Long hostId, String remotePath, String localPath) {
        log.info("上传本地文件: hostId={}, remote={}, local={}", hostId, remotePath, localPath);

        HostConnection conn = getHostConnection(hostId);

        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();

            try (ClientSession session = connect(client, conn)) {
                SftpClientFactory factory = SftpClientFactory.instance();
                try (SftpClient sftp = factory.createSftpClient(session)) {
                    // 确保远程目录存在
                    Path remoteParent = Paths.get(remotePath).getParent();
                    if (remoteParent != null) {
                        try {
                            sftp.mkdir(remoteParent.toString());
                        } catch (Exception e) {
                            log.debug("创建远程目录失败或已存在: {}", remoteParent);
                        }
                    }

                    // 上传文件
                    Path localFile = Paths.get(localPath);
                    try (InputStream is = Files.newInputStream(localFile);
                         OutputStream os = sftp.write(remotePath)) {
                        is.transferTo(os);
                    }

                    log.info("文件上传成功: {} -> {}", localPath, remotePath);
                }
            }
        } catch (Exception e) {
            log.error("上传文件失败: {}", remotePath, e);
            throw new BusinessException("上传文件失败: " + e.getMessage());
        }
    }

    /**
     * 下载文件
     *
     * @param hostId     主机ID
     * @param remotePath 远程文件路径
     * @param localPath  本地文件路径
     */
    public void downloadFile(Long hostId, String remotePath, String localPath) {
        log.info("下载文件: hostId={}, remote={}, local={}", hostId, remotePath, localPath);

        HostConnection conn = getHostConnection(hostId);

        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();

            try (ClientSession session = connect(client, conn)) {
                SftpClientFactory factory = SftpClientFactory.instance();
                try (SftpClient sftp = factory.createSftpClient(session)) {
                    // 确保本地目录存在
                    Path localParent = Paths.get(localPath).getParent();
                    if (localParent != null) {
                        Files.createDirectories(localParent);
                    }

                    // 下载文件
                    try (InputStream is = sftp.read(remotePath);
                         OutputStream os = Files.newOutputStream(Paths.get(localPath))) {
                        is.transferTo(os);
                    }

                    log.info("文件下载成功: {} -> {}", remotePath, localPath);
                }
            }
        } catch (Exception e) {
            log.error("下载文件失败: {}", remotePath, e);
            throw new BusinessException("下载文件失败: " + e.getMessage());
        }
    }

    /**
     * 下载文件到内存
     *
     * @param hostId     主机ID
     * @param remotePath 远程文件路径
     * @return 文件内容
     */
    public byte[] downloadFileToMemory(Long hostId, String remotePath) {
        log.info("下载文件到内存: hostId={}, path={}", hostId, remotePath);

        HostConnection conn = getHostConnection(hostId);

        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();

            try (ClientSession session = connect(client, conn)) {
                SftpClientFactory factory = SftpClientFactory.instance();
                try (SftpClient sftp = factory.createSftpClient(session)) {
                    try (InputStream is = sftp.read(remotePath);
                         ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        is.transferTo(baos);
                        return baos.toByteArray();
                    }
                }
            }
        } catch (Exception e) {
            log.error("下载文件失败: {}", remotePath, e);
            throw new BusinessException("下载文件失败: " + e.getMessage());
        }
    }

    /**
     * 删除文件
     *
     * @param hostId     主机ID
     * @param remotePath 远程文件路径
     */
    public void deleteFile(Long hostId, String remotePath) {
        log.info("删除文件: hostId={}, path={}", hostId, remotePath);

        HostConnection conn = getHostConnection(hostId);

        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();

            try (ClientSession session = connect(client, conn)) {
                SftpClientFactory factory = SftpClientFactory.instance();
                try (SftpClient sftp = factory.createSftpClient(session)) {
                    sftp.remove(remotePath);
                    log.info("文件删除成功: {}", remotePath);
                }
            }
        } catch (Exception e) {
            log.error("删除文件失败: {}", remotePath, e);
            throw new BusinessException("删除文件失败: " + e.getMessage());
        }
    }

    /**
     * 创建目录
     *
     * @param hostId     主机ID
     * @param remotePath 远程目录路径
     */
    public void createDirectory(Long hostId, String remotePath) {
        log.info("创建目录: hostId={}, path={}", hostId, remotePath);

        HostConnection conn = getHostConnection(hostId);

        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();

            try (ClientSession session = connect(client, conn)) {
                SftpClientFactory factory = SftpClientFactory.instance();
                try (SftpClient sftp = factory.createSftpClient(session)) {
                    sftp.mkdir(remotePath);
                    log.info("目录创建成功: {}", remotePath);
                }
            }
        } catch (Exception e) {
            log.error("创建目录失败: {}", remotePath, e);
            throw new BusinessException("创建目录失败: " + e.getMessage());
        }
    }

    /**
     * 删除目录
     *
     * @param hostId     主机ID
     * @param remotePath 远程目录路径
     * @param recursive  是否递归删除
     */
    public void deleteDirectory(Long hostId, String remotePath, boolean recursive) {
        log.info("删除目录: hostId={}, path={}, recursive={}", hostId, remotePath, recursive);

        HostConnection conn = getHostConnection(hostId);

        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();

            try (ClientSession session = connect(client, conn)) {
                SftpClientFactory factory = SftpClientFactory.instance();
                try (SftpClient sftp = factory.createSftpClient(session)) {
                    if (recursive) {
                        // 递归删除
                        deleteRecursive(sftp, remotePath);
                    } else {
                        sftp.rmdir(remotePath);
                    }
                    log.info("目录删除成功: {}", remotePath);
                }
            }
        } catch (Exception e) {
            log.error("删除目录失败: {}", remotePath, e);
            throw new BusinessException("删除目录失败: " + e.getMessage());
        }
    }

    /**
     * 移动/重命名文件
     *
     * @param hostId      主机ID
     * @param oldPath     原路径
     * @param newPath     新路径
     */
    public void moveFile(Long hostId, String oldPath, String newPath) {
        log.info("移动文件: hostId={}, old={}, new={}", hostId, oldPath, newPath);

        HostConnection conn = getHostConnection(hostId);

        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();

            try (ClientSession session = connect(client, conn)) {
                SftpClientFactory factory = SftpClientFactory.instance();
                try (SftpClient sftp = factory.createSftpClient(session)) {
                    sftp.rename(oldPath, newPath);
                    log.info("文件移动成功: {} -> {}", oldPath, newPath);
                }
            }
        } catch (Exception e) {
            log.error("移动文件失败: {} -> {}", oldPath, newPath, e);
            throw new BusinessException("移动文件失败: " + e.getMessage());
        }
    }

    /**
     * 读取文本文件内容
     *
     * @param hostId     主机ID
     * @param remotePath 远程文件路径
     * @return 文件内容
     */
    public String readTextFile(Long hostId, String remotePath) {
        log.info("读取文本文件: hostId={}, path={}", hostId, remotePath);

        byte[] content = downloadFileToMemory(hostId, remotePath);
        return new String(content, StandardCharsets.UTF_8);
    }

    /**
     * 写入文本文件
     *
     * @param hostId     主机ID
     * @param remotePath 远程文件路径
     * @param content    文件内容
     */
    public void writeTextFile(Long hostId, String remotePath, String content) {
        log.info("写入文本文件: hostId={}, path={}", hostId, remotePath);

        HostConnection conn = getHostConnection(hostId);

        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();

            try (ClientSession session = connect(client, conn)) {
                SftpClientFactory factory = SftpClientFactory.instance();
                try (SftpClient sftp = factory.createSftpClient(session)) {
                    // 确保远程目录存在
                    Path remoteParent = Paths.get(remotePath).getParent();
                    if (remoteParent != null) {
                        try {
                            sftp.mkdir(remoteParent.toString());
                        } catch (Exception e) {
                            log.debug("创建远程目录失败或已存在: {}", remoteParent);
                        }
                    }

                    // 写入文件
                    try (OutputStream os = sftp.write(remotePath)) {
                        os.write(content.getBytes(StandardCharsets.UTF_8));
                    }

                    log.info("文本文件写入成功: {}", remotePath);
                }
            }
        } catch (Exception e) {
            log.error("写入文本文件失败: {}", remotePath, e);
            throw new BusinessException("写入文本文件失败: " + e.getMessage());
        }
    }

    /**
     * 检查文件是否存在
     *
     * @param hostId     主机ID
     * @param remotePath 远程文件路径
     * @return 是否存在
     */
    public boolean exists(Long hostId, String remotePath) {
        HostConnection conn = getHostConnection(hostId);

        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();

            try (ClientSession session = connect(client, conn)) {
                SftpClientFactory factory = SftpClientFactory.instance();
                try (SftpClient sftp = factory.createSftpClient(session)) {
                    SftpClient.Attributes attrs = sftp.stat(remotePath);
                    return attrs != null;
                }
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取文件信息
     *
     * @param hostId     主机ID
     * @param remotePath 远程文件路径
     * @return 文件信息
     */
    public FileInfo getFileInfo(Long hostId, String remotePath) {
        HostConnection conn = getHostConnection(hostId);

        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();

            try (ClientSession session = connect(client, conn)) {
                SftpClientFactory factory = SftpClientFactory.instance();
                try (SftpClient sftp = factory.createSftpClient(session)) {
                    SftpClient.Attributes attrs = sftp.stat(remotePath);

                    FileInfo info = new FileInfo();
                    info.setName(Paths.get(remotePath).getFileName().toString());
                    info.setPath(remotePath);
                    info.setDirectory(attrs.isDirectory());
                    info.setSize(attrs.getSize());
                    info.setLastModified(attrs.getModifyTime().toMillis());
                    info.setPermissions(formatPermissions(attrs.getPermissions()));

                    return info;
                }
            }
        } catch (Exception e) {
            log.error("获取文件信息失败: {}", remotePath, e);
            throw new BusinessException("获取文件信息失败: " + e.getMessage());
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 获取主机连接信息
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

        // 解密私钥
        if (host.getSshPrivateKey() != null && !host.getSshPrivateKey().isEmpty()) {
            conn.setPrivateKey(AesUtil.decrypt(host.getSshPrivateKey()));
        }

        // 解密密码
        if (host.getSshPassword() != null && !host.getSshPassword().isEmpty()) {
            conn.setPassword(AesUtil.decrypt(host.getSshPassword()));
        }

        return conn;
    }

    /**
     * 建立SSH连接
     */
    private ClientSession connect(SshClient client, HostConnection conn) throws Exception {
        ClientSession session = client.connect(conn.getUsername(), conn.getHost(), conn.getPort())
                .verify(10000, TimeUnit.MILLISECONDS)
                .getSession();

        // 认证：优先私钥，其次密码
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
     * 解析私钥字符串为 KeyPair
     * 复用 SshUtil 的解析逻辑（通过 Apache MINA SSHD 的 KeyPairResourceParser）
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
     * 递归删除目录
     */
    private void deleteRecursive(SftpClient sftp, String path) throws IOException {
        Iterable<SftpClient.DirEntry> entries = sftp.readDir(path);
        for (SftpClient.DirEntry entry : entries) {
            String name = entry.getFilename();
            if (".".equals(name) || "..".equals(name)) {
                continue;
            }

            String childPath = path + "/" + name;
            if (entry.getAttributes().isDirectory()) {
                deleteRecursive(sftp, childPath);
            } else {
                sftp.remove(childPath);
            }
        }
        sftp.rmdir(path);
    }

    /**
     * 格式化权限
     */
    private String formatPermissions(int perms) {
        StringBuilder sb = new StringBuilder();
        sb.append((perms & 0400) != 0 ? 'r' : '-');
        sb.append((perms & 0200) != 0 ? 'w' : '-');
        sb.append((perms & 0100) != 0 ? 'x' : '-');
        sb.append((perms & 0040) != 0 ? 'r' : '-');
        sb.append((perms & 0020) != 0 ? 'w' : '-');
        sb.append((perms & 0010) != 0 ? 'x' : '-');
        sb.append((perms & 0004) != 0 ? 'r' : '-');
        sb.append((perms & 0002) != 0 ? 'w' : '-');
        sb.append((perms & 0001) != 0 ? 'x' : '-');
        return sb.toString();
    }

    /**
     * 主机连接信息
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

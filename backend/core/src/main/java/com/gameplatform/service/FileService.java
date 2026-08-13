package com.gameplatform.service;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.deploy.HostCredentials;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.util.SshUtil;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
    private final DeploymentAccess deployAccess;

    /**
     * 同主机 SSH 连接缓存：避免每个远程操作重建连接（握手 ~0.4s/次，
     * 插件一次列表 = 5 次操作 ≈ 2.3s 的元凶）。
     * 会话失效（对端关闭）后由下一次操作自动重连。
     */
    private final ConcurrentHashMap<Long, DeploymentAccess.SshConnection> connectionCache =
            new ConcurrentHashMap<>();

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

        List<FileInfo> files = new ArrayList<>();

        DeploymentAccess.SshConnection conn = connectSftp(hostId);
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(conn.session())) {
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

        DeploymentAccess.SshConnection conn = connectSftp(hostId);
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(conn.session())) {
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

        DeploymentAccess.SshConnection conn = connectSftp(hostId);
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(conn.session())) {
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

        DeploymentAccess.SshConnection conn = connectSftp(hostId);
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(conn.session())) {
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

        DeploymentAccess.SshConnection conn = connectSftp(hostId);
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(conn.session())) {
                    try (InputStream is = sftp.read(remotePath);
                         ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        is.transferTo(baos);
                        return baos.toByteArray();
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

        DeploymentAccess.SshConnection conn = connectSftp(hostId);
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(conn.session())) {
                    sftp.remove(remotePath);
                    log.info("文件删除成功: {}", remotePath);
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

        DeploymentAccess.SshConnection conn = connectSftp(hostId);
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(conn.session())) {
                    sftp.mkdir(remotePath);
                    log.info("目录创建成功: {}", remotePath);
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

        DeploymentAccess.SshConnection conn = connectSftp(hostId);
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(conn.session())) {
                    if (recursive) {
                        // 递归删除
                        deleteRecursive(sftp, remotePath);
                    } else {
                        sftp.rmdir(remotePath);
                    }
                    log.info("目录删除成功: {}", remotePath);
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

        DeploymentAccess.SshConnection conn = connectSftp(hostId);
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(conn.session())) {
                    sftp.rename(oldPath, newPath);
                    log.info("文件移动成功: {} -> {}", oldPath, newPath);
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

        DeploymentAccess.SshConnection conn = connectSftp(hostId);
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(conn.session())) {
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
        DeploymentAccess.SshConnection conn = connectSftp(hostId);
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(conn.session())) {
                    SftpClient.Attributes attrs = sftp.stat(remotePath);
                    return attrs != null;
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
        DeploymentAccess.SshConnection conn = connectSftp(hostId);
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(conn.session())) {
                    SftpClient.Attributes attrs = sftp.stat(remotePath);

                    FileInfo info = new FileInfo();
                    info.setName(Paths.get(remotePath).getFileName().toString());
                    info.setPath(remotePath);
                    info.setDirectory(attrs.isDirectory());
                    info.setSize(attrs.getSize());
                    info.setLastModified(attrs.getModifyTime().toMillis());
                    info.setPermissions(formatPermissions(attrs.getPermissions()));

                    return info;
        } catch (Exception e) {
            log.error("获取文件信息失败: {}", remotePath, e);
            throw new BusinessException("获取文件信息失败: " + e.getMessage());
        }
    }

    // ==================== 字节范围读取 / Tail / 命令执行 ====================

    /**
     * 读取远程文件字节范围（FileAccessService SPI 的唯一实现，行为只此一份）
     *
     * @param offset 起始偏移（负数为从文件末尾倒数）
     * @param length 读取长度（&lt;=0 表示读到文件末尾）
     */
    public byte[] getFileBytes(Long hostId, String remotePath, long offset, long length) {
        log.info("读取文件字节范围: hostId={}, path={}, offset={}, length={}", hostId, remotePath, offset, length);
        DeploymentAccess.SshConnection conn = connectSftp(hostId);
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(conn.session())) {
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
        } catch (Exception e) {
            log.error("读取文件字节范围失败: {}", remotePath, e);
            throw new BusinessException("读取文件字节范围失败: " + e.getMessage());
        }
    }

    /**
     * Tail 远程文件（从 offset 起按行消费新增内容，返回新的偏移）
     */
    public long tailFile(Long hostId, String remotePath, long offset, Charset charset,
                         Consumer<String> lineConsumer) {
        log.info("Tail 文件: hostId={}, path={}, offset={}", hostId, remotePath, offset);
        DeploymentAccess.SshConnection conn = connectSftp(hostId);
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(conn.session())) {
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
        } catch (Exception e) {
            log.error("Tail 文件失败: {}", remotePath, e);
            throw new BusinessException("Tail 文件失败: " + e.getMessage());
        }
    }

    /**
     * 在指定主机上执行命令（凭据解析统一走 DeploymentAccess，SSH 传输委托 SshUtil）
     */
    public SshUtil.CommandResult executeCommand(Long hostId, String command, long timeoutMs) {
        HostCredentials conn = deployAccess.credentials(hostId);
        return sshUtil.executeCommand(conn.host(), conn.port(), conn.username(),
                conn.privateKey(), conn.password(), command, timeoutMs);
    }

    // ==================== 私有方法 ====================

    /**
     * 获取同主机 SSH 连接（统一走 DeploymentAccess：主机查询、凭据解密、建连认证）。
     *
     * <p>按 hostId 缓存复用：首次建立连接，后续操作共享同一会话（会话失效自动重连）。
     * 调用方不得关闭返回的连接——生命周期由本模块统一管理。</p>
     */
    private DeploymentAccess.SshConnection connectSftp(Long hostId) {
        DeploymentAccess.SshConnection existing = connectionCache.get(hostId);
        if (existing != null && existing.isOpen()) {
            return existing;
        }
        if (existing != null) {
            connectionCache.remove(hostId, existing);
            existing.close();
        }
        Host host = hostMapper.selectById(hostId);
        if (host == null) {
            throw new BusinessException("主机不存在");
        }
        try {
            DeploymentAccess.SshConnection created = deployAccess.connect(host);
            connectionCache.put(hostId, created);
            return created;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("SSH连接失败: " + e.getMessage());
        }
    }

    /** 应用关闭时释放全部缓存连接 */
    @PreDestroy
    public void shutdownConnections() {
        connectionCache.values().forEach(DeploymentAccess.SshConnection::close);
        connectionCache.clear();
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

}

package com.gameplatform.util;

import cn.hutool.core.util.StrUtil;
import com.gameplatform.config.GamePlatformConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.loader.KeyPairResourceParser;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SSH连接工具类
 * 使用Apache MINA SSHD实现SSH连接和SFTP文件传输
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
public class SshUtil {

    private static final int DEFAULT_PORT = 22;
    private static final int DEFAULT_TIMEOUT = 10000;

    // ===== 连接池配置 =====
    /** 空闲会话超时时间：超过该时长未使用的会话将被后台清理器关闭（默认 5 分钟） */
    private static final long SESSION_IDLE_TIMEOUT_MS = 5 * 60 * 1000L;
    /** 空闲会话清理周期 */
    private static final long REAPER_INTERVAL_MS = 60 * 1000L;

    private final GamePlatformConfig.SshConfig sshConfig;

    // ===== 连接池状态 =====
    /** 共享 SSH 客户端（懒启动，所有命令复用以避免重复初始化开销） */
    private volatile SshClient sharedClient;
    private final Object clientInitLock = new Object();

    /** 主机 → 缓存会话池：相同 HostKey 的命令复用同一已认证会话 */
    private final ConcurrentHashMap<HostKey, CachedSession> sessionPool = new ConcurrentHashMap<>();

    /** 空闲会话清理器（守护线程，不阻止 JVM 退出） */
    private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ssh-session-reaper");
        t.setDaemon(true);
        return t;
    });

    public SshUtil(GamePlatformConfig gamePlatformConfig) {
        this.sshConfig = gamePlatformConfig.getSsh();
        // 启动定期清理任务：每 60 秒扫描一次会话池，关闭空闲超时的会话
        reaper.scheduleAtFixedRate(this::reapIdleSessions,
                REAPER_INTERVAL_MS, REAPER_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 主机标识（作为会话池的 key）
     * 注意：不含密码/私钥字段，避免敏感信息作为 key 长期驻留；
     * 同一 host+port+username 视为同一会话槽位，认证信息变更时通过失效重建处理
     */
    private record HostKey(String host, int port, String username) {
    }

    /**
     * 缓存的会话条目：包装 ClientSession + 锁 + 最后使用时间
     * 锁用于串行化同一会话上的命令执行，避免 MINA SSHD 并发通道的潜在问题
     */
    private static class CachedSession {
        final HostKey key;
        final ClientSession session;
        final ReentrantLock lock = new ReentrantLock();
        volatile long lastUsed;

        CachedSession(HostKey key, ClientSession session) {
            this.key = key;
            this.session = session;
            this.lastUsed = System.currentTimeMillis();
        }

        /** 是否空闲超时 */
        boolean isIdle() {
            return System.currentTimeMillis() - lastUsed > SESSION_IDLE_TIMEOUT_MS;
        }

        /** 是否仍然有效（已开启；会话由 connect() 创建时已完成认证，isOpen 即代表可用） */
        boolean isValid() {
            return session != null && session.isOpen();
        }
    }

    /**
     * 获取共享 SshClient（双重检查锁定懒加载）
     * SshClient 是线程安全的，整个应用共享一个实例
     */
    private SshClient getSharedClient() {
        if (sharedClient == null) {
            synchronized (clientInitLock) {
                if (sharedClient == null) {
                    SshClient c = SshClient.setUpDefaultClient();
                    c.start();
                    sharedClient = c;
                    log.info("SSH 共享客户端已启动");
                }
            }
        }
        return sharedClient;
    }

    /**
     * 获取或创建会话：优先复用池中已认证会话，失效则重建
     */
    private CachedSession getOrCreateSession(String host, int port, String username,
                                              String privateKey, String password,
                                              long timeoutMs) throws Exception {
        HostKey key = new HostKey(host, port, username);

        // 尝试复用缓存会话
        CachedSession cached = sessionPool.get(key);
        if (cached != null) {
            if (cached.isValid()) {
                return cached;
            }
            // 失效：从池中移除并关闭
            if (sessionPool.remove(key, cached)) {
                closeSessionQuietly(cached.session);
                log.debug("SSH 会话失效已重建: {}@{}:{}", username, host, port);
            }
        }

        // 创建新会话
        ClientSession session = connect(getSharedClient(), host, port, username, privateKey, password, timeoutMs);
        if (session == null) {
            throw new RuntimeException("SSH 连接失败: " + username + "@" + host + ":" + port);
        }
        CachedSession entry = new CachedSession(key, session);
        sessionPool.put(key, entry);
        return entry;
    }

    /**
     * 清理空闲会话：周期性扫描池，关闭超过空闲超时的会话
     */
    private void reapIdleSessions() {
        try {
            for (Map.Entry<HostKey, CachedSession> e : sessionPool.entrySet()) {
                CachedSession cs = e.getValue();
                // 仅在未加锁（无活跃命令）且空闲超时时清理
                if (cs.isIdle() && cs.lock.tryLock()) {
                    try {
                        if (cs.isIdle()) {
                            sessionPool.remove(e.getKey(), cs);
                            closeSessionQuietly(cs.session);
                            log.debug("SSH 会话空闲超时被清理: {}", e.getKey());
                        }
                    } finally {
                        cs.lock.unlock();
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("SSH 会话清理异常: {}", ex.getMessage());
        }
    }

    /** 安静关闭会话 */
    private void closeSessionQuietly(ClientSession session) {
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 销毁时清理所有资源：关闭所有会话和共享客户端
     */
    @jakarta.annotation.PreDestroy
    public void destroy() {
        log.info("SSH 连接池销毁中...");
        reaper.shutdownNow();
        for (HostKey key : new ArrayList<>(sessionPool.keySet())) {
            CachedSession cs = sessionPool.remove(key);
            if (cs != null) {
                closeSessionQuietly(cs.session);
            }
        }
        if (sharedClient != null) {
            try {
                sharedClient.close();
            } catch (Exception ignored) {
            }
        }
        log.info("SSH 连接池已销毁");
    }

    /**
     * 测试SSH连接
     *
     * @param host        主机地址
     * @param port        SSH端口
     * @param username    用户名
     * @param privateKey  私钥(已解密)
     * @param password    密码(可选,私钥优先)
     * @param timeoutMs   超时时间(毫秒)
     * @return 是否连接成功
     */
    public boolean testConnection(String host, int port, String username,
                                   String privateKey, String password, long timeoutMs) {
        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();

            ClientSession session = connect(client, host, port, username, privateKey, password, timeoutMs);
            if (session != null) {
                session.close();
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("SSH连接测试失败: {}@{}:{} e:", username, host, port, e);
            return false;
        }
    }

    /**
     * 执行远程命令
     *
     * @param host        主机地址
     * @param port        SSH端口
     * @param username    用户名
     * @param privateKey  私钥(已解密)
     * @param password    密码(可选)
     * @param command     命令
     * @return 命令执行结果
     */
    public CommandResult executeCommand(String host, int port, String username,
                                         String privateKey, String password, String command) {
        return executeCommand(host, port, username, privateKey, password, command,
                sshConfig != null ? sshConfig.getSessionTimeout() : DEFAULT_TIMEOUT);
    }

    /**
     * 执行远程命令
     *
     * @param host        主机地址
     * @param port        SSH端口
     * @param username    用户名
     * @param privateKey  私钥(已解密)
     * @param password    密码(可选)
     * @param command     命令
     * @param timeoutMs   超时时间(毫秒)
     * @return 命令执行结果
     */
    public CommandResult executeCommand(String host, int port, String username,
                                         String privateKey, String password,
                                         String command, long timeoutMs) {
        CommandResult result = new CommandResult();

        try {
            return executeWithRetry(host, port, username, privateKey, password, command, timeoutMs, true);
        } catch (Exception e) {
            log.error("执行远程命令失败: {} - {}", command, e.getMessage());
            result.setSuccess(false);
            result.setError(e.getMessage());
            return result;
        }
    }

    /**
     * 带重试的命令执行：会话失效时自动重建并重试一次
     * 通过连接池复用已认证会话，避免每次命令重新建立 TCP+SSH+认证（约 3s）的开销
     *
     * @param retryOnFailure 首次调用传 true，重试时传 false 避免无限递归
     */
    private CommandResult executeWithRetry(String host, int port, String username,
                                            String privateKey, String password,
                                            String command, long timeoutMs,
                                            boolean retryOnFailure) throws Exception {
        CachedSession cs = getOrCreateSession(host, port, username, privateKey, password, timeoutMs);
        cs.lock.lockInterruptibly();
        try {
            cs.lastUsed = System.currentTimeMillis();

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            int exitCode = 0;
            boolean execSuccess = true;
            String errMsg = null;

            try {
                cs.session.executeRemoteCommand(command, stdout, stderr, StandardCharsets.UTF_8);
            } catch (Exception e) {
                // 检测会话是否在执行期间失效（如服务端超时关闭）
                if (!cs.session.isOpen() && retryOnFailure) {
                    log.warn("SSH 会话在执行期间失效，重建后重试: {}@{}:{}", username, host, port);
                    sessionPool.remove(cs.key, cs);
                    closeSessionQuietly(cs.session);
                    return executeWithRetry(host, port, username, privateKey, password, command, timeoutMs, false);
                }
                // executeRemoteCommand 在 exit code != 0 时会抛异常，但 stdout/stderr 已被填充
                // 解析异常消息中的 exit code（消息格式: "Remote command failed (XXX): ..."）
                execSuccess = false;
                errMsg = e.getMessage();
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("Remote command failed \\((\\d+)\\)").matcher(e.getMessage() == null ? "" : e.getMessage());
                if (m.find()) {
                    exitCode = Integer.parseInt(m.group(1));
                } else {
                    exitCode = -1;
                }
            }

            CommandResult result = new CommandResult();
            result.setExitCode(exitCode);
            result.setOutput(stdout.toString(StandardCharsets.UTF_8));
            String stderrStr = stderr.toString(StandardCharsets.UTF_8);
            result.setError(stderrStr.isEmpty() && errMsg != null ? errMsg : stderrStr);
            // success 仅在 exit code == 0 时为 true；调用方可根据需要检查 exitCode/output 判断业务成功
            result.setSuccess(execSuccess && exitCode == 0);
            return result;
        } finally {
            cs.lock.unlock();
        }
    }

    /**
     * 上传文件
     *
     * @param host         主机地址
     * @param port         SSH端口
     * @param username     用户名
     * @param privateKey   私钥
     * @param password     密码
     * @param localPath    本地文件路径
     * @param remotePath   远程文件路径
     * @return 是否成功
     */
    public boolean uploadFile(String host, int port, String username,
                               String privateKey, String password,
                               String localPath, String remotePath) {
        long timeoutMs = sshConfig != null ? sshConfig.getSessionTimeout() : DEFAULT_TIMEOUT;
        try {
            return executeSftpWithRetry(host, port, username, privateKey, password, timeoutMs, true, (sftp) -> {
                // 确保远程目录存在
                Path remoteParent = Paths.get(remotePath).getParent();
                if (remoteParent != null) {
                    try {
                        // 尝试创建目录，如果已存在会抛出异常
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
                return true;
            });
        } catch (Exception e) {
            log.error("文件上传失败: {} -> {} - {}", localPath, remotePath, e.getMessage());
            return false;
        }
    }

    /**
     * SFTP 操作函数式接口
     */
    @FunctionalInterface
    private interface SftpOperation<T> {
        T execute(SftpClient sftp) throws Exception;
    }

    /**
     * 带重试的 SFTP 操作：通过连接池复用会话，会话失效时重建并重试一次
     */
    private <T> T executeSftpWithRetry(String host, int port, String username,
                                        String privateKey, String password,
                                        long timeoutMs, boolean retryOnFailure,
                                        SftpOperation<T> operation) throws Exception {
        CachedSession cs = getOrCreateSession(host, port, username, privateKey, password, timeoutMs);
        cs.lock.lockInterruptibly();
        try {
            cs.lastUsed = System.currentTimeMillis();
            SftpClientFactory factory = SftpClientFactory.instance();
            try (SftpClient sftp = factory.createSftpClient(cs.session)) {
                return operation.execute(sftp);
            } catch (Exception e) {
                // 检测会话失效：重建并重试一次
                if (!cs.session.isOpen() && retryOnFailure) {
                    log.warn("SSH 会话在 SFTP 操作期间失效，重建后重试: {}@{}:{}", username, host, port);
                    sessionPool.remove(cs.key, cs);
                    closeSessionQuietly(cs.session);
                    return executeSftpWithRetry(host, port, username, privateKey, password, timeoutMs, false, operation);
                }
                throw e;
            }
        } finally {
            cs.lock.unlock();
        }
    }

    /**
     * 下载文件
     *
     * @param host         主机地址
     * @param port         SSH端口
     * @param username     用户名
     * @param privateKey   私钥
     * @param password     密码
     * @param remotePath   远程文件路径
     * @param localPath    本地文件路径
     * @return 是否成功
     */
    public boolean downloadFile(String host, int port, String username,
                                 String privateKey, String password,
                                 String remotePath, String localPath) {
        long timeoutMs = sshConfig != null ? sshConfig.getSessionTimeout() : DEFAULT_TIMEOUT;
        try {
            return executeSftpWithRetry(host, port, username, privateKey, password, timeoutMs, true, (sftp) -> {
                // 确保本地目录存在
                Path localParent = Paths.get(localPath).getParent();
                if (localParent != null) {
                    Files.createDirectories(localParent);
                }

                // 下载文件
                try (InputStream is = sftp.read(remotePath);
                     OutputStream os = Files.newOutputStream(Paths.get(localPath),
                             StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    is.transferTo(os);
                }

                log.info("文件下载成功: {} -> {}", remotePath, localPath);
                return true;
            });
        } catch (Exception e) {
            log.error("文件下载失败: {} -> {} - {}", remotePath, localPath, e.getMessage());
            return false;
        }
    }

    /**
     * 列出远程目录文件
     *
     * @param host         主机地址
     * @param port         SSH端口
     * @param username     用户名
     * @param privateKey   私钥
     * @param password     密码
     * @param remotePath   远程目录路径
     * @return 文件列表
     */
    public List<RemoteFileInfo> listFiles(String host, int port, String username,
                                           String privateKey, String password, String remotePath) {
        List<RemoteFileInfo> files = new ArrayList<>();
        long timeoutMs = sshConfig != null ? sshConfig.getSessionTimeout() : DEFAULT_TIMEOUT;
        try {
            return executeSftpWithRetry(host, port, username, privateKey, password, timeoutMs, true, (sftp) -> {
                Iterable<SftpClient.DirEntry> entries = sftp.readDir(remotePath);

                List<RemoteFileInfo> result = new ArrayList<>();
                for (SftpClient.DirEntry entry : entries) {
                    RemoteFileInfo info = new RemoteFileInfo();
                    info.setName(entry.getFilename());
                    info.setPath(remotePath + "/" + entry.getFilename());
                    info.setDirectory(entry.getAttributes().isDirectory());
                    info.setSize(entry.getAttributes().getSize());
                    info.setLastModified(entry.getAttributes().getModifyTime().toMillis());
                    result.add(info);
                }
                return result;
            });
        } catch (Exception e) {
            log.error("列出远程目录失败: {} - {}", remotePath, e.getMessage());
            return files;
        }
    }

    /**
     * 删除远程文件
     *
     * @param host         主机地址
     * @param port         SSH端口
     * @param username     用户名
     * @param privateKey   私钥
     * @param password     密码
     * @param remotePath   远程文件路径
     * @return 是否成功
     */
    public boolean deleteFile(String host, int port, String username,
                               String privateKey, String password, String remotePath) {
        long timeoutMs = sshConfig != null ? sshConfig.getSessionTimeout() : DEFAULT_TIMEOUT;
        try {
            return executeSftpWithRetry(host, port, username, privateKey, password, timeoutMs, true, (sftp) -> {
                sftp.remove(remotePath);
                log.info("远程文件删除成功: {}", remotePath);
                return true;
            });
        } catch (Exception e) {
            log.error("删除远程文件失败: {} - {}", remotePath, e.getMessage());
            return false;
        }
    }

    /**
     * 获取主机资源使用情况
     *
     * @param host       主机地址
     * @param port       SSH端口
     * @param username   用户名
     * @param privateKey 私钥
     * @param password   密码
     * @return 资源信息
     */
    public ResourceInfo getResourceInfo(String host, int port, String username,
                                         String privateKey, String password) {
        ResourceInfo info = new ResourceInfo();
        // 跟踪是否有任何命令成功执行（用于判定 SSH 连接是否可用）
        // 即使部分命令失败（如 top 不存在），只要有一条成功就认为 SSH 连接正常
        // 如果所有命令都失败，说明 SSH 连接本身有问题（如连接超时），应返回 success=false
        boolean anyCommandSuccess = false;

        try {
            // ========== CPU信息 ==========
            // 获取CPU核心数
            CommandResult cpuCoresResult = executeCommand(host, port, username, privateKey, password,
                    "nproc");
            if (cpuCoresResult.isSuccess() && StrUtil.isNotBlank(cpuCoresResult.getOutput())) {
                info.setCpuCores(Integer.parseInt(cpuCoresResult.getOutput().trim()));
                anyCommandSuccess = true;
            }

            // 获取CPU型号
            CommandResult cpuModelResult = executeCommand(host, port, username, privateKey, password,
                    "cat /proc/cpuinfo | grep 'model name' | head -1 | cut -d':' -f2 | sed 's/^ *//'");
            if (cpuModelResult.isSuccess() && StrUtil.isNotBlank(cpuModelResult.getOutput())) {
                info.setCpuModel(cpuModelResult.getOutput().trim());
                anyCommandSuccess = true;
            }

            // 获取CPU使用率
            CommandResult cpuResult = executeCommand(host, port, username, privateKey, password,
                    "top -bn1 | grep 'Cpu(s)' | awk '{print $2}' | cut -d'%' -f1");
            if (cpuResult.isSuccess() && StrUtil.isNotBlank(cpuResult.getOutput())) {
                info.setCpuUsage(Double.parseDouble(cpuResult.getOutput().trim()));
            }

            // ========== 内存信息 ==========
            // 获取内存详细信息 (total, used, free)
            CommandResult memDetailResult = executeCommand(host, port, username, privateKey, password,
                    "free -m | grep Mem | awk '{print $2, $3, $4}'");
            if (memDetailResult.isSuccess() && StrUtil.isNotBlank(memDetailResult.getOutput())) {
                String[] memParts = memDetailResult.getOutput().trim().split("\\s+");
                if (memParts.length >= 3) {
                    info.setMemoryTotal(Long.parseLong(memParts[0]));
                    info.setMemoryUsed(Long.parseLong(memParts[1]));
                    info.setMemoryFree(Long.parseLong(memParts[2]));
                }
                anyCommandSuccess = true;
            }

            // 获取内存使用率
            CommandResult memResult = executeCommand(host, port, username, privateKey, password,
                    "free | grep Mem | awk '{print ($3/$2) * 100.0}'");
            if (memResult.isSuccess() && StrUtil.isNotBlank(memResult.getOutput())) {
                info.setMemoryUsage(Double.parseDouble(memResult.getOutput().trim()));
            }

            // ========== 磁盘信息 ==========
            // 获取磁盘详细信息 (total, used, available)
            CommandResult diskDetailResult = executeCommand(host, port, username, privateKey, password,
                    "df -BG / | tail -1 | awk '{print $2, $3, $4}' | sed 's/G//g'");
            if (diskDetailResult.isSuccess() && StrUtil.isNotBlank(diskDetailResult.getOutput())) {
                String[] diskParts = diskDetailResult.getOutput().trim().split("\\s+");
                if (diskParts.length >= 3) {
                    info.setDiskTotal(Long.parseLong(diskParts[0]));
                    info.setDiskUsed(Long.parseLong(diskParts[1]));
                    info.setDiskFree(Long.parseLong(diskParts[2]));
                }
                anyCommandSuccess = true;
            }

            // 获取磁盘使用率
            CommandResult diskResult = executeCommand(host, port, username, privateKey, password,
                    "df -h / | tail -1 | awk '{print $5}' | cut -d'%' -f1");
            if (diskResult.isSuccess() && StrUtil.isNotBlank(diskResult.getOutput())) {
                info.setDiskUsage(Double.parseDouble(diskResult.getOutput().trim()));
            }

            // ========== 网络信息 ==========
            // 获取网络流量统计
            CommandResult netResult = executeCommand(host, port, username, privateKey, password,
                    "cat /proc/net/dev | grep -E 'eth0|ens33|ens192|enp' | head -1 | awk '{print $2, $10}'");
            if (netResult.isSuccess() && StrUtil.isNotBlank(netResult.getOutput())) {
                String[] netParts = netResult.getOutput().trim().split("\\s+");
                if (netParts.length >= 2) {
                    info.setNetworkRxBytes(Long.parseLong(netParts[0]));
                    info.setNetworkTxBytes(Long.parseLong(netParts[1]));
                }
                anyCommandSuccess = true;
            }

            // 至少一条命令成功才认为 SSH 连接可用；全部失败说明连接本身有问题
            info.setSuccess(anyCommandSuccess);
            if (!anyCommandSuccess) {
                info.setError("所有 SSH 命令均执行失败，可能是连接超时或认证失败");
            }
        } catch (Exception e) {
            log.error("获取主机资源信息失败: {}", e.getMessage());
            info.setSuccess(false);
            info.setError(e.getMessage());
        }

        return info;
    }

    /**
     * 扫描端口占用情况
     *
     * @param host       主机地址
     * @param port       SSH端口
     * @param username   用户名
     * @param privateKey 私钥
     * @param password   密码
     * @param portRange  端口范围(如: 25565-25575)
     * @return 端口占用列表
     */
    public List<PortInfo> scanPorts(String host, int port, String username,
                                     String privateKey, String password, String portRange) {
        List<PortInfo> ports = new ArrayList<>();

        try {
            // 使用netstat或ss命令扫描端口
            String command = String.format("netstat -tuln | grep -E ':%s' || ss -tuln | grep -E ':%s'",
                    portRange.replace("-", ":"), portRange.replace("-", ":"));

            CommandResult result = executeCommand(host, port, username, privateKey, password, command);
            if (result.isSuccess()) {
                String[] lines = result.getOutput().split("\n");
                for (String line : lines) {
                    if (StrUtil.isNotBlank(line)) {
                        PortInfo portInfo = parsePortLine(line);
                        if (portInfo != null) {
                            ports.add(portInfo);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("扫描端口失败: {}", e.getMessage());
        }

        return ports;
    }

    /**
     * 检查指定端口是否被占用
     *
     * @param host        主机地址
     * @param port        SSH端口
     * @param username    用户名
     * @param privateKey  私钥(已解密)
     * @param password    密码(可选)
     * @param targetPort  要检查的目标端口
     * @return 端口检查结果
     */
    public PortCheckResult checkPort(String host, int port, String username,
                                      String privateKey, String password, int targetPort) {
        PortCheckResult result = new PortCheckResult();
        result.setPort(targetPort);

        try {
            // 使用 ss 或 netstat 检查端口占用情况（含进程信息）
            // -t TCP, -u UDP, -l listening, -n numeric, -p process(需要root权限)
            String command = String.format(
                    "ss -tulnp 2>/dev/null | grep -E ':%d( |$)' || netstat -tulnp 2>/dev/null | grep -E ':%d( |$)'",
                    targetPort, targetPort);

            CommandResult cmdResult = executeCommand(host, port, username, privateKey, password, command);

            if (cmdResult.isSuccess()) {
                String output = cmdResult.getOutput();
                if (StrUtil.isBlank(output)) {
                    // 端口未被占用
                    result.setAvailable(true);
                    result.setUsedBy(null);
                } else {
                    // 端口被占用，尝试解析进程信息
                    result.setAvailable(false);
                    result.setUsedBy(parseProcessInfo(output));
                }
            } else {
                // 命令执行失败，保守起见认为端口可用（避免阻塞部署）
                result.setAvailable(true);
                result.setError(cmdResult.getError());
            }
        } catch (Exception e) {
            log.error("检查端口占用失败: port={} - {}", targetPort, e.getMessage());
            result.setAvailable(false);
            result.setError(e.getMessage());
        }

        return result;
    }

    /**
     * 解析端口占用进程信息
     * 支持 ss 的 users:(("process",pid=xxx)) 和 netstat 的 pid/process 格式
     *
     * @param output 命令输出
     * @return 进程名，无法解析时返回 null
     */
    private String parseProcessInfo(String output) {
        try {
            // ss 输出格式: users:(("java",pid=1234,fd=21))
            int usersIdx = output.indexOf("users:((\"");
            if (usersIdx >= 0) {
                int start = usersIdx + "users:((\"".length();
                int end = output.indexOf("\"", start);
                if (end > start) {
                    return output.substring(start, end);
                }
            }

            // netstat 输出格式: 1234/java (最后一列)
            String[] lines = output.trim().split("\n");
            String lastLine = lines[lines.length - 1].trim();
            String[] parts = lastLine.split("\\s+");
            String lastPart = parts[parts.length - 1];

            int slashIdx = lastPart.lastIndexOf('/');
            if (slashIdx >= 0 && slashIdx < lastPart.length() - 1) {
                return lastPart.substring(slashIdx + 1);
            }

            return null;
        } catch (Exception e) {
            log.debug("解析进程信息失败: {}", output);
            return null;
        }
    }

    /**
     * 建立SSH连接
     */
    private ClientSession connect(SshClient client, String host, int port,
                                   String username, String privateKey,
                                   String password, long timeoutMs) throws Exception {
        int connectTimeout = sshConfig != null ? sshConfig.getConnectTimeout() : DEFAULT_TIMEOUT;

        ClientSession session = client.connect(username, host, port)
                .verify(connectTimeout, TimeUnit.MILLISECONDS)
                .getSession();

        // 认证方式: 优先使用私钥
        if (StrUtil.isNotBlank(privateKey)) {
            // 解析私钥
            KeyPair keyPair = parsePrivateKey(privateKey);
            if (keyPair != null) {
                session.addPublicKeyIdentity(keyPair);
            }
        }

        // 如果有密码,添加密码认证
        if (StrUtil.isNotBlank(password)) {
            session.addPasswordIdentity(password);
        }

        // 完成认证
        if (!session.auth().verify(timeoutMs, TimeUnit.MILLISECONDS).isSuccess()) {
            log.error("SSH认证失败: {}@{}:{}", username, host, port);
            return null;
        }

        return session;
    }

    /**
     * 解析私钥（支持 PEM 格式和 OpenSSH 格式）
     * 使用 Apache MINA SSHD 自带的 KeyPairResourceParser 解析
     */
    private KeyPair parsePrivateKey(String privateKey) {
        if (StrUtil.isBlank(privateKey)) {
            return null;
        }
        try {
            // 组合 OpenSSH 和 PEM 两种格式的解析器
            KeyPairResourceParser parser = KeyPairResourceParser.aggregate(
                    org.apache.sshd.common.config.keys.loader.openssh.OpenSSHKeyPairResourceParser.INSTANCE,
                    org.apache.sshd.common.config.keys.loader.pem.PEMResourceParserUtils.PROXY
            );
            org.apache.sshd.common.NamedResource resourceKey = org.apache.sshd.common.NamedResource.ofName("private-key");
            java.util.Collection<KeyPair> keyPairs = parser.loadKeyPairs(
                    null, resourceKey, null, privateKey);
            if (keyPairs != null && !keyPairs.isEmpty()) {
                return keyPairs.iterator().next();
            }
            log.error("解析私钥失败: 未解析到任何密钥对，请检查私钥格式");
            return null;
        } catch (Exception e) {
            log.error("解析私钥失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析端口行
     */
    private PortInfo parsePortLine(String line) {
        try {
            // 示例行: tcp  0  0  0.0.0.0:25565  0.0.0.0:*  LISTEN
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 5) {
                PortInfo info = new PortInfo();
                info.setProtocol(parts[0]);

                // 解析端口号
                String[] addressParts = parts[3].split(":");
                if (addressParts.length >= 2) {
                    info.setPort(Integer.parseInt(addressParts[addressParts.length - 1]));
                }

                info.setState(parts.length > 5 ? parts[5] : parts[4]);
                return info;
            }
        } catch (Exception e) {
            log.debug("解析端口行失败: {}", line);
        }
        return null;
    }

    // ========== 内部类 ==========

    /**
     * 命令执行结果
     */
    @lombok.Data
    public static class CommandResult {
        /**
         * 是否成功
         */
        private boolean success;

        /**
         * 退出码
         */
        private int exitCode;

        /**
         * 标准输出（初始化为空字符串，避免命令执行异常时返回 null 导致 NPE）
         */
        private String output = "";

        /**
         * 错误输出（初始化为空字符串，避免命令执行异常时返回 null 导致 NPE）
         */
        private String error = "";
    }

    /**
     * 远程文件信息
     */
    @lombok.Data
    public static class RemoteFileInfo {
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
         * 文件大小
         */
        private long size;

        /**
         * 最后修改时间
         */
        private long lastModified;
    }

    /**
     * 资源信息
     */
    @lombok.Data
    public static class ResourceInfo {
        /**
         * 是否成功获取
         */
        private boolean success;

        // ========== CPU信息 ==========
        /**
         * CPU核心数
         */
        private Integer cpuCores;

        /**
         * CPU使用率(%)
         */
        private Double cpuUsage;

        /**
         * CPU型号
         */
        private String cpuModel;

        // ========== 内存信息 ==========
        /**
         * 总内存(MB)
         */
        private Long memoryTotal;

        /**
         * 已用内存(MB)
         */
        private Long memoryUsed;

        /**
         * 空闲内存(MB)
         */
        private Long memoryFree;

        /**
         * 内存使用率(%)
         */
        private Double memoryUsage;

        // ========== 磁盘信息 ==========
        /**
         * 总磁盘(GB)
         */
        private Long diskTotal;

        /**
         * 已用磁盘(GB)
         */
        private Long diskUsed;

        /**
         * 空闲磁盘(GB)
         */
        private Long diskFree;

        /**
         * 磁盘使用率(%)
         */
        private Double diskUsage;

        // ========== 网络信息 ==========
        /**
         * 接收字节数
         */
        private Long networkRxBytes;

        /**
         * 发送字节数
         */
        private Long networkTxBytes;

        /**
         * 错误信息
         */
        private String error;
    }

    /**
     * 端口信息
     */
    @lombok.Data
    public static class PortInfo {
        /**
         * 协议
         */
        private String protocol;

        /**
         * 端口号
         */
        private int port;

        /**
         * 状态
         */
        private String state;
    }

    /**
     * 端口检查结果
     */
    @lombok.Data
    public static class PortCheckResult {
        /**
         * 目标端口
         */
        private int port;

        /**
         * 是否可用(未被占用)
         */
        private boolean available;

        /**
         * 占用进程名(不可用时)
         */
        private String usedBy;

        /**
         * 错误信息
         */
        private String error;
    }

}

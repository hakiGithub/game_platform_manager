package com.gameplatform.plugin.service;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.deploy.HostCredentials;
import com.gameplatform.plugin.service.SshTunnelService.TunnelHandle;
import com.gameplatform.util.SshUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSH 隧道管理器（ADR-0009，宿主实现）。
 * <p>
 * 承载 {@link SshTunnelService} SPI 的全部隧道生命周期规则：
 * <ul>
 *   <li>去重键 (ownerPluginId, 凭据来源, remoteHost, remotePort)：同插件对同一
 *       目标重复 open 返回同一句柄并叠加引用计数；跨插件不共享</li>
 *   <li>{@code openByHost} 复用 {@link SshUtil} 连接池会话并钉住（reaper 跳过）；
 *       {@code openWithCredentials} 持有专用 SshClient+Session（不入池，凭据隔离）</li>
 *   <li>本地转发端口仅绑定 127.0.0.1，端口由 OS 随机分配</li>
 *   <li>三层关闭兜底：close() 引用计数归零 → 插件卸载强制清理 → 宿主删除主机联动</li>
 * </ul>
 * 插件子容器通过 {@link #forPlugin(String)} 获得绑定 pluginId 的
 * {@link SshTunnelService} 实例（与 ExtensionClient 绑定 pluginId 同模式）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SshTunnelManager {

    /** 专用会话连接/认证超时（毫秒） */
    private static final long CONNECT_TIMEOUT_MS = 10_000;

    private final SshUtil sshUtil;
    private final DeploymentAccess deploymentAccess;

    /** 隧道 ID → 隧道条目 */
    private final Map<String, TunnelEntry> tunnelsById = new ConcurrentHashMap<>();
    /** 去重键 → 隧道条目 */
    private final Map<String, TunnelEntry> tunnelsByKey = new ConcurrentHashMap<>();
    /** 开/关隧道串行化（低频操作，粗粒度锁即可） */
    private final Object tunnelLock = new Object();

    /**
     * 创建绑定指定插件 ID 的 SPI 实例（注册进插件子容器）。
     */
    public SshTunnelService forPlugin(String pluginId) {
        return new PluginBoundService(pluginId);
    }

    // ==================== 开隧道 ====================

    /**
     * 用平台已登记主机的凭据开隧道（复用 SshUtil 会话池，会话被钉住）。
     */
    TunnelHandle openByHost(String ownerPluginId, Long hostId, String remoteHost, int remotePort) {
        validateTarget(remoteHost, remotePort);
        String key = dedupKey(ownerPluginId, "host:" + hostId, remoteHost, remotePort);

        synchronized (tunnelLock) {
            TunnelEntry existing = reuseOrEvict(key);
            if (existing != null) {
                return existing.toHandle();
            }

            HostCredentials cred = deploymentAccess.credentials(hostId);
            ClientSession session;
            try {
                session = sshUtil.acquirePinnedSession(
                        cred.host(), cred.port(), cred.username(), cred.privateKey(), cred.password());
            } catch (Exception e) {
                throw new BusinessException("SSH 隧道建立失败（hostId=" + hostId + "）: " + e.getMessage());
            }
            try {
                TunnelEntry entry = createEntry(ownerPluginId, hostId, null,
                        remoteHost, remotePort, session, null, key);
                return entry.toHandle();
            } catch (Exception e) {
                sshUtil.releasePinnedSession(session);
                throw new BusinessException("SSH 本地端口转发创建失败: " + e.getMessage());
            }
        }
    }

    /**
     * 用插件自带凭据开隧道（专用 SshClient+Session，不入共享池，宿主不落库不写日志）。
     */
    TunnelHandle openWithCredentials(String ownerPluginId, SshTunnelService.SshEndpoint ssh,
                                     String remoteHost, int remotePort) {
        if (ssh == null) {
            throw new BusinessException("SSH 端点凭据不能为空");
        }
        validateTarget(remoteHost, remotePort);
        String fingerprint = credentialFingerprint(ssh);
        String key = dedupKey(ownerPluginId, "cred:" + fingerprint, remoteHost, remotePort);

        synchronized (tunnelLock) {
            TunnelEntry existing = reuseOrEvict(key);
            if (existing != null) {
                return existing.toHandle();
            }

            SshClient client = SshClient.setUpDefaultClient();
            client.start();
            ClientSession session = null;
            try {
                session = client.connect(ssh.user(), ssh.host(), ssh.port())
                        .verify(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .getSession();
                if (ssh.privateKey() != null && !ssh.privateKey().isBlank()) {
                    java.security.KeyPair keyPair = SshUtil.parsePrivateKey(ssh.privateKey());
                    if (keyPair != null) {
                        session.addPublicKeyIdentity(keyPair);
                    }
                }
                if (ssh.password() != null && !ssh.password().isBlank()) {
                    session.addPasswordIdentity(ssh.password());
                }
                if (!session.auth().verify(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS).isSuccess()) {
                    throw new BusinessException("SSH 认证失败: " + ssh.user() + "@" + ssh.host() + ":" + ssh.port());
                }
                TunnelEntry entry = createEntry(ownerPluginId, null, fingerprint,
                        remoteHost, remotePort, session, client, key);
                return entry.toHandle();
            } catch (BusinessException e) {
                closeDedicatedSessionQuietly(session, client);
                throw e;
            } catch (Exception e) {
                closeDedicatedSessionQuietly(session, client);
                throw new BusinessException("SSH 隧道建立失败: " + e.getMessage());
            }
        }
    }

    // ==================== 关隧道 ====================

    /**
     * 关闭隧道（幂等）：引用计数减 1，减至 0 才真正关闭转发与 SSH 资源。
     * 只允许关闭 handle.ownerPluginId 匹配的句柄。
     */
    void close(TunnelHandle handle) {
        if (handle == null) {
            return;
        }
        TunnelEntry entry = tunnelsById.get(handle.id());
        if (entry == null || !entry.ownerPluginId.equals(handle.ownerPluginId())) {
            // 已整体关闭（幂等）或越权关闭他人句柄：直接忽略
            return;
        }
        int remaining = entry.refCount.updateAndGet(c -> Math.max(0, c - 1));
        if (remaining == 0) {
            synchronized (tunnelLock) {
                if (!entry.closed) {
                    removeEntry(entry);
                }
            }
        }
    }

    /**
     * 插件 stop/unload 兜底：强制关闭该插件的全部隧道（无视引用计数）。
     *
     * @return 关闭的隧道数
     */
    public int closeAllForPlugin(String pluginId) {
        List<TunnelEntry> toClose = tunnelsById.values().stream()
                .filter(e -> pluginId.equals(e.ownerPluginId) && !e.closed)
                .toList();
        if (toClose.isEmpty()) {
            return 0;
        }
        synchronized (tunnelLock) {
            for (TunnelEntry entry : toClose) {
                if (!entry.closed) {
                    removeEntry(entry);
                }
            }
        }
        log.info("[SshTunnel] 插件 [{}] 卸载，强制关闭 {} 条隧道", pluginId, toClose.size());
        return toClose.size();
    }

    /**
     * 宿主删除主机联动：关闭该 hostId 开出的全部（平台凭据）隧道。
     *
     * @return 关闭的隧道数
     */
    public int closeAllForHost(Long hostId) {
        List<TunnelEntry> toClose = tunnelsById.values().stream()
                .filter(e -> hostId.equals(e.hostId) && !e.closed)
                .toList();
        if (toClose.isEmpty()) {
            return 0;
        }
        synchronized (tunnelLock) {
            for (TunnelEntry entry : toClose) {
                if (!entry.closed) {
                    removeEntry(entry);
                }
            }
        }
        log.info("[SshTunnel] 主机 [{}] 删除，联动关闭 {} 条隧道", hostId, toClose.size());
        return toClose.size();
    }

    // ==================== 私有方法 ====================

    /**
     * 命中去重键时复用（引用计数 +1）；条目已死（会话失效/已关闭）则清理后返回 null 触发重建。
     */
    private TunnelEntry reuseOrEvict(String key) {
        TunnelEntry existing = tunnelsByKey.get(key);
        if (existing == null) {
            return null;
        }
        if (!existing.closed && existing.session.isOpen()) {
            existing.refCount.incrementAndGet();
            return existing;
        }
        synchronized (tunnelLock) {
            if (!existing.closed) {
                removeEntry(existing);
            }
        }
        return null;
    }

    private TunnelEntry createEntry(String ownerPluginId, Long hostId, String credentialFingerprint,
                                    String remoteHost, int remotePort,
                                    ClientSession session, SshClient dedicatedClient, String key) throws Exception {
        // 本地端口仅绑定回环，端口由 OS 随机分配（bind :0 后取实际端口）
        SshdSocketAddress bound = session.startLocalPortForwarding(
                new SshdSocketAddress("127.0.0.1", 0),
                new SshdSocketAddress(remoteHost, remotePort));

        TunnelEntry entry = new TunnelEntry(
                UUID.randomUUID().toString(), ownerPluginId, hostId, credentialFingerprint,
                remoteHost, remotePort, bound.getPort(), bound, session, dedicatedClient, key);
        tunnelsById.put(entry.id, entry);
        tunnelsByKey.put(key, entry);
        log.info("[SshTunnel] 已开启: plugin={}, target={}:{}, localPort={}, source={}",
                ownerPluginId, remoteHost, remotePort, bound.getPort(),
                hostId != null ? "host:" + hostId : "credentials");
        return entry;
    }

    /**
     * 真正关闭隧道条目：停止本地转发；平台凭据会话释放钉住（留在池中），
     * 专用会话连 client 一并关闭。
     */
    private void removeEntry(TunnelEntry entry) {
        entry.closed = true;
        tunnelsById.remove(entry.id);
        tunnelsByKey.remove(entry.key, entry);
        try {
            entry.session.stopLocalPortForwarding(entry.boundAddress);
        } catch (Exception e) {
            log.debug("[SshTunnel] 停止本地转发异常（会话可能已失效）: {}", e.getMessage());
        }
        if (entry.dedicatedClient != null) {
            closeDedicatedSessionQuietly(entry.session, entry.dedicatedClient);
        } else {
            sshUtil.releasePinnedSession(entry.session);
        }
        log.info("[SshTunnel] 已关闭: plugin={}, target={}:{}, localPort={}",
                entry.ownerPluginId, entry.remoteHost, entry.remotePort, entry.localPort);
    }

    private void closeDedicatedSessionQuietly(ClientSession session, SshClient client) {
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
            }
        }
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void validateTarget(String remoteHost, int remotePort) {
        if (remoteHost == null || remoteHost.isBlank()) {
            throw new BusinessException("remoteHost 不能为空");
        }
        if (remotePort <= 0 || remotePort > 65535) {
            throw new BusinessException("remotePort 非法: " + remotePort);
        }
    }

    private String dedupKey(String ownerPluginId, String source, String remoteHost, int remotePort) {
        return ownerPluginId + "|" + source + "|" + remoteHost + ":" + remotePort;
    }

    /**
     * 凭据指纹（SHA-256，仅用于去重键，不入日志）：不同插件凭据同目标不共享，
     * 同插件同凭据同目标复用。
     */
    private String credentialFingerprint(SshTunnelService.SshEndpoint ssh) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((ssh.host() + "|" + ssh.port() + "|" + ssh.user()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (ssh.password() != null) {
                digest.update(ssh.password().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (ssh.privateKey() != null) {
                digest.update(ssh.privateKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            // SHA-256 必然可用；兜底用身份哈希
            return Integer.toHexString(System.identityHashCode(ssh));
        }
    }

    // ==================== 内部类 ====================

    /** 隧道条目：一条实际存在的本地端口转发及其记账信息 */
    private static class TunnelEntry {
        final String id;
        final String ownerPluginId;
        /** 平台凭据隧道的主机 ID；专用凭据隧道为 null */
        final Long hostId;
        final String remoteHost;
        final int remotePort;
        final AtomicInteger refCount = new AtomicInteger(1);
        final int localPort;
        /** startLocalPortForwarding 返回的绑定地址（stop 时需回传同一地址） */
        final SshdSocketAddress boundAddress;
        final ClientSession session;
        /** 专用凭据隧道的独立客户端（随隧道关闭）；平台凭据隧道为 null */
        final SshClient dedicatedClient;
        final String key;
        volatile boolean closed = false;

        TunnelEntry(String id, String ownerPluginId, Long hostId, String credentialFingerprint,
                    String remoteHost, int remotePort, int localPort, SshdSocketAddress boundAddress,
                    ClientSession session, SshClient dedicatedClient, String key) {
            this.id = id;
            this.ownerPluginId = ownerPluginId;
            this.hostId = hostId;
            this.remoteHost = remoteHost;
            this.remotePort = remotePort;
            this.localPort = localPort;
            this.boundAddress = boundAddress;
            this.session = session;
            this.dedicatedClient = dedicatedClient;
            this.key = key;
        }

        TunnelHandle toHandle() {
            return new TunnelHandle(id, localPort, remoteHost, remotePort, ownerPluginId);
        }
    }

    /** 绑定 pluginId 的 SPI 视图（注册进插件子容器，与 ExtensionClient 绑定模式一致） */
    private class PluginBoundService implements SshTunnelService {

        private final String pluginId;

        PluginBoundService(String pluginId) {
            this.pluginId = pluginId;
        }

        @Override
        public TunnelHandle openByHost(Long hostId, String remoteHost, int remotePort) {
            return SshTunnelManager.this.openByHost(pluginId, hostId, remoteHost, remotePort);
        }

        @Override
        public TunnelHandle openWithCredentials(SshEndpoint ssh, String remoteHost, int remotePort) {
            return SshTunnelManager.this.openWithCredentials(pluginId, ssh, remoteHost, remotePort);
        }

        @Override
        public void close(TunnelHandle handle) {
            SshTunnelManager.this.close(handle);
        }
    }
}

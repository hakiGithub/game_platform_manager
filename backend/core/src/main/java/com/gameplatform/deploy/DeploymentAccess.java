package com.gameplatform.deploy;

import com.gameplatform.adapter.DeployAdapter.DeployType;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.util.AesUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 部署接入深模块（架构评审 2026-08-13 候选 2，见 docs/design/adr/glossary.md）
 *
 * <p>唯一权威承载两类规则：
 * <ol>
 *   <li>deployType 分类归一：null/空/"native" → LINUX_GSM（"native" 是游戏元数据中的
 *       LinuxGSM 原生部署别名，如 minecraft.yml）；未知非空值快速失败。</li>
 *   <li>Host → SSH 凭据解析：私钥/密码解密、端口默认 22、建连认证（私钥优先密码回退）。</li>
 * </ol>
 *
 * <p>SSH 传输能力仍由 {@link com.gameplatform.util.SshUtil} 承担，本模块只做「接入准备」。
 */
@Slf4j
@Component
public class DeploymentAccess {

    /** 默认 SSH 端口 */
    private static final int DEFAULT_SSH_PORT = 22;
    /** 连接/认证超时（毫秒） */
    private static final long TIMEOUT_MS = 10000;

    private final HostMapper hostMapper;
    private final Supplier<SshClient> sshClientFactory;

    @Autowired
    public DeploymentAccess(HostMapper hostMapper) {
        this(hostMapper, SshClient::setUpDefaultClient);
    }

    /** 内部接缝：测试可注入自定义 SshClient 工厂，不暴露到外部接口 */
    DeploymentAccess(HostMapper hostMapper, Supplier<SshClient> sshClientFactory) {
        this.hostMapper = hostMapper;
        this.sshClientFactory = sshClientFactory;
    }

    /**
     * deployType 分类归一
     *
     * @param deployType 实例的部署类型代码
     * @return 归一后的部署类型
     * @throws BusinessException 未知非空值（快速失败，避免静默走错误部署路径）
     */
    public DeployType classify(String deployType) {
        if (deployType == null || deployType.isBlank()) {
            return DeployType.LINUX_GSM;
        }
        if ("native".equalsIgnoreCase(deployType)) {
            return DeployType.LINUX_GSM;
        }
        DeployType type = DeployType.fromCode(deployType);
        if (type == null) {
            throw new BusinessException("不支持的部署类型: " + deployType);
        }
        return type;
    }

    /**
     * 是否原生（LinuxGSM）部署
     */
    public boolean isNativeDeploy(String deployType) {
        return classify(deployType) == DeployType.LINUX_GSM;
    }

    /**
     * 是否 Docker 类部署（docker / docker-compose / linuxgsm-docker）
     */
    public boolean isDockerDeploy(String deployType) {
        DeployType type = classify(deployType);
        return type == DeployType.DOCKER
                || type == DeployType.DOCKER_COMPOSE
                || type == DeployType.LINUX_GSM_DOCKER;
    }

    /**
     * 解析主机 SSH 凭据（解密私钥/密码，端口默认 22）
     *
     * @throws BusinessException 主机不存在
     */
    public HostCredentials credentials(Host host) {
        if (host == null) {
            throw new BusinessException("主机不存在");
        }
        String privateKey = null;
        if (host.getSshPrivateKey() != null && !host.getSshPrivateKey().isEmpty()) {
            privateKey = AesUtil.decrypt(host.getSshPrivateKey());
        }
        String password = null;
        if (host.getSshPassword() != null && !host.getSshPassword().isEmpty()) {
            password = AesUtil.decrypt(host.getSshPassword());
        }
        return new HostCredentials(
                host.getIpAddress(),
                host.getSshPort() != null ? host.getSshPort() : DEFAULT_SSH_PORT,
                host.getSshUser(),
                privateKey,
                password);
    }

    /**
     * 按主机 ID 解析 SSH 凭据
     *
     * @throws BusinessException 主机不存在
     */
    public HostCredentials credentials(Long hostId) {
        Host host = hostMapper.selectById(hostId);
        if (host == null) {
            throw new BusinessException("主机不存在");
        }
        return credentials(host);
    }

    /**
     * 建立 SSH 连接并认证（私钥优先，密码回退）
     *
     * <p>返回的 {@link SshConnection} 同时持有 ClientSession 与 SshClient，
     * 调用方 try-with-resources 关闭即可，无需关心底层生命周期。
     */
    public SshConnection connect(Host host) throws Exception {
        HostCredentials conn = credentials(host);
        SshClient client = sshClientFactory.get();
        client.start();
        ClientSession session = client.connect(conn.username(), conn.host(), conn.port())
                .verify(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .getSession();

        if (conn.privateKey() != null && !conn.privateKey().isEmpty()) {
            try {
                java.security.KeyPair keyPair = parsePrivateKey(conn.privateKey());
                if (keyPair != null) {
                    session.addPublicKeyIdentity(keyPair);
                }
            } catch (Exception e) {
                log.warn("私钥解析失败，回退到密码认证: {}", e.getMessage());
            }
        }
        if (conn.password() != null && !conn.password().isEmpty()) {
            session.addPasswordIdentity(conn.password());
        }

        if (!session.auth().verify(TIMEOUT_MS, TimeUnit.MILLISECONDS).isSuccess()) {
            throw new BusinessException("SSH认证失败：用户名=" + conn.username()
                    + "，主机=" + conn.host() + ":" + conn.port()
                    + "，请检查主机配置的密码或私钥");
        }
        return new SshConnection(client, session);
    }

    /**
     * 解析私钥字符串为 KeyPair
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
     * 已建立的 SSH 连接，同时持有 ClientSession 与 SshClient，随 close() 一并释放
     */
    public static class SshConnection implements AutoCloseable {
        private final SshClient client;
        private final ClientSession session;

        SshConnection(SshClient client, ClientSession session) {
            this.client = client;
            this.session = session;
        }

        public ClientSession session() {
            return session;
        }

        @Override
        public void close() {
            try {
                if (session.isOpen()) {
                    session.close();
                }
            } catch (Exception e) {
                log.warn("关闭 SSH 会话失败: {}", e.getMessage());
            }
            try {
                client.stop();
            } catch (Exception e) {
                log.warn("关闭 SSH 客户端失败: {}", e.getMessage());
            }
        }
    }
}

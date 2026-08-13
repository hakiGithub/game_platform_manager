package com.gameplatform.deploy;

import com.gameplatform.adapter.DeployAdapter.DeployType;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.util.AesUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * DeploymentAccess 单元测试（架构评审 2026-08-13 候选 2）
 * 锁定 deployType 分类归一与 Host→SSH 凭据解析的唯一权威行为。
 */
@ExtendWith(MockitoExtension.class)
class DeploymentAccessTest {

    @Mock
    private HostMapper hostMapper;

    private DeploymentAccess deploymentAccess() {
        return new DeploymentAccess(hostMapper);
    }

    @Test
    @DisplayName("classify：null/空/native 归一为 LINUX_GSM")
    void classifyDefaultsToLinuxGsm() {
        DeploymentAccess access = deploymentAccess();
        assertEquals(DeployType.LINUX_GSM, access.classify(null));
        assertEquals(DeployType.LINUX_GSM, access.classify(""));
        assertEquals(DeployType.LINUX_GSM, access.classify("  "));
        assertEquals(DeployType.LINUX_GSM, access.classify("native"));
        assertEquals(DeployType.LINUX_GSM, access.classify("NATIVE"));
    }

    @Test
    @DisplayName("classify：已知类型原样返回")
    void classifyKnownTypes() {
        DeploymentAccess access = deploymentAccess();
        assertEquals(DeployType.DOCKER, access.classify("docker"));
        assertEquals(DeployType.DOCKER_COMPOSE, access.classify("docker-compose"));
        assertEquals(DeployType.LINUX_GSM_DOCKER, access.classify("linuxgsm-docker"));
        assertEquals(DeployType.LINUX_GSM, access.classify("linuxgsm"));
    }

    @Test
    @DisplayName("classify：未知非空值快速失败")
    void classifyUnknownThrows() {
        DeploymentAccess access = deploymentAccess();
        BusinessException e = assertThrows(BusinessException.class,
                () -> access.classify("linux-gsm"));
        assertTrue(e.getMessage().contains("linux-gsm"));
    }

    @Test
    @DisplayName("isDockerDeploy/isNativeDeploy 与 classify 语义一致")
    void predicates() {
        DeploymentAccess access = deploymentAccess();
        assertTrue(access.isDockerDeploy("docker"));
        assertTrue(access.isDockerDeploy("docker-compose"));
        assertTrue(access.isDockerDeploy("linuxgsm-docker"));
        assertFalse(access.isDockerDeploy("linuxgsm"));
        assertFalse(access.isDockerDeploy("native"));
        assertFalse(access.isDockerDeploy(null));

        assertTrue(access.isNativeDeploy("linuxgsm"));
        assertTrue(access.isNativeDeploy("native"));
        assertFalse(access.isNativeDeploy("docker"));
        assertTrue(access.isNativeDeploy(null));
    }

    @Test
    @DisplayName("credentials：解密私钥/密码、端口默认 22")
    void credentialsDecrypts() {
        Host host = new Host();
        host.setIpAddress("192.168.1.10");
        host.setSshPort(null);
        host.setSshUser("steam");
        host.setSshPrivateKey(AesUtil.encrypt("priv"));
        host.setSshPassword(AesUtil.encrypt("pass"));

        HostCredentials conn = deploymentAccess().credentials(host);
        assertEquals("192.168.1.10", conn.host());
        assertEquals(22, conn.port());
        assertEquals("steam", conn.username());
        assertEquals("priv", conn.privateKey());
        assertEquals("pass", conn.password());
    }

    @Test
    @DisplayName("credentials：显式端口沿用")
    void credentialsKeepsPort() {
        Host host = new Host();
        host.setIpAddress("192.168.1.10");
        host.setSshPort(2222);
        host.setSshUser("u");
        assertEquals(2222, deploymentAccess().credentials(host).port());
    }

    @Test
    @DisplayName("credentials(hostId)：主机不存在抛异常")
    void credentialsHostIdNotFound() {
        when(hostMapper.selectById(99L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> deploymentAccess().credentials(99L));
    }

    @Test
    @DisplayName("credentials(Host)：空主机抛异常")
    void credentialsNullHost() {
        assertThrows(BusinessException.class, () -> deploymentAccess().credentials((Host) null));
    }
}

package com.gameplatform.service;

import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.deploy.HostCredentials;
import com.gameplatform.dto.HostCreateDTO;
import com.gameplatform.dto.HostUpdateDTO;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.plugin.service.SshTunnelManager;
import com.gameplatform.service.impl.HostServiceImpl;
import com.gameplatform.util.AesUtil;
import com.gameplatform.util.SshUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PLUT-46：主机凭据加解密对称性测试。
 *
 * <p>锁定「写一次（HostServiceImpl）、读一次（DeploymentAccess）」的契约：
 * 任意写路径落库的私钥/密码，读侧单次解密必须还原出明文。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("主机凭据加解密对称性测试（PLUT-46）")
class HostCredentialEncryptionTest {

    private static final String PLAIN_PEM =
            "-----BEGIN RSA PRIVATE KEY-----\n"
            + "MIIBVwIBADANBgkqhkiG9w0BAQEFAASCAUEwggE9AgEAAkEA0Z3VS5JJcds3xfn/\n"
            + "ygWyF8PbnGy0AHB7MhgwKVPSmwaFkYLvFAKE_TEST_KEY_NOT_REAL_AAAAAAAAA=\n"
            + "-----END RSA PRIVATE KEY-----\n";
    private static final String PLAIN_PASSWORD = "p@ssw0rd-123";

    @Mock
    private HostMapper hostMapper;
    @Mock
    private SshUtil sshUtil;
    @Mock
    private SshTunnelManager sshTunnelManager;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private HostServiceImpl hostService;
    private DeploymentAccess deploymentAccess;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // 独立运行（非 Spring 上下文）时，LambdaUpdateWrapper 需要 Host 的列缓存
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Host.class);
    }

    @BeforeEach
    void setUp() {
        // 读侧使用真实 DeploymentAccess，锁定跨层对称性
        deploymentAccess = new DeploymentAccess(hostMapper);
        hostService = new HostServiceImpl(hostMapper, sshUtil, deploymentAccess,
                sshTunnelManager, jdbcTemplate);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
    }

    private HostCreateDTO createDTO() {
        HostCreateDTO dto = new HostCreateDTO();
        dto.setName("h");
        dto.setIp("10.0.0.1");
        dto.setSshPort(22);
        dto.setSshUsername("root");
        return dto;
    }

    private Host capturedInsert() {
        ArgumentCaptor<Host> captor = ArgumentCaptor.forClass(Host.class);
        verify(hostMapper).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("create-明文私钥：落库单次加密，读侧一次解密还原明文")
    void createPrivateKeyStoredOnceAndReadable() {
        when(hostMapper.selectByIpAddress(anyString())).thenReturn(null);
        HostCreateDTO dto = createDTO();
        dto.setSshPrivateKey(PLAIN_PEM);

        hostService.createHost(dto);

        Host saved = capturedInsert();
        assertNotEquals(PLAIN_PEM, saved.getSshPrivateKey(), "私钥必须密文落库");
        assertEquals(PLAIN_PEM, AesUtil.decrypt(saved.getSshPrivateKey()),
                "单次解密必须还原明文（若二次加密此处必失败）");

        HostCredentials cred = deploymentAccess.credentials(saved);
        assertEquals(PLAIN_PEM, cred.privateKey(), "读侧凭据必须是明文 PEM");
    }

    @Test
    @DisplayName("create-DTO 传入已加密串：不二次加密，读侧仍一次还原")
    void createWithAlreadyEncryptedKeyNotDoubleEncrypted() {
        when(hostMapper.selectByIpAddress(anyString())).thenReturn(null);
        HostCreateDTO dto = createDTO();
        String cipher = AesUtil.encrypt(PLAIN_PEM);
        assertTrue(AesUtil.isEncrypted(cipher));
        dto.setSshPrivateKey(cipher);

        hostService.createHost(dto);

        Host saved = capturedInsert();
        assertEquals(cipher, saved.getSshPrivateKey(), "已加密输入应原样落库，不得再加密");
        assertEquals(PLAIN_PEM, AesUtil.decrypt(saved.getSshPrivateKey()));
        assertEquals(PLAIN_PEM, deploymentAccess.credentials(saved).privateKey());
    }

    @Test
    @DisplayName("create-密码：单次加密落库，读侧一次解密还原（零回归）")
    void createPasswordStoredOnceAndReadable() {
        when(hostMapper.selectByIpAddress(anyString())).thenReturn(null);
        HostCreateDTO dto = createDTO();
        dto.setSshPassword(PLAIN_PASSWORD);

        hostService.createHost(dto);

        Host saved = capturedInsert();
        assertNotEquals(PLAIN_PASSWORD, saved.getSshPassword());
        HostCredentials cred = deploymentAccess.credentials(saved);
        assertEquals(PLAIN_PASSWORD, cred.password());
    }

    @Test
    @DisplayName("update-明文私钥：落库单次加密，读侧一次解密还原")
    void updatePrivateKeyStoredOnceAndReadable() {
        Host existing = new Host();
        existing.setId(1L);
        existing.setHostName("h");
        existing.setIpAddress("10.0.0.1");
        existing.setSshPort(22);
        existing.setSshUser("root");
        when(hostMapper.selectById(1L)).thenReturn(existing);

        HostUpdateDTO dto = new HostUpdateDTO();
        dto.setId(1L);
        dto.setSshPrivateKey(PLAIN_PEM);

        hostService.updateHost(dto);

        ArgumentCaptor<Host> captor = ArgumentCaptor.forClass(Host.class);
        verify(hostMapper).updateById(captor.capture());
        Host saved = captor.getValue();
        assertEquals(PLAIN_PEM, AesUtil.decrypt(saved.getSshPrivateKey()));
        assertEquals(PLAIN_PEM, deploymentAccess.credentials(saved).privateKey());
    }

    @Test
    @DisplayName("update-明文密码（key→password 切换）：单次加密且触发旧私钥清除")
    void updatePasswordStoredOnceAndClearsKey() {
        Host existing = new Host();
        existing.setId(1L);
        existing.setIpAddress("10.0.0.1");
        existing.setSshPrivateKey(AesUtil.encrypt(PLAIN_PEM));
        when(hostMapper.selectById(1L)).thenReturn(existing);

        HostUpdateDTO dto = new HostUpdateDTO();
        dto.setId(1L);
        dto.setSshPassword(PLAIN_PASSWORD);

        hostService.updateHost(dto);

        ArgumentCaptor<Host> captor = ArgumentCaptor.forClass(Host.class);
        verify(hostMapper).updateById(captor.capture());
        assertEquals(PLAIN_PASSWORD,
                deploymentAccess.credentials(captor.getValue()).password());
        // 认证方式切换语义：提供新密码时显式清除存量私钥
        verify(hostMapper).update(eq(null), any());
    }

    @Test
    @DisplayName("update-DTO 传入已加密密码串：不二次加密")
    void updateWithAlreadyEncryptedPasswordNotDoubleEncrypted() {
        Host existing = new Host();
        existing.setId(1L);
        existing.setIpAddress("10.0.0.1");
        when(hostMapper.selectById(1L)).thenReturn(existing);

        String cipher = AesUtil.encrypt(PLAIN_PASSWORD);
        HostUpdateDTO dto = new HostUpdateDTO();
        dto.setId(1L);
        dto.setSshPassword(cipher);

        hostService.updateHost(dto);

        ArgumentCaptor<Host> captor = ArgumentCaptor.forClass(Host.class);
        verify(hostMapper).updateById(captor.capture());
        assertEquals(cipher, captor.getValue().getSshPassword());
        assertEquals(PLAIN_PASSWORD,
                deploymentAccess.credentials(captor.getValue()).password());
    }
}

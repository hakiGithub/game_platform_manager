package com.gameplatform.service;

import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.util.AesUtil;
import com.gameplatform.util.SshUtil;
import com.gameplatform.vo.HostsRefreshPreview;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * HostsFileRefresher 单元测试
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("宿主机 hosts 刷新服务测试")
class HostsFileRefresherTest {

    @Mock
    private HostMapper hostMapper;

    @Mock
    private SshUtil sshUtil;

    private HostsFileRefresher refresher;

    private MockedStatic<AesUtil> aesUtilMockedStatic;

    private Host testHost;

    @BeforeEach
    void setUp() {
        testHost = new Host();
        testHost.setId(1L);
        testHost.setHostName("haki-pc");
        testHost.setIpAddress("192.168.111.253");
        testHost.setSshPort(22);
        testHost.setSshUser("haki");
        testHost.setSshPassword("enc-pwd");
        testHost.setSshPrivateKey("enc-key");

        // 默认：解密返回非空字符串
        // 注意：AesUtil.decrypt 是静态方法，无法通过 @Mock 实例 mock，必须使用 MockedStatic
        aesUtilMockedStatic = mockStatic(AesUtil.class);
        aesUtilMockedStatic.when(() -> AesUtil.decrypt("enc-pwd")).thenReturn("decrypted-pwd");
        aesUtilMockedStatic.when(() -> AesUtil.decrypt("enc-key")).thenReturn("decrypted-key");
        when(hostMapper.selectById(1L)).thenReturn(testHost);

        // 凭据解析走真实 DeploymentAccess（其内部调用被 mock 的静态 AesUtil.decrypt）
        refresher = new HostsFileRefresher(hostMapper, sshUtil, new DeploymentAccess(hostMapper));
    }

    @AfterEach
    void tearDown() {
        if (aesUtilMockedStatic != null) {
            aesUtilMockedStatic.close();
        }
    }

    @Test
    @DisplayName("应正确提取 127.0.0.1 行的非系统别名域名")
    void shouldExtractDomainsFromLoopbackLine() {
        // Given: hosts 文件包含 127.0.0.1 行，混合系统别名和真实域名
        String hostsContent = "127.0.0.1 localhost\n" +
                "127.0.0.1 raw.githubusercontent.com github.com\n" +
                "::1 localhost ip6-localhost\n";

        when(sshUtil.executeCommand(
                eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("cat /etc/hosts"), anyLong()))
                .thenReturn(mockResult(hostsContent, true));

        when(sshUtil.executeCommand(
                eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("hostname"), anyLong()))
                .thenReturn(mockResult("haki-pc\n", true));

        when(sshUtil.executeCommand(
                eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("sudo -n true 2>/dev/null"), anyLong()))
                .thenReturn(mockResult("", false));

        // When
        HostsRefreshPreview preview = refresher.previewRefresh(1L);

        // Then: 系统别名 localhost 已过滤，仅保留真实域名
        assertNotNull(preview);
        assertEquals("192.168.111.253", preview.getHostLanIp());
        assertEquals("haki-pc", preview.getHostname());
        assertTrue(preview.getDomainsToRefresh().contains("raw.githubusercontent.com"));
        assertTrue(preview.getDomainsToRefresh().contains("github.com"));
        assertFalse(preview.getDomainsToRefresh().contains("localhost"));
    }

    @Test
    @DisplayName("应排除 hostname 自身（避免改主机名条目）")
    void shouldExcludeHostnameItself() {
        String hostsContent = "127.0.0.1 localhost haki-pc\n" +
                "127.0.0.1 github.com\n";

        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("cat /etc/hosts"), anyLong()))
                .thenReturn(mockResult(hostsContent, true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("hostname"), anyLong()))
                .thenReturn(mockResult("haki-pc\n", true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("sudo -n true 2>/dev/null"), anyLong()))
                .thenReturn(mockResult("", false));

        HostsRefreshPreview preview = refresher.previewRefresh(1L);

        assertTrue(preview.getDomainsToRefresh().contains("github.com"));
        assertFalse(preview.getDomainsToRefresh().contains("haki-pc"),
                "hostname 自身应被排除");
    }

    @Test
    @DisplayName("已是 hostLanIp 的条目应排除（幂等性）")
    void shouldExcludeAlreadyTargetIpEntries() {
        String hostsContent = "127.0.0.1 localhost\n" +
                "192.168.111.253 github.com\n" +  // 已是目标 IP
                "127.0.0.1 raw.githubusercontent.com\n";

        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("cat /etc/hosts"), anyLong()))
                .thenReturn(mockResult(hostsContent, true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("hostname"), anyLong()))
                .thenReturn(mockResult("haki-pc\n", true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("sudo -n true 2>/dev/null"), anyLong()))
                .thenReturn(mockResult("", false));

        HostsRefreshPreview preview = refresher.previewRefresh(1L);

        // github.com 已是目标 IP，不应出现在待改列表
        assertFalse(preview.getDomainsToRefresh().contains("github.com"));
        assertTrue(preview.getDomainsToRefresh().contains("raw.githubusercontent.com"));
    }

    @Test
    @DisplayName("应正确处理 # 行内注释：不当作域名提取，保留在原行")
    void shouldHandleInlineComment() throws Exception {
        // Given: hosts 文件含 # 行内注释（如 #S302 来源标记），格式与宿主机实际文件一致
        String hostsContent = "127.0.0.1 localhost\n" +
                "127.0.0.1 github.com #S302\n" +
                "127.0.0.1 raw.githubusercontent.com #S302\n" +
                "127.0.0.1 ad.example.com #adblock\n";

        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("cat /etc/hosts"), anyLong()))
                .thenReturn(mockResult(hostsContent, true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("hostname"), anyLong()))
                .thenReturn(mockResult("haki-pc\n", true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("sudo -n true 2>/dev/null"), anyLong()))
                .thenReturn(mockResult("", true));

        // When: 预检
        HostsRefreshPreview preview = refresher.previewRefresh(1L);

        // Then: #S302 不应被当作域名
        java.util.List<String> domains = preview.getDomainsToRefresh();
        assertTrue(domains.contains("github.com"));
        assertTrue(domains.contains("raw.githubusercontent.com"));
        assertTrue(domains.contains("ad.example.com"));
        assertFalse(domains.contains("#s302"), "#S302 不应被当作域名");
        assertFalse(domains.contains("#adblock"), "#adblock 不应被当作域名");
        assertFalse(domains.contains("s302"), "无 # 前缀的 S302 也不应出现");

        // === 验证 refreshHosts 生成的新内容：注释保留 + 域名不混入注释 ===
        java.util.concurrent.atomic.AtomicReference<String> uploadedContent = new java.util.concurrent.atomic.AtomicReference<>();
        when(sshUtil.uploadFile(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                contains("hosts-refresh-"), startsWith("/tmp/hosts-refresh-")))
                .thenAnswer(invocation -> {
                    String localPath = invocation.getArgument(5);
                    uploadedContent.set(java.nio.file.Files.readString(java.nio.file.Paths.get(localPath)));
                    return true;
                });
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                matches("sudo cp /etc/hosts /etc/hosts\\.bak\\..+"), anyLong()))
                .thenReturn(mockResult("", true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                matches("sudo cp /tmp/hosts-refresh-.+\\.tmp /etc/hosts"), anyLong()))
                .thenReturn(mockResult("", true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                matches("rm -f /tmp/hosts-refresh-.+\\.tmp"), anyLong()))
                .thenReturn(mockResult("", true));

        // 执行：只刷新 github.com 和 raw.githubusercontent.com
        com.gameplatform.vo.HostsRefreshResult result = refresher.refreshHosts(1L, null,
                java.util.Arrays.asList("github.com", "raw.githubusercontent.com"));

        assertTrue(result.isSuccess());
        assertNotNull(uploadedContent.get(), "应已上传新 hosts 内容");
        String newContent = uploadedContent.get();

        // 找到 192.168.111.253 行（新追加的 LAN IP 行）
        String lanIpLine = null;
        for (String l : newContent.split("\\r?\\n")) {
            if (l.startsWith("192.168.111.253")) {
                lanIpLine = l;
                break;
            }
        }
        assertNotNull(lanIpLine, "应包含 192.168.111.253 行");
        assertTrue(lanIpLine.contains("github.com"));
        assertTrue(lanIpLine.contains("raw.githubusercontent.com"));
        assertFalse(lanIpLine.contains("#"), "LAN IP 行不应包含 # 注释（避免后续域名变注释）");

        // 验证：未选中的域名行应保留原 # 注释
        boolean hasAdblockComment = false;
        for (String l : newContent.split("\\r?\\n")) {
            if (l.startsWith("127.0.0.1") && l.contains("ad.example.com")) {
                assertTrue(l.contains("#adblock"), "未选中域名行的 #adblock 注释应保留");
                hasAdblockComment = true;
            }
            if (l.startsWith("127.0.0.1")) {
                assertFalse(l.contains("github.com"), "github.com 应已从 127.0.0.1 行移除");
                assertFalse(l.contains("raw.githubusercontent.com"),
                        "raw.githubusercontent.com 应已从 127.0.0.1 行移除");
            }
        }
        assertTrue(hasAdblockComment, "未选中域名行应保留在 127.0.0.1");
    }

    @Test
    @DisplayName("无待改域名时应返回空列表")
    void shouldReturnEmptyListWhenNoDomainToRefresh() {
        String hostsContent = "127.0.0.1 localhost\n" +
                "192.168.111.253 github.com raw.githubusercontent.com\n";

        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("cat /etc/hosts"), anyLong()))
                .thenReturn(mockResult(hostsContent, true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("hostname"), anyLong()))
                .thenReturn(mockResult("haki-pc\n", true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("sudo -n true 2>/dev/null"), anyLong()))
                .thenReturn(mockResult("", false));

        HostsRefreshPreview preview = refresher.previewRefresh(1L);

        assertNotNull(preview.getDomainsToRefresh());
        assertTrue(preview.getDomainsToRefresh().isEmpty(),
                "无待改域名时应返回空列表");
    }

    @Test
    @DisplayName("读取 /etc/hosts 失败时应抛出业务异常")
    void shouldThrowWhenReadHostsFails() {
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("cat /etc/hosts"), anyLong()))
                .thenReturn(mockResult("", false));

        assertThrows(RuntimeException.class, () -> refresher.previewRefresh(1L));
    }

    @Test
    @DisplayName("免密 sudo 可用时，应使用 sudo cp 完成刷新")
    void shouldRefreshWithPasswordlessSudo() {
        // Given: hosts 文件包含 1 个待改域名
        String hostsContent = "127.0.0.1 localhost\n" +
                "127.0.0.1 github.com\n";
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("cat /etc/hosts"), anyLong()))
                .thenReturn(mockResult(hostsContent, true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("hostname"), anyLong()))
                .thenReturn(mockResult("haki-pc\n", true));

        // 免密 sudo 可用
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("sudo -n true 2>/dev/null"), anyLong()))
                .thenReturn(mockResult("", true));

        // SFTP 上传成功（localPath 是 Java 临时文件路径，remotePath 是 /tmp/hosts-refresh-xxx.tmp）
        when(sshUtil.uploadFile(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                contains("hosts-refresh-"), startsWith("/tmp/hosts-refresh-")))
                .thenReturn(true);

        // sudo cp 备份 + 覆盖 都成功
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                matches("sudo cp /etc/hosts /etc/hosts\\.bak\\..+"), anyLong()))
                .thenReturn(mockResult("", true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                matches("sudo cp /tmp/hosts-refresh-.+\\.tmp /etc/hosts"), anyLong()))
                .thenReturn(mockResult("", true));

        // 临时文件清理
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                matches("rm -f /tmp/hosts-refresh-.+\\.tmp"), anyLong()))
                .thenReturn(mockResult("", true));

        // When: sudoPassword 为 null（免密 sudo）
        com.gameplatform.vo.HostsRefreshResult result = refresher.refreshHosts(1L, null);

        // Then
        assertTrue(result.isSuccess());
        assertEquals("192.168.111.253", result.getHostLanIp());
        assertTrue(result.getRefreshedDomains().contains("github.com"));
        assertNotNull(result.getBackupPath());
        assertTrue(result.getBackupPath().startsWith("/etc/hosts.bak."));
    }

    @Test
    @DisplayName("免密 sudo 不可用且未提供密码时，应返回失败并提示需要密码")
    void shouldFailWhenSudoPasswordMissing() {
        String hostsContent = "127.0.0.1 localhost\n127.0.0.1 github.com\n";
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("cat /etc/hosts"), anyLong()))
                .thenReturn(mockResult(hostsContent, true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("hostname"), anyLong()))
                .thenReturn(mockResult("haki-pc\n", true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("sudo -n true 2>/dev/null"), anyLong()))
                .thenReturn(mockResult("", false));

        com.gameplatform.vo.HostsRefreshResult result = refresher.refreshHosts(1L, null);

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().toLowerCase().contains("sudo")
                || result.getErrorMessage().toLowerCase().contains("密码"),
                "错误信息应提示 sudo 密码相关");
    }

    @Test
    @DisplayName("提供 sudo 密码时，应使用 echo 'pwd' | sudo -S cp 命令")
    void shouldUseSudoWithPasswordWhenProvided() {
        String hostsContent = "127.0.0.1 localhost\n127.0.0.1 github.com\n";
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("cat /etc/hosts"), anyLong()))
                .thenReturn(mockResult(hostsContent, true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("hostname"), anyLong()))
                .thenReturn(mockResult("haki-pc\n", true));

        // 免密 sudo 不可用（但提供了密码，所以应该跳过 sudo -n true 检测直接用密码）
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("sudo -n true 2>/dev/null"), anyLong()))
                .thenReturn(mockResult("", false));

        when(sshUtil.uploadFile(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                contains("hosts-refresh-"), startsWith("/tmp/hosts-refresh-")))
                .thenReturn(true);

        // 关键断言：使用 echo 'pwd' | sudo -S cp 命令
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                matches("echo 'mypassword' \\| sudo -S cp /etc/hosts /etc/hosts\\.bak\\..+"),
                anyLong()))
                .thenReturn(mockResult("", true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                matches("echo 'mypassword' \\| sudo -S cp /tmp/hosts-refresh-.+\\.tmp /etc/hosts"),
                anyLong()))
                .thenReturn(mockResult("", true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                matches("rm -f /tmp/hosts-refresh-.+\\.tmp"), anyLong()))
                .thenReturn(mockResult("", true));

        com.gameplatform.vo.HostsRefreshResult result = refresher.refreshHosts(1L, "mypassword");

        assertTrue(result.isSuccess());
        assertTrue(result.getRefreshedDomains().contains("github.com"));
    }

    @Test
    @DisplayName("无待改域名时应直接返回成功，不执行 sudo 操作")
    void shouldReturnSuccessWithoutSudoWhenNoDomainToRefresh() {
        String hostsContent = "127.0.0.1 localhost\n" +
                "192.168.111.253 github.com\n"; // 已是目标 IP
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("cat /etc/hosts"), anyLong()))
                .thenReturn(mockResult(hostsContent, true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("hostname"), anyLong()))
                .thenReturn(mockResult("haki-pc\n", true));

        com.gameplatform.vo.HostsRefreshResult result = refresher.refreshHosts(1L, null);

        assertTrue(result.isSuccess());
        assertTrue(result.getRefreshedDomains().isEmpty());
        // 不应调用 sudo 相关命令
        verify(sshUtil, never()).executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("sudo -n true 2>/dev/null"), anyLong());
    }

    @Test
    @DisplayName("sudo 命令失败时应返回失败并保留临时文件路径")
    void shouldReturnFailureWhenSudoCommandFails() {
        String hostsContent = "127.0.0.1 localhost\n127.0.0.1 github.com\n";
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("cat /etc/hosts"), anyLong()))
                .thenReturn(mockResult(hostsContent, true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("hostname"), anyLong()))
                .thenReturn(mockResult("haki-pc\n", true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("sudo -n true 2>/dev/null"), anyLong()))
                .thenReturn(mockResult("", true));
        when(sshUtil.uploadFile(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                contains("hosts-refresh-"), startsWith("/tmp/hosts-refresh-")))
                .thenReturn(true);

        // sudo cp 备份命令失败
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                matches("sudo cp /etc/hosts /etc/hosts\\.bak\\..+"), anyLong()))
                .thenReturn(mockResult("sudo: error", false));

        com.gameplatform.vo.HostsRefreshResult result = refresher.refreshHosts(1L, null);

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
    }

    /**
     * 构造 mock CommandResult
     */
    private SshUtil.CommandResult mockResult(String output, boolean success) {
        SshUtil.CommandResult r = new SshUtil.CommandResult();
        r.setOutput(output);
        r.setSuccess(success);
        r.setExitCode(success ? 0 : 1);
        return r;
    }
}

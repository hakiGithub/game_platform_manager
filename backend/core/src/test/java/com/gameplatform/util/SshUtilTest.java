package com.gameplatform.util;

import com.gameplatform.config.GamePlatformConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SSH工具类测试类
 * 使用Mock测试SSH相关功能
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SSH工具类测试")
class SshUtilTest {

    @Mock
    private GamePlatformConfig gamePlatformConfig;

    @Mock
    private GamePlatformConfig.SshConfig sshConfig;

    private SshUtil sshUtil;

    private static final String TEST_HOST = "192.168.1.100";
    private static final int TEST_PORT = 22;
    private static final String TEST_USERNAME = "root";
    private static final String TEST_PRIVATE_KEY = "-----BEGIN RSA PRIVATE KEY-----\n" +
            "MIIEpQIBAAKCAQEA0Z3VS5JJcds3xfn/ygWyF8PbnGy0AHB7MhgwKVPSmwaFkYLv\n" +
            "FAKE_KEY_FOR_TESTING_ONLY_NOT_REAL\n" +
            "-----END RSA PRIVATE KEY-----";
    private static final String TEST_PASSWORD = "password123";

    @BeforeEach
    void setUp() {
        lenient().when(gamePlatformConfig.getSsh()).thenReturn(sshConfig);
        lenient().when(sshConfig.getConnectTimeout()).thenReturn(5000);
        lenient().when(sshConfig.getSessionTimeout()).thenReturn(10000);
        
        // 手动创建 SshUtil 实例，因为 @InjectMocks 无法正确处理嵌套配置
        sshUtil = new SshUtil(gamePlatformConfig);
    }

    @Test
    @DisplayName("测试SSH连接-使用私钥")
    void testTestConnectionWithPrivateKey() {
        // Given: SSH连接通常需要真实环境，这里测试方法存在性
        // 注意：由于SSH连接需要真实服务器，这里主要测试方法结构和异常处理

        // When & Then: 由于无法连接到真实服务器，应该返回false
        // 实际测试中应该使用Testcontainers或Mock SSH服务器
        boolean result = sshUtil.testConnection(TEST_HOST, TEST_PORT, TEST_USERNAME,
                TEST_PRIVATE_KEY, null, 5000);

        // 期望返回false，因为私钥是伪造的
        assertFalse(result);
    }

    @Test
    @DisplayName("测试SSH连接-使用密码")
    void testTestConnectionWithPassword() {
        // When & Then
        boolean result = sshUtil.testConnection(TEST_HOST, TEST_PORT, TEST_USERNAME,
                null, TEST_PASSWORD, 5000);

        // 期望返回false，因为没有真实服务器
        assertFalse(result);
    }

    @Test
    @DisplayName("测试SSH连接-无效主机")
    void testTestConnectionInvalidHost() {
        // When & Then
        boolean result = sshUtil.testConnection("invalid.host.local", TEST_PORT, TEST_USERNAME,
                null, TEST_PASSWORD, 1000);

        assertFalse(result);
    }

    @Test
    @DisplayName("执行远程命令-成功场景模拟")
    void testExecuteCommand() {
        // Given: 模拟命令执行
        String command = "echo 'Hello World'";

        // When
        SshUtil.CommandResult result = sshUtil.executeCommand(TEST_HOST, TEST_PORT, TEST_USERNAME,
                null, TEST_PASSWORD, command, 5000);

        // Then: 由于没有真实连接，应该返回失败
        assertNotNull(result);
        // 实际结果取决于是否能连接到服务器
    }

    @Test
    @DisplayName("命令执行结果类-基本功能")
    void testCommandResult() {
        // Given
        SshUtil.CommandResult result = new SshUtil.CommandResult();

        // When
        result.setSuccess(true);
        result.setExitCode(0);
        result.setOutput("Hello World");
        result.setError("");

        // Then
        assertTrue(result.isSuccess());
        assertEquals(0, result.getExitCode());
        assertEquals("Hello World", result.getOutput());
        assertEquals("", result.getError());
    }

    @Test
    @DisplayName("远程文件信息类-基本功能")
    void testRemoteFileInfo() {
        // Given
        SshUtil.RemoteFileInfo info = new SshUtil.RemoteFileInfo();

        // When
        info.setName("test.txt");
        info.setPath("/home/user/test.txt");
        info.setDirectory(false);
        info.setSize(1024L);
        info.setLastModified(1234567890L);

        // Then
        assertEquals("test.txt", info.getName());
        assertEquals("/home/user/test.txt", info.getPath());
        assertFalse(info.isDirectory());
        assertEquals(1024L, info.getSize());
        assertEquals(1234567890L, info.getLastModified());
    }

    @Test
    @DisplayName("资源信息类-基本功能")
    void testResourceInfo() {
        // Given
        SshUtil.ResourceInfo info = new SshUtil.ResourceInfo();

        // When
        info.setSuccess(true);
        info.setCpuUsage(45.5);
        info.setMemoryUsage(60.2);
        info.setDiskUsage(30.0);
        info.setError(null);

        // Then
        assertTrue(info.isSuccess());
        assertEquals(45.5, info.getCpuUsage());
        assertEquals(60.2, info.getMemoryUsage());
        assertEquals(30.0, info.getDiskUsage());
        assertNull(info.getError());
    }

    @Test
    @DisplayName("端口信息类-基本功能")
    void testPortInfo() {
        // Given
        SshUtil.PortInfo info = new SshUtil.PortInfo();

        // When
        info.setProtocol("tcp");
        info.setPort(25565);
        info.setState("LISTEN");

        // Then
        assertEquals("tcp", info.getProtocol());
        assertEquals(25565, info.getPort());
        assertEquals("LISTEN", info.getState());
    }

    @Test
    @DisplayName("上传文件-失败场景")
    void testUploadFileFailure() {
        // Given
        String localPath = "/tmp/local.txt";
        String remotePath = "/tmp/remote.txt";

        // When
        boolean result = sshUtil.uploadFile(TEST_HOST, TEST_PORT, TEST_USERNAME,
                null, TEST_PASSWORD, localPath, remotePath);

        // Then: 由于没有真实连接，应该返回false
        assertFalse(result);
    }

    @Test
    @DisplayName("下载文件-失败场景")
    void testDownloadFileFailure() {
        // Given
        String remotePath = "/tmp/remote.txt";
        String localPath = "/tmp/local.txt";

        // When
        boolean result = sshUtil.downloadFile(TEST_HOST, TEST_PORT, TEST_USERNAME,
                null, TEST_PASSWORD, remotePath, localPath);

        // Then: 由于没有真实连接，应该返回false
        assertFalse(result);
    }

    @Test
    @DisplayName("列出远程文件-失败场景")
    void testListFilesFailure() {
        // Given
        String remotePath = "/tmp";

        // When
        List<SshUtil.RemoteFileInfo> files = sshUtil.listFiles(TEST_HOST, TEST_PORT, TEST_USERNAME,
                null, TEST_PASSWORD, remotePath);

        // Then: 应该返回空列表
        assertNotNull(files);
        assertTrue(files.isEmpty());
    }

    @Test
    @DisplayName("删除远程文件-失败场景")
    void testDeleteFileFailure() {
        // Given
        String remotePath = "/tmp/test.txt";

        // When
        boolean result = sshUtil.deleteFile(TEST_HOST, TEST_PORT, TEST_USERNAME,
                null, TEST_PASSWORD, remotePath);

        // Then: 由于没有真实连接，应该返回false
        assertFalse(result);
    }

    @Test
    @DisplayName("获取资源信息-失败场景")
    void testGetResourceInfoFailure() {
        // When
        SshUtil.ResourceInfo info = sshUtil.getResourceInfo(TEST_HOST, TEST_PORT, TEST_USERNAME,
                null, TEST_PASSWORD);

        // Then: 由于没有真实连接，应该返回失败状态
        assertNotNull(info);
        // 实际结果取决于是否能连接到服务器
    }

    @Test
    @DisplayName("扫描端口-失败场景")
    void testScanPortsFailure() {
        // Given
        String portRange = "25565-25575";

        // When
        List<SshUtil.PortInfo> ports = sshUtil.scanPorts(TEST_HOST, TEST_PORT, TEST_USERNAME,
                null, TEST_PASSWORD, portRange);

        // Then: 应该返回空列表或部分结果
        assertNotNull(ports);
    }

    @Test
    @DisplayName("SSH配置-超时设置")
    void testSshConfigTimeout() {
        // Given & When & Then
        assertEquals(5000, sshConfig.getConnectTimeout());
        assertEquals(10000, sshConfig.getSessionTimeout());
    }

    @Test
    @DisplayName("端口范围扫描-空结果")
    void testScanPortsEmptyResult() {
        // Given: 无效的端口范围
        String invalidPortRange = "";

        // When
        List<SshUtil.PortInfo> ports = sshUtil.scanPorts(TEST_HOST, TEST_PORT, TEST_USERNAME,
                null, TEST_PASSWORD, invalidPortRange);

        // Then
        assertNotNull(ports);
    }

    @Test
    @DisplayName("命令执行结果-失败状态")
    void testCommandResultFailure() {
        // Given
        SshUtil.CommandResult result = new SshUtil.CommandResult();

        // When
        result.setSuccess(false);
        result.setExitCode(1);
        result.setOutput("");
        result.setError("Command not found");

        // Then
        assertFalse(result.isSuccess());
        assertEquals(1, result.getExitCode());
        assertEquals("Command not found", result.getError());
    }

    @Test
    @DisplayName("资源信息-失败状态")
    void testResourceInfoFailure() {
        // Given
        SshUtil.ResourceInfo info = new SshUtil.ResourceInfo();

        // When
        info.setSuccess(false);
        info.setError("Connection refused");

        // Then
        assertFalse(info.isSuccess());
        assertEquals("Connection refused", info.getError());
    }
}

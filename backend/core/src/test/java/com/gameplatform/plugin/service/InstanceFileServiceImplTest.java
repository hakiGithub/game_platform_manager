package com.gameplatform.plugin.service;

import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameMetadataMapper;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.plugin.service.FileAccessService.FileInfo;
import com.gameplatform.util.SshUtil;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link InstanceFileServiceImpl} 单元测试。
 *
 * <p>通过 Mockito mock 四个依赖（InstanceQueryService、FileAccessService、SshUtil、HostMapper），
 * 验证 Native（linuxgsm）路由委托、路径校验、摘要计算等行为。
 *
 * <p>说明：测试中 Host 的 sshPassword/sshPrivateKey 均设为 null，避免触发 AesUtil.decrypt。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InstanceFileServiceImpl 单元测试")
class InstanceFileServiceImplTest {

    @Mock private InstanceQueryService instanceQueryService;
    @Mock private FileAccessService fileAccessService;
    @Mock private SshUtil sshUtil;
    @Mock private HostMapper hostMapper;
    @Mock private GameMetadataMapper gameMetadataMapper;

    @InjectMocks
    private InstanceFileServiceImpl service;

    private InstanceVO nativeInstance;
    private Host host;

    @BeforeEach
    void setUp() {
        nativeInstance = new InstanceVO();
        nativeInstance.setId(1L);
        nativeInstance.setHostId(10L);
        nativeInstance.setDeployType("linuxgsm");
        nativeInstance.setInstallPath("/home/gameserver/instance_1");

        host = new Host();
        host.setIpAddress("192.168.1.100");
        host.setSshPort(22);
        host.setSshUser("gameserver");
        // 设为 null 以避免触发 AesUtil.decrypt
        host.setSshPassword(null);
        host.setSshPrivateKey(null);
    }

    // ===== Native 路由测试 =====

    @Test
    @DisplayName("Native readTextFile 委托 FileAccessService")
    void readTextFile_native_delegatesToFileAccessService() {
        when(instanceQueryService.getInstanceById(1L)).thenReturn(nativeInstance);
        when(fileAccessService.readTextFile(eq(10L), anyString(), any()))
            .thenReturn("server config content");

        String result = service.readTextFile(1L, "cfg/server.cfg");

        assertEquals("server config content", result);
        verify(fileAccessService).readTextFile(
            eq(10L), eq("/home/gameserver/instance_1/cfg/server.cfg"), any());
    }

    @Test
    @DisplayName("Native readTextFile(charset) 委托带编码")
    void readTextFile_nativeWithCharset_delegatesWithCharset() {
        when(instanceQueryService.getInstanceById(1L)).thenReturn(nativeInstance);
        when(fileAccessService.readTextFile(eq(10L), anyString(), eq(StandardCharsets.UTF_8)))
            .thenReturn("utf8 content");

        String result = service.readTextFile(1L, "cfg/server.cfg", StandardCharsets.UTF_8);

        assertEquals("utf8 content", result);
        verify(fileAccessService).readTextFile(
            eq(10L), eq("/home/gameserver/instance_1/cfg/server.cfg"), eq(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Native writeTextFile 委托 FileAccessService")
    void writeTextFile_native_delegatesToFileAccessService() {
        when(instanceQueryService.getInstanceById(1L)).thenReturn(nativeInstance);

        service.writeTextFile(1L, "cfg/server.cfg", "content");

        verify(fileAccessService).writeTextFile(
            10L, "/home/gameserver/instance_1/cfg/server.cfg", "content");
    }

    @Test
    @DisplayName("Native exists 委托 FileAccessService")
    void exists_native_delegatesToFileAccessService() {
        when(instanceQueryService.getInstanceById(1L)).thenReturn(nativeInstance);
        when(fileAccessService.exists(10L, "/home/gameserver/instance_1/cfg/server.cfg"))
            .thenReturn(true);

        assertTrue(service.exists(1L, "cfg/server.cfg"));
    }

    @Test
    @DisplayName("Native listFiles 委托 FileAccessService")
    void listFiles_native_delegatesToFileAccessService() {
        when(instanceQueryService.getInstanceById(1L)).thenReturn(nativeInstance);
        FileInfo fi = new FileInfo();
        fi.setName("server.cfg");
        when(fileAccessService.listFiles(10L, "/home/gameserver/instance_1/cfg"))
            .thenReturn(List.of(fi));

        List<FileInfo> result = service.listFiles(1L, "cfg");

        assertEquals(1, result.size());
        assertEquals("server.cfg", result.get(0).getName());
    }

    @Test
    @DisplayName("Native deleteFile 委托 FileAccessService")
    void deleteFile_native_delegatesToFileAccessService() {
        when(instanceQueryService.getInstanceById(1L)).thenReturn(nativeInstance);

        service.deleteFile(1L, "cfg/server.cfg");

        verify(fileAccessService).deleteFile(10L, "/home/gameserver/instance_1/cfg/server.cfg");
    }

    // ===== 路径校验测试 =====

    @Test
    @DisplayName("相对路径禁止包含 ..（抛 IllegalArgumentException）")
    void validateRelativePath_rejectsDoubleDot() {
        when(instanceQueryService.getInstanceById(1L)).thenReturn(nativeInstance);

        assertThrows(IllegalArgumentException.class, () ->
            service.readTextFile(1L, "../../etc/passwd"));
    }

    @Test
    @DisplayName("相对路径多斜线被规范化，前导 ./ 被剥离")
    void validateRelativePath_normalizesPath() {
        when(instanceQueryService.getInstanceById(1L)).thenReturn(nativeInstance);
        // 实现折叠多斜线、剥离前后 "/"、剥离前导 "./" 与段内 "/./"，resolvedPath 归一化为
        // "/home/gameserver/instance_1/a/b/c"
        when(fileAccessService.readTextFile(eq(10L),
                eq("/home/gameserver/instance_1/a/b/c"), any()))
            .thenReturn("content");

        String result = service.readTextFile(1L, "./a//b///c");

        assertEquals("content", result);
    }

    @Test
    @DisplayName("实例不存在时抛 IllegalArgumentException")
    void resolveRoute_throwsWhenInstanceNotFound() {
        when(instanceQueryService.getInstanceById(999L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () ->
            service.readTextFile(999L, "any"));
    }

    @Test
    @DisplayName("不支持的部署类型抛 UnsupportedOperationException")
    void resolveRoute_throwsForUnsupportedDeployType() {
        InstanceVO unsupported = new InstanceVO();
        unsupported.setId(3L);
        unsupported.setHostId(30L);
        unsupported.setDeployType("unknown");
        when(instanceQueryService.getInstanceById(3L)).thenReturn(unsupported);

        assertThrows(UnsupportedOperationException.class, () ->
            service.readTextFile(3L, "any"));
    }

    // ===== 摘要计算测试 =====

    @Test
    @DisplayName("Native computeDigest(MD5) 执行 md5sum 命令")
    void computeDigest_native_executesMd5sum() {
        when(instanceQueryService.getInstanceById(1L)).thenReturn(nativeInstance);
        when(hostMapper.selectById(10L)).thenReturn(host);
        SshUtil.CommandResult result = mock(SshUtil.CommandResult.class);
        when(result.getExitCode()).thenReturn(0);
        when(result.getOutput()).thenReturn(
            "d41d8cd98f00b204e9800998ecf8427e  /path/file\n");
        when(sshUtil.executeCommand(eq("192.168.1.100"), eq(22), eq("gameserver"),
                isNull(), isNull(), anyString()))
            .thenReturn(result);

        String digest = service.computeDigest(1L, "cfg/server.cfg", "MD5");

        assertEquals("d41d8cd98f00b204e9800998ecf8427e", digest);
        verify(sshUtil).executeCommand(eq("192.168.1.100"), eq(22), eq("gameserver"),
            isNull(), isNull(), eq("md5sum /home/gameserver/instance_1/cfg/server.cfg"));
    }

    @Test
    @DisplayName("非法算法名（含 ; 与空格）抛 IllegalArgumentException")
    void computeDigest_throwsForInvalidAlgorithmName() {
        assertThrows(IllegalArgumentException.class, () ->
            service.computeDigest(1L, "any", "md5; rm -rf /"));
    }

    @Test
    @DisplayName("md5sum 命令不存在（exit code 127）抛 UnsupportedOperationException")
    void computeDigest_native_throwsWhenCommandNotFound() {
        when(instanceQueryService.getInstanceById(1L)).thenReturn(nativeInstance);
        when(hostMapper.selectById(10L)).thenReturn(host);
        SshUtil.CommandResult result = mock(SshUtil.CommandResult.class);
        when(result.getExitCode()).thenReturn(127);
        when(sshUtil.executeCommand(anyString(), anyInt(), anyString(),
                isNull(), isNull(), anyString()))
            .thenReturn(result);

        assertThrows(UnsupportedOperationException.class, () ->
            service.computeDigest(1L, "cfg/server.cfg", "MD5"));
    }
}

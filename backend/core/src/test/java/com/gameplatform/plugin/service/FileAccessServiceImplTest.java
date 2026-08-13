package com.gameplatform.plugin.service;

import com.gameplatform.service.FileService;
import com.gameplatform.util.SshUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FileAccessServiceImpl 薄适配单测（架构评审 2026-08-13 候选 3）
 *
 * <p>锁定「全部方法委托 FileService、仅做类型改写」的形状：
 * SFTP 行为只存在于 FileService 一个实现，此处不应再出现任何 SshClient/SFTP 代码。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FileAccessServiceImpl 薄适配测试")
class FileAccessServiceImplTest {

    @Mock
    private FileService fileService;

    @InjectMocks
    private FileAccessServiceImpl service;

    @Test
    @DisplayName("getFileBytes 委托 FileService")
    void getFileBytesDelegates() {
        byte[] expected = {1, 2, 3};
        when(fileService.getFileBytes(10L, "/a/b", 0L, 100L)).thenReturn(expected);

        byte[] result = service.getFileBytes(10L, "/a/b", 0L, 100L);

        assertSame(expected, result);
        verify(fileService).getFileBytes(10L, "/a/b", 0L, 100L);
    }

    @Test
    @DisplayName("tailFile 委托 FileService")
    void tailFileDelegates() {
        Consumer<String> consumer = line -> { };
        when(fileService.tailFile(eq(10L), eq("/a/b.log"), eq(5L), eq(StandardCharsets.UTF_8), any()))
                .thenReturn(42L);

        long result = service.tailFile(10L, "/a/b.log", 5L, StandardCharsets.UTF_8, consumer);

        assertEquals(42L, result);
        verify(fileService).tailFile(eq(10L), eq("/a/b.log"), eq(5L), eq(StandardCharsets.UTF_8), same(consumer));
    }

    @Test
    @DisplayName("executeCommand 委托 FileService 并转换结果类型")
    void executeCommandDelegatesAndConverts() {
        SshUtil.CommandResult core = new SshUtil.CommandResult();
        core.setSuccess(true);
        core.setExitCode(0);
        core.setOutput("ok");
        core.setError("");
        when(fileService.executeCommand(10L, "ls", 5000L)).thenReturn(core);

        FileAccessService.CommandResult result = service.executeCommand(10L, "ls", 5000L);

        assertTrue(result.isSuccess());
        assertEquals(0, result.getExitCode());
        assertEquals("ok", result.getOutput());
    }
}

package com.gameplatform.patch;

import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.plugin.patch.HostCapabilities;
import com.gameplatform.plugin.service.AbstractInstanceFileService;
import com.gameplatform.service.FileService;
import com.gameplatform.util.SshUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("补丁安装执行器命令形状")
class PatchInstallExecutorCommandShapeTest {

    @Test
    @DisplayName("原生 unzip 使用 -d 指定目标目录")
    void nativeZipExtractUsesDestinationOption() {
        AbstractInstanceFileService instanceFileService = mock(AbstractInstanceFileService.class);
        FileService fileService = mock(FileService.class);
        HostMapper hostMapper = mock(HostMapper.class);
        PatchInstallExecutor executor = new PatchInstallExecutor(
                instanceFileService, fileService, hostMapper,
                mock(HostCapabilityProber.class), new PatchDecisionEngine(),
                mock(PatchArchiveExtractor.class));

        Host host = new Host();
        host.setId(1L);
        HostCapabilities capabilities = new HostCapabilities();
        capabilities.setTools(Map.of("unzip", true));
        SshUtil.CommandResult ok = new SshUtil.CommandResult();
        ok.setSuccess(true);
        ok.setExitCode(0);
        ok.setOutput("");
        ok.setError("");
        when(fileService.executeCommand(eq(1L), anyString(), anyLong())).thenReturn(ok);

        executor.toolingExtract(host, capabilities, PatchFormat.ZIP,
                "/tmp/archive.zip", "/tmp/output", null);

        verify(fileService).executeCommand(eq(1L),
                org.mockito.ArgumentMatchers.contains(
                        "unzip -o '/tmp/archive.zip' -d '/tmp/gp_tooling_"),
                anyLong());
        verify(fileService, never()).executeCommand(eq(1L),
                contains("docker run --rm --user"), anyLong());
    }

    @Test
    @DisplayName("Docker RAR 回退使用 7zz，不调用镜像中不存在的 unrar")
    void dockerRarExtractUsesSevenZip() {
        AbstractInstanceFileService instanceFileService = mock(AbstractInstanceFileService.class);
        FileService fileService = mock(FileService.class);
        HostMapper hostMapper = mock(HostMapper.class);
        PatchInstallExecutor executor = new PatchInstallExecutor(
                instanceFileService, fileService, hostMapper,
                mock(HostCapabilityProber.class), new PatchDecisionEngine(),
                mock(PatchArchiveExtractor.class));

        Host host = new Host();
        host.setId(1L);
        HostCapabilities capabilities = new HostCapabilities();
        capabilities.setTools(Map.of());
        capabilities.setDocker(true);
        SshUtil.CommandResult ok = new SshUtil.CommandResult();
        ok.setSuccess(true);
        ok.setExitCode(0);
        ok.setOutput("");
        ok.setError("");
        when(fileService.executeCommand(eq(1L), anyString(), anyLong())).thenReturn(ok);

        executor.toolingExtract(host, capabilities, PatchFormat.RAR,
                "/tmp/archive.rar", "/tmp/output", null);

        verify(fileService).executeCommand(eq(1L),
                contains("docker run --rm --user \"$(id -u):$(id -g)\""), anyLong());
        verify(fileService).executeCommand(eq(1L),
                contains("7zz x -y /a/archive.rar -o/d"), anyLong());
        verify(fileService, never()).executeCommand(eq(1L),
                contains("unrar x"), anyLong());
    }

    @Test
    @DisplayName("Docker ZIP 回退使用 bsdtar 挂载解压")
    void dockerZipExtractUsesBsdtar() {
        AbstractInstanceFileService instanceFileService = mock(AbstractInstanceFileService.class);
        FileService fileService = mock(FileService.class);
        HostMapper hostMapper = mock(HostMapper.class);
        PatchInstallExecutor executor = new PatchInstallExecutor(
                instanceFileService, fileService, hostMapper,
                mock(HostCapabilityProber.class), new PatchDecisionEngine(),
                mock(PatchArchiveExtractor.class));

        Host host = new Host();
        host.setId(1L);
        HostCapabilities capabilities = new HostCapabilities();
        capabilities.setTools(Map.of());
        capabilities.setDocker(true);
        SshUtil.CommandResult ok = new SshUtil.CommandResult();
        ok.setSuccess(true);
        ok.setExitCode(0);
        ok.setOutput("");
        ok.setError("");
        when(fileService.executeCommand(eq(1L), anyString(), anyLong())).thenReturn(ok);

        executor.toolingExtract(host, capabilities, PatchFormat.ZIP,
                "/tmp/archive.zip", "/tmp/output", null);

        verify(fileService).executeCommand(eq(1L),
                contains("docker run --rm --user \"$(id -u):$(id -g)\""), anyLong());
        verify(fileService).executeCommand(eq(1L),
                org.mockito.ArgumentMatchers.argThat(command ->
                        command.contains("-v '/tmp':/a")
                                && command.contains(":/d")), anyLong());
        verify(fileService).executeCommand(eq(1L),
                contains("bsdtar -xf /a/archive.zip -C /d"), anyLong());
    }
}

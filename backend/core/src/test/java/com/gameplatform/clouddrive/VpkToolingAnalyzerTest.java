package com.gameplatform.clouddrive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.entity.GameMetadata;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameMetadataMapper;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.patch.HostCapabilityProber;
import com.gameplatform.plugin.patch.HostCapabilities;
import com.gameplatform.plugin.service.ContainerIdResolver;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.plugin.service.VpkAnalyzeResult;
import com.gameplatform.util.SshUtil;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VpkToolingAnalyzer 主机侧寻址回归测试（ADR-0027 / ticket 03）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VpkToolingAnalyzerTest {

    @Mock private DeploymentAccess deployAccess;
    @Mock private SshUtil sshUtil;
    @Mock private HostMapper hostMapper;
    @Mock private GameMetadataMapper gameMetadataMapper;
    @Mock private InstanceQueryService instanceQueryService;
    @Mock private ContainerIdResolver containerIdResolver;
    @Mock private HostCapabilityProber prober;

    private VpkToolingAnalyzer analyzer;
    private Host host;
    private InstanceVO dockerInstance;

    @BeforeEach
    void setUp() {
        analyzer = new VpkToolingAnalyzer(deployAccess, sshUtil, new ObjectMapper(),
                hostMapper, gameMetadataMapper, instanceQueryService, containerIdResolver, prober);
        host = new Host();
        host.setId(10L);
        host.setIpAddress("127.0.0.1");
        host.setSshPort(22);
        host.setSshUser("steam");

        dockerInstance = new InstanceVO();
        dockerInstance.setId(1L);
        dockerInstance.setHostId(10L);
        dockerInstance.setDeployType("docker");
        Map<String, Object> config = new HashMap<>();
        config.put("containerWorkDir", "/l4d2");
        dockerInstance.setConfigInfo(config);

        HostCapabilities caps = new HostCapabilities();
        caps.setDocker(true);
        caps.getTools().put("sha256sum", true);

        when(instanceQueryService.getInstanceById(1L)).thenReturn(dockerInstance);
        when(hostMapper.selectById(10L)).thenReturn(host);
        when(prober.probe(10L)).thenReturn(caps);
        when(deployAccess.classify("docker")).thenReturn(
                com.gameplatform.adapter.DeployAdapter.DeployType.DOCKER);
        when(deployAccess.isNativeDeploy("docker")).thenReturn(false);
        when(deployAccess.credentials(host)).thenReturn(
                new com.gameplatform.deploy.HostCredentials("127.0.0.1", 22, "steam", null, "pw"));
        when(containerIdResolver.resolve(eq(dockerInstance), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn("container-1");
    }

    @Test
    @DisplayName("Docker 摘要只读容器内文件，不 docker cp VPK")
    void fileDigest_dockerDoesNotCopyVpk() {
        SshUtil.CommandResult ok = command(0, "a".repeat(64), "");
        SshUtil.CommandResult analyzed = command(0, "{\"title\":\"Test Campaign\",\"chapters\":[]}", "");
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("python3 "))).thenReturn(analyzed);
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), org.mockito.ArgumentMatchers.argThat(cmd ->
                        cmd != null && !cmd.contains("python3 ")))).thenReturn(ok);

        analyzer.fileDigest(1L, "left4dead2/addons/map.vpk");

        verify(sshUtil).executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(), anyString(),
                contains("docker exec 'container-1' sh -c"));
        verify(sshUtil, never()).executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(), anyString(),
                contains("docker cp 'container-1:"));
    }

    @Test
    @DisplayName("Docker 分析命令使用容器内 VPK 路径")
    void analyzeVpk_dockerUsesContainerPath() {
        SshUtil.CommandResult analyzed = command(0, "{\"title\":\"Test Campaign\",\"chapters\":[]}", "");
        SshUtil.CommandResult digest = command(0, "a".repeat(64), "");
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("python3 "))).thenReturn(analyzed);
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), org.mockito.ArgumentMatchers.argThat(cmd ->
                        cmd != null && !cmd.contains("python3 ")))).thenReturn(
                digest);
        when(sshUtil.uploadFile(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(), anyString(),
                anyString(), anyString(),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(true);

        analyzer.analyzeVpk(1L, "left4dead2/addons/map.vpk");

        verify(sshUtil, atLeastOnce()).executeCommand(anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString(),
                org.mockito.ArgumentMatchers.isNull(), anyString(),
                contains("/l4d2/left4dead2/addons/map.vpk"));
        verify(sshUtil, never()).executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(), anyString(),
                contains("docker cp 'container-1:/l4d2/left4dead2/addons/map.vpk'"));
    }

    @Test
    @DisplayName("容器工作目录缺失时从游戏元数据回退")
    void analyzeVpk_usesGameMetadataWorkingDirFallback() {
        dockerInstance.getConfigInfo().remove("containerWorkDir");
        dockerInstance.setGameCode("l4d2");
        GameMetadata metadata = new GameMetadata();
        metadata.setDeployConfig(Map.of("docker", Map.of("workingDir", "/metadata-l4d2")));
        when(gameMetadataMapper.selectByGameCode("l4d2")).thenReturn(metadata);
        SshUtil.CommandResult analyzed = command(0, "{\"title\":\"Test Campaign\",\"chapters\":[]}", "");
        SshUtil.CommandResult digest = command(0, "a".repeat(64), "");
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("python3 "))).thenReturn(analyzed);
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), org.mockito.ArgumentMatchers.argThat(cmd ->
                        cmd != null && !cmd.contains("python3 ")))).thenReturn(
                digest);
        when(sshUtil.uploadFile(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(), anyString(),
                anyString(), anyString(),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(true);

        analyzer.analyzeVpk(1L, "left4dead2/addons/map.vpk");

        verify(sshUtil, atLeastOnce()).executeCommand(anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString(),
                org.mockito.ArgumentMatchers.isNull(), anyString(),
                contains("/metadata-l4d2/left4dead2/addons/map.vpk"));
    }

    @Test
    @DisplayName("Docker 详情只读 VPK 元数据，不计算整文件摘要")
    void inspectVpk_dockerSkipsDigest() {
        SshUtil.CommandResult analyzed = command(0, "{\"title\":\"Test Campaign\",\"chapters\":[]}", "");
        SshUtil.CommandResult ignored = command(0, "", "");
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("python3 "))).thenReturn(analyzed);
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), org.mockito.ArgumentMatchers.argThat(cmd ->
                        cmd != null && !cmd.contains("python3 ")))).thenReturn(ignored);
        when(sshUtil.uploadFile(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(), anyString(),
                anyString(), anyString(),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(true);

        VpkAnalyzeResult result = analyzer.inspectVpk(1L, "left4dead2/addons/map.vpk");

        assertEquals("Test Campaign", result.getTitle());
        assertNull(result.getDigest());
        verify(sshUtil, never()).executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(), anyString(),
                contains("sha256sum"));
        verify(sshUtil, atLeastOnce()).executeCommand(anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString(),
                org.mockito.ArgumentMatchers.isNull(), anyString(),
                contains("--volumes-from 'container-1'"));
    }

    @Test
    @DisplayName("容器内带空格路径始终按单个 shell 参数传递")
    void inspectVpk_quotesContainerPathWithSpaces() {
        SshUtil.CommandResult analyzed = command(0, "{\"title\":\"Test Campaign\",\"chapters\":[]}", "");
        SshUtil.CommandResult ignored = command(0, "", "");
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("python3 "))).thenReturn(analyzed);
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), org.mockito.ArgumentMatchers.argThat(cmd ->
                        cmd != null && !cmd.contains("python3 ")))).thenReturn(ignored);
        when(sshUtil.uploadFile(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(), anyString(),
                anyString(), anyString(),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(true);

        analyzer.inspectVpk(1L, "left4dead2/addons/my map.vpk");

        verify(sshUtil, atLeastOnce()).executeCommand(anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString(),
                org.mockito.ArgumentMatchers.isNull(), anyString(),
                contains("'/l4d2/left4dead2/addons/my map.vpk'"));
    }

    @Test
    @DisplayName("目标容器有 python3 时脚本直接原地执行")
    void inspectVpk_targetContainerPython() {
        SshUtil.CommandResult analyzed = command(0, "{\"title\":\"Test Campaign\",\"chapters\":[]}", "");
        SshUtil.CommandResult ignored = command(0, "", "");
        SshUtil.CommandResult probe = command(0, "__ok__\n", "");
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("command -v python3"))).thenReturn(probe);
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("sh -c 'python3 "))
                ).thenReturn(analyzed);
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), org.mockito.ArgumentMatchers.argThat(cmd ->
                        cmd != null && !cmd.contains("command -v python3")
                                && !cmd.contains("python3 ")))).thenReturn(ignored);
        when(sshUtil.uploadFile(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(), anyString(),
                anyString(), anyString(),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(true);

        analyzer.inspectVpk(1L, "left4dead2/addons/map.vpk");

        verify(sshUtil, never()).executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(), anyString(),
                contains("--entrypoint python3"));
    }

    private SshUtil.CommandResult command(int exit, String output, String error) {
        SshUtil.CommandResult result = org.mockito.Mockito.mock(SshUtil.CommandResult.class);
        when(result.getExitCode()).thenReturn(exit);
        when(result.isSuccess()).thenReturn(exit == 0);
        when(result.getOutput()).thenReturn(output);
        when(result.getError()).thenReturn(error);
        return result;
    }
}

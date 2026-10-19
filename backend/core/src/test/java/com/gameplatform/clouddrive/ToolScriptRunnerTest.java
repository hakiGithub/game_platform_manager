package com.gameplatform.clouddrive;

import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.entity.GameMetadata;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameMetadataMapper;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.patch.HostCapabilityProber;
import com.gameplatform.plugin.patch.HostCapabilities;
import com.gameplatform.plugin.service.ContainerIdResolver;
import com.gameplatform.plugin.service.InstanceQueryService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ToolScriptRunner 主机侧寻址与脚本分发回归测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolScriptRunnerTest {

    @Mock private DeploymentAccess deployAccess;
    @Mock private SshUtil sshUtil;
    @Mock private HostMapper hostMapper;
    @Mock private GameMetadataMapper gameMetadataMapper;
    @Mock private InstanceQueryService instanceQueryService;
    @Mock private ContainerIdResolver containerIdResolver;
    @Mock private HostCapabilityProber prober;
    @Mock private ToolScriptRegistry toolScriptRegistry;

    private ToolScriptRunner runner;
    private Host host;
    private InstanceVO dockerInstance;

    @BeforeEach
    void setUp() {
        runner = new ToolScriptRunner(deployAccess, sshUtil,
                hostMapper, gameMetadataMapper, instanceQueryService, containerIdResolver,
                prober, toolScriptRegistry);
        when(toolScriptRegistry.read("plugin-l4d2:tooling/vpk_analyze.py"))
                .thenReturn("# test script\n");
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
        SshUtil.CommandResult inspect = command(0,
                "volume|/l4d2|/var/lib/docker/volumes/test/_data|test-data\n", "");
        org.mockito.Mockito.lenient().doReturn(inspect).when(sshUtil).executeCommand(
                anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(), anyString(),
                org.mockito.ArgumentMatchers.argThat(cmd -> cmd != null
                        && cmd.contains("docker inspect ")));
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
                anyString(), contains("sha256sum"))).thenReturn(ok);

        runner.fileDigest(1L, "left4dead2/addons/map.vpk");

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
                anyString(), contains("sha256sum"))).thenReturn(digest);
        when(sshUtil.uploadFile(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(), anyString(),
                anyString(), anyString(),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(true);

        runner.runToolScript(1L, "left4dead2/addons/map.vpk",
                "plugin-l4d2:tooling/vpk_analyze.py");

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
        dockerInstance.setConfigInfo(new HashMap<>());
        dockerInstance.setGameCode("l4d2");
        GameMetadata metadata = new GameMetadata();
        metadata.setDeployConfig(Map.of("docker", Map.of("workingDir", "/metadata-l4d2")));
        when(gameMetadataMapper.selectByGameCode("l4d2")).thenReturn(metadata);
        SshUtil.CommandResult inspect = command(0,
                "volume|/metadata-l4d2|/var/lib/docker/volumes/test/_data|test-data\n", "");
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("docker inspect "))).thenReturn(inspect);
        SshUtil.CommandResult analyzed = command(0, "{\"title\":\"Test Campaign\",\"chapters\":[]}", "");
        SshUtil.CommandResult digest = command(0, "a".repeat(64), "");
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("python3 "))).thenReturn(analyzed);
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("sha256sum"))).thenReturn(digest);
        when(sshUtil.uploadFile(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(), anyString(),
                anyString(), anyString(),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(true);

        runner.runToolScript(1L, "left4dead2/addons/map.vpk",
                "plugin-l4d2:tooling/vpk_analyze.py");

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
        SshUtil.CommandResult missingPython = command(1, "", "");
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("python3 "))).thenReturn(analyzed);
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("command -v python3"))).thenReturn(missingPython);
        when(sshUtil.uploadFile(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(), anyString(),
                anyString(), anyString(),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(true);

        String result = runner.runToolScript(1L, "left4dead2/addons/map.vpk",
                "plugin-l4d2:tooling/vpk_analyze.py");

        assertTrue(result.contains("Test Campaign"));
        verify(sshUtil, never()).executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(), anyString(),
                contains("sha256sum"));
        verify(sshUtil, atLeastOnce()).executeCommand(anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString(),
                org.mockito.ArgumentMatchers.isNull(), anyString(),
                contains("type=volume,source=test-data"
                        + ",destination=/gp-target/l4d2,readonly"));
        verify(sshUtil, atLeastOnce()).executeCommand(anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString(),
                org.mockito.ArgumentMatchers.isNull(), anyString(),
                contains("GP_TOOL_TARGET_ROOT=/gp-target"));
        verify(sshUtil, never()).executeCommand(anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString(),
                org.mockito.ArgumentMatchers.isNull(), anyString(),
                contains("--volumes-from"));
    }

    @Test
    @DisplayName("容器内带空格路径始终按单个 shell 参数传递")
    void inspectVpk_quotesContainerPathWithSpaces() {
        SshUtil.CommandResult analyzed = command(0, "{\"title\":\"Test Campaign\",\"chapters\":[]}", "");
        SshUtil.CommandResult ignored = command(0, "", "");
        SshUtil.CommandResult missingPython = command(1, "", "");
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("python3 "))).thenReturn(analyzed);
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("command -v python3"))).thenReturn(missingPython);
        when(sshUtil.uploadFile(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(), anyString(),
                anyString(), anyString(),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(true);

        runner.runToolScript(1L, "left4dead2/addons/my map.vpk",
                "plugin-l4d2:tooling/vpk_analyze.py");

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
        SshUtil.CommandResult inspect = command(0,
                "volume|/metadata-l4d2|/var/lib/docker/volumes/test/_data|test-data\n", "");
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("docker inspect "))).thenReturn(inspect);
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

        runner.runToolScript(1L, "left4dead2/addons/map.vpk",
                "plugin-l4d2:tooling/vpk_analyze.py");

        verify(sshUtil, never()).executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(), anyString(),
                contains("--entrypoint /usr/bin/python3"));
    }

    @Test
    @DisplayName("目标根挂载映射到独立工具目录而不覆盖镜像根")
    void inspectVpk_rootMountUsesIsolatedToolingRoot() {
        SshUtil.CommandResult missingPython = command(1, "", "");
        SshUtil.CommandResult inspect = command(0,
                "bind|/|/var/lib/docker/volumes/test/_data|\n", "");
        SshUtil.CommandResult analyzed = command(0,
                "{\"title\":\"Test Campaign\",\"chapters\":[]}", "");
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("command -v python3"))).thenReturn(missingPython);
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("docker inspect "))).thenReturn(inspect);
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("--entrypoint /usr/bin/python3"))).thenReturn(analyzed);
        when(sshUtil.uploadFile(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(), anyString(),
                anyString(), anyString(),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(true);

        runner.runToolScript(1L, "left4dead2/addons/map.vpk",
                "plugin-l4d2:tooling/vpk_analyze.py");

        verify(sshUtil, atLeastOnce()).executeCommand(anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString(),
                org.mockito.ArgumentMatchers.isNull(), anyString(),
                contains("type=bind,source=/var/lib/docker/volumes/test/_data"
                        + ",destination=/gp-target,readonly"));
        verify(sshUtil, never()).executeCommand(anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString(),
                org.mockito.ArgumentMatchers.isNull(), anyString(),
                contains("destination=/,readonly"));
    }

    @Test
    @DisplayName("工具镜像缺少 python3 时返回可操作错误且隐藏 Docker 内部回显")
    void inspectVpk_toolingImageMissingPython_returnsActionableError() {
        SshUtil.CommandResult probeMissing = command(1, "", "");
        SshUtil.CommandResult dockerNoise = command(125, "",
                "docker: Error response from daemon: failed to create task for container: "
                        + "exec: \"/usr/bin/python3\": executable file not found in $PATH");
        SshUtil.CommandResult cleanup = command(0, "", "");
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("command -v python3"))).thenReturn(probeMissing);
        org.mockito.Mockito.lenient().doReturn(command(0,
                        "volume|/l4d2|/var/lib/docker/volumes/test/_data|test-data\n", ""))
                .when(sshUtil).executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                        anyString(), org.mockito.ArgumentMatchers.isNull(), anyString(),
                        contains("docker inspect "));
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("--entrypoint /usr/bin/python3")))
                .thenReturn(dockerNoise);
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("--mount 'type=volume")))
                .thenReturn(dockerNoise);
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("rm -f '/tmp/gp_tool_"))).thenReturn(cleanup);
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("docker cp "))).thenReturn(cleanup);
        when(sshUtil.uploadFile(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(), anyString(),
                anyString(), anyString(),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(true);

        com.gameplatform.common.exception.BusinessException error = assertThrows(
                com.gameplatform.common.exception.BusinessException.class,
                () -> runner.runToolScript(1L, "left4dead2/addons/red_square.vpk",
                        "plugin-l4d2:tooling/vpk_analyze.py"));

        assertTrue(error.getMessage().contains("red_square.vpk"));
        assertTrue(error.getMessage().contains("工具容器"), error.getMessage());
        assertTrue(error.getMessage().contains("重新拉取")
                || error.getMessage().contains("检查 platform-tools"), error.getMessage());
        assertTrue(error.getMessage().contains("platform-tools"), error.getMessage());
        assertTrue(!error.getMessage().contains("failed to create task"));
        verify(sshUtil, atLeastOnce()).executeCommand(anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString(),
                org.mockito.ArgumentMatchers.isNull(), anyString(),
                contains("rm -f '/tmp/gp_tool_"));
    }

    @Test
    @DisplayName("工具镜像拉取失败与文件不可读能区分")
    void inspectVpk_toolingPullFailure_andFileReadFailureAreDistinct() {
        SshUtil.CommandResult probeMissing = command(1, "", "");
        SshUtil.CommandResult pullFailure = command(125, "",
                "Error response from daemon: manifest unknown: manifest unknown");
        SshUtil.CommandResult cleanup = command(0, "", "");
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("command -v python3"))).thenReturn(probeMissing);
        org.mockito.Mockito.lenient().doReturn(command(0,
                        "volume|/l4d2|/var/lib/docker/volumes/test/_data|test-data\n", ""))
                .when(sshUtil).executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                        anyString(), org.mockito.ArgumentMatchers.isNull(), anyString(),
                        contains("docker inspect "));
        when(sshUtil.uploadFile(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(), anyString(),
                anyString(), anyString(),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(true);
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("--entrypoint /usr/bin/python3")))
                .thenReturn(pullFailure);
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("--mount 'type=volume")))
                .thenReturn(pullFailure);
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("rm -f '/tmp/gp_tool_"))).thenReturn(cleanup);
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("docker cp "))).thenReturn(cleanup);

        com.gameplatform.common.exception.BusinessException pullError = assertThrows(
                com.gameplatform.common.exception.BusinessException.class,
                () -> runner.runToolScript(1L, "left4dead2/addons/map.vpk",
                        "plugin-l4d2:tooling/vpk_analyze.py"));
        assertTrue(pullError.getMessage().contains("工具容器"), pullError.getMessage());

        SshUtil.CommandResult readFailure = command(1, "",
                "No such file or directory: /l4d2/left4dead2/addons/map.vpk");
        when(sshUtil.executeCommand(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), contains("--entrypoint /usr/bin/python3")))
                .thenReturn(readFailure);

        com.gameplatform.common.exception.BusinessException fileError = assertThrows(
                com.gameplatform.common.exception.BusinessException.class,
                () -> runner.runToolScript(1L, "left4dead2/addons/map.vpk",
                        "plugin-l4d2:tooling/vpk_analyze.py"));
        assertTrue(fileError.getMessage().contains("目标文件不可读"), fileError.getMessage());
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

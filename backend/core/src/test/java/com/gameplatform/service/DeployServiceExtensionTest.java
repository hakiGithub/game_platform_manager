package com.gameplatform.service;

import com.gameplatform.adapter.DeployAdapter;
import com.gameplatform.adapter.DeployAdapterFactory;
import com.gameplatform.config.GamePlatformConfig;
import com.gameplatform.controller.InstanceController;
import com.gameplatform.deploy.CatalogView;
import com.gameplatform.deploy.DeployVersionCatalogService;
import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.deploy.ExtensionScriptRunner;
import com.gameplatform.deploy.HostCredentials;
import com.gameplatform.deploy.VersionEntry;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.plugin.extension.deploy.DeployVersionDeclaration;
import com.gameplatform.plugin.extension.deploy.PatchStepDeclaration;
import com.gameplatform.plugin.extension.deploy.ScriptPosition;
import com.gameplatform.plugin.extension.deploy.ScriptStepDeclaration;
import com.gameplatform.plugin.patch.PatchInstallService;
import com.gameplatform.plugin.service.AbstractInstanceFileService;
import com.gameplatform.plugin.service.PluginFrameworkService;
import com.gameplatform.service.deploy.DeployExtensionExecutor;
import com.gameplatform.service.deploy.ExtensionLogLine;
import com.gameplatform.service.deploy.ExtensionStageSink;
import com.gameplatform.util.SshUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 扩展阶段接入部署主干的核对（B-10 接线 + B-11 字段位）。
 *
 * <p>核对三件事：① <b>有扩展步骤时</b>契约字段进得了日志流、顶层 {@code stage}/{@code progress}
 * 报得出来、{@code HEALTH_CHECK} 改报 85、{@code COMPLETE} 仍是 100（§14.7 / §14.11 / BR-10）；
 * ② <b>无扩展声明的游戏</b>一行新字段都不带、适配器扩展面零调用（V-10 子项 / BR-11）——逐值序列比对
 * 归 {@link DeployProgressSamplingBaselineTest}，本类只判「扩展面没被碰过」这一侧；
 * ③ 扩展阶段自己终止时，主流程不再补同阶段的 {@code ERROR} 行（ui-spec §7 X-07 锚点二 B），
 * 但终态字段与实例 {@code ERROR} 照写。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("部署主干接线（B-10 / B-11）")
class DeployServiceExtensionTest {

    private static final Long INSTANCE_ID = 11L;
    private static final String EVIDENCE_DIR = "target/evidence";

    @Mock
    private DeployAdapterFactory adapterFactory;
    @Mock
    private GameInstanceMapper instanceMapper;
    @Mock
    private HostMapper hostMapper;
    @Mock
    private SshUtil sshUtil;
    @Mock
    private DeploymentAccess deployAccess;
    @Mock
    private DeployExtensionExecutor extensionExecutor;
    @Mock
    private DeployAdapter adapter;
    @Mock
    private PatchInstallService patchInstallService;
    @Mock
    private ExtensionScriptRunner scriptRunner;

    @InjectMocks
    private DeployService deployService;

    private final List<Integer> progressSamples = new ArrayList<>();
    private final List<String> stageSamples = new ArrayList<>();
    private final List<String> statusSamples = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(adapterFactory.getAdapter(any(DeployAdapter.DeployType.class))).thenReturn(adapter);
        when(adapterFactory.getAdapter(anyString())).thenReturn(adapter);
        when(deployAccess.credentials(any(Host.class)))
                .thenReturn(new HostCredentials("10.0.0.1", 22, "root", null, "secret"));
        when(hostMapper.selectById(1L)).thenReturn(host());
        when(instanceMapper.selectById(INSTANCE_ID)).thenReturn(instance());
        stubEnvironmentChecksPass();
        when(adapter.preDeploy(eq(INSTANCE_ID), any(), any())).thenAnswer(invocation -> {
            sampleNow();
            return true;
        });
        when(adapter.deploy(eq(INSTANCE_ID), any(), any())).thenAnswer(invocation -> {
            sampleNow();
            return true;
        });
        // 在适配器被调用的时刻采一次顶层值 = 该阶段 updateTaskStatus 之后、动作之前的值，
        // 正是 V-10 说的「同频轮询样本」在这个时刻能读到的顶层 progress（回调时刻采不到：
        // updateTaskStatus 不产生回调，也不产生日志行）。
        when(adapter.healthCheck(eq(INSTANCE_ID), any())).thenAnswer(invocation -> {
            sampleNow();
            return true;
        });
    }

    private void sampleNow() {
        DeployService.DeployTaskStatus status = deployService.getTaskStatus(INSTANCE_ID);
        if (status != null) {
            progressSamples.add(status.getProgress());
            stageSamples.add(status.getStage());
            statusSamples.add(status.getStatus());
        }
    }

    // ==================== §14.7 / §14.11：有扩展步骤的条件分配 ====================

    @Test
    @DisplayName("有扩展步骤：扩展段占 [80,84]、顶层 stage 报 EXTENSION 且 status 仍是 installing、HEALTH_CHECK 改报 85")
    void extensionDeployReportsGatedProgress() {
        when(extensionExecutor.runExtensionPhase(any(), any())).thenAnswer(invocation -> {
            ExtensionStageSink sink = invocation.getArgument(1);
            sink.reportProgress(80);
            sink.append(WordsShim.enter());
            sink.append(stepRow("E-1", 1, 2, "补丁替换", "PATCH", "SUCCESS", 2_200L, null));
            sink.reportProgress(82);
            sink.append(stepRow("E-2", 2, 2, "脚本执行", "SCRIPT", "SUCCESS", 1_200L, 0));
            sink.reportProgress(84);
            sink.append(WordsShim.finishSuccess());
            sink.append(WordsShim.complete());
            sink.append(WordsShim.handoff());
            // 扩展阶段进行中采一次：顶层 stage 此时必须是 EXTENSION（§14.11 的激活态驱动源）
            sampleNow();
            return true;
        });

        assertTrue(deployService.deploy(context(), sampler()));

        assertEquals(85, firstProgressAtStage("HEALTH_CHECK"), "§14.7：该分支内顶层 progress 由 80 改报 85");
        assertEquals(100, finalStatus().getProgress(), "COMPLETE = 100 一字不改（BR-10 / N-06）");
        assertTrue(stageSamples.contains(DeployExtensionExecutor.EXTENSION_STAGE), "顶层 stage 报得出 EXTENSION");
        assertEquals("installing", statusSamples.get(stageSamples.indexOf(DeployExtensionExecutor.EXTENSION_STAGE)),
                "§14.11：EXTENSION 与 DEPLOY 同词，不冒出第三种状态");
        assertTrue(finalStatus().getLogs().stream()
                .anyMatch(r -> DeployExtensionExecutor.EXTENSION_STAGE.equals(r.getStage())));
    }

    @Test
    @DisplayName("八个契约属性经 deploy-progress 出口原样可达（AC-16 数据侧 / V-08 供字段）")
    void contractFieldsSurviveTheVoMapping() {
        when(extensionExecutor.runExtensionPhase(any(), any())).thenAnswer(invocation -> {
            ExtensionStageSink sink = invocation.getArgument(1);
            sink.append(stepRow("E-1", 1, 1, "补丁替换", "PATCH", "SUCCESS", 2_200L, null));
            sink.append(stepRow("E-1", 1, 1, "补丁替换", "PATCH", "START", null, null));
            return true;
        });
        deployService.deploy(context(), sampler());

        InstanceController.DeployProgressVO vo = controller().getDeployProgress(INSTANCE_ID).getData();
        assertNotNullStage(vo);
        InstanceController.LogEntryVO row = vo.getLogs().stream()
                .filter(r -> "SUCCESS".equals(r.getStepEvent())).findFirst().orElseThrow();
        assertEquals("E-1", row.getStepId());
        assertEquals(1, row.getStepIndex());
        assertEquals(1, row.getStepTotal());
        assertEquals("补丁替换", row.getStepLabel());
        assertEquals("PATCH", row.getStepType());
        assertEquals("SUCCESS", row.getStepEvent());
        assertEquals(2_200L, row.getElapsedMs());
        assertNull(row.getExitCode(), "PATCH 恒不回报退出码（§14.6 exitCode 行）");
        assertEquals("EXTENSION", row.getStage());
        // 顶层 stage：部署终态是 COMPLETE，扩展期间报过 EXTENSION（见上一用例）
        assertEquals("COMPLETE", vo.getStage());

        InstanceController.LogEntryVO startRow = vo.getLogs().stream()
                .filter(r -> "START".equals(r.getStepEvent())).findFirst().orElseThrow();
        assertNull(startRow.getElapsedMs(), "START 行不带耗时：三项齐备判据的终态侧才要求 elapsedMs 非空");
    }

    @Test
    @DisplayName("无扩展声明的游戏（执行器判「本段不存在」）：新字段全 null、扩展面零调用（V-10 子项 / BR-11）")
    void noExtensionDeployCarriesNoStepFieldsAndNeverTouchesExtensionSurfaces() throws Exception {
        when(extensionExecutor.runExtensionPhase(any(), any())).thenReturn(false);

        assertTrue(deployService.deploy(context(), sampler()));

        for (DeployService.LogEntry entry : finalStatus().getLogs()) {
            assertNull(entry.getStepId(), entry.getMessage());
            assertNull(entry.getStepIndex(), entry.getMessage());
            assertNull(entry.getStepTotal(), entry.getMessage());
            assertNull(entry.getStepLabel(), entry.getMessage());
            assertNull(entry.getStepType(), entry.getMessage());
            assertNull(entry.getStepEvent(), entry.getMessage());
            assertNull(entry.getElapsedMs(), entry.getMessage());
            assertNull(entry.getExitCode(), entry.getMessage());
            assertFalse(DeployExtensionExecutor.EXTENSION_STAGE.equals(entry.getStage()), entry.getMessage());
        }
        assertFalse(stageSamples.contains(DeployExtensionExecutor.EXTENSION_STAGE));
        assertEquals(80, firstProgressAtStage("HEALTH_CHECK"), "无扩展步骤分支不改任何字面量");
        verify(adapter, never()).stop(anyLong(), any());
        verify(adapter, never()).ensureRunningForExtension(anyLong(), any());
        writeEvidence("deploy-progress-no-extension.json");
    }

    // ==================== 失败路径：终态行不被重复污染 ====================

    @Test
    @DisplayName("扩展阶段自身终止（收尾失败）⇒ 主流程不补同阶段 ERROR 行，但终态字段与实例 ERROR 照写（X-07 B）")
    void extensionTerminalRowsAreNotDuplicated() {
        when(extensionExecutor.runExtensionPhase(any(), any())).thenAnswer(invocation -> {
            ExtensionStageSink sink = invocation.getArgument(1);
            sink.reportProgress(80);
            sink.append(WordsShim.enter());
            sink.append(WordsShim.finishFailed());
            throw new DeployExtensionExecutor.ExtensionPhaseException(WordsShim.FINISH_FAILED_DIALOG);
        });

        assertFalse(deployService.deploy(context(), sampler()));

        List<DeployService.LogEntry> logs = finalStatus().getLogs();
        DeployService.LogEntry last = logs.get(logs.size() - 1);
        assertEquals("FAILURE", last.getStepEvent(), "末行必须是执行器自己产的收尾失败行");
        assertEquals(2, logs.stream().filter(r -> DeployExtensionExecutor.EXTENSION_STAGE.equals(r.getStage())).count(),
                "进入行 + 收尾失败行，一条都不多（主流程的两条补行已挡住）");
        assertTrue(finalStatus().isCompleted());
        assertFalse(finalStatus().isSuccess());
        assertEquals(WordsShim.FINISH_FAILED_DIALOG, finalStatus().getError());
        assertEquals(DeployAdapter.InstanceStatus.ERROR.getCode(), lastWrittenRunStatus(), "实例置为异常");
    }

    @Test
    @DisplayName("既有失败路径不变：非扩展阶段的 DeployException 照旧产主流程终态行（回归护栏）")
    void plainDeployFailureStillAppendsTerminalRows() {
        when(adapter.deploy(eq(INSTANCE_ID), any(), any())).thenReturn(false);

        assertFalse(deployService.deploy(context(), sampler()));

        DeployService.LogEntry last = finalStatus().getLogs().get(finalStatus().getLogs().size() - 1);
        assertEquals("DEPLOY", last.getStage());
        assertEquals("ERROR", last.getLevel());
        assertEquals("部署失败", finalStatus().getError());
    }

    // ==================== V-08 取证：真执行器接真主流程的 deploy-progress 响应 ====================

    @Test
    @DisplayName("真执行器 + 真主流程：扩展阶段的行经 deploy-progress 出口落成可机械核对的响应体（取证）")
    void realExecutorLinesReachTheVo() throws Exception {
        PatchStepDeclaration patchStep = new PatchStepDeclaration("桩改造补丁",
                "http://127.0.0.1:8099/patch/stub-version-marker.zip", "stub-patch", null, null, null, true);
        ScriptStepDeclaration scriptStep = new ScriptStepDeclaration("桩版本标记脚本", "echo STUB-MARKER",
                null, null, ScriptPosition.HOST, true, null);
        DeployVersionDeclaration declaration = new DeployVersionDeclaration("2.0.0-patched", "桩改造版",
                null, false, List.of(patchStep), List.of(scriptStep));
        VersionEntry entry = new VersionEntry(declaration, "桩改造版", false, List.of());
        DeployVersionCatalogService catalog = mock(DeployVersionCatalogService.class);
        when(catalog.read(anyString(), anyString())).thenReturn(CatalogView.available(List.of(entry)));
        PluginFrameworkService plugins = mock(PluginFrameworkService.class);
        // ② 支（纯声明型插件）：动态入口不给步骤，由条目 patches ++ scripts 承担
        when(plugins.getExtensionByGameCode(anyString())).thenReturn(null);
        when(scriptRunner.run(any(), anyInt(), anyLong(), anyString())).thenReturn(
                new ExtensionScriptRunner.ScriptRunResult(0, false, 600_000L, 12L,
                        new ExtensionScriptRunner.TruncatedOutput("STUB-MARKER 2.0.0-patched", 25L, false),
                        new ExtensionScriptRunner.TruncatedOutput("", 0L, false)));
        org.springframework.test.util.ReflectionTestUtils.setField(deployService, "deployExtensionExecutor",
                new DeployExtensionExecutor(catalog, plugins, patchInstallService, scriptRunner));
        when(adapter.getStatus(anyLong(), any())).thenReturn(DeployAdapter.InstanceStatus.STOPPED);
        when(adapter.ensureRunningForExtension(anyLong(), any())).thenReturn(true);

        assertTrue(deployService.deploy(context(), sampler()));

        InstanceController.DeployProgressVO vo = controller().getDeployProgress(INSTANCE_ID).getData();
        writeEvidence("deploy-progress-with-extension.json");

        // 只读字段的机械判据（完整口径见 scripts/v08-extension-log-contract.py，脚本读的是上面这份响应体）
        List<InstanceController.LogEntryVO> stepRows = vo.getLogs().stream()
                .filter(r -> r.getStepId() != null).toList();
        assertEquals(2, stepRows.stream().map(InstanceController.LogEntryVO::getStepId).distinct().count());
        assertTrue(stepRows.stream().allMatch(r -> "EXTENSION".equals(r.getStage())),
                "V-08 取值域：stepId != null 的行必带 EXTENSION");
        assertEquals(1.0, kpi02(stepRows), 0.0);
    }

    @Test
    @DisplayName("真执行器 + 失败形状：FAILURE 与 ROLLBACK 行落进核对物（规则 4/5 不在纯 happy path 上空真）")
    void realExecutorFailureLinesReachTheVo() throws Exception {
        // E-1 非致命 PATCH 失败（带回滚信号）⇒ FAILURE(WARN) + 恰一行 ROLLBACK；
        // E-2 致命 SCRIPT 退出 3 ⇒ FAILURE(ERROR) + exitCode 3 ⇒ 致命终止，不收尾、不进 HEALTH_CHECK
        PatchStepDeclaration patchStep = new PatchStepDeclaration("桩失败补丁",
                "http://127.0.0.1:8099/patch/stub-version-marker.zip", "stub-patch", null, null, null, false);
        ScriptStepDeclaration scriptStep = new ScriptStepDeclaration("桩退出脚本", "echo STUB-MARKER",
                null, null, ScriptPosition.HOST, true, null);
        DeployVersionDeclaration declaration = new DeployVersionDeclaration("2.0.0-patched", "桩改造版",
                null, false, List.of(patchStep), List.of(scriptStep));
        VersionEntry entry = new VersionEntry(declaration, "桩改造版", false, List.of());
        DeployVersionCatalogService catalog = mock(DeployVersionCatalogService.class);
        when(catalog.read(anyString(), anyString())).thenReturn(CatalogView.available(List.of(entry)));
        PluginFrameworkService plugins = mock(PluginFrameworkService.class);
        when(plugins.getExtensionByGameCode(anyString())).thenReturn(null);
        doAnswer(invocation -> {
            ((com.gameplatform.plugin.patch.PatchInstallProgressListener) invocation.getArgument(1))
                    .onLog("已回滚备份");
            throw new RuntimeException("补丁包写入目标路径失败");
        }).when(patchInstallService).installSync(any(), any());
        when(scriptRunner.run(any(), anyInt(), anyLong(), anyString())).thenReturn(
                new ExtensionScriptRunner.ScriptRunResult(3, false, 600_000L, 9L,
                        new ExtensionScriptRunner.TruncatedOutput("STUB-FAIL", 9L, false),
                        new ExtensionScriptRunner.TruncatedOutput("", 0L, false)));
        org.springframework.test.util.ReflectionTestUtils.setField(deployService, "deployExtensionExecutor",
                new DeployExtensionExecutor(catalog, plugins, patchInstallService, scriptRunner));
        when(adapter.getStatus(anyLong(), any())).thenReturn(DeployAdapter.InstanceStatus.STOPPED);

        assertFalse(deployService.deploy(context(), sampler()));

        InstanceController.DeployProgressVO vo = controller().getDeployProgress(INSTANCE_ID).getData();
        writeEvidence("deploy-progress-with-failure.json");

        List<InstanceController.LogEntryVO> rows = vo.getLogs();
        List<InstanceController.LogEntryVO> failures = rows.stream()
                .filter(r -> "FAILURE".equals(r.getStepEvent()) && r.getStepId() != null).toList();
        assertEquals(2, failures.size(), "两支失败行都在");
        assertEquals(List.of("WARN", "ERROR"), failures.stream()
                .map(InstanceController.LogEntryVO::getLevel).toList(), "非致命 WARN / 致命 ERROR");
        assertEquals(java.util.Arrays.asList(null, 3), failures.stream()
                .map(InstanceController.LogEntryVO::getExitCode).toList(), "PATCH 恒 null；SCRIPT 带退出码");
        assertEquals(1, rows.stream().filter(r -> "ROLLBACK".equals(r.getStepEvent())).count(), "规则 5");
        assertTrue(rows.stream().noneMatch(r -> r.getMessage() != null && r.getMessage().startsWith("部署扩展阶段收尾")),
                "致命终止那一路不收尾（X-07 C）");
    }

    /** KPI-02 的机械比例：分子 = 三项齐备的步骤数，分母 = 非空 stepId 去重计数。 */
    private static double kpi02(List<InstanceController.LogEntryVO> stepRows) {        List<String> ids = stepRows.stream().map(InstanceController.LogEntryVO::getStepId).distinct().toList();
        long complete = ids.stream().filter(id -> {
            List<InstanceController.LogEntryVO> group = stepRows.stream()
                    .filter(r -> id.equals(r.getStepId())).toList();
            long starts = group.stream().filter(r -> "START".equals(r.getStepEvent())).count();
            List<InstanceController.LogEntryVO> terminals = group.stream()
                    .filter(r -> "SUCCESS".equals(r.getStepEvent()) || "FAILURE".equals(r.getStepEvent())).toList();
            return starts == 1 && terminals.size() == 1 && terminals.get(0).getElapsedMs() != null;
        }).count();
        return ids.isEmpty() ? 1.0 : (double) complete / ids.size();
    }

    // ==================== 夹具 ====================

    private DeployService.DeployContext context() {
        return DeployService.DeployContext.builder()
                .instanceId(INSTANCE_ID)
                .hostId(1L)
                .deployType(DeployAdapter.DeployType.DOCKER_COMPOSE)
                .config(new HashMap<>(Map.of("instanceId", INSTANCE_ID)))
                .autoRollback(false)
                // autoStart=false：START 分支里有既有的 5 s sleep，与本票判定无关
                .autoStart(false)
                .explicitVersionSelection(true)
                .build();
    }

    /** 每次回调事件后采一次顶层值，等价于对 {@code deploy-progress} 的同频轮询（V-10 的采样对象）。 */
    private com.gameplatform.adapter.DeployProgressCallback sampler() {
        return new com.gameplatform.adapter.DeployProgressCallback() {
            @Override
            public void onProgress(int percent, String stage, String message) {
                sample();
            }

            @Override
            public void onComplete(boolean success, String message) {
                sample();
            }

            @Override
            public void onError(String error, String stage, boolean recoverable) {
                sample();
            }

            @Override
            public void onLog(String level, String message) {
                sample();
            }

            @Override
            public void onStageStart(String stage, String description) {
                sample();
            }

            @Override
            public void onStageComplete(String stage, boolean success, String message) {
                sample();
            }

            private void sample() {
                DeployService.DeployTaskStatus status = deployService.getTaskStatus(INSTANCE_ID);
                if (status != null) {
                    progressSamples.add(status.getProgress());
                    stageSamples.add(status.getStage());
                    statusSamples.add(status.getStatus());
                }
            }
        };
    }

    private void stubEnvironmentChecksPass() {
        when(sshUtil.executeCommand(anyString(), anyInt(), anyString(), any(), any(), anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    String command = invocation.getArgument(5);
                    SshUtil.CommandResult result = new SshUtil.CommandResult();
                    result.setSuccess(true);
                    result.setExitCode(0);
                    result.setOutput(command.startsWith("df -h") ? "10"
                            : command.startsWith("free") ? "50.0"
                            : command.contains("--version")
                            ? "Docker version 24.0.0 / Docker Compose version v2" : "ok");
                    return result;
                });
    }

    private static ExtensionLogLine stepRow(String stepId, int index, int total, String label, String type,
                                            String event, Long elapsedMs, Integer exitCode) {
        return new ExtensionLogLine("SUCCESS".equals(event) ? "SUCCESS" : "INFO",
                "步骤 " + index + "/" + total + " " + label, DeployExtensionExecutor.EXTENSION_STAGE,
                stepId, index, total, label, type, event, elapsedMs, exitCode);
    }

    private DeployService.DeployTaskStatus finalStatus() {
        return deployService.getTaskStatus(INSTANCE_ID);
    }

    private int firstProgressAtStage(String wantedStage) {
        for (int i = 0; i < stageSamples.size(); i++) {
            if (wantedStage.equals(stageSamples.get(i))) {
                return progressSamples.get(i);
            }
        }
        throw new AssertionError("采样里没有阶段 " + wantedStage);
    }

    private Integer lastWrittenRunStatus() {
        ArgumentCaptor<GameInstance> captor = ArgumentCaptor.forClass(GameInstance.class);
        verify(instanceMapper, atLeastOnce()).updateById(captor.capture());
        List<GameInstance> all = captor.getAllValues();
        return all.get(all.size() - 1).getRunStatus();
    }

    private static void assertNotNullStage(InstanceController.DeployProgressVO vo) {
        org.junit.jupiter.api.Assertions.assertNotNull(vo, "deploy-progress 响应为空");
    }

    private InstanceController controller() {
        return new InstanceController(mock(InstanceService.class), mock(HostService.class),
                mock(GamePlatformConfig.class), mock(AbstractInstanceFileService.class), deployService);
    }

    /** 把 {@code deploy-progress} 的真实响应体落成 V-08 核对脚本的输入（取证，不改判据）。 */
    private void writeEvidence(String fileName) throws Exception {
        InstanceController.DeployProgressVO vo = controller().getDeployProgress(INSTANCE_ID).getData();
        java.nio.file.Path dir = java.nio.file.Path.of(EVIDENCE_DIR);
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve(fileName),
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(vo));
    }

    private static GameInstance instance() {
        GameInstance instance = new GameInstance();
        instance.setId(INSTANCE_ID);
        instance.setInstanceName("扩展接线核对");
        instance.setHostId(1L);
        instance.setGameCode("stub");
        instance.setDeployType("docker-compose");
        instance.setRunStatus(DeployAdapter.InstanceStatus.INSTALLING.getCode());
        instance.setConfigInfo(new HashMap<>(Map.of("deployVersion", "2.0.0-patched")));
        instance.setRuntimeMetadata(new HashMap<>(Map.of("workDir", "/srv/stub")));
        return instance;
    }

    private static Host host() {
        Host host = new Host();
        host.setId(1L);
        host.setHostName("核对主机");
        host.setIpAddress("10.0.0.1");
        host.setSshPort(22);
        host.setSshUser("root");
        return host;
    }

    /**
     * 阶段级行的取样替身：词面与 {@code Words}（登记处）同源，本类只核对它们经
     * {@code DeployService} 落地后字段不丢，不在此重复核对词面本身。
     */
    private static final class WordsShim {
        private static final String FINISH_FAILED_DIALOG =
                "部署扩展阶段收尾失败：容器未能恢复到运行态。实例状态已置为异常，未启动。";

        static ExtensionLogLine enter() {
            return new ExtensionLogLine("INFO", "进入部署扩展阶段",
                    DeployExtensionExecutor.EXTENSION_STAGE, null, null, null, null, null, null, null, null);
        }

        static ExtensionLogLine finishSuccess() {
            return new ExtensionLogLine("SUCCESS", "部署扩展阶段收尾 · 容器已恢复到运行态 · 成功 · 耗时 1秒",
                    DeployExtensionExecutor.EXTENSION_STAGE, null, null, null, null, null, "SUCCESS", 1_000L, null);
        }

        static ExtensionLogLine finishFailed() {
            return new ExtensionLogLine("ERROR",
                    "部署扩展阶段收尾 · 容器未能恢复到运行态 · 失败 · 原因：起回后未确认容器处于运行态",
                    DeployExtensionExecutor.EXTENSION_STAGE, null, null, null, null, null, "FAILURE", 1_000L, null);
        }

        static ExtensionLogLine complete() {
            return new ExtensionLogLine("SUCCESS", "部署扩展阶段完成 · 共 2 步 · 总耗时 5秒",
                    DeployExtensionExecutor.EXTENSION_STAGE, null, null, null, null, null, "SUCCESS", 5_000L, null);
        }

        static ExtensionLogLine handoff() {
            return new ExtensionLogLine("INFO", "部署扩展阶段结束，进入健康检查与启动",
                    DeployExtensionExecutor.EXTENSION_STAGE, null, null, null, null, null, null, null, null);
        }
    }
}

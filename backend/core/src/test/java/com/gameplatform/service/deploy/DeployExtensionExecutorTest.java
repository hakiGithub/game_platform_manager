package com.gameplatform.service.deploy;

import com.gameplatform.adapter.DeployAdapter;
import com.gameplatform.deploy.CatalogView;
import com.gameplatform.deploy.DeployVersionCatalogService;
import com.gameplatform.deploy.ExtensionScriptRunner;
import com.gameplatform.deploy.VersionEntry;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.plugin.extension.GameEnhancementExtension;
import com.gameplatform.plugin.extension.deploy.DeployExtensionContext;
import com.gameplatform.plugin.extension.deploy.DeployVersionDeclaration;
import com.gameplatform.plugin.extension.deploy.PatchStepDeclaration;
import com.gameplatform.plugin.extension.deploy.ScriptPosition;
import com.gameplatform.plugin.extension.deploy.ScriptStepDeclaration;
import com.gameplatform.plugin.patch.PatchInstallProgressListener;
import com.gameplatform.plugin.patch.PatchInstallService;
import com.gameplatform.plugin.service.PluginFrameworkService;
import com.gameplatform.service.DeployService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DeployExtensionExecutor} 的框架语义与呈现契约核对（B-09）。
 *
 * <p>本票的框架类判定在这里逐条落成断言：§16.2 解析顺序 ①②③、§14.5 停止语义（V-11）、
 * §14.6 五条规则（V-08 / KPI-02 / AC-03）、§14.6 {@code stage} 两支钉值、BR-12 四态拦截
 * （V-27 ②、AC-20 族）、§14.12/§14.13 收尾两支（V-27 ①③、V-12）、§14.7 进度算术（V-10）、
 * V-04 致命性与顺序、V-22 两个判据块、V-24 两类形态互不重叠。</p>
 *
 * <p><b>断言只读契约字段</b>（{@code stepId}/{@code stepEvent}/{@code elapsedMs}/{@code exitCode}/
 * {@code stage}），不读 {@code message}——例外处已就地注明该行是文本核对（V-22 判据块 2）还是
 * 词面槽位填充核对，两者都不是 KPI-02 的机械判据。</p>
 *
 * <p>桩/夹具结果只计入框架类判定，不得登记为 AC-05 / KPI-01 / KPI-03 的通过（BR-15、V-18 / AC-26 ①）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("部署扩展阶段执行器（B-09）")
class DeployExtensionExecutorTest {

    private static final Long INSTANCE_ID = 7L;
    private static final Long HOST_ID = 3L;
    private static final String GAME_CODE = "stub";
    private static final String DEPLOY_TYPE = "docker-compose";
    private static final String VERSION_ID = "2.0.0-patched";
    private static final String EXTENSION = DeployExtensionExecutor.EXTENSION_STAGE;

    @Mock
    private DeployVersionCatalogService catalogService;
    @Mock
    private PluginFrameworkService pluginFrameworkService;
    @Mock
    private PatchInstallService patchInstallService;
    @Mock
    private ExtensionScriptRunner scriptRunner;
    @Mock
    private DeployAdapter adapter;
    @Mock
    private GameEnhancementExtension extension;

    private DeployExtensionExecutor executor;
    private RecordingSink sink;
    private GameInstance instance;
    private Map<String, Object> config;

    @BeforeEach
    void setUp() {
        executor = new DeployExtensionExecutor(catalogService, pluginFrameworkService,
                patchInstallService, scriptRunner);
        // 停止判定的间隔走测试接缝（生产值 2 s 不变，判据「3 次」不变），否则每例真等 6 s
        executor.stopPollIntervalMs = 1L;
        sink = new RecordingSink();

        instance = new GameInstance();
        instance.setId(INSTANCE_ID);
        instance.setGameCode(GAME_CODE);
        instance.setDeployType(DEPLOY_TYPE);
        instance.setConfigInfo(new HashMap<>(Map.of(DeployVersionCatalogService.VERSION_KEY, VERSION_ID)));
        instance.setRuntimeMetadata(new HashMap<>(Map.of("workDir", "/srv/stub")));

        config = new HashMap<>();
        config.put("instanceId", INSTANCE_ID);
    }

    // ==================== §16.2 步骤集解析唯一顺序 ====================

    @Nested
    @DisplayName("§16.2 步骤集解析唯一顺序")
    class StepResolution {

        @Test
        @DisplayName("① 动态入口非空 ⇒ 用它，不取条目自带步骤")
        void dynamicEntryWins() {
            givenCatalog(CatalogView.available(List.of(entry(VERSION_ID,
                    List.of(patch("条目补丁", true)), List.of()))));
            when(pluginFrameworkService.getExtensionByGameCode(GAME_CODE)).thenReturn(extension);
            when(extension.getDeployExtensionSteps(any())).thenReturn(List.of(script("动态脚本", true)));
            givenScriptExit(0);
            givenStageCanFinish();

            assertTrue(run());

            assertEquals(List.of("E-1"), stepIds(sink.lines));
            assertEquals("动态脚本", label(sink.lines, "E-1"));
            verify(patchInstallService, never()).installSync(any(), any());
        }

        @Test
        @DisplayName("② 动态入口为空 ⇒ 取条目 patches ++ scripts，按声明序编号（FR-05 / BR-08）")
        void entryStepsInDeclarationOrder() {
            givenSteps(List.of(patch("补丁一", true)), List.of(script("脚本二", true), script("脚本三", true)));

            assertTrue(run());

            assertEquals(List.of("E-1", "E-2", "E-3"), stepIds(sink.lines));
            assertEquals(List.of("补丁一", "脚本二", "脚本三"), List.of(
                    label(sink.lines, "E-1"), label(sink.lines, "E-2"), label(sink.lines, "E-3")));
            assertEquals(List.of(1, 2, 3), indexes(sink.lines));
            assertEquals(List.of(3), totals(sink.lines), "stepTotal 在阶段入口算步骤集时即得");
        }

        @Test
        @DisplayName("③ 两路都空 ⇒ 不进入扩展阶段：零行、零进度、一次适配器调用都没有（AC-15 序列逐字相同）")
        void noStepsMeansNoStage() {
            givenCatalog(CatalogView.available(List.of(entry(VERSION_ID, List.of(), List.of()))));
            when(pluginFrameworkService.getExtensionByGameCode(GAME_CODE)).thenReturn(extension);
            when(extension.getDeployExtensionSteps(any())).thenReturn(List.of());

            assertFalse(run());

            assertEquals(List.of(), sink.lines);
            assertEquals(List.of(), sink.progress);
            verify(adapter, never()).stop(anyLong(), any());
            verify(adapter, never()).ensureRunningForExtension(anyLong(), any());
        }

        @Test
        @DisplayName("① 支 SPI 抛异常 ⇒ 回落 ②，异常不外泄成未分类失败")
        void dynamicEntryExceptionFallsBackToEntry() {
            givenCatalog(CatalogView.available(List.of(entry(VERSION_ID,
                    List.of(patch("条目补丁", true)), List.of()))));
            when(pluginFrameworkService.getExtensionByGameCode(GAME_CODE)).thenReturn(extension);
            when(extension.getDeployExtensionSteps(any())).thenThrow(new IllegalStateException("插件内部异常"));
            givenStageCanFinish();

            assertTrue(run());

            assertEquals(List.of("E-1"), stepIds(sink.lines));
            assertEquals("条目补丁", label(sink.lines, "E-1"));
        }

        @Test
        @DisplayName("ctx 的 instanceId / selectedVersionId / configInfo 与该实例库中状态一致（V-24「① 支真被走到」）")
        void contextCarriesInstanceState() {
            givenSteps(List.of(patch("条目补丁", true)), List.of());
            when(extension.getDeployExtensionSteps(any())).thenReturn(List.of());

            run();

            ArgumentCaptor<DeployExtensionContext> captor = ArgumentCaptor.forClass(DeployExtensionContext.class);
            verify(extension).getDeployExtensionSteps(captor.capture());
            DeployExtensionContext ctx = captor.getValue();
            assertEquals(INSTANCE_ID, ctx.instanceId());
            assertEquals(GAME_CODE, ctx.gameCode());
            assertEquals(DEPLOY_TYPE, ctx.deployType());
            assertEquals(VERSION_ID, ctx.selectedVersionId());
            assertEquals(instance.getConfigInfo(), ctx.configInfo());
        }
    }

    // ==================== BR-12 ====================

    @Nested
    @DisplayName("BR-12 拦截与目录说明行")
    class Br12AndCatalogNotes {

        @Test
        @DisplayName("有键 + 目录 ABSENT ⇒ 进入行 + 拦截行即 ERROR 终止：无步骤行、无收尾 / 完成 / 交棒行")
        void absentCatalogWithKeyIntercepts() {
            givenCatalog(CatalogView.absent());

            Outcome outcome = runExpectingFailure();

            assertEquals(List.of(), outcome.stepIds);
            assertEquals("进入部署扩展阶段", outcome.texts.get(0));
            assertFalse(anyStartWith(outcome.rows, "部署扩展阶段收尾"), "X-07 C：拦截那一路不得有收尾行");
            assertFalse(anyStartWith(outcome.rows, "部署扩展阶段完成"));
            assertFalse(anyStartWith(outcome.rows, "部署扩展阶段结束"));
            verify(adapter, never()).stop(anyLong(), any());
        }

        @Test
        @DisplayName("拦截行 stage == EXTENSION（v0.3.5 拆分钉值 ①），且全部 stepId == null")
        void interceptionRowsAreStagedExtension() {
            givenCatalog(CatalogView.invalid("条目「x」的 url 不得为空"));

            Outcome outcome = runExpectingFailure();

            assertTrue(outcome.rows.stream().allMatch(r -> EXTENSION.equals(r.stage())));
            assertTrue(outcome.rows.stream().allMatch(r -> r.stepId() == null));
            assertTrue(outcome.rows.stream().allMatch(r -> r.stepEvent() == null));
        }

        @Test
        @DisplayName("本次显式选择 ⇒ 「本次所选」词面，且恢复路径行不出现")
        void explicitSelectionWordingBranch() {
            givenCatalog(CatalogView.absent());

            Outcome outcome = runExpectingFailure(true);

            assertTrue(outcome.texts.get(1).contains("本次所选版本 " + VERSION_ID), outcome.texts.get(1));
            assertFalse(anyStartWith(outcome.rows, "恢复路径："));
        }

        @Test
        @DisplayName("配置既存键 ⇒ 「由既往部署写入」词面 + 恢复路径行")
        void existingKeyWordingBranch() {
            givenCatalog(CatalogView.absent());

            Outcome outcome = runExpectingFailure(false);

            assertTrue(outcome.texts.get(1).contains("实例配置要求的版本"), outcome.texts.get(1));
            assertTrue(outcome.texts.get(1).contains("由既往部署写入实例配置"));
            assertTrue(anyStartWith(outcome.rows, "恢复路径："));
        }

        @Test
        @DisplayName("目录 AVAILABLE 但所选 versionId 不在条目中（G2）⇒ 拦截，不静默按默认版本交付")
        void versionNotInCatalogIntercepts() {
            givenCatalog(CatalogView.available(List.of(entry("9.9.9", List.of(), List.of()))));

            Outcome outcome = runExpectingFailure();

            assertEquals(List.of(), outcome.stepIds);
            verify(patchInstallService, never()).installSync(any(), any());
        }

        @Test
        @DisplayName("EMPTY 目录 + 有键 ⇒ versionId 必然不在条目中 ⇒ 拦截，且不产「不合法」提示行")
        void emptyCatalogWithKeyIntercepts() {
            givenCatalog(CatalogView.empty());

            Outcome outcome = runExpectingFailure();

            assertFalse(anyStartWith(outcome.rows, "提示："));
        }

        @Test
        @DisplayName("无键 + INVALID ⇒ 只产一条说明行，stage 归实际发生所在的既有阶段（本期 DEPLOY），不拦截")
        void invalidWithoutKeyEmitsNoteOnCurrentStage() {
            instance.setConfigInfo(new HashMap<>());
            givenCatalog(CatalogView.invalid("条目「x」的 versionId 含 [A-Za-z0-9._-] 之外的字符"));

            assertFalse(run());

            assertEquals(1, sink.lines.size());
            ExtensionLogLine note = sink.lines.get(0);
            assertEquals("DEPLOY", note.stage(), "钉的是「行归它实际发生的阶段」这条判据，不是 EXTENSION 字面量");
            assertEquals("WARN", note.level());
            assertNull(note.stepId());
            assertTrue(note.message().contains("条目「x」的 versionId"), "〈校验失败要点〉槽位取 invalidReason");
            verify(adapter, never()).stop(anyLong(), any());
        }

        @Test
        @DisplayName("无键 + ABSENT / EMPTY / AVAILABLE（默认条目）⇒ 零行（AC-24 ③⑤、AC-02 / AC-15 同族）")
        void noKeyWithoutInvalidEmitsNothing() {
            instance.setConfigInfo(new HashMap<>());
            for (CatalogView view : List.of(CatalogView.absent(), CatalogView.empty(),
                    CatalogView.available(List.of(entry(VERSION_ID, List.of(), List.of()))))) {
                sink = new RecordingSink();
                givenCatalog(view);
                assertFalse(run(), "state=" + view.state());
                assertEquals(List.of(), sink.lines, "state=" + view.state());
            }
        }
    }

    // ==================== §14.5 停实例（V-11） ====================

    @Nested
    @DisplayName("§14.5 停止语义")
    class StopSemantics {

        @Test
        @DisplayName("行序即 §6.2 规则 1 的开头：进入行 → 停实例前置行 → 停实例完成行 → 步骤行")
        void stopRowsPrecedeStepRows() {
            givenSteps(List.of(patch("补丁一", true)), List.of());

            assertTrue(run());

            assertEquals(List.of("进入部署扩展阶段",
                    "正在停止实例，确保扩展步骤在实例未运行时执行",
                    "实例已停止"),
                    List.of(sink.lines.get(0).message(), sink.lines.get(1).message(), sink.lines.get(2).message()));
            assertEquals(EXTENSION, sink.lines.get(2).stage());
            assertEquals("E-1", sink.lines.get(3).stepId());
            verify(adapter).stop(INSTANCE_ID, config);
        }

        @Test
        @DisplayName("停不下来（3 次判定仍 RUNNING）⇒ 致命：零步骤行、无收尾行")
        void stopFailureIsFatalWithZeroStepRows() {
            givenSteps(List.of(patch("补丁一", true)), List.of());
            when(adapter.getStatus(eq(INSTANCE_ID), any())).thenReturn(DeployAdapter.InstanceStatus.RUNNING);

            Outcome outcome = runExpectingFailure();

            verify(adapter, times(DeployExtensionExecutor.STOP_POLL_ATTEMPTS)).getStatus(eq(INSTANCE_ID), any());
            assertEquals(List.of(), outcome.stepIds, "V-11：停失败注入 ⇒ ERROR 且零步骤行");
            assertFalse(anyStartWith(outcome.rows, "部署扩展阶段收尾"));
            assertTrue(anyStartWith(outcome.rows, "实例停止失败："));
        }

        @Test
        @DisplayName("适配器 stop() 抛异常 ⇒ 同样致命，且不再做停止判定")
        void stopThrowIsFatal() {
            givenSteps(List.of(patch("补丁一", true)), List.of());
            when(adapter.stop(eq(INSTANCE_ID), any())).thenThrow(new IllegalStateException("SSH 通道断开"));

            Outcome outcome = runExpectingFailure();

            assertEquals(List.of(), outcome.stepIds);
            verify(adapter, never()).getStatus(anyLong(), any());
        }
    }

    // ==================== §14.6 五条规则（V-08 / KPI-02） ====================

    @Nested
    @DisplayName("§14.6 五条规则")
    class PresentationContract {

        @Test
        @DisplayName("规则 1 归组：一次部署内 stepId 与 stepIndex 一一对应")
        void rowsGroupByStepId() {
            givenSteps(List.of(patch("补丁一", true)), List.of(script("脚本二", true)));

            run();

            Map<String, Integer> indexByStepId = new HashMap<>();
            for (ExtensionLogLine row : sink.lines) {
                if (row.stepId() != null) {
                    indexByStepId.put(row.stepId(), row.stepIndex());
                }
            }
            assertEquals(Map.of("E-1", 1, "E-2", 2), indexByStepId);
        }

        @Test
        @DisplayName("规则 2 三项齐备：每个非空 stepId 恰一 START + 恰一终态且终态 elapsedMs 非空 ⇒ KPI-02 = 100%")
        void everyStepIsComplete() {
            givenSteps(List.of(patch("补丁一", true)), List.of(script("脚本二", true)));

            run();

            List<String> denominator = stepIds(sink.lines);
            assertEquals(List.of("E-1", "E-2"), denominator);
            for (String stepId : denominator) {
                List<ExtensionLogLine> group = rowsOf(sink.lines, stepId);
                assertEquals(1, countEvent(group, "START"), stepId);
                assertEquals(1, countEvent(group, "SUCCESS") + countEvent(group, "FAILURE"), stepId);
                assertNotNull(terminal(group).elapsedMs(), stepId + " 的终态行必须带耗时（AC-03 耗时可见）");
            }
            assertEquals(1.0, kpi02Ratio(sink.lines), 0.0);
        }

        @Test
        @DisplayName("规则 2 分母排除阶段级行：收尾行带 stepEvent 但 stepId == null ⇒ 不进分母（SUG-13）")
        void stageRowsAreOutsideDenominator() {
            givenSteps(List.of(patch("补丁一", true)), List.of());

            run();

            List<ExtensionLogLine> stageRows = sink.lines.stream().filter(r -> r.stepId() == null).toList();
            assertEquals(6, stageRows.size(),
                    "进入 / 停实例前置 / 停实例完成 / 收尾 / 阶段完成 / 交棒 六条阶段级行");
            assertTrue(stageRows.stream().noneMatch(r -> r.stepId() != null));
            assertEquals(1.0, kpi02Ratio(sink.lines), 0.0);
        }

        @Test
        @DisplayName("规则 3 串行可见：步骤 N 的终态行之后才出现 N+1 的 START（AC-06 / FR-12）")
        void serialVisibility() {
            givenSteps(List.of(patch("补丁一", true)), List.of(script("脚本二", true), script("脚本三", true)));

            run();

            assertEquals(List.of("E-1", "E-1", "E-2", "E-2", "E-3", "E-3"), stepIdSequence(sink.lines));
            int indexOfSecondStart = indexOf(sink.lines,
                    r -> "E-2".equals(r.stepId()) && "START".equals(r.stepEvent()));
            int indexOfFirstTerminal = indexOf(sink.lines,
                    r -> "E-1".equals(r.stepId()) && "SUCCESS".equals(r.stepEvent()));
            assertTrue(indexOfFirstTerminal < indexOfSecondStart);
        }

        @Test
        @DisplayName("规则 4 承载位：PATCH 终态 exitCode == null；SCRIPT 成功终态 exitCode == 0")
        void exitCodeOnlyOnScriptRows() {
            givenSteps(List.of(patch("补丁一", true)), List.of(script("脚本二", true)));

            run();

            assertNull(terminal(rowsOf(sink.lines, "E-1")).exitCode(), "§14.6：执行器不回报退出码，不为此改 PatchInstallExecutor");
            assertEquals(0, terminal(rowsOf(sink.lines, "E-2")).exitCode());
            assertEquals("PATCH", rowsOf(sink.lines, "E-1").get(0).stepType());
            assertEquals("SCRIPT", rowsOf(sink.lines, "E-2").get(0).stepType());
        }

        @Test
        @DisplayName("V-22 判据块 1：exit 3 ⇒ FAILURE ∧ exitCode == 3 ∧ 该步致命 ⇒ 部署 ERROR")
        void nonzeroExitFailsTheStep() {
            givenSteps(List.of(), List.of(script("脚本一", true)));
            givenScriptExit(3);

            Outcome outcome = runExpectingFailure();

            ExtensionLogLine terminal = terminal(outcome.rows);
            assertEquals("FAILURE", terminal.stepEvent());
            assertEquals(3, terminal.exitCode());
            assertEquals("ERROR", terminal.level());
            assertTrue(outcome.failed);
        }

        @Test
        @DisplayName("规则 4 超时支：exitCode == null 且原因段指名 timeoutMs（与非零退出可靠区分）")
        void timeoutKeepsExitCodeNullAndNamesBudget() {
            givenSteps(List.of(), List.of(script("脚本一", true)));
            when(scriptRunner.run(any(), anyInt(), anyLong(), anyString())).thenReturn(
                    scriptResult(null, true, 60_000L, 60_000L));

            Outcome outcome = runExpectingFailure();

            ExtensionLogLine terminal = terminal(outcome.rows);
            assertNull(terminal.exitCode());
            assertTrue(terminal.message().contains("60000"), "〈timeoutMs〉 槽位有值（原因段，非机械判据）");
            assertTrue(terminal.message().contains("未返回，判失败"));
            assertTrue(terminal.message().contains("脚本可能仍在宿主机后台继续执行"), "§15.3 的诚实限制随行");
        }

        @Test
        @DisplayName("V-22 判据块 2 + 截断 NOTE 接线：同 stepId 下有承载 stdout / stderr 的 NOTE 行与截断说明行")
        void outputAndTruncationRideNoteRows() {
            givenSteps(List.of(), List.of(script("脚本一", true)));
            when(scriptRunner.run(any(), anyInt(), anyLong(), anyString())).thenReturn(
                    new ExtensionScriptRunner.ScriptRunResult(0, false, 600_000L, 5L,
                            new ExtensionScriptRunner.TruncatedOutput("STUB-STDOUT-MARKER", 9_000L, true),
                            new ExtensionScriptRunner.TruncatedOutput("STUB-STDERR-MARKER", 9_000L, true)));

            run();

            List<ExtensionLogLine> notes = rowsOf(sink.lines, "E-1").stream()
                    .filter(r -> "NOTE".equals(r.stepEvent())).toList();
            assertEquals(4, notes.size(), "stdout / 其截断行 / stderr / 其截断行");
            assertTrue(notes.stream().anyMatch(n -> n.message().contains("STUB-STDOUT-MARKER")));
            assertTrue(notes.stream().anyMatch(n -> n.message().contains("STUB-STDERR-MARKER")));
            // 截断说明必须来自 truncationNote()：漏调界面就读到「头尾无缝相连」的假完整输出（stage 3 G2 前提 2）
            assertTrue(notes.stream().anyMatch(n -> n.message().equals("输出已截断，共 9000 字节")),
                    notes.stream().map(ExtensionLogLine::message).toList().toString());
            // NOTE 行排在终态行之前，否则 X-07 A（收尾成功行的前一行必须是最后一步的终态行）会被判破
            assertTrue(sink.lines.indexOf(notes.get(0)) < indexOf(sink.lines, r -> "SUCCESS".equals(r.stepEvent())
                    && "E-1".equals(r.stepId())));
        }

        @Test
        @DisplayName("规则 5：PATCH 终态 FAILURE ⇒ 终态之后、下一步 START 之前恰有一行 ROLLBACK（已回滚备份 → INFO）")
        void rollbackRowPositionAndLevel() {
            givenSteps(List.of(patch("补丁一", false), patch("补丁二", true)), List.of());
            AtomicInteger invocations = new AtomicInteger();
            doAnswer(invocation -> {
                if (invocations.incrementAndGet() == 1) {
                    PatchInstallProgressListener listener = invocation.getArgument(1);
                    listener.onLog("已回滚备份");
                    throw new RuntimeException("补丁 sha256 校验失败: 期望 a，实际 b");
                }
                return null;
            }).when(patchInstallService).installSync(any(), any());

            assertTrue(run());

            List<ExtensionLogLine> rows = sink.lines;
            List<ExtensionLogLine> group = rowsOf(rows, "E-1");
            assertEquals(1, countEvent(group, "ROLLBACK"));
            ExtensionLogLine rollback = group.stream().filter(r -> "ROLLBACK".equals(r.stepEvent())).findFirst().orElseThrow();
            assertEquals("INFO", rollback.level());
            assertEquals("已回滚备份", rollback.message());
            assertNull(rollback.elapsedMs(), "回滚记录位不是终态行");
            int terminalIndex = indexOf(rows, r -> "E-1".equals(r.stepId()) && "FAILURE".equals(r.stepEvent()));
            int nextStartIndex = indexOf(rows, r -> "E-2".equals(r.stepId()) && "START".equals(r.stepEvent()));
            assertTrue(rows.indexOf(rollback) > terminalIndex, "必须在终态行之后");
            assertTrue(rows.indexOf(rollback) < nextStartIndex, "必须在下一步 START 之前");
        }

        @Test
        @DisplayName("规则 5：执行器回报回滚失败 ⇒ level == ERROR，词面原样转写")
        void rollbackFailureRowIsError() {
            givenSteps(List.of(patch("补丁一", true)), List.of());
            doAnswer(invocation -> {
                PatchInstallProgressListener listener = invocation.getArgument(1);
                listener.onLog("回滚失败: 权限不足");
                throw new RuntimeException("目标路径写入失败");
            }).when(patchInstallService).installSync(any(), any());

            Outcome outcome = runExpectingFailure();

            ExtensionLogLine rollback = outcome.rows.stream()
                    .filter(r -> "ROLLBACK".equals(r.stepEvent())).findFirst().orElseThrow();
            assertEquals("ERROR", rollback.level());
            assertEquals("回滚失败: 权限不足", rollback.message());
            assertEquals("E-1", rollback.stepId());
        }

        @Test
        @DisplayName("规则 5：失败发生在备份之前（执行器没回报过回滚）⇒ 仍恰有一行，INFO + 无备份可回滚")
        void rollbackRowExistsWithoutBackup() {
            givenSteps(List.of(patch("补丁一", true)), List.of());
            doThrow(new RuntimeException("补丁包来源不可达 http://127.0.0.1:8099/patch/x.zip"))
                    .when(patchInstallService).installSync(any(), any());

            Outcome outcome = runExpectingFailure();

            assertEquals(1, countEvent(outcome.rows, "ROLLBACK"));
            ExtensionLogLine rollback = outcome.rows.stream()
                    .filter(r -> "ROLLBACK".equals(r.stepEvent())).findFirst().orElseThrow();
            assertEquals("INFO", rollback.level());
            assertEquals("无备份可回滚", rollback.message());
        }

        @Test
        @DisplayName("PATCH 步骤的〈原因〉槽位取宿主执行器的技术归因，含期望/实际摘要（V-06 的核对物）")
        void patchReasonSegmentCarriesExpectedAndActual() {
            givenSteps(List.of(patch("补丁一", true)), List.of());
            doThrow(new RuntimeException("补丁 sha256 校验失败: 期望 deadbeef，实际 cafefood"))
                    .when(patchInstallService).installSync(any(), any());

            Outcome outcome = runExpectingFailure();

            ExtensionLogLine terminal = terminal(outcome.rows);
            assertNull(terminal.exitCode());
            assertTrue(terminal.message().contains("期望 deadbeef"), terminal.message());
            assertTrue(terminal.message().contains("实际 cafefood"));
            // 跨票口径：步骤级失败行的 message 不含引导词「原因：」（前端自己补）
            assertFalse(terminal.message().startsWith("原因："), terminal.message());
        }
    }

    // ==================== V-04 致命性与顺序 ====================

    @Nested
    @DisplayName("V-04 声明序与致命性")
    class FatalAndNonFatal {

        @Test
        @DisplayName("#2 非致命失败记 WARN 后继续，#3 照常判定，阶段正常收尾")
        void nonFatalContinues() {
            givenSteps(List.of(patch("补丁一", true)),
                    List.of(script("脚本二", false), script("脚本三", true)));
            AtomicInteger scriptIndex = new AtomicInteger();
            when(scriptRunner.run(any(), anyInt(), anyLong(), anyString())).thenAnswer(invocation -> {
                int index = invocation.getArgument(1);
                return scriptResult(index == 2 ? 3 : 0, false, 600_000L, scriptIndex.incrementAndGet() * 10L);
            });

            assertTrue(run());

            ExtensionLogLine failure = sink.lines.stream()
                    .filter(r -> "FAILURE".equals(r.stepEvent())).findFirst().orElseThrow();
            assertEquals("E-2", failure.stepId());
            assertEquals("WARN", failure.level());
            assertEquals(3, failure.exitCode());
            assertTrue(anyStartWith(sink.lines, "该步骤声明为非致命，继续执行后续步骤"));
            assertEquals(1, countEvent(rowsOf(sink.lines, "E-3"), "SUCCESS"), "非致命失败后第 3 步仍执行");
            assertFalse(anyStartWith(sink.lines, "致命步骤失败"), "非致命不触发致命终止");
        }

        @Test
        @DisplayName("#3 致命失败 ⇒ 无第 4 步、产致命终止行、阶段不收尾")
        void fatalStopsPipeline() {
            givenSteps(List.of(patch("补丁一", true), patch("补丁二", true), patch("补丁三", true)), List.of());
            AtomicInteger invocations = new AtomicInteger();
            doAnswer(invocation -> {
                if (invocations.incrementAndGet() >= 2) {
                    throw new RuntimeException("下载失败");
                }
                return null;
            }).when(patchInstallService).installSync(any(), any());

            Outcome outcome = runExpectingFailure();

            assertEquals(List.of("E-1", "E-2"), outcome.stepIds, "致命后不得再执行第 3 步");
            assertTrue(anyStartWith(outcome.rows,
                    "致命步骤失败，部署终止：不再执行后续步骤，实例不启动、不交付，状态置为异常"));
            assertFalse(anyStartWith(outcome.rows, "部署扩展阶段收尾"), "X-07 C");
        }
    }

    // ==================== §14.12 / §14.13 收尾 ====================

    @Nested
    @DisplayName("§14.12 / §14.13 收尾两支")
    class Finishing {

        @Test
        @DisplayName("成功支：收尾 SUCCESS 行（承载收尾耗时）→ 阶段完成行 → 交棒行，且完成行的耗时是整段")
        void successBranchEmitsThreeStageRows() {
            givenSteps(List.of(patch("补丁一", true)), List.of(script("脚本二", true)));

            run();

            List<ExtensionLogLine> rows = sink.lines;
            ExtensionLogLine finish = finishRow(rows);
            assertTrue(finish.message().startsWith("部署扩展阶段收尾 · 容器已恢复到运行态 · 成功 · 耗时"),
                    finish.message());
            assertNotNull(finish.elapsedMs(), "收尾耗时预算自此有承载行（§14.12 v0.3.2）");
            assertNull(finish.exitCode());
            assertNull(finish.stepId(), "收尾行是阶段级行，不进 KPI-02 分母");
            ExtensionLogLine complete = rows.get(rows.indexOf(finish) + 1);
            assertTrue(complete.message().startsWith("部署扩展阶段完成 · 共 2 步 · 总耗时"), complete.message());
            assertNotNull(complete.elapsedMs());
            assertEquals("部署扩展阶段结束，进入健康检查与启动", rows.get(rows.indexOf(complete) + 1).message());
            // X-07 A / A′：收尾成功行的前一行是最后一步（2/2）的终态行
            ExtensionLogLine beforeFinish = rows.get(rows.indexOf(finish) - 1);
            assertEquals("E-2", beforeFinish.stepId());
            assertEquals(2, beforeFinish.stepIndex());
            assertEquals(2, beforeFinish.stepTotal());
            // X-07 D：收尾成功行恰一条
            assertEquals(1, rows.stream().filter(r -> r.message().startsWith("部署扩展阶段收尾 · 容器已恢复")).count());
        }

        @Test
        @DisplayName("失败支（起回后未确认运行）⇒ FAILURE + ERROR 是该阶段末行，不接完成 / 交棒（V-27 ③）")
        void failureBranchEndsTheStage() {
            givenSteps(List.of(patch("补丁一", true)), List.of());
            when(adapter.ensureRunningForExtension(anyLong(), any())).thenReturn(false);

            Outcome outcome = runExpectingFailure();

            ExtensionLogLine last = outcome.rows.get(outcome.rows.size() - 1);
            assertEquals("FAILURE", last.stepEvent());
            assertEquals("ERROR", last.level());
            assertNull(last.stepId());
            assertTrue(last.message().startsWith("部署扩展阶段收尾 · 容器未能恢复到运行态 · 失败 · 原因："),
                    "阶段级行按整句词面直出（跨票口径只约束步骤级失败行）");
            assertFalse(anyStartWith(outcome.rows, "部署扩展阶段完成"));
            assertFalse(anyStartWith(outcome.rows, "部署扩展阶段结束"));
        }

        @Test
        @DisplayName("收尾只经 adapter 的一个方法，本类不拼 compose 命令（V-13 / V-27 ① 的结构性判据）")
        void finishGoesThroughAdapterOnly() {
            givenSteps(List.of(patch("补丁一", true)), List.of());

            run();

            verify(adapter).ensureRunningForExtension(INSTANCE_ID, config);
        }

        @Test
        @DisplayName("集合外 deployType：目录 INVALID（N5）⇒ BR-12 处置，默认抛异常路径零调用（V-27 ②）")
        void outOfSetDeployTypeNeverReachesDefaultThrow() {
            instance.setDeployType("docker");
            givenCatalog(CatalogView.invalid("部署方式「docker」下不得声明 patches/scripts/imageTag"));

            Outcome outcome = runExpectingFailure();

            verify(adapter, never()).ensureRunningForExtension(anyLong(), any());
            assertEquals(List.of(), outcome.stepIds);
        }

        @Test
        @DisplayName("适配器起回抛异常 ⇒ 按致命处置，failure 行仍在末尾")
        void finishThrowIsFatal() {
            givenSteps(List.of(patch("补丁一", true)), List.of());
            when(adapter.ensureRunningForExtension(anyLong(), any()))
                    .thenThrow(new UnsupportedOperationException("该部署方式不支持扩展阶段收尾起回"));

            Outcome outcome = runExpectingFailure();

            assertEquals("FAILURE", outcome.rows.get(outcome.rows.size() - 1).stepEvent());
        }
    }

    // ==================== §14.7 进度与接线处校验 ====================

    @Nested
    @DisplayName("§14.7 条件进度与 workDir 挡下")
    class ProgressAndGuards {

        @Test
        @DisplayName("算术：起点 80，其后 80 + floor(4 × i / total)，上限 84，单调不减")
        void progressArithmetic() {
            assertEquals(81, DeployExtensionExecutor.progressAfterStep(1, 3));
            assertEquals(82, DeployExtensionExecutor.progressAfterStep(2, 3));
            assertEquals(84, DeployExtensionExecutor.progressAfterStep(3, 3));
            assertEquals(82, DeployExtensionExecutor.progressAfterStep(1, 2));
            assertEquals(84, DeployExtensionExecutor.progressAfterStep(2, 2));
            assertEquals(84, DeployExtensionExecutor.progressAfterStep(9, 9));
            assertEquals(80, DeployExtensionExecutor.progressAfterStep(0, 3));
        }

        @Test
        @DisplayName("阶段入口报 80，每步判定完成后逐步上报；上报不产日志行")
        void progressReportedWithoutRows() {
            givenSteps(List.of(patch("补丁一", true), patch("补丁二", true)), List.of());

            run();

            assertEquals(List.of(80, 82, 84), sink.progress);
        }

        @Test
        @DisplayName("workDir 为空 ⇒ 在接线处挡下：不进段、零行、一个脚本都不执行")
        void blankWorkDirIsRejectedBeforeEntering() {
            instance.setRuntimeMetadata(new HashMap<>(Map.of("workDir", "  ")));
            givenSteps(List.of(), List.of(script("脚本一", true)));

            DeployService.DeployException thrown =
                    assertThrows(DeployService.DeployException.class, () -> run());

            assertEquals(DeployService.DeployException.class, thrown.getClass(), "不是扩展阶段自己终止的行形制");
            assertEquals(List.of(), sink.lines);
            verify(scriptRunner, never()).run(any(), anyInt(), anyLong(), anyString());
        }

        @Test
        @DisplayName("纯 PATCH 步骤集不需要 workDir（只有脚本要落临时文件）")
        void patchOnlyNeedsNoWorkDir() {
            instance.setRuntimeMetadata(new HashMap<>());
            givenSteps(List.of(patch("补丁一", true)), List.of());

            assertTrue(run());
        }
    }

    // ==================== V-24 ====================

    @Test
    @DisplayName("V-24：① 代码计算支与 ② 条目自带支的 stepId → stepLabel 序列各自等于各自声明序且互不重叠")
    void twoInstancesTwoShapesDoNotCross() {
        GameInstance other = new GameInstance();
        other.setId(8L);
        other.setGameCode(GAME_CODE);
        other.setDeployType(DEPLOY_TYPE);
        other.setConfigInfo(new HashMap<>(Map.of(DeployVersionCatalogService.VERSION_KEY, "2.1.0-selective")));
        other.setRuntimeMetadata(new HashMap<>(Map.of("workDir", "/srv/stub-b")));

        givenCatalog(CatalogView.available(List.of(
                entry(VERSION_ID, List.of(patch("A条目补丁", true)), List.of(script("A条目脚本", true))),
                entry("2.1.0-selective", List.of(patch("B条目补丁", true)), List.of()))));
        when(pluginFrameworkService.getExtensionByGameCode(GAME_CODE)).thenReturn(extension);
        when(extension.getDeployExtensionSteps(any())).thenAnswer(invocation -> {
            DeployExtensionContext ctx = invocation.getArgument(0);
            return VERSION_ID.equals(ctx.selectedVersionId())
                    ? List.of(script("A动态脚本", true), patch("A动态补丁", false))
                    : List.of();
        });
        when(adapter.getStatus(anyLong(), any())).thenReturn(DeployAdapter.InstanceStatus.STOPPED);
        when(adapter.ensureRunningForExtension(anyLong(), any())).thenReturn(true);
        givenScriptExit(0);

        RecordingSink sinkA = new RecordingSink();
        RecordingSink sinkB = new RecordingSink();
        assertTrue(executor.runExtensionPhase(requestFor(instance, true), sinkA));
        assertTrue(executor.runExtensionPhase(requestFor(other, false), sinkB));

        assertEquals(List.of("A动态脚本", "A动态补丁"), labelSequence(sinkA.lines));
        assertEquals(List.of("B条目补丁"), labelSequence(sinkB.lines));
        assertFalse(labelSequence(sinkA.lines).contains("B条目补丁"), "两实例步骤集互不串用");
    }

    // ==================== V-13 代码形状 ====================

    @Test
    @DisplayName("V-13：executor 源码内不出现 instanceof DeployAdapter / 不拼 compose 命令（按 kind() 分派）")
    void noInstanceOfAdapterInSource() throws java.io.IOException {
        java.nio.file.Path source = java.nio.file.Path.of(
                "src/main/java/com/gameplatform/service/deploy/DeployExtensionExecutor.java");
        // 只判代码：注释里出现过「instanceof DeployAdapter」正是本条红线被写进文档的地方
        String code = java.nio.file.Files.readAllLines(source).stream()
                .map(String::trim)
                .filter(line -> !line.startsWith("*") && !line.startsWith("//") && !line.startsWith("/*"))
                .collect(java.util.stream.Collectors.joining("\n"));

        assertFalse(code.contains("instanceof DeployAdapter"), "§14.13.2 红线");
        assertFalse(code.contains("docker compose"), "收尾不得在本类拼命令");
        assertFalse(code.contains("up -d"), "收尾不得在本类拼命令");
        assertTrue(code.contains("switch (step.kind())"), "按 kind() 分派");
    }

    // ==================== 夹具与工具 ====================

    private boolean run() {
        return executor.runExtensionPhase(request(true), sink);
    }

    private Outcome runExpectingFailure() {
        return runExpectingFailure(true);
    }

    private Outcome runExpectingFailure(boolean explicitSelection) {
        try {
            executor.runExtensionPhase(request(explicitSelection), sink);
            return new Outcome(false, sink.lines, stepIds(sink.lines), sink.texts());
        } catch (DeployExtensionExecutor.ExtensionPhaseException e) {
            return new Outcome(true, sink.lines, stepIds(sink.lines), sink.texts());
        }
    }

    private DeployExtensionExecutor.Request request(boolean explicitSelection) {
        return requestFor(instance, explicitSelection);
    }

    private DeployExtensionExecutor.Request requestFor(GameInstance target, boolean explicitSelection) {
        return new DeployExtensionExecutor.Request(target.getId(), HOST_ID, adapter, config, target,
                "DEPLOY", explicitSelection);
    }

    private void givenCatalog(CatalogView view) {
        when(catalogService.read(anyString(), anyString())).thenReturn(view);
    }

    /**
     * 走 ② 支（条目自带步骤）的标准布置：目录可用、动态入口为空、停止判定一次成立、收尾成立。
     * 补丁步骤缺省全部成功，脚本步骤缺省退出码 0。
     */
    private void givenSteps(List<PatchStepDeclaration> patches, List<ScriptStepDeclaration> scripts) {
        givenCatalog(CatalogView.available(List.of(entry(VERSION_ID, patches, scripts))));
        when(pluginFrameworkService.getExtensionByGameCode(GAME_CODE)).thenReturn(extension);
        when(extension.getDeployExtensionSteps(any())).thenReturn(List.of());
        when(adapter.getStatus(anyLong(), any())).thenReturn(DeployAdapter.InstanceStatus.STOPPED);
        when(adapter.ensureRunningForExtension(anyLong(), any())).thenReturn(true);
        givenScriptExit(0);
    }

    private void givenScriptExit(int exitCode) {
        when(scriptRunner.run(any(), anyInt(), anyLong(), anyString()))
                .thenReturn(scriptResult(exitCode, false, 600_000L, 5L));
    }

    /** 阶段能正常收尾（起回成立）：不布置这一条的用例都会在收尾处按致命终止。 */
    private void givenStageCanFinish() {
        when(adapter.getStatus(anyLong(), any())).thenReturn(DeployAdapter.InstanceStatus.STOPPED);
        when(adapter.ensureRunningForExtension(anyLong(), any())).thenReturn(true);
    }

    private static ExtensionScriptRunner.ScriptRunResult scriptResult(Integer exitCode, boolean timedOut,
                                                                     long timeoutMs, long elapsedMs) {
        return new ExtensionScriptRunner.ScriptRunResult(exitCode, timedOut, timeoutMs, elapsedMs,
                new ExtensionScriptRunner.TruncatedOutput("", 0L, false),
                new ExtensionScriptRunner.TruncatedOutput("", 0L, false));
    }

    private static VersionEntry entry(String versionId, List<PatchStepDeclaration> patches,
                                      List<ScriptStepDeclaration> scripts) {
        DeployVersionDeclaration declaration =
                new DeployVersionDeclaration(versionId, versionId, null, false, patches, scripts);
        return new VersionEntry(declaration, versionId, false, List.of());
    }

    private static PatchStepDeclaration patch(String label, boolean fatal) {
        return new PatchStepDeclaration(label, "http://127.0.0.1:8099/patch/stub-version-marker.zip",
                "stub-patch", null, null, null, fatal);
    }

    private static ScriptStepDeclaration script(String label, boolean fatal) {
        return new ScriptStepDeclaration(label, "echo stub", null, null, ScriptPosition.HOST, fatal, null);
    }

    // ---- 机械判据小工具：只读契约字段 ----

    private static List<String> stepIds(List<ExtensionLogLine> rows) {
        return rows.stream().map(ExtensionLogLine::stepId).filter(Objects::nonNull).distinct().toList();
    }

    private static List<String> stepIdSequence(List<ExtensionLogLine> rows) {
        return rows.stream().map(ExtensionLogLine::stepId).filter(Objects::nonNull).toList();
    }

    private static List<Integer> indexes(List<ExtensionLogLine> rows) {
        return rows.stream().map(ExtensionLogLine::stepIndex).filter(Objects::nonNull).distinct().sorted().toList();
    }

    private static List<Integer> totals(List<ExtensionLogLine> rows) {
        return rows.stream().map(ExtensionLogLine::stepTotal).filter(Objects::nonNull).distinct().toList();
    }

    private static List<ExtensionLogLine> rowsOf(List<ExtensionLogLine> rows, String stepId) {
        return rows.stream().filter(r -> stepId.equals(r.stepId())).toList();
    }

    private static int countEvent(List<ExtensionLogLine> rows, String event) {
        return (int) rows.stream().filter(r -> event.equals(r.stepEvent())).count();
    }

    private static ExtensionLogLine terminal(List<ExtensionLogLine> rows) {
        return rows.stream()
                .filter(r -> "SUCCESS".equals(r.stepEvent()) || "FAILURE".equals(r.stepEvent()))
                .filter(r -> r.stepId() != null)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("没有终态行"));
    }

    private static ExtensionLogLine finishRow(List<ExtensionLogLine> rows) {
        return rows.stream().filter(r -> r.message() != null && r.message().startsWith("部署扩展阶段收尾"))
                .findFirst().orElseThrow(() -> new AssertionError("没有收尾行"));
    }

    private static int indexOf(List<ExtensionLogLine> rows, Predicate<ExtensionLogLine> predicate) {
        for (int i = 0; i < rows.size(); i++) {
            if (predicate.test(rows.get(i))) {
                return i;
            }
        }
        throw new AssertionError("没有匹配的行");
    }

    private static String label(List<ExtensionLogLine> rows, String stepId) {
        return rowsOf(rows, stepId).stream().map(ExtensionLogLine::stepLabel).filter(Objects::nonNull)
                .findFirst().orElse(null);
    }

    private static List<String> labelSequence(List<ExtensionLogLine> rows) {
        List<String> labels = new ArrayList<>();
        for (ExtensionLogLine row : rows) {
            if (row.stepId() != null && "START".equals(row.stepEvent())) {
                labels.add(row.stepLabel());
            }
        }
        return labels;
    }

    private static boolean anyStartWith(List<ExtensionLogLine> rows, String prefix) {
        return rows.stream().anyMatch(r -> r.message() != null && r.message().startsWith(prefix));
    }

    /**
     * KPI-02（§14.6 规则 2 的机械形式）：分子 = 满足「恰一 START + 恰一终态且终态 elapsedMs 非空」的步骤数，
     * 分母 = 非空 stepId 的去重计数（阶段级行一律不进分母）。
     */
    private static double kpi02Ratio(List<ExtensionLogLine> rows) {
        List<String> steps = stepIds(rows);
        if (steps.isEmpty()) {
            return 1.0;
        }
        long satisfied = steps.stream().filter(stepId -> {
            List<ExtensionLogLine> group = rowsOf(rows, stepId);
            if (countEvent(group, "START") != 1) {
                return false;
            }
            if (countEvent(group, "SUCCESS") + countEvent(group, "FAILURE") != 1) {
                return false;
            }
            return terminal(group).elapsedMs() != null;
        }).count();
        return (double) satisfied / steps.size();
    }

    /** 记录型接缝实现：产行与顶层进度都收进列表，判据只读列表里的契约字段。 */
    private static final class RecordingSink implements ExtensionStageSink {
        private final List<ExtensionLogLine> lines = new ArrayList<>();
        private final List<Integer> progress = new ArrayList<>();

        @Override
        public void append(ExtensionLogLine line) {
            lines.add(line);
        }

        @Override
        public void reportProgress(int value) {
            progress.add(value);
        }

        private List<String> texts() {
            return lines.stream().map(ExtensionLogLine::message).toList();
        }
    }

    private record Outcome(boolean failed, List<ExtensionLogLine> rows, List<String> stepIds, List<String> texts) {
    }
}

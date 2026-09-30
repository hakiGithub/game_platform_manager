package com.gameplatform.service.deploy;

import com.gameplatform.adapter.DeployAdapter;
import com.gameplatform.deploy.CatalogState;
import com.gameplatform.deploy.CatalogView;
import com.gameplatform.deploy.DeployVersionCatalogService;
import com.gameplatform.deploy.ExtensionScriptRunner;
import com.gameplatform.deploy.VersionEntry;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.plugin.extension.GameEnhancementExtension;
import com.gameplatform.plugin.extension.deploy.DeployExtensionContext;
import com.gameplatform.plugin.extension.deploy.DeployExtensionStepDeclaration;
import com.gameplatform.plugin.extension.deploy.PatchStepDeclaration;
import com.gameplatform.plugin.extension.deploy.ScriptStepDeclaration;
import com.gameplatform.plugin.extension.deploy.StepKind;
import com.gameplatform.plugin.patch.PatchInstallProgressListener;
import com.gameplatform.plugin.patch.PatchInstallRequest;
import com.gameplatform.plugin.patch.PatchInstallService;
import com.gameplatform.plugin.service.PluginFrameworkService;
import com.gameplatform.service.DeployService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 部署扩展阶段的执行管线（design.md §14.6 五条规则、§16.2 解析顺序、§14.5 停止语义、
 * §14.12 / §14.13.2 / §14.13.3 收尾、§16.3 + PRD BR-12 拦截；对应实现步骤 B-09）。
 *
 * <p>本类是「能力面（MERC-18/20 交付的目录读取、同步补丁入口、脚本安全形状、两类起回）」
 * 与「产品部署主干」之间的接线层：行为多（四态判定、停止语义、逐步阻塞、回滚记录位、
 * 截断与耗时承载）、接口小（一个 {@link #runExtensionPhase} + 一个回传接缝
 * {@link ExtensionStageSink}）。既有阶段一行都不经这里。</p>
 *
 * <p><b>分派形状</b>（§16.2 / V-13）：步骤集元素类型是 sealed 上界
 * {@link DeployExtensionStepDeclaration}，按 {@code kind()} 分派。本类
 * <b>不得</b>出现 {@code instanceof DeployAdapter} —— 「起回」的调用面是
 * {@link DeployAdapter#ensureRunningForExtension}，适配器由主流程解析后交进来
 * （§14.13.2「禁止在 executor 里 instanceof 分派或自行拼 compose 命令」）。</p>
 *
 * <p><b>词面</b>：OP-05 未关闭 ⇒ 用户可见文案一律取 ui-spec.md @ {@code 62b49a9}
 * §6.2／§6.3／§6.4 与 design §14.6／§8.3 已登记的词面（见 {@link Words}），
 * 登记里没有的形态不在这里造词——已登记的槽位（{@code 〈原因〉}）填宿主侧技术归因，
 * 未登记的行形制作为缺口回贴，见本票回传的缺口清单。{@code message} 是给人读的，
 * 不是核对对象（§14.6 承载位分工表）。</p>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeployExtensionExecutor {

    /** 扩展阶段的阶段常量（§14.6 {@code stage} 行）。 */
    public static final String EXTENSION_STAGE = "EXTENSION";

    /** §14.5 停止判定：3 次 × 2 s，判定对象是 {@code DeployAdapter.getStatus(instanceId, config)} 非 RUNNING。 */
    static final int STOP_POLL_ATTEMPTS = 3;
    static final long STOP_POLL_INTERVAL_MS = 2_000L;

    /**
     * 停止判定的轮询间隔。非 {@code final} 是测试接缝（同 {@code PatchInstallServiceImpl#mutexPollIntervalMs}）：
     * 单测按「判定次数 = 3」核对 §14.5，不需要真等 6 s；生产值恒为 2 s。
     */
    long stopPollIntervalMs = STOP_POLL_INTERVAL_MS;

    /** §14.7 条件分配：有扩展步骤时扩展段占 {@code [80, 84]}。 */
    static final int PROGRESS_FLOOR = 80;
    static final int PROGRESS_SPAN = 4;
    static final int PROGRESS_CEILING = 84;

    /** §8.1 阶段预算里停实例那一档的观察值，只进 {@code 〈原因〉} 槽位的技术归因。 */
    private static final String STOP_STILL_RUNNING = "3 次 × 2 秒判定后实例状态仍为 RUNNING";

    private final DeployVersionCatalogService catalogService;
    private final PluginFrameworkService pluginFrameworkService;
    private final PatchInstallService patchInstallService;
    private final ExtensionScriptRunner scriptRunner;

    /**
     * 扩展阶段的输入。由 {@code DeployService} 在 {@code DEPLOY} 完成行之后组装
     * （§14.13.2：用<b>已解析的</b> adapter，本类不自行解析、不按类型分派）。
     *
     * @param instance            <b>DEPLOY 完成后</b>从库中重读的实例行：{@code configInfo} 是 BR-12
     *                            判定的输入，{@code runtimeMetadata.workDir} 是脚本落位目录（两类适配器
     *                            在 {@code DEPLOY} 末回写，§14.13.3「收尾不重做 DEPLOY 末的回写」同源）
     * @param currentStage        本次调用时主流程实际所在的既有阶段名，决定「目录不合法说明行」的
     *                            {@code stage} 取值（§14.6 v0.3.5 拆分裁定：钉的是判据，不是字面量）
     * @param explicitVersionSelection 本次版本值是<b>向导本次所选</b>还是<b>实例配置既存键</b>——
     *                            BR-12 两支词面的区分锚点（ui-spec §6.3）
     */
    public record Request(Long instanceId,
                          Long hostId,
                          DeployAdapter adapter,
                          Map<String, Object> config,
                          GameInstance instance,
                          String currentStage,
                          boolean explicitVersionSelection) {
    }

    /**
     * 扩展阶段自己已经产过该阶段的终态行 ⇒ 主流程收尾时不得再补行。
     *
     * <p>存在的理由是一条代码事实：{@code DeployService} 的两个 catch 分支各追加一条
     * {@code ERROR} 行（{@code appendLog} 与 {@code notifyError}），其 {@code stage} 取顶层
     * {@code DeployTaskStatus.stage} —— 扩展阶段期间那正是 {@code "EXTENSION"}。不挡住的话，
     * 「收尾失败行必为该阶段末行」（ui-spec §7 X-07 锚点二 B）会被两条平台行破掉。
     * 异常本身照旧向主流程传递失败语义（{@code error}/{@code completed}/{@code success}/实例
     * {@code ERROR} 一律不变），只是不再重复产行。</p>
     */
    public static class ExtensionPhaseException extends DeployService.DeployException {

        public ExtensionPhaseException(String message) {
            super(message);
        }
    }

    /**
     * 在 {@code DEPLOY} 完成行与 {@code HEALTH_CHECK} 之间执行扩展阶段。
     *
     * @return 本次部署是否<b>执行了</b>扩展步骤——{@code true} 时主流程按 §14.7 把
     *         {@code HEALTH_CHECK} 的顶层 {@code progress} 报 85；{@code false} 时该分支
     *         一个字都不动（AC-15 / KPI-04 的回归前提）
     * @throws ExtensionPhaseException 致命失败（停不下来 / 致命步骤失败 / 收尾失败 / BR-12 拦截）
     * @throws DeployService.DeployException 脚本无处落位（{@code workDir} 为空）
     */
    public boolean runExtensionPhase(Request request, ExtensionStageSink sink) {
        GameInstance instance = request.instance();
        if (instance == null) {
            // 库中查不到这一行 ⇒ 版本载体与 workDir 都无从取得，本段整体不存在。
            // 主流程后续的适配器调用会按既有语义暴露「实例不存在」，这里不替它造失败。
            return false;
        }

        CatalogView catalog = catalogService.read(instance.getGameCode(), instance.getDeployType());
        String selectedVersionId = selectedVersionId(instance);

        if (selectedVersionId == null) {
            // 默认版本目标（PRD §8.4.2 S1/S2）：不进入扩展阶段。目录不合法时只补一条说明行，
            // 该行归它实际发生所在的既有阶段（§14.6 ②，本期即 DEPLOY），不归 EXTENSION。
            if (catalog.state() == CatalogState.INVALID) {
                sink.append(Words.catalogInvalidNote(catalog.invalidReason(), request.currentStage()));
            }
            return false;
        }

        VersionEntry entry = catalog.findEntry(selectedVersionId).orElse(null);
        if (catalog.state() != CatalogState.AVAILABLE || entry == null) {
            interceptAndFail(request, sink, selectedVersionId);
        }

        List<DeployExtensionStepDeclaration> steps = resolveSteps(instance, selectedVersionId, entry);
        if (steps.isEmpty()) {
            // §16.2 解析顺序 ③：两路都空 ⇒ 本段整体不存在，序列与改造前逐字相同（零行）。
            return false;
        }
        requireScriptWorkDir(instance, steps);

        long phaseStartedAt = System.currentTimeMillis();
        sink.reportProgress(PROGRESS_FLOOR);
        sink.append(Words.enterStage());
        ensureStoppedForExtension(request, sink);

        for (int i = 0; i < steps.size(); i++) {
            DeployExtensionStepDeclaration step = steps.get(i);
            executeStep(step, new ExtensionLogLine.StepIdentity("E-" + (i + 1), i + 1, steps.size(),
                    step.label(), step.kind().name()), request, sink);
            sink.reportProgress(progressAfterStep(i + 1, steps.size()));
        }

        finishAndHandOff(request, sink, steps.size(), phaseStartedAt);
        return true;
    }

    // ==================== 步骤集解析（§16.2 唯一顺序） ====================

    /**
     * ① {@code getDeployExtensionSteps(ctx)} 非空 → 用它；② 否则取所选目录条目的
     * {@code patches ++ scripts}（声明序）；③ 都空由调用方判「不进入扩展阶段」。
     *
     * <p>两条路汇入同一个消费者 ⇒ AC-18「两实例步骤集互不串用」在 ① ② 上同一套判定（V-24）。</p>
     */
    private List<DeployExtensionStepDeclaration> resolveSteps(GameInstance instance,
                                                             String selectedVersionId,
                                                             VersionEntry entry) {
        GameEnhancementExtension extension =
                pluginFrameworkService.getExtensionByGameCode(instance.getGameCode());
        if (extension != null) {
            DeployExtensionContext context = new DeployExtensionContext(instance.getId(),
                    instance.getGameCode(), instance.getDeployType(), selectedVersionId,
                    instance.getConfigInfo() == null ? Map.of() : Map.copyOf(instance.getConfigInfo()));
            try {
                List<DeployExtensionStepDeclaration> dynamic = extension.getDeployExtensionSteps(context);
                if (dynamic != null && !dynamic.isEmpty()) {
                    return List.copyOf(dynamic);
                }
            } catch (Exception e) {
                // 插件侧数据不可信：SPI 抛异常不外泄成部署侧的 500（与 §16.3 目录读取同一处置口径），
                // 按「① 支未给出步骤集」继续走 ② 支。
                log.warn("插件 [{}] 的动态步骤集入口异常，按「无动态步骤」继续: instanceId={}, cause={}",
                        instance.getGameCode(), instance.getId(), e.toString());
            }
        }
        // ② 支：条目自带步骤。拼接复用目录服务的同一个实现（显式类型见证必需，§16.2 推导规则）。
        return DeployVersionCatalogService.stepsOf(entry.declaration());
    }

    /**
     * 脚本步骤无处落位 ⇒ 在<b>进入阶段之前</b>挡下。
     *
     * <p>{@code workDir} 取自 {@code runtimeMetadata}（两类适配器在 {@code DEPLOY} 末回写）；
     * 空串会把临时脚本写成 {@code /.platform-extension/E-<n>.sh}（宿主机根目录，越出实例边界）。
     * 这条判定属接线处的输入校验，不是新造闸门：既然必定写错位置，就不该先产进入行再失败。</p>
     */
    private void requireScriptWorkDir(GameInstance instance, List<DeployExtensionStepDeclaration> steps) {
        boolean hasScriptStep = steps.stream().anyMatch(s -> s.kind() == StepKind.SCRIPT);
        if (!hasScriptStep) {
            return;
        }
        if (scriptWorkDir(instance) == null) {
            throw new DeployService.DeployException(
                    "扩展步骤的宿主机工作目录未记录（runtimeMetadata.workDir 为空），脚本步骤无处落位，部署终止");
        }
    }

    // ==================== 停实例（§14.5） ====================

    /**
     * 进入行之后、任何步骤之前执行。判定只认 {@code getStatus} 非 RUNNING（3 次 × 2 s），
     * <b>不</b>看 {@code stop()} 的返回值——设计把「已停止」的可观察定义钉在轮询上。
     *
     * <p>期间不回写实例状态：本方法只调适配器，PRD §9 要求扩展阶段期间 {@code run_status}
     * 恒为 {@code INSTALLING(5)}（§5.5）。现有 {@code DeployService.stop()} 会写 {@code STOPPED}，
     * 故不复用它。</p>
     */
    private void ensureStoppedForExtension(Request request, ExtensionStageSink sink) {
        sink.append(Words.stopBefore());
        Long instanceId = request.instanceId();
        Map<String, Object> config = request.config();
        try {
            request.adapter().stop(instanceId, config);
        } catch (Exception e) {
            // 停不下来即致命：在未确认停止的实例上替换文件正是决策 3 要消除的中间态（§14.5）。
            failToStop(sink, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        String lastSeen = null;
        for (int attempt = 1; attempt <= STOP_POLL_ATTEMPTS; attempt++) {
            if (!sleepQuietly(stopPollIntervalMs)) {
                failToStop(sink, "等待停止判定的间隔被中断");
            }
            lastSeen = observeStatus(request.adapter(), instanceId, config);
            if (!DeployAdapter.InstanceStatus.RUNNING.name().equals(lastSeen)) {
                sink.append(Words.stopDone());
                return;
            }
        }
        failToStop(sink, STOP_STILL_RUNNING);
    }

    /** 观察实例状态名；探测异常按「未确认停止」处置，继续下一次判定。 */
    private String observeStatus(DeployAdapter adapter, Long instanceId, Map<String, Object> config) {
        try {
            DeployAdapter.InstanceStatus status = adapter.getStatus(instanceId, config);
            return status == null ? null : status.name();
        } catch (Exception e) {
            log.warn("扩展阶段停止判定探测异常，继续下一次判定: instanceId={}, cause={}", instanceId, e.toString());
            return null;
        }
    }

    private void failToStop(ExtensionStageSink sink, String cause) {
        sink.append(Words.stopFailed(cause));
        throw new ExtensionPhaseException(Words.stopFailed(cause).message());
    }

    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ==================== 单步执行（§14.6 五条规则 / §14.6 规则 4、5） ====================

    private void executeStep(DeployExtensionStepDeclaration step, ExtensionLogLine.StepIdentity identity,
                             Request request, ExtensionStageSink sink) {
        sink.append(Words.stepStart(identity));
        long startedAt = System.currentTimeMillis();
        StepResult result = switch (step.kind()) {
            case PATCH -> runPatchStep((PatchStepDeclaration) step, identity, request, startedAt);
            case SCRIPT -> runScriptStep((ScriptStepDeclaration) step, identity, request, startedAt);
        };

        result.noteRows().forEach(sink::append);
        sink.append(result.succeeded()
                ? Words.stepSuccess(identity, result.elapsedMs(), result.exitCode())
                : Words.stepFailure(identity,
                        step.fatal() ? ExtensionLogLine.Level.ERROR : ExtensionLogLine.Level.WARN,
                        result.reason(), result.elapsedMs(), result.exitCode()));
        if (result.rollbackRow() != null) {
            // 规则 5：该 stepId 的终态 FAILURE ⇒ 终态行之后、下一步 START 之前恰有一行 ROLLBACK。
            sink.append(result.rollbackRow());
        }
        if (result.succeeded()) {
            return;
        }
        if (step.fatal()) {
            sink.append(Words.fatalHalt());
            throw new ExtensionPhaseException(Words.dialogFatalStep(identity));
        }
        sink.append(Words.nonFatalContinue());
    }

    private StepResult runPatchStep(PatchStepDeclaration step, ExtensionLogLine.StepIdentity identity,
                                    Request request, long startedAt) {
        AtomicReference<String> rollbackSignal = new AtomicReference<>();
        PatchInstallProgressListener listener = new PatchInstallProgressListener() {
            @Override
            public void onProgress(int percent, String message) {
                // 补丁执行器的进度分桶是它自己的口径，扩展阶段每步的行形制由 §14.6 定死，不在此转写
            }

            @Override
            public void onLog(String message) {
                if (message == null) {
                    return;
                }
                if (Words.ROLLBACK_DONE.equals(message) || message.startsWith(Words.ROLLBACK_FAILED_PREFIX)) {
                    rollbackSignal.set(message);
                }
            }

            @Override
            public boolean isCancelled() {
                // 部署主流程不在任务中心内，扩展阶段没有取消入口（design.md §14.1）
                return false;
            }
        };

        // 请求对象引用透传给同步入口，includePattern 不经 payload 序列化往返（§14.2 / V-07 落位半）。
        // headers 不在声明模型内（PRD §8.2 硬约束），因此这里恒为空。
        PatchInstallRequest installRequest = PatchInstallRequest.builder()
                .instanceId(request.instanceId())
                .url(step.url())
                .targetPath(step.targetPath())
                .sha256(step.sha256())
                .includePattern(step.includePattern())
                .format(step.format())
                .build();

        String failure = null;
        try {
            patchInstallService.installSync(installRequest, listener);
        } catch (Exception e) {
            failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        long elapsedMs = System.currentTimeMillis() - startedAt;
        if (failure == null) {
            return StepResult.success(elapsedMs, null, List.of(), null);
        }
        // PATCH 步骤恒不回报退出码（§14.6 exitCode 行：不为此改 PatchInstallExecutor）
        return StepResult.failure(elapsedMs, null, List.of(failure),
                Words.rollbackRow(identity, rollbackSignal.get()));
    }

    private StepResult runScriptStep(ScriptStepDeclaration step, ExtensionLogLine.StepIdentity identity,
                                     Request request, long startedAt) {
        List<ExtensionLogLine> noteRows = new ArrayList<>();
        long elapsedMs;
        try {
            ExtensionScriptRunner.ScriptRunResult result =
                    scriptRunner.run(step, identity.index(), request.hostId(), scriptWorkDir(request.instance()));
            elapsedMs = System.currentTimeMillis() - startedAt;
            collectOutput(identity, result.stdout(), noteRows);
            collectOutput(identity, result.stderr(), noteRows);
            if (result.succeeded()) {
                return StepResult.success(elapsedMs, result.exitCode(), noteRows, null);
            }
            if (result.timedOut()) {
                // 规则 4：超时的 exitCode 为 null 且原因段指名超时，与非零退出可靠区分。
                // §15.3 的诚实限制随行：不得给运维「已终止」的错觉。
                return StepResult.failure(elapsedMs, null,
                        List.of(Words.scriptTimeoutReason(result.timeoutMs())), noteRows, null);
            }
            return StepResult.failure(elapsedMs, result.exitCode(),
                    List.of(Words.scriptExitReason(result.exitCode())), noteRows, null);
        } catch (ExtensionScriptRunner.ScriptPreconditionException e) {
            // 脚本没能开始执行（下载失败 / 摘要不符 / 源缺失 / 非 http(s)）：宿主机上不留文件（B-13）。
            elapsedMs = System.currentTimeMillis() - startedAt;
            String technical = e.getMessage() == null ? e.getPrecondition().name() : e.getMessage();
            return StepResult.failure(elapsedMs, null, List.of(technical), noteRows, null);
        }
    }

    /** §8.3：单步 stdout / stderr 各截断至 4000 字符，截断发生时补一条 {@code NOTE}「输出已截断，共 N 字节」。 */
    private void collectOutput(ExtensionLogLine.StepIdentity identity,
                               ExtensionScriptRunner.TruncatedOutput output,
                               List<ExtensionLogLine> noteRows) {
        if (output == null) {
            return;
        }
        if (output.text() != null && !output.text().isEmpty()) {
            noteRows.add(Words.stepNote(identity, output.text()));
        }
        String truncation = output.truncationNote();
        if (truncation != null) {
            noteRows.add(Words.stepNote(identity, truncation));
        }
    }

    // ==================== 收尾与交棒（§14.12 / §14.13.2） ====================

    /**
     * 全部步骤判定完成后把容器起回，两支各产一条独立收尾行（§14.12 v0.3.2 定死）。
     *
     * <p>收尾失败按致命处置：{@code FAILURE} + {@code ERROR} 后<b>不接</b>阶段完成行、交棒行，
     * 也不进 {@code HEALTH_CHECK}——「起不回去」不能让健康检查去替它失败（§14.13.3 失败处置行）。</p>
     */
    private void finishAndHandOff(Request request, ExtensionStageSink sink, int total, long phaseStartedAt) {
        long finishStartedAt = System.currentTimeMillis();
        String failure = null;
        try {
            if (!request.adapter().ensureRunningForExtension(request.instanceId(), request.config())) {
                failure = "起回后未确认容器处于运行态";
            }
        } catch (Exception e) {
            failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        long finishElapsedMs = System.currentTimeMillis() - finishStartedAt;
        if (failure != null) {
            sink.append(Words.finishFailed(finishElapsedMs, failure));
            throw new ExtensionPhaseException(Words.dialogFinishFailed());
        }
        sink.append(Words.finishSuccess(finishElapsedMs));
        sink.append(Words.stageComplete(total, System.currentTimeMillis() - phaseStartedAt));
        sink.append(Words.handoff());
    }

    // ==================== BR-12（§16.3 四态 + PRD BR-12） ====================

    /**
     * 版本声明不可用即拦截：产进入行 + 拦截行（G2 形态另加恢复路径行）后判失败。
     *
     * <p>该次部署的阶段以「入口判定不合法」短暂成立（§3.2 {@code :81}）：其后无步骤行、
     * 无收尾 / 阶段完成 / 交棒行。拦截行的 {@code stage} 取 {@code "EXTENSION"}（§14.6 ① 钉值），
     * 阶段带 latch 因此成立。绝不静默按默认版本交付。</p>
     */
    private void interceptAndFail(Request request, ExtensionStageSink sink, String selectedVersionId) {
        sink.reportProgress(PROGRESS_FLOOR);
        sink.append(Words.enterStage());
        List<ExtensionLogLine> lines = Words.br12Interception(selectedVersionId, request.explicitVersionSelection());
        lines.forEach(sink::append);
        throw new ExtensionPhaseException(lines.get(0).message());
    }

    // ==================== 取值与算术 ====================

    /** 版本选择的唯一载体 = {@code configInfo.deployVersion}（PRD §8.4）；非字符串或空白一律按「未选择」处置。 */
    private static String selectedVersionId(GameInstance instance) {
        Map<String, Object> configInfo = instance.getConfigInfo();
        if (configInfo == null) {
            return null;
        }
        Object value = configInfo.get(DeployVersionCatalogService.VERSION_KEY);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /** 脚本临时目录的宿主基址：{@code runtimeMetadata.workDir}（DEPLOY 末回写），空/缺失返回 {@code null}。 */
    private static String scriptWorkDir(GameInstance instance) {
        Map<String, Object> metadata = instance.getRuntimeMetadata();
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get("workDir");
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /** §14.7：起点报 80，其后 {@code 80 + floor(4 × i / total)}，上限 84；单调不减。 */
    static int progressAfterStep(int done, int total) {
        if (total <= 0) {
            return PROGRESS_FLOOR;
        }
        return Math.min(PROGRESS_CEILING, PROGRESS_FLOOR + (int) Math.floor((double) PROGRESS_SPAN * done / total));
    }

    /**
     * 一步的判定结果（内部形状）。
     *
     * @param noteRows      先于终态行落地的 {@code NOTE} 行（stdout / stderr 及其截断说明）。
     *                      排在终态行之前的理由是 ui-spec §7 X-07 锚点二 A：收尾成功行的前一行
     *                      必须是最后一步的<b>终态</b>行，输出行若排在后面会把那条机械判据判破。
     * @param reasonSegments {@code FAILURE} 行的原因段本体（引导词「原因：」归界面，跨票口径）
     * @param rollbackRow   {@code PATCH} 步骤失败时的回滚记录位（规则 5）；其余为 {@code null}
     */
    private record StepResult(boolean succeeded, long elapsedMs, Integer exitCode,
                              List<ExtensionLogLine> noteRows, List<String> reasonSegments,
                              ExtensionLogLine rollbackRow) {

        static StepResult success(long elapsedMs, Integer exitCode, List<ExtensionLogLine> noteRows,
                                  ExtensionLogLine rollbackRow) {
            return new StepResult(true, elapsedMs, exitCode, noteRows, List.of(), rollbackRow);
        }

        static StepResult failure(long elapsedMs, Integer exitCode, List<String> reasonSegments,
                                  List<ExtensionLogLine> noteRows, ExtensionLogLine rollbackRow) {
            return new StepResult(false, elapsedMs, exitCode, noteRows, reasonSegments, rollbackRow);
        }

        static StepResult failure(long elapsedMs, Integer exitCode, List<String> reasonSegments,
                                  ExtensionLogLine rollbackRow) {
            return new StepResult(false, elapsedMs, exitCode, List.of(), reasonSegments, rollbackRow);
        }

        /** 多个原因段（如 stdout 与 stderr 同行缺失）时以「；」相连，仍不含引导词。 */
        String reason() {
            return String.join("；", reasonSegments);
        }
    }
}

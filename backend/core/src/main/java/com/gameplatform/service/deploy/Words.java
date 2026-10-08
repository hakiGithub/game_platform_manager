package com.gameplatform.service.deploy;

import java.util.List;

/**
 * 扩展阶段日志行的词面册（本类只做两件事：取已登记词面填槽、按字段形状产行）。
 *
 * <p><b>为什么不新造词</b>：PRD §16.2 的 OP-05 未关闭，用户可见文案的合法来源只有
 * ui-spec.md @ {@code 62b49a9} 的 §6.2（行序列与阶段级/步骤级词面）、§6.3（原因段与
 * BR-12 两支、对话框错误条）、§6.4（不出现在界面的字面量）与 design.md §14.6（{@code ROLLBACK}
 * 记录位的转写词面）、§8.3（截断说明行）。登记里没有的行形制（如 {@code stdout}/{@code stderr}
 * 行的通道标识）在本类里一律不造，只把宿主侧技术归因填进<b>已登记的槽位</b>，缺口回贴。</p>
 *
 * <p><b>{@code message} 的分工</b>（design.md §14.6 末段 + 跨票口径，按消费面分工而非单向不对称）：
 * 它是给人读的，不是核对对象。渲染整句由界面拼（MERC-19 的 {@code DeployProgress.vue} 对
 * START / SUCCESS / FAILURE 一律按字段拼装），服务端只给界面消费的那一段：</p>
 * <ul>
 *   <li>步骤级 {@code FAILURE} 行——<b>致命与非致命同形</b>——只写<b>原因段本体</b>：界面读
 *       {@code message} 当原因段（剥前导「原因：」后自己补上），写整句会渲染成重复内容。
 *       「失败（非致命）」不是服务端的文案分支，由界面按 {@code level == WARN} 渲染；</li>
 *   <li>步骤级 {@code SUCCESS} 行界面不消费 {@code message}，服务端按 §6.2 整句词面直出（design
 *       §14.6 的成功行例子正是这一支）；</li>
 *   <li>阶段级行（{@code stepId == null}）不受此限，按 §6.2 整句词面直出。</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
final class Words {

    // ---- ui-spec §6.1：种类词面（§6.4 明令界面不得出现 PATCH / SCRIPT 字面量） ----
    private static final String KIND_PATCH = "补丁替换";
    private static final String KIND_SCRIPT = "脚本执行";

    // ---- ui-spec §6.2：阶段级行（stepId == null）整句词面 ----
    private static final String ENTER_STAGE = "进入部署扩展阶段";
    private static final String STOP_BEFORE = "正在停止实例，确保扩展步骤在实例未运行时执行";
    private static final String STOP_DONE = "实例已停止";
    private static final String STOP_FAILED = "实例停止失败：%s —— 扩展步骤不得在实例仍运行时执行，部署终止";
    private static final String FINISH_SUCCESS = "部署扩展阶段收尾 · 容器已恢复到运行态 · 成功 · 耗时 %s";
    private static final String FINISH_FAILED = "部署扩展阶段收尾 · 容器未能恢复到运行态 · 失败 · 原因：%s";
    private static final String STAGE_COMPLETE = "部署扩展阶段完成 · 共 %d 步 · 总耗时 %s";
    private static final String HANDOFF = "部署扩展阶段结束，进入健康检查与启动";

    // ---- ui-spec §6.2：步骤行整句词面（阶段级行直出 message，故这里给全句） ----
    private static final String STEP_START = "步骤 %d/%d %s · %s 开始";
    private static final String STEP_SUCCESS = "步骤 %d/%d %s · %s · 成功 · 耗时 %s";
    private static final String FATAL_HALT = "致命步骤失败，部署终止：不再执行后续步骤，实例不启动、不交付，状态置为异常";
    private static final String NON_FATAL_CONTINUE = "该步骤声明为非致命，继续执行后续步骤";
    /** ui-spec §6.2 规则 2：{@code label} 缺省时渲染侧回退为「〈种类〉 〈序号〉」。 */
    private static final String LABEL_FALLBACK = "%s %d";

    // ---- design.md §14.6 规则 5：回滚记录位的转写词面（不改 PatchInstallExecutor，只转写它的回报） ----
    static final String ROLLBACK_DONE = "已回滚备份";
    static final String ROLLBACK_FAILED_PREFIX = "回滚失败: ";
    private static final String ROLLBACK_NONE = "无备份可回滚";

    // ---- design.md §8.3：输出截断说明行 ----
    private static final String TRUNCATION_NOTE = "输出已截断，共 %d 字节";

    // ---- ui-spec §6.3：原因段与 BR-12 两支、对话框错误条 ----
    private static final String REASON_SCRIPT_EXIT = "脚本退出码 %d（非 0 即判失败）";
    private static final String REASON_SCRIPT_TIMEOUT = "脚本执行超过 %d 未返回，判失败";
    /**
     * ui-spec §6.3「摘要不符」行的槽位形状（登记在补丁包那一行）。SCRIPT 侧的同一形态没有登记行
     * （缺口 #2 维持登记），这里只借用它把 {@code ScriptPreconditionException} 的结构化期望 / 实际
     * 带进原因段，不另造句式。
     */
    private static final String REASON_EXPECTED_ACTUAL = "（期望 %s，实际 %s）";
    /** design.md §15.3：超时行必须随行写明「不再等待」不等于「远端已终止」。 */
    private static final String TIMEOUT_NOT_KILLED = "脚本可能仍在宿主机后台继续执行";
    private static final String CATALOG_INVALID_NOTE =
            "提示：该游戏版本目录的声明不合法（%s），已按默认版本部署，未执行部署扩展步骤";
    private static final String BR12_CHOSEN =
            "部署终止：本次所选版本 %s 不可用 —— 该游戏的版本目录当前不可读取或未通过声明校验";
    private static final String BR12_CHOSEN_EXTRA =
            "实例未启动、未交付，状态置为异常；该版本要求不会被静默改为默认版本";
    private static final String BR12_FROM_CONFIG =
            "部署终止：实例配置要求的版本 %s 不可用 —— 该版本要求由既往部署写入实例配置，本次未改选；"
                    + "而该游戏的版本目录当前不可读取或未通过声明校验";
    private static final String BR12_RECOVERY =
            "恢复路径：先恢复该游戏的版本目录（重新启用插件，或由插件维护者修复声明）后重新部署；"
                    + "目录恢复后如不再需要该版本，请在「选择游戏」步骤改选「默认版本」再部署（目录未恢复时该选项不存在）";
    private static final String DIALOG_FATAL_STEP =
            "部署扩展阶段的致命步骤失败：%s · %s。实例状态已置为异常，未启动。";
    private static final String DIALOG_FINISH_FAILED =
            "部署扩展阶段收尾失败：容器未能恢复到运行态。实例状态已置为异常，未启动。";

    private Words() {
    }

    // ==================== 阶段级行 ====================

    static ExtensionLogLine enterStage() {
        return stage(ExtensionLogLine.Level.INFO, ENTER_STAGE);
    }

    static ExtensionLogLine stopBefore() {
        return stage(ExtensionLogLine.Level.INFO, STOP_BEFORE);
    }

    static ExtensionLogLine stopDone() {
        return stage(ExtensionLogLine.Level.SUCCESS, STOP_DONE);
    }

    static ExtensionLogLine stopFailed(String cause) {
        return stage(ExtensionLogLine.Level.ERROR, String.format(STOP_FAILED, cause));
    }

    static ExtensionLogLine handoff() {
        return stage(ExtensionLogLine.Level.INFO, HANDOFF);
    }

    static ExtensionLogLine fatalHalt() {
        return stage(ExtensionLogLine.Level.ERROR, FATAL_HALT);
    }

    static ExtensionLogLine nonFatalContinue() {
        return stage(ExtensionLogLine.Level.INFO, NON_FATAL_CONTINUE);
    }

    /** 收尾成功支：{@code SUCCESS} 行承载 §8.1 的收尾耗时预算（该段耗时，不是整阶段耗时）。 */
    static ExtensionLogLine finishSuccess(long elapsedMs) {
        return ExtensionLogLine.stageEventLine(ExtensionLogLine.Level.SUCCESS.value(),
                String.format(FINISH_SUCCESS, formatDuration(elapsedMs)),
                DeployExtensionExecutor.EXTENSION_STAGE, ExtensionLogLine.Event.SUCCESS.value(), elapsedMs);
    }

    /** 收尾失败支：致命，其后不接阶段完成行、交棒行、{@code HEALTH_CHECK} 行。 */
    static ExtensionLogLine finishFailed(long elapsedMs, String cause) {
        return ExtensionLogLine.stageEventLine(ExtensionLogLine.Level.ERROR.value(),
                String.format(FINISH_FAILED, cause), DeployExtensionExecutor.EXTENSION_STAGE,
                ExtensionLogLine.Event.FAILURE.value(), elapsedMs);
    }

    /** 阶段完成行：{@code elapsedMs} 是「进入行 → 完成行」的整段（ui-spec §6.2 规则 1 要点 ④）。 */
    static ExtensionLogLine stageComplete(int total, long elapsedMs) {
        return ExtensionLogLine.stageEventLine(ExtensionLogLine.Level.SUCCESS.value(),
                String.format(STAGE_COMPLETE, total, formatDuration(elapsedMs)),
                DeployExtensionExecutor.EXTENSION_STAGE, ExtensionLogLine.Event.SUCCESS.value(), elapsedMs);
    }

    /**
     * 目录不合法说明行（§8.1 无键 → 默认版本那一路）。
     *
     * <p>{@code stage} 取<b>它实际发生所在的既有阶段</b>，不是 {@code "EXTENSION"}——无键即不拦截、
     * 步骤集为空 ⇒ 扩展段整体不存在，本行承在已成立的 {@code DEPLOY} 上（design.md §14.6 ②，
     * v0.3.5 按 Leader 裁定 {@code 01a0c4d8} 拆分钉值）。</p>
     */
    static ExtensionLogLine catalogInvalidNote(String invalidReason, String stageWhereItHappened) {
        return ExtensionLogLine.stageLine(ExtensionLogLine.Level.WARN.value(),
                String.format(CATALOG_INVALID_NOTE, invalidReason), stageWhereItHappened);
    }

    // ==================== BR-12 两支 ====================

    /**
     * BR-12 拦截行。两支的区分锚点是「本次所选」vs「实例配置要求的…由既往部署写入」，
     * 且恢复路径行只在既存键那一支出现（ui-spec §6.3）。
     *
     * <p>既存键那一支是 <b>3 行</b>不是 2 行：§6.3 分支 ② 的追加行原文写作「<b>上一行</b> +
     * {@code 恢复路径：…}」，「上一行」= ① 的追加行（未启动、未交付那句），它承载 BR-12
     * 「不得静默按默认版本交付」的承诺；实物屏 LF 也数过一遍 =「两条 ERROR + 一条恢复路径 WARN」。
     * 恢复路径取 {@code WARN}：它是出路，不是第二条失败。</p>
     */
    static List<ExtensionLogLine> br12Interception(String versionId, boolean chosenThisSubmission) {
        if (chosenThisSubmission) {
            return List.of(stage(ExtensionLogLine.Level.ERROR, String.format(BR12_CHOSEN, versionId)),
                    stage(ExtensionLogLine.Level.ERROR, BR12_CHOSEN_EXTRA));
        }
        return List.of(stage(ExtensionLogLine.Level.ERROR, String.format(BR12_FROM_CONFIG, versionId)),
                stage(ExtensionLogLine.Level.ERROR, BR12_CHOSEN_EXTRA),
                stage(ExtensionLogLine.Level.WARN, BR12_RECOVERY));
    }

    // ==================== 步骤行 ====================

    static ExtensionLogLine stepStart(ExtensionLogLine.StepIdentity identity) {
        return ExtensionLogLine.stepLine(ExtensionLogLine.Level.INFO.value(),
                String.format(STEP_START, identity.index(), identity.total(),
                        displayLabel(identity), kindWord(identity.type())),
                identity, ExtensionLogLine.Event.START.value(), null, null);
    }

    static ExtensionLogLine stepSuccess(ExtensionLogLine.StepIdentity identity, long elapsedMs, Integer exitCode) {
        return ExtensionLogLine.stepLine(ExtensionLogLine.Level.SUCCESS.value(),
                String.format(STEP_SUCCESS, identity.index(), identity.total(),
                        displayLabel(identity), kindWord(identity.type()), formatDuration(elapsedMs)),
                identity, ExtensionLogLine.Event.SUCCESS.value(), elapsedMs, exitCode);
    }

    /**
     * 步骤失败行：{@code message} 只写<b>原因段本体</b>（不含引导词「原因：」，跨票口径），
     * 致命取 {@code ERROR}、非致命取 {@code WARN}（§14.6 {@code level} 口径 / ui-spec §6.2）。
     * 两支<b>同形</b>——「失败（非致命）」由界面按 {@code level} 渲染，不是这里的文案分支。
     */
    static ExtensionLogLine stepFailure(ExtensionLogLine.StepIdentity identity, ExtensionLogLine.Level level,
                                        String reasonSegment, long elapsedMs, Integer exitCode) {
        return ExtensionLogLine.stepLine(level.value(), reasonSegment, identity,
                ExtensionLogLine.Event.FAILURE.value(), elapsedMs, exitCode);
    }

    /** {@code stdout} / {@code stderr} 与截断说明的承载行（§8.3、§14.6 承载位分工表：非机械判据）。 */
    static ExtensionLogLine stepNote(ExtensionLogLine.StepIdentity identity, String text) {
        return ExtensionLogLine.stepLine(ExtensionLogLine.Level.INFO.value(), text, identity,
                ExtensionLogLine.Event.NOTE.value(), null, null);
    }

    /**
     * 回滚记录位（规则 5）：{@code PATCH} 步骤终态 {@code FAILURE} ⇒ 恰有一行。
     *
     * <p>转写执行器既有的两句回报而不改它的回调接口：{@code 已回滚备份} → {@code INFO}、
     * {@code 回滚失败: …} → {@code ERROR}；失败发生在备份之前（执行器没回报过回滚）→ 仍写该行，
     * {@code INFO} + 「无备份可回滚」。<b>回滚是否真的成功不由本行判定</b>（V-05 走文件比对）。</p>
     */
    static ExtensionLogLine rollbackRow(ExtensionLogLine.StepIdentity identity, String executorSignal) {
        if (executorSignal != null && executorSignal.startsWith(ROLLBACK_FAILED_PREFIX)) {
            return ExtensionLogLine.stepLine(ExtensionLogLine.Level.ERROR.value(), executorSignal, identity,
                    ExtensionLogLine.Event.ROLLBACK.value(), null, null);
        }
        String message = ROLLBACK_DONE.equals(executorSignal) ? ROLLBACK_DONE : ROLLBACK_NONE;
        return ExtensionLogLine.stepLine(ExtensionLogLine.Level.INFO.value(), message, identity,
                ExtensionLogLine.Event.ROLLBACK.value(), null, null);
    }

    // ==================== 原因段 ====================

    static String scriptExitReason(int exitCode) {
        return String.format(REASON_SCRIPT_EXIT, exitCode);
    }

    static String scriptTimeoutReason(long timeoutMs) {
        return String.format(REASON_SCRIPT_TIMEOUT, timeoutMs) + "。" + TIMEOUT_NOT_KILLED;
    }

    /**
     * 脚本没能开始执行的原因段：宿主技术归因 + 摘要不符时的期望 / 实际。
     *
     * <p>{@code expectedSha256} / {@code actualSha256} 只在 {@code CHECKSUM_MISMATCH} 一支非空，
     * 其余支（下载失败 / 源缺失 / 非 http(s)）原样返回技术归因，不拼出「期望 null」这种空话。</p>
     */
    static String scriptPreconditionReason(String technicalCause, String expectedSha256, String actualSha256) {
        if (expectedSha256 == null || actualSha256 == null) {
            return technicalCause;
        }
        return technicalCause + String.format(REASON_EXPECTED_ACTUAL, expectedSha256, actualSha256);
    }

    /** §8.3 的截断说明行文本（{@code ExtensionScriptRunner.TruncatedOutput#truncationNote()} 的同义实现）。 */
    static String truncationNote(long originalBytes) {
        return String.format(TRUNCATION_NOTE, originalBytes);
    }

    // ==================== 对话框错误条（data.error） ====================

    static String dialogFatalStep(ExtensionLogLine.StepIdentity identity) {
        return String.format(DIALOG_FATAL_STEP, displayLabel(identity), kindWord(identity.type()));
    }

    static String dialogFinishFailed() {
        return DIALOG_FINISH_FAILED;
    }

    // ==================== 内部：槽位取值 ====================

    private static ExtensionLogLine stage(ExtensionLogLine.Level level, String message) {
        return ExtensionLogLine.stageLine(level.value(), message, DeployExtensionExecutor.EXTENSION_STAGE);
    }

    /** 声明未给出 {@code label} 时，服务端只在 {@code message} 里按 §6.2 规则 2 回退；契约字段仍为 {@code null}。 */
    private static String displayLabel(ExtensionLogLine.StepIdentity identity) {
        return identity.label() != null && !identity.label().isBlank()
                ? identity.label()
                : String.format(LABEL_FALLBACK, kindWord(identity.type()), identity.index());
    }

    private static String kindWord(String stepType) {
        return "SCRIPT".equals(stepType) ? KIND_SCRIPT : KIND_PATCH;
    }

    /**
     * 时长格式（ui-spec §6.2 规则 3：{@code N秒} / {@code N分N秒} / {@code N小时N分}，不引入裸 ms）。
     *
     * <p>毫秒 → 秒按 design.md §14.6 的 {@code max(1, round(ms / 1000))}：既有公式下 {@code <1000ms}
     * 会渲染成「0秒」，与 AC-03「耗时可见」的观感冲突。权威值只在契约字段 {@code elapsedMs} 上，
     * 这里的秒值只进给人读的 {@code message}。</p>
     */
    private static String formatDuration(long elapsedMs) {
        int seconds = (int) Math.max(1L, Math.round(elapsedMs / 1000.0));
        if (seconds < 60) {
            return seconds + "秒";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "分" + (seconds % 60) + "秒";
        }
        return (seconds / 3600) + "小时" + ((seconds % 3600) / 60) + "分";
    }
}

package com.gameplatform.service.deploy;

/**
 * 部署扩展阶段一条日志行的契约形状（design.md §14.6）。
 *
 * <p>本类型是「执行方产行」与「主应用写进行流」之间唯一的接缝载体：执行器只描述
 * <b>判据要求的字段</b>，不关心内存态 {@code LogEntry} 的 id 与时间戳（那由
 * {@code DeployService} 落）。八个契约字段与 {@code LogEntry}/{@code LogEntryVO}
 * 一一对应（六个字段位、其中两位各含两个属性 ⇒ 8 个属性，SUG-18）。</p>
 *
 * <p>两类行的分工（§14.6 {@code stepId} 行）：</p>
 * <ul>
 *   <li><b>步骤行</b>（{@code stepId != null}）：{@code START} / {@code SUCCESS} / {@code FAILURE}
 *       三行一组，另可有 {@code NOTE}（输出与截断）与 {@code ROLLBACK}（回滚记录位）；
 *       它们进 KPI-02 的分母。</li>
 *   <li><b>阶段级行</b>（{@code stepId == null}）：七项枚举——进入 / 交棒 / 停实例 /
 *       BR-12 拦截 / 目录不合法说明 / 收尾 / 阶段完成。前四类 {@code stepEvent} 为 {@code null}，
 *       后三类带 {@code SUCCESS}/{@code FAILURE}；一律不进分母（SUG-13）。</li>
 * </ul>
 *
 * @param level     {@code INFO} / {@code WARN} / {@code ERROR} / {@code SUCCESS}（后端取值集合不变，§14.6 末段）
 * @param message   给人读的文本，不是核对对象；步骤级失败行只写<b>原因段本体</b>（引导词「原因：」归界面）
 * @param stage     行归属的阶段：扩展阶段行取常量 {@code "EXTENSION"}，目录不合法说明行取它实际发生所在的既有阶段
 * @param stepId    归组主键 {@code E-<序号>}；阶段级行为 {@code null}
 * @param stepIndex 展示位序号，自 1 起
 * @param stepTotal 展示位总数（阶段入口算步骤集时即得，FR-05 / FR-12）
 * @param stepLabel 声明侧 {@code label}；未声明为 {@code null}，缺省回退词面归界面（ui-spec §6.2 规则 2）
 * @param stepType  {@code PATCH} / {@code SCRIPT} 字面量；阶段级行为 {@code null}
 * @param stepEvent {@code START} / {@code SUCCESS} / {@code FAILURE} / {@code ROLLBACK} / {@code NOTE}
 * @param elapsedMs 毫秒权威值；仅 {@code SUCCESS} / {@code FAILURE} 与阶段完成行非空
 * @param exitCode  仅 {@code SCRIPT} 步骤的终态行非空（超时为 {@code null}）；{@code PATCH} 与阶段级行恒 {@code null}
 * @author GamePlatform
 * @version 1.0.0
 */
public record ExtensionLogLine(String level,
                               String message,
                               String stage,
                               String stepId,
                               Integer stepIndex,
                               Integer stepTotal,
                               String stepLabel,
                               String stepType,
                               String stepEvent,
                               Long elapsedMs,
                               Integer exitCode) {

    /** {@code level} 的取值域（§14.6 末段：后端保持大写集合不变，归一化落在前端 §14.9）。 */
    enum Level {
        INFO, SUCCESS, WARN, ERROR;

        String value() {
            return name();
        }
    }

    /** {@code stepEvent} 的取值域（§14.6 契约取值定死为五项）。 */
    enum Event {
        START, SUCCESS, FAILURE, ROLLBACK, NOTE;

        String value() {
            return name();
        }
    }

    /** 阶段级行（无 {@code stepEvent}）：进入 / 交棒 / 停实例 / BR-12 拦截 / 目录不合法说明。 */
    static ExtensionLogLine stageLine(String level, String message, String stage) {
        return new ExtensionLogLine(level, message, stage, null, null, null, null, null, null, null, null);
    }

    /**
     * 阶段级行且带 {@code stepEvent}：收尾行（成功/失败两支）与阶段完成行。
     *
     * <p>带 {@code stepEvent} 但不带 {@code stepId} ⇒ 按 §14.6 规则 2 仍不进 KPI-02 分母，
     * 同时是可机械判定的阶段级事件行。</p>
     */
    static ExtensionLogLine stageEventLine(String level, String message, String stage,
                                           String stepEvent, Long elapsedMs) {
        return new ExtensionLogLine(level, message, stage, null, null, null, null, null,
                stepEvent, elapsedMs, null);
    }

    /** 步骤行。 */
    static ExtensionLogLine stepLine(String level, String message, StepIdentity identity,
                                     String stepEvent, Long elapsedMs, Integer exitCode) {
        return new ExtensionLogLine(level, message, DeployExtensionExecutor.EXTENSION_STAGE,
                identity.stepId(), identity.index(), identity.total(), identity.label(),
                identity.type(), stepEvent, elapsedMs, exitCode);
    }

    /**
     * 一条声明步骤在本次部署内的标识（§14.6 的四个展示位 + 归组主键）。
     *
     * @param label 声明侧 {@code label}，可为 {@code null}（契约字段保持 {@code null}，回退词面只在 message 里做）
     */
    record StepIdentity(String stepId, int index, int total, String label, String type) {
    }
}

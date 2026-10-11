package com.gameplatform.plugin.extension.deploy;

/**
 * 脚本步骤声明（PRD §8.3、design.md §16.2）。
 *
 * <p>正文永不拼进命令行（design.md §8.4：落文件后执行），声明侧因此不承担引号转义面。
 * 步骤须按幂等设计（BR-06）；超时不等于远端进程已终止（design.md §15.3）。</p>
 *
 * @param label     日志展示名，必填非空
 * @param content   内嵌脚本正文，与 {@code url} 二选一（恰有一个）
 * @param url       远端脚本来源，与 {@code content} 二选一，合法 http(s) URL
 * @param sha256    配合 {@code url} 使用，可空则不校验
 * @param position  执行位置，可空即 {@link ScriptPosition#HOST}；本期声明 {@code CONTAINER}
 *                  属声明不合法（design.md §14.5.1，校验规则 N4）
 * @param fatal     步骤致命性。PRD §8.3 的口径是「可选、缺省致命」，而 Java 原始 {@code boolean}
 *                  的缺省是 {@code false} ⇒ 声明方必须显式写 {@code true}
 * @param timeoutMs 步骤超时，可空即 {@code 600_000}；合法区间 {@code [1000, 1800000]}，
 *                  越界判声明不合法而非静默夹取（design.md §15.2）。
 *                  超时判失败，但超时不等于远端进程已终止（design.md §15.3）
 */
public record ScriptStepDeclaration(String label, String content, String url, String sha256,
                                    ScriptPosition position, boolean fatal, Long timeoutMs)
        implements DeployExtensionStepDeclaration {

    @Override
    public StepKind kind() {
        return StepKind.SCRIPT;
    }
}

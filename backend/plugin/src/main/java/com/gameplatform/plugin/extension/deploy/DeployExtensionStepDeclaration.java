package com.gameplatform.plugin.extension.deploy;

/**
 * 部署扩展步骤声明的公共上界（design.md §16.2，v0.3 定义体，回应 REV-4）。
 *
 * <p>承载两件事：§16.2 动态入口 {@code getDeployExtensionSteps(ctx)} 的返回类型，
 * 以及 BR-08 / AC-06 要求的「PATCH 与 SCRIPT 混排于同一有序清单」。
 * 两个实现者就是 {@link PatchStepDeclaration} 与 {@link ScriptStepDeclaration}
 * ⇒ 目录条目的 {@code patches} 拼接 {@code scripts} 零包装即得一个有序混合清单
 * （拼接处需显式类型见证 {@code Stream.<DeployExtensionStepDeclaration>concat(...)}，
 * 不带类型见证在 javac 17 下编译不过，见 design.md §16.2「推导规则」）。</p>
 *
 * <p>sealed 的 {@code permits} 要求实现类同包 ⇒ 插件侧写不出「补丁步骤带执行位置」
 * 这类形状，能在编译期挡住的非法组合不留到运行期。</p>
 */
public sealed interface DeployExtensionStepDeclaration
        permits PatchStepDeclaration, ScriptStepDeclaration {

    /** 步骤展示位（design.md §14.6 的 stepLabel 来源） */
    String label();

    /** 步骤致命性：PRD §8.2 / §8.3 的缺省口径是致命（声明方须显式传 {@code true}，见各 record 的说明）；
     *  主应用只读取，不推断不覆盖（BR-04） */
    boolean fatal();

    /** 执行器分派位（Java 17 无 pattern-matching switch，用显式 kind() 而非 instanceof 链） */
    StepKind kind();
}

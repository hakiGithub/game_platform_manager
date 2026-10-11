package com.gameplatform.plugin.extension.deploy;

import java.util.List;

/**
 * 版本目录条目声明（PRD §8.1、design.md §16.2）。
 *
 * <p>归属键是扩展点的 {@code getGameCode()}，条目本身不带游戏码 ⇒ SDK 层零游戏语义。
 * 声明只出自插件代码，不是运行时可配（design.md §16.2「声明来源」）。</p>
 *
 * <p>{@code patches} 与 {@code scripts} 的元素类型即 {@link DeployExtensionStepDeclaration}
 * 的两个 permitted 子类型 ⇒ 两者按声明序拼接即得该版本的有序步骤清单，
 * 无需包装类型或适配层（BR-08，拼接写法见 design.md §16.2「推导规则」）。</p>
 *
 * @param versionId    版号，必填：非空、同一游戏内唯一、仅 {@code [A-Za-z0-9._-]}
 * @param displayName  展示名，可空即取 {@code versionId}
 * @param imageTag     镜像 tag，可空即沿用该 deployType 模板既有 tag；
 *                     落位机制 = 该 deployType 的保留变量 {@code PLATFORM_IMAGE_TAG}，
 *                     值一律由平台写（design.md §14.4，禁止私加第二种落位方式）
 * @param defaultEntry 是否默认版本条目，同一游戏至多一条 {@code true}；
 *                     默认条目带步骤属合法但永不执行（design.md §16.2）
 * @param patches      补丁步骤集，按声明序；可空即无步骤（PRD §8.1 默认「空」）
 * @param scripts      脚本步骤集，按声明序；可空即无步骤（PRD §8.1 默认「空」）
 */
public record DeployVersionDeclaration(String versionId, String displayName, String imageTag,
                                       Boolean defaultEntry,
                                       List<PatchStepDeclaration> patches,
                                       List<ScriptStepDeclaration> scripts) {
}

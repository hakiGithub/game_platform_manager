package com.gameplatform.plugin.extension.deploy;

/**
 * 部署扩展步骤的执行类别（design.md §16.2）。
 *
 * <p>执行器按 {@link DeployExtensionStepDeclaration#kind()} 分派，不使用类型判断链。</p>
 */
public enum StepKind {

    /** 补丁替换步骤 */
    PATCH,

    /** 脚本执行步骤 */
    SCRIPT
}

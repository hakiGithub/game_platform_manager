package com.gameplatform.plugin.extension.deploy;

/**
 * 补丁步骤声明（PRD §8.2、design.md §16.2）。
 *
 * <p>执行复用宿主侧 {@code PatchInstallService} 全链路（下载、解压、sha256 校验、
 * 备份与自动回滚、宿主机/容器路由），插件不实现落位（FR-14）。
 * 本期硬约束：{@code headers} 不作为可声明字段（PRD §8.2、design.md §14.2）。</p>
 *
 * @param label          日志展示名；可空，缺省语义为「序号 + 步骤类别」（PRD §8.2）
 * @param url            补丁包来源，必填，合法 http(s) URL；本期仅远端 URL（决策 6）
 * @param targetPath     实例相对路径，必填；绝对路径或 {@code ..} 越界属声明不合法（BR-05）
 * @param sha256         完整性校验摘要，可空则不校验；声明了即先校验后落地宿主机（design.md §8.4）
 * @param includePattern 压缩包内落位范围，可空即全部落位，逗号分隔 glob，仅压缩包生效；
 *                       经扩展阶段声明时由同步补丁入口原对象透传（design.md §14.2）
 * @param format         压缩包格式，可空即按扩展名判定，取值沿用宿主 {@code PatchFormat}
 * @param fatal          步骤致命性，缺省语义为致命（BR-04）
 */
public record PatchStepDeclaration(String label, String url, String targetPath, String sha256,
                                   String includePattern, String format, boolean fatal)
        implements DeployExtensionStepDeclaration {

    @Override
    public StepKind kind() {
        return StepKind.PATCH;
    }
}

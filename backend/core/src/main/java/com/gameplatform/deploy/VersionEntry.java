package com.gameplatform.deploy;

import com.gameplatform.plugin.extension.deploy.DeployVersionDeclaration;
import com.gameplatform.plugin.extension.deploy.PatchStepDeclaration;
import com.gameplatform.plugin.extension.deploy.ScriptStepDeclaration;
import com.gameplatform.plugin.extension.deploy.StepKind;

import java.util.List;

/**
 * 已通过校验的版本目录条目（design.md §16.3），保持插件声明序。
 *
 * <p>{@code displayName} 与 {@code stepSummary.label} 的缺省回退分工不同：前者是
 * PRD §8.1 登记的字段默认值（缺省即 {@code versionId}），在服务端算；后者的缺省回退
 * 属界面词面（ui-spec §6.2「补丁替换 〈序号〉」），归前端，因此这里原样透传声明值、
 * 不为它造任何服务端文案。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public record VersionEntry(DeployVersionDeclaration declaration,
                           String displayName,
                           boolean defaultEntry,
                           List<StepSummary> stepSummary) {

    public VersionEntry {
        stepSummary = List.copyOf(stepSummary);
    }

    public String versionId() {
        return declaration.versionId();
    }

    public String imageTag() {
        return declaration.imageTag();
    }

    public List<PatchStepDeclaration> patches() {
        return declaration.patches() == null ? List.of() : declaration.patches();
    }

    public List<ScriptStepDeclaration> scripts() {
        return declaration.scripts() == null ? List.of() : declaration.scripts();
    }

    /**
     * 步骤预览行（design.md §16.4）。
     *
     * @param index 声明序序号，自 1 起（PRD §8.1「按声明序编号」）
     * @param label 声明侧标签，未声明为 {@code null}，回退词面归界面
     * @param type  步骤种类，声明侧字面量，不进界面（ui-spec §6.4）
     * @param fatal 致命性，主应用不推断不覆盖（BR-04）
     */
    public record StepSummary(int index, String label, StepKind type, boolean fatal) {
    }
}

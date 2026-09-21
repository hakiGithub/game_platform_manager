package com.gameplatform.plugin.extension.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * B-01 的类型闭合判据（design.md §16.2「推导规则」、§7.1 B-01）。
 *
 * <p>正例在本文件里以普通断言常驻：目录条目的 {@code patches} 与 {@code scripts}
 * 拼成一个 {@code List<DeployExtensionStepDeclaration>} 不需要任何包装类型或适配层
 * （带显式类型见证）。<b>反例</b>——不带类型见证的字面写法在 javac 17 下编译不过——
 * 无法用「跑一段代码」表达（它编译不成 class），因此连同可复跑命令登记在
 * {@code backend/plugin-stub/acceptance/javaprobe/README.md}。</p>
 */
@DisplayName("扩展步骤声明的类型闭合（B-01 判据）")
class DeployExtensionStepDeclarationTest {

    private static final List<PatchStepDeclaration> PATCHES = List.of(
            new PatchStepDeclaration("补丁一", "http://127.0.0.1:8099/a.zip", "target-a",
                    null, null, null, true));

    private static final List<ScriptStepDeclaration> SCRIPTS = List.of(
            new ScriptStepDeclaration("脚本一", "echo 1", null, null, ScriptPosition.HOST, true, null),
            new ScriptStepDeclaration("脚本二", "echo 2", null, null, ScriptPosition.HOST, false, 5_000L));

    @Test
    @DisplayName("带类型见证的拼接可直接赋给 sealed 上界的 List，且保持声明序 PATCH→SCRIPT")
    void concatAssignsToSealedUpperBound() {
        List<DeployExtensionStepDeclaration> steps =
                Stream.<DeployExtensionStepDeclaration>concat(PATCHES.stream(), SCRIPTS.stream()).toList();

        assertEquals(3, steps.size(), "零包装：不产生中间元素");
        assertEquals(List.of(StepKind.PATCH, StepKind.SCRIPT, StepKind.SCRIPT),
                steps.stream().map(DeployExtensionStepDeclaration::kind).toList(),
                "混排清单按声明序，分派位可读");
        assertInstanceOf(PatchStepDeclaration.class, steps.get(0));
        assertInstanceOf(ScriptStepDeclaration.class, steps.get(1));
        // 元素身份未变（无适配层即无字段复制）
        assertSame(PATCHES.get(0), steps.get(0));
        assertSame(SCRIPTS.get(1), steps.get(2));
    }

    @Test
    @DisplayName("步骤集入口的返回类型即 sealed 上界，两类步骤可直接混列")
    void mixedListIsHomogeneousAtTheUpperBound() {
        List<DeployExtensionStepDeclaration> mixed = List.of(
                SCRIPTS.get(0), PATCHES.get(0), SCRIPTS.get(1));

        assertEquals(List.of("脚本一", "补丁一", "脚本二"),
                mixed.stream().map(DeployExtensionStepDeclaration::label).toList());
        assertEquals(List.of(true, true, false),
                mixed.stream().map(DeployExtensionStepDeclaration::fatal).toList());
    }

    @Test
    @DisplayName("目录条目自带两栈步骤，条目自身不重复声明步骤类型")
    void versionEntryCarriesItsOwnStepSets() {
        DeployVersionDeclaration entry = new DeployVersionDeclaration(
                "1.0.0-x", null, null, Boolean.TRUE, PATCHES, SCRIPTS);

        assertEquals("1.0.0-x", entry.versionId());
        assertEquals(Boolean.TRUE, entry.defaultEntry());
        assertSame(PATCHES, entry.patches());
        assertSame(SCRIPTS, entry.scripts());
    }
}

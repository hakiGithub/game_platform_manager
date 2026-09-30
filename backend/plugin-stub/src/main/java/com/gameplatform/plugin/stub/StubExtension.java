package com.gameplatform.plugin.stub;

import com.gameplatform.plugin.extension.GameEnhancementExtension;
import com.gameplatform.plugin.extension.deploy.DeployVersionDeclaration;
import com.gameplatform.plugin.extension.deploy.PatchStepDeclaration;
import com.gameplatform.plugin.extension.deploy.ScriptStepDeclaration;
import com.gameplatform.plugin.extension.deploy.ScriptPosition;
import org.pf4j.Extension;

import java.util.List;

/**
 * 验收桩插件的扩展声明（design.md §3.3 组 K、§7.5 T-01；PRD FR-24 / §5.1.1 验收资产行）。
 *
 * <p><b>零游戏语义</b>：只声明一份版本目录（≥2 条目 + PATCH/SCRIPT 混排步骤集），
 * 不实现任何控制器、任务处理器、菜单、持久化模型或生命周期钩子。
 * 存在意义是证明「除 dnf-tw 之外还有东西能声明扩展，且主应用无需为它改一行代码」（AC-25），
 * 因此本类与 {@code plugin-dnf-tw} 任何时候都不得混放（AC-26 ②③）。</p>
 *
 * <p>所有取值都是<b>夹具取值</b>，只服务框架验证：不得写成任何游戏的真实资料，
 * 其验证结果不得登记为 AC-05 / KPI-01 / KPI-03 的通过（BR-15 ①②③、N-12）。
 * 补丁与脚本来源指向回环地址上的验收期静态源，见本模块 README.md。</p>
 *
 * <p>{@link #getDeployVersions(String)} 对<b>任意</b> deployType 返回同一份目录：
 * 「集合外 deployType 带步骤即不合法」是主应用的校验规则 N5（design.md §16.3），
 * 桩插件替它过滤就等于把 §10 V-27 第二判据块的反例构造手段删掉。</p>
 */
@Extension
public class StubExtension implements GameEnhancementExtension {

    /** 与 {@code acceptance/games/stub.yml} 的 {@code game.code} 一致（归属键）。 */
    public static final String GAME_CODE = "stub";

    /** 验收期由 {@code acceptance/fixtures/} 起的本地静态源（见 README「夹具投放」）。 */
    private static final String FIXTURE_SOURCE_BASE = "http://127.0.0.1:8099";

    /** 与 {@code acceptance/fixtures/patch/stub-version-marker.zip} 的字节摘要一致（同包用例核对）。 */
    static final String PATCH_PACKAGE_SHA256 =
            "c599f2e5eac8e829803550b83e2e6dbb194f2bdab772fedcd5d3c387e2d8bd58";

    /** 与 {@code acceptance/fixtures/scripts/stub-nonzero.sh} 的字节摘要一致（同包用例核对）。 */
    static final String REMOTE_SCRIPT_SHA256 =
            "c8963961d0a438d1ae4606edbc352128945c1c1cb33b89a0d92a57295b7f8fa6";

    @Override
    public String getGameCode() {
        return GAME_CODE;
    }

    @Override
    public String getGameName() {
        return "验收资产";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "部署扩展步骤框架的验收资产：仅声明版本目录与混排步骤集，不随产品发布（FR-24 / BR-15 ②）";
    }

    @Override
    public List<DeployVersionDeclaration> getDeployVersions(String deployType) {
        return List.of(defaultEntry(), patchedEntry(), selectiveEntry());
    }

    /**
     * 默认版本条目：带一条步骤且这条步骤<b>永不执行</b>。
     *
     * <p>存在的理由是 design.md §16.2 表「目录条目能不能既带步骤又不被选」= 合法但不执行，
     * 脚本正文只打一行标记，验收时按「该标记不出现在部署日志」来核对默认版本路径
     * 没有误入扩展阶段（PRD §8.4.2 S2、AC-02 / AC-15 同族判据）。</p>
     */
    private static DeployVersionDeclaration defaultEntry() {
        return new DeployVersionDeclaration(
                "1.0.0-default",
                null,
                null,
                true,
                List.of(),
                List.of(new ScriptStepDeclaration(
                        "桩默认条目哨兵",
                        """
                        #!/usr/bin/env bash
                        echo "STUB-CANARY 默认版本条目不应执行扩展步骤"
                        """,
                        null,
                        null,
                        ScriptPosition.HOST,
                        true,
                        null)));
    }

    /** 非默认条目 · 全量落位 + 声明摘要 + 声明镜像 tag + PATCH 无 label（核对界面侧缺省回退）。 */
    private static DeployVersionDeclaration patchedEntry() {
        return new DeployVersionDeclaration(
                "2.0.0-patched",
                "桩改造版",
                "acceptance-stub-2.0.0",
                false,
                List.of(new PatchStepDeclaration(
                        null,
                        FIXTURE_SOURCE_BASE + "/patch/stub-version-marker.zip",
                        "stub-patch",
                        PATCH_PACKAGE_SHA256,
                        null,
                        null,
                        true)),
                List.of(new ScriptStepDeclaration(
                        "桩版本改造脚本",
                        """
                        #!/usr/bin/env bash
                        # 只打印，不读写宿主机路径 ⇒ 幂等（BR-06）；pwd 打出来是给验收记录
                        # 留一条「扩展步骤实际工作目录」的观测值，不在声明侧假设它是什么。
                        set -u
                        echo "STUB-MARKER 2.0.0-patched"
                        echo "STUB-PWD $(pwd)"
                        exit 0
                        """,
                        null,
                        null,
                        ScriptPosition.HOST,
                        true,
                        null)));
    }

    /** 非默认条目 · 产物筛选 + 不声明摘要 + URL 取脚本 + 非致命退出码 3 + stdout/stderr 双通道。 */
    private static DeployVersionDeclaration selectiveEntry() {
        return new DeployVersionDeclaration(
                "2.1.0-selective",
                "桩选装版",
                null,
                false,
                List.of(new PatchStepDeclaration(
                        "桩限定范围补丁",
                        FIXTURE_SOURCE_BASE + "/patch/stub-version-marker.zip",
                        "stub-patch-filtered",
                        null,
                        "*.txt",
                        null,
                        true)),
                List.of(
                        new ScriptStepDeclaration(
                                "桩可继续脚本",
                                null,
                                FIXTURE_SOURCE_BASE + "/scripts/stub-nonzero.sh",
                                REMOTE_SCRIPT_SHA256,
                                ScriptPosition.HOST,
                                false,
                                5_000L),
                        new ScriptStepDeclaration(
                                "桩收尾脚本",
                                """
                                #!/usr/bin/env bash
                                # 同一脚各出一行 stdout 与一行 stderr，供 §14.6 承载位分工表的
                                # 「文本可见性核对」（V-22 判据块 2）取标记。
                                set -u
                                echo "STUB-STDOUT 标准输出标记行"
                                echo "STUB-STDERR 标准错误标记行" >&2
                                exit 0
                                """,
                                null,
                                null,
                                ScriptPosition.HOST,
                                true,
                                null)));
    }
}

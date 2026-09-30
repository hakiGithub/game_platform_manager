import com.gameplatform.plugin.extension.deploy.DeployExtensionStepDeclaration;
import com.gameplatform.plugin.extension.deploy.PatchStepDeclaration;
import com.gameplatform.plugin.extension.deploy.ScriptStepDeclaration;

import java.util.List;
import java.util.stream.Stream;

/**
 * B-01 的<b>正例</b>（design.md §16.2「推导规则」）：带显式类型见证即可赋给
 * {@code List<DeployExtensionStepDeclaration>}，无需包装类型、无需适配层、不复制字段。
 *
 * <p>本文件<b>不参与构建</b>（不在 src/main/java 或 src/test/java 下），只用 javac 手工跑；
 * 同一断言的常驻版本在
 * {@code backend/plugin/src/test/java/com/gameplatform/plugin/extension/deploy/DeployExtensionStepDeclarationTest.java}
 * 与 {@code backend/plugin-stub/src/test/java/com/gameplatform/plugin/stub/StubPluginLoadTest.java}。</p>
 */
public class ProbeWithWitness {

    static List<DeployExtensionStepDeclaration> merge(
            List<PatchStepDeclaration> patches, List<ScriptStepDeclaration> scripts) {
        return Stream.<DeployExtensionStepDeclaration>concat(patches.stream(), scripts.stream()).toList();
    }
}

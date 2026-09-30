import com.gameplatform.plugin.extension.deploy.DeployExtensionStepDeclaration;
import com.gameplatform.plugin.extension.deploy.PatchStepDeclaration;
import com.gameplatform.plugin.extension.deploy.ScriptStepDeclaration;

import java.util.List;
import java.util.stream.Stream;

/**
 * B-01 的<b>反例</b>（design.md §16.2「推导规则」）：不带显式类型见证的拼接编译不过。
 *
 * <p>本文件<b>不参与构建</b>（不在 src/main/java 或 src/test/java 下），只用 javac 手工跑，
 * 命令与预期输出见同目录 README.md。它的失败本身就是「零适配层」结论成立的证据。</p>
 */
public class ProbeNoWitness {

    static List<DeployExtensionStepDeclaration> merge(
            List<PatchStepDeclaration> patches, List<ScriptStepDeclaration> scripts) {
        return Stream.concat(patches.stream(), scripts.stream()).toList();
    }
}

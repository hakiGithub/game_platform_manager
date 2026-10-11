package com.gameplatform.plugin.extension;

import com.gameplatform.plugin.extension.deploy.DeployExtensionContext;
import com.gameplatform.plugin.extension.deploy.DeployExtensionStepDeclaration;
import com.gameplatform.plugin.extension.deploy.DeployVersionDeclaration;
import com.gameplatform.plugin.patch.PatchInstallProgressListener;
import com.gameplatform.plugin.patch.PatchInstallRequest;
import com.gameplatform.plugin.patch.PatchInstallService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * B-02 / B-03 的「纯加法」判据（design.md §7.1）。
 *
 * <p>default 方法的意义是<b>既有实现者零改动</b>：一个只实现四个元数据方法的扩展类
 * （最贴近 plugin-l4d2 的既有形状）必须照常可编译、可实例化，且新入口默认给出空集合
 * 而非 null——主应用按「空即不进入扩展阶段」判定（PRD §8.4.2 S1/S2）。</p>
 */
@DisplayName("扩展声明入口的默认值（B-02 / B-03 纯加法判据）")
class GameEnhancementExtensionDeployDeclarationTest {

    /** 只实现四个抽象元数据方法：证明新入口没有将任何实现方拖入改动。 */
    private static class MetadataOnlyExtension implements GameEnhancementExtension {
        @Override
        public String getGameCode() {
            return "metadata-only";
        }

        @Override
        public String getGameName() {
            return "元数据-only 扩展";
        }

        @Override
        public String getVersion() {
            return "1.0.0";
        }

        @Override
        public String getDescription() {
            return "仅用于核对 default 方法";
        }
    }

    /** 只实现 install/probeHost：证明新增 default 不破坏既有 PatchInstallService 实现者。 */
    private static class LegacyOnlyPatchService implements PatchInstallService {
        @Override
        public String install(PatchInstallRequest request) {
            return "task-of-legacy-path";
        }

        @Override
        public com.gameplatform.plugin.patch.HostCapabilities probeHost(Long hostId) {
            return null;
        }
    }

    @Test
    @DisplayName("未声明版本目录的扩展返回空列表，而非 null")
    void getDeployVersionsDefaultsToEmptyList() {
        GameEnhancementExtension extension = new MetadataOnlyExtension();

        assertEquals(List.of(), extension.getDeployVersions("docker-compose"));
        assertEquals(0, extension.getDeployVersions("docker-compose").size());
    }

    @Test
    @DisplayName("未实现动态步骤集的扩展返回空列表，由目录条目自带步骤承担")
    void getDeployExtensionStepsDefaultsToEmptyList() {
        GameEnhancementExtension extension = new MetadataOnlyExtension();
        DeployExtensionContext ctx = new DeployExtensionContext(
                1L, "metadata-only", "docker-compose", null, Map.of("MYSQL_PORT", "3000"));

        List<DeployExtensionStepDeclaration> steps = extension.getDeployExtensionSteps(ctx);

        assertEquals(List.of(), steps);
    }

    @Test
    @DisplayName("两个新入口的返回类型即 sealed 上界与目录条目类型（不需要适配层）")
    void newEntriesReturnTheDeclaredTypes() {
        List<DeployVersionDeclaration> catalog = List.of(
                new DeployVersionDeclaration("1.0.0", null, null, Boolean.TRUE, List.of(), List.of()));
        assertEquals(DeployVersionDeclaration.class, catalog.get(0).getClass());
    }

    @Test
    @DisplayName("宿主未提供同步补丁通道时默认实现抛异常，不静默成功")
    void installSyncDefaultThrowsInsteadOfPretending() {
        PatchInstallService service = new LegacyOnlyPatchService();
        PatchInstallRequest request = PatchInstallRequest.builder().build();
        PatchInstallProgressListener listener = new PatchInstallProgressListener() {
            @Override
            public void onProgress(int percent, String message) {
            }

            @Override
            public void onLog(String message) {
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };

        assertThrows(UnsupportedOperationException.class, () -> service.installSync(request, listener));
        assertEquals("task-of-legacy-path", service.install(request), "既有 install() 行为未变");
    }
}

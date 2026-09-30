package com.gameplatform.deploy;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.plugin.extension.deploy.DeployVersionDeclaration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本选择三态与提交期撞键校验（design.md §10 V-03、§5.3、§14.10）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@DisplayName("版本选择三态与 BR-07 撞键（V-03）")
class DeployVersionSelectionTest {

    private static final String KEY = DeployVersionCatalogService.VERSION_KEY;

    private static final CatalogView AVAILABLE = CatalogView.available(List.of(
            versionEntry("1.0.0", true), versionEntry("2.0.0", false)));

    @Test
    @DisplayName("S1 目录不可用且无键 → 不写键，载荷与现状等价")
    void s1DoesNotWriteKey() {
        Map<String, Object> configInfo = config("maxPlayers", 20);

        for (CatalogView catalog : List.of(CatalogView.absent(), CatalogView.empty(),
                CatalogView.invalid("声明不合法"))) {
            Map<String, Object> result = DeployVersionSelection.apply(configInfo, catalog);
            assertFalse(result.containsKey(KEY), catalog.state().name());
            assertEquals(Map.of("maxPlayers", 20), result, catalog.state().name());
        }
    }

    @Test
    @DisplayName("S1 目录不可用但键既存 → 原样保留：删掉它就等于取消 BR-12 的拦截（AC-20 (b)）")
    void s1KeepsExistingKeyForBr12() {
        Map<String, Object> configInfo = config(KEY, "2.0.0");

        assertEquals("2.0.0", DeployVersionSelection.apply(configInfo, CatalogView.invalid("x")).get(KEY));
        assertEquals("2.0.0", DeployVersionSelection.apply(configInfo, CatalogView.absent()).get(KEY));
    }

    @Test
    @DisplayName("S2 目录可用、未做选择 → 不写键")
    void s2WithoutSelectionWritesNothing() {
        Map<String, Object> result = DeployVersionSelection.apply(config("maxPlayers", 20), AVAILABLE);

        assertFalse(result.containsKey(KEY));
    }

    @Test
    @DisplayName("S2 显式选默认条目 → 不写键，且既存键被删（§14.8 口径 1：这是唯一的删键入口）")
    void s2RemovesExistingKey() {
        Map<String, Object> configInfo = config("maxPlayers", 20);
        configInfo.put(KEY, "1.0.0");

        Map<String, Object> result = DeployVersionSelection.apply(configInfo, AVAILABLE);

        assertFalse(result.containsKey(KEY));
        assertEquals(20, result.get("maxPlayers"), "删键不得顺手改掉其它键");
        assertTrue(configInfo.containsKey(KEY), "纯函数：不得改写入参");
    }

    @Test
    @DisplayName("S3 选非默认条目 → 写键且值精确等于 versionId（不做规范化宽容）")
    void s3WritesExactVersionId() {
        Map<String, Object> result = DeployVersionSelection.apply(config(KEY, "2.0.0"), AVAILABLE);

        assertEquals("2.0.0", result.get(KEY));
    }

    @Test
    @DisplayName("S3 带空白的值不被宽容成合法 versionId：原样交给 BR-12 在扩展阶段入口判")
    void s3DoesNotNormalize() {
        Map<String, Object> configInfo = config(KEY, " 2.0.0");

        assertEquals(" 2.0.0", DeployVersionSelection.apply(configInfo, AVAILABLE).get(KEY));
    }

    @Test
    @DisplayName("S3 目录可用但值不在条目中（G2）→ 仍保留该值，不回落默认、不删键")
    void s3KeepsVersionMissingFromCatalog() {
        Map<String, Object> configInfo = config(KEY, "9.9.9-gone");

        assertEquals("9.9.9-gone", DeployVersionSelection.apply(configInfo, AVAILABLE).get(KEY));
    }

    @Test
    @DisplayName("BR-07 撞键：游戏把变量名声明成 deployVersion ⇒ 提交被拒且原因可辨识")
    void collisionWithDeclaredVariableIsRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> DeployVersionSelection.verifyNoKeyCollision(config(KEY, "2.0.0"), List.of(KEY, "gamePort")));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains(KEY), ex.getMessage());
    }

    @Test
    @DisplayName("未选版本时无键可撞 ⇒ 不判撞键（禁止清单不得拦下普通部署）")
    void collisionCheckOnlyAppliesWhenKeyPresent() {
        DeployVersionSelection.verifyNoKeyCollision(config("maxPlayers", 20), List.of(KEY));
    }

    @Test
    @DisplayName("变量名不含 deployVersion 时正常提交")
    void noCollisionPasses() {
        DeployVersionSelection.verifyNoKeyCollision(config(KEY, "2.0.0"), List.of("gamePort"));
    }

    @Test
    @DisplayName("反例：声明了保留变量的游戏提交带 PLATFORM_IMAGE_TAG ⇒ 不 400（AC-14 正向路径）")
    void reservedImageTagVariableIsNotOnTheCollisionList() {
        Map<String, Object> payload = config(KEY, "2.0.0",
                DeployVersionCatalogService.PLATFORM_IMAGE_TAG_KEY, "user-guessed-tag");

        DeployVersionSelection.verifyNoKeyCollision(payload,
                List.of(DeployVersionCatalogService.PLATFORM_IMAGE_TAG_KEY, "gamePort"));
    }

    @Test
    @DisplayName("AC-20 可测性保护：目录不可用绝不在提交期 400")
    void unavailableCatalogNeverRejectsSubmission() {
        Map<String, Object> payload = config(KEY, "2.0.0");

        for (CatalogView catalog : List.of(CatalogView.absent(), CatalogView.empty(),
                CatalogView.invalid("N1/N2 未过"))) {
            DeployVersionSelection.verifyNoKeyCollision(payload, List.of("gamePort"));
            assertEquals("2.0.0", DeployVersionSelection.apply(payload, catalog).get(KEY), catalog.state().name());
        }
    }

    private static VersionEntry versionEntry(String versionId, boolean defaultEntry) {
        DeployVersionDeclaration declaration =
                new DeployVersionDeclaration(versionId, null, null, defaultEntry, List.of(), List.of());
        return new VersionEntry(declaration, versionId, defaultEntry, List.of());
    }

    private static Map<String, Object> config(Object... pairs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}

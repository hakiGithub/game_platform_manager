package com.gameplatform.plugin.extension;

import com.gameplatform.api.extension.AbstractExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link ExtensionRouter} 单元测试。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@DisplayName("ExtensionRouter 路由解析测试")
class ExtensionRouterTest {

    private final ExtensionRouter router = new ExtensionRouter();

    // ===== 测试用的 Extension 模型 =====

    @ExtensionModel(strategy = Strategy.SHARED)
    static class SharedModel extends AbstractExtension<Object> {
    }

    @ExtensionModel(strategy = Strategy.PLUGIN_ISOLATED)
    static class PluginIsolatedModel extends AbstractExtension<Object> {
    }

    @ExtensionModel(strategy = Strategy.MODEL_ISOLATED)
    static class ModelIsolatedModel extends AbstractExtension<Object> {
    }

    @ExtensionModel(strategy = Strategy.MODEL_ISOLATED, group = "custom-group", kind = "CustomKind")
    static class AnnotatedModel extends AbstractExtension<Object> {
    }

    // ===== 测试用例 =====

    @Test
    @DisplayName("SHARED 策略表名为 extensions")
    void sharedStrategy_resolvesToExtensionsTable() {
        ResolvedRoute route = router.resolve(SharedModel.class, "plugin-l4d2");
        assertEquals("extensions", route.table());
        assertEquals(Strategy.SHARED, route.strategy());
    }

    @Test
    @DisplayName("PLUGIN_ISOLATED 策略表名为 ext_{pluginId}")
    void pluginIsolatedStrategy_resolvesToPluginTable() {
        ResolvedRoute route = router.resolve(PluginIsolatedModel.class, "plugin-l4d2");
        assertEquals("ext_plugin_l4d2", route.table());
        assertEquals(Strategy.PLUGIN_ISOLATED, route.strategy());
    }

    @Test
    @DisplayName("MODEL_ISOLATED 策略表名为 ext_{pluginId}_{kind}")
    void modelIsolatedStrategy_resolvesToKindTable() {
        ResolvedRoute route = router.resolve(ModelIsolatedModel.class, "plugin-l4d2");
        assertEquals("ext_plugin_l4d2_modelisolatedmodel", route.table());
        assertEquals(Strategy.MODEL_ISOLATED, route.strategy());
    }

    @Test
    @DisplayName("默认 group 为 pluginId，默认 kind 为类名")
    void defaultGroupAndKind() {
        ResolvedRoute route = router.resolve(SharedModel.class, "plugin-l4d2");
        assertEquals("plugin-l4d2", route.group());
        assertEquals("SharedModel", route.kind());
    }

    @Test
    @DisplayName("注解可覆盖 group 和 kind")
    void annotationOverridesGroupAndKind() {
        ResolvedRoute route = router.resolve(AnnotatedModel.class, "plugin-l4d2");
        assertEquals("custom-group", route.group());
        assertEquals("CustomKind", route.kind());
        // 表名按 sanitize 后的 kind 生成
        assertEquals("ext_plugin_l4d2_customkind", route.table());
    }

    @Test
    @DisplayName("无注解的类默认使用 SHARED 策略")
    void noAnnotation_defaultsToShared() {
        ResolvedRoute route = router.resolve(NoAnnotationModel.class, "plugin-l4d2");
        assertEquals(Strategy.SHARED, route.strategy());
        assertEquals("extensions", route.table());
    }

    static class NoAnnotationModel extends AbstractExtension<Object> {
    }

    @Test
    @DisplayName("sanitize 把非 [a-z0-9] 字符替换为下划线并转小写")
    void sanitize_replacesSpecialChars() {
        assertEquals("plugin_l4d2", ExtensionRouter.sanitize("plugin-l4d2"));
        assertEquals("plugin_l4d2", ExtensionRouter.sanitize("Plugin-L4D2"));
        assertEquals("abc_123", ExtensionRouter.sanitize("abc.123"));
        assertEquals("_", ExtensionRouter.sanitize(null));
        assertEquals("_", ExtensionRouter.sanitize(""));
    }

    @Test
    @DisplayName("ResolvedRoute 是不可变 record")
    void resolvedRoute_isImmutableRecord() {
        ResolvedRoute route = new ResolvedRoute("ext_x", "g", "k", Strategy.MODEL_ISOLATED);
        assertEquals("ext_x", route.table());
        assertEquals("g", route.group());
        assertEquals("k", route.kind());
        assertEquals(Strategy.MODEL_ISOLATED, route.strategy());
        assertNotNull(route.toString());
    }
}

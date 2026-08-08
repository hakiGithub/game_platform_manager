package com.gameplatform.plugin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.plugin.context.PluginSpringContextFactory;
import com.gameplatform.plugin.extension.GameEnhancementExtension;
import com.gameplatform.plugin.extension.PluginMenuDeclaration;
import com.gameplatform.plugin.service.impl.PluginFrameworkServiceImpl;
import com.gameplatform.plugin.vo.PluginManifestVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.pf4j.PluginManager;
import org.pf4j.PluginWrapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * PluginFrameworkServiceImpl 菜单拼装逻辑测试（ADR-0001）。
 * <p>
 * 验证主应用从扩展点 getMenus() 拼装 manifest 的行为：
 * 空列表、requireInstance 默认值、path 唯一性校验、capabilities 推导。
 * 不再验证已删除的 buildDefaultMenus 与 loadManifestFromFile。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PluginFrameworkServiceImpl 菜单拼装测试（ADR-0001）")
class PluginFrameworkServiceImplMenusTest {

    @Mock
    private PluginManager pluginManager;

    @Mock
    private PluginSpringContextFactory springContextFactory;

    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PluginFrameworkServiceImpl service;

    private static final String PLUGIN_ID = "test-plugin";
    private static final String GAME_CODE = "test-game";

    private GameEnhancementExtension extension;
    private PluginWrapper pluginWrapper;

    @BeforeEach
    void setUp() {
        extension = org.mockito.Mockito.mock(GameEnhancementExtension.class);
        when(extension.getGameCode()).thenReturn(GAME_CODE);
        when(extension.getGameName()).thenReturn("测试游戏");
        when(extension.getVersion()).thenReturn("1.0.0");
        when(extension.getDescription()).thenReturn("测试插件描述");
        when(extension.getIcon()).thenReturn("assets/icon.png");
        when(extension.getFrontendEntry()).thenReturn("index.html");
        when(extension.getManifest()).thenReturn(new HashMap<>(Map.of(
                "gameCode", GAME_CODE,
                "gameName", "测试游戏"
        )));

        pluginWrapper = org.mockito.Mockito.mock(PluginWrapper.class);
        when(pluginWrapper.getPluginId()).thenReturn(PLUGIN_ID);
        when(pluginManager.getPlugin(PLUGIN_ID)).thenReturn(pluginWrapper);
        when(pluginManager.getPlugins()).thenReturn(List.of(pluginWrapper));
        when(pluginManager.getExtensions(GameEnhancementExtension.class))
                .thenReturn(List.of(extension));

        // PluginUtils.findPluginIdByExtension 遍历 getPlugins()，
        // 对每个 plugin 调用 getExtensions(GameEnhancementExtension.class, pluginId)
        // 并比较 extension.getClass().getName()。同一 mock 实例 className 相同，匹配成功。
        when(pluginManager.getExtensions(GameEnhancementExtension.class, PLUGIN_ID))
                .thenReturn(List.of(extension));
    }

    @Nested
    @DisplayName("空菜单列表")
    class EmptyMenus {

        @Test
        @DisplayName("getMenus 返回空列表时 manifest.frontend.menus 为空")
        void getMenus_returnsEmpty_listRendersEmpty() {
            when(extension.getMenus()).thenReturn(List.of());

            PluginManifestVO manifest = service.getManifestByPluginId(PLUGIN_ID);

            assertNotNull(manifest);
            assertNotNull(manifest.getFrontend().getMenus());
            assertTrue(manifest.getFrontend().getMenus().isEmpty());
        }

        @Test
        @DisplayName("getMenus 返回 null 时 manifest.frontend.menus 为空（兼容默认实现）")
        void getMenus_returnsNull_listRendersEmpty() {
            when(extension.getMenus()).thenReturn(null);

            PluginManifestVO manifest = service.getManifestByPluginId(PLUGIN_ID);

            assertNotNull(manifest);
            assertNotNull(manifest.getFrontend().getMenus());
            assertTrue(manifest.getFrontend().getMenus().isEmpty());
        }
    }

    @Nested
    @DisplayName("requireInstance 默认值")
    class RequireInstanceDefaults {

        @Test
        @DisplayName("PluginMenuDeclaration.requireInstance 为 null 时 VO 中填补为 true")
        void getMenus_requireInstanceNull_defaultsToTrue() {
            when(extension.getMenus()).thenReturn(List.of(
                    PluginMenuDeclaration.builder()
                            .title("RCON").path("/rcon").icon("Monitor").order(1)
                            .requireInstance(null)
                            .build()
            ));

            PluginManifestVO manifest = service.getManifestByPluginId(PLUGIN_ID);

            assertEquals(Boolean.TRUE, manifest.getFrontend().getMenus().get(0).getRequireInstance());
        }

        @Test
        @DisplayName("PluginMenuDeclaration.requireInstance 显式 false 时 VO 中保留 false")
        void getMenus_requireInstanceFalse_preservedAsFalse() {
            when(extension.getMenus()).thenReturn(List.of(
                    PluginMenuDeclaration.builder()
                            .title("地图中心").path("/map-center").icon("MapLocation").order(1)
                            .requireInstance(Boolean.FALSE)
                            .build()
            ));

            PluginManifestVO manifest = service.getManifestByPluginId(PLUGIN_ID);

            assertEquals(Boolean.FALSE, manifest.getFrontend().getMenus().get(0).getRequireInstance());
        }

        @Test
        @DisplayName("PluginMenuDeclaration.requireInstance 显式 true 时 VO 中保留 true")
        void getMenus_requireInstanceTrue_preservedAsTrue() {
            when(extension.getMenus()).thenReturn(List.of(
                    PluginMenuDeclaration.builder()
                            .title("RCON").path("/rcon").icon("Monitor").order(1)
                            .requireInstance(Boolean.TRUE)
                            .build()
            ));

            PluginManifestVO manifest = service.getManifestByPluginId(PLUGIN_ID);

            assertEquals(Boolean.TRUE, manifest.getFrontend().getMenus().get(0).getRequireInstance());
        }
    }

    @Nested
    @DisplayName("path 唯一性校验")
    class PathUniqueness {

        @Test
        @DisplayName("同插件内 path 重复时抛 IllegalStateException")
        void getMenus_duplicatePath_throwsIllegalStateException() {
            when(extension.getMenus()).thenReturn(List.of(
                    PluginMenuDeclaration.builder()
                            .title("RCON").path("/rcon").icon("Monitor").order(1).build(),
                    PluginMenuDeclaration.builder()
                            .title("RCON 重复").path("/rcon").icon("Monitor").order(2).build()
            ));

            assertThrows(IllegalStateException.class,
                    () -> service.getManifestByPluginId(PLUGIN_ID));
        }

        @Test
        @DisplayName("path 为空字符串时抛 IllegalStateException")
        void getMenus_emptyPath_throwsIllegalStateException() {
            when(extension.getMenus()).thenReturn(List.of(
                    PluginMenuDeclaration.builder()
                            .title("空 path").path("").icon("Monitor").order(1).build()
            ));

            assertThrows(IllegalStateException.class,
                    () -> service.getManifestByPluginId(PLUGIN_ID));
        }

        @Test
        @DisplayName("path 为空白字符串时抛 IllegalStateException")
        void getMenus_blankPath_throwsIllegalStateException() {
            when(extension.getMenus()).thenReturn(List.of(
                    PluginMenuDeclaration.builder()
                            .title("空白 path").path("   ").icon("Monitor").order(1).build()
            ));

            assertThrows(IllegalStateException.class,
                    () -> service.getManifestByPluginId(PLUGIN_ID));
        }
    }

    @Nested
    @DisplayName("菜单顺序保留")
    class OrderPreservation {

        @Test
        @DisplayName("主应用不重排，菜单按 declaration 顺序保留")
        void getMenus_orderPreserved() {
            when(extension.getMenus()).thenReturn(List.of(
                    PluginMenuDeclaration.builder().title("第三").path("/c").icon("C").order(3).build(),
                    PluginMenuDeclaration.builder().title("第一").path("/a").icon("A").order(1).build(),
                    PluginMenuDeclaration.builder().title("第二").path("/b").icon("B").order(2).build()
            ));

            PluginManifestVO manifest = service.getManifestByPluginId(PLUGIN_ID);

            List<PluginManifestVO.MenuConfig> menus = manifest.getFrontend().getMenus();
            assertEquals(3, menus.size());
            // 主应用不重排，保留 declaration 顺序（前端 Sidebar.vue 按 order 排序）
            assertEquals("/c", menus.get(0).getPath());
            assertEquals("/a", menus.get(1).getPath());
            assertEquals("/b", menus.get(2).getPath());
        }
    }

    @Nested
    @DisplayName("capabilities 推导")
    class CapabilitiesDerivation {

        @Test
        @DisplayName("extensions.capabilities 等于菜单 path 集合")
        void capabilities_derivedFromMenuPaths() {
            when(extension.getMenus()).thenReturn(List.of(
                    PluginMenuDeclaration.builder().title("仪表盘").path("/dashboard").icon("Odometer").order(1).build(),
                    PluginMenuDeclaration.builder().title("RCON").path("/rcon").icon("Monitor").order(2).build(),
                    PluginMenuDeclaration.builder().title("地图中心").path("/map-center").icon("MapLocation").order(3)
                            .requireInstance(Boolean.FALSE).build()
            ));

            PluginManifestVO manifest = service.getManifestByPluginId(PLUGIN_ID);

            assertNotNull(manifest.getExtensions());
            Object capabilities = manifest.getExtensions().get("capabilities");
            assertNotNull(capabilities);
            assertTrue(capabilities instanceof List);
            @SuppressWarnings("unchecked")
            List<String> capList = (List<String>) capabilities;
            assertEquals(List.of("/dashboard", "/rcon", "/map-center"), capList);
        }

        @Test
        @DisplayName("空菜单时 capabilities 为空列表")
        void capabilities_emptyWhenNoMenus() {
            when(extension.getMenus()).thenReturn(List.of());

            PluginManifestVO manifest = service.getManifestByPluginId(PLUGIN_ID);

            Object capabilities = manifest.getExtensions().get("capabilities");
            assertNotNull(capabilities);
            assertTrue(capabilities instanceof List);
            assertTrue(((List<?>) capabilities).isEmpty());
        }

        @Test
        @DisplayName("manifest Map 中不残留 features key")
        void manifest_hasNoFeaturesKey() {
            when(extension.getMenus()).thenReturn(List.of(
                    PluginMenuDeclaration.builder().title("RCON").path("/rcon").icon("Monitor").order(1).build()
            ));

            PluginManifestVO manifest = service.getManifestByPluginId(PLUGIN_ID);

            assertFalse(manifest.getExtensions().containsKey("features"),
                    "manifest 不应再包含 features key（ADR-0001 已删除）");
        }
    }

    @Nested
    @DisplayName("缓存行为")
    class Caching {

        @Test
        @DisplayName("第二次调用返回缓存对象（同一实例）")
        void cachedManifest_returnedOnSecondCall() {
            when(extension.getMenus()).thenReturn(List.of(
                    PluginMenuDeclaration.builder().title("RCON").path("/rcon").icon("Monitor").order(1).build()
            ));

            PluginManifestVO first = service.getManifestByPluginId(PLUGIN_ID);
            PluginManifestVO second = service.getManifestByPluginId(PLUGIN_ID);

            assertNotNull(first);
            assertTrue(first == second, "期望同一实例引用（命中缓存）");
        }

        @Test
        @DisplayName("path 重复抛异常后 manifest 不缓存")
        void duplicatePath_notCached() {
            when(extension.getMenus()).thenReturn(List.of(
                    PluginMenuDeclaration.builder().title("RCON").path("/rcon").icon("Monitor").order(1).build(),
                    PluginMenuDeclaration.builder().title("重复").path("/rcon").icon("Monitor").order(2).build()
            ));

            // 第一次调用抛异常
            assertThrows(IllegalStateException.class,
                    () -> service.getManifestByPluginId(PLUGIN_ID));

            // 修正菜单为合法列表
            when(extension.getMenus()).thenReturn(List.of(
                    PluginMenuDeclaration.builder().title("RCON").path("/rcon").icon("Monitor").order(1).build()
            ));

            // 第二次调用应成功（缓存未被污染）
            PluginManifestVO manifest = service.getManifestByPluginId(PLUGIN_ID);
            assertNotNull(manifest);
            assertEquals(1, manifest.getFrontend().getMenus().size());
        }
    }

    @Test
    @DisplayName("插件不存在时返回 null")
    void pluginNotFound_returnsNull() {
        when(pluginManager.getPlugin("nonexistent")).thenReturn(null);

        PluginManifestVO result = service.getManifestByPluginId("nonexistent");
        assertNull(result);
    }
}

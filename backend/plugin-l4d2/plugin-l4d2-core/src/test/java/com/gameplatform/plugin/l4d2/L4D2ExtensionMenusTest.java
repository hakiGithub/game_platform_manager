package com.gameplatform.plugin.l4d2;

import com.gameplatform.plugin.extension.PluginMenuDeclaration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L4D2Extension.getMenus() 单元测试（ADR-0001）。
 * <p>
 * 验证插件返回的菜单清单完整性、path 唯一性、requireInstance 默认值与显式声明。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@DisplayName("L4D2Extension 菜单清单测试")
class L4D2ExtensionMenusTest {

    private final L4D2Extension extension = new L4D2Extension();

    @Test
    @DisplayName("getMenus 返回 17 项菜单")
    void getMenus_returns17Items() {
        List<PluginMenuDeclaration> menus = extension.getMenus();
        assertEquals(17, menus.size());
    }

    @Test
    @DisplayName("所有菜单 path 唯一")
    void getMenus_allPathsUnique() {
        List<PluginMenuDeclaration> menus = extension.getMenus();
        Set<String> paths = new HashSet<>();
        for (PluginMenuDeclaration m : menus) {
            assertTrue(paths.add(m.getPath()),
                    "path 重复: " + m.getPath());
        }
    }

    @Test
    @DisplayName("/map-center 显式 requireInstance=false")
    void getMenus_mapCenterRequireInstanceFalse() {
        PluginMenuDeclaration mapCenter = findMenuByPath("/map-center");
        assertNotNull(mapCenter, "/map-center 菜单不存在");
        assertEquals(Boolean.FALSE, mapCenter.getRequireInstance(),
                "/map-center requireInstance 应为 false");
    }

    @Test
    @DisplayName("除 /map-center 外其余 requireInstance 为 true（含 null 默认）")
    void getMenus_otherMenusRequireInstanceTrue() {
        List<PluginMenuDeclaration> menus = extension.getMenus();
        for (PluginMenuDeclaration m : menus) {
            if ("/map-center".equals(m.getPath())) continue;
            // @Builder.Default 保证 requireInstance 非 null，值为 Boolean.TRUE
            assertNotNull(m.getRequireInstance(),
                    "菜单 " + m.getPath() + " requireInstance 不应为 null");
            assertEquals(Boolean.TRUE, m.getRequireInstance(),
                    "菜单 " + m.getPath() + " requireInstance 应为 true");
        }
    }

    @Test
    @DisplayName("order 从 1 递增到 17 无缺漏")
    void getMenus_ordersAreSequential() {
        List<PluginMenuDeclaration> menus = extension.getMenus();
        List<Integer> orders = menus.stream()
                .map(PluginMenuDeclaration::getOrder)
                .sorted()
                .collect(Collectors.toList());
        for (int i = 0; i < orders.size(); i++) {
            assertEquals(i + 1, orders.get(i),
                    "order 第 " + (i + 1) + " 位应为 " + (i + 1) + "，实际 " + orders.get(i));
        }
    }

    @Test
    @DisplayName("getManifest 返回的 Map 中无 features key")
    void getMenus_manifestHasNoFeaturesField() {
        Map<String, Object> manifest = extension.getManifest();
        assertFalse(manifest.containsKey("features"),
                "manifest 中不应再包含 features key（ADR-0001 已删除）");
    }

    @Test
    @DisplayName("getManifest 返回的 Map 中无 frontend key（菜单已迁移到 getMenus）")
    void getMenus_manifestHasNoFrontendMenusField() {
        Map<String, Object> manifest = extension.getManifest();
        assertFalse(manifest.containsKey("frontend"),
                "manifest 中不应再包含 frontend key（ADR-0001 菜单迁移到 getMenus）");
    }

    @Test
    @DisplayName("所有菜单 path 非空且以 / 开头")
    void getMenus_allPathsStartWithSlash() {
        List<PluginMenuDeclaration> menus = extension.getMenus();
        for (PluginMenuDeclaration m : menus) {
            assertNotNull(m.getPath(), "菜单 path 不应为 null");
            assertTrue(m.getPath().startsWith("/"),
                    "菜单 path 应以 / 开头: " + m.getPath());
            assertFalse(m.getPath().trim().isEmpty(),
                    "菜单 path 不应为空字符串");
        }
    }

    @Test
    @DisplayName("所有菜单 title 与 icon 非空")
    void getMenus_allTitlesAndIconsNonNull() {
        List<PluginMenuDeclaration> menus = extension.getMenus();
        for (PluginMenuDeclaration m : menus) {
            assertNotNull(m.getTitle(), "菜单 title 不应为 null: " + m.getPath());
            assertFalse(m.getTitle().trim().isEmpty(),
                    "菜单 title 不应为空: " + m.getPath());
            assertNotNull(m.getIcon(), "菜单 icon 不应为 null: " + m.getPath());
        }
    }

    private PluginMenuDeclaration findMenuByPath(String path) {
        return extension.getMenus().stream()
                .filter(m -> path.equals(m.getPath()))
                .findFirst()
                .orElse(null);
    }
}

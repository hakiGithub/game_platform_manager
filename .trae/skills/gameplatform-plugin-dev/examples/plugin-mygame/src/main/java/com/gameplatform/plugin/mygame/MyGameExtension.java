package com.gameplatform.plugin.mygame;

import com.gameplatform.plugin.extension.GameEnhancementExtension;
import com.gameplatform.plugin.extension.PluginMenuDeclaration;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.Extension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MyGame 游戏增强扩展点实现（demo）。
 * <p>
 * 演示要点：
 * <ul>
 *   <li>元数据方法：{@link #getGameCode()} / {@link #getGameName()} / {@link #getVersion()} / {@link #getDescription()}</li>
 *   <li>{@link #getMenus()} 声明菜单清单（ADR-0001）：宿主不预置任何默认菜单，插件需显式声明</li>
 *   <li>{@link #getManifest()} 返回自描述元数据（API 端点等）；不再写 features 字段（已废弃）</li>
 *   <li>生命周期钩子：{@link #onInstanceCreate} 等（默认空实现，按需覆盖）</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Extension
public class MyGameExtension implements GameEnhancementExtension {

    @Override
    public String getGameCode() {
        return "mygame";
    }

    @Override
    public String getGameName() {
        return "我的游戏";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "MyGame demo plugin - 演示最小可运行双端插件";
    }

    /**
     * 插件清单（被宿主合并进 PluginManifestVO.extensions 字段，透传给前端）。
     * <p>
     * 注意（ADR-0001）：原 features 字段已废弃，菜单由 {@link #getMenus()} 单独声明。
     */
    @Override
    public Map<String, Object> getManifest() {
        Map<String, Object> manifest = new HashMap<>();
        manifest.put("gameCode", getGameCode());
        manifest.put("gameName", getGameName());
        manifest.put("version", getVersion());
        manifest.put("description", getDescription());

        // API 端点列表（前端可读取用于自动发现 / 文档展示）
        Map<String, String> apiEndpoints = new HashMap<>();
        apiEndpoints.put("notes", "/api/plugin/mygame/notes");
        manifest.put("apiEndpoints", apiEndpoints);

        // ❌ 不要写 features 字段（ADR-0001 已废弃）
        // ✅ 菜单清单由 getMenus() 声明
        return manifest;
    }

    /**
     * 菜单清单声明（ADR-0001）。
     * <p>
     * 演示最小菜单：仅 /dashboard 一项（依赖 instanceId）。
     * <ul>
     *   <li>path 必须与前端路由（frontend/src/router/index.ts）严格对齐</li>
     *   <li>同插件内 path 必须唯一，重复抛 IllegalStateException</li>
     *   <li>requireInstance 默认 true；纯资源浏览页显式设 Boolean.FALSE</li>
     * </ul>
     */
    @Override
    public List<PluginMenuDeclaration> getMenus() {
        return List.of(
                PluginMenuDeclaration.builder()
                        .title("仪表盘").path("/dashboard").icon("Odometer").order(1).build()
                // 扩展示例：
                // PluginMenuDeclaration.builder()
                //         .title("笔记管理").path("/notes").icon("Document").order(2).build(),
                // PluginMenuDeclaration.builder()
                //         .title("资源中心").path("/resources").icon("Files").order(3)
                //         .requireInstance(Boolean.FALSE).build()  // 纯资源浏览页
        );
    }

    // ==================== 生命周期钩子（按需覆盖） ====================

    @Override
    public void onInstanceCreate(Long instanceId, Map<String, Object> config) {
        log.info("[MyGame] 实例创建: instanceId={}, config={}", instanceId, config);
    }

    @Override
    public void onInstanceStart(Long instanceId) {
        log.info("[MyGame] 实例启动: instanceId={}", instanceId);
    }

    @Override
    public void onInstanceStop(Long instanceId) {
        log.info("[MyGame] 实例停止: instanceId={}", instanceId);
    }

    @Override
    public void onInstanceDelete(Long instanceId) {
        log.info("[MyGame] 实例删除: instanceId={}", instanceId);
    }

    // ==================== 前端资源 ====================

    @Override
    public String getIcon() {
        return "assets/icon.png";
    }

    @Override
    public String getFrontendEntry() {
        return "index.html";
    }

    @Override
    public String getBasePackage() {
        return "com.gameplatform.plugin.mygame";
    }
}

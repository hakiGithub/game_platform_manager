package com.gameplatform.plugin.l4d2;

import com.gameplatform.plugin.extension.GameEnhancementExtension;
import com.gameplatform.plugin.extension.PluginMenuDeclaration;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.Extension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * L4D2 游戏增强扩展点实现
 * 
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Extension
public class L4D2Extension implements GameEnhancementExtension {

    @Override
    public String getGameCode() {
        return "l4d2";
    }

    @Override
    public String getGameName() {
        return "求生之路2";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "求生之路2 游戏服务器增强插件，提供 RCON 远程管理、VPK 地图解析等功能";
    }

    @Override
    public Map<String, Object> getManifest() {
        Map<String, Object> manifest = new HashMap<>();
        manifest.put("gameCode", getGameCode());
        manifest.put("gameName", getGameName());
        manifest.put("version", getVersion());
        manifest.put("description", getDescription());

        // API 端点列表
        Map<String, String> apiEndpoints = new HashMap<>();
        apiEndpoints.put("status", "/api/plugin/l4d2/rcon/status");
        apiEndpoints.put("maps", "/api/plugin/l4d2/vpk/maps");
        apiEndpoints.put("changeMap", "/api/plugin/l4d2/rcon/map");
        apiEndpoints.put("kick", "/api/plugin/l4d2/rcon/kick");
        apiEndpoints.put("ban", "/api/plugin/l4d2/rcon/ban");
        apiEndpoints.put("difficulty", "/api/plugin/l4d2/rcon/difficulty");
        apiEndpoints.put("gameMode", "/api/plugin/l4d2/rcon/gamemode");
        apiEndpoints.put("maxPlayers", "/api/plugin/l4d2/rcon/maxplayers");
        apiEndpoints.put("command", "/api/plugin/l4d2/rcon/command");
        manifest.put("apiEndpoints", apiEndpoints);

        // ADR-0001: features 与 frontend.menus 字段删除
        // 菜单清单迁移到 getMenus()；capabilities 由主应用从菜单 path 集合推导
        // features 不再用于菜单 gate

        return manifest;
    }

    /**
     * 插件菜单清单（ADR-0001）
     * <p>
     * 返回 20 项菜单（6 区段分组，parent 二级归组），主应用 PluginFrameworkServiceImpl
     * 调用此方法拼装 manifest.frontend.menus；主应用侧边栏按 parent 建树渲染。
     * 菜单 path 需与子应用前端路由（plugin-l4d2/frontend/src/router/index.ts）保持一致。
     * <p>
     * 区段（order 十年档：组头 10 的倍数，组内 +1..+n）：
     * <ol>
     *   <li>仪表盘（顶层，不归组，order 1）</li>
     *   <li>配置管理 /config：服务器配置 / 服务器信息（含版本信息）/ 预设场景</li>
     *   <li>运维控制 /ops：控制台 / 系统监控 / 日志 / 重启管理</li>
     *   <li>地图管理 /maps-group：地图管理 / 地图中心</li>
     *   <li>玩家管理 /players：玩家统计（含游玩时长）/ 管理员</li>
     *   <li>扩展与数据 /data：插件管理 / 备份还原 / 下载管理</li>
     * </ol>
     * <ul>
     *   <li>{@code /version}、{@code /playtime} 已合并进 /server-info、/player-stats（页面区块/Tab），不再独立声明</li>
     *   <li>{@code /map-center} 显式 {@code requireInstance=false}（纯资源浏览页，无需实例）</li>
     *   <li>其余菜单依赖 {@code @Builder.Default} 默认 {@code requireInstance=true}</li>
     *   <li>分组父节点（/config 等）仅作侧边栏分组容器，无对应页面路由</li>
     * </ul>
     */
    @Override
    public List<PluginMenuDeclaration> getMenus() {
        return List.of(
                // === 仪表盘（顶层） ===
                PluginMenuDeclaration.builder()
                        .title("仪表盘").path("/dashboard").icon("Odometer").order(1).build(),
                // === 配置管理 ===
                PluginMenuDeclaration.builder()
                        .title("配置管理").path("/config").icon("Setting").order(10).build(),
                PluginMenuDeclaration.builder()
                        .title("服务器配置").path("/server-config").icon("Setting").order(11)
                        .parent("/config").build(),
                PluginMenuDeclaration.builder()
                        .title("服务器信息").path("/server-info").icon("InfoFilled").order(12)
                        .parent("/config").build(),
                PluginMenuDeclaration.builder()
                        .title("预设场景").path("/preset").icon("MagicStick").order(13)
                        .parent("/config").build(),
                // === 运维控制 ===
                PluginMenuDeclaration.builder()
                        .title("运维控制").path("/ops").icon("Monitor").order(20).build(),
                PluginMenuDeclaration.builder()
                        .title("控制台").path("/rcon").icon("Monitor").order(21)
                        .parent("/ops").build(),
                PluginMenuDeclaration.builder()
                        .title("系统监控").path("/monitor").icon("Monitor").order(22)
                        .parent("/ops").build(),
                PluginMenuDeclaration.builder()
                        .title("日志").path("/logs").icon("Document").order(23)
                        .parent("/ops").build(),
                PluginMenuDeclaration.builder()
                        .title("重启管理").path("/restart").icon("RefreshRight").order(24)
                        .parent("/ops").build(),
                // === 地图管理 ===
                PluginMenuDeclaration.builder()
                        .title("地图管理").path("/maps-group").icon("Position").order(30).build(),
                PluginMenuDeclaration.builder()
                        .title("地图管理").path("/maps").icon("Position").order(31)
                        .parent("/maps-group").build(),
                PluginMenuDeclaration.builder()
                        .title("地图中心").path("/map-center").icon("MapLocation").order(32)
                        .parent("/maps-group").requireInstance(Boolean.FALSE).build(),
                // === 玩家管理 ===
                PluginMenuDeclaration.builder()
                        .title("玩家管理").path("/players").icon("User").order(40).build(),
                PluginMenuDeclaration.builder()
                        .title("玩家统计").path("/player-stats").icon("User").order(41)
                        .parent("/players").build(),
                PluginMenuDeclaration.builder()
                        .title("管理员").path("/admins").icon("User").order(42)
                        .parent("/players").build(),
                // === 扩展与数据 ===
                PluginMenuDeclaration.builder()
                        .title("扩展与数据").path("/data").icon("Box").order(50).build(),
                PluginMenuDeclaration.builder()
                        .title("插件管理").path("/plugins").icon("Box").order(51)
                        .parent("/data").build(),
                PluginMenuDeclaration.builder()
                        .title("备份还原").path("/backup").icon("FolderOpened").order(52)
                        .parent("/data").build(),
                PluginMenuDeclaration.builder()
                        .title("下载管理").path("/download").icon("Download").order(53)
                        .parent("/data").build()
        );
    }

    @Override
    public void onInstanceCreate(Long instanceId, Map<String, Object> config) {
        // 插件库目录（plugins_store/）和 .enabled_plugins.yaml 采用懒初始化策略：
        // 在 PluginInstallService 首次访问时检查并创建，避免实例尚未部署完成时操作远程文件失败。
        log.info("L4D2 实例创建: instanceId={}, config={}, 插件库将在首次访问时懒初始化", instanceId, config);
    }

    @Override
    public void onInstanceStart(Long instanceId) {
        log.info("L4D2 实例启动: instanceId={}", instanceId);
    }

    @Override
    public void onInstanceStop(Long instanceId) {
        log.info("L4D2 实例停止: instanceId={}", instanceId);
    }

    @Override
    public void onInstanceDelete(Long instanceId) {
        // 扩展资源（EnabledPluginResource、PluginConfigResource）的清理由 InstanceService
        // 在删除实例时通过 ExtensionClient 按 instanceId 过滤删除，此处仅记录日志。
        log.info("L4D2 实例删除: instanceId={}, 扩展资源将由 InstanceService 清理", instanceId);
    }

    @Override
    public String getIcon() {
        return "assets/l4d2-icon.png";
    }

    @Override
    public String getFrontendEntry() {
        return "index.html";
    }

    @Override
    public String getBasePackage() {
        return "com.gameplatform.plugin.l4d2";
    }
}

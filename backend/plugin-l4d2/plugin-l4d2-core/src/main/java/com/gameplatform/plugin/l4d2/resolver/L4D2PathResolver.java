package com.gameplatform.plugin.l4d2.resolver;

import org.springframework.stereotype.Component;

/**
 * L4D2 路径解析器：返回相对于实例游戏数据根目录的相对路径。
 *
 * <p>所有路径使用正斜杠。调用方需结合 InstanceFileService 使用：
 * instanceFileService.readTextFile(instanceId, pathResolver.getServerCfgPath()).
 *
 * <p>根目录语义：
 * - Native/LinuxGSM：installPath
 * - Docker/Compose/LinuxGsmDocker：runtimeMetadata.containerWorkDir
 *
 * @author GamePlatform
 * @version 2.0.0
 */
@Component
public class L4D2PathResolver {

    private static final String LEFT_4_DEAD_2 = "left4dead2";

    public String getGamePath() {
        return LEFT_4_DEAD_2;
    }

    public String getAddonsPath() {
        return getGamePath() + "/addons";
    }

    public String getSourceModPath() {
        return getAddonsPath() + "/sourcemod";
    }

    public String getSourceModPluginsPath() {
        return getSourceModPath() + "/plugins";
    }

    public String getSourceModPluginsDisabledPath() {
        return getSourceModPluginsPath() + "/disabled";
    }

    public String getSourceModConfigsPath() {
        return getSourceModPath() + "/configs";
    }

    public String getSourceModLogsPath() {
        return getSourceModPath() + "/logs";
    }

    public String getCfgPath() {
        return getGamePath() + "/cfg";
    }

    public String getSourceModCfgPath() {
        return getCfgPath() + "/sourcemod";
    }

    public String getServerCfgPath() {
        return getCfgPath() + "/server.cfg";
    }

    public String getMaplistPath() {
        return getAddonsPath() + "/maplist.txt";
    }

    public String getMotdPath() {
        return getGamePath() + "/motd.txt";
    }

    public String getHostInfoPath() {
        return getGamePath() + "/host.txt";
    }

    public String getHostnameConfigPath() {
        return getSourceModConfigsPath() + "/l4d2_hostname.txt";
    }

    public String getAdminsIniPath() {
        return getSourceModConfigsPath() + "/admins_simple.ini";
    }

    public String getFileRefsPath() {
        return getSourceModPath() + "/.file_refs.json";
    }

    // ===== 插件库目录（与游戏目录分离，对齐 l4d2-server-next 设计）=====

    /**
     * 插件库根目录：addons/sourcemod/plugins_store
     * 所有未启用/已上传/商店下载的插件均存放在此目录下，每个插件一个子目录。
     */
    public String getPluginsStorePath() {
        return getSourceModPath() + "/plugins_store";
    }

    /**
     * 单个插件的库目录：addons/sourcemod/plugins_store/{pluginName}
     */
    public String getPluginStorePath(String pluginName) {
        return getPluginsStorePath() + "/" + pluginName;
    }

    /**
     * 插件库中插件的 left4dead2 目录：addons/sourcemod/plugins_store/{pluginName}/left4dead2
     * 启用时从此目录复制文件到游戏目录（left4dead2/）。
     */
    public String getPluginLeft4Dead2Path(String pluginName) {
        return getPluginStorePath(pluginName) + "/left4dead2";
    }

    /**
     * 已启用插件清单文件：addons/sourcemod/.enabled_plugins.yaml
     * fileRefs 内存 Map 从此文件懒加载重建。
     */
    public String getEnabledPluginsYamlPath() {
        return getSourceModPath() + "/.enabled_plugins.yaml";
    }

    /**
     * 插件元数据文件：addons/sourcemod/plugins_store/{pluginName}/plugin.yaml
     * 记录 source（panel/store/upload）、fileList、configFiles。
     */
    public String getPluginYamlPath(String pluginName) {
        return getPluginStorePath(pluginName) + "/plugin.yaml";
    }

    /**
     * 插件 README 文件：addons/sourcemod/plugins_store/{pluginName}/README.md
     */
    public String getPluginReadmePath(String pluginName) {
        return getPluginStorePath(pluginName) + "/README.md";
    }

    // ===== 商店下载临时目录（对齐 l4d2-server-next DownloadTempDir）=====

    /**
     * 商店下载临时目录根：addons/sourcemod/.download_temp
     *
     * <p>每次下载任务在此目录下创建 {taskId} 子目录，成功后原子重命名为正式插件目录。
     * 应用启动时由 PluginStoreMigration 整体清空。
     */
    public String getDownloadTempPath() {
        return getSourceModPath() + "/.download_temp";
    }

    /**
     * 单次下载任务的临时目录：addons/sourcemod/.download_temp/{taskId}
     *
     * @param taskId 下载任务 ID（用作临时目录名）
     */
    public String getDownloadTaskTempPath(String taskId) {
        return getDownloadTempPath() + "/" + taskId;
    }
}

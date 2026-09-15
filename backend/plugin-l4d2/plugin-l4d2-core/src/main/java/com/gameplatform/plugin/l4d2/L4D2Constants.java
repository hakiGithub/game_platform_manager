package com.gameplatform.plugin.l4d2;

/**
 * L4D2 插件常量。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public final class L4D2Constants {

    private L4D2Constants() {}

    /** 插件版本号 */
    public static final String VERSION = "2.0.0";

    /** 构建时间（由 Maven git-commit-id-plugin 注入；缺省为占位） */
    public static final String BUILD_TIME = "${build.time:unknown}";

    /** Git commit ID（由 Maven 注入） */
    public static final String GIT_COMMIT = "${git.commit.id:unknown}";

    /** 自定义配置块标记 */
    public static final String CUSTOM_CONFIG_MARK = "// [L4D2-MANAGER-CUSTOM]";

    /** 平台插件标识 */
    public static final String PLATFORM_PLUGIN_KEYWORD = "插件平台";

    /** fileRefs 持久化文件名 */
    public static final String FILE_REFS_FILENAME = ".file_refs.json";

    /** 任务中心：任务来源（插件 gameCode 大写） */
    public static final String TASK_SOURCE = "L4D2";

    /** 任务中心：任务作用域类型 - 实例 */
    public static final String SCOPE_TYPE_INSTANCE = "INSTANCE";

    /** 任务中心：任务类型 - 内置插件单个安装 */
    public static final String TASK_TYPE_BUILTIN_PLUGIN_INSTALL = "builtin-plugin-install";

    /** 任务中心：任务类型 - 内置插件批量安装 */
    public static final String TASK_TYPE_BUILTIN_PLUGIN_BATCH_INSTALL = "builtin-plugin-batch-install";

    /** 任务中心：任务类型 - 地图上传 */
    public static final String TASK_TYPE_MAP_UPLOAD = "map-upload";

    /** 任务中心：任务类型 - 地图爬取 */
    public static final String TASK_TYPE_CRAWL = "crawl";

    /** 任务中心：任务类型 - 云盘转存安装（ADR-0025） */
    public static final String TASK_TYPE_CLOUD_INSTALL = "cloud-install";

    /** 任务中心：任务类型 - 地图批量识别（ADR-0027） */
    public static final String TASK_TYPE_MAP_RECOGNIZE = "map-recognize";

    /** 内置插件安装任务短时防重提交窗口（毫秒） */
    public static final long INSTALL_SUBMIT_DEDUP_WINDOW_MS = 30_000L;
}

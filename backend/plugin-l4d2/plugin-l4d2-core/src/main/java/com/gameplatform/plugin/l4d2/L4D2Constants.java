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
}

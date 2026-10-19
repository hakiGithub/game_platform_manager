package com.gameplatform.plugin.l4d2.util;

import java.util.List;

/**
 * RCON 命令失败检测器（对齐 l4d2-server-next sourceModPluginCommandFailed）。
 *
 * <p>通过关键字匹配判断 RCON 命令输出是否表示失败。覆盖 SourceMod 常见失败响应，
 * 例如 "unknown command"、"Plugin not found"、"failed to load" 等。
 *
 * <p>使用方法：
 * <pre>{@code
 * String output = rconService.executeCommand(instanceId, "sm plugins load " + pluginName);
 * if (RconFailureDetector.isFailed(output)) {
 *     throw new RuntimeException("RCON 加载失败: " + output);
 * }
 * }</pre>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public final class RconFailureDetector {

    /**
     * 失败标记列表（小写匹配，对齐 l4d2-server-next sourceModPluginCommandFailed）。
     */
    private static final List<String> FAILURE_MARKERS = List.of(
        "unknown command",
        "no such command",
        "failed",
        "error",
        "not found",
        "invalid",
        "could not",
        "unable to",
        "is not loaded",
        "no matching plugin"
    );

    /**
     * changelevel 的失败标记。地图命令单独匹配，避免把正常 status 日志中的
     * "error" 等通用词误判成换图失败。
     */
    private static final List<String> CHANGE_MAP_FAILURE_MARKERS = List.of(
        "no such map",
        "changelevel failed",
        "failed to load map",
        "couldn't find map",
        "could not find map",
        "map not found"
    );

    private RconFailureDetector() {
    }

    /**
     * 检测 RCON 命令输出是否表示失败。
     *
     * @param output RCON 命令输出（可能为 null）
     * @return true 表示输出包含失败标记
     */
    public static boolean isFailed(String output) {
        if (output == null || output.isEmpty()) {
            return false;
        }
        String lower = output.toLowerCase().trim();
        return FAILURE_MARKERS.stream().anyMatch(lower::contains);
    }

    /**
     * 检测地图切换命令是否被服务器明确拒绝。
     *
     * @param output changelevel 的 RCON 回显
     * @return true 表示服务器明确拒绝或无法加载目标地图
     */
    public static boolean isChangeMapFailed(String output) {
        if (output == null || output.isEmpty()) {
            return false;
        }
        String lower = output.toLowerCase().trim();
        return CHANGE_MAP_FAILURE_MARKERS.stream().anyMatch(lower::contains);
    }
}

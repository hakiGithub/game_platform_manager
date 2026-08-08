package com.gameplatform.plugin.extension;

import com.gameplatform.api.extension.AbstractExtension;
import org.springframework.stereotype.Component;

/**
 * 表名路由解析器。
 * <p>
 * ExtensionClient 在拼参数化 SQL 前调用本类得到表名与身份信息，
 * 比事后拦截 SQL 字符串更干净、不可绕过。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Component
public class ExtensionRouter {

    /**
     * 解析模型类的路由信息。
     *
     * @param modelClass 扩展资源类
     * @param pluginId   当前插件ID
     * @return 路由结果（表名 / group / kind / 策略）
     */
    public ResolvedRoute resolve(Class<? extends AbstractExtension<?>> modelClass, String pluginId) {
        ExtensionModel meta = modelClass.getAnnotation(ExtensionModel.class);
        Strategy strategy = (meta != null) ? meta.strategy() : Strategy.SHARED;
        String group = (meta != null && !meta.group().isEmpty()) ? meta.group() : pluginId;
        String kind = (meta != null && !meta.kind().isEmpty()) ? meta.kind() : modelClass.getSimpleName();

        String table = switch (strategy) {
            case SHARED -> "extensions";
            case PLUGIN_ISOLATED -> "ext_" + sanitize(pluginId);
            case MODEL_ISOLATED -> "ext_" + sanitize(pluginId) + "_" + sanitizeLower(kind);
        };
        return new ResolvedRoute(table, group, kind, strategy);
    }

    /**
     * 把输入中非 [a-z0-9_] 的字符替换为下划线并转小写。
     * <p>
     * 例如 pluginId "plugin-l4d2"、kind "Admin" → "plugin_l4d2"、"admin"。
     * 仅用于表名转换；group_name 列存原始 pluginId（不做 sanitize）。
     *
     * @param input 原始输入
     * @return 安全的表名片段
     */
    static String sanitize(String input) {
        if (input == null || input.isEmpty()) {
            return "_";
        }
        return input.toLowerCase().replaceAll("[^a-z0-9]", "_");
    }

    /**
     * sanitize 的 kind 专用别名，语义一致。
     */
    static String sanitizeLower(String input) {
        return sanitize(input);
    }
}

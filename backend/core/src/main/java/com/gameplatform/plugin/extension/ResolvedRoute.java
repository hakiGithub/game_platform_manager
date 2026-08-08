package com.gameplatform.plugin.extension;

/**
 * 路由解析结果，携带表名、身份信息与策略。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public record ResolvedRoute(String table, String group, String kind, Strategy strategy) {
}

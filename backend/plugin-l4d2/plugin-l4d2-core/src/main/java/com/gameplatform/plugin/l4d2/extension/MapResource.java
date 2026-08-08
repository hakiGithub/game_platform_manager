package com.gameplatform.plugin.l4d2.extension;

import com.gameplatform.api.extension.AbstractExtension;
import com.gameplatform.plugin.extension.ExtensionModel;
import com.gameplatform.plugin.extension.Strategy;

/**
 * 地图中心扩展资源。
 * <p>
 * PLUGIN_ISOLATED 策略，与 CrawlTaskResource 共用 {@code ext_{pluginId}} 表，通过 kind 区分。
 * name 规范：使用 sourceId（从详情页 URL 提取的数字 ID，如 "807"）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtensionModel(strategy = Strategy.PLUGIN_ISOLATED)
public class MapResource extends AbstractExtension<MapSpec> {
}

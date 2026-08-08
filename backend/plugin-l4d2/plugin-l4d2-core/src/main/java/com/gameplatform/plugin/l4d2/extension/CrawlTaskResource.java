package com.gameplatform.plugin.l4d2.extension;

import com.gameplatform.api.extension.AbstractExtension;
import com.gameplatform.plugin.extension.ExtensionModel;
import com.gameplatform.plugin.extension.Strategy;

/**
 * 爬取任务扩展资源。
 * <p>
 * PLUGIN_ISOLATED 策略，与 MapResource 共用 {@code ext_{pluginId}} 表，通过 kind 区分。
 * name 规范：{@code crawl-{timestamp}}。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtensionModel(strategy = Strategy.PLUGIN_ISOLATED)
public class CrawlTaskResource extends AbstractExtension<CrawlTaskSpec> {
}

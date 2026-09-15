package com.gameplatform.plugin.l4d2.extension;

import com.gameplatform.api.extension.AbstractExtension;
import com.gameplatform.plugin.extension.ExtensionModel;
import com.gameplatform.plugin.extension.Strategy;

/**
 * L4D2 地图识别扩展资源（ADR-0027）。
 * <p>
 * MODEL_ISOLATED 策略，物理表 {@code ext_plugin_l4d2_maprecognition}。
 * name 规范：VPK sha-256 摘要（小写 hex），插件级共享、跨实例复用。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)
public class MapRecognitionResource extends AbstractExtension<MapRecognitionSpec> {
}

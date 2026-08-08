package com.gameplatform.plugin.l4d2.extension;

import com.gameplatform.api.extension.AbstractExtension;
import com.gameplatform.plugin.extension.ExtensionModel;
import com.gameplatform.plugin.extension.Strategy;

/**
 * L4D2 玩家统计-玩家记录扩展资源。
 * <p>
 * MODEL_ISOLATED 策略，物理表 {@code ext_plugin_l4d2_playerstatplayer}。
 * name 规范：{@code {instanceId}-{snapshotId}-{steamId}-{idx}}。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtensionModel(strategy = Strategy.MODEL_ISOLATED)
public class PlayerStatPlayerResource extends AbstractExtension<PlayerStatPlayerSpec> {
}

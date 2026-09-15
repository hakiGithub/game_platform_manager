package com.gameplatform.clouddrive.extension;

import com.gameplatform.api.extension.AbstractExtension;
import com.gameplatform.plugin.extension.ExtensionModel;
import com.gameplatform.plugin.extension.Strategy;

/**
 * 云盘账号扩展资源（ADR-0024）。
 * <p>
 * SHARED 策略 + 宿主保留命名空间 {@code platform}，落主应用启动时自动创建的
 * 全局 {@code extensions} 表（不新增表）。name 为账号唯一业务标识，
 * 同时也是默认挂载路径 {@code /{providerType}/{name}} 的组成部分。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtensionModel(strategy = Strategy.SHARED, group = "platform", kind = "cloud_account")
public class CloudAccountResource extends AbstractExtension<CloudAccountSpec> {
}

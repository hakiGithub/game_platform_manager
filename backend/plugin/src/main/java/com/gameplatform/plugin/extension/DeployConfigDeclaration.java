package com.gameplatform.plugin.extension;

import java.util.Map;

/**
 * 插件声明的部署方式配置（v3.6.0）。
 *
 * <p>插件通过 {@link GameEnhancementExtension#getDeployConfigs()} 声明该游戏
 * 的部署配置模板，主应用在读取游戏部署配置时合并：
 * <ul>
 *   <li>部署选项：插件声明的部署类型自动加入该游戏的部署方式选项
 *       （仅限主应用已支持的部署类型 code）</li>
 *   <li>配置覆盖：同一部署类型下，插件声明整节替换主应用游戏元数据
 *       （games/*.yml）的同名配置节，插件优先</li>
 * </ul>
 *
 * <p>config 结构与主应用游戏元数据 deployConfig 的同名节完全同构
 * （如 linuxgsm-docker 节：composeTemplate / variables / namedVolumes /
 * imageRepo / imageTag / shortname / workingDir 等），主应用合并时零转换。
 *
 * @param deployType 部署类型 code（与 {@code DeployAdapter.DeployType} 一致，
 *                  如 docker / docker-compose / linuxgsm / linuxgsm-docker）
 * @param config     该部署类型的完整配置节（整节替换语义）
 * @author GamePlatform
 * @version 1.0.0
 */
public record DeployConfigDeclaration(String deployType, Map<String, Object> config) {
}

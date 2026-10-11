package com.gameplatform.plugin.extension.deploy;

import java.util.Map;

/**
 * 扩展步骤声明入口的实例上下文（design.md §16.2）。
 *
 * <p>供 {@code getDeployExtensionSteps(ctx)} 按实例配置动态计算步骤集（FR-05）：
 * 部署向导步骤 2 尚无实例，故静态目录入口不带本上下文，两入口形状不同源于此。</p>
 *
 * @param instanceId         实例 ID
 * @param gameCode           归属游戏码（扩展点的唯一归属键）
 * @param deployType         本次部署方式；本期支持扩展步骤的集合见 design.md §14.13.1
 * @param selectedVersionId  本次部署选定的版本条目 {@code versionId}；
 *                           为空即默认版本（不进入扩展阶段，PRD §8.4.2 S2）
 * @param configInfo         实例完整配置（扁平 map，含 {@code variables[].name} 各键与
 *                           系统保留键），只读视图
 */
public record DeployExtensionContext(Long instanceId, String gameCode, String deployType,
                                     String selectedVersionId, Map<String, Object> configInfo) {
}

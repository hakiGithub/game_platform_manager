package com.gameplatform.plugin.extension;

/**
 * 实例信息提供者扩展点（ADR-0017）。
 * <p>
 * 插件按实例提供动态信息（当前玩家数等）：插件内以 {@code @Component} 实现
 * 本接口，主应用在插件子容器创建时扫描注册（按游戏编码），卸载/热重载时注销。
 * <p>
 * 未实现本扩展点的游戏自动走降级默认值（RUNNING 用库中存量玩家数，
 * 非 RUNNING 为 0），API 语义不变。
 * <p>
 * 并发调度与缓存由主应用负责：主应用按实例维度做 TTL 缓存（默认 15s），
 * 列表场景并发调用并施加整体查询预算（默认 3s）。实现方只管单实例查询，
 * 无需自行缓存；查询慢时可能被预算截断（结果作废，本次降级）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface InstanceInfoProvider {

    /**
     * 查询实例动态信息。
     *
     * @param instanceId 实例 ID
     * @return 动态信息；返回 null 表示本次不可知（主应用降级默认值，不落库）
     */
    InstanceDynamicInfo getInstanceInfo(long instanceId);
}

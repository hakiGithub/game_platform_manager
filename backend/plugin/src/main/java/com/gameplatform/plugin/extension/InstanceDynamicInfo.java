package com.gameplatform.plugin.extension;

import java.util.Map;

/**
 * 实例动态信息（ADR-0017）。
 * <p>
 * {@code playerCount} 与 {@code maxPlayerCount} 是主应用消费的类型化契约字段
 * （当前玩家数 / 人数上限，maxPlayerCount 允许为 null 表示该游戏无法提供）；
 * {@code extras} 是开放扩展袋，主应用不解释其内容，仅透传到实例详情 VO 供
 * 前端/插件页面自取。字段演进路径：extras 透传先行，主应用按需升级为类型化字段。
 *
 * @param playerCount    当前玩家数；null 表示本次不可知
 * @param maxPlayerCount 人数上限；null 表示无法提供（无降级值，展示侧自行处理）
 * @param extras         扩展信息；null 或空表示无
 * @author GamePlatform
 * @version 1.1.0
 */
public record InstanceDynamicInfo(Integer playerCount, Integer maxPlayerCount, Map<String, Object> extras) {

    /**
     * 仅玩家数的便捷构造（人数上限未知）。
     */
    public static InstanceDynamicInfo ofPlayerCount(Integer playerCount) {
        return new InstanceDynamicInfo(playerCount, null, Map.of());
    }

    /**
     * 当前/上限玩家数的便捷构造。
     */
    public static InstanceDynamicInfo ofPlayers(Integer playerCount, Integer maxPlayerCount) {
        return new InstanceDynamicInfo(playerCount, maxPlayerCount, Map.of());
    }
}

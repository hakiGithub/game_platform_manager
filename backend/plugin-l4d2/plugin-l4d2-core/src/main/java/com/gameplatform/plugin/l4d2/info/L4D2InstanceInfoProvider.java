package com.gameplatform.plugin.l4d2.info;

import com.gameplatform.plugin.extension.InstanceDynamicInfo;
import com.gameplatform.plugin.extension.InstanceInfoProvider;
import com.gameplatform.plugin.l4d2.service.L4D2RconService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * L4D2 实例信息提供者（ADR-0017 首个实现）。
 * <p>
 * 经宿主 RCON 执行 {@code status}，复用 {@link L4D2RconService} 的人数解析取当前玩家数。
 * 查询异常（RuntimeException）由主应用捕获降级：不落库、保留上次值。
 */
@Slf4j
@Component
public class L4D2InstanceInfoProvider implements InstanceInfoProvider {

    private final L4D2RconService rconService;

    public L4D2InstanceInfoProvider(L4D2RconService rconService) {
        this.rconService = rconService;
    }

    @Override
    public InstanceDynamicInfo getInstanceInfo(long instanceId) {
        String statusText = rconService.executeCommand(instanceId, "status");
        Integer playerCount = rconService.extractPlayerCount(statusText);
        if (playerCount == null) {
            return null;
        }
        return InstanceDynamicInfo.ofPlayerCount(playerCount);
    }
}

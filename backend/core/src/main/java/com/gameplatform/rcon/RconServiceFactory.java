package com.gameplatform.rcon;

import com.gameplatform.plugin.service.RconService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * RCON 宿主能力工厂（ADR-0016）。
 * <p>
 * 为插件生成绑定 pluginId 的 {@link RconService}：调用方审计标识由服务端写入，
 * 插件无法伪造或省略来源。由 PluginSpringContextFactory 注册进插件子容器。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Service
@RequiredArgsConstructor
public class RconServiceFactory {

    private final RconExecutor rconExecutor;

    /**
     * 创建绑定指定插件 ID 的 RCON 服务实例。
     *
     * @param pluginId 插件 ID（作为审计调用方标识）
     */
    public RconService forPlugin(String pluginId) {
        return new RconService() {
            @Override
            public String executeCommand(long instanceId, String command) {
                return rconExecutor.execute(instanceId, command, null, pluginId);
            }

            @Override
            public String executeCommand(long instanceId, String command, Duration timeout) {
                return rconExecutor.execute(instanceId, command, timeout, pluginId);
            }

            @Override
            public boolean testConnection(long instanceId) {
                return rconExecutor.testConnection(instanceId, pluginId);
            }
        };
    }
}

package com.gameplatform.rcon;

import com.gameplatform.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * RCON 命令执行入口（ADR-0016，主应用传输层）。
 * <p>
 * 统一收口命令执行：委托 {@link RconConnectionManager} 完成连接借用与协议交互，
 * 并以独立审计 logger 记录调用方（插件 ID 或 "main-app"）、实例与命令。
 * 调用方标识由服务端绑定，调用方无法伪造或省略。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RconExecutor {

    /** 审计专用 logger，供日志采集侧按名称单独归档 */
    private static final org.slf4j.Logger AUDIT = org.slf4j.LoggerFactory.getLogger("RCON_AUDIT");

    public static final String MAIN_APP_CALLER = "main-app";

    private final RconConnectionManager connectionManager;

    /**
     * 执行 RCON 命令并写审计日志。
     *
     * @param instanceId 实例 ID
     * @param command    命令
     * @param timeout    读超时覆盖；null 用默认
     * @param caller     调用方标识（插件 ID 或 main-app）
     */
    public String execute(long instanceId, String command, Duration timeout, String caller) {
        long startTime = System.currentTimeMillis();
        try {
            String output = connectionManager.withConnection(instanceId, timeout,
                    (in, out) -> RconProtocol.sendCommand(in, out, command));
            AUDIT.info("caller={}, instanceId={}, command={}, elapsedMs={}, success={}",
                    caller, instanceId, command, System.currentTimeMillis() - startTime, true);
            return output;
        } catch (RuntimeException e) {
            // 失败调用同样落审计（ADR-0016 决策 6：审计覆盖成功与失败）
            AUDIT.info("caller={}, instanceId={}, command={}, elapsedMs={}, success={}, reason={}",
                    caller, instanceId, command, System.currentTimeMillis() - startTime, false, e.getMessage());
            throw e;
        }
    }

    /**
     * 测试 RCON 连通性（建连 + 认证），并写审计日志。
     */
    public boolean testConnection(long instanceId, String caller) {
        try {
            connectionManager.withConnection(instanceId, (in, out) -> {
                RconProtocol.sendCommand(in, out, "");
                return true;
            });
            AUDIT.info("caller={}, instanceId={}, action=testConnection, success=true", caller, instanceId);
            return true;
        } catch (BusinessException e) {
            AUDIT.info("caller={}, instanceId={}, action=testConnection, success=false, reason={}",
                    caller, instanceId, e.getMessage());
            return false;
        }
    }
}

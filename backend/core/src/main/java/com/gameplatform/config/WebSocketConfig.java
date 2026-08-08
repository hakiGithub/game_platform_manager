package com.gameplatform.config;

import com.gameplatform.websocket.DockerAttachWebSocketHandler;
import com.gameplatform.websocket.DockerExecWebSocketHandler;
import com.gameplatform.websocket.DockerLogsWebSocketHandler;
import com.gameplatform.websocket.InstanceConsoleWebSocketHandler;
import com.gameplatform.websocket.InstanceLogWebSocketHandler;
import com.gameplatform.websocket.SshWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket配置类
 * 配置原生WebSocket端点
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final SshWebSocketHandler sshWebSocketHandler;
    private final InstanceLogWebSocketHandler instanceLogWebSocketHandler;
    private final InstanceConsoleWebSocketHandler instanceConsoleWebSocketHandler;
    private final DockerExecWebSocketHandler dockerExecWebSocketHandler;
    private final DockerAttachWebSocketHandler dockerAttachWebSocketHandler;
    private final DockerLogsWebSocketHandler dockerLogsWebSocketHandler;

    /**
     * 注册WebSocket处理器
     * 客户端通过该端点建立WebSocket连接
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // SSH终端端点
        // 支持路径参数: /ws/ssh/{hostId}
        registry.addHandler(sshWebSocketHandler, "/ws/ssh/*")
                .setAllowedOriginPatterns("*");

        // 实例日志端点
        // 支持路径参数: /ws/instance/{instanceId}/logs
        registry.addHandler(instanceLogWebSocketHandler, "/ws/instance/*/logs")
                .setAllowedOriginPatterns("*");

        // 实例控制台端点
        // 支持路径参数: /ws/instance/{instanceId}/console
        registry.addHandler(instanceConsoleWebSocketHandler, "/ws/instance/*/console")
                .setAllowedOriginPatterns("*");

        // Docker Exec 终端端点
        // 支持路径参数: /ws/docker/{hostId}/containers/{containerId}/exec
        registry.addHandler(dockerExecWebSocketHandler, "/ws/docker/*/containers/*/exec")
                .setAllowedOriginPatterns("*");

        // Docker Attach 终端端点
        // 支持路径参数: /ws/docker/{hostId}/containers/{containerId}/attach
        registry.addHandler(dockerAttachWebSocketHandler, "/ws/docker/*/containers/*/attach")
                .setAllowedOriginPatterns("*");

        // Docker Logs 实时日志端点
        // 支持路径参数: /ws/docker/{hostId}/containers/{containerId}/logs 或 /ws/docker/{hostId}/containers/{containerId}/logs/{tail}
        registry.addHandler(dockerLogsWebSocketHandler, "/ws/docker/*/containers/*/logs", "/ws/docker/*/containers/*/logs/*")
                .setAllowedOriginPatterns("*");
    }
}

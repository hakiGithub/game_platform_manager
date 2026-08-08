package com.gameplatform.config;

import com.gameplatform.websocket.InstanceConsoleWebSocketHandler;
import com.gameplatform.websocket.InstanceLogWebSocketHandler;
import com.gameplatform.websocket.SshWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket处理器配置
 * 注册原生WebSocket处理器
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Configuration
@EnableWebSocket
public class WebSocketHandlerConfig implements WebSocketConfigurer {

    private final SshWebSocketHandler sshWebSocketHandler;
    private final InstanceLogWebSocketHandler instanceLogWebSocketHandler;
    private final InstanceConsoleWebSocketHandler instanceConsoleWebSocketHandler;

    public WebSocketHandlerConfig(SshWebSocketHandler sshWebSocketHandler,
                                  InstanceLogWebSocketHandler instanceLogWebSocketHandler,
                                  InstanceConsoleWebSocketHandler instanceConsoleWebSocketHandler) {
        this.sshWebSocketHandler = sshWebSocketHandler;
        this.instanceLogWebSocketHandler = instanceLogWebSocketHandler;
        this.instanceConsoleWebSocketHandler = instanceConsoleWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 注册SSH终端WebSocket处理器
        registry.addHandler(sshWebSocketHandler, "/ws/ssh/raw")
                .setAllowedOrigins("*");

        // 注册实例日志WebSocket处理器
        registry.addHandler(instanceLogWebSocketHandler, "/ws/instance-log/raw")
                .setAllowedOrigins("*");

        // 注册实例控制台WebSocket处理器
        registry.addHandler(instanceConsoleWebSocketHandler, "/ws/instance-console/raw")
                .setAllowedOrigins("*");
    }
}

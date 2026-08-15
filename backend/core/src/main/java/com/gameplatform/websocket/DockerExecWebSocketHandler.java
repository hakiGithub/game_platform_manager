package com.gameplatform.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.channel.ChannelExec;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Docker Exec WebSocket处理器
 * 用于在容器内执行命令的终端
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
public class DockerExecWebSocketHandler extends TextWebSocketHandler {

    private final HostMapper hostMapper;
    private final DockerExecConnector dockerExecConnector;
    private final ConnectionLifecycle lifecycle;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, DockerExecSession> sessions = new ConcurrentHashMap<>();

    public DockerExecWebSocketHandler(HostMapper hostMapper, DockerExecConnector dockerExecConnector,
                                      ConnectionLifecycle lifecycle) {
        this.hostMapper = hostMapper;
        this.dockerExecConnector = dockerExecConnector;
        this.lifecycle = lifecycle;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("Docker Exec WebSocket连接建立: {}", session.getId());

        // 从URL路径中获取参数: /ws/docker/{hostId}/containers/{containerId}/exec
        PathParams params = extractPathParams(session.getUri().getPath());

        if (params.hostId == null || params.containerId == null) {
            sendErrorMessage(session, "缺少必要参数");
            session.close();
            return;
        }

        // 获取主机信息
        Host host = hostMapper.selectById(params.hostId);
        if (host == null) {
            sendErrorMessage(session, "主机不存在");
            session.close();
            return;
        }

        // 建立SSH连接并启动docker exec
        try {
            DockerExecSession execSession = createExecSession(host, params.containerId, session);
            sessions.put(session.getId(), execSession);
            lifecycle.register(session.getId(), execSession);

            sendMessage(session, new WsMessage("connected", "Docker Exec 连接成功"));
            log.info("Docker Exec 连接建立成功: sessionId={}, container={}", session.getId(), params.containerId);
        } catch (Exception e) {
            log.error("Docker Exec 连接建立失败: {}", e.getMessage(), e);
            sendErrorMessage(session, "连接失败: " + e.getMessage());
            session.close();
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();

        if ("ping".equals(payload)) {
            sendMessage(session, new WsMessage("pong", ""));
            return;
        }

        DockerExecSession execSession = sessions.get(session.getId());
        if (execSession == null || !execSession.isConnected()) {
            sendErrorMessage(session, "连接未建立");
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> messageMap = objectMapper.readValue(payload, java.util.Map.class);
            String type = (String) messageMap.get("type");

            switch (type) {
                case "command":
                    String command = (String) messageMap.get("data");
                    if (command != null) {
                        execSession.sendCommand(command);
                    }
                    break;

                case "resize":
                    int cols = ((Number) messageMap.getOrDefault("cols", 80)).intValue();
                    int rows = ((Number) messageMap.getOrDefault("rows", 24)).intValue();
                    execSession.resizeTerminal(cols, rows);
                    break;

                case "ping":
                    sendMessage(session, new WsMessage("pong", ""));
                    break;

                default:
                    log.warn("未知的消息类型: {}", type);
            }
        } catch (Exception e) {
            log.error("处理消息失败: {}", e.getMessage(), e);
            sendErrorMessage(session, "处理消息失败: " + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("Docker Exec WebSocket连接关闭: {}, status={}", session.getId(), status);

        sessions.remove(session.getId());
        lifecycle.unregister(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("Docker Exec WebSocket传输错误: {}", session.getId(), exception);

        sessions.remove(session.getId());
        lifecycle.unregister(session.getId());
    }

    /**
     * 创建Docker Exec会话（建连+认证统一走 DeploymentAccess）
     */
    private DockerExecSession createExecSession(Host host, String containerId, WebSocketSession webSocketSession) throws Exception {
        DockerExecConnector.OpenedExec opened = dockerExecConnector.openInteractive(host, containerId);
        return new DockerExecSession(opened.ssh(), opened.channel(), webSocketSession, lifecycle.executor());
    }

    /**
     * 从URL路径提取参数
     */
    private PathParams extractPathParams(String path) {
        PathParams params = new PathParams();
        if (path == null) return params;

        if (path.startsWith("/api")) {
            path = path.substring(4);
        }

        // 格式: /ws/docker/{hostId}/containers/{containerId}/exec
        String[] parts = path.split("/");
        if (parts.length >= 6 && "ws".equals(parts[1]) && "docker".equals(parts[2])) {
            try {
                params.hostId = Long.parseLong(parts[3]);
            } catch (NumberFormatException e) {
                // ignore
            }
            
            // 查找 containers 后面的 containerId
            for (int i = 0; i < parts.length - 1; i++) {
                if ("containers".equals(parts[i])) {
                    params.containerId = parts[i + 1];
                    break;
                }
            }
        }

        return params;
    }

    private void sendMessage(WebSocketSession session, WsMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception e) {
            log.error("发送消息失败: {}", e.getMessage());
        }
    }

    private void sendErrorMessage(WebSocketSession session, String error) {
        sendMessage(session, new WsMessage("error", error));
    }

    @Data
    public static class WsMessage {
        private String type;
        private String data;

        public WsMessage() {}

        public WsMessage(String type, String data) {
            this.type = type;
            this.data = data;
        }
    }

    private static class PathParams {
        Long hostId;
        String containerId;
    }

    /**
     * Docker Exec 会话封装
     */
    private static class DockerExecSession implements AutoCloseable {
        private final DeploymentAccess.SshConnection ssh;
        private final ChannelExec channel;
        private final WebSocketSession webSocketSession;
        private final ExecutorService executorService;
        private final AtomicBoolean connected = new AtomicBoolean(true);
        private Future<?> outputReader;

        public DockerExecSession(DeploymentAccess.SshConnection ssh, ChannelExec channel,
                                WebSocketSession webSocketSession, ExecutorService executorService) {
            this.ssh = ssh;
            this.channel = channel;
            this.webSocketSession = webSocketSession;
            this.executorService = executorService;

            startOutputReader();
        }

        private void startOutputReader() {
            outputReader = executorService.submit(() -> {
                try (InputStream is = channel.getInvertedOut();
                     InputStream err = channel.getInvertedErr()) {
                    
                    byte[] buffer = new byte[1024];
                    int len;
                    
                    // 读取标准输出和错误输出
                    while (connected.get()) {
                        if (is.available() > 0) {
                            len = is.read(buffer);
                            if (len > 0) {
                                String output = new String(buffer, 0, len, StandardCharsets.UTF_8);
                                sendOutput(output);
                            }
                        }
                        
                        if (err.available() > 0) {
                            len = err.read(buffer);
                            if (len > 0) {
                                String output = new String(buffer, 0, len, StandardCharsets.UTF_8);
                                sendOutput(output);
                            }
                        }
                        
                        Thread.sleep(10);
                    }
                } catch (Exception e) {
                    if (connected.get()) {
                        log.error("读取输出失败: {}", e.getMessage());
                    }
                }
            });
        }

        public void sendCommand(String command) throws IOException {
            if (!connected.get()) {
                throw new IOException("连接已关闭");
            }

            OutputStream os = channel.getInvertedIn();
            os.write(command.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        public void resizeTerminal(int cols, int rows) {
            log.debug("调整终端大小: cols={}, rows={}", cols, rows);
        }

        private void sendOutput(String output) {
            try {
                WsMessage message = new WsMessage("output", output);
                String json = new ObjectMapper().writeValueAsString(message);
                synchronized (webSocketSession) {
                    if (webSocketSession.isOpen()) {
                        webSocketSession.sendMessage(new TextMessage(json));
                    }
                }
            } catch (Exception e) {
                log.error("发送输出失败: {}", e.getMessage());
            }
        }

        public boolean isConnected() {
            return connected.get() && ssh.session().isOpen() && channel.isOpen();
        }

        public void close() {
            connected.set(false);

            if (outputReader != null) {
                outputReader.cancel(true);
            }

            try { channel.close(); } catch (Exception e) { log.debug("关闭通道失败: {}", e.getMessage()); }

            // 释放会话与客户端
            ssh.close();

            log.info("Docker Exec 会话已关闭");
        }
    }
}

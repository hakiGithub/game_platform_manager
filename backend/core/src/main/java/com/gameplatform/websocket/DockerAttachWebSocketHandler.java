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
 * Docker Attach WebSocket处理器
 * 用于连接到容器的标准输入输出
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
public class DockerAttachWebSocketHandler extends TextWebSocketHandler {

    private final HostMapper hostMapper;
    private final DeploymentAccess deployAccess;
    private final ConnectionLifecycle lifecycle;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, DockerAttachSession> sessions = new ConcurrentHashMap<>();

    public DockerAttachWebSocketHandler(HostMapper hostMapper, DeploymentAccess deployAccess,
                                        ConnectionLifecycle lifecycle) {
        this.hostMapper = hostMapper;
        this.deployAccess = deployAccess;
        this.lifecycle = lifecycle;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("Docker Attach WebSocket连接建立: {}", session.getId());

        PathParams params = extractPathParams(session.getUri().getPath());

        if (params.hostId == null || params.containerId == null) {
            sendErrorMessage(session, "缺少必要参数");
            session.close();
            return;
        }

        Host host = hostMapper.selectById(params.hostId);
        if (host == null) {
            sendErrorMessage(session, "主机不存在");
            session.close();
            return;
        }

        try {
            DockerAttachSession attachSession = createAttachSession(host, params.containerId, session);
            sessions.put(session.getId(), attachSession);
            lifecycle.register(session.getId(), attachSession);

            sendMessage(session, new WsMessage("connected", "Docker Attach 连接成功"));
            log.info("Docker Attach 连接建立成功: sessionId={}, container={}", session.getId(), params.containerId);
        } catch (Exception e) {
            log.error("Docker Attach 连接建立失败: {}", e.getMessage(), e);
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

        DockerAttachSession attachSession = sessions.get(session.getId());
        if (attachSession == null || !attachSession.isConnected()) {
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
                        attachSession.sendInput(command);
                    }
                    break;

                case "ping":
                    sendMessage(session, new WsMessage("pong", ""));
                    break;

                default:
                    log.warn("未知的消息类型: {}", type);
            }
        } catch (Exception e) {
            log.error("处理消息失败: {}", e.getMessage(), e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("Docker Attach WebSocket连接关闭: {}, status={}", session.getId(), status);

        sessions.remove(session.getId());
        lifecycle.unregister(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("Docker Attach WebSocket传输错误: {}", session.getId(), exception);

        sessions.remove(session.getId());
        lifecycle.unregister(session.getId());
    }

    private DockerAttachSession createAttachSession(Host host, String containerId, WebSocketSession webSocketSession) throws Exception {
        DeploymentAccess.SshConnection ssh = deployAccess.connect(host);

        // docker attach 命令
        String attachCommand = String.format("docker attach %s", containerId);

        ChannelExec channel = ssh.session().createExecChannel(attachCommand);
        channel.open().verify(10000, TimeUnit.MILLISECONDS);

        return new DockerAttachSession(ssh, channel, webSocketSession, lifecycle.executor());
    }

    private PathParams extractPathParams(String path) {
        PathParams params = new PathParams();
        if (path == null) return params;

        if (path.startsWith("/api")) {
            path = path.substring(4);
        }

        // 格式: /ws/docker/{hostId}/containers/{containerId}/attach
        String[] parts = path.split("/");
        if (parts.length >= 6 && "ws".equals(parts[1]) && "docker".equals(parts[2])) {
            try {
                params.hostId = Long.parseLong(parts[3]);
            } catch (NumberFormatException e) {
                // ignore
            }
            
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

    private static class DockerAttachSession implements AutoCloseable {
        private final DeploymentAccess.SshConnection ssh;
        private final ChannelExec channel;
        private final WebSocketSession webSocketSession;
        private final ExecutorService executorService;
        private final AtomicBoolean connected = new AtomicBoolean(true);
        private Future<?> outputReader;

        public DockerAttachSession(DeploymentAccess.SshConnection ssh, ChannelExec channel,
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

        public void sendInput(String input) throws IOException {
            if (!connected.get()) {
                throw new IOException("连接已关闭");
            }

            OutputStream os = channel.getInvertedIn();
            os.write(input.getBytes(StandardCharsets.UTF_8));
            os.flush();
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

            log.info("Docker Attach 会话已关闭");
        }
    }
}

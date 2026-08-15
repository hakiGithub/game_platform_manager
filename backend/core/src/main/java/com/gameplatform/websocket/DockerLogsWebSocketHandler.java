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
 * Docker Logs WebSocket处理器
 * 用于实时推送容器日志
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
public class DockerLogsWebSocketHandler extends TextWebSocketHandler {

    private final HostMapper hostMapper;
    private final DeploymentAccess deployAccess;
    private final ConnectionLifecycle lifecycle;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, DockerLogsSession> sessions = new ConcurrentHashMap<>();

    public DockerLogsWebSocketHandler(HostMapper hostMapper, DeploymentAccess deployAccess,
                                      ConnectionLifecycle lifecycle) {
        this.hostMapper = hostMapper;
        this.deployAccess = deployAccess;
        this.lifecycle = lifecycle;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("Docker Logs WebSocket连接建立: {}", session.getId());

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
            DockerLogsSession logsSession = createLogsSession(host, params.containerId, params.tail, session);
            sessions.put(session.getId(), logsSession);
            lifecycle.register(session.getId(), logsSession);

            sendMessage(session, new WsMessage("connected", "Docker Logs 连接成功"));
            log.info("Docker Logs 连接建立成功: sessionId={}, container={}", session.getId(), params.containerId);
        } catch (Exception e) {
            log.error("Docker Logs 连接建立失败: {}", e.getMessage(), e);
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

        DockerLogsSession logsSession = sessions.get(session.getId());
        if (logsSession == null) {
            sendErrorMessage(session, "连接未建立");
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> messageMap = objectMapper.readValue(payload, java.util.Map.class);
            String type = (String) messageMap.get("type");

            if ("ping".equals(type)) {
                sendMessage(session, new WsMessage("pong", ""));
            }
        } catch (Exception e) {
            log.error("处理消息失败: {}", e.getMessage(), e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("Docker Logs WebSocket连接关闭: {}, status={}", session.getId(), status);

        sessions.remove(session.getId());
        lifecycle.unregister(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("Docker Logs WebSocket传输错误: {}", session.getId(), exception);

        sessions.remove(session.getId());
        lifecycle.unregister(session.getId());
    }

    private DockerLogsSession createLogsSession(Host host, String containerId, Integer tail, WebSocketSession webSocketSession) throws Exception {
        DeploymentAccess.SshConnection ssh = deployAccess.connect(host);

        // docker logs 命令，使用 -f 参数实时跟踪日志
        StringBuilder logsCommand = new StringBuilder("docker logs -f");

        if (tail != null && tail > 0) {
            logsCommand.append(" --tail ").append(tail);
        } else {
            logsCommand.append(" --tail 100"); // 默认显示最近100行
        }

        logsCommand.append(" --timestamps");
        logsCommand.append(" ").append(containerId);

        ChannelExec channel = ssh.session().createExecChannel(logsCommand.toString());
        channel.open().verify(10000, TimeUnit.MILLISECONDS);

        return new DockerLogsSession(ssh, channel, webSocketSession, lifecycle.executor());
    }

    private PathParams extractPathParams(String path) {
        PathParams params = new PathParams();
        if (path == null) return params;

        if (path.startsWith("/api")) {
            path = path.substring(4);
        }

        // 格式: /ws/docker/{hostId}/containers/{containerId}/logs 或
        //       /ws/docker/{hostId}/containers/{containerId}/logs/{tail}
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
            
            // 解析 tail 参数
            if (parts.length >= 8) {
                try {
                    params.tail = Integer.parseInt(parts[7]);
                } catch (NumberFormatException e) {
                    // ignore
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
        private String timestamp;

        public WsMessage() {}

        public WsMessage(String type, String data) {
            this.type = type;
            this.data = data;
        }
        
        public WsMessage(String type, String data, String timestamp) {
            this.type = type;
            this.data = data;
            this.timestamp = timestamp;
        }
    }

    private static class PathParams {
        Long hostId;
        String containerId;
        Integer tail;
    }

    private static class DockerLogsSession implements AutoCloseable {
        private final DeploymentAccess.SshConnection ssh;
        private final ChannelExec channel;
        private final WebSocketSession webSocketSession;
        private final ExecutorService executorService;
        private final AtomicBoolean connected = new AtomicBoolean(true);
        private Future<?> outputReader;

        public DockerLogsSession(DeploymentAccess.SshConnection ssh, ChannelExec channel,
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
                     InputStream err = channel.getInvertedErr();
                     BufferedReader stdoutReader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                     BufferedReader stderrReader = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))) {
                    
                    String line;
                    
                    // 读取标准输出
                    while (connected.get() && (line = stdoutReader.readLine()) != null) {
                        sendLogLine(line, "stdout");
                    }
                    
                    // 读取错误输出
                    while (connected.get() && (line = stderrReader.readLine()) != null) {
                        sendLogLine(line, "stderr");
                    }
                    
                } catch (Exception e) {
                    if (connected.get()) {
                        log.error("读取日志失败: {}", e.getMessage());
                        sendLogLine("日志流结束: " + e.getMessage(), "system");
                    }
                }
            });
        }

        private void sendLogLine(String line, String stream) {
            try {
                // 解析日志行中的时间戳（如果存在）
                String timestamp = null;
                String content = line;
                
                // Docker logs --timestamps 格式: 2024-03-24T10:30:00.123456789Z log content
                if (line.length() > 30 && line.charAt(4) == '-' && line.charAt(7) == '-') {
                    int spaceIndex = line.indexOf(' ');
                    if (spaceIndex > 0) {
                        timestamp = line.substring(0, spaceIndex);
                        content = line.substring(spaceIndex + 1);
                    }
                }
                
                WsMessage message = new WsMessage("log", content, timestamp);
                String json = new ObjectMapper().writeValueAsString(message);
                synchronized (webSocketSession) {
                    if (webSocketSession.isOpen()) {
                        webSocketSession.sendMessage(new TextMessage(json));
                    }
                }
            } catch (Exception e) {
                log.error("发送日志失败: {}", e.getMessage());
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

            log.info("Docker Logs 会话已关闭");
        }
    }
}

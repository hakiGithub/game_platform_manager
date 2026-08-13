package com.gameplatform.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.channel.ChannelShell;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Web SSH终端WebSocket处理器
 * 处理WebSocket连接，建立SSH会话，转发终端输入输出
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
public class SshWebSocketHandler extends TextWebSocketHandler {

    private final HostMapper hostMapper;
    private final DeploymentAccess deployAccess;
    private final ConnectionLifecycle lifecycle;
    private final ObjectMapper objectMapper;

    // 存储会话与SSH连接的映射
    private final ConcurrentHashMap<String, SshConnection> sshConnections = new ConcurrentHashMap<>();

    public SshWebSocketHandler(HostMapper hostMapper, DeploymentAccess deployAccess,
                               ConnectionLifecycle lifecycle) {
        this.hostMapper = hostMapper;
        this.deployAccess = deployAccess;
        this.lifecycle = lifecycle;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("WebSocket连接建立: {}", session.getId());

        // 从URL路径中获取主机ID (格式: /ws/ssh/{hostId})
        Long hostId = extractHostIdFromPath(session.getUri().getPath());

        if (hostId == null) {
            sendErrorMessage(session, "未提供主机ID");
            session.close();
            return;
        }

        // 获取主机信息
        Host host = hostMapper.selectById(hostId);
        if (host == null) {
            sendErrorMessage(session, "主机不存在");
            session.close();
            return;
        }

        // 建立SSH连接
        try {
            SshConnection connection = createSshConnection(host, session);
            sshConnections.put(session.getId(), connection);
            lifecycle.register(session.getId(), connection);

            // 发送连接成功消息
            sendMessage(session, new WsMessage("connected", "SSH连接成功"));

            log.info("SSH连接建立成功: sessionId={}, host={}", session.getId(), host.getHostName());
        } catch (Exception e) {
            log.error("SSH连接建立失败: {}", e.getMessage(), e);
            sendErrorMessage(session, "SSH连接失败: " + e.getMessage());
            session.close();
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("收到WebSocket消息: {}", payload);

        // 处理纯文本 ping 消息
        if ("ping".equals(payload)) {
            sendMessage(session, new WsMessage("pong", ""));
            return;
        }

        SshConnection connection = sshConnections.get(session.getId());
        if (connection == null || !connection.isConnected()) {
            sendErrorMessage(session, "SSH连接未建立");
            return;
        }

        try {
            // 解析消息为Map，以支持灵活的字段
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> messageMap = objectMapper.readValue(payload, java.util.Map.class);
            String type = (String) messageMap.get("type");

            switch (type) {
                case "command":
                    // 发送命令到SSH
                    String command = (String) messageMap.get("data");
                    if (command != null) {
                        connection.sendCommand(command);
                    }
                    break;

                case "resize":
                    // 调整终端大小 - 支持直接从字段读取或从data字段解析
                    int cols = 80;
                    int rows = 24;
                    
                    // 优先直接从字段读取
                    if (messageMap.containsKey("cols")) {
                        cols = ((Number) messageMap.get("cols")).intValue();
                    }
                    if (messageMap.containsKey("rows")) {
                        rows = ((Number) messageMap.get("rows")).intValue();
                    }
                    
                    // 如果没有直接字段，尝试从data字段解析
                    if (cols == 80 && rows == 24 && messageMap.containsKey("data")) {
                        String data = (String) messageMap.get("data");
                        if (data != null && !data.isEmpty()) {
                            @SuppressWarnings("unchecked")
                            java.util.Map<String, Integer> size = objectMapper.readValue(data, java.util.Map.class);
                            cols = size.getOrDefault("cols", 80);
                            rows = size.getOrDefault("rows", 24);
                        }
                    }
                    
                    connection.resizeTerminal(cols, rows);
                    break;

                case "ping":
                    // 心跳响应
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
        log.info("WebSocket连接关闭: {}, status={}", session.getId(), status);

        sshConnections.remove(session.getId());
        lifecycle.unregister(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket传输错误: {}", session.getId(), exception);

        sshConnections.remove(session.getId());
        lifecycle.unregister(session.getId());
    }

    /**
     * 创建SSH连接（建连+认证统一走 DeploymentAccess）
     */
    private SshConnection createSshConnection(Host host, WebSocketSession webSocketSession) throws Exception {
        DeploymentAccess.SshConnection ssh = deployAccess.connect(host);

        // 创建Shell通道
        ChannelShell channel = ssh.session().createShellChannel();
        channel.open().verify(10000, TimeUnit.MILLISECONDS);

        return new SshConnection(ssh, channel, webSocketSession, lifecycle.executor());
    }

    /**
     * 从URL路径中提取主机ID
     * 格式: /ws/ssh/{hostId} 或 /api/ws/ssh/{hostId}
     */
    private Long extractHostIdFromPath(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }

        // 移除context-path前缀（如果有）
        if (path.startsWith("/api")) {
            path = path.substring(4);
        }

        // 格式: /ws/ssh/{hostId}
        String[] parts = path.split("/");
        if (parts.length >= 4 && "ws".equals(parts[1]) && "ssh".equals(parts[2])) {
            try {
                return Long.parseLong(parts[3]);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        return null;
    }

    /**
     * 发送消息
     */
    private void sendMessage(WebSocketSession session, WsMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.error("发送消息失败: {}", e.getMessage());
        }
    }

    /**
     * 发送错误消息
     */
    private void sendErrorMessage(WebSocketSession session, String error) {
        sendMessage(session, new WsMessage("error", error));
    }

    /**
     * WebSocket消息结构
     */
    @Data
    public static class WsMessage {
        private String type;
        private String data;

        public WsMessage() {
        }

        public WsMessage(String type, String data) {
            this.type = type;
            this.data = data;
        }
    }

    /**
     * SSH连接封装类
     */
    private static class SshConnection implements AutoCloseable {
        private final DeploymentAccess.SshConnection ssh;
        private final ChannelShell channel;
        private final WebSocketSession webSocketSession;
        private final ExecutorService executorService;
        private final AtomicBoolean connected = new AtomicBoolean(true);

        private Future<?> stdoutReader;

        public SshConnection(DeploymentAccess.SshConnection ssh, ChannelShell channel,
                            WebSocketSession webSocketSession, ExecutorService executorService) {
            this.ssh = ssh;
            this.channel = channel;
            this.webSocketSession = webSocketSession;
            this.executorService = executorService;

            // 启动输出读取线程
            startOutputReaders();
        }

        /**
         * 启动输出读取线程
         */
        private void startOutputReaders() {
            // 读取标准输出
            stdoutReader = executorService.submit(() -> {
                try (InputStream is = channel.getInvertedOut()) {
                    byte[] buffer = new byte[1024];
                    int len;
                    while (connected.get() && (len = is.read(buffer)) != -1) {
                        String output = new String(buffer, 0, len, StandardCharsets.UTF_8);
                        sendOutput(output);
                    }
                } catch (IOException e) {
                    if (connected.get()) {
                        log.error("读取标准输出失败: {}", e.getMessage());
                    }
                }
            });
        }

        /**
         * 发送命令
         */
        public void sendCommand(String command) throws IOException {
            if (!connected.get()) {
                throw new IOException("连接已关闭");
            }

            OutputStream os = channel.getInvertedIn();
            os.write(command.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        /**
         * 调整终端大小
         */
        public void resizeTerminal(int cols, int rows) {
            // Apache MINA SSHD 2.x 不直接支持动态调整大小
            // 需要在创建channel时设置，或者使用pty
            log.debug("调整终端大小: cols={}, rows={}", cols, rows);
        }

        /**
         * 发送输出到WebSocket
         */
        private void sendOutput(String output) {
            try {
                WsMessage message = new WsMessage("output", output);
                String json = new ObjectMapper().writeValueAsString(message);
                webSocketSession.sendMessage(new TextMessage(json));
            } catch (Exception e) {
                log.error("发送输出失败: {}", e.getMessage());
            }
        }

        /**
         * 是否已连接
         */
        public boolean isConnected() {
            return connected.get() && ssh.session().isOpen() && channel.isOpen();
        }

        /**
         * 关闭连接
         */
        public void close() {
            connected.set(false);

            // 取消读取线程
            if (stdoutReader != null) {
                stdoutReader.cancel(true);
            }

            // 关闭通道
            try {
                channel.close();
            } catch (Exception e) {
                log.debug("关闭通道失败: {}", e.getMessage());
            }

            // 释放会话与客户端
            ssh.close();

            log.info("SSH连接已关闭");
        }
    }
}

package com.gameplatform.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.util.AesUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.session.ClientSession;
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
    private final ObjectMapper objectMapper;

    // 存储会话与SSH连接的映射
    private final ConcurrentHashMap<String, SshConnection> sshConnections = new ConcurrentHashMap<>();

    // 线程池用于处理SSH输入输出
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public SshWebSocketHandler(HostMapper hostMapper) {
        this.hostMapper = hostMapper;
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

        SshConnection connection = sshConnections.remove(session.getId());
        if (connection != null) {
            connection.close();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket传输错误: {}", session.getId(), exception);

        SshConnection connection = sshConnections.remove(session.getId());
        if (connection != null) {
            connection.close();
        }
    }

    /**
     * 创建SSH连接
     */
    private SshConnection createSshConnection(Host host, WebSocketSession webSocketSession) throws Exception {
        SshClient client = SshClient.setUpDefaultClient();
        client.start();

        int port = host.getSshPort() != null ? host.getSshPort() : 22;
        ClientSession session = client.connect(host.getSshUser(), host.getIpAddress(), port)
                .verify(10000, TimeUnit.MILLISECONDS)
                .getSession();

        // 认证 - 优先使用私钥，其次使用密码
        boolean authenticated = false;

        // 1. 尝试私钥认证
        if (host.getSshPrivateKey() != null && !host.getSshPrivateKey().isEmpty()) {
            try {
                String privateKey = AesUtil.decrypt(host.getSshPrivateKey());
                if (privateKey != null && !privateKey.isEmpty()) {
                    // 加载私钥
                    java.security.KeyPair keyPair = loadPrivateKey(privateKey);
                    if (keyPair != null) {
                        session.addPublicKeyIdentity(keyPair);
                        if (session.auth().verify(10000, TimeUnit.MILLISECONDS).isSuccess()) {
                            authenticated = true;
                            log.debug("SSH私钥认证成功: {}", host.getHostName());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("私钥认证失败，尝试密码认证: {}", e.getMessage());
            }
        }

        // 2. 如果私钥认证失败，尝试密码认证
        if (!authenticated && host.getSshPassword() != null && !host.getSshPassword().isEmpty()) {
            try {
                String password = AesUtil.decrypt(host.getSshPassword());
                if (password != null && !password.isEmpty()) {
                    session.addPasswordIdentity(password);
                    if (session.auth().verify(10000, TimeUnit.MILLISECONDS).isSuccess()) {
                        authenticated = true;
                        log.debug("SSH密码认证成功: {}", host.getHostName());
                    }
                }
            } catch (Exception e) {
                log.error("密码认证失败: {}", e.getMessage());
            }
        }

        if (!authenticated) {
            throw new RuntimeException("SSH认证失败：私钥和密码认证均失败");
        }

        // 创建Shell通道
        ChannelShell channel = session.createShellChannel();
        channel.open().verify(10000, TimeUnit.MILLISECONDS);

        return new SshConnection(client, session, channel, webSocketSession, executorService);
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
     * 加载私钥
     * 使用 Apache MINA SSHD 的 BuiltinSecurityProvider
     */
    private java.security.KeyPair loadPrivateKey(String privateKeyContent) {
        try {
            // 使用 Apache MINA SSHD 的 SecurityUtils 加载私钥
            org.apache.sshd.common.config.keys.FilePasswordProvider passwordProvider =
                    org.apache.sshd.common.config.keys.FilePasswordProvider.EMPTY;

            java.io.InputStream keyStream = new java.io.ByteArrayInputStream(
                    privateKeyContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // 使用 SecurityUtils 的静态方法加载密钥对
            java.lang.reflect.Method method = org.apache.sshd.common.util.security.SecurityUtils.class
                    .getMethod("loadKeyPairIdentities", 
                            org.apache.sshd.common.session.SessionContext.class,
                            org.apache.sshd.common.NamedResource.class,
                            java.io.InputStream.class,
                            org.apache.sshd.common.config.keys.FilePasswordProvider.class);

            @SuppressWarnings("unchecked")
            Iterable<java.security.KeyPair> keyPairs = (Iterable<java.security.KeyPair>) method.invoke(
                    null, null, null, keyStream, passwordProvider);

            if (keyPairs != null) {
                for (java.security.KeyPair keyPair : keyPairs) {
                    return keyPair;
                }
            }
        } catch (Exception e) {
            log.error("加载私钥失败: {}", e.getMessage());
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
    private static class SshConnection {
        private final SshClient client;
        private final ClientSession session;
        private final ChannelShell channel;
        private final WebSocketSession webSocketSession;
        private final ExecutorService executorService;
        private final AtomicBoolean connected = new AtomicBoolean(true);

        private Future<?> stdoutReader;

        public SshConnection(SshClient client, ClientSession session, ChannelShell channel,
                            WebSocketSession webSocketSession, ExecutorService executorService) {
            this.client = client;
            this.session = session;
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
            return connected.get() && session.isOpen() && channel.isOpen();
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

            // 关闭通道和会话
            try {
                channel.close();
            } catch (Exception e) {
                log.debug("关闭通道失败: {}", e.getMessage());
            }

            try {
                session.close();
            } catch (Exception e) {
                log.debug("关闭会话失败: {}", e.getMessage());
            }

            try {
                client.stop();
            } catch (Exception e) {
                log.debug("停止客户端失败: {}", e.getMessage());
            }

            log.info("SSH连接已关闭");
        }
    }
}

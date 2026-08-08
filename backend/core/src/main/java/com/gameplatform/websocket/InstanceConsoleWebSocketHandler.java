package com.gameplatform.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameInstanceMapper;
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
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 实例控制台WebSocket处理器
 * 实现实例控制台交互，支持命令发送和输出接收
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
public class InstanceConsoleWebSocketHandler extends TextWebSocketHandler {

    private final GameInstanceMapper instanceMapper;
    private final HostMapper hostMapper;
    private final ObjectMapper objectMapper;

    // 存储会话与控制台连接的映射
    private final ConcurrentHashMap<String, ConsoleConnection> consoleConnections = new ConcurrentHashMap<>();

    // 线程池用于处理控制台输入输出
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public InstanceConsoleWebSocketHandler(GameInstanceMapper instanceMapper, HostMapper hostMapper) {
        this.instanceMapper = instanceMapper;
        this.hostMapper = hostMapper;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("实例控制台WebSocket连接建立: {}", session.getId());

        // 从URL路径中获取实例ID (格式: /ws/instance/{instanceId}/console)
        Long instanceId = extractInstanceIdFromPath(session.getUri().getPath());

        if (instanceId == null) {
            sendErrorMessage(session, "未提供实例ID");
            session.close();
            return;
        }

        // 获取实例信息
        GameInstance instance = instanceMapper.selectById(instanceId);
        if (instance == null) {
            sendErrorMessage(session, "实例不存在");
            session.close();
            return;
        }

        // 获取主机信息
        Host host = hostMapper.selectById(instance.getHostId());
        if (host == null) {
            sendErrorMessage(session, "主机不存在");
            session.close();
            return;
        }

        // 建立控制台连接
        try {
            ConsoleConnection connection = createConsoleConnection(instance, host, session);
            consoleConnections.put(session.getId(), connection);

            // 发送连接成功消息
            sendMessage(session, new WsMessage("connected", "控制台连接成功"));
            sendMessage(session, new WsMessage("info", "实例: " + instance.getInstanceName()));

            log.info("实例控制台连接建立成功: sessionId={}, instanceId={}", session.getId(), instanceId);
        } catch (Exception e) {
            log.error("实例控制台连接建立失败: {}", e.getMessage(), e);
            sendErrorMessage(session, "控制台连接失败: " + e.getMessage());
            session.close();
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("收到WebSocket消息: {}", payload);

        ConsoleConnection connection = consoleConnections.get(session.getId());
        if (connection == null || !connection.isConnected()) {
            sendErrorMessage(session, "控制台连接未建立");
            return;
        }

        try {
            WsMessage wsMessage = objectMapper.readValue(payload, WsMessage.class);

            switch (wsMessage.getType()) {
                case "command":
                    // 发送命令到控制台
                    String command = wsMessage.getData();
                    connection.sendCommand(command);
                    break;

                case "input":
                    // 发送输入字符
                    String input = wsMessage.getData();
                    connection.sendInput(input);
                    break;

                case "resize":
                    // 调整终端大小
                    @SuppressWarnings("unchecked")
                    Map<String, Integer> size = objectMapper.readValue(wsMessage.getData(), Map.class);
                    int cols = size.getOrDefault("cols", 80);
                    int rows = size.getOrDefault("rows", 24);
                    connection.resizeTerminal(cols, rows);
                    break;

                case "ping":
                    // 心跳响应
                    sendMessage(session, new WsMessage("pong", ""));
                    break;

                default:
                    log.warn("未知的消息类型: {}", wsMessage.getType());
            }
        } catch (Exception e) {
            log.error("处理消息失败: {}", e.getMessage(), e);
            sendErrorMessage(session, "处理消息失败: " + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("实例控制台WebSocket连接关闭: {}, status={}", session.getId(), status);

        ConsoleConnection connection = consoleConnections.remove(session.getId());
        if (connection != null) {
            connection.close();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("实例控制台WebSocket传输错误: {}", session.getId(), exception);

        ConsoleConnection connection = consoleConnections.remove(session.getId());
        if (connection != null) {
            connection.close();
        }
    }

    /**
     * 创建控制台连接
     */
    private ConsoleConnection createConsoleConnection(GameInstance instance, Host host,
                                                      WebSocketSession webSocketSession) throws Exception {
        SshClient client = SshClient.setUpDefaultClient();
        client.start();

        int port = host.getSshPort() != null ? host.getSshPort() : 22;
        ClientSession session = client.connect(host.getSshUser(), host.getIpAddress(), port)
                .verify(10000, TimeUnit.MILLISECONDS)
                .getSession();

        // 认证：优先私钥，其次密码
        String privateKey = null;
        if (host.getSshPrivateKey() != null && !host.getSshPrivateKey().isEmpty()) {
            privateKey = AesUtil.decrypt(host.getSshPrivateKey());
        }
        if (privateKey != null && !privateKey.isEmpty()) {
            // 解析私钥并添加为公钥认证凭据
            try {
                java.security.KeyPair keyPair = parsePrivateKey(privateKey);
                if (keyPair != null) {
                    session.addPublicKeyIdentity(keyPair);
                }
            } catch (Exception e) {
                log.warn("私钥解析失败，回退到密码认证: {}", e.getMessage());
            }
        }
        if (host.getSshPassword() != null && !host.getSshPassword().isEmpty()) {
            String password = AesUtil.decrypt(host.getSshPassword());
            if (password != null && !password.isEmpty()) {
                session.addPasswordIdentity(password);
            }
        }

        if (!session.auth().verify(10000, TimeUnit.MILLISECONDS).isSuccess()) {
            throw new RuntimeException("SSH认证失败：用户名=" + host.getSshUser()
                    + "，主机=" + host.getIpAddress() + ":" + port
                    + "，请检查主机配置的密码或私钥");
        }

        // 创建Shell通道
        ChannelShell channel = session.createShellChannel();
        channel.open().verify(10000, TimeUnit.MILLISECONDS);

        // 进入实例安装目录
        String installPath = instance.getInstallPath();
        if (installPath != null && !installPath.isEmpty()) {
            OutputStream stdin = channel.getInvertedIn();
            stdin.write(("cd " + installPath + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        }

        return new ConsoleConnection(client, session, channel, webSocketSession, executorService);
    }

    /**
     * 解析私钥字符串为 KeyPair
     * 复用 SshUtil 的解析逻辑（通过 Apache MINA SSHD 的 KeyPairResourceParser）
     */
    private java.security.KeyPair parsePrivateKey(String privateKey) throws Exception {
        if (privateKey == null || privateKey.isEmpty()) {
            return null;
        }
        org.apache.sshd.common.config.keys.loader.KeyPairResourceParser parser =
                org.apache.sshd.common.config.keys.loader.KeyPairResourceParser.aggregate(
                        org.apache.sshd.common.config.keys.loader.openssh.OpenSSHKeyPairResourceParser.INSTANCE,
                        org.apache.sshd.common.config.keys.loader.pem.PEMResourceParserUtils.PROXY
                );
        org.apache.sshd.common.NamedResource resourceKey = org.apache.sshd.common.NamedResource.ofName("private-key");
        java.util.Collection<java.security.KeyPair> keyPairs = parser.loadKeyPairs(
                null, resourceKey, null, privateKey);
        if (keyPairs == null || keyPairs.isEmpty()) {
            return null;
        }
        return keyPairs.iterator().next();
    }

    /**
     * 从URL路径中提取实例ID
     * 格式: /ws/instance/{instanceId}/console 或 /api/ws/instance/{instanceId}/console
     */
    private Long extractInstanceIdFromPath(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }

        // 移除context-path前缀（如果有）
        if (path.startsWith("/api")) {
            path = path.substring(4);
        }

        // 格式: /ws/instance/{instanceId}/console
        String[] parts = path.split("/");
        if (parts.length >= 5 && "ws".equals(parts[1]) && "instance".equals(parts[2]) && "console".equals(parts[4])) {
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
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json));
            }
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
     * 控制台连接封装类
     */
    private static class ConsoleConnection {
        private final SshClient client;
        private final ClientSession session;
        private final ChannelShell channel;
        private final WebSocketSession webSocketSession;
        private final ExecutorService executorService;
        private final AtomicBoolean connected = new AtomicBoolean(true);

        private Future<?> stdoutReader;

        public ConsoleConnection(SshClient client, ClientSession session, ChannelShell channel,
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
                        sendError("连接已断开");
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
            os.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        /**
         * 发送输入字符
         */
        public void sendInput(String input) throws IOException {
            if (!connected.get()) {
                throw new IOException("连接已关闭");
            }

            OutputStream os = channel.getInvertedIn();
            os.write(input.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        /**
         * 调整终端大小
         */
        public void resizeTerminal(int cols, int rows) {
            log.debug("调整终端大小: cols={}, rows={}", cols, rows);
            // 注意：Apache MINA SSHD 2.x 不直接支持动态调整大小
        }

        /**
         * 发送输出到WebSocket
         */
        private void sendOutput(String output) {
            try {
                WsMessage message = new WsMessage("output", output);
                String json = new ObjectMapper().writeValueAsString(message);
                if (webSocketSession.isOpen()) {
                    webSocketSession.sendMessage(new TextMessage(json));
                }
            } catch (Exception e) {
                log.error("发送输出失败: {}", e.getMessage());
            }
        }

        /**
         * 发送错误到WebSocket
         */
        private void sendError(String error) {
            try {
                WsMessage message = new WsMessage("error", error);
                String json = new ObjectMapper().writeValueAsString(message);
                if (webSocketSession.isOpen()) {
                    webSocketSession.sendMessage(new TextMessage(json));
                }
            } catch (Exception e) {
                log.error("发送错误失败: {}", e.getMessage());
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

            log.info("控制台连接已关闭");
        }
    }
}

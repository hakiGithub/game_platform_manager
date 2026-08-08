package com.gameplatform.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.adapter.DeployAdapter;
import com.gameplatform.adapter.DeployAdapterFactory;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.mapper.GameInstanceMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.*;

/**
 * 实例日志WebSocket处理器
 * 实时推送实例日志到前端
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
public class InstanceLogWebSocketHandler extends TextWebSocketHandler {

    private final GameInstanceMapper instanceMapper;
    private final DeployAdapterFactory adapterFactory;
    private final ObjectMapper objectMapper;

    // 存储会话与日志读取任务的映射
    private final ConcurrentHashMap<String, LogReaderTask> logReaders = new ConcurrentHashMap<>();

    // 线程池用于处理日志读取
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public InstanceLogWebSocketHandler(GameInstanceMapper instanceMapper, DeployAdapterFactory adapterFactory) {
        this.instanceMapper = instanceMapper;
        this.adapterFactory = adapterFactory;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("实例日志WebSocket连接建立: {}", session.getId());

        // 从URL路径中获取实例ID (格式: /ws/instance/{instanceId}/logs)
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

        // 启动日志读取任务
        try {
            LogReaderTask readerTask = new LogReaderTask(instanceId, session, adapterFactory, instanceMapper);
            Future<?> future = executorService.submit(readerTask);
            readerTask.setFuture(future);
            logReaders.put(session.getId(), readerTask);

            // 发送连接成功消息
            sendMessage(session, new WsMessage("connected", "日志连接成功"));

            log.info("实例日志读取启动: sessionId={}, instanceId={}", session.getId(), instanceId);
        } catch (Exception e) {
            log.error("启动日志读取失败: {}", e.getMessage(), e);
            sendErrorMessage(session, "启动日志读取失败: " + e.getMessage());
            session.close();
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("收到WebSocket消息: {}", payload);

        LogReaderTask readerTask = logReaders.get(session.getId());
        if (readerTask == null) {
            sendErrorMessage(session, "日志读取未启动");
            return;
        }

        try {
            WsMessage wsMessage = objectMapper.readValue(payload, WsMessage.class);

            switch (wsMessage.getType()) {
                case "ping":
                    sendMessage(session, new WsMessage("pong", ""));
                    break;

                case "lines":
                    // 设置读取行数
                    int lines = Integer.parseInt(wsMessage.getData());
                    readerTask.setLines(lines);
                    break;

                case "pause":
                    // 暂停/恢复日志推送
                    boolean paused = Boolean.parseBoolean(wsMessage.getData());
                    readerTask.setPaused(paused);
                    sendMessage(session, new WsMessage("status", paused ? "paused" : "resumed"));
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
        log.info("实例日志WebSocket连接关闭: {}, status={}", session.getId(), status);

        LogReaderTask readerTask = logReaders.remove(session.getId());
        if (readerTask != null) {
            readerTask.stop();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("实例日志WebSocket传输错误: {}", session.getId(), exception);

        LogReaderTask readerTask = logReaders.remove(session.getId());
        if (readerTask != null) {
            readerTask.stop();
        }
    }

    /**
     * 从URL路径中提取实例ID
     * 格式: /ws/instance/{instanceId}/logs 或 /api/ws/instance/{instanceId}/logs
     */
    private Long extractInstanceIdFromPath(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }

        // 移除context-path前缀（如果有）
        if (path.startsWith("/api")) {
            path = path.substring(4);
        }

        // 格式: /ws/instance/{instanceId}/logs
        String[] parts = path.split("/");
        if (parts.length >= 5 && "ws".equals(parts[1]) && "instance".equals(parts[2]) && "logs".equals(parts[4])) {
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
     * 日志读取任务
     */
    private class LogReaderTask implements Runnable {
        private final Long instanceId;
        private final WebSocketSession session;
        private final DeployAdapterFactory adapterFactory;
        private final GameInstanceMapper instanceMapper;

        private volatile int lines = 100;
        private volatile boolean paused = false;
        private volatile boolean running = true;
        private Future<?> future;

        // 上次读取的日志内容（用于检测变化）
        private String lastLogContent = "";

        public LogReaderTask(Long instanceId, WebSocketSession session,
                            DeployAdapterFactory adapterFactory, GameInstanceMapper instanceMapper) {
            this.instanceId = instanceId;
            this.session = session;
            this.adapterFactory = adapterFactory;
            this.instanceMapper = instanceMapper;
        }

        public void setFuture(Future<?> future) {
            this.future = future;
        }

        public void setLines(int lines) {
            this.lines = lines;
        }

        public void setPaused(boolean paused) {
            this.paused = paused;
        }

        public void stop() {
            running = false;
            if (future != null) {
                future.cancel(true);
            }
        }

        @Override
        public void run() {
            log.info("开始读取实例日志: instanceId={}", instanceId);

            while (running && session.isOpen()) {
                try {
                    if (!paused) {
                        readAndSendLogs();
                    }
                    // 每秒读取一次
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("读取日志失败: {}", e.getMessage());
                    sendError("读取日志失败: " + e.getMessage());
                    break;
                }
            }

            log.info("实例日志读取结束: instanceId={}", instanceId);
        }

        private void readAndSendLogs() {
            try {
                GameInstance instance = instanceMapper.selectById(instanceId);
                if (instance == null) {
                    sendError("实例不存在");
                    stop();
                    return;
                }

                String deployType = instance.getDeployType();
                if (deployType == null || deployType.isEmpty()) {
                    deployType = "native";
                }
                DeployAdapter adapter = adapterFactory.getAdapter(deployType);
                Map<String, Object> config = instance.getConfigInfo();

                // 获取日志
                String logContent = adapter.getLogs(instanceId, config, lines);

                // 如果日志内容变化，发送给客户端
                if (logContent != null && !logContent.equals(lastLogContent)) {
                    // 只发送新增的部分
                    String newContent = extractNewContent(lastLogContent, logContent);
                    if (!newContent.isEmpty()) {
                        sendLog(newContent);
                    }
                    lastLogContent = logContent;
                }

            } catch (Exception e) {
                log.error("获取日志失败: {}", e.getMessage());
            }
        }

        /**
         * 提取新增内容
         */
        private String extractNewContent(String oldContent, String newContent) {
            if (oldContent.isEmpty()) {
                return newContent;
            }

            // 如果新内容包含旧内容，返回差异部分
            if (newContent.endsWith(oldContent)) {
                return newContent.substring(0, newContent.length() - oldContent.length());
            }

            // 如果旧内容包含在新内容中，返回新内容
            if (newContent.contains(oldContent)) {
                int index = newContent.indexOf(oldContent);
                return newContent.substring(0, index);
            }

            // 无法确定差异，返回全部新内容
            return newContent;
        }

        private void sendLog(String logContent) {
            try {
                WsMessage message = new WsMessage("log", logContent);
                String json = objectMapper.writeValueAsString(message);
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            } catch (Exception e) {
                log.error("发送日志失败: {}", e.getMessage());
            }
        }

        private void sendError(String error) {
            try {
                WsMessage message = new WsMessage("error", error);
                String json = objectMapper.writeValueAsString(message);
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            } catch (Exception e) {
                log.error("发送错误失败: {}", e.getMessage());
            }
        }
    }
}

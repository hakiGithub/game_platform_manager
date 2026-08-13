package com.gameplatform.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

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

    private final ConnectionLifecycle lifecycle;
    private final DeployAdapterLogProvider logProvider;
    private final ObjectMapper objectMapper;

    // 存储会话与日志读取任务的映射
    private final ConcurrentHashMap<String, LogReaderTask> logReaders = new ConcurrentHashMap<>();

    public InstanceLogWebSocketHandler(ConnectionLifecycle lifecycle, DeployAdapterLogProvider logProvider) {
        this.lifecycle = lifecycle;
        this.logProvider = logProvider;
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

        // 启动日志读取任务（实例不存在时由 LogProvider 快速失败并终止推送）

        try {
            LogReaderTask readerTask = new LogReaderTask(instanceId, session, logProvider);
            logReaders.put(session.getId(), readerTask);
            lifecycle.register(session.getId(), readerTask);
            Future<?> future = lifecycle.executor().submit(readerTask);
            readerTask.setFuture(future);

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

        logReaders.remove(session.getId());
        lifecycle.unregister(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("实例日志WebSocket传输错误: {}", session.getId(), exception);

        logReaders.remove(session.getId());
        lifecycle.unregister(session.getId());
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
    private class LogReaderTask implements Runnable, AutoCloseable {
        private final Long instanceId;
        private final WebSocketSession session;
        private final LogTailer tailer;

        private volatile boolean paused = false;
        private volatile boolean running = true;
        private Future<?> future;

        public LogReaderTask(Long instanceId, WebSocketSession session, LogProvider provider) {
            this.instanceId = instanceId;
            this.session = session;
            this.tailer = new LogTailer(provider, instanceId, 100);
        }

        public void setFuture(Future<?> future) {
            this.future = future;
        }

        public void setLines(int lines) {
            tailer.setLines(lines);
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
        public void close() {
            stop();
        }

        @Override
        public void run() {
            log.info("开始读取实例日志: instanceId={}", instanceId);

            while (running && session.isOpen()) {
                try {
                    if (!paused) {
                        // 轮询 + 增量 diff 语义由 LogTailer 承载，此处只负责调度与发送
                        String newContent = tailer.pollOnce();
                        if (newContent != null && !newContent.isEmpty()) {
                            sendLog(newContent);
                        }
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

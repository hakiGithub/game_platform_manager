package com.gameplatform.websocket;

/**
 * 实例日志流深模块（架构评审 2026-08-13 候选 4）
 *
 * <p>把「轮询获取日志 + 增量 diff」的语义收敛为纯状态机：
 * 每次 {@link #pollOnce()} 从 {@link LogProvider} 拉取完整日志快照，
 * 与上次内容做增量 diff，返回新增部分（无新增返回 null）。
 * 调度节奏（轮询间隔、线程）由调用方决定，本模块不依赖时间与线程。
 */
public class LogTailer {

    private final LogProvider provider;
    private final Long instanceId;
    private int lines;

    /** 上次读取的日志内容（用于检测变化） */
    private String lastLogContent = "";

    public LogTailer(LogProvider provider, Long instanceId, int lines) {
        this.provider = provider;
        this.instanceId = instanceId;
        this.lines = lines;
    }

    /** 调整拉取行数（下次轮询生效） */
    public void setLines(int lines) {
        this.lines = lines;
    }

    /**
     * 执行一次轮询。
     *
     * @return 自上次轮询以来新增的日志内容；无新增返回 null
     */
    public String pollOnce() {
        String content = provider.fetch(instanceId, lines);
        if (content == null || content.equals(lastLogContent)) {
            return null;
        }
        String newContent = extractNewContent(lastLogContent, content);
        lastLogContent = content;
        return newContent.isEmpty() ? null : newContent;
    }

    /**
     * 提取新增内容（语义与原 InstanceLogWebSocketHandler 保持一致）：
     * 新旧内容按「新内容包含旧内容」判定，返回不重叠部分；无法判定差异时整体返回。
     */
    private String extractNewContent(String oldContent, String newContent) {
        if (oldContent.isEmpty()) {
            return newContent;
        }
        if (newContent.endsWith(oldContent)) {
            return newContent.substring(0, newContent.length() - oldContent.length());
        }
        if (newContent.contains(oldContent)) {
            int index = newContent.indexOf(oldContent);
            return newContent.substring(0, index);
        }
        return newContent;
    }
}

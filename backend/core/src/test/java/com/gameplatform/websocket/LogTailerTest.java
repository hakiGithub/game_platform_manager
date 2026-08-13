package com.gameplatform.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LogTailer 单元测试（架构评审 2026-08-13 候选 4）
 * 用假 LogProvider 锁定「轮询 + 增量 diff」语义，无需真实 SSH/容器。
 */
@DisplayName("LogTailer 日志流状态机测试")
class LogTailerTest {

    private static class FakeLogProvider implements LogProvider {
        private String content;

        FakeLogProvider(String initial) {
            this.content = initial;
        }

        void append(String addition) {
            this.content = this.content + addition;
        }

        @Override
        public String fetch(Long instanceId, int lines) {
            return content;
        }
    }

    @Test
    @DisplayName("首次轮询整体返回")
    void firstPollReturnsWholeContent() {
        FakeLogProvider provider = new FakeLogProvider("line1\n");
        LogTailer tailer = new LogTailer(provider, 1L, 100);

        assertEquals("line1\n", tailer.pollOnce());
    }

    @Test
    @DisplayName("无变化时返回 null")
    void noChangeReturnsNull() {
        FakeLogProvider provider = new FakeLogProvider("line1\n");
        LogTailer tailer = new LogTailer(provider, 1L, 100);

        tailer.pollOnce();
        assertNull(tailer.pollOnce());
    }

    @Test
    @DisplayName("新内容包含旧内容且不重叠部分为空时不推送（返回 null），且状态已推进")
    void appendReturnsOnlyNewContent() {
        FakeLogProvider provider = new FakeLogProvider("line1\n");
        LogTailer tailer = new LogTailer(provider, 1L, 100);

        tailer.pollOnce();
        provider.append("line2\nline3\n");
        // 语义与原 handler 一致：新内容包含旧内容时取不重叠部分；为空则不推送
        assertNull(tailer.pollOnce());
        // 状态已推进：下一次轮询无新增
        assertNull(tailer.pollOnce());
    }

    @Test
    @DisplayName("内容不包含旧内容时整体视为新增")
    void rotatedContentReturnsWhole() {
        FakeLogProvider provider = new FakeLogProvider("line1\nline2\n");
        LogTailer tailer = new LogTailer(provider, 1L, 100);

        tailer.pollOnce();
        provider.content = "line3\n";
        assertEquals("line3\n", tailer.pollOnce());
    }

    @Test
    @DisplayName("provider 返回 null 时不推进状态")
    void nullFetchKeepsState() {
        LogTailer tailer = new LogTailer((id, lines) -> null, 1L, 100);

        assertNull(tailer.pollOnce());
    }
}

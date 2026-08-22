package com.gameplatform.schedule;

import com.gameplatform.plugin.schedule.ScheduledTaskHandler;
import com.gameplatform.plugin.task.TaskContext;
import com.gameplatform.plugin.task.TaskPayload;
import com.gameplatform.plugin.task.TaskResult;
import com.gameplatform.vo.TaskTypeVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ScheduledTaskHandlerRegistry} 注册表测试（ADR-0011 D3）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@DisplayName("ScheduledTaskHandlerRegistry 注册表")
class ScheduledTaskHandlerRegistryTest {

    private ScheduledTaskHandlerRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ScheduledTaskHandlerRegistry();
    }

    private ScheduledTaskHandler handler(String key, String displayName) {
        return new ScheduledTaskHandler() {
            @Override
            public String getKey() {
                return key;
            }

            @Override
            public String getDisplayName() {
                return displayName;
            }

            @Override
            public TaskResult execute(TaskContext context, TaskPayload payload) {
                return TaskResult.success();
            }
        };
    }

    @Test
    @DisplayName("注册后可通过 source+handlerKey 查询到")
    void registerAndGet() {
        ScheduledTaskHandler h = handler("mapCrawl", "地图爬取");
        registry.register("L4D2", "mapCrawl", h);
        assertSame(h, registry.get("L4D2", "mapCrawl"));
    }

    @Test
    @DisplayName("同 source 同 key 重复注册抛 IllegalStateException")
    void duplicateRegisterThrows() {
        registry.register("L4D2", "mapCrawl", handler("mapCrawl", "地图爬取"));
        assertThrows(IllegalStateException.class,
                () -> registry.register("L4D2", "mapCrawl", handler("mapCrawl", "另一个")));
    }

    @Test
    @DisplayName("不同 source 相同 key 可并存（来源隔离）")
    void sameKeyDifferentSource() {
        ScheduledTaskHandler main = handler("backup", "主应用备份");
        ScheduledTaskHandler plugin = handler("backup", "插件备份");
        registry.register("MAIN", "backup", main);
        registry.register("L4D2", "backup", plugin);
        assertSame(main, registry.get("MAIN", "backup"));
        assertSame(plugin, registry.get("L4D2", "backup"));
    }

    @Test
    @DisplayName("未注册的 key 返回 null")
    void getUnregisteredReturnsNull() {
        assertNull(registry.get("L4D2", "notExists"));
    }

    @Test
    @DisplayName("unregisterBySource 注销该来源全部 Handler，其他来源不受影响")
    void unregisterBySource() {
        registry.register("L4D2", "mapCrawl", handler("mapCrawl", "地图爬取"));
        registry.register("L4D2", "clean", handler("clean", "清理"));
        registry.register("MAIN", "backup", handler("backup", "备份"));

        int removed = registry.unregisterBySource("L4D2");

        assertEquals(2, removed);
        assertNull(registry.get("L4D2", "mapCrawl"));
        assertNull(registry.get("L4D2", "clean"));
        assertNotNull(registry.get("MAIN", "backup"));
    }

    @Test
    @DisplayName("listHandlers 按 source + key 排序输出")
    void listHandlersSorted() {
        registry.register("L4D2", "mapCrawl", handler("mapCrawl", "地图爬取"));
        registry.register("MAIN", "backup", handler("backup", "备份"));

        List<TaskTypeVO> list = registry.listHandlers();

        assertEquals(2, list.size());
        assertEquals("L4D2", list.get(0).getSource());
        assertEquals("mapCrawl", list.get(0).getTaskType());
        assertEquals("地图爬取", list.get(0).getDisplayName());
        assertEquals("MAIN", list.get(1).getSource());
    }

    @Test
    @DisplayName("listKeysBySource 返回指定来源的 key 集合")
    void listKeysBySource() {
        registry.register("L4D2", "mapCrawl", handler("mapCrawl", "地图爬取"));
        registry.register("MAIN", "backup", handler("backup", "备份"));

        List<String> keys = registry.listKeysBySource("L4D2");

        assertEquals(List.of("mapCrawl"), keys);
    }
}

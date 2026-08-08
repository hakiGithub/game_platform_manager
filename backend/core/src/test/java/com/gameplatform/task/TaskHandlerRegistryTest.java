package com.gameplatform.task;

import com.gameplatform.plugin.task.TaskHandler;
import com.gameplatform.plugin.task.TaskPayload;
import com.gameplatform.plugin.task.TaskResult;
import com.gameplatform.plugin.task.TaskContext;
import com.gameplatform.plugin.task.TaskSubmitContext;
import com.gameplatform.vo.TaskTypeVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TaskHandlerRegistry} 注册表测试（ADR-002）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>register/get 基本语义</li>
 *   <li>重复注册抛 IllegalStateException</li>
 *   <li>unregisterBySource 注销来源全部 Handler</li>
 *   <li>listTypes 列出已注册类型</li>
 *   <li>taskSourceIndex：indexTaskSource / getSourceByTaskId / 移除 / 清空</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@DisplayName("TaskHandlerRegistry 注册表")
class TaskHandlerRegistryTest {

    private TaskHandlerRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new TaskHandlerRegistry();
    }

    private TaskHandler noopHandler(String displayName) {
        return new TaskHandler() {
            @Override
            public String getType() {
                return "noop";
            }

            @Override
            public String getDisplayName() {
                return displayName;
            }

            @Override
            public boolean isRetryable() {
                return false;
            }

            @Override
            public long getDefaultTimeoutMs() {
                return 0;
            }

            @Override
            public TaskResult execute(TaskContext context, TaskPayload payload) {
                return TaskResult.success();
            }
        };
    }

    // ==================== register / get ====================

    @Nested
    @DisplayName("register 注册与 get 查询")
    class RegisterAndGet {

        @Test
        @DisplayName("注册后可通过 source+taskType 查询到")
        void registerAndGet() {
            TaskHandler h = noopHandler("测试");
            registry.register("L4D2", "crawl", h);

            TaskHandler found = registry.get("L4D2", "crawl");
            assertSame(h, found);
        }

        @Test
        @DisplayName("未注册的返回 null")
        void unregisteredReturnsNull() {
            assertNull(registry.get("MAIN", "nonexistent"));
        }

        @Test
        @DisplayName("同 taskType 不同 source 各自独立")
        void sameTypeDifferentSource() {
            TaskHandler h1 = noopHandler("L4D2 爬取");
            TaskHandler h2 = noopHandler("MAIN 部署");
            registry.register("L4D2", "crawl", h1);
            registry.register("MAIN", "crawl", h2);

            assertSame(h1, registry.get("L4D2", "crawl"));
            assertSame(h2, registry.get("MAIN", "crawl"));
        }

        @Test
        @DisplayName("重复注册抛 IllegalStateException")
        void duplicateRegisterThrows() {
            registry.register("L4D2", "crawl", noopHandler("first"));
            assertThrows(IllegalStateException.class,
                    () -> registry.register("L4D2", "crawl", noopHandler("second")));
        }
    }

    // ==================== unregisterBySource ====================

    @Nested
    @DisplayName("unregisterBySource 按来源注销")
    class UnregisterBySource {

        @Test
        @DisplayName("注销来源全部 Handler，返回数量")
        void unregisterAllBySource() {
            registry.register("L4D2", "crawl", noopHandler("爬取"));
            registry.register("L4D2", "export", noopHandler("导出"));
            registry.register("MAIN", "deploy", noopHandler("部署"));

            int count = registry.unregisterBySource("L4D2");

            assertEquals(2, count);
            assertNull(registry.get("L4D2", "crawl"));
            assertNull(registry.get("L4D2", "export"));
            // MAIN 不受影响
            assertNotNull(registry.get("MAIN", "deploy"));
        }

        @Test
        @DisplayName("注销不存在的来源返回 0")
        void unregisterUnknownReturnsZero() {
            int count = registry.unregisterBySource("UNKNOWN");
            assertEquals(0, count);
        }

        @Test
        @DisplayName("注销后可重新注册")
        void reRegisterAfterUnregister() {
            registry.register("L4D2", "crawl", noopHandler("v1"));
            registry.unregisterBySource("L4D2");

            // 重新注册不应抛异常
            assertDoesNotThrow(() -> registry.register("L4D2", "crawl", noopHandler("v2")));
            assertNotNull(registry.get("L4D2", "crawl"));
        }
    }

    // ==================== listTypes ====================

    @Nested
    @DisplayName("listTypes 列出所有已注册类型")
    class ListTypes {

        @Test
        @DisplayName("空注册表返回空列表")
        void emptyReturnsEmptyList() {
            List<TaskTypeVO> types = registry.listTypes();
            assertNotNull(types);
            assertTrue(types.isEmpty());
        }

        @Test
        @DisplayName("返回所有已注册类型，包含 source/taskType/displayName")
        void returnsAllTypes() {
            registry.register("L4D2", "crawl", noopHandler("地图爬取"));
            registry.register("MAIN", "deploy", noopHandler("实例部署"));

            List<TaskTypeVO> types = registry.listTypes();

            assertEquals(2, types.size());
            assertTrue(types.stream().anyMatch(t ->
                    "L4D2".equals(t.getSource()) && "crawl".equals(t.getTaskType())
                            && "地图爬取".equals(t.getDisplayName())));
            assertTrue(types.stream().anyMatch(t ->
                    "MAIN".equals(t.getSource()) && "deploy".equals(t.getTaskType())
                            && "实例部署".equals(t.getDisplayName())));
        }
    }

    // ==================== taskSourceIndex ====================

    @Nested
    @DisplayName("taskSourceIndex 反向索引")
    class TaskSourceIndex {

        @Test
        @DisplayName("indexTaskSource 后可按 taskId 查找 source")
        void indexAndGet() {
            registry.indexTaskSource("task-1", "L4D2");
            assertEquals("L4D2", registry.getSourceByTaskId("task-1"));
        }

        @Test
        @DisplayName("未索引的 taskId 返回 null")
        void unknownTaskReturnsNull() {
            assertNull(registry.getSourceByTaskId("nonexistent"));
        }

        @Test
        @DisplayName("removeTaskSourceIndex 后查询返回 null")
        void removeIndex() {
            registry.indexTaskSource("task-1", "L4D2");
            registry.removeTaskSourceIndex("task-1");
            assertNull(registry.getSourceByTaskId("task-1"));
        }

        @Test
        @DisplayName("clearTaskSourceIndex 清空全部索引")
        void clearAllIndex() {
            registry.indexTaskSource("task-1", "L4D2");
            registry.indexTaskSource("task-2", "MAIN");

            registry.clearTaskSourceIndex();

            assertNull(registry.getSourceByTaskId("task-1"));
            assertNull(registry.getSourceByTaskId("task-2"));
        }

        @Test
        @DisplayName("null 参数安全处理")
        void nullArgsSafe() {
            assertDoesNotThrow(() -> registry.indexTaskSource(null, "L4D2"));
            assertDoesNotThrow(() -> registry.indexTaskSource("task-1", null));
            assertNull(registry.getSourceByTaskId(null));
            assertDoesNotThrow(() -> registry.removeTaskSourceIndex(null));
        }
    }
}

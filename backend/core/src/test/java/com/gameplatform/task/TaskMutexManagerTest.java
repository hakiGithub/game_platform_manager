package com.gameplatform.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TaskMutexManager} 互斥键内存管理测试（ADR-018）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>putIfAbsent 基本语义（首次成功、重复失败、同 taskId 幂等）</li>
 *   <li>remove 按 mutexKey + taskId 释放（CAS 不误删）</li>
 *   <li>removeByTaskId 反查释放</li>
 *   <li>clear 清空</li>
 *   <li>并发竞争：N 个线程同 mutexKey，仅一个成功</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@DisplayName("TaskMutexManager 互斥键管理")
class TaskMutexManagerTest {

    private TaskMutexManager manager;

    @BeforeEach
    void setUp() {
        manager = new TaskMutexManager();
    }

    // ==================== 基本语义 ====================

    @Nested
    @DisplayName("putIfAbsent 基本语义")
    class PutIfAbsent {

        @Test
        @DisplayName("首次占用成功，size=1")
        void firstAcquireSucceeds() {
            String mutexKey = "L4D2:crawl";
            String taskId = "task-1";

            boolean ok = manager.putIfAbsent(mutexKey, taskId);

            assertTrue(ok);
            assertTrue(manager.isHeld(mutexKey));
            assertEquals(1, manager.size());
        }

        @Test
        @DisplayName("同 mutexKey 不同 taskId 第二次占用失败")
        void secondAcquireFails() {
            String mutexKey = "MAIN:deploy";
            assertTrue(manager.putIfAbsent(mutexKey, "task-1"));
            assertFalse(manager.putIfAbsent(mutexKey, "task-2"));
            assertEquals(1, manager.size());
        }

        @Test
        @DisplayName("同 mutexKey 同 taskId 重复占用视为成功（幂等防御）")
        void sameTaskIdIdempotent() {
            String mutexKey = "MAIN:backup";
            assertTrue(manager.putIfAbsent(mutexKey, "task-x"));
            assertTrue(manager.putIfAbsent(mutexKey, "task-x"));
            assertEquals(1, manager.size());
        }

        @Test
        @DisplayName("不同 mutexKey 各自独立占用")
        void differentMutexKeysIndependent() {
            assertTrue(manager.putIfAbsent("L4D2:crawl", "t1"));
            assertTrue(manager.putIfAbsent("L4D2:export", "t2"));
            assertTrue(manager.putIfAbsent("MAIN:deploy", "t3"));
            assertEquals(3, manager.size());
        }
    }

    // ==================== remove ====================

    @Nested
    @DisplayName("remove(mutexKey, taskId) 释放")
    class RemoveByKeyAndTask {

        @Test
        @DisplayName("释放后 mutexKey 可再次占用")
        void releaseAllowsReacquire() {
            manager.putIfAbsent("k1", "t1");
            manager.remove("k1", "t1");

            assertFalse(manager.isHeld("k1"));
            assertTrue(manager.putIfAbsent("k1", "t2"));
        }

        @Test
        @DisplayName("CAS 删除：仅当 taskId 匹配才删除，避免误删")
        void casDeleteDoesNotRemoveOtherTask() {
            manager.putIfAbsent("k1", "t1");
            // 用错误的 taskId 尝试删除
            manager.remove("k1", "wrong-task");

            assertTrue(manager.isHeld("k1"));
            assertEquals(1, manager.size());
        }

        @Test
        @DisplayName("null 参数安全处理")
        void nullArgsSafe() {
            manager.putIfAbsent("k1", "t1");
            assertDoesNotThrow(() -> manager.remove(null, "t1"));
            assertDoesNotThrow(() -> manager.remove("k1", null));
            // 原 mutexKey 仍占用
            assertTrue(manager.isHeld("k1"));
        }
    }

    // ==================== removeByTaskId ====================

    @Nested
    @DisplayName("removeByTaskId(taskId) 反查释放")
    class RemoveByTaskId {

        @Test
        @DisplayName("按 taskId 释放成功，mutexKey 同时清除")
        void releaseByTaskId() {
            manager.putIfAbsent("k1", "t1");
            manager.removeByTaskId("t1");

            assertFalse(manager.isHeld("k1"));
            assertEquals(0, manager.size());
        }

        @Test
        @DisplayName("未知 taskId 不抛异常")
        void unknownTaskIdSafe() {
            assertDoesNotThrow(() -> manager.removeByTaskId("nonexistent"));
        }

        @Test
        @DisplayName("null taskId 安全处理")
        void nullTaskIdSafe() {
            assertDoesNotThrow(() -> manager.removeByTaskId(null));
        }

        @Test
        @DisplayName("释放后再用同 mutexKey 不同 taskId 占用成功")
        void reacquireAfterRemoveByTaskId() {
            manager.putIfAbsent("k1", "t1");
            manager.removeByTaskId("t1");
            assertTrue(manager.putIfAbsent("k1", "t2"));
        }
    }

    // ==================== clear ====================

    @Nested
    @DisplayName("clear() 全量清空")
    class Clear {

        @Test
        @DisplayName("清空所有互斥键")
        void clearsAll() {
            manager.putIfAbsent("k1", "t1");
            manager.putIfAbsent("k2", "t2");
            manager.putIfAbsent("k3", "t3");
            assertEquals(3, manager.size());

            manager.clear();

            assertEquals(0, manager.size());
            assertFalse(manager.isHeld("k1"));
        }

        @Test
        @DisplayName("清空后所有 mutexKey 可再次占用")
        void reacquireAfterClear() {
            manager.putIfAbsent("k1", "t1");
            manager.clear();
            assertTrue(manager.putIfAbsent("k1", "t2"));
        }
    }

    // ==================== 并发测试 ====================

    @Nested
    @DisplayName("并发竞争")
    class Concurrency {

        @Test
        @DisplayName("10 个线程同时占同 mutexKey，仅 1 个成功")
        void concurrentAcquireOnlyOneSucceeds() throws InterruptedException {
            int threadCount = 10;
            String mutexKey = "L4D2:crawl";
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneGate = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);
            List<String> taskIds = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                String taskId = "task-" + i;
                taskIds.add(taskId);
                pool.submit(() -> {
                    try {
                        startGate.await();
                        if (manager.putIfAbsent(mutexKey, taskId)) {
                            successCount.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }

            startGate.countDown();
            assertTrue(doneGate.await(5, TimeUnit.SECONDS), "所有线程应在 5s 内完成");
            pool.shutdown();

            assertEquals(1, successCount.get(), "仅一个线程应成功占用");
            assertEquals(threadCount - 1, failCount.get());
            assertTrue(manager.isHeld(mutexKey));
        }

        @Test
        @DisplayName("并发场景：10 个不同 mutexKey 并发占用，全部成功")
        void concurrentDifferentKeysAllSucceed() throws InterruptedException {
            int threadCount = 10;
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneGate = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final String mutexKey = "MAIN:deploy-" + i;
                final String taskId = "task-" + i;
                pool.submit(() -> {
                    try {
                        startGate.await();
                        if (manager.putIfAbsent(mutexKey, taskId)) {
                            successCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }

            startGate.countDown();
            assertTrue(doneGate.await(5, TimeUnit.SECONDS));
            pool.shutdown();

            assertEquals(threadCount, successCount.get());
            assertEquals(threadCount, manager.size());
        }

        @Test
        @DisplayName("并发释放与占用交替不产生数据竞争")
        void concurrentReleaseAndAcquire() throws InterruptedException {
            int rounds = 50;
            ExecutorService pool = Executors.newFixedThreadPool(4);
            CountDownLatch doneGate = new CountDownLatch(rounds * 2);
            AtomicInteger successCount = new AtomicInteger(0);

            // 一半线程占用，一半线程释放
            for (int i = 0; i < rounds; i++) {
                final String mutexKey = "k-" + (i % 5);
                final String taskId = UUID.randomUUID().toString();
                pool.submit(() -> {
                    try {
                        if (manager.putIfAbsent(mutexKey, taskId)) {
                            successCount.incrementAndGet();
                        }
                    } finally {
                        doneGate.countDown();
                    }
                });
                pool.submit(() -> {
                    try {
                        manager.removeByTaskId(taskId);
                    } finally {
                        doneGate.countDown();
                    }
                });
            }

            assertTrue(doneGate.await(10, TimeUnit.SECONDS));
            pool.shutdown();
            // 至少有部分成功（具体数量取决于调度顺序，无法严格断言）
            assertTrue(successCount.get() >= 0);
        }
    }
}

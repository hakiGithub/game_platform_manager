package com.gameplatform.patch;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.plugin.patch.PatchInstallProgressListener;
import com.gameplatform.plugin.patch.PatchInstallRequest;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.plugin.task.TaskService;
import com.gameplatform.plugin.task.TaskSubmitRequest;
import com.gameplatform.task.TaskMutexManager;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 同步补丁入口的每主机互斥测试（design.md §7.2 B-04、§8.2、§14.14 —— V-26 / V-07）。
 *
 * <p>每主机互斥的真实承担者是任务中心的内存键 {@code PATCH_INSTALL:<hostId>}；直调
 * {@code PatchInstallExecutor.execute()} 绕开了 {@code TaskServiceImpl.submit} 上那次
 * {@code putIfAbsent}，因此 {@code installSync} 必须承<b>同一个</b>键补回来。
 *
 * <p>这里用 {@link TaskMutexManager} 的<b>真身</b>而非替身：V-26 的四条判据（持锁期间
 * {@code isHeld == true}、异常后已释放、{@code removeByTaskId} 扫不到）都是关于这个键管理器的
 * 行为，替身只会把判定变成「我调用了我自己」。
 */
@ExtendWith(MockitoExtension.class)
class PatchInstallServiceImplSyncTest {

    private static final Long INSTANCE_ID = 7L;
    private static final Long HOST_ID = 3L;
    private static final String MUTEX_KEY = "PATCH_INSTALL:" + HOST_ID;

    @Mock
    private TaskService taskService;
    @Mock
    private InstanceQueryService instanceQueryService;
    @Mock
    private HostCapabilityProber prober;
    @Mock
    private PatchInstallExecutor executor;

    private TaskMutexManager mutexManager;
    private PatchInstallServiceImpl service;

    @BeforeEach
    void setUp() {
        mutexManager = spy(new TaskMutexManager());
        service = new PatchInstallServiceImpl(taskService, instanceQueryService, prober, executor, mutexManager);
        // 真实预算 600 s / 轮询 2 s（§8.1）。单测核的是「等满了会怎样」这个行为，不是这两个数，
        // 因此把它们缩到毫秒级——常量本身仍是产品值。
        service.mutexPollIntervalMs = 5L;
        service.mutexWaitBudgetMs = 60L;

        InstanceVO instance = new InstanceVO();
        instance.setId(INSTANCE_ID);
        instance.setHostId(HOST_ID);
        lenient().when(instanceQueryService.getInstanceById(INSTANCE_ID)).thenReturn(instance);
    }

    @Test
    @DisplayName("V-07：请求对象引用透传给执行器，includePattern 不经 payload 截断")
    void handsRequestReferenceToExecutor() {
        PatchInstallRequest request = request();
        service.installSync(request, listener());

        ArgumentCaptor<PatchInstallRequest> captor = ArgumentCaptor.forClass(PatchInstallRequest.class);
        verify(executor).execute(captor.capture(), any(PatchInstallExecutor.ProgressListener.class));
        assertSame(request, captor.getValue(), "必须是同一个对象引用，而不是重建的副本");
        assertEquals("*.vpk,*.vpk.001", captor.getValue().getIncludePattern());
    }

    @Test
    @DisplayName("V-26：持锁期间 isHeld 为真且任务中心进不来，执行完即释放")
    void holdsKeyForTheWholeExecuteAndReleasesAfter() {
        AtomicBoolean heldDuringExecute = new AtomicBoolean();
        AtomicBoolean taskCenterBlocked = new AtomicBoolean();
        doAnswer(invocation -> {
            heldDuringExecute.set(mutexManager.isHeld(MUTEX_KEY));
            taskCenterBlocked.set(!mutexManager.putIfAbsent(MUTEX_KEY, "task-center-1"));
            return null;
        }).when(executor).execute(any(), any());

        service.installSync(request(), listener());

        assertTrue(heldDuringExecute.get());
        assertTrue(taskCenterBlocked.get(), "同主机经任务中心提交的那一路必须被挡住");
        assertFalse(mutexManager.isHeld(MUTEX_KEY));
    }

    @Test
    @DisplayName("V-26：同主机两路的 execute() 时间区间不重叠")
    void twoRunsOnSameHostNeverOverlap() throws Exception {
        // 这一路核的是「后来者排队等到锁」，预算必须大于单次执行时长（产品值 600 s 远大于任何补丁）
        service.mutexWaitBudgetMs = 5_000L;
        List<long[]> windows = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            long start = System.nanoTime();
            Thread.sleep(80L);
            windows.add(new long[]{start, System.nanoTime()});
            return null;
        }).when(executor).execute(any(), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> service.installSync(request(), listener())));
            futures.add(pool.submit(() -> service.installSync(request(), listener())));
            for (Future<?> future : futures) {
                future.get(10L, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(2, windows.size());
        long[] earlier = windows.get(0);
        long[] later = windows.get(1);
        if (earlier[0] > later[0]) {
            long[] swap = earlier;
            earlier = later;
            later = swap;
        }
        assertTrue(later[0] >= earlier[1],
                "两次 execute() 的区间必须无交集：" + Arrays.toString(earlier) + " / " + Arrays.toString(later));
    }

    @Test
    @DisplayName("V-26：与任务中心提交的那一路互斥")
    void yieldsToTaskCenterSubmittedRun() {
        mutexManager.putIfAbsent(MUTEX_KEY, "task-center-1");

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.installSync(request(), listener()));

        assertTrue(e.getMessage().contains("等待同主机补丁互斥超时"), e.getMessage());
        verify(executor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("V-26：等满预算判该步失败并在原因段指名，不静默跳过")
    void givesUpAfterBudgetAndNamesTheReason() {
        doAnswer(invocation -> false).when(mutexManager).putIfAbsent(eq(MUTEX_KEY), anyString());

        long startedAt = System.currentTimeMillis();
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.installSync(request(), listener()));
        long waited = System.currentTimeMillis() - startedAt;

        assertTrue(e.getMessage().contains("等待同主机补丁互斥超时"), e.getMessage());
        assertTrue(e.getMessage().contains("hostId=" + HOST_ID), "原因段要指名是哪台主机：" + e.getMessage());
        assertTrue(waited >= 60L && waited < 5_000L, "等待应落在预算量级：" + waited + "ms");
        verify(executor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("V-26：执行器抛异常后键同样释放")
    void releasesKeyWhenExecutorThrows() {
        doThrow(new BusinessException("补丁下载失败")).when(executor).execute(any(), any());

        assertThrows(BusinessException.class, () -> service.installSync(request(), listener()));
        assertFalse(mutexManager.isHeld(MUTEX_KEY));
    }

    @Test
    @DisplayName("V-26：键与任务中心默认规则逐字相同，holder 取 EXT: 前缀且不受 removeByTaskId 波及")
    void keyMatchesTaskCenterAndHolderIsOutOfItsReach() {
        service.installSync(request(), listener());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> holder = ArgumentCaptor.forClass(String.class);
        verify(mutexManager).putIfAbsent(key.capture(), holder.capture());
        // 任务中心默认规则 = taskType + ":" + scopeKey，scopeKey = String.valueOf(instance.getHostId())
        assertEquals("PATCH_INSTALL" + ":" + String.valueOf(HOST_ID), key.getValue());
        assertTrue(holder.getValue().startsWith("EXT:"),
                "holder 必须是 EXT: 前缀的非任务 ID 串：" + holder.getValue());

        // 任务中心的 PENDING 超时清扫只按 DB 任务 ID 反查，拿不到 EXT: holder ⇒ 不会把部署中的锁误释放
        mutexManager.putIfAbsent(MUTEX_KEY, holder.getValue());
        mutexManager.removeByTaskId("1823456789012345678");
        assertTrue(mutexManager.isHeld(MUTEX_KEY), "按其他任务 ID 清扫不得释放扩展阶段持有的键");
    }

    @Test
    @DisplayName("不同主机各自持键，互不阻塞")
    void differentHostsDoNotBlockEachOther() {
        String otherHostKey = "PATCH_INSTALL:9";
        mutexManager.putIfAbsent(otherHostKey, "task-center-1");

        service.installSync(request(), listener());

        verify(executor).execute(any(), any());
        assertTrue(mutexManager.isHeld(otherHostKey));
    }

    @Test
    @DisplayName("实例不存在时不承键、不执行")
    void missingInstanceFailsBeforeLocking() {
        when(instanceQueryService.getInstanceById(INSTANCE_ID)).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.installSync(request(), listener()));
        assertEquals(0, mutexManager.size());
        verify(executor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("回归：既有 install() 提交路径一字未动（丢字段属 RISK-D05，本期不修）")
    void asyncSubmitPathUnchanged() {
        service.install(request());

        ArgumentCaptor<TaskSubmitRequest> captor = ArgumentCaptor.forClass(TaskSubmitRequest.class);
        verify(taskService).submit(captor.capture());
        TaskSubmitRequest submit = captor.getValue();
        assertEquals(Set.of("instanceId", "url", "targetPath"), submit.getPayload().keySet(),
                "提交路径仍只带那五个键里的本例三项，未被顺手补全");
        assertEquals("PATCH_INSTALL", submit.getTaskType());
        assertEquals(String.valueOf(HOST_ID), submit.getScopeKey());
        verify(executor, never()).execute(any(), any());
    }

    private static PatchInstallRequest request() {
        return PatchInstallRequest.builder()
                .instanceId(INSTANCE_ID)
                .url("https://example.invalid/patch.zip")
                .targetPath("game/addons")
                .includePattern("*.vpk,*.vpk.001")
                .build();
    }

    private static PatchInstallProgressListener listener() {
        return new PatchInstallProgressListener() {
            @Override
            public void onProgress(int percent, String message) {
            }

            @Override
            public void onLog(String message) {
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };
    }
}

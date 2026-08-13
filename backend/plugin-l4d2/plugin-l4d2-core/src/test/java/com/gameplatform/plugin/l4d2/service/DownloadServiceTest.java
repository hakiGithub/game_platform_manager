package com.gameplatform.plugin.l4d2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.extension.ListOptions;
import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.dto.UrlDownloadDTO;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.extension.DownloadTaskResource;
import com.gameplatform.plugin.l4d2.extension.DownloadTaskSpec;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.DownloadTaskVO;
import com.gameplatform.plugin.patch.PatchInstallRequest;
import com.gameplatform.plugin.patch.PatchInstallService;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.plugin.task.TaskService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DownloadService 单元测试（对齐 plan §4.1.8）。
 *
 * <p>重点测并发安全与状态管理，不实际执行下载（mock ExternalHttpClient.download 抛异常）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DownloadServiceTest {

    @Mock
    private ExternalHttpClient httpClient;

    @Mock
    private InstanceFileService instanceFileService;

    @Mock
    private InstanceQueryService instanceQueryService;

    @Mock
    private ExtensionClient extensionClient;

    @Mock
    private PatchInstallService patchInstallService;

    @Mock
    private TaskService taskService;

    private L4D2Config config;

    private final L4D2PathResolver pathResolver = new L4D2PathResolver();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DownloadService service;

    private InstanceVO instance;

    @BeforeEach
    void setUp() {
        config = new L4D2Config();
        service = new DownloadService(httpClient, instanceFileService, instanceQueryService,
                extensionClient, config, pathResolver, objectMapper, patchInstallService, taskService);

        // URL 任务委托主应用 PatchInstallService：返回固定的任务中心 ID
        lenient().when(patchInstallService.install(any(PatchInstallRequest.class)))
                .thenReturn("patch-task-1");
        lenient().when(taskService.getTask(anyString())).thenReturn(null);
        lenient().doNothing().when(taskService).cancelMyOwn(anyString());

        // 默认实例
        instance = new InstanceVO();
        instance.setId(1L);
        instance.setHostId(10L);
        instance.setInstallPath("/home/l4d2");
        lenient().when(instanceQueryService.getInstanceById(1L)).thenReturn(instance);

        // 默认让 httpClient.download 抛异常，避免实际下载
        lenient().when(httpClient.download(any(), any(), any(), any(), any()))
                .thenThrow(new L4D2PluginException(L4D2PluginException.NETWORK, "test download failure"));

        // 默认让 getById 返回一个 PENDING 资源（供 updateDb / cancel 用）
        lenient().when(extensionClient.getById(eq(DownloadTaskResource.class), anyString()))
                .thenAnswer(inv -> Optional.of(buildResource(inv.getArgument(1), "PENDING")));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        // 等待异步任务完成，避免泄漏到其他测试
        Thread.sleep(300);
    }

    // ============================================================
    // create_url_task_single：单 URL 任务创建
    // ============================================================

    @Test
    void create_url_task_single() {
        UrlDownloadDTO dto = new UrlDownloadDTO();
        dto.setInstanceId(1L);
        dto.setUrl("https://example.com/test.vpk");
        dto.setFilename("test.vpk");
        dto.setTargetPath("addons/");

        List<String> taskIds = service.createUrlTasks(dto);

        assertEquals(1, taskIds.size());
        assertNotNull(taskIds.get(0));

        // 验证 create 被调用 1 次
        ArgumentCaptor<DownloadTaskResource> captor = ArgumentCaptor.forClass(DownloadTaskResource.class);
        verify(extensionClient, times(1)).create(captor.capture());
        DownloadTaskResource created = captor.getValue();
        assertEquals("PENDING", created.getStatus());
        assertEquals("PENDING", created.getSpec().getTaskStatus());
        assertEquals("URL", created.getSpec().getTaskType());
        assertEquals("https://example.com/test.vpk", created.getSpec().getTaskUrl());
        assertEquals(1L, created.getSpec().getInstanceId());
        // 执行委托主应用：记录关联任务中心 taskId，请求目标路径 = targetPath + filename
        assertEquals("patch-task-1", created.getSpec().getPatchTaskId());
        ArgumentCaptor<PatchInstallRequest> requestCaptor = ArgumentCaptor.forClass(PatchInstallRequest.class);
        verify(patchInstallService).install(requestCaptor.capture());
        assertEquals(1L, requestCaptor.getValue().getInstanceId());
        assertEquals("https://example.com/test.vpk", requestCaptor.getValue().getUrl());
        assertEquals("addons/test.vpk", requestCaptor.getValue().getTargetPath());
    }

    // ============================================================
    // create_url_task_multiple：多 URL 切分
    // ============================================================

    @Test
    void create_url_task_multiple() {
        UrlDownloadDTO dto = new UrlDownloadDTO();
        dto.setInstanceId(1L);
        dto.setUrl("https://example.com/a.vpk\nhttps://example.com/b.vpk");
        dto.setFilename("test.vpk");
        dto.setTargetPath("addons/");

        List<String> taskIds = service.createUrlTasks(dto);

        assertEquals(2, taskIds.size());
        assertNotEquals(taskIds.get(0), taskIds.get(1));

        // 验证 create 被调用 2 次，主应用 install 也被调用 2 次
        verify(extensionClient, times(2)).create(any(DownloadTaskResource.class));
        verify(patchInstallService, times(2)).install(any(PatchInstallRequest.class));
    }

    // ============================================================
    // create_url_task_disk_full：mock 磁盘空间 95% → 抛异常
    // ============================================================

    @Test
    void create_url_task_disk_full() {
        // 使用 mockConstruction 模拟 File 构造，让磁盘使用率达到 95%
        try (MockedConstruction<File> mocked = mockConstruction(File.class,
                (mock, context) -> {
                    when(mock.getTotalSpace()).thenReturn(100L);
                    when(mock.getUsableSpace()).thenReturn(5L); // 95% 使用率
                })) {
            UrlDownloadDTO dto = new UrlDownloadDTO();
            dto.setInstanceId(1L);
            dto.setUrl("https://example.com/test.vpk");
            dto.setFilename("test.vpk");
            dto.setTargetPath("addons/");

            L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                    () -> service.createUrlTasks(dto));
            assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
            assertTrue(ex.getMessage().contains("磁盘空间不足"));
            // 不应创建任何任务
            verify(extensionClient, never()).create(any());
        }
    }

    // ============================================================
    // cancel_task_while_pending：创建后立即取消
    // ============================================================

    @Test
    void cancel_task_while_pending() throws Exception {
        // 预占信号量全部许可，使异步任务停留在 PENDING（阻塞在 acquire）
        Semaphore semaphore = (Semaphore) ReflectionTestUtils.getField(service, "downloadSemaphore");
        assertNotNull(semaphore);
        semaphore.acquire(3);
        try {
            UrlDownloadDTO dto = new UrlDownloadDTO();
            dto.setInstanceId(1L);
            dto.setUrl("https://example.com/test.vpk");
            dto.setFilename("test.vpk");
            dto.setTargetPath("addons/");

            List<String> taskIds = service.createUrlTasks(dto);
            String taskId = taskIds.get(0);

            // 等待异步任务进入阻塞（确保仍为 PENDING）
            Thread.sleep(200);
            DownloadTaskVO vo = service.getTask(taskId);
            assertEquals("PENDING", vo.getStatus());

            // 立即取消
            service.cancel(taskId);

            // 验证 DB 至少有一次更新为 CANCELLED（cancel 方法同步调用 update）
            verify(extensionClient, atLeastOnce())
                    .update(argThat(r -> r != null && "CANCELLED".equals(r.getStatus())));
        } finally {
            semaphore.release(3);
        }
        // 等待异步 catch 块完成
        Thread.sleep(300);
    }

    // ============================================================
    // list_tasks_filter_by_instance：按实例过滤
    // ============================================================

    @Test
    void list_tasks_filter_by_instance() {
        DownloadTaskResource task1 = buildResource("task-1", "COMPLETED");
        task1.getSpec().setInstanceId(1L);

        when(extensionClient.list(eq(DownloadTaskResource.class), any(ListOptions.class)))
                .thenReturn(List.of(task1));

        List<DownloadTaskVO> result = service.listTasks(1L, null);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getInstanceId());
        assertEquals("task-1", result.get(0).getTaskId());
    }

    // ============================================================
    // list_tasks_filter_by_status：过滤 COMPLETED 任务
    // ============================================================

    @Test
    void list_tasks_filter_by_status() {
        DownloadTaskResource completed = buildResource("task-1", "COMPLETED");
        completed.getSpec().setStartTime("2026-07-20T10:00:00");
        DownloadTaskResource failed = buildResource("task-2", "FAILED");
        failed.getSpec().setStartTime("2026-07-20T09:00:00");

        when(extensionClient.list(eq(DownloadTaskResource.class), any(ListOptions.class)))
                .thenReturn(Arrays.asList(completed, failed));

        List<DownloadTaskVO> result = service.listTasks(null, "COMPLETED");

        assertEquals(1, result.size());
        assertEquals("COMPLETED", result.get(0).getStatus());
        assertEquals("task-1", result.get(0).getTaskId());
    }

    // ============================================================
    // cleanup_on_startup：mock DB 有 IN_PROGRESS 记录，cleanupOnStartup 后变 FAILED
    // ============================================================

    @Test
    void cleanup_on_startup() {
        DownloadTaskResource pending = buildResource("task-pending", "PENDING");
        DownloadTaskResource downloading = buildResource("task-downloading", "DOWNLOADING");
        DownloadTaskResource completed = buildResource("task-completed", "COMPLETED");

        when(extensionClient.listAll(DownloadTaskResource.class))
                .thenReturn(Arrays.asList(pending, downloading, completed));

        service.cleanupOnStartup();

        // 应仅更新 PENDING 和 DOWNLOADING 为 FAILED，COMPLETED 跳过
        ArgumentCaptor<DownloadTaskResource> captor = ArgumentCaptor.forClass(DownloadTaskResource.class);
        verify(extensionClient, times(2)).update(captor.capture());
        List<DownloadTaskResource> updated = captor.getAllValues();
        for (DownloadTaskResource r : updated) {
            assertEquals("FAILED", r.getStatus());
            assertEquals("FAILED", r.getSpec().getTaskStatus());
            assertEquals("服务重启中断", r.getSpec().getErrorMessage());
            assertNotNull(r.getSpec().getCompleteTime());
        }
        // 验证 COMPLETED 记录未被更新
        assertEquals("COMPLETED", completed.getSpec().getTaskStatus());
    }

    // ============================================================
    // concurrent_task_list_access：100 线程并发 listTasks + cancelTask
    // ============================================================

    @Test
    void concurrent_task_list_access() throws Exception {
        // 预填充 DB mock
        List<DownloadTaskResource> dbTasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            dbTasks.add(buildResource("task-" + i, "COMPLETED"));
        }
        when(extensionClient.list(eq(DownloadTaskResource.class), any(ListOptions.class)))
                .thenReturn(dbTasks);

        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (idx % 2 == 0) {
                        // 一半线程并发 listTasks
                        service.listTasks(1L, null);
                    } else {
                        // 一半线程并发 cancel（任务不在内存中，仅触发 DB 更新路径）
                        service.cancel("task-" + (idx % 10));
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(15, TimeUnit.SECONDS), "所有线程应在 15 秒内完成");
        assertEquals(0, errors.get(), "并发访问不应有异常: " + errors.get());

        executor.shutdown();
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    /**
     * 构造测试用 DownloadTaskResource。
     */
    private DownloadTaskResource buildResource(String taskId, String status) {
        DownloadTaskResource resource = new DownloadTaskResource();
        resource.setName(taskId);
        resource.setStatus(status);
        DownloadTaskSpec spec = new DownloadTaskSpec();
        spec.setTaskId(taskId);
        spec.setInstanceId(1L);
        spec.setTaskType("URL");
        spec.setTaskUrl("https://example.com/" + taskId + ".vpk");
        spec.setFilename(taskId + ".vpk");
        spec.setTargetPath("addons/");
        spec.setTaskStatus(status);
        spec.setProgress(0.0);
        spec.setDownloadedSize(0L);
        spec.setFileSize(0L);
        spec.setDownloadSpeed(0.0);
        spec.setRetryCount(0);
        spec.setMaxRetry(3);
        spec.setIsDeleted(false);
        spec.setStartTime(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        resource.setSpec(spec);
        return resource;
    }
}

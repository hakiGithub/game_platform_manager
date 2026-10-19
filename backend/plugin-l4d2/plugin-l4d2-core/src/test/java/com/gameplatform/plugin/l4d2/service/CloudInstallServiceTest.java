package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.extension.DownloadTaskResource;
import com.gameplatform.plugin.l4d2.extension.DownloadTaskSpec;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.patch.HostCapabilities;
import com.gameplatform.plugin.patch.PatchInstallService;
import com.gameplatform.plugin.service.CloudDriveService;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.plugin.task.TaskContext;
import com.gameplatform.plugin.task.ChildTaskCancelledException;
import com.gameplatform.plugin.task.ChildTaskTimeoutException;
import com.gameplatform.plugin.task.TaskPayload;
import com.gameplatform.plugin.task.TaskService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CloudInstallServiceTest {

    @Mock private ExtensionClient extensionClient;
    @Mock private CloudDriveService cloudDriveService;
    @Mock private PatchInstallService patchInstallService;
    @Mock private InstanceQueryService instanceQueryService;
    @Mock private InstanceFileService instanceFileService;
    @Mock private TaskService taskService;
    @Mock private DownloadService downloadService;
    @Mock private MapRecognitionService mapRecognitionService;
    @Mock private TaskContext taskContext;

    private CloudInstallService service;
    private DownloadTaskResource taskResource;
    private final L4D2PathResolver pathResolver = new L4D2PathResolver();

    @BeforeEach
    void setUp() throws Exception {
        service = new CloudInstallService(extensionClient, cloudDriveService, patchInstallService,
                instanceQueryService, instanceFileService, taskService, downloadService,
                pathResolver, new L4D2Config(), mapRecognitionService);

        InstanceVO instance = new InstanceVO();
        instance.setId(97L);
        instance.setHostId(2L);
        when(instanceQueryService.getInstanceById(97L)).thenReturn(instance);
        doAnswer(invocation -> null).when(downloadService).acquireDownloadSlot();

        DownloadTaskSpec spec = new DownloadTaskSpec();
        spec.setTaskId("download-1");
        spec.setTaskStatus(DownloadService.STATUS_PENDING);
        taskResource = new DownloadTaskResource();
        taskResource.setName("download-1");
        taskResource.setStatus(DownloadService.STATUS_PENDING);
        taskResource.setSpec(spec);
        when(extensionClient.get(eq(DownloadTaskResource.class), eq("download-1")))
                .thenReturn(Optional.of(taskResource));

        when(cloudDriveService.list(eq("live-189"), anyString(), eq(true)))
                .thenReturn(List.of(new CloudDriveService.CloudFileInfo(
                        "map.rar", "/maps/map.rar", false, 8L, null)));
        when(cloudDriveService.download(eq("live-189"), eq("/maps/map.rar"), any(OutputStream.class)))
                .thenAnswer(invocation -> {
                    OutputStream out = invocation.getArgument(2);
                    out.write(new byte[]{1, 2, 3});
                    return 3L;
                });
    }

    @Test
    void relayDownload_shouldStopWritingWhenTaskIsCancelled() throws Exception {
        CloudInstallService.ProgressOutputStream output = new CloudInstallService.ProgressOutputStream(
                new ByteArrayOutputStream(), 10L, pct -> { }, () -> {
                    throw new ChildTaskCancelledException("任务已取消");
                });

        ChildTaskCancelledException signal = assertThrows(
                ChildTaskCancelledException.class,
                () -> output.write(new byte[]{1, 2, 3}, 0, 3));

        assertEquals("任务已取消", signal.getMessage());
    }

    @Test
    void rarProduct_shouldPersistRelayMode() throws Exception {
        HostCapabilities capabilities = new HostCapabilities();
        capabilities.getTools().put("curl", true);
        when(patchInstallService.probeHost(2L)).thenReturn(capabilities);
        when(taskContext.isCancelled()).thenReturn(false);
        when(taskContext.isTimeout()).thenReturn(false);

        // RAR 在 DIRECT 分支被跳过并进入 RELAY；用取消信号在解压前结束流程，
        // 这样测试聚焦“模式已写为 RELAY”，不依赖真实 RAR 解压工具。
        when(cloudDriveService.download(eq("live-189"), anyString(), any(OutputStream.class)))
                .thenAnswer(invocation -> {
                    OutputStream out = invocation.getArgument(2);
                    out.write(new byte[]{1});
                    throw new ChildTaskCancelledException("任务已取消");
                });

        TaskPayload payload = new TaskPayload(Map.of(
                "downloadTaskId", "download-1",
                "instanceId", 97L,
                "accountName", "live-189",
                "shareUrl", "https://cloud.189.cn/t/share",
                "cloudPath", "/maps/share-80f0499c"));

        ChildTaskCancelledException ex = assertThrows(ChildTaskCancelledException.class,
                () -> service.execute(taskContext, payload));

        assertTrue(ex.getMessage().contains("任务已取消"));
        ArgumentCaptor<DownloadTaskResource> captor = ArgumentCaptor.forClass(DownloadTaskResource.class);
        verify(extensionClient, atLeastOnce()).update(captor.capture());
        // update 可能被多次调用（begin/progress/mode）；最后一次成功记录的 spec 必须为 RELAY。
        DownloadTaskResource updated = captor.getValue();
        assertEquals(CloudInstallService.MODE_RELAY, updated.getSpec().getTransferMode());
        assertEquals(DownloadService.STATUS_CANCELLED, updated.getSpec().getTaskStatus());
    }

    @Test
    void relayDownload_whenCancelledAfterHostWrapsException_shouldPersistCancelled() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        when(taskContext.isCancelled()).thenAnswer(invocation -> cancelled.get());
        when(taskContext.isTimeout()).thenReturn(false);
        when(cloudDriveService.download(eq("live-189"), anyString(), any(OutputStream.class)))
                .thenAnswer(invocation -> {
                    OutputStream out = invocation.getArgument(2);
                    out.write(new byte[]{1});
                    cancelled.set(true);
                    throw new IllegalStateException("下载失败: 任务已取消");
                });

        TaskPayload payload = new TaskPayload(Map.of(
                "downloadTaskId", "download-1",
                "instanceId", 97L,
                "accountName", "live-189",
                "shareUrl", "https://cloud.189.cn/t/share",
                "cloudPath", "/maps/share-80f0499c"));

        ChildTaskCancelledException error = assertThrows(ChildTaskCancelledException.class,
                () -> service.execute(taskContext, payload));

        assertTrue(error.getMessage().contains("任务已取消"));
        ArgumentCaptor<DownloadTaskResource> captor = ArgumentCaptor.forClass(DownloadTaskResource.class);
        verify(extensionClient, atLeastOnce()).update(captor.capture());
        DownloadTaskResource updated = captor.getValue();
        assertEquals(DownloadService.STATUS_CANCELLED, updated.getSpec().getTaskStatus());
        assertFalse(updated.getSpec().getTaskStatus().equals(DownloadService.STATUS_FAILED));
    }

    @Test
    void relayDownload_whenTimeoutAfterHostWrapsException_shouldPersistFailed() {
        AtomicBoolean timeout = new AtomicBoolean(false);
        when(taskContext.isCancelled()).thenReturn(false);
        when(taskContext.isTimeout()).thenAnswer(invocation -> timeout.get());
        when(cloudDriveService.download(eq("live-189"), anyString(), any(OutputStream.class)))
                .thenAnswer(invocation -> {
                    OutputStream out = invocation.getArgument(2);
                    out.write(new byte[]{1});
                    timeout.set(true);
                    throw new IllegalStateException("下载失败: 任务已超时");
                });

        TaskPayload payload = new TaskPayload(Map.of(
                "downloadTaskId", "download-1",
                "instanceId", 97L,
                "accountName", "live-189",
                "shareUrl", "https://cloud.189.cn/t/share",
                "cloudPath", "/maps/share-80f0499c"));

        ChildTaskTimeoutException error = assertThrows(ChildTaskTimeoutException.class,
                () -> service.execute(taskContext, payload));

        assertTrue(error.getMessage().contains("任务已超时"));
        ArgumentCaptor<DownloadTaskResource> captor = ArgumentCaptor.forClass(DownloadTaskResource.class);
        verify(extensionClient, atLeastOnce()).update(captor.capture());
        DownloadTaskResource updated = captor.getValue();
        assertEquals(DownloadService.STATUS_FAILED, updated.getSpec().getTaskStatus());
    }

    @Test
    void relayDownload_nonCancellationFailure_shouldPersistFailed() {
        when(taskContext.isCancelled()).thenReturn(false);
        when(taskContext.isTimeout()).thenReturn(false);
        when(cloudDriveService.download(eq("live-189"), anyString(), any(OutputStream.class)))
                .thenThrow(new IllegalStateException("下载失败: 网络中断"));

        TaskPayload payload = new TaskPayload(Map.of(
                "downloadTaskId", "download-1",
                "instanceId", 97L,
                "accountName", "live-189",
                "shareUrl", "https://cloud.189.cn/t/share",
                "cloudPath", "/maps/share-80f0499c"));

        assertThrows(com.gameplatform.plugin.l4d2.exception.L4D2PluginException.class,
                () -> service.execute(taskContext, payload));

        ArgumentCaptor<DownloadTaskResource> captor = ArgumentCaptor.forClass(DownloadTaskResource.class);
        verify(extensionClient, atLeastOnce()).update(captor.capture());
        DownloadTaskResource updated = captor.getValue();
        assertEquals(DownloadService.STATUS_FAILED, updated.getSpec().getTaskStatus());
    }
}

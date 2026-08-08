package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.dto.PluginStoreDownloadDTO;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.util.GitHubApiClient;
import com.gameplatform.plugin.l4d2.util.GitHubApiClient.TreeEntry;
import com.gameplatform.plugin.l4d2.vo.PluginStoreDetailVO;
import com.gameplatform.plugin.l4d2.vo.PluginStoreDownloadTaskVO;
import com.gameplatform.plugin.l4d2.vo.PluginStoreItemVO;
import com.gameplatform.plugin.service.InstanceFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PluginStoreService 单元测试。
 *
 * <p>GitHubApiClient、ExternalHttpClient、PluginInstallService 均通过 Mockito mock，
 * 不发起真实网络请求。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PluginStoreServiceTest {

    private static final String LFS_POINTER =
            "version https://git-lfs.github.com/spec/v1\n" +
                    "oid sha256:abc123def456\n" +
                    "size 5000";

    private static final String LFS_OID = "abc123def456";
    private static final String LFS_DOWNLOAD_URL = "https://download.example.com/lfs/test.zip";

    @Mock
    private GitHubApiClient gitHubApiClient;

    @Mock
    private ExternalHttpClient httpClient;

    @Mock
    private PluginInstallService pluginInstallService;

    @Mock
    private PluginMetaService pluginMetaService;

    @Mock
    private L4D2PathResolver pathResolver;

    @Mock
    private InstanceFileService instanceFileService;

    private L4D2Config config;

    private PluginStoreService service;

    private File tempFile;

    @BeforeEach
    void setUp() throws Exception {
        config = new L4D2Config();
        service = new PluginStoreService(gitHubApiClient, httpClient, pluginInstallService,
                pluginMetaService, config, pathResolver, instanceFileService);
        tempFile = File.createTempFile("plugin-store-test-", ".zip");
        tempFile.deleteOnExit();
    }

    @Test
    void list_shouldReturnAllPluginsWhenNoFilter() {
        when(gitHubApiClient.getTree()).thenReturn(buildTestTree());

        List<PluginStoreItemVO> result = service.list(null, null);

        assertEquals(3, result.size());
        verify(gitHubApiClient, times(1)).getTree();
    }

    @Test
    void list_shouldFilterByKeyword() {
        when(gitHubApiClient.getTree()).thenReturn(buildTestTree());

        List<PluginStoreItemVO> result = service.list("plugin", null);

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(i -> i.getPluginId().contains("plugin")));
    }

    @Test
    void list_shouldFilterByAnotherKeyword() {
        when(gitHubApiClient.getTree()).thenReturn(buildTestTree());

        List<PluginStoreItemVO> result = service.list("another", null);

        assertEquals(1, result.size());
        assertEquals("another-1", result.get(0).getPluginId());
    }

    @Test
    void list_shouldFilterByCategory() {
        when(gitHubApiClient.getTree()).thenReturn(buildTestTree());

        // 所有插件分类都是 "plugin"
        List<PluginStoreItemVO> result = service.list(null, "plugin");
        assertEquals(3, result.size());

        List<PluginStoreItemVO> empty = service.list(null, "non-existent");
        assertTrue(empty.isEmpty());
    }

    @Test
    void list_shouldUseCacheOnSecondCall() {
        when(gitHubApiClient.getTree()).thenReturn(buildTestTree());

        // 第一次调用：触发 getTree
        List<PluginStoreItemVO> first = service.list(null, null);
        assertEquals(3, first.size());

        // 第二次调用：应使用缓存，不再调用 getTree
        List<PluginStoreItemVO> second = service.list(null, null);
        assertEquals(3, second.size());

        // 只调用过一次
        verify(gitHubApiClient, times(1)).getTree();
    }

    @Test
    void list_shouldReturnEmptyWhenTreeEmpty() {
        when(gitHubApiClient.getTree()).thenReturn(List.of());

        List<PluginStoreItemVO> result = service.list(null, null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void list_shouldIncludeAllPluginDirsUnderPluginsPrefix() {
        // 新逻辑：plugins/ 下的所有目录都显示（不要求 README+zip）
        // plugin-1 只有 zip，plugin-2 只有 README，another-1 两个都有
        List<TreeEntry> tree = List.of(
                new TreeEntry("plugins/plugin-1/plugin.zip", "blob", "zip-1", 1000),
                new TreeEntry("plugins/plugin-2/README.md", "blob", "readme-2", 200),
                new TreeEntry("plugins/another-1/README.md", "blob", "readme-3", 50),
                new TreeEntry("plugins/another-1/plugin.zip", "blob", "zip-3", 1500)
        );
        when(gitHubApiClient.getTree()).thenReturn(tree);

        List<PluginStoreItemVO> result = service.list(null, null);
        // 3 个插件目录都应显示
        assertEquals(3, result.size());
        // another-1 的总大小 = 50 + 1500 = 1550
        PluginStoreItemVO another = result.stream()
                .filter(i -> "another-1".equals(i.getPluginId()))
                .findFirst().orElse(null);
        assertNotNull(another);
        assertEquals(1550L, another.getSize());
        assertEquals(2, another.getFileCount());
    }

    @Test
    void detail_shouldAssembleDetailVO() {
        when(gitHubApiClient.getTree()).thenReturn(buildTestTree());
        when(gitHubApiClient.getBlobContent("readme-sha-1")).thenReturn("# Plugin 1\nTest README");

        PluginStoreDetailVO vo = service.detail("plugin-1");

        assertEquals("plugin-1", vo.getPluginId());
        assertEquals("plugin-1", vo.getName());
        assertEquals("plugin", vo.getCategory());
        // size 为插件目录下所有文件总大小：README(100) + plugin.zip(5000) = 5100
        assertEquals(5100L, vo.getSize());
        assertEquals("# Plugin 1\nTest README", vo.getReadme());
        assertNotNull(vo.getFileList());
        // 文件列表应包含 plugins/plugin-1/ 下所有 blob
        assertEquals(2, vo.getFileList().size());
        assertTrue(vo.getFileList().stream()
                .anyMatch(f -> "plugins/plugin-1/README.md".equals(f.getPath())));
        assertTrue(vo.getFileList().stream()
                .anyMatch(f -> "plugins/plugin-1/plugin.zip".equals(f.getPath()) && f.getSize() == 5000L));
    }

    @Test
    void detail_shouldThrowWhenPluginNotExists() {
        when(gitHubApiClient.getTree()).thenReturn(buildTestTree());

        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> service.detail("non-existent-plugin"));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
    }

    @Test
    void detail_shouldThrowWhenPluginIdBlank() {
        assertThrows(L4D2PluginException.class, () -> service.detail(""));
        assertThrows(L4D2PluginException.class, () -> service.detail(null));
    }

    @Test
    void readme_shouldReturnReadmeContent() {
        when(gitHubApiClient.getTree()).thenReturn(buildTestTree());
        when(gitHubApiClient.getBlobContent("readme-sha-1")).thenReturn("README content");

        String content = service.readme("plugin-1");
        assertEquals("README content", content);
    }

    @Test
    void readme_shouldReturnEmptyWhenReadmeMissing() {
        when(gitHubApiClient.getTree()).thenReturn(buildTestTree());

        // 构造无 README.md 的 plugin-3 目录（plugins/plugin-3/ 下只有 plugin.zip）
        List<TreeEntry> tree = List.of(
                new TreeEntry("plugins/plugin-3/plugin.zip", "blob", "zip-3", 1000)
        );
        when(gitHubApiClient.getTree()).thenReturn(tree);

        String content = service.readme("plugin-3");
        assertEquals("", content);
    }

    @Test
    void download_shouldCreateTaskAndRunToCompleted() throws Exception {
        setupDownloadMocks("plugin-1", "zip-sha-1");

        PluginStoreDownloadDTO dto = new PluginStoreDownloadDTO();
        dto.setInstanceId(1L);
        dto.setPluginId("plugin-1");

        String taskId = service.download(dto);
        assertNotNull(taskId);
        assertFalse(taskId.isBlank());

        PluginStoreDownloadTaskVO task = waitForTerminalStatus(taskId, 5000);
        assertNotNull(task);
        assertEquals(PluginStoreService.STATUS_COMPLETED, task.getStatus());
        assertEquals(100, task.getProgress());
        assertNotNull(task.getFinishedAt());
        assertEquals(1L, task.getInstanceId());
        assertEquals("plugin-1", task.getPluginId());

        // 新逻辑：逐文件下载后直接 atomicMoveToStore（不再调用 installFromLocalFileToTempDir）
        verify(pluginInstallService, times(1)).atomicMoveToStore(eq(1L), anyString(), eq("plugin-1"));
    }

    @Test
    void download_shouldFailWhenPluginDirMissing() throws Exception {
        // tree 中没有 plugins/plugin-x/ 下的任何文件
        when(gitHubApiClient.getTree()).thenReturn(buildTestTree());

        PluginStoreDownloadDTO dto = new PluginStoreDownloadDTO();
        dto.setInstanceId(1L);
        dto.setPluginId("plugin-x");

        String taskId = service.download(dto);
        PluginStoreDownloadTaskVO task = waitForTerminalStatus(taskId, 5000);

        assertNotNull(task);
        assertEquals(PluginStoreService.STATUS_FAILED, task.getStatus());
        assertNotNull(task.getError());
        verify(pluginInstallService, never()).atomicMoveToStore(anyLong(), anyString(), anyString());
    }

    @Test
    void download_shouldFailWhenBlobContentNull() throws Exception {
        when(gitHubApiClient.getTree()).thenReturn(buildTestTree());
        // blob API 返回 null → 回退到 raw URL 下载
        when(gitHubApiClient.getBlobContent(anyString())).thenReturn(null);
        // raw URL 下载也失败
        when(httpClient.downloadWithRetry(anyString(), anyString(), any(), any(), any(), anyInt()))
                .thenThrow(new RuntimeException("download failed"));

        PluginStoreDownloadDTO dto = new PluginStoreDownloadDTO();
        dto.setInstanceId(1L);
        dto.setPluginId("plugin-1");

        String taskId = service.download(dto);
        PluginStoreDownloadTaskVO task = waitForTerminalStatus(taskId, 5000);

        assertNotNull(task);
        assertEquals(PluginStoreService.STATUS_FAILED, task.getStatus());
        verify(pluginInstallService, never()).atomicMoveToStore(anyLong(), anyString(), anyString());
    }

    @Test
    void download_shouldHandleLfsFiles() throws Exception {
        when(gitHubApiClient.getTree()).thenReturn(buildTestTree());
        // 所有 blob 返回 LFS 指针
        when(gitHubApiClient.getBlobContent(anyString())).thenReturn(LFS_POINTER);
        when(gitHubApiClient.parseLfsPointer(LFS_POINTER))
                .thenReturn(new GitHubApiClient.LfsPointer(LFS_OID, 0L));
        when(gitHubApiClient.batchLfsObjects(anyList()))
                .thenReturn(Map.of(LFS_OID, LFS_DOWNLOAD_URL));
        when(httpClient.downloadWithRetry(anyString(), anyString(), any(), any(), any(), anyInt()))
                .thenReturn(tempFile);

        PluginStoreDownloadDTO dto = new PluginStoreDownloadDTO();
        dto.setInstanceId(1L);
        dto.setPluginId("plugin-1");

        String taskId = service.download(dto);
        PluginStoreDownloadTaskVO task = waitForTerminalStatus(taskId, 5000);

        assertNotNull(task);
        assertEquals(PluginStoreService.STATUS_COMPLETED, task.getStatus());
        verify(pluginInstallService, times(1)).atomicMoveToStore(eq(1L), anyString(), eq("plugin-1"));
    }

    @Test
    void download_shouldThrowWhenInstanceIdNull() {
        PluginStoreDownloadDTO dto = new PluginStoreDownloadDTO();
        dto.setPluginId("plugin-1");

        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> service.download(dto));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
    }

    @Test
    void download_shouldThrowWhenPluginIdBlank() {
        PluginStoreDownloadDTO dto = new PluginStoreDownloadDTO();
        dto.setInstanceId(1L);

        assertThrows(L4D2PluginException.class, () -> service.download(dto));

        dto.setPluginId("");
        assertThrows(L4D2PluginException.class, () -> service.download(dto));
    }

    @Test
    void listTasks_shouldReturnTasksForInstance() throws Exception {
        setupDownloadMocks("plugin-1", "zip-sha-1");

        PluginStoreDownloadDTO dto = new PluginStoreDownloadDTO();
        dto.setInstanceId(1L);
        dto.setPluginId("plugin-1");

        String taskId = service.download(dto);
        waitForTerminalStatus(taskId, 5000);

        List<PluginStoreDownloadTaskVO> tasks1 = service.listTasks(1L);
        assertEquals(1, tasks1.size());
        assertEquals(taskId, tasks1.get(0).getTaskId());

        List<PluginStoreDownloadTaskVO> tasks2 = service.listTasks(2L);
        assertTrue(tasks2.isEmpty());

        // null 返回所有
        List<PluginStoreDownloadTaskVO> all = service.listTasks(null);
        assertEquals(1, all.size());
    }

    @Test
    void cancel_shouldBeNoOpForNonExistentTask() {
        assertDoesNotThrow(() -> service.cancel("non-existent-task-id"));
    }

    @Test
    void cancel_shouldBeNoOpForCompletedTask() throws Exception {
        setupDownloadMocks("plugin-1", "zip-sha-1");

        PluginStoreDownloadDTO dto = new PluginStoreDownloadDTO();
        dto.setInstanceId(1L);
        dto.setPluginId("plugin-1");

        String taskId = service.download(dto);
        waitForTerminalStatus(taskId, 5000);

        // 已 COMPLETED，再 cancel 应为 no-op
        service.cancel(taskId);

        PluginStoreDownloadTaskVO task = service.listTasks(1L).get(0);
        assertEquals(PluginStoreService.STATUS_COMPLETED, task.getStatus());
    }

    @Test
    void cancel_shouldCancelRunningTask() throws Exception {
        when(gitHubApiClient.getTree()).thenReturn(buildTestTree());
        when(gitHubApiClient.getBlobContent(anyString())).thenReturn("file content");
        when(gitHubApiClient.getRawDownloadUrl(anyString())).thenReturn("https://raw.example.com/file");

        // 阻塞 httpClient.downloadWithRetry 直到 latch 释放
        CountDownLatch blockLatch = new CountDownLatch(1);
        when(httpClient.downloadWithRetry(anyString(), anyString(), any(), any(), any(), anyInt()))
                .thenAnswer(inv -> {
                    blockLatch.await(5, TimeUnit.SECONDS);
                    return tempFile;
                });

        PluginStoreDownloadDTO dto = new PluginStoreDownloadDTO();
        dto.setInstanceId(1L);
        dto.setPluginId("plugin-1");

        String taskId = service.download(dto);

        // 等待任务进入 DOWNLOADING 状态
        assertTrue(awaitStatus(taskId, PluginStoreService.STATUS_DOWNLOADING, 2000),
                "任务未进入 DOWNLOADING 状态");

        // 取消
        service.cancel(taskId);

        // 释放下载
        blockLatch.countDown();

        PluginStoreDownloadTaskVO task = waitForTerminalStatus(taskId, 5000);
        assertNotNull(task);
        assertEquals(PluginStoreService.STATUS_CANCELLED, task.getStatus());
        assertNotNull(task.getFinishedAt());
        // 已取消时不应调用 install
        verify(pluginInstallService, never()).installFromLocalFile(anyLong(), any(File.class));
    }

    // ========== 辅助方法 ==========

    private List<TreeEntry> buildTestTree() {
        // 对齐 l4d2-server-next 仓库结构：plugins/{pluginName}/...
        return List.of(
                new TreeEntry("plugins/plugin-1", "tree", "dir-1", 0),
                new TreeEntry("plugins/plugin-1/README.md", "blob", "readme-sha-1", 100),
                new TreeEntry("plugins/plugin-1/plugin.zip", "blob", "zip-sha-1", 5000),
                new TreeEntry("plugins/plugin-2", "tree", "dir-2", 0),
                new TreeEntry("plugins/plugin-2/README.md", "blob", "readme-sha-2", 200),
                new TreeEntry("plugins/plugin-2/plugin.zip", "blob", "zip-sha-2", 8000),
                new TreeEntry("plugins/another-1", "tree", "dir-3", 0),
                new TreeEntry("plugins/another-1/README.md", "blob", "readme-sha-3", 50),
                new TreeEntry("plugins/another-1/plugin.zip", "blob", "zip-sha-3", 1500)
        );
    }

    private void setupDownloadMocks(String pluginId, String zipSha) {
        when(gitHubApiClient.getTree()).thenReturn(buildTestTree());
        // 所有 blob 返回非 LFS 内容（触发 raw URL 下载路径）
        when(gitHubApiClient.getBlobContent(anyString())).thenReturn("file content");
        when(gitHubApiClient.getRawDownloadUrl(anyString())).thenReturn("https://raw.example.com/file");
        when(httpClient.downloadWithRetry(anyString(), anyString(), any(), any(), any(), anyInt()))
                .thenReturn(tempFile);
        // 商店下载走临时目录+原子移动模式：mock 返回插件名列表
        when(pluginInstallService.installFromLocalFileToTempDir(anyLong(), any(File.class), anyString()))
                .thenReturn(List.of(pluginId));
        doNothing().when(pluginInstallService).atomicMoveToStore(anyLong(), anyString(), anyString());
    }

    private PluginStoreDownloadTaskVO waitForTerminalStatus(String taskId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            PluginStoreDownloadTaskVO task = findTask(taskId);
            if (task != null && isTerminal(task.getStatus())) {
                return task;
            }
            TimeUnit.MILLISECONDS.sleep(30);
        }
        return findTask(taskId);
    }

    private boolean awaitStatus(String taskId, String status, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            PluginStoreDownloadTaskVO task = findTask(taskId);
            if (task != null && status.equals(task.getStatus())) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return false;
    }

    private PluginStoreDownloadTaskVO findTask(String taskId) {
        return service.listTasks(null).stream()
                .filter(t -> taskId.equals(t.getTaskId()))
                .findFirst()
                .orElse(null);
    }

    private boolean isTerminal(String status) {
        return PluginStoreService.STATUS_COMPLETED.equals(status)
                || PluginStoreService.STATUS_FAILED.equals(status)
                || PluginStoreService.STATUS_CANCELLED.equals(status);
    }
}

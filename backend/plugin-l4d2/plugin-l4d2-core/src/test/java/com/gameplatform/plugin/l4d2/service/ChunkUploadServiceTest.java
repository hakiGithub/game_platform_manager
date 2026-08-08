package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.extension.ListOptions;
import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.dto.ChunkUploadInitDTO;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.extension.ChunkUploadResource;
import com.gameplatform.plugin.l4d2.extension.ChunkUploadSpec;
import com.gameplatform.plugin.l4d2.vo.ChunkUploadInitVO;
import com.gameplatform.plugin.l4d2.vo.ChunkUploadStatusVO;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ChunkUploadService 单元测试。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChunkUploadServiceTest {

    @Mock
    private ExtensionClient extensionClient;

    @Mock
    private InstanceFileService instanceFileService;

    @Mock
    private InstanceQueryService instanceQueryService;

    private L4D2Config config;

    private ChunkUploadService service;

    private final List<Path> cleanupPaths = new ArrayList<>();

    private static final long MB = 1024L * 1024;
    private static final long GB = 1024L * 1024 * 1024;

    @BeforeEach
    void setUp() {
        config = new L4D2Config();
        config.getChunkUpload().setChunkSizeBytes(5 * MB);
        config.getChunkUpload().setMaxTotalSizeBytes(2 * GB);
        config.getChunkUpload().setExpireMs(6L * 3600 * 1000);
        config.getChunkUpload().setDiskUsageThreshold(0.9);

        service = new ChunkUploadService(extensionClient, instanceFileService, instanceQueryService, config);

        // 默认实例
        InstanceVO instance = new InstanceVO();
        instance.setId(1L);
        instance.setHostId(10L);
        instance.setInstallPath("/home/l4d2");
        lenient().when(instanceQueryService.getInstanceById(1L)).thenReturn(instance);
    }

    @AfterEach
    void tearDown() {
        for (Path p : cleanupPaths) {
            deleteRecursively(p);
        }
        cleanupPaths.clear();
    }

    // ===== init =====

    @Test
    void init_success() {
        ChunkUploadInitDTO dto = new ChunkUploadInitDTO();
        dto.setInstanceId(1L);
        dto.setFilename("test.vpk");
        dto.setTotalSize(10 * MB);
        dto.setTotalChunks(2);

        ChunkUploadInitVO vo = service.init(dto);

        assertNotNull(vo.getUploadId());
        assertEquals(5 * MB, vo.getChunkSize());

        // 验证 Resource 被创建
        ArgumentCaptor<ChunkUploadResource> captor = ArgumentCaptor.forClass(ChunkUploadResource.class);
        verify(extensionClient).create(captor.capture());
        ChunkUploadResource created = captor.getValue();
        assertEquals(vo.getUploadId(), created.getName());
        assertEquals(vo.getUploadId(), created.getSpec().getUploadId());
        assertEquals("UPLOADING", created.getSpec().getStatus());
        assertEquals(0, created.getSpec().getReceivedChunks());
        assertNotNull(created.getSpec().getReceivedIndexes());
        assertTrue(created.getSpec().getReceivedIndexes().isEmpty());
        assertEquals("addons/test.vpk", created.getSpec().getTargetPath());

        // 验证临时目录已创建
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "l4d2-chunk-" + vo.getUploadId());
        assertTrue(tempDir.exists());
        assertTrue(tempDir.isDirectory());
        cleanupPaths.add(tempDir.toPath());
    }

    @Test
    void init_exceedsMaxSize() {
        ChunkUploadInitDTO dto = new ChunkUploadInitDTO();
        dto.setInstanceId(1L);
        dto.setFilename("big.vpk");
        dto.setTotalSize(3 * GB); // 超过 2GB
        dto.setTotalChunks(600);

        L4D2PluginException ex = assertThrows(L4D2PluginException.class, () -> service.init(dto));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
        verify(extensionClient, never()).create(any());
    }

    // ===== uploadChunk =====

    @Test
    void uploadChunk_success() throws IOException {
        String uploadId = "test-upload-success";
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "l4d2-chunk-" + uploadId);
        tempDir.mkdirs();
        cleanupPaths.add(tempDir.toPath());

        ChunkUploadResource resource = buildResource(uploadId, "UPLOADING", 2, new HashSet<>());
        resource.getSpec().setTempDir(tempDir.getAbsolutePath());
        when(extensionClient.get(ChunkUploadResource.class, uploadId)).thenReturn(Optional.of(resource));

        byte[] content = "hello-chunk-0".getBytes();
        MultipartFile chunk = new MockMultipartFile("chunk", "chunk-0", "application/octet-stream", content);

        service.uploadChunk(uploadId, 0, chunk);

        File chunkFile = new File(tempDir, "chunk-0");
        assertTrue(chunkFile.exists());
        assertArrayEquals(content, Files.readAllBytes(chunkFile.toPath()));

        verify(extensionClient).update(resource);
        assertEquals(1, resource.getSpec().getReceivedChunks());
        assertTrue(resource.getSpec().getReceivedIndexes().contains(0));
    }

    @Test
    void uploadChunk_invalidIndex() {
        String uploadId = "test-upload-invalid-idx";
        ChunkUploadResource resource = buildResource(uploadId, "UPLOADING", 2, new HashSet<>());
        when(extensionClient.get(ChunkUploadResource.class, uploadId)).thenReturn(Optional.of(resource));

        MultipartFile chunk = new MockMultipartFile("chunk", "chunk", "application/octet-stream", new byte[]{1});

        // index < 0
        assertThrows(L4D2PluginException.class, () -> service.uploadChunk(uploadId, -1, chunk));
        // index >= totalChunks
        assertThrows(L4D2PluginException.class, () -> service.uploadChunk(uploadId, 2, chunk));

        verify(extensionClient, never()).update(any());
    }

    @Test
    void uploadChunk_wrongStatus() {
        String uploadId = "test-upload-wrong-status";
        ChunkUploadResource resource = buildResource(uploadId, "COMPLETED", 2, new HashSet<>(Arrays.asList(0, 1)));
        when(extensionClient.get(ChunkUploadResource.class, uploadId)).thenReturn(Optional.of(resource));

        MultipartFile chunk = new MockMultipartFile("chunk", "chunk", "application/octet-stream", new byte[]{1});

        assertThrows(L4D2PluginException.class, () -> service.uploadChunk(uploadId, 0, chunk));
        verify(extensionClient, never()).update(any());
    }

    // ===== status =====

    @Test
    void status_success() {
        String uploadId = "test-status";
        ChunkUploadResource resource = buildResource(uploadId, "UPLOADING", 2, new HashSet<>(Set.of(0)));
        resource.getSpec().setReceivedChunks(1);
        when(extensionClient.get(ChunkUploadResource.class, uploadId)).thenReturn(Optional.of(resource));

        ChunkUploadStatusVO vo = service.status(uploadId);

        assertEquals(uploadId, vo.getUploadId());
        assertEquals(2, vo.getTotalChunks());
        assertEquals(1, vo.getReceivedChunks());
        assertEquals("UPLOADING", vo.getStatus());
        assertTrue(vo.getReceivedIndexes().contains(0));
        assertEquals(50.0, vo.getProgress(), 0.001);
    }

    // ===== complete =====

    @Test
    void complete_success() throws IOException {
        String uploadId = "test-complete";
        // 创建临时目录与分片文件
        File tempDir = Files.createTempDirectory("l4d2-chunk-test-complete-").toFile();
        cleanupPaths.add(tempDir.toPath());
        byte[] chunk0 = "part0-".getBytes();
        byte[] chunk1 = "part1".getBytes();
        Files.write(new File(tempDir, "chunk-0").toPath(), chunk0);
        Files.write(new File(tempDir, "chunk-1").toPath(), chunk1);

        ChunkUploadResource resource = buildResource(uploadId, "UPLOADING", 2, new HashSet<>(Arrays.asList(0, 1)));
        resource.getSpec().setReceivedChunks(2);
        resource.getSpec().setTempDir(tempDir.getAbsolutePath());
        resource.getSpec().setOriginalFilename("merged.vpk");
        resource.getSpec().setTargetPath("addons/merged.vpk");
        when(extensionClient.get(ChunkUploadResource.class, uploadId)).thenReturn(Optional.of(resource));

        // 使用 doAnswer 验证合并文件在 uploadLocalFile 调用时存在
        doAnswer(invocation -> {
            String localPath = invocation.getArgument(2);
            File mergedFile = new File(localPath);
            assertTrue(mergedFile.exists(), "合并文件应在 uploadLocalFile 调用时存在");
            assertTrue(mergedFile.length() > 0);
            // 验证合并内容
            byte[] merged = Files.readAllBytes(mergedFile.toPath());
            byte[] expected = new byte[chunk0.length + chunk1.length];
            System.arraycopy(chunk0, 0, expected, 0, chunk0.length);
            System.arraycopy(chunk1, 0, expected, chunk0.length, chunk1.length);
            assertArrayEquals(expected, merged);
            return null;
        }).when(instanceFileService).uploadLocalFile(eq(1L), eq("addons/merged.vpk"), anyString());

        service.complete(uploadId);

        verify(instanceFileService).uploadLocalFile(eq(1L), eq("addons/merged.vpk"), anyString());
        verify(extensionClient).update(resource);
        assertEquals("COMPLETED", resource.getSpec().getStatus());
        assertNotNull(resource.getSpec().getCompletedAt());
        // 临时目录应被删除
        assertFalse(tempDir.exists());
    }

    @Test
    void complete_missingChunks() {
        String uploadId = "test-complete-missing";
        ChunkUploadResource resource = buildResource(uploadId, "UPLOADING", 2, new HashSet<>(Set.of(0)));
        resource.getSpec().setReceivedChunks(1);
        when(extensionClient.get(ChunkUploadResource.class, uploadId)).thenReturn(Optional.of(resource));

        L4D2PluginException ex = assertThrows(L4D2PluginException.class, () -> service.complete(uploadId));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
        verify(instanceFileService, never()).uploadLocalFile(anyLong(), anyString(), anyString());
    }

    // ===== cancel =====

    @Test
    void cancel_success() throws IOException {
        String uploadId = "test-cancel";
        File tempDir = Files.createTempDirectory("l4d2-chunk-test-cancel-").toFile();
        cleanupPaths.add(tempDir.toPath());
        Files.write(new File(tempDir, "chunk-0").toPath(), "data".getBytes());

        ChunkUploadResource resource = buildResource(uploadId, "UPLOADING", 2, new HashSet<>());
        resource.getSpec().setTempDir(tempDir.getAbsolutePath());
        when(extensionClient.get(ChunkUploadResource.class, uploadId)).thenReturn(Optional.of(resource));

        service.cancel(uploadId);

        assertFalse(tempDir.exists());
        verify(extensionClient).delete(ChunkUploadResource.class, uploadId);
    }

    // ===== cleanupExpired =====

    @Test
    void cleanupExpired_removesOldRecords() throws IOException {
        // 旧记录：7 小时前创建（已过期）
        File oldTempDir = Files.createTempDirectory("l4d2-chunk-test-old-").toFile();
        cleanupPaths.add(oldTempDir.toPath());
        ChunkUploadResource oldResource = buildResource("old-upload-id", "UPLOADING", 2, new HashSet<>());
        oldResource.getSpec().setTempDir(oldTempDir.getAbsolutePath());
        oldResource.getSpec().setCreatedAt(LocalDateTime.now().minusHours(7));
        oldResource.setName("old-upload-id");

        // 新记录：1 小时前创建（未过期）
        File newTempDir = Files.createTempDirectory("l4d2-chunk-test-new-").toFile();
        cleanupPaths.add(newTempDir.toPath());
        ChunkUploadResource newResource = buildResource("new-upload-id", "UPLOADING", 2, new HashSet<>());
        newResource.getSpec().setTempDir(newTempDir.getAbsolutePath());
        newResource.getSpec().setCreatedAt(LocalDateTime.now().minusHours(1));
        newResource.setName("new-upload-id");

        when(extensionClient.list(eq(ChunkUploadResource.class), any(ListOptions.class)))
                .thenReturn(Arrays.asList(oldResource, newResource));

        service.cleanupExpired();

        // 旧记录被清理
        assertFalse(oldTempDir.exists(), "旧记录的临时目录应被删除");
        verify(extensionClient).delete(ChunkUploadResource.class, "old-upload-id");
        // 新记录保留
        assertTrue(newTempDir.exists(), "新记录的临时目录应保留");
        verify(extensionClient, never()).delete(ChunkUploadResource.class, "new-upload-id");
    }

    // ===== 辅助方法 =====

    private ChunkUploadResource buildResource(String uploadId, String status, int totalChunks, Set<Integer> receivedIndexes) {
        ChunkUploadResource resource = new ChunkUploadResource();
        resource.setName(uploadId);
        resource.setStatus(status);
        ChunkUploadSpec spec = new ChunkUploadSpec();
        spec.setUploadId(uploadId);
        spec.setInstanceId(1L);
        spec.setOriginalFilename("test.vpk");
        spec.setTotalSize(10 * MB);
        spec.setTotalChunks(totalChunks);
        spec.setReceivedChunks(receivedIndexes == null ? 0 : receivedIndexes.size());
        spec.setTargetPath("addons/test.vpk");
        spec.setStatus(status);
        spec.setCreatedAt(LocalDateTime.now());
        spec.setReceivedIndexes(receivedIndexes == null ? new HashSet<>() : new HashSet<>(receivedIndexes));
        resource.setSpec(spec);
        return resource;
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }
}

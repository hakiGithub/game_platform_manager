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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * L4D2 大文件分片上传服务：初始化、上传分片、查询进度、完成合并、取消、过期清理。
 *
 * <p>临时文件存放在 {@code java.io.tmpdir/l4d2-chunk-{uploadId}/}，
 * 元数据通过 {@link ChunkUploadResource} 持久化（name = uploadId）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkUploadService {

    private static final String STATUS_UPLOADING = "UPLOADING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_EXPIRED = "EXPIRED";
    private static final String STATUS_FAILED = "FAILED";

    private final ExtensionClient extensionClient;
    private final InstanceFileService instanceFileService;
    private final InstanceQueryService instanceQueryService;
    private final L4D2Config config;

    /**
     * 初始化分片上传。
     */
    public ChunkUploadInitVO init(ChunkUploadInitDTO dto) {
        // 1. 校验总大小
        if (dto.getTotalSize() > config.getChunkUpload().getMaxTotalSizeBytes()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "文件大小超过上限: " + dto.getTotalSize() + " > " + config.getChunkUpload().getMaxTotalSizeBytes());
        }
        // 2. 校验实例存在
        InstanceVO instance = instanceQueryService.getInstanceById(dto.getInstanceId());
        if (instance == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "实例不存在: " + dto.getInstanceId());
        }
        // 3. 检查磁盘空间
        checkDiskSpace();
        // 4. 生成 uploadId 与临时目录
        String uploadId = UUID.randomUUID().toString();
        File tempDir = getTempDir(uploadId);
        if (!tempDir.mkdirs()) {
            throw new L4D2PluginException(L4D2PluginException.FILE, "创建临时目录失败: " + tempDir.getAbsolutePath());
        }
        // 5. 计算目标路径
        String targetPath = (dto.getTargetPath() == null || dto.getTargetPath().isBlank())
                ? "addons/" + dto.getFilename()
                : dto.getTargetPath();
        // 6. 创建 ChunkUploadResource
        ChunkUploadResource resource = new ChunkUploadResource();
        ChunkUploadSpec spec = new ChunkUploadSpec();
        spec.setUploadId(uploadId);
        spec.setInstanceId(dto.getInstanceId());
        spec.setOriginalFilename(dto.getFilename());
        spec.setTotalSize(dto.getTotalSize());
        spec.setTotalChunks(dto.getTotalChunks());
        spec.setReceivedChunks(0);
        spec.setTempDir(tempDir.getAbsolutePath());
        spec.setTargetPath(targetPath);
        spec.setStatus(STATUS_UPLOADING);
        spec.setCreatedAt(LocalDateTime.now());
        spec.setReceivedIndexes(new HashSet<>());
        resource.setSpec(spec);
        resource.setName(uploadId);
        resource.setStatus(STATUS_UPLOADING);
        extensionClient.create(resource);
        // 7. 返回 VO
        ChunkUploadInitVO vo = new ChunkUploadInitVO();
        vo.setUploadId(uploadId);
        vo.setChunkSize(config.getChunkUpload().getChunkSizeBytes());
        return vo;
    }

    /**
     * 上传分片。
     */
    public void uploadChunk(String uploadId, int index, MultipartFile chunk) {
        ChunkUploadResource resource = getResource(uploadId);
        ChunkUploadSpec spec = resource.getSpec();
        if (!STATUS_UPLOADING.equals(spec.getStatus())) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "上传状态非法: " + spec.getStatus() + ", uploadId=" + uploadId);
        }
        if (index < 0 || index >= spec.getTotalChunks()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "分片索引越界: index=" + index + ", totalChunks=" + spec.getTotalChunks());
        }
        File chunkFile = getChunkFile(uploadId, index);
        try {
            try (InputStream in = chunk.getInputStream()) {
                Files.copy(in, chunkFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new L4D2PluginException(L4D2PluginException.FILE, "写入分片文件失败: " + chunkFile, e);
        }
        Set<Integer> indexes = spec.getReceivedIndexes();
        if (indexes == null) {
            indexes = new HashSet<>();
            spec.setReceivedIndexes(indexes);
        }
        indexes.add(index);
        spec.setReceivedChunks(indexes.size());
        extensionClient.update(resource);
    }

    /**
     * 查询上传状态。
     */
    public ChunkUploadStatusVO status(String uploadId) {
        ChunkUploadResource resource = getResource(uploadId);
        ChunkUploadSpec spec = resource.getSpec();
        ChunkUploadStatusVO vo = new ChunkUploadStatusVO();
        vo.setUploadId(uploadId);
        vo.setTotalChunks(spec.getTotalChunks());
        vo.setReceivedChunks(spec.getReceivedChunks());
        vo.setReceivedIndexes(spec.getReceivedIndexes());
        vo.setStatus(spec.getStatus());
        double progress = spec.getTotalChunks() > 0
                ? spec.getReceivedChunks() * 100.0 / spec.getTotalChunks()
                : 0.0;
        vo.setProgress(progress);
        return vo;
    }

    /**
     * 完成上传：合并分片并上传到远程主机。
     */
    public void complete(String uploadId) {
        ChunkUploadResource resource = getResource(uploadId);
        ChunkUploadSpec spec = resource.getSpec();
        if (spec.getReceivedChunks() != spec.getTotalChunks()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "分片未接收完整: received=" + spec.getReceivedChunks() + ", total=" + spec.getTotalChunks());
        }
        File mergedFile = new File(spec.getTempDir(), spec.getOriginalFilename());
        try {
            mergeChunks(spec, mergedFile);
        } catch (IOException e) {
            spec.setStatus(STATUS_FAILED);
            extensionClient.update(resource);
            throw new L4D2PluginException(L4D2PluginException.FILE, "合并分片失败: " + uploadId, e);
        }
        try {
            instanceFileService.uploadLocalFile(spec.getInstanceId(), spec.getTargetPath(),
                    mergedFile.getAbsolutePath());
        } catch (L4D2PluginException e) {
            throw e;
        } catch (Exception e) {
            spec.setStatus(STATUS_FAILED);
            extensionClient.update(resource);
            throw new L4D2PluginException(L4D2PluginException.NETWORK, "上传到远程失败: " + uploadId, e);
        }
        spec.setStatus(STATUS_COMPLETED);
        spec.setCompletedAt(LocalDateTime.now());
        extensionClient.update(resource);
        deleteTempDirQuietly(spec.getTempDir());
    }

    /**
     * 取消上传：清理临时文件并删除记录。
     */
    public void cancel(String uploadId) {
        ChunkUploadResource resource = getResource(uploadId);
        deleteTempDirQuietly(resource.getSpec().getTempDir());
        extensionClient.delete(ChunkUploadResource.class, uploadId);
    }

    /**
     * 过期清理：每小时扫描，删除 6 小时未完成的记录与临时文件。
     */
    @Scheduled(fixedRate = 3600_000)
    public void cleanupExpired() {
        ListOptions opts = ListOptions.builder()
                .specFilter("$.status", "=", STATUS_UPLOADING)
                .build();
        List<ChunkUploadResource> list = extensionClient.list(ChunkUploadResource.class, opts);
        LocalDateTime now = LocalDateTime.now();
        long expireMs = config.getChunkUpload().getExpireMs();
        for (ChunkUploadResource resource : list) {
            ChunkUploadSpec spec = resource.getSpec();
            if (spec == null || spec.getCreatedAt() == null) {
                continue;
            }
            LocalDateTime expireAt = spec.getCreatedAt().plusNanos(expireMs * 1_000_000L);
            if (expireAt.isBefore(now)) {
                log.info("清理过期分片上传记录 uploadId={}, createdAt={}", spec.getUploadId(), spec.getCreatedAt());
                deleteTempDirQuietly(spec.getTempDir());
                try {
                    extensionClient.delete(ChunkUploadResource.class, resource.getName());
                } catch (Exception e) {
                    log.warn("删除过期 Resource 失败 uploadId={}, err={}", spec.getUploadId(), e.getMessage());
                }
            }
        }
    }

    // ===== 私有辅助方法 =====

    private ChunkUploadResource getResource(String uploadId) {
        Optional<ChunkUploadResource> opt = extensionClient.get(ChunkUploadResource.class, uploadId);
        if (opt.isEmpty() || opt.get().getSpec() == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "上传记录不存在: " + uploadId);
        }
        return opt.get();
    }

    private void checkDiskSpace() {
        File tmpDir = new File(System.getProperty("java.io.tmpdir"));
        long total = tmpDir.getTotalSpace();
        long free = tmpDir.getFreeSpace();
        if (total <= 0) {
            return;
        }
        double usage = 1.0 - (double) free / total;
        if (usage > config.getChunkUpload().getDiskUsageThreshold()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "磁盘使用率超过阈值: " + String.format("%.2f%%", usage * 100));
        }
    }

    private File getTempDir(String uploadId) {
        return new File(System.getProperty("java.io.tmpdir"), "l4d2-chunk-" + uploadId);
    }

    private File getChunkFile(String uploadId, int index) {
        return new File(getTempDir(uploadId), "chunk-" + index);
    }

    private void mergeChunks(ChunkUploadSpec spec, File mergedFile) throws IOException {
        File tempDir = new File(spec.getTempDir());
        // 使用 TreeSet 保证按 index 升序合并
        TreeSet<Integer> sortedIndexes = new TreeSet<>(spec.getReceivedIndexes());
        try (FileOutputStream out = new FileOutputStream(mergedFile)) {
            for (Integer index : sortedIndexes) {
                File chunkFile = new File(tempDir, "chunk-" + index);
                if (!chunkFile.exists()) {
                    throw new IOException("分片文件缺失: chunk-" + index);
                }
                try (FileInputStream in = new FileInputStream(chunkFile)) {
                    in.transferTo(out);
                }
            }
        }
    }

    private void deleteTempDirQuietly(String tempDirPath) {
        if (tempDirPath == null || tempDirPath.isBlank()) {
            return;
        }
        Path path = Paths.get(tempDirPath);
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("删除临时文件失败 path={}, err={}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("删除临时目录失败 path={}, err={}", tempDirPath, e.getMessage());
        }
    }
}

package com.gameplatform.clouddrive;

import com.gameplatform.clouddrive.extension.CloudAccountResource;
import com.gameplatform.common.exception.BusinessException;
import com.haki.clouddrive.core.domain.JobRecord;
import com.haki.clouddrive.core.model.LinkOptions;
import com.haki.clouddrive.core.model.LinkResolution;
import com.haki.clouddrive.core.model.ListResult;
import com.haki.clouddrive.core.model.CloudObject;
import com.haki.clouddrive.core.transfer.TransferRequest;
import com.haki.clouddrive.sdk.CloudDriveClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 云盘宿主能力执行器（ADR-0024）：传输层实现，账号按 name 寻址。
 * 调用方标识（插件 ID 或 main-app）统一写入日志审计。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CloudDriveExecutor {

    private static final long POLL_INTERVAL_MS = 500L;

    private final CloudAccountService accountService;
    private final CloudDriveClient client;
    private final CloudDriveProperties properties;

    public List<com.gameplatform.plugin.service.CloudDriveService.CloudFileInfo> list(
            String accountName, String path, boolean refresh, String caller) {
        ListResult page;
        try {
            page = client.list(unifiedPath(accountName, path), refresh);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("列目录失败: " + e.getMessage());
        }
        log.info("[CloudDrive][{}] list account={} path={} refresh={} -> {} 项",
                caller, accountName, path, refresh, page.items().size());
        return page.items().stream()
                .map(this::toFileInfo)
                .toList();
    }

    public com.gameplatform.plugin.service.CloudDriveService.CloudLink link(
            String accountName, String path, String caller) {
        LinkResolution resolution;
        try {
            resolution = client.link(unifiedPath(accountName, path), LinkOptions.forApi());
        } catch (Exception e) {
            throw new BusinessException("直链解析失败: " + e.getMessage());
        }
        if (resolution instanceof LinkResolution.Available available) {
            log.info("[CloudDrive][{}] link account={} path={}", caller, accountName, path);
            return new com.gameplatform.plugin.service.CloudDriveService.CloudLink(
                    available.url(),
                    available.headers(),
                    available.expiresAt() == null ? null : available.expiresAt().toEpochMilli());
        }
        LinkResolution.Unavailable unavailable = (LinkResolution.Unavailable) resolution;
        throw new BusinessException("直链不可用: " + unavailable.reason()
                + (unavailable.retryable() ? "（可重试）" : ""));
    }

    public long download(String accountName, String path, OutputStream target, String caller) {
        try {
            long bytes = client.download(unifiedPath(accountName, path), target);
            log.info("[CloudDrive][{}] download account={} path={} -> {} bytes", caller, accountName, path, bytes);
            return bytes;
        } catch (Exception e) {
            throw new BusinessException("下载失败: " + e.getMessage());
        }
    }

    /**
     * 同步转存（ADR-0024）：submitTransfer + 轮询到终态；超时抛错并附 jobId。
     * SDK 无取消作业 API，超时后底层作业继续后台执行。
     */
    public List<String> transfer(String accountName, String shareUrl, String targetPath,
                                 Duration timeout, String caller) {
        Duration effectiveTimeout = timeout == null
                ? Duration.ofMillis(properties.getTransferTimeoutMillis())
                : timeout;
        String target = unifiedPath(accountName, targetPath);

        String jobId;
        try {
            jobId = client.submitTransfer(TransferRequest.of(shareUrl, target));
        } catch (Exception e) {
            throw new BusinessException("提交转存失败: " + e.getMessage());
        }
        log.info("[CloudDrive][{}] transfer account={} share={} -> {} jobId={}",
                caller, accountName, shareUrl, target, jobId);

        long deadline = System.currentTimeMillis() + effectiveTimeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Optional<JobRecord> job = client.getJob(jobId);
            if (job.isPresent()) {
                JobRecord record = job.get();
                switch (record.status()) {
                    case COMPLETED -> {
                        List<String> result = record.resultFiles() == null ? List.of() : record.resultFiles();
                        log.info("[CloudDrive][{}] transfer jobId={} 完成，产物 {} 项", caller, jobId, result.size());
                        return result;
                    }
                    case FAILED -> throw new BusinessException("转存失败: "
                            + (record.message() == null ? record.failureKind() : record.message()));
                    default -> { /* RUNNING：继续轮询 */ }
                }
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException("转存等待被中断: jobId=" + jobId);
            }
        }
        throw new BusinessException("转存超时（" + effectiveTimeout.toSeconds() + "s），jobId="
                + jobId + "；底层作业仍在后台执行，可稍后重试");
    }

    // ── 内部 ────────────────────────────────────────────────

    private String unifiedPath(String accountName, String relativePath) {
        CloudAccountResource res = accountService.get(accountName)
                .orElseThrow(() -> new BusinessException("云盘账号不存在: " + accountName));
        String base = accountService.mountPath(res);
        if (relativePath == null || relativePath.isBlank() || relativePath.equals("/")) {
            return base + "/";
        }
        return base + (relativePath.startsWith("/") ? relativePath : "/" + relativePath);
    }

    private com.gameplatform.plugin.service.CloudDriveService.CloudFileInfo toFileInfo(CloudObject obj) {
        return new com.gameplatform.plugin.service.CloudDriveService.CloudFileInfo(
                obj.name(), obj.path(), obj.directory(), obj.size(),
                obj.modifiedAt() == null ? null : obj.modifiedAt().toEpochMilli());
    }
}

package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.util.GbkCodecUtil;
import com.gameplatform.plugin.l4d2.vo.LogFileInfoVO;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.FileAccessService.FileInfo;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SourceMod 日志服务：日志列表、内容读取、SSE 实时流。
 *
 * <p>SSE 使用独立线程池（4 线程）轮询远程文件增量，并在 emitter 完成/超时/错误时
 * 取消定时任务，避免资源泄漏。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceModLogService {

    private static final Pattern LOG_PATTERN = Pattern.compile("(L\\d{8}\\.log|errors_\\d{8}\\.log)");
    private static final Pattern ERROR_LOG_PATTERN = Pattern.compile("errors_\\d{8}\\.log");
    private static final long MAX_CONTENT_BYTES = 200 * 1024;
    private static final long SSE_TAIL_INTERVAL_MS = 1000;

    private final InstanceFileService instanceFileService;
    private final InstanceQueryService instanceQueryService;
    private final L4D2PathResolver pathResolver;
    private final Charset gbk = GbkCodecUtil.gbk();
    private final ScheduledExecutorService sseScheduler = Executors.newScheduledThreadPool(4);

    /**
     * 列出 SourceMod 日志目录下匹配的日志文件，按修改时间倒序。
     */
    public List<LogFileInfoVO> listFiles(Long instanceId) {
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            return List.of();
        }
        String logsPath = pathResolver.getSourceModLogsPath();
        List<FileInfo> files;
        try {
            files = instanceFileService.listFiles(instanceId, logsPath);
        } catch (Exception e) {
            log.warn("列出日志文件失败 instanceId={}, err={}", instanceId, e.getMessage());
            return List.of();
        }
        return files.stream()
                .filter(f -> !f.isDirectory() && LOG_PATTERN.matcher(f.getName()).matches())
                .sorted(Comparator.comparingLong(FileInfo::getLastModified).reversed())
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    /**
     * 读取日志文件末尾 200KB 内容（自动检测编码）。
     */
    public String getContent(Long instanceId, String file) {
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            return "";
        }
        String path = pathResolver.getSourceModLogsPath() + "/" + file;
        byte[] bytes = instanceFileService.getFileBytes(instanceId, path, -MAX_CONTENT_BYTES, MAX_CONTENT_BYTES);
        return GbkCodecUtil.decodeAuto(bytes);
    }

    /**
     * SSE 实时日志流：先推送末尾 200KB 历史，再按 1s 间隔轮询增量。
     */
    public SseEmitter stream(Long instanceId, String file) {
        SseEmitter emitter = new SseEmitter(0L);
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            log.warn("SSE 流启动失败：实例不存在 instanceId={}", instanceId);
            emitter.complete();
            return emitter;
        }
        String path = pathResolver.getSourceModLogsPath() + "/" + file;

        // 1. 初始推送末尾 200KB 历史
        try {
            byte[] history = instanceFileService.getFileBytes(instanceId, path, -MAX_CONTENT_BYTES, MAX_CONTENT_BYTES);
            String text = GbkCodecUtil.decodeAuto(history);
            for (String line : text.split("\n")) {
                if (!line.isEmpty()) {
                    emitter.send(SseEmitter.event().data(line));
                }
            }
        } catch (Exception e) {
            log.warn("SSE 历史推送失败 path={}, err={}", path, e.getMessage());
        }

        // 2. 轮询增量
        final long[] offset = {getFileEndOffset(instanceId, path)};
        ScheduledFuture<?> future = sseScheduler.scheduleAtFixedRate(() -> {
            try {
                long currentSize = getCurrentFileSize(instanceId, path);
                if (currentSize > offset[0]) {
                    long newOffset = instanceFileService.tailFile(instanceId, path, offset[0], gbk, line -> {
                        try {
                            emitter.send(SseEmitter.event().data(line));
                        } catch (IOException ignored) {
                            // 客户端可能已断开，忽略
                        }
                    });
                    offset[0] = newOffset;
                } else if (currentSize < offset[0]) {
                    // 文件被截断/轮转，重置偏移
                    offset[0] = 0;
                }
            } catch (Exception e) {
                log.warn("SSE tail 错误 path={}, err={}", path, e.getMessage());
            }
        }, SSE_TAIL_INTERVAL_MS, SSE_TAIL_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // 3. 清理：客户端断开/超时/错误时取消定时任务
        Runnable cleanup = () -> future.cancel(false);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(t -> cleanup.run());

        return emitter;
    }

    private LogFileInfoVO toVO(FileInfo f) {
        LogFileInfoVO vo = new LogFileInfoVO();
        vo.setName(f.getName());
        vo.setPath(f.getPath());
        vo.setSize(f.getSize());
        vo.setLastModified(f.getLastModified());
        vo.setErrorLog(ERROR_LOG_PATTERN.matcher(f.getName()).matches());
        return vo;
    }

    private long getFileEndOffset(Long instanceId, String path) {
        try {
            FileInfo info = instanceFileService.getFileInfo(instanceId, path);
            return info != null ? info.getSize() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private long getCurrentFileSize(Long instanceId, String path) {
        try {
            FileInfo info = instanceFileService.getFileInfo(instanceId, path);
            return info != null ? info.getSize() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}

package com.gameplatform.plugin.l4d2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.vo.SystemMetricVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 监控指标临时文件存储。
 *
 * <p>每个实例每天一个 NDJSON 文件，采集时只追加一行；历史查询流式读取并按桶聚合，
 * 不把完整历史载入内存。文件位于 {@code java.io.tmpdir/l4d2-monitor/}。
 */
@Slf4j
@Service
public class MonitorMetricFileStore {

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ObjectMapper objectMapper;
    private final L4D2Config config;
    private final Path rootDir;

    @Autowired
    public MonitorMetricFileStore(ObjectMapper objectMapper, L4D2Config config) {
        this(objectMapper, config,
                Path.of(System.getProperty("java.io.tmpdir"), "l4d2-monitor"));
    }

    MonitorMetricFileStore(ObjectMapper objectMapper, L4D2Config config, Path rootDir) {
        this.objectMapper = objectMapper;
        this.config = config;
        this.rootDir = rootDir;
    }

    /**
     * 追加一条指标。同步写入，调用方已经按实例串行采集。
     */
    public void append(long instanceId, SystemMetricVO metric) {
        if (metric == null || metric.getTimestamp() == null) {
            return;
        }
        Path file = fileFor(instanceId, metric.getTimestamp());
        try {
            Files.createDirectories(file.getParent());
            String line = objectMapper.writeValueAsString(metric);
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException e) {
            log.warn("写入监控临时文件失败 instanceId={}, file={}, err={}",
                    instanceId, file, e.getMessage());
        }
    }

    /**
     * 流式扫描指定时间范围，按 maxPoints / downsampleTo 在读取过程中聚合。
     */
    public List<SystemMetricVO> query(long instanceId, long startMs, long endMs) {
        int maxPoints = Math.max(1, config.getMonitor().getMaxPoints());
        int downsampleTo = Math.max(1, config.getMonitor().getDownsampleTo());
        long duration = Math.max(1L, endMs - startMs);
        long bucketSize = Math.max(1L, duration / downsampleTo);
        Aggregator aggregator = new Aggregator(maxPoints, downsampleTo, startMs, bucketSize);
        forEachMetric(instanceId, startMs, endMs, aggregator);
        return aggregator.finish();
    }

    /**
     * 删除保留期之前的文件。
     */
    public int cleanupExpired() {
        if (!Files.isDirectory(rootDir)) {
            return 0;
        }
        long expireBefore = System.currentTimeMillis() - config.getMonitor().getRetentionMs();
        int deleted = 0;
        try (Stream<Path> files = Files.walk(rootDir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                try {
                    if (Files.getLastModifiedTime(file).toMillis() < expireBefore) {
                        Files.deleteIfExists(file);
                        deleted++;
                    }
                } catch (IOException e) {
                    log.warn("删除过期监控文件失败 file={}, err={}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("扫描监控临时目录失败 dir={}, err={}", rootDir, e.getMessage());
        }
        return deleted;
    }

    /**
     * 删除指定实例的全部临时文件。
     */
    public void deleteInstance(long instanceId) {
        Path dir = rootDir.resolve(String.valueOf(instanceId));
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("删除实例监控临时文件失败 path={}, err={}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("删除实例监控目录失败 dir={}, err={}", dir, e.getMessage());
        }
    }

    private void forEachMetric(long instanceId, long startMs, long endMs, Consumer<SystemMetricVO> consumer) {
        Path dir = rootDir.resolve(String.valueOf(instanceId));
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList()) {
                readFile(file, startMs, endMs, consumer);
            }
        } catch (IOException e) {
            log.warn("扫描实例监控文件失败 instanceId={}, err={}", instanceId, e.getMessage());
        }
    }

    /**
     * 返回最近一条指标。按日期倒序读取文件，命中后立即停止，不扫描完整历史。
     */
    public SystemMetricVO latest(long instanceId) {
        Path dir = rootDir.resolve(String.valueOf(instanceId));
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> ordered = files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::getFileName).reversed())
                    .toList();
            for (Path file : ordered) {
                SystemMetricVO latest = latestInFile(file);
                if (latest != null) {
                    return latest;
                }
            }
        } catch (IOException e) {
            log.warn("读取实例最新监控指标失败 instanceId={}, err={}", instanceId, e.getMessage());
        }
        return null;
    }

    private SystemMetricVO latestInFile(Path file) {
        SystemMetricVO latest = null;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    SystemMetricVO metric = objectMapper.readValue(line, SystemMetricVO.class);
                    if (metric.getTimestamp() != null
                            && (latest == null || metric.getTimestamp() > latest.getTimestamp())) {
                        latest = metric;
                    }
                } catch (IOException e) {
                    log.warn("跳过损坏的监控记录 file={}, err={}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("读取监控文件失败 file={}, err={}", file, e.getMessage());
        }
        return latest;
    }

    private void readFile(Path file, long startMs, long endMs, Consumer<SystemMetricVO> consumer) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    SystemMetricVO metric = objectMapper.readValue(line, SystemMetricVO.class);
                    Long ts = metric.getTimestamp();
                    if (ts != null && ts >= startMs && ts <= endMs) {
                        consumer.accept(metric);
                    }
                } catch (IOException e) {
                    log.warn("跳过损坏的监控记录 file={}, err={}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("读取监控文件失败 file={}, err={}", file, e.getMessage());
        }
    }

    private Path fileFor(long instanceId, long timestamp) {
        String day = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
                .format(DAY_FORMAT);
        return rootDir.resolve(String.valueOf(instanceId)).resolve(day + ".ndjson");
    }

    /** 读取过程中聚合，内存上限始终受 maxPoints 与 downsampleTo 约束。 */
    private static final class Aggregator implements Consumer<SystemMetricVO> {

        private final int maxPoints;
        private final int downsampleTo;
        private final long startMs;
        private final long bucketSize;
        private final List<SystemMetricVO> raw = new ArrayList<>();
        private final Map<Long, SystemMetricVO> buckets = new LinkedHashMap<>();
        private boolean downsampling;

        private Aggregator(int maxPoints, int downsampleTo, long startMs, long bucketSize) {
            this.maxPoints = maxPoints;
            this.downsampleTo = downsampleTo;
            this.startMs = startMs;
            this.bucketSize = bucketSize;
        }

        @Override
        public void accept(SystemMetricVO metric) {
            if (metric == null) {
                return;
            }
            if (!downsampling) {
                raw.add(metric);
                if (raw.size() > maxPoints) {
                    downsampling = true;
                    for (SystemMetricVO item : raw) {
                        addToBucket(item);
                    }
                    raw.clear();
                    compactBuckets();
                }
                return;
            }
            addToBucket(metric);
            if (buckets.size() > maxPoints) {
                compactBuckets();
            }
        }

        private void addToBucket(SystemMetricVO metric) {
            Long ts = metric.getTimestamp();
            if (ts == null) {
                return;
            }
            long bucketIndex = Math.max(0L, (ts - startMs) / bucketSize);
            SystemMetricVO bucket = buckets.get(bucketIndex);
            if (bucket == null) {
                bucket = copy(metric);
                bucket.setTimestamp(startMs + bucketIndex * bucketSize);
                buckets.put(bucketIndex, bucket);
            } else {
                mergeMax(bucket, metric);
            }
        }

        private void compactBuckets() {
            if (buckets.size() <= downsampleTo) {
                return;
            }
            Map<Long, SystemMetricVO> compacted = new LinkedHashMap<>();
            long ordinal = 0;
            for (SystemMetricVO entry : buckets.values()) {
                long compactIndex = ordinal * downsampleTo / buckets.size();
                SystemMetricVO target = compacted.get(compactIndex);
                if (target == null) {
                    compacted.put(compactIndex, copy(entry));
                } else {
                    mergeMax(target, entry);
                }
                ordinal++;
            }
            buckets.clear();
            buckets.putAll(compacted);
        }

        private List<SystemMetricVO> finish() {
            if (!downsampling) {
                return raw;
            }
            compactBuckets();
            List<SystemMetricVO> result = new ArrayList<>(buckets.values());
            result.sort(Comparator.comparing(SystemMetricVO::getTimestamp));
            return result;
        }

        private static SystemMetricVO copy(SystemMetricVO source) {
            SystemMetricVO target = new SystemMetricVO();
            target.setTimestamp(source.getTimestamp());
            copyFields(target, source);
            return target;
        }

        private static void mergeMax(SystemMetricVO target, SystemMetricVO source) {
            target.setCpuPercent(max(target.getCpuPercent(), source.getCpuPercent()));
            target.setCpuMaxCore(max(target.getCpuMaxCore(), source.getCpuMaxCore()));
            target.setMemUsed(max(target.getMemUsed(), source.getMemUsed()));
            target.setMemTotal(max(target.getMemTotal(), source.getMemTotal()));
            target.setSwapUsed(max(target.getSwapUsed(), source.getSwapUsed()));
            target.setNetUpSpeed(max(target.getNetUpSpeed(), source.getNetUpSpeed()));
            target.setNetDownSpeed(max(target.getNetDownSpeed(), source.getNetDownSpeed()));
            target.setDiskUsed(max(target.getDiskUsed(), source.getDiskUsed()));
            target.setDiskTotal(max(target.getDiskTotal(), source.getDiskTotal()));
        }

        private static void copyFields(SystemMetricVO target, SystemMetricVO source) {
            target.setCpuPercent(source.getCpuPercent());
            target.setCpuMaxCore(source.getCpuMaxCore());
            target.setMemUsed(source.getMemUsed());
            target.setMemTotal(source.getMemTotal());
            target.setSwapUsed(source.getSwapUsed());
            target.setNetUpSpeed(source.getNetUpSpeed());
            target.setNetDownSpeed(source.getNetDownSpeed());
            target.setDiskUsed(source.getDiskUsed());
            target.setDiskTotal(source.getDiskTotal());
        }

        private static Double max(Double left, Double right) {
            if (left == null) return right;
            if (right == null) return left;
            return Math.max(left, right);
        }
    }
}

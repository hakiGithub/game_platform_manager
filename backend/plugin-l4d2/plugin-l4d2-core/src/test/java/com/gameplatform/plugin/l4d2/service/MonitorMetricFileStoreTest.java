package com.gameplatform.plugin.l4d2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.vo.SystemMetricVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MonitorMetricFileStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void append_query_filters_time_and_orders_ascending() {
        MonitorMetricFileStore store = store();
        long base = System.currentTimeMillis();
        store.append(1L, metric(base + 2_000, 20.0));
        store.append(1L, metric(base, 10.0));
        store.append(1L, metric(base + 1_000, 15.0));

        List<SystemMetricVO> result = store.query(1L, base, base + 1_500);

        assertEquals(2, result.size());
        assertEquals(base, result.get(0).getTimestamp());
        assertEquals(base + 1_000, result.get(1).getTimestamp());
    }

    @Test
    void query_downsamples_and_caps_memory_bucket_count() {
        L4D2Config config = config();
        config.getMonitor().setMaxPoints(10);
        config.getMonitor().setDownsampleTo(5);
        MonitorMetricFileStore store = new MonitorMetricFileStore(new ObjectMapper(), config, tempDir);
        long base = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            store.append(1L, metric(base + i * 1_000L, i));
        }

        List<SystemMetricVO> result = store.query(1L, base, base + 99_000L);

        assertTrue(result.size() <= 5, "实际桶数=" + result.size());
        assertTrue(result.get(0).getTimestamp() <= result.get(result.size() - 1).getTimestamp());
        assertTrue(result.stream().anyMatch(item -> item.getCpuPercent() >= 90.0));
    }

    @Test
    void latest_reads_latest_value_without_full_range_scan() {
        MonitorMetricFileStore store = store();
        long base = System.currentTimeMillis();
        store.append(1L, metric(base, 10.0));
        store.append(1L, metric(base + 1_000, 30.0));

        SystemMetricVO latest = store.latest(1L);

        assertNotNull(latest);
        assertEquals(base + 1_000, latest.getTimestamp());
    }

    @Test
    void cleanup_expired_deletes_old_files() throws IOException {
        L4D2Config config = config();
        config.getMonitor().setRetentionMs(60_000);
        MonitorMetricFileStore store = new MonitorMetricFileStore(new ObjectMapper(), config, tempDir);
        Path old = tempDir.resolve("1").resolve("old.ndjson");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "{}\n");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(2, ChronoUnit.HOURS)));
        Path fresh = tempDir.resolve("1").resolve("fresh.ndjson");
        Files.writeString(fresh, "{}\n");

        int deleted = store.cleanupExpired();

        assertEquals(1, deleted);
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(fresh));
    }

    @Test
    void delete_instance_removes_directory() throws IOException {
        MonitorMetricFileStore store = store();
        Path dir = tempDir.resolve("9");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("x.ndjson"), "{}\n");

        store.deleteInstance(9L);

        assertFalse(Files.exists(dir));
    }

    private MonitorMetricFileStore store() {
        return new MonitorMetricFileStore(new ObjectMapper(), config(), tempDir);
    }

    private L4D2Config config() {
        L4D2Config config = new L4D2Config();
        config.getMonitor().setMaxPoints(2000);
        config.getMonitor().setDownsampleTo(720);
        config.getMonitor().setRetentionMs(3L * 24 * 3600 * 1000);
        return config;
    }

    private SystemMetricVO metric(long timestamp, double cpu) {
        SystemMetricVO metric = new SystemMetricVO();
        metric.setTimestamp(timestamp);
        metric.setCpuPercent(cpu);
        metric.setCpuMaxCore(cpu);
        metric.setMemUsed(1.0);
        metric.setMemTotal(2.0);
        metric.setSwapUsed(0.0);
        metric.setNetUpSpeed(1.0);
        metric.setNetDownSpeed(2.0);
        metric.setDiskUsed(3.0);
        metric.setDiskTotal(4.0);
        return metric;
    }
}

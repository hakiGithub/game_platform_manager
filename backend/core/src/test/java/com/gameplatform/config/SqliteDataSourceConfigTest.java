package com.gameplatform.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteDataSourceConfigTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("SQLite 连接启用 busy_timeout 并在写锁释放后继续写入")
    void waitsForBusyWriteLock() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("busy.sqlite");
        DataSourceProperties properties = new DataSourceProperties();
        properties.setUrl(url);
        properties.setDriverClassName("org.sqlite.JDBC");

        try (HikariDataSource dataSource = new SqliteDataSourceConfig().dataSource(properties)) {
            assertEquals("30000", dataSource.getDataSourceProperties().getProperty("busy_timeout"));
            assertTrue(dataSource.getConnectionInitSql().contains("journal_mode=WAL"));

            try (Connection first = dataSource.getConnection();
                 Connection second = dataSource.getConnection();
                 Statement firstStatement = first.createStatement();
                 Statement secondStatement = second.createStatement()) {
                firstStatement.execute("CREATE TABLE lock_probe (id INTEGER PRIMARY KEY, value TEXT)");
                first.setAutoCommit(false);
                firstStatement.execute("INSERT INTO lock_probe(value) VALUES ('first')");

                Thread releaseLock = new Thread(() -> {
                    try {
                        Thread.sleep(800);
                        first.commit();
                    } catch (Exception ignored) {
                        // The waiting write assertion below reports the result.
                    }
                });
                releaseLock.start();

                long startedAt = System.nanoTime();
                second.setAutoCommit(false);
                secondStatement.execute("INSERT INTO lock_probe(value) VALUES ('second')");
                second.commit();
                long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
                releaseLock.join(5_000);

                assertTrue(elapsedMs >= 500, "第二个写连接应等待第一个写事务释放锁");
            }
        }
    }
}

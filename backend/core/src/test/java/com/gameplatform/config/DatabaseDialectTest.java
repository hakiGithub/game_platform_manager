package com.gameplatform.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 数据库方言判定单元测试（ADR-0015）。
 *
 * @author GamePlatform
 * @version 1.1.0
 */
class DatabaseDialectTest {

    @Test
    @DisplayName("SQLite 产品名映射到 SQLITE 方言")
    void fromProductNameSqlite() {
        assertEquals(DatabaseDialect.SQLITE, DatabaseDialect.fromProductName("SQLite"));
        assertEquals(DatabaseDialect.SQLITE, DatabaseDialect.fromProductName("SQLite (xerial)"));
    }

    @Test
    @DisplayName("MySQL 与 MariaDB 产品名均映射到 MYSQL 方言")
    void fromProductNameMysql() {
        assertEquals(DatabaseDialect.MYSQL, DatabaseDialect.fromProductName("MySQL"));
        assertEquals(DatabaseDialect.MYSQL, DatabaseDialect.fromProductName("MariaDB"));
        assertEquals(DatabaseDialect.MYSQL,
                DatabaseDialect.fromProductName("MariaDB 10.11.6-MariaDB-log"));
    }

    @Test
    @DisplayName("PostgreSQL 产品名映射到 POSTGRESQL 方言")
    void fromProductNamePostgresql() {
        assertEquals(DatabaseDialect.POSTGRESQL, DatabaseDialect.fromProductName("PostgreSQL"));
        assertEquals(DatabaseDialect.POSTGRESQL, DatabaseDialect.fromProductName("PostgreSQL 16.2"));
    }

    @Test
    @DisplayName("未知产品名与空产品名抛出异常")
    void fromProductNameUnknown() {
        assertThrows(IllegalStateException.class,
                () -> DatabaseDialect.fromProductName("Oracle"));
        assertThrows(IllegalStateException.class,
                () -> DatabaseDialect.fromProductName(null));
        assertThrows(IllegalStateException.class,
                () -> DatabaseDialect.fromProductName(" "));
    }

    @Test
    @DisplayName("方言脚本路径按方言区分")
    void scriptLocations() {
        assertEquals("db/schema-sqlite.sql", DatabaseDialect.SQLITE.schemaLocation());
        assertEquals("db/data-sqlite.sql", DatabaseDialect.SQLITE.dataLocation());
        assertEquals("db/schema-mysql.sql", DatabaseDialect.MYSQL.schemaLocation());
        assertEquals("db/data-mysql.sql", DatabaseDialect.MYSQL.dataLocation());
        assertEquals("db/schema-postgresql.sql", DatabaseDialect.POSTGRESQL.schemaLocation());
        assertEquals("db/data-postgresql.sql", DatabaseDialect.POSTGRESQL.dataLocation());
    }

    @Test
    @DisplayName("仅 MySQL 不支持 CREATE INDEX IF NOT EXISTS")
    void createIndexIfNotExistsSupport() {
        assertEquals(true, DatabaseDialect.SQLITE.supportsCreateIndexIfNotExists());
        assertEquals(false, DatabaseDialect.MYSQL.supportsCreateIndexIfNotExists());
        assertEquals(true, DatabaseDialect.POSTGRESQL.supportsCreateIndexIfNotExists());
    }
}

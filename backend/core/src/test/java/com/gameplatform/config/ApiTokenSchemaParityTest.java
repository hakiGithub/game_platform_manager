package com.gameplatform.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-7: api_token 五处方言脚本奇偶校验（ADR-0029 R2 的廉价守卫）。
 *
 * <p>防止「忘了同步 H2/PG/MySQL」：断言 schema-sqlite / schema-mysql / schema-postgresql /
 * schema-h2（测试脚本）/ V1.11 迁移脚本五份 DDL 的 api_token 列名集合完全一致；
 * 另断言 sqlite 段与 V1.11 段全文一致（同一条 DDL 两处落地）。</p>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
class ApiTokenSchemaParityTest {

    private static final String SQLITE = "db/schema-sqlite.sql";
    private static final String MYSQL = "db/schema-mysql.sql";
    private static final String POSTGRES = "db/schema-postgresql.sql";
    private static final String H2 = "db/schema-h2.sql";
    private static final String V111 = "db/migration/V1.11__api_token.sql";

    private static final Set<String> EXPECTED_COLUMNS = Set.of(
            "id", "name", "token_prefix", "token_hash", "scope", "user_id",
            "expires_at", "revoked", "revoked_at", "last_used_at",
            "create_time", "update_time", "is_deleted", "remark");

    private String read(String classpathLocation) throws IOException {
        return StreamUtils.copyToString(
                new ClassPathResource(classpathLocation).getInputStream(), StandardCharsets.UTF_8);
    }

    /** 提取 CREATE TABLE IF NOT EXISTS api_token (...) 块（MySQL 以 ") ENGINE=..." 闭合）。 */
    private String apiTokenBlock(String sql) {
        int start = sql.indexOf("CREATE TABLE IF NOT EXISTS api_token");
        assertTrue(start >= 0, "应存在 api_token 建表语句");
        int closeLine = sql.indexOf("\n)", start);
        assertTrue(closeLine > start, "api_token 建表语句应正确闭合");
        int lineEnd = sql.indexOf('\n', closeLine + 1);
        int end = lineEnd > 0 ? lineEnd : sql.length();
        return sql.substring(start, end);
    }

    /** 解析建表块内的列名（剔除 PRIMARY/UNIQUE/KEY/CONSTRAINT/FOREIGN 等约束行）。 */
    private Set<String> columnsOf(String sql) {
        String block = apiTokenBlock(sql);
        Set<String> columns = new LinkedHashSet<>();
        for (String rawLine : block.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("--") || line.startsWith("CREATE TABLE") || line.startsWith(")")) {
                continue;
            }
            String first = line.split("[\\s(]")[0].trim().toLowerCase(Locale.ROOT);
            if (first.isEmpty() || first.matches("^(primary|unique|key|constraint|foreign|index)$")) {
                continue;
            }
            columns.add(first);
        }
        return columns;
    }

    /** 规范化文本用于全文比较：去注释行、折叠空白、小写。 */
    private String normalize(String sql) {
        return Arrays.stream(sql.split("\n"))
                .map(String::trim)
                .filter(line -> !line.startsWith("--") && !line.isEmpty())
                .collect(Collectors.joining("\n"))
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    @Test
    @DisplayName("五份方言脚本（sqlite/mysql/pg/h2/V1.11）的 api_token 列名集合一致")
    void columnParityAcrossDialects() throws IOException {
        String sqlite = read(SQLITE);
        String mysql = read(MYSQL);
        String postgres = read(POSTGRES);
        String h2 = read(H2);
        String v111 = read(V111);

        Set<String> sqliteCols = columnsOf(sqlite);
        Set<String> mysqlCols = columnsOf(mysql);
        Set<String> pgCols = columnsOf(postgres);
        Set<String> h2Cols = columnsOf(h2);
        Set<String> v111Cols = columnsOf(v111);

        assertEquals(EXPECTED_COLUMNS, sqliteCols, "sqlite 列应齐全");
        assertEquals(sqliteCols, mysqlCols, "mysql 列应与 sqlite 一致");
        assertEquals(sqliteCols, pgCols, "postgresql 列应与 sqlite 一致");
        assertEquals(sqliteCols, h2Cols, "H2 测试脚本列应与 sqlite 一致（最容易漏的同步点）");
        assertEquals(sqliteCols, v111Cols, "V1.11 迁移列应与 sqlite 一致");
    }

    @Test
    @DisplayName("sqlite 段与 V1.11 段全文一致（去注释折叠空白后逐字符等价）")
    void sqliteSectionMatchesV111() throws IOException {
        String sqliteBlock = apiTokenBlock(read(SQLITE));
        String v111Block = apiTokenBlock(read(V111));

        assertEquals(normalize(sqliteBlock), normalize(v111Block),
                "「新库走 schema、老库走迁移」两条路径必须产出完全相同的结构");
    }

    @Test
    @DisplayName("唯一索引 uk_api_token_hash 在三方言脚本与迁移脚本中均存在")
    void uniqueHashIndexEverywhere() throws IOException {
        Pattern uk = Pattern.compile("uk_api_token_hash");
        assertTrue(uk.matcher(read(SQLITE)).find(), "sqlite 应有 uk_api_token_hash");
        assertTrue(uk.matcher(read(MYSQL)).find(), "mysql 应有 uk_api_token_hash");
        assertTrue(uk.matcher(read(POSTGRES)).find(), "postgresql 应有 uk_api_token_hash");
        assertTrue(uk.matcher(read(V111)).find(), "V1.11 应有 uk_api_token_hash");
        // H2 沿用内联 UNIQUE 风格，无独立索引名，只验列上的 UNIQUE
        Matcher h2Block = Pattern.compile("token_hash[^,]*UNIQUE", Pattern.CASE_INSENSITIVE)
                .matcher(read(H2));
        assertTrue(h2Block.find(), "H2 的 token_hash 列应有内联 UNIQUE");
    }

}

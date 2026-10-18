package com.gameplatform.clouddrive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.config.DatabaseDialect;
import com.gameplatform.config.DatabaseDialectResolver;
import com.gameplatform.plugin.extension.ExtensionStoreInitializer;
import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.extension.ExtensionClientImpl;
import com.gameplatform.plugin.extension.ExtensionIdGenerator;
import com.gameplatform.plugin.extension.ExtensionQueryDialect;
import com.gameplatform.plugin.extension.ExtensionRouter;
import com.gameplatform.plugin.extension.SqliteQueryDialect;
import com.gameplatform.plugin.extension.SnowflakeIdGenerator;
import com.haki.clouddrive.sdk.CloudDriveClient;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证全新 SQLite 启动时，云盘账号启动对账不会早于 extensions 表初始化。
 *
 * <p>这是 E2E 临时库启动的真实回归场景：CloudAccountService 的
 * {@code @PostConstruct} 会立即查询 extensions；若 ExtensionStoreInitializer
 * 尚未初始化，启动会在 "no such table: extensions" 处失败。</p>
 */
class CloudAccountServiceInitializationOrderTest {

    @Test
    void cloudAccountStartupWaitsForExtensionStoreInitializer() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(InitializationOrderConfig.class)) {
            assertThat(context.getBean(CloudAccountService.class)).isNotNull();
            assertThat(context.getBean(JdbcTemplate.class).queryForObject(
                    "SELECT COUNT(*) FROM extensions", Long.class)).isZero();
        }
    }

    @Configuration
    @ComponentScan(
            basePackages = {
                    "com.gameplatform.clouddrive",
                    "com.gameplatform.plugin.extension"
            },
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {CloudAccountService.class, ExtensionStoreInitializer.class}
            )
    )
    static class InitializationOrderConfig {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:cloud-account-init;MODE=MySQL;DB_CLOSE_DELAY=-1",
                    "sa",
                    "");
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        DatabaseDialectResolver databaseDialectResolver() {
            DatabaseDialectResolver resolver = mock(DatabaseDialectResolver.class);
            when(resolver.resolve()).thenReturn(DatabaseDialect.SQLITE);
            return resolver;
        }

        @Bean
        ExtensionRouter extensionRouter() {
            return new ExtensionRouter();
        }

        @Bean
        ExtensionQueryDialect extensionQueryDialect() {
            return new SqliteQueryDialect();
        }

        @Bean
        ExtensionIdGenerator extensionIdGenerator() {
            return new SnowflakeIdGenerator();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ExtensionClient hostExtensionClient(
                JdbcTemplate jdbcTemplate, ExtensionRouter router,
                ExtensionQueryDialect queryDialect, ObjectMapper objectMapper,
                ExtensionIdGenerator idGenerator) {
            return new ExtensionClientImpl(
                    jdbcTemplate, router, "platform", queryDialect,
                    objectMapper, Set.of(), idGenerator);
        }

        @Bean
        CloudDriveClient cloudDriveClient() {
            return mock(CloudDriveClient.class);
        }
    }
}

package com.gameplatform.plugin.extension;

import com.gameplatform.api.extension.AbstractExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 扩展资源查询方言接口。
 * <p>
 * 不同数据库对 JSON 字段过滤的实现不同，由具体方言决定是 SQL 下推还是内存过滤。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface ExtensionQueryDialect {

    /**
     * 执行列表查询，返回反序列化后的资源列表。
     *
     * @param route        路由信息（表名/身份/策略）
     * @param modelClass   资源类型
     * @param opts         查询选项
     * @param jdbcTemplate JDBC 模板
     * @param objectMapper JSON 序列化器
     * @param <T>          资源类型
     * @return 资源列表
     */
    <T extends AbstractExtension<?>> List<T> list(ResolvedRoute route, Class<T> modelClass,
                                                   ListOptions opts, JdbcTemplate jdbcTemplate,
                                                   ObjectMapper objectMapper);

    /**
     * 执行计数查询。
     *
     * @param route        路由信息
     * @param modelClass   资源类型
     * @param opts         查询选项
     * @param jdbcTemplate JDBC 模板
     * @param objectMapper JSON 序列化器（spec/label 内存过滤时需要反序列化）
     * @param <T>          资源类型
     * @return 数量
     */
    <T extends AbstractExtension<?>> long count(ResolvedRoute route, Class<T> modelClass,
                                                 ListOptions opts, JdbcTemplate jdbcTemplate,
                                                 ObjectMapper objectMapper);
}

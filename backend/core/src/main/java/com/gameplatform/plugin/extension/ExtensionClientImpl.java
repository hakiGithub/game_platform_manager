package com.gameplatform.plugin.extension;

import com.gameplatform.api.extension.AbstractExtension;
import com.gameplatform.api.extension.ExtensionMetadata;
import com.gameplatform.plugin.extension.exception.DuplicateExtensionException;
import com.gameplatform.plugin.extension.exception.ExtensionNotFoundException;
import com.gameplatform.plugin.extension.exception.ExtensionStoreException;
import com.gameplatform.plugin.extension.exception.OptimisticLockException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * ExtensionClient 的默认实现。
 * <p>
 * 实例在插件子容器创建时绑定 pluginId，所有 SQL 经 {@link ExtensionRouter} 选表
 * 并强制注入 group_name/kind 过滤，插件代码无法绕过。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public class ExtensionClientImpl implements ExtensionClient {

    private final JdbcTemplate jdbcTemplate;
    private final ExtensionRouter router;
    private final String pluginId;
    private final ExtensionQueryDialect queryDialect;
    private final ObjectMapper objectMapper;
    private final Set<String> ownedTables;
    private final ExtensionIdGenerator idGenerator;

    public ExtensionClientImpl(JdbcTemplate jdbcTemplate, ExtensionRouter router,
                                String pluginId, ExtensionQueryDialect queryDialect,
                                ObjectMapper objectMapper, Set<String> ownedTables,
                                ExtensionIdGenerator idGenerator) {
        this.jdbcTemplate = jdbcTemplate;
        this.router = router;
        this.pluginId = pluginId;
        this.queryDialect = queryDialect;
        this.objectMapper = objectMapper;
        this.ownedTables = ownedTables;
        this.idGenerator = idGenerator;
    }

    @Override
    public <T extends AbstractExtension<?>> void create(T extension) {
        ResolvedRoute route = router.resolve(toModelClass(extension), pluginId);
        long now = System.currentTimeMillis();

        extension.setId(idGenerator.nextId());
        extension.setGroupName(route.group());
        extension.setKind(route.kind());
        extension.setVersion(1);
        if (extension.getStatus() == null) {
            extension.setStatus("ACTIVE");
        }
        if (extension.getMetadata() == null) {
            extension.setMetadata(new ExtensionMetadata());
        }
        extension.getMetadata().setCreationTimestamp(now);
        extension.getMetadata().setUpdateTimestamp(now);

        String sql = "INSERT INTO " + route.table()
                + " (id, name, group_name, kind, version, metadata, spec, status, creation_timestamp, update_timestamp)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try {
            jdbcTemplate.update(sql,
                    extension.getId(),
                    extension.getName(),
                    route.group(),
                    route.kind(),
                    extension.getVersion(),
                    toJson(extension.getMetadata()),
                    toJson(extension.getSpec()),
                    extension.getStatus(),
                    now,
                    now);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new DuplicateExtensionException(
                    "资源已存在: " + route.kind() + "/" + extension.getName());
        } catch (org.springframework.dao.DataAccessException e) {
            // SQLite JDBC 的 PRIMARY KEY/UNIQUE 冲突可能被归类为 UncategorizedSQLException 而非 DuplicateKeyException
            String msg = e.getMostSpecificCause().getMessage();
            if (msg != null && (msg.contains("CONSTRAINT") || msg.contains("PRIMARY KEY") || msg.contains("UNIQUE"))) {
                throw new DuplicateExtensionException(
                        "资源已存在: " + route.kind() + "/" + extension.getName());
            }
            throw new ExtensionStoreException("创建资源失败: " + route.kind() + "/" + extension.getName(), e);
        }
    }

    @Override
    public <T extends AbstractExtension<?>> void update(T extension) {
        ResolvedRoute route = router.resolve(toModelClass(extension), pluginId);
        long now = System.currentTimeMillis();
        if (extension.getMetadata() != null) {
            extension.getMetadata().setUpdateTimestamp(now);
        }

        String sql = "UPDATE " + route.table()
                + " SET spec=?, metadata=?, version=version+1, status=?, update_timestamp=?"
                + " WHERE id=? AND version=?";
        int affected;
        try {
            affected = jdbcTemplate.update(sql,
                    toJson(extension.getSpec()),
                    toJson(extension.getMetadata()),
                    extension.getStatus(),
                    now,
                    extension.getId(),
                    extension.getVersion());
        } catch (Exception e) {
            throw new ExtensionStoreException("更新资源失败: " + route.kind() + "/" + extension.getName(), e);
        }
        if (affected == 0) {
            // 检查是否存在
            if (get(toModelClass(extension), extension.getName()).isPresent()) {
                throw new OptimisticLockException(
                        "版本冲突: " + route.kind() + "/" + extension.getName());
            }
            throw new ExtensionNotFoundException(
                    "资源不存在: " + route.kind() + "/" + extension.getName());
        }
        // 更新 version
        extension.setVersion(extension.getVersion() + 1);
    }

    @Override
    public <T extends AbstractExtension<?>> void delete(Class<T> modelClass, String name) {
        ResolvedRoute route = router.resolve(modelClass, pluginId);
        String sql = "DELETE FROM " + route.table()
                + " WHERE name=? AND group_name=? AND kind=?";
        int affected;
        try {
            affected = jdbcTemplate.update(sql, name, route.group(), route.kind());
        } catch (Exception e) {
            throw new ExtensionStoreException("删除资源失败: " + route.kind() + "/" + name, e);
        }
        if (affected == 0) {
            throw new ExtensionNotFoundException("资源不存在: " + route.kind() + "/" + name);
        }
    }

    @Override
    public <T extends AbstractExtension<?>> T updateStatus(Class<T> modelClass, String name, String status) {
        ResolvedRoute route = router.resolve(modelClass, pluginId);
        long now = System.currentTimeMillis();
        String sql = "UPDATE " + route.table()
                + " SET status=?, update_timestamp=?"
                + " WHERE name=? AND group_name=? AND kind=?";
        int affected;
        try {
            affected = jdbcTemplate.update(sql, status, now, name, route.group(), route.kind());
        } catch (Exception e) {
            throw new ExtensionStoreException("更新状态失败: " + route.kind() + "/" + name, e);
        }
        if (affected == 0) {
            throw new ExtensionNotFoundException("资源不存在: " + route.kind() + "/" + name);
        }
        return get(modelClass, name).orElseThrow(() -> new ExtensionNotFoundException(
                "资源不存在: " + route.kind() + "/" + name));
    }

    @Override
    public <T extends AbstractExtension<?>> Optional<T> get(Class<T> modelClass, String name) {
        ResolvedRoute route = router.resolve(modelClass, pluginId);
        String sql = "SELECT * FROM " + route.table()
                + " WHERE name=? AND group_name=? AND kind=?";
        try {
            List<T> results = jdbcTemplate.query(sql,
                    new ExtensionRowMapper<>(modelClass, objectMapper),
                    name, route.group(), route.kind());
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        } catch (Exception e) {
            throw new ExtensionStoreException("查询资源失败: " + route.kind() + "/" + name, e);
        }
    }

    @Override
    public <T extends AbstractExtension<?>> void deleteById(Class<T> modelClass, String id) {
        ResolvedRoute route = router.resolve(modelClass, pluginId);
        String sql = "DELETE FROM " + route.table() + " WHERE id=?";
        int affected;
        try {
            affected = jdbcTemplate.update(sql, id);
        } catch (Exception e) {
            throw new ExtensionStoreException("删除资源失败: " + route.kind() + "/" + id, e);
        }
        if (affected == 0) {
            throw new ExtensionNotFoundException("资源不存在: " + route.kind() + "/" + id);
        }
    }

    @Override
    public <T extends AbstractExtension<?>> T updateStatusById(Class<T> modelClass, String id, String status) {
        ResolvedRoute route = router.resolve(modelClass, pluginId);
        long now = System.currentTimeMillis();
        String sql = "UPDATE " + route.table()
                + " SET status=?, update_timestamp=?"
                + " WHERE id=?";
        int affected;
        try {
            affected = jdbcTemplate.update(sql, status, now, id);
        } catch (Exception e) {
            throw new ExtensionStoreException("更新状态失败: " + route.kind() + "/" + id, e);
        }
        if (affected == 0) {
            throw new ExtensionNotFoundException("资源不存在: " + route.kind() + "/" + id);
        }
        return getById(modelClass, id).orElseThrow(() -> new ExtensionNotFoundException(
                "资源不存在: " + route.kind() + "/" + id));
    }

    @Override
    public <T extends AbstractExtension<?>> Optional<T> getById(Class<T> modelClass, String id) {
        ResolvedRoute route = router.resolve(modelClass, pluginId);
        String sql = "SELECT * FROM " + route.table() + " WHERE id=?";
        try {
            List<T> results = jdbcTemplate.query(sql,
                    new ExtensionRowMapper<>(modelClass, objectMapper),
                    id);
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        } catch (Exception e) {
            throw new ExtensionStoreException("查询资源失败: " + route.kind() + "/" + id, e);
        }
    }

    @Override
    public <T extends AbstractExtension<?>> List<T> list(Class<T> modelClass, ListOptions opts) {
        ResolvedRoute route = router.resolve(modelClass, pluginId);
        return queryDialect.list(route, modelClass, opts, jdbcTemplate, objectMapper);
    }

    @Override
    public <T extends AbstractExtension<?>> List<T> listAll(Class<T> modelClass) {
        return list(modelClass, new ListOptions());
    }

    @Override
    public long count(Class<? extends AbstractExtension<?>> modelClass, ListOptions opts) {
        ResolvedRoute route = router.resolve(modelClass, pluginId);
        return queryDialect.count(route, modelClass, opts, jdbcTemplate, objectMapper);
    }

    @Override
    public Set<String> getManagedTables() {
        return ownedTables;
    }

    // ==================== 私有方法 ====================

    @SuppressWarnings("unchecked")
    private <T extends AbstractExtension<?>> Class<? extends AbstractExtension<?>> toModelClass(T extension) {
        return (Class<? extends AbstractExtension<?>>) extension.getClass();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new ExtensionStoreException("序列化失败: " + obj.getClass().getName(), e);
        }
    }
}

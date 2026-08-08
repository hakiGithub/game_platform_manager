package com.gameplatform.plugin.extension;

import com.gameplatform.api.extension.AbstractExtension;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件 Extension 表 schema 管理器。
 * <p>
 * 负责插件加载时按 {@link ExtensionModel} 注解建表，插件卸载/清空时 DROP。
 * 每个插件拥有的物理表名集合记录在内存中，purge API 据此清理。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginSchemaManager {

    private final JdbcTemplate jdbcTemplate;
    private final ExtensionRouter router;
    private final ExtensionScanner scanner;

    /** pluginId → 该插件拥有的物理表名集合 */
    private final ConcurrentHashMap<String, Set<String>> ownership = new ConcurrentHashMap<>();

    /**
     * 扫描插件 basePackage 下的 @ExtensionModel 类，对非 SHARED 策略建表。
     *
     * @param pluginId          插件ID
     * @param pluginClassLoader 插件 ClassLoader
     * @param basePackage       扫描基础包
     * @return 该插件拥有的表名集合（不含 SHARED 全局表）
     */
    public Set<String> createSchemas(String pluginId, ClassLoader pluginClassLoader, String basePackage) {
        Set<Class<? extends AbstractExtension<?>>> classes = scanner.scan(basePackage, pluginClassLoader);
        Set<String> owned = new HashSet<>();
        for (Class<? extends AbstractExtension<?>> clazz : classes) {
            ResolvedRoute route = router.resolve(clazz, pluginId);
            if (route.strategy() == Strategy.SHARED) {
                continue;
            }
            jdbcTemplate.execute(DdlTemplate.generate(route.table()));
            owned.add(route.table());
            log.info("[PluginSchema] 插件 [{}] 建表: {} (kind={}, strategy={})",
                    pluginId, route.table(), route.kind(), route.strategy());
        }
        ownership.put(pluginId, owned);
        return owned;
    }

    /**
     * 删除插件所有专属表（purge API 调用）。
     *
     * @param pluginId 插件ID
     */
    public void purge(String pluginId) {
        Set<String> tables = ownership.remove(pluginId);
        if (tables == null || tables.isEmpty()) {
            log.info("[PluginSchema] 插件 [{}] 无专属表可清理", pluginId);
            return;
        }
        for (String table : tables) {
            jdbcTemplate.execute(DdlTemplate.drop(table));
            log.info("[PluginSchema] 插件 [{}] 删表: {}", pluginId, table);
        }
    }

    /**
     * 获取插件拥有的表名集合（只读视图）。
     *
     * @param pluginId 插件ID
     * @return 表名集合
     */
    public Set<String> getOwnedTables(String pluginId) {
        return Set.copyOf(ownership.getOrDefault(pluginId, Set.of()));
    }
}

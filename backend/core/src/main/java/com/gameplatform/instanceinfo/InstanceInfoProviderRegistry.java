package com.gameplatform.instanceinfo;

import com.gameplatform.plugin.extension.InstanceInfoProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实例信息提供者注册表（ADR-0017）。
 * <p>
 * key 为插件来源（gameCode 大写），与 {@code TaskHandlerRegistry} 同模式：
 * register（putIfAbsent，重复抛 ISE）/ unregisterBySource（插件卸载/热重载时清理）。
 * 查询侧按实例的 gameCode 取 Provider；无实现返回 empty（降级路径）。
 */
@Slf4j
@Component
public class InstanceInfoProviderRegistry {

    private final ConcurrentHashMap<String, InstanceInfoProvider> providers = new ConcurrentHashMap<>();

    /**
     * 注册提供者（插件子容器创建时扫描调用）。
     *
     * @throws IllegalStateException 同一来源重复注册
     */
    public void register(String source, InstanceInfoProvider provider) {
        InstanceInfoProvider existing = providers.putIfAbsent(source, provider);
        if (existing != null) {
            throw new IllegalStateException("来源 " + source + " 的 InstanceInfoProvider 已注册");
        }
        log.info("[InstanceInfo] 来源 [{}] 已注册实例信息提供者: {}", source, provider.getClass().getSimpleName());
    }

    /**
     * 注销来源的全部提供者（插件卸载/热重载时调用）。
     *
     * @return 注销数量
     */
    public int unregisterBySource(String source) {
        if (source == null || source.isBlank()) {
            return 0;
        }
        int before = providers.size();
        providers.entrySet().removeIf(e -> e.getKey().equals(source));
        int removed = before - providers.size();
        if (removed > 0) {
            log.info("[InstanceInfo] 来源 [{}] 已注销 {} 个实例信息提供者", source, removed);
        }
        return removed;
    }

    /**
     * 按来源取提供者；无实现返回 empty。
     */
    public Optional<InstanceInfoProvider> findBySource(String source) {
        if (source == null || source.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(providers.get(source));
    }
}

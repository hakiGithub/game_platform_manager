package com.gameplatform.instanceinfo;

import com.gameplatform.plugin.extension.InstanceInfoProvider;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InstanceInfoProviderRegistry 注册/注销行为测试（ADR-0017）。
 */
class InstanceInfoProviderRegistryTest {

    private final InstanceInfoProviderRegistry registry = new InstanceInfoProviderRegistry();
    private final InstanceInfoProvider provider = instanceId -> null;

    @Test
    void register_and_find() {
        registry.register("L4D2", provider);

        assertTrue(registry.findBySource("L4D2").isPresent());
        // 精确键匹配：大小写归一由调用方（InstanceInfoService）负责
        assertTrue(registry.findBySource("l4d2").isEmpty());
        assertTrue(registry.findBySource("DST").isEmpty());
        assertTrue(registry.findBySource(null).isEmpty());
        assertTrue(registry.findBySource("").isEmpty());
    }

    @Test
    void register_duplicate_throws() {
        registry.register("L4D2", provider);

        assertThrows(IllegalStateException.class, () -> registry.register("L4D2", provider));
    }

    @Test
    void unregisterBySource_removesOnlyTarget() {
        registry.register("L4D2", provider);
        registry.register("DST", instanceId -> null);

        int removed = registry.unregisterBySource("L4D2");

        assertEquals(1, removed);
        assertTrue(registry.findBySource("L4D2").isEmpty());
        assertTrue(registry.findBySource("DST").isPresent());
        assertEquals(0, registry.unregisterBySource("L4D2"));
        assertEquals(0, registry.unregisterBySource(null));
    }
}

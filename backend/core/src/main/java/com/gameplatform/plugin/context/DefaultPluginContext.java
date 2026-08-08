package com.gameplatform.plugin.context;

import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.Map;

/**
 * PluginContext 的默认实现。
 * <p>
 * 数据持久化通过子容器注入的 {@code ExtensionClient} Bean 完成，不再经由此上下文传递。
 *
 * @author GamePlatform
 * @version 3.0.0
 */
@Getter
@Builder
public class DefaultPluginContext implements PluginContext {

    private final String pluginId;
    private final String gameCode;
    private final String gameName;
    private final String version;
    private final Map<String, String> customProperties;

    @Override
    public Map<String, String> getCustomProperties() {
        return customProperties != null ? Collections.unmodifiableMap(customProperties) : Collections.emptyMap();
    }
}

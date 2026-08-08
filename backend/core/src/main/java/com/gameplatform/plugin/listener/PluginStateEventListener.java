package com.gameplatform.plugin.listener;

import com.gameplatform.plugin.extension.GameEnhancementExtension;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.PluginStateEvent;
import org.pf4j.PluginStateListener;
import org.pf4j.PluginWrapper;
import org.springframework.stereotype.Component;

/**
 * 插件状态监听器
 * 监听插件状态变化，执行相应的生命周期钩子
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginStateEventListener implements PluginStateListener {

    private final PluginLifecycleHook lifecycleHook;

    @Override
    public void pluginStateChanged(PluginStateEvent event) {
        PluginWrapper plugin = event.getPlugin();
        String pluginId = plugin.getPluginId();
        
        log.info("插件状态变化: {} -> {} -> {}", 
                pluginId, 
                event.getOldState(), 
                event.getPluginState());

        switch (event.getPluginState()) {
            case STARTED -> lifecycleHook.onPluginStart(plugin);
            case STOPPED -> lifecycleHook.onPluginStop(plugin);
            case CREATED -> lifecycleHook.onPluginCreate(plugin);
            case DISABLED -> lifecycleHook.onPluginDisable(plugin);
            case RESOLVED -> lifecycleHook.onPluginResolve(plugin);
        }
    }

}

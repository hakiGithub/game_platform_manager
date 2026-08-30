package com.gameplatform.plugin.l4d2.task;

import com.gameplatform.plugin.l4d2.L4D2Constants;
import com.gameplatform.plugin.task.TaskHandler;
import com.gameplatform.plugin.task.TaskHandlerExtension;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * L4D2 插件任务处理器注册入口。
 *
 * <p>由 {@code PluginSpringContextFactory} 在加载插件时通过 Spring 子容器扫描此 Bean，
 * 将返回的 Handler 注册到 {@code TaskHandlerRegistry}。
 *
 * <p>任务来源（source）由框架自动填充为插件 gameCode 的大写形式（L4D2），
 * Handler 只需关注 taskType 与执行逻辑。
 *
 * <p>已注册的处理器：
 * <ul>
 *   <li>{@code crawl} → {@link CrawlTaskHandler}：地图爬取任务</li>
 *   <li>{@code map-upload} → {@link MapUploadTaskHandler}：地图上传任务（ADR-0018）</li>
 *   <li>{@code builtin-plugin-install} → {@link BuiltinPluginInstallTaskHandler}：内置插件安装任务</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@Slf4j
@Component
public class L4D2TaskHandlerExtension implements TaskHandlerExtension {

    private final Map<String, TaskHandler> handlers;

    public L4D2TaskHandlerExtension(CrawlTaskHandler crawlTaskHandler,
                                    MapUploadTaskHandler mapUploadTaskHandler,
                                    BuiltinPluginInstallTaskHandler builtinPluginInstallTaskHandler) {
        // 单装与批装共用同一 Handler 实例，注册两个 taskType（Handler 内按 payload 区分）
        this.handlers = Map.of(
                L4D2Constants.TASK_TYPE_CRAWL, crawlTaskHandler,
                L4D2Constants.TASK_TYPE_MAP_UPLOAD, mapUploadTaskHandler,
                L4D2Constants.TASK_TYPE_BUILTIN_PLUGIN_INSTALL, builtinPluginInstallTaskHandler,
                L4D2Constants.TASK_TYPE_BUILTIN_PLUGIN_BATCH_INSTALL, builtinPluginInstallTaskHandler);
        log.info("[L4D2] 任务处理器已注册: crawl -> {}, map-upload -> {}, builtin-plugin-install -> {}",
                crawlTaskHandler.getClass().getSimpleName(), mapUploadTaskHandler.getClass().getSimpleName(),
                builtinPluginInstallTaskHandler.getClass().getSimpleName());
    }

    @Override
    public Map<String, TaskHandler> getTaskHandlers() {
        return handlers;
    }
}

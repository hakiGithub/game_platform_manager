package com.gameplatform.plugin.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.plugin.exception.PluginPathConflictException;
import com.gameplatform.plugin.extension.*;
import com.gameplatform.plugin.schedule.ScheduleDeclaration;
import com.gameplatform.plugin.schedule.ScheduleService;
import com.gameplatform.plugin.schedule.ScheduledTaskDeclarationExtension;
import com.gameplatform.plugin.schedule.ScheduledTaskHandler;
import com.gameplatform.plugin.service.FileAccessService;
import com.gameplatform.plugin.service.HostQueryService;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.plugin.service.SshTunnelManager;
import com.gameplatform.plugin.service.SshTunnelService;
import com.gameplatform.plugin.task.TaskHandler;
import com.gameplatform.plugin.task.TaskHandlerExtension;
import com.gameplatform.plugin.task.TaskService;
import com.gameplatform.plugin.util.PluginUtils;
import com.gameplatform.schedule.PluginScheduleServiceAdapter;
import com.gameplatform.schedule.ScheduleManagementService;
import com.gameplatform.schedule.ScheduledTaskHandlerRegistry;
import com.gameplatform.service.TaskAdminService;
import com.gameplatform.task.TaskHandlerRegistry;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.PluginWrapper;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 插件 Spring 子容器工厂。
 * <p>
 * 为每个外部加载的插件创建独立的 Spring 子容器，
 * 扫描并注册其 @RestController Bean 到主 DispatcherServlet。
 * <p>
 * 同时负责：
 * <ul>
 *   <li>向插件子容器注入 {@link TaskService}（ADR-025）</li>
 *   <li>扫描 {@link TaskHandlerExtension} 注册到 {@link TaskHandlerRegistry}（ADR-002）</li>
 *   <li>插件卸载时取消运行任务 + 物理删除记录（ADR-013）</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 2.1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginSpringContextFactory {

    /** 插件卸载时协作式取消的等待上限（毫秒，ADR-013） */
    private static final long UNLOAD_CANCEL_WAIT_MS = 10_000L;

    /** 协作式取消的轮询间隔（毫秒） */
    private static final long UNLOAD_CANCEL_POLL_INTERVAL_MS = 500L;

    private final ApplicationContext mainContext;
    private final RequestMappingHandlerMapping handlerMapping;
    private final JdbcTemplate jdbcTemplate;
    private final PluginSchemaManager schemaManager;
    private final ExtensionRouter extensionRouter;
    private final ExtensionQueryDialect extensionQueryDialect;
    private final ObjectMapper objectMapper;
    private final TaskHandlerRegistry taskHandlerRegistry;
    private final TaskAdminService taskAdminService;
    private final SshTunnelManager sshTunnelManager;
    private final ScheduledTaskHandlerRegistry scheduleHandlerRegistry;
    private final ScheduleManagementService scheduleManagementService;

    /** 已加载的插件上下文信息 */
    private final Map<String, PluginContextInfo> loadedPlugins = new ConcurrentHashMap<>();

    /** 已注册的 URL 路径 → 插件ID 映射（用于冲突检测） */
    private final Map<String, String> registeredPaths = new ConcurrentHashMap<>();

    /**
     * 为插件创建 Spring 子容器并注册控制器。
     *
     * @param wrapper   PF4J 插件包装器
     * @param extension 游戏增强扩展点
     * @param properties 插件配置属性
     */
    public void loadPluginSpringContext(PluginWrapper wrapper,
                                         GameEnhancementExtension extension,
                                         Properties properties) {
        String pluginId = wrapper.getPluginId();
        String basePackage = properties.getProperty("plugin.basePackage", extension.getBasePackage());

        log.info("[PluginSpring] Creating Spring child context for plugin [{}]", pluginId);
        log.info("[PluginSpring]   Base package: {}", basePackage);

        // 1. 扫描 @ExtensionModel 类并为非 SHARED 策略建专属表
        Set<String> ownedTables = schemaManager.createSchemas(
                pluginId, wrapper.getPluginClassLoader(), basePackage);

        // 2. 创建子容器
        AnnotationConfigApplicationContext childContext = new AnnotationConfigApplicationContext();
        childContext.setParent(mainContext);
        childContext.setClassLoader(wrapper.getPluginClassLoader());
        childContext.setId("plugin-" + pluginId);

        // 3. 注册 ExtensionClient Bean（绑定 pluginId）
        ExtensionIdGenerator idGenerator = mainContext.getBean(ExtensionIdGenerator.class);
        ExtensionClientImpl extensionClient = new ExtensionClientImpl(
                jdbcTemplate, extensionRouter, pluginId,
                extensionQueryDialect, objectMapper, ownedTables, idGenerator);
        childContext.getBeanFactory().registerSingleton("extensionClient", extensionClient);
        log.info("  已注册 ExtensionClient，专属表: {}", ownedTables);

        // 4. 注册插件可用的宿主服务（解耦插件对 core 的直接依赖）
        InstanceQueryService instanceQueryService = mainContext.getBean(InstanceQueryService.class);
        HostQueryService hostQueryService = mainContext.getBean(HostQueryService.class);
        FileAccessService fileAccessService = mainContext.getBean(FileAccessService.class);
        InstanceFileService instanceFileService = mainContext.getBean(InstanceFileService.class);
        childContext.getBeanFactory().registerSingleton("instanceQueryService", instanceQueryService);
        childContext.getBeanFactory().registerSingleton("hostQueryService", hostQueryService);
        childContext.getBeanFactory().registerSingleton("fileAccessService", fileAccessService);
        childContext.getBeanFactory().registerSingleton("instanceFileService", instanceFileService);
        // 注入 TaskService（ADR-025）：插件通过此接口提交/查询/取消任务
        TaskService taskService = mainContext.getBean(TaskService.class);
        childContext.getBeanFactory().registerSingleton("taskService", taskService);
        // 注入 SshTunnelService（ADR-0009）：绑定 pluginId 的 SSH 隧道能力
        SshTunnelService sshTunnelService = sshTunnelManager.forPlugin(pluginId);
        childContext.getBeanFactory().registerSingleton("sshTunnelService", sshTunnelService);
        // 注入 ScheduleService（ADR-0011）：绑定 pluginId+source 的定时计划能力（来源隔离）
        String scheduleSource = extension.getGameCode().toUpperCase();
        ScheduleService scheduleService = new PluginScheduleServiceAdapter(
                scheduleManagementService, pluginId, scheduleSource);
        childContext.getBeanFactory().registerSingleton("scheduleService", scheduleService);
        log.info("  已注册插件可用服务: InstanceQueryService, HostQueryService, FileAccessService, InstanceFileService, TaskService, SshTunnelService, ScheduleService");

        // 5. 扫描插件包路径
        childContext.scan(basePackage);
        childContext.refresh();

        // 6. 发现并注册控制器到主 HandlerMapping
        Map<String, Object> controllers = childContext.getBeansWithAnnotation(RestController.class);
        controllers.putAll(childContext.getBeansWithAnnotation(org.springframework.stereotype.Controller.class));

        List<String> registeredEndpoints = new ArrayList<>();
        List<RequestMappingInfo> registeredInfos = new ArrayList<>();

        for (Object controller : controllers.values()) {
            String beanName = getBeanName(childContext, controller);
            if (beanName.startsWith("org.springframework")) continue;

            registerControllerMethods(pluginId, controller, registeredEndpoints, registeredInfos);
        }

        // 7. 扫描 TaskHandlerExtension（ADR-002），注册到 TaskHandlerRegistry
        String taskSource = extension.getGameCode().toUpperCase();
        int registeredHandlers = scanAndRegisterTaskHandlers(childContext, taskSource);

        // 7.5 定时计划联动（ADR-0011 D5/D8）：注册 Handler → upsert 声明式默认计划 → 恢复停用前的暂停
        int scheduleHandlers = scanAndRegisterScheduleHandlers(childContext, scheduleSource);
        upsertScheduleDeclarations(pluginId, scheduleSource, childContext);
        resumeSchedulesByPlugin(pluginId);

        // 8. 构建并注册 PluginContext
        DefaultPluginContext pluginContext = DefaultPluginContext.builder()
                .pluginId(pluginId)
                .gameCode(extension.getGameCode())
                .gameName(extension.getGameName())
                .version(extension.getVersion())
                .customProperties(extractCustomProperties(properties))
                .build();
        PluginContextHolder.register(pluginContext);

        // 9. 调用扩展点的 onLoad 钩子
        try {
            extension.onLoad(pluginContext);
        } catch (Exception e) {
            log.error("插件 [{}] onLoad 钩子执行失败", pluginId, e);
        }

        // 10. 缓存以进行生命周期管理
        loadedPlugins.put(pluginId, new PluginContextInfo(wrapper, childContext, controllers.keySet(), registeredInfos, taskSource));

        log.info("  共注册 {} 个端点, {} 个任务处理器, {} 个定时任务处理器",
                registeredEndpoints.size(), registeredHandlers, scheduleHandlers);
        log.info("======== 插件 [{}] Spring 子容器创建完成 ========", pluginId);
    }

    /**
     * 扫描插件子容器中的 {@link TaskHandlerExtension} Bean，将返回的 Handler 注册到 {@link TaskHandlerRegistry}。
     *
     * <p>插件实现 {@code TaskHandlerExtension} 并标注 {@code @Component}，
     * 通过构造注入获取插件子容器中的依赖（如 CrawlTaskHandler、MapCenterService）。
     *
     * @param childContext 插件子容器
     * @param source       任务来源（gameCode 大写）
     * @return 已注册的 Handler 数量
     */
    private int scanAndRegisterTaskHandlers(AnnotationConfigApplicationContext childContext, String source) {
        Map<String, TaskHandlerExtension> extensionBeans = childContext.getBeansOfType(TaskHandlerExtension.class);
        if (extensionBeans.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (TaskHandlerExtension ext : extensionBeans.values()) {
            Map<String, TaskHandler> handlers;
            try {
                handlers = ext.getTaskHandlers();
            } catch (Exception e) {
                log.error("[TaskCenter] 插件 [{}] TaskHandlerExtension.getTaskHandlers() 执行失败，跳过", source, e);
                continue;
            }
            if (handlers == null || handlers.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, TaskHandler> entry : handlers.entrySet()) {
                String taskType = entry.getKey();
                TaskHandler handler = entry.getValue();
                if (taskType == null || taskType.isBlank()) {
                    log.warn("[TaskCenter] 插件 [{}] 跳过空 taskType", source);
                    continue;
                }
                if (handler == null) {
                    log.warn("[TaskCenter] 插件 [{}] taskType={} 的 Handler 为 null，跳过", source, taskType);
                    continue;
                }
                try {
                    taskHandlerRegistry.register(source, taskType, handler);
                    count++;
                } catch (IllegalStateException e) {
                    log.error("[TaskCenter] 插件 [{}] 任务类型 {} 注册失败（重复注册）: {}", source, taskType, e.getMessage());
                }
            }
        }
        if (count > 0) {
            log.info("[TaskCenter] 来源 [{}] 已注册 {} 个任务处理器", source, count);
        }
        return count;
    }

    /**
     * 扫描插件子容器中的 {@link ScheduledTaskHandler} Bean，按 {@code (source, key)}
     * 注册到 {@link ScheduledTaskHandlerRegistry}（ADR-0011 D3）。
     *
     * <p>插件子容器内一个 {@code @Component} 即一个 Handler；重复注册抛
     * IllegalStateException（热重载残留由 unload 时 unregisterBySource 清理，
     * 正常流程不会触发）。
     *
     * @param childContext 插件子容器
     * @param source       来源（gameCode 大写）
     * @return 已注册的 Handler 数量
     */
    private int scanAndRegisterScheduleHandlers(AnnotationConfigApplicationContext childContext, String source) {
        Map<String, ScheduledTaskHandler> handlerBeans = childContext.getBeansOfType(ScheduledTaskHandler.class);
        if (handlerBeans.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (ScheduledTaskHandler handler : handlerBeans.values()) {
            String key = handler.getKey();
            if (key == null || key.isBlank()) {
                log.warn("[Schedule] 插件来源 [{}] 跳过 key 为空的定时任务处理器 {}", source,
                        handler.getClass().getSimpleName());
                continue;
            }
            try {
                scheduleHandlerRegistry.register(source, key, handler);
                count++;
            } catch (IllegalStateException e) {
                log.error("[Schedule] 来源 [{}] 定时任务处理器 {} 注册失败: {}", source, key, e.getMessage());
            }
        }
        return count;
    }

    /**
     * 扫描 {@link ScheduledTaskDeclarationExtension}，将声明式默认计划按稳定键
     * {@code pluginId:key} upsert（ADR-0011 D5：用户改过跳过、删过不复活）。
     */
    private void upsertScheduleDeclarations(String pluginId, String source,
                                            AnnotationConfigApplicationContext childContext) {
        Map<String, ScheduledTaskDeclarationExtension> extensionBeans =
                childContext.getBeansOfType(ScheduledTaskDeclarationExtension.class);
        if (extensionBeans.isEmpty()) {
            return;
        }
        List<ScheduleDeclaration> declarations = new ArrayList<>();
        for (ScheduledTaskDeclarationExtension ext : extensionBeans.values()) {
            try {
                List<ScheduleDeclaration> result = ext.getScheduleDeclarations();
                if (result != null) {
                    declarations.addAll(result);
                }
            } catch (Exception e) {
                log.error("[Schedule] 插件 [{}] getScheduleDeclarations() 执行失败，跳过该扩展点", pluginId, e);
            }
        }
        if (declarations.isEmpty()) {
            return;
        }
        try {
            scheduleManagementService.upsertDeclarations(pluginId, source, declarations);
        } catch (Exception e) {
            log.error("[Schedule] 插件 [{}] 声明式计划 upsert 失败（不影响插件加载）", pluginId, e);
        }
    }

    /**
     * 恢复插件停用/热重载前暂停的计划（ADR-0011 D8：enabled=1 的重新注册调度）。
     */
    private void resumeSchedulesByPlugin(String pluginId) {
        try {
            scheduleManagementService.resumeByPlugin(pluginId);
        } catch (Exception e) {
            log.error("[Schedule] 插件 [{}] 恢复定时计划失败（不影响插件加载）", pluginId, e);
        }
    }

    /**
     * 卸载插件 Spring 上下文。
     *
     * <p>卸载流程（ADR-013）：
     * <ol>
     *   <li>调用 {@link TaskAdminService#cancelBySource} 取消该 source 所有 PENDING/RUNNING 任务</li>
     *   <li>轮询等待最多 10s 让 Handler 优雅退出</li>
     *   <li>调用 {@link TaskHandlerRegistry#unregisterBySource} 注销 Handler</li>
     *   <li>调用 {@link TaskAdminService#purgeBySource} 物理删除任务记录与日志</li>
     *   <li>调用扩展点 onUnload 钩子</li>
     *   <li>注销控制器映射 + 关闭子容器 + 注销 PluginContext</li>
     * </ol>
     *
     * @param pluginId 插件ID
     * @param extension 扩展点（可为 null，为 null 时跳过 onUnload 钩子）
     */
    public void unloadPluginContext(String pluginId, GameEnhancementExtension extension) {
        unloadPluginContext(pluginId, extension, true);
    }

    /**
     * 卸载插件 Spring 上下文。
     *
     * @param purgeTasks 是否物理删除该插件 source 的任务记录与日志：
     *                   卸载移除插件时为 true；热部署/重载（同插件即将重新
     *                   加载）应为 false——取消运行中任务与注销 Handler 仍然
     *                   执行（旧 classloader 必须释放），但保留历史记录供
     *                   重载后的插件继续读取（如爬取统计）。
     */
    public void unloadPluginContext(String pluginId, GameEnhancementExtension extension, boolean purgeTasks) {
        PluginContextInfo info = loadedPlugins.remove(pluginId);
        if (info == null) {
            log.warn("插件 [{}] 未找到 Spring 上下文", pluginId);
            return;
        }

        log.info("卸载插件 [{}] Spring 上下文", pluginId);

        // 1. 任务中心清理：取消运行中任务 + 注销 Handler（purgeTasks 时物理删除记录，ADR-013）
        cleanupTasksForPlugin(pluginId, info.getTaskSource(), purgeTasks);

        // 1.5 定时计划联动（ADR-0011 D8）：注销 Handler；
        //     卸载移除 → 物理清理计划+记录；热重载/停用 → 暂停（重载后 resume 恢复）
        cleanupSchedulesForPlugin(pluginId, info.getTaskSource(), purgeTasks);

        // 2. 调用扩展点的 onUnload 钩子
        if (extension != null) {
            try {
                extension.onUnload();
            } catch (Exception e) {
                log.error("插件 [{}] onUnload 钩子执行失败", pluginId, e);
            }
        }

        // 2.5 强制关闭该插件的全部 SSH 隧道（ADR-0009 兜底；
        // 插件在 onUnload 中主动 close 自己的句柄是加速路径）
        try {
            sshTunnelManager.closeAllForPlugin(pluginId);
        } catch (Exception e) {
            log.warn("[SshTunnel] 插件 [{}] 卸载时关闭隧道异常: {}", pluginId, e.getMessage());
        }

        // 3. 注销所有控制器映射
        for (RequestMappingInfo mappingInfo : info.getRegisteredMappings()) {
            handlerMapping.unregisterMapping(mappingInfo);
            // 清除路径注册记录
            mappingInfo.getPathPatternsCondition().getPatterns().forEach(pattern ->
                    registeredPaths.remove(pattern.getPatternString()));
        }

        // 4. 关闭子容器
        info.getChildContext().close();

        // 5. 注销上下文
        PluginContextHolder.unregister(pluginId);
        PluginUtils.invalidateCache(pluginId);

        log.info("插件 [{}] Spring 上下文已卸载", pluginId);
    }

    /**
     * 插件卸载时的定时计划清理（ADR-0011 D8）。
     *
     * <p>步骤：
     * <ol>
     *   <li>注销该来源全部 ScheduledTaskHandler（旧 classloader 必须释放）</li>
     *   <li>卸载移除（purgeTasks=true）→ 物理删除计划 + 触发记录 + 日志</li>
     *   <li>热重载/停用（purgeTasks=false）→ 暂停计划（保留 enabled 用户意图，
     *       重载后 resumeByPlugin 恢复）</li>
     * </ol>
     *
     * @param pluginId   插件ID
     * @param source     计划来源（gameCode 大写）
     * @param purgeTasks 是否物理删除（与任务中心 purgeTasks 语义对齐）
     */
    private void cleanupSchedulesForPlugin(String pluginId, String source, boolean purgeTasks) {
        if (source == null || source.isBlank()) {
            return;
        }
        try {
            scheduleHandlerRegistry.unregisterBySource(source);
        } catch (Exception e) {
            log.warn("[Schedule] 插件 [{}] 卸载时注销定时任务处理器异常: {}", pluginId, e.getMessage());
        }
        try {
            if (purgeTasks) {
                scheduleManagementService.purgeByPlugin(pluginId);
            } else {
                scheduleManagementService.pauseByPlugin(pluginId, "插件停用或热重载");
            }
        } catch (Exception e) {
            log.warn("[Schedule] 插件 [{}] 卸载时清理定时计划异常: {}", pluginId, e.getMessage());
        }
    }

    /**
     * 插件卸载时的任务中心清理（ADR-013）。
     *
     * <p>步骤：
     * <ol>
     *   <li>调用 cancelBySource 协作式取消所有 PENDING/RUNNING 任务</li>
     *   <li>轮询等待最多 10s 让 Handler 优雅退出</li>
     *   <li>注销 Handler（之后残留任务无法重试/详情）</li>
     *   <li>物理删除该 source 的所有任务记录与日志</li>
     * </ol>
     *
     * @param pluginId 插件ID（仅日志用）
     * @param source   任务来源
     * @param purgeTasks 是否物理删除任务记录与日志（热部署传 false 保留历史）
     */
    private void cleanupTasksForPlugin(String pluginId, String source, boolean purgeTasks) {
        if (source == null || source.isBlank()) {
            return;
        }
        // 1. 协作式取消 PENDING/RUNNING 任务
        int cancelled = 0;
        try {
            cancelled = taskAdminService.cancelBySource(source);
        } catch (Exception e) {
            log.warn("[TaskCenter] 插件 [{}] 卸载时取消任务异常: {}", pluginId, e.getMessage());
        }
        if (cancelled > 0) {
            log.info("[TaskCenter] 插件 [{}] 卸载前已请求取消 {} 个任务，等待优雅退出...", pluginId, cancelled);
            // 2. 轮询等待最多 10s
            waitForTaskGracefulShutdown(pluginId, source);
        }

        // 3. 注销 Handler
        try {
            int unregistered = taskHandlerRegistry.unregisterBySource(source);
            if (unregistered > 0) {
                log.info("[TaskCenter] 插件 [{}] 已注销 {} 个任务处理器", pluginId, unregistered);
            }
        } catch (Exception e) {
            log.warn("[TaskCenter] 插件 [{}] 注销任务处理器异常: {}", pluginId, e.getMessage());
        }

        // 4. 物理删除任务记录与日志（热部署时保留历史）
        if (!purgeTasks) {
            log.info("[TaskCenter] 热部署模式：保留插件 [{}] 的任务历史记录", pluginId);
            return;
        }
        try {
            int purged = taskAdminService.purgeBySource(source);
            if (purged > 0) {
                log.info("[TaskCenter] 插件 [{}] 卸载已清理 {} 个任务记录", pluginId, purged);
            }
        } catch (Exception e) {
            log.warn("[TaskCenter] 插件 [{}] 物理清理任务记录异常: {}", pluginId, e.getMessage());
        }
    }

    /**
     * 轮询等待该 source 的所有任务退出 RUNNING 状态。
     *
     * <p>每 500ms 轮询一次，最多等待 10s。超时后无论是否仍有 RUNNING 任务都继续后续清理。
     * 通过查询 task_record 表中该 source 的 RUNNING/PENDING 任务数判断。
     */
    private void waitForTaskGracefulShutdown(String pluginId, String source) {
        long deadline = System.currentTimeMillis() + UNLOAD_CANCEL_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                TimeUnit.MILLISECONDS.sleep(UNLOAD_CANCEL_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("[TaskCenter] 插件 [{}] 卸载等待被中断，继续后续清理", pluginId);
                return;
            }
            // 通过 TaskAdminService 查询是否仍有未结束任务
            // 这里复用 listTasks 查询，避免在 PluginSpringContextFactory 中直接依赖 Mapper
            try {
                com.gameplatform.plugin.task.TaskQuery runningQuery = com.gameplatform.plugin.task.TaskQuery.builder()
                        .source(source).status("RUNNING").page(1).size(1).build();
                var page = taskAdminService.listTasks(runningQuery);
                if (page.getRecords().isEmpty()) {
                    // 再检查 PENDING（cancelBySource 已直接置 CANCELLED，但兜底查一次）
                    com.gameplatform.plugin.task.TaskQuery pendingQuery = com.gameplatform.plugin.task.TaskQuery.builder()
                            .source(source).status("PENDING").page(1).size(1).build();
                    var pendingPage = taskAdminService.listTasks(pendingQuery);
                    if (pendingPage.getRecords().isEmpty()) {
                        log.info("[TaskCenter] 插件 [{}] 所有任务已退出，继续卸载", pluginId);
                        return;
                    }
                }
            } catch (Exception e) {
                log.warn("[TaskCenter] 插件 [{}] 轮询任务状态异常: {}", pluginId, e.getMessage());
                return;
            }
        }
        log.warn("[TaskCenter] 插件 [{}] 等待 {}ms 后仍有任务未退出，强制清理",
                pluginId, UNLOAD_CANCEL_WAIT_MS);
    }

    /**
     * 卸载插件 Spring 上下文（兼容方法，不调用 onUnload 钩子）。
     */
    public void unloadPluginContext(String pluginId) {
        unloadPluginContext(pluginId, null);
    }

    /**
     * 检查插件是否已加载 Spring 上下文。
     */
    public boolean isPluginContextLoaded(String pluginId) {
        return loadedPlugins.containsKey(pluginId);
    }

    /**
     * 获取已加载的插件上下文信息。
     */
    public PluginContextInfo getLoadedPlugin(String pluginId) {
        return loadedPlugins.get(pluginId);
    }

    // ==================== 私有方法 ====================

    /**
     * 从控制器方法中提取请求映射信息。
     * 提取公共逻辑，消除 register/unregister 中的重复代码。
     */
    private List<EndpointMapping> extractEndpointMappings(Object controller) {
        Class<?> clazz = controller.getClass();
        // 处理 CGLIB 代理
        if (clazz.getName().contains("$$")) {
            clazz = clazz.getSuperclass();
        }

        RequestMapping classMapping = clazz.getAnnotation(RequestMapping.class);
        String basePath = "";
        if (classMapping != null && classMapping.value().length > 0) {
            basePath = PluginUtils.stripApiPrefix(classMapping.value()[0]);
        }

        List<EndpointMapping> mappings = new ArrayList<>();
        for (Method method : clazz.getDeclaredMethods()) {
            EndpointMapping mapping = extractMethodMapping(method, basePath);
            if (mapping != null) {
                mappings.add(mapping);
            }
        }
        return mappings;
    }

    /**
     * 从单个方法提取映射信息。
     * <p>方法级映射未显式指定路径时，默认继承类级 basePath（如 @PostMapping 直接映射到类路径）。
     */
    private EndpointMapping extractMethodMapping(Method method, String basePath) {
        Set<String> paths = new LinkedHashSet<>();
        Set<RequestMethod> httpMethods = new LinkedHashSet<>();

        RequestMapping reqMapping = method.getAnnotation(RequestMapping.class);
        GetMapping getMapping = method.getAnnotation(GetMapping.class);
        PostMapping postMapping = method.getAnnotation(PostMapping.class);
        PutMapping putMapping = method.getAnnotation(PutMapping.class);
        DeleteMapping deleteMapping = method.getAnnotation(DeleteMapping.class);

        if (reqMapping != null) {
            if (reqMapping.value().length == 0) {
                paths.add(basePath);
            } else {
                for (String p : reqMapping.value()) paths.add(basePath + p);
            }
            Collections.addAll(httpMethods, reqMapping.method());
        } else if (getMapping != null) {
            if (getMapping.value().length == 0) {
                paths.add(basePath);
            } else {
                for (String p : getMapping.value()) paths.add(basePath + p);
            }
            httpMethods.add(RequestMethod.GET);
        } else if (postMapping != null) {
            if (postMapping.value().length == 0) {
                paths.add(basePath);
            } else {
                for (String p : postMapping.value()) paths.add(basePath + p);
            }
            httpMethods.add(RequestMethod.POST);
        } else if (putMapping != null) {
            if (putMapping.value().length == 0) {
                paths.add(basePath);
            } else {
                for (String p : putMapping.value()) paths.add(basePath + p);
            }
            httpMethods.add(RequestMethod.PUT);
        } else if (deleteMapping != null) {
            if (deleteMapping.value().length == 0) {
                paths.add(basePath);
            } else {
                for (String p : deleteMapping.value()) paths.add(basePath + p);
            }
            httpMethods.add(RequestMethod.DELETE);
        } else {
            return null;
        }

        if (paths.isEmpty()) {
            return null;
        }

        return new EndpointMapping(paths, httpMethods);
    }

    private void registerControllerMethods(String pluginId, Object controller,
                                            List<String> registeredEndpoints,
                                            List<RequestMappingInfo> registeredInfos) {
        List<EndpointMapping> mappings = extractEndpointMappings(controller);

        for (EndpointMapping mapping : mappings) {
            // 路径冲突检测
            for (String path : mapping.paths()) {
                String existingPlugin = registeredPaths.get(path);
                if (existingPlugin != null && !existingPlugin.equals(pluginId)) {
                    throw new PluginPathConflictException(pluginId, path, existingPlugin);
                }
            }

            RequestMappingInfo info = RequestMappingInfo
                    .paths(mapping.paths().toArray(new String[0]))
                    .methods(mapping.methods().toArray(new RequestMethod[0]))
                    .build();

            handlerMapping.registerMapping(info, controller, getHandlerMethod(controller, mappings, mapping));
            registeredInfos.add(info);

            // 记录路径注册
            for (String path : mapping.paths()) {
                registeredPaths.put(path, pluginId);
            }

            registeredEndpoints.add(mapping.methods() + " " + mapping.paths());
        }
    }

    /**
     * 获取控制器中与映射对应的方法（简化实现：返回第一个匹配的方法）。
     */
    private Method getHandlerMethod(Object controller, List<EndpointMapping> allMappings, EndpointMapping target) {
        Class<?> clazz = controller.getClass();
        if (clazz.getName().contains("$$")) {
            clazz = clazz.getSuperclass();
        }
        for (Method method : clazz.getDeclaredMethods()) {
            EndpointMapping m = extractMethodMapping(method,
                    clazz.getAnnotation(RequestMapping.class) != null
                            && clazz.getAnnotation(RequestMapping.class).value().length > 0
                            ? PluginUtils.stripApiPrefix(clazz.getAnnotation(RequestMapping.class).value()[0])
                            : "");
            if (m != null && m.paths().equals(target.paths()) && m.methods().equals(target.methods())) {
                return method;
            }
        }
        return null;
    }

    private String getBeanName(AnnotationConfigApplicationContext ctx, Object bean) {
        for (String name : ctx.getBeanDefinitionNames()) {
            if (ctx.getBean(name) == bean) return name;
        }
        return bean.getClass().getName();
    }

    /**
     * 从 plugin.properties 中提取自定义属性（非标准键）。
     */
    private Map<String, String> extractCustomProperties(Properties props) {
        Set<String> standardKeys = Set.of(
                "plugin.id", "plugin.class", "plugin.version",
                "plugin.gameCode", "plugin.basePackage"
        );
        Map<String, String> custom = new HashMap<>();
        for (String key : props.stringPropertyNames()) {
            if (!standardKeys.contains(key)) {
                custom.put(key, props.getProperty(key));
            }
        }
        return custom;
    }

    // ==================== 内部类 ====================

    /** 端点映射信息 */
    private record EndpointMapping(Set<String> paths, Set<RequestMethod> methods) {
    }

    @Data
    private static class PluginContextInfo {
        private final PluginWrapper pluginWrapper;
        private final AnnotationConfigApplicationContext childContext;
        private final Set<String> controllerBeanNames;
        private final List<RequestMappingInfo> registeredMappings;
        /** 任务来源（gameCode 大写），用于插件卸载时取消/清理任务 */
        private final String taskSource;
    }
}

package com.gameplatform.plugin.schedule;

import org.pf4j.ExtensionPoint;

import java.util.List;

/**
 * 定时计划声明扩展点（ADR-0011 D5）
 *
 * <p>插件实现此接口并标注 {@code @Component}，由 {@code PluginSpringContextFactory}
 * 在插件加载时扫描，将返回的声明按稳定键 {@code pluginId:key} upsert 为默认计划。
 *
 * <p>与编程式 {@link ScheduleService} 的分工：
 * <ul>
 *   <li>声明式：随插件分发的默认计划（代码即文档，升级可演进）</li>
 *   <li>编程式：运行时按用户/业务动态创建的计划</li>
 * </ul>
 *
 * <p>示例：
 * <pre>{@code
 * @Component
 * public class L4D2ScheduleExtension implements ScheduledTaskDeclarationExtension {
 *     @Override
 *     public List<ScheduleDeclaration> getScheduleDeclarations() {
 *         return List.of(ScheduleDeclaration.builder()
 *                 .key("dailyMapCrawl")
 *                 .name("每日地图增量爬取")
 *                 .handlerKey("mapCrawl")
 *                 .cron("0 0 4 * * ?")
 *                 .payload(Map.of("crawlType", "increment"))
 *                 .enabled(true)
 *                 .build());
 *     }
 * }
 * }</pre>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface ScheduledTaskDeclarationExtension extends ExtensionPoint {

    /**
     * 返回插件声明的默认计划列表
     *
     * <p>同插件内 key 必须唯一，否则注册时抛出 IllegalStateException。
     * 返回空列表或不实现均合法（插件仅用编程式 API）。
     */
    List<ScheduleDeclaration> getScheduleDeclarations();
}

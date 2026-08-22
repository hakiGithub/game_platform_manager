package com.gameplatform.plugin.schedule;

import com.gameplatform.plugin.task.TaskContext;
import com.gameplatform.plugin.task.TaskPayload;
import com.gameplatform.plugin.task.TaskResult;
import org.pf4j.ExtensionPoint;

/**
 * 定时任务处理器契约（ADR-0011）
 *
 * <p>定时任务体系的独立执行契约，与任务中心 {@code TaskHandler} 完全分离——
 * 不复用其注册表、状态机与互斥键；无自动重试（下一轮 cron 即天然重试，
 * 失败后仅支持手动重跑）。
 *
 * <p>注册方式：插件子容器内一个 {@code @Component} 即一个 Handler，
 * 由 {@code PluginSpringContextFactory} 扫描后按 {@code (source, key)} 注册到
 * {@code ScheduledTaskHandlerRegistry}（source 为插件 gameCode 大写）。
 * 主应用 Handler 通过 core 侧 Spring Bean + 启动注册（source=MAIN）。
 *
 * <p>实现要求：
 * <ul>
 *   <li>实现类必须标注 {@code @Component} 以被插件子容器扫描</li>
 *   <li>Handler 必须无状态（依赖通过构造注入，状态通过 {@link TaskContext} 传递）</li>
 *   <li>不要在 execute 中捕获 InterruptedException 后吞掉，应重新设置中断标志并退出</li>
 *   <li>同一 Handler 可被多个计划引用，不同计划触发可并发执行——
 *       Handler 需对不同 payload 并发执行安全</li>
 *   <li>成败后的清理逻辑在 execute 内 try/finally 自理（契约无生命周期钩子）</li>
 * </ul>
 *
 * <p>示例：
 * <pre>{@code
 * @Component
 * public class MapCrawlScheduleHandler implements ScheduledTaskHandler {
 *     private final MapCenterService mapCenterService;
 *
 *     public MapCrawlScheduleHandler(MapCenterService mapCenterService) {
 *         this.mapCenterService = mapCenterService;
 *     }
 *
 *     @Override
 *     public String getKey() {
 *         return "mapCrawl";
 *     }
 *
 *     @Override
 *     public String getDisplayName() {
 *         return "地图定时爬取";
 *     }
 *
 *     @Override
 *     public TaskResult execute(TaskContext ctx, TaskPayload payload) throws Exception {
 *         String crawlType = payload.getString("crawlType", "increment");
 *         ctx.log("开始爬取，类型=" + crawlType);
 *         return mapCenterService.doCrawl(crawlType);
 *     }
 * }
 * }</pre>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface ScheduledTaskHandler extends ExtensionPoint {

    /**
     * 处理器 key（同一 source 内唯一），计划定义通过此 key 引用处理器
     */
    String getKey();

    /**
     * 显示名称（固定中文）
     */
    String getDisplayName();

    /**
     * 默认超时时间（毫秒）。0 表示不超时。
     *
     * <p>超时后流程与任务中心一致（协作式）：先置 timeout 标志由 Handler
     * 在循环中检查主动退出，超时阈值后再等 30s grace period 强制中断。
     * 默认 30 分钟。
     */
    default long getDefaultTimeoutMs() {
        return 30 * 60 * 1000L;
    }

    /**
     * 执行定时任务（核心方法）
     *
     * <p>实现要点：
     * <ol>
     *   <li>通过 {@link TaskContext#log} 记录关键节点日志（写 run 日志表）</li>
     *   <li>在循环中调用 {@link TaskContext#isCancelled()} 检查取消</li>
     *   <li>在循环中调用 {@link TaskContext#isTimeout()} 检查超时</li>
     *   <li>可通过 {@link TaskContext#reportProgress} 上报进度</li>
     *   <li>返回 {@link TaskResult} 包装执行结果</li>
     * </ol>
     *
     * @param context 执行上下文（run 版实现，taskId 为 runId）
     * @param payload 计划定义的 payload 模板（本次触发的快照）
     * @return 执行结果
     * @throws Exception 执行异常（自动记录到 run 的 error_message）
     */
    TaskResult execute(TaskContext context, TaskPayload payload) throws Exception;
}

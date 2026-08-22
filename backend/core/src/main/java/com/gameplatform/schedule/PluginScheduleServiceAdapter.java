package com.gameplatform.schedule;

import com.gameplatform.common.result.PageResult;
import com.gameplatform.plugin.schedule.ScheduleCreateRequest;
import com.gameplatform.plugin.schedule.ScheduleQuery;
import com.gameplatform.plugin.schedule.ScheduleRunQuery;
import com.gameplatform.plugin.schedule.ScheduleRunVO;
import com.gameplatform.plugin.schedule.ScheduleService;
import com.gameplatform.plugin.schedule.ScheduleUpdateRequest;
import com.gameplatform.plugin.schedule.ScheduleVO;
import com.gameplatform.plugin.task.TaskLog;

import java.util.List;

/**
 * 插件侧定时计划服务适配器（ADR-0011 D5）
 *
 * <p>由 PluginSpringContextFactory 在插件加载时按 {@code (pluginId, source)}
 * 创建并注册到插件子容器。所有操作委托 {@link ScheduleManagementService}
 * 的 Scoped 系列方法——source 在构造时绑定，插件无法伪造来源，
 * 也无法越权操作其他来源的计划。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public class PluginScheduleServiceAdapter implements ScheduleService {

    private final ScheduleManagementService delegate;
    private final String pluginId;
    private final String source;

    public PluginScheduleServiceAdapter(ScheduleManagementService delegate, String pluginId, String source) {
        this.delegate = delegate;
        this.pluginId = pluginId;
        this.source = source;
    }

    @Override
    public String create(ScheduleCreateRequest request) {
        return delegate.createScoped(source, pluginId, request);
    }

    @Override
    public void update(String id, ScheduleUpdateRequest request) {
        delegate.updateScoped(id, source, request);
    }

    @Override
    public void enable(String id) {
        delegate.enableScoped(id, source);
    }

    @Override
    public void disable(String id) {
        delegate.disableScoped(id, source);
    }

    @Override
    public void delete(String id) {
        delegate.deleteScoped(id, source);
    }

    @Override
    public String trigger(String id) {
        return delegate.triggerScoped(id, source);
    }

    @Override
    public ScheduleVO get(String id) {
        return delegate.getScoped(id, source);
    }

    @Override
    public PageResult<ScheduleVO> list(ScheduleQuery query) {
        return delegate.listScoped(query, source);
    }

    @Override
    public PageResult<ScheduleRunVO> listRuns(ScheduleRunQuery query) {
        return delegate.listRunsScoped(query, source);
    }

    @Override
    public List<TaskLog> getRunLogs(String runId) {
        return delegate.getRunLogsScoped(runId, source);
    }
}

package com.gameplatform.websocket;

/**
 * 实例日志获取适配器接口（架构评审 2026-08-13 候选 4）
 *
 * <p>接缝位于「日志来源」：生产实现包装 {@code DeployAdapter.getLogs}（按部署类型分发），
 * 测试注入假替身即可测 LogTailer 与 handler 的推送语义，无需真实 SSH/容器。
 */
public interface LogProvider {

    /**
     * 拉取指定实例的日志内容快照。
     *
     * @param instanceId 实例 ID
     * @param lines      期望行数（由实现解释）
     * @return 当前完整日志内容，或 null 表示不可用
     */
    String fetch(Long instanceId, int lines);
}

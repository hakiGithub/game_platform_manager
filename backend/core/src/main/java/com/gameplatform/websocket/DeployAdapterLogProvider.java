package com.gameplatform.websocket;

import com.gameplatform.adapter.DeployAdapter;
import com.gameplatform.adapter.DeployAdapterFactory;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.mapper.GameInstanceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 基于部署适配器的日志获取实现（生产 LogProvider）
 *
 * <p>按实例 deployType（经 DeploymentAccess.classify 归一）分发到对应适配器的 getLogs。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeployAdapterLogProvider implements LogProvider {

    private final GameInstanceMapper instanceMapper;
    private final DeployAdapterFactory adapterFactory;
    private final DeploymentAccess deployAccess;

    @Override
    public String fetch(Long instanceId, int lines) {
        GameInstance instance = instanceMapper.selectById(instanceId);
        if (instance == null) {
            // 与旧语义一致：实例不存在时终止日志推送（由调用方停止循环）
            throw new BusinessException("实例不存在");
        }
        DeployAdapter adapter = adapterFactory.getAdapter(
                deployAccess.classify(instance.getDeployType()));
        Map<String, Object> config = instance.getConfigInfo();
        try {
            return adapter.getLogs(instanceId, config, lines);
        } catch (Exception e) {
            log.error("获取日志失败: instanceId={}, error={}", instanceId, e.getMessage());
            return null;
        }
    }
}

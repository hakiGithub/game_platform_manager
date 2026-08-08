package com.gameplatform.adapter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 部署适配器工厂
 * 根据部署类型返回对应的适配器实例
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
public class DeployAdapterFactory {

    @Autowired
    private List<DeployAdapter> adapters;

    private final Map<DeployAdapter.DeployType, DeployAdapter> adapterMap = new HashMap<>();

    @PostConstruct
    public void init() {
        for (DeployAdapter adapter : adapters) {
            adapterMap.put(adapter.getDeployType(), adapter);
            log.info("注册部署适配器: {} -> {}", adapter.getDeployType(), adapter.getClass().getSimpleName());
        }
    }

    /**
     * 根据部署类型获取适配器
     *
     * @param deployType 部署类型
     * @return 适配器实例
     * @throws IllegalArgumentException 如果找不到对应的适配器
     */
    public DeployAdapter getAdapter(DeployAdapter.DeployType deployType) {
        DeployAdapter adapter = adapterMap.get(deployType);
        if (adapter == null) {
            throw new IllegalArgumentException("不支持的部署类型: " + deployType);
        }
        return adapter;
    }

    /**
     * 根据部署类型代码获取适配器
     *
     * @param typeCode 部署类型代码 (linuxgsm, docker, docker-compose)
     * @return 适配器实例
     * @throws IllegalArgumentException 如果找不到对应的适配器
     */
    public DeployAdapter getAdapter(String typeCode) {
        DeployAdapter.DeployType deployType = DeployAdapter.DeployType.fromCode(typeCode);
        if (deployType == null) {
            throw new IllegalArgumentException("不支持的部署类型代码: " + typeCode);
        }
        return getAdapter(deployType);
    }

    /**
     * 根据实例ID获取适配器
     * 通过查询实例的部署类型来获取对应适配器
     *
     * @param instanceId 实例ID
     * @return 适配器实例
     */
    public DeployAdapter getAdapterByInstanceId(Long instanceId) {
        // 这里需要通过实例服务查询实例的部署类型
        // 简化实现，实际应用中需要注入InstanceService
        throw new UnsupportedOperationException("请使用getAdapter(DeployType)或getAdapter(String)方法");
    }

    /**
     * 检查是否支持指定的部署类型
     *
     * @param deployType 部署类型
     * @return 是否支持
     */
    public boolean supports(DeployAdapter.DeployType deployType) {
        return adapterMap.containsKey(deployType);
    }

    /**
     * 检查是否支持指定的部署类型代码
     *
     * @param typeCode 部署类型代码
     * @return 是否支持
     */
    public boolean supports(String typeCode) {
        DeployAdapter.DeployType deployType = DeployAdapter.DeployType.fromCode(typeCode);
        return deployType != null && supports(deployType);
    }

    /**
     * 获取所有支持的部署类型
     *
     * @return 部署类型列表
     */
    public Map<DeployAdapter.DeployType, String> getSupportedTypes() {
        Map<DeployAdapter.DeployType, String> types = new HashMap<>();
        for (DeployAdapter.DeployType type : adapterMap.keySet()) {
            types.put(type, type.getDescription());
        }
        return types;
    }
}

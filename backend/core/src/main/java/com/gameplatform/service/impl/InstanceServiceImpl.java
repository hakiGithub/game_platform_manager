package com.gameplatform.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gameplatform.adapter.DeployAdapter;
import com.gameplatform.adapter.DeployAdapterFactory;
import com.gameplatform.adapter.DeployProgressCallback;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.common.result.PageResult;
import com.gameplatform.dto.*;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.GameMetadata;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.mapper.GameMetadataMapper;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.plugin.listener.PluginLifecycleHook;
import com.gameplatform.service.DeployService;
import com.gameplatform.service.InstanceService;
import com.gameplatform.service.LogService;
import com.gameplatform.util.SshUtil;
import com.gameplatform.vo.InstanceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 游戏实例服务实现类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstanceServiceImpl implements InstanceService {

    private final GameInstanceMapper instanceMapper;
    private final GameMetadataMapper gameMetadataMapper;
    private final HostMapper hostMapper;
    private final LogService logService;
    private final DeployAdapterFactory adapterFactory;
    private final DeployService deployService;
    private final SshUtil sshUtil;
    private final PluginLifecycleHook pluginLifecycleHook;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InstanceVO createInstance(InstanceCreateDTO dto) {
        // 检查同一主机下实例名称是否已存在（host_id + instance_name 联合唯一）
        // 注意：查询含已逻辑删除的记录，因为 UNIQUE 约束在物理层面对所有记录生效
        GameInstance existInstance = instanceMapper.selectByHostIdAndInstanceName(
                dto.getHostId(), dto.getInstanceName());
        if (existInstance != null) {
            if (existInstance.getDeleted() != null && existInstance.getDeleted() == 1) {
                // 逻辑删除残留记录导致 UNIQUE 约束冲突，物理清除后允许重试
                log.warn("检测到逻辑删除残留实例，物理清除以释放实例名: id={}, name={}",
                        existInstance.getId(), existInstance.getInstanceName());
                instanceMapper.physicalDeleteById(existInstance.getId());
            } else {
                throw new BusinessException("该主机下实例名称「" + dto.getInstanceName() + "」已存在，请更换名称后重试");
            }
        }

        // 检查主机是否存在
        Host host = hostMapper.selectById(dto.getHostId());
        if (host == null) {
            throw new BusinessException("主机不存在");
        }

        // 检查游戏是否存在
        GameMetadata game = gameMetadataMapper.selectById(dto.getGameId());
        if (game == null) {
            throw new BusinessException("游戏不存在");
        }

        GameInstance instance = new GameInstance();
        BeanUtil.copyProperties(dto, instance);

        // 设置游戏编码（用于插件匹配）
        instance.setGameCode(game.getGameCode());

        // 初始状态为部署中
        instance.setRunStatus(5);
        instance.setOnlinePlayers(0);

        try {
            instanceMapper.insert(instance);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.warn("同主机实例名称重复（数据库联合唯一约束）: hostId={}, name={}", dto.getHostId(), dto.getInstanceName());
            throw new BusinessException("该主机下实例名称「" + dto.getInstanceName() + "」已存在，请更换名称后重试");
        }

        logService.log(getCurrentUser(), "CREATE", "INSTANCE",
                "创建实例: " + instance.getInstanceName(), "success", null, null);

        // 通知 gameCode 匹配的插件扩展点（异常不影响实例创建）
        try {
            pluginLifecycleHook.executeInstanceCreateHooks(instance.getId(), game.getGameCode(),
                    instance.getConfigInfo() != null ? instance.getConfigInfo() : Map.of());
        } catch (Exception e) {
            log.warn("插件实例创建钩子执行异常，不影响实例创建: instanceId={}", instance.getId(), e);
        }

        // 构建部署上下文并异步触发部署（异常不影响实例创建响应）
        try {
            DeployService.DeployContext context = DeployService.DeployContext.builder()
                    .instanceId(instance.getId())
                    .hostId(dto.getHostId())
                    .deployType(DeployAdapter.DeployType.fromCode(dto.getDeployType()))
                    .config(buildDeployConfig(instance))
                    .autoRollback(false)
                    .autoStart(true)
                    .build();

            deployService.deployAsync(context);
        } catch (Exception e) {
            log.error("触发异步部署失败，实例已创建但部署未启动: instanceId={}", instance.getId(), e);
            // 部署触发失败时标记为异常状态
            try {
                instance.setRunStatus(2); // error
                instanceMapper.updateById(instance);
            } catch (Exception ex) {
                log.error("标记实例为异常状态失败: instanceId={}", instance.getId(), ex);
            }
        }

        InstanceVO vo = convertToVO(instance);
        vo.setDeployTaskId(String.valueOf(instance.getId()));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InstanceVO updateInstance(InstanceUpdateDTO dto) {
        GameInstance instance = instanceMapper.selectById(dto.getId());
        if (instance == null) {
            throw new BusinessException("实例不存在");
        }

        // 检查实例名称是否被同一主机下其他实例使用（host_id + instance_name 联合唯一）
        // 注意：查询含已逻辑删除的记录，因为 UNIQUE 约束在物理层面对所有记录生效
        if (dto.getInstanceName() != null && !dto.getInstanceName().equals(instance.getInstanceName())) {
            GameInstance existInstance = instanceMapper.selectByHostIdAndInstanceName(
                    instance.getHostId(), dto.getInstanceName());
            if (existInstance != null && !existInstance.getId().equals(dto.getId())) {
                if (existInstance.getDeleted() != null && existInstance.getDeleted() == 1) {
                    log.warn("检测到逻辑删除残留实例，物理清除以释放实例名: id={}, name={}",
                            existInstance.getId(), existInstance.getInstanceName());
                    instanceMapper.physicalDeleteById(existInstance.getId());
                } else {
                    throw new BusinessException("该主机下实例名称「" + dto.getInstanceName() + "」已被使用，请更换名称");
                }
            }
        }

        BeanUtil.copyProperties(dto, instance, "id");
        instanceMapper.updateById(instance);
        
        logService.log(getCurrentUser(), "UPDATE", "INSTANCE", 
                "更新实例: " + instance.getInstanceName(), "success", null, null);
        
        return convertToVO(instance);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteInstance(Long id) {
        GameInstance instance = instanceMapper.selectById(id);
        if (instance == null) {
            throw new BusinessException("实例不存在");
        }

        // 调用适配器 uninstall 完全清理远程资源（停止 + 删除容器 + 删除工作目录）
        // 忽略卸载失败，继续删除数据库记录，避免残留数据导致无法重新部署
        try {
            String deployType = instance.getDeployType();
            if (deployType == null || deployType.isEmpty()) {
                deployType = "native";
            }
            DeployAdapter adapter = adapterFactory.getAdapter(deployType);
            Map<String, Object> config = buildDeployConfig(instance);
            boolean uninstalled = adapter.uninstall(id, config, DeployProgressCallback.NO_OP);
            if (!uninstalled) {
                log.warn("实例远程资源卸载未完全成功，继续删除数据库记录: instanceId={}", id);
            }
        } catch (Exception e) {
            log.warn("实例远程资源卸载异常，继续删除数据库记录: instanceId={}", id, e);
        }

        // 通知 gameCode 匹配的插件扩展点（在删除前，让插件能感知到被删实例）
        pluginLifecycleHook.executeInstanceDeleteHooks(id, instance.getGameCode());

        // 使用物理删除，避免逻辑删除后 instance_name UNIQUE 约束仍冲突
        int rows = instanceMapper.physicalDeleteById(id);
        if (rows == 0) {
            throw new BusinessException("删除实例失败，记录不存在或已被删除");
        }
        log.info("物理删除实例成功: id={}, name={}", id, instance.getInstanceName());

        logService.log(getCurrentUser(), "DELETE", "INSTANCE",
                "删除实例: " + instance.getInstanceName(), "success", null, null);
    }

    @Override
    public InstanceVO getInstanceById(Long id) {
        GameInstance instance = instanceMapper.selectById(id);
        if (instance == null) {
            throw new BusinessException("实例不存在");
        }
        // 仅返回静态数据，不调用适配器 getDetails（避免 SSH/Docker 调用导致响应缓慢）
        // 动态资源数据（CPU/内存/运行时长）请通过 getInstanceMetrics 接口异步拉取
        return convertToVO(instance);
    }

    @Override
    public Map<String, Object> getInstanceMetrics(Long id) {
        Map<String, Object> metrics = new HashMap<>();
        GameInstance instance = instanceMapper.selectById(id);
        if (instance == null) {
            throw new BusinessException("实例不存在");
        }
        metrics.put("instanceId", id);
        metrics.put("runStatus", instance.getRunStatus());

        // 仅在实例运行中时拉取动态资源数据
        if (instance.getRunStatus() == null || instance.getRunStatus() != 1) {
            metrics.put("available", false);
            metrics.put("reason", "实例未运行");
            return metrics;
        }

        metrics.put("available", true);
        try {
            String deployType = instance.getDeployType();
            if (deployType == null || deployType.isEmpty()) {
                deployType = "native";
            }
            DeployAdapter adapter = adapterFactory.getAdapter(deployType);
            Map<String, Object> config = buildDeployConfig(instance);
            Map<String, Object> details = adapter.getDetails(id, config);
            if (details != null && !details.isEmpty()) {
                Object cpu = details.get("cpuUsage");
                if (cpu instanceof Number) {
                    metrics.put("cpuUsage", ((Number) cpu).doubleValue());
                }
                Object mem = details.get("memoryUsage");
                if (mem instanceof Number) {
                    metrics.put("memoryUsage", ((Number) mem).doubleValue());
                }
                Object memText = details.get("memoryUsageText");
                if (memText instanceof String) {
                    metrics.put("memoryUsageText", (String) memText);
                }
                Object uptime = details.get("uptime");
                if (uptime instanceof Number) {
                    metrics.put("uptime", ((Number) uptime).longValue());
                }
                Object players = details.get("onlinePlayers");
                if (players instanceof Number) {
                    metrics.put("onlinePlayers", ((Number) players).intValue());
                }
            }
        } catch (Exception e) {
            log.warn("获取实例 {} 动态资源数据失败: {}", id, e.getMessage());
            metrics.put("available", false);
            metrics.put("reason", e.getMessage());
        }
        return metrics;
    }

    @Override
    public PageResult<InstanceVO> pageInstances(PageQueryDTO queryDTO) {
        LambdaQueryWrapper<GameInstance> wrapper = new LambdaQueryWrapper<>();
        
        // 关键词搜索
        if (queryDTO.getKeyword() != null && !queryDTO.getKeyword().isEmpty()) {
            wrapper.like(GameInstance::getInstanceName, queryDTO.getKeyword());
        }
        
        // 排序
        wrapper.orderByDesc(GameInstance::getCreateTime);
        
        Page<GameInstance> page = new Page<>(queryDTO.getCurrent(), queryDTO.getSize());
        Page<GameInstance> result = instanceMapper.selectPage(page, wrapper);
        
        List<InstanceVO> voList = result.getRecords().stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        
        return new PageResult<>(voList, result.getTotal(), queryDTO.getCurrent(), queryDTO.getSize());
    }

    @Override
    public List<InstanceVO> getInstancesByHostId(Long hostId) {
        List<GameInstance> instances = instanceMapper.selectByHostId(hostId);
        return instances.stream().map(this::convertToVO).collect(Collectors.toList());
    }

    @Override
    public List<InstanceVO> getInstancesByGameId(Long gameId) {
        List<GameInstance> instances = instanceMapper.selectByGameId(gameId);
        return instances.stream().map(this::convertToVO).collect(Collectors.toList());
    }

    @Override
    public List<InstanceVO> getInstancesByGameCode(String gameCode) {
        List<GameInstance> instances = instanceMapper.selectByGameCode(gameCode);
        return instances.stream().map(this::convertToVO).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean startInstance(Long id) {
        log.info("开始启动实例: {}", id);
        
        GameInstance instance = instanceMapper.selectById(id);
        if (instance == null) {
            throw new BusinessException("实例不存在");
        }
        
        if (instance.getRunStatus() == 1) {
            throw new BusinessException("实例已在运行中");
        }
        
        // 更新状态为启动中
        instanceMapper.updateRunStatus(id, 2);
        
        try {
            // 获取适配器
            String deployType = instance.getDeployType();
            if (deployType == null || deployType.isEmpty()) {
                deployType = "native";
            }
            DeployAdapter adapter = adapterFactory.getAdapter(deployType);
            
            // 获取配置（使用完整部署配置，包含 installPath/workDir 等运行时元数据）
            Map<String, Object> config = buildDeployConfig(instance);
            
            // 调用适配器启动
            boolean success = adapter.start(id, config);
            
            if (success) {
                instanceMapper.updateRunStatus(id, 1);
                logService.log(getCurrentUser(), "START", "INSTANCE",
                        "启动实例成功: " + instance.getInstanceName(), "success", null, null);
                log.info("实例启动成功: {}", instance.getInstanceName());
                // 通知 gameCode 匹配的插件扩展点
                pluginLifecycleHook.executeInstanceStartHooks(id, instance.getGameCode());
            } else {
                instanceMapper.updateRunStatus(id, 2); // 异常状态
                logService.log(getCurrentUser(), "START", "INSTANCE", 
                        "启动实例失败: " + instance.getInstanceName(), "failure", null, "启动命令执行失败");
                log.error("实例启动失败: {}", instance.getInstanceName());
            }
            
            return success;
        } catch (Exception e) {
            instanceMapper.updateRunStatus(id, 2); // 异常状态
            logService.log(getCurrentUser(), "START", "INSTANCE", 
                    "启动实例异常: " + instance.getInstanceName(), "failure", null, e.getMessage());
            log.error("启动实例异常: {}", instance.getInstanceName(), e);
            throw new BusinessException("启动实例失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean stopInstance(Long id) {
        log.info("开始停止实例: {}", id);
        
        GameInstance instance = instanceMapper.selectById(id);
        if (instance == null) {
            throw new BusinessException("实例不存在");
        }
        
        if (instance.getRunStatus() == 0) {
            throw new BusinessException("实例已停止");
        }
        
        // 更新状态为停止中
        instanceMapper.updateRunStatus(id, 3);
        
        try {
            // 获取适配器
            String deployType = instance.getDeployType();
            if (deployType == null || deployType.isEmpty()) {
                deployType = "native";
            }
            DeployAdapter adapter = adapterFactory.getAdapter(deployType);
            
            // 获取配置（使用完整部署配置，包含 installPath/workDir 等运行时元数据）
            Map<String, Object> config = buildDeployConfig(instance);
            
            // 调用适配器停止
            boolean success = adapter.stop(id, config);
            
            if (success) {
                instanceMapper.updateRunStatus(id, 0);
                instanceMapper.updateOnlinePlayers(id, 0);
                logService.log(getCurrentUser(), "STOP", "INSTANCE",
                        "停止实例成功: " + instance.getInstanceName(), "success", null, null);
                log.info("实例停止成功: {}", instance.getInstanceName());
                // 通知 gameCode 匹配的插件扩展点
                pluginLifecycleHook.executeInstanceStopHooks(id, instance.getGameCode());
            } else {
                instanceMapper.updateRunStatus(id, 2); // 异常状态
                logService.log(getCurrentUser(), "STOP", "INSTANCE", 
                        "停止实例失败: " + instance.getInstanceName(), "failure", null, "停止命令执行失败");
                log.error("实例停止失败: {}", instance.getInstanceName());
            }
            
            return success;
        } catch (Exception e) {
            instanceMapper.updateRunStatus(id, 2); // 异常状态
            logService.log(getCurrentUser(), "STOP", "INSTANCE", 
                    "停止实例异常: " + instance.getInstanceName(), "failure", null, e.getMessage());
            log.error("停止实例异常: {}", instance.getInstanceName(), e);
            throw new BusinessException("停止实例失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean restartInstance(Long id) {
        log.info("开始重启实例: {}", id);
        
        GameInstance instance = instanceMapper.selectById(id);
        if (instance == null) {
            throw new BusinessException("实例不存在");
        }
        
        try {
            // 获取适配器
            String deployType = instance.getDeployType();
            if (deployType == null || deployType.isEmpty()) {
                deployType = "native";
            }
            DeployAdapter adapter = adapterFactory.getAdapter(deployType);
            
            // 获取配置（使用完整部署配置，包含 installPath/workDir 等运行时元数据）
            Map<String, Object> config = buildDeployConfig(instance);
            
            // 调用适配器重启
            boolean success = adapter.restart(id, config);
            
            if (success) {
                instanceMapper.updateRunStatus(id, 1);
                logService.log(getCurrentUser(), "RESTART", "INSTANCE", 
                        "重启实例成功: " + instance.getInstanceName(), "success", null, null);
                log.info("实例重启成功: {}", instance.getInstanceName());
            } else {
                instanceMapper.updateRunStatus(id, 2); // 异常状态
                logService.log(getCurrentUser(), "RESTART", "INSTANCE", 
                        "重启实例失败: " + instance.getInstanceName(), "failure", null, "重启命令执行失败");
                log.error("实例重启失败: {}", instance.getInstanceName());
            }
            
            return success;
        } catch (Exception e) {
            instanceMapper.updateRunStatus(id, 2); // 异常状态
            logService.log(getCurrentUser(), "RESTART", "INSTANCE", 
                    "重启实例异常: " + instance.getInstanceName(), "failure", null, e.getMessage());
            log.error("重启实例异常: {}", instance.getInstanceName(), e);
            throw new BusinessException("重启实例失败: " + e.getMessage());
        }
    }

    /**
     * 获取实例状态（包含健康检查）
     *
     * @param id 实例ID
     * @return 实例VO
     */
    public InstanceVO getInstanceStatus(Long id) {
        GameInstance instance = instanceMapper.selectById(id);
        if (instance == null) {
            throw new BusinessException("实例不存在");
        }
        
        try {
            // 获取适配器
            String deployType = instance.getDeployType();
            if (deployType == null || deployType.isEmpty()) {
                deployType = "native";
            }
            DeployAdapter adapter = adapterFactory.getAdapter(deployType);
            
            // 获取配置（使用完整部署配置，包含 installPath/workDir 等运行时元数据）
            Map<String, Object> config = buildDeployConfig(instance);
            
            // 调用适配器健康检查
            DeployAdapter.InstanceStatus status = adapter.getStatus(id, config);
            
            // 更新数据库状态（如果与实际情况不符）
            int dbStatus = instance.getRunStatus();
            int actualStatus = status.getCode();
            
            if (dbStatus != actualStatus && (actualStatus == 0 || actualStatus == 1)) {
                instanceMapper.updateRunStatus(id, actualStatus);
                instance.setRunStatus(actualStatus);
            }
            
            // 如果运行中，尝试获取在线玩家数
            if (status == DeployAdapter.InstanceStatus.RUNNING) {
                updateOnlinePlayers(instance, adapter, config);
            }
            
        } catch (Exception e) {
            log.error("获取实例状态异常: {}", instance.getInstanceName(), e);
            // 标记为异常状态
            instance.setRunStatus(2);
        }
        
        return convertToVO(instance);
    }

    @Override
    public String getInstanceLogs(Long id, int lines) {
        GameInstance instance = instanceMapper.selectById(id);
        if (instance == null) {
            throw new BusinessException("实例不存在");
        }
        
        try {
            // 获取适配器
            String deployType = instance.getDeployType();
            if (deployType == null || deployType.isEmpty()) {
                deployType = "native";
            }
            DeployAdapter adapter = adapterFactory.getAdapter(deployType);
            
            // 获取配置（使用完整部署配置，包含 installPath/workDir 等运行时元数据）
            Map<String, Object> config = buildDeployConfig(instance);
            
            // 调用适配器获取日志
            return adapter.getLogs(id, config, lines);
            
        } catch (Exception e) {
            log.error("获取实例日志异常: {}", instance.getInstanceName(), e);
            return "获取日志失败: " + e.getMessage();
        }
    }

    /**
     * 执行实例命令
     *
     * @param id      实例ID
     * @param command 命令
     * @return 执行结果
     */
    public String executeCommand(Long id, String command) {
        GameInstance instance = instanceMapper.selectById(id);
        if (instance == null) {
            throw new BusinessException("实例不存在");
        }
        
        try {
            // 获取适配器
            String deployType = instance.getDeployType();
            if (deployType == null || deployType.isEmpty()) {
                deployType = "native";
            }
            DeployAdapter adapter = adapterFactory.getAdapter(deployType);
            
            // 获取配置（使用完整部署配置，包含 installPath/workDir 等运行时元数据）
            Map<String, Object> config = buildDeployConfig(instance);
            
            // 调用适配器执行命令
            String result = adapter.executeCommand(id, config, command);
            
            logService.log(getCurrentUser(), "EXECUTE", "INSTANCE", 
                    "执行命令: " + instance.getInstanceName() + " - " + command, "success", null, null);
            
            return result;
            
        } catch (Exception e) {
            log.error("执行实例命令异常: {}", instance.getInstanceName(), e);
            throw new BusinessException("执行命令失败: " + e.getMessage());
        }
    }

    /**
     * 更新在线玩家数
     *
     * @param instance 实例
     * @param adapter  适配器
     * @param config   配置
     */
    private void updateOnlinePlayers(GameInstance instance, DeployAdapter adapter, Map<String, Object> config) {
        try {
            // 获取实例详情
            Map<String, Object> details = adapter.getDetails(instance.getId(), config);
            if (details != null && details.get("onlinePlayers") != null) {
                int onlinePlayers = Integer.parseInt(details.get("onlinePlayers").toString());
                instanceMapper.updateOnlinePlayers(instance.getId(), onlinePlayers);
                instance.setOnlinePlayers(onlinePlayers);
            }
        } catch (Exception e) {
            log.debug("获取在线玩家数失败: {}", instance.getInstanceName());
        }
    }

    /**
     * 构建部署配置 Map（合并游戏元数据部署模板、configInfo、portConfig、installPath）
     * 优先级：游戏元数据部署模板（最低） < configInfo < portConfig < 实例字段（最高）
     */
    private Map<String, Object> buildDeployConfig(GameInstance instance) {
        Map<String, Object> config = new HashMap<>();

        // 1. 从游戏元数据中注入部署类型对应的模板配置（docker.image、docker.ports 等）
        try {
            GameMetadata game = gameMetadataMapper.selectById(instance.getGameId());
            if (game != null && game.getDeployConfig() != null) {
                String deployType = instance.getDeployType();
                if (deployType != null && !deployType.isEmpty()) {
                    Object typeConfig = game.getDeployConfig().get(deployType);
                    if (typeConfig instanceof Map) {
                        config.putAll((Map<String, Object>) typeConfig);
                    }
                }
                // 注入 defaultPorts（供 adapter 判断用户是否改了端口，决定是否需要端口映射）
                Object defaultPorts = game.getDeployConfig().get("defaultPorts");
                if (defaultPorts instanceof Map) {
                    config.put("defaultPorts", defaultPorts);
                }
            }
        } catch (Exception e) {
            log.warn("从游戏元数据注入部署模板失败: instanceId={}, gameId={}",
                    instance.getId(), instance.getGameId(), e);
        }

        // 2. 合并实例的 configInfo（用户配置，覆盖模板默认值）
        if (instance.getConfigInfo() != null) {
            config.putAll(instance.getConfigInfo());
        }

        // 3. 合并实例的 portConfig（端口配置）
        if (instance.getPortConfig() != null) {
            config.putAll(instance.getPortConfig());
        }

        // 4. 注入实例元数据
        config.put("installPath", instance.getInstallPath());
        config.put("instanceId", instance.getId());
        config.put("gameCode", instance.getGameCode());

        // 5. 注入运行时元数据（供适配器使用）
        // 将 runtimeMetadata 中的关键字段提升到 config 顶层，确保 uninstall/stop/start 等后续操作
        // 能还原部署时的 projectName/workDir/containerName（这些值在 preDeploy 中展开 ~ 后写入，
        // 但只持久化到 runtimeMetadata，不会回写到 configInfo）。
        if (instance.getRuntimeMetadata() != null) {
            Map<String, Object> metadata = instance.getRuntimeMetadata();
            config.put("runtimeMetadata", metadata);
            promoteMetadataIfAbsent(config, metadata, "projectName");
            promoteMetadataIfAbsent(config, metadata, "workDir");
            promoteMetadataIfAbsent(config, metadata, "containerName");
            promoteMetadataIfAbsent(config, metadata, "shortname");
            promoteMetadataIfAbsent(config, metadata, "serviceName");
        }

        // 6. 组合完整的镜像名（image:tag）
        String image = (String) config.get("image");
        String tag = (String) config.get("tag");
        if (image != null && !image.isEmpty() && tag != null && !tag.isEmpty()
                && !image.contains(":")) {
            config.put("image", image + ":" + tag);
        }

        log.debug("构建部署配置完成: instanceId={}, deployType={}, image={}",
                instance.getId(), instance.getDeployType(), config.get("image"));
        return config;
    }

    /**
     * 将 runtimeMetadata 中的字段提升到 config 顶层（仅当顶层不存在或为空时）。
     * 用于在 uninstall/stop/start 等后续操作中还原部署时的 projectName/workDir/containerName。
     */
    private void promoteMetadataIfAbsent(Map<String, Object> config, Map<String, Object> metadata, String key) {
        if (metadata == null || !metadata.containsKey(key)) {
            return;
        }
        Object existing = config.get(key);
        if (existing == null
                || (existing instanceof String && ((String) existing).isEmpty())) {
            config.put(key, metadata.get(key));
        }
    }

    @Override
    public void retryDeploy(Long id) {
        GameInstance instance = instanceMapper.selectById(id);
        if (instance == null) {
            throw new BusinessException("实例不存在");
        }
        if (instance.getRunStatus() != 2) {
            throw new BusinessException("只有异常状态的实例可以重试部署");
        }

        // 标记为部署中
        instance.setRunStatus(5);
        instanceMapper.updateById(instance);

        // 先清理旧容器（忽略失败）
        try {
            String deployType = instance.getDeployType();
            if (deployType != null && !deployType.isEmpty()) {
                DeployAdapter adapter = adapterFactory.getAdapter(deployType);
                adapter.uninstall(id, buildDeployConfig(instance), DeployProgressCallback.NO_OP);
            }
        } catch (Exception e) {
            log.warn("重试部署时清理旧容器失败: instanceId={}", id, e);
        }

        // 重新触发部署
        DeployService.DeployContext context = DeployService.DeployContext.builder()
                .instanceId(instance.getId())
                .hostId(instance.getHostId())
                .deployType(DeployAdapter.DeployType.fromCode(instance.getDeployType()))
                .config(buildDeployConfig(instance))
                .autoRollback(false)
                .autoStart(true)
                .build();

        deployService.deployAsync(context);
    }

    @Override
    public int recoverDeployingInstances() {
        List<GameInstance> deploying = instanceMapper.selectList(
                new LambdaQueryWrapper<GameInstance>()
                        .eq(GameInstance::getRunStatus, 5));

        if (deploying.isEmpty()) {
            log.info("手动恢复：未发现 run_status=5 的实例");
            return 0;
        }

        log.warn("手动恢复：发现 {} 个 run_status=5 的实例，将标记为异常", deploying.size());
        int count = 0;
        for (GameInstance instance : deploying) {
            try {
                instance.setRunStatus(2); // error
                int rows = instanceMapper.updateById(instance);
                if (rows > 0) {
                    count++;
                    log.warn("手动恢复：实例 {} [{}] 已标记为异常", instance.getId(), instance.getInstanceName());
                }
            } catch (Exception e) {
                log.error("手动恢复：实例 {} [{}] 标记异常失败", instance.getId(), instance.getInstanceName(), e);
            }
        }
        log.info("手动恢复完成：共恢复 {} 个实例", count);
        return count;
    }

    /**
     * 转换为VO
     */
    private InstanceVO convertToVO(GameInstance instance) {
        InstanceVO vo = new InstanceVO();
        BeanUtil.copyProperties(instance, vo);
        
        // 获取主机名称和IP
        Host host = hostMapper.selectById(instance.getHostId());
        if (host != null) {
            vo.setHostName(host.getHostName());
            vo.setHostIp(host.getIpAddress());
        }
        
        // 获取游戏名称和图标
        GameMetadata game = gameMetadataMapper.selectById(instance.getGameId());
        if (game != null) {
            vo.setGameName(game.getGameName());
            vo.setIconUrl(game.getIconUrl());
        }

        // 映射 runStatus 到 status 字符串
        vo.setStatus(mapRunStatusToString(instance.getRunStatus()));

        return vo;
    }

    /**
     * 将 runStatus 整型映射为前端使用的 status 字符串
     */
    private String mapRunStatusToString(Integer runStatus) {
        if (runStatus == null) return "unknown";
        return switch (runStatus) {
            case 0 -> "stopped";
            case 1 -> "running";
            case 2 -> "error";
            case 3 -> "stopping";
            case 5 -> "deploying";
            case 6 -> "starting";
            default -> "unknown";
        };
    }

    /**
     * 获取当前用户
     */
    private String getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            return authentication.getName();
        }
        return "system";
    }

}

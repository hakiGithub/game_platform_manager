package com.gameplatform.service.sync.impl;

import com.gameplatform.config.InstanceSyncProperties;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.service.sync.DockerInstanceSyncStrategy;
import com.gameplatform.service.sync.InstanceSyncService;
import com.gameplatform.service.sync.NativeInstanceSyncStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 实例状态同步服务实现
 *
 * <p>调度策略：
 * <ul>
 *   <li>从 {@link HostMapper#selectOnlineHosts()} 获取在线主机（含完整 SSH 凭据）</li>
 *   <li>每台主机按 deploy_type 分组：
 *     <ul>
 *       <li>Docker 类（docker / docker-compose / linuxgsm-docker）→ {@link DockerInstanceSyncStrategy}</li>
 *       <li>Native 类（linuxgsm 及其他）→ {@link NativeInstanceSyncStrategy}</li>
 *     </ul>
 *   </li>
 *   <li>主机级异常隔离：单台主机失败不影响其他主机</li>
 *   <li>变更统计：执行前快照 run_status，执行后比对差异</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Service
public class InstanceSyncServiceImpl implements InstanceSyncService {

    private static final Logger log = LoggerFactory.getLogger(InstanceSyncServiceImpl.class);
    private static final String LOG_PREFIX = "[InstanceSync]";

    /**
     * Docker 类部署类型常量
     */
    private static final List<String> DOCKER_DEPLOY_TYPES = List.of(
            "docker", "docker-compose", "linuxgsm-docker"
    );

    private final HostMapper hostMapper;
    private final GameInstanceMapper instanceMapper;
    private final DockerInstanceSyncStrategy dockerStrategy;
    private final NativeInstanceSyncStrategy nativeStrategy;
    private final InstanceSyncProperties properties;

    public InstanceSyncServiceImpl(HostMapper hostMapper,
                                    GameInstanceMapper instanceMapper,
                                    DockerInstanceSyncStrategy dockerStrategy,
                                    NativeInstanceSyncStrategy nativeStrategy,
                                    InstanceSyncProperties properties) {
        this.hostMapper = hostMapper;
        this.instanceMapper = instanceMapper;
        this.dockerStrategy = dockerStrategy;
        this.nativeStrategy = nativeStrategy;
        this.properties = properties;
    }

    @Override
    public SyncSummary syncAll() {
        if (!properties.isEnabled()) {
            log.info("{} 同步已禁用 (game-platform.instance-sync.enabled=false)", LOG_PREFIX);
            return SyncSummary.empty();
        }

        List<Host> onlineHosts;
        try {
            onlineHosts = hostMapper.selectOnlineHosts();
        } catch (Exception e) {
            log.error("{} 查询在线主机列表失败: {}", LOG_PREFIX, e.getMessage(), e);
            return SyncSummary.empty();
        }

        if (onlineHosts == null || onlineHosts.isEmpty()) {
            log.info("{} 无在线主机，跳过同步", LOG_PREFIX);
            return SyncSummary.empty();
        }

        log.info("{} 开始同步 {} 台在线主机的实例状态", LOG_PREFIX, onlineHosts.size());

        int successHosts = 0;
        int failedHosts = 0;
        int totalUpdated = 0;

        for (Host host : onlineHosts) {
            try {
                int updated = syncHost(host);
                successHosts++;
                totalUpdated += updated;
            } catch (Exception e) {
                failedHosts++;
                log.error("{} 主机 {} ({}) 同步失败: {}",
                        LOG_PREFIX, host.getHostName(), host.getIpAddress(), e.getMessage(), e);
            }
        }

        SyncSummary summary = new SyncSummary(onlineHosts.size(), successHosts, failedHosts, totalUpdated);
        log.info("{} 同步完成: 总主机={}, 成功={}, 失败={}, 实例变更={}",
                LOG_PREFIX, summary.totalHosts(), summary.successHosts(),
                summary.failedHosts(), summary.totalUpdated());
        return summary;
    }

    @Override
    public int syncHost(Host host) {
        if (host == null || host.getId() == null) {
            return 0;
        }

        List<GameInstance> dockerInstances;
        try {
            dockerInstances = instanceMapper.selectByHostIdAndDeployTypes(host.getId(), DOCKER_DEPLOY_TYPES);
        } catch (Exception e) {
            log.warn("{} 主机 {} ({}): 查询 Docker 类实例失败: {}",
                    LOG_PREFIX, host.getHostName(), host.getIpAddress(), e.getMessage());
            dockerInstances = List.of();
        }

        List<GameInstance> nativeInstances;
        try {
            List<GameInstance> all = instanceMapper.selectByHostId(host.getId());
            nativeInstances = all == null ? List.of() :
                    all.stream()
                            .filter(i -> !DOCKER_DEPLOY_TYPES.contains(i.getDeployType()))
                            .toList();
        } catch (Exception e) {
            log.warn("{} 主机 {} ({}): 查询 Native 类实例失败: {}",
                    LOG_PREFIX, host.getHostName(), host.getIpAddress(), e.getMessage());
            nativeInstances = List.of();
        }

        int dockerCount = dockerInstances == null ? 0 : dockerInstances.size();
        int nativeCount = nativeInstances == null ? 0 : nativeInstances.size();

        if (dockerCount == 0 && nativeCount == 0) {
            log.debug("{} 主机 {} ({}): 无实例可同步", LOG_PREFIX, host.getHostName(), host.getIpAddress());
            return 0;
        }

        log.info("{} 主机 {} ({}): 开始同步 (Docker 类={}, Native 类={})",
                LOG_PREFIX, host.getHostName(), host.getIpAddress(), dockerCount, nativeCount);

        // 执行前快照 run_status，用于统计实际变更数
        Map<Long, Integer> dockerSnapshot = snapshotStatuses(dockerInstances);
        Map<Long, Integer> nativeSnapshot = snapshotStatuses(nativeInstances);

        if (dockerCount > 0) {
            dockerStrategy.syncHost(host, dockerInstances);
        }
        if (nativeCount > 0) {
            nativeStrategy.syncHost(host, nativeInstances);
        }

        int dockerChanged = countChanges(dockerInstances, dockerSnapshot);
        int nativeChanged = countChanges(nativeInstances, nativeSnapshot);
        int totalChanged = dockerChanged + nativeChanged;

        log.info("{} 主机 {} ({}): 同步完成，实例变更={} (Docker={}, Native={})",
                LOG_PREFIX, host.getHostName(), host.getIpAddress(),
                totalChanged, dockerChanged, nativeChanged);
        return totalChanged;
    }

    private Map<Long, Integer> snapshotStatuses(List<GameInstance> instances) {
        Map<Long, Integer> snapshot = new HashMap<>();
        if (instances == null) return snapshot;
        for (GameInstance i : instances) {
            if (i.getId() != null) {
                snapshot.put(i.getId(), i.getRunStatus());
            }
        }
        return snapshot;
    }

    private int countChanges(List<GameInstance> instances, Map<Long, Integer> snapshot) {
        if (instances == null || snapshot == null) return 0;
        int changed = 0;
        for (GameInstance i : instances) {
            Integer oldStatus = snapshot.get(i.getId());
            if (oldStatus != null && !oldStatus.equals(i.getRunStatus())) {
                changed++;
            }
        }
        return changed;
    }
}

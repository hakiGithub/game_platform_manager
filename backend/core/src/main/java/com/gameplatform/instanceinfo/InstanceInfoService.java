package com.gameplatform.instanceinfo;

import com.gameplatform.adapter.DeployAdapter;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.plugin.extension.InstanceDynamicInfo;
import com.gameplatform.plugin.extension.InstanceInfoProvider;
import com.gameplatform.vo.InstanceVO;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 实例动态信息查询服务（ADR-0017）。
 * <p>
 * 实时查询 + 实例维度 TTL 缓存（Guava，默认 15s）：列表对当页 RUNNING 实例
 * 并发调用 {@link InstanceInfoProvider}，整体预算内收口（默认 3s），单实例
 * 超时 5s。查询成功且玩家数变化才回写 {@code game_instance.online_players}
 * （唯一事实源）；无 Provider / 返回 null / 超时超预算一律降级为 VO 现值
 * （RUNNING=库存量值，非 RUNNING=0），降级不覆盖已落库值。
 */
@Slf4j
@Service
public class InstanceInfoService {

    private final InstanceInfoProviderRegistry registry;
    private final InstanceInfoProperties properties;
    private final GameInstanceMapper instanceMapper;

    private final ThreadPoolExecutor executor;
    private final Cache<Long, Optional<InstanceDynamicInfo>> cache;

    public InstanceInfoService(InstanceInfoProviderRegistry registry,
                               InstanceInfoProperties properties,
                               GameInstanceMapper instanceMapper) {
        this.registry = registry;
        this.properties = properties;
        this.instanceMapper = instanceMapper;

        InstanceInfoProperties.Pool poolCfg = properties.getPool();
        this.executor = new ThreadPoolExecutor(poolCfg.getCoreSize(), poolCfg.getMaxSize(),
                60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(200),
                r -> {
                    Thread t = new Thread(r, "instance-info-query");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);

        this.cache = CacheBuilder.newBuilder()
                .expireAfterWrite(properties.getCacheTtlSeconds(), TimeUnit.SECONDS)
                .build();
    }

    /**
     * 批量增强列表 VO：对当页实例并发查询（缓存未命中的才真正调 Provider），
     * 整体预算内收口（默认 3s）。
     */
    public void enrichInstances(List<InstanceVO> voList) {
        enrichInstances(voList, properties.getQueryBudgetMillis());
    }

    /**
     * 单实例增强（详情场景）：预算放宽到单实例查询超时（5s，ADR-0017 决策 4）。
     */
    public void enrichInstance(InstanceVO vo) {
        if (vo == null) {
            return;
        }
        enrichInstances(List.of(vo), properties.getPerQueryTimeoutMillis());
    }

    private void enrichInstances(List<InstanceVO> voList, long budgetMillis) {
        if (voList == null || voList.isEmpty()) {
            return;
        }
        long deadline = System.currentTimeMillis() + budgetMillis;
        Map<Long, CompletableFuture<Optional<InstanceDynamicInfo>>> pending = new HashMap<>();
        Map<Long, InstanceVO> voById = new HashMap<>();
        for (InstanceVO v : voList) {
            voById.put(v.getId(), v);
        }
        // 请求线程放弃等待的实例（超预算/异常），其结果由异步回调补缓存+回写
        Set<Long> abandoned = ConcurrentHashMap.newKeySet();

        for (InstanceVO vo : voList) {
            if (!isRunning(vo)) {
                vo.setOnlinePlayers(0);
                continue;
            }
            if (isProviderAbsent(vo)) {
                continue;
            }
            Optional<InstanceDynamicInfo> cached = cache.getIfPresent(vo.getId());
            if (cached != null) {
                apply(vo, cached.orElse(null));
                continue;
            }
            try {
                pending.put(vo.getId(), submitQuery(vo, abandoned));
            } catch (RejectedExecutionException e) {
                log.warn("[InstanceInfo] 查询线程池饱和，实例 {} 本次降级", vo.getId());
            }
        }

        int degraded = 0;
        for (Map.Entry<Long, CompletableFuture<Optional<InstanceDynamicInfo>>> entry : pending.entrySet()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                degraded++;
                abandoned.add(entry.getKey());
                continue;
            }
            InstanceVO vo = voById.get(entry.getKey());
            if (vo == null) {
                continue;
            }
            try {
                Optional<InstanceDynamicInfo> info = entry.getValue().get(remaining, TimeUnit.MILLISECONDS);
                cache.put(entry.getKey(), info);
                apply(vo, info.orElse(null));
            } catch (Exception e) {
                degraded++;
                abandoned.add(entry.getKey());
                if (e instanceof java.util.concurrent.TimeoutException) {
                    log.debug("[InstanceInfo] 实例 {} 查询超预算，本次降级", entry.getKey());
                } else {
                    log.warn("[InstanceInfo] 实例 {} 查询失败，本次降级: {}", entry.getKey(), e.getMessage());
                }
            }
        }
        if (degraded > 0) {
            log.info("[InstanceInfo] 列表查询完成，{} 个实例降级（超预算/失败）", degraded);
        }
    }

    /**
     * 失效实例缓存（实例停止/删除时联动）。
     */
    public void invalidate(long instanceId) {
        cache.invalidate(instanceId);
    }

    /**
     * 失效全部缓存（插件卸载/热重载时的超集失效；TTL 有界，无害）。
     */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        cache.invalidateAll();
    }

    // ========== 内部方法 ==========

    private CompletableFuture<Optional<InstanceDynamicInfo>> submitQuery(InstanceVO vo, Set<Long> abandoned) {
        InstanceInfoProvider provider = registry.findBySource(providerSource(vo)).orElseThrow();
        Long instanceId = vo.getId();
        CompletableFuture<Optional<InstanceDynamicInfo>> future = CompletableFuture.supplyAsync(() -> {
            try {
                return Optional.ofNullable(provider.getInstanceInfo(instanceId));
            } catch (Exception e) {
                log.debug("[InstanceInfo] Provider 查询实例 {} 异常: {}", instanceId, e.getMessage());
                return Optional.<InstanceDynamicInfo>empty();
            }
        }, executor);
        // ADR-0017 决策 4：请求线程超预算放弃等待后，已完成的结果仍要落缓存并回写，
        // 供下一个请求命中（否则冷连接场景会反复降级）；正常路径由请求线程处理
        future.whenComplete((info, ex) -> {
            if (ex != null) {
                return;
            }
            // 缓存写入始终执行（幂等，值相同或更新；TTL 有界防止旧值长驻）
            Optional<InstanceDynamicInfo> prev = cache.getIfPresent(instanceId);
            cache.put(instanceId, info);
            // 回写仅在请求线程已放弃等待时执行，正常路径由请求线程按 VO 旧值比较回写
            if (abandoned.contains(instanceId)) {
                writeBackIfChanged(instanceId, prev, info);
            }
        });
        return future;
    }

    /**
     * 与缓存中上一次结果比较，玩家数变化才回写 DB（异步完成路径）。
     * prev 为 null 视为首次写入。
     */
    private void writeBackIfChanged(Long instanceId,
                                    Optional<InstanceDynamicInfo> prev,
                                    Optional<InstanceDynamicInfo> info) {
        if (info.isEmpty() || info.get().playerCount() == null) {
            return;
        }
        Integer pc = info.get().playerCount();
        boolean unchanged = prev != null && prev.isPresent()
                && pc.equals(prev.get().playerCount());
        if (unchanged) {
            return;
        }
        try {
            instanceMapper.updateOnlinePlayers(instanceId, pc);
        } catch (Exception e) {
            log.warn("[InstanceInfo] 异步回写玩家数失败 instanceId={}: {}", instanceId, e.getMessage());
        }
    }

    /**
     * 应用查询结果：playerCount 有值则覆盖 VO 并按变化回写；extras 非空则透传。
     * null 结果（不可知）保持 VO 现值（降级不覆盖）。
     */
    private void apply(InstanceVO vo, InstanceDynamicInfo info) {
        if (info == null) {
            return;
        }
        Integer pc = info.playerCount();
        if (pc != null && !pc.equals(vo.getOnlinePlayers())) {
            vo.setOnlinePlayers(pc);
            try {
                instanceMapper.updateOnlinePlayers(vo.getId(), pc);
            } catch (Exception e) {
                log.warn("[InstanceInfo] 回写玩家数失败 instanceId={}: {}", vo.getId(), e.getMessage());
            }
        }
        // maxPlayerCount 允许为 null（该游戏无法提供），不落库（无对应列），降级也不覆盖
        if (info.maxPlayerCount() != null) {
            vo.setMaxPlayerCount(info.maxPlayerCount());
        }
        if (info.extras() != null && !info.extras().isEmpty()) {
            vo.setInfoExtras(info.extras());
        }
    }

    private boolean isRunning(InstanceVO vo) {
        return vo.getRunStatus() != null
                && vo.getRunStatus() == DeployAdapter.InstanceStatus.RUNNING.getCode();
    }

    private boolean isProviderAbsent(InstanceVO vo) {
        return registry.findBySource(providerSource(vo)).isEmpty();
    }

    private String providerSource(InstanceVO vo) {
        return vo.getGameCode() == null ? "" : vo.getGameCode().toUpperCase();
    }
}

package com.gameplatform.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gameplatform.adapter.DeployAdapter;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.mapper.GameInstanceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 部署任务恢复定时任务
 * 定期检查 run_status=INSTALLING（5，安装中）的实例，如果超过阈值未更新则标记为 ERROR（异常）
 *
 * 场景：
 * - 异步部署线程被 JVM 强制中断（kill -9），run_status 未被正确更新
 * - 部署过程中应用崩溃，run_status 停留在 INSTALLING
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeployRecoveryTask {

    private final GameInstanceMapper instanceMapper;

    /** 部署超时阈值（分钟）：超过此时间未更新的 deploying 实例视为中断 */
    private static final int DEPLOY_TIMEOUT_MINUTES = 2;

    /**
     * 每 60 秒检查一次部署中实例是否超时
     */
    @Scheduled(fixedRate = 60 * 1000)
    public void checkStaleDeployingInstances() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(DEPLOY_TIMEOUT_MINUTES);

        List<GameInstance> staleInstances;
        try {
            staleInstances = instanceMapper.selectList(
                    new LambdaQueryWrapper<GameInstance>()
                            .eq(GameInstance::getRunStatus,
                                    DeployAdapter.InstanceStatus.INSTALLING.getCode())
                            .lt(GameInstance::getUpdateTime, threshold));
        } catch (Exception e) {
            log.error("查询超时部署中实例失败", e);
            return;
        }

        if (staleInstances.isEmpty()) {
            return;
        }

        log.warn("发现 {} 个部署超时（超过 {} 分钟未更新）的实例，将标记为异常",
                staleInstances.size(), DEPLOY_TIMEOUT_MINUTES);

        for (GameInstance instance : staleInstances) {
            try {
                instance.setRunStatus(DeployAdapter.InstanceStatus.ERROR.getCode());
                int rows = instanceMapper.updateById(instance);
                if (rows > 0) {
                    log.warn("实例 {} [{}] 部署超时已标记为异常 (updateTime={})",
                            instance.getId(), instance.getInstanceName(), instance.getUpdateTime());
                } else {
                    log.error("实例 {} [{}] 标记异常失败，updateById 返回 0 行",
                            instance.getId(), instance.getInstanceName());
                }
            } catch (Exception e) {
                log.error("实例 {} [{}] 标记异常时发生异常",
                        instance.getId(), instance.getInstanceName(), e);
            }
        }
    }
}

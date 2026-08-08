package com.gameplatform.listener;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.mapper.GameInstanceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 部署任务恢复监听器
 * 应用启动时，将中断的部署中实例标记为异常
 *
 * 使用 ApplicationRunner 替代 @EventListener(ApplicationReadyEvent.class)，
 * 确保在所有 Bean 初始化完成后执行。
 * 使用 TransactionTemplate 显式控制事务，避免 @Transactional 在事件监听器上不生效的问题。
 */
@Slf4j
@Component
public class DeployRecoveryListener implements ApplicationRunner {

    private final GameInstanceMapper instanceMapper;
    private final TransactionTemplate transactionTemplate;

    public DeployRecoveryListener(GameInstanceMapper instanceMapper,
                                  PlatformTransactionManager transactionManager) {
        this.instanceMapper = instanceMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        log.info("DeployRecoveryListener 启动恢复检查开始执行");

        List<GameInstance> deploying;
        try {
            deploying = instanceMapper.selectList(
                    new LambdaQueryWrapper<GameInstance>()
                            .eq(GameInstance::getRunStatus, 5));
        } catch (Exception e) {
            log.error("DeployRecoveryListener 查询部署中实例失败", e);
            return;
        }

        if (deploying.isEmpty()) {
            log.info("DeployRecoveryListener 未发现部署中被中断的实例（run_status=5），恢复检查完成");
            return;
        }

        log.warn("发现 {} 个部署中被中断的实例（run_status=5），将标记为异常。实例 ID 列表：{}",
                deploying.size(), deploying.stream().map(g -> String.valueOf(g.getId())).toList());
        int successCount = 0;
        int failCount = 0;
        for (GameInstance instance : deploying) {
            try {
                // 使用 TransactionTemplate 显式控制事务，确保更新提交
                Boolean result = transactionTemplate.execute(status -> {
                    instance.setRunStatus(2); // error
                    int rows = instanceMapper.updateById(instance);
                    return rows > 0;
                });
                if (Boolean.TRUE.equals(result)) {
                    successCount++;
                    log.warn("实例 {} [{}] 部署任务因应用重启中断，已标记为异常",
                            instance.getId(), instance.getInstanceName());
                } else {
                    failCount++;
                    log.error("实例 {} [{}] 标记异常失败，updateById 返回 0 行",
                            instance.getId(), instance.getInstanceName());
                }
            } catch (Exception e) {
                failCount++;
                log.error("实例 {} [{}] 标记异常时发生异常",
                        instance.getId(), instance.getInstanceName(), e);
            }
        }
        log.info("DeployRecoveryListener 恢复检查完成：成功 {} 个，失败 {} 个", successCount, failCount);
    }
}

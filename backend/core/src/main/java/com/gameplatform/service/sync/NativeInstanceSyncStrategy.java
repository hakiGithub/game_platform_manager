package com.gameplatform.service.sync;

import com.gameplatform.adapter.DeployAdapter.InstanceStatus;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.util.AesUtil;
import com.gameplatform.util.SshUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native 部署的实例状态同步策略
 * 通过 SSH 执行 pgrep -f "&lt;startCommand 关键部分&gt;" 检测进程
 *
 * <p>关键字解析规则：
 * <ol>
 *   <li>优先取 -game 参数值（如 left4dead2）</li>
 *   <li>无则取最后一个非选项参数（如 server.jar）</li>
 * </ol>
 *
 * <p>状态对账：pgrep exit code 0 → RUNNING；exit code 1 → STOPPED；其他 → 跳过不更新
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Component
public class NativeInstanceSyncStrategy {

    private static final Logger log = LoggerFactory.getLogger(NativeInstanceSyncStrategy.class);
    private static final String LOG_PREFIX = "[InstanceSync]";
    private static final Pattern GAME_PARAM_PATTERN = Pattern.compile("-game\\s+(\\S+)");
    private static final Pattern JAR_PARAM_PATTERN = Pattern.compile("-jar\\s+(\\S+)");

    private final SshUtil sshUtil;
    private final AesUtil aesUtil;
    private final GameInstanceMapper instanceMapper;

    public NativeInstanceSyncStrategy(SshUtil sshUtil, AesUtil aesUtil, GameInstanceMapper instanceMapper) {
        this.sshUtil = sshUtil;
        this.aesUtil = aesUtil;
        this.instanceMapper = instanceMapper;
    }

    /**
     * 同步单台主机上的所有 Native 类实例
     */
    public void syncHost(Host host, List<GameInstance> instances) {
        if (instances == null || instances.isEmpty()) {
            return;
        }
        for (GameInstance instance : instances) {
            try {
                syncInstance(host, instance);
            } catch (Exception e) {
                log.error("{} 实例 #{} ({}) Native 同步异常: {}",
                        LOG_PREFIX, instance.getId(), instance.getInstanceName(), e.getMessage(), e);
            }
        }
    }

    private void syncInstance(Host host, GameInstance instance) {
        String startCommand = instance.getStartCommand();
        if (startCommand == null || startCommand.isBlank()) {
            log.debug("{} 实例 #{}: startCommand 为空，跳过 Native 同步",
                    LOG_PREFIX, instance.getId());
            return;
        }

        String keyword = parseStartCommandKeyword(startCommand);
        if (keyword == null || keyword.isBlank()) {
            log.debug("{} 实例 #{}: 无法从 startCommand 解析关键字，跳过",
                    LOG_PREFIX, instance.getId());
            return;
        }

        InstanceStatus targetStatus = detectProcessStatus(host, keyword);
        if (targetStatus == null) {
            // 检测失败（SSH 异常等），不更新
            return;
        }

        if (shouldUpdate(instance.getRunStatus(), targetStatus)) {
            String remark = targetStatus == InstanceStatus.RUNNING ? null : "进程未运行";
            updateInstanceStatus(instance, targetStatus, remark);
        } else {
            log.debug("{} 实例 #{} ({}): Native 状态未变化 ({}→{})",
                    LOG_PREFIX, instance.getId(), instance.getInstanceName(),
                    instance.getRunStatus(), targetStatus.getCode());
        }
    }

    /**
     * 从 startCommand 解析关键部分用于 pgrep 匹配
     * 优先级：
     *   1. -game 参数值（Source 引擎，如 left4dead2）
     *   2. -jar 参数值（Java 服务器，如 server.jar）
     *   3. 第一个非选项参数（可执行文件名，如 start.sh）
     * 包级可见以便未来扩展测试
     */
    String parseStartCommandKeyword(String startCommand) {
        if (startCommand == null || startCommand.isBlank()) {
            return null;
        }
        // 1. 优先取 -game 参数值
        Matcher gameMatcher = GAME_PARAM_PATTERN.matcher(startCommand);
        if (gameMatcher.find()) {
            return gameMatcher.group(1);
        }
        // 2. 取 -jar 参数值（Java 服务器）
        Matcher jarMatcher = JAR_PARAM_PATTERN.matcher(startCommand);
        if (jarMatcher.find()) {
            String jarPath = jarMatcher.group(1);
            int slashIdx = jarPath.lastIndexOf('/');
            return slashIdx >= 0 ? jarPath.substring(slashIdx + 1) : jarPath;
        }
        // 3. 取第一个非选项参数作为可执行文件名
        String[] parts = startCommand.split("\\s+");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("-")) {
                continue;
            }
            int slashIdx = trimmed.lastIndexOf('/');
            return slashIdx >= 0 ? trimmed.substring(slashIdx + 1) : trimmed;
        }
        return null;
    }

    private InstanceStatus detectProcessStatus(Host host, String keyword) {
        try {
            String command = String.format("pgrep -f \"%s\" 2>/dev/null", escapeKeyword(keyword));
            SshUtil.CommandResult result = executeCommand(host, command, 10000);
            int exitCode = result.getExitCode();
            if (exitCode == 0) {
                return InstanceStatus.RUNNING;
            } else if (exitCode == 1) {
                return InstanceStatus.STOPPED;
            } else {
                log.warn("{} pgrep 返回非预期 exit code={}, 跳过该实例", LOG_PREFIX, exitCode);
                return null;
            }
        } catch (Exception e) {
            log.warn("{} pgrep 执行失败，跳过: {}", LOG_PREFIX, e.getMessage());
            return null;
        }
    }

    private String escapeKeyword(String keyword) {
        // 简单转义双引号，避免命令注入
        return keyword.replace("\"", "\\\"");
    }

    /**
     * 通过 Host 实体执行 SSH 命令
     * 复用 AbstractDeployAdapter 中的模式：先解密 privateKey/password，再调用 SshUtil
     */
    private SshUtil.CommandResult executeCommand(Host host, String command, long timeoutMs) {
        String privateKey = getDecryptedPrivateKey(host);
        String password = getDecryptedPassword(host);
        return sshUtil.executeCommand(
                host.getIpAddress(),
                host.getSshPort(),
                host.getSshUser(),
                privateKey,
                password,
                command,
                timeoutMs
        );
    }

    private String getDecryptedPrivateKey(Host host) {
        if (host.getSshPrivateKey() == null || host.getSshPrivateKey().isEmpty()) {
            return null;
        }
        try {
            return aesUtil.decrypt(host.getSshPrivateKey());
        } catch (Exception e) {
            log.error("解密私钥失败: {}", e.getMessage());
            return null;
        }
    }

    private String getDecryptedPassword(Host host) {
        if (host.getSshPassword() == null || host.getSshPassword().isEmpty()) {
            return null;
        }
        try {
            return aesUtil.decrypt(host.getSshPassword());
        } catch (Exception e) {
            log.error("解密密码失败: {}", e.getMessage());
            return null;
        }
    }

    private boolean shouldUpdate(int currentStatus, InstanceStatus target) {
        return currentStatus != target.getCode();
    }

    private void updateInstanceStatus(GameInstance instance, InstanceStatus target, String remark) {
        int oldStatus = instance.getRunStatus();
        int newStatus = target.getCode();
        instance.setRunStatus(newStatus);
        instance.setRemark(remark);
        instanceMapper.updateById(instance);
        log.info("{} 实例 #{} ({}): Native 状态变更 {}→{} {}",
                LOG_PREFIX, instance.getId(), instance.getInstanceName(),
                oldStatus, newStatus, remark != null ? "(" + remark + ")" : "");
    }
}

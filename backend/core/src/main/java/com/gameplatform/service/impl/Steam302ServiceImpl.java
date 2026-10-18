package com.gameplatform.service.impl;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.config.Steam302Properties;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.plugin.task.TaskService;
import com.gameplatform.plugin.task.TaskSubmitRequest;
import com.gameplatform.service.Steam302Service;
import com.gameplatform.steam302.Steam302HostsSync;
import com.gameplatform.util.SudoAwareSshRunner;
import com.gameplatform.util.SshUtil;
import com.gameplatform.vo.Steam302StatusVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Steam302 主机加速服务实现
 *
 * <p>所有远程操作通过 {@link SudoAwareSshRunner}（root 直跑 / sudo 复用 SSH 密码），
 * docker 命令先直跑、权限被拒再提权（复用安装执行器同款策略，见 ADR-0019）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Steam302ServiceImpl implements Steam302Service {

    private static final long DOCKER_TIMEOUT_MS = 60_000;
    private static final long FAST_TIMEOUT_MS = 15_000;

    /** S302.ini 的 [Setting] 键值行 */
    private static final Pattern INI_LINE = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*)$");

    private static final String PHASE_NOT_INSTALLED = "NOT_INSTALLED";
    private static final String PHASE_STOPPED = "STOPPED";
    private static final String PHASE_RUNNING = "RUNNING";

    private final Steam302Properties props;
    private final SudoAwareSshRunner runner;
    private final Steam302HostsSync hostsSync;
    private final TaskService taskService;
    private final HostMapper hostMapper;

    @Override
    public Steam302StatusVO status(Long hostId) {
        Steam302StatusVO vo = new Steam302StatusVO();

        SshUtil.CommandResult ps = runDocker(hostId, "docker ps -a --filter name=^/"
                + props.getContainerName() + "$ --format '{{.State}}|{{.Status}}|{{.Image}}'", DOCKER_TIMEOUT_MS);
        if (!ps.isSuccess() || ps.getOutput().isBlank()) {
            vo.setPhase(PHASE_NOT_INSTALLED);
            vo.setMessage("未安装");
            return vo;
        }
        String[] parts = ps.getOutput().trim().split("\\|", 3);
        String state = parts[0];
        vo.setContainerStatus(parts.length > 1 ? parts[1] : state);
        vo.setImage(parts.length > 2 ? parts[2] : props.getImage());

        boolean running = "running".equalsIgnoreCase(state);
        vo.setPhase(running ? PHASE_RUNNING : PHASE_STOPPED);
        vo.setMessage(running ? "运行中" : "已停止（" + vo.getContainerStatus() + "）");

        // 容器共享加速开关（随数据卷持久化，未安装时无 flag 文件）；展示当前目标 IP
        boolean shareMode = hostsSync.isContainerShareEnabled(hostId);
        vo.setContainerShare(shareMode);
        vo.setTargetIp(shareMode ? hostMapper.selectById(hostId).getIpAddress() : "127.0.0.1");

        // /etc/hosts 劫持条目数（容器停止时 CLI 已撤条目，为 0 属正常）
        SshUtil.CommandResult hosts = runner.run(hostId, "grep -c '#S302' /etc/hosts || true", FAST_TIMEOUT_MS);
        vo.setHostsEntries(hosts.isSuccess() ? parseIntOr(hosts.getOutput(), 0) : -1);

        // 自愈：容器被 docker 策略自起（如宿主机重启、WSL 重生成 /etc/hosts）后条目丢失，自动补写
        if (running && Integer.valueOf(0).equals(vo.getHostsEntries())) {
            try {
                int synced = reconcileHostsAndStart(hostId, false);
                vo.setHostsEntries(synced >= 0 ? synced : 0);
                log.info("检测到运行中但劫持条目缺失（宿主机重启/容器自起），"
                        + "已停容器写入并重启: hostId={}", hostId);
            } catch (Exception e) {
                log.warn("自动补写 hosts 劫持条目失败: hostId={}, {}", hostId, e.getMessage());
            }
        }
        // 状态一致性：API 声称容器共享模式时必须能从实际 hosts 看到 LAN 标记。
        // 这里不做静默修正，保留真实状态供面板和 E2E 判定；修复动作由安装/启停流程负责。
        if (running && shareMode && vo.getHostsEntries() != null && vo.getHostsEntries() > 0) {
            SshUtil.CommandResult lan = runner.run(hostId, "grep -c '#S302-LAN' /etc/hosts || true", FAST_TIMEOUT_MS);
            int lanEntries = lan.isSuccess() ? parseIntOr(lan.getOutput(), 0) : -1;
            if (lanEntries <= 0) {
                vo.setMessage("运行中（容器共享加速未生效：/etc/hosts 仍为本机模式）");
                log.warn("Steam302 容器共享状态与 hosts 不一致: hostId={}, shareMode=true, targetIp={}, lanEntries={}; "
                                + "remediation: 重新切换容器共享加速或检查工具是否覆盖 hosts",
                        hostId, vo.getTargetIp(), lanEntries);
            }
        }

        // Caddyfile 代理域名数（只统计行首站点地址行，避免把 reverse_proxy 上游计入）
        SshUtil.CommandResult domains = runner.run(hostId,
                "grep -E '^https?://' " + props.getDataDir()
                        + "/steamcommunity_302.caddy.json 2>/dev/null"
                        + " | grep -oE 'https?://[A-Za-z0-9.*_-]+' | sort -u | wc -l", FAST_TIMEOUT_MS);
        vo.setProxiedDomains(domains.isSuccess() ? parseIntOr(domains.getOutput(), 0) : -1);

        // CA 是否已信任
        vo.setCertTrusted(isCertTrusted(hostId));
        return vo;
    }

    @Override
    public String install(Long hostId) {
        if (hostId == null) {
            throw new BusinessException("hostId 不能为空");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("hostId", hostId);

        TaskSubmitRequest submit = TaskSubmitRequest.builder()
                .taskType("STEAM302_INSTALL")
                .source("MAIN")
                .scopeType("HOST")
                .scopeKey(String.valueOf(hostId))
                .scopeName("Steam302 安装: 主机 #" + hostId)
                .payload(payload)
                .build();
        String taskId = taskService.submit(submit);
        log.info("提交 Steam302 安装任务: taskId={}, hostId={}", taskId, hostId);
        return taskId;
    }

    @Override
    public void start(Long hostId) {
        ensurePlatformManagedHosts(hostId);
        // 运行中的 CLI 会在启动/刷新阶段重写 /etc/hosts；必须先停容器，
        // 写完平台条目后再启动，否则 #S302-LAN 会被覆盖回 127.0.0.1。
        reconcileHostsAndStart(hostId, true);
    }

    @Override
    public void stop(Long hostId) {
        SshUtil.CommandResult r = runDocker(hostId, "docker stop " + props.getContainerName(), DOCKER_TIMEOUT_MS);
        if (!r.isSuccess()) {
            throw new BusinessException("停止失败: " + firstLine(r.getError()));
        }
        // 尽力清除劫持条目恢复直连；失败不阻塞停止动作（状态页可见 hosts 条目数）
        try {
            hostsSync.clear(hostId);
        } catch (Exception e) {
            log.warn("清除 hosts 劫持条目失败: hostId={}, {}", hostId, e.getMessage());
        }
    }

    @Override
    public LinkedHashMap<String, String> getConfig(Long hostId) {
        String iniPath = props.getDataDir() + "/S302.ini";
        SshUtil.CommandResult r = runner.run(hostId, "cat " + iniPath, FAST_TIMEOUT_MS);
        if (!r.isSuccess()) {
            throw new BusinessException("读取配置失败（未安装或无权访问）: " + firstLine(r.getError()));
        }
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        boolean inSetting = false;
        for (String line : r.getOutput().split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inSetting = "[Setting]".equalsIgnoreCase(trimmed);
                continue;
            }
            if (!inSetting) {
                continue;
            }
            Matcher m = INI_LINE.matcher(trimmed);
            if (m.matches()) {
                result.put(m.group(1), m.group(2).strip());
            }
        }
        return result;
    }

    @Override
    public void saveConfig(Long hostId, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        LinkedHashMap<String, String> current = getConfig(hostId);
        for (String key : values.keySet()) {
            if (!current.containsKey(key)) {
                throw new BusinessException("未知的配置键: " + key);
            }
        }
        LinkedHashMap<String, String> merged = new LinkedHashMap<>(current);
        merged.putAll(values);

        StringBuilder sb = new StringBuilder("[Setting]\n");
        for (Map.Entry<String, String> e : merged.entrySet()) {
            sb.append(String.format("%-30s= %s%n", e.getKey(), e.getValue()));
        }
        runner.writeFilePrivileged(hostId, props.getDataDir() + "/S302.ini", sb.toString(), DOCKER_TIMEOUT_MS);
        log.info("Steam302 配置已保存: hostId={}, keys={}", hostId, values.keySet());
    }

    @Override
    public void setContainerShare(Long hostId, boolean enabled) {
        Steam302StatusVO current = status(hostId);
        if (PHASE_NOT_INSTALLED.equals(current.getPhase())) {
            throw new BusinessException("Steam302 未安装，请先安装后再配置容器共享加速");
        }
        boolean restarted = ensurePlatformManagedHosts(hostId);
        if (restarted) {
            awaitProxyReady(hostId);
        }
        String flagPath = props.getDataDir() + "/" + Steam302HostsSync.CONTAINER_SHARE_FLAG;
        if (enabled) {
            runner.writeFilePrivileged(hostId, flagPath, "1", DOCKER_TIMEOUT_MS);
        } else {
            SshUtil.CommandResult rm = runner.runPrivileged(hostId, "rm -f " + flagPath, FAST_TIMEOUT_MS);
            if (!rm.isSuccess()) {
                throw new BusinessException("关闭容器共享加速失败: " + firstLine(rm.getError()));
            }
        }
        // 立即按新目标重写 hosts。运行中的 CLI 会重写宿主 hosts，
        // 因此先停容器、写入，再启动，不能因 flag 未变化而跳过同步。
        int synced = reconcileHostsAndStart(hostId, false);
        Steam302StatusVO after = status(hostId);
        if (enabled && after.getTargetIp() != null
                && !"127.0.0.1".equals(after.getTargetIp())
                && after.getMessage() != null
                && after.getMessage().contains("容器共享加速未生效")) {
            throw new BusinessException("容器共享加速已写入 flag，但宿主机 hosts 仍是本机模式；"
                    + "remediation: 检查 Steam302 容器是否在启动时覆盖 /etc/hosts，"
                    + "或改用平台写入后再启动的顺序");
        }
        log.info("容器共享加速已{}: hostId={}, hosts 重写 {}",
                enabled ? "开启" : "关闭", hostId, synced >= 0 ? synced + " 条" : "无变化");
    }

    // ==================== 私有方法 ====================

    /**
     * 平台独占 hosts 管理：老版本安装或手工修改可能仍保留 Auto_Modify_Hosts=1。
     * CLI 运行中会周期性重写 /etc/hosts，必须写回 0 并重启容器使其重新读取配置。
     *
     * @return 是否因配置变更重启了运行中的容器
     */
    private boolean ensurePlatformManagedHosts(Long hostId) {
        String iniPath = props.getDataDir() + "/S302.ini";
        SshUtil.CommandResult current = runner.run(hostId, "cat " + iniPath, DOCKER_TIMEOUT_MS);
        if (!current.isSuccess()) {
            throw new BusinessException("读取 S302.ini 失败，无法关闭自动改写 hosts: "
                    + firstLine(current.getError()));
        }
        if (!current.getOutput().matches("(?s).*Auto_Modify_Hosts\\s*=\\s*0\\s*(?:\\r?\\n|$).*")) {
            String updated = current.getOutput().replaceAll(
                    "(?m)^Auto_Modify_Hosts\\s*=.*$",
                    "Auto_Modify_Hosts            = 0");
            if (updated.equals(current.getOutput())) {
                throw new BusinessException("S302.ini 缺少 Auto_Modify_Hosts 配置项，无法保证平台独占 hosts 管理");
            }
            runner.writeFilePrivileged(hostId, iniPath, updated, DOCKER_TIMEOUT_MS);
            SshUtil.CommandResult state = runDocker(hostId,
                    "docker inspect --format '{{.State.Running}}' " + props.getContainerName(),
                    FAST_TIMEOUT_MS);
            boolean running = state.isSuccess() && "true".equals(state.getOutput().trim());
            if (running) {
                SshUtil.CommandResult restart = runDocker(hostId,
                        "docker restart " + props.getContainerName(), DOCKER_TIMEOUT_MS);
                if (!restart.isSuccess()) {
                    throw new BusinessException("关闭 Auto_Modify_Hosts 后重启 Steam302 失败: "
                            + firstLine(restart.getError()));
                }
                log.info("已将 Auto_Modify_Hosts 关闭并重启 Steam302: hostId={}", hostId);
                return true;
            }
        }
        return false;
    }

    /**
     * 在容器停止态写入 hosts，再恢复容器运行。
     *
     * <p>Steam302 CLI 在运行期间仍可能把宿主 /etc/hosts 恢复为自身模板；
     * 实测只有在停止态写入后再启动，平台生成的 LAN 条目才能稳定保留。</p>
     *
     * @return hostsSync 的写入结果；无变化时为 -1
     */
    private int reconcileHostsAndStart(Long hostId, boolean forceStart) {
        boolean wasRunning = isContainerRunning(hostId);
        if (wasRunning) {
            SshUtil.CommandResult stop = runDocker(hostId,
                    "docker stop " + props.getContainerName(), DOCKER_TIMEOUT_MS);
            if (!stop.isSuccess()) {
                throw new BusinessException("写入 hosts 前停止 Steam302 失败: "
                        + firstLine(stop.getError()));
            }
        }

        RuntimeException syncError = null;
        int synced = -1;
        try {
            synced = hostsSync.syncFromCaddyfile(hostId);
        } catch (RuntimeException e) {
            syncError = e;
        }

        try {
            if (wasRunning || forceStart) {
                SshUtil.CommandResult start = runDocker(hostId,
                        "docker start " + props.getContainerName(), DOCKER_TIMEOUT_MS);
                if (!start.isSuccess()) {
                    throw new BusinessException("hosts 写入后启动 Steam302 失败: "
                            + firstLine(start.getError()));
                }
                awaitProxyReady(hostId);
            }
        } catch (RuntimeException startError) {
            if (syncError != null) {
                startError.addSuppressed(syncError);
            }
            throw startError;
        }

        if (syncError != null) {
            throw syncError;
        }
        return synced;
    }

    private boolean isContainerRunning(Long hostId) {
        SshUtil.CommandResult state = runDocker(hostId,
                "docker inspect --format '{{.State.Running}}' " + props.getContainerName(),
                FAST_TIMEOUT_MS);
        return state.isSuccess() && "true".equals(state.getOutput().trim());
    }

    /** 等待工具的 80/443 端口转发就绪（host 网络，容器启动后约数秒） */
    private void awaitProxyReady(Long hostId) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            SshUtil.CommandResult r = runner.run(hostId,
                    "(ss -tln 2>/dev/null || netstat -tln 2>/dev/null) | grep -q ':443 '", 10_000);
            if (r.isSuccess() && r.getExitCode() == 0) {
                return;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("等待 443 监听超时（30s），仍尝试 hosts 同步: hostId={}", hostId);
    }

    private boolean isCertTrusted(Long hostId) {
        SshUtil.CommandResult check = runner.runPrivileged(hostId,
                "test -f /usr/local/share/ca-certificates/steam302-steamcommunityCA.crt"
                        + " -o -f /etc/pki/ca-trust/source/anchors/steam302-steamcommunityCA.pem && echo YES", FAST_TIMEOUT_MS);
        return check.isSuccess() && check.getOutput().contains("YES");
    }

    private SshUtil.CommandResult runDocker(Long hostId, String command, long timeoutMs) {
        SshUtil.CommandResult direct = runner.run(hostId, command, timeoutMs);
        if (direct.isSuccess()) {
            return direct;
        }
        String err = direct.getError() == null ? "" : direct.getError();
        if (err.contains("permission denied") || err.contains("Permission denied")) {
            return runner.runPrivileged(hostId, command, timeoutMs);
        }
        return direct;
    }

    private int parseIntOr(String s, int def) {
        try {
            return Integer.parseInt(s.strip());
        } catch (Exception e) {
            return def;
        }
    }

    private String firstLine(String s) {
        if (s == null || s.isBlank()) {
            return "未知错误";
        }
        return s.strip().split("\n")[0];
    }
}

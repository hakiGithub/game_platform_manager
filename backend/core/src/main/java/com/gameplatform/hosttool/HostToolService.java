package com.gameplatform.hosttool;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.patch.HostCapabilityProber;
import com.gameplatform.plugin.patch.HostCapabilities;
import com.gameplatform.util.SudoAwareSshRunner;
import com.gameplatform.util.SshUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 主机环境工具服务（ADR-0021）
 *
 * <p>白名单工具的状态查询与同步安装。状态不落库：每次现探测（command -v 便宜），
 * 安装成功后失效该主机的探测缓存让面板立即刷新。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HostToolService {

    /** 安装命令执行超时（小包安装通常数秒~数十秒，预留网络慢的情形） */
    private static final long INSTALL_TIMEOUT_MS = 120_000L;

    private final HostMapper hostMapper;
    private final HostCapabilityProber prober;
    private final HostToolCatalog catalog;
    private final SudoAwareSshRunner sshRunner;

    /** 工具状态项 */
    public record ToolStatus(String tool, String displayName, boolean installed, boolean installable) {
    }

    /** 面板数据：工具清单 + 环境能力（包管理器/提权/Docker） */
    public record HostToolsVO(List<ToolStatus> tools, String packageManager,
                              boolean sudoNopasswd, boolean docker) {
    }

    /**
     * 查询主机环境工具面板数据（复用能力探测与 60s 缓存）。
     */
    public HostToolsVO getTools(Long hostId) {
        requireHost(hostId);
        HostCapabilities caps = prober.probe(hostId);
        List<ToolStatus> tools = new ArrayList<>();
        for (String tool : HostToolCatalog.WHITELIST) {
            boolean installable = catalog.isWhitelisted(tool)
                    && caps.getPackageManager() != null
                    && !caps.getPackageManager().isBlank();
            tools.add(new ToolStatus(tool, catalog.displayName(tool), caps.hasTool(tool), installable));
        }
        return new HostToolsVO(tools, caps.getPackageManager(),
                Boolean.TRUE.equals(caps.getSudoNopasswd()), caps.hasDocker());
    }

    /**
     * 同步安装白名单工具（ADR-0021 决策 3：同步执行、发行版自适应、提权复用 SudoAwareSshRunner）。
     *
     * @return 安装命令的输出（成功时 stdout，失败抛业务异常含 stderr）
     */
    public String install(Long hostId, String tool) {
        requireHost(hostId);
        if (!catalog.isWhitelisted(tool)) {
            throw new BusinessException("不支持的工具: " + tool + "（仅允许白名单: " + HostToolCatalog.WHITELIST + "）");
        }
        HostCapabilities caps = prober.probe(hostId);
        if (caps.hasTool(tool)) {
            return "工具 " + tool + " 已安装，无需重复安装";
        }
        String command = catalog.buildInstallCommand(tool, caps.getPackageManager());
        SshUtil.CommandResult result = sshRunner.runPrivileged(hostId, command, INSTALL_TIMEOUT_MS);
        if (result == null || !result.isSuccess()) {
            String error = result != null ? result.getError() : "无响应";
            throw new BusinessException("安装 " + catalog.displayName(tool) + " 失败（可在主机详情用 Web 终端手动安装）: "
                    + firstLine(error));
        }
        prober.invalidate(hostId);
        log.info("主机环境工具安装成功: hostId={}, tool={}, command={}", hostId, tool, command);
        return result.getOutput();
    }

    private void requireHost(Long hostId) {
        Host host = hostMapper.selectById(hostId);
        if (host == null) {
            throw new BusinessException("主机不存在: " + hostId);
        }
    }

    private String firstLine(String s) {
        if (s == null || s.isBlank()) {
            return "未知错误";
        }
        return s.strip().split("\n")[0];
    }
}

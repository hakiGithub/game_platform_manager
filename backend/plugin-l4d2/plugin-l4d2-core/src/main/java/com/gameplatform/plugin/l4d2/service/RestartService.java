package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.dto.RestartConfigUpdateDTO;
import com.gameplatform.plugin.l4d2.enums.RestartMode;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.vo.RestartConfigVO;
import com.gameplatform.plugin.service.FileAccessService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * L4D2 服务器重启服务。
 *
 * <p>对齐源项目 {@code controller/restart.go}：
 * <ul>
 *   <li>RCON 模式：通过 RCON 协议发送 {@code _restart} 命令，要求服务器在线</li>
 *   <li>命令模式：通过 {@link FileAccessService} 在实例所属主机上执行 shell 命令
 *       （默认 {@code docker restart <containerName>}，可用 customCmd 覆盖）</li>
 *   <li>优先级：AUTO 模式按 {@code config.restart.byRcon} 决定；RCON/COMMAND 强制走对应模式</li>
 * </ul>
 *
 * <p>命令注入防护：使用单引号对自定义命令进行 shell 转义。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestartService {

    private static final String RCON_RESTART_COMMAND = "_restart";
    private static final String DEFAULT_RCON_HOST = "127.0.0.1";

    private final InstanceQueryService instanceQueryService;
    private final FileAccessService fileAccessService;
    private final RconService rconService;
    private final L4D2Config config;

    /**
     * 重启 L4D2 服务器。
     *
     * @param instanceId 实例 ID
     * @param mode       重启模式；AUTO 时按配置决定
     */
    public void restart(Long instanceId, RestartMode mode) {
        if (!isEnabled()) {
            throw new IllegalStateException("重启功能已禁用");
        }
        if (instanceId == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "实例 ID 不能为空");
        }
        RestartMode effective = mode == null ? RestartMode.AUTO : mode;
        boolean useRcon;
        switch (effective) {
            case RCON -> useRcon = true;
            case COMMAND -> useRcon = false;
            default -> useRcon = config.getRestart().isByRcon();
        }
        log.info("重启 L4D2 实例 {}, mode={}, effective={}", instanceId, effective, useRcon ? "RCON" : "COMMAND");
        if (useRcon) {
            restartByRcon(instanceId);
        } else {
            restartByCommand(instanceId);
        }
    }

    /**
     * 强制通过 RCON 协议重启。
     */
    public void restartByRcon(Long instanceId) {
        if (!isEnabled()) {
            throw new IllegalStateException("重启功能已禁用");
        }
        loadInstance(instanceId);
        log.info("RCON 重启实例 {}: cmd={}", instanceId, RCON_RESTART_COMMAND);
        try {
            rconService.executeCommand(instanceId, RCON_RESTART_COMMAND);
        } catch (Exception e) {
            throw new L4D2PluginException(L4D2PluginException.RCON,
                    "RCON 重启失败：" + e.getMessage(), e);
        }
    }

    /**
     * 强制通过 shell 命令重启（在实例所属主机上通过 SSH 执行）。
     */
    public void restartByCommand(Long instanceId) {
        if (!isEnabled()) {
            throw new IllegalStateException("重启功能已禁用");
        }
        InstanceVO instance = loadInstance(instanceId);
        String command = buildRestartCommand(instanceId);
        long timeoutMs = config.getRestart().getCommandTimeoutMs();
        log.info("命令重启实例 {}: hostId={}, cmd={}", instanceId, instance.getHostId(), command);

        FileAccessService.CommandResult result = fileAccessService.executeCommand(
                instance.getHostId(), command, timeoutMs);
        if (!result.isSuccess()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "重启命令执行失败，exitCode=" + result.getExitCode()
                            + "，output=" + result.getOutput()
                            + "，error=" + result.getError()
                            + "，cmd=" + command);
        }
    }

    /**
     * 获取当前重启配置。
     */
    public RestartConfigVO getConfig() {
        L4D2Config.Restart restart = config.getRestart();
        RestartConfigVO vo = new RestartConfigVO();
        vo.setByRcon(restart.isByRcon());
        vo.setContainerName(restart.getContainerName());
        vo.setCustomCmd(restart.getCustomCmd());
        vo.setEnabled(restart.isEnabled());
        vo.setAvailableModes(List.of(RestartMode.AUTO.name(), RestartMode.RCON.name(), RestartMode.COMMAND.name()));
        return vo;
    }

    /**
     * 更新重启配置（null 字段保持原值）。
     */
    public void setConfig(RestartConfigUpdateDTO dto) {
        L4D2Config.Restart restart = config.getRestart();
        if (dto.getByRcon() != null) {
            restart.setByRcon(dto.getByRcon());
        }
        if (dto.getContainerName() != null && !dto.getContainerName().isBlank()) {
            restart.setContainerName(dto.getContainerName());
        }
        if (dto.getCustomCmd() != null) {
            restart.setCustomCmd(dto.getCustomCmd());
        }
        log.info("重启配置已更新: byRcon={}, containerName={}, customCmd={}",
                restart.isByRcon(), restart.getContainerName(), restart.getCustomCmd());
    }

    /**
     * 当前是否启用重启功能。
     */
    public boolean isEnabled() {
        return config.getRestart().isEnabled();
    }

    /**
     * 运行时启用/禁用重启功能。
     */
    public void setEnabled(boolean enabled) {
        config.getRestart().setEnabled(enabled);
        log.info("重启功能已{}", enabled ? "启用" : "禁用");
    }

    // ===== 私有方法 =====

    /**
     * 构建重启命令。
     * <p>优先使用 customCmd；为空时回退到 {@code docker restart <containerName>}。
     * 命令将在实例所属远程主机上通过 SSH 执行，统一使用 {@code sh -c} 包装以支持 shell 特性。
     */
    private String buildRestartCommand(Long instanceId) {
        String cmd = config.getRestart().getCustomCmd();
        if (cmd == null || cmd.isBlank()) {
            String container = resolveContainerIdentifier(instanceId);
            cmd = "docker restart " + container;
        }
        return "sh -c " + shellQuote(cmd);
    }

    /**
     * 解析容器标识：优先使用 runtime_metadata.containerId（最可靠，不受容器名变化影响），
     * 其次从实例配置读取容器名（兼容 containerName / CONTAINER_NAME / container_name），
     * 最后回退到 config.restart.containerName。
     */
    private String resolveContainerIdentifier(Long instanceId) {
        try {
            InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
            if (instance != null) {
                String containerId = extractRuntimeContainerId(instance.getRuntimeMetadata());
                if (containerId != null && !containerId.isBlank()) {
                    log.info("实例 {} 使用 runtime_metadata.containerId 重启: {}", instanceId, containerId);
                    return containerId;
                }
                String containerName = extractContainerName(instance.getConfigInfo());
                if (containerName != null && !containerName.isBlank()) {
                    return containerName;
                }
            }
        } catch (Exception e) {
            log.warn("解析实例 {} 容器标识失败，使用默认值: {}", instanceId, e.getMessage());
        }
        String fallback = config.getRestart().getContainerName();
        return (fallback == null || fallback.isBlank()) ? "l4d2" : fallback;
    }

    /**
     * 从运行时元数据中提取容器 ID。
     */
    private String extractRuntimeContainerId(Map<String, Object> runtimeMetadata) {
        if (runtimeMetadata == null) {
            return null;
        }
        Object value = runtimeMetadata.get("containerId");
        if (value != null && !value.toString().isBlank()) {
            return value.toString();
        }
        return null;
    }

    /**
     * 从实例配置信息中提取容器名，兼容多种命名风格。
     */
    private String extractContainerName(Map<String, Object> configInfo) {
        if (configInfo == null) {
            return null;
        }
        for (String key : List.of("containerName", "CONTAINER_NAME", "container_name")) {
            Object value = configInfo.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return null;
    }

    /**
     * 使用单引号对命令字符串进行 shell 转义。
     */
    private String shellQuote(String cmd) {
        return "'" + cmd.replace("'", "'\"'\"'") + "'";
    }

    /**
     * 加载实例；不存在则抛业务异常。
     */
    private InstanceVO loadInstance(Long instanceId) {
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "实例不存在: " + instanceId);
        }
        return instance;
    }
}

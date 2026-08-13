package com.gameplatform.adapter;

import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.deploy.HostCredentials;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.util.SshUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 部署适配器抽象基类
 * 提供通用的部署适配器功能实现
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
public abstract class AbstractDeployAdapter implements DeployAdapter {

    @Autowired
    protected SshUtil sshUtil;

    @Autowired
    protected HostMapper hostMapper;

    @Autowired
    protected GameInstanceMapper instanceMapper;

    @Autowired
    protected DeploymentAccess deployAccess;

    /**
     * 获取主机信息
     *
     * @param hostId 主机ID
     * @return 主机信息
     */
    protected Host getHost(Long hostId) {
        return hostMapper.selectById(hostId);
    }

    /**
     * 获取实例信息
     *
     * @param instanceId 实例ID
     * @return 实例信息
     */
    protected GameInstance getInstance(Long instanceId) {
        return instanceMapper.selectById(instanceId);
    }

    /**
     * 获取实例和主机信息
     *
     * @param instanceId 实例ID
     * @return 实例和主机信息
     */
    protected InstanceHostInfo getInstanceHostInfo(Long instanceId) {
        GameInstance instance = getInstance(instanceId);
        if (instance == null) {
            return null;
        }
        Host host = getHost(instance.getHostId());
        if (host == null) {
            return null;
        }
        return new InstanceHostInfo(instance, host);
    }

    /**
     * 解析主机 SSH 凭据（统一走 DeploymentAccess，替代各处内联解密副本）
     */
    protected HostCredentials credentials(Host host) {
        return deployAccess.credentials(host);
    }

    /**
     * 执行远程命令
     *
     * @param host    主机信息
     * @param command 命令
     * @return 命令执行结果
     */
    protected SshUtil.CommandResult executeCommand(Host host, String command) {
        HostCredentials conn = credentials(host);
        return sshUtil.executeCommand(
                conn.host(), conn.port(), conn.username(), conn.privateKey(), conn.password(),
                command
        );
    }

    /**
     * 执行远程命令（带超时）
     *
     * @param host      主机信息
     * @param command   命令
     * @param timeoutMs 超时时间（毫秒）
     * @return 命令执行结果
     */
    protected SshUtil.CommandResult executeCommand(Host host, String command, long timeoutMs) {
        HostCredentials conn = credentials(host);
        return sshUtil.executeCommand(
                conn.host(), conn.port(), conn.username(), conn.privateKey(), conn.password(),
                command,
                timeoutMs
        );
    }

    /**
     * 上传文件到远程主机
     *
     * @param host       主机信息
     * @param localPath  本地路径
     * @param remotePath 远程路径
     * @return 是否成功
     */
    protected boolean uploadFile(Host host, String localPath, String remotePath) {
        HostCredentials conn = credentials(host);
        return sshUtil.uploadFile(
                conn.host(), conn.port(), conn.username(), conn.privateKey(), conn.password(),
                localPath,
                remotePath
        );
    }

    /**
     * 从远程主机下载文件
     *
     * @param host       主机信息
     * @param remotePath 远程路径
     * @param localPath  本地路径
     * @return 是否成功
     */
    protected boolean downloadFile(Host host, String remotePath, String localPath) {
        HostCredentials conn = credentials(host);
        return sshUtil.downloadFile(
                conn.host(), conn.port(), conn.username(), conn.privateKey(), conn.password(),
                remotePath,
                localPath
        );
    }

    /**
     * 检查端口是否被占用
     *
     * @param host 主机信息
     * @param port 端口号
     * @return 是否被占用
     */
    protected boolean isPortInUse(Host host, int port) {
        SshUtil.CommandResult result = executeCommand(host,
                String.format("netstat -tuln | grep ':%d ' || ss -tuln | grep ':%d '", port, port));
        return result.isSuccess() && !result.getOutput().trim().isEmpty();
    }

    /**
     * 检查Docker是否已安装
     *
     * @param host 主机信息
     * @return 是否已安装
     */
    protected boolean isDockerInstalled(Host host) {
        SshUtil.CommandResult result = executeCommand(host, "docker --version");
        return result.isSuccess() && result.getOutput().contains("Docker version");
    }

    /**
     * 检查Docker Compose是否已安装
     *
     * @param host 主机信息
     * @return 是否已安装
     */
    protected boolean isDockerComposeInstalled(Host host) {
        SshUtil.CommandResult result = executeCommand(host, "docker compose version || docker-compose --version");
        return result.isSuccess() &&
                (result.getOutput().contains("Docker Compose") || result.getOutput().contains("docker-compose"));
    }

    /**
     * 获取磁盘空间信息（GB）
     *
     * @param host 主机信息
     * @param path 路径
     * @return 可用空间（GB），-1表示获取失败
     */
    protected double getAvailableDiskSpace(Host host, String path) {
        SshUtil.CommandResult result = executeCommand(host,
                String.format("df -BG %s | tail -1 | awk '{print $4}' | sed 's/G//'", path));
        if (result.isSuccess()) {
            try {
                return Double.parseDouble(result.getOutput().trim());
            } catch (NumberFormatException e) {
                log.warn("解析磁盘空间失败: {}", result.getOutput());
            }
        }
        return -1;
    }

    /**
     * 获取内存信息（MB）
     *
     * @param host 主机信息
     * @return 可用内存（MB），-1表示获取失败
     */
    protected long getAvailableMemory(Host host) {
        SshUtil.CommandResult result = executeCommand(host,
                "free -m | grep Mem | awk '{print $7}'");
        if (result.isSuccess()) {
            try {
                return Long.parseLong(result.getOutput().trim());
            } catch (NumberFormatException e) {
                log.warn("解析内存信息失败: {}", result.getOutput());
            }
        }
        return -1;
    }

    /**
     * 发送进度回调
     *
     * @param callback 回调接口
     * @param percent  进度百分比
     * @param stage    阶段
     * @param message  消息
     */
    protected void notifyProgress(DeployProgressCallback callback, int percent, String stage, String message) {
        if (callback != null) {
            callback.onProgress(percent, stage, message);
        }
    }

    /**
     * 发送阶段开始回调
     *
     * @param callback    回调接口
     * @param stage       阶段
     * @param description 描述
     */
    protected void notifyStageStart(DeployProgressCallback callback, String stage, String description) {
        if (callback != null) {
            callback.onStageStart(stage, description);
        }
        log.info("[{}] {}", stage, description);
    }

    /**
     * 发送阶段完成回调
     *
     * @param callback 回调接口
     * @param stage    阶段
     * @param success  是否成功
     * @param message  消息
     */
    protected void notifyStageComplete(DeployProgressCallback callback, String stage, boolean success, String message) {
        if (callback != null) {
            callback.onStageComplete(stage, success, message);
        }
        if (success) {
            log.info("[{}] 完成: {}", stage, message);
        } else {
            log.error("[{}] 失败: {}", stage, message);
        }
    }

    /**
     * 发送错误回调
     *
     * @param callback    回调接口
     * @param error       错误信息
     * @param stage       阶段
     * @param recoverable 是否可恢复
     */
    protected void notifyError(DeployProgressCallback callback, String error, String stage, boolean recoverable) {
        if (callback != null) {
            callback.onError(error, stage, recoverable);
        }
        log.error("[{}] 错误: {} (可恢复: {})", stage, error, recoverable);
    }

    /**
     * 发送日志回调
     *
     * @param callback 回调接口
     * @param level    日志级别
     * @param message  消息
     */
    protected void notifyLog(DeployProgressCallback callback, String level, String message) {
        if (callback != null) {
            callback.onLog(level, message);
        }
        switch (level.toUpperCase()) {
            case "ERROR" -> log.error(message);
            case "WARN" -> log.warn(message);
            default -> log.info(message);
        }
    }

    /**
     * 发送完成回调
     *
     * @param callback 回调接口
     * @param success  是否成功
     * @param message  消息
     */
    protected void notifyComplete(DeployProgressCallback callback, boolean success, String message) {
        if (callback != null) {
            callback.onComplete(success, message);
        }
        if (success) {
            log.info("部署完成: {}", message);
        } else {
            log.error("部署失败: {}", message);
        }
    }

    /**
     * 从配置中获取字符串值
     *
     * @param config       配置
     * @param key          键
     * @param defaultValue 默认值
     * @return 值
     */
    protected String getConfigString(Map<String, Object> config, String key, String defaultValue) {
        if (config == null || !config.containsKey(key)) {
            return defaultValue;
        }
        Object value = config.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * 从配置中获取整数值
     *
     * @param config       配置
     * @param key          键
     * @param defaultValue 默认值
     * @return 值
     */
    protected int getConfigInt(Map<String, Object> config, String key, int defaultValue) {
        if (config == null || !config.containsKey(key)) {
            return defaultValue;
        }
        Object value = config.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 检查 compose 文件引用的所有镜像是否都已存在于本地 Docker。
     * <p>
     * 通过 {@code docker-compose config --images} 列出 compose 文件中引用的所有镜像
     * （变量已被替换为实际值），然后对每个镜像用 {@code docker images -q <image>} 检查本地是否存在。
     * <p>
     * 如果所有镜像都已存在本地，返回 true；任一镜像不存在或检查失败时返回 false。
     * 检查失败（命令异常）视为未命中，让调用方回退到 pull 流程。
     *
     * @param host       目标主机
     * @param workDir    compose 文件所在目录
     * @param composeCmd compose 命令（docker compose 或 docker-compose）
     * @param projectName compose 项目名
     * @return true 表示所有镜像都已存在本地，可跳过 pull；false 表示需要执行 pull
     */
    protected boolean isAllImagesAvailableLocally(Host host, String workDir, String composeCmd, String projectName) {
        try {
            // 列出 compose 文件引用的所有镜像（变量替换后的实际镜像名）
            // 注意：`config --images` 仅 docker compose V2 支持，V1（docker-compose 1.x）不支持
            // 为兼容 V1/V2，统一使用 `config | grep image:` 方式提取
            String listCmd = String.format(
                    "cd %s && %s -p %s config 2>/dev/null | grep -E '^[[:space:]]*image:[[:space:]]'",
                    workDir, composeCmd, projectName);
            SshUtil.CommandResult imagesResult = executeCommand(host, listCmd, 30000);
            log.info("[ImageCheck] 执行命令: {}", listCmd);
            log.info("[ImageCheck] 命令成功: {}, 输出: [{}], 错误: [{}]",
                    imagesResult.isSuccess(), imagesResult.getOutput(), imagesResult.getError());
            // grep 无匹配时返回 exit code 1，视为成功但输出为空
            String output = imagesResult.getOutput();
            if (output == null || output.trim().isEmpty()) {
                log.warn("[ImageCheck] compose 镜像列表为空，将执行 pull");
                return false;
            }
            // 解析每行 "    image: xxx" 提取镜像名
            String[] lines = output.trim().split("\\s*\n\\s*");
            List<String> images = new ArrayList<>();
            for (String line : lines) {
                if (line.isEmpty()) continue;
                // 匹配 "image: xxx" 或 "image:xxx"
                String image = line.replaceFirst("^[[:space:]]*image:[[:space:]]*", "").trim();
                // 去除可能的引号
                image = image.replaceAll("^[\"']|[\"']$", "").trim();
                if (!image.isEmpty()) {
                    images.add(image);
                }
            }
            log.info("[ImageCheck] 解析到镜像列表: {}", images);
            if (images.isEmpty()) {
                log.warn("[ImageCheck] 未能从 compose 配置解析出镜像，将执行 pull");
                return false;
            }
            for (String image : images) {
                // docker images -q 在镜像存在时输出镜像 ID（非空），不存在时输出空
                String checkCmd = String.format("docker images -q %s", image);
                SshUtil.CommandResult checkResult = executeCommand(host, checkCmd, 10000);
                log.info("[ImageCheck] 检查镜像 {}: 命令={}, 成功={}, 输出=[{}]",
                        image, checkCmd, checkResult.isSuccess(), checkResult.getOutput());
                if (!checkResult.isSuccess() || checkResult.getOutput() == null
                        || checkResult.getOutput().trim().isEmpty()) {
                    log.info("[ImageCheck] 本地未找到镜像 {}，需要拉取", image);
                    return false;
                }
            }
            log.info("[ImageCheck] 所有 compose 镜像都已存在本地，跳过 pull");
            return true;
        } catch (Exception e) {
            log.warn("[ImageCheck] 检查本地镜像存在性失败，将执行 pull: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 从配置中获取布尔值
     *
     * @param config       配置
     * @param key          键
     * @param defaultValue 默认值
     * @return 值
     */
    protected boolean getConfigBoolean(Map<String, Object> config, String key, boolean defaultValue) {
        if (config == null || !config.containsKey(key)) {
            return defaultValue;
        }
        Object value = config.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    /**
     * 实例和主机信息包装类
     */
    protected record InstanceHostInfo(GameInstance instance, Host host) {
    }

    // ==================== Docker 容器资源占用查询 ====================

    /**
     * 查询指定 Docker 容器的资源占用信息（CPU/内存/运行时长）。
     * 使用 `docker stats --no-stream` 获取 CPU/内存，使用 `docker inspect` 获取启动时间计算运行时长。
     * 适用于所有 Docker 类部署方式（docker / docker-compose / linuxgsm-docker）。
     *
     * @param host          远程主机
     * @param containerName 容器名称或容器ID
     * @return 包含 cpuUsage/memoryUsage/memoryUsageText/uptime 的 Map；查询失败时对应字段为 null
     */
    protected Map<String, Object> queryDockerContainerStats(Host host, String containerName) {
        Map<String, Object> stats = new HashMap<>();
        if (host == null || containerName == null || containerName.isEmpty()) {
            return stats;
        }

        // 使用自定义格式（不带 table 前缀），便于按行解析
        // 输出形如：container_id 1.23% 100MiB / 2GiB 5.00% 1.2GB / 2.3GB 10MB / 5MB container_name
        // 字段顺序：Container, CPUPerc, MemUsage, MemPerc, NetIO, BlockIO, Name（Pids 可选）
        String format = "{{.Container}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.Name}}";
        SshUtil.CommandResult statsResult = executeCommand(host,
                String.format("docker stats --no-stream --format \"%s\" %s 2>/dev/null",
                        format, containerName), 15000);

        if (statsResult.isSuccess()) {
            String output = statsResult.getOutput().trim();
            if (!output.isEmpty()) {
                parseDockerStatsLine(output, stats);
            }
        } else {
            log.warn("查询容器资源占用失败: host={}, container={}, error={}",
                    host.getIpAddress(), containerName, statsResult.getError());
        }

        // 查询容器启动时间，计算运行时长（秒）
        SshUtil.CommandResult startResult = executeCommand(host,
                String.format("docker inspect -f '{{.State.StartedAt}}' %s 2>/dev/null", containerName), 10000);
        if (startResult.isSuccess()) {
            String startedAt = startResult.getOutput().trim();
            Long uptime = parseUptimeFromIso(startedAt);
            if (uptime != null) {
                stats.put("uptime", uptime);
            }
        }

        return stats;
    }

    /**
     * 解析单行 docker stats 输出。
     * 期望格式（tab 分隔）：containerId\tCPU%\tMemUsage\tMemPerc%\tName
     */
    private void parseDockerStatsLine(String line, Map<String, Object> stats) {
        // 兼容多行情况：取最后一行（第一行可能是表头，但我们没用 table 格式，所以这里取第一个非空行）
        String[] lines = line.split("\n");
        String target = null;
        for (String l : lines) {
            if (l != null && !l.trim().isEmpty()) {
                target = l.trim();
                break;
            }
        }
        if (target == null) return;

        String[] parts = target.split("\t");
        if (parts.length < 4) {
            // 格式不符，放弃解析
            return;
        }

        // parts[1] = "1.23%"
        Double cpu = parsePercentage(parts[1]);
        if (cpu != null) stats.put("cpuUsage", cpu);

        // parts[2] = "100MiB / 2GiB"
        stats.put("memoryUsageText", parts[2].trim());

        // parts[3] = "5.00%"
        Double mem = parsePercentage(parts[3]);
        if (mem != null) stats.put("memoryUsage", mem);
    }

    /**
     * 解析百分比字符串，如 "1.23%" -> 1.23
     */
    private Double parsePercentage(String s) {
        if (s == null) return null;
        String trimmed = s.trim().replace("%", "").replace(" ", "");
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("--")) return null;
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 从 ISO 8601 时间字符串（如 2026-07-18T08:30:00.123456789Z 或带时区）计算到现在的运行时长（秒）。
     */
    private Long parseUptimeFromIso(String isoTime) {
        if (isoTime == null || isoTime.trim().isEmpty() || isoTime.startsWith("0001")) return null;
        try {
            // 兼容带纳秒和Z后缀的格式：截取到毫秒
            String normalized = isoTime.trim();
            // 将纳秒精度截断到毫秒（3位）
            Pattern p = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})\\.?(\\d{1,9})?(Z|[+-]\\d{2}:?\\d{2})?");
            Matcher m = p.matcher(normalized);
            if (m.matches()) {
                String base = m.group(1);
                String frac = m.group(2);
                String tz = m.group(3);
                if (frac != null && frac.length() > 3) {
                    frac = frac.substring(0, 3);
                } else if (frac != null) {
                    frac = String.format("%-3s", frac).replace(" ", "0");
                }
                String formatted = base + (frac != null ? "." + frac : "");
                // 默认按 UTC 处理（docker inspect 返回的时间通常是 UTC，以 Z 结尾）
                java.time.Instant instant;
                if (tz == null || tz.equals("Z")) {
                    instant = java.time.LocalDateTime.parse(formatted)
                            .atZone(java.time.ZoneOffset.UTC).toInstant();
                } else {
                    // 带时区偏移
                    String offset = tz.length() == 5 ? tz.substring(0, 3) + ":" + tz.substring(3) : tz;
                    instant = java.time.LocalDateTime.parse(formatted)
                            .atZone(java.time.ZoneId.ofOffset("", java.time.ZoneOffset.of(offset))).toInstant();
                }
                long uptimeSec = (System.currentTimeMillis() - instant.toEpochMilli()) / 1000;
                return uptimeSec > 0 ? uptimeSec : 0;
            }
        } catch (Exception e) {
            log.warn("解析容器启动时间失败: {}", isoTime, e);
        }
        return null;
    }
}

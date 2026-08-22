package com.gameplatform.adapter;

import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.util.SshUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Docker部署适配器
 * 使用docker-java SDK封装Docker操作
 * 支持镜像拉取、容器创建、端口映射、卷挂载等功能
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
public class DockerAdapter extends AbstractDeployAdapter {

    // 默认超时时间
    private static final int DEFAULT_TIMEOUT = 300;

    @Override
    public DeployType getDeployType() {
        return DeployType.DOCKER;
    }

    @Override
    public boolean validateEnvironment(Long hostId, Map<String, Object> config) {
        Host host = getHost(hostId);
        if (host == null) {
            log.error("主机不存在: {}", hostId);
            return false;
        }

        // 检查Docker是否已安装
        if (!isDockerInstalled(host)) {
            log.error("主机 {} 未安装Docker", hostId);
            return false;
        }

        // 检查Docker服务是否运行
        SshUtil.CommandResult result = executeCommand(host, "docker info");
        if (!result.isSuccess()) {
            log.error("主机 {} Docker服务未运行", hostId);
            return false;
        }

        // 检查磁盘空间（至少需要2GB）
        double availableSpace = getAvailableDiskSpace(host, "/var/lib/docker");
        if (availableSpace < 2 && availableSpace != -1) {
            log.error("主机 {} Docker磁盘空间不足: {}GB < 2GB", hostId, availableSpace);
            return false;
        }

        // 检查端口是否被占用
        List<Map<String, Object>> portMappings = getPortMappings(config);
        for (Map<String, Object> mapping : portMappings) {
            Integer hostPort = (Integer) mapping.get("hostPort");
            if (hostPort != null && hostPort > 0 && isPortInUse(host, hostPort)) {
                log.error("主机 {} 端口 {} 已被占用", hostId, hostPort);
                return false;
            }
        }

        return true;
    }

    @Override
    public boolean preDeploy(Long instanceId, Map<String, Object> config, DeployProgressCallback callback) {
        notifyStageStart(callback, "PRE_DEPLOY", "开始Docker预部署准备");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyError(callback, "实例或主机不存在", "PRE_DEPLOY", false);
            return false;
        }

        Host host = info.host();
        String image = getConfigString(config, "image", "");

        if (image.isEmpty()) {
            notifyError(callback, "未指定Docker镜像", "PRE_DEPLOY", false);
            return false;
        }

        try {
            notifyProgress(callback, 30, "PRE_DEPLOY", "检查Docker镜像");
            // 检查镜像是否已存在
            SshUtil.CommandResult checkResult = executeCommand(host,
                    String.format("docker images -q %s", image));

            if (!checkResult.isSuccess()) {
                notifyError(callback, "检查Docker镜像失败: " + checkResult.getError(), "PRE_DEPLOY", false);
                return false;
            }

            if (checkResult.getOutput().trim().isEmpty()) {
                notifyProgress(callback, 60, "PRE_DEPLOY", "拉取Docker镜像: " + image);
                // 拉取镜像
                SshUtil.CommandResult pullResult = executeCommand(host,
                        String.format("docker pull %s", image), 600000);

                if (!pullResult.isSuccess()) {
                    notifyError(callback, "拉取镜像失败: " + pullResult.getError(), "PRE_DEPLOY", false);
                    return false;
                }
            }

            notifyProgress(callback, 100, "PRE_DEPLOY", "预部署完成");
            notifyStageComplete(callback, "PRE_DEPLOY", true, "Docker预部署准备完成");
            return true;

        } catch (Exception e) {
            log.error("Docker预部署失败", e);
            notifyError(callback, "预部署异常: " + e.getMessage(), "PRE_DEPLOY", false);
            return false;
        }
    }

    @Override
    public boolean deploy(Long instanceId, Map<String, Object> config, DeployProgressCallback callback) {
        notifyStageStart(callback, "DEPLOY", "开始Docker部署");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyError(callback, "实例或主机不存在", "DEPLOY", false);
            return false;
        }

        Host host = info.host();
        String containerName = getContainerName(instanceId, config);

        try {
            // 检查是否已存在同名容器
            notifyProgress(callback, 10, "DEPLOY", "检查现有容器");
            String existingContainerId = getContainerId(host, containerName);
            if (existingContainerId != null) {
                notifyProgress(callback, 20, "DEPLOY", "移除现有容器");
                executeCommand(host, String.format("docker rm -f %s", containerName), 30000);
            }

            notifyProgress(callback, 30, "DEPLOY", "构建容器启动参数");
            // 构建docker run命令
            String dockerRunCmd = buildDockerRunCommand(containerName, config);

            notifyProgress(callback, 60, "DEPLOY", "创建并启动容器");
            // 创建并启动容器
            SshUtil.CommandResult result = executeCommand(host, dockerRunCmd, 120000);

            if (!result.isSuccess()) {
                notifyError(callback, "创建容器失败: " + result.getError(), "DEPLOY", false);
                return false;
            }

            // 获取容器ID
            String containerId = getContainerId(host, containerName);
            if (containerId == null) {
                notifyError(callback, "无法获取容器ID", "DEPLOY", false);
                return false;
            }

            notifyProgress(callback, 80, "DEPLOY", "验证容器状态");
            // 等待容器启动
            Thread.sleep(3000);

            // 验证容器状态
            if (!isContainerRunning(host, containerName)) {
                // 获取容器日志
                SshUtil.CommandResult logResult = executeCommand(host,
                        String.format("docker logs %s 2>&1 | tail -20", containerName), 10000);
                notifyError(callback, "容器启动失败，日志: " + logResult.getOutput(), "DEPLOY", false);
                return false;
            }

            // 更新实例信息
            GameInstance instance = info.instance();
            instance.setInstallPath(containerId); // 使用容器ID作为安装路径
            instance.setStartCommand(String.format("docker start %s", containerName));
            instance.setStopCommand(String.format("docker stop %s", containerName));

            // 写入 configInfo 供 InstanceFileService 解析容器内文件路径
            // containerWorkDir 来自部署配置 workingDir，默认 /home/steam
            Map<String, Object> configInfo = instance.getConfigInfo() != null
                    ? new HashMap<>(instance.getConfigInfo())
                    : new HashMap<>();
            configInfo.put("containerWorkDir", getConfigString(config, "workingDir", "/home/steam"));
            instance.setConfigInfo(configInfo);

            instanceMapper.updateById(instance);

            notifyProgress(callback, 100, "DEPLOY", "部署完成");
            notifyStageComplete(callback, "DEPLOY", true, "Docker容器部署成功");
            return true;

        } catch (Exception e) {
            log.error("Docker部署失败", e);
            notifyError(callback, "部署异常: " + e.getMessage(), "DEPLOY", false);
            return false;
        }
    }

    @Override
    public boolean start(Long instanceId, Map<String, Object> config) {
        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            return false;
        }

        Host host = info.host();
        String containerName = getContainerName(instanceId, config);

        SshUtil.CommandResult result = executeCommand(host,
                String.format("docker start %s", containerName), 30000);

        return result.isSuccess();
    }

    @Override
    public boolean stop(Long instanceId, Map<String, Object> config) {
        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            return false;
        }

        Host host = info.host();
        String containerName = getContainerName(instanceId, config);

        SshUtil.CommandResult result = executeCommand(host,
                String.format("docker stop %s", containerName), 60000);

        return result.isSuccess();
    }

    @Override
    public boolean restart(Long instanceId, Map<String, Object> config) {
        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            return false;
        }

        Host host = info.host();
        String containerName = getContainerName(instanceId, config);

        SshUtil.CommandResult result = executeCommand(host,
                String.format("docker restart %s", containerName), 60000);

        return result.isSuccess();
    }

    @Override
    public boolean healthCheck(Long instanceId, Map<String, Object> config) {
        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            return false;
        }

        Host host = info.host();
        String containerName = getContainerName(instanceId, config);

        // 检查容器运行状态
        if (!isContainerRunning(host, containerName)) {
            return false;
        }

        // 检查健康检查配置
        String healthCmd = getConfigString(config, "healthCheck.command", "");
        if (!healthCmd.isEmpty()) {
            SshUtil.CommandResult result = executeCommand(host,
                    String.format("docker exec %s %s", containerName, healthCmd), 10000);
            return result.isSuccess();
        }

        return true;
    }

    @Override
    public boolean update(Long instanceId, Map<String, Object> config, DeployProgressCallback callback) {
        notifyStageStart(callback, "UPDATE", "开始更新Docker容器");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyError(callback, "实例或主机不存在", "UPDATE", false);
            return false;
        }

        Host host = info.host();
        String containerName = getContainerName(instanceId, config);
        String image = getConfigString(config, "image", "");

        try {
            notifyProgress(callback, 20, "UPDATE", "停止并删除旧容器");
            // 停止并删除旧容器
            executeCommand(host, String.format("docker stop %s", containerName), 60000);
            executeCommand(host, String.format("docker rm %s", containerName), 30000);

            notifyProgress(callback, 40, "UPDATE", "拉取最新镜像");
            // 拉取最新镜像
            SshUtil.CommandResult pullResult = executeCommand(host,
                    String.format("docker pull %s", image), 600000);

            if (!pullResult.isSuccess()) {
                notifyError(callback, "拉取镜像失败: " + pullResult.getError(), "UPDATE", false);
                return false;
            }

            notifyProgress(callback, 70, "UPDATE", "创建新容器");
            // 重新部署
            if (!deploy(instanceId, config, DeployProgressCallback.NO_OP)) {
                notifyError(callback, "重新部署失败", "UPDATE", false);
                return false;
            }

            notifyProgress(callback, 100, "UPDATE", "更新完成");
            notifyStageComplete(callback, "UPDATE", true, "Docker容器更新成功");
            return true;

        } catch (Exception e) {
            log.error("Docker更新失败", e);
            notifyError(callback, "更新异常: " + e.getMessage(), "UPDATE", false);
            return false;
        }
    }

    @Override
    public boolean uninstall(Long instanceId, Map<String, Object> config, DeployProgressCallback callback) {
        notifyStageStart(callback, "UNINSTALL", "开始卸载Docker容器");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyError(callback, "实例或主机不存在", "UNINSTALL", false);
            return false;
        }

        Host host = info.host();
        String containerName = getContainerName(instanceId, config);

        try {
            notifyProgress(callback, 30, "UNINSTALL", "停止容器");
            // 停止容器
            executeCommand(host, String.format("docker stop %s", containerName), 60000);

            notifyProgress(callback, 60, "UNINSTALL", "删除容器");
            // 删除容器
            SshUtil.CommandResult result = executeCommand(host,
                    String.format("docker rm %s", containerName), 30000);

            if (!result.isSuccess()) {
                notifyError(callback, "删除容器失败: " + result.getError(), "UNINSTALL", true);
            }

            // 是否删除镜像
            boolean removeImage = getConfigBoolean(config, "removeImage", false);
            if (removeImage) {
                notifyProgress(callback, 80, "UNINSTALL", "删除镜像");
                String image = getConfigString(config, "image", "");
                if (!image.isEmpty()) {
                    executeCommand(host, String.format("docker rmi %s", image), 60000);
                }
            }

            notifyProgress(callback, 100, "UNINSTALL", "卸载完成");
            notifyStageComplete(callback, "UNINSTALL", true, "Docker容器卸载成功");
            return true;

        } catch (Exception e) {
            log.error("Docker卸载失败", e);
            notifyError(callback, "卸载异常: " + e.getMessage(), "UNINSTALL", false);
            return false;
        }
    }

    @Override
    public String getLogs(Long instanceId, Map<String, Object> config, int lines) {
        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            return "";
        }

        Host host = info.host();
        String containerName = getContainerName(instanceId, config);

        SshUtil.CommandResult result = executeCommand(host,
                String.format("docker logs --tail %d %s 2>&1", lines, containerName), 10000);

        return result.getOutput();
    }

    @Override
    public InstanceStatus getStatus(Long instanceId, Map<String, Object> config) {
        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            return InstanceStatus.ERROR;
        }

        Host host = info.host();
        String containerName = getContainerName(instanceId, config);

        // 获取容器状态
        SshUtil.CommandResult result = executeCommand(host,
                String.format("docker inspect -f '{{.State.Status}}' %s 2>/dev/null", containerName));

        String status = result.getOutput().trim();

        return switch (status) {
            case "running" -> InstanceStatus.RUNNING;
            case "exited" -> InstanceStatus.STOPPED;
            case "restarting" -> InstanceStatus.STARTING;
            case "dead" -> InstanceStatus.ERROR;
            default -> InstanceStatus.NOT_INSTALLED;
        };
    }

    @Override
    public Map<String, Object> getDetails(Long instanceId, Map<String, Object> config) {
        Map<String, Object> details = new HashMap<>();

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            return details;
        }

        Host host = info.host();
        String containerName = getContainerName(instanceId, config);

        // 获取容器详细信息
        SshUtil.CommandResult result = executeCommand(host,
                String.format("docker inspect %s 2>/dev/null", containerName));

        if (result.isSuccess()) {
            String output = result.getOutput();
            details.put("rawOutput", output);

            // 解析关键信息
            parseContainerDetails(output, details);
        }

        // 获取容器资源占用信息（CPU/内存/运行时长）- 通过公共方法解析为结构化字段
        Map<String, Object> stats = queryDockerContainerStats(host, containerName);
        if (!stats.isEmpty()) {
            details.putAll(stats);
            // 同时保留原始 stats 字符串，便于兼容旧代码
            details.put("stats", stats);
        }

        details.put("instanceId", instanceId);
        details.put("containerName", containerName);

        return details;
    }

    @Override
    public String executeCommand(Long instanceId, Map<String, Object> config, String command) {
        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            return "实例或主机不存在";
        }

        Host host = info.host();
        String containerName = getContainerName(instanceId, config);

        SshUtil.CommandResult result = executeCommand(host,
                String.format("docker exec %s %s", containerName, command), 60000);

        return result.getOutput() + (result.getError().isEmpty() ? "" : "\n错误: " + result.getError());
    }

    @Override
    public String backup(Long instanceId, Map<String, Object> config, DeployProgressCallback callback) {
        notifyStageStart(callback, "BACKUP", "开始备份Docker容器数据");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyError(callback, "实例或主机不存在", "BACKUP", false);
            return null;
        }

        Host host = info.host();
        String containerName = getContainerName(instanceId, config);
        String backupPath = String.format("/tmp/backup_%s_%d.tar.gz",
                containerName, System.currentTimeMillis());

        try {
            notifyProgress(callback, 40, "BACKUP", "导出容器数据");
            // 获取容器卷挂载点
            List<String> volumes = getContainerVolumes(host, containerName);

            if (volumes.isEmpty()) {
                // 如果没有卷，导出整个容器
                SshUtil.CommandResult result = executeCommand(host,
                        String.format("docker export %s | gzip > %s", containerName, backupPath), 300000);

                if (!result.isSuccess()) {
                    notifyError(callback, "导出容器失败: " + result.getError(), "BACKUP", false);
                    return null;
                }
            } else {
                // 备份卷数据
                String volumePaths = volumes.stream()
                        .map(v -> String.format("-v %s:%s", v, v))
                        .collect(Collectors.joining(" "));

                SshUtil.CommandResult result = executeCommand(host,
                        String.format("docker run --rm %s -v /backup:/backup alpine tar czf /backup/backup.tar.gz %s",
                                volumePaths, String.join(" ", volumes)), 300000);

                if (!result.isSuccess()) {
                    notifyError(callback, "备份卷数据失败: " + result.getError(), "BACKUP", false);
                    return null;
                }

                backupPath = "/backup/backup.tar.gz";
            }

            notifyProgress(callback, 100, "BACKUP", "备份完成");
            notifyStageComplete(callback, "BACKUP", true, "备份成功: " + backupPath);

            return backupPath;

        } catch (Exception e) {
            log.error("Docker备份失败", e);
            notifyError(callback, "备份异常: " + e.getMessage(), "BACKUP", false);
            return null;
        }
    }

    @Override
    public boolean restore(Long instanceId, Map<String, Object> config, String backupPath, DeployProgressCallback callback) {
        notifyStageStart(callback, "RESTORE", "开始恢复Docker容器数据");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyError(callback, "实例或主机不存在", "RESTORE", false);
            return false;
        }

        Host host = info.host();
        String containerName = getContainerName(instanceId, config);

        try {
            notifyProgress(callback, 30, "RESTORE", "停止容器");
            // 停止容器
            stop(instanceId, config);

            notifyProgress(callback, 50, "RESTORE", "恢复数据");
            // 获取容器卷挂载点
            List<String> volumes = getContainerVolumes(host, containerName);

            if (!volumes.isEmpty()) {
                // 恢复卷数据
                String volumePaths = volumes.stream()
                        .map(v -> String.format("-v %s:%s", v, v))
                        .collect(Collectors.joining(" "));

                SshUtil.CommandResult result = executeCommand(host,
                        String.format("docker run --rm %s -v %s:/backup alpine sh -c 'cd / && tar xzf /backup'",
                                volumePaths, backupPath), 300000);

                if (!result.isSuccess()) {
                    notifyError(callback, "恢复数据失败: " + result.getError(), "RESTORE", false);
                    return false;
                }
            }

            notifyProgress(callback, 80, "RESTORE", "启动容器");
            // 启动容器
            start(instanceId, config);

            notifyProgress(callback, 100, "RESTORE", "恢复完成");
            notifyStageComplete(callback, "RESTORE", true, "恢复成功");
            return true;

        } catch (Exception e) {
            log.error("Docker恢复失败", e);
            notifyError(callback, "恢复异常: " + e.getMessage(), "RESTORE", false);
            return false;
        }
    }

    @Override
    public boolean cleanup(Long instanceId, Map<String, Object> config, DeployProgressCallback callback) {
        notifyStageStart(callback, "CLEANUP", "开始清理Docker残留资源");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyStageComplete(callback, "CLEANUP", true, "实例不存在，无需清理");
            return true;
        }

        Host host = info.host();
        String containerName = getContainerName(instanceId, config);

        try {
            // 停止并删除容器
            executeCommand(host, String.format("docker stop %s 2>/dev/null || true", containerName), 30000);
            executeCommand(host, String.format("docker rm %s 2>/dev/null || true", containerName), 30000);

            // 清理未使用的卷
            executeCommand(host, "docker volume prune -f 2>/dev/null || true", 30000);

            notifyStageComplete(callback, "CLEANUP", true, "清理完成");
            return true;

        } catch (Exception e) {
            log.error("Docker清理失败", e);
            notifyError(callback, "清理异常: " + e.getMessage(), "CLEANUP", true);
            return false;
        }
    }

    // ========== 私有方法 ==========

    /**
     * 获取容器名称
     */
    private String getContainerName(Long instanceId, Map<String, Object> config) {
        String customName = getConfigString(config, "containerName", "");
        if (!customName.isEmpty()) {
            return customName;
        }
        return String.format("game-instance-%d", instanceId);
    }

    /**
     * 获取容器ID
     */
    private String getContainerId(Host host, String containerName) {
        SshUtil.CommandResult result = executeCommand(host,
                String.format("docker ps -aq -f name=%s", containerName));
        String id = result.getOutput().trim();
        return id.isEmpty() ? null : id;
    }

    /**
     * 检查容器是否运行
     */
    private boolean isContainerRunning(Host host, String containerName) {
        SshUtil.CommandResult result = executeCommand(host,
                String.format("docker ps -q -f name=%s -f status=running", containerName));
        return !result.getOutput().trim().isEmpty();
    }

    /**
     * 获取端口映射列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getPortMappings(Map<String, Object> config) {
        Object ports = config.get("ports");
        if (ports instanceof List) {
            return (List<Map<String, Object>>) ports;
        }
        return new ArrayList<>();
    }

    /**
     * 获取卷挂载列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getVolumeMounts(Map<String, Object> config) {
        Object volumes = config.get("volumes");
        if (volumes instanceof List) {
            return (List<Map<String, Object>>) volumes;
        }
        return new ArrayList<>();
    }

    /**
     * 获取环境变量列表
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> getEnvironmentVars(Map<String, Object> config) {
        Object env = config.get("environment");
        if (env instanceof Map) {
            Map<String, Object> envMap = (Map<String, Object>) env;
            Map<String, String> result = new HashMap<>();
            envMap.forEach((k, v) -> result.put(k, v != null ? v.toString() : ""));
            return result;
        }
        return new HashMap<>();
    }

    /**
     * 构建docker run命令
     */
    private String buildDockerRunCommand(String containerName, Map<String, Object> config) {
        StringBuilder cmd = new StringBuilder("docker run -d");

        // 容器名称
        cmd.append(" --name ").append(containerName);

        // 重启策略
        String restartPolicy = getConfigString(config, "restartPolicy", "unless-stopped");
        cmd.append(" --restart ").append(restartPolicy);

        // 端口映射
        List<Map<String, Object>> ports = getPortMappings(config);
        for (Map<String, Object> port : ports) {
            Integer hostPort = (Integer) port.get("hostPort");
            Integer containerPort = (Integer) port.get("containerPort");
            String protocol = (String) port.getOrDefault("protocol", "tcp");

            if (hostPort != null && containerPort != null) {
                cmd.append(String.format(" -p %d:%d/%s", hostPort, containerPort, protocol));
            }
        }

        // 卷挂载
        List<Map<String, Object>> volumes = getVolumeMounts(config);
        for (Map<String, Object> volume : volumes) {
            String hostPath = (String) volume.get("hostPath");
            String containerPath = (String) volume.get("containerPath");
            String mode = (String) volume.getOrDefault("mode", "rw");

            if (hostPath != null && containerPath != null) {
                cmd.append(String.format(" -v %s:%s:%s", hostPath, containerPath, mode));
            }
        }

        // 按需挂载宿主机 SSL 证书 + /etc/hosts（用于反向代理场景）
        // - 证书：使容器信任宿主机的 CA 证书，避免 SSL 校验失败
        // - hosts：让容器直接读宿主机的 hosts，避免容器内 DNS 解析到 127.0.0.1
        //   （宿主机 hosts 刷新后把反向代理域名指向 LAN IP，容器共享此解析结果）
        Object mountFlag = config.get("mountHostCerts");
        boolean mountHostCerts = Boolean.TRUE.equals(mountFlag)
                || (mountFlag instanceof String && "true".equalsIgnoreCase((String) mountFlag));
        if (mountHostCerts) {
            String hostCertPath = getConfigString(config, "hostCertPath", "/etc/ssl/certs/ca-certificates.crt");
            String containerCertPath = "/etc/ssl/certs/ca-certificates.crt";
            cmd.append(String.format(" -v %s:%s:ro", hostCertPath, containerCertPath));
            cmd.append(String.format(" -v %s:%s:ro", "/etc/hosts", "/etc/hosts"));
            log.info("Docker run 命令注入宿主机证书 + hosts 挂载: {}:{}, /etc/hosts",
                    hostCertPath, containerCertPath);
        }

        // 环境变量
        Map<String, String> envVars = getEnvironmentVars(config);
        for (Map.Entry<String, String> entry : envVars.entrySet()) {
            cmd.append(String.format(" -e %s=%s", entry.getKey(), entry.getValue()));
        }

        // 网络模式
        String networkMode = getConfigString(config, "networkMode", "");
        if (!networkMode.isEmpty()) {
            cmd.append(" --network ").append(networkMode);
        }

        // 资源限制（ADR-0010）：前端提交 configInfo.resources.{memoryLimit(GB),cpuLimit(核)}
        Double memoryLimit = getResourceLimit(config, "memoryLimit");
        if (memoryLimit != null) {
            cmd.append(" --memory ").append(formatMemoryG(memoryLimit));
        }

        Double cpuLimit = getResourceLimit(config, "cpuLimit");
        if (cpuLimit != null) {
            cmd.append(" --cpus ").append(formatCpu(cpuLimit));
        }

        // 工作目录
        String workingDir = getConfigString(config, "workingDir", "");
        if (!workingDir.isEmpty()) {
            cmd.append(" -w ").append(workingDir);
        }

        // 用户
        String user = getConfigString(config, "user", "");
        if (!user.isEmpty()) {
            cmd.append(" --user ").append(user);
        }

        // 主机名
        String hostname = getConfigString(config, "hostname", "");
        if (!hostname.isEmpty()) {
            cmd.append(" --hostname ").append(hostname);
        }

        // 镜像
        String image = getConfigString(config, "image", "");
        cmd.append(" ").append(image);

        // 启动命令（可选）
        String command = getConfigString(config, "command", "");
        if (!command.isEmpty()) {
            cmd.append(" ").append(command);
        }

        return cmd.toString();
    }

    /**
     * 获取容器卷列表
     */
    private List<String> getContainerVolumes(Host host, String containerName) {
        List<String> volumes = new ArrayList<>();
        SshUtil.CommandResult result = executeCommand(host,
                String.format("docker inspect -f '{{range .Mounts}}{{.Source}} {{end}}' %s 2>/dev/null", containerName));

        String output = result.getOutput().trim();
        if (!output.isEmpty()) {
            volumes.addAll(Arrays.asList(output.split("\\s+")));
        }

        return volumes;
    }

    /**
     * 解析容器详情
     */
    private void parseContainerDetails(String output, Map<String, Object> details) {
        // 简化解析，提取关键信息
        if (output.contains("\"Id\"")) {
            details.put("hasDetails", true);
        }
        // 实际应用中可以使用JSON解析器
    }
}

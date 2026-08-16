package com.gameplatform.adapter;

import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.util.SshUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * LinuxGSM Docker 部署适配器
 * 基于 gameservermanagers/gameserver 镜像，将 LinuxGSM 封装在 Docker 容器中运行
 * <p>
 * 参考文档：https://github.com/GameServerManagers/docker-gameserver
 * <p>
 * 工作原理：
 * 1. 容器启动后镜像内置的 entrypoint 会以 linuxgsm 用户身份自动执行 auto-install（首次启动）
 * 2. 所有 LinuxGSM 命令通过 `docker exec --user linuxgsm <container> ./<shortname> <command>` 调用
 * 3. 数据持久化在 /data 目录（linuxgsm 用户家目录）
 * 4. 推荐 network_mode: host，避免端口映射遗漏
 * <p>
 * 与 DockerComposeAdapter 的差异：
 * - 容器启动后通过 LinuxGSM 命令管理游戏进程（start/stop/restart/status）
 * - 不直接操作容器，而是通过 LinuxGSM 脚本
 * - 支持 LinuxGSM 特有命令：details、monitor、update、backup、send、console
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
public class LinuxGsmDockerAdapter extends AbstractDeployAdapter {

    private static final String COMPOSE_FILE = "docker-compose.yml";
    private static final String PROJECT_PREFIX = "lgsm";

    /**
     * 主机级 Compose 命令缓存：hostId → 实际命令（"docker compose" 或 "docker-compose"）
     */
    private final ConcurrentHashMap<Long, String> composeCommandCache = new ConcurrentHashMap<>();

    @Override
    public DeployType getDeployType() {
        return DeployType.LINUX_GSM_DOCKER;
    }

    /**
     * 检测远程主机支持的 Compose 命令
     * 优先级：docker compose（CLI 插件）> docker-compose（独立二进制）> docker compose（兜底）
     */
    private String getComposeCommand(Host host) {
        return composeCommandCache.computeIfAbsent(host.getId(), hostId -> {
            SshUtil.CommandResult pluginResult = executeCommand(host, "docker compose version 2>/dev/null", 10000);
            if (pluginResult.isSuccess() && pluginResult.getOutput().toLowerCase().contains("compose")) {
                log.info("主机 {} 使用 docker compose（CLI 插件）", hostId);
                return "docker compose";
            }
            SshUtil.CommandResult binaryResult = executeCommand(host, "docker-compose version 2>/dev/null", 10000);
            if (binaryResult.isSuccess() && binaryResult.getOutput().toLowerCase().contains("compose")) {
                log.info("主机 {} 使用 docker-compose（独立二进制）", hostId);
                return "docker-compose";
            }
            log.warn("主机 {} 未检测到 Compose 命令，默认使用 docker compose", hostId);
            return "docker compose";
        });
    }

    @Override
    public boolean validateEnvironment(Long hostId, Map<String, Object> config) {
        Host host = getHost(hostId);
        if (host == null) {
            log.error("主机不存在: {}", hostId);
            return false;
        }

        if (!isDockerInstalled(host)) {
            log.error("主机 {} 未安装Docker", hostId);
            return false;
        }

        if (!isDockerComposeInstalled(host)) {
            log.error("主机 {} 未安装Docker Compose", hostId);
            return false;
        }

        // 检查磁盘空间（LinuxGSM 镜像 + 游戏文件，至少需要 5GB）
        double availableSpace = getAvailableDiskSpace(host, "/var/lib/docker");
        if (availableSpace < 5 && availableSpace != -1) {
            log.error("主机 {} Docker磁盘空间不足: {}GB < 5GB", hostId, availableSpace);
            return false;
        }

        // 校验必要配置
        String shortname = getConfigString(config, "shortname", "");
        if (shortname.isEmpty()) {
            log.error("缺少必要配置: shortname（LinuxGSM 脚本名）");
            return false;
        }

        return true;
    }

    @Override
    public boolean preDeploy(Long instanceId, Map<String, Object> config, DeployProgressCallback callback) {
        notifyStageStart(callback, "PRE_DEPLOY", "开始 LinuxGSM Docker 预部署准备");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyError(callback, "实例或主机不存在", "PRE_DEPLOY", false);
            return false;
        }

        Host host = info.host();
        String projectName = getProjectName(instanceId, config);
        String workDir = getWorkDir(instanceId, config);

        try {
            notifyProgress(callback, 20, "PRE_DEPLOY", "创建工作目录");
            // 展开家目录简写 ~（SFTP 上传需要绝对路径）
            workDir = resolveWorkDir(host, workDir);
            config.put("workDir", workDir);
            // 创建工作目录
            SshUtil.CommandResult mkdirResult = executeCommand(host, "mkdir -p " + workDir);
            if (!mkdirResult.isSuccess()) {
                notifyError(callback, "创建工作目录失败: " + mkdirResult.getError(), "PRE_DEPLOY", false);
                return false;
            }

            notifyProgress(callback, 35, "PRE_DEPLOY", "生成 docker-compose.yml");
            // 使用 yml 配置中的 composeTemplate
            String composeTemplate = getConfigString(config, "composeTemplate", "");
            if (composeTemplate.isEmpty()) {
                notifyError(callback, "缺少 composeTemplate 配置", "PRE_DEPLOY", false);
                return false;
            }

            if (!uploadComposeFile(host, workDir, composeTemplate, config)) {
                notifyError(callback, "上传 docker-compose.yml 失败", "PRE_DEPLOY", false);
                return false;
            }

            notifyProgress(callback, 50, "PRE_DEPLOY", "生成 .env 文件");
            try {
                String envContent = generateEnvFileContent(config);
                if (!envContent.isEmpty()) {
                    if (!uploadEnvFile(host, workDir, envContent)) {
                        notifyError(callback, "上传 .env 文件失败", "PRE_DEPLOY", false);
                        return false;
                    }
                }
            } catch (RuntimeException e) {
                notifyError(callback, "生成 .env 文件失败: " + e.getMessage(), "PRE_DEPLOY", false);
                return false;
            }

            notifyProgress(callback, 70, "PRE_DEPLOY", "验证 Compose 配置");
            String composeCmd = getComposeCommand(host);
            SshUtil.CommandResult validateResult = executeCommand(host,
                    String.format("cd %s && %s -p %s config", workDir, composeCmd, projectName), 30000);
            if (!validateResult.isSuccess()) {
                notifyError(callback, "Compose 配置验证失败: " + validateResult.getError(), "PRE_DEPLOY", false);
                return false;
            }

            notifyProgress(callback, 85, "PRE_DEPLOY", "拉取镜像");
            // 检查本地是否已存在 compose 引用的所有镜像
            // 若全部已存在则跳过 pull，避免去 Docker Hub 拉取时遇到认证/速率限制问题
            if (isAllImagesAvailableLocally(host, workDir, composeCmd, projectName)) {
                notifyProgress(callback, 92, "PRE_DEPLOY", "本地镜像已存在，跳过拉取");
            } else {
                // LinuxGSM 镜像较大（约 1-2GB），超时设为 20 分钟
                SshUtil.CommandResult pullResult = executeCommand(host,
                        String.format("cd %s && %s -p %s pull", workDir, composeCmd, projectName), 1200000);
                if (!pullResult.isSuccess()) {
                    notifyError(callback, "拉取镜像失败: " + pullResult.getError(), "PRE_DEPLOY", true);
                    // 拉取失败不阻止部署，up -d 会再次尝试拉取
                }
            }

            notifyProgress(callback, 100, "PRE_DEPLOY", "预部署完成");
            notifyStageComplete(callback, "PRE_DEPLOY", true, "LinuxGSM Docker 预部署准备完成");
            return true;

        } catch (Exception e) {
            log.error("LinuxGSM Docker 预部署失败", e);
            notifyError(callback, "预部署异常: " + e.getMessage(), "PRE_DEPLOY", false);
            return false;
        }
    }

    @Override
    public boolean deploy(Long instanceId, Map<String, Object> config, DeployProgressCallback callback) {
        notifyStageStart(callback, "DEPLOY", "开始 LinuxGSM Docker 部署");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyError(callback, "实例或主机不存在", "DEPLOY", false);
            return false;
        }

        Host host = info.host();
        String projectName = getProjectName(instanceId, config);
        String workDir = getWorkDir(instanceId, config);
        String shortname = getConfigString(config, "shortname", "");
        String serviceName = getConfigString(config, "serviceName", shortname);

        try {
            String composeCmd = getComposeCommand(host);

            notifyProgress(callback, 30, "DEPLOY", "启动容器");
            // timeout 1200 兜底：compose up -d 可能长时间拉取镜像，SshUtil 的
            // timeoutMs 仅作用于建连，命令执行无超时会无限阻塞部署线程
            SshUtil.CommandResult upResult = executeCommand(host,
                    String.format("cd %s && timeout 1200 %s -p %s up -d", workDir, composeCmd, projectName), 1200000);
            if (!upResult.isSuccess()) {
                notifyError(callback, "启动容器失败: " + upResult.getError(), "DEPLOY", false);
                return false;
            }

            notifyProgress(callback, 50, "DEPLOY", "等待容器就绪");
            // 等待 entrypoint 启动 linuxgsm 用户进程
            Thread.sleep(8000);

            // 验证容器运行状态
            SshUtil.CommandResult psResult = executeCommand(host,
                    String.format("cd %s && %s -p %s ps", workDir, composeCmd, projectName), 30000);
            String psOutput = psResult.getOutput();
            boolean isRunning = psResult.isSuccess()
                    && (psOutput.contains("running") || psOutput.contains("Up"));
            if (!isRunning) {
                SshUtil.CommandResult logResult = executeCommand(host,
                        String.format("cd %s && %s -p %s logs --no-color --tail 50", workDir, composeCmd, projectName), 30000);
                notifyError(callback, "容器启动异常，日志: " + stripAnsiCodes(logResult.getOutput()), "DEPLOY", false);
                return false;
            }

            notifyProgress(callback, 70, "DEPLOY", "验证 LinuxGSM 安装");
            // 通过 docker exec 验证 LinuxGSM 脚本是否已就绪
            // 首次启动时镜像 entrypoint 会自动 auto-install，这里仅做就绪检测
            String containerName = getContainerName(host, config, projectName, serviceName);
            SshUtil.CommandResult detailsResult = executeLinuxGsmCommand(host, containerName, shortname,
                    "details", 60000);
            if (!detailsResult.isSuccess()) {
                log.warn("LinuxGSM details 命令执行失败（可能仍在安装中）: {}", detailsResult.getError());
                // 不阻止部署，首次安装可能耗时较长
            }

            // 更新实例信息
            GameInstance instance = info.instance();
            instance.setInstallPath(workDir);
            instance.setStartCommand(String.format("cd %s && %s -p %s start", workDir, composeCmd, projectName));
            instance.setStopCommand(String.format("cd %s && %s -p %s stop", workDir, composeCmd, projectName));

            // 组装运行时元数据
            try {
                notifyProgress(callback, 85, "DEPLOY", "获取运行时元数据");
                Map<String, Object> runtimeMetadata = new LinkedHashMap<>();
                runtimeMetadata.put("containerName", containerName);
                runtimeMetadata.put("shortname", shortname);
                runtimeMetadata.put("workDir", workDir);
                runtimeMetadata.put("projectName", projectName);
                runtimeMetadata.put("generatedAt", java.time.LocalDateTime.now().toString());
                instance.setRuntimeMetadata(runtimeMetadata);
                log.info("实例 {} 运行时元数据: containerName={}, shortname={}",
                        instance.getId(), containerName, shortname);
            } catch (Exception e) {
                log.warn("组装运行时元数据失败（不影响部署结果）: {}", e.getMessage());
            }

            // 写入 configInfo 供 InstanceFileService 解析容器内文件路径
            // LinuxGSM 镜像 WORKDIR 固定为 /app
            Map<String, Object> configInfo = instance.getConfigInfo() != null
                    ? new HashMap<>(instance.getConfigInfo())
                    : new HashMap<>();
            configInfo.put("containerWorkDir", "/app");
            instance.setConfigInfo(configInfo);

            instanceMapper.updateById(instance);

            notifyProgress(callback, 100, "DEPLOY", "部署完成");
            notifyStageComplete(callback, "DEPLOY", true, "LinuxGSM Docker 部署成功");
            return true;

        } catch (Exception e) {
            log.error("LinuxGSM Docker 部署失败", e);
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
        String shortname = getConfigString(config, "shortname", "");
        String serviceName = getConfigString(config, "serviceName", shortname);
        String projectName = getProjectName(instanceId, config);
        String containerName = getContainerName(host, config, projectName, serviceName);

        // 先确保容器已启动
        ensureContainerRunning(host, instanceId, config);

        // 前置诊断：检查 LinuxGSM 游戏脚本是否已生成
        // LinuxGSM 容器首次启动时，entrypoint 会执行 linuxgsm.sh <gameserver> 创建 ./<shortname> 脚本，
        // 该过程依赖从 GitHub 下载 serverlist.csv。若容器内网络不通（如 DNS 污染、代理限制），
        // 脚本创建会失败，后续 start/stop/auto-install 全部报 "No such file or directory"。
        // 此时直接返回 false 并记录详细诊断信息，让用户知道根因是网络问题而非代码问题。
        SshUtil.CommandResult scriptCheck = executeCommand(host,
                String.format("docker exec %s ls -la /app/%s 2>&1", containerName, shortname), 10000);
        if (!scriptCheck.isSuccess() || scriptCheck.getOutput() == null
                || scriptCheck.getOutput().toLowerCase().contains("no such file")) {
            log.error("LinuxGSM 游戏脚本 /app/{} 不存在，LinuxGSM 初始化可能失败。容器日志:\n{}",
                    shortname, getContainerLogs(host, containerName, 30));
            // 抛出带提示信息的异常，让上层捕获并展示给用户
            throw new RuntimeException(String.format(
                    "LinuxGSM 游戏脚本 /app/%s 不存在，LinuxGSM 初始化失败。"
                    + "常见原因：容器内无法访问 GitHub 下载 serverlist.csv（网络/DNS 问题）。"
                    + "请检查容器网络配置或宿主机 DNS 设置后重新部署。", shortname));
        }

        // 通过 LinuxGSM 启动游戏服务器
        // 注意：LinuxGSM start 命令在服务器已运行时会返回非零 exit code（如 2），
        // 但输出会包含 "already running"，这属于成功情况。
        // SshUtil.executeCommand 已修改为非零 exit code 时仍捕获 stdout/stderr，
        // 因此这里无论 isSuccess() 如何，都检查输出是否包含成功标识。
        SshUtil.CommandResult result = executeLinuxGsmCommand(host, containerName, shortname, "start", 120000);
        String output = result.getOutput() == null ? "" : result.getOutput().toLowerCase();
        if (output.contains("started") || output.contains("already running") || output.contains("starting")) {
            return true;
        }
        log.warn("LinuxGSM start 命令未返回成功标识，exitCode={}, 输出: {}", result.getExitCode(), result.getOutput());
        return result.isSuccess();
    }

    /**
     * 获取容器最近 N 行日志（用于错误诊断）。
     */
    private String getContainerLogs(Host host, String containerName, int lines) {
        try {
            SshUtil.CommandResult result = executeCommand(host,
                    String.format("docker logs --tail %d %s 2>&1", lines, containerName), 15000);
            return result.getOutput() != null ? result.getOutput() : "";
        } catch (Exception e) {
            return "(获取容器日志失败: " + e.getMessage() + ")";
        }
    }

    @Override
    public boolean stop(Long instanceId, Map<String, Object> config) {
        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            return false;
        }

        Host host = info.host();
        String shortname = getConfigString(config, "shortname", "");
        String serviceName = getConfigString(config, "serviceName", shortname);
        String projectName = getProjectName(instanceId, config);
        String containerName = getContainerName(host, config, projectName, serviceName);

        // 通过 LinuxGSM 停止游戏服务器
        // 与 start 类似，stop 命令在服务器未运行时也可能返回非零 exit code，
        // 但输出包含 "not running"，这属于成功情况。
        SshUtil.CommandResult result = executeLinuxGsmCommand(host, containerName, shortname, "stop", 60000);
        String output = result.getOutput() == null ? "" : result.getOutput().toLowerCase();
        if (output.contains("stopped") || output.contains("not running") || output.contains("stopping")) {
            return true;
        }
        return result.isSuccess();
    }

    @Override
    public boolean restart(Long instanceId, Map<String, Object> config) {
        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            return false;
        }

        Host host = info.host();
        String shortname = getConfigString(config, "shortname", "");
        String serviceName = getConfigString(config, "serviceName", shortname);
        String projectName = getProjectName(instanceId, config);
        String containerName = getContainerName(host, config, projectName, serviceName);

        ensureContainerRunning(host, instanceId, config);

        SshUtil.CommandResult result = executeLinuxGsmCommand(host, containerName, shortname, "restart", 120000);
        String output = result.getOutput() == null ? "" : result.getOutput().toLowerCase();
        // restart 命令成功时输出包含 "restart"，失败时包含 "fail" 或为空
        return output.contains("restart") || output.contains("started") || output.contains("stopping");
    }

    @Override
    public boolean healthCheck(Long instanceId, Map<String, Object> config) {
        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            return false;
        }

        Host host = info.host();
        String projectName = getProjectName(instanceId, config);
        String workDir = getWorkDir(instanceId, config);
        String composeCmd = getComposeCommand(host);

        // 健康检查策略：仅验证容器运行状态
        // LinuxGSM Docker 容器 entrypoint 启动后会异步执行 SteamCMD 下载游戏文件并自动启动游戏服务器，
        // 该过程可能持续数分钟。部署后立即调用 monitor 命令必然失败（游戏服务器尚未就绪）。
        // 因此部署健康检查只验证容器是否成功启动并运行；
        // 游戏服务器层面的就绪状态由运行时的 status / metrics 接口反映。
        // 容器配置 restart: unless-stopped 保证容器级别的可用性。
        SshUtil.CommandResult psResult = executeCommand(host,
                String.format("cd %s && %s -p %s ps -q", workDir, composeCmd, projectName), 30000);
        if (!psResult.isSuccess() || psResult.getOutput().trim().isEmpty()) {
            log.warn("LinuxGSM Docker 健康检查失败：未找到运行中的容器 instanceId={}", instanceId);
            return false;
        }

        String[] containers = psResult.getOutput().trim().split("\n");
        for (String containerId : containers) {
            if (!containerId.trim().isEmpty()) {
                SshUtil.CommandResult healthResult = executeCommand(host,
                        String.format("docker inspect -f '{{.State.Running}}' %s", containerId.trim()));
                if (!healthResult.isSuccess() || !"true".equals(healthResult.getOutput().trim())) {
                    log.warn("LinuxGSM Docker 健康检查失败：容器未运行 containerId={}", containerId.trim());
                    return false;
                }
            }
        }

        return true;
    }

    @Override
    public boolean update(Long instanceId, Map<String, Object> config, DeployProgressCallback callback) {
        notifyStageStart(callback, "UPDATE", "开始更新 LinuxGSM 游戏服务器");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyError(callback, "实例或主机不存在", "UPDATE", false);
            return false;
        }

        Host host = info.host();
        String shortname = getConfigString(config, "shortname", "");
        String serviceName = getConfigString(config, "serviceName", shortname);
        String projectName = getProjectName(instanceId, config);
        String containerName = getContainerName(host, config, projectName, serviceName);

        try {
            notifyProgress(callback, 30, "UPDATE", "停止游戏服务器");
            executeLinuxGsmCommand(host, containerName, shortname, "stop", 60000);

            notifyProgress(callback, 50, "UPDATE", "执行 LinuxGSM update");
            SshUtil.CommandResult result = executeLinuxGsmCommand(host, containerName, shortname, "update", 600000);
            if (!result.isSuccess()) {
                notifyError(callback, "更新失败: " + result.getError(), "UPDATE", false);
                return false;
            }

            notifyProgress(callback, 80, "UPDATE", "启动游戏服务器");
            executeLinuxGsmCommand(host, containerName, shortname, "start", 120000);

            notifyProgress(callback, 100, "UPDATE", "更新完成");
            notifyStageComplete(callback, "UPDATE", true, "LinuxGSM 游戏服务器更新成功");
            return true;

        } catch (Exception e) {
            log.error("LinuxGSM Docker 更新失败", e);
            notifyError(callback, "更新异常: " + e.getMessage(), "UPDATE", false);
            return false;
        }
    }

    @Override
    public boolean uninstall(Long instanceId, Map<String, Object> config, DeployProgressCallback callback) {
        notifyStageStart(callback, "UNINSTALL", "开始卸载 LinuxGSM Docker 服务");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyError(callback, "实例或主机不存在", "UNINSTALL", false);
            return false;
        }

        Host host = info.host();
        String projectName = getProjectName(instanceId, config);
        String workDir = getWorkDir(instanceId, config);

        try {
            String composeCmd = getComposeCommand(host);
            notifyProgress(callback, 30, "UNINSTALL", "停止并删除容器");
            SshUtil.CommandResult downResult = executeCommand(host,
                    String.format("cd %s && %s -p %s down", workDir, composeCmd, projectName), 120000);
            if (!downResult.isSuccess()) {
                notifyError(callback, "停止容器失败: " + downResult.getError(), "UNINSTALL", true);
            }

            notifyProgress(callback, 60, "UNINSTALL", "清理数据卷");
            boolean removeVolumes = getConfigBoolean(config, "removeVolumes", false);
            if (removeVolumes) {
                executeCommand(host,
                        String.format("cd %s && %s -p %s down -v", workDir, composeCmd, projectName), 120000);
            }

            notifyProgress(callback, 80, "UNINSTALL", "删除工作目录");
            executeCommand(host, "rm -rf " + workDir);

            notifyProgress(callback, 100, "UNINSTALL", "卸载完成");
            notifyStageComplete(callback, "UNINSTALL", true, "LinuxGSM Docker 服务卸载成功");
            return true;

        } catch (Exception e) {
            log.error("LinuxGSM Docker 卸载失败", e);
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
        String projectName = getProjectName(instanceId, config);
        String workDir = getWorkDir(instanceId, config);
        String composeCmd = getComposeCommand(host);
        String serviceName = getConfigString(config, "serviceName", getConfigString(config, "shortname", ""));
        String serviceArg = serviceName.isEmpty() ? "" : " " + serviceName;

        // 使用 --no-color 禁用 ANSI 颜色控制字符
        SshUtil.CommandResult result = executeCommand(host,
                String.format("cd %s && %s -p %s logs --no-color --tail %d%s",
                        workDir, composeCmd, projectName, lines, serviceArg), 30000);

        return stripAnsiCodes(result.getOutput());
    }

    @Override
    public InstanceStatus getStatus(Long instanceId, Map<String, Object> config) {
        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            return InstanceStatus.ERROR;
        }

        Host host = info.host();
        String projectName = getProjectName(instanceId, config);
        String workDir = getWorkDir(instanceId, config);
        String composeCmd = getComposeCommand(host);

        // 1. 检查容器运行状态
        SshUtil.CommandResult result = executeCommand(host,
                String.format("cd %s && %s -p %s ps --format json 2>/dev/null || %s -p %s ps",
                        workDir, composeCmd, projectName, composeCmd, projectName), 30000);
        String output = result.getOutput();

        if (output.trim().isEmpty() || output.contains("No containers")) {
            return InstanceStatus.NOT_INSTALLED;
        }
        if (output.contains("exited") || output.contains("Exit")) {
            return InstanceStatus.STOPPED;
        }
        if (!output.contains("running") && !output.contains("Up")) {
            return InstanceStatus.ERROR;
        }

        // 2. 容器在运行，进一步通过 LinuxGSM status 检查游戏进程状态
        String shortname = getConfigString(config, "shortname", "");
        String serviceName = getConfigString(config, "serviceName", shortname);
        String containerName = getContainerName(host, config, projectName, serviceName);
        SshUtil.CommandResult lgsmStatus = executeLinuxGsmCommand(host, containerName, shortname, "status", 15000);
        String lgsmOutput = lgsmStatus.getOutput().toLowerCase();

        if (lgsmOutput.contains("running") || lgsmOutput.contains("online")) {
            return InstanceStatus.RUNNING;
        } else if (lgsmOutput.contains("stopped") || lgsmOutput.contains("offline")) {
            return InstanceStatus.STOPPED;
        } else if (lgsmOutput.contains("starting")) {
            return InstanceStatus.STARTING;
        } else if (lgsmOutput.contains("stopping")) {
            return InstanceStatus.STOPPING;
        }

        // LinuxGSM 命令失败但容器运行，按运行中处理
        return InstanceStatus.RUNNING;
    }

    @Override
    public Map<String, Object> getDetails(Long instanceId, Map<String, Object> config) {
        Map<String, Object> details = new HashMap<>();

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            return details;
        }

        Host host = info.host();
        String projectName = getProjectName(instanceId, config);
        String workDir = getWorkDir(instanceId, config);
        String shortname = getConfigString(config, "shortname", "");
        String serviceName = getConfigString(config, "serviceName", shortname);
        String containerName = getContainerName(host, config, projectName, serviceName);

        // 获取 LinuxGSM 详情（核心信息来源）
        SshUtil.CommandResult detailsResult = executeLinuxGsmCommand(host, containerName, shortname, "details", 30000);
        if (detailsResult.isSuccess()) {
            details.put("linuxgsmDetails", stripAnsiCodes(detailsResult.getOutput()));
        }

        // 获取容器状态
        String composeCmd = getComposeCommand(host);
        SshUtil.CommandResult psResult = executeCommand(host,
                String.format("cd %s && %s -p %s ps", workDir, composeCmd, projectName), 30000);
        if (psResult.isSuccess()) {
            details.put("containerStatus", psResult.getOutput());
        }

        // 获取容器资源占用信息（CPU/内存/运行时长）
        // 直接使用容器名查询 docker stats（容器名由 deploy 阶段确定）
        if (containerName != null && !containerName.isEmpty()) {
            Map<String, Object> containerStats = queryDockerContainerStats(host, containerName);
            if (!containerStats.isEmpty()) {
                details.putAll(containerStats);
            }
        }

        details.put("instanceId", instanceId);
        details.put("projectName", projectName);
        details.put("workDir", workDir);
        details.put("containerName", containerName);
        details.put("shortname", shortname);

        return details;
    }

    @Override
    public String executeCommand(Long instanceId, Map<String, Object> config, String command) {
        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            return "实例或主机不存在";
        }

        Host host = info.host();
        String shortname = getConfigString(config, "shortname", "");
        String serviceName = getConfigString(config, "serviceName", shortname);
        String projectName = getProjectName(instanceId, config);
        String containerName = getContainerName(host, config, projectName, serviceName);

        // 通过 docker exec 调用 LinuxGSM 命令
        // command 可以是 "details"、"monitor"、"update"、"send 'say hello'" 等
        SshUtil.CommandResult result = executeLinuxGsmCommand(host, containerName, shortname, command, 60000);
        return stripAnsiCodes(result.getOutput())
                + (result.getError().isEmpty() ? "" : "\n错误: " + result.getError());
    }

    @Override
    public String backup(Long instanceId, Map<String, Object> config, DeployProgressCallback callback) {
        notifyStageStart(callback, "BACKUP", "开始备份 LinuxGSM 游戏服务器");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyError(callback, "实例或主机不存在", "BACKUP", false);
            return null;
        }

        Host host = info.host();
        String shortname = getConfigString(config, "shortname", "");
        String serviceName = getConfigString(config, "serviceName", shortname);
        String projectName = getProjectName(instanceId, config);
        String containerName = getContainerName(host, config, projectName, serviceName);

        try {
            notifyProgress(callback, 50, "BACKUP", "执行 LinuxGSM backup");
            SshUtil.CommandResult result = executeLinuxGsmCommand(host, containerName, shortname, "backup", 600000);
            if (!result.isSuccess()) {
                notifyError(callback, "备份失败: " + result.getError(), "BACKUP", false);
                return null;
            }

            // 备份文件默认存储在容器内 /data/lgsm/backup/，通过 docker cp 拷贝到宿主机
            String hostBackupDir = String.format("/tmp/lgsm-backup-%d", System.currentTimeMillis());
            executeCommand(host, String.format("mkdir -p %s", hostBackupDir));
            executeCommand(host, String.format(
                    "docker cp %s:/data/lgsm/backup/. %s/ 2>/dev/null || docker cp %s:/home/linuxgsm/lgsm/backup/. %s/ 2>/dev/null || true",
                    containerName, hostBackupDir, containerName, hostBackupDir), 60000);

            notifyProgress(callback, 100, "BACKUP", "备份完成");
            notifyStageComplete(callback, "BACKUP", true, "备份成功: " + hostBackupDir);
            return hostBackupDir;

        } catch (Exception e) {
            log.error("LinuxGSM Docker 备份失败", e);
            notifyError(callback, "备份异常: " + e.getMessage(), "BACKUP", false);
            return null;
        }
    }

    @Override
    public boolean restore(Long instanceId, Map<String, Object> config, String backupPath, DeployProgressCallback callback) {
        notifyStageStart(callback, "RESTORE", "开始恢复 LinuxGSM 游戏服务器");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyError(callback, "实例或主机不存在", "RESTORE", false);
            return false;
        }

        Host host = info.host();
        String shortname = getConfigString(config, "shortname", "");
        String serviceName = getConfigString(config, "serviceName", shortname);
        String projectName = getProjectName(instanceId, config);
        String containerName = getContainerName(host, config, projectName, serviceName);

        try {
            notifyProgress(callback, 30, "RESTORE", "停止游戏服务器");
            executeLinuxGsmCommand(host, containerName, shortname, "stop", 60000);

            notifyProgress(callback, 60, "RESTORE", "恢复备份数据到容器");
            // 将备份文件拷贝回容器
            SshUtil.CommandResult cpResult = executeCommand(host,
                    String.format("docker cp %s/. %s:/data/lgsm/backup/ 2>/dev/null || docker cp %s/. %s:/home/linuxgsm/lgsm/backup/",
                            backupPath, containerName, backupPath, containerName), 120000);
            if (!cpResult.isSuccess()) {
                notifyError(callback, "恢复备份失败: " + cpResult.getError(), "RESTORE", false);
                return false;
            }

            notifyProgress(callback, 80, "RESTORE", "启动游戏服务器");
            executeLinuxGsmCommand(host, containerName, shortname, "start", 120000);

            notifyProgress(callback, 100, "RESTORE", "恢复完成");
            notifyStageComplete(callback, "RESTORE", true, "恢复成功");
            return true;

        } catch (Exception e) {
            log.error("LinuxGSM Docker 恢复失败", e);
            notifyError(callback, "恢复异常: " + e.getMessage(), "RESTORE", false);
            return false;
        }
    }

    @Override
    public boolean cleanup(Long instanceId, Map<String, Object> config, DeployProgressCallback callback) {
        notifyStageStart(callback, "CLEANUP", "开始清理 LinuxGSM Docker 残留资源");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyStageComplete(callback, "CLEANUP", true, "实例不存在，无需清理");
            return true;
        }

        Host host = info.host();
        String projectName = getProjectName(instanceId, config);
        String workDir = getWorkDir(instanceId, config);

        try {
            String composeCmd = getComposeCommand(host);
            executeCommand(host,
                    String.format("cd %s && %s -p %s down --remove-orphans 2>/dev/null || true",
                            workDir, composeCmd, projectName), 120000);
            executeCommand(host, "rm -rf " + workDir);
            executeCommand(host, "docker volume prune -f 2>/dev/null || true", 30000);
            executeCommand(host, "docker network prune -f 2>/dev/null || true", 30000);

            notifyStageComplete(callback, "CLEANUP", true, "清理完成");
            return true;

        } catch (Exception e) {
            log.error("LinuxGSM Docker 清理失败", e);
            notifyError(callback, "清理异常: " + e.getMessage(), "CLEANUP", true);
            return false;
        }
    }

    // ========== 私有方法 ==========

    /**
     * 获取项目名
     */
    private String getProjectName(Long instanceId, Map<String, Object> config) {
        String customName = getConfigString(config, "projectName", "");
        if (!customName.isEmpty()) {
            return customName;
        }
        return String.format("%s%d", PROJECT_PREFIX, instanceId);
    }

    /**
     * 获取工作目录
     * 优先级：config.workDir > config.installPath > 默认路径（家目录下）
     */
    private String getWorkDir(Long instanceId, Map<String, Object> config) {
        String customDir = getConfigString(config, "workDir", "");
        if (!customDir.isEmpty()) {
            return customDir;
        }
        String installPath = getConfigString(config, "installPath", "");
        if (!installPath.isEmpty()) {
            return installPath;
        }
        return String.format("~/games/lgsm-%d", instanceId);
    }

    /**
     * 将 ~ 展开为绝对路径
     */
    private String resolveWorkDir(Host host, String workDir) {
        if (workDir == null || !workDir.startsWith("~")) {
            return workDir;
        }
        try {
            SshUtil.CommandResult homeResult = executeCommand(host, "echo $HOME");
            if (homeResult.isSuccess()) {
                String home = homeResult.getOutput().trim();
                if (!home.isEmpty() && home.startsWith("/")) {
                    String resolved = home + workDir.substring(1);
                    log.info("展开家目录简写: {} -> {}", workDir, resolved);
                    return resolved;
                }
            }
        } catch (Exception e) {
            log.warn("展开家目录简写失败: {}, 使用原路径", workDir);
        }
        return workDir;
    }

    /**
     * 获取容器名称
     * 优先级：config.containerName > runtimeMetadata.containerName > docker compose ps 查询 > 默认命名
     *
     * @param host        远程主机
     * @param config      部署配置（可能包含 containerName 字段和 runtimeMetadata）
     * @param projectName Compose 项目名
     * @param serviceName 服务名（兜底命名用）
     * @return 容器名或容器ID
     */
    @SuppressWarnings("unchecked")
    private String getContainerName(Host host, Map<String, Object> config, String projectName, String serviceName) {
        // 1. 优先从 config 读取显式配置的 containerName
        String customName = getConfigString(config, "containerName", "");
        if (!customName.isEmpty()) {
            return customName;
        }

        // 2. 从 runtimeMetadata 读取（deploy 阶段已写入 containerName）
        Object runtimeMetaObj = config != null ? config.get("runtimeMetadata") : null;
        if (runtimeMetaObj instanceof Map) {
            Object savedName = ((Map<String, Object>) runtimeMetaObj).get("containerName");
            if (savedName != null && !savedName.toString().isEmpty()) {
                return savedName.toString();
            }
        }

        // 3. 通过 docker compose ps 查询实际容器名（必须 cd 到 workDir 才能找到 compose 文件）
        String workDir = getConfigString(config, "workDir", "");
        if (workDir.isEmpty()) {
            workDir = getConfigString(config, "installPath", "");
        }
        String composeCmd = getComposeCommand(host);
        SshUtil.CommandResult result;
        if (workDir.isEmpty()) {
            result = executeCommand(host,
                    String.format("%s -p %s ps -q 2>/dev/null | head -1", composeCmd, projectName), 15000);
        } else {
            result = executeCommand(host,
                    String.format("cd %s && %s -p %s ps -q 2>/dev/null | head -1",
                            workDir, composeCmd, projectName), 15000);
        }
        if (result.isSuccess()) {
            String containerId = result.getOutput().trim();
            if (!containerId.isEmpty()) {
                SshUtil.CommandResult nameResult = executeCommand(host,
                        String.format("docker inspect -f '{{.Name}}' %s | sed 's/^\\///'", containerId));
                if (nameResult.isSuccess() && !nameResult.getOutput().trim().isEmpty()) {
                    return nameResult.getOutput().trim();
                }
                return containerId;
            }
        }

        // 4. 兜底使用 projectName_serviceName 格式
        return projectName + "_" + serviceName;
    }

    /**
     * 执行 LinuxGSM 命令
     * 通过 docker exec --user linuxgsm 进入容器，以 linuxgsm 用户身份执行脚本
     *
     * @param host         远程主机
     * @param containerName 容器名或容器ID
     * @param shortname    LinuxGSM 脚本名（如 l4d2server、cs2server）
     * @param command      LinuxGSM 子命令（如 start、stop、details、monitor、update）
     * @param timeoutMs    超时时间
     * @return 命令执行结果
     */
    private SshUtil.CommandResult executeLinuxGsmCommand(Host host, String containerName,
                                                          String shortname, String command, long timeoutMs) {
        // LinuxGSM Docker 镜像的 gameserver 脚本位于 /app 目录（镜像 WORKDIR）
        // /data 是数据持久化目录（serverfiles/log/config-lgsm 等）
        // 使用 `docker exec -w /app` 设置工作目录，避免 bash -lc 嵌套单引号被 SSH 远程 shell 解析问题
        // （SSH exec 通道会把命令交给远程 shell 解析，bash -lc '...' 的单引号会被外层 shell 误处理，
        // 导致 exit code 127 "command not found"）
        //
        // 注意：SshUtil 的 timeoutMs 仅作用于 SSH 建连/认证，命令读取是阻塞的
        // （executeRemoteCommand 直到命令结束才返回）。首次启动镜像 auto-install 时
        // details 等命令可能长时间挂起，导致部署线程无限阻塞（"验证 LinuxGSM 安装"卡住）。
        // 用 GNU timeout(1) 包一层 shell 级超时，超时后杀掉 docker exec（exit 124）。
        String fullCommand = String.format(
                "timeout %d docker exec --user linuxgsm -w /app %s ./%s %s",
                Math.max(1, timeoutMs / 1000), containerName, shortname, command);
        log.info("执行 LinuxGSM 命令: {} (容器: {}, 超时: {}ms)", command, containerName, timeoutMs);
        return executeCommand(host, fullCommand, timeoutMs);
    }

    /**
     * 确保容器已启动（用于 start/restart 场景，容器可能被 stop 过）
     */
    private void ensureContainerRunning(Host host, Long instanceId, Map<String, Object> config) {
        String projectName = getProjectName(instanceId, config);
        String workDir = getWorkDir(instanceId, config);
        String composeCmd = getComposeCommand(host);

        SshUtil.CommandResult psResult = executeCommand(host,
                String.format("cd %s && %s -p %s ps -q", workDir, composeCmd, projectName), 15000);
        if (psResult.isSuccess() && !psResult.getOutput().trim().isEmpty()) {
            // 检查容器是否在运行
            String[] containers = psResult.getOutput().trim().split("\n");
            for (String containerId : containers) {
                if (!containerId.trim().isEmpty()) {
                    SshUtil.CommandResult stateResult = executeCommand(host,
                            String.format("docker inspect -f '{{.State.Running}}' %s", containerId.trim()));
                    if (stateResult.isSuccess() && "false".equals(stateResult.getOutput().trim())) {
                        // 容器已停止，先启动容器
                        log.info("容器 {} 已停止，先启动容器", containerId.trim());
                        executeCommand(host,
                                String.format("cd %s && %s -p %s start", workDir, composeCmd, projectName), 60000);
                        try {
                            TimeUnit.SECONDS.sleep(5);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return;
                }
            }
        }
        // 没有运行中的容器，执行 up -d
        log.info("未发现运行中的容器，执行 up -d");
        executeCommand(host,
                String.format("cd %s && %s -p %s up -d", workDir, composeCmd, projectName), 120000);
        try {
            TimeUnit.SECONDS.sleep(8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 去除 ANSI 转义序列
     */
    private String stripAnsiCodes(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.replaceAll("\u001B\\[[0-9;]*[a-zA-Z]", "");
    }

    /**
     * 上传 Compose 文件
     *
     * @param host    远程主机
     * @param workDir 工作目录
     * @param content compose 模板内容
     * @param config  部署配置（用于读取 mountHostCerts 等选项）
     */
    private boolean uploadComposeFile(Host host, String workDir, String content, Map<String, Object> config) {
        try {
            // 确保 compose 文件包含顶级 volumes 声明
            // docker-compose 1.29+ 严格校验命名卷声明，缺失会报错：
            // "Named volume xxx is used in service but no declaration was found in the volumes section"
            content = ensureVolumesDeclaration(content);

            // 按需注入端口映射：当用户修改了默认端口时，从 network_mode: host 切换为
            // bridge 模式 + 显式端口映射，让用户自定义端口生效
            content = injectPortMappings(content, config);

            // 按需注入宿主机 SSL 证书挂载（用于反向代理场景，用户可选）
            content = injectHostCertsMount(content, config);

            // 始终注入宿主机 CA 证书挂载（LinuxGSM 框架运行的基本需求）
            // LinuxGSM entrypoint 需要访问 GitHub 下载 serverlist.csv 和脚本框架，
            // 容器自带的 CA 证书可能不完整或过时，导致 curl SSL 验证失败，
            // 进而无法创建 ./<shortname> 脚本，部署必然失败。
            // 因此对 LinuxGSM Docker 部署，CA 证书挂载是必需的，不依赖 mountHostCerts 配置。
            content = injectRequiredCaCerts(content);

            String tmpDir = System.getProperty("java.io.tmpdir");
            java.io.File tempFile = new java.io.File(tmpDir, "lgsm-compose-" + System.currentTimeMillis() + ".yml");
            String tempPath = tempFile.getAbsolutePath();
            java.nio.file.Files.write(tempFile.toPath(),
                    content.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            String remotePath = workDir + "/" + COMPOSE_FILE;
            boolean uploaded = uploadFile(host, tempPath, remotePath);

            java.nio.file.Files.deleteIfExists(tempFile.toPath());
            return uploaded;
        } catch (Exception e) {
            log.error("上传 Compose 文件失败", e);
            return false;
        }
    }

    /**
     * 按需注入端口映射。
     * <p>
     * LinuxGSM Docker 模板默认使用 {@code network_mode: host}，容器内服务器监听
     * defaultPorts 中定义的端口。当用户在部署向导中修改了端口（portConfig 中的值
     * 与 defaultPorts 不同），host 模式下用户的端口修改不会生效。
     * <p>
     * 本方法检测端口差异：
     * <ul>
     *   <li>若所有端口都与默认值相同 → 保持 host 模式（推荐，避免端口映射遗漏）</li>
     *   <li>若有任一端口被修改 → 移除 {@code network_mode: host}，注入 {@code ports:} 映射，
     *       格式为 {@code 宿主端口:容器端口}（同时映射 TCP 和 UDP）</li>
     * </ul>
     * 容器内端口固定为 defaultPorts（LinuxGSM 服务器内部监听端口），宿主机端口为
     * portConfig 中的用户值。
     *
     * @param content compose 模板内容
     * @param config  部署配置，需包含 defaultPorts（Map）和 portConfig 展开后的端口字段
     * @return 处理后的 compose 内容
     */
    @SuppressWarnings("unchecked")
    private String injectPortMappings(String content, Map<String, Object> config) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        // 必须存在 network_mode: host 才需要处理（非 host 模式说明模板已自定义端口映射）
        if (!content.contains("network_mode: host")) {
            return content;
        }

        // 读取 defaultPorts（容器内默认端口，来自 yml 配置）
        Object defaultPortsObj = config.get("defaultPorts");
        if (!(defaultPortsObj instanceof Map)) {
            log.info("[PortMapping] 未提供 defaultPorts，保持 host 网络模式");
            return content;
        }
        Map<String, Object> defaultPorts = (Map<String, Object>) defaultPortsObj;
        if (defaultPorts.isEmpty()) {
            return content;
        }

        // 构建 端口映射列表：{容器端口: 宿主端口}，仅对有差异的端口进行映射
        // portConfig 展开后，config 顶层有 game/query/rcon/steam 等端口字段（用户值）
        List<String> portMappings = new java.util.ArrayList<>();
        boolean hasPortDiff = false;
        for (Map.Entry<String, Object> entry : defaultPorts.entrySet()) {
            String key = entry.getKey();
            Object defaultVal = entry.getValue();
            if (defaultVal == null) continue;
            int containerPort;
            try {
                containerPort = Integer.parseInt(defaultVal.toString());
            } catch (NumberFormatException e) {
                continue;
            }
            Object userValObj = config.get(key);
            int hostPort = containerPort; // 默认与容器端口一致
            if (userValObj != null) {
                try {
                    hostPort = Integer.parseInt(userValObj.toString());
                } catch (NumberFormatException e) {
                    hostPort = containerPort;
                }
            }
            if (hostPort != containerPort) {
                hasPortDiff = true;
                log.info("[PortMapping] 端口 {} 被修改: 容器 {} -> 宿主 {}", key, containerPort, hostPort);
            }
            // 无论是否修改，都生成映射项（host 模式下所有端口都暴露，切到 bridge 也应全部映射）
            portMappings.add(String.format("\"%d:%d\"", hostPort, containerPort));
            portMappings.add(String.format("\"%d:%d/udp\"", hostPort, containerPort));
        }

        if (!hasPortDiff) {
            log.info("[PortMapping] 所有端口与默认值相同，保持 host 网络模式");
            return content;
        }

        // 移除 network_mode: host 行
        String newContent = content.replaceAll(
                "(?m)^\\s*network_mode:\\s*host\\s*\\n", "");
        log.info("[PortMapping] 检测到端口修改，从 host 模式切换为 bridge + 端口映射");

        // 在每个 service 块中注入 ports 列表
        // 匹配 "  serviceName:" 后面的属性行，在第一个属性前插入 ports
        // 简化方案：在 "    image:" 行之前插入 ports 块（image 是几乎所有 service 的第一个属性）
        StringBuilder portsBlock = new StringBuilder();
        String portsIndent = "    "; // 4 空格缩进（service 下属性）
        portsBlock.append(portsIndent).append("ports:\n");
        for (String mapping : portMappings) {
            portsBlock.append(portsIndent).append("  - ").append(mapping).append("\n");
        }

        // 在 "    image:" 行之前插入 ports 块
        newContent = newContent.replaceFirst(
                "(?m)^(    image:)",
                java.util.regex.Matcher.quoteReplacement(portsBlock.toString()) + "$1");

        log.info("[PortMapping] 已注入端口映射:\n{}", portsBlock);
        return newContent;
    }

    /**
     * 始终注入宿主机 CA 证书挂载（LinuxGSM 框架必需）。
     * <p>
     * 与 {@link #injectHostCertsMount} 不同，此方法无条件注入（除非已存在相同挂载），
     * 因为 LinuxGSM entrypoint 需要访问 GitHub 下载脚本框架，CA 证书是基本运行需求。
     * 挂载路径固定为 /etc/ssl/certs/ca-certificates.crt（只读），覆盖镜像自带的证书 bundle。
     */
    private String injectRequiredCaCerts(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        String hostCertPath = "/etc/ssl/certs/ca-certificates.crt";
        String containerCertPath = "/etc/ssl/certs/ca-certificates.crt";
        String mountEntry = hostCertPath + ":" + containerCertPath + ":ro";

        // 幂等检测：已存在相同挂载则跳过（可能已被 injectHostCertsMount 注入）
        if (content.contains(mountEntry)) {
            return content;
        }

        // 找到所有 service 的 volumes 列表，在每个列表末尾追加 CA 证书挂载
        java.util.regex.Pattern serviceVolumesPattern = java.util.regex.Pattern.compile(
                "(?m)(^( {2,})volumes:\\s*\\n)((?:\\2 +- [^\\n]+\\n?)+)");
        java.util.regex.Matcher matcher = serviceVolumesPattern.matcher(content);
        StringBuffer sb = new StringBuffer();
        boolean injected = false;
        while (matcher.find()) {
            String volumesHeader = matcher.group(1);
            String indent = matcher.group(2);
            String volumesBlock = matcher.group(3);
            String itemIndent = indent + "  ";
            String newBlock = volumesBlock;
            if (!newBlock.endsWith("\n")) {
                newBlock += "\n";
            }
            newBlock += itemIndent + "- " + mountEntry + "\n";
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(volumesHeader + newBlock));
            injected = true;
        }
        matcher.appendTail(sb);

        if (injected) {
            log.info("已注入宿主机 CA 证书挂载（LinuxGSM 框架必需）: {}", mountEntry);
            return sb.toString();
        }
        log.warn("未找到 service volumes 块，无法注入 CA 证书挂载");
        return content;
    }

    /**
     * 确保 compose 文件包含顶级 volumes 声明。
     * <p>
     * 如果模板中使用了命名卷（如 ${DATA_VOLUME:-xxx}）但没有顶级 volumes 声明，
     * 则自动在 services: 之前注入：
     * <pre>
     * volumes:
     *   xxx:
     *
     * services:
     *   ...
     * </pre>
     * 幂等：已存在顶级 volumes 声明时不重复添加。
     *
     * @param content 原始 compose 模板内容
     * @return 修正后的内容
     */
    private String ensureVolumesDeclaration(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        // 检测是否已存在顶级 volumes 声明（行首 volumes:，无缩进）
        // 注意区分 service 级别的 volumes（有缩进，如 "  volumes:"）
        java.util.regex.Pattern topVolumesPattern = java.util.regex.Pattern.compile(
                "(?m)^volumes:\\s*$");
        if (topVolumesPattern.matcher(content).find()) {
            return content; // 已有顶级 volumes 声明，无需处理
        }

        // 提取所有命名卷默认值：${DATA_VOLUME:-xxx} → xxx
        java.util.regex.Pattern volumeVarPattern = java.util.regex.Pattern.compile(
                "\\$\\{DATA_VOLUME:-([^}]+)\\}");
        java.util.regex.Matcher matcher = volumeVarPattern.matcher(content);
        java.util.LinkedHashSet<String> volumeNames = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            volumeNames.add(matcher.group(1).trim());
        }

        if (volumeNames.isEmpty()) {
            return content; // 没有命名卷，无需处理
        }

        // 构造顶级 volumes 声明块
        StringBuilder volumesBlock = new StringBuilder("volumes:\n");
        for (String name : volumeNames) {
            volumesBlock.append("  ").append(name).append(":\n");
        }
        volumesBlock.append("\n");

        // 在 services: 之前插入 volumes 声明块
        // services: 通常在行首（无缩进），如 "services:"
        String servicesMarker = "services:";
        int servicesIdx = content.indexOf(servicesMarker);
        if (servicesIdx < 0) {
            // 没有 services: 标记（异常情况），在文件开头插入
            log.warn("compose 模板未找到 services: 标记，volumes 声明将插入到文件开头");
            return volumesBlock.toString() + content;
        }

        // 找到 services: 所在行的起始位置（行首）
        int lineStart = servicesIdx;
        while (lineStart > 0 && content.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }

        // 仅当 services: 在行首（无缩进，即顶级 key）时才插入
        if (lineStart == servicesIdx) {
            String before = content.substring(0, lineStart);
            String after = content.substring(lineStart);
            log.info("注入顶级 volumes 声明: {}", volumeNames);
            return before + volumesBlock.toString() + after;
        }

        // services: 有缩进（不正常），不处理
        log.warn("compose 模板中 services: 有缩进，未注入 volumes 声明");
        return content;
    }

    /**
     * 按需注入宿主机 SSL 证书 + /etc/hosts 挂载。
     * <p>
     * 当 config.mountHostCerts=true 时，在每个服务的 volumes 列表中追加：
     * <pre>
     *   - {hostCertPath}:/etc/ssl/certs/ca-certificates.crt:ro
     *   - /etc/hosts:/etc/hosts:ro
     * </pre>
     * - 证书：容器内 curl/wget 使用宿主机的 CA 证书 bundle，解决反向代理场景下
     *   "SSL certificate problem: unable to get local issuer certificate" 问题
     * - hosts：让容器直接读宿主机 hosts，避免容器内 DNS 解析到 127.0.0.1
     *   （宿主机 hosts 刷新后把反向代理域名指向 LAN IP，容器共享此解析结果）
     * <p>
     * 幂等：已存在相同证书挂载时不重复添加（hosts 挂载一并跳过）。
     *
     * @param content 原始 compose 内容
     * @param config  部署配置（读取 mountHostCerts、hostCertPath）
     * @return 修正后的内容
     */
    private String injectHostCertsMount(String content, Map<String, Object> config) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        // 读取配置项（默认不启用）
        Object mountFlag = config.get("mountHostCerts");
        boolean mountHostCerts = Boolean.TRUE.equals(mountFlag)
                || (mountFlag instanceof String && "true".equalsIgnoreCase((String) mountFlag));
        if (!mountHostCerts) {
            return content;
        }

        // 宿主机证书路径（默认 /etc/ssl/certs/ca-certificates.crt）
        String hostCertPath = getConfigString(config, "hostCertPath", "/etc/ssl/certs/ca-certificates.crt");
        // 容器内目标路径（与宿主机相同，覆盖镜像自带的证书 bundle）
        String containerCertPath = "/etc/ssl/certs/ca-certificates.crt";
        String certMountEntry = hostCertPath + ":" + containerCertPath + ":ro";
        String hostsMountEntry = "/etc/hosts:/etc/hosts:ro";

        // 分别检查证书和 hosts 是否已存在（不要互相作为代理，它们可能被独立注入）
        boolean hostsAlreadyExists = content.contains(hostsMountEntry);
        boolean certAlreadyExists = content.contains(certMountEntry);
        if (certAlreadyExists && hostsAlreadyExists) {
            log.debug("compose 内容已包含证书和 hosts 挂载，跳过注入");
            return content;
        }

        // 找到所有 service 的 volumes 列表，在每个列表末尾追加证书 + hosts 挂载
        // 匹配形如（注意列表项缩进比 volumes: 多 2 空格）：
        //     volumes:
        //       - xxx:/data
        //       - yyy:/config
        // 在最后一个 - xxx 行之后追加新的挂载项
        java.util.regex.Pattern serviceVolumesPattern = java.util.regex.Pattern.compile(
                "(?m)(^( {2,})volumes:\\s*\\n)((?:\\2 +- [^\\n]+\\n?)+)");
        java.util.regex.Matcher matcher = serviceVolumesPattern.matcher(content);
        StringBuffer sb = new StringBuffer();
        boolean injected = false;
        while (matcher.find()) {
            String volumesHeader = matcher.group(1);
            String indent = matcher.group(2);
            String volumesBlock = matcher.group(3);
            // 在 volumes 块末尾追加挂载项（缩进 = volumes 缩进 + 2 空格）
            String itemIndent = indent + "  ";
            String newBlock = volumesBlock;
            // 确保末尾有换行
            if (!newBlock.endsWith("\n")) {
                newBlock += "\n";
            }
            // 证书：仅在当前 volumes 块中不存在时追加
            if (!newBlock.contains(certMountEntry)) {
                newBlock += itemIndent + "- " + certMountEntry + "\n";
                injected = true;
            }
            // hosts：仅在当前 volumes 块中不存在时追加
            if (!newBlock.contains(hostsMountEntry)) {
                newBlock += itemIndent + "- " + hostsMountEntry + "\n";
                injected = true;
            }
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(volumesHeader + newBlock));
        }
        matcher.appendTail(sb);

        if (injected) {
            log.info("已注入宿主机证书 + hosts 挂载到 compose: {}, {} (mountHostCerts=true)",
                    certMountEntry, hostsMountEntry);
            return sb.toString();
        } else {
            log.warn("mountHostCerts=true 但未在 compose 模板中找到 service volumes 块，证书挂载未注入");
            return content;
        }
    }

    /**
     * 生成 .env 文件内容
     * 遍历 variables 元信息，优先使用用户输入值，其次使用默认值
     */
    @SuppressWarnings("unchecked")
    private String generateEnvFileContent(Map<String, Object> config) {
        StringBuilder envContent = new StringBuilder();
        Object variablesObj = config.get("variables");
        if (variablesObj == null) {
            return envContent.toString();
        }

        List<Map<String, Object>> variables;
        if (variablesObj instanceof List) {
            variables = (List<Map<String, Object>>) variablesObj;
        } else {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                variables = mapper.convertValue(variablesObj,
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
                log.info("variables 配置类型异常 {}，已通过 Jackson 转换为 List", variablesObj.getClass());
            } catch (Exception e) {
                log.warn("variables 配置类型异常且转换失败: {} - {}", variablesObj.getClass(), e.getMessage());
                return envContent.toString();
            }
        }

        for (Map<String, Object> var : variables) {
            String name = (String) var.get("name");
            if (name == null || name.isEmpty()) {
                continue;
            }
            String defaultValue = var.get("defaultValue") != null ? var.get("defaultValue").toString() : "";
            boolean required = Boolean.TRUE.equals(var.get("required"));

            Object userValue = config.get(name);
            String value = userValue != null ? userValue.toString() : defaultValue;

            if (value.isEmpty() && required) {
                throw new RuntimeException("必填变量 " + name + " 未提供值");
            }

            envContent.append(name).append("=").append(value).append("\n");
        }
        return envContent.toString();
    }

    /**
     * 上传 .env 文件
     */
    private boolean uploadEnvFile(Host host, String workDir, String content) {
        try {
            String tmpDir = System.getProperty("java.io.tmpdir");
            java.io.File tempFile = new java.io.File(tmpDir, "lgsm-env-" + System.currentTimeMillis() + ".txt");
            String tempPath = tempFile.getAbsolutePath();
            java.nio.file.Files.write(tempFile.toPath(),
                    content.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            String remotePath = workDir + "/.env";
            boolean uploaded = uploadFile(host, tempPath, remotePath);

            java.nio.file.Files.deleteIfExists(tempFile.toPath());
            if (uploaded) {
                log.info(".env 文件上传成功: {}", remotePath);
            }
            return uploaded;
        } catch (Exception e) {
            log.error("上传 .env 文件失败", e);
            return false;
        }
    }
}

package com.gameplatform.adapter;

import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.util.SshUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.StringWriter;
import java.util.*;

/**
 * Docker Compose部署适配器
 * 支持docker-compose.yml文件解析、在线编辑和多服务编排
 *
 * Docker Compose命令参考：
 * - docker compose up -d       - 后台启动所有服务
 * - docker compose down        - 停止并删除所有服务
 * - docker compose start       - 启动服务
 * - docker compose stop        - 停止服务
 * - docker compose restart     - 重启服务
 * - docker compose ps          - 查看服务状态
 * - docker compose logs        - 查看服务日志
 * - docker compose pull        - 拉取镜像
 * - docker compose build       - 构建镜像
 * - docker compose config      - 验证和查看配置
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
public class DockerComposeAdapter extends AbstractDeployAdapter {

    // 默认Compose文件名
    private static final String COMPOSE_FILE = "docker-compose.yml";
    // 默认项目名前缀
    private static final String PROJECT_PREFIX = "game";

    /**
     * 主机级 Compose 命令缓存：hostId → 实际命令（"docker compose" 或 "docker-compose"）
     * 避免每次部署都重复检测
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, String> composeCommandCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public DeployType getDeployType() {
        return DeployType.DOCKER_COMPOSE;
    }

    /**
     * 检测远程主机支持的 Compose 命令
     * 优先级：docker compose（CLI 插件）> docker-compose（独立二进制）> docker compose（兜底，让错误更友好）
     *
     * @param host 远程主机
     * @return "docker compose" 或 "docker-compose"
     */
    private String getComposeCommand(Host host) {
        return composeCommandCache.computeIfAbsent(host.getId(), hostId -> {
            // 优先检测 docker compose（CLI 插件形式）
            SshUtil.CommandResult pluginResult = executeCommand(host, "docker compose version 2>/dev/null", 10000);
            if (pluginResult.isSuccess() && pluginResult.getOutput().toLowerCase().contains("compose")) {
                log.info("主机 {} 使用 docker compose（CLI 插件）", hostId);
                return "docker compose";
            }
            // 其次检测 docker-compose（独立二进制）
            SshUtil.CommandResult binaryResult = executeCommand(host, "docker-compose version 2>/dev/null", 10000);
            if (binaryResult.isSuccess() && binaryResult.getOutput().toLowerCase().contains("compose")) {
                log.info("主机 {} 使用 docker-compose（独立二进制）", hostId);
                return "docker-compose";
            }
            // 兜底：默认用 docker compose，让后续错误信息更具可读性
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

        // 检查Docker是否已安装
        if (!isDockerInstalled(host)) {
            log.error("主机 {} 未安装Docker", hostId);
            return false;
        }

        // 检查Docker Compose是否已安装
        if (!isDockerComposeInstalled(host)) {
            log.error("主机 {} 未安装Docker Compose", hostId);
            return false;
        }

        // 检查磁盘空间（至少需要3GB）
        double availableSpace = getAvailableDiskSpace(host, "/var/lib/docker");
        if (availableSpace < 3 && availableSpace != -1) {
            log.error("主机 {} Docker磁盘空间不足: {}GB < 3GB", hostId, availableSpace);
            return false;
        }

        // 检查端口是否被占用
        List<Map<String, Object>> services = getServices(config);
        for (Map<String, Object> service : services) {
            List<Map<String, Object>> ports = getServicePorts(service);
            for (Map<String, Object> port : ports) {
                Integer hostPort = (Integer) port.get("hostPort");
                if (hostPort != null && hostPort > 0 && isPortInUse(host, hostPort)) {
                    log.error("主机 {} 端口 {} 已被占用", hostId, hostPort);
                    return false;
                }
            }
        }

        return true;
    }

    @Override
    public boolean preDeploy(Long instanceId, Map<String, Object> config, DeployProgressCallback callback) {
        notifyStageStart(callback, "PRE_DEPLOY", "开始Docker Compose预部署准备");

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
            // 展开家目录简写 ~（SSH exec 和 SFTP 都需要绝对路径）
            workDir = resolveWorkDir(host, workDir);
            // 把展开后的绝对路径回写到 config，供后续 deploy/start/stop 等方法复用
            config.put("workDir", workDir);
            // 创建工作目录
            SshUtil.CommandResult mkdirResult = executeCommand(host, "mkdir -p " + workDir);
            if (!mkdirResult.isSuccess()) {
                notifyError(callback, "创建工作目录失败: " + mkdirResult.getError(), "PRE_DEPLOY", false);
                return false;
            }

            notifyProgress(callback, 30, "PRE_DEPLOY", "生成docker-compose.yml");
            // 生成或上传docker-compose.yml
            // 优先级: composeTemplate(模板驱动) > composeContent(用户直接提供) > composeFile(本地文件) > generateComposeFile(自动生成)
            String composeTemplate = getConfigString(config, "composeTemplate", "");
            String composeContent = getConfigString(config, "composeContent", "");
            String composeFile = getConfigString(config, "composeFile", "");

            String composeToUpload = null;
            if (!composeTemplate.isEmpty()) {
                // 模板驱动模式：使用 yml 中定义的 compose 模板原文
                composeToUpload = composeTemplate;
            } else if (!composeContent.isEmpty()) {
                composeToUpload = composeContent;
            } else if (!composeFile.isEmpty()) {
                // 上传本地compose文件（特殊处理，不通过 uploadComposeFile）
                String remotePath = workDir + "/" + COMPOSE_FILE;
                if (!uploadFile(host, composeFile, remotePath)) {
                    notifyError(callback, "上传docker-compose.yml失败", "PRE_DEPLOY", false);
                    return false;
                }
            } else {
                composeToUpload = generateComposeFile(config);
            }

            if (composeToUpload != null) {
                if (!uploadComposeFile(host, workDir, composeToUpload, config)) {
                    notifyError(callback, "上传docker-compose.yml失败", "PRE_DEPLOY", false);
                    return false;
                }
            }

            // 资源限制 override（ADR-0010）：fail-open，失败不阻塞部署
            syncResourceOverride(host, workDir, config, composeToUpload);

            // 模板驱动模式下，生成并上传 .env 文件
            if (!composeTemplate.isEmpty()) {
                notifyProgress(callback, 45, "PRE_DEPLOY", "生成 .env 文件");
                try {
                    String envContent = generateEnvFileContent(config);
                    if (!envContent.isEmpty()) {
                        if (!uploadEnvFile(host, workDir, envContent)) {
                            notifyError(callback, "上传.env文件失败", "PRE_DEPLOY", false);
                            return false;
                        }
                    }
                } catch (RuntimeException e) {
                    notifyError(callback, "生成.env文件失败: " + e.getMessage(), "PRE_DEPLOY", false);
                    return false;
                }
            }

            notifyProgress(callback, 60, "PRE_DEPLOY", "验证Compose配置");
            // 验证配置
            String composeCmd = getComposeCommand(host);
            SshUtil.CommandResult validateResult = executeCommand(host,
                    String.format("cd %s && COMPOSE_HTTP_TIMEOUT=300 %s -p %s config", workDir, composeCmd, projectName), 30000);

            if (!validateResult.isSuccess()) {
                notifyError(callback, "Compose配置验证失败: " + validateResult.getError(), "PRE_DEPLOY", false);
                return false;
            }

            notifyProgress(callback, 80, "PRE_DEPLOY", "拉取镜像");
            // 检查本地是否已存在 compose 引用的所有镜像
            // 若全部已存在则跳过 pull，避免去 Docker Hub 拉取时遇到认证/速率限制问题
            // （例如用户已手动 docker pull 的私有镜像或大镜像）
            if (isAllImagesAvailableLocally(host, workDir, composeCmd, projectName)) {
                notifyProgress(callback, 90, "PRE_DEPLOY", "本地镜像已存在，跳过拉取");
            } else {
                // 拉取镜像（L4D2 等游戏镜像较大，超时设置为 20 分钟）
                SshUtil.CommandResult pullResult = executeCommand(host,
                        String.format("cd %s && COMPOSE_HTTP_TIMEOUT=300 %s -p %s pull", workDir, composeCmd, projectName), 1200000);

                if (!pullResult.isSuccess()) {
                    notifyError(callback, "拉取镜像失败: " + pullResult.getError(), "PRE_DEPLOY", true);
                    // 拉取失败不阻止部署，可能在启动时自动拉取
                }
            }

            notifyProgress(callback, 100, "PRE_DEPLOY", "预部署完成");
            notifyStageComplete(callback, "PRE_DEPLOY", true, "Docker Compose预部署准备完成");
            return true;

        } catch (Exception e) {
            log.error("Docker Compose预部署失败", e);
            notifyError(callback, "预部署异常: " + e.getMessage(), "PRE_DEPLOY", false);
            return false;
        }
    }

    @Override
    public boolean deploy(Long instanceId, Map<String, Object> config, DeployProgressCallback callback) {
        notifyStageStart(callback, "DEPLOY", "开始Docker Compose部署");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyError(callback, "实例或主机不存在", "DEPLOY", false);
            return false;
        }

        Host host = info.host();
        String projectName = getProjectName(instanceId, config);
        String workDir = getWorkDir(instanceId, config);

        try {
            String composeCmd = getComposeCommand(host);
            notifyProgress(callback, 30, "DEPLOY", "启动服务");
            // 启动所有服务（若镜像未在 pull 阶段完成拉取，up -d 会自动拉取，超时同样设为 20 分钟）。
            // timeout 1200 兜底：SshUtil 的 timeoutMs 仅作用于建连，命令执行无超时
            // （executeRemoteCommand 阻塞直到命令结束），拉取卡死会无限阻塞部署线程
            SshUtil.CommandResult upResult = executeCommand(host,
                    String.format("cd %s && COMPOSE_HTTP_TIMEOUT=300 timeout 1200 %s -p %s up -d",
                            workDir, composeCmd, projectName), 1200000);

            if (!upResult.isSuccess()) {
                notifyError(callback, "启动服务失败: " + upResult.getError(), "DEPLOY", false);
                return false;
            }

            notifyProgress(callback, 70, "DEPLOY", "验证服务状态");
            // 等待服务启动
            Thread.sleep(5000);

            // 验证服务状态
            SshUtil.CommandResult psResult = executeCommand(host,
                    String.format("cd %s && COMPOSE_HTTP_TIMEOUT=300 %s -p %s ps", workDir, composeCmd, projectName), 30000);

            // docker-compose V1 输出状态为 "Up"，V2（docker compose）输出状态为 "running"
            // 两者都表示服务正常运行，需要同时兼容
            String psOutput = psResult.getOutput();
            boolean isRunning = psResult.isSuccess()
                    && (psOutput.contains("running") || psOutput.contains("Up"));
            if (!isRunning) {
                // 获取日志
                SshUtil.CommandResult logResult = executeCommand(host,
                        String.format("cd %s && COMPOSE_HTTP_TIMEOUT=300 %s -p %s logs --no-color --tail 50", workDir, composeCmd, projectName), 30000);
                notifyError(callback, "服务启动异常，日志: " + stripAnsiCodes(logResult.getOutput()), "DEPLOY", false);
                return false;
            }

            // 更新实例信息
            GameInstance instance = info.instance();
            instance.setInstallPath(workDir);
            instance.setStartCommand(String.format("cd %s && COMPOSE_HTTP_TIMEOUT=300 %s -p %s start", workDir, composeCmd, projectName));
            instance.setStopCommand(String.format("cd %s && COMPOSE_HTTP_TIMEOUT=300 %s -p %s stop", workDir, composeCmd, projectName));

            // 组装运行时元数据（卷宿主路径、容器ID、工作目录、项目名）
            java.util.Map<String, Object> runtimeMetadata = new java.util.LinkedHashMap<>();
            try {
                notifyProgress(callback, 85, "DEPLOY", "获取运行时元数据");

                // 获取命名卷宿主路径
                Object namedVolumesObj = config.get("namedVolumes");
                java.util.List<String> namedVolumes = new java.util.ArrayList<>();
                if (namedVolumesObj instanceof java.util.List) {
                    namedVolumes = (java.util.List<String>) namedVolumesObj;
                }
                java.util.Map<String, String> volumePaths = getVolumeHostPaths(host, projectName, namedVolumes);
                runtimeMetadata.put("volumePaths", volumePaths);

                // 获取容器ID
                String containerId = getComposeContainerId(host, workDir, projectName);
                runtimeMetadata.put("containerId", containerId);

                runtimeMetadata.put("workDir", workDir);
                runtimeMetadata.put("projectName", projectName);
                runtimeMetadata.put("generatedAt", java.time.LocalDateTime.now().toString());

                instance.setRuntimeMetadata(runtimeMetadata);
                log.info("实例 {} 运行时元数据: 卷路径数={}, 容器ID={}",
                        instance.getId(), volumePaths.size(),
                        containerId.length() > 12 ? containerId.substring(0, 12) : containerId);
            } catch (Exception e) {
                log.warn("组装运行时元数据失败（不影响部署结果）: {}", e.getMessage());
            }

            // 写入 configInfo 供 InstanceFileService 解析容器内文件路径
            // containerWorkDir 来自部署配置 workingDir，默认 /
            // serviceName 用于 InstanceFileService 通过 compose ps 解析容器 ID
            Map<String, Object> configInfo = instance.getConfigInfo() != null
                    ? new HashMap<>(instance.getConfigInfo())
                    : new HashMap<>();
            // 组装 configInfo.database（ADR-0009）：按 yml database 声明 + 变量最终值
            Map<String, Object> databaseInfo = assembleDatabaseConfig(config);
            if (databaseInfo != null) {
                configInfo.put("database", databaseInfo);
            }
            configInfo.put("containerWorkDir", getConfigString(config, "workingDir", "/"));
            if (!configInfo.containsKey("serviceName")) {
                String primaryServiceName = getConfigString(config, "serviceName", "");
                if (primaryServiceName.isEmpty()) {
                    List<Map<String, Object>> services = getServices(config);
                    if (!services.isEmpty() && services.get(0).get("name") != null) {
                        primaryServiceName = services.get(0).get("name").toString();
                    }
                }
                if (!primaryServiceName.isEmpty()) {
                    configInfo.put("serviceName", primaryServiceName);
                }
            }
            instance.setConfigInfo(configInfo);

            instanceMapper.updateById(instance);

            notifyProgress(callback, 100, "DEPLOY", "部署完成");
            notifyStageComplete(callback, "DEPLOY", true, "Docker Compose部署成功");
            return true;

        } catch (Exception e) {
            log.error("Docker Compose部署失败", e);
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
        String projectName = getProjectName(instanceId, config);
        String workDir = getWorkDir(instanceId, config);
        String composeCmd = getComposeCommand(host);

        SshUtil.CommandResult result = executeCommand(host,
                String.format("cd %s && COMPOSE_HTTP_TIMEOUT=300 %s -p %s start", workDir, composeCmd, projectName), 60000);

        return result.isSuccess();
    }

    @Override
    public boolean stop(Long instanceId, Map<String, Object> config) {
        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            return false;
        }

        Host host = info.host();
        String projectName = getProjectName(instanceId, config);
        String workDir = getWorkDir(instanceId, config);
        String composeCmd = getComposeCommand(host);

        SshUtil.CommandResult result = executeCommand(host,
                String.format("cd %s && COMPOSE_HTTP_TIMEOUT=300 %s -p %s stop", workDir, composeCmd, projectName), 120000);

        return result.isSuccess();
    }

    @Override
    public boolean restart(Long instanceId, Map<String, Object> config) {
        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            return false;
        }

        Host host = info.host();
        String projectName = getProjectName(instanceId, config);
        String workDir = getWorkDir(instanceId, config);
        String composeCmd = getComposeCommand(host);

        SshUtil.CommandResult result = executeCommand(host,
                String.format("cd %s && COMPOSE_HTTP_TIMEOUT=300 %s -p %s restart", workDir, composeCmd, projectName), 120000);

        return result.isSuccess();
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

        // 检查所有服务状态
        SshUtil.CommandResult result = executeCommand(host,
                String.format("cd %s && COMPOSE_HTTP_TIMEOUT=300 %s -p %s ps -q", workDir, composeCmd, projectName), 30000);

        if (!result.isSuccess() || result.getOutput().trim().isEmpty()) {
            return false;
        }

        // 检查是否有服务在运行
        String[] containers = result.getOutput().trim().split("\n");
        for (String containerId : containers) {
            if (!containerId.trim().isEmpty()) {
                SshUtil.CommandResult healthResult = executeCommand(host,
                        String.format("docker inspect -f '{{.State.Running}}' %s", containerId.trim()));
                if (!healthResult.isSuccess() || !"true".equals(healthResult.getOutput().trim())) {
                    return false;
                }
            }
        }

        return true;
    }

    @Override
    public boolean update(Long instanceId, Map<String, Object> config, DeployProgressCallback callback) {
        notifyStageStart(callback, "UPDATE", "开始更新Docker Compose服务");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyError(callback, "实例或主机不存在", "UPDATE", false);
            return false;
        }

        Host host = info.host();
        String projectName = getProjectName(instanceId, config);
        String workDir = getWorkDir(instanceId, config);

        try {
            String composeCmd = getComposeCommand(host);

            // 资源限制 override 重新同步（ADR-0010）：--force-recreate 重建容器是
            // 限制生效点，更新前须按最新 resources 状态生成/删除 override
            String overrideSource = getConfigString(config, "composeTemplate", "");
            if (overrideSource.isEmpty()) {
                overrideSource = getConfigString(config, "composeContent", "");
            }
            syncResourceOverride(host, workDir, config, overrideSource);

            notifyProgress(callback, 30, "UPDATE", "拉取最新镜像");
            // 拉取最新镜像
            SshUtil.CommandResult pullResult = executeCommand(host,
                    String.format("cd %s && COMPOSE_HTTP_TIMEOUT=300 %s -p %s pull", workDir, composeCmd, projectName), 600000);

            notifyProgress(callback, 60, "UPDATE", "重新创建服务");
            // 重新创建服务
            SshUtil.CommandResult upResult = executeCommand(host,
                    String.format("cd %s && COMPOSE_HTTP_TIMEOUT=300 %s -p %s up -d --force-recreate", workDir, composeCmd, projectName), 300000);

            if (!upResult.isSuccess()) {
                notifyError(callback, "更新服务失败: " + upResult.getError(), "UPDATE", false);
                return false;
            }

            notifyProgress(callback, 100, "UPDATE", "更新完成");
            notifyStageComplete(callback, "UPDATE", true, "Docker Compose服务更新成功");
            return true;

        } catch (Exception e) {
            log.error("Docker Compose更新失败", e);
            notifyError(callback, "更新异常: " + e.getMessage(), "UPDATE", false);
            return false;
        }
    }

    @Override
    public boolean uninstall(Long instanceId, Map<String, Object> config, DeployProgressCallback callback) {
        notifyStageStart(callback, "UNINSTALL", "开始卸载Docker Compose服务");

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
            notifyProgress(callback, 30, "UNINSTALL", "停止并删除服务");
            // 停止并删除服务
            SshUtil.CommandResult downResult = executeCommand(host,
                    String.format("cd %s && COMPOSE_HTTP_TIMEOUT=300 %s -p %s down", workDir, composeCmd, projectName), 120000);

            if (!downResult.isSuccess()) {
                notifyError(callback, "停止服务失败: " + downResult.getError(), "UNINSTALL", true);
            }

            notifyProgress(callback, 60, "UNINSTALL", "清理数据");
            // 是否删除卷
            boolean removeVolumes = getConfigBoolean(config, "removeVolumes", false);
            if (removeVolumes) {
                executeCommand(host,
                        String.format("cd %s && COMPOSE_HTTP_TIMEOUT=300 %s -p %s down -v", workDir, composeCmd, projectName), 120000);
            }

            notifyProgress(callback, 80, "UNINSTALL", "删除工作目录");
            // 删除工作目录
            executeCommand(host, "rm -rf " + workDir);

            notifyProgress(callback, 100, "UNINSTALL", "卸载完成");
            notifyStageComplete(callback, "UNINSTALL", true, "Docker Compose服务卸载成功");
            return true;

        } catch (Exception e) {
            log.error("Docker Compose卸载失败", e);
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
        String serviceName = getConfigString(config, "serviceName", "");

        String serviceArg = serviceName.isEmpty() ? "" : " " + serviceName;

        // 使用 --no-color 禁用 ANSI 颜色控制字符，避免前端显示乱码
        SshUtil.CommandResult result = executeCommand(host,
                String.format("cd %s && COMPOSE_HTTP_TIMEOUT=300 %s -p %s logs --no-color --tail %d%s",
                        workDir, composeCmd, projectName, lines, serviceArg), 30000);

        return stripAnsiCodes(result.getOutput());
    }

    /**
     * 去除 ANSI 转义序列（颜色控制字符等）
     * 兜底处理：即使 --no-color 未生效，也能清理掉残留的 ANSI 码
     *
     * @param text 原始文本
     * @return 清理后的文本
     */
    private String stripAnsiCodes(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.replaceAll("\u001B\\[[0-9;]*[a-zA-Z]", "");
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

        // 获取服务状态（兼容新旧 docker compose 版本：优先 json 格式，失败回退普通格式）
        SshUtil.CommandResult result = executeCommand(host,
                String.format("cd %s && COMPOSE_HTTP_TIMEOUT=300 %s -p %s ps --format json 2>/dev/null || %s -p %s ps",
                        workDir, composeCmd, projectName, composeCmd, projectName), 30000);

        String output = result.getOutput();

        // docker-compose V1 输出状态为 "Up"，V2（docker compose）输出状态为 "running"
        // 两者都表示服务正在运行，需要同时兼容
        if (output.contains("running") || output.contains("Up")) {
            return InstanceStatus.RUNNING;
        } else if (output.contains("exited") || output.contains("Exit")) {
            return InstanceStatus.STOPPED;
        } else if (output.trim().isEmpty() || output.contains("No containers")) {
            return InstanceStatus.NOT_INSTALLED;
        } else {
            return InstanceStatus.ERROR;
        }
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
        String composeCmd = getComposeCommand(host);

        // 获取服务列表
        SshUtil.CommandResult psResult = executeCommand(host,
                String.format("cd %s && COMPOSE_HTTP_TIMEOUT=300 %s -p %s ps", workDir, composeCmd, projectName), 30000);

        if (psResult.isSuccess()) {
            details.put("services", psResult.getOutput());
        }

        // 获取配置
        SshUtil.CommandResult configResult = executeCommand(host,
                String.format("cd %s && COMPOSE_HTTP_TIMEOUT=300 %s -p %s config", workDir, composeCmd, projectName), 30000);

        if (configResult.isSuccess()) {
            details.put("config", configResult.getOutput());
        }

        // 获取统计信息
        SshUtil.CommandResult statsResult = executeCommand(host,
                String.format("cd %s && COMPOSE_HTTP_TIMEOUT=300 %s -p %s top", workDir, composeCmd, projectName), 30000);

        if (statsResult.isSuccess()) {
            details.put("processes", statsResult.getOutput());
        }

        // 获取容器资源占用信息（CPU/内存/运行时长）
        // 通过 compose ps -q 拿到容器ID，再调用公共方法查询 docker stats
        String containerId = getComposeContainerId(host, workDir, projectName);
        if (containerId != null && !containerId.isEmpty()) {
            Map<String, Object> containerStats = queryDockerContainerStats(host, containerId);
            if (!containerStats.isEmpty()) {
                details.putAll(containerStats);
            }
        }

        details.put("instanceId", instanceId);
        details.put("projectName", projectName);
        details.put("workDir", workDir);

        return details;
    }

    @Override
    public String executeCommand(Long instanceId, Map<String, Object> config, String command) {
        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            return "实例或主机不存在";
        }

        Host host = info.host();
        String projectName = getProjectName(instanceId, config);
        String workDir = getWorkDir(instanceId, config);
        String serviceName = getConfigString(config, "serviceName", "");

        if (serviceName.isEmpty()) {
            return "未指定服务名称";
        }

        String composeCmd = getComposeCommand(host);
        SshUtil.CommandResult result = executeCommand(host,
                String.format("cd %s && COMPOSE_HTTP_TIMEOUT=300 %s -p %s exec %s %s",
                        workDir, composeCmd, projectName, serviceName, command), 60000);

        return result.getOutput() + (result.getError().isEmpty() ? "" : "\n错误: " + result.getError());
    }

    @Override
    public String backup(Long instanceId, Map<String, Object> config, DeployProgressCallback callback) {
        notifyStageStart(callback, "BACKUP", "开始备份Docker Compose数据");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyError(callback, "实例或主机不存在", "BACKUP", false);
            return null;
        }

        Host host = info.host();
        String projectName = getProjectName(instanceId, config);
        String workDir = getWorkDir(instanceId, config);
        String backupPath = String.format("/tmp/backup_%s_%d.tar.gz", projectName, System.currentTimeMillis());

        try {
            notifyProgress(callback, 40, "BACKUP", "导出卷数据");
            // 获取所有卷
            String composeCmd = getComposeCommand(host);
            SshUtil.CommandResult volumesResult = executeCommand(host,
                    String.format("cd %s && COMPOSE_HTTP_TIMEOUT=300 %s -p %s config --volumes", workDir, composeCmd, projectName), 30000);

            if (volumesResult.isSuccess()) {
                String[] volumes = volumesResult.getOutput().trim().split("\n");
                for (String volume : volumes) {
                    if (!volume.trim().isEmpty()) {
                        // 备份每个卷
                        String volumeBackup = String.format("/tmp/volume_%s_%d.tar.gz",
                                volume.trim(), System.currentTimeMillis());
                        executeCommand(host, String.format(
                                "docker run --rm -v %s_%s:/data -v /tmp:/backup alpine tar czf %s /data",
                                projectName, volume.trim(), volumeBackup), 300000);
                    }
                }
            }

            notifyProgress(callback, 70, "BACKUP", "备份配置文件");
            // 备份compose文件和配置
            SshUtil.CommandResult backupResult = executeCommand(host,
                    String.format("tar czf %s -C %s .", backupPath, workDir), 60000);

            if (!backupResult.isSuccess()) {
                notifyError(callback, "备份失败: " + backupResult.getError(), "BACKUP", false);
                return null;
            }

            notifyProgress(callback, 100, "BACKUP", "备份完成");
            notifyStageComplete(callback, "BACKUP", true, "备份成功: " + backupPath);

            return backupPath;

        } catch (Exception e) {
            log.error("Docker Compose备份失败", e);
            notifyError(callback, "备份异常: " + e.getMessage(), "BACKUP", false);
            return null;
        }
    }

    @Override
    public boolean restore(Long instanceId, Map<String, Object> config, String backupPath, DeployProgressCallback callback) {
        notifyStageStart(callback, "RESTORE", "开始恢复Docker Compose数据");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyError(callback, "实例或主机不存在", "RESTORE", false);
            return false;
        }

        Host host = info.host();
        String projectName = getProjectName(instanceId, config);
        String workDir = getWorkDir(instanceId, config);

        try {
            notifyProgress(callback, 30, "RESTORE", "停止服务");
            // 停止服务
            stop(instanceId, config);

            notifyProgress(callback, 50, "RESTORE", "恢复配置");
            // 恢复配置文件
            SshUtil.CommandResult restoreResult = executeCommand(host,
                    String.format("tar xzf %s -C %s", backupPath, workDir), 60000);

            if (!restoreResult.isSuccess()) {
                notifyError(callback, "恢复配置失败: " + restoreResult.getError(), "RESTORE", false);
                return false;
            }

            notifyProgress(callback, 80, "RESTORE", "启动服务");
            // 启动服务
            start(instanceId, config);

            notifyProgress(callback, 100, "RESTORE", "恢复完成");
            notifyStageComplete(callback, "RESTORE", true, "恢复成功");
            return true;

        } catch (Exception e) {
            log.error("Docker Compose恢复失败", e);
            notifyError(callback, "恢复异常: " + e.getMessage(), "RESTORE", false);
            return false;
        }
    }

    @Override
    public boolean cleanup(Long instanceId, Map<String, Object> config, DeployProgressCallback callback) {
        notifyStageStart(callback, "CLEANUP", "开始清理Docker Compose残留资源");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyStageComplete(callback, "CLEANUP", true, "实例不存在，无需清理");
            return true;
        }

        Host host = info.host();
        String projectName = getProjectName(instanceId, config);
        String workDir = getWorkDir(instanceId, config);

        try {
            // 停止并删除服务
            String composeCmd = getComposeCommand(host);
            executeCommand(host,
                    String.format("cd %s && COMPOSE_HTTP_TIMEOUT=300 %s -p %s down --remove-orphans 2>/dev/null || true",
                            workDir, composeCmd, projectName), 120000);

            // 删除工作目录
            executeCommand(host, "rm -rf " + workDir);

            // 清理未使用的卷和网络
            executeCommand(host, "docker volume prune -f 2>/dev/null || true", 30000);
            executeCommand(host, "docker network prune -f 2>/dev/null || true", 30000);

            notifyStageComplete(callback, "CLEANUP", true, "清理完成");
            return true;

        } catch (Exception e) {
            log.error("Docker Compose清理失败", e);
            notifyError(callback, "清理异常: " + e.getMessage(), "CLEANUP", true);
            return false;
        }
    }

    /**
     * 验证Compose文件内容
     *
     * @param content Compose文件内容
     * @return 验证结果
     */
    public boolean validateComposeContent(String content) {
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> compose = yaml.load(content);

            // 基本验证
            if (compose == null || !compose.containsKey("services")) {
                return false;
            }

            Map<String, Object> services = (Map<String, Object>) compose.get("services");
            return services != null && !services.isEmpty();

        } catch (Exception e) {
            log.error("Compose文件验证失败", e);
            return false;
        }
    }

    /**
     * 生成Compose文件内容
     *
     * @param config 配置
     * @return Compose文件内容
     */
    public String generateComposeFile(Map<String, Object> config) {
        Map<String, Object> compose = new LinkedHashMap<>();

        // 版本
        compose.put("version", "3.8");

        // 服务
        Map<String, Object> services = new LinkedHashMap<>();
        List<Map<String, Object>> serviceConfigs = getServices(config);

        for (Map<String, Object> serviceConfig : serviceConfigs) {
            String serviceName = (String) serviceConfig.get("name");
            if (serviceName == null || serviceName.isEmpty()) {
                continue;
            }

            Map<String, Object> service = new LinkedHashMap<>();

            // 镜像
            String image = (String) serviceConfig.get("image");
            if (image != null && !image.isEmpty()) {
                service.put("image", image);
            }

            // 容器名称
            String containerName = (String) serviceConfig.get("containerName");
            if (containerName != null && !containerName.isEmpty()) {
                service.put("container_name", containerName);
            }

            // 重启策略
            String restart = (String) serviceConfig.getOrDefault("restart", "unless-stopped");
            service.put("restart", restart);

            // 端口映射
            List<Map<String, Object>> ports = getServicePorts(serviceConfig);
            if (!ports.isEmpty()) {
                List<String> portMappings = new ArrayList<>();
                for (Map<String, Object> port : ports) {
                    Integer hostPort = (Integer) port.get("hostPort");
                    Integer containerPort = (Integer) port.get("containerPort");
                    String protocol = (String) port.getOrDefault("protocol", "tcp");
                    if (hostPort != null && containerPort != null) {
                        portMappings.add(String.format("%d:%d/%s", hostPort, containerPort, protocol));
                    }
                }
                if (!portMappings.isEmpty()) {
                    service.put("ports", portMappings);
                }
            }

            // 卷挂载
            List<Map<String, Object>> volumes = getServiceVolumes(serviceConfig);
            if (!volumes.isEmpty()) {
                List<String> volumeMappings = new ArrayList<>();
                for (Map<String, Object> volume : volumes) {
                    String hostPath = (String) volume.get("hostPath");
                    String containerPath = (String) volume.get("containerPath");
                    String mode = (String) volume.getOrDefault("mode", "rw");
                    if (hostPath != null && containerPath != null) {
                        volumeMappings.add(String.format("%s:%s:%s", hostPath, containerPath, mode));
                    }
                }
                if (!volumeMappings.isEmpty()) {
                    service.put("volumes", volumeMappings);
                }
            }

            // 环境变量
            Map<String, String> environment = getServiceEnvironment(serviceConfig);
            if (!environment.isEmpty()) {
                service.put("environment", environment);
            }

            // 网络
            List<String> networks = (List<String>) serviceConfig.get("networks");
            if (networks != null && !networks.isEmpty()) {
                service.put("networks", networks);
            }

            // 依赖
            List<String> dependsOn = (List<String>) serviceConfig.get("dependsOn");
            if (dependsOn != null && !dependsOn.isEmpty()) {
                service.put("depends_on", dependsOn);
            }

            services.put(serviceName, service);
        }

        compose.put("services", services);

        // 网络配置
        Map<String, Object> networks = (Map<String, Object>) config.get("networks");
        if (networks != null && !networks.isEmpty()) {
            compose.put("networks", networks);
        }

        // 卷配置
        Map<String, Object> volumes = (Map<String, Object>) config.get("volumes");
        if (volumes != null && !volumes.isEmpty()) {
            compose.put("volumes", volumes);
        }

        // 生成YAML
        Yaml yaml = new Yaml();
        StringWriter writer = new StringWriter();
        yaml.dump(compose, writer);

        return writer.toString();
    }

    // ========== 私有方法 ==========

    /**
     * 按 yml dockerCompose.database 声明组装 configInfo.database（ADR-0009）。
     * <p>
     * 变量解析与 .env 生成同源同规则：用户输入值 &gt; variables 元信息默认值 &gt; 字面量兜底。
     * 部署（{@link #deploy}）与实例更新（InstanceServiceImpl.updateInstance）共用本方法，
     * 确保两条路径产出的 database 节一致。
     *
     * @param config 部署配置（须含 database 声明节与变量最终值，即 buildDeployConfig 产物）
     * @return {type, host, port, user, password, databases[]}；无 database 声明返回 null
     */
    public static Map<String, Object> assembleDatabaseConfig(Map<String, Object> config) {
        if (config == null) {
            return null;
        }
        Object declObj = config.get("database");
        if (!(declObj instanceof Map)) {
            return null;
        }
        Map<?, ?> decl = (Map<?, ?>) declObj;
        if (decl.get("type") == null && decl.get("host") == null) {
            return null;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", decl.get("type"));
        result.put("host", decl.get("host"));
        result.put("port", resolveDatabaseVar(decl, "portVar", "port", config));
        result.put("user", decl.get("user"));
        result.put("password", resolveDatabaseVar(decl, "passwordVar", "password", config));
        Object databases = decl.get("databases");
        if (databases instanceof List) {
            result.put("databases", databases);
        }
        return result;
    }

    /**
     * 解析 database 声明中的变量名引用字段：用户输入值 &gt; variables 默认值 &gt; 同名字面量兜底。
     */
    private static Object resolveDatabaseVar(Map<?, ?> decl, String varKey, String literalKey,
                                             Map<String, Object> config) {
        Object varName = decl.get(varKey);
        if (varName != null && !varName.toString().isEmpty()) {
            Object userValue = config.get(varName.toString());
            if (userValue != null && !userValue.toString().isEmpty()) {
                return userValue;
            }
            Object defaultValue = findVariableDefault(config, varName.toString());
            if (defaultValue != null && !defaultValue.toString().isEmpty()) {
                return defaultValue;
            }
        }
        return decl.get(literalKey);
    }

    /**
     * 从 variables 元信息中查找指定变量的默认值。
     * 兼容 List 形态与 JsonTypeHandler 反序列化后的 Map 形态（key 为 "0"/"1"/...）。
     */
    private static Object findVariableDefault(Map<String, Object> config, String varName) {
        Object varsObj = config.get("variables");
        Iterable<?> vars = null;
        if (varsObj instanceof List) {
            vars = (List<?>) varsObj;
        } else if (varsObj instanceof Map) {
            vars = ((Map<?, ?>) varsObj).values();
        }
        if (vars == null) {
            return null;
        }
        for (Object varObj : vars) {
            if (varObj instanceof Map) {
                Map<?, ?> var = (Map<?, ?>) varObj;
                if (varName.equals(var.get("name"))) {
                    return var.get("defaultValue");
                }
            }
        }
        return null;
    }

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
     * 优先级：config.workDir（显式覆盖） > config.installPath（实例字段） > 默认路径
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
        return String.format("/opt/gameplatform/instances/%d", instanceId);
    }

    /**
     * 将远程工作目录中的 ~ 展开为绝对路径
     * SFTP 协议不展开 ~，必须先通过 SSH exec 获取 $HOME 再展开
     * SSH exec 的 cd 命令会自动展开 ~，所以此方法主要用于 SFTP 上传场景
     *
     * @param host    远程主机
     * @param workDir 工作目录（可能以 ~ 开头）
     * @return 绝对路径（如果展开失败则返回原路径）
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
     * 获取命名卷的宿主路径
     * Docker Compose 命名卷规则: {projectName}_{volumeName}
     *
     * @param host         远程主机
     * @param projectName  Compose 项目名
     * @param namedVolumes 命名卷列表（来自 yml 配置）
     * @return 卷名 → 宿主路径 的映射
     */
    private java.util.Map<String, String> getVolumeHostPaths(Host host, String projectName,
                                                              java.util.List<String> namedVolumes) {
        java.util.Map<String, String> volumePaths = new java.util.LinkedHashMap<>();
        if (namedVolumes == null || namedVolumes.isEmpty()) {
            return volumePaths;
        }

        for (String volumeName : namedVolumes) {
            String fullVolumeName = projectName + "_" + volumeName;
            SshUtil.CommandResult result = executeCommand(host,
                    String.format("docker volume inspect %s -f '{{.Mountpoint}}'", fullVolumeName));
            if (result.isSuccess() && !result.getOutput().trim().isEmpty()) {
                volumePaths.put(volumeName, result.getOutput().trim());
            } else {
                log.warn("获取卷宿主路径失败: {}", fullVolumeName);
            }
        }
        return volumePaths;
    }

    /**
     * 获取 Compose 项目的主容器 ID
     * <p>
     * 必须先 cd 到 workDir，因为 docker-compose ps 需要读取 docker-compose.yml 才能识别项目下的服务。
     * 若不 cd，即使指定 -p 项目名，命令也会因为找不到 compose 文件而返回空。
     *
     * @param host        远程主机
     * @param workDir     Compose 工作目录（包含 docker-compose.yml）
     * @param projectName Compose 项目名
     * @return 容器 ID（可能为空字符串）
     */
    private String getComposeContainerId(Host host, String workDir, String projectName) {
        String composeCmd = getComposeCommand(host);
        String cmd = String.format("cd %s && COMPOSE_HTTP_TIMEOUT=300 %s -p %s ps -q 2>/dev/null | head -1",
                workDir, composeCmd, projectName);
        SshUtil.CommandResult result = executeCommand(host, cmd);
        if (result.isSuccess()) {
            return result.getOutput().trim();
        }
        return "";
    }

    /**
     * 上传Compose文件
     *
     * @param host    远程主机
     * @param workDir 工作目录
     * @param content compose 内容
     * @param config  部署配置（用于读取 mountHostCerts 等选项）
     */
    private boolean uploadComposeFile(Host host, String workDir, String content, Map<String, Object> config) {
        try {
            // 确保 compose 文件包含顶级 volumes 声明（命名卷缺失会校验失败）
            content = ensureVolumesDeclaration(content);

            // 按需注入宿主机 SSL 证书挂载（用于反向代理场景）
            content = injectHostCertsMount(content, config);

            // 使用跨平台临时目录（java.io.tmpdir），避免在 Windows 上 /tmp 不存在
            String tmpDir = System.getProperty("java.io.tmpdir");
            java.io.File tempFile = new java.io.File(tmpDir, "docker-compose-" + System.currentTimeMillis() + ".yml");
            String tempPath = tempFile.getAbsolutePath();
            java.nio.file.Files.write(tempFile.toPath(), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            String remotePath = workDir + "/" + COMPOSE_FILE;
            boolean uploaded = uploadFile(host, tempPath, remotePath);

            java.nio.file.Files.deleteIfExists(tempFile.toPath());

            return uploaded;
        } catch (Exception e) {
            log.error("上传Compose文件失败", e);
            return false;
        }
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
     * @param content 原始 compose 内容
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
            return content;
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
            return content;
        }

        // 构造顶级 volumes 声明块
        StringBuilder volumesBlock = new StringBuilder("volumes:\n");
        for (String name : volumeNames) {
            volumesBlock.append("  ").append(name).append(":\n");
        }
        volumesBlock.append("\n");

        // 在 services: 之前插入 volumes 声明块
        String servicesMarker = "services:";
        int servicesIdx = content.indexOf(servicesMarker);
        if (servicesIdx < 0) {
            log.warn("compose 模板未找到 services: 标记，volumes 声明将插入到文件开头");
            return volumesBlock.toString() + content;
        }

        // 找到 services: 所在行的起始位置（行首）
        int lineStart = servicesIdx;
        while (lineStart > 0 && content.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }

        return content.substring(0, lineStart) + volumesBlock.toString() + content.substring(lineStart);
    }

    /**
     * 按需注入宿主机 SSL 证书 + /etc/hosts 挂载（用于反向代理场景）。
     * <p>
     * 当 config.mountHostCerts=true 时，在每个 service 的 volumes 列表末尾追加：
     * <pre>
     *   - {hostCertPath}:/etc/ssl/certs/ca-certificates.crt:ro
     *   - /etc/hosts:/etc/hosts:ro
     * </pre>
     * - 证书：使容器信任宿主机 CA 证书，避免 SSL 校验失败
     * - hosts：让容器直接读宿主机 hosts，避免容器内 DNS 解析到 127.0.0.1
     *   （宿主机 hosts 刷新后把反向代理域名指向 LAN IP，容器共享此解析结果）
     * <p>
     * 证书和 hosts 各自独立幂等检查：已存在的不会重复注入，缺失的会补上。
     *
     * @param content compose 内容
     * @param config  部署配置
     * @return 修正后的内容
     */
    private String injectHostCertsMount(String content, Map<String, Object> config) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        Object mountFlag = config.get("mountHostCerts");
        boolean mountHostCerts = Boolean.TRUE.equals(mountFlag)
                || (mountFlag instanceof String && "true".equalsIgnoreCase((String) mountFlag));
        if (!mountHostCerts) {
            return content;
        }

        String hostCertPath = getConfigString(config, "hostCertPath", "/etc/ssl/certs/ca-certificates.crt");
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

        // 匹配 service 级 volumes 块（列表项缩进比 volumes: 多 2 空格）
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
            log.warn("mountHostCerts=true 但未在 compose 中找到 service volumes 块，证书挂载未注入");
            return content;
        }
    }

    /**
     * 生成 .env 文件内容
     * 遍历 variables 元信息，优先使用用户输入值，其次使用默认值
     *
     * @param config 部署配置（包含 variables 元信息和用户输入值）
     * @return .env 文件内容（KEY=VALUE 每行一个）
     * @throws RuntimeException 如果必填变量未提供值
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
            // JsonTypeHandler 把 JSON 解析为 Map<String, Object>，导致原本是 List 的 variables
            // 可能被转成 LinkedHashMap（key 为 "0"/"1"/...）。用 Jackson 转回 List。
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
     *
     * @param host    远程主机
     * @param workDir 远程工作目录
     * @param content .env 文件内容
     * @return 是否上传成功
     */
    private boolean uploadEnvFile(Host host, String workDir, String content) {
        try {
            // 使用跨平台临时目录（java.io.tmpdir），避免在 Windows 上 /tmp 不存在
            String tmpDir = System.getProperty("java.io.tmpdir");
            java.io.File tempFile = new java.io.File(tmpDir, "env-" + System.currentTimeMillis() + ".txt");
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

    /**
     * 获取服务列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getServices(Map<String, Object> config) {
        Object services = config.get("services");
        if (services instanceof List) {
            return (List<Map<String, Object>>) services;
        }
        return new ArrayList<>();
    }

    /**
     * 获取服务端口
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getServicePorts(Map<String, Object> service) {
        Object ports = service.get("ports");
        if (ports instanceof List) {
            return (List<Map<String, Object>>) ports;
        }
        return new ArrayList<>();
    }

    /**
     * 获取服务卷
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getServiceVolumes(Map<String, Object> service) {
        Object volumes = service.get("volumes");
        if (volumes instanceof List) {
            return (List<Map<String, Object>>) volumes;
        }
        return new ArrayList<>();
    }

    /**
     * 获取服务环境变量
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> getServiceEnvironment(Map<String, Object> service) {
        Object env = service.get("environment");
        if (env instanceof Map) {
            Map<String, Object> envMap = (Map<String, Object>) env;
            Map<String, String> result = new HashMap<>();
            envMap.forEach((k, v) -> result.put(k, v != null ? v.toString() : ""));
            return result;
        }
        return new HashMap<>();
    }
}

package com.gameplatform.service.sync;

import com.gameplatform.adapter.DeployAdapter.InstanceStatus;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.service.docker.DockerContainerLinkService;
import com.gameplatform.service.docker.dto.ContainerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Docker 类部署的实例状态同步策略
 * 适配 docker / docker-compose / linuxgsm-docker 三种部署类型
 *
 * <p>匹配规则（三级）：
 * <ol>
 *   <li>容器ID 精确匹配（runtime_metadata.containerId 或 install_path）</li>
 *   <li>容器名精确匹配（runtime_metadata.containerName / projectName 或 configInfo.containerName）</li>
 *   <li>多字段严格匹配（镜像名一致 + 容器名包含 gameCode/instanceName 关键字）</li>
 * </ol>
 *
 * <p>状态对账：以主机实际状态为准，仅状态不一致时才 UPDATE run_status。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Component
public class DockerInstanceSyncStrategy {

    private static final Logger log = LoggerFactory.getLogger(DockerInstanceSyncStrategy.class);
    private static final String LOG_PREFIX = "[InstanceSync]";

    private final DockerContainerLinkService dockerContainerLinkService;
    private final GameInstanceMapper instanceMapper;

    public DockerInstanceSyncStrategy(DockerContainerLinkService dockerContainerLinkService,
                                       GameInstanceMapper instanceMapper) {
        this.dockerContainerLinkService = dockerContainerLinkService;
        this.instanceMapper = instanceMapper;
    }

    /**
     * 同步单台主机上的所有 Docker 类实例
     *
     * @param host      主机信息
     * @param instances 该主机上所有 Docker 类实例
     */
    public void syncHost(Host host, List<GameInstance> instances) {
        if (instances == null || instances.isEmpty()) {
            return;
        }
        List<ContainerInfo> containers;
        try {
            containers = dockerContainerLinkService.getContainers(host);
        } catch (Exception e) {
            log.warn("{} 主机 {} ({}): docker ps 失败，跳过 {} 个实例: {}",
                    LOG_PREFIX, host.getHostName(), host.getIpAddress(), instances.size(), e.getMessage());
            return;
        }

        for (GameInstance instance : instances) {
            try {
                syncInstance(host, instance, containers);
            } catch (Exception e) {
                log.error("{} 实例 #{} ({}) 同步异常: {}",
                        LOG_PREFIX, instance.getId(), instance.getInstanceName(), e.getMessage(), e);
            }
        }
    }

    private void syncInstance(Host host, GameInstance instance, List<ContainerInfo> containers) {
        InstanceMatchResult matchResult = matchContainer(instance, containers);

        if (!matchResult.matched()) {
            // 未匹配到容器，认为已被外部删除
            if (shouldUpdate(instance.getRunStatus(), InstanceStatus.STOPPED)) {
                updateInstanceStatus(instance, InstanceStatus.STOPPED, matchResult.remark());
            }
            return;
        }

        // 匹配到容器，查询容器实际状态
        String containerStatus = queryContainerStatus(host, instance, containers);
        writeBackContainerId(host, instance, matchResult);
        InstanceStatus targetStatus = mapContainerStatus(containerStatus);

        if (shouldUpdate(instance.getRunStatus(), targetStatus)) {
            String remark = targetStatus == InstanceStatus.RUNNING ? null :
                    (targetStatus == InstanceStatus.STOPPED ? "容器已退出" : null);
            updateInstanceStatus(instance, targetStatus, remark);
        } else {
            log.debug("{} 实例 #{} ({}): 状态未变化 ({}→{})，跳过更新",
                    LOG_PREFIX, instance.getId(), instance.getInstanceName(),
                    instance.getRunStatus(), targetStatus.getCode());
        }
    }

    /**
     * 将匹配到的容器 ID 写回 runtime_metadata（部署中断/容器重建后自动修复，
     * 供删除清理、控制台、文件路由直接使用）。
     */
    private void writeBackContainerId(Host host, GameInstance instance, InstanceMatchResult matchResult) {
        if (matchResult == null || matchResult.containerId() == null) {
            return;
        }
        try {
            Map<String, Object> runtime = instance.getRuntimeMetadata();
            boolean dirty = false;
            if (runtime == null) {
                runtime = new java.util.LinkedHashMap<>();
                instance.setRuntimeMetadata(runtime);
                dirty = true;
            }
            Object existing = runtime.get("containerId");
            String matched = matchResult.containerId();
            if (!(existing instanceof String s) || s.isBlank() || !s.equals(matched)) {
                runtime.put("containerId", matched);
                dirty = true;
            }
            if (dirty) {
                instanceMapper.updateById(instance);
                log.info("{} 实例 #{} ({}) 容器 ID 已回写 runtime_metadata: {}",
                        LOG_PREFIX, instance.getId(), instance.getInstanceName(),
                        matched.length() > 12 ? matched.substring(0, 12) : matched);
            }
        } catch (Exception e) {
            log.warn("{} 实例 #{} 容器 ID 回写失败: {}",
                    LOG_PREFIX, instance.getId(), e.getMessage());
        }
    }

    /**
     * 匹配容器（容器ID 优先 → 容器名 → 多字段严格匹配）
     * 包级可见以便未来扩展测试
     */
    InstanceMatchResult matchContainer(GameInstance instance, List<ContainerInfo> containers) {
        if (containers == null || containers.isEmpty()) {
            return InstanceMatchResult.notFound("容器不存在（已被外部删除）");
        }

        // 第 1 级：容器ID 匹配（支持完整ID 与短ID 互为前缀，docker ps 默认输出 12 位短ID）
        String expectedContainerId = resolveContainerId(instance);
        if (expectedContainerId != null && !expectedContainerId.isBlank()) {
            for (ContainerInfo c : containers) {
                if (containerIdMatches(expectedContainerId, c.containerId())) {
                    return InstanceMatchResult.matched(InstanceStatus.RUNNING, c.containerId());
                }
            }
        }

        // 第 2 级：容器名精确匹配
        String expectedContainerName = resolveContainerName(instance);
        if (expectedContainerName != null && !expectedContainerName.isBlank()) {
            for (ContainerInfo c : containers) {
                if (expectedContainerName.equals(c.containerName())) {
                    return InstanceMatchResult.matched(InstanceStatus.RUNNING, c.containerId());
                }
            }
        }

        // 第 2.5 级：docker-compose 项目名前缀匹配
        // compose 容器名规范为 {projectName}_{service}_{n}（如 game56_l4d2_1），
        // 宿主机重启容器重建（ID 变更）后，项目名前缀是稳定的重新识别依据。
        // 注意：config 的 CONTAINER_NAME 是容器内环境变量，不是容器名，不能作精确匹配依据。
        if ("docker-compose".equals(instance.getDeployType())) {
            String projectName = resolveProjectName(instance);
            if (projectName != null && !projectName.isBlank()) {
                String prefix = projectName + "_";
                for (ContainerInfo c : containers) {
                    if (c.containerName() != null && c.containerName().startsWith(prefix)) {
                        return InstanceMatchResult.matched(InstanceStatus.RUNNING, c.containerId());
                    }
                }
            }
        }

        // 第 3 级：多字段严格匹配
        for (ContainerInfo c : containers) {
            if (matchByMultipleFields(instance, c)) {
                return InstanceMatchResult.matched(InstanceStatus.RUNNING, c.containerId());
            }
        }

        return InstanceMatchResult.notFound("容器不存在（已被外部删除）");
    }

    /**
     * 容器ID 匹配：支持完整ID 与短ID 互为前缀
     * Docker ps 默认输出 12 位短ID，runtime_metadata.containerId 可能存完整 64 位ID
     */
    private boolean containerIdMatches(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        String e = expected.toLowerCase();
        String a = actual.toLowerCase();
        // 完全相等
        if (e.equals(a)) {
            return true;
        }
        // 一方是另一方的前缀（短ID 是完整ID 的前缀）
        return e.startsWith(a) || a.startsWith(e);
    }

    /**
     * 解析实例的容器ID
     * 优先级 1: runtime_metadata.containerId
     * 优先级 2: install_path（仅当看起来像容器ID 时使用，避免误把路径当ID）
     */
    private String resolveContainerId(GameInstance instance) {
        Map<String, Object> runtime = instance.getRuntimeMetadata();
        if (runtime != null) {
            Object cidObj = runtime.get("containerId");
            if (cidObj instanceof String cid && !cid.isBlank()) {
                return cid;
            }
        }
        String installPath = instance.getInstallPath();
        if (installPath != null && !installPath.isBlank() && looksLikeContainerId(installPath)) {
            return installPath;
        }
        return null;
    }

    /**
     * 解析实例的容器名
     * 优先级：
     *   1. runtime_metadata.containerName（部署时写入）
     *   2. config_info.containerName（小写驼峰）
     *   3. config_info.CONTAINER_NAME（大写下划线，docker-compose 模板变量常用）
     *   4. config_info.container_name（小写下划线）
     * 注意：runtime_metadata.projectName 是 docker-compose 项目名，不是容器名，不再作为容器名候选
     * DockerAdapter 默认命名：game-instance-{id}（仅 docker 部署类型）
     */
    private String resolveContainerName(GameInstance instance) {
        Map<String, Object> runtime = instance.getRuntimeMetadata();
        if (runtime != null) {
            Object name = runtime.get("containerName");
            if (name instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        Map<String, Object> config = instance.getConfigInfo();
        if (config != null) {
            String fromConfig = pickFirstNonBlankString(config,
                    "containerName", "CONTAINER_NAME", "container_name");
            if (fromConfig != null) {
                return fromConfig;
            }
        }
        // DockerAdapter 默认命名
        if ("docker".equals(instance.getDeployType()) && instance.getId() != null) {
            return String.format("game-instance-%d", instance.getId());
        }
        return null;
    }

    /**
     * 从 Map 中按多个候选 key 顺序取第一个非空白字符串值
     */
    private String pickFirstNonBlankString(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object v = map.get(key);
            if (v instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    /**
     * 多字段严格匹配：镜像名一致 + 容器名包含关键字（gameCode 或 instanceName，不区分大小写）
     * 注意：ContainerInfo 当前不含端口信息，端口校验降级跳过
     */
    private boolean matchByMultipleFields(GameInstance instance, ContainerInfo container) {
        // 1. 镜像名一致
        String expectedImage = resolveExpectedImage(instance);
        if (expectedImage == null || !imageMatches(expectedImage, container.imageName())) {
            return false;
        }
        // 2. 容器名包含 gameCode 或 instanceName 关键字（不区分大小写）
        String keyword = extractKeyword(instance);
        if (keyword == null || keyword.isBlank()) {
            return false;
        }
        if (container.containerName() == null ||
                !container.containerName().toLowerCase().contains(keyword.toLowerCase())) {
            return false;
        }
        // 3. 端口校验：ContainerInfo 不含端口信息，降级跳过
        return true;
    }

    private String resolveExpectedImage(GameInstance instance) {
        Map<String, Object> config = instance.getConfigInfo();
        if (config != null) {
            // 兼容多种命名风格：image / IMAGE / IMAGE_NAME / imageName
            String image = pickFirstNonBlankString(config, "image", "IMAGE", "IMAGE_NAME", "imageName");
            if (image != null) {
                return image;
            }
            // docker-compose 模板变量风格：IMAGE_REPO + IMAGE_TAG 组合
            String repo = pickFirstNonBlankString(config, "IMAGE_REPO", "imageRepo");
            if (repo != null) {
                String tag = pickFirstNonBlankString(config, "IMAGE_TAG", "imageTag");
                return tag == null ? repo : repo + ":" + tag;
            }
        }
        return null;
    }

    /**
     * 解析 compose 项目名：优先 runtime_metadata.projectName（部署时写入），
     * 缺失时按实例 ID 推导 game{id}（与 DockerComposeAdapter.PROJECT_PREFIX 约定一致）。
     */
    private String resolveProjectName(GameInstance instance) {
        Map<String, Object> runtime = instance.getRuntimeMetadata();
        if (runtime != null) {
            Object projectName = runtime.get("projectName");
            if (projectName instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return instance.getId() == null ? null : "game" + instance.getId();
    }

    private String extractKeyword(GameInstance instance) {
        if (instance.getGameCode() != null && !instance.getGameCode().isBlank()) {
            return instance.getGameCode();
        }
        if (instance.getInstanceName() != null && !instance.getInstanceName().isBlank()) {
            return instance.getInstanceName();
        }
        return null;
    }

    private boolean imageMatches(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        String e = stripTag(expected).toLowerCase();
        String a = stripTag(actual).toLowerCase();
        // 镜像名可能含 registry 前缀，用包含关系判断
        return e.contains(a) || a.contains(e);
    }

    private String stripTag(String image) {
        int tagIdx = image.lastIndexOf(':');
        if (tagIdx > 0 && !image.substring(tagIdx + 1).contains("/")) {
            return image.substring(0, tagIdx);
        }
        return image;
    }

    private boolean looksLikeContainerId(String s) {
        // 容器ID 是 12 或 64 位十六进制字符串
        return s.matches("[0-9a-fA-F]{12,64}");
    }

    /**
     * 查询容器的实际状态字符串
     * 优先用容器ID（必须能在 containers 列表中找到，否则可能是过期数据）；否则通过容器名查找 ID 后查询
     */
    private String queryContainerStatus(Host host, GameInstance instance, List<ContainerInfo> containers) {
        String containerId = resolveContainerId(instance);
        if (containerId != null) {
            // 找到匹配的容器（支持短ID/完整ID 互匹配），用容器列表中的真实ID 查询状态
            String matchedId = findMatchedContainerId(containers, containerId);
            if (matchedId != null) {
                return dockerContainerLinkService.getContainerStatus(host, matchedId);
            }
        }
        // 退化：通过容器名查找 ID
        String containerName = resolveContainerName(instance);
        if (containerName != null) {
            for (ContainerInfo c : containers) {
                if (containerName.equals(c.containerName())) {
                    return dockerContainerLinkService.getContainerStatus(host, c.containerId());
                }
            }
        }
        // 多字段匹配的情况下，匹配到的容器就是 containers 中的一个，但无法确定 ID
        // 此时保守认为运行中（避免误报停止）
        return "running";
    }

    /**
     * 在容器列表中查找与期望容器ID 匹配的真实容器ID（支持短ID/完整ID 互匹配）
     * 返回容器列表中的真实ID（可能是短ID 或完整ID，取决于 docker ps 命令），用于后续 inspect
     */
    private String findMatchedContainerId(List<ContainerInfo> containers, String expectedId) {
        if (containers == null || expectedId == null) {
            return null;
        }
        for (ContainerInfo c : containers) {
            if (containerIdMatches(expectedId, c.containerId())) {
                return c.containerId();
            }
        }
        return null;
    }

    private boolean containsContainerId(List<ContainerInfo> containers, String containerId) {
        return findMatchedContainerId(containers, containerId) != null;
    }

    private InstanceStatus mapContainerStatus(String status) {
        if (status == null) {
            return InstanceStatus.RUNNING;
        }
        return switch (status.trim()) {
            case "running" -> InstanceStatus.RUNNING;
            case "exited", "dead", "terminated" -> InstanceStatus.STOPPED;
            case "restarting" -> InstanceStatus.STARTING;
            case "paused" -> InstanceStatus.RUNNING;
            default -> InstanceStatus.RUNNING;
        };
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
        log.info("{} 实例 #{} ({}): 状态变更 {}→{} {}",
                LOG_PREFIX, instance.getId(), instance.getInstanceName(),
                oldStatus, newStatus, remark != null ? "(" + remark + ")" : "");
    }
}

package com.gameplatform.plugin.service;

import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.deploy.HostCredentials;
import com.gameplatform.util.SshUtil;
import com.gameplatform.vo.InstanceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 容器标识解析器（实例 → 容器 ID/名称）。
 *
 * <p>供控制台（docker exec 交互）、文件路由（buildRoute）等复用：
 * <ol>
 *   <li>优先 metadata/runtimeMetadata 的 containerId</li>
 *   <li>docker 类：容器名兜底（containerName / CONTAINER_NAME / container_name）</li>
 *   <li>docker-compose：projectName + serviceName 动态查询，缺失回退容器名查询</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContainerIdResolver {

    private final DeploymentAccess deployAccess;
    private final SshUtil sshUtil;

    /**
     * 解析实例对应的容器标识（ID 或名称）。
     *
     * @param instance 实例
     * @param metadata 实例配置（configInfo 合并 runtimeMetadata 后的 Map；调用方负责合并）
     * @return 容器 ID 或容器名
     * @throws IllegalStateException 无法解析（实例非 docker 类/容器未运行/信息缺失）
     */
    public String resolve(InstanceVO instance, Map<String, Object> metadata) {
        String deployType = instance.getDeployType();

        if ("docker".equals(deployType) || "linuxgsm-docker".equals(deployType)) {
            // docker run 创建后容器 ID 稳定：containerId 优先
            String containerId = getString(metadata, "containerId", null);
            if (containerId != null && !containerId.isBlank()) {
                return containerId;
            }
            // 容器名兜底（兼容显式配置与 DockerAdapter 默认命名 game-instance-{id}）
            String containerName = resolveContainerName(metadata);
            if (containerName == null || containerName.isBlank()) {
                containerName = instance.getId() == null ? null : "game-instance-" + instance.getId();
            }
            if (containerName != null && !containerName.isBlank()) {
                return containerName;
            }
            throw new IllegalStateException(
                deployType + " 实例缺少 containerId/containerName: " + instance.getId());
        }

        if ("docker-compose".equals(deployType)) {
            // compose 容器重建后 ID 会变（宿主机重启场景）：动态查询当前真实容器，
            // containerId 仅作查询失败时的回退（不盲信，避免同步写回前用旧 ID 操作失败）。
            // projectName 限定精确识别（game{id}）：避免多项目同名容器（CONTAINER_NAME=l4d2）误匹配。
            // 优先 runtimeMetadata.projectName，缺失时按实例 ID 推导 game{id}
            String projectName = getString(metadata, "projectName", null);
            if (projectName == null || projectName.isBlank()) {
                projectName = "game" + instance.getId();
            }
            String serviceName = getString(metadata, "serviceName", null);
            HostCredentials conn = deployAccess.credentials(instance.getHostId());
            String cmd;
            if (serviceName != null && !serviceName.isBlank()) {
                cmd = "docker compose -p " + projectName + " ps -q " + serviceName;
            } else {
                // 项目前缀精确匹配（compose 容器名规范：{projectName}_{service}_{n}）
                cmd = "docker ps -q -f name=" + projectName + "_";
            }
            SshUtil.CommandResult r = sshUtil.executeCommand(
                conn.host(), conn.port(), conn.username(), conn.privateKey(), conn.password(), cmd);
            if (r.getExitCode() == 0) {
                String output = r.getOutput().trim();
                if (!output.isEmpty()) {
                    String[] lines = output.split("\n");
                    if (lines.length > 1) {
                        throw new IllegalStateException(
                            "容器 ID 不唯一，匹配到 " + lines.length + " 个容器");
                    }
                    return lines[0];
                }
            }
            // 动态查询失败/容器未运行：回退容器名（compose 可能显式 container_name，如 l4d2，
            // 此时 projectName 前缀查询无匹配；容器名在重建后稳定，可直接交给 docker exec 解析），
            // 再回退缓存 containerId（至少能给出旧容器标识供排障）
            String containerName = resolveContainerName(metadata);
            if (containerName != null && !containerName.isBlank()) {
                return containerName;
            }
            String containerId = getString(metadata, "containerId", null);
            if (containerId != null && !containerId.isBlank()) {
                return containerId;
            }
            throw new IllegalStateException(
                "无法解析容器 ID（容器未运行或查询失败）: " + instance.getId());
        }

        throw new IllegalStateException("不支持的部署类型: " + deployType);
    }

    /** 解析容器名：兼容 containerName / CONTAINER_NAME / container_name 多种命名风格 */
    public String resolveContainerName(Map<String, Object> metadata) {
        String name = getString(metadata, "containerName", null);
        if (name == null || name.isBlank()) {
            name = getString(metadata, "CONTAINER_NAME", null);
        }
        if (name == null || name.isBlank()) {
            name = getString(metadata, "container_name", null);
        }
        return name;
    }

    private String getString(Map<String, Object> map, String key, String defaultVal) {
        Object v = map.get(key);
        if (v == null) {
            return defaultVal;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? defaultVal : s;
    }
}

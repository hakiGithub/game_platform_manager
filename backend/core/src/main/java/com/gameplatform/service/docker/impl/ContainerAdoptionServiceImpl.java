package com.gameplatform.service.docker.impl;

import com.gameplatform.adapter.DeployAdapter;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.dto.docker.ContainerAdoptDTO;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.GameMetadata;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.mapper.GameMetadataMapper;
import com.gameplatform.plugin.listener.PluginLifecycleHook;
import com.gameplatform.service.docker.ContainerAdoptionService;
import com.gameplatform.service.docker.DockerContainerService;
import com.gameplatform.vo.docker.ContainerDetailVO;
import com.gameplatform.vo.docker.ContainerListVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 容器认领服务实现（ADR-0023）
 *
 * <p>deployType 探测：以游戏元数据 supportedDeployTypes 为准，Docker 类内按
 * docker > docker-compose > linuxgsm-docker 取优先级；仅支持 compose 的游戏自动落
 * compose，并从容器的 com.docker.compose.* labels 预填 project/workDir/service。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContainerAdoptionServiceImpl implements ContainerAdoptionService {

    private static final List<String> ADOPTABLE_DEPLOY_TYPES =
            List.of("docker", "docker-compose", "linuxgsm-docker");

    private static final String LABEL_COMPOSE_PROJECT = "com.docker.compose.project";
    private static final String LABEL_COMPOSE_WORKDIR = "com.docker.compose.project.working_dir";
    private static final String LABEL_COMPOSE_SERVICE = "com.docker.compose.service";

    private final DockerContainerService containerService;
    private final GameInstanceMapper instanceMapper;
    private final GameMetadataMapper gameMetadataMapper;
    private final PluginLifecycleHook pluginLifecycleHook;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long adoptContainer(Long hostId, String containerId, ContainerAdoptDTO dto) {
        // 1. 容器必须真实存在（docker inspect，失败抛容器不存在）
        ContainerDetailVO detail = containerService.getContainerDetail(hostId, containerId);

        // 2. 游戏元数据必须存在
        GameMetadata game = gameMetadataMapper.selectById(dto.getGameId());
        if (game == null) {
            throw new BusinessException("游戏不存在");
        }

        // 3. 同主机实例名唯一（与 createInstance 一致：清理逻辑删除残留后放行）
        GameInstance exist = instanceMapper.selectByHostIdAndInstanceName(hostId, dto.getInstanceName());
        if (exist != null) {
            if (exist.getDeleted() != null && exist.getDeleted() == 1) {
                log.warn("[Adopt] 逻辑删除残留实例，物理清除以释放实例名: id={}, name={}",
                        exist.getId(), exist.getInstanceName());
                instanceMapper.physicalDeleteById(exist.getId());
            } else {
                throw new BusinessException("该主机下实例名称「" + dto.getInstanceName() + "」已存在，请更换名称后重试");
            }
        }

        // 4. deployType 探测 + compose 字段解析
        String deployType = resolveDeployType(game, dto);
        Map<String, Object> runtimeMetadata = buildRuntimeMetadata(detail, dto, deployType);

        // 5. 构建实例：跳过部署，初始状态取容器实际状态
        GameInstance instance = new GameInstance();
        instance.setInstanceName(dto.getInstanceName());
        instance.setHostId(hostId);
        instance.setGameId(dto.getGameId());
        instance.setGameCode(game.getGameCode());
        instance.setDeployType(deployType);
        instance.setPortConfig(resolvePortConfig(game, detail));
        instance.setConfigInfo(Map.of());
        instance.setRuntimeMetadata(runtimeMetadata);
        boolean running = "running".equalsIgnoreCase(detail.getStatus());
        instance.setRunStatus(running
                ? DeployAdapter.InstanceStatus.RUNNING.getCode()
                : DeployAdapter.InstanceStatus.STOPPED.getCode());
        instance.setOnlinePlayers(0);
        instance.setRemark(firstNonBlank(dto.getRemark(),
                "容器认领 " + detail.getContainerName() + " @ " + LocalDateTime.now()));

        try {
            instanceMapper.insert(instance);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException("该主机下实例名称「" + dto.getInstanceName() + "」已存在，请更换名称后重试");
        }

        // 6. 插件实例创建钩子（与常规创建一致，异常不影响认领）
        try {
            pluginLifecycleHook.executeInstanceCreateHooks(instance.getId(), game.getGameCode(), Map.of());
        } catch (Exception e) {
            log.warn("[Adopt] 插件实例创建钩子执行异常，不影响认领: instanceId={}", instance.getId(), e);
        }

        log.info("[Adopt] 容器认领成功: hostId={}, container={}, instanceId={}, deployType={}, runStatus={}",
                hostId, detail.getContainerName(), instance.getId(), deployType, instance.getRunStatus());
        return instance.getId();
    }

    /** deployType 解析：显式指定须在游戏支持列表内且为 Docker 类；缺省按优先级探测 */
    private String resolveDeployType(GameMetadata game, ContainerAdoptDTO dto) {
        List<String> supported = game.getSupportedDeployTypes();
        if (dto.getDeployType() != null && !dto.getDeployType().isBlank()) {
            String t = dto.getDeployType();
            if (!ADOPTABLE_DEPLOY_TYPES.contains(t)) {
                throw new BusinessException("认领仅支持 Docker 类部署方式（docker/docker-compose/linuxgsm-docker）");
            }
            if (supported != null && !supported.isEmpty() && !supported.contains(t)) {
                throw new BusinessException("游戏「" + game.getGameName() + "」不支持部署方式 " + t);
            }
            return t;
        }
        for (String t : ADOPTABLE_DEPLOY_TYPES) {
            if (supported != null && supported.contains(t)) {
                return t;
            }
        }
        throw new BusinessException("游戏「" + game.getGameName() + "」元数据不支持任何 Docker 类部署方式，无法认领容器");
    }

    /** runtime_metadata：容器标识 + adopted 标记 + compose 字段（labels 预填，缺失则要求显式提供） */
    private Map<String, Object> buildRuntimeMetadata(ContainerDetailVO detail, ContainerAdoptDTO dto, String deployType) {
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("containerId", detail.getContainerId());
        runtime.put("containerName", detail.getContainerName());
        runtime.put(ADOPTED_FLAG_KEY, true);
        runtime.put("adoptedAt", LocalDateTime.now().toString());

        if ("docker-compose".equals(deployType)) {
            String workDir = firstNonBlank(dto.getWorkDir(), label(detail, LABEL_COMPOSE_WORKDIR));
            String projectName = firstNonBlank(dto.getProjectName(), label(detail, LABEL_COMPOSE_PROJECT));
            String serviceName = firstNonBlank(dto.getServiceName(), label(detail, LABEL_COMPOSE_SERVICE));
            if (workDir == null || projectName == null) {
                throw new BusinessException("docker-compose 认领需要 compose 项目名与工作目录："
                        + "容器 labels 未携带且未手动填写，请在认领对话框中补充");
            }
            runtime.put("workDir", workDir);
            runtime.put("projectName", projectName);
            if (serviceName != null) {
                runtime.put("serviceName", serviceName);
            }
        }
        return runtime;
    }

    /** portConfig：主端口 game 取容器实际映射的第一个主机端口，缺省回退游戏默认端口 */
    private Map<String, Object> resolvePortConfig(GameMetadata game, ContainerDetailVO detail) {
        Map<String, Object> portConfig = new LinkedHashMap<>();
        Integer mainPort = null;
        List<ContainerListVO.PortMapping> ports = detail.getPorts();
        if (ports != null) {
            for (ContainerListVO.PortMapping pm : ports) {
                if (pm.getHostPort() != null && pm.getHostPort() > 0) {
                    mainPort = pm.getHostPort();
                    break;
                }
            }
        }
        if (mainPort == null) {
            mainPort = game.getDefaultPort();
        }
        if (mainPort != null) {
            portConfig.put("game", mainPort);
        }
        return portConfig;
    }

    private String label(ContainerDetailVO detail, String key) {
        if (detail.getLabels() == null) {
            return null;
        }
        String v = detail.getLabels().get(key);
        return (v == null || v.isBlank()) ? null : v;
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }
}

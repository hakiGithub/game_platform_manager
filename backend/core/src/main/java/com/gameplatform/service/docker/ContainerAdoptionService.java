package com.gameplatform.service.docker;

import com.gameplatform.dto.docker.ContainerAdoptDTO;
import com.gameplatform.entity.GameInstance;

/**
 * Docker 容器认领为游戏实例服务（ADR-0023）
 *
 * <p>认领 = 创建真实 game_instance 记录并回写 runtime_metadata.containerId/containerName
 * + adopted 标记，跳过部署流程；初始 run_status 取容器实际状态。
 */
public interface ContainerAdoptionService {

    /** runtime_metadata 中认领标记的键名 */
    String ADOPTED_FLAG_KEY = "adopted";

    /**
     * 将主机上的已有容器认领为游戏实例。
     *
     * @param hostId      主机ID
     * @param containerId 容器ID（完整或短 ID）
     * @param dto         认领参数（实例名、游戏、可选 deployType 与 compose 字段）
     * @return 新建实例ID
     */
    Long adoptContainer(Long hostId, String containerId, ContainerAdoptDTO dto);

    /** 判断实例是否为认领实例（runtime_metadata.adopted = true） */
    static boolean isAdopted(GameInstance instance) {
        return instance != null
                && instance.getRuntimeMetadata() != null
                && Boolean.TRUE.equals(instance.getRuntimeMetadata().get(ADOPTED_FLAG_KEY));
    }
}

package com.gameplatform.service.docker;

import com.gameplatform.dto.docker.ContainerLogQueryDTO;
import com.gameplatform.dto.docker.ContainerOperationDTO;
import com.gameplatform.vo.docker.*;

import java.util.List;

/**
 * Docker容器管理服务接口
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface DockerContainerService {

    /**
     * 获取容器列表
     *
     * @param hostId   主机ID
     * @param status   状态筛选
     * @param keyword  关键词搜索
     * @param linked   关联状态筛选
     * @return 容器列表
     */
    List<ContainerListVO> listContainers(Long hostId, String status, String keyword, Boolean linked);

    /**
     * 获取容器详情
     *
     * @param hostId      主机ID
     * @param containerId 容器ID
     * @return 容器详情
     */
    ContainerDetailVO getContainerDetail(Long hostId, String containerId);

    /**
     * 启动容器
     *
     * @param hostId      主机ID
     * @param containerId 容器ID
     * @return 操作结果
     */
    ContainerOperationResult startContainer(Long hostId, String containerId);

    /**
     * 停止容器
     *
     * @param hostId      主机ID
     * @param containerId 容器ID
     * @param dto         操作参数
     * @return 操作结果
     */
    ContainerOperationResult stopContainer(Long hostId, String containerId, ContainerOperationDTO dto);

    /**
     * 重启容器
     *
     * @param hostId      主机ID
     * @param containerId 容器ID
     * @param dto         操作参数
     * @return 操作结果
     */
    ContainerOperationResult restartContainer(Long hostId, String containerId, ContainerOperationDTO dto);

    /**
     * 删除容器
     *
     * @param hostId      主机ID
     * @param containerId 容器ID
     * @param dto         操作参数
     * @return 操作结果
     */
    ContainerOperationResult deleteContainer(Long hostId, String containerId, ContainerOperationDTO dto);

    /**
     * 获取容器资源统计
     *
     * @param hostId      主机ID
     * @param containerId 容器ID
     * @return 资源统计
     */
    ContainerStatsVO getContainerStats(Long hostId, String containerId);

    /**
     * 获取容器健康状态
     *
     * @param hostId      主机ID
     * @param containerId 容器ID
     * @return 健康状态
     */
    ContainerHealthVO getContainerHealth(Long hostId, String containerId);

    /**
     * 获取容器日志
     *
     * @param hostId      主机ID
     * @param containerId 容器ID
     * @param query       查询参数
     * @return 日志内容
     */
    ContainerLogVO getContainerLogs(Long hostId, String containerId, ContainerLogQueryDTO query);

    /**
     * 容器操作结果
     */
    record ContainerOperationResult(
            Boolean success,
            String containerId,
            String message
    ) {}

    /**
     * 容器日志VO
     */
    record ContainerLogVO(
            String containerId,
            List<LogLine> logs,
            Integer total
    ) {}

    /**
     * 日志行
     */
    record LogLine(
            String time,
            String content
    ) {}
}

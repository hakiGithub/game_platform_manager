package com.gameplatform.service.docker;

import com.gameplatform.dto.docker.ContainerLinkDTO;
import com.gameplatform.entity.DockerContainerLink;
import com.gameplatform.entity.Host;
import com.gameplatform.service.docker.dto.ContainerInfo;
import com.gameplatform.vo.docker.ContainerLinkVO;

import java.util.List;

/**
 * Docker容器关联管理服务接口
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface DockerContainerLinkService {

    /**
     * 创建关联
     *
     * @param dto    关联请求DTO
     * @param userId 创建用户ID
     * @return 关联记录
     */
    ContainerLinkVO createLink(ContainerLinkDTO dto, Long userId);

    /**
     * 更新关联
     *
     * @param id  关联ID
     * @param dto 更新DTO
     * @return 更新后的关联记录
     */
    ContainerLinkVO updateLink(Long id, ContainerLinkDTO dto);

    /**
     * 删除关联
     *
     * @param id 关联ID
     */
    void deleteLink(Long id);

    /**
     * 根据ID获取关联详情
     *
     * @param id 关联ID
     * @return 关联详情
     */
    ContainerLinkVO getLinkById(Long id);

    /**
     * 获取关联列表
     *
     * @param hostId      主机ID
     * @param instanceId  实例ID
     * @param containerId 容器ID
     * @param linkType    关联类型
     * @return 关联列表
     */
    List<ContainerLinkVO> listLinks(Long hostId, Long instanceId, String containerId, String linkType);

    /**
     * 执行自动关联
     *
     * @param hostId 主机ID
     * @param userId 创建用户ID
     * @return 自动关联结果
     */
    AutoLinkResult autoLink(Long hostId, Long userId);

    /**
     * 根据主机ID和容器ID获取关联
     *
     * @param hostId      主机ID
     * @param containerId 容器ID
     * @return 关联记录
     */
    DockerContainerLink getLinkByContainer(Long hostId, String containerId);

    /**
     * 获取主机上所有 Docker 容器（含已停止的）
     * 通过 SSH 执行 docker ps -a --format 解析
     *
     * @param host 主机信息（需含 SSH 凭据）
     * @return 容器信息列表；SSH 失败时返回空列表
     */
    List<ContainerInfo> getContainers(Host host);

    /**
     * 查询单个容器的状态字符串
     * 通过 SSH 执行 docker inspect -f '{{.State.Status}}' 获取
     *
     * @param host        主机信息
     * @param containerId 容器ID
     * @return 状态字符串（running/exited/restarting/dead/paused），查询失败返回 null
     */
    String getContainerStatus(Host host, String containerId);

    /**
     * 自动关联结果
     */
    record AutoLinkResult(
            Integer totalContainers,
            Integer linkedCount,
            Integer skippedCount,
            List<LinkDetail> links
    ) {}

    /**
     * 关联详情
     */
    record LinkDetail(
            String containerId,
            String containerName,
            Long instanceId,
            String instanceName,
            String matchedImage
    ) {}
}

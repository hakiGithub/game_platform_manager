package com.gameplatform.service.docker.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.common.result.ResultCode;
import com.gameplatform.dto.docker.ContainerLinkDTO;
import com.gameplatform.entity.DockerContainerLink;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.DockerContainerLinkMapper;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.service.docker.DockerContainerLinkService;
import com.gameplatform.service.docker.dto.ContainerInfo;
import com.gameplatform.util.AesUtil;
import com.gameplatform.util.SshUtil;
import com.gameplatform.vo.docker.ContainerLinkVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Docker容器关联管理服务实现
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DockerContainerLinkServiceImpl implements DockerContainerLinkService {

    private final DockerContainerLinkMapper linkMapper;
    private final HostMapper hostMapper;
    private final GameInstanceMapper instanceMapper;
    private final SshUtil sshUtil;
    private final AesUtil aesUtil;

    @Override
    @Transactional
    public ContainerLinkVO createLink(ContainerLinkDTO dto, Long userId) {
        // 检查主机是否存在
        Host host = hostMapper.selectById(dto.getHostId());
        if (host == null) {
            throw new BusinessException(ResultCode.HOST_NOT_FOUND);
        }
        
        // 检查容器是否存在
        if (!containerExists(host, dto.getContainerId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "容器不存在: " + dto.getContainerId());
        }
        
        // 检查是否已存在关联
        DockerContainerLink existing = linkMapper.selectByHostAndContainer(dto.getHostId(), dto.getContainerId());
        if (existing != null) {
            throw new BusinessException(ResultCode.CONFLICT, "容器已存在关联关系");
        }
        
        // 如果关联实例，检查实例是否存在
        if ("instance".equals(dto.getLinkType()) && dto.getInstanceId() != null) {
            GameInstance instance = instanceMapper.selectById(dto.getInstanceId());
            if (instance == null) {
                throw new BusinessException(ResultCode.GAME_INSTANCE_NOT_FOUND);
            }
        }
        
        // 创建关联记录
        DockerContainerLink link = new DockerContainerLink();
        link.setHostId(dto.getHostId());
        link.setContainerId(dto.getContainerId());
        link.setContainerName(dto.getContainerName());
        link.setInstanceId("instance".equals(dto.getLinkType()) ? dto.getInstanceId() : null);
        link.setLinkType(dto.getLinkType());
        link.setImageName(dto.getImageName());
        link.setImageTag(dto.getImageTag());
        link.setAutoLinked(0);
        link.setCreateBy(userId);
        link.setRemark(dto.getRemark());
        
        linkMapper.insert(link);
        
        return convertToVO(link);
    }

    @Override
    @Transactional
    public ContainerLinkVO updateLink(Long id, ContainerLinkDTO dto) {
        DockerContainerLink link = linkMapper.selectById(id);
        if (link == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "关联记录不存在");
        }
        
        // 更新关联信息
        if (dto.getInstanceId() != null) {
            GameInstance instance = instanceMapper.selectById(dto.getInstanceId());
            if (instance == null) {
                throw new BusinessException(ResultCode.GAME_INSTANCE_NOT_FOUND);
            }
            link.setInstanceId(dto.getInstanceId());
            link.setLinkType("instance");
        } else if ("host".equals(dto.getLinkType())) {
            link.setInstanceId(null);
            link.setLinkType("host");
        }
        
        if (dto.getRemark() != null) {
            link.setRemark(dto.getRemark());
        }
        
        linkMapper.updateById(link);
        
        return convertToVO(link);
    }

    @Override
    @Transactional
    public void deleteLink(Long id) {
        DockerContainerLink link = linkMapper.selectById(id);
        if (link == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "关联记录不存在");
        }
        
        linkMapper.deleteById(id);
    }

    @Override
    public ContainerLinkVO getLinkById(Long id) {
        DockerContainerLinkMapper.DockerContainerLinkDetail detail = linkMapper.selectDetailById(id);
        if (detail == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "关联记录不存在");
        }
        
        return convertDetailToVO(detail);
    }

    @Override
    public List<ContainerLinkVO> listLinks(Long hostId, Long instanceId, String containerId, String linkType) {
        List<DockerContainerLinkMapper.DockerContainerLinkDetail> details;
        
        if (hostId != null) {
            details = linkMapper.selectDetailByHostId(hostId);
        } else if (instanceId != null) {
            List<DockerContainerLink> links = linkMapper.selectByInstanceId(instanceId);
            details = links.stream()
                    .map(this::convertToDetail)
                    .toList();
        } else if (containerId != null) {
            LambdaQueryWrapper<DockerContainerLink> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DockerContainerLink::getContainerId, containerId);
            List<DockerContainerLink> links = linkMapper.selectList(wrapper);
            details = links.stream()
                    .map(this::convertToDetail)
                    .toList();
        } else {
            // 返回所有关联
            LambdaQueryWrapper<DockerContainerLink> wrapper = new LambdaQueryWrapper<>();
            if (linkType != null && !linkType.isEmpty()) {
                wrapper.eq(DockerContainerLink::getLinkType, linkType);
            }
            wrapper.orderByDesc(DockerContainerLink::getCreateTime);
            List<DockerContainerLink> links = linkMapper.selectList(wrapper);
            details = links.stream()
                    .map(this::convertToDetail)
                    .toList();
        }
        
        return details.stream()
                .map(this::convertDetailToVO)
                .toList();
    }

    @Override
    @Transactional
    public AutoLinkResult autoLink(Long hostId, Long userId) {
        Host host = hostMapper.selectById(hostId);
        if (host == null) {
            throw new BusinessException(ResultCode.HOST_NOT_FOUND);
        }
        
        // 获取主机上所有容器
        List<ContainerInfo> containers = getContainers(host);
        
        // 获取主机上所有游戏实例
        List<GameInstance> instances = getInstancesByHost(hostId);
        
        // 获取已存在的关联
        List<DockerContainerLink> existingLinks = linkMapper.selectByHostId(hostId);
        Set<String> linkedContainers = new HashSet<>();
        existingLinks.forEach(l -> linkedContainers.add(l.getContainerId()));
        
        int linkedCount = 0;
        int skippedCount = 0;
        List<LinkDetail> links = new ArrayList<>();
        
        for (ContainerInfo container : containers) {
            // 跳过已关联的容器
            if (linkedContainers.contains(container.containerId())) {
                skippedCount++;
                continue;
            }
            
            // 尝试匹配实例
            GameInstance matchedInstance = matchInstance(container, instances);
            
            if (matchedInstance != null) {
                // 创建关联
                DockerContainerLink link = new DockerContainerLink();
                link.setHostId(hostId);
                link.setContainerId(container.containerId());
                link.setContainerName(container.containerName());
                link.setInstanceId(matchedInstance.getId());
                link.setLinkType("instance");
                link.setImageName(container.imageName());
                link.setImageTag(container.imageTag());
                link.setAutoLinked(1);
                link.setCreateBy(userId);
                
                linkMapper.insert(link);
                
                linkedCount++;
                links.add(new LinkDetail(
                        container.containerId(),
                        container.containerName(),
                        matchedInstance.getId(),
                        matchedInstance.getInstanceName(),
                        container.imageName()
                ));
            } else {
                skippedCount++;
            }
        }
        
        return new AutoLinkResult(containers.size(), linkedCount, skippedCount, links);
    }

    @Override
    public DockerContainerLink getLinkByContainer(Long hostId, String containerId) {
        return linkMapper.selectByHostAndContainer(hostId, containerId);
    }

    @Override
    public List<ContainerInfo> getContainers(Host host) {
        // 使用 --no-trunc 返回完整容器ID（64位），避免与 runtime_metadata.containerId（完整ID）比较时失败
        String command = "docker ps -a --no-trunc --format '{{.ID}}|{{.Names}}|{{.Image}}'";
        SshUtil.CommandResult result = executeCommand(host, command, 30000);
        
        List<ContainerInfo> containers = new ArrayList<>();
        if (result.isSuccess()) {
            String[] lines = result.getOutput().split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                
                String[] parts = line.split("\\|");
                if (parts.length >= 3) {
                    String containerId = parts[0].trim();
                    String containerName = parts[1].trim();
                    String image = parts[2].trim();
                    
                    String imageName = "";
                    String imageTag = "";
                    
                    int lastColon = image.lastIndexOf(':');
                    if (lastColon > 0 && !image.substring(lastColon).contains("/")) {
                        imageName = image.substring(0, lastColon);
                        imageTag = image.substring(lastColon + 1);
                    } else {
                        imageName = image;
                        imageTag = "latest";
                    }
                    
                    containers.add(new ContainerInfo(containerId, containerName, imageName, imageTag));
                }
            }
        }
        
        return containers;
    }

    @Override
    public String getContainerStatus(Host host, String containerId) {
        if (containerId == null || containerId.isBlank()) {
            return null;
        }
        try {
            String command = String.format("docker inspect -f '{{.State.Status}}' %s 2>/dev/null", containerId);
            SshUtil.CommandResult result = executeCommand(host, command, 10000);
            if (result.isSuccess() && result.getOutput() != null) {
                String status = result.getOutput().trim();
                return status.isEmpty() ? null : status;
            }
        } catch (Exception e) {
            log.warn("查询容器状态失败 host={}, containerId={}: {}", host.getId(), containerId, e.getMessage());
        }
        return null;
    }

    // ========== 私有方法 ==========

    private boolean containerExists(Host host, String containerId) {
        String command = String.format("docker ps -aq -f id=%s", containerId);
        SshUtil.CommandResult result = executeCommand(host, command, 10000);
        return result.isSuccess() && !result.getOutput().trim().isEmpty();
    }

    private List<GameInstance> getInstancesByHost(Long hostId) {
        LambdaQueryWrapper<GameInstance> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GameInstance::getHostId, hostId);
        return instanceMapper.selectList(wrapper);
    }

    private GameInstance matchInstance(ContainerInfo container, List<GameInstance> instances) {
        if (container.imageName() == null || container.imageName().isEmpty()) {
            return null;
        }
        
        // 根据镜像名称匹配实例
        // TODO: 这里需要根据游戏元数据的镜像配置进行匹配
        // 简化处理：根据实例名称或配置中的镜像名称匹配
        
        for (GameInstance instance : instances) {
            // 这里可以实现更复杂的匹配逻辑
            // 例如：检查实例的配置信息中是否包含镜像名称
            if (instance.getInstanceName() != null && 
                    instance.getInstanceName().toLowerCase().contains(container.imageName().toLowerCase())) {
                return instance;
            }
        }
        
        return null;
    }

    private SshUtil.CommandResult executeCommand(Host host, String command, int timeout) {
        try {
            return sshUtil.executeCommand(
                    host.getIpAddress(),
                    host.getSshPort() != null ? host.getSshPort() : 22,
                    host.getSshUser(),
                    null,
                    getDecryptedPassword(host),
                    command,
                    timeout
            );
        } catch (Exception e) {
            log.error("执行SSH命令失败: {}", e.getMessage(), e);
            SshUtil.CommandResult errorResult = new SshUtil.CommandResult();
            errorResult.setExitCode(1);
            errorResult.setOutput("");
            errorResult.setError(e.getMessage());
            errorResult.setSuccess(false);
            return errorResult;
        }
    }

    private String getDecryptedPassword(Host host) {
        if (host.getSshPassword() == null || host.getSshPassword().isEmpty()) {
            return null;
        }
        try {
            return aesUtil.decrypt(host.getSshPassword());
        } catch (Exception e) {
            log.error("解密密码失败: {}", e.getMessage());
            return host.getSshPassword();
        }
    }

    private ContainerLinkVO convertToVO(DockerContainerLink link) {
        ContainerLinkVO vo = new ContainerLinkVO();
        vo.setId(link.getId());
        vo.setHostId(link.getHostId());
        vo.setContainerId(link.getContainerId());
        vo.setContainerName(link.getContainerName());
        vo.setInstanceId(link.getInstanceId());
        vo.setLinkType(link.getLinkType());
        vo.setImageName(link.getImageName());
        vo.setImageTag(link.getImageTag());
        vo.setAutoLinked(link.getAutoLinked() == 1);
        vo.setCreateBy(link.getCreateBy());
        vo.setCreateTime(link.getCreateTime());
        vo.setUpdateTime(link.getUpdateTime());
        
        // 获取主机名称
        Host host = hostMapper.selectById(link.getHostId());
        if (host != null) {
            vo.setHostName(host.getHostName());
        }
        
        // 获取实例名称
        if (link.getInstanceId() != null) {
            GameInstance instance = instanceMapper.selectById(link.getInstanceId());
            if (instance != null) {
                vo.setInstanceName(instance.getInstanceName());
            }
        }
        
        return vo;
    }

    private ContainerLinkVO convertDetailToVO(DockerContainerLinkMapper.DockerContainerLinkDetail detail) {
        ContainerLinkVO vo = new ContainerLinkVO();
        vo.setId(detail.getId());
        vo.setHostId(detail.getHostId());
        vo.setHostName(detail.getHostName());
        vo.setContainerId(detail.getContainerId());
        vo.setContainerName(detail.getContainerName());
        vo.setInstanceId(detail.getInstanceId());
        vo.setInstanceName(detail.getInstanceName());
        vo.setLinkType(detail.getLinkType());
        vo.setImageName(detail.getImageName());
        vo.setImageTag(detail.getImageTag());
        vo.setAutoLinked(detail.getAutoLinked() == 1);
        vo.setCreateBy(detail.getCreateBy());
        vo.setCreateTime(detail.getCreateTime());
        vo.setUpdateTime(detail.getUpdateTime());
        return vo;
    }

    private DockerContainerLinkMapper.DockerContainerLinkDetail convertToDetail(DockerContainerLink link) {
        // 简单实现，实际应该重新查询
        return new DockerContainerLinkMapper.DockerContainerLinkDetail() {
            @Override
            public Long getId() { return link.getId(); }
            @Override
            public Long getHostId() { return link.getHostId(); }
            @Override
            public String getHostName() {
                Host host = hostMapper.selectById(link.getHostId());
                return host != null ? host.getHostName() : null;
            }
            @Override
            public String getContainerId() { return link.getContainerId(); }
            @Override
            public String getContainerName() { return link.getContainerName(); }
            @Override
            public Long getInstanceId() { return link.getInstanceId(); }
            @Override
            public String getInstanceName() {
                if (link.getInstanceId() == null) return null;
                GameInstance instance = instanceMapper.selectById(link.getInstanceId());
                return instance != null ? instance.getInstanceName() : null;
            }
            @Override
            public String getLinkType() { return link.getLinkType(); }
            @Override
            public String getImageName() { return link.getImageName(); }
            @Override
            public String getImageTag() { return link.getImageTag(); }
            @Override
            public Integer getAutoLinked() { return link.getAutoLinked(); }
            @Override
            public Long getCreateBy() { return link.getCreateBy(); }
            @Override
            public LocalDateTime getCreateTime() { return link.getCreateTime(); }
            @Override
            public LocalDateTime getUpdateTime() { return link.getUpdateTime(); }
        };
    }
}

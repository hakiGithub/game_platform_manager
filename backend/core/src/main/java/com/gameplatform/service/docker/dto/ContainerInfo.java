package com.gameplatform.service.docker.dto;

/**
 * Docker 容器信息
 * 用于在 service 层传递 docker ps 解析后的容器元数据
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public record ContainerInfo(
        /** 容器ID（12 或 64 位十六进制字符串） */
        String containerId,
        /** 容器名 */
        String containerName,
        /** 镜像名（不含 tag） */
        String imageName,
        /** 镜像 tag */
        String imageTag
) {
}

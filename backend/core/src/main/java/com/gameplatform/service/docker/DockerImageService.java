package com.gameplatform.service.docker;

import com.gameplatform.vo.docker.ImageListVO;

import java.util.List;

/**
 * Docker镜像管理服务接口
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface DockerImageService {

    /**
     * 获取镜像列表
     *
     * @param hostId   主机ID
     * @param keyword  关键词搜索
     * @param dangling 是否只显示悬空镜像
     * @return 镜像列表
     */
    List<ImageListVO> listImages(Long hostId, String keyword, Boolean dangling);

    /**
     * 删除镜像
     *
     * @param hostId  主机ID
     * @param imageId 镜像ID
     * @param force   是否强制删除
     * @return 删除结果
     */
    ImageDeleteResult deleteImage(Long hostId, String imageId, Boolean force);

    /**
     * 清理悬空镜像
     *
     * @param hostId 主机ID
     * @return 清理结果
     */
    ImagePruneResult pruneImages(Long hostId);

    /**
     * 镜像删除结果
     */
    record ImageDeleteResult(
            Boolean success,
            String imageId,
            String message,
            List<String> deletedImages
    ) {}

    /**
     * 镜像清理结果
     */
    record ImagePruneResult(
            List<String> deletedImages,
            Long spaceReclaimed
    ) {}
}

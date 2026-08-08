package com.gameplatform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Docker容器关联实体类
 * 对应表: docker_container_link
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("docker_container_link")
public class DockerContainerLink extends BaseEntity {

    /**
     * 主机ID
     */
    private Long hostId;

    /**
     * Docker容器ID
     */
    private String containerId;

    /**
     * 容器名称
     */
    private String containerName;

    /**
     * 关联的游戏实例ID
     */
    private Long instanceId;

    /**
     * 关联类型: instance-关联实例, host-关联主机
     */
    private String linkType;

    /**
     * 镜像名称（不含标签）
     */
    private String imageName;

    /**
     * 镜像标签
     */
    private String imageTag;

    /**
     * 是否自动关联: 0-手动, 1-自动
     */
    private Integer autoLinked;

    /**
     * 创建人ID
     */
    private Long createBy;

}

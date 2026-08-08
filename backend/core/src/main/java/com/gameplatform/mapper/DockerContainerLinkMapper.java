package com.gameplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gameplatform.entity.DockerContainerLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Docker容器关联Mapper
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Mapper
public interface DockerContainerLinkMapper extends BaseMapper<DockerContainerLink> {

    /**
     * 根据主机ID和容器ID查询关联
     *
     * @param hostId      主机ID
     * @param containerId 容器ID
     * @return 关联记录
     */
    @Select("SELECT * FROM docker_container_link WHERE host_id = #{hostId} AND container_id = #{containerId} AND is_deleted = 0")
    DockerContainerLink selectByHostAndContainer(@Param("hostId") Long hostId, @Param("containerId") String containerId);

    /**
     * 根据主机ID查询所有关联
     *
     * @param hostId 主机ID
     * @return 关联列表
     */
    @Select("SELECT * FROM docker_container_link WHERE host_id = #{hostId} AND is_deleted = 0 ORDER BY create_time DESC")
    List<DockerContainerLink> selectByHostId(@Param("hostId") Long hostId);

    /**
     * 根据实例ID查询关联
     *
     * @param instanceId 实例ID
     * @return 关联列表
     */
    @Select("SELECT * FROM docker_container_link WHERE instance_id = #{instanceId} AND is_deleted = 0")
    List<DockerContainerLink> selectByInstanceId(@Param("instanceId") Long instanceId);

    /**
     * 根据创建人查询关联
     *
     * @param createBy 创建人ID
     * @return 关联列表
     */
    @Select("SELECT * FROM docker_container_link WHERE create_by = #{createBy} AND is_deleted = 0 ORDER BY create_time DESC")
    List<DockerContainerLink> selectByCreateBy(@Param("createBy") Long createBy);

    /**
     * 查询关联详情（包含主机名称、实例名称）
     *
     * @param id 关联ID
     * @return 关联详情
     */
    @Select("SELECT dcl.*, h.host_name, gi.instance_name " +
            "FROM docker_container_link dcl " +
            "LEFT JOIN host_info h ON dcl.host_id = h.id " +
            "LEFT JOIN game_instance gi ON dcl.instance_id = gi.id " +
            "WHERE dcl.id = #{id} AND dcl.is_deleted = 0")
    DockerContainerLinkDetail selectDetailById(@Param("id") Long id);

    /**
     * 查询主机下所有关联详情
     *
     * @param hostId 主机ID
     * @return 关联详情列表
     */
    @Select("SELECT dcl.*, h.host_name, gi.instance_name " +
            "FROM docker_container_link dcl " +
            "LEFT JOIN host_info h ON dcl.host_id = h.id " +
            "LEFT JOIN game_instance gi ON dcl.instance_id = gi.id " +
            "WHERE dcl.host_id = #{hostId} AND dcl.is_deleted = 0 " +
            "ORDER BY dcl.create_time DESC")
    List<DockerContainerLinkDetail> selectDetailByHostId(@Param("hostId") Long hostId);

    /**
     * Docker容器关联详情（包含关联的名称）
     */
    interface DockerContainerLinkDetail {
        Long getId();
        Long getHostId();
        String getHostName();
        String getContainerId();
        String getContainerName();
        Long getInstanceId();
        String getInstanceName();
        String getLinkType();
        String getImageName();
        String getImageTag();
        Integer getAutoLinked();
        Long getCreateBy();
        java.time.LocalDateTime getCreateTime();
        java.time.LocalDateTime getUpdateTime();
    }
}

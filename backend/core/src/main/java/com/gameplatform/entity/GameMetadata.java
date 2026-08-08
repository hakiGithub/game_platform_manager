package com.gameplatform.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gameplatform.handler.JsonListTypeHandler;
import com.gameplatform.handler.JsonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

/**
 * 游戏元数据实体类
 * 对应表: game_metadata
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "game_metadata", autoResultMap = true)
public class GameMetadata extends BaseEntity {

    /**
     * 游戏名称
     */
    private String gameName;

    /**
     * 游戏代码(唯一标识)
     */
    private String gameCode;

    /**
     * 游戏描述
     */
    private String description;

    /**
     * 支持的部署类型(JSON数组) ["docker", "native"]
     */
    @TableField(typeHandler = JsonListTypeHandler.class)
    private List<String> supportedDeployTypes;

    /**
     * 默认端口
     */
    private Integer defaultPort;

    /**
     * 环境依赖(JSON格式)
     */
    @TableField(typeHandler = JsonTypeHandler.class)
    private Map<String, Object> environmentDeps;

    /**
     * 部署配置模板(JSON格式)
     */
    @TableField(typeHandler = JsonTypeHandler.class)
    private Map<String, Object> deployConfig;

    /**
     * 自定义操作(JSON格式)
     */
    @TableField(typeHandler = JsonTypeHandler.class)
    private Map<String, Object> customOperations;

    /**
     * 图标URL
     */
    private String iconUrl;

}

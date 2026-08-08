package com.gameplatform.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gameplatform.handler.JsonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 插件信息实体类
 * 对应表: plugin_info
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "plugin_info", autoResultMap = true)
public class PluginInfo extends BaseEntity {

    /**
     * 插件ID(唯一标识)
     */
    private String pluginId;

    /**
     * 插件名称
     */
    private String pluginName;

    /**
     * 插件版本
     */
    private String version;

    /**
     * 插件状态 0-禁用 1-启用
     */
    private Integer status;

    /**
     * 插件描述
     */
    private String description;

    /**
     * 扩展点配置(JSON格式)
     */
    @TableField(typeHandler = JsonTypeHandler.class)
    private Map<String, Object> extensionPoints;

    /**
     * 配置模式(JSON格式)
     */
    @TableField(typeHandler = JsonTypeHandler.class)
    private Map<String, Object> configSchema;

    /**
     * 作者
     */
    private String author;

    /**
     * 插件类型
     * game_enhancement: 游戏增强插件
     * system: 系统插件
     * integration: 集成插件
     */
    private String pluginType;

    /**
     * 游戏编码（关联游戏）
     */
    private String gameCode;

    /**
     * 插件文件路径
     */
    private String filePath;

    /**
     * 运行时状态
     * CREATED: 已创建
     * DISABLED: 已禁用
     * RESOLVED: 已解析
     * STARTED: 已启动
     * STOPPED: 已停止
     */
    private String runtimeState;

    /**
     * 加载时间
     */
    private LocalDateTime loadTime;

    /**
     * 启动时间
     */
    private LocalDateTime startTime;

}

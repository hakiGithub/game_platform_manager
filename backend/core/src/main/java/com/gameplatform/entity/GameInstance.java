package com.gameplatform.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gameplatform.handler.JsonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 游戏实例实体类
 * 对应表: game_instance
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "game_instance", autoResultMap = true)
public class GameInstance extends BaseEntity {

    /**
     * 实例名称
     */
    private String instanceName;

    /**
     * 主机ID
     */
    private Long hostId;

    /**
     * 游戏ID
     */
    private Long gameId;

    /**
     * 游戏编码（冗余字段，便于查询）
     */
    private String gameCode;

    /**
     * 部署类型 docker/native
     */
    private String deployType;

    /**
     * 端口配置(JSON格式)
     */
    @TableField(typeHandler = JsonTypeHandler.class)
    private Map<String, Object> portConfig;

    /**
     * 运行状态 0-已停止 1-运行中 2-异常
     */
    private Integer runStatus;

    /**
     * 在线玩家数
     */
    private Integer onlinePlayers;

    /**
     * 配置信息(JSON格式)
     */
    @TableField(typeHandler = JsonTypeHandler.class)
    private Map<String, Object> configInfo;

    /**
     * 安装路径
     */
    private String installPath;

    /**
     * 启动命令
     */
    private String startCommand;

    /**
     * 停止命令
     */
    private String stopCommand;

    /**
     * 数据库配置(JSON格式)
     * 包含: type, host, port, database, username, password等
     */
    @TableField(typeHandler = JsonTypeHandler.class)
    private Map<String, Object> databaseConfig;

    /**
     * 存档路径
     */
    private String savePath;

    /**
     * 配置文件路径
     */
    private String configPath;

    /**
     * 最后备份时间
     */
    private LocalDateTime lastBackupTime;

    /**
     * 运行时元数据(JSON格式)
     * 存储部署后产生的动态信息：volumePaths、containerId、workDir、projectName、generatedAt
     */
    @TableField(typeHandler = JsonTypeHandler.class)
    private Map<String, Object> runtimeMetadata;

}

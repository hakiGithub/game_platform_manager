package com.gameplatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 游戏实例响应VO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "游戏实例响应VO")
public class InstanceVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 实例ID
     */
    @Schema(description = "实例ID")
    private Long id;

    /**
     * 实例名称
     */
    @Schema(description = "实例名称")
    private String instanceName;

    /**
     * 主机ID
     */
    @Schema(description = "主机ID")
    private Long hostId;

    /**
     * 主机名称
     */
    @Schema(description = "主机名称")
    private String hostName;

    /**
     * 主机IP地址
     */
    @Schema(description = "主机IP地址")
    private String hostIp;

    /**
     * 游戏ID
     */
    @Schema(description = "游戏ID")
    private Long gameId;

    /**
     * 游戏编码（用于插件匹配）
     */
    @Schema(description = "游戏编码")
    private String gameCode;

    /**
     * 游戏名称
     */
    @Schema(description = "游戏名称")
    private String gameName;

    /**
     * 游戏图标URL
     */
    @Schema(description = "游戏图标URL")
    private String iconUrl;

    /**
     * 部署类型 docker/native
     */
    @Schema(description = "部署类型")
    private String deployType;

    /**
     * 端口配置
     */
    @Schema(description = "端口配置")
    private Map<String, Object> portConfig;

    /**
     * 运行状态数字码（DeployAdapter.InstanceStatus 词汇表，见 ADR-0005）
     */
    @Schema(description = "运行状态数字码（InstanceStatus 词汇表，见 ADR-0005）")
    private Integer runStatus;

    /**
     * 运行状态描述（InstanceStatus.description 派生，中文文本唯一来源）
     */
    @Schema(description = "运行状态描述（InstanceStatus.description 派生）")
    private String runStatusDesc;

    /**
     * 在线玩家数
     */
    @Schema(description = "在线玩家数")
    private Integer onlinePlayers;

    /**
     * 配置信息
     */
    @Schema(description = "配置信息")
    private Map<String, Object> configInfo;

    /**
     * 运行时元数据（如容器 ID、项目名等，由部署适配器写入）
     */
    @Schema(description = "运行时元数据")
    private Map<String, Object> runtimeMetadata;

    /**
     * 安装路径
     */
    @Schema(description = "安装路径")
    private String installPath;

    /**
     * 启动命令
     */
    @Schema(description = "启动命令")
    private String startCommand;

    /**
     * 停止命令
     */
    @Schema(description = "停止命令")
    private String stopCommand;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @Schema(description = "状态字符串（前端使用）")
    private String status;

    @Schema(description = "部署任务ID（等于实例ID的字符串形式）")
    private String deployTaskId;

    /**
     * CPU使用率（百分比，0-100）
     */
    @Schema(description = "CPU使用率（百分比）")
    private Double cpuUsage;

    /**
     * 内存使用率（百分比，0-100）
     */
    @Schema(description = "内存使用率（百分比）")
    private Double memoryUsage;

    /**
     * 内存使用量文本，如 "120.5MiB / 2GiB"
     */
    @Schema(description = "内存使用量文本")
    private String memoryUsageText;

    /**
     * 运行时长（秒）
     */
    @Schema(description = "运行时长（秒）")
    private Long uptime;

}

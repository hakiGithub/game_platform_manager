package com.gameplatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 主机信息响应VO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "主机信息响应VO")
public class HostVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主机ID
     */
    @Schema(description = "主机ID")
    private Long id;

    /**
     * 主机名称
     */
    @Schema(description = "主机名称")
    private String name;

    /**
     * IP地址
     */
    @Schema(description = "IP地址")
    private String ip;

    /**
     * SSH端口
     */
    @Schema(description = "SSH端口")
    private Integer sshPort;

    /**
     * SSH用户名
     */
    @Schema(description = "SSH用户名")
    private String sshUsername;

    /**
     * 在线状态 0-离线 1-在线
     */
    @Schema(description = "在线状态 0-离线 1-在线")
    private Integer status;

    /**
     * 在线状态描述
     */
    @Schema(description = "在线状态描述")
    private String onlineStatusDesc;

    /**
     * 标签(JSON数组格式)
     */
    @Schema(description = "标签(JSON数组格式)")
    private String tags;

    /**
     * 操作系统类型
     */
    @Schema(description = "操作系统类型")
    private String osType;

    /**
     * 操作系统版本
     */
    @Schema(description = "操作系统版本")
    private String osVersion;

    /**
     * CPU核心数
     */
    @Schema(description = "CPU核心数")
    private Integer cpuCores;

    /**
     * 内存大小(MB)
     */
    @Schema(description = "内存大小(MB)")
    private Long memoryMb;

    /**
     * 磁盘大小(GB)
     */
    @Schema(description = "磁盘大小(GB)")
    private Long diskGb;

    /**
     * CPU使用率(百分比)
     */
    @Schema(description = "CPU使用率(百分比)")
    private BigDecimal cpuUsage;

    /**
     * 内存使用率(百分比)
     */
    @Schema(description = "内存使用率(百分比)")
    private BigDecimal memoryUsage;

    /**
     * 磁盘使用率(百分比)
     */
    @Schema(description = "磁盘使用率(百分比)")
    private BigDecimal diskUsage;

    /**
     * 最后检测时间
     */
    @Schema(description = "最后检测时间")
    private LocalDateTime lastCheckTime;

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

    /**
     * 获取在线状态描述
     */
    public String getOnlineStatusDesc() {
        if (status == null) {
            return "未知";
        }
        return status == 1 ? "在线" : "离线";
    }

}

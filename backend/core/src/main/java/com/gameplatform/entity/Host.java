package com.gameplatform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 主机信息实体类
 * 对应表: host_info
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("host_info")
public class Host extends BaseEntity {

    /**
     * 主机名称
     */
    private String hostName;

    /**
     * IP地址
     */
    private String ipAddress;

    /**
     * SSH端口
     */
    private Integer sshPort;

    /**
     * SSH用户名
     */
    private String sshUser;

    /**
     * SSH密码(加密存储)
     */
    private String sshPassword;

    /**
     * SSH私钥(加密存储)
     */
    private String sshPrivateKey;

    /**
     * 标签(JSON数组格式)
     */
    private String tags;

    /**
     * 在线状态 0-离线 1-在线
     */
    private Integer onlineStatus;

    /**
     * 操作系统类型
     */
    private String osType;

    /**
     * 操作系统版本
     */
    private String osVersion;

    /**
     * CPU核心数
     */
    private Integer cpuCores;

    /**
     * 内存大小(MB)
     */
    private Long memoryMb;

    /**
     * 磁盘大小(GB)
     */
    private Long diskGb;

    /**
     * CPU使用率(百分比)
     */
    private BigDecimal cpuUsage;

    /**
     * 内存使用率(百分比)
     */
    private BigDecimal memoryUsage;

    /**
     * 磁盘使用率(百分比)
     */
    private BigDecimal diskUsage;

    /**
     * 最后检测时间
     */
    private LocalDateTime lastCheckTime;

}

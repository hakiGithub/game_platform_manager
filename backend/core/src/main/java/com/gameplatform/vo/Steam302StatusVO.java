package com.gameplatform.vo;

import lombok.Data;

/**
 * Steam302 主机部署状态 VO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class Steam302StatusVO {

    /** 部署阶段：NOT_INSTALLED / STOPPED / RUNNING */
    private String phase;

    /** 容器状态原文（docker ps Status 列），未安装时为 null */
    private String containerStatus;

    /** 容器使用的镜像 */
    private String image;

    /** /etc/hosts 中 #S302 劫持条目数（-1 表示无法读取） */
    private Integer hostsEntries;

    /** Caddyfile 中当前代理的域名数（-1 表示无法读取） */
    private Integer proxiedDomains;

    /** CA 证书是否已安装到宿主机信任库 */
    private Boolean certTrusted;

    /** 容器共享加速是否开启（hosts 指向宿主机 LAN IP，bridge 容器可共享代理） */
    private Boolean containerShare;

    /** 当前 hosts 劫持条目的目标 IP（127.0.0.1 或宿主机 LAN IP） */
    private String targetIp;

    /** 展示用描述 */
    private String message;
}

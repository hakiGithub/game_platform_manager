package com.gameplatform.vo;

import lombok.Data;

import java.util.List;

/**
 * 宿主机 hosts 刷新执行结果
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class HostsRefreshResult {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 错误信息（失败时填）
     */
    private String errorMessage;

    /**
     * 备份路径（如 /etc/hosts.bak.20260718120000）
     */
    private String backupPath;

    /**
     * 实际修改的域名清单（无待改域名时为空列表）
     */
    private List<String> refreshedDomains;

    /**
     * 宿主机 LAN IP
     */
    private String hostLanIp;
}

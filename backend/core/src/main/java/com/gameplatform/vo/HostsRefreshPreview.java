package com.gameplatform.vo;

import lombok.Data;

import java.util.List;

/**
 * 宿主机 hosts 刷新预检结果
 *
 * <p>用于前端弹窗展示「将修改哪些域名」+ sudo 状态。</p>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class HostsRefreshPreview {

    /**
     * 宿主机 LAN IP（目标 IP，来源 host.ipAddress）
     */
    private String hostLanIp;

    /**
     * 主机名（用于排除 hostname 自身的条目）
     */
    private String hostname;

    /**
     * 待改域名清单（当前指向 127.0.0.1 且非系统别名、非 hostname、非已是 hostLanIp 的域名）
     */
    private List<String> domainsToRefresh;

    /**
     * 免密 sudo 是否可用（sudo -n true 检测结果）
     */
    private boolean sudoAvailable;

    /**
     * 是否需要 sudo 密码（!sudoAvailable 时为 true）
     */
    private boolean needsSudoPassword;
}

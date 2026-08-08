package com.gameplatform.plugin.l4d2.extension;

import lombok.Data;

import java.io.Serializable;

/**
 * L4D2 管理员业务数据。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class AdminSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实例ID */
    private Long instanceId;

    /** SteamID */
    private String steamId;

    /** 管理员权限标志 */
    private String adminFlags;

    /** 备注信息 */
    private String remark;

    /** 是否激活 */
    private Boolean isActive;
}

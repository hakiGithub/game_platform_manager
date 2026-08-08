package com.gameplatform.plugin.l4d2.vo.admin;

import lombok.Data;

/**
 * admins_simple.ini 单行条目。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class AdminEntry {
    /** SteamID（STEAM_0:1:xxx）或 IP */
    private String identity;
    /** flags（如 99:z） */
    private String flags;
    /** 备注（注释） */
    private String remark;
}

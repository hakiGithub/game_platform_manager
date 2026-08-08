package com.gameplatform.plugin.l4d2.vo.backup;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 备份内容聚合：插件列表 + admins_simple.ini + 服务器信息 + 服务器配置。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class BackupContent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 已启用的插件名列表（去除 .smx 后缀） */
    private List<String> enabledPlugins;

    /** admins_simple.ini 原文 */
    private String adminsIniContent;

    /** 服务器信息快照 */
    private ServerInfoSnapshot serverInfo;

    /** server.cfg 关键字段快照 */
    private ServerConfigSnapshot serverConfig;
}

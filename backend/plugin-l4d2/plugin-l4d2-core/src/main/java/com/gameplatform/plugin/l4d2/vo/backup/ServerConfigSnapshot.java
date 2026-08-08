package com.gameplatform.plugin.l4d2.vo.backup;

import lombok.Data;

import java.io.Serializable;

/**
 * server.cfg 关键字段快照。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class ServerConfigSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    /** sv_tags 值 */
    private String svTags;

    /** sv_allow_lobby_connect_only 值 */
    private String svAllowLobbyConnectOnly;

    /** sv_steamgroup 值 */
    private String svSteamgroup;

    /** 其他自定义配置原文（阶段 2 扩展） */
    private String customConfig;
}

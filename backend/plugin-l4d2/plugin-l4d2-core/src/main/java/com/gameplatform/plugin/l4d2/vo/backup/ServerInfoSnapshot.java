package com.gameplatform.plugin.l4d2.vo.backup;

import lombok.Data;

import java.io.Serializable;

/**
 * 服务器信息快照（hostname/motd/host）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class ServerInfoSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    /** hostname 文件内容 */
    private String hostname;

    /** motd 文件内容 */
    private String motd;

    /** host 文件内容 */
    private String host;
}

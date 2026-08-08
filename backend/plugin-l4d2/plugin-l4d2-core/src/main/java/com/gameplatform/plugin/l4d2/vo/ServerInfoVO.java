package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 服务器信息响应 VO（hostname/motd/host）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "服务器信息响应")
public class ServerInfoVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** hostname 文件内容 */
    @Schema(description = "hostname 文件内容")
    private String hostname;

    /** motd 文件内容 */
    @Schema(description = "motd 文件内容")
    private String motd;

    /** host 文件内容 */
    @Schema(description = "host 文件内容")
    private String host;
}

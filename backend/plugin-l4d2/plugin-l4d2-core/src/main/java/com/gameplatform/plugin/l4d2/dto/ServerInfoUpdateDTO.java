package com.gameplatform.plugin.l4d2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 服务器信息更新请求 DTO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "服务器信息更新请求")
public class ServerInfoUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实例ID */
    @Schema(description = "实例ID")
    private Long instanceId;

    /** hostname 文件内容（null 表示不更新） */
    @Schema(description = "hostname 文件内容")
    private String hostname;

    /** motd 文件内容（null 表示不更新） */
    @Schema(description = "motd 文件内容")
    private String motd;

    /** host 文件内容（null 表示不更新） */
    @Schema(description = "host 文件内容")
    private String host;
}

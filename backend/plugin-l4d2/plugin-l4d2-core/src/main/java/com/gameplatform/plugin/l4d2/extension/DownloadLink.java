package com.gameplatform.plugin.l4d2.extension;

import lombok.Data;

import java.io.Serializable;

/**
 * 下载链接信息。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class DownloadLink implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 下载渠道：百度网盘 / 迅雷云盘 / 天翼云盘 */
    private String channel;

    /** 分享链接 */
    private String shareUrl;

    /** 提取码 */
    private String accessCode;
}

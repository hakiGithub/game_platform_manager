package com.gameplatform.plugin.l4d2.extension;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 地图中心业务数据。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class MapSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 数据来源：ORANGE / WORKSHOP */
    private String source;

    /** 详情页完整 URL */
    private String sourceUrl;

    /** 中文名称 */
    private String titleCn;

    /** 英文名称 */
    private String titleEn;

    /** 地图大小（原始文本，如 834MB） */
    private String fileSize;

    /** 地图大小（字节） */
    private Long fileSizeBytes;

    /** 游戏模式列表，如 ["合作", "对抗"] */
    private List<String> gameModes;

    /** 关卡数量 */
    private Integer chapterCount;

    /** 地图类型，如 L4D2 */
    private String mapType;

    /** 作者（老地图可能为空） */
    private String author;

    /** 地图日期 */
    private String mapDate;

    /** VPK 文件名，如 earthcrashmy.vpk */
    private String vpkFileName;

    /** 星级评分（0-5） */
    private Double starRating;

    /** 平台支持 */
    private String platform;

    /** 语言 */
    private String language;

    /** 授权形式 */
    private String license;

    /** 地图简介 */
    private String description;

    /** 建图命令列表，如 ["map earthcrash_l4d2", "map 2_earthcrash_l4d2"] */
    private List<String> mapCommands;

    /** 缩略图 URL */
    private String thumbnailUrl;

    /** 截图 URL 列表 */
    private List<String> screenshotUrls;

    /** 下载链接列表 */
    private List<DownloadLink> downloadLinks;

    /** 浏览次数 */
    private Integer viewCount;

    /** 来源站点的更新时间（用于增量判断，如 2026-07-22） */
    private String sourceUpdateDate;

    /** 上次爬取时间戳 */
    private Long crawlTime;
}

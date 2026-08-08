package com.gameplatform.plugin.l4d2.crawler.dto;

import lombok.Data;

import java.util.List;

/**
 * 列表页解析出的地图摘要项。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class MapListItem {

    /** 从详情页URL提取的数字ID，如 "807" */
    private String sourceId;

    /** 详情页完整URL */
    private String detailUrl;

    /** 地图名称（中英文） */
    private String title;

    /** 缩略图URL */
    private String thumbnailUrl;

    /** 地图大小文本，如 "834MB" */
    private String fileSize;

    /** 地图模式列表，如 ["合作", "对抗"] */
    private List<String> gameModes;

    /** 关卡数文本，如 "4关" */
    private String chapterCountText;

    /** 地图类型，如 "L4D2" */
    private String mapType;

    /** 作者 */
    private String author;

    /** 星级文本，如 "★★★★☆" */
    private String starRatingText;

    /** 更新时间，如 "2026-07-22" */
    private String updateDate;

    /** 摘要文本 */
    private String summary;
}

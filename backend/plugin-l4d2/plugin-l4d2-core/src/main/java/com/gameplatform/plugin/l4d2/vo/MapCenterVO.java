package com.gameplatform.plugin.l4d2.vo;

import com.gameplatform.plugin.l4d2.extension.DownloadLink;
import lombok.Data;

import java.util.List;

/**
 * 地图中心展示对象。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class MapCenterVO {

    private String id;
    private String sourceId;
    private String source;
    private String sourceUrl;
    private String titleCn;
    private String titleEn;
    private String fileSize;
    private Long fileSizeBytes;
    private List<String> gameModes;
    private Integer chapterCount;
    private String mapType;
    private String author;
    private String mapDate;
    private String vpkFileName;
    private Double starRating;
    private String platform;
    private String language;
    private String license;
    private String description;
    private List<String> mapCommands;
    private String thumbnailUrl;
    private List<String> screenshotUrls;
    private List<DownloadLink> downloadLinks;
    private Integer viewCount;
    private String sourceUpdateDate;
    private Long crawlTime;
}

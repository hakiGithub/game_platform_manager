package com.gameplatform.plugin.l4d2.crawler.dto;

import com.gameplatform.plugin.l4d2.extension.DownloadLink;
import lombok.Data;

import java.util.List;

/**
 * 详情页解析出的地图详细信息。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class MapDetail {

    private String titleCn;
    private String titleEn;
    private String type;           // 合作/对抗
    private String language;
    private String fileSize;       // 834MB
    private String platform;
    private String addDate;
    private Double starRating;
    private Integer viewCount;
    private String license;
    private String description;
    private List<String> mapCommands;
    private String vpkFileName;
    private String author;
    private String mapDate;
    private List<DownloadLink> downloadLinks;
    private List<String> screenshotUrls;
}

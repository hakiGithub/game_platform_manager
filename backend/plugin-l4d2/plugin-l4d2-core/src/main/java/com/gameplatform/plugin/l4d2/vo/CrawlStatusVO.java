package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;

/**
 * 爬取任务状态对象。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class CrawlStatusVO {

    private String taskId;
    private String taskType;    // FULL / INCREMENTAL
    private String source;     // ORANGE
    private String status;     // RUNNING / COMPLETED / FAILED
    private Integer totalPages;
    private Integer processedPages;
    private Integer totalMaps;
    private Integer newMaps;
    private Integer updatedMaps;
    private Integer skippedMaps;
    private Integer failedMaps;
    private Long startTime;
    private Long endTime;
    private String errorMessage;
}

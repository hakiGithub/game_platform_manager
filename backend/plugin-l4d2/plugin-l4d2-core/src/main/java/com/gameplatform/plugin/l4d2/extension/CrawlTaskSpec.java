package com.gameplatform.plugin.l4d2.extension;

import lombok.Data;

import java.io.Serializable;

/**
 * 爬取任务业务数据。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class CrawlTaskSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 任务类型：FULL / INCREMENTAL */
    private String taskType;

    /** 来源：ORANGE / WORKSHOP */
    private String source;

    /** 状态：RUNNING / COMPLETED / FAILED */
    private String status;

    /** 总页数 */
    private Integer totalPages;

    /** 已处理页数 */
    private Integer processedPages;

    /** 总地图数 */
    private Integer totalMaps;

    /** 新增地图数 */
    private Integer newMaps;

    /** 更新地图数 */
    private Integer updatedMaps;

    /** 跳过地图数（未变化） */
    private Integer skippedMaps;

    /** 失败地图数 */
    private Integer failedMaps;

    /** 开始时间 */
    private Long startTime;

    /** 结束时间 */
    private Long endTime;

    /** 错误信息 */
    private String errorMessage;
}

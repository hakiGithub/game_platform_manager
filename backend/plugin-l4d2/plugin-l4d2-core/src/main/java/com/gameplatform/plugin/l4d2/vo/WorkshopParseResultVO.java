package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Workshop 解析结果视图对象（对齐源项目 workshop.go WorkshopParseResult）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "Workshop 解析结果")
public class WorkshopParseResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 源 Workshop ID（解析得到的纯数字 ID） */
    @Schema(description = "源 Workshop ID")
    private String sourceId;

    /** 可下载项列表（合集情况下可能多条） */
    @Schema(description = "可下载项列表")
    private List<WorkshopItemVO> items;
}

package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 通用链接解析结果视图对象（对齐源项目 link_parser.go LinkParseResult）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "通用链接解析结果")
public class LinkParseResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 源类型：workshop / unknown */
    @Schema(description = "源类型")
    private String sourceType;

    /** 源 ID（Workshop 情况下为 Workshop ID；其他情况为空） */
    @Schema(description = "源 ID")
    private String sourceId;

    /** 原始链接 */
    @Schema(description = "原始链接")
    private String originalLink;

    /** 可下载项列表 */
    @Schema(description = "可下载项列表")
    private List<LinkParseItemVO> items;
}

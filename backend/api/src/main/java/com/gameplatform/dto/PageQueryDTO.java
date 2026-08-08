package com.gameplatform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 分页查询DTO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "分页查询DTO")
public class PageQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前页码
     */
    @Schema(description = "当前页码", example = "1", defaultValue = "1")
    private Integer current = 1;

    /**
     * 每页数量
     */
    @Schema(description = "每页数量", example = "10", defaultValue = "10")
    private Integer size = 10;

    /**
     * 排序字段
     */
    @Schema(description = "排序字段", example = "create_time")
    private String orderBy;

    /**
     * 排序方式 asc/desc
     */
    @Schema(description = "排序方式", example = "desc", allowableValues = {"asc", "desc"})
    private String order = "desc";

    /**
     * 关键词搜索
     */
    @Schema(description = "关键词搜索")
    private String keyword;

}

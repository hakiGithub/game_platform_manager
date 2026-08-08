package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 地图列表响应 VO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "地图列表响应")
public class MapListVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 战役标题
     */
    @Schema(description = "战役标题")
    private String title;

    /**
     * VPK 文件名
     */
    @Schema(description = "VPK 文件名")
    private String vpkName;

    /**
     * 章节列表
     */
    @Schema(description = "章节列表")
    private List<ChapterVO> chapters;

    /**
     * 章节信息
     */
    @Data
    @Schema(description = "章节信息")
    public static class ChapterVO implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * 地图代码
         */
        @Schema(description = "地图代码", example = "c1m1_hotel")
        private String code;

        /**
         * 章节标题
         */
        @Schema(description = "章节标题", example = "旅馆")
        private String title;

        /**
         * 支持的游戏模式
         */
        @Schema(description = "支持的游戏模式", example = "[\"coop\", \"versus\"]")
        private List<String> modes;
    }
}

package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * VPK 战役任务信息
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class MissionInfoVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String vpkName;
    private String title;
    private List<ChapterVO> chapters;

    @Data
    public static class ChapterVO implements Serializable {
        private static final long serialVersionUID = 1L;
        private String code;
        private String title;
        private List<String> modes;
    }
}

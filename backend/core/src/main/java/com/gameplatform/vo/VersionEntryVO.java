package com.gameplatform.vo;

import lombok.Data;

import java.util.List;

/**
 * 版本目录条目在部署向导响应里的投影（design.md §16.4）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class VersionEntryVO {

    /** 版号，即提交时 {@code configInfo.deployVersion} 的值。 */
    private String versionId;

    /** 展示名，未声明时服务端回退为 {@code versionId}（PRD §8.1 字段默认值）。 */
    private String displayName;

    /** 是否默认条目：选中它等价于不写键（PRD §8.4.2 S2）。 */
    private Boolean isDefault;

    /** 步骤预览，只能在服务端算（步骤集解析顺序在 core 侧）。 */
    private List<StepSummaryVO> stepSummary;

    /**
     * 步骤预览行。{@code label} 原样透传声明值，其缺省回退词面归界面（ui-spec §6.2）；
     * {@code type} 是声明侧字面量，不进界面（ui-spec §6.4）。
     */
    @Data
    public static class StepSummaryVO {

        /** 声明序序号，自 1 起。 */
        private Integer index;

        private String label;

        private String type;

        private Boolean fatal;
    }
}

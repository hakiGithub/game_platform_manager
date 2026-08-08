package com.gameplatform.plugin.l4d2.vo.config;

import lombok.Data;

/**
 * SourceMod cfg 配置项。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class ConfigItem {
    /** 键名 */
    private String key;
    /** 当前值 */
    private String value;
    /** 默认值（来自 // Default: xxx） */
    private String defaultValue;
    /** 最小值（来自 // Min: xxx） */
    private Double min;
    /** 最大值（来自 // Max: xxx） */
    private Double max;
    /** 描述（来自 // 描述文本） */
    private String description;
    /** 行号（1-based） */
    private int lineNumber;
}

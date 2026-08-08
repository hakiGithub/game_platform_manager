package com.gameplatform.plugin.extension;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 插件配置字段定义
 * <p>
 * 插件通过 {@link GameEnhancementExtension#getConfigFields()} 声明配置项，
 * 框架在前端自动渲染对应的配置表单，并在插件加载时将配置值传递给插件。
 *
 * @author GamePlatform
 * @version 2.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginConfigField {

    /**
     * 字段键名（英文小写 + 下划线，如 "rcon_port"）
     */
    private String key;

    /**
     * 显示标签（如 "RCON 端口"）
     */
    private String label;

    /**
     * 字段类型
     */
    private FieldType type;

    /**
     * 默认值
     */
    private String defaultValue;

    /**
     * 是否必填
     */
    private boolean required;

    /**
     * 描述说明
     */
    private String description;

    /**
     * 可选值列表（用于 select 类型）
     */
    private List<String> options;

    /**
     * 校验正则表达式（可选）
     */
    private String validationPattern;

    /**
     * 配置字段类型枚举
     */
    public enum FieldType {
        TEXT,
        NUMBER,
        BOOLEAN,
        SELECT,
        PASSWORD,
        TEXTAREA
    }
}

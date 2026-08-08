package com.gameplatform.plugin.l4d2.dto;

import com.gameplatform.plugin.l4d2.vo.config.ConfigItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 更新 SourceMod 插件配置请求 DTO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class PluginConfigUpdateDTO {
    /** 实例ID */
    @NotNull
    private Long instanceId;
    /** 插件名称 */
    @NotBlank
    private String pluginName;
    /** 配置项列表 */
    @NotEmpty
    private List<ConfigItem> items;
}

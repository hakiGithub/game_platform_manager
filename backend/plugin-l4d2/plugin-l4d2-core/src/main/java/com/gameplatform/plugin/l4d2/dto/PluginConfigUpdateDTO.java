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
    /** 目标 cfg 的稳定文件 ID（优先于 configPath）。 */
    private String fileId;
    /**
     * 目标 cfg 路径或文件名。
     *
     * <p>可选；为空时保留旧行为，写第一个存在的候选文件。非空时必须属于该插件
     * 声明的候选文件集合，且不能通过路径穿越逃逸。
     */
    private String configPath;
}

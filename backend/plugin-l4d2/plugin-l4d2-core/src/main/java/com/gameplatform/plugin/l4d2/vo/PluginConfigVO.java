package com.gameplatform.plugin.l4d2.vo;

import com.gameplatform.plugin.l4d2.vo.config.ConfigItem;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SourceMod 插件配置响应 VO。
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@Data
public class PluginConfigVO {
    /** 插件名称 */
    private String pluginName;
    /** 兼容字段：首个可编辑 cfg 文件名 */
    private String configName;
    /** 兼容字段：首个可编辑 cfg 路径 */
    private String configPath;
    /** 兼容字段：首个可编辑 cfg 的配置项 */
    private List<ConfigItem> items;
    /** 兼容字段：首个可编辑 cfg 的原始内容 */
    private String rawContent;
    /** 兼容字段：首个可编辑 cfg 的最后同步时间 */
    private LocalDateTime lastSyncedAt;
    /** 插件的全部可编辑 cfg 文件 */
    private List<PluginConfigFileVO> configFiles;
}

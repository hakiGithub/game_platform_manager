package com.gameplatform.plugin.l4d2.vo;

import com.gameplatform.plugin.l4d2.vo.config.ConfigItem;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SourceMod 插件配置响应 VO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class PluginConfigVO {
    /** 插件名称 */
    private String pluginName;
    /** cfg 文件名 */
    private String configName;
    /** 相对 left4dead2 目录的路径 */
    private String configPath;
    /** 解析后的配置项 */
    private List<ConfigItem> items;
    /** 原始文件内容（GBK 解码后） */
    private String rawContent;
    /** 最后同步时间 */
    private LocalDateTime lastSyncedAt;
}

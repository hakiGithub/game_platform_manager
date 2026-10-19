package com.gameplatform.plugin.l4d2.vo;

import com.gameplatform.plugin.l4d2.vo.config.ConfigItem;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 单个 SourceMod cfg 文件的配置视图。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class PluginConfigFileVO {
    /** 稳定的候选文件标识，用于按文件保存 */
    private String fileId;
    /** cfg 文件名 */
    private String configName;
    /** 相对 left4dead2 目录的路径 */
    private String configPath;
    /** 解析后的配置项；读取失败时为空 */
    private List<ConfigItem> items;
    /** 原始文件内容（GBK 解码后） */
    private String rawContent;
    /** 文件是否存在 */
    private boolean exists;
    /** 读取状态：OK / EMPTY / MISSING / ERROR */
    private String status;
    /** 读取失败原因 */
    private String error;
    /** 最后同步时间 */
    private LocalDateTime lastSyncedAt;
}

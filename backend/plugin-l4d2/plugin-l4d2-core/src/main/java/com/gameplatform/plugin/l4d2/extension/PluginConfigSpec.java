package com.gameplatform.plugin.l4d2.extension;

import com.gameplatform.plugin.l4d2.vo.config.ConfigItem;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * L4D2 SourceMod 插件配置业务数据。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class PluginConfigSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实例ID */
    private Long instanceId;

    /** 主机ID */
    private Long hostId;

    /** 插件名称（.smx 文件名，不含扩展名） */
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

    // ===== 旧字段（保留兼容，不再使用） =====

    /** 插件状态（enabled/disabled） */
    private String pluginStatus;

    /** 描述 */
    private String description;

    /** 版本 */
    private String version;

    /** 作者 */
    private String author;

    /** 启用时间 */
    private String enableTime;

    /** 是否删除 */
    private Boolean isDeleted;

    /** 备注 */
    private String remark;
}

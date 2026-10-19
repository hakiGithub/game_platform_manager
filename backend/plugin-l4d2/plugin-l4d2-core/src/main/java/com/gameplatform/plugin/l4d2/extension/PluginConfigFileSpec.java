package com.gameplatform.plugin.l4d2.extension;

import com.gameplatform.plugin.l4d2.vo.config.ConfigItem;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 插件多文件配置快照中的单个 cfg 文件。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class PluginConfigFileSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    private String fileId;
    private String configName;
    private String configPath;
    private List<ConfigItem> items;
    private String rawContent;
    private boolean exists;
    private String status;
    private String error;
    private LocalDateTime lastSyncedAt;
}

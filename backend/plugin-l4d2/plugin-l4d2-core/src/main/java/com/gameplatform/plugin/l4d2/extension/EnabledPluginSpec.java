package com.gameplatform.plugin.l4d2.extension;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 已启用插件扩展资源业务数据。
 *
 * <p>物理表：ext_plugin_l4d2_enabledpluginresource（MODEL_ISOLATED 策略）。
 * 与 .enabled_plugins.yaml 双写，yaml 为事实来源，扩展资源用于前端快速查询。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class EnabledPluginSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实例ID */
    private Long instanceId;

    /** 主机ID */
    private Long hostId;

    /** 插件名（plugins_store 子目录名） */
    private String pluginName;

    /** 来源：panel / store / upload */
    private String source;

    /** 启用时间 */
    private LocalDateTime enabledAt;

    /** 启用时复制到游戏目录的文件列表（相对 left4dead2/） */
    private List<String> files = new ArrayList<>();
}

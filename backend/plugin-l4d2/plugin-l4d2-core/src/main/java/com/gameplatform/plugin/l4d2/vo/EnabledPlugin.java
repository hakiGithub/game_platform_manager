package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 已启用插件数据（对应 .enabled_plugins.yaml 中的条目）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class EnabledPlugin implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 插件名（plugins_store 子目录名） */
    private String name;

    /** 来源：panel / store / upload */
    private String source;

    /** 启用时间戳（毫秒） */
    private Long enabledAt;

    /** 启用时复制到游戏目录的文件列表（相对 left4dead2/） */
    private List<String> files = new ArrayList<>();
}

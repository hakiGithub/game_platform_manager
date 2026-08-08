package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 预设中的插件条目（对齐 l4d2-server-next PresetPlugin）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class PresetPlugin implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 插件名（plugins_store 子目录名） */
    private String name;

    /** 配置覆盖（每个 cfg 文件一个条目） */
    private List<PresetPluginConfig> configs = new ArrayList<>();
}

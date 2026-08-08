package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 预设插件配置覆盖（对齐 l4d2-server-next PresetPluginConfig）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class PresetPluginConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /** cfg 文件名（如 l4d2_ai_damagefix.cfg） */
    private String name;

    /** CVAR 键值对（key → value 字符串） */
    private Map<String, String> values = new LinkedHashMap<>();
}

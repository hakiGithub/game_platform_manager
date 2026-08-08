package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * L4D2 预设详情 VO（对齐 l4d2-server-next Preset）。
 *
 * <p>结构：name + desc + plugins[]（每个 plugin 含 configs[]）
 *
 * @author GamePlatform
 * @version 2.0.0
 */
@Data
public class PresetDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 预设 ID（英文标识，用于 URL） */
    private String id;

    /** 预设名（中文显示） */
    private String name;

    /** 描述 */
    private String description;

    /** 游戏模式（coop/versus/...） */
    private String gameMode;

    /** 最大玩家数 */
    private Integer maxPlayers;

    /** 平台插件名（空表示无平台插件） */
    private String platform;

    /** 插件列表（启用这些插件 + 应用 configs 覆盖） */
    private List<PresetPlugin> plugins = new ArrayList<>();
}

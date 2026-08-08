package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件元数据（对应 plugins_store/{name}/plugin.yaml）。
 *
 * <p>对齐 l4d2-server-next plugins.yaml 中的 plugin_sources map + 文件列表。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class PluginMeta implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 插件名（plugins_store 子目录名） */
    private String name;

    /** 来源：panel / store / upload */
    private String source;

    /** 版本 */
    private String version;

    /** 作者 */
    private String author;

    /** 描述（README 第一段） */
    private String description;

    /** 插件包含的所有文件（相对 left4dead2/，启用时复制目标） */
    private List<String> fileList = new ArrayList<>();

    /** 配置文件列表（相对 left4dead2/，用于前端配置编辑入口） */
    private List<String> configFiles = new ArrayList<>();

    /** 创建时间戳（毫秒） */
    private Long createdAt;

    /** 更新时间戳（毫秒） */
    private Long updatedAt;
}

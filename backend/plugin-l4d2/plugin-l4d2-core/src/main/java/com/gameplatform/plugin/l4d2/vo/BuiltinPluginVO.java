package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 内置插件清单项 VO（对应 builtin-plugins.yaml 中的一个条目）。
 *
 * <p>与 PluginStoreItemVO 区别：内置插件从 JAR classpath 读取，无需 GitHub API；
 * 此 VO 还携带 installed 字段，便于前端"内置插件市场"对话框展示安装状态。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "内置插件清单项")
public class BuiltinPluginVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 插件 ID（= ZIP 文件名去掉 .zip，= plugins_store 下的目录名） */
    @Schema(description = "插件ID")
    private String id;

    /** 展示名 */
    @Schema(description = "插件名称")
    private String name;

    /**
     * 分类：
     * <ul>
     *   <li>platform - 平台插件（SourceMod + Metamod 框架）</li>
     *   <li>required - 必选插件（基础功能依赖）</li>
     *   <li>optional - 可选插件（修复类）</li>
     *   <li>custom   - 自选插件（玩法增强）</li>
     * </ul>
     */
    @Schema(description = "分类：platform | required | optional | custom")
    private String category;

    /** builtin-plugins/ 目录下 ZIP 文件名 */
    @Schema(description = "ZIP 文件名")
    private String fileName;

    /** ZIP 文件大小（字节） */
    @Schema(description = "ZIP 大小（字节）")
    private Long size;

    /** 适用平台：linux | windows | all */
    @Schema(description = "适用平台：linux | windows | all")
    private String platform;

    /** 描述 */
    @Schema(description = "插件描述")
    private String description;

    /** 是否已安装到当前实例的 plugins_store（运行时填充，YAML 中不存在） */
    @Schema(description = "是否已安装")
    private Boolean installed;
}

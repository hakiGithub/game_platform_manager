package com.gameplatform.plugin.l4d2.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 构建信息响应 VO（对齐 plan §6.3.4）。
 *
 * <p>承载插件版本、Git commit、构建时间、JDK/PF4J/Spring Boot 版本等信息，
 * 由 {@code META-INF/build.properties}（Maven 资源过滤生成）+ 代码内运行时信息合并产生。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Schema(description = "构建信息响应")
public class BuildInfoVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 插件版本号 */
    @Schema(description = "插件版本号")
    private String version;

    /** Git commit hash（短） */
    @Schema(description = "Git commit hash（短）")
    private String commit;

    /** 构建时间（ISO 格式） */
    @Schema(description = "构建时间（ISO 格式）")
    private String buildTime;

    /** JDK 版本 */
    @Schema(description = "JDK 版本")
    private String jdkVersion;

    /** PF4J 框架版本 */
    @Schema(description = "PF4J 框架版本")
    private String pf4jVersion;

    /** 插件 ID（固定 plugin-l4d2） */
    @Schema(description = "插件 ID")
    private String pluginId;

    /** 插件描述 */
    @Schema(description = "插件描述")
    private String pluginDescription;

    /** Spring Boot 版本 */
    @Schema(description = "Spring Boot 版本")
    private String springBootVersion;
}

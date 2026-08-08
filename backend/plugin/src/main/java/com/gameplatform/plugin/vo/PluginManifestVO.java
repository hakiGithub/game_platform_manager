package com.gameplatform.plugin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 插件清单响应VO
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "插件清单响应VO")
public class PluginManifestVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 插件ID
     */
    @Schema(description = "插件ID")
    private String pluginId;

    /**
     * 游戏编码
     */
    @Schema(description = "游戏编码")
    private String gameCode;

    /**
     * 游戏名称
     */
    @Schema(description = "游戏名称")
    private String gameName;

    /**
     * 插件版本
     */
    @Schema(description = "插件版本")
    private String version;

    /**
     * 插件描述
     */
    @Schema(description = "插件描述")
    private String description;

    /**
     * 图标路径
     */
    @Schema(description = "图标路径")
    private String icon;

    /**
     * Wujie 子应用入口 URL
     * 例如: /api/plugins/l4d2/ui/index.html
     */
    @Schema(description = "Wujie 子应用入口 URL")
    private String frontendEntry;

    /**
     * 前端配置
     */
    @Schema(description = "前端配置")
    private FrontendConfig frontend;

    /**
     * API配置
     */
    @Schema(description = "API配置")
    private ApiConfig api;

    /**
     * 扩展点配置
     */
    @Schema(description = "扩展点配置")
    private Map<String, Object> extensions;

    /**
     * 前端配置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "前端配置")
    public static class FrontendConfig implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * 入口文件路径
         */
        @Schema(description = "入口文件路径")
        private String entry;

        /**
         * 路由配置
         */
        @Schema(description = "路由配置")
        private List<RouteConfig> routes;

        /**
         * 菜单配置
         */
        @Schema(description = "菜单配置")
        private List<MenuConfig> menus;

        /**
         * 资源文件列表
         */
        @Schema(description = "资源文件列表")
        private List<String> assets;
    }

    /**
     * 路由配置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "路由配置")
    public static class RouteConfig implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * 路由路径
         */
        @Schema(description = "路由路径")
        private String path;

        /**
         * 组件名称
         */
        @Schema(description = "组件名称")
        private String component;

        /**
         * 路由名称
         */
        @Schema(description = "路由名称")
        private String name;

        /**
         * 路由元信息
         */
        @Schema(description = "路由元信息")
        private Map<String, Object> meta;
    }

    /**
     * 菜单配置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "菜单配置")
    public static class MenuConfig implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * 菜单标题
         */
        @Schema(description = "菜单标题")
        private String title;

        /**
         * 菜单路径
         */
        @Schema(description = "菜单路径")
        private String path;

        /**
         * 图标
         */
        @Schema(description = "图标")
        private String icon;

        /**
         * 排序
         */
        @Schema(description = "排序")
        private Integer order;

        /**
         * 父菜单
         */
        @Schema(description = "父菜单")
        private String parent;

        /**
         * 是否要求选中实例后才渲染子应用
         * - true（默认）：必须携带 instanceId 才能进入页面，例如 RCON、地图管理
         * - false：纯资源浏览页，无需实例即可访问，例如地图中心
         * 前端依据此字段决定是否弹出实例选择对话框
         */
        @Schema(description = "是否要求选中实例")
        private Boolean requireInstance;
    }

    /**
     * API配置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "API配置")
    public static class ApiConfig implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * API基础路径
         */
        @Schema(description = "API基础路径")
        private String basePath;

        /**
         * API端点列表
         */
        @Schema(description = "API端点列表")
        private List<ApiEndpoint> endpoints;
    }

    /**
     * API端点
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "API端点")
    public static class ApiEndpoint implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * 端点路径
         */
        @Schema(description = "端点路径")
        private String path;

        /**
         * HTTP方法
         */
        @Schema(description = "HTTP方法")
        private String method;

        /**
         * 描述
         */
        @Schema(description = "描述")
        private String description;
    }

}

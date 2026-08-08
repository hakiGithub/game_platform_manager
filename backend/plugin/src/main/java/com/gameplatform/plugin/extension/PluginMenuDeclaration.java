package com.gameplatform.plugin.extension;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 插件菜单声明对象（ADR-0001）
 * <p>
 * 插件通过 {@link GameEnhancementExtension#getMenus()} 返回此对象列表。
 * 主应用 PluginFrameworkServiceImpl 拼装 manifest 时读取，序列化为
 * PluginManifestVO.MenuConfig 返回给前端。
 *
 * <h3>字段约束</h3>
 * <ul>
 *   <li>{@code path} 同插件内必须唯一，重复时主应用抛 IllegalStateException</li>
 *   <li>{@code requireInstance} 默认 true；纯资源页（如地图中心）显式设 false</li>
 *   <li>{@code icon} 当前为 Element Plus 图标组件名（如 "Monitor"）</li>
 * </ul>
 *
 * @author GamePlatform
 * @since 2.1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginMenuDeclaration {

    /** 菜单标题（前端展示文案） */
    private String title;

    /** 菜单路径（子应用前端路由，如 "/rcon"）；同插件内必须唯一 */
    private String path;

    /** 图标（Element Plus 图标组件名，如 "Monitor"） */
    private String icon;

    /** 排序值（升序） */
    private Integer order;

    /** 父菜单路径（用于二级菜单分组，可空） */
    private String parent;

    /**
     * 是否要求选中实例后才渲染子应用
     * - true（默认）：必须携带 instanceId 才能进入页面，如 RCON、地图管理
     * - false：纯资源浏览页，无需实例即可访问，如地图中心
     */
    @Builder.Default
    private Boolean requireInstance = Boolean.TRUE;
}

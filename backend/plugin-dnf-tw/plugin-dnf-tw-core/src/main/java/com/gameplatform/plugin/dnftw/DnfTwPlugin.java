package com.gameplatform.plugin.dnftw;

import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;

/**
 * dnf-tw 插件主类（design.md §3.3 组 J、§7.3 B-14；PRD FR-22 / AC-23 ①）。
 *
 * <p>最小承载形状：只提供 {@link DnfTwExtension} 这一个扩展点实现，
 * 不含控制器 / 任务处理器 / 菜单 / 持久化模型 / 生命周期钩子，
 * 也<b>不含插件前端</b>（FR-22、AC-23 ④；ADR-0003 已废弃 standalone 模式，新插件不得再实现）。</p>
 *
 * <p>本期 OP-02 裁决下本模块恒处「框架 + 版本目录占位模板，不可上线」态：
 * 装载它不改变 dnf-tw 的任何现有行为（AC-24 ④）。</p>
 */
public class DnfTwPlugin extends Plugin {

    public DnfTwPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }
}

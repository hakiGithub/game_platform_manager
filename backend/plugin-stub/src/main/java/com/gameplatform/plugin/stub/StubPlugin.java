package com.gameplatform.plugin.stub;

import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;

/**
 * 验收桩插件主类（design.md §3.3 组 K / PRD FR-24）。
 *
 * <p>本类与 {@link StubExtension} 一起构成<b>验收资产</b>：只用来证明扩展声明框架的通用性
 * （G-01 / AC-25），不含任何游戏语义，不单列为产品模块，不进产品发布物（BR-15 ②、AC-26 ②）。
 * 与 {@code plugin-dnf-tw} 不得混放（AC-26 ③）。</p>
 */
public class StubPlugin extends Plugin {

    public StubPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }
}

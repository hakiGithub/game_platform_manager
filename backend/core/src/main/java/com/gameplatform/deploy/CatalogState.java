package com.gameplatform.deploy;

/**
 * 版本目录的读取结果状态（design.md §16.3）。
 *
 * <p>四态必须互相可区分：{@code EMPTY}（读取成功但 0 条目）与 {@code INVALID}
 * （有条目但未通过校验）若塌成一态，界面上就会把「该游戏没有版本」说成
 * 「该游戏的声明不合法」（RISK-13 / AC-24 ③）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public enum CatalogState {

    /** 无插件 / 插件未加载 / SPI 抛异常：目录无从谈起 */
    ABSENT,

    /** 读取成功、0 条目（dnf-tw 本期态） */
    EMPTY,

    /** 有条目但任一不合规 ⇒ 整目录不可用，禁止部分采纳 */
    INVALID,

    /** 合法且 ≥1 条目 */
    AVAILABLE
}

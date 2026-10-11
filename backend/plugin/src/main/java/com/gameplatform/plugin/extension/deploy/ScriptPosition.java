package com.gameplatform.plugin.extension.deploy;

/**
 * 脚本步骤的执行位置（design.md §16.2、§14.5）。
 *
 * <p>本期只交付宿主机（SSH）执行；{@link #CONTAINER} 保留为声明形状，
 * 由主应用在声明读取期判为不合法（design.md §14.5.1 三条独立依据、校验规则 N4）。</p>
 */
public enum ScriptPosition {

    /** 宿主机，经 SSH 执行（本期唯一合法取值，缺省即此） */
    HOST,

    /** 容器内执行（本期不支持，声明即不合法） */
    CONTAINER
}

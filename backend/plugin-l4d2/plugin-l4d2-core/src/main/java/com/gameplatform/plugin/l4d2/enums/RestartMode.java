package com.gameplatform.plugin.l4d2.enums;

/**
 * L4D2 服务器重启模式。
 *
 * <p>对齐源项目 {@code controller/restart.go}：
 * <ul>
 *   <li>{@link #AUTO} —— 按配置决定（{@code byRcon=true} 走 RCON，{@code false} 走命令）</li>
 *   <li>{@link #RCON} —— 强制通过 RCON 协议发送 {@code _restart}</li>
 *   <li>{@link #COMMAND} —— 强制通过 shell 执行 {@code docker restart} 或自定义命令</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public enum RestartMode {
    /** 按配置决定：byRcon=true→RCON，false→COMMAND */
    AUTO,
    /** 强制 RCON 模式 */
    RCON,
    /** 强制命令模式 */
    COMMAND
}

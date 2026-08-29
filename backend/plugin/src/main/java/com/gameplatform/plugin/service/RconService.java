package com.gameplatform.plugin.service;

import java.time.Duration;

/**
 * RCON 宿主能力服务（ADR-0016）。
 * <p>
 * 主应用统一管理的 Source RCON 传输层：插件按实例 ID 执行 RCON 命令，
 * 端点解析（标准键 {@code configInfo.rconPort}/{@code rconPassword}）、
 * 连接池与认证由主应用负责。密码不可由插件指定。
 * <p>
 * 每次执行自动携带调用方（插件 ID）写入统一审计日志，来源不可伪造。
 * 仅提供传输能力；游戏命令语义（status 解析、kick/ban 语法等）由插件自行实现。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface RconService {

    /**
     * 执行 RCON 命令（默认读超时）。
     *
     * @param instanceId 实例 ID
     * @param command    要执行的命令
     * @return 命令原始输出文本
     * @throws com.gameplatform.common.exception.BusinessException 实例不存在、端点不可达或连接/通信失败
     */
    String executeCommand(long instanceId, String command);

    /**
     * 执行 RCON 命令（自定义读超时）。
     *
     * @param instanceId 实例 ID
     * @param command    要执行的命令
     * @param timeout    本次执行的响应等待上限；为 null 时使用默认超时
     * @return 命令原始输出文本
     * @throws com.gameplatform.common.exception.BusinessException 实例不存在、端点不可达或连接/通信失败
     */
    String executeCommand(long instanceId, String command, Duration timeout);

    /**
     * 测试 RCON 连通性（建连 + 认证，不执行业务命令）。
     *
     * @param instanceId 实例 ID
     * @return 认证成功返回 true
     */
    boolean testConnection(long instanceId);
}

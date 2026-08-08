package com.gameplatform.plugin.service;

import com.gameplatform.vo.InstanceVO;

import java.util.List;

/**
 * 实例查询与控制服务。
 * <p>
 * 提供给插件使用的游戏实例相关能力，包括实例查询、状态获取与生命周期控制。
 * 不包含实例创建/更新/删除等管理操作（属宿主核心职责）。
 * <p>
 * 实现由宿主核心模块提供，通过插件 Spring 子容器注入。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface InstanceQueryService {

    /**
     * 根据实例 ID 查询实例详情。
     *
     * @param id 实例 ID
     * @return 实例视图对象；若不存在返回 null
     */
    InstanceVO getInstanceById(Long id);

    /**
     * 根据主机 ID 查询该主机下的所有实例。
     *
     * @param hostId 主机 ID
     * @return 实例列表；无数据时返回空列表
     */
    List<InstanceVO> getInstancesByHostId(Long hostId);

    /**
     * 根据游戏 ID 查询该游戏下的所有实例。
     *
     * @param gameId 游戏 ID
     * @return 实例列表；无数据时返回空列表
     */
    List<InstanceVO> getInstancesByGameId(Long gameId);

    /**
     * 根据游戏编码查询该游戏下的所有实例。
     *
     * @param gameCode 游戏编码（如 l4d2）
     * @return 实例列表；无数据时返回空列表
     */
    List<InstanceVO> listByGameCode(String gameCode);

    /**
     * 获取实例运行状态（刷新并返回最新状态）。
     *
     * @param id 实例 ID
     * @return 实例视图对象（含最新状态）；若不存在返回 null
     */
    InstanceVO getInstanceStatus(Long id);

    /**
     * 启动指定实例。
     *
     * @param id 实例 ID
     * @return 是否启动成功
     */
    boolean startInstance(Long id);

    /**
     * 停止指定实例。
     *
     * @param id 实例 ID
     * @return 是否停止成功
     */
    boolean stopInstance(Long id);

    /**
     * 重启指定实例。
     *
     * @param id 实例 ID
     * @return 是否重启成功
     */
    boolean restartInstance(Long id);

    /**
     * 获取实例最近日志。
     *
     * @param id    实例 ID
     * @param lines 日志行数
     * @return 日志文本内容
     */
    String getInstanceLogs(Long id, int lines);

    /**
     * 在实例控制台执行命令。
     *
     * @param id      实例 ID
     * @param command 待执行命令
     * @return 命令执行输出
     */
    String executeCommand(Long id, String command);
}

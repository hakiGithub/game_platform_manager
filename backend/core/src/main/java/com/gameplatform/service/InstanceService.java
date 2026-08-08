package com.gameplatform.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gameplatform.common.result.PageResult;
import com.gameplatform.dto.*;
import com.gameplatform.vo.InstanceVO;

import java.util.List;

/**
 * 游戏实例服务接口
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface InstanceService {

    /**
     * 创建游戏实例
     *
     * @param dto 实例创建DTO
     * @return 实例VO
     */
    InstanceVO createInstance(InstanceCreateDTO dto);

    /**
     * 更新游戏实例
     *
     * @param dto 实例更新DTO
     * @return 实例VO
     */
    InstanceVO updateInstance(InstanceUpdateDTO dto);

    /**
     * 删除游戏实例
     *
     * @param id 实例ID
     */
    void deleteInstance(Long id);

    /**
     * 根据ID查询实例
     *
     * @param id 实例ID
     * @return 实例VO
     */
    InstanceVO getInstanceById(Long id);

    /**
     * 查询实例的动态资源数据（CPU/内存/运行时长等）。
     * <p>
     * 该接口从适配器 getDetails 拉取实时数据，可能涉及 SSH/Docker 调用，响应较慢。
     * 前端应异步调用此接口，不要阻塞静态信息展示。
     *
     * @param id 实例ID
     * @return 动态数据 Map，包含 cpuUsage/memoryUsage/memoryUsageText/uptime 等字段
     */
    java.util.Map<String, Object> getInstanceMetrics(Long id);

    /**
     * 分页查询实例
     *
     * @param queryDTO 查询DTO
     * @return 分页结果
     */
    PageResult<InstanceVO> pageInstances(PageQueryDTO queryDTO);

    /**
     * 根据主机ID查询实例
     *
     * @param hostId 主机ID
     * @return 实例列表
     */
    List<InstanceVO> getInstancesByHostId(Long hostId);

    /**
     * 根据游戏ID查询实例
     *
     * @param gameId 游戏ID
     * @return 实例列表
     */
    List<InstanceVO> getInstancesByGameId(Long gameId);

    /**
     * 根据游戏编码查询实例
     *
     * @param gameCode 游戏编码（如 l4d2）
     * @return 实例列表
     */
    List<InstanceVO> getInstancesByGameCode(String gameCode);

    /**
     * 启动实例
     *
     * @param id 实例ID
     * @return 是否成功
     */
    boolean startInstance(Long id);

    /**
     * 停止实例
     *
     * @param id 实例ID
     * @return 是否成功
     */
    boolean stopInstance(Long id);

    /**
     * 重启实例
     *
     * @param id 实例ID
     * @return 是否成功
     */
    boolean restartInstance(Long id);

    /**
     * 获取实例状态
     *
     * @param id 实例ID
     * @return 实例VO
     */
    InstanceVO getInstanceStatus(Long id);

    /**
     * 获取实例日志
     *
     * @param id    实例ID
     * @param lines 行数
     * @return 日志内容
     */
    String getInstanceLogs(Long id, int lines);

    /**
     * 执行实例命令
     *
     * @param id      实例ID
     * @param command 命令
     * @return 执行结果
     */
    String executeCommand(Long id, String command);

    /**
     * 重试部署（仅限异常状态实例）
     * @param id 实例ID
     */
    void retryDeploy(Long id);

    /**
     * 恢复中断的部署任务（将 run_status=5 的实例标记为异常）
     * @return 恢复的实例数量
     */
    int recoverDeployingInstances();

}

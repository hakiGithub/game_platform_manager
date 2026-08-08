package com.gameplatform.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gameplatform.common.result.PageResult;
import com.gameplatform.dto.*;
import com.gameplatform.util.SshUtil;
import com.gameplatform.vo.HostResourceVO;
import com.gameplatform.vo.HostVO;
import com.gameplatform.vo.LoginVO;

import java.util.List;

/**
 * 主机服务接口
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface HostService {

    /**
     * 创建主机
     *
     * @param dto 主机创建DTO
     * @return 主机VO
     */
    HostVO createHost(HostCreateDTO dto);

    /**
     * 更新主机
     *
     * @param dto 主机更新DTO
     * @return 主机VO
     */
    HostVO updateHost(HostUpdateDTO dto);

    /**
     * 删除主机
     *
     * @param id 主机ID
     */
    void deleteHost(Long id);

    /**
     * 根据ID查询主机
     *
     * @param id 主机ID
     * @return 主机VO
     */
    HostVO getHostById(Long id);

    /**
     * 分页查询主机
     *
     * @param queryDTO 查询DTO
     * @return 分页结果
     */
    PageResult<HostVO> pageHosts(PageQueryDTO queryDTO);

    /**
     * 查询所有在线主机
     *
     * @return 主机列表
     */
    List<HostVO> getOnlineHosts();

    /**
     * 测试主机连接
     *
     * @param id 主机ID
     * @return 是否连接成功
     */
    boolean testConnection(Long id);

    /**
     * 刷新主机状态
     *
     * @param id 主机ID
     */
    void refreshStatus(Long id);

    /**
     * 刷新所有主机状态
     * 用于定时任务批量更新主机资源信息
     */
    void refreshAllHostsStatus();

    /**
     * 获取主机详细资源信息
     * 包含CPU、内存、磁盘、网络的详细信息
     *
     * @param id 主机ID
     * @return 主机资源信息VO
     */
    HostResourceVO getHostResourceInfo(Long id);

    /**
     * 检查主机端口占用情况
     *
     * @param id   主机ID
     * @param port 端口号
     * @return 端口检查结果
     */
    SshUtil.PortCheckResult checkPort(Long id, int port);

}

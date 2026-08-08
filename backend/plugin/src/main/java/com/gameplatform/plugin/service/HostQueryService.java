package com.gameplatform.plugin.service;

import com.gameplatform.vo.HostResourceVO;
import com.gameplatform.vo.HostVO;

/**
 * 主机信息查询服务。
 * <p>
 * 提供给插件使用的主机相关查询能力，包括主机详情与资源监控信息。
 * <p>
 * 实现由宿主核心模块提供，通过插件 Spring 子容器注入。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface HostQueryService {

    /**
     * 获取主机资源监控信息（CPU/内存/磁盘/网络）。
     *
     * @param hostId 主机 ID
     * @return 主机资源信息视图对象；若不存在返回 null
     */
    HostResourceVO getHostResourceInfo(Long hostId);

    /**
     * 获取主机详情（IP/端口/SSH 凭据等基础信息）。
     *
     * @param hostId 主机 ID
     * @return 主机视图对象；若不存在返回 null
     */
    HostVO getHostById(Long hostId);
}

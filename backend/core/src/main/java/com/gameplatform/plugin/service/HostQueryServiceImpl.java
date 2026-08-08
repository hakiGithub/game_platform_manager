package com.gameplatform.plugin.service;

import com.gameplatform.service.HostService;
import com.gameplatform.vo.HostResourceVO;
import com.gameplatform.vo.HostVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 主机信息查询服务实现。
 * <p>
 * 委托转发至 {@link HostService}，不重复业务逻辑。
 * 通过 @Service 注册到主容器，再由 {@code PluginSpringContextFactory}
 * 注入到插件子容器供插件使用。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HostQueryServiceImpl implements HostQueryService {

    private final HostService hostService;

    @Override
    public HostResourceVO getHostResourceInfo(Long hostId) {
        return hostService.getHostResourceInfo(hostId);
    }

    @Override
    public HostVO getHostById(Long hostId) {
        return hostService.getHostById(hostId);
    }
}

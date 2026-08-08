package com.gameplatform.plugin.service;

import com.gameplatform.service.InstanceService;
import com.gameplatform.vo.InstanceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 实例查询与控制服务实现。
 * <p>
 * 委托转发至 {@link InstanceService}，不重复业务逻辑。
 * 通过 @Service 注册到主容器，再由 {@code PluginSpringContextFactory}
 * 注入到插件子容器供插件使用。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstanceQueryServiceImpl implements InstanceQueryService {

    private final InstanceService instanceService;

    @Override
    public InstanceVO getInstanceById(Long id) {
        return instanceService.getInstanceById(id);
    }

    @Override
    public List<InstanceVO> getInstancesByHostId(Long hostId) {
        return instanceService.getInstancesByHostId(hostId);
    }

    @Override
    public List<InstanceVO> getInstancesByGameId(Long gameId) {
        return instanceService.getInstancesByGameId(gameId);
    }

    @Override
    public List<InstanceVO> listByGameCode(String gameCode) {
        return instanceService.getInstancesByGameCode(gameCode);
    }

    @Override
    public InstanceVO getInstanceStatus(Long id) {
        return instanceService.getInstanceStatus(id);
    }

    @Override
    public boolean startInstance(Long id) {
        return instanceService.startInstance(id);
    }

    @Override
    public boolean stopInstance(Long id) {
        return instanceService.stopInstance(id);
    }

    @Override
    public boolean restartInstance(Long id) {
        return instanceService.restartInstance(id);
    }

    @Override
    public String getInstanceLogs(Long id, int lines) {
        return instanceService.getInstanceLogs(id, lines);
    }

    @Override
    public String executeCommand(Long id, String command) {
        return instanceService.executeCommand(id, command);
    }
}

package com.gameplatform.hostaccess;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 主机作用域操作执行器：统一加载主机、执行操作并对账在线状态。
 */
@Component
@RequiredArgsConstructor
public class HostSession {

    private final HostMapper hostMapper;
    private final RemoteHost remoteHost;

    @FunctionalInterface
    public interface HostOperation<T> {
        T execute(Host host, RemoteHost remoteHost);
    }

    @FunctionalInterface
    public interface HostProbe<T> {
        T execute(Host host, RemoteHost remoteHost);

        default boolean isReachable(T result) {
            return Boolean.TRUE.equals(result);
        }
    }

    /**
     * 操作正常返回即视为主机可达；只有 {@link HostAccessException} 标离线，
     * 业务命令的非零退出码仍按正常结果处理。
     */
    public <T> T run(Long hostId, HostOperation<T> operation) {
        Host host = requireHost(hostId);
        try {
            T result = operation.execute(host, remoteHost);
            if (result instanceof ResourceInfo info && !info.isSuccess()) {
                throw new HostAccessException("主机资源查询失败: "
                        + (info.getError() == null || info.getError().isBlank()
                        ? "主机不可达" : info.getError()));
            }
            hostMapper.updateOnlineStatus(hostId, 1);
            return result;
        } catch (HostAccessException e) {
            hostMapper.updateOnlineStatus(hostId, 0);
            throw e;
        }
    }

    /** 探测类操作显式返回可达性；传输异常与可达性 false 都写离线。 */
    public <T> T probe(Long hostId, HostProbe<T> probe) {
        Host host = requireHost(hostId);
        try {
            T result = probe.execute(host, remoteHost);
            hostMapper.updateOnlineStatus(hostId, probe.isReachable(result) ? 1 : 0);
            return result;
        } catch (HostAccessException e) {
            hostMapper.updateOnlineStatus(hostId, 0);
            throw e;
        }
    }

    public Host requireHost(Long hostId) {
        Host host = hostMapper.selectById(hostId);
        if (host == null) {
            throw new BusinessException("主机不存在");
        }
        return host;
    }
}

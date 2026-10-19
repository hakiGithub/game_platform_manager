package com.gameplatform.hostaccess;

/** 主机级 SSH 隧道清理能力。HostService 只依赖此接口，不依赖插件容器实现。 */
public interface HostTunnelLifecycle {

    int closeAllForHost(Long hostId);
}

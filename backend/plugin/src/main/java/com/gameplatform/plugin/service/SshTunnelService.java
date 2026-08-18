package com.gameplatform.plugin.service;

/**
 * SSH 隧道服务（ADR-0009，宿主能力服务）
 * <p>
 * 为插件提供本地端口转发能力：在平台本地绑定回环端口，经 SSH 隧道转发到
 * SSH 主机可达的远端目标（如实例容器内 / 宿主机映射的 MySQL）。
 * <p>
 * 两种凭据来源：
 * <ul>
 *   <li>{@link #openByHost(Long, String, int)}：使用平台已登记主机的凭据
 *       （复用平台 SSH 连接池，会话被隧道钉住防止空闲回收）</li>
 *   <li>{@link #openWithCredentials(SshEndpoint, String, int)}：使用插件自带凭据
 *       （专用 SSH 会话，宿主不落库、不写日志）</li>
 * </ul>
 * <p>
 * 生命周期约定：
 * <ul>
 *   <li>去重键为 (ownerPluginId, 凭据来源, remoteHost, remotePort)：同插件对同一目标
 *       重复 open 返回同一 {@link TunnelHandle} 并叠加引用计数，跨插件不共享</li>
 *   <li>{@link #close(TunnelHandle)} 幂等：引用计数减至 0 才真正关闭隧道</li>
 *   <li>插件 onUnload 主动 close 是加速路径；插件 stop/unload 时宿主会强制关闭
 *       该插件的全部句柄（兜底）</li>
 *   <li>宿主删除主机时，该 hostId 开出的全部平台凭据隧道将被联动关闭</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface SshTunnelService {

    /**
     * 用平台已登记主机的凭据开隧道
     *
     * @param hostId     平台已登记主机 ID
     * @param remoteHost 远端目标主机（从 SSH 主机视角可达，如 127.0.0.1 表示主机本机回环）
     * @param remotePort 远端目标端口
     * @return 隧道句柄（含已绑定的本地回环端口）
     * @throws com.gameplatform.common.exception.BusinessException 主机不存在或 SSH 建连/转发失败
     */
    TunnelHandle openByHost(Long hostId, String remoteHost, int remotePort);

    /**
     * 用调用方自带凭据开隧道（插件连接档案场景）
     * <p>
     * 凭据仅用于本次建连，宿主不持久化、不写日志。
     *
     * @param ssh        SSH 端点凭据
     * @param remoteHost 远端目标主机（从该 SSH 主机视角可达）
     * @param remotePort 远端目标端口
     * @return 隧道句柄（含已绑定的本地回环端口）
     * @throws com.gameplatform.common.exception.BusinessException SSH 建连/认证或转发失败
     */
    TunnelHandle openWithCredentials(SshEndpoint ssh, String remoteHost, int remotePort);

    /**
     * 关闭隧道（幂等）
     * <p>
     * 引用计数减 1；减至 0 时真正关闭本地端口转发并释放 SSH 会话资源。
     * 只能关闭本插件（handle.ownerPluginId 匹配）的句柄。
     *
     * @param handle 此前 open 返回的句柄；为 null 时直接返回
     */
    void close(TunnelHandle handle);

    /**
     * SSH 端点凭据（插件自带凭据场景）
     * <p>
     * 注意：toString 已脱敏，凭据不会出现在日志中。
     */
    record SshEndpoint(String host, int port, String user, String password, String privateKey) {

        public SshEndpoint {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("SSH host 不能为空");
            }
            if (user == null || user.isBlank()) {
                throw new IllegalArgumentException("SSH user 不能为空");
            }
        }

        /** 端口缺省 22 */
        public SshEndpoint(String host, String user, String password) {
            this(host, 22, user, password, null);
        }

        @Override
        public String toString() {
            // 凭据脱敏：禁止密码/私钥进入日志
            return "SshEndpoint[host=" + host + ", port=" + port + ", user=" + user
                    + ", password=" + (password == null ? "null" : "***") + ", privateKey="
                    + (privateKey == null ? "null" : "***") + "]";
        }
    }

    /**
     * 隧道句柄
     * <p>
     * localPort 为平台本地已绑定的回环端口（仅 127.0.0.1 可达），
     * 插件连接 127.0.0.1:localPort 即等于连接 remoteHost:remotePort。
     */
    record TunnelHandle(String id, int localPort, String remoteHost, int remotePort,
                        String ownerPluginId) {
    }
}

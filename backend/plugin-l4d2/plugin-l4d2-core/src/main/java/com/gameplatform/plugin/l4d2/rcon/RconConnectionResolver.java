package com.gameplatform.plugin.l4d2.rcon;

import com.gameplatform.vo.HostVO;
import com.gameplatform.vo.InstanceVO;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * RCON 连接端点解析器。
 * <p>
 * 纯逻辑单元，从 InstanceVO + HostVO 解析出 (host, port, password)。
 * 兼容多部署类型与字段命名差异。无 I/O，无状态。
 */
@Component
public class RconConnectionResolver {

    /** 默认 RCON 端口（与 L4D2 docker-compose 实际部署一致） */
    private static final int DEFAULT_RCON_PORT = 27015;

    /**
     * 解析实例的 RCON 端点。
     *
     * @param instance 实例（含 configInfo、portConfig、deployType）
     * @param host     主机（含 ip）；为 null 时返回 empty
     * @return RCON 端点；配置缺失或端口未映射时返回 empty
     */
    public Optional<RconEndpoint> resolve(InstanceVO instance, HostVO host) {
        if (instance == null || host == null || host.getIp() == null || host.getIp().isBlank()) {
            return Optional.empty();
        }

        Map<String, Object> configInfo = instance.getConfigInfo();
        Map<String, Object> portConfig = instance.getPortConfig();

        int port = resolvePort(configInfo, portConfig);
        String password = resolvePassword(configInfo);

        if (password == null || password.isBlank()) {
            return Optional.empty();
        }

        if (!isPortMapped(instance, port)) {
            return Optional.empty();
        }

        RconEndpoint endpoint = new RconEndpoint(host.getIp(), port, password);
        return endpoint.isValid() ? Optional.of(endpoint) : Optional.empty();
    }

    /**
     * 三级回退解析端口：configInfo.rconPort → portConfig.rcon → 默认 27015
     */
    private int resolvePort(Map<String, Object> configInfo, Map<String, Object> portConfig) {
        if (configInfo != null) {
            Object port = configInfo.get("rconPort");
            if (port instanceof Number n) {
                return n.intValue();
            }
        }
        if (portConfig != null) {
            Object port = portConfig.get("rcon");
            if (port instanceof Number n) {
                return n.intValue();
            }
        }
        return DEFAULT_RCON_PORT;
    }

    /**
     * 三级回退解析密码：configInfo.rconPassword → L4D2_RCON_PASSWORD → SRCDS_RCONPW
     */
    private String resolvePassword(Map<String, Object> configInfo) {
        if (configInfo == null) return null;
        Object pwd = configInfo.get("rconPassword");
        if (pwd != null && !pwd.toString().isBlank()) return pwd.toString();
        pwd = configInfo.get("L4D2_RCON_PASSWORD");
        if (pwd != null && !pwd.toString().isBlank()) return pwd.toString();
        pwd = configInfo.get("SRCDS_RCONPW");
        if (pwd != null && !pwd.toString().isBlank()) return pwd.toString();
        return null;
    }

    /**
     * 判断 RCON 端口是否已映射到宿主机。
     * Native/linuxgsm 直接监听端口，必然可达。
     * Docker 类需检查 portConfig 中是否有映射记录。
     */
    private boolean isPortMapped(InstanceVO instance, int rconPort) {
        String deployType = instance.getDeployType();
        if ("native".equals(deployType) || "linuxgsm".equals(deployType)) {
            return true;
        }
        Map<String, Object> portConfig = instance.getPortConfig();
        if (portConfig == null) return false;
        return portConfig.containsValue(rconPort)
            || portConfig.containsKey("rcon")
            || portConfig.containsKey(String.valueOf(rconPort));
    }
}

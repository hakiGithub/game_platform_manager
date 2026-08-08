package com.gameplatform.plugin.l4d2.rcon;

/**
 * RCON 连接端点（host, port, password）。
 * 由 RconConnectionResolver 解析产生，供 RconConnectionManager 建立连接。
 */
public record RconEndpoint(String host, int port, String password) {

    /**
     * 校验端点是否有效。
     */
    public boolean isValid() {
        return host != null && !host.isBlank()
            && port > 0 && port < 65536
            && password != null && !password.isBlank();
    }
}

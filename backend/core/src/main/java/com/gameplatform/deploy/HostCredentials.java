package com.gameplatform.deploy;

/**
 * 已解密的主机 SSH 凭据（DeploymentAccess.credentials 的返回结构）
 *
 * @param host       主机 IP
 * @param port       SSH 端口（空值时已默认 22）
 * @param username   SSH 用户名
 * @param privateKey 解密后的私钥（可为 null）
 * @param password   解密后的密码（可为 null）
 */
public record HostCredentials(String host, int port, String username,
                              String privateKey, String password) {
}

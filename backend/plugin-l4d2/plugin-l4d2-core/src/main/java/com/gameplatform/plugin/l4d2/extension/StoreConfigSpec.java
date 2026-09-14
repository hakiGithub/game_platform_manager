package com.gameplatform.plugin.l4d2.extension;

import lombok.Data;

import java.io.Serializable;

/**
 * 远端插件仓库配置 spec。
 *
 * <p>githubToken 以 AES 加密密文形式存储（见 {@code TokenCipher}），
 * 加载后解密应用到运行时配置。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class StoreConfigSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 仓库地址（owner/repo 归一形式）；空 = 未配置仓库 */
    private String repo;

    /** 分支（空时运行时回退 master） */
    private String branch;

    /** GitHub 加速代理前缀（如 https://gh-proxy.com/），空 = 直连 */
    private String proxyUrl;

    /** GitHub 访问令牌（AES 密文） */
    private String githubToken;
}

package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 远端插件仓库配置视图（令牌永不回传明文，只给脱敏提示）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class PluginStoreConfigVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 仓库地址是否已配置（owner/repo 非空） */
    private boolean configured;

    /** 仓库地址（owner/repo 归一形式；未配置时空串） */
    private String repo;

    /** 分支 */
    private String branch;

    /** 加速代理前缀（空 = 直连） */
    private String proxyUrl;

    /** 令牌脱敏提示（形如 ****abcd；未配置令牌时为 null） */
    private String githubTokenMasked;
}

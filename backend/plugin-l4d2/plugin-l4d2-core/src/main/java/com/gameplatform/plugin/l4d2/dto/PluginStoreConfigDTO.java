package com.gameplatform.plugin.l4d2.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 远端插件仓库配置保存/测试请求。
 *
 * <p>githubToken 语义：留空 = 保留已配置的令牌；填新值 = 覆盖。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class PluginStoreConfigDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 仓库地址（owner/repo 或完整 GitHub URL） */
    private String repo;

    /** 分支；留空回退 master（URL /tree/{branch} 后缀解析值优先） */
    private String branch;

    /** GitHub 加速代理前缀；留空 = 直连 */
    private String proxyUrl;

    /** 访问令牌（明文传输，HTTPS 下由传输层保护；留空保留原值） */
    private String githubToken;
}

package com.gameplatform.plugin.l4d2.util;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GitHubErrorTranslator} 单元测试：GitHub 异常归类为可读文案。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
class GitHubErrorTranslatorTest {

    @Test
    void translate_404_to_repo_not_found_message() {
        L4D2PluginException e = new L4D2PluginException(L4D2PluginException.EXTERNAL_API,
                "GET 请求失败: https://api.github.com/...",
                new HttpClientErrorException(HttpStatus.NOT_FOUND, "Not Found"));
        L4D2PluginException translated = GitHubErrorTranslator.translate(e);
        assertEquals(L4D2PluginException.EXTERNAL_API, translated.getCode());
        assertTrue(translated.getMessage().contains("仓库不存在或分支错误"),
                "实际文案: " + translated.getMessage());
    }

    @Test
    void translate_403_to_rate_limit_message() {
        L4D2PluginException e = new L4D2PluginException(L4D2PluginException.EXTERNAL_API, "GET 请求失败",
                new HttpClientErrorException(HttpStatus.FORBIDDEN, "Forbidden"));
        assertTrue(GitHubErrorTranslator.translate(e).getMessage().contains("限流"));
    }

    @Test
    void translate_401_to_token_message() {
        L4D2PluginException e = new L4D2PluginException(L4D2PluginException.EXTERNAL_API, "GET 请求失败",
                new HttpClientErrorException(HttpStatus.UNAUTHORIZED, "Unauthorized"));
        assertTrue(GitHubErrorTranslator.translate(e).getMessage().contains("令牌"));
    }

    @Test
    void translate_5xx_to_server_error_message() {
        L4D2PluginException e = new L4D2PluginException(L4D2PluginException.EXTERNAL_API, "GET 请求失败",
                new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error",
                        new byte[0], StandardCharsets.UTF_8));
        L4D2PluginException translated = GitHubErrorTranslator.translate(e);
        assertTrue(translated.getMessage().contains("HTTP 500"), "实际文案: " + translated.getMessage());
    }

    @Test
    void translate_unknown_host_suggests_proxy() throws Exception {
        L4D2PluginException e = new L4D2PluginException(L4D2PluginException.EXTERNAL_API, "GET 请求失败",
                new UnknownHostException("api.github.com"));
        String msg = GitHubErrorTranslator.translate(e).getMessage();
        assertTrue(msg.contains("加速代理"), "实际文案: " + msg);
    }

    @Test
    void translate_rest_client_response_exception_with_headers() {
        // RestClient 实际抛出的子类带响应体，验证 headers 构造路径
        L4D2PluginException e = new L4D2PluginException(L4D2PluginException.EXTERNAL_API, "GET 请求失败",
                HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                        new HttpHeaders(), new byte[0], StandardCharsets.UTF_8));
        assertTrue(GitHubErrorTranslator.translate(e).getMessage().contains("分支"));
    }

    @Test
    void translate_non_github_exception_passthrough() {
        L4D2PluginException e = new L4D2PluginException(L4D2PluginException.BUSINESS, "pluginId 不能为空");
        assertSame(e, GitHubErrorTranslator.translate(e));
    }
}

package com.gameplatform.plugin.l4d2.util;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import org.springframework.web.client.RestClientResponseException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * GitHub 异常归类翻译：把 {@code ExternalHttpClient} 包装的底层异常
 * 翻译为面向用户的可读文案（消灭裸 500）。
 *
 * <ul>
 *   <li>404 → 仓库不存在或分支错误</li>
 *   <li>401 → 令牌无效</li>
 *   <li>403 → API 限流（提示配令牌）</li>
 *   <li>DNS/连接/超时 → 网络不可达（提示配代理）</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public final class GitHubErrorTranslator {

    private GitHubErrorTranslator() {
    }

    /**
     * 归类翻译插件异常。非 GitHub 链路的异常原样返回。
     *
     * @param e 原始插件异常
     * @return 文案可读的插件异常（code 不变；无法归类时原样返回）
     */
    public static L4D2PluginException translate(L4D2PluginException e) {
        if (e == null) {
            return null;
        }
        String message = classify(e);
        return message != null ? new L4D2PluginException(e.getCode(), message, e) : e;
    }

    private static String classify(Throwable e) {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 8) {
            if (cur instanceof RestClientResponseException rre) {
                return byStatus(rre.getStatusCode().value());
            }
            if (cur instanceof UnknownHostException) {
                return "无法解析 GitHub 域名，请检查服务器网络，或在仓库配置中设置加速代理";
            }
            if (cur instanceof ConnectException) {
                return "无法连接 GitHub，请检查服务器网络，或在仓库配置中设置加速代理";
            }
            if (cur instanceof SocketTimeoutException) {
                return "连接 GitHub 超时，请检查服务器网络，或在仓库配置中设置加速代理";
            }
            cur = cur.getCause();
            depth++;
        }
        return null;
    }

    private static String byStatus(int status) {
        return switch (status) {
            case 404 -> "远端仓库不存在或分支错误，请在仓库设置中检查仓库地址与分支";
            case 401 -> "GitHub 认证失败，请在仓库设置中检查访问令牌";
            case 403 -> "GitHub API 限流或无权限，请在仓库设置中配置访问令牌后重试";
            default -> status >= 500
                    ? "GitHub 服务异常（HTTP " + status + "），请稍后重试"
                    : "GitHub 请求失败（HTTP " + status + "），请检查仓库配置";
        };
    }
}

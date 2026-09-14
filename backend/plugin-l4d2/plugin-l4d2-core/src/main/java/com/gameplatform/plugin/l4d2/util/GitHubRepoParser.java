package com.gameplatform.plugin.l4d2.util;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub 仓库地址解析：接受 {@code owner/repo} 或完整 URL，归一为 owner/repo。
 *
 * <p>支持的 URL 形式（host 限定 github.com）：
 * <ul>
 *   <li>{@code https://github.com/{owner}/{repo}}</li>
 *   <li>可带 {@code .git} 后缀、尾斜杠</li>
 *   <li>可带 {@code /tree/{branch}} 后缀（同时解析出分支）</li>
 *   <li>可省略 scheme（{@code github.com/owner/repo}）或用 http</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public final class GitHubRepoParser {

    private static final Pattern GITHUB_URL = Pattern.compile(
            "^(?:https?://)?github\\.com/([^/\\s]+)/([^/\\s]+?)(?:\\.git)?(?:/tree/(.+?))?/?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OWNER_REPO = Pattern.compile("^([^/\\s]+)/([^/\\s]+)$");

    private GitHubRepoParser() {
    }

    /**
     * 归一解析结果。
     *
     * @param repo   owner/repo 形式仓库地址
     * @param branch URL 中解析出的分支；未携带时为 null（由调用方回退默认值）
     */
    public record RepoRef(String repo, String branch) {
    }

    /**
     * 解析仓库地址输入。
     *
     * @param input 用户输入（owner/repo 或 GitHub URL）
     * @return 归一结果，永不为 null
     * @throws L4D2PluginException 输入为空或格式无法识别（BUSINESS）
     */
    public static RepoRef parse(String input) {
        if (input == null || input.isBlank()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "仓库地址不能为空");
        }
        String trimmed = input.trim();
        Matcher m = GITHUB_URL.matcher(trimmed);
        if (m.matches()) {
            String repo = m.group(1) + "/" + m.group(2);
            String branch = (m.group(3) == null || m.group(3).isBlank()) ? null : m.group(3);
            return new RepoRef(repo, branch);
        }
        m = OWNER_REPO.matcher(trimmed);
        if (m.matches()) {
            return new RepoRef(trimmed, null);
        }
        throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                "无法识别的仓库地址: " + trimmed + "，请使用 owner/repo 或 https://github.com/owner/repo");
    }
}

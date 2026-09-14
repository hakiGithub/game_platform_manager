package com.gameplatform.plugin.l4d2.util;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link GitHubRepoParser} 单元测试：owner/repo 与完整 URL 的归一解析。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
class GitHubRepoParserTest {

    @Test
    void parse_owner_repo_plain() {
        GitHubRepoParser.RepoRef ref = GitHubRepoParser.parse("LaoYutang/l4d2-plugins-store");
        assertEquals("LaoYutang/l4d2-plugins-store", ref.repo());
        assertNull(ref.branch());
    }

    @Test
    void parse_full_https_url() {
        GitHubRepoParser.RepoRef ref = GitHubRepoParser.parse("https://github.com/LaoYutang/l4d2-plugins-store");
        assertEquals("LaoYutang/l4d2-plugins-store", ref.repo());
        assertNull(ref.branch());
    }

    @Test
    void parse_url_with_git_suffix_and_trailing_slash() {
        GitHubRepoParser.RepoRef ref = GitHubRepoParser.parse("https://github.com/o/r.git/");
        assertEquals("o/r", ref.repo());
        assertNull(ref.branch());
    }

    @Test
    void parse_url_with_tree_branch() {
        GitHubRepoParser.RepoRef ref = GitHubRepoParser.parse("https://github.com/o/r/tree/dev-branch");
        assertEquals("o/r", ref.repo());
        assertEquals("dev-branch", ref.branch());
    }

    @Test
    void parse_url_without_scheme() {
        GitHubRepoParser.RepoRef ref = GitHubRepoParser.parse("github.com/o/r");
        assertEquals("o/r", ref.repo());
        assertNull(ref.branch());
    }

    @Test
    void parse_http_url() {
        GitHubRepoParser.RepoRef ref = GitHubRepoParser.parse("http://github.com/o/r");
        assertEquals("o/r", ref.repo());
    }

    @Test
    void parse_blank_throws_business() {
        L4D2PluginException e = assertThrows(L4D2PluginException.class, () -> GitHubRepoParser.parse("  "));
        assertEquals(L4D2PluginException.BUSINESS, e.getCode());
    }

    @Test
    void parse_unrecognized_throws_business() {
        L4D2PluginException e = assertThrows(L4D2PluginException.class,
                () -> GitHubRepoParser.parse("https://gitlab.com/o/r"));
        assertEquals(L4D2PluginException.BUSINESS, e.getCode());
    }

    @Test
    void parse_single_segment_throws_business() {
        assertThrows(L4D2PluginException.class, () -> GitHubRepoParser.parse("only-repo"));
    }
}

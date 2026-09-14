package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.dto.PluginStoreConfigDTO;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.extension.StoreConfigResource;
import com.gameplatform.plugin.l4d2.extension.StoreConfigSpec;
import com.gameplatform.plugin.l4d2.util.GitHubApiClient;
import com.gameplatform.plugin.l4d2.util.TokenCipher;
import com.gameplatform.plugin.l4d2.vo.PluginStoreConfigVO;
import com.gameplatform.plugin.l4d2.vo.PluginStoreTestVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StoreConfigService} 单元测试：懒加载、yml 种子、AES 加密 upsert、测试连接。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StoreConfigServiceTest {

    @Mock
    private ExtensionClient extensionClient;

    @Mock
    private GitHubApiClient gitHubApiClient;

    private L4D2Config config;
    private StoreConfigService service;

    @BeforeEach
    void setUp() {
        config = new L4D2Config();
        service = new StoreConfigService(config, extensionClient, gitHubApiClient);
    }

    private PluginStoreConfigDTO dto(String repo, String branch, String proxyUrl, String token) {
        PluginStoreConfigDTO d = new PluginStoreConfigDTO();
        d.setRepo(repo);
        d.setBranch(branch);
        d.setProxyUrl(proxyUrl);
        d.setGithubToken(token);
        return d;
    }

    // ============================================================
    // ensureLoaded
    // ============================================================

    @Test
    void ensureLoaded_no_record_no_yml_stays_unconfigured() {
        when(extensionClient.get(StoreConfigResource.class, StoreConfigService.CONFIG_RESOURCE_NAME))
                .thenReturn(Optional.empty());

        service.ensureLoaded();

        assertFalse(service.isConfigured());
        assertEquals("", config.getPluginStore().getRepo());
        verify(extensionClient, org.mockito.Mockito.never()).create(any());
    }

    @Test
    void ensureLoaded_no_record_seeds_from_yml_and_encrypts_token() {
        config.getPluginStore().setRepo("LaoYutang/l4d2-plugins-store");
        config.getPluginStore().setBranch("master");
        config.getPluginStore().setProxyUrl("https://gh-proxy.com/");
        config.getPluginStore().setGithubToken("yml-token");
        when(extensionClient.get(StoreConfigResource.class, StoreConfigService.CONFIG_RESOURCE_NAME))
                .thenReturn(Optional.empty());

        service.ensureLoaded();

        ArgumentCaptor<StoreConfigResource> captor = ArgumentCaptor.forClass(StoreConfigResource.class);
        verify(extensionClient).create(captor.capture());
        StoreConfigSpec spec = captor.getValue().getSpec();
        assertEquals("LaoYutang/l4d2-plugins-store", spec.getRepo());
        assertEquals("yml-token", TokenCipher.decrypt(spec.getGithubToken()), "落库必须是密文");
        assertTrue(service.isConfigured());
        assertEquals("yml-token", config.getPluginStore().getGithubToken(), "运行时应用明文");
    }

    @Test
    void ensureLoaded_with_record_applies_decrypted_values() {
        StoreConfigSpec stored = new StoreConfigSpec();
        stored.setRepo("a/b");
        stored.setBranch("dev");
        stored.setProxyUrl("https://p.example/");
        stored.setGithubToken(TokenCipher.encrypt("plain-token"));
        StoreConfigResource resource = new StoreConfigResource();
        resource.setName(StoreConfigService.CONFIG_RESOURCE_NAME);
        resource.setSpec(stored);
        when(extensionClient.get(StoreConfigResource.class, StoreConfigService.CONFIG_RESOURCE_NAME))
                .thenReturn(Optional.of(resource));

        PluginStoreConfigVO vo = service.get();

        assertTrue(vo.isConfigured());
        assertEquals("a/b", vo.getRepo());
        assertEquals("dev", vo.getBranch());
        assertEquals("plain-token", config.getPluginStore().getGithubToken());
        assertEquals("****oken", vo.getGithubTokenMasked());
    }

    @Test
    void ensureLoaded_extension_failure_falls_back_to_current() {
        when(extensionClient.get(StoreConfigResource.class, StoreConfigService.CONFIG_RESOURCE_NAME))
                .thenThrow(new RuntimeException("table not ready"));
        config.getPluginStore().setRepo("fallback/r");

        service.ensureLoaded();

        assertTrue(service.isConfigured(), "加载失败时应沿用内存配置");
        assertEquals("fallback/r", config.getPluginStore().getRepo());
    }

    // ============================================================
    // save
    // ============================================================

    @Test
    void save_normalizes_full_url_and_updates() {
        // 预置已有记录（save → upsert 走 update 分支）
        StoreConfigSpec stored = new StoreConfigSpec();
        stored.setRepo("old/r");
        stored.setGithubToken(TokenCipher.encrypt("old-token"));
        StoreConfigResource resource = new StoreConfigResource();
        resource.setName(StoreConfigService.CONFIG_RESOURCE_NAME);
        resource.setSpec(stored);
        when(extensionClient.get(StoreConfigResource.class, StoreConfigService.CONFIG_RESOURCE_NAME))
                .thenReturn(Optional.of(resource));

        PluginStoreConfigVO vo = service.save(dto("https://github.com/New/Repo.git/tree/release", "", "", ""));

        verify(extensionClient).update(resource);
        StoreConfigSpec saved = resource.getSpec();
        assertEquals("New/Repo", saved.getRepo(), "URL 应归一为 owner/repo");
        assertEquals("release", saved.getBranch(), "URL /tree/ 后缀解析分支");
        assertEquals("old-token", TokenCipher.decrypt(saved.getGithubToken()), "令牌留空应保留原值");
        assertTrue(vo.isConfigured());
        assertEquals("New/Repo", vo.getRepo());
    }

    @Test
    void save_new_record_creates_with_encrypted_token() {
        when(extensionClient.get(StoreConfigResource.class, StoreConfigService.CONFIG_RESOURCE_NAME))
                .thenReturn(Optional.empty());

        service.save(dto("o/r", "main", "https://p/", "brand-new-token"));

        ArgumentCaptor<StoreConfigResource> captor = ArgumentCaptor.forClass(StoreConfigResource.class);
        verify(extensionClient).create(captor.capture());
        StoreConfigSpec saved = captor.getValue().getSpec();
        assertEquals("o/r", saved.getRepo());
        assertEquals("main", saved.getBranch());
        assertEquals("https://p/", saved.getProxyUrl());
        assertEquals("brand-new-token", TokenCipher.decrypt(saved.getGithubToken()));
        assertEquals("brand-new-token", config.getPluginStore().getGithubToken());
    }

    @Test
    void save_blank_repo_throws_business() {
        service.ensureLoaded();
        L4D2PluginException e = assertThrows(L4D2PluginException.class,
                () -> service.save(dto(" ", null, null, null)));
        assertEquals(L4D2PluginException.BUSINESS, e.getCode());
    }

    @Test
    void save_branch_with_whitespace_throws_business() {
        L4D2PluginException e = assertThrows(L4D2PluginException.class,
                () -> service.save(dto("o/r", "dev branch", null, null)));
        assertEquals(L4D2PluginException.BUSINESS, e.getCode());
    }

    // ============================================================
    // requireConfigured / test
    // ============================================================

    @Test
    void requireConfigured_throws_dedicated_code_when_unconfigured() {
        when(extensionClient.get(StoreConfigResource.class, StoreConfigService.CONFIG_RESOURCE_NAME))
                .thenReturn(Optional.empty());

        L4D2PluginException e = assertThrows(L4D2PluginException.class, service::requireConfigured);
        assertEquals(L4D2PluginException.STORE_NOT_CONFIGURED, e.getCode());
        assertTrue(e.getMessage().contains("未配置"));
    }

    @Test
    void test_counts_plugins_and_falls_back_token() {
        config.getPluginStore().setGithubToken("configured-token");
        when(gitHubApiClient.getTree(eq("o/r"), eq("master"), eq(""), eq("configured-token")))
                .thenReturn(List.of(
                        new GitHubApiClient.TreeEntry("plugins/A/a.smx", "blob", "s1", 10),
                        new GitHubApiClient.TreeEntry("plugins/A/sub/b.txt", "blob", "s2", 10),
                        new GitHubApiClient.TreeEntry("plugins/B/c.smx", "blob", "s3", 10),
                        new GitHubApiClient.TreeEntry("README.md", "blob", "s4", 10),
                        new GitHubApiClient.TreeEntry("plugins", "tree", "s5", 0)));

        PluginStoreTestVO vo = service.test(dto("o/r", "", "", ""));

        assertEquals("o/r", vo.getRepo());
        assertEquals("master", vo.getBranch(), "分支缺省回退 master");
        assertEquals(2, vo.getPluginCount(), "仅统计 plugins/ 下一级插件目录");
    }

    @Test
    void test_blank_repo_throws_business() {
        L4D2PluginException e = assertThrows(L4D2PluginException.class,
                () -> service.test(dto(null, null, null, null)));
        assertEquals(L4D2PluginException.BUSINESS, e.getCode());
    }

    @Test
    void get_unconfigured_masks_null_token() {
        when(extensionClient.get(StoreConfigResource.class, StoreConfigService.CONFIG_RESOURCE_NAME))
                .thenReturn(Optional.empty());

        PluginStoreConfigVO vo = service.get();

        assertFalse(vo.isConfigured());
        assertNull(vo.getGithubTokenMasked());
        assertEquals("master", vo.getBranch());
    }
}

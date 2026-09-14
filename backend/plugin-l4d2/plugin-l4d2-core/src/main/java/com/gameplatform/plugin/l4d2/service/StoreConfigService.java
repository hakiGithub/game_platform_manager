package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.dto.PluginStoreConfigDTO;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.extension.StoreConfigResource;
import com.gameplatform.plugin.l4d2.extension.StoreConfigSpec;
import com.gameplatform.plugin.l4d2.util.GitHubApiClient;
import com.gameplatform.plugin.l4d2.util.GitHubRepoParser;
import com.gameplatform.plugin.l4d2.util.TokenCipher;
import com.gameplatform.plugin.l4d2.vo.PluginStoreConfigVO;
import com.gameplatform.plugin.l4d2.vo.PluginStoreTestVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 远端插件仓库配置服务：运行时配置的加载、保存、测试连接。
 *
 * <p>持久化走 {@link ExtensionClient}（PLUGIN_ISOLATED 单例，对齐 ADR-0020
 * {@code RestartConfigResource} 模式）：启动后首次访问加载并应用到运行时配置
 * {@link L4D2Config.PluginStore}，保存即落库；令牌 AES 加密存储、永不回传明文。
 *
 * <p>首次加载且库中无记录时，yml {@code plugin.l4d2.plugin-store.repo} 显式配置值
 * 作为种子初始化（默认代码不再内置仓库地址，新装即"未配置仓库"）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreConfigService {

    /** 持久化配置的扩展资源 name（插件全局唯一一条） */
    public static final String CONFIG_RESOURCE_NAME = "store-config";

    /** 仓库分支缺省值 */
    public static final String DEFAULT_BRANCH = "master";

    /** 仓库插件目录前缀（与 PluginStoreService 的分组约定一致） */
    private static final String PLUGIN_DIR_PREFIX = "plugins/";

    private final L4D2Config config;
    private final ExtensionClient extensionClient;
    private final GitHubApiClient gitHubApiClient;

    /** 懒加载标记（扩展表就绪时机晚于 Bean 创建，避免 @PostConstruct 时序问题） */
    private volatile boolean loaded = false;

    /**
     * 首次访问时从扩展存储加载持久化配置并应用到运行时；
     * 无记录时用 yml 显式配置做首次种子；两者皆无则保持"未配置"。
     */
    public void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            try {
                Optional<StoreConfigResource> record =
                        extensionClient.get(StoreConfigResource.class, CONFIG_RESOURCE_NAME);
                if (record.isPresent()) {
                    applySpec(toPlainSpec(record.get().getSpec()));
                    log.info("已加载持久化远端仓库配置: repo={}", record.get().getSpec().getRepo());
                } else {
                    seedFromYml();
                }
            } catch (Exception e) {
                log.warn("加载远端仓库配置失败，沿用当前配置: {}", e.getMessage());
            } finally {
                loaded = true;
            }
        }
    }

    /**
     * 当前配置视图（令牌只回传脱敏提示）。
     */
    public PluginStoreConfigVO get() {
        ensureLoaded();
        L4D2Config.PluginStore ps = config.getPluginStore();
        PluginStoreConfigVO vo = new PluginStoreConfigVO();
        vo.setConfigured(isConfigured());
        vo.setRepo(ps.getRepo());
        vo.setBranch(firstNonBlank(ps.getBranch(), DEFAULT_BRANCH));
        vo.setProxyUrl(ps.getProxyUrl());
        vo.setGithubTokenMasked(TokenCipher.mask(ps.getGithubToken()));
        return vo;
    }

    /** 仓库地址是否已配置（owner/repo 非空）。调用前需 {@link #ensureLoaded()} */
    public boolean isConfigured() {
        String repo = config.getPluginStore().getRepo();
        return repo != null && !repo.isBlank();
    }

    /**
     * 校验无未配置仓库（list/detail/readme/download 入口统一前置检查）。
     */
    public void requireConfigured() {
        ensureLoaded();
        if (!isConfigured()) {
            throw new L4D2PluginException(L4D2PluginException.STORE_NOT_CONFIGURED,
                    "远端插件仓库未配置，请先在插件管理 → 远端仓库中完成配置");
        }
    }

    /**
     * 保存仓库配置（upsert + 应用到运行时；令牌留空 = 保留原值）。
     */
    public PluginStoreConfigVO save(PluginStoreConfigDTO dto) {
        ensureLoaded();
        GitHubRepoParser.RepoRef ref = requireRepo(dto);
        String branch = requireBranch(firstNonBlank(dto.getBranch(), ref.branch(), DEFAULT_BRANCH));

        StoreConfigSpec spec = new StoreConfigSpec();
        spec.setRepo(ref.repo());
        spec.setBranch(branch);
        spec.setProxyUrl(trimToEmpty(dto.getProxyUrl()));
        String inputToken = trimToEmpty(dto.getGithubToken());
        spec.setGithubToken(inputToken.isEmpty()
                ? TokenCipher.encrypt(config.getPluginStore().getGithubToken())
                : TokenCipher.encrypt(inputToken));

        upsert(spec);
        applySpec(toPlainSpec(spec));
        log.info("远端仓库配置已保存: repo={}, branch={}", spec.getRepo(), spec.getBranch());
        return get();
    }

    /**
     * 测试连接：用表单当前值（未保存）实拉一次仓库目录树，返回插件数量。
     * 表单令牌留空时沿用当前已配置令牌；失败抛出可归类异常（Controller 翻译文案）。
     */
    public PluginStoreTestVO test(PluginStoreConfigDTO dto) {
        GitHubRepoParser.RepoRef ref = requireRepo(dto);
        String branch = requireBranch(firstNonBlank(dto.getBranch(), ref.branch(), DEFAULT_BRANCH));
        String proxyUrl = trimToEmpty(dto.getProxyUrl());
        String token = trimToEmpty(dto.getGithubToken()).isEmpty()
                ? config.getPluginStore().getGithubToken()
                : dto.getGithubToken().trim();

        List<GitHubApiClient.TreeEntry> tree = gitHubApiClient.getTree(ref.repo(), branch, proxyUrl, token);
        PluginStoreTestVO vo = new PluginStoreTestVO();
        vo.setRepo(ref.repo());
        vo.setBranch(branch);
        vo.setPluginCount(countPlugins(tree));
        return vo;
    }

    // ===== 私有实现 =====

    /** 库中无记录时，yml 显式配置作为首次种子写入并应用 */
    private void seedFromYml() {
        L4D2Config.PluginStore ps = config.getPluginStore();
        if (ps.getRepo() == null || ps.getRepo().isBlank()) {
            return;
        }
        StoreConfigSpec spec = new StoreConfigSpec();
        spec.setRepo(ps.getRepo().trim());
        spec.setBranch(firstNonBlank(ps.getBranch(), DEFAULT_BRANCH));
        spec.setProxyUrl(trimToEmpty(ps.getProxyUrl()));
        spec.setGithubToken(TokenCipher.encrypt(ps.getGithubToken()));
        upsert(spec);
        applySpec(toPlainSpec(spec));
        log.info("已用 yml 配置初始化远端仓库配置: repo={}", spec.getRepo());
    }

    private GitHubRepoParser.RepoRef requireRepo(PluginStoreConfigDTO dto) {
        if (dto == null || dto.getRepo() == null || dto.getRepo().isBlank()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "仓库地址不能为空");
        }
        return GitHubRepoParser.parse(dto.getRepo());
    }

    private String requireBranch(String branch) {
        if (branch == null || branch.isBlank()) {
            branch = DEFAULT_BRANCH;
        }
        if (branch.matches(".*\\s.*")) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "分支名不能包含空白字符: " + branch);
        }
        return branch;
    }

    /** 将解密后的明文 spec 应用到运行时配置（GitHubApiClient 只读运行时配置） */
    private void applySpec(StoreConfigSpec plain) {
        L4D2Config.PluginStore ps = config.getPluginStore();
        ps.setRepo(plain.getRepo() == null ? "" : plain.getRepo().trim());
        ps.setBranch(firstNonBlank(plain.getBranch(), DEFAULT_BRANCH));
        ps.setProxyUrl(trimToEmpty(plain.getProxyUrl()));
        ps.setGithubToken(trimToEmpty(plain.getGithubToken()));
    }

    /** 密文 spec → 明文 spec（令牌解密） */
    private StoreConfigSpec toPlainSpec(StoreConfigSpec stored) {
        StoreConfigSpec plain = new StoreConfigSpec();
        plain.setRepo(stored.getRepo());
        plain.setBranch(stored.getBranch());
        plain.setProxyUrl(stored.getProxyUrl());
        plain.setGithubToken(TokenCipher.decrypt(stored.getGithubToken()));
        return plain;
    }

    /** 存在则乐观锁更新，否则创建（对齐 RestartService.persistConfig） */
    private void upsert(StoreConfigSpec spec) {
        Optional<StoreConfigResource> existing =
                extensionClient.get(StoreConfigResource.class, CONFIG_RESOURCE_NAME);
        if (existing.isPresent()) {
            StoreConfigResource resource = existing.get();
            resource.setSpec(spec);
            extensionClient.update(resource);
        } else {
            StoreConfigResource resource = new StoreConfigResource();
            resource.setName(CONFIG_RESOURCE_NAME);
            resource.setSpec(spec);
            extensionClient.create(resource);
        }
    }

    /** 统计仓库根 plugins/ 目录下的一级子目录数（即插件数） */
    private int countPlugins(List<GitHubApiClient.TreeEntry> tree) {
        Set<String> names = new HashSet<>();
        for (GitHubApiClient.TreeEntry e : tree) {
            String path = e.path();
            if (path == null || !path.startsWith(PLUGIN_DIR_PREFIX)) {
                continue;
            }
            String rest = path.substring(PLUGIN_DIR_PREFIX.length());
            int slash = rest.indexOf('/');
            if (slash > 0) {
                names.add(rest.substring(0, slash));
            }
        }
        return names.size();
    }

    private String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }
}

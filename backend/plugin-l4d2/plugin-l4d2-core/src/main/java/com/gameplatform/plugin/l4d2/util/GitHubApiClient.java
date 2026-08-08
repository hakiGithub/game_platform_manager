package com.gameplatform.plugin.l4d2.util;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.service.ExternalHttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GitHub API 客户端：仓库目录树、文件 Blob、LFS BatchAPI 下载。
 *
 * <p>封装与 GitHub REST API 与 Git LFS BatchAPI 的交互，依赖 {@link ExternalHttpClient}
 * 进行实际 HTTP 请求。仓库与分支从 {@link L4D2Config.PluginStore} 读取。
 *
 * <p>限流说明：未认证请求 60/h，可通过环境变量 {@code GITHUB_TOKEN} 提升到 5000/h
 * （本实现可选，不作强制要求）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitHubApiClient {

    private static final String GITHUB_API_BASE = "https://api.github.com/repos";
    private static final String LFS_POINTER_PREFIX = "version https://git-lfs.github.com/spec/v1";
    private static final String ENV_GITHUB_TOKEN = "GITHUB_TOKEN";

    private final ExternalHttpClient httpClient;
    private final L4D2Config config;

    /**
     * 获取仓库分支的递归目录树。
     *
     * <p>调用 {@code GET /repos/{owner}/{repo}/git/trees/{branch}?recursive=1}。
     *
     * @return 目录树条目列表（包含 blob 与 tree 类型）
     */
    @SuppressWarnings("unchecked")
    public List<TreeEntry> getTree() {
        L4D2Config.PluginStore ps = config.getPluginStore();
        String rawUrl = String.format("%s/%s/git/trees/%s?recursive=1",
                GITHUB_API_BASE, ps.getRepo(), ps.getBranch());
        String url = applyProxy(rawUrl);
        Map<String, Object> resp = httpClient.getForObject(url, Map.class, buildAuthParams());
        if (resp == null) {
            return List.of();
        }
        Object treeObj = resp.get("tree");
        if (!(treeObj instanceof List<?> rawList)) {
            return List.of();
        }
        List<TreeEntry> result = new ArrayList<>(rawList.size());
        for (Object item : rawList) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            String path = asString(m.get("path"));
            String type = asString(m.get("type"));
            String sha = asString(m.get("sha"));
            long size = asLong(m.get("size"));
            result.add(new TreeEntry(path, type, sha, size));
        }
        return result;
    }

    /**
     * 获取 Blob 内容（base64 自动解码为 UTF-8 字符串）。
     *
     * <p>调用 {@code GET /repos/{owner}/{repo}/git/blobs/{sha}}。
     */
    @SuppressWarnings("unchecked")
    public String getBlobContent(String sha) {
        if (sha == null || sha.isBlank()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "Blob SHA 不能为空");
        }
        L4D2Config.PluginStore ps = config.getPluginStore();
        String rawUrl = String.format("%s/%s/git/blobs/%s",
                GITHUB_API_BASE, ps.getRepo(), sha);
        String url = applyProxy(rawUrl);
        Map<String, Object> resp = httpClient.getForObject(url, Map.class, buildAuthParams());
        if (resp == null) {
            return null;
        }
        String content = asString(resp.get("content"));
        String encoding = asString(resp.get("encoding"));
        if (content == null) {
            return null;
        }
        if ("base64".equalsIgnoreCase(encoding)) {
            try {
                byte[] decoded = Base64.getDecoder().decode(content.replaceAll("\\s", ""));
                return new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                log.warn("base64 解码失败 sha={}, err={}", sha, e.getMessage());
                return content;
            }
        }
        return content;
    }

    /**
     * 检测内容是否为 Git LFS 指针文件。
     *
     * <p>LFS 指针文件以 {@code version https://git-lfs.github.com/spec/v1} 开头。
     */
    public boolean isLfsPointer(String content) {
        return content != null && content.startsWith(LFS_POINTER_PREFIX);
    }

    /**
     * 解析 LFS 指针文件内容，返回 OID 与大小。
     *
     * <p>对齐 l4d2-server-next parseGitLFSPointer。
     *
     * @param content 文件内容（UTF-8 字符串）
     * @return LFS 指针对象；非 LFS 文件返回 null
     */
    public LfsPointer parseLfsPointer(String content) {
        if (content == null || !content.startsWith(LFS_POINTER_PREFIX)) {
            return null;
        }
        String oid = null;
        long size = 0L;
        for (String line : content.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("oid sha256:")) {
                oid = trimmed.substring("oid sha256:".length()).trim();
            } else if (trimmed.startsWith("size ")) {
                try {
                    size = Long.parseLong(trimmed.substring("size ".length()).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (oid == null) {
            return null;
        }
        return new LfsPointer(oid, size);
    }

    /**
     * 批量获取 LFS 对象的真实下载 URL。
     *
     * <p>调用 {@code POST /repos/{owner}/{repo}/info/lfs/objects/batch}，
     * 请求体遵循 Git LFS BatchAPI 规范：
     * <pre>
     * {
     *   "operation": "download",
     *   "transfers": ["basic"],
     *   "objects": [{"oid": "...", "size": 0}]
     * }
     * </pre>
     *
     * @param oids LFS 对象 SHA256 OID 列表
     * @return oid → 真实下载 URL 映射；未提供下载链接的 OID 不会出现在结果中
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> batchLfsObjects(List<String> oids) {
        Map<String, String> result = new HashMap<>();
        if (oids == null || oids.isEmpty()) {
            return result;
        }
        L4D2Config.PluginStore ps = config.getPluginStore();
        String rawUrl = String.format("%s/%s/info/lfs/objects/batch",
                GITHUB_API_BASE, ps.getRepo());
        String url = applyProxy(rawUrl);

        Map<String, Object> body = new HashMap<>();
        body.put("operation", "download");
        body.put("transfers", List.of("basic"));
        List<Map<String, Object>> objects = new ArrayList<>(oids.size());
        for (String oid : oids) {
            Map<String, Object> obj = new HashMap<>();
            obj.put("oid", oid);
            obj.put("size", 0);
            objects.add(obj);
        }
        body.put("objects", objects);

        Map<String, Object> resp = httpClient.postForObject(url, body, Map.class);
        if (resp == null) {
            return result;
        }
        Object objsObj = resp.get("objects");
        if (!(objsObj instanceof List<?> list)) {
            return result;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            String oid = asString(m.get("oid"));
            Object actions = m.get("actions");
            if (!(actions instanceof Map<?, ?> am)) {
                continue;
            }
            Object download = am.get("download");
            if (!(download instanceof Map<?, ?> dm)) {
                continue;
            }
            String href = asString(dm.get("href"));
            if (oid != null && href != null) {
                result.put(oid, href);
            }
        }
        return result;
    }

    /**
     * 下载 LFS 对象到目标文件。
     *
     * <p>先调用 {@link #batchLfsObjects(List)} 获取真实下载 URL，再通过
     * {@link ExternalHttpClient#download} 下载到临时文件后移动到目标位置。
     *
     * @param oid    LFS 对象 OID
     * @param target 目标文件
     */
    public void downloadLfsObject(String oid, File target) {
        if (oid == null || oid.isBlank()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "LFS OID 不能为空");
        }
        if (target == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "目标文件不能为空");
        }
        Map<String, String> urls = batchLfsObjects(List.of(oid));
        String url = urls.get(oid);
        if (url == null) {
            throw new L4D2PluginException(L4D2PluginException.EXTERNAL_API,
                    "LFS 对象不存在或无可下载链接: " + oid);
        }
        File downloaded = httpClient.download(url, target.getName(), null, null, null);
        if (downloaded == null) {
            throw new L4D2PluginException(L4D2PluginException.NETWORK,
                    "LFS 对象下载失败: " + oid);
        }
        if (downloaded.getAbsolutePath().equals(target.getAbsolutePath())) {
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "创建目标目录失败: " + parent.getAbsolutePath());
        }
        try {
            Files.move(downloaded.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "移动下载文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构造 raw.githubusercontent.com 下载 URL（用于非 LFS 文件直接下载）。
     *
     * <p>格式：{@code https://raw.githubusercontent.com/{repo}/{branch}/{path}}
     * 若配置了 proxyUrl，则拼接为 {@code {proxyUrl}{rawUrl}}。
     *
     * @param path 仓库内文件路径（如 {@code plugins/xxx/left4dead2/addons/...}）
     * @return 完整下载 URL
     */
    public String getRawDownloadUrl(String path) {
        L4D2Config.PluginStore ps = config.getPluginStore();
        String rawUrl = String.format("https://raw.githubusercontent.com/%s/%s/%s",
                ps.getRepo(), ps.getBranch(), path);
        return applyProxy(rawUrl);
    }

    /**
     * 应用代理 URL：若配置了 proxyUrl，则拼接为 {proxyUrl}{rawUrl}。
     * 对齐 l4d2-server-next applyProxy。
     */
    String applyProxy(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return rawUrl;
        }
        String proxyUrl = config.getPluginStore().getProxyUrl();
        if (proxyUrl == null || proxyUrl.isBlank()) {
            return rawUrl;
        }
        return proxyUrl + rawUrl;
    }

    /**
     * 解析 GitHub Token：优先用配置类 token，其次环境变量 GITHUB_TOKEN。
     */
    String resolveToken() {
        String configToken = config.getPluginStore().getGithubToken();
        if (configToken != null && !configToken.isBlank()) {
            return configToken;
        }
        return System.getenv(ENV_GITHUB_TOKEN);
    }

    /**
     * 构造可选的认证查询参数（GITHUB_TOKEN 环境变量）。
     *
     * <p>未设置环境变量时返回空 Map，使用匿名限流。
     */
    private Map<String, ?> buildAuthParams() {
        String token = resolveToken();
        if (token == null || token.isBlank()) {
            return null;
        }
        return Map.of("access_token", token);
    }

    private String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private long asLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o == null) {
            return 0L;
        }
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * LFS 指针文件解析结果。
     *
     * @param oid  LFS 对象 SHA256 OID
     * @param size LFS 对象字节数
     */
    public record LfsPointer(String oid, long size) {
    }

    /**
     * GitHub Trees API 返回的目录条目。
     *
     * @param path 仓库内相对路径
     * @param type 条目类型：blob / tree / commit
     * @param sha  Git 对象 SHA
     * @param size 字节大小（仅 blob 有意义）
     */
    public record TreeEntry(String path, String type, String sha, long size) {
    }
}

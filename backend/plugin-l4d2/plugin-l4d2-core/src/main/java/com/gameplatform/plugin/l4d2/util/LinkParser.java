package com.gameplatform.plugin.l4d2.util;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用链接解析工具（对齐源项目 link_parser.go 与 workshop.go:87-120）。
 *
 * <p>核心能力：
 * <ul>
 *   <li>{@link #parse(String)}：识别链接类型（workshop / unknown）</li>
 *   <li>{@link #parseWorkshopId(String)}：从 URL 或纯数字字符串提取 Workshop ID（&gt;= 100000）</li>
 *   <li>{@link #isValidWorkshopId(String)}：校验 Workshop ID 有效性</li>
 *   <li>{@link #isWorkshopLink(String)}：判断是否为 Workshop 链接</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public final class LinkParser {

    /** Workshop ID 阈值：纯数字 &gt;= 100000 才视为有效（对齐源 workshop.go:247-249） */
    private static final long WORKSHOP_ID_THRESHOLD = 100_000L;

    /** 纯数字正则 */
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^\\d+$");

    /** 提取首个数字串（对齐源 workshop.go:112 re.FindString） */
    private static final Pattern FIRST_DIGITS_PATTERN = Pattern.compile("\\d+");

    /** 源类型常量 */
    public static final String SOURCE_WORKSHOP = "workshop";
    public static final String SOURCE_UNKNOWN = "unknown";

    private LinkParser() {
    }

    /**
     * 解析任意链接：先尝试 Workshop（含 sharedfiles / workshop / 纯数字 ID），不支持则返回 sourceType="unknown"。
     *
     * <p>对齐源项目 {@code link_parser.go:33-48 ParseDownloadLink}：本插件当前仅支持 Workshop 类型，
     * 其他链接（如 QQ 闪电传输）由后续 Phase 处理。
     *
     * @param rawLink 原始链接
     * @return 解析结果（sourceType / sourceId / originalLink）
     */
    public static LinkParseResult parse(String rawLink) {
        if (rawLink == null || rawLink.isBlank()) {
            return new LinkParseResult(SOURCE_UNKNOWN, null, rawLink);
        }
        try {
            String id = parseWorkshopId(rawLink);
            return new LinkParseResult(SOURCE_WORKSHOP, id, rawLink);
        } catch (L4D2PluginException e) {
            return new LinkParseResult(SOURCE_UNKNOWN, null, rawLink);
        }
    }

    /**
     * 提取 Workshop ID（对齐源 workshop.go:87-120）。
     *
     * <p>解析顺序：
     * <ol>
     *   <li>若输入本身是纯数字且 &gt;= 100000，直接返回</li>
     *   <li>解析 URL，识别 steamcommunity.com / steampowered.com / steamworkshop.download 主机</li>
     *   <li>优先取 query 参数 id</li>
     *   <li>否则用正则 {@code \d+} 匹配第一个数字串</li>
     *   <li>都失败抛 {@link L4D2PluginException}(BUSINESS, "未找到有效的工坊 ID")</li>
     * </ol>
     *
     * @param url 原始 URL 或纯数字 ID
     * @return Workshop ID（纯数字字符串）
     * @throws L4D2PluginException 无法提取有效 ID 时抛出
     */
    public static String parseWorkshopId(String url) throws L4D2PluginException {
        if (url == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "未找到有效的工坊 ID");
        }
        String trimmed = url.trim();
        if (isValidWorkshopId(trimmed)) {
            return trimmed;
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "无效的工坊链接");
        }

        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        boolean isSteamHost = host.endsWith("steamcommunity.com")
                || host.endsWith("steampowered.com")
                || host.endsWith("steamworkshop.download");

        // 优先从 query 参数取 id
        if (isSteamHost) {
            String id = getQueryParam(uri, "id");
            if (isValidWorkshopId(id)) {
                return id;
            }
        }

        // 退回到正则匹配（条件：steam 主机 或 包含 sharedfiles/workshop 路径）
        if (isSteamHost
                || trimmed.contains("steamcommunity.com/sharedfiles")
                || trimmed.contains("steamcommunity.com/workshop")) {
            Matcher matcher = FIRST_DIGITS_PATTERN.matcher(trimmed);
            if (matcher.find()) {
                String match = matcher.group();
                if (isValidWorkshopId(match)) {
                    return match;
                }
            }
        }

        throw new L4D2PluginException(L4D2PluginException.BUSINESS, "未找到有效的工坊 ID");
    }

    /**
     * 校验 Workshop ID：非空、纯数字、&gt;= 100000。
     *
     * @param id 待校验的 ID 字符串
     * @return 有效返回 true，否则 false
     */
    public static boolean isValidWorkshopId(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        if (!NUMERIC_PATTERN.matcher(id).matches()) {
            return false;
        }
        try {
            long num = Long.parseLong(id);
            return num >= WORKSHOP_ID_THRESHOLD;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 判断是否是 Workshop 链接（含纯数字 ID）。
     *
     * @param rawLink 原始链接
     * @return 是 Workshop 链接返回 true，否则 false
     */
    public static boolean isWorkshopLink(String rawLink) {
        if (rawLink == null || rawLink.isBlank()) {
            return false;
        }
        try {
            parseWorkshopId(rawLink);
            return true;
        } catch (L4D2PluginException e) {
            return false;
        }
    }

    /**
     * 从 URI query 中获取指定参数值（手动解析以兼容 Java URI 不直接暴露 query 解析的问题）。
     */
    private static String getQueryParam(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            String key = idx >= 0 ? pair.substring(0, idx) : pair;
            String value = idx >= 0 ? pair.substring(idx + 1) : "";
            try {
                if (java.net.URLDecoder.decode(key, java.nio.charset.StandardCharsets.UTF_8).equals(name)) {
                    return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                // 忽略解码失败，继续下一项
            }
        }
        return null;
    }

    /**
     * 链接解析结果 record（仅描述类型与 ID，详情由 WorkshopDownloadService 进一步获取）。
     *
     * @param sourceType   源类型：{@link #SOURCE_WORKSHOP} 或 {@link #SOURCE_UNKNOWN}
     * @param sourceId     Workshop ID；非 Workshop 链接为 null
     * @param originalLink 原始链接
     */
    public record LinkParseResult(
            String sourceType,
            String sourceId,
            String originalLink
    ) {
    }
}

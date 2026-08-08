package com.gameplatform.plugin.l4d2.crawler;

import com.gameplatform.plugin.l4d2.crawler.dto.MapDetail;
import com.gameplatform.plugin.l4d2.crawler.dto.MapListItem;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.extension.DownloadLink;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * orangetage.com 站点爬虫实现。
 *
 * <p>列表页：{@code /map/}（第1页），{@code /map/{n}.html}（第2页起）。
 * <br>详情页：{@code /map/{YYYYMMDD}/{id}.html}。
 *
 * <p>站点编码为 GBK，Jsoup 会根据 meta charset 自动解码。
 *
 * <p>页面结构：
 * <ul>
 *   <li><b>列表页卡片</b>：{@code <dl class="down_list">} 包含
 *       {@code <div class="list_img"><a><img></a></div>}（缩略图）、
 *       {@code <dt><h5><a title="完整标题">标题</a></h5></dt>}（标题+详情链接）、
 *       {@code <dd class="down_txt">连写摘要</dd>}、
 *       {@code <dd class="down_attribute"><span>结构化字段</span></dd>}（地图大小/星级/更新时间）</li>
 *   <li><b>详情页信息表</b>：{@code <table class="down_info">} 内 th/td 键值对，
 *       caption h5 为标题，th 含"地图大小：""添加时间：""评论等级：""浏览次数："等</li>
 *   <li><b>详情页描述</b>：{@code <div class="down_intro">} 内含 h4 标题、img 截图、
 *       地图字段、文件名称、建图命令、提取码（按网盘分组，红色 span）</li>
 *   <li><b>详情页下载链接</b>：{@code <ul class="xz_a"><li><a href>渠道名</a></li></ul>}</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@Slf4j
@Component
public class OrangetageCrawler implements MapCrawler {

    public static final String SOURCE = "ORANGE";
    private static final String BASE_URL = "http://www.orangetage.com/map/";
    private static final String SITE_ORIGIN = "http://www.orangetage.com";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final int TIMEOUT_MS = 15000;

    /** 详情页 URL 中的数字 ID，如 /map/20260722/807.html → 807 */
    private static final Pattern SOURCE_ID_PATTERN = Pattern.compile("/(\\d+)\\.html$");
    /** 列表页 URL 中的页码，如 /map/3.html → 3 */
    private static final Pattern PAGE_NUM_PATTERN = Pattern.compile("/map/(\\d+)\\.html");
    /** 提取码/提取密码：xxx（任意位置，4~6 位字母数字）。
     *  注意：站点实际使用"提取密码"而非"提取码"，必须同时兼容两者。
     */
    private static final Pattern ACCESS_CODE_PATTERN =
            Pattern.compile("提取(?:密码|码)[：:]\\s*([A-Za-z0-9]{4,6})");
    /** VPK 文件名：文件名称：xxx.vpk */
    private static final Pattern VPK_PATTERN =
            Pattern.compile("文件名称[：:]\\s*([^\\s，。,\\n<>]+\\.vpk)", Pattern.CASE_INSENSITIVE);
    /** 建图命令：map xxx（按行匹配，避免误抓正文中 "map" 文本） */
    private static final Pattern MAP_CMD_PATTERN =
            Pattern.compile("(?im)^\\s*(?:第[^：]*?[：:]\\s*)?map\\s+([A-Za-z0-9_\\-]+)\\s*$");
    /** 列表摘要中按出现顺序排列的字段标签，用于切分连写文本 */
    private static final String[] SUMMARY_FIELDS = {
            "地图名称", "地图大小", "地图模式", "地图关卡", "地图类型",
            "地图作者", "地图日期", "更新时间", "地图星级", "星级推荐", "文件名称"
    };

    /** 详情页信息表 th 标签 → 内部 key */
    private static final Map<String, String> DETAIL_LABEL_MAP = new LinkedHashMap<>();

    static {
        DETAIL_LABEL_MAP.put("授权形式", "license");
        DETAIL_LABEL_MAP.put("授权", "license");
        DETAIL_LABEL_MAP.put("类型", "type");
        DETAIL_LABEL_MAP.put("地图类型", "type");
        DETAIL_LABEL_MAP.put("地图类别", "type");
        DETAIL_LABEL_MAP.put("语言", "language");
        DETAIL_LABEL_MAP.put("地图语言", "language");
        DETAIL_LABEL_MAP.put("大小", "fileSize");
        DETAIL_LABEL_MAP.put("地图大小", "fileSize");
        DETAIL_LABEL_MAP.put("平台", "platform");
        DETAIL_LABEL_MAP.put("支持平台", "platform");
        DETAIL_LABEL_MAP.put("添加时间", "addDate");
        DETAIL_LABEL_MAP.put("更新时间", "addDate");
        DETAIL_LABEL_MAP.put("星级", "starRating");
        DETAIL_LABEL_MAP.put("评论等级", "starRating");
        DETAIL_LABEL_MAP.put("星级推荐", "starRating");
        DETAIL_LABEL_MAP.put("浏览次数", "viewCount");
        DETAIL_LABEL_MAP.put("浏览", "viewCount");
        DETAIL_LABEL_MAP.put("作者", "author");
        DETAIL_LABEL_MAP.put("地图作者", "author");
        DETAIL_LABEL_MAP.put("地图日期", "mapDate");
        DETAIL_LABEL_MAP.put("日期", "mapDate");
    }

    /** 网盘渠道关键字（按 href 域名 / 链接文本判定） */
    private static final Map<String, String> CHANNEL_KEYWORDS = new LinkedHashMap<>();

    static {
        CHANNEL_KEYWORDS.put("百度网盘", "pan.baidu.com");
        CHANNEL_KEYWORDS.put("迅雷云盘", "pan.xunlei.com");
        CHANNEL_KEYWORDS.put("天翼云盘", "cloud.189.cn");
        CHANNEL_KEYWORDS.put("阿里云盘", "aliyundrive");
        CHANNEL_KEYWORDS.put("阿里云盘", "alipan.com");
        CHANNEL_KEYWORDS.put("OneDrive", "onedrive");
        CHANNEL_KEYWORDS.put("OneDrive", "1drv.ms");
        CHANNEL_KEYWORDS.put("MEGA", "mega.nz");
        CHANNEL_KEYWORDS.put("夸克网盘", "pan.quark.cn");
    }

    @Override
    public String getSource() {
        return SOURCE;
    }

    @Override
    public List<MapListItem> crawlAllListItems() {
        int total = getTotalPages();
        log.info("开始全量抓取列表，共 {} 页", total);
        List<MapListItem> all = new ArrayList<>();
        for (int page = 1; page <= total; page++) {
            try {
                List<MapListItem> items = crawlListPage(page);
                all.addAll(items);
                log.info("列表第 {}/{} 页抓取完成，本页 {} 条，累计 {} 条",
                        page, total, items.size(), all.size());
                if (page < total) {
                    sleepRandom();
                }
            } catch (Exception e) {
                log.warn("列表第 {} 页抓取失败，跳过: {}", page, e.getMessage());
            }
        }
        log.info("全量列表抓取完成，共 {} 条", all.size());
        return all;
    }

    @Override
    public List<MapListItem> crawlListPage(int page) {
        String url = (page <= 1) ? BASE_URL : BASE_URL + page + ".html";
        log.debug("抓取列表页: {}", url);
        Document doc = fetch(url);
        return parseListPage(doc);
    }

    @Override
    public int getTotalPages() {
        try {
            Document doc = fetch(BASE_URL);
            int max = 1;
            // 扫描分页区域 #pages 内的 a[href]，提取最大页码
            // 注意：a.a1 文本"805条"是总数不是页码，必须用 href 解析
            Elements links = doc.select("a[href]");
            Pattern alt = Pattern.compile("/map/(?:list_|index_)?(\\d+)\\.html");
            for (Element a : links) {
                String href = a.attr("abs:href");
                if (href == null || href.isEmpty()) {
                    href = a.attr("href");
                }
                if (href == null) {
                    continue;
                }
                Matcher m = PAGE_NUM_PATTERN.matcher(href);
                if (m.find()) {
                    try {
                        int n = Integer.parseInt(m.group(1));
                        if (n > max) {
                            max = n;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                    continue;
                }
                Matcher m2 = alt.matcher(href);
                if (m2.find()) {
                    try {
                        int n = Integer.parseInt(m2.group(1));
                        if (n > max) {
                            max = n;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            return max;
        } catch (Exception e) {
            log.warn("获取总页数失败，默认 1 页: {}", e.getMessage());
            return 1;
        }
    }

    @Override
    public MapDetail crawlDetail(String detailUrl) {
        if (detailUrl == null || detailUrl.isBlank()) {
            return null;
        }
        try {
            Document doc = fetch(detailUrl);
            return parseDetailPage(doc, detailUrl);
        } catch (Exception e) {
            log.warn("详情页抓取失败 url={}: {}", detailUrl, e.getMessage());
            return null;
        }
    }

    // ========== 列表页解析 ==========

    /**
     * 列表页解析：以 {@code <dl class="down_list">} 为卡片根，
     * 分别从 dt/div/dd 子元素提取标题、缩略图、摘要、结构化字段。
     */
    private List<MapListItem> parseListPage(Document doc) {
        List<MapListItem> result = new ArrayList<>();
        Map<String, MapListItem> dedup = new LinkedHashMap<>();

        for (Element dl : doc.select("dl.down_list")) {
            // 标题与详情链接：dt h5 a
            Element titleA = dl.selectFirst("dt h5 a, dt a, h5 a");
            if (titleA == null) {
                // 兜底：扫描 dl 内任意详情链接
                titleA = findFirstDetailLink(dl);
            }
            if (titleA == null) {
                continue;
            }

            String href = titleA.attr("abs:href");
            if (href == null || href.isEmpty()) {
                href = titleA.attr("href");
            }
            String sourceId = extractSourceId(href);
            if (sourceId == null) {
                continue;
            }
            if (dedup.containsKey(sourceId)) {
                continue;
            }

            MapListItem item = new MapListItem();
            item.setSourceId(sourceId);
            item.setDetailUrl(absolutize(href));

            // 标题：优先 title 属性（完整、未截断），其次 a 文本
            String title = titleA.attr("title");
            if (title == null || title.isBlank()) {
                title = text(titleA);
            }
            item.setTitle(title);

            // 缩略图：div.list_img img（列表卡片专用容器）
            Element img = dl.selectFirst("div.list_img img, .list_img img, img");
            if (img != null) {
                String src = img.attr("abs:src");
                if (src == null || src.isEmpty()) {
                    src = img.attr("src");
                }
                if (src != null && !src.isEmpty() && !src.startsWith("data:")) {
                    item.setThumbnailUrl(absolutize(src));
                }
            }

            // 摘要连写文本：dd.down_txt
            Element txtEl = dl.selectFirst("dd.down_txt, .down_txt");
            String summary = txtEl != null ? text(txtEl) : "";
            item.setSummary(summary);
            parseSummary(summary, item);

            // 结构化字段：dd.down_attribute 内 span（地图大小 / 星级推荐 / 更新时间）
            // 列表页 span 按"标签：值"格式，文本完整可靠，优先覆盖摘要解析结果
            Element attrEl = dl.selectFirst("dd.down_attribute, .down_attribute");
            if (attrEl != null) {
                parseListAttribute(attrEl, item);
            }

            dedup.put(sourceId, item);
            result.add(item);
        }
        return result;
    }

    /** 解析列表页 dd.down_attribute 中的结构化 span 字段 */
    private void parseListAttribute(Element attrEl, MapListItem item) {
        for (Element span : attrEl.select("span")) {
            String t = text(span);
            if (t == null || t.isBlank()) {
                continue;
            }
            // 兼容全角/半角冒号
            String label;
            String value;
            int colon = indexOfColon(t);
            if (colon > 0 && colon < t.length() - 1) {
                label = t.substring(0, colon).trim();
                value = t.substring(colon + 1).trim();
            } else {
                continue;
            }

            switch (label) {
                case "地图大小" -> {
                    if (!value.isEmpty()) {
                        item.setFileSize(value);
                    }
                }
                case "星级推荐", "地图星级" -> {
                    if (!value.isEmpty()) {
                        item.setStarRatingText(value);
                    }
                }
                case "更新时间" -> {
                    // 列表页 sourceUpdateDate 的权威来源（格式 YYYY-MM-DD）
                    if (!value.isEmpty()) {
                        item.setUpdateDate(value);
                    }
                }
                default -> { /* 忽略未知字段 */ }
            }
        }
    }

    /** 在 dl 内查找第一个匹配 /map/.../id.html 的链接 */
    private Element findFirstDetailLink(Element root) {
        for (Element a : root.select("a[href]")) {
            String href = a.attr("abs:href");
            if (href == null || href.isEmpty()) {
                href = a.attr("href");
            }
            if (SOURCE_ID_PATTERN.matcher(href).find()) {
                return a;
            }
        }
        return null;
    }

    /** 从详情页 URL 提取数字 ID */
    private String extractSourceId(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = SOURCE_ID_PATTERN.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 从连写摘要文本中切分各字段。
     * <p>文本形如：地图名称：xxx地图大小：834MB地图模式：合作 对抗地图关卡：4关...
     * <p>注意：摘要可能被截断，缺少"更新时间"等字段，
     * 因此列表页的更新时间应以 dd.down_attribute 中的 span 为准。
     */
    private void parseSummary(String summary, MapListItem item) {
        if (summary == null || summary.isBlank()) {
            return;
        }
        // 地图大小（仅在结构化字段缺失时作为兜底）
        String size = extractField(summary, "地图大小");
        if (size != null && !size.isBlank() && (item.getFileSize() == null || item.getFileSize().isBlank())) {
            item.setFileSize(size);
        }
        // 地图模式
        String modes = extractField(summary, "地图模式");
        if (modes != null && !modes.isBlank()) {
            List<String> modeList = new ArrayList<>();
            for (String s : modes.split("[\\s/、,，]+")) {
                if (!s.isBlank()) {
                    modeList.add(s.trim());
                }
            }
            if (!modeList.isEmpty()) {
                item.setGameModes(modeList);
            }
        }
        // 关卡数
        String chapter = extractField(summary, "地图关卡");
        if (chapter != null && !chapter.isBlank()) {
            item.setChapterCountText(chapter);
        }
        // 地图类型
        String type = extractField(summary, "地图类型");
        if (type != null && !type.isBlank()) {
            item.setMapType(type);
        }
        // 作者
        String author = extractField(summary, "地图作者");
        if (author != null && !author.isBlank()) {
            item.setAuthor(author);
        }
        // 地图日期（注意：摘要中可能没有"更新时间"，但有"地图日期"）
        String date = extractField(summary, "地图日期");
        if (date == null || date.isBlank()) {
            date = extractField(summary, "更新时间");
        }
        // 更新时间仅在结构化字段未提供时作为兜底
        if (date != null && !date.isBlank() && (item.getUpdateDate() == null || item.getUpdateDate().isBlank())) {
            item.setUpdateDate(date);
        }
        // 星级（兜底）
        String star = extractField(summary, "地图星级");
        if (star == null || star.isBlank()) {
            star = extractField(summary, "星级推荐");
        }
        if (star != null && !star.isBlank() && (item.getStarRatingText() == null || item.getStarRatingText().isBlank())) {
            item.setStarRatingText(star);
        }
    }

    /**
     * 通用字段切分：定位「field：」，截取到下一个已知字段标签之前。
     */
    private String extractField(String text, String field) {
        int start = indexOfLabel(text, field, 0);
        if (start < 0) {
            return null;
        }
        int valStart = start + field.length() + 1; // 跳过「：」
        if (valStart > text.length()) {
            return null;
        }
        int end = text.length();
        for (String f : SUMMARY_FIELDS) {
            if (f.equals(field)) {
                continue;
            }
            int idx = indexOfLabel(text, f, valStart);
            if (idx >= 0 && idx < end) {
                end = idx;
            }
        }
        return text.substring(valStart, end).trim();
    }

    /** 兼容全角/半角冒号定位「field：」 */
    private int indexOfLabel(String text, String field, int from) {
        int idx = text.indexOf(field + "：", from);
        if (idx >= 0) {
            return idx;
        }
        idx = text.indexOf(field + ":", from);
        return idx;
    }

    /** 查找文本中第一个全角/半角冒号位置 */
    private int indexOfColon(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '：' || c == ':') {
                return i;
            }
        }
        return -1;
    }

    // ========== 详情页解析 ==========

    private MapDetail parseDetailPage(Document doc, String detailUrl) {
        MapDetail detail = new MapDetail();

        // 标题：优先 table.down_info caption h5，其次 div.down_intro h4.tits，最后 h1 / title
        String fullTitle = null;
        Element captionH5 = doc.selectFirst("table.down_info caption h5, table.down_info caption");
        if (captionH5 != null) {
            fullTitle = text(captionH5);
        }
        if (fullTitle == null || fullTitle.isBlank()) {
            Element introH4 = doc.selectFirst("div.down_intro h4, div.down_intro h4.tits");
            if (introH4 != null) {
                fullTitle = text(introH4);
            }
        }
        if (fullTitle == null || fullTitle.isBlank()) {
            Element h1 = doc.selectFirst("h1");
            if (h1 != null) {
                fullTitle = text(h1);
            }
        }
        if (fullTitle == null || fullTitle.isBlank()) {
            fullTitle = doc.title();
        }
        if (fullTitle != null && !fullTitle.isBlank()) {
            // title 标签可能含站点后缀（如 " - 橙子游戏"），裁剪
            int dashIdx = fullTitle.lastIndexOf(" - ");
            if (dashIdx > 10) {
                fullTitle = fullTitle.substring(0, dashIdx).trim();
            }
            splitTitle(fullTitle, detail);
        }

        // 信息表：table.down_info 内 th/td 键值对
        Map<String, String> info = parseInfoTable(doc);
        applyInfo(detail, info);

        // 描述区域：div.down_intro（核心数据容器，含截图、字段、建图命令、提取码）
        Element introEl = doc.selectFirst("div.down_intro, .down_intro");
        String introText = introEl != null ? introEl.text() : "";
        String introHtml = introEl != null ? introHtml(introEl) : "";

        // 描述：截取 down_intro 内有效段落，去除截图 img 噪音
        detail.setDescription(extractDescriptionFromIntro(introEl, introText));

        // 建图命令：在 introHtml 中按行匹配 map xxx
        detail.setMapCommands(extractMapCommandsFromHtml(introHtml));

        // VPK 文件名：在 introText 中匹配"文件名称：xxx.vpk"
        detail.setVpkFileName(extractVpkFileName(introText));

        // 截图：div.down_intro 内的 img（游戏截图），排除小图标
        detail.setScreenshotUrls(extractScreenshots(introEl));

        // 下载链接 + 提取码
        // 1) 先从 introText 中按网盘渠道提取提取码：百度网盘提取码：exs9 / 迅雷云盘提取码：sjnh
        Map<String, String> channelToAccessCode = extractAccessCodes(introText);
        // 2) 再从 ul.xz_a a 中提取下载链接，按渠道关联提取码
        detail.setDownloadLinks(extractDownloadLinks(doc, channelToAccessCode));

        // 作者兜底：详情页信息表可能没有作者，从 introText 的"地图作者"提取
        if (detail.getAuthor() == null || detail.getAuthor().isBlank()) {
            String author = extractField(introText, "地图作者");
            if (author != null && !author.isBlank()) {
                detail.setAuthor(author);
            }
        }

        // 地图日期兜底：从 introText 的"地图日期"提取
        if (detail.getMapDate() == null || detail.getMapDate().isBlank()) {
            String mapDate = extractField(introText, "地图日期");
            if (mapDate != null && !mapDate.isBlank()) {
                detail.setMapDate(mapDate);
            }
        }

        if (detail.getAuthor() == null) {
            log.debug("详情页无作者字段（老地图），url={}", detailUrl);
        }
        return detail;
    }

    /** 拆分中英文标题：地球坠落 v1.0 (Earth Crash v1.0) 或 Last Hours v1.1 (最后几个小时 v1.1)
     *  站点标题格式不固定，需根据括号内外实际语言判定，避免中英文倒置。
     */
    private void splitTitle(String fullTitle, MapDetail detail) {
        String t = fullTitle.trim();
        Matcher m = Pattern.compile("(.+?)\\(([^)]+)\\)\\s*$").matcher(t);
        if (m.find()) {
            String part1 = m.group(1).trim();
            String part2 = m.group(2).trim();
            boolean p1Cn = part1.matches(".*[\\u4e00-\\u9fa5].*");
            boolean p2Cn = part2.matches(".*[\\u4e00-\\u9fa5].*");
            if (p1Cn && !p2Cn) {
                // 括号外中文，括号内英文
                detail.setTitleCn(part1);
                detail.setTitleEn(part2);
            } else if (p2Cn && !p1Cn) {
                // 括号外英文，括号内中文（站点常见格式）
                detail.setTitleEn(part1);
                detail.setTitleCn(part2);
            } else {
                // 都含中文或都不含中文，保留括号外作为主标题
                detail.setTitleCn(part1);
                if (!part1.equals(part2)) {
                    detail.setTitleEn(part2);
                }
            }
        } else {
            // 无括号，含中文视为中文标题，否则英文
            if (t.matches(".*[\\u4e00-\\u9fa5].*")) {
                detail.setTitleCn(t);
            } else {
                detail.setTitleEn(t);
            }
        }
    }

    /**
     * 解析信息表 table.down_info 内 th/td 键值对。
     * <p>结构：每行 tr 含 th（标签，带末尾冒号）和 td（值）。
     * <br>注意：值 td 前后可能有空白，需 trim。
     */
    private Map<String, String> parseInfoTable(Document doc) {
        Map<String, String> info = new LinkedHashMap<>();
        Element table = doc.selectFirst("table.down_info, .down_info");
        if (table == null) {
            // 兜底：扫描所有 .tit 标签
            return parseTitLabels(doc);
        }
        for (Element tr : table.select("tr")) {
            Element th = tr.selectFirst("th");
            if (th == null) {
                continue;
            }
            String label = text(th);
            if (label == null) {
                continue;
            }
            label = label.replaceAll("[：:]\\s*$", "").trim();
            if (label.isEmpty()) {
                continue;
            }
            // 值：th 同行的下一个 td
            Element td = th.nextElementSibling();
            if (td == null || !td.tagName().equalsIgnoreCase("td")) {
                // 兜底：tr 内第一个 td
                td = tr.selectFirst("td");
            }
            if (td == null) {
                continue;
            }
            String value = text(td);
            if (value != null && !value.isBlank()) {
                info.putIfAbsent(label, value.trim());
            }
        }
        return info;
    }

    /** 兜底：扫描 class="tit" 的标签元素，取其兄弟节点为值 */
    private Map<String, String> parseTitLabels(Document doc) {
        Map<String, String> info = new LinkedHashMap<>();
        for (Element tit : doc.select(".tit")) {
            String label = text(tit);
            if (label == null) {
                continue;
            }
            label = label.replaceAll("[：:]\\s*$", "").trim();
            if (label.isEmpty()) {
                continue;
            }
            String value = nextSiblingText(tit);
            if (value != null && !value.isBlank()) {
                info.putIfAbsent(label, value.trim());
            }
        }
        return info;
    }

    /** 获取元素下一个兄弟节点的文本 */
    private String nextSiblingText(Element el) {
        Element next = el.nextElementSibling();
        if (next != null) {
            String t = text(next);
            if (t != null && !t.isBlank()) {
                return t;
            }
        }
        Element parent = el.parent();
        if (parent != null) {
            Element pnext = parent.nextElementSibling();
            if (pnext != null) {
                String t = text(pnext);
                if (t != null && !t.isBlank()) {
                    return t;
                }
            }
        }
        return null;
    }

    /** 将信息表键值对应用到 MapDetail */
    private void applyInfo(MapDetail detail, Map<String, String> info) {
        for (Map.Entry<String, String> e : info.entrySet()) {
            String key = DETAIL_LABEL_MAP.get(e.getKey());
            if (key == null) {
                continue;
            }
            String val = e.getValue();
            switch (key) {
                case "type" -> detail.setType(val);
                case "language" -> detail.setLanguage(val);
                case "fileSize" -> detail.setFileSize(val);
                case "platform" -> detail.setPlatform(val);
                case "addDate" -> detail.setAddDate(val);
                case "license" -> detail.setLicense(val);
                case "author" -> detail.setAuthor(val);
                case "mapDate" -> detail.setMapDate(val);
                case "starRating" -> detail.setStarRating(parseStarRating(val));
                case "viewCount" -> detail.setViewCount(parseViewCount(val));
                default -> { /* 忽略未知字段 */ }
            }
        }
    }

    /** 字段行标签前缀（用于过滤 intro 内的结构化字段行，避免污染描述） */
    private static final Pattern FIELD_LINE_PATTERN = Pattern.compile(
            "^(地图名称|地图大小|地图模式|地图关卡|地图类型|地图作者|地图日期|更新时间|"
                    + "地图星级|星级推荐|文件名称|添加时间|类型|语言|大小|平台|支持平台|"
                    + "授权形式|授权|星级|评论等级|浏览次数|浏览|作者|日期|建图命令"
                    + "|第一关|第二关|第三关|第四关|第五关|第六关|第七关|第八关"
                    + "|提取密码|提取码"
                    + "|百度网盘|迅雷云盘|天翼云盘|阿里云盘|夸克网盘|OneDrive|MEGA|蓝奏云"
                    + ")[：:]?.*$");
    /** 网盘渠道+提取码行：百度网盘提取码：xxx / 迅雷云盘提取密码：xxx 等 */
    private static final Pattern ACCESS_CODE_LINE_PATTERN =
            Pattern.compile("^.*提取(?:密码|码)[：:].*$");
    /** 建图命令行：第N关：map xxx 或 map xxx */
    private static final Pattern MAP_CMD_LINE_PATTERN =
            Pattern.compile("^(?:第[^：]*[：:]\\s*)?map\\s+[A-Za-z0-9_\\-]+$", Pattern.CASE_INSENSITIVE);

    /**
     * 描述提取：从 div.down_intro 内取有效描述段落，过滤字段行/建图命令/提取码。
     * <p>down_intro 内含 h4 标题、img 截图、span 字段行、建图命令、提取密码等。
     * Jsoup text() 会丢失换行，导致所有内容连写。
     * <p>这里改用 introHtml 按 br 换行后逐行过滤，只保留描述性文字段落。
     * <p>若地图仅有字段无描述（如老地图），返回空字符串，前端可显示"暂无描述"。
     */
    private String extractDescriptionFromIntro(Element introEl, String introText) {
        if (introEl == null || introText == null || introText.isBlank()) {
            return "";
        }
        // 克隆元素，移除标题(h4/h5/h6)、图片、脚本、样式（已单独提取）
        Element introClone = introEl.clone();
        introClone.select("h4, h5, h6, img, script, style").remove();
        String html = introClone.html();
        if (html == null || html.isBlank()) {
            return "";
        }
        // 将 <br> 和块级元素边界替换为换行，便于按行过滤
        // 内联元素（span/strong/em/b/i/font 等）不加换行，避免割裂"提取密码：yd7q"等文本
        String normalized = html
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</?(p|div|li|tr|td|th|caption|table|tbody|thead|ul|ol|dl|dt|dd)[^>]*>", "\n");
        // 去除剩余 HTML 标签（内联标签等），保留纯文本
        String text = normalized.replaceAll("<[^>]+>", "");
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // 过滤字段行（地图名称：xxx / 地图大小：xxx / 百度网盘：xxx 等）
            if (FIELD_LINE_PATTERN.matcher(trimmed).matches()) {
                continue;
            }
            // 过滤提取码行（百度网盘提取码：xxx / 迅雷云盘提取密码：xxx 等）
            if (ACCESS_CODE_LINE_PATTERN.matcher(trimmed).matches()) {
                continue;
            }
            // 过滤建图命令行（map xxx / 第N关：map xxx）
            if (MAP_CMD_LINE_PATTERN.matcher(trimmed).matches()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(trimmed);
        }
        String result = sb.toString().trim();
        return result.length() > 2000 ? result.substring(0, 2000) : result;
    }

    /**
     * 建图命令：在 introHtml 中按行匹配 "map xxx"。
     * <p>down_intro 内建图命令格式：
     * <pre>
     * 建图命令<br/>
     * 第一关：map earthcrash_l4d2<br/>
     * 第二关：map 2_earhcrash_l4d2<br/>
     * </pre>
     * <p>按 br 换行后正则匹配更准确。
     */
    private List<String> extractMapCommandsFromHtml(String introHtml) {
        List<String> cmds = new ArrayList<>();
        if (introHtml == null || introHtml.isBlank()) {
            return cmds;
        }
        // 将 <br/> / <br> / <br /> 替换为换行，便于按行匹配
        String normalized = introHtml.replaceAll("(?i)<br\\s*/?>", "\n");
        Matcher m = MAP_CMD_PATTERN.matcher(normalized);
        while (m.find()) {
            String cmd = "map " + m.group(1);
            if (!cmds.contains(cmd)) {
                cmds.add(cmd);
            }
        }
        // 兜底：从原始 HTML 中匹配 map xxx
        if (cmds.isEmpty()) {
            Matcher m2 = Pattern.compile("map\\s+([A-Za-z0-9_\\-]+)").matcher(introHtml);
            while (m2.find()) {
                String cmd = "map " + m2.group(1);
                if (!cmds.contains(cmd)) {
                    cmds.add(cmd);
                }
            }
        }
        return cmds;
    }

    /** VPK 文件名：从文本中匹配"文件名称：xxx.vpk" */
    private String extractVpkFileName(String introText) {
        if (introText == null || introText.isBlank()) {
            return null;
        }
        Matcher m = VPK_PATTERN.matcher(introText);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * 提取码：按网盘渠道分组提取。
     * <p>down_intro 末尾格式：
     * <pre>
     * 百度网盘提取码：exs9
     * 迅雷云盘提取码：sjnh
     * 天翼云盘提取码：bx04
     * </pre>
     * <p>down_intro.text() 会丢失换行，但"渠道名+提取码："前缀可定位。
     */
    private Map<String, String> extractAccessCodes(String introText) {
        Map<String, String> result = new LinkedHashMap<>();
        if (introText == null || introText.isBlank()) {
            return result;
        }
        for (String channel : CHANNEL_KEYWORDS.keySet()) {
            // 匹配：百度网盘提取密码：exs9 / 百度网盘提取码：exs9（兼容两种写法）
            Pattern p = Pattern.compile(
                    Pattern.quote(channel) + "\\s*提取(?:密码|码)[：:]\\s*([A-Za-z0-9]{4,6})");
            Matcher m = p.matcher(introText);
            if (m.find()) {
                result.put(channel, m.group(1));
            }
        }
        // 兜底：匹配无渠道前缀的提取码（如老地图只有"提取码：abcd"）
        if (result.isEmpty()) {
            Matcher m = ACCESS_CODE_PATTERN.matcher(introText);
            if (m.find()) {
                result.put("", m.group(1));
            }
        }
        return result;
    }

    /**
     * 下载链接：从 ul.xz_a a 中提取，按渠道关联提取码。
     * <p>结构：
     * <pre>
     * &lt;ul class="l xz_a wrap blue"&gt;
     *   &lt;li&gt;&lt;a href='https://pan.baidu.com/s/xxx' target='_blank'&gt;百度网盘&lt;/a&gt;&lt;/li&gt;
     *   &lt;li&gt;&lt;a href='https://pan.xunlei.com/s/xxx' target='_blank'&gt;迅雷云盘&lt;/a&gt;&lt;/li&gt;
     * &lt;/ul&gt;
     * </pre>
     */
    private List<DownloadLink> extractDownloadLinks(Document doc, Map<String, String> channelToAccessCode) {
        List<DownloadLink> links = new ArrayList<>();
        // 优先从 ul.xz_a 提取
        Elements anchors = doc.select("ul.xz_a a[href], .xz_a a[href]");
        // 兜底：扫描所有 a[href]，按域名判定
        if (anchors.isEmpty()) {
            anchors = doc.select("a[href]");
        }
        for (Element a : anchors) {
            String href = a.attr("abs:href");
            if (href == null || href.isEmpty()) {
                href = a.attr("href");
            }
            if (href == null || href.isBlank()) {
                continue;
            }
            String channel = detectChannel(href, a);
            if (channel == null) {
                continue;
            }
            DownloadLink link = new DownloadLink();
            link.setChannel(channel);
            link.setShareUrl(absolutize(href));
            // 关联提取码：优先同名渠道，其次无渠道前缀的提取码
            String code = channelToAccessCode.get(channel);
            if (code == null) {
                code = channelToAccessCode.get("");
            }
            link.setAccessCode(code);
            links.add(link);
        }
        return links;
    }

    /** 按 href 域名 / 链接文本判定下载渠道 */
    private String detectChannel(String href, Element a) {
        String h = href.toLowerCase();
        for (Map.Entry<String, String> e : CHANNEL_KEYWORDS.entrySet()) {
            if (h.contains(e.getValue())) {
                return e.getKey();
            }
        }
        // 链接文本含网盘关键字
        String txt = text(a);
        if (txt != null) {
            if (txt.contains("百度")) {
                return "百度网盘";
            }
            if (txt.contains("迅雷")) {
                return "迅雷云盘";
            }
            if (txt.contains("天翼")) {
                return "天翼云盘";
            }
            if (txt.contains("阿里")) {
                return "阿里云盘";
            }
            if (txt.contains("夸克")) {
                return "夸克网盘";
            }
        }
        return null;
    }

    /** 截图：div.down_intro 内的 img，排除小图标 / logo */
    private List<String> extractScreenshots(Element introEl) {
        List<String> urls = new ArrayList<>();
        if (introEl == null) {
            return urls;
        }
        for (Element img : introEl.select("img[src]")) {
            String src = img.attr("abs:src");
            if (src == null || src.isEmpty()) {
                src = img.attr("src");
            }
            if (src == null || src.isBlank() || src.startsWith("data:")) {
                continue;
            }
            String low = src.toLowerCase();
            if (low.contains("logo") || low.contains("icon") || low.contains("avatar")
                    || low.contains("favicon") || low.contains("loading")) {
                continue;
            }
            int w = parseDim(img.attr("width"));
            int h = parseDim(img.attr("height"));
            // 排除 table.down_info 中的预览图（width=250 height=220）
            // 这里在 introEl 内，已经排除 table 中的图
            if (w > 0 && w < 60) {
                continue;
            }
            if (h > 0 && h < 60) {
                continue;
            }
            String abs = absolutize(src);
            if (!urls.contains(abs)) {
                urls.add(abs);
            }
        }
        return urls;
    }

    // ========== 工具方法 ==========

    /** 计数 ★ 字符数量作为星级（★=1，☆=0） */
    private Double parseStarRating(String val) {
        if (val == null) {
            return null;
        }
        long count = val.chars().filter(c -> c == '★').count();
        if (count > 0) {
            return (double) count;
        }
        try {
            return Double.parseDouble(val.replaceAll("[^0-9.]", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private Integer parseViewCount(String val) {
        if (val == null) {
            return null;
        }
        try {
            return Integer.parseInt(val.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private int parseDim(String dim) {
        if (dim == null || dim.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(dim.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private String text(Element el) {
        return el == null ? null : el.text();
    }

    /** 获取元素内部 HTML（保留 br 标签，便于按行解析建图命令） */
    private String introHtml(Element el) {
        if (el == null) {
            return "";
        }
        return el.html();
    }

    /** 将可能相对的 URL 转为绝对 URL */
    private String absolutize(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        if (url.startsWith("//")) {
            return "http:" + url;
        }
        if (url.startsWith("/")) {
            return SITE_ORIGIN + url;
        }
        return BASE_URL + url;
    }

    /** 发起 HTTP 请求并返回 Jsoup Document */
    private Document fetch(String url) {
        try {
            return Jsoup.connect(url)
                    .userAgent(UA)
                    .timeout(TIMEOUT_MS)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Referer", BASE_URL)
                    .get();
        } catch (Exception e) {
            throw new L4D2PluginException(L4D2PluginException.NETWORK,
                    "抓取页面失败: " + url, e);
        }
    }

    /** 随机休眠 2~3 秒，避免请求过快被站点封禁 */
    private void sleepRandom() {
        try {
            long ms = ThreadLocalRandom.current().nextLong(2000L, 3000L);
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

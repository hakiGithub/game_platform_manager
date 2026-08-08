package com.gameplatform.plugin.l4d2.task;

import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.l4d2.crawler.OrangetageCrawler;
import com.gameplatform.plugin.l4d2.crawler.dto.MapDetail;
import com.gameplatform.plugin.l4d2.crawler.dto.MapListItem;
import com.gameplatform.plugin.l4d2.extension.DownloadLink;
import com.gameplatform.plugin.l4d2.extension.MapResource;
import com.gameplatform.plugin.l4d2.extension.MapSpec;
import com.gameplatform.plugin.task.TaskContext;
import com.gameplatform.plugin.task.TaskHandler;
import com.gameplatform.plugin.task.TaskPayload;
import com.gameplatform.plugin.task.TaskResult;
import com.gameplatform.plugin.task.TaskSubmitContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 地图爬取任务处理器。
 *
 * <p>从 MapCenterService 迁移而来，将爬取逻辑封装为无状态 Handler，
 * 由任务中心统一调度、监控、重试。
 *
 * <p>任务参数（payload）：
 * <ul>
 *   <li>{@code crawlType}：FULL / INCREMENTAL（默认 FULL）</li>
 * </ul>
 *
 * <p>执行流程：
 * <ol>
 *   <li>获取总页数 → 抓取所有列表项（分页循环，支持取消/超时检查）</li>
 *   <li>逐条处理：增量跳过 / 抓详情 / 新增或更新 MapResource</li>
 *   <li>上报进度 + 记录日志</li>
 * </ol>
 *
 * <p>互斥：默认按 (source=L4D2, taskType=crawl) 互斥，同一时间仅一个爬取任务。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrawlTaskHandler implements TaskHandler {

    private final OrangetageCrawler crawler;
    private final ExtensionClient extensionClient;

    /** 文件大小文本 → 字节数，如 834MB / 1.2GB / 512KB */
    private static final Pattern SIZE_PATTERN =
            Pattern.compile("([\\d.]+)\\s*(KB|MB|GB|TB)", Pattern.CASE_INSENSITIVE);
    /** 关卡数文本 → 数字，如 4关 / 4 关 */
    private static final Pattern CHAPTER_PATTERN = Pattern.compile("(\\d+)");

    /** 超时：30 分钟（爬取含 2~3s/页休眠，总耗时较长） */
    private static final long DEFAULT_TIMEOUT_MS = 30 * 60 * 1000L;

    @Override
    public String getType() {
        return "crawl";
    }

    @Override
    public String getDisplayName() {
        return "地图爬取";
    }

    @Override
    public boolean isRetryable() {
        return true;
    }

    @Override
    public int getMaxRetryCount() {
        return 3;
    }

    @Override
    public long getDefaultTimeoutMs() {
        return DEFAULT_TIMEOUT_MS;
    }

    @Override
    public void onSubmit(TaskSubmitContext ctx) {
        String crawlType = ctx.getPayload().getString("crawlType", "FULL");
        if (!"FULL".equalsIgnoreCase(crawlType) && !"INCREMENTAL".equalsIgnoreCase(crawlType)) {
            throw new IllegalArgumentException("无效的爬取类型: " + crawlType + "，仅支持 FULL / INCREMENTAL");
        }
    }

    @Override
    public TaskResult execute(TaskContext context, TaskPayload payload) throws Exception {
        String crawlType = payload.getString("crawlType", "FULL");
        boolean incremental = "INCREMENTAL".equalsIgnoreCase(crawlType);

        context.log("开始" + (incremental ? "增量" : "全量") + "爬取，来源: " + crawler.getSource());

        // ===== 阶段 1：获取总页数 =====
        int totalPages = crawler.getTotalPages();
        context.log("总页数: " + totalPages);
        context.reportProgress(0, "开始抓取列表，共 " + totalPages + " 页");

        // ===== 阶段 2：分页抓取列表（30% 进度） =====
        // 跨页按 sourceId 去重：同一张地图可能在多个分类页（合作/对抗/生存）重复出现，
        // 页内已由 crawler.parseListPage 去重，但跨页累加会引入重复项，
        // 导致 total 数字虚高（如 4635 vs 实际 805 独立地图）并造成重复详情请求。
        List<MapListItem> allItems = new ArrayList<>();
        Set<String> seenSourceIds = new LinkedHashSet<>();
        int duplicateSkipped = 0;
        for (int page = 1; page <= totalPages; page++) {
            if (context.isCancelled()) {
                context.log("WARN", "任务已取消，停止列表抓取");
                return TaskResult.failure("任务已取消");
            }
            if (context.isTimeout()) {
                context.log("WARN", "任务已超时，停止列表抓取");
                return TaskResult.failure("任务执行超时");
            }
            try {
                List<MapListItem> items = crawler.crawlListPage(page);
                for (MapListItem item : items) {
                    String sid = item.getSourceId();
                    if (sid == null || sid.isBlank()) {
                        continue;
                    }
                    if (seenSourceIds.add(sid)) {
                        allItems.add(item);
                    } else {
                        duplicateSkipped++;
                    }
                }
            } catch (Exception e) {
                context.log("WARN", "第 " + page + " 页抓取失败: " + e.getMessage());
            }
            int progress = totalPages > 0 ? (int) (page * 30.0 / totalPages) : 30;
            context.reportProgress(progress,
                    "已抓取 " + page + "/" + totalPages + " 页，独立 " + allItems.size()
                            + " 条（跨页重复跳过 " + duplicateSkipped + "）");
        }
        context.log("列表抓取完成，独立 " + allItems.size()
                + " 条（跨页重复跳过 " + duplicateSkipped + "）");

        // ===== 阶段 3：逐条处理详情（30%→100%） =====
        // total 已是去重后的独立地图数（跨页重复已在阶段 2 跳过）
        int total = allItems.size();
        int newMaps = 0, updatedMaps = 0, skippedMaps = 0, failedMaps = 0;

        context.log("开始处理详情，独立地图 " + total + " 条（跨页重复已跳过 " + duplicateSkipped + "）");

        for (int i = 0; i < total; i++) {
            if (context.isCancelled()) {
                context.log("WARN", "任务已取消，停止详情处理");
                return buildCancelledResult(total, newMaps, updatedMaps, skippedMaps, failedMaps, crawlType);
            }
            if (context.isTimeout()) {
                context.log("WARN", "任务已超时，停止详情处理");
                return buildTimeoutResult(total, newMaps, updatedMaps, skippedMaps, failedMaps, crawlType);
            }

            MapListItem item = allItems.get(i);
            try {
                String result = processItem(item, incremental);
                switch (result) {
                    case "NEW" -> newMaps++;
                    case "UPDATED" -> updatedMaps++;
                    case "SKIPPED" -> skippedMaps++;
                    default -> failedMaps++;
                }
            } catch (Exception e) {
                failedMaps++;
                context.log("WARN", "处理地图失败 sourceId=" + item.getSourceId()
                        + ", url=" + item.getDetailUrl() + ": " + e.getMessage());
            }

            // 每 20 条或最后一条上报进度
            if ((i + 1) % 20 == 0 || i == total - 1) {
                int progress = 30 + (int) ((i + 1) * 70.0 / total);
                context.reportProgress(progress,
                        String.format("已处理 %d/%d（新增 %d，更新 %d，跳过 %d，失败 %d）",
                                i + 1, total, newMaps, updatedMaps, skippedMaps, failedMaps));
            }
        }

        context.reportProgress(100, "爬取完成");
        context.log("爬取完成: 独立 " + total + " 张，新增 " + newMaps
                + "，更新 " + updatedMaps + "，跳过 " + skippedMaps
                + "，失败 " + failedMaps + "（列表跨页重复跳过 " + duplicateSkipped + "）");

        Map<String, Object> data = new LinkedHashMap<>();
        // totalMaps：去重后的独立地图数（与数据库记录数一致）
        data.put("totalMaps", total);
        // duplicateSkipped：列表阶段跨页重复跳过的条目数（仅用于诊断，不参与 new+update+skip+failed 求和）
        data.put("duplicateSkipped", duplicateSkipped);
        data.put("newMaps", newMaps);
        data.put("updatedMaps", updatedMaps);
        data.put("skippedMaps", skippedMaps);
        data.put("failedMaps", failedMaps);
        data.put("totalPages", totalPages);
        data.put("crawlType", crawlType);
        data.put("source", crawler.getSource());

        return TaskResult.success(data);
    }

    @Override
    public String getResultSummary(TaskResult result) {
        if (result == null || !result.isSuccess()) {
            return result != null ? result.getMessage() : null;
        }
        Map<String, Object> data = result.getData();
        Object total = data.get("totalMaps");
        Object newMaps = data.get("newMaps");
        Object updated = data.get("updatedMaps");
        Object skipped = data.get("skippedMaps");
        Object failed = data.get("failedMaps");
        Object dup = data.get("duplicateSkipped");
        if (total == null) {
            return result.getMessage();
        }
        String base = String.format("成功爬取 %s 张独立地图（新增 %s，更新 %s，跳过 %s，失败 %s）",
                total, newMaps, updated, skipped, failed);
        if (dup instanceof Number n && n.intValue() > 0) {
            base += String.format("（列表跨页重复跳过 %s）", n);
        }
        return base;
    }

    // ==================== 核心处理逻辑 ====================

    /**
     * 处理单个地图条目：增量跳过 / 抓详情 / 新增或更新
     *
     * @return "NEW" / "UPDATED" / "SKIPPED" / "FAILED"
     */
    private String processItem(MapListItem item, boolean incremental) {
        String sourceId = item.getSourceId();
        if (sourceId == null || sourceId.isBlank()) {
            log.warn("跳过无 sourceId 的条目: {}", item.getDetailUrl());
            return "FAILED";
        }

        Optional<MapResource> existingOpt = extensionClient.get(MapResource.class, sourceId);

        // 增量：sourceUpdateDate 未变化则跳过，不抓详情
        if (incremental && existingOpt.isPresent()) {
            MapSpec oldSpec = existingOpt.get().getSpec();
            if (oldSpec != null && equalsStr(oldSpec.getSourceUpdateDate(), item.getUpdateDate())) {
                return "SKIPPED";
            }
        }

        MapDetail detail = null;
        try {
            detail = crawler.crawlDetail(item.getDetailUrl());
        } catch (Exception e) {
            log.warn("抓取详情失败 sourceId={}, url={}: {}", sourceId, item.getDetailUrl(), e.getMessage());
        }

        MapSpec spec = buildMapSpec(item, detail);
        if (existingOpt.isPresent()) {
            MapResource r = existingOpt.get();
            r.setSpec(spec);
            extensionClient.update(r);
            return "UPDATED";
        } else {
            MapResource r = new MapResource();
            r.setName(sourceId);
            r.setSpec(spec);
            r.setStatus("ACTIVE");
            extensionClient.create(r);
            return "NEW";
        }
    }

    // ==================== 资源 ↔ Spec 转换 ====================

    private MapSpec buildMapSpec(MapListItem item, MapDetail detail) {
        MapSpec spec = new MapSpec();
        spec.setSource(crawler.getSource());
        spec.setSourceUrl(item.getDetailUrl());
        spec.setCrawlTime(System.currentTimeMillis());

        // sourceUpdateDate 优先级：
        // 1) 详情页"添加时间"（地图实际入库时间，每张地图唯一）
        // 2) 列表页"更新时间"（站点首页刷新时间，同一天所有地图相同，仅作兜底）
        // 3) 详情页 URL 中的日期 /map/YYYYMMDD/id.html
        // 注意：列表页的"更新时间"是站点推荐/刷新时间，不代表地图实际更新时间
        String sourceUpdateDate = null;
        if (detail != null && detail.getAddDate() != null && !detail.getAddDate().isBlank()) {
            sourceUpdateDate = detail.getAddDate();
        } else if (item.getUpdateDate() != null && !item.getUpdateDate().isBlank()
                && isValidDate(item.getUpdateDate())) {
            sourceUpdateDate = item.getUpdateDate();
        } else {
            sourceUpdateDate = extractDateFromUrl(item.getDetailUrl());
        }
        spec.setSourceUpdateDate(sourceUpdateDate);

        // 列表项提供
        spec.setThumbnailUrl(item.getThumbnailUrl());
        if (item.getGameModes() != null && !item.getGameModes().isEmpty()) {
            spec.setGameModes(item.getGameModes());
        }
        spec.setFileSize(item.getFileSize());
        if (item.getFileSize() != null) {
            spec.setFileSizeBytes(parseFileSizeBytes(item.getFileSize()));
        }
        spec.setChapterCount(parseChapterCount(item.getChapterCountText()));
        spec.setMapType(item.getMapType());

        // 详情提供（覆盖列表项）
        if (detail != null) {
            if (detail.getTitleCn() != null) {
                spec.setTitleCn(detail.getTitleCn());
            } else {
                spec.setTitleCn(item.getTitle());
            }
            spec.setTitleEn(detail.getTitleEn());
            if (detail.getType() != null) {
                if (spec.getGameModes() == null || spec.getGameModes().isEmpty()) {
                    spec.setGameModes(splitModes(detail.getType()));
                }
            }
            if (detail.getFileSize() != null) {
                spec.setFileSize(detail.getFileSize());
                spec.setFileSizeBytes(parseFileSizeBytes(detail.getFileSize()));
            }
            spec.setAuthor(detail.getAuthor() != null ? detail.getAuthor() : item.getAuthor());
            spec.setMapDate(detail.getMapDate());
            spec.setVpkFileName(detail.getVpkFileName());
            spec.setStarRating(detail.getStarRating());
            spec.setPlatform(detail.getPlatform());
            spec.setLanguage(detail.getLanguage());
            spec.setLicense(detail.getLicense());
            spec.setDescription(detail.getDescription());
            spec.setMapCommands(detail.getMapCommands());
            spec.setScreenshotUrls(detail.getScreenshotUrls());
            spec.setDownloadLinks(detail.getDownloadLinks());
            spec.setViewCount(detail.getViewCount());
        } else {
            // 详情缺失时用列表项兜底
            spec.setTitleCn(item.getTitle());
            spec.setAuthor(item.getAuthor());
            spec.setStarRating(parseStarRatingText(item.getStarRatingText()));
        }
        return spec;
    }

    // ==================== 工具方法 ====================

    /** 校验日期格式是否为 YYYY-MM-DD（防止"2015-04-22文件名称："这类脏数据） */
    private boolean isValidDate(String date) {
        if (date == null || date.isBlank()) {
            return false;
        }
        return date.matches("\\d{4}-\\d{2}-\\d{2}");
    }

    /** 从详情页 URL 提取日期：/map/20231218/638.html → 2023-12-18 */
    private String extractDateFromUrl(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = Pattern.compile("/map/(\\d{4})(\\d{2})(\\d{2})/").matcher(url);
        return m.find() ? m.group(1) + "-" + m.group(2) + "-" + m.group(3) : null;
    }

    private boolean equalsStr(String a, String b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b);
    }

    private List<String> splitModes(String type) {
        List<String> modes = new ArrayList<>();
        if (type == null) {
            return modes;
        }
        for (String s : type.split("[\\s/、,，]+")) {
            if (!s.isBlank()) {
                modes.add(s.trim());
            }
        }
        return modes;
    }

    /** "834MB" → 字节数 */
    private Long parseFileSizeBytes(String sizeText) {
        if (sizeText == null || sizeText.isBlank()) {
            return null;
        }
        Matcher m = SIZE_PATTERN.matcher(sizeText);
        if (!m.find()) {
            return null;
        }
        try {
            double num = Double.parseDouble(m.group(1));
            String unit = m.group(2).toUpperCase();
            return switch (unit) {
                case "KB" -> (long) (num * 1024);
                case "MB" -> (long) (num * 1024 * 1024);
                case "GB" -> (long) (num * 1024 * 1024 * 1024);
                case "TB" -> (long) (num * 1024L * 1024 * 1024 * 1024);
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    /** "4关" → 4 */
    private Integer parseChapterCount(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = CHAPTER_PATTERN.matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** "★★★★☆" → 4.0 */
    private Double parseStarRatingText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        long count = text.chars().filter(c -> c == '★').count();
        return count > 0 ? (double) count : null;
    }

    // ==================== 中断结果构建 ====================

    private TaskResult buildCancelledResult(int total, int newMaps, int updatedMaps,
                                            int skippedMaps, int failedMaps, String crawlType) {
        return buildInterruptedResult("任务已取消", "cancelled", total, newMaps, updatedMaps,
                skippedMaps, failedMaps, crawlType);
    }

    private TaskResult buildTimeoutResult(int total, int newMaps, int updatedMaps,
                                         int skippedMaps, int failedMaps, String crawlType) {
        return buildInterruptedResult("任务执行超时", "timeout", total, newMaps, updatedMaps,
                skippedMaps, failedMaps, crawlType);
    }

    /**
     * 构建中断场景的结果数据（取消 / 超时共用）
     *
     * <p>注意：total 已是去重后的独立地图数（与最终入库的 sourceId 数一致），
     * 不包含列表阶段跨页跳过的重复条目。
     */
    private TaskResult buildInterruptedResult(String message, String flagKey,
                                             int total, int newMaps, int updatedMaps,
                                             int skippedMaps, int failedMaps, String crawlType) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalMaps", total);
        data.put("newMaps", newMaps);
        data.put("updatedMaps", updatedMaps);
        data.put("skippedMaps", skippedMaps);
        data.put("failedMaps", failedMaps);
        data.put("crawlType", crawlType);
        data.put("source", crawler.getSource());
        data.put(flagKey, true);
        return TaskResult.failure(message, data);
    }
}

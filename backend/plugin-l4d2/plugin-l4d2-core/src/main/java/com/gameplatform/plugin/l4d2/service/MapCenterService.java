package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.common.result.PageResult;
import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.extension.ListOptions;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.extension.MapResource;
import com.gameplatform.plugin.l4d2.extension.MapSpec;
import com.gameplatform.plugin.l4d2.vo.CrawlStatusVO;
import com.gameplatform.plugin.l4d2.vo.MapCenterVO;
import com.gameplatform.plugin.task.TaskQuery;
import com.gameplatform.plugin.task.TaskService;
import com.gameplatform.plugin.task.TaskSubmitRequest;
import com.gameplatform.plugin.task.TaskVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 地图中心服务：地图浏览查询与爬虫任务调度。
 *
 * <p>地图数据以 {@link MapResource} 扩展资源持久化（name=sourceId）。
 * 爬取任务通过 {@link TaskService} 提交到任务中心统一调度（ADR-025），
 * 爬取执行逻辑见 {@link com.gameplatform.plugin.l4d2.task.CrawlTaskHandler}。
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@Slf4j
@Service
public class MapCenterService {

    private final ExtensionClient extensionClient;
    private final TaskService taskService;

    public MapCenterService(ExtensionClient extensionClient,
                           TaskService taskService) {
        this.extensionClient = extensionClient;
        this.taskService = taskService;
    }

    // ========== 查询 ==========

    /**
     * 分页查询地图列表。
     *
     * <p>source、mode 通过 specFilter 下推过滤；keyword 需在 titleCn / titleEn / author 间 OR 匹配，
     * 由于 specFilter 仅支持 AND，关键字过滤在内存中完成，再排序与分页。
     */
    public PageResult<MapCenterVO> listMaps(String source, String keyword, String mode,
                                            Integer page, Integer size, String sort) {
        int pageNum = (page == null || page < 1) ? 1 : page;
        int pageSize = (size == null || size < 1) ? 20 : size;

        ListOptions.Builder builder = ListOptions.builder().limit(Integer.MAX_VALUE).offset(0);
        if (source != null && !source.isBlank()) {
            builder.specFilter("$.source", "=", source);
        }
        if (mode != null && !mode.isBlank()) {
            builder.specFilter("$.gameModes", "like", mode);
        }

        List<MapResource> resources = extensionClient.list(MapResource.class, builder.build());
        List<MapCenterVO> vos = new ArrayList<>();
        if (resources != null) {
            for (MapResource r : resources) {
                if (r.getSpec() == null) {
                    continue;
                }
                vos.add(convertToVO(r));
            }
        }

        // 关键字 OR 过滤（titleCn / titleEn / author）
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim().toLowerCase();
            vos.removeIf(vo -> !containsIgnoreCase(vo.getTitleCn(), kw)
                    && !containsIgnoreCase(vo.getTitleEn(), kw)
                    && !containsIgnoreCase(vo.getAuthor(), kw)
                    && !containsIgnoreCase(vo.getVpkFileName(), kw));
        }

        // 排序
        sortMaps(vos, sort);

        long total = vos.size();
        int from = (pageNum - 1) * pageSize;
        int to = Math.min(from + pageSize, vos.size());
        List<MapCenterVO> pageRecords = (from >= vos.size())
                ? new ArrayList<>() : new ArrayList<>(vos.subList(from, to));
        return new PageResult<>(pageRecords, total, pageNum, pageSize);
    }

    /** 获取单个地图详情 */
    public MapCenterVO getMap(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "sourceId 不能为空");
        }
        Optional<MapResource> opt = extensionClient.get(MapResource.class, sourceId);
        if (opt.isEmpty()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "地图不存在: " + sourceId);
        }
        return convertToVO(opt.get());
    }

    // ========== 爬虫任务调度 ==========

    /**
     * 触发爬取（异步）。通过任务中心提交，返回任务ID。
     *
     * <p>提交后由 {@link com.gameplatform.plugin.l4d2.task.CrawlTaskHandler} 异步执行，
     * 任务进度、日志、状态可通过任务中心统一查询。
     *
     * @param type FULL 或 INCREMENTAL
     * @return 任务ID
     */
    public String triggerCrawl(String type) {
        String crawlType = (type == null || type.isBlank()) ? "FULL" : type.toUpperCase();
        if (!"FULL".equals(crawlType) && !"INCREMENTAL".equals(crawlType)) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "无效的爬取类型: " + type + "，仅支持 FULL / INCREMENTAL");
        }

        TaskSubmitRequest request = TaskSubmitRequest.builder()
                .taskType("crawl")
                .source("L4D2")
                .scopeType("GLOBAL")
                .payload(Map.of("crawlType", crawlType))
                .build();

        String taskId = taskService.submit(request);
        log.info("触发地图爬取任务 taskId={}, crawlType={}", taskId, crawlType);
        return taskId;
    }

    /** 全量爬取 */
    public String crawlFull() {
        return triggerCrawl("FULL");
    }

    /** 增量爬取 */
    public String crawlIncremental() {
        return triggerCrawl("INCREMENTAL");
    }

    /**
     * 查询当前/最近一次爬取任务状态。
     *
     * <p>从任务中心查询最新的 crawl 类型任务，映射为 {@link CrawlStatusVO}。
     * 运行中任务仅返回 status/progress 概要；已完成任务从 result 中提取详细计数。
     */
    public CrawlStatusVO getCrawlStatus() {
        TaskQuery query = TaskQuery.builder()
                .source("L4D2")
                .taskType("crawl")
                .page(1)
                .size(1)
                .build();
        PageResult<TaskVO> result = taskService.listTasks(query);
        if (result == null || result.getRecords() == null || result.getRecords().isEmpty()) {
            CrawlStatusVO empty = new CrawlStatusVO();
            empty.setStatus("COMPLETED");
            empty.setTotalPages(0);
            empty.setProcessedPages(0);
            empty.setTotalMaps(0);
            empty.setNewMaps(0);
            empty.setUpdatedMaps(0);
            empty.setSkippedMaps(0);
            empty.setFailedMaps(0);
            return empty;
        }
        return toStatusVO(result.getRecords().get(0));
    }

    // ========== 资源 → VO 转换 ==========

    private CrawlStatusVO toStatusVO(TaskVO task) {
        CrawlStatusVO vo = new CrawlStatusVO();
        vo.setTaskId(task.getId());
        vo.setStatus(task.getStatus());
        vo.setErrorMessage(task.getErrorMessage());
        vo.setStartTime(asEpochMillis(task.getStartedAt()));
        vo.setEndTime(asEpochMillis(task.getCompletedAt()));

        // 从 payload 提取 crawlType
        Object payload = task.getPayload();
        if (payload instanceof Map<?, ?> map) {
            Object crawlType = map.get("crawlType");
            vo.setTaskType(crawlType != null ? crawlType.toString() : "FULL");
        } else {
            vo.setTaskType("FULL");
        }

        // 从 result 提取详细计数（仅终态任务有 result）
        Object resultObj = task.getResult();
        if (resultObj instanceof Map<?, ?> resultMap) {
            vo.setTotalPages(asInt(resultMap.get("totalPages")));
            vo.setTotalMaps(asInt(resultMap.get("totalMaps")));
            vo.setNewMaps(asInt(resultMap.get("newMaps")));
            vo.setUpdatedMaps(asInt(resultMap.get("updatedMaps")));
            vo.setSkippedMaps(asInt(resultMap.get("skippedMaps")));
            vo.setFailedMaps(asInt(resultMap.get("failedMaps")));
            Object source = resultMap.get("source");
            if (source != null) {
                vo.setSource(source.toString());
            }
            // processedPages = totalPages（终态时全部处理完）
            if (vo.getTotalPages() != null) {
                vo.setProcessedPages(vo.getTotalPages());
            }
        } else {
            // 运行中或无 result：设置默认值
            vo.setTotalPages(0);
            vo.setProcessedPages(0);
            vo.setTotalMaps(0);
            vo.setNewMaps(0);
            vo.setUpdatedMaps(0);
            vo.setSkippedMaps(0);
            vo.setFailedMaps(0);
        }
        return vo;
    }

    private MapCenterVO convertToVO(MapResource r) {
        MapCenterVO vo = new MapCenterVO();
        vo.setId(r.getId());
        vo.setSourceId(r.getName());
        MapSpec s = r.getSpec();
        if (s == null) {
            return vo;
        }
        vo.setSource(s.getSource());
        vo.setSourceUrl(s.getSourceUrl());
        vo.setTitleCn(s.getTitleCn());
        vo.setTitleEn(s.getTitleEn());
        vo.setFileSize(s.getFileSize());
        vo.setFileSizeBytes(s.getFileSizeBytes());
        vo.setGameModes(s.getGameModes());
        vo.setChapterCount(s.getChapterCount());
        vo.setMapType(s.getMapType());
        vo.setAuthor(s.getAuthor());
        vo.setMapDate(s.getMapDate());
        vo.setVpkFileName(s.getVpkFileName());
        vo.setStarRating(s.getStarRating());
        vo.setPlatform(s.getPlatform());
        vo.setLanguage(s.getLanguage());
        vo.setLicense(s.getLicense());
        vo.setDescription(s.getDescription());
        vo.setMapCommands(s.getMapCommands());
        vo.setThumbnailUrl(s.getThumbnailUrl());
        vo.setScreenshotUrls(s.getScreenshotUrls());
        vo.setDownloadLinks(s.getDownloadLinks());
        vo.setViewCount(s.getViewCount());
        vo.setSourceUpdateDate(s.getSourceUpdateDate());
        vo.setCrawlTime(s.getCrawlTime());
        return vo;
    }

    // ========== 工具方法 ==========

    private void sortMaps(List<MapCenterVO> vos, String sort) {
        if (sort == null || sort.isBlank()) {
            sort = "updateDateDesc";
        }
        final String s = sort.toLowerCase();
        Comparator<MapCenterVO> cmp = switch (s) {
            // 前端实际使用的排序值（与 MapCenter.vue 一致）
            case "updatedatedesc" -> Comparator.comparing(MapCenterVO::getSourceUpdateDate,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            case "filesizedesc" -> Comparator.comparing(MapCenterVO::getFileSizeBytes,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            case "ratingdesc" -> Comparator.comparing(MapCenterVO::getStarRating,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            case "viewcountdesc" -> Comparator.comparing(MapCenterVO::getViewCount,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            // 兼容旧值（Swagger 文档）
            case "rating" -> Comparator.comparing(MapCenterVO::getStarRating,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            case "size" -> Comparator.comparing(MapCenterVO::getFileSizeBytes,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            case "views" -> Comparator.comparing(MapCenterVO::getViewCount,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            case "name" -> Comparator.comparing(MapCenterVO::getTitleCn,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "newest" -> Comparator.comparing(MapCenterVO::getCrawlTime,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            default -> Comparator.comparing(MapCenterVO::getSourceUpdateDate,
                    Comparator.nullsLast(Comparator.reverseOrder()));
        };
        vos.sort(cmp);
    }

    private boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword);
    }

    private Long asEpochMillis(java.time.LocalDateTime time) {
        if (time == null) {
            return null;
        }
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private Integer asInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.dto.WorkshopDownloadDTO;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.util.LinkParser;
import com.gameplatform.plugin.l4d2.util.SteamApiClient;
import com.gameplatform.plugin.l4d2.vo.LinkParseItemVO;
import com.gameplatform.plugin.l4d2.vo.LinkParseResultVO;
import com.gameplatform.plugin.l4d2.vo.WorkshopItemVO;
import com.gameplatform.plugin.l4d2.vo.WorkshopParseResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workshop 下载服务：解析 Workshop 链接、创建下载任务（含合集与依赖展开）。
 *
 * <p>对齐源项目 {@code workshop.go:38-85 ParseWorkshopDownloadLink} 与
 * {@code link_parser.go ParseDownloadLink}。
 *
 * <p>核心流程：
 * <ol>
 *   <li>{@link #parseWorkshop(String)}：URL/ID → Workshop 文件详情列表</li>
 *   <li>{@link #createWorkshopTasks(WorkshopDownloadDTO)}：解析 + 批量创建下载任务</li>
 *   <li>{@link #parseLink(String)}：通用链接解析（先尝试 Workshop，不支持则返回 unknown）</li>
 * </ol>
 *
 * <p>持久化通过 {@link DownloadService} 完成（双层存储：内存 + DB），
 * 本服务不直接调用 {@code ExtensionClient}。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkshopDownloadService {

    /** PENDING_MANUAL 状态任务的默认备注（提示用户配置代理 URL） */
    private static final String PENDING_MANUAL_REMARK =
            "Steam API 未返回 file_url，请配置代理 URL：plugin.l4d2.workshop.proxy-url";

    private final SteamApiClient steamApiClient;
    private final DownloadService downloadService;
    private final L4D2Config config;

    /**
     * 解析 Workshop URL/ID 为可下载项列表（对齐源 workshop.go:38-85）。
     *
     * @param workshopUrlOrId Workshop URL 或纯数字 ID
     * @return 解析结果（sourceId + items 列表）
     * @throws L4D2PluginException 链接无效、Steam API 调用失败、未找到工坊文件时抛出
     */
    public WorkshopParseResultVO parseWorkshop(String workshopUrlOrId) {
        String id = LinkParser.parseWorkshopId(workshopUrlOrId);
        List<SteamApiClient.WorkshopDetail> details = steamApiClient.getPublishedFileDetails(List.of(id));
        if (details == null || details.isEmpty()) {
            throw new L4D2PluginException(L4D2PluginException.EXTERNAL_API, "未找到工坊文件");
        }
        SteamApiClient.WorkshopDetail parent = details.get(0);

        // 合集/依赖展开
        List<SteamApiClient.WorkshopDetail> items = new ArrayList<>();
        if (parent.childrenIds() != null && !parent.childrenIds().isEmpty()) {
            List<SteamApiClient.WorkshopDetail> childDetails =
                    steamApiClient.getPublishedFileDetails(parent.childrenIds());
            if (childDetails == null) {
                childDetails = List.of();
            }
            // 父项 file_url 非空 → 加入列表开头；再加所有子项
            // 父项 file_url 为空 → 仅用子项
            if (parent.fileUrl() != null && !parent.fileUrl().isBlank()) {
                items.add(parent);
            }
            items.addAll(childDetails);
        } else {
            // 无子项：仅用 parent
            items.add(parent);
        }

        // 过滤无效项：result != 1 或 publishedFileId 为空
        List<SteamApiClient.WorkshopDetail> filtered = new ArrayList<>();
        for (SteamApiClient.WorkshopDetail item : items) {
            if (item.result() != 1) {
                continue;
            }
            if (item.publishedFileId() == null || item.publishedFileId().isBlank()) {
                continue;
            }
            filtered.add(item);
        }

        // 去重：按 publishedFileId 去重，保留质量分数最高者（对齐源 normalizeWorkshopItems）
        Map<String, SteamApiClient.WorkshopDetail> bestById = new LinkedHashMap<>();
        Map<String, Integer> bestScoreById = new LinkedHashMap<>();
        for (SteamApiClient.WorkshopDetail item : filtered) {
            String pid = item.publishedFileId();
            int score = workshopItemQualityScore(item);
            Integer prevScore = bestScoreById.get(pid);
            if (prevScore == null) {
                bestById.put(pid, item);
                bestScoreById.put(pid, score);
            } else if (score > prevScore) {
                bestById.put(pid, item);
                bestScoreById.put(pid, score);
            }
        }

        // 转换为 VO
        List<WorkshopItemVO> voList = new ArrayList<>(bestById.size());
        for (SteamApiClient.WorkshopDetail item : bestById.values()) {
            voList.add(toWorkshopItemVO(item));
        }

        WorkshopParseResultVO vo = new WorkshopParseResultVO();
        vo.setSourceId(id);
        vo.setItems(voList);
        return vo;
    }

    /**
     * 创建 Workshop 下载任务（支持合集，返回多任务 ID）。
     *
     * <p>对每个解析出的 item：
     * <ul>
     *   <li>{@code hasFileUrl=true}：调用 {@link DownloadService#createWorkshopTask} 创建异步下载任务</li>
     *   <li>{@code hasFileUrl=false}：调用 {@link DownloadService#createManualTask} 创建 PENDING_MANUAL 任务</li>
     * </ul>
     *
     * @param dto 下载请求（instanceId + workshopUrlOrId）
     * @return 任务 ID 列表（每个 item 一个任务）
     */
    public List<String> createWorkshopTasks(WorkshopDownloadDTO dto) {
        if (dto == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "下载请求不能为空");
        }
        if (dto.getInstanceId() == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "instanceId 不能为空");
        }
        if (dto.getWorkshopUrlOrId() == null || dto.getWorkshopUrlOrId().isBlank()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "workshopUrlOrId 不能为空");
        }

        WorkshopParseResultVO parseResult = parseWorkshop(dto.getWorkshopUrlOrId());
        List<WorkshopItemVO> items = parseResult.getItems();
        if (items == null || items.isEmpty()) {
            throw new L4D2PluginException(L4D2PluginException.EXTERNAL_API, "未找到可下载的工坊文件");
        }

        String proxyUrl = config.getWorkshop().getProxyUrl();
        List<String> taskIds = new ArrayList<>(items.size());
        for (WorkshopItemVO item : items) {
            String taskId;
            if (item.isHasFileUrl()) {
                taskId = downloadService.createWorkshopTask(
                        dto.getInstanceId(),
                        item.getPublishedFileId(),
                        item.getTitle(),
                        item.getPreviewUrl(),
                        item.getFileUrl(),
                        item.getFilename(),
                        proxyUrl
                );
            } else {
                taskId = downloadService.createManualTask(
                        dto.getInstanceId(),
                        item.getPublishedFileId(),
                        item.getTitle(),
                        item.getPreviewUrl(),
                        PENDING_MANUAL_REMARK
                );
            }
            taskIds.add(taskId);
        }
        log.info("创建 Workshop 任务批量完成: sourceId={}, 任务数={}, instanceId={}",
                parseResult.getSourceId(), taskIds.size(), dto.getInstanceId());
        return taskIds;
    }

    /**
     * 通用链接解析：先尝试 Workshop，不支持则返回 sourceType="unknown"。
     *
     * <p>对齐源项目 {@code link_parser.go:33-48 ParseDownloadLink}：本插件当前仅支持 Workshop 类型。
     *
     * @param url 待解析的链接
     * @return 解析结果（sourceType=workshop 时含详细 items；sourceType=unknown 时 items 为空列表）
     */
    public LinkParseResultVO parseLink(String url) {
        LinkParser.LinkParseResult parseResult = LinkParser.parse(url);
        LinkParseResultVO vo = new LinkParseResultVO();
        vo.setSourceType(parseResult.sourceType());
        vo.setSourceId(parseResult.sourceId());
        vo.setOriginalLink(parseResult.originalLink());

        if (LinkParser.SOURCE_WORKSHOP.equals(parseResult.sourceType())) {
            // 进一步解析 Workshop 返回详细 items
            WorkshopParseResultVO workshopResult = parseWorkshop(url);
            List<WorkshopItemVO> workshopItems = workshopResult.getItems();
            List<LinkParseItemVO> linkItems = new ArrayList<>(
                    workshopItems == null ? 0 : workshopItems.size());
            if (workshopItems != null) {
                for (WorkshopItemVO w : workshopItems) {
                    LinkParseItemVO item = new LinkParseItemVO();
                    item.setId(w.getPublishedFileId());
                    item.setTitle(w.getTitle());
                    item.setFilename(w.getFilename());
                    item.setFileSize(w.getFileSize());
                    item.setFileUrl(w.getFileUrl());
                    item.setPreviewUrl(w.getPreviewUrl());
                    item.setReferer(config.getWorkshop().getProxyUrl());
                    item.setSupported(w.isHasFileUrl());
                    item.setDisabledReason(w.isHasFileUrl() ? null : PENDING_MANUAL_REMARK);
                    linkItems.add(item);
                }
            }
            vo.setItems(linkItems);
        } else {
            vo.setItems(List.of());
        }
        return vo;
    }

    // ===== 私有辅助方法 =====

    /**
     * WorkshopDetail → WorkshopItemVO 转换。
     */
    private WorkshopItemVO toWorkshopItemVO(SteamApiClient.WorkshopDetail item) {
        WorkshopItemVO vo = new WorkshopItemVO();
        vo.setPublishedFileId(item.publishedFileId());
        vo.setTitle(item.title());
        vo.setFilename(item.fileName());
        vo.setFileSize(String.valueOf(item.fileSize()));
        vo.setFileUrl(item.fileUrl());
        vo.setPreviewUrl(item.previewUrl());
        vo.setHasFileUrl(item.fileUrl() != null && !item.fileUrl().isBlank());
        return vo;
    }

    /**
     * 计算质量分数（对齐源 workshop.go:204-219 workshopItemQualityScore）：
     * <ul>
     *   <li>title 非空 +4</li>
     *   <li>filename 非空 +3</li>
     *   <li>fileSize &gt; 0 +2</li>
     *   <li>previewUrl 非空 +1</li>
     * </ul>
     */
    private int workshopItemQualityScore(SteamApiClient.WorkshopDetail item) {
        int score = 0;
        if (item.title() != null && !item.title().isBlank()) {
            score += 4;
        }
        if (item.fileName() != null && !item.fileName().isBlank()) {
            score += 3;
        }
        if (item.fileSize() > 0) {
            score += 2;
        }
        if (item.previewUrl() != null && !item.previewUrl().isBlank()) {
            score += 1;
        }
        return score;
    }
}

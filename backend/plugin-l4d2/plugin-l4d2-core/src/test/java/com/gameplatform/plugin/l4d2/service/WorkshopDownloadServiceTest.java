package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.dto.WorkshopDownloadDTO;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.util.SteamApiClient;
import com.gameplatform.plugin.l4d2.vo.LinkParseResultVO;
import com.gameplatform.plugin.l4d2.vo.WorkshopItemVO;
import com.gameplatform.plugin.l4d2.vo.WorkshopParseResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorkshopDownloadService 单元测试（对齐 plan §4.2.6）。
 *
 * <p>SteamApiClient 与 DownloadService 均被 mock，仅测试解析逻辑与任务委派逻辑。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkshopDownloadServiceTest {

    @Mock
    private SteamApiClient steamApiClient;

    @Mock
    private DownloadService downloadService;

    private L4D2Config config;

    private WorkshopDownloadService service;

    @BeforeEach
    void setUp() {
        config = new L4D2Config();
        config.getSteam().setApiKey("test-key");
        config.getWorkshop().setProxyUrl("https://proxy.example.com/");
        service = new WorkshopDownloadService(steamApiClient, downloadService, config);

        // 默认让 downloadService 返回假 taskId
        when(downloadService.createWorkshopTask(anyLong(), anyString(), anyString(), any(),
                        anyString(), any(), any()))
                .thenReturn("fake-task-id");
        when(downloadService.createManualTask(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn("fake-manual-id");
    }

    // ============================================================
    // parse_workshop_single：单个 detail（无 children）
    // ============================================================

    @Test
    void parse_workshop_single() {
        SteamApiClient.WorkshopDetail detail = new SteamApiClient.WorkshopDetail(
                "123456", 1, "Test Map", "test.vpk", 12345L,
                "https://download/test.vpk", "https://preview/test.png", List.of()
        );
        when(steamApiClient.getPublishedFileDetails(List.of("123456")))
                .thenReturn(List.of(detail));

        WorkshopParseResultVO result = service.parseWorkshop("123456");

        assertEquals("123456", result.getSourceId());
        assertEquals(1, result.getItems().size());
        WorkshopItemVO item = result.getItems().get(0);
        assertEquals("123456", item.getPublishedFileId());
        assertEquals("Test Map", item.getTitle());
        assertEquals("test.vpk", item.getFilename());
        assertEquals("12345", item.getFileSize());
        assertEquals("https://download/test.vpk", item.getFileUrl());
        assertTrue(item.isHasFileUrl());
    }

    // ============================================================
    // parse_workshop_collection：parent + 2 children
    // ============================================================

    @Test
    void parse_workshop_collection() {
        SteamApiClient.WorkshopDetail parent = new SteamApiClient.WorkshopDetail(
                "100000", 1, "Collection", "col.vpk", 1000L,
                "https://download/col.vpk", "https://preview/col.png",
                List.of("111", "222")
        );
        SteamApiClient.WorkshopDetail child1 = new SteamApiClient.WorkshopDetail(
                "111", 1, "Child 1", "c1.vpk", 100L,
                "https://download/c1.vpk", null, List.of()
        );
        SteamApiClient.WorkshopDetail child2 = new SteamApiClient.WorkshopDetail(
                "222", 1, "Child 2", "c2.vpk", 200L,
                "https://download/c2.vpk", null, List.of()
        );

        when(steamApiClient.getPublishedFileDetails(List.of("100000")))
                .thenReturn(List.of(parent));
        when(steamApiClient.getPublishedFileDetails(List.of("111", "222")))
                .thenReturn(List.of(child1, child2));

        WorkshopParseResultVO result = service.parseWorkshop("100000");

        assertEquals(3, result.getItems().size());
        // 顺序：parent 在前，然后 children
        assertEquals("100000", result.getItems().get(0).getPublishedFileId());
        assertEquals("111", result.getItems().get(1).getPublishedFileId());
        assertEquals("222", result.getItems().get(2).getPublishedFileId());
    }

    // ============================================================
    // parse_workshop_collection_parent_no_file_url：parent file_url 空 + 2 children
    // ============================================================

    @Test
    void parse_workshop_collection_parent_no_file_url() {
        SteamApiClient.WorkshopDetail parent = new SteamApiClient.WorkshopDetail(
                "100000", 1, "Collection", "col.vpk", 0L,
                null, "https://preview/col.png",  // file_url 为空
                List.of("111", "222")
        );
        SteamApiClient.WorkshopDetail child1 = new SteamApiClient.WorkshopDetail(
                "111", 1, "Child 1", "c1.vpk", 100L,
                "https://download/c1.vpk", null, List.of()
        );
        SteamApiClient.WorkshopDetail child2 = new SteamApiClient.WorkshopDetail(
                "222", 1, "Child 2", "c2.vpk", 200L,
                "https://download/c2.vpk", null, List.of()
        );

        when(steamApiClient.getPublishedFileDetails(List.of("100000")))
                .thenReturn(List.of(parent));
        when(steamApiClient.getPublishedFileDetails(List.of("111", "222")))
                .thenReturn(List.of(child1, child2));

        WorkshopParseResultVO result = service.parseWorkshop("100000");

        // 仅 children，parent 因 file_url 空被跳过
        assertEquals(2, result.getItems().size());
        assertEquals("111", result.getItems().get(0).getPublishedFileId());
        assertEquals("222", result.getItems().get(1).getPublishedFileId());
    }

    // ============================================================
    // parse_workshop_dedup：重复 publishedFileId 去重
    // ============================================================

    @Test
    void parse_workshop_dedup() {
        // parent 包含自身 ID 作为 child（构造重复场景）
        SteamApiClient.WorkshopDetail parent = new SteamApiClient.WorkshopDetail(
                "100000", 1, "Parent Title", "parent.vpk", 1000L,
                "https://download/parent.vpk", "https://preview/parent.png",
                List.of("111", "100000")  // 100000 与 parent 同 ID（构造重复）
        );
        SteamApiClient.WorkshopDetail child1 = new SteamApiClient.WorkshopDetail(
                "111", 1, "Child 1", "c1.vpk", 100L,
                "https://download/c1.vpk", null, List.of()
        );
        // 与 parent 同 ID 的低分项（quality score 较低）
        SteamApiClient.WorkshopDetail duplicateParent = new SteamApiClient.WorkshopDetail(
                "100000", 1, "Low Quality", "", 0L,
                "https://download/low.vpk", null, List.of()
        );

        when(steamApiClient.getPublishedFileDetails(List.of("100000")))
                .thenReturn(List.of(parent));
        when(steamApiClient.getPublishedFileDetails(List.of("111", "100000")))
                .thenReturn(List.of(child1, duplicateParent));

        WorkshopParseResultVO result = service.parseWorkshop("100000");

        // 2 项：parent (id=100000) + child1 (id=111)，duplicateParent 被去重
        assertEquals(2, result.getItems().size());
        // 保留质量分数最高者：parent (title+4, filename+3, fileSize+2, previewUrl+1 = 10)
        // duplicateParent (title+4, fileSize=0, no filename, no preview = 4)
        WorkshopItemVO first = result.getItems().get(0);
        assertEquals("100000", first.getPublishedFileId());
        assertEquals("Parent Title", first.getTitle());
        assertEquals("parent.vpk", first.getFilename());
        WorkshopItemVO second = result.getItems().get(1);
        assertEquals("111", second.getPublishedFileId());
    }

    // ============================================================
    // parse_workshop_filter_invalid：result != 1 被过滤
    // ============================================================

    @Test
    void parse_workshop_filter_invalid() {
        SteamApiClient.WorkshopDetail parent = new SteamApiClient.WorkshopDetail(
                "100000", 1, "Parent", "p.vpk", 100L,
                "https://download/p.vpk", null, List.of()
        );

        // 第二个调用返回的子项 result=0（无效）
        when(steamApiClient.getPublishedFileDetails(any()))
                .thenReturn(List.of(parent));

        WorkshopParseResultVO result = service.parseWorkshop("100000");
        assertEquals(1, result.getItems().size());
    }

    // ============================================================
    // parse_workshop_empty_details：Steam API 返回空列表 → 抛异常
    // ============================================================

    @Test
    void parse_workshop_empty_details() {
        when(steamApiClient.getPublishedFileDetails(List.of("100000")))
                .thenReturn(List.of());

        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> service.parseWorkshop("100000"));
        assertEquals(L4D2PluginException.EXTERNAL_API, ex.getCode());
        assertTrue(ex.getMessage().contains("未找到工坊文件"));
    }

    // ============================================================
    // create_workshop_task_with_file_url：有 file_url 调用 createWorkshopTask
    // ============================================================

    @Test
    void create_workshop_task_with_file_url() {
        SteamApiClient.WorkshopDetail detail = new SteamApiClient.WorkshopDetail(
                "123456", 1, "Test Map", "test.vpk", 12345L,
                "https://download/test.vpk", "https://preview/test.png", List.of()
        );
        when(steamApiClient.getPublishedFileDetails(List.of("123456")))
                .thenReturn(List.of(detail));

        WorkshopDownloadDTO dto = new WorkshopDownloadDTO();
        dto.setInstanceId(1L);
        dto.setWorkshopUrlOrId("123456");

        List<String> taskIds = service.createWorkshopTasks(dto);

        assertEquals(1, taskIds.size());
        assertEquals("fake-task-id", taskIds.get(0));

        // 验证 createWorkshopTask 被调用，createManualTask 未被调用
        verify(downloadService, times(1)).createWorkshopTask(
                eq(1L), eq("123456"), eq("Test Map"), eq("https://preview/test.png"),
                eq("https://download/test.vpk"), eq("test.vpk"), eq("https://proxy.example.com/"));
        verify(downloadService, never()).createManualTask(
                anyLong(), anyString(), anyString(), any(), anyString());
    }

    // ============================================================
    // create_workshop_task_without_file_url：无 file_url 调用 createManualTask
    // ============================================================

    @Test
    void create_workshop_task_without_file_url() {
        SteamApiClient.WorkshopDetail detail = new SteamApiClient.WorkshopDetail(
                "123456", 1, "Test Map", "test.vpk", 12345L,
                null,  // file_url 为空
                "https://preview/test.png", List.of()
        );
        when(steamApiClient.getPublishedFileDetails(List.of("123456")))
                .thenReturn(List.of(detail));

        WorkshopDownloadDTO dto = new WorkshopDownloadDTO();
        dto.setInstanceId(1L);
        dto.setWorkshopUrlOrId("123456");

        List<String> taskIds = service.createWorkshopTasks(dto);

        assertEquals(1, taskIds.size());
        assertEquals("fake-manual-id", taskIds.get(0));

        // 验证 createManualTask 被调用，createWorkshopTask 未被调用
        verify(downloadService, never()).createWorkshopTask(
                anyLong(), anyString(), anyString(), any(), anyString(), any(), any());
        verify(downloadService, times(1)).createManualTask(
                eq(1L), eq("123456"), eq("Test Map"), eq("https://preview/test.png"),
                anyString());
    }

    // ============================================================
    // create_workshop_task_batch：3 items → 3 个 taskId
    // ============================================================

    @Test
    void create_workshop_task_batch() {
        SteamApiClient.WorkshopDetail parent = new SteamApiClient.WorkshopDetail(
                "100000", 1, "Collection", "col.vpk", 1000L,
                "https://download/col.vpk", "https://preview/col.png",
                List.of("111", "222")
        );
        SteamApiClient.WorkshopDetail child1 = new SteamApiClient.WorkshopDetail(
                "111", 1, "Child 1", "c1.vpk", 100L,
                "https://download/c1.vpk", null, List.of()
        );
        SteamApiClient.WorkshopDetail child2 = new SteamApiClient.WorkshopDetail(
                "222", 1, "Child 2", "c2.vpk", 200L,
                "https://download/c2.vpk", null, List.of()
        );

        when(steamApiClient.getPublishedFileDetails(List.of("100000")))
                .thenReturn(List.of(parent));
        when(steamApiClient.getPublishedFileDetails(List.of("111", "222")))
                .thenReturn(List.of(child1, child2));

        // 让 createWorkshopTask 每次返回不同 ID
        when(downloadService.createWorkshopTask(eq(1L), eq("100000"), anyString(), any(),
                        anyString(), any(), any()))
                .thenReturn("task-1");
        when(downloadService.createWorkshopTask(eq(1L), eq("111"), anyString(), any(),
                        anyString(), any(), any()))
                .thenReturn("task-2");
        when(downloadService.createWorkshopTask(eq(1L), eq("222"), anyString(), any(),
                        anyString(), any(), any()))
                .thenReturn("task-3");

        WorkshopDownloadDTO dto = new WorkshopDownloadDTO();
        dto.setInstanceId(1L);
        dto.setWorkshopUrlOrId("100000");

        List<String> taskIds = service.createWorkshopTasks(dto);

        assertEquals(3, taskIds.size());
        // 3 个都有 file_url → createWorkshopTask 被调用 3 次
        verify(downloadService, times(3)).createWorkshopTask(
                anyLong(), anyString(), anyString(), any(), anyString(), any(), any());
        verify(downloadService, never()).createManualTask(
                anyLong(), anyString(), anyString(), any(), anyString());
    }

    // ============================================================
    // create_workshop_task_mixed：部分有 file_url，部分没有
    // ============================================================

    @Test
    void create_workshop_task_mixed() {
        SteamApiClient.WorkshopDetail parent = new SteamApiClient.WorkshopDetail(
                "100000", 1, "Collection", "col.vpk", 1000L,
                "https://download/col.vpk", "https://preview/col.png",
                List.of("111", "222")
        );
        SteamApiClient.WorkshopDetail child1 = new SteamApiClient.WorkshopDetail(
                "111", 1, "Child 1", "c1.vpk", 100L,
                "https://download/c1.vpk", null, List.of()
        );
        // child2 无 file_url
        SteamApiClient.WorkshopDetail child2 = new SteamApiClient.WorkshopDetail(
                "222", 1, "Child 2", "c2.vpk", 200L,
                null, null, List.of()
        );

        when(steamApiClient.getPublishedFileDetails(List.of("100000")))
                .thenReturn(List.of(parent));
        when(steamApiClient.getPublishedFileDetails(List.of("111", "222")))
                .thenReturn(List.of(child1, child2));

        WorkshopDownloadDTO dto = new WorkshopDownloadDTO();
        dto.setInstanceId(1L);
        dto.setWorkshopUrlOrId("100000");

        List<String> taskIds = service.createWorkshopTasks(dto);

        assertEquals(3, taskIds.size());
        // 2 个有 file_url（parent + child1）→ createWorkshopTask 被调用 2 次
        verify(downloadService, times(2)).createWorkshopTask(
                anyLong(), anyString(), anyString(), any(), anyString(), any(), any());
        // 1 个无 file_url（child2）→ createManualTask 被调用 1 次
        verify(downloadService, times(1)).createManualTask(
                anyLong(), anyString(), anyString(), any(), anyString());
    }

    // ============================================================
    // create_workshop_task_null_dto：参数校验
    // ============================================================

    @Test
    void create_workshop_task_null_dto() {
        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> service.createWorkshopTasks(null));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
    }

    @Test
    void create_workshop_task_null_instance_id() {
        WorkshopDownloadDTO dto = new WorkshopDownloadDTO();
        dto.setWorkshopUrlOrId("123456");

        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> service.createWorkshopTasks(dto));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
        assertTrue(ex.getMessage().contains("instanceId"));
    }

    @Test
    void create_workshop_task_null_url_or_id() {
        WorkshopDownloadDTO dto = new WorkshopDownloadDTO();
        dto.setInstanceId(1L);

        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> service.createWorkshopTasks(dto));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
        assertTrue(ex.getMessage().contains("workshopUrlOrId"));
    }

    // ============================================================
    // parse_link_workshop：解析 workshop 链接返回详细 items
    // ============================================================

    @Test
    void parse_link_workshop() {
        SteamApiClient.WorkshopDetail detail = new SteamApiClient.WorkshopDetail(
                "123456", 1, "Test Map", "test.vpk", 12345L,
                "https://download/test.vpk", "https://preview/test.png", List.of()
        );
        when(steamApiClient.getPublishedFileDetails(List.of("123456")))
                .thenReturn(List.of(detail));

        LinkParseResultVO result = service.parseLink("123456");

        assertEquals("workshop", result.getSourceType());
        assertEquals("123456", result.getSourceId());
        assertEquals("123456", result.getOriginalLink());
        assertEquals(1, result.getItems().size());
        assertTrue(result.getItems().get(0).isSupported());
        assertFalse(result.getItems().get(0).getDisabledReason() != null
                && !result.getItems().get(0).getDisabledReason().isEmpty());
    }

    // ============================================================
    // parse_link_unknown：未知链接返回 sourceType=unknown
    // ============================================================

    @Test
    void parse_link_unknown() {
        LinkParseResultVO result = service.parseLink("https://example.com/foo");

        assertEquals("unknown", result.getSourceType());
        assertTrue(result.getItems() == null || result.getItems().isEmpty());
    }

    // ============================================================
    // parse_link_no_file_url：file_url 为空 → supported=false
    // ============================================================

    @Test
    void parse_link_no_file_url() {
        SteamApiClient.WorkshopDetail detail = new SteamApiClient.WorkshopDetail(
                "123456", 1, "Test Map", "test.vpk", 12345L,
                null,  // file_url 为空
                "https://preview/test.png", List.of()
        );
        when(steamApiClient.getPublishedFileDetails(List.of("123456")))
                .thenReturn(List.of(detail));

        LinkParseResultVO result = service.parseLink("123456");

        assertEquals("workshop", result.getSourceType());
        assertEquals(1, result.getItems().size());
        assertFalse(result.getItems().get(0).isSupported());
        assertNotNull(result.getItems().get(0).getDisabledReason());
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    /**
     * 验证 ArgumentCaptor 工具方法（保留以便扩展）。
     */
    @SuppressWarnings("unused")
    private <T> ArgumentCaptor<T> captor(Class<T> cls) {
        return ArgumentCaptor.forClass(cls);
    }
}

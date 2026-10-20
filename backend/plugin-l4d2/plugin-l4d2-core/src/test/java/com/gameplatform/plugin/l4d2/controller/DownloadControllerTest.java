package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.dto.ParseLinkDTO;
import com.gameplatform.plugin.l4d2.dto.ParseWorkshopDTO;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.service.CloudInstallService;
import com.gameplatform.plugin.l4d2.service.DownloadService;
import com.gameplatform.plugin.l4d2.service.WorkshopDownloadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DownloadControllerTest {

    private WorkshopDownloadService workshopDownloadService;
    private DownloadController controller;

    @BeforeEach
    void setUp() {
        workshopDownloadService = mock(WorkshopDownloadService.class);
        controller = new DownloadController(
                mock(DownloadService.class),
                workshopDownloadService,
                mock(CloudInstallService.class));
    }

    @Test
    void parseWorkshop_pluginFailureReturnsReadableBusinessResult() {
        ParseWorkshopDTO dto = new ParseWorkshopDTO();
        dto.setUrl("https://steamcommunity.com/sharedfiles/filedetails/?id=3560886237");
        when(workshopDownloadService.parseWorkshop(dto.getUrl()))
                .thenThrow(new L4D2PluginException(
                        L4D2PluginException.EXTERNAL_API,
                        "Steam Workshop 解析失败，请稍后重试"));

        Result<?> result = controller.parseWorkshop(dto);

        assertEquals(400, result.getCode());
        assertEquals("Steam Workshop 解析失败，请稍后重试", result.getMessage());
    }

    @Test
    void parseLink_pluginFailureReturnsReadableBusinessResult() {
        ParseLinkDTO dto = new ParseLinkDTO();
        dto.setUrl("invalid-link");
        when(workshopDownloadService.parseLink(dto.getUrl()))
                .thenThrow(new L4D2PluginException(
                        L4D2PluginException.BUSINESS, "未找到有效的工坊 ID"));

        Result<?> result = controller.parseLink(dto);

        assertEquals(400, result.getCode());
        assertEquals("未找到有效的工坊 ID", result.getMessage());
    }
}
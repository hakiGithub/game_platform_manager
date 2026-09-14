package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.dto.PluginStoreConfigDTO;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.service.PluginStoreService;
import com.gameplatform.plugin.l4d2.service.StoreConfigService;
import com.gameplatform.plugin.l4d2.vo.PluginStoreConfigVO;
import com.gameplatform.plugin.l4d2.vo.PluginStoreItemVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PluginStoreController 单元测试：业务错误 HTTP 200 化、未配置业务码 1550、GitHub 异常文案翻译。
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PluginStoreControllerTest {

    @Mock
    private PluginStoreService pluginStoreService;

    @Mock
    private StoreConfigService storeConfigService;

    private PluginStoreController controller;

    @BeforeEach
    void setUp() {
        controller = new PluginStoreController(pluginStoreService, storeConfigService);
    }

    @Test
    void list_unconfigured_returns_business_code_1550() {
        when(pluginStoreService.list(any(), any()))
                .thenThrow(new L4D2PluginException(L4D2PluginException.STORE_NOT_CONFIGURED,
                        "远端插件仓库未配置，请先在插件管理 → 远端仓库中完成配置"));

        Result<List<PluginStoreItemVO>> result = controller.list(null, null, 1, 20);

        assertNotNull(result);
        assertEquals(L4D2PluginException.CODE_STORE_NOT_CONFIGURED, result.getCode());
        assertTrue(result.getMessage().contains("未配置"));
    }

    @Test
    void list_github_404_translated_not_raw_500() {
        when(pluginStoreService.list(any(), any()))
                .thenThrow(new L4D2PluginException(L4D2PluginException.EXTERNAL_API, "GET 请求失败: url",
                        HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                                new HttpHeaders(), new byte[0], StandardCharsets.UTF_8)));

        Result<List<PluginStoreItemVO>> result = controller.list(null, null, 1, 20);

        assertEquals(400, result.getCode(), "业务错误应 HTTP 200 + Result.fail(400)");
        assertTrue(result.getMessage().contains("仓库不存在或分支错误"), "实际文案: " + result.getMessage());
    }

    @Test
    void list_success_passthrough() {
        PluginStoreItemVO item = new PluginStoreItemVO();
        item.setPluginId("demo");
        when(pluginStoreService.list(any(), any())).thenReturn(List.of(item));

        Result<List<PluginStoreItemVO>> result = controller.list(null, null, 1, 20);

        assertEquals(200, result.getCode());
        assertEquals(1, result.getData().size());
    }

    @Test
    void saveConfig_saves_then_evicts_cache() {
        PluginStoreConfigDTO dto = new PluginStoreConfigDTO();
        dto.setRepo("o/r");
        PluginStoreConfigVO vo = new PluginStoreConfigVO();
        vo.setConfigured(true);
        when(storeConfigService.save(dto)).thenReturn(vo);

        Result<PluginStoreConfigVO> result = controller.saveConfig(dto);

        assertEquals(200, result.getCode());
        assertTrue(result.getData().isConfigured());
        verify(pluginStoreService).evictCache();
    }

    @Test
    void saveConfig_invalid_repo_returns_message_not_500() {
        PluginStoreConfigDTO dto = new PluginStoreConfigDTO();
        dto.setRepo("///bad");
        when(storeConfigService.save(any()))
                .thenThrow(new L4D2PluginException(L4D2PluginException.BUSINESS, "无法识别的仓库地址"));

        Result<PluginStoreConfigVO> result = controller.saveConfig(dto);

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("仓库地址"));
        verify(pluginStoreService, never()).evictCache();
    }

    @Test
    void download_unconfigured_returns_1550_without_task() {
        doThrow(new L4D2PluginException(L4D2PluginException.STORE_NOT_CONFIGURED, "远端插件仓库未配置"))
                .when(pluginStoreService).download(any());

        Result<String> result = controller.download(new com.gameplatform.plugin.l4d2.dto.PluginStoreDownloadDTO());

        assertEquals(L4D2PluginException.CODE_STORE_NOT_CONFIGURED, result.getCode());
    }

    @Test
    void getConfig_delegates() {
        PluginStoreConfigVO vo = new PluginStoreConfigVO();
        when(storeConfigService.get()).thenReturn(vo);

        assertEquals(200, controller.getConfig().getCode());
        verify(storeConfigService).get();
    }
}

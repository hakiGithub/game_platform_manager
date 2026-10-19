package com.gameplatform.plugin.l4d2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.plugin.l4d2.exception.VpkInvalidException;
import com.gameplatform.plugin.l4d2.vo.VpkAnalyzeResult;
import com.gameplatform.plugin.service.HostToolingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VpkToolingServiceTest {

    @Mock
    private HostToolingService hostToolingService;

    private VpkToolingService service;

    @BeforeEach
    void setUp() {
        service = new VpkToolingService(hostToolingService, new ObjectMapper());
    }

    @Test
    @DisplayName("插件解析宿主返回的原始 JSON 并保留章节模式")
    void inspectVpk_parsesRawScriptOutput() {
        when(hostToolingService.runToolScript(1L, "left4dead2/addons/map.vpk",
                VpkToolingService.ANALYZE_SCRIPT))
                .thenReturn("""
                        {
                          "title": "Test Campaign",
                          "chapters": [
                            {"code": "test_map_1", "title": "Chapter 1",
                             "modes": ["coop", "versus"]}
                          ]
                        }
                        """);

        VpkAnalyzeResult result = service.inspectVpk(1L, "left4dead2/addons/map.vpk");

        assertEquals("Test Campaign", result.getTitle());
        assertEquals(1, result.getChapters().size());
        assertEquals("test_map_1", result.getChapters().get(0).getCode());
        assertEquals("Chapter 1", result.getChapters().get(0).getTitle());
        assertEquals(java.util.List.of("coop", "versus"),
                result.getChapters().get(0).getModes());
        verify(hostToolingService).runToolScript(1L, "left4dead2/addons/map.vpk",
                VpkToolingService.ANALYZE_SCRIPT);
    }

    @Test
    @DisplayName("无 mission 的脚本退出映射为 VpkInvalidException")
    void inspectVpk_noMissionMapsToInvalid() {
        when(hostToolingService.runToolScript(1L, "left4dead2/addons/empty.vpk",
                VpkToolingService.ANALYZE_SCRIPT))
                .thenThrow(new BusinessException(
                        "工具脚本执行失败: no valid mission found in VPK"));

        VpkInvalidException error = assertThrows(VpkInvalidException.class,
                () -> service.inspectVpk(1L, "left4dead2/addons/empty.vpk"));

        assertTrue(error.getMessage().contains("empty.vpk"));
        assertTrue(error.getMessage().contains("mission"));
    }

    @Test
    @DisplayName("其他脚本失败保持基础设施异常，不误判为 INVALID")
    void inspectVpk_otherFailureIsNotInvalid() {
        when(hostToolingService.runToolScript(1L, "left4dead2/addons/map.vpk",
                VpkToolingService.ANALYZE_SCRIPT))
                .thenThrow(new BusinessException("工具容器不可用"));

        assertThrows(BusinessException.class,
                () -> service.inspectVpk(1L, "left4dead2/addons/map.vpk"));
    }
}

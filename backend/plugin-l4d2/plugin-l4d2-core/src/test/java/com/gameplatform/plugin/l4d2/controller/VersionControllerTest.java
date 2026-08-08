package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.util.BuildInfoReader;
import com.gameplatform.plugin.l4d2.vo.BuildInfoVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

/**
 * {@link VersionController} 单元测试（对齐 plan §6.3.5）。
 *
 * <p>mock {@link BuildInfoReader} 注入到控制器，验证两个端点返回的 Result 结构正确。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VersionControllerTest {

    @Mock
    private BuildInfoReader buildInfoReader;

    private VersionController controller;

    @BeforeEach
    void setUp() {
        controller = new VersionController(buildInfoReader);
    }

    // ============================================================
    // get_version_returns_full_info：GET / → 返回 BuildInfoVO 含 version
    // ============================================================

    @Test
    void get_version_returns_full_info() {
        BuildInfoVO vo = new BuildInfoVO();
        vo.setVersion("1.0.0");
        vo.setCommit("abc1234");
        vo.setBuildTime("2026-07-20T10:00:00Z");
        vo.setJdkVersion("17.0.1");
        vo.setPf4jVersion("3.10.0");
        vo.setPluginId("plugin-l4d2");
        vo.setPluginDescription("L4D2 游戏服务器增强插件");
        vo.setSpringBootVersion("3.2.5");
        when(buildInfoReader.toVO()).thenReturn(vo);

        Result<BuildInfoVO> result = controller.getVersion();

        assertNotNull(result);
        assertNotNull(result.getData());
        assertEquals("1.0.0", result.getData().getVersion());
        assertEquals("abc1234", result.getData().getCommit());
        assertEquals("2026-07-20T10:00:00Z", result.getData().getBuildTime());
        assertEquals("17.0.1", result.getData().getJdkVersion());
        assertEquals("3.10.0", result.getData().getPf4jVersion());
        assertEquals("3.2.5", result.getData().getSpringBootVersion());
    }

    // ============================================================
    // get_short_returns_version_string：GET /short → 返回 version 字符串
    // ============================================================

    @Test
    void get_short_returns_version_string() {
        when(buildInfoReader.getVersion()).thenReturn("1.2.3");

        Result<String> result = controller.getShortVersion();

        assertNotNull(result);
        assertNotNull(result.getData());
        assertEquals("1.2.3", result.getData());
    }

    // ============================================================
    // get_version_includes_plugin_id：GET / → BuildInfoVO.pluginId == "plugin-l4d2"
    // ============================================================

    @Test
    void get_version_includes_plugin_id() {
        BuildInfoVO vo = new BuildInfoVO();
        vo.setVersion("1.0.0");
        vo.setPluginId("plugin-l4d2");
        when(buildInfoReader.toVO()).thenReturn(vo);

        Result<BuildInfoVO> result = controller.getVersion();

        assertNotNull(result);
        assertNotNull(result.getData());
        assertEquals("plugin-l4d2", result.getData().getPluginId());
    }

    // ============================================================
    // get_short_returns_unknown_when_reader_returns_unknown：降级场景
    // ============================================================

    @Test
    void get_short_returns_unknown_when_reader_returns_unknown() {
        when(buildInfoReader.getVersion()).thenReturn("unknown");

        Result<String> result = controller.getShortVersion();

        assertNotNull(result);
        assertEquals("unknown", result.getData());
    }
}

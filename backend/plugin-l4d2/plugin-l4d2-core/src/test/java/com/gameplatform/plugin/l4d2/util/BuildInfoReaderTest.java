package com.gameplatform.plugin.l4d2.util;

import com.gameplatform.plugin.l4d2.vo.BuildInfoVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

/**
 * {@link BuildInfoReader} 单元测试（对齐 plan §6.3.5）。
 *
 * <p>通过 mock {@link ClassPathResource} 模拟 build.properties 文件存在/缺失/读取异常等场景，
 * 不依赖真实 classpath 资源。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BuildInfoReaderTest {

    @Mock
    private ClassPathResource buildPropertiesResource;

    private BuildInfoReader reader;

    @BeforeEach
    void setUp() {
        // 通过包级可见的构造器注入 mock 资源
        reader = new BuildInfoReader(buildPropertiesResource);
    }

    // ============================================================
    // init_reads_build_properties_when_present：build.properties 存在 → 字段被赋值
    // ============================================================

    @Test
    void init_reads_build_properties_when_present() throws IOException {
        String content = "version=1.2.3\nbuildTime=2026-07-20T10:00:00Z\n";
        InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        when(buildPropertiesResource.exists()).thenReturn(true);
        when(buildPropertiesResource.getInputStream()).thenReturn(is);

        reader.init();

        assertEquals("1.2.3", reader.getVersion());
        assertEquals("1.2.3", reader.toVO().getVersion());
        assertEquals("2026-07-20T10:00:00Z", reader.toVO().getBuildTime());
    }

    // ============================================================
    // init_uses_unknown_when_properties_missing：build.properties 不存在 → 字段为 unknown
    // ============================================================

    @Test
    void init_uses_unknown_when_properties_missing() throws IOException {
        when(buildPropertiesResource.exists()).thenReturn(true);
        when(buildPropertiesResource.getInputStream()).thenThrow(new IOException("not found"));

        reader.init();

        BuildInfoVO vo = reader.toVO();
        assertEquals("unknown", vo.getVersion());
        assertEquals("unknown", vo.getBuildTime());
        assertEquals("unknown", vo.getCommit());
    }

    // ============================================================
    // init_uses_unknown_when_resource_does_not_exist：exists() 返回 false
    // ============================================================

    @Test
    void init_uses_unknown_when_resource_does_not_exist() {
        when(buildPropertiesResource.exists()).thenReturn(false);

        reader.init();

        BuildInfoVO vo = reader.toVO();
        assertEquals("unknown", vo.getVersion());
        assertEquals("unknown", vo.getBuildTime());
        assertEquals("unknown", vo.getCommit());
    }

    // ============================================================
    // to_vo_maps_all_fields：toVO() 包含所有字段
    // ============================================================

    @Test
    void to_vo_maps_all_fields() throws IOException {
        String content = "version=2.0.0\nbuildTime=2026-08-01T08:30:00Z\n";
        InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        when(buildPropertiesResource.exists()).thenReturn(true);
        when(buildPropertiesResource.getInputStream()).thenReturn(is);

        reader.init();

        BuildInfoVO vo = reader.toVO();
        assertNotNull(vo);
        assertEquals("2.0.0", vo.getVersion());
        assertEquals("2026-08-01T08:30:00Z", vo.getBuildTime());
        assertEquals(System.getProperty("java.version"), vo.getJdkVersion());
        assertNotNull(vo.getPf4jVersion());
        assertEquals(BuildInfoReader.PLUGIN_ID, vo.getPluginId());
        assertEquals(BuildInfoReader.PLUGIN_DESCRIPTION, vo.getPluginDescription());
        assertNotNull(vo.getSpringBootVersion());
    }

    // ============================================================
    // jdk_version_returns_system_property：jdkVersion 等于 System.getProperty("java.version")
    // ============================================================

    @Test
    void jdk_version_returns_system_property() {
        when(buildPropertiesResource.exists()).thenReturn(false);

        reader.init();

        String expected = System.getProperty("java.version");
        assertEquals(expected, reader.toVO().getJdkVersion());
    }

    // ============================================================
    // plugin_id_and_description_are_constant：固定字段始终来自常量
    // ============================================================

    @Test
    void plugin_id_and_description_are_constant() {
        when(buildPropertiesResource.exists()).thenReturn(false);

        reader.init();

        BuildInfoVO vo = reader.toVO();
        assertEquals(BuildInfoReader.PLUGIN_ID, vo.getPluginId());
        assertEquals(BuildInfoReader.PLUGIN_DESCRIPTION, vo.getPluginDescription());
    }

    // ============================================================
    // get_version_returns_version_field：getVersion() 与 toVO().getVersion() 一致
    // ============================================================

    @Test
    void get_version_returns_version_field() throws IOException {
        String content = "version=9.9.9\nbuildTime=2026-12-31T23:59:59Z\n";
        InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        when(buildPropertiesResource.exists()).thenReturn(true);
        when(buildPropertiesResource.getInputStream()).thenReturn(is);

        reader.init();

        assertEquals(reader.toVO().getVersion(), reader.getVersion());
        assertEquals("9.9.9", reader.getVersion());
    }
}

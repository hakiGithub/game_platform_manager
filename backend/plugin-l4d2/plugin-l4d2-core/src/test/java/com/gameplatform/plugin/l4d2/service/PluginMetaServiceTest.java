package com.gameplatform.plugin.l4d2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.PluginMeta;
import com.gameplatform.plugin.service.InstanceFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PluginMetaServiceTest {

    private InstanceFileService instanceFileService;
    private PluginMetaService service;

    @BeforeEach
    void setUp() {
        instanceFileService = mock(InstanceFileService.class);
        L4D2PathResolver resolver = new L4D2PathResolver();
        ObjectMapper yamlMapper = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        service = new PluginMetaService(instanceFileService, resolver, yamlMapper);
    }

    @Test
    void save_shouldWriteYamlWithAllFields() throws Exception {
        PluginMeta meta = new PluginMeta();
        meta.setName("l4d2_test");
        meta.setSource("upload");
        meta.setVersion("1.0");
        meta.setAuthor("tester");
        meta.setDescription("desc");
        meta.setFileList(List.of("addons/sourcemod/plugins/l4d2_test.smx"));
        meta.setConfigFiles(List.of("cfg/sourcemod/l4d2_test.cfg"));

        service.save(100L, meta);

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(instanceFileService).writeTextFile(eq(100L),
                eq("left4dead2/addons/sourcemod/plugins_store/l4d2_test/plugin.yaml"),
                contentCaptor.capture());
        String yaml = contentCaptor.getValue();
        assertThat(yaml).contains("l4d2_test");
        assertThat(yaml).contains("upload");
        assertThat(yaml).contains("l4d2_test.smx");
    }

    @Test
    void load_shouldReturnNullWhenFileMissing() {
        when(instanceFileService.exists(eq(100L), any())).thenReturn(false);
        PluginMeta result = service.load(100L, "l4d2_test");
        assertThat(result).isNull();
    }

    @Test
    void load_shouldParseYamlBack() throws Exception {
        when(instanceFileService.exists(eq(100L), any())).thenReturn(true);
        String yaml = "name: \"l4d2_test\"\nsource: \"store\"\nversion: \"1.2\"\nfile_list:\n  - \"addons/sourcemod/plugins/l4d2_test.smx\"\n";
        when(instanceFileService.readTextFile(eq(100L), any(), eq(StandardCharsets.UTF_8))).thenReturn(yaml);

        PluginMeta result = service.load(100L, "l4d2_test");
        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("l4d2_test");
        assertThat(result.getSource()).isEqualTo("store");
        assertThat(result.getVersion()).isEqualTo("1.2");
        assertThat(result.getFileList()).containsExactly("addons/sourcemod/plugins/l4d2_test.smx");
    }
}

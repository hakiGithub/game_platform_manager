package com.gameplatform.plugin.l4d2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.l4d2.extension.EnabledPluginResource;
import com.gameplatform.plugin.l4d2.extension.EnabledPluginSpec;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.EnabledPlugin;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EnabledPluginsService 单元测试。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
class EnabledPluginsServiceTest {

    private InstanceFileService instanceFileService;
    private ExtensionClient extensionClient;
    private InstanceQueryService instanceQueryService;
    private EnabledPluginsService service;

    @BeforeEach
    void setUp() {
        instanceFileService = mock(InstanceFileService.class);
        extensionClient = mock(ExtensionClient.class);
        instanceQueryService = mock(InstanceQueryService.class);
        L4D2PathResolver pathResolver = new L4D2PathResolver();
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        service = new EnabledPluginsService(
                instanceFileService, pathResolver, extensionClient, instanceQueryService, yamlMapper);
    }

    @Test
    void loadYaml_emptyFile_returnsEmptyList() {
        when(instanceFileService.exists(eq(1L), anyString())).thenReturn(false);
        assertThat(service.loadYaml(1L)).isEmpty();
    }

    @Test
    void saveYaml_writesRemoteAndSyncsResource() {
        EnabledPlugin plugin = new EnabledPlugin();
        plugin.setName("l4d2_test");
        plugin.setSource("upload");
        plugin.setEnabledAt(1711084800000L);
        plugin.setFiles(List.of("addons/sourcemod/plugins/test.smx"));

        when(extensionClient.get(eq(EnabledPluginResource.class), anyString()))
                .thenReturn(Optional.empty());

        service.saveYaml(1L, List.of(plugin));

        verify(instanceFileService).writeTextFile(eq(1L), anyString(), anyString());
        verify(extensionClient).create(any(EnabledPluginResource.class));
    }

    @Test
    void add_appendsToExistingYaml() {
        when(instanceFileService.exists(eq(1L), anyString())).thenReturn(false);

        EnabledPlugin toAdd = new EnabledPlugin();
        toAdd.setName("new_one");
        toAdd.setSource("store");
        toAdd.setEnabledAt(2L);
        toAdd.setFiles(List.of("b.smx"));

        when(extensionClient.get(eq(EnabledPluginResource.class), anyString()))
                .thenReturn(Optional.empty());

        service.add(1L, toAdd);

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(instanceFileService).writeTextFile(eq(1L), anyString(), contentCaptor.capture());
        assertThat(contentCaptor.getValue()).contains("new_one");
    }

    @Test
    void remove_removesFromYamlAndResource() {
        EnabledPlugin plugin = new EnabledPlugin();
        plugin.setName("to_remove");
        plugin.setSource("upload");
        plugin.setEnabledAt(1L);
        plugin.setFiles(List.of("a.smx"));

        EnabledPluginResource resource = new EnabledPluginResource();
        resource.setName("1-to_remove");
        EnabledPluginSpec spec = new EnabledPluginSpec();
        spec.setInstanceId(1L);
        spec.setPluginName("to_remove");
        resource.setSpec(spec);
        when(extensionClient.get(eq(EnabledPluginResource.class), eq("1-to_remove")))
                .thenReturn(Optional.of(resource));

        when(instanceFileService.exists(eq(1L), anyString())).thenReturn(false);
        service.remove(1L, "to_remove");

        verify(extensionClient).delete(eq(EnabledPluginResource.class), eq("1-to_remove"));
    }

    @Test
    void isEnabled_returnsTrueWhenExists() {
        when(instanceFileService.exists(eq(1L), anyString())).thenReturn(true);
        when(instanceFileService.readTextFile(eq(1L), anyString(), eq(StandardCharsets.UTF_8)))
                .thenReturn("enabled_plugins:\n  - name: \"foo\"\n    source: \"panel\"\n    enabled_at: 1\n    files:\n      - \"a.smx\"\n");
        assertThat(service.isEnabled(1L, "foo")).isTrue();
        assertThat(service.isEnabled(1L, "bar")).isFalse();
    }
}

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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

        verify(instanceFileService).writeTextFile(
                eq(1L), anyString(), anyString(), eq(StandardCharsets.UTF_8));
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
        verify(instanceFileService).writeTextFile(
                eq(1L), anyString(), contentCaptor.capture(), eq(StandardCharsets.UTF_8));
        assertThat(contentCaptor.getValue()).contains("new_one");
    }

    @Test
    void addAll_deduplicatesAndWritesOnce() {
        when(instanceFileService.exists(eq(1L), anyString())).thenReturn(false);

        EnabledPlugin first = new EnabledPlugin();
        first.setName("same");
        first.setSource("panel");
        first.setEnabledAt(1L);
        first.setFiles(List.of("a.smx"));

        EnabledPlugin replacement = new EnabledPlugin();
        replacement.setName("same");
        replacement.setSource("builtin");
        replacement.setEnabledAt(2L);
        replacement.setFiles(List.of("b.smx"));

        EnabledPlugin second = new EnabledPlugin();
        second.setName("other");
        second.setSource("panel");
        second.setEnabledAt(3L);
        second.setFiles(List.of("c.smx"));

        when(extensionClient.get(eq(EnabledPluginResource.class), anyString()))
                .thenReturn(Optional.empty());

        service.addAll(1L, List.of(first, replacement, second));

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(instanceFileService, org.mockito.Mockito.times(1))
                .writeTextFile(eq(1L), anyString(), contentCaptor.capture(),
                        eq(StandardCharsets.UTF_8));
        String yaml = contentCaptor.getValue();
        assertThat(yaml).contains("same", "other", "builtin");
        assertThat(yaml).doesNotContain("a.smx");
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

    @Test
    void loadYaml_restoresCorruptedChineseNameFromUniqueStoreDirectory() {
        when(instanceFileService.exists(eq(1L), anyString())).thenReturn(true);
        when(instanceFileService.readTextFile(eq(1L), anyString(), eq(StandardCharsets.UTF_8)))
                .thenReturn("""
                        enabled_plugins:
                          - name: "???-????????(v2.19.23)(??????)(sorallll)"
                            source: "upload"
                            enabled_at: 1
                            files:
                              - "addons/sourcemod/plugins/l4d2_emshud_info.smx"
                        """);
        when(instanceFileService.listFiles(eq(1L), anyString()))
                .thenAnswer(invocation -> {
                    String path = invocation.getArgument(1);
                    if (path.endsWith("/plugins_store")) {
                        return List.of(storeDirectory(
                                "自选-排行榜插件(v2.19.23)(豆瓣酱な)(sorallll)"));
                    }
                    if (path.contains("自选-排行榜插件(v2.19.23)(豆瓣酱な)(sorallll)")
                            && path.endsWith("/plugins")) {
                        return List.of(storeDirectory("l4d2_emshud_info.smx", false));
                    }
                    return List.of();
                });

        List<EnabledPlugin> plugins = service.loadYaml(1L);

        assertThat(plugins).hasSize(1);
        assertThat(plugins.get(0).getName())
                .isEqualTo("自选-排行榜插件(v2.19.23)(豆瓣酱な)(sorallll)");
    }

    @Test
    void loadYaml_skipsAmbiguousCorruptedName() {
        when(instanceFileService.exists(eq(1L), anyString())).thenReturn(true);
        when(instanceFileService.readTextFile(eq(1L), anyString(), eq(StandardCharsets.UTF_8)))
                .thenReturn("""
                        enabled_plugins:
                          - name: "???-????????"
                            source: "upload"
                            enabled_at: 1
                        """);
        when(instanceFileService.listFiles(eq(1L), anyString()))
                .thenAnswer(invocation -> {
                    String path = invocation.getArgument(1);
                    if (path.endsWith("/plugins_store")) {
                        return List.of(
                                storeDirectory("自选-排行榜插件(v2.19.23)(豆瓣酱な)(sorallll)"),
                                storeDirectory("必选-修复类(v1.4)(Lux)"));
                    }
                    if (path.endsWith("/plugins")) {
                        return List.of(storeDirectory("l4d2_emshud_info.smx", false));
                    }
                    return List.of();
                });

        assertThat(service.loadYaml(1L)).isEmpty();
    }

    @Test
    void loadYaml_indexesStoreDirectoriesOnceForMultipleCorruptedNames() {
        when(instanceFileService.exists(eq(1L), anyString())).thenReturn(true);
        when(instanceFileService.readTextFile(eq(1L), anyString(), eq(StandardCharsets.UTF_8)))
                .thenReturn("""
                        enabled_plugins:
                          - name: "???-????????"
                            source: "upload"
                            enabled_at: 1
                            files:
                              - "addons/sourcemod/plugins/alpha.smx"
                          - name: "???-????"
                            source: "upload"
                            enabled_at: 2
                            files:
                              - "addons/sourcemod/plugins/beta.smx"
                        """);
        when(instanceFileService.listFiles(eq(1L), anyString()))
                .thenAnswer(invocation -> {
                    String path = invocation.getArgument(1);
                    if (path.endsWith("/plugins_store")) {
                        return List.of(
                                storeDirectory("中文插件甲"),
                                storeDirectory("中文插件乙"));
                    }
                    if (path.contains("中文插件甲") && path.endsWith("/plugins")) {
                        return List.of(storeDirectory("nested/alpha.smx", false));
                    }
                    if (path.contains("中文插件乙") && path.endsWith("/plugins")) {
                        return List.of(storeDirectory("beta.smx", false));
                    }
                    return List.of();
                });

        assertThat(service.loadYaml(1L))
                .extracting(EnabledPlugin::getName)
                .containsExactly("中文插件甲", "中文插件乙");
        verify(instanceFileService, times(1)).listFiles(
                eq(1L), eq("left4dead2/addons/sourcemod/plugins_store"));
        verify(instanceFileService, times(1)).listFiles(eq(1L),
                eq("left4dead2/addons/sourcemod/plugins_store/中文插件甲"
                        + "/left4dead2/addons/sourcemod/plugins"));
        verify(instanceFileService, times(1)).listFiles(eq(1L),
                eq("left4dead2/addons/sourcemod/plugins_store/中文插件乙"
                        + "/left4dead2/addons/sourcemod/plugins"));
    }

    @Test
    void loadYaml_keepsExactChineseNameWithoutDirectoryLookup() {
        when(instanceFileService.exists(eq(1L), anyString())).thenReturn(true);
        when(instanceFileService.readTextFile(eq(1L), anyString(), eq(StandardCharsets.UTF_8)))
                .thenReturn("""
                        enabled_plugins:
                          - name: "自选-排行榜插件(v2.19.23)(豆瓣酱な)(sorallll)"
                            source: "upload"
                            enabled_at: 1
                        """);

        assertThat(service.loadYaml(1L))
                .extracting(EnabledPlugin::getName)
                .containsExactly("自选-排行榜插件(v2.19.23)(豆瓣酱な)(sorallll)");
        verify(instanceFileService, never()).listFiles(anyLong(), anyString());
    }

    private com.gameplatform.plugin.service.FileAccessService.FileInfo storeDirectory(String name) {
        return storeDirectory(name, true);
    }

    private com.gameplatform.plugin.service.FileAccessService.FileInfo storeDirectory(
            String name, boolean directory) {
        com.gameplatform.plugin.service.FileAccessService.FileInfo info =
                new com.gameplatform.plugin.service.FileAccessService.FileInfo();
        info.setName(name);
        info.setDirectory(directory);
        return info;
    }
}

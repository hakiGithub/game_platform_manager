package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.EnabledPlugin;
import com.gameplatform.plugin.service.InstanceFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FileRefsService 单元测试。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
class FileRefsServiceTest {

    private InstanceFileService instanceFileService;
    private EnabledPluginsService enabledPluginsService;
    private FileRefsService service;

    @BeforeEach
    void setUp() {
        instanceFileService = mock(InstanceFileService.class);
        enabledPluginsService = mock(EnabledPluginsService.class);
        L4D2PathResolver pathResolver = new L4D2PathResolver();
        service = new FileRefsService(instanceFileService, pathResolver, enabledPluginsService);
    }

    @Test
    void normalizeRelPath_lowercasesAndReplacesBackslash() {
        assertThat(service.normalizeRelPath("CFG/SourceMod\\A.cfg"))
                .isEqualTo("cfg/sourcemod/a.cfg");
    }

    @Test
    void normalizeRelPath_stripsLeadingDotSlash() {
        assertThat(service.normalizeRelPath("./cfg/sourcemod/a.cfg"))
                .isEqualTo("cfg/sourcemod/a.cfg");
    }

    @Test
    void loadRefs_emptyYaml_returnsEmptyMap() {
        when(enabledPluginsService.loadYaml(1L)).thenReturn(List.of());
        assertThat(service.loadRefs(1L)).isEmpty();
    }

    @Test
    void rebuild_fromEnabledPluginsYaml() {
        EnabledPlugin p1 = new EnabledPlugin();
        p1.setName("p1");
        p1.setFiles(List.of("addons/sourcemod/plugins/a.smx", "cfg/sourcemod/shared.cfg"));
        EnabledPlugin p2 = new EnabledPlugin();
        p2.setName("p2");
        p2.setFiles(List.of("cfg/sourcemod/shared.cfg"));
        when(enabledPluginsService.loadYaml(1L)).thenReturn(List.of(p1, p2));

        service.rebuild(1L);

        assertThat(service.loadRefs(1L).get("cfg/sourcemod/shared.cfg"))
                .containsExactlyInAnyOrder("p1", "p2");
        assertThat(service.loadRefs(1L).get("addons/sourcemod/plugins/a.smx"))
                .containsExactly("p1");
    }

    @Test
    void removeRefs_returnsZeroedFiles() {
        EnabledPlugin p1 = new EnabledPlugin();
        p1.setName("p1");
        p1.setFiles(List.of("shared.cfg"));
        EnabledPlugin p2 = new EnabledPlugin();
        p2.setName("p2");
        p2.setFiles(List.of("shared.cfg"));
        when(enabledPluginsService.loadYaml(1L)).thenReturn(List.of(p1, p2));
        service.rebuild(1L);

        List<String> zeroed = service.removeRefs(1L, "p1");
        assertThat(zeroed).isEmpty();  // shared.cfg 仍被 p2 引用

        zeroed = service.removeRefs(1L, "p2");
        assertThat(zeroed).containsExactly("shared.cfg");
    }

    @Test
    void addRefs_onlyMemory_noRemoteWrite() {
        when(enabledPluginsService.loadYaml(1L)).thenReturn(List.of());
        service.loadRefs(1L);
        service.addRefs(1L, "p1", List.of("a.cfg"));
        assertThat(service.loadRefs(1L).get("a.cfg")).containsExactly("p1");
    }
}

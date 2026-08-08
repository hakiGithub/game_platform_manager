package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.util.GbkCodecUtil;
import com.gameplatform.plugin.service.FileAccessService.FileInfo;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PluginInstallServiceSmxListTest {

    private PluginInstallService service;
    private InstanceFileService instanceFileService;

    @BeforeEach
    void setUp() {
        instanceFileService = mock(InstanceFileService.class);
        InstanceQueryService instanceQueryService = mock(InstanceQueryService.class);
        InstanceVO instance = new InstanceVO();
        instance.setId(100L);
        when(instanceQueryService.getInstanceById(100L)).thenReturn(instance);

        service = new PluginInstallService(
                instanceQueryService,
                instanceFileService,
                mock(RconService.class),
                mock(FileRefsService.class),
                new L4D2PathResolver(),
                mock(PluginMetaService.class),
                mock(EnabledPluginsService.class));
    }

    @Test
    void listPluginSmxIds_shouldReturnSortedSmxFileIds() {
        when(instanceFileService.listFiles(eq(100L),
                eq("left4dead2/addons/sourcemod/plugins_store/myplugin/left4dead2/addons/sourcemod/plugins")))
                .thenReturn(List.of(
                        fileInfo("beta.smx", false),
                        fileInfo("alpha.smx", false),
                        fileInfo("nested", true)));

        List<String> ids = service.listPluginSmxIds(100L, "myplugin");

        assertThat(ids).containsExactly("alpha", "beta");
    }

    @Test
    void listPluginSmxIds_shouldReturnEmptyWhenDirMissing() {
        when(instanceFileService.listFiles(anyLong(), anyString())).thenThrow(new RuntimeException("dir missing"));
        List<String> ids = service.listPluginSmxIds(100L, "nonexistent");
        assertThat(ids).isEmpty();
    }

    private FileInfo fileInfo(String name, boolean isDir) {
        FileInfo f = new FileInfo();
        f.setName(name);
        f.setDirectory(isDir);
        return f;
    }
}

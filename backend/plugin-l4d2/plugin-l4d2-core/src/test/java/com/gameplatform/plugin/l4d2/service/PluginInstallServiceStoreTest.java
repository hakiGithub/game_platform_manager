package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.util.GbkCodecUtil;
import com.gameplatform.plugin.l4d2.vo.PluginMeta;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PluginInstallServiceStoreTest {

    private PluginInstallService service;
    private InstanceFileService instanceFileService;
    private PluginMetaService pluginMetaService;
    private InstanceQueryService instanceQueryService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        instanceFileService = mock(InstanceFileService.class);
        pluginMetaService = mock(PluginMetaService.class);
        instanceQueryService = mock(InstanceQueryService.class);
        InstanceVO instance = new InstanceVO();
        instance.setId(100L);
        instance.setHostId(1L);
        when(instanceQueryService.getInstanceById(100L)).thenReturn(instance);

        service = new PluginInstallService(
                instanceQueryService,
                instanceFileService,

                mock(FileRefsService.class),
                new L4D2PathResolver(),
                pluginMetaService,
                mock(EnabledPluginsService.class));
    }

    @Test
    void installFromLocalFile_zip_shouldExtractToPluginsStore() throws Exception {
        File zip = createTestZip("myplugin", "addons/sourcemod/plugins/myplugin.smx",
                "cfg/sourcemod/myplugin.cfg");

        service.installFromLocalFile(100L, zip);

        verify(instanceFileService).uploadLocalFile(eq(100L),
                eq("left4dead2/addons/sourcemod/plugins_store/myplugin/left4dead2/addons/sourcemod/plugins/myplugin.smx"),
                anyString());
        verify(instanceFileService).uploadLocalFile(eq(100L),
                eq("left4dead2/addons/sourcemod/plugins_store/myplugin/left4dead2/cfg/sourcemod/myplugin.cfg"),
                anyString());
        ArgumentCaptor<PluginMeta> metaCaptor = ArgumentCaptor.forClass(PluginMeta.class);
        verify(pluginMetaService).save(eq(100L), metaCaptor.capture());
        PluginMeta saved = metaCaptor.getValue();
        assertThat(saved.getName()).isEqualTo("myplugin");
        assertThat(saved.getSource()).isEqualTo("upload");
        assertThat(saved.getFileList()).contains(
                "addons/sourcemod/plugins/myplugin.smx",
                "cfg/sourcemod/myplugin.cfg");
    }

    private File createTestZip(String topLevelDir, String... entries) throws Exception {
        File zip = tempDir.resolve("test.zip").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry(topLevelDir + "/left4dead2/"));
            zos.closeEntry();
            for (String entry : entries) {
                zos.putNextEntry(new ZipEntry(topLevelDir + "/left4dead2/" + entry));
                zos.write("dummy".getBytes());
                zos.closeEntry();
            }
        }
        return zip;
    }
}

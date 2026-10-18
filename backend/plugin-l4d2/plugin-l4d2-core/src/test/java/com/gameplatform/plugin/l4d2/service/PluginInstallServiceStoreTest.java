package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.util.GbkCodecUtil;
import com.gameplatform.plugin.l4d2.vo.PluginMeta;
import com.gameplatform.plugin.service.InstanceArchiveImportRequest;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceArchiveImportResult;
import com.gameplatform.plugin.service.InstanceArchiveImportService;
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
    private InstanceArchiveImportService archiveImportService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        instanceFileService = mock(InstanceFileService.class);
        pluginMetaService = mock(PluginMetaService.class);
        instanceQueryService = mock(InstanceQueryService.class);
        archiveImportService = mock(InstanceArchiveImportService.class);
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
                mock(EnabledPluginsService.class),
                archiveImportService);
    }

    @Test
    void installFromLocalFile_zip_shouldExtractToPluginsStore() throws Exception {
        File zip = createTestZip("myplugin", "addons/sourcemod/plugins/myplugin.smx",
                "cfg/sourcemod/myplugin.cfg");
        stubArchiveImport(InstanceArchiveImportResult.builder()
                .pluginName("myplugin")
                .fileCount(2)
                .totalBytes(10)
                .relativePaths(List.of(
                        "left4dead2/addons/sourcemod/plugins/myplugin.smx",
                        "left4dead2/cfg/sourcemod/myplugin.cfg"))
                .build());

        service.installFromLocalFile(100L, zip);

        // 快路径：原始 ZIP 只交给归档导入一次，不再逐文件上传
        verify(archiveImportService).importArchive(any(), any());
        verify(instanceFileService, never()).uploadLocalFile(anyLong(), anyString(), anyString(), any());
        ArgumentCaptor<PluginMeta> metaCaptor = ArgumentCaptor.forClass(PluginMeta.class);
        verify(pluginMetaService).save(eq(100L), metaCaptor.capture());
        PluginMeta saved = metaCaptor.getValue();
        assertThat(saved.getName()).isEqualTo("myplugin");
        assertThat(saved.getSource()).isEqualTo("upload");
        assertThat(saved.getFileList()).contains(
                "addons/sourcemod/plugins/myplugin.smx",
                "cfg/sourcemod/myplugin.cfg");
    }

    @Test
    void installBuiltinZip_shouldUseManifestPluginIdAsTargetName() throws Exception {
        File zip = createTestZip("random-temp-name", "addons/sourcemod/plugins/a.smx");
        String pluginId = "必选-修复类(v1.1)(修复部分梯子导致的崩溃)(SilverShot and Peace-Maker)";
        stubArchiveImport(InstanceArchiveImportResult.builder()
                .pluginName(pluginId)
                .fileCount(1)
                .totalBytes(5)
                .relativePaths(List.of(
                        "left4dead2/addons/sourcemod/plugins/a.smx"))
                .build());

        service.installFromLocalFileWithPluginName(100L, zip, pluginId, null);

        ArgumentCaptor<InstanceArchiveImportRequest> requestCaptor =
                ArgumentCaptor.forClass(InstanceArchiveImportRequest.class);
        var listenerCaptor = ArgumentCaptor.forClass(
                com.gameplatform.plugin.service.InstanceArchiveImportListener.class);
        verify(archiveImportService).importArchive(requestCaptor.capture(), listenerCaptor.capture());
        InstanceArchiveImportRequest request = requestCaptor.getValue();
        assertThat(request.getTargetRelativePath())
                .isEqualTo("left4dead2/addons/sourcemod/plugins_store/" + pluginId);
        assertThat(request.getExpectedPluginName()).isEqualTo(pluginId);
        assertThat(request.getStructureRoot()).isEqualTo("left4dead2");
        ArgumentCaptor<PluginMeta> metaCaptor = ArgumentCaptor.forClass(PluginMeta.class);
        verify(pluginMetaService).save(eq(100L), metaCaptor.capture());
        assertThat(metaCaptor.getValue().getName()).isEqualTo(pluginId);
    }

    @Test
    void uploadSmx_shouldUseOriginalFilenameAsPluginName() throws Exception {
        service.installFromUpload(100L, new org.springframework.mock.web.MockMultipartFile(
                "file", "e2e-sample-plugin.smx", "application/octet-stream",
                new byte[]{0x01, 0x02, 0x03, 0x04}));

        verify(instanceFileService).uploadLocalFile(eq(100L),
                eq("left4dead2/addons/sourcemod/plugins_store/e2e-sample-plugin/left4dead2/addons/sourcemod/plugins/e2e-sample-plugin.smx"),
                anyString(), isNull());
        ArgumentCaptor<PluginMeta> metaCaptor = ArgumentCaptor.forClass(PluginMeta.class);
        verify(pluginMetaService).save(eq(100L), metaCaptor.capture());
        assertThat(metaCaptor.getValue().getName()).isEqualTo("e2e-sample-plugin");
    }

    @Test
    void uploadZip_responseNameMatchesInstalledDirectory() throws Exception {
        File zip = createTestZip("e2e-zip-plugin", "addons/sourcemod/plugins/a.smx");
        stubArchiveImport(InstanceArchiveImportResult.builder()
                .pluginName("e2e-zip-plugin")
                .fileCount(1)
                .totalBytes(5)
                .relativePaths(List.of(
                        "left4dead2/addons/sourcemod/plugins/a.smx"))
                .build());

        var result = service.installFromUpload(100L,
                new org.springframework.mock.web.MockMultipartFile(
                        "file", "e2e-zip-plugin.zip", "application/zip",
                        java.nio.file.Files.readAllBytes(zip.toPath())));

        assertThat(result.getName()).isEqualTo("e2e-zip-plugin");
    }

    private File createTestZip(String topLevelDir, String... entries) throws Exception {
        File zip = tempDir.resolve(topLevelDir + ".zip").toFile();
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

    private void stubArchiveImport(InstanceArchiveImportResult result) {
        when(archiveImportService.importArchive(any(), any())).thenAnswer(invocation -> {
            com.gameplatform.plugin.service.InstanceArchiveImportRequest request =
                    invocation.getArgument(0);
            request.getCommitAction().afterAtomicMove(result);
            return result;
        });
    }
}

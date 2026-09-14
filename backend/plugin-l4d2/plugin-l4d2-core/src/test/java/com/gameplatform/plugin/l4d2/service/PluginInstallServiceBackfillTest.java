package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.PluginMeta;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.FileAccessService.FileInfo;
import com.gameplatform.plugin.service.InstanceQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PluginInstallService#backfillStoreMeta} 单元测试：
 * 商店下载安装后回填 source=store 与 fileList/configFiles。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PluginInstallServiceBackfillTest {

    private static final String PLUGIN = "自选-马格南(v1.0.2)";
    private static final String STORE_L4D2 = "/store/" + PLUGIN + "/left4dead2";

    @Mock
    private InstanceQueryService instanceQueryService;

    @Mock
    private InstanceFileService instanceFileService;

    @Mock
    private FileRefsService fileRefsService;

    @Mock
    private L4D2PathResolver pathResolver;

    @Mock
    private PluginMetaService pluginMetaService;

    @Mock
    private EnabledPluginsService enabledPluginsService;

    private PluginInstallService service;

    @BeforeEach
    void setUp() {
        service = new PluginInstallService(instanceQueryService, instanceFileService,
                fileRefsService, pathResolver, pluginMetaService, enabledPluginsService);
        when(pathResolver.getPluginLeft4Dead2Path(PLUGIN)).thenReturn(STORE_L4D2);
    }

    private FileInfo file(String name) {
        FileInfo f = new FileInfo();
        f.setName(name);
        return f;
    }

    private FileInfo dir(String name) {
        FileInfo f = new FileInfo();
        f.setName(name);
        f.setDirectory(true);
        return f;
    }

    @Test
    void backfill_merges_scan_into_existing_meta_and_keeps_source_store() {
        // atomicMoveToStore 已写入的骨架元数据（fileList 为空）
        PluginMeta skeleton = new PluginMeta();
        skeleton.setName(PLUGIN);
        skeleton.setSource("store");
        skeleton.setCreatedAt(1L);
        when(pluginMetaService.load(1L, PLUGIN)).thenReturn(skeleton);

        // 库目录：left4dead2/{addons/sourcemod/{plugins/a.smx, cfg/x.cfg}, cfg/sourcemod/y.cfg, cfg/server.vdf}
        when(instanceFileService.listFiles(1L, STORE_L4D2)).thenReturn(List.of(dir("addons"), dir("cfg"), file("cfg/server.vdf")));
        when(instanceFileService.listFiles(1L, STORE_L4D2 + "/addons")).thenReturn(List.of(dir("sourcemod")));
        when(instanceFileService.listFiles(1L, STORE_L4D2 + "/addons/sourcemod"))
                .thenReturn(List.of(dir("plugins"), dir("cfg")));
        when(instanceFileService.listFiles(1L, STORE_L4D2 + "/addons/sourcemod/plugins"))
                .thenReturn(List.of(file("a.smx")));
        when(instanceFileService.listFiles(1L, STORE_L4D2 + "/addons/sourcemod/cfg"))
                .thenReturn(List.of(file("x.cfg")));
        when(instanceFileService.listFiles(1L, STORE_L4D2 + "/cfg")).thenReturn(List.of(dir("sourcemod")));
        when(instanceFileService.listFiles(1L, STORE_L4D2 + "/cfg/sourcemod"))
                .thenReturn(List.of(file("y.cfg")));

        service.backfillStoreMeta(1L, PLUGIN);

        ArgumentCaptor<PluginMeta> captor = ArgumentCaptor.forClass(PluginMeta.class);
        verify(pluginMetaService).save(eq(1L), captor.capture());
        PluginMeta saved = captor.getValue();
        assertEquals("store", saved.getSource(), "回填不得覆盖商店来源");
        assertTrue(saved.getFileList().contains("addons/sourcemod/plugins/a.smx"));
        assertTrue(saved.getFileList().contains("addons/sourcemod/cfg/x.cfg"));
        assertTrue(saved.getFileList().contains("cfg/sourcemod/y.cfg"));
        assertTrue(saved.getFileList().contains("cfg/server.vdf"));
        assertTrue(saved.getConfigFiles().contains("cfg/sourcemod/y.cfg"),
                "cfg/sourcemod/ 下的 .cfg 应计入 configFiles");
        assertTrue(saved.getConfigFiles().stream().noneMatch(p -> p.startsWith("addons")),
                "addons 下的 .cfg 不属于游戏 cfg/sourcemod 配置");
        assertTrue(saved.getUpdatedAt() != null && saved.getUpdatedAt() > 0);
    }

    @Test
    void backfill_creates_meta_when_missing() {
        when(pluginMetaService.load(1L, PLUGIN)).thenReturn(null);
        when(instanceFileService.listFiles(1L, STORE_L4D2)).thenReturn(List.of(file("addons")));
        when(instanceFileService.listFiles(1L, STORE_L4D2 + "/addons")).thenReturn(null);

        service.backfillStoreMeta(1L, PLUGIN);

        ArgumentCaptor<PluginMeta> captor = ArgumentCaptor.forClass(PluginMeta.class);
        verify(pluginMetaService).save(eq(1L), captor.capture());
        PluginMeta saved = captor.getValue();
        assertEquals(PLUGIN, saved.getName());
        assertEquals("store", saved.getSource());
        assertNotNull(saved.getCreatedAt(), "新建元数据应补 createdAt");
    }

    @Test
    void backfill_keeps_existing_fileList_without_rescan() {
        PluginMeta meta = new PluginMeta();
        meta.setName(PLUGIN);
        meta.setSource("store");
        meta.setFileList(List.of("addons/sourcemod/plugins/already.smx"));
        when(pluginMetaService.load(1L, PLUGIN)).thenReturn(meta);

        service.backfillStoreMeta(1L, PLUGIN);

        verify(pluginMetaService).save(eq(1L), eq(meta));
        verify(instanceFileService, never()).listFiles(anyLong(), contains("left4dead2"));
        assertEquals(List.of("addons/sourcemod/plugins/already.smx"), meta.getFileList());
    }

    @Test
    void backfill_swallows_errors_without_throwing() {
        when(pluginMetaService.load(1L, PLUGIN)).thenThrow(new RuntimeException("fs unavailable"));

        service.backfillStoreMeta(1L, PLUGIN);

        verify(pluginMetaService, never()).save(any(), any());
    }
}

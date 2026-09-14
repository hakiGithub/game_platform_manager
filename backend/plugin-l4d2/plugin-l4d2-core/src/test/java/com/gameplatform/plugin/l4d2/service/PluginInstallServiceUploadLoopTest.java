package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.service.FileTransferProgressCallback;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 安装上传循环的进度/取消语义测试（真实 ZIP 解压 + mock 远程上传）。
 *
 * <p>回归背景：上传循环曾对单文件异常吞掉继续、无取消检查点，
 * 导致任务取消在安装阶段完全无效且整过程零进度。
 */
@ExtendWith(MockitoExtension.class)
class PluginInstallServiceUploadLoopTest {

    @Mock
    private InstanceQueryService instanceQueryService;
    @Mock
    private InstanceFileService instanceFileService;
    @Mock
    private com.gameplatform.plugin.l4d2.service.FileRefsService fileRefsService;
    @Mock
    private L4D2PathResolver pathResolver;
    @Mock
    private com.gameplatform.plugin.l4d2.service.PluginMetaService pluginMetaService;
    @Mock
    private com.gameplatform.plugin.l4d2.service.EnabledPluginsService enabledPluginsService;

    private PluginInstallService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new PluginInstallService(instanceQueryService, instanceFileService,
                fileRefsService, pathResolver, pluginMetaService, enabledPluginsService);
    }

    /** 构造多插件归档：myplugin/left4dead2/{addons/a.txt, cfg/sourcemod/x.cfg} */
    private File buildPluginZip() throws Exception {
        File zip = new File(tempDir.toFile(), "myplugin.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("myplugin/left4dead2/addons/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("myplugin/left4dead2/addons/a.txt"));
            zos.write("addon-a".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("myplugin/left4dead2/cfg/sourcemod/x.cfg"));
            zos.write("cfg-x".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return zip;
    }

    private InstallProgressListener listenerOf(AtomicBoolean cancelled, List<Integer> percents) {
        return new InstallProgressListener() {
            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }

            @Override
            public void onProgress(int percent, String message) {
                percents.add(percent);
            }
        };
    }

    @Test
    void 取消标志置位_上传循环首个文件前即中止() throws Exception {
        File zip = buildPluginZip();
        InstanceVO instance = new InstanceVO();
        when(instanceQueryService.getInstanceById(9L)).thenReturn(instance);
        when(pathResolver.getPluginLeft4Dead2Path("myplugin")).thenReturn("plugins_store/myplugin/left4dead2");

        AtomicBoolean cancelled = new AtomicBoolean(true);
        List<Integer> percents = new ArrayList<>();

        L4D2PluginException e = assertThrows(L4D2PluginException.class,
                () -> service.installFromLocalFile(9L, zip, listenerOf(cancelled, percents)));

        assertTrue(e.getMessage().contains("取消"));
        // 取消在任一上传发生前生效
        verify(instanceFileService, never())
                .uploadLocalFile(anyLong(), anyString(), anyString(), any());
        verify(pluginMetaService, never()).save(anyLong(), any());
    }

    @Test
    void 逐文件上传_传输回调内取消即中止且循环彻底终止() throws Exception {
        File zip = buildPluginZip();
        InstanceVO instance = new InstanceVO();
        when(instanceQueryService.getInstanceById(9L)).thenReturn(instance);
        when(pathResolver.getPluginLeft4Dead2Path("myplugin")).thenReturn("plugins_store/myplugin/left4dead2");

        // 第一个文件传输中途置取消：下一次回调检查抛异常中止传输（SDK 异常即中止契约）
        AtomicBoolean cancelled = new AtomicBoolean(false);
        List<Integer> percents = new ArrayList<>();
        List<FileTransferProgressCallback> callbacks = new ArrayList<>();
        doAnswer(inv -> {
            FileTransferProgressCallback cb = inv.getArgument(3);
            if (cb != null) {
                callbacks.add(cb);
                cb.onStart(1024);
                cb.onProgress(256, 1024); // 正常上报
                cancelled.set(true);      // 模拟用户此刻点取消
                cb.onProgress(512, 1024); // 回调内检查取消 → 抛异常中止传输
            }
            return null;
        }).when(instanceFileService)
                .uploadLocalFile(eq(9L), anyString(), anyString(), any());

        L4D2PluginException e = assertThrows(L4D2PluginException.class,
                () -> service.installFromLocalFile(9L, zip, listenerOf(cancelled, percents)));

        assertTrue(e.getMessage().contains("取消"));
        // 只有第一个文件拿到回调；取消后剩余文件不再上传
        assertEquals(1, callbacks.size());
        verify(instanceFileService, never())
                .uploadLocalFile(eq(9L), contains("cfg/sourcemod/x.cfg"), anyString(), any());
        // 进度上报过解压完成与上传里程碑
        assertTrue(percents.contains(18));
        assertTrue(percents.stream().anyMatch(p -> p >= 20));
        // 清单未写入（任务中止）
        verify(pluginMetaService, never()).save(anyLong(), any());
    }

    @Test
    void 正常安装_上传逐文件进行且最终写清单() throws Exception {
        File zip = buildPluginZip();
        InstanceVO instance = new InstanceVO();
        when(instanceQueryService.getInstanceById(9L)).thenReturn(instance);
        when(pathResolver.getPluginLeft4Dead2Path("myplugin")).thenReturn("plugins_store/myplugin/left4dead2");

        AtomicBoolean cancelled = new AtomicBoolean(false);
        List<Integer> percents = new ArrayList<>();
        List<FileTransferProgressCallback> callbacks = new ArrayList<>();
        doAnswer(inv -> {
            FileTransferProgressCallback cb = inv.getArgument(3);
            if (cb != null) {
                callbacks.add(cb);
                cb.onStart(1024);
                cb.onProgress(256, 1024);
                cb.onProgress(1024, 1024);
                cb.onComplete();
            }
            return null;
        }).when(instanceFileService)
                .uploadLocalFile(eq(9L), anyString(), anyString(), any());

        service.installFromLocalFile(9L, zip, listenerOf(cancelled, percents));

        // 两个文件各走一次带回调的上传
        assertEquals(2, callbacks.size());
        // 进度单调且到达上传区间上界（≤90）
        for (int i = 1; i < percents.size(); i++) {
            assertTrue(percents.get(i) >= percents.get(i - 1));
        }
        assertTrue(percents.get(percents.size() - 1) <= 90);
        // 字节级进度到达区间上界附近（两文件共 ~13B，90% 上界 90%）
        verify(pluginMetaService).save(eq(9L), any());
    }
}

package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.MissionInfoVO;
import com.gameplatform.plugin.l4d2.vo.VpkTrimResultVO;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MapService 单元测试。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MapServiceTest {

    @Mock
    private VpkParserService vpkParserService;

    @Mock
    private VpkTrimService vpkTrimService;

    @Mock
    private InstanceQueryService instanceQueryService;

    @Mock
    private InstanceFileService instanceFileService;

    @Mock
    private RconService rconService;

    private final L4D2Config config = new L4D2Config();

    private final L4D2PathResolver pathResolver = new L4D2PathResolver();

    private MapService mapService;

    private InstanceVO instance;

    @BeforeEach
    void setUp() {
        mapService = new MapService(
                vpkParserService, vpkTrimService, instanceQueryService,
                instanceFileService, rconService, config, pathResolver);

        instance = new InstanceVO();
        instance.setId(1L);
        instance.setHostId(10L);
        instance.setHostIp("127.0.0.1");
        instance.setInstallPath("/home/l4d2");
        Map<String, Object> configInfo = new HashMap<>();
        configInfo.put("rconPort", 27015);
        configInfo.put("rconPassword", "test-pwd");
        instance.setConfigInfo(configInfo);
        when(instanceQueryService.getInstanceById(1L)).thenReturn(instance);
    }

    // ============================================================
    // hotReload 测试
    // ============================================================

    @Test
    void hotReload_success() {
        String expectedCommand = config.getMapHotReload().getCommand();
        when(rconService.executeCommand(eq(1L), eq(expectedCommand)))
                .thenReturn("ok");

        mapService.hotReload(1L);

        verify(rconService, times(1)).executeCommand(
                eq(1L), eq(expectedCommand));
    }

    @Test
    void hotReload_rconFailed() {
        when(rconService.executeCommand(anyLong(), anyString()))
                .thenThrow(new RuntimeException("connection refused"));

        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> mapService.hotReload(1L));
        assertEquals(L4D2PluginException.RCON, ex.getCode());
    }

    @Test
    void hotReload_instanceNotFound() {
        when(instanceQueryService.getInstanceById(999L)).thenReturn(null);
        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> mapService.hotReload(999L));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
    }

    // ============================================================
    // trimMap 测试
    // ============================================================

    @Test
    void trimMap_success() {
        String addonsPath = "left4dead2/addons";
        String remoteVpkPath = addonsPath + "/test.vpk";

        VpkTrimResultVO trimResult = new VpkTrimResultVO();
        trimResult.setFileName("test.vpk");
        trimResult.setOriginalSize(1000);
        trimResult.setTrimmedSize(400);
        trimResult.setSavedBytes(600);
        trimResult.setTotalEntries(10);
        trimResult.setTrimmedEntries(6);
        trimResult.setBackupCreated(true);
        when(vpkTrimService.trim(any(), eq(true))).thenReturn(trimResult);

        VpkTrimResultVO result = mapService.trimMap(1L, "test.vpk");

        assertEquals("test.vpk", result.getFileName());
        assertEquals(1000, result.getOriginalSize());
        assertEquals(400, result.getTrimmedSize());
        assertEquals(600, result.getSavedBytes());
        assertTrue(result.isBackupCreated());

        // 验证下载远程 VPK 到本地临时文件
        verify(instanceFileService).downloadFile(eq(1L), eq(remoteVpkPath), anyString());
        // 验证上传裁剪后文件覆盖原 VPK
        verify(instanceFileService).uploadLocalFile(eq(1L), eq(remoteVpkPath), anyString());
        // 验证清缓存
        verify(vpkParserService).clearCache(addonsPath);
    }

    @Test
    void trimMap_invalidMapName_withDoubleDot() {
        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> mapService.trimMap(1L, "../etc/passwd"));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
        verifyNoFileAccessInteractions();
    }

    @Test
    void trimMap_invalidMapName_withSlash() {
        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> mapService.trimMap(1L, "sub/test.vpk"));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
        verifyNoFileAccessInteractions();
    }

    @Test
    void trimMap_invalidMapName_withBackslash() {
        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> mapService.trimMap(1L, "sub\\test.vpk"));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
        verifyNoFileAccessInteractions();
    }

    @Test
    void trimMap_instanceNotFound() {
        when(instanceQueryService.getInstanceById(999L)).thenReturn(null);
        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> mapService.trimMap(999L, "test.vpk"));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
    }

    // ============================================================
    // trimBatch 测试
    // ============================================================

    @Test
    void trimBatch_success() {
        VpkTrimResultVO resultA = new VpkTrimResultVO();
        resultA.setFileName("a.vpk");
        resultA.setOriginalSize(100);
        resultA.setTrimmedSize(50);
        resultA.setSavedBytes(50);
        resultA.setTotalEntries(4);
        resultA.setTrimmedEntries(2);
        resultA.setBackupCreated(true);

        VpkTrimResultVO resultB = new VpkTrimResultVO();
        resultB.setFileName("b.vpk");
        resultB.setOriginalSize(200);
        resultB.setTrimmedSize(80);
        resultB.setSavedBytes(120);
        resultB.setTotalEntries(8);
        resultB.setTrimmedEntries(4);
        resultB.setBackupCreated(true);

        // 由于 trim 内部使用任意 File，无法区分两次调用，这里统一返回 resultA，再覆盖 fileName 验证
        when(vpkTrimService.trim(any(), eq(true))).thenAnswer(invocation -> {
            VpkTrimResultVO r = new VpkTrimResultVO();
            r.setOriginalSize(100);
            r.setTrimmedSize(50);
            r.setSavedBytes(50);
            r.setTotalEntries(4);
            r.setTrimmedEntries(2);
            r.setBackupCreated(true);
            return r;
        });

        List<VpkTrimResultVO> results = mapService.trimBatch(1L, List.of("a.vpk", "b.vpk"));

        assertEquals(2, results.size());
        // 验证 downloadFile 被调用 2 次（每个 VPK 一次）
        verify(instanceFileService, times(2)).downloadFile(eq(1L), anyString(), anyString());
        // 验证 uploadLocalFile 被调用 2 次
        verify(instanceFileService, times(2)).uploadLocalFile(eq(1L), anyString(), anyString());
        // 验证 trim 被调用 2 次
        verify(vpkTrimService, times(2)).trim(any(), eq(true));
    }

    @Test
    void trimBatch_partialFailure_returnsPlaceholder() {
        // 第一个 VPK 名合法但 trim 抛异常；第二个 VPK 名非法
        when(vpkTrimService.trim(any(), eq(true)))
                .thenThrow(new L4D2PluginException(L4D2PluginException.FILE, "trim error"));

        List<VpkTrimResultVO> results = mapService.trimBatch(1L, List.of("a.vpk", "../bad.vpk"));

        assertEquals(2, results.size());
        // 第一个：trim 失败，应返回占位结果
        // 第二个：路径校验失败，应返回占位结果
        for (VpkTrimResultVO r : results) {
            assertEquals(0, r.getOriginalSize());
            assertEquals(0, r.getTrimmedSize());
        }
    }

    // ============================================================
    // getMission 测试
    // ============================================================

    @Test
    void getMission_success() {
        String remoteVpkPath = "left4dead2/addons/test.vpk";

        MissionInfoVO mission = new MissionInfoVO();
        mission.setVpkName("test.vpk");
        mission.setTitle("Test Campaign");
        when(vpkTrimService.parseMission(any())).thenReturn(mission);

        MissionInfoVO result = mapService.getMission(1L, "test.vpk");

        assertEquals("test.vpk", result.getVpkName());
        assertEquals("Test Campaign", result.getTitle());

        // 验证下载文件
        verify(instanceFileService).downloadFile(eq(1L), eq(remoteVpkPath), anyString());
        // 验证调用了 parseMission
        verify(vpkTrimService).parseMission(any());
    }

    @Test
    void getMission_invalidMapName() {
        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> mapService.getMission(1L, "../passwd"));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
        verifyNoFileAccessInteractions();
    }

    // ============================================================
    // refreshCache 测试
    // ============================================================

    @Test
    void refreshCache_success() {
        mapService.refreshCache(1L);
        verify(vpkParserService).clearCache("left4dead2/addons");
    }

    @Test
    void refreshCache_instanceNotFound() {
        when(instanceQueryService.getInstanceById(999L)).thenReturn(null);
        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> mapService.refreshCache(999L));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
    }

    // ============================================================
    // deleteMap 测试
    // ============================================================

    @Test
    void deleteMap_success() {
        mapService.deleteMap(1L, "test.vpk");

        verify(instanceFileService).deleteFile(1L, "left4dead2/addons/test.vpk");
        verify(vpkParserService).clearCache("left4dead2/addons");
    }

    @Test
    void deleteMap_invalidMapName() {
        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> mapService.deleteMap(1L, "../../etc/passwd"));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
        verifyNoFileAccessInteractions();
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private void verifyNoFileAccessInteractions() {
        org.mockito.Mockito.verifyNoInteractions(instanceFileService);
    }
}

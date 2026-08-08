package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.dto.ServerConfigUpdateDTO;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.ServerConfigVO;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ServerConfigService 单元测试（对齐 plan §6.1.4）。
 *
 * <p>覆盖：配置解析、写入、多 tick 同步、RCON 重载、路径穿越防护。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServerConfigServiceTest {

    @Mock
    private InstanceQueryService instanceQueryService;

    @Mock
    private InstanceFileService instanceFileService;

    @Mock
    private RconService rconService;

    private L4D2Config config;

    private L4D2PathResolver pathResolver;

    private ServerConfigService service;

    private static final Long INSTANCE_ID = 1L;
    private static final Long HOST_ID = 10L;
    private static final String INSTALL_PATH = "/home/l4d2";
    private static final String CFG_PATH = "left4dead2/cfg";
    private static final String SERVER_CFG_PATH = CFG_PATH + "/server.cfg";

    @BeforeEach
    void setUp() {
        config = new L4D2Config();
        pathResolver = new L4D2PathResolver();
        service = new ServerConfigService(instanceQueryService, instanceFileService,
                pathResolver, config, rconService);
    }

    // ============================================================
    // get_server_config_parses_fields_correctly
    // ============================================================
    @Test
    void get_server_config_parses_fields_correctly() {
        InstanceVO instance = buildInstance();
        when(instanceQueryService.getInstanceById(INSTANCE_ID)).thenReturn(instance);

        String content = String.join("\n",
                "hostname \"Test Server\"",
                "rcon_password \"rcon123\"",
                "sv_password \"sv123\"",
                "sv_maxplayers 8",
                "sv_visiblemaxplayers 8",
                "map c1m1_hotel",
                "mp_gamemode coop",
                "z_difficulty Normal",
                "sv_cheats 1",
                "",
                "// [L4D2-MANAGER-CUSTOM]",
                "custom_line1 value1",
                "custom_line2 value2");
        when(instanceFileService.readTextFile(eq(INSTANCE_ID), eq(SERVER_CFG_PATH))).thenReturn(content);

        ServerConfigVO vo = service.getServerConfig(INSTANCE_ID);

        assertEquals(INSTANCE_ID, vo.getInstanceId());
        assertEquals("Test Server", vo.getHostname());
        assertEquals("rcon123", vo.getRconPassword());
        assertEquals("sv123", vo.getSvPassword());
        assertEquals(8, vo.getMaxPlayers());
        assertEquals(8, vo.getVisibleMaxPlayers());
        assertEquals("c1m1_hotel", vo.getMapName());
        assertEquals("coop", vo.getGameMode());
        assertEquals("Normal", vo.getDifficulty());
        assertNotNull(vo.getExtraConfig());
        assertEquals("1", vo.getExtraConfig().get("sv_cheats"));
        assertNotNull(vo.getCustomConfig());
        assertTrue(vo.getCustomConfig().contains("custom_line1 value1"));
        assertTrue(vo.getCustomConfig().contains("custom_line2 value2"));
    }

    // ============================================================
    // get_server_config_returns_empty_when_file_missing
    // ============================================================
    @Test
    void get_server_config_returns_empty_when_file_missing() {
        InstanceVO instance = buildInstance();
        when(instanceQueryService.getInstanceById(INSTANCE_ID)).thenReturn(instance);
        when(instanceFileService.readTextFile(eq(INSTANCE_ID), eq(SERVER_CFG_PATH)))
                .thenThrow(new RuntimeException("file not found"));

        ServerConfigVO vo = service.getServerConfig(INSTANCE_ID);

        assertEquals(INSTANCE_ID, vo.getInstanceId());
        assertNull(vo.getHostname());
        assertNull(vo.getRconPassword());
        assertNull(vo.getMaxPlayers());
        assertNull(vo.getCustomConfig());
    }

    // ============================================================
    // update_server_config_writes_main_file
    // ============================================================
    @Test
    void update_server_config_writes_main_file() {
        InstanceVO instance = buildInstance();
        when(instanceQueryService.getInstanceById(INSTANCE_ID)).thenReturn(instance);
        when(instanceFileService.exists(eq(INSTANCE_ID), anyString())).thenReturn(false);

        ServerConfigUpdateDTO dto = buildFullDto();

        service.updateServerConfig(INSTANCE_ID, dto);

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(instanceFileService).writeTextFile(eq(INSTANCE_ID), eq(SERVER_CFG_PATH), contentCaptor.capture());
        String written = contentCaptor.getValue();
        assertTrue(written.contains("hostname \"Test Server\""));
        assertTrue(written.contains("rcon_password \"rcon123\""));
        assertTrue(written.contains("sv_password \"sv123\""));
        assertTrue(written.contains("sv_maxplayers 8"));
        assertTrue(written.contains("sv_visiblemaxplayers 8"));
        assertTrue(written.contains("map c1m1_hotel"));
        assertTrue(written.contains("mp_gamemode coop"));
        assertTrue(written.contains("z_difficulty Normal"));
    }

    // ============================================================
    // update_server_config_syncs_multi_tick_when_target_exists
    // ============================================================
    @Test
    void update_server_config_syncs_multi_tick_when_target_exists() {
        InstanceVO instance = buildInstance();
        when(instanceQueryService.getInstanceById(INSTANCE_ID)).thenReturn(instance);

        // 仅 128tick 文件存在
        when(instanceFileService.exists(INSTANCE_ID, CFG_PATH + "/server.cfg.128tick")).thenReturn(true);
        when(instanceFileService.exists(INSTANCE_ID, CFG_PATH + "/server.cfg.100tick")).thenReturn(false);
        when(instanceFileService.exists(INSTANCE_ID, CFG_PATH + "/server.cfg.60tick")).thenReturn(false);
        when(instanceFileService.exists(INSTANCE_ID, CFG_PATH + "/server.cfg.30tick")).thenReturn(false);

        ServerConfigUpdateDTO dto = buildFullDto();

        service.updateServerConfig(INSTANCE_ID, dto);

        // 主文件 + 128tick = 2 次
        verify(instanceFileService).writeTextFile(eq(INSTANCE_ID), eq(SERVER_CFG_PATH), anyString());
        verify(instanceFileService).writeTextFile(eq(INSTANCE_ID),
                eq(CFG_PATH + "/server.cfg.128tick"), anyString());
        verify(instanceFileService, never()).writeTextFile(eq(INSTANCE_ID),
                eq(CFG_PATH + "/server.cfg.100tick"), anyString());
        verify(instanceFileService, never()).writeTextFile(eq(INSTANCE_ID),
                eq(CFG_PATH + "/server.cfg.60tick"), anyString());
        verify(instanceFileService, never()).writeTextFile(eq(INSTANCE_ID),
                eq(CFG_PATH + "/server.cfg.30tick"), anyString());
        verify(instanceFileService, times(2)).writeTextFile(eq(INSTANCE_ID), anyString(), anyString());
    }

    // ============================================================
    // update_server_config_skips_multi_tick_when_target_missing
    // ============================================================
    @Test
    void update_server_config_skips_multi_tick_when_target_missing() {
        InstanceVO instance = buildInstance();
        when(instanceQueryService.getInstanceById(INSTANCE_ID)).thenReturn(instance);
        when(instanceFileService.exists(eq(INSTANCE_ID), anyString())).thenReturn(false);

        ServerConfigUpdateDTO dto = buildFullDto();

        service.updateServerConfig(INSTANCE_ID, dto);

        // 仅主文件被写入
        verify(instanceFileService, times(1)).writeTextFile(eq(INSTANCE_ID), anyString(), anyString());
        verify(instanceFileService).writeTextFile(eq(INSTANCE_ID), eq(SERVER_CFG_PATH), anyString());
    }

    // ============================================================
    // update_server_config_preserves_custom_config_after_marker
    // ============================================================
    @Test
    void update_server_config_preserves_custom_config_after_marker() {
        InstanceVO instance = buildInstance();
        when(instanceQueryService.getInstanceById(INSTANCE_ID)).thenReturn(instance);
        when(instanceFileService.exists(eq(INSTANCE_ID), anyString())).thenReturn(false);

        ServerConfigUpdateDTO dto = buildFullDto();
        dto.setCustomConfig("say Hello World\nmp_disable_autostick 1");

        service.updateServerConfig(INSTANCE_ID, dto);

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(instanceFileService, atLeastOnce()).writeTextFile(eq(INSTANCE_ID), eq(SERVER_CFG_PATH),
                contentCaptor.capture());
        String written = contentCaptor.getValue();
        assertTrue(written.contains(ServerConfigService.CUSTOM_CONFIG_MARKER));
        assertTrue(written.contains("say Hello World"));
        assertTrue(written.contains("mp_disable_autostick 1"));

        // marker 在 customConfig 之前
        int markerIdx = written.indexOf(ServerConfigService.CUSTOM_CONFIG_MARKER);
        int customIdx = written.indexOf("say Hello World");
        assertTrue(markerIdx >= 0 && customIdx > markerIdx);
    }

    // ============================================================
    // reload_config_calls_rcon_exec
    // ============================================================
    @Test
    void reload_config_calls_rcon_exec() {
        InstanceVO instance = buildInstance();
        when(instanceQueryService.getInstanceById(INSTANCE_ID)).thenReturn(instance);

        service.reloadConfig(INSTANCE_ID);

        verify(rconService).executeCommand(eq(1L),
                eq("exec server.cfg"));
    }

    // ============================================================
    // get_file_content_rejects_path_traversal
    // ============================================================
    @Test
    void get_file_content_rejects_path_traversal() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getFileContent(INSTANCE_ID, "../etc/passwd"));

        verify(instanceFileService, never()).readTextFile(eq(INSTANCE_ID), anyString());
    }

    // ============================================================
    // update_file_content_rejects_path_traversal
    // ============================================================
    @Test
    void update_file_content_rejects_path_traversal() {
        assertThrows(IllegalArgumentException.class,
                () -> service.updateFileContent(INSTANCE_ID, "../etc/passwd", "content"));

        verify(instanceFileService, never()).writeTextFile(eq(INSTANCE_ID), anyString(), anyString());
    }

    // ============================================================
    // get_file_content_returns_content_for_valid_filename
    // ============================================================
    @Test
    void get_file_content_returns_content_for_valid_filename() {
        InstanceVO instance = buildInstance();
        when(instanceQueryService.getInstanceById(INSTANCE_ID)).thenReturn(instance);
        when(instanceFileService.readTextFile(eq(INSTANCE_ID), eq(CFG_PATH + "/server.cfg")))
                .thenReturn("hostname \"X\"");

        String content = service.getFileContent(INSTANCE_ID, "server.cfg");

        assertEquals("hostname \"X\"", content);
    }

    // ============================================================
    // update_file_content_writes_for_valid_filename
    // ============================================================
    @Test
    void update_file_content_writes_for_valid_filename() {
        InstanceVO instance = buildInstance();
        when(instanceQueryService.getInstanceById(INSTANCE_ID)).thenReturn(instance);

        service.updateFileContent(INSTANCE_ID, "server.cfg", "hostname \"Y\"");

        verify(instanceFileService).writeTextFile(eq(INSTANCE_ID), eq(CFG_PATH + "/server.cfg"),
                eq("hostname \"Y\""));
    }

    // ============================================================
    // update_server_config_throws_when_instance_missing
    // ============================================================
    @Test
    void update_server_config_throws_when_instance_missing() {
        when(instanceQueryService.getInstanceById(INSTANCE_ID)).thenReturn(null);

        ServerConfigUpdateDTO dto = buildFullDto();

        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> service.updateServerConfig(INSTANCE_ID, dto));
        assertTrue(ex.getMessage().contains("实例不存在"));
        verify(instanceFileService, never()).writeTextFile(eq(INSTANCE_ID), anyString(), anyString());
    }

    // ============================================================
    // get_file_content_rejects_slash_in_filename
    // ============================================================
    @Test
    void get_file_content_rejects_slash_in_filename() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getFileContent(INSTANCE_ID, "subdir/server.cfg"));
    }

    // ============================================================
    // get_file_content_rejects_backslash_in_filename
    // ============================================================
    @Test
    void get_file_content_rejects_backslash_in_filename() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getFileContent(INSTANCE_ID, "subdir\\server.cfg"));
    }

    // ============================================================
    // parse_server_config_handles_empty_content
    // ============================================================
    @Test
    void get_server_config_returns_empty_vo_when_content_empty() {
        InstanceVO instance = buildInstance();
        when(instanceQueryService.getInstanceById(INSTANCE_ID)).thenReturn(instance);
        when(instanceFileService.readTextFile(eq(INSTANCE_ID), eq(SERVER_CFG_PATH))).thenReturn("");

        ServerConfigVO vo = service.getServerConfig(INSTANCE_ID);

        assertEquals(INSTANCE_ID, vo.getInstanceId());
        assertNull(vo.getHostname());
        assertNull(vo.getRconPassword());
        assertFalse(vo.getCustomConfig() != null && !vo.getCustomConfig().isEmpty());
    }

    // ===== 辅助方法 =====

    private InstanceVO buildInstance() {
        InstanceVO vo = new InstanceVO();
        vo.setId(INSTANCE_ID);
        vo.setHostId(HOST_ID);
        vo.setInstallPath(INSTALL_PATH);
        vo.setGameCode("l4d2");
        Map<String, Object> configInfo = new HashMap<>();
        configInfo.put("rconPort", 27015);
        configInfo.put("rconPassword", "rcon123");
        vo.setConfigInfo(configInfo);
        return vo;
    }

    private ServerConfigUpdateDTO buildFullDto() {
        ServerConfigUpdateDTO dto = new ServerConfigUpdateDTO();
        dto.setInstanceId(INSTANCE_ID);
        dto.setHostname("Test Server");
        dto.setRconPassword("rcon123");
        dto.setSvPassword("sv123");
        dto.setMaxPlayers(8);
        dto.setVisibleMaxPlayers(8);
        dto.setMapName("c1m1_hotel");
        dto.setGameMode("coop");
        dto.setDifficulty("Normal");
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("sv_cheats", "1");
        dto.setExtraConfig(extra);
        return dto;
    }
}

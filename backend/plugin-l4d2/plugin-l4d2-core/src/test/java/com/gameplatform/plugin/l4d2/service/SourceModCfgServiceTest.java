package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.extension.PluginConfigResource;
import com.gameplatform.plugin.l4d2.extension.PluginConfigSpec;
import com.gameplatform.plugin.l4d2.parser.SourceModCfgParser;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.CandidatePathVO;
import com.gameplatform.plugin.l4d2.vo.config.ConfigItem;
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

import java.nio.charset.Charset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SourceModCfgService 单元测试。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SourceModCfgServiceTest {

    private static final Long INSTANCE_ID = 1L;
    private static final Long HOST_ID = 10L;
    private static final String INSTALL_PATH = "/home/l4d2";
    private static final String CFG_SOURCEMOD_ABS = "left4dead2/cfg/sourcemod";
    private static final String PLUGINS_ABS = "left4dead2/addons/sourcemod/plugins";

    @Mock
    private InstanceQueryService instanceQueryService;

    @Mock
    private InstanceFileService instanceFileService;

    @Mock
    private ExtensionClient extensionClient;

    @Mock
    private RconService rconService;

    @Mock
    private PluginConfigAuditService auditService;

    private final SourceModCfgParser cfgParser = new SourceModCfgParser();
    private final L4D2PathResolver pathResolver = new L4D2PathResolver();

    private SourceModCfgService service;

    private InstanceVO instance;

    @BeforeEach
    void setUp() {
        service = new SourceModCfgService(
                instanceQueryService, instanceFileService, extensionClient,
                cfgParser, pathResolver, rconService, auditService);
        instance = new InstanceVO();
        instance.setId(INSTANCE_ID);
        instance.setHostId(HOST_ID);
        instance.setInstallPath(INSTALL_PATH);
        when(instanceQueryService.getInstanceById(INSTANCE_ID)).thenReturn(instance);
        // 默认无扩展资源存在
        lenientGetReturnsEmpty();
    }

    private void lenientGetReturnsEmpty() {
        org.mockito.Mockito.lenient()
                .when(extensionClient.get(eq(PluginConfigResource.class), anyString()))
                .thenReturn(Optional.empty());
    }

    // ===== 1. 候选路径推导：l4d2_ai_upgrade（含 l4d_ 别名）=====

    @Test
    void getCandidatePaths_l4d2AiUpgrade_shouldReturnFourCandidatesWithL4dAlias() {
        List<String> paths = service.getCandidatePaths("l4d2_ai_upgrade");
        assertEquals(4, paths.size(), "l4d2_ 前缀应返回 4 个候选（含 l4d_ 别名）");
        assertEquals("cfg/sourcemod/l4d2_ai_upgrade.cfg", paths.get(0));
        assertEquals("addons/sourcemod/plugins/l4d2_ai_upgrade.cfg", paths.get(1));
        // l4d_ 别名
        assertTrue(paths.stream().anyMatch(p -> p.equals("cfg/sourcemod/l4d_ai_upgrade.cfg")),
                "应包含 l4d_ 别名候选");
        assertTrue(paths.stream().anyMatch(p -> p.equals("addons/sourcemod/plugins/l4d_ai_upgrade.cfg")),
                "应包含 l4d_ 别名候选");
    }

    // ===== 2. 候选路径推导：admin_esp（非 l4d_/l4d2_ 前缀，不生成别名）=====

    @Test
    void getCandidatePaths_adminEsp_shouldReturnTwoCandidates() {
        List<String> paths = service.getCandidatePaths("admin_esp");
        assertEquals(2, paths.size(), "非 l4d_/l4d2_ 前缀不生成别名");
        assertEquals("cfg/sourcemod/admin_esp.cfg", paths.get(0));
        assertEquals("addons/sourcemod/plugins/admin_esp.cfg", paths.get(1));
    }

    // ===== 2b. l4d_ ↔ l4d2_ 互转 =====

    @Test
    void getCandidatePaths_l4dPrefix_shouldGenerateL4d2Alias() {
        List<String> paths = service.getCandidatePaths("l4d_multi_slot");
        assertEquals(4, paths.size(), "l4d_ 前缀应返回 4 个候选（含 l4d2_ 别名）");
        assertTrue(paths.stream().anyMatch(p -> p.contains("l4d2_multi_slot.cfg")),
                "l4d_ 插件应同时返回 l4d2_ 别名候选");
    }

    @Test
    void getCandidatePaths_blankName_shouldReturnEmpty() {
        List<String> paths = service.getCandidatePaths("");
        assertTrue(paths.isEmpty(), "空插件名应返回空列表");
    }

    // ===== 3. getConfig：第一个候选存在 → 返回 1 个 item =====

    @Test
    void getConfig_firstCandidateExists_shouldReturnParsedResource() {
        String cfgAbs = CFG_SOURCEMOD_ABS + "/l4d2_ai_upgrade.cfg";
        when(instanceFileService.exists(eq(INSTANCE_ID), eq(cfgAbs))).thenReturn(true);
        when(instanceFileService.readTextFile(eq(INSTANCE_ID), eq(cfgAbs), any(Charset.class)))
                .thenReturn("\"test\" \"value\" // desc\n");

        PluginConfigResource resource = service.getConfig(INSTANCE_ID, "l4d2_ai_upgrade");

        assertNotNull(resource);
        PluginConfigSpec spec = resource.getSpec();
        assertNotNull(spec);
        assertEquals("l4d2_ai_upgrade", spec.getPluginName());
        assertEquals("l4d2_ai_upgrade.cfg", spec.getConfigName());
        assertEquals("cfg/sourcemod/l4d2_ai_upgrade.cfg", spec.getConfigPath());
        assertNotNull(spec.getItems());
        assertEquals(1, spec.getItems().size());
        assertEquals("test", spec.getItems().get(0).getKey());
        assertEquals("value", spec.getItems().get(0).getValue());
        // 不存在 → 调用 create
        verify(extensionClient).create(any(PluginConfigResource.class));
        verify(extensionClient, never()).update(any(PluginConfigResource.class));
    }

    // ===== 4. getConfig：所有候选均不存在 → 返回 null =====

    @Test
    void getConfig_noCandidateExists_shouldReturnNull() {
        when(instanceFileService.exists(eq(INSTANCE_ID), anyString())).thenReturn(false);

        PluginConfigResource resource = service.getConfig(INSTANCE_ID, "l4d2_ai_upgrade");

        assertNull(resource);
        verify(extensionClient, never()).create(any(PluginConfigResource.class));
        verify(extensionClient, never()).update(any(PluginConfigResource.class));
    }

    // ===== 5. updateConfig：候选存在 → 调用 writeTextFile =====

    @Test
    void updateConfig_candidateExists_shouldWriteSerializedContent() {
        String cfgAbs = CFG_SOURCEMOD_ABS + "/l4d2_ai_upgrade.cfg";
        when(instanceFileService.exists(eq(INSTANCE_ID), eq(cfgAbs))).thenReturn(true);
        String original = "\"sm_dp\" \"1.0\" // Default: 0.5 Min: 0 Max: 10 伤害倍率\n";
        when(instanceFileService.readTextFile(eq(INSTANCE_ID), eq(cfgAbs), any(Charset.class)))
                .thenReturn(original);

        // 解析后修改 value
        List<ConfigItem> items = cfgParser.parse(original);
        assertEquals(1, items.size());
        items.get(0).setValue("2.5");

        service.updateConfig(INSTANCE_ID, "l4d2_ai_upgrade", items);

        // 验证 writeTextFile 被调用，且写入的内容包含新值并保留注释
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(instanceFileService).writeTextFile(eq(INSTANCE_ID), eq(cfgAbs), captor.capture());
        String written = captor.getValue();
        assertTrue(written.contains("\"sm_dp\" \"2.5\""), "写入内容应包含新值 2.5");
        assertTrue(written.contains("// Default: 0.5"), "写入内容应保留 Default 注释");
        assertTrue(written.contains("Min: 0"), "写入内容应保留 Min 注释");
        assertTrue(written.contains("Max: 10"), "写入内容应保留 Max 注释");
        assertTrue(written.contains("伤害倍率"), "写入内容应保留描述注释");
        // 不存在扩展资源 → create 调用
        verify(extensionClient).create(any(PluginConfigResource.class));
    }

    @Test
    void updateConfig_candidateExists_existingResource_shouldCallUpdate() {
        String cfgAbs = CFG_SOURCEMOD_ABS + "/l4d2_ai_upgrade.cfg";
        when(instanceFileService.exists(eq(INSTANCE_ID), eq(cfgAbs))).thenReturn(true);
        String original = "\"sm_dp\" \"1.0\" // Default: 0.5\n";
        when(instanceFileService.readTextFile(eq(INSTANCE_ID), eq(cfgAbs), any(Charset.class)))
                .thenReturn(original);

        // 模拟已存在资源
        PluginConfigResource existing = new PluginConfigResource();
        existing.setName(INSTANCE_ID + "-l4d2_ai_upgrade");
        PluginConfigSpec spec = new PluginConfigSpec();
        spec.setInstanceId(INSTANCE_ID);
        existing.setSpec(spec);
        when(extensionClient.get(eq(PluginConfigResource.class), eq(INSTANCE_ID + "-l4d2_ai_upgrade")))
                .thenReturn(Optional.of(existing));

        List<ConfigItem> items = cfgParser.parse(original);
        items.get(0).setValue("2.0");

        service.updateConfig(INSTANCE_ID, "l4d2_ai_upgrade", items);

        verify(extensionClient).update(any(PluginConfigResource.class));
        verify(extensionClient, never()).create(any(PluginConfigResource.class));
    }

    @Test
    void updateConfig_noCandidateExists_shouldThrowBusinessException() {
        when(instanceFileService.exists(eq(INSTANCE_ID), anyString())).thenReturn(false);

        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> service.updateConfig(INSTANCE_ID, "l4d2_ai_upgrade", List.of()));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
        verify(instanceFileService, never())
                .writeTextFile(anyLong(), anyString(), anyString());
    }

    // ===== 6. listCandidates：第一个存在，第二个不存在（含 l4d_ 别名候选）=====

    @Test
    void listCandidates_firstExistsSecondNot_shouldReturnCorrectFlags() {
        String cfgAbs = CFG_SOURCEMOD_ABS + "/l4d2_ai_upgrade.cfg";
        String pluginsAbs = PLUGINS_ABS + "/l4d2_ai_upgrade.cfg";
        String cfgAliasAbs = CFG_SOURCEMOD_ABS + "/l4d_ai_upgrade.cfg";
        String pluginsAliasAbs = PLUGINS_ABS + "/l4d_ai_upgrade.cfg";
        when(instanceFileService.exists(eq(INSTANCE_ID), eq(cfgAbs))).thenReturn(true);
        when(instanceFileService.exists(eq(INSTANCE_ID), eq(pluginsAbs))).thenReturn(false);
        when(instanceFileService.exists(eq(INSTANCE_ID), eq(cfgAliasAbs))).thenReturn(false);
        when(instanceFileService.exists(eq(INSTANCE_ID), eq(pluginsAliasAbs))).thenReturn(false);

        List<CandidatePathVO> result = service.listCandidates(INSTANCE_ID, "l4d2_ai_upgrade");

        // l4d2_ 插件会生成 l4d_ 别名候选，共 4 个
        assertEquals(4, result.size());
        assertEquals("cfg/sourcemod/l4d2_ai_upgrade.cfg", result.get(0).getPath());
        assertTrue(result.get(0).isExists(), "第一个候选应存在");
        assertEquals("addons/sourcemod/plugins/l4d2_ai_upgrade.cfg", result.get(1).getPath());
        assertFalse(result.get(1).isExists(), "第二个候选应不存在");
        assertEquals("cfg/sourcemod/l4d_ai_upgrade.cfg", result.get(2).getPath());
        assertFalse(result.get(2).isExists(), "l4d_ 别名候选应不存在");
        assertEquals("addons/sourcemod/plugins/l4d_ai_upgrade.cfg", result.get(3).getPath());
        assertFalse(result.get(3).isExists(), "l4d_ 别名候选应不存在");
    }

    // ===== 7. parse + serialize 往返测试：保留注释和元数据 =====

    @Test
    void parseAndSerialize_roundtrip_shouldPreserveCommentsAndMetadata() {
        String original = "// header comment\n"
                + "\"sm_dp\" \"1.0\" // Default: 0.5 Min: 0 Max: 10 伤害倍率\n"
                + "\"sm_enable\" \"1\" // 是否启用\n";

        List<ConfigItem> items = cfgParser.parse(original);
        assertEquals(2, items.size());

        // 修改第一个 item 的值
        items.get(0).setValue("2.5");
        // 修改第二个 item 的值
        items.get(1).setValue("0");

        String serialized = cfgParser.serialize(items, original);

        // 验证注释完整保留
        assertTrue(serialized.contains("// header comment"), "应保留头部注释");
        assertTrue(serialized.contains("// Default: 0.5 Min: 0 Max: 10 伤害倍率"),
                "应保留完整元数据注释");
        assertTrue(serialized.contains("// 是否启用"), "应保留中文描述注释");

        // 验证值已更新
        assertTrue(serialized.contains("\"sm_dp\" \"2.5\""), "应更新 sm_dp 为 2.5");
        assertTrue(serialized.contains("\"sm_enable\" \"0\""), "应更新 sm_enable 为 0");

        // 验证再次解析能还原结构
        List<ConfigItem> reparsed = cfgParser.parse(serialized);
        assertEquals(2, reparsed.size());
        assertEquals("2.5", reparsed.get(0).getValue());
        assertEquals("0.5", reparsed.get(0).getDefaultValue());
        assertEquals(0.0, reparsed.get(0).getMin());
        assertEquals(10.0, reparsed.get(0).getMax());
    }

    // ===== 额外：实例不存在 =====

    @Test
    void getConfig_instanceNotFound_shouldThrowBusinessException() {
        when(instanceQueryService.getInstanceById(999L)).thenReturn(null);

        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> service.getConfig(999L, "any"));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
    }

    // ===== 8. applyTempConfig：委托给 RconService 并构造 sm_cvar 命令 =====

    @Test
    void applyTempConfig_shouldExecuteRconSmCvarCommand() {
        when(rconService.executeCommand(eq(INSTANCE_ID), eq("sm_cvar l4d2_max_players \"8\"")))
                .thenReturn("[SM] cvar changed");

        assertDoesNotThrow(() -> service.applyTempConfig(INSTANCE_ID, "l4d2_max_players", "8"));

        verify(rconService).executeCommand(eq(INSTANCE_ID), eq("sm_cvar l4d2_max_players \"8\""));
    }

    @Test
    void applyTempConfig_blankCvarName_shouldThrowBusinessException() {
        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> service.applyTempConfig(INSTANCE_ID, "  ", "8"));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
        verify(rconService, never()).executeCommand(anyLong(), anyString());
    }

    @Test
    void applyTempConfig_rconThrows_shouldWrapAsRconException() {
        when(rconService.executeCommand(eq(INSTANCE_ID), anyString()))
                .thenThrow(new RuntimeException("RCON connection refused"));

        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> service.applyTempConfig(INSTANCE_ID, "l4d2_max_players", "8"));
        assertEquals(L4D2PluginException.RCON, ex.getCode());
        assertTrue(ex.getMessage().contains("RCON connection refused"));
    }

    @Test
    void applyTempConfig_instanceNotFound_shouldThrowBusinessException() {
        when(instanceQueryService.getInstanceById(999L)).thenReturn(null);

        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> service.applyTempConfig(999L, "l4d2_max_players", "8"));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
    }

    // ===== 9. restoreDefaults：从 defaultValue 重建文件 =====

    @Test
    void restoreDefaults_noCandidateExists_shouldThrowBusinessException() {
        when(instanceFileService.exists(eq(INSTANCE_ID), anyString())).thenReturn(false);

        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> service.restoreDefaults(INSTANCE_ID, "l4d2_ai_upgrade"));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
    }

    @Test
    void restoreDefaults_allItemsHaveDefaults_shouldResetAndWriteFile() {
        String cfgAbs = CFG_SOURCEMOD_ABS + "/l4d2_ai_upgrade.cfg";
        when(instanceFileService.exists(eq(INSTANCE_ID), eq(cfgAbs))).thenReturn(true);
        String original = "\"sm_dp\" \"2.5\" // Default: 0.5 Min: 0 Max: 10 伤害倍率\n";
        when(instanceFileService.readTextFile(eq(INSTANCE_ID), eq(cfgAbs), any(Charset.class)))
                .thenReturn(original);

        service.restoreDefaults(INSTANCE_ID, "l4d2_ai_upgrade");

        // 验证写回的文件包含默认值 0.5
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(instanceFileService).writeTextFile(eq(INSTANCE_ID), eq(cfgAbs), captor.capture());
        String written = captor.getValue();
        assertTrue(written.contains("\"sm_dp\" \"0.5\""), "应将 sm_dp 重置为默认值 0.5");
        assertTrue(written.contains("// Default: 0.5"), "应保留 Default 注释");
    }

    @Test
    void restoreDefaults_noDefaultValue_shouldNotCallWriteTextFile() {
        String cfgAbs = CFG_SOURCEMOD_ABS + "/l4d2_ai_upgrade.cfg";
        when(instanceFileService.exists(eq(INSTANCE_ID), eq(cfgAbs))).thenReturn(true);
        // 没有 Default 注释
        String original = "\"sm_dp\" \"2.5\" // 伤害倍率\n";
        when(instanceFileService.readTextFile(eq(INSTANCE_ID), eq(cfgAbs), any(Charset.class)))
                .thenReturn(original);

        service.restoreDefaults(INSTANCE_ID, "l4d2_ai_upgrade");

        // 无默认值 → 无需写入
        verify(instanceFileService, never())
                .writeTextFile(anyLong(), anyString(), anyString());
    }

    @Test
    void restoreDefaults_alreadyAtDefault_shouldNotCallWriteTextFile() {
        String cfgAbs = CFG_SOURCEMOD_ABS + "/l4d2_ai_upgrade.cfg";
        when(instanceFileService.exists(eq(INSTANCE_ID), eq(cfgAbs))).thenReturn(true);
        // value 已等于 default
        String original = "\"sm_dp\" \"0.5\" // Default: 0.5\n";
        when(instanceFileService.readTextFile(eq(INSTANCE_ID), eq(cfgAbs), any(Charset.class)))
                .thenReturn(original);

        service.restoreDefaults(INSTANCE_ID, "l4d2_ai_upgrade");

        verify(instanceFileService, never())
                .writeTextFile(anyLong(), anyString(), anyString());
    }
}

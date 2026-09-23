package com.gameplatform.deploy;

import com.gameplatform.entity.GameMetadata;
import com.gameplatform.mapper.GameMetadataMapper;
import com.gameplatform.plugin.extension.DeployConfigDeclaration;
import com.gameplatform.plugin.extension.GameEnhancementExtension;
import com.gameplatform.plugin.extension.deploy.DeployVersionDeclaration;
import com.gameplatform.plugin.extension.deploy.PatchStepDeclaration;
import com.gameplatform.plugin.extension.deploy.ScriptPosition;
import com.gameplatform.plugin.extension.deploy.ScriptStepDeclaration;
import com.gameplatform.plugin.service.PluginFrameworkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 版本目录单一读者的读取与校验（design.md §10 V-01 / V-02）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("版本目录单一读者（V-01 四态 / V-02 §8.1 + N1…N5）")
class DeployVersionCatalogServiceTest {

    private static final String GAME = "stub";
    private static final String COMPOSE = "docker-compose";
    private static final String TAG_KEY = DeployVersionCatalogService.PLATFORM_IMAGE_TAG_KEY;
    private static final String TEMPLATE_WITH_PLACEHOLDER = "image: alpine:${PLATFORM_IMAGE_TAG:-3.20}";

    @Mock
    private GameMetadataMapper gameMetadataMapper;

    @Mock
    private PluginFrameworkService pluginFrameworkService;

    @Mock
    private GameEnhancementExtension extension;

    private DeployVersionCatalogService service;

    @BeforeEach
    void setUp() {
        service = new DeployVersionCatalogService(gameMetadataMapper, pluginFrameworkService);
        lenient().when(pluginFrameworkService.getExtensionByGameCode(GAME)).thenReturn(extension);
        // 桩游戏外置元数据的表侧形状：声明了保留变量 + 模板含占位符（N1/N2 的正面对照）
        tableSide(List.of(variable(TAG_KEY, "3.20")), TEMPLATE_WITH_PLACEHOLDER);
    }

    // ============================================================
    // V-01 四态
    // ============================================================

    @Test
    @DisplayName("V-01 无插件 → ABSENT")
    void read_noExtensionIsAbsent() {
        when(pluginFrameworkService.getExtensionByGameCode(GAME)).thenReturn(null);

        CatalogView view = service.read(GAME, COMPOSE);

        assertEquals(CatalogState.ABSENT, view.state());
        assertFalse(view.availableForWizard());
        assertTrue(view.entries().isEmpty());
    }

    @Test
    @DisplayName("V-01 getDeployVersions 抛异常 → ABSENT 且不外泄到向导（AC-20 构造手段）")
    void read_spiThrowingIsAbsentNotError() {
        when(extension.getDeployVersions(anyString())).thenThrow(new IllegalStateException("插件内部异常"));

        CatalogView view = assertDoesNotThrow(() -> service.read(GAME, COMPOSE));

        assertEquals(CatalogState.ABSENT, view.state());
        assertNull(view.invalidReason());
    }

    @Test
    @DisplayName("V-01 读取成功 0 条目 → EMPTY，且不产生任何「不合法」文案（AC-24 ③ / RISK-13）")
    void read_emptyIsDistinguishableFromInvalid() {
        when(extension.getDeployVersions(anyString())).thenReturn(List.of());

        CatalogView view = service.read(GAME, COMPOSE);

        assertEquals(CatalogState.EMPTY, view.state());
        assertNull(view.invalidReason());
        assertFalse(view.availableForWizard());
    }

    @Test
    @DisplayName("V-01 含非法条目 → INVALID + 归因非空、零可采纳条目")
    void read_invalidCarriesReason() {
        when(extension.getDeployVersions(anyString())).thenReturn(List.of(entry("bad id", null, null, null, null)));

        CatalogView view = service.read(GAME, COMPOSE);

        assertEquals(CatalogState.INVALID, view.state());
        assertNotNull(view.invalidReason());
        assertFalse(view.invalidReason().isBlank());
        assertTrue(view.entries().isEmpty(), "INVALID 不得带任何可采纳条目");
    }

    @Test
    @DisplayName("V-01 合法 → AVAILABLE 且保持声明序")
    void read_availableKeepsDeclarationOrder() {
        when(extension.getDeployVersions(anyString())).thenReturn(List.of(
                entry("1.0.0", "初版", true, null, null),
                entry("2.0.0", null, false, tagPatch(), null)));

        CatalogView view = service.read(GAME, COMPOSE);

        assertEquals(CatalogState.AVAILABLE, view.state());
        assertTrue(view.availableForWizard());
        assertEquals(List.of("1.0.0", "2.0.0"),
                view.entries().stream().map(VersionEntry::versionId).toList());
    }

    @Test
    @DisplayName("条目缺省值：displayName 服务端回退 versionId，步骤 label 原样透传（回退词面归界面）")
    void entryDefaultsSplitBetweenServiceAndUi() {
        when(extension.getDeployVersions(anyString()))
                .thenReturn(List.of(entry("2.0.0", null, false, unlabeledPatch(), null)));

        VersionEntry entry = service.read(GAME, COMPOSE).entries().get(0);

        assertEquals("2.0.0", entry.displayName());
        assertEquals(1, entry.stepSummary().get(0).index());
        assertNull(entry.stepSummary().get(0).label());
    }

    // ============================================================
    // V-02 PRD §8.1 基础项 + §16.3 N1…N5 逐规则各一条非法样本
    // ============================================================

    static Stream<IllegalSample> illegalSamples() {
        return Stream.of(
                new IllegalSample("versionId 为空", List.of(entry("  ", null, null, null, null))),
                new IllegalSample("versionId 同一游戏内唯一", List.of(
                        entry("1.0.0", null, null, null, null), entry("1.0.0", null, null, null, null))),
                new IllegalSample("versionId 字符集 [A-Za-z0-9._-]", List.of(entry("1.0 0", null, null, null, null))),
                new IllegalSample("default 至多一条", List.of(
                        entry("1.0.0", null, true, null, null), entry("2.0.0", null, true, null, null))),
                new IllegalSample("PATCH url 必填", List.of(entry("2.0.0", null, false,
                        new PatchStepDeclaration("p", null, "target", null, null, null, true), null))),
                new IllegalSample("PATCH targetPath 必填", List.of(entry("2.0.0", null, false,
                        new PatchStepDeclaration("p", "http://127.0.0.1:8099/a.zip", " ", null, null, null, true), null))),
                new IllegalSample("BR-05 PATCH targetPath 不得为绝对路径", List.of(entry("2.0.0", null, false,
                        new PatchStepDeclaration("p", "http://127.0.0.1:8099/a.zip", "/etc/passwd", null, null, null, true), null))),
                new IllegalSample("BR-05 PATCH targetPath 不得越界", List.of(entry("2.0.0", null, false,
                        new PatchStepDeclaration("p", "http://127.0.0.1:8099/a.zip", "../escape", null, null, null, true), null))),
                new IllegalSample("PATCH sha256 须为 64 位十六进制", List.of(entry("2.0.0", null, false,
                        new PatchStepDeclaration("p", "http://127.0.0.1:8099/a.zip", "target", "xyz", null, null, true), null))),
                new IllegalSample("SCRIPT label 必填非空", List.of(entry("2.0.0", null, false, null,
                        script(" ", "#!/bin/sh", null, ScriptPosition.HOST, null)))),
                new IllegalSample("SCRIPT 正文 / URL 二选一（两者皆无）", List.of(entry("2.0.0", null, false, null,
                        script("s", null, null, ScriptPosition.HOST, null)))),
                new IllegalSample("SCRIPT 正文 / URL 二选一（两者都有）", List.of(entry("2.0.0", null, false, null,
                        script("s", "#!/bin/sh", "http://127.0.0.1:8099/s.sh", ScriptPosition.HOST, null)))),
                new IllegalSample("SCRIPT url 须为 http(s)", List.of(entry("2.0.0", null, false, null,
                        script("s", null, "ftp://host/s.sh", ScriptPosition.HOST, null)))),
                new IllegalSample("N4 timeoutMs 低于下限", List.of(entry("2.0.0", null, false, null,
                        script("s", "#!/bin/sh", null, ScriptPosition.HOST, 999L)))).expecting("timeoutMs"),
                new IllegalSample("N4 timeoutMs 高于上限", List.of(entry("2.0.0", null, false, null,
                        script("s", "#!/bin/sh", null, ScriptPosition.HOST, 1_800_001L)))).expecting("timeoutMs"),
                new IllegalSample("N4 position 本期只允许 HOST", List.of(entry("2.0.0", null, false, null,
                        script("s", "#!/bin/sh", null, ScriptPosition.CONTAINER, null)))).expecting("HOST"),
                new IllegalSample("N1 声明 imageTag 但表侧未声明保留变量",
                        List.of(entryWithImageTag("2.0.0", "3.21")))
                        .withTableSide(List.of(variable("SOME_OTHER", "x")), TEMPLATE_WITH_PLACEHOLDER)
                        .expecting("（N1）"),
                new IllegalSample("N2 / 反例 b 表侧模板缺 ${PLATFORM_IMAGE_TAG 字面量",
                        List.of(entryWithImageTag("2.0.0", "3.21")))
                        .withTableSide(List.of(variable(TAG_KEY, "3.20")), "image: alpine:3.20")
                        .expecting("（N2）"),
                new IllegalSample("N2 不认 $PLATFORM_IMAGE_TAG 简写", List.of(entryWithImageTag("2.0.0", "3.21")))
                        .withTableSide(List.of(variable(TAG_KEY, "3.20")), "image: alpine:$PLATFORM_IMAGE_TAG")
                        .expecting("（N2）"),
                new IllegalSample("N3 imageTag 禁空白", List.of(entryWithImageTag("2.0.0", "3.2 1")))
                        .expecting("（N3）"),
                new IllegalSample("N3 imageTag 禁改写 .env 行结构", List.of(entryWithImageTag("2.0.0", "3.21\nEVIL=1")))
                        .expecting("（N3）"),
                new IllegalSample("N3 保留变量 defaultValue 须过 tag 格式",
                        List.of(entryWithImageTag("2.0.0", "3.21")))
                        .withTableSide(List.of(variable(TAG_KEY, "${OTHER}")), TEMPLATE_WITH_PLACEHOLDER)
                        .expecting("（N3）"),
                new IllegalSample("N5 集合外 deployType 带步骤",
                        List.of(entry("2.0.0", null, false, tagPatch(), null)))
                        .onDeployType("docker").expecting("patches/scripts/imageTag"),
                new IllegalSample("N5 集合外 deployType 带 imageTag",
                        List.of(entryWithImageTag("2.0.0", "3.21")))
                        .onDeployType("linuxgsm").expecting("patches/scripts/imageTag"));
    }

    @ParameterizedTest(name = "V-02 {0} ⇒ 整目录 INVALID")
    @MethodSource("illegalSamples")
    void illegalSampleInvalidatesWholeCatalog(IllegalSample sample) {
        if (sample.variables() != null || sample.composeTemplate() != null) {
            tableSide(sample.variables(), sample.composeTemplate());
        }
        when(extension.getDeployVersions(anyString())).thenReturn(sample.entries());

        CatalogView view = service.read(GAME, sample.deployType());

        assertEquals(CatalogState.INVALID, view.state(), sample.label());
        assertNotNull(view.invalidReason(), sample.label());
        if (sample.reasonFragment() != null) {
            assertTrue(view.invalidReason().contains(sample.reasonFragment()),
                    sample.label() + "：归因应指名该规则，实得 " + view.invalidReason());
        }
        assertTrue(view.entries().isEmpty(), sample.label() + "：禁止「跳过该条继续」的部分采纳");
    }

    @Test
    @DisplayName("V-02 一条合法 + 一条非法 ⇒ 整目录 INVALID，合法那条同样不采纳")
    void oneIllegalEntryInvalidatesLegalSibling() {
        when(extension.getDeployVersions(anyString())).thenReturn(List.of(
                entry("1.0.0", null, true, null, null),
                entry("bad id", null, false, null, null)));

        CatalogView view = service.read(GAME, COMPOSE);

        assertEquals(CatalogState.INVALID, view.state());
        assertTrue(view.entries().isEmpty());
    }

    @Test
    @DisplayName("V-02 反例 a：getDeployConfigs() 整节替换出的 variables/composeTemplate 不得让 N1/N2 判过")
    void pluginMergedDeployConfigsDoNotSatisfyN1N2() {
        // 表侧什么都没声明；插件走整节替换通道补上保留变量与占位模板 —— 部署侧看不见这份声明
        tableSide(List.of(), "image: alpine:3.20");
        when(extension.getDeployConfigs()).thenReturn(List.of(new DeployConfigDeclaration(COMPOSE, Map.of(
                "variables", List.of(variable(TAG_KEY, "3.20")),
                "composeTemplate", TEMPLATE_WITH_PLACEHOLDER))));
        when(extension.getDeployVersions(anyString())).thenReturn(List.of(entryWithImageTag("2.0.0", "3.21")));

        CatalogView view = service.read(GAME, COMPOSE);

        assertEquals(CatalogState.INVALID, view.state());
        assertTrue(view.invalidReason().contains("N1"), view.invalidReason());
        assertTrue(view.invalidReason().contains("N2"), view.invalidReason());
    }

    @Test
    @DisplayName("N5 只拦「集合外且带步骤/带 tag」：集合外 deployType 的零步骤条目仍合法")
    void unsupportedDeployTypeWithoutStepsIsStillValid() {
        tableSide(List.of(), null);
        when(extension.getDeployVersions(anyString())).thenReturn(List.of(entry("1.0.0", null, true, null, null)));

        CatalogView view = service.read(GAME, "docker");

        assertEquals(CatalogState.AVAILABLE, view.state());
    }

    @Test
    @DisplayName("保留变量声明态取表快照：未声明该 deployType 时 declared=false（§14.4 门控 ①）")
    void platformImageTagDeclarationComesFromTableSnapshot() {
        assertEquals(new DeployVersionCatalogService.PlatformImageTagDeclaration(true, "3.20"),
                service.platformImageTag(GAME, COMPOSE));
        assertEquals(new DeployVersionCatalogService.PlatformImageTagDeclaration(false, null),
                service.platformImageTag(GAME, "docker"));
    }

    @Test
    @DisplayName("BR-07 撞键清单的 ① 项数据源 = 表侧 variables[].name")
    void declaredVariableNamesComesFromTableSnapshot() {
        tableSide(List.of(variable("gamePort"), variable(TAG_KEY, "3.20")), TEMPLATE_WITH_PLACEHOLDER);

        assertEquals(List.of("gamePort", TAG_KEY), service.declaredVariableNames(GAME, COMPOSE));
    }

    // ============================================================
    // 夹具
    // ============================================================

    /** 一条非法样本：目录声明 + 可选的表侧快照 / deployType 覆盖 / 归因片段。 */
    private record IllegalSample(String label, List<DeployVersionDeclaration> entries,
                                 List<Map<String, Object>> variables, String composeTemplate,
                                 String deployType, String reasonFragment) {

        IllegalSample(String label, List<DeployVersionDeclaration> entries) {
            this(label, entries, null, null, COMPOSE, null);
        }

        IllegalSample withTableSide(List<Map<String, Object>> variables, String composeTemplate) {
            return new IllegalSample(label, entries, variables, composeTemplate, deployType, reasonFragment);
        }

        IllegalSample onDeployType(String type) {
            return new IllegalSample(label, entries, variables, composeTemplate, type, reasonFragment);
        }

        /** 钉住「是哪条规则判掉的」，防止样本被另一条规则顺带判过而空转。 */
        IllegalSample expecting(String fragment) {
            return new IllegalSample(label, entries, variables, composeTemplate, deployType, fragment);
        }
    }

    private void tableSide(List<Map<String, Object>> variables, String composeTemplate) {
        Map<String, Object> typeConfig = new HashMap<>();
        if (variables != null) {
            typeConfig.put("variables", variables);
        }
        if (composeTemplate != null) {
            typeConfig.put("composeTemplate", composeTemplate);
        }
        GameMetadata game = new GameMetadata();
        game.setGameCode(GAME);
        game.setDeployConfig(new HashMap<>(Map.of(COMPOSE, typeConfig)));
        when(gameMetadataMapper.selectByGameCode(GAME)).thenReturn(game);
    }

    private static Map<String, Object> variable(String name) {
        return variable(name, null);
    }

    private static Map<String, Object> variable(String name, Object defaultValue) {
        Map<String, Object> variable = new HashMap<>();
        variable.put("name", name);
        if (defaultValue != null) {
            variable.put("defaultValue", defaultValue);
        }
        return variable;
    }

    private static DeployVersionDeclaration entry(String versionId, String displayName, Boolean defaultEntry,
                                                  PatchStepDeclaration patch, ScriptStepDeclaration script) {
        return new DeployVersionDeclaration(versionId, displayName, null, defaultEntry,
                patch == null ? List.of() : List.of(patch),
                script == null ? List.of() : List.of(script));
    }

    private static DeployVersionDeclaration entryWithImageTag(String versionId, String imageTag) {
        return new DeployVersionDeclaration(versionId, null, imageTag, false, List.of(), List.of());
    }

    private static PatchStepDeclaration tagPatch() {
        return new PatchStepDeclaration("桩补丁", "http://127.0.0.1:8099/a.zip", "target", null, null, null, true);
    }

    /** 桩插件 2.0.0 条目的真实形状：PATCH 不声明 label（PRD §8.2 允许，缺省回退归界面）。 */
    private static PatchStepDeclaration unlabeledPatch() {
        return new PatchStepDeclaration(null, "http://127.0.0.1:8099/a.zip", "target", null, null, null, true);
    }

    private static ScriptStepDeclaration script(String label, String content, String url,
                                                ScriptPosition position, Long timeoutMs) {
        return new ScriptStepDeclaration(label, content, url, null, position, true, timeoutMs);
    }
}

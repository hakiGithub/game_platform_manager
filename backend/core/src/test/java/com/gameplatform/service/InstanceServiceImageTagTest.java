package com.gameplatform.service;

import com.gameplatform.deploy.CatalogView;
import com.gameplatform.deploy.DeployVersionCatalogService;
import com.gameplatform.deploy.VersionEntry;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.GameMetadata;
import com.gameplatform.mapper.GameMetadataMapper;
import com.gameplatform.plugin.extension.deploy.DeployVersionDeclaration;
import com.gameplatform.service.impl.InstanceServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code buildDeployConfig} 第 5.5 步的 imageTag 注入（design.md §14.4 / §14.4.3，B-08）。
 *
 * <p>注入点落在部署配置组装期而非扩展阶段：compose 模板驱动模式下适配器不做任何 tag 替换，
 * 而 {@code .env} 在 {@code PRE_DEPLOY} 就生成（§14.4）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("第 5.5 步 imageTag 注入（B-08 / AC-14 ④ / V-29 快路径）")
class InstanceServiceImageTagTest {

    private static final String TAG_KEY = DeployVersionCatalogService.PLATFORM_IMAGE_TAG_KEY;
    private static final String VERSION_KEY = DeployVersionCatalogService.VERSION_KEY;
    private static final String GAME = "stub";
    private static final String DEPLOY_TYPE = "docker-compose";

    @Mock
    private GameMetadataMapper gameMetadataMapper;

    @Mock
    private DeployVersionCatalogService deployVersionCatalogService;

    @InjectMocks
    private InstanceServiceImpl instanceService;

    private GameInstance instance;

    @BeforeEach
    void setUp() {
        // 桩游戏外置元数据：表侧 docker-compose 一节声明了保留变量 + 占位模板
        tableSide(List.of(Map.of("name", TAG_KEY, "defaultValue", "3.20", "hidden", true)));

        instance = new GameInstance();
        instance.setId(1L);
        instance.setGameId(1L);
        instance.setGameCode(GAME);
        instance.setDeployType(DEPLOY_TYPE);
        instance.setInstallPath("/srv/stub");
        instance.setConfigInfo(new HashMap<>());
    }

    // ============================================================
    // 门控 ①：未声明保留变量 ⇒ 完全不写
    // ============================================================

    @Test
    @DisplayName("门控 ①：游戏未声明保留变量 ⇒ 平台不写，该游戏的 .env 与模板逐字节不变")
    void undeclaredReservedVariableIsLeftAlone() throws Exception {
        tableSide(List.of(Map.of("name", "SOME_OTHER", "defaultValue", "x")));
        instance.setConfigInfo(config(VERSION_KEY, "2.0.0"));
        stubCatalog(entry("2.0.0", "acceptance-stub-2.0.0"));

        assertFalse(buildDeployConfig().containsKey(TAG_KEY));
    }

    @Test
    @DisplayName("门控 ①：未声明保留变量的游戏即便自己塞了该键，平台也不越权替它写")
    void undeclaredReservedVariableLeavesSubmittedValueAlone() throws Exception {
        tableSide(List.of());
        instance.setConfigInfo(config(VERSION_KEY, "2.0.0", TAG_KEY, "user-guessed-tag"));
        stubCatalog(entry("2.0.0", "acceptance-stub-2.0.0"));

        assertEquals("user-guessed-tag", buildDeployConfig().get(TAG_KEY));
    }

    // ============================================================
    // 门控 ②：声明了 ⇒ 值一律由平台写
    // ============================================================

    @Test
    @DisplayName("门控 ②：命中条目带 imageTag ⇒ 用条目值，覆盖用户提交的该键值（AC-14 ②④）")
    void declaredImageTagOverridesSubmittedValue() throws Exception {
        // 用户先经通用写接口把该键写成他值，再部署 ⇒ 组装出的仍是平台写的值
        instance.setConfigInfo(config(VERSION_KEY, "2.0.0", TAG_KEY, "latest"));
        stubCatalog(entry("2.0.0", "acceptance-stub-2.0.0"));

        assertEquals("acceptance-stub-2.0.0", buildDeployConfig().get(TAG_KEY));
    }

    @Test
    @DisplayName("门控 ②：命中条目未声明 imageTag ⇒ 沿用该保留变量的 defaultValue（模板既有 tag 不变）")
    void entryWithoutImageTagFallsBackToDeclaredDefault() throws Exception {
        instance.setConfigInfo(config(VERSION_KEY, "1.0.0"));
        stubCatalog(entry("1.0.0", null));

        assertEquals("3.20", buildDeployConfig().get(TAG_KEY));
    }

    @Test
    @DisplayName("目录取不到该版本（INVALID / 键已失效）⇒ 落回 defaultValue，绝不采信提交值")
    void unavailableCatalogNeverTrustsSubmittedValue() throws Exception {
        instance.setConfigInfo(config(VERSION_KEY, "9.9.9-gone", TAG_KEY, "attacker-chosen"));
        when(deployVersionCatalogService.read(GAME, DEPLOY_TYPE)).thenReturn(CatalogView.invalid("N2 未过"));

        assertEquals("3.20", buildDeployConfig().get(TAG_KEY));
    }

    @Test
    @DisplayName("AC-14 ④ 的完整形状：本次没选版本，声明了保留变量的游戏同样不采信提交值")
    void plainDeployAlsoRejectsSubmittedReservedValue() throws Exception {
        // §14.4 表 ② + PRD §8.4.3：「值一律由平台写」不以 deployVersion 存在为前提，
        // 否则未选版本的普通部署会把提交值经 generateEnvFileContent 直接落进 .env
        instance.setConfigInfo(config("maxPlayers", 20, TAG_KEY, "attacker-chosen"));

        assertEquals("3.20", buildDeployConfig().get(TAG_KEY));
    }

    // ============================================================
    // V-29 快路径：不读目录、不调 SPI
    // ============================================================

    @Test
    @DisplayName("V-29：configInfo 不含 deployVersion ⇒ 目录读取与 SPI 调用次数 = 0（门控仍生效、零额外读盘）")
    void noVersionKeyTriggersNoCatalogRead() throws Exception {
        instance.setConfigInfo(config("maxPlayers", 20));

        assertEquals("3.20", buildDeployConfig().get(TAG_KEY));
        verify(deployVersionCatalogService, never()).read(anyString(), anyString());
    }

    @Test
    @DisplayName("V-29：键值为 null 也不算「带版本部署」，不触发目录读取")
    void nullVersionKeyAlsoShortCircuits() throws Exception {
        Map<String, Object> configInfo = new HashMap<>();
        configInfo.put(VERSION_KEY, null);
        instance.setConfigInfo(configInfo);

        assertEquals("3.20", buildDeployConfig().get(TAG_KEY));
        verify(deployVersionCatalogService, never()).read(anyString(), anyString());
    }

    @Test
    @DisplayName("带版本键时目录恰好读一次（不重复读）")
    void versionedDeployReadsCatalogExactlyOnce() throws Exception {
        instance.setConfigInfo(config(VERSION_KEY, "2.0.0"));
        stubCatalog(entry("2.0.0", "acceptance-stub-2.0.0"));

        buildDeployConfig();

        verify(deployVersionCatalogService, times(1)).read(GAME, DEPLOY_TYPE);
    }

    @Test
    @DisplayName("非 compose 类游戏（表侧无该节）⇒ 门控 ① 生效，平台不写该键")
    void gameWithoutThatDeployTypeSectionIsUnaffected() throws Exception {
        when(gameMetadataMapper.selectById(1L)).thenReturn(null);
        when(deployVersionCatalogService.read(GAME, DEPLOY_TYPE)).thenReturn(CatalogView.absent());
        instance.setConfigInfo(config(VERSION_KEY, "2.0.0", TAG_KEY, "user-guessed-tag"));

        // 平台没写 ⇒ 载荷里的值原样留在组装结果上（它也不会进 .env：variables[] 里没有这一项）
        assertEquals("user-guessed-tag", buildDeployConfig().get(TAG_KEY));
    }

    @Test
    @DisplayName("保留变量声明了但 defaultValue 缺省 ⇒ 平台写 null，由 compose 的 ${VAR:-默认} 兜底")
    void blankDefaultValueFallsThroughToComposeInterpolation() throws Exception {
        tableSide(List.of(Map.of("name", TAG_KEY)));
        instance.setConfigInfo(config(VERSION_KEY, "1.0.0", TAG_KEY, "attacker-chosen"));
        stubCatalog(entry("1.0.0", null));

        assertNull(buildDeployConfig().get(TAG_KEY));
    }

    // ============================================================
    // 夹具
    // ============================================================

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildDeployConfig() throws Exception {
        Method method = InstanceServiceImpl.class
                .getDeclaredMethod("buildDeployConfig", GameInstance.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(instanceService, instance);
    }

    private void tableSide(List<Map<String, Object>> variables) {
        Map<String, Object> typeConfig = new HashMap<>();
        typeConfig.put("variables", variables);
        typeConfig.put("composeTemplate", "image: alpine:${PLATFORM_IMAGE_TAG:-3.20}");
        GameMetadata game = new GameMetadata();
        game.setGameCode(GAME);
        game.setDeployConfig(new HashMap<>(Map.of(DEPLOY_TYPE, typeConfig)));
        lenient().when(gameMetadataMapper.selectById(1L)).thenReturn(game);
    }

    private void stubCatalog(VersionEntry... entries) {
        when(deployVersionCatalogService.read(GAME, DEPLOY_TYPE))
                .thenReturn(CatalogView.available(List.of(entries)));
    }

    private static VersionEntry entry(String versionId, String imageTag) {
        DeployVersionDeclaration declaration =
                new DeployVersionDeclaration(versionId, null, imageTag, false, List.of(), List.of());
        return new VersionEntry(declaration, versionId, false, List.of());
    }

    private static Map<String, Object> config(Object... pairs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}

package com.gameplatform.plugin.dnftw;

import com.gameplatform.plugin.extension.GameEnhancementExtension;
import com.gameplatform.plugin.extension.deploy.DeployExtensionContext;
import com.gameplatform.plugin.extension.deploy.DeployVersionDeclaration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginClassLoader;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交付载体的可用性核对（design.md §7.3 B-14/B-15 完成判据；PRD AC-23 ①②④、V-17）。
 *
 * <p>用 PF4J 真实加载插件 JAR（{@code target/plugins/} 下的 jar 由 maven-jar-plugin 的
 * {@code stage-plugin-jar-for-load-test} 在 process-test-classes 阶段产出），经扩展点解析出
 * {@link GameEnhancementExtension} 再读目录 ⇒ 证明的是「平台装载形态下能读到这份声明」，
 * 而不是「这个类能 new 出来」。</p>
 *
 * <p>主应用侧四态判定（{@code EMPTY} 而非 {@code INVALID}/{@code ABSENT}）不在本用例里核，
 * 那是 {@code backend/core} 的唯一读者职责（design.md §16.3、V-01）；本用例钉的是
 * 装载形状 + 归属键 + 「读为空且不抛异常」这三项 AC-23 判据。</p>
 */
@DisplayName("plugin-dnf-tw 经 PF4J 加载后可读出扩展声明")
class DnfTwPluginLoadTest {

    /** dnf_tw.yml 声明的部署方式 + N5 支持集合外的两类，一并核对「不按 deployType 分支」。 */
    private static final List<String> EVERY_DEPLOY_TYPE =
            List.of("docker-compose", "linuxgsm-docker", "docker", "linuxgsm");

    /** 管理器留到用例结束再停：先 close 会关掉插件 ClassLoader，断言期取到的实例可能失效。 */
    private PluginManager manager;

    @AfterEach
    void stopPluginManager() {
        if (manager != null) {
            manager.stopPlugins();
        }
    }

    @Test
    @DisplayName("已加载插件清单含 plugin-dnf-tw 且处于 STARTED，归属键 getGameCode() = dnf_tw（AC-23 ①）")
    void pluginIsLoadedAndClaimsTheDnfTwGameCode() {
        PluginWrapper wrapper = loadedPlugin();
        assertEquals(PluginState.STARTED, wrapper.getPluginState(),
                "插件未启动则扩展点不会被平台读到");
        assertEquals("plugin-dnf-tw", wrapper.getDescriptor().getPluginId());
        assertEquals("dnf_tw", loadedExtension().getGameCode(),
                "归属键必须与 core/src/main/resources/games/dnf_tw.yml 的 game.code 一致");
    }

    @Test
    @DisplayName("各 deployType 读目录都得到空列表：不报错、不抛异常（AC-23 ②）")
    void catalogReadsEmptyForEveryDeployTypeWithoutThrowing() {
        GameEnhancementExtension extension = loadedExtension();

        for (String deployType : EVERY_DEPLOY_TYPE) {
            List<DeployVersionDeclaration> catalog = extension.getDeployVersions(deployType);
            assertNotNull(catalog, deployType + " 必须返回空列表而不是 null");
            assertEquals(0, catalog.size(),
                    "占位模板未填充 ⇒ 装载后读到的条目数为 0（deployType=" + deployType + "）");
        }
    }

    @Test
    @DisplayName("动态入口保持 SDK 默认空集：步骤集由目录条目承载（§16.2 解析顺序 ②）")
    void dynamicStepEntryKeepsTheSdkDefault() {
        DeployExtensionContext context =
                new DeployExtensionContext(1L, "dnf_tw", "docker-compose", null, Map.of());

        assertTrue(loadedExtension().getDeployExtensionSteps(context).isEmpty(),
                "dnf-tw 是纯声明型插件，不实现 ① 支");
    }

    @Test
    @DisplayName("JAR 与模块内不含插件前端产物（AC-23 ④ / FR-22）")
    void jarAndModuleCarryNoFrontendArtifacts() throws IOException {
        Path jar = stagedJar();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<String> names = zip.stream().map(entry -> entry.getName()).toList();
            assertTrue(names.stream().noneMatch(n -> n.startsWith("ui/")),
                    "插件前端产物不得进 JAR：" + names.stream().filter(n -> n.startsWith("ui/")).toList());
            assertTrue(names.stream().noneMatch(n -> n.endsWith(".html") || n.endsWith(".vue")),
                    "插件前端产物不得进 JAR：" + names.stream()
                            .filter(n -> n.endsWith(".html") || n.endsWith(".vue")).toList());
            assertTrue(names.contains("version-catalog.yml"), "版本目录模板要打进 JAR（声明来源 = 插件 JAR 内）");
            assertTrue(names.contains("plugin.properties"), "PF4J 插件元数据要打进 JAR");
        }
        Path moduleRoot = Path.of("..").toAbsolutePath().normalize();
        assertTrue(Files.notExists(moduleRoot.resolve("frontend")),
                "模块不得建 frontend/ 子工程（FR-22 / AC-23 ④；ADR-0003 亦不引入 standalone 模式）：" + moduleRoot);
    }

    private GameEnhancementExtension loadedExtension() {
        manager = new DefaultPluginManager(stagedPluginsRoot());
        manager.loadPlugins();
        manager.startPlugins();

        List<GameEnhancementExtension> extensions = manager.getExtensions(GameEnhancementExtension.class);
        // pf4j 3.10 的 AbstractExtensionFinder.readStorages() 同时读「插件内」与「应用 classpath 上」
        // 的 META-INF/extensions.idx。本模块的 target/classes 与插件 jar 同源 ⇒ 测试期会看到两份实例。
        // 只有由 PluginClassLoader 装载的那一份才是平台装载形态下读到的对象，断言认它。
        List<GameEnhancementExtension> fromPluginJar = extensions.stream()
                .filter(e -> e.getClass().getClassLoader() instanceof PluginClassLoader)
                .toList();
        assertEquals(1, fromPluginJar.size(),
                "plugin-dnf-tw 恰好提供一个扩展实现 :: plugins=" + manager.getPlugins()
                        + " exts=" + extensions.stream()
                        .map(e -> e.getClass().getName() + "@" + e.getClass().getClassLoader()).toList());
        return fromPluginJar.get(0);
    }

    private PluginWrapper loadedPlugin() {
        loadedExtension();
        PluginWrapper wrapper = manager.getPlugin("plugin-dnf-tw");
        assertNotNull(wrapper, "插件清单里没有 plugin-dnf-tw :: " + manager.getPlugins());
        return wrapper;
    }

    /**
     * 每次用例一个独立插件目录：PF4J 在 Windows 上持有 jar 句柄直到 JVM 退出，
     * 复用同一目录会让下一次 copy 撞 FileSystemException，把真正的断言失败埋进文件锁噪声里。
     */
    private static Path stagedPluginsRoot() {
        Path jar = stagedJar();
        try {
            Path root = Files.createTempDirectory("plugin-dnf-tw-load");
            Files.copy(jar, root.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException("无法为 plugin-dnf-tw 准备加载目录", e);
        }
    }

    private static Path stagedJar() {
        Path dir = Path.of("target", "plugins");
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("缺少 " + dir + "：插件 jar 由 maven-jar-plugin 的 "
                    + "stage-plugin-jar-for-load-test 执行在 process-test-classes 阶段产出；"
                    + "在 backend/ 下执行 mvn -pl plugin-dnf-tw/plugin-dnf-tw-core -am test");
        }
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(dir + " 下没有 jar"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

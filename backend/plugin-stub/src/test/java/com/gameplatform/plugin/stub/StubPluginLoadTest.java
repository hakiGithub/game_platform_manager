package com.gameplatform.plugin.stub;

import com.gameplatform.plugin.extension.GameEnhancementExtension;
import com.gameplatform.plugin.extension.deploy.DeployExtensionStepDeclaration;
import com.gameplatform.plugin.extension.deploy.DeployVersionDeclaration;
import com.gameplatform.plugin.extension.deploy.PatchStepDeclaration;
import com.gameplatform.plugin.extension.deploy.ScriptStepDeclaration;
import com.gameplatform.plugin.extension.deploy.StepKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginClassLoader;
import org.pf4j.PluginManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收资产自身的可用性核对（design.md §7.5 T-01、§7.1 B-01；MERC-14「怎么验」末段）。
 *
 * <p>用 PF4J 真实加载桩插件 JAR（{@code target/plugins/} 下的 jar 由 maven-jar-plugin 的
 * {@code stage-plugin-jar-for-load-test} 在 process-test-classes 阶段产出），经扩展点解析出
 * {@link GameEnhancementExtension} 再读 {@code getDeployVersions} ⇒ 证明「这份声明能被平台读出来」，
 * 而不只是「这个类能 new 出来」。</p>
 *
 * <p>本票不核对目录校验（{@code DeployVersionCatalogService} 在 stage 2），
 * 因此只断言声明的形状：条目数、默认条目、PATCH/SCRIPT 混排、类型闭合。</p>
 */
@DisplayName("桩插件经 PF4J 加载后可读出扩展声明")
class StubPluginLoadTest {

    /** 管理器留到用例结束再停：先 close 会关掉插件 ClassLoader，断言期取到的实例可能失效 */
    private PluginManager manager;

    @AfterEach
    void stopPluginManager() {
        if (manager != null) {
            manager.stopPlugins();
        }
    }

    @Test
    @DisplayName("加载 plugin-stub 后 getDeployVersions 得到 ≥2 条目，含一条 defaultEntry")
    void loadsAndReadsVersionCatalog() {
        GameEnhancementExtension extension = loadedExtension();

        assertEquals(StubExtension.GAME_CODE, extension.getGameCode());

        List<DeployVersionDeclaration> catalog = extension.getDeployVersions("docker-compose");
        assertTrue(catalog.size() >= 2, "PRD §5.1.1 要求 ≥2 条目，实际 " + catalog.size());
        assertEquals(1, catalog.stream().filter(e -> Boolean.TRUE.equals(e.defaultEntry())).count(),
                "同一游戏至多一条 defaultEntry（PRD §8.1）");
        assertTrue(catalog.stream().anyMatch(e -> !Boolean.TRUE.equals(e.defaultEntry())),
                "至少一条非默认条目，否则 S3 态无从构造");
    }

    @Test
    @DisplayName("非默认条目的步骤集是 PATCH/SCRIPT 混排的有序清单（BR-08 / AC-06 形状）")
    void nonDefaultEntriesCarryMixedOrderedStepSets() {
        List<DeployVersionDeclaration> catalog =
                loadedExtension().getDeployVersions("docker-compose");

        List<DeployVersionDeclaration> nonDefault = catalog.stream()
                .filter(e -> !Boolean.TRUE.equals(e.defaultEntry()))
                .toList();
        assertTrue(nonDefault.size() >= 1);

        for (DeployVersionDeclaration entry : nonDefault) {
            List<DeployExtensionStepDeclaration> steps = mergeInDeclarationOrder(entry);
            assertTrue(steps.size() >= 2, entry.versionId() + " 的步骤集应至少两步");
            assertEquals(EnumSet.of(StepKind.PATCH, StepKind.SCRIPT),
                    EnumSet.copyOf(steps.stream().map(DeployExtensionStepDeclaration::kind).toList()),
                    entry.versionId() + " 必须 PATCH 与 SCRIPT 混排");
            assertTrue(entry.versionId().matches("[A-Za-z0-9._-]+"), "versionId 字符集（PRD §8.1）");
        }

        // 序号自 1 起按声明序编号由主应用做，这里核对声明序稳定：首条非默认条目 = 一个补丁 + 一个脚本
        assertEquals(List.of(StepKind.PATCH, StepKind.SCRIPT),
                mergeInDeclarationOrder(nonDefault.get(0))
                        .stream().map(DeployExtensionStepDeclaration::kind).toList());
    }

    @Test
    @DisplayName("夹具包体摘要与声明一致，避免 sha256 与字节脱钩后被误判为实现缺陷")
    void fixtureChecksumsMatchDeclarations() throws IOException {
        Path base = Path.of("acceptance", "fixtures");

        assertEquals(StubExtension.PATCH_PACKAGE_SHA256,
                sha256(base.resolve("patch").resolve("stub-version-marker.zip")));
        assertEquals(StubExtension.REMOTE_SCRIPT_SHA256,
                sha256(base.resolve("scripts").resolve("stub-nonzero.sh")));
    }

    /** 把已构建的桩插件 jar 放进一个独立目录，交给 PF4J 加载并取出唯一扩展。 */
    private GameEnhancementExtension loadedExtension() {
        Path root = stagedPluginsRoot();
        manager = new DefaultPluginManager(root);
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
                "桩插件恰好提供一个扩展实现 :: plugins=" + manager.getPlugins()
                        + " exts=" + extensions.stream()
                        .map(e -> e.getClass().getName() + "@" + e.getClass().getClassLoader()).toList());
        return fromPluginJar.get(0);
    }

    /**
     * 目录条目步骤集的唯一解析顺序 ②：patches 拼接 scripts（design.md §16.2）。
     * 显式类型见证是<b>必要</b>的而非风格——反例见 {@code acceptance/javaprobe/README.md}。
     */
    private static List<DeployExtensionStepDeclaration> mergeInDeclarationOrder(DeployVersionDeclaration entry) {
        List<PatchStepDeclaration> patches = entry.patches();
        List<ScriptStepDeclaration> scripts = entry.scripts();
        return Stream.<DeployExtensionStepDeclaration>concat(patches.stream(), scripts.stream()).toList();
    }

    /**
     * 每次用例一个独立插件目录：PF4J 在 Windows 上持有 jar 句柄直到 JVM 退出，
     * 复用同一目录会让下一次 copy 撞 {@code FileSystemException}，
     * 把真正的断言失败埋进文件锁噪声里。目录不主动删（删不掉是常态，不是缺陷）。
     */
    private static Path stagedPluginsRoot() {
        Path jar = stagedJar();
        try {
            Path root = Files.createTempDirectory("plugin-stub-load");
            Files.copy(jar, root.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException("无法为桩插件准备加载目录", e);
        }
    }

    private static Path stagedJar() {
        Path dir = Path.of("target", "plugins");
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("缺少 " + dir + "：桩插件 jar 由 maven-jar-plugin 的 "
                    + "stage-plugin-jar-for-load-test 执行在 process-test-classes 阶段产出；"
                    + "在 backend/ 下执行 mvn -Pacceptance-assets -pl plugin-stub -am test");
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(dir + " 下没有 jar"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

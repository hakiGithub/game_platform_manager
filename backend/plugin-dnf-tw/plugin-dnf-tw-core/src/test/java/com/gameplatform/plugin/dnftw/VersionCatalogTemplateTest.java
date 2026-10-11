package com.gameplatform.plugin.dnftw;

import com.gameplatform.plugin.extension.deploy.DeployVersionDeclaration;
import com.gameplatform.plugin.extension.deploy.PatchStepDeclaration;
import com.gameplatform.plugin.extension.deploy.ScriptPosition;
import com.gameplatform.plugin.extension.deploy.ScriptStepDeclaration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 占位模板的读取形状核对（design.md §7.3 B-15；PRD FR-23 / §8.6；AC-23 ②、AC-24 ⑤⑥）。
 *
 * <p>三件事分别钉住：</p>
 * <ol>
 *   <li><b>本期读为空</b>：出厂模板 → 0 条目、不抛异常（主应用据此得 {@code EMPTY}，不是 {@code INVALID}）。</li>
 *   <li><b>空来自过滤而不是文件本来就空</b>：同一份模板里确实有骨架条目，只是每条都带未填充标记。</li>
 *   <li><b>「填充即生效」不需要改代码</b>：只换内容（{@link VersionCatalogTemplate#parse(String)} 与
 *       {@link VersionCatalogTemplate#read()} 共用同一段解析 / 过滤 / 搬运代码，只差字节来源），
 *       条目即对外声明。</li>
 * </ol>
 *
 * <p>第 3 项用的取值是<b>测试自有值</b>（{@code ut-*} / 回环地址），只服务形状核对：不进
 * {@code version-catalog.yml}、不进插件 JAR（测试资源不参与打包）、不进产品目录，
 * 也不得被登记为 dnf-tw 真实版本的验收结果（AC-26 ②③、BR-15 ①②③）。</p>
 */
@DisplayName("dnf-tw 版本目录占位模板：未填充即读为空，填充即生效")
class VersionCatalogTemplateTest {

    @Test
    @DisplayName("出厂模板读出 0 条目：空列表、不报错、不抛异常（AC-23 ② / AC-24）")
    void shippedTemplateReadsAsEmptyCatalog() {
        List<DeployVersionDeclaration> catalog = VersionCatalogTemplate.read();

        assertNotNull(catalog, "未填充必须返回空列表，不可为 null");
        assertEquals(0, catalog.size(), "占位模板未填充 ⇒ 对外声明的条目数必须为 0（PRD §8.6）");
    }

    @Test
    @DisplayName("空目录来自「未填充标记过滤」，不是文件本身没有条目")
    void emptinessComesFromFilteringNotFromAnEmptyFile() {
        String template = shippedTemplateText();
        Object document = new Yaml().load(template);
        assertTrue(document instanceof Map<?, ?>, "模板顶层必须是字段映射");
        Object versions = ((Map<?, ?>) document).get("versions");
        assertTrue(versions instanceof List<?> list && !list.isEmpty(),
                "模板要按 §8.1～§8.3 列出完整字段骨架，所以文件里确实有条目");

        assertEquals(0, VersionCatalogTemplate.parse(template).size(),
                "这些条目都带未填充标记 ⇒ 读取结果为空");
    }

    @Test
    @DisplayName("模板文件与模块元数据两处都带「占位模板，不可上线」，且正文无任何示例值（AC-24 ⑤⑥）")
    void templateCarriesTheNoticeAndHoldsNoExampleValues() {
        String template = shippedTemplateText();
        assertTrue(template.contains(VersionCatalogTemplate.PLACEHOLDER_NOTICE),
                "AC-24 ⑤：模板文件本身要显式标注「占位模板，不可上线」");
        assertEquals(VersionCatalogTemplate.PLACEHOLDER_NOTICE, DnfTwExtension.PLACEHOLDER_NOTICE,
                "两处标注必须是同一句登记词面，不能各写一套");
        assertTrue(new DnfTwExtension().getDescription().contains(VersionCatalogTemplate.PLACEHOLDER_NOTICE),
                "模块元数据侧（扩展点 description → 插件清单）也要看得见这句标注");

        // AC-24 ⑥：正文（去掉注释后）每个带值的字段只能还是未填充标记
        for (String line : template.split("\r?\n")) {
            String value = valueOfCodeLine(line);
            if (value == null || value.isEmpty()) {
                continue;
            }
            assertEquals(VersionCatalogTemplate.UNFILLED_MARKER, value,
                    "模板正文出现非未填充标记的取值 ⇒ 有示例值外泄风险：" + line);
        }
        assertFalse(template.contains("http://") || template.contains("https://"),
                "AC-24 ⑥：模板不得含任何 URL 字面量（N-12）");
    }

    @Test
    @DisplayName("只替换模板内容即让条目对外声明：不改代码、不改声明接口（FR-23 / BR-15 ④）")
    void filledTemplateBecomesVisibleWithoutAnyCodeChange() {
        List<DeployVersionDeclaration> catalog = VersionCatalogTemplate.parse("""
                versions:
                  - versionId: ut-filled-a
                    displayName: UT 已填充条目甲
                    default: true
                """);

        assertEquals(1, catalog.size(), "填完的条目必须对外声明，否则「填充即生效」不成立");
        DeployVersionDeclaration entry = catalog.get(0);
        assertEquals("ut-filled-a", entry.versionId());
        assertEquals("UT 已填充条目甲", entry.displayName());
        assertEquals(Boolean.TRUE, entry.defaultEntry());
        assertNull(entry.imageTag(), "未写的可选字段搬运成 null，不编造取值");
        assertEquals(List.of(), entry.patches(), "缺省无补丁步骤");
        assertEquals(List.of(), entry.scripts(), "缺省无脚本步骤");
    }

    @Test
    @DisplayName("同目录内混排：填完的条目生效，仍带标记的条目整条不生效（未填完即不生效）")
    void partiallyFilledEntryIsDroppedWholeNotHalfApplied() {
        List<DeployVersionDeclaration> catalog = VersionCatalogTemplate.parse("""
                versions:
                  - versionId: ut-filled-a
                    patches:
                      - label: UT 补丁
                        url: http://127.0.0.1/ut/patch.zip
                        targetPath: ut-patch
                        fatal: false
                  - versionId: ut-half-filled
                    patches:
                      - label: UT 还没填完
                        url: UNFILLED
                """);

        assertEquals(List.of("ut-filled-a"),
                catalog.stream().map(DeployVersionDeclaration::versionId).toList(),
                "未填完的那条不能被半采纳（N-12 / BR-15 ①），填完的那条按声明序生效");

        PatchStepDeclaration patch = catalog.get(0).patches().get(0);
        assertEquals("UT 补丁", patch.label());
        assertEquals("http://127.0.0.1/ut/patch.zip", patch.url());
        assertEquals("ut-patch", patch.targetPath());
        assertFalse(patch.fatal(), "§8.2 缺省 true，但显式声明不得被覆盖（BR-04）");
        assertNull(patch.sha256(), "未声明的可选字段搬运成 null");
    }

    @Test
    @DisplayName("脚本步骤搬运保留声明序与 §8.3 缺省值")
    void scriptStepsKeepOrderAndDefaults() {
        List<DeployVersionDeclaration> catalog = VersionCatalogTemplate.parse("""
                versions:
                  - versionId: ut-filled-a
                    scripts:
                      - label: UT 甲
                        content: echo UT-A
                      - label: UT 乙
                        content: echo UT-B
                        position: host
                        timeoutMs: 5000
                """);

        List<ScriptStepDeclaration> scripts = catalog.get(0).scripts();
        assertEquals(List.of("UT 甲", "UT 乙"), scripts.stream().map(ScriptStepDeclaration::label).toList());
        assertEquals(ScriptPosition.HOST, scripts.get(0).position(), "§8.3 缺省即 host");
        assertTrue(scripts.get(0).fatal(), "§8.3 缺省 fatal = true");
        assertNull(scripts.get(0).timeoutMs(), "未声明超时由主应用取缺省值，插件侧不填");
        assertEquals(Long.valueOf(5000L), scripts.get(1).timeoutMs());
    }

    @Test
    @DisplayName("本期不合法的 position 声明原样搬运，交主应用判声明不合法（design.md §14.5）")
    void containerPositionIsPassedThroughForTheHostToReject() {
        List<DeployVersionDeclaration> catalog = VersionCatalogTemplate.parse("""
                versions:
                  - versionId: ut-filled-a
                    scripts:
                      - label: UT 容器内
                        content: echo UT
                        position: container
                """);

        assertEquals(ScriptPosition.CONTAINER, catalog.get(0).scripts().get(0).position(),
                "插件侧替主应用过滤就等于把规则 N4 的判定对象抹掉，故只搬运不改写");
    }

    @Test
    @DisplayName("模板出现未定义字段或形状不对时不猜：抛异常交主应用归 ABSENT")
    void unknownKeysAndBadShapesAreNotGuessed() {
        IllegalStateException unknown = assertThrows(IllegalStateException.class,
                () -> VersionCatalogTemplate.parse("""
                        versions:
                          - versionId: ut-filled-a
                            version: ut
                        """));
        assertTrue(unknown.getMessage().contains("version"), "归因要指名是哪个字段：" + unknown.getMessage());

        assertThrows(IllegalStateException.class, () -> VersionCatalogTemplate.parse("""
                versions:
                  - versionId: ut-filled-a
                    default: maybe
                """));
        assertThrows(IllegalStateException.class, () -> VersionCatalogTemplate.parse("""
                versions:
                  - versionId: ut-filled-a
                    scripts:
                      - label: UT
                        content: echo UT
                        position: ssh
                """));
    }

    private static String shippedTemplateText() {
        try (InputStream in = VersionCatalogTemplateTest.class.getResourceAsStream(VersionCatalogTemplate.RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("缺少版本目录模板资源 " + VersionCatalogTemplate.RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 取一行的正文取值：整行注释 / 无正文 → {@code null}；{@code key:} 这类只带子节点的键 → 空串。
     */
    private static String valueOfCodeLine(String line) {
        String trimmed = line.stripLeading();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null;
        }
        int comment = trimmed.indexOf('#');
        String code = (comment < 0 ? trimmed : trimmed.substring(0, comment)).trim();
        int colon = code.indexOf(':');
        if (colon < 0) {
            return null;
        }
        return code.substring(colon + 1).trim();
    }
}

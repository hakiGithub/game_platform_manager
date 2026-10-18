package com.gameplatform.plugin.dnftw;

import com.gameplatform.plugin.extension.deploy.DeployVersionDeclaration;
import com.gameplatform.plugin.extension.deploy.PatchStepDeclaration;
import com.gameplatform.plugin.extension.deploy.ScriptPosition;
import com.gameplatform.plugin.extension.deploy.ScriptStepDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * dnf-tw 版本目录模板的唯一读取路径（design.md §7.3 B-15；PRD §8.6 / FR-23）。
 *
 * <p><b>形状</b>：解析模板 → 按「条目内是否仍带未填充标记」逐条过滤 → 把留下的条目逐字段搬运成
 * SDK 声明类型。三步对任何模板内容都走同一段代码，<b>没有</b>「模板未填充 ⇒ 直接返回空列表」
 * 这类按内容分支的支路：本期 {@code version-catalog.yml} 读为空是过滤的结果，不是特判的结果。
 * 这正是「真实资料到位后只替换目录内容即生效」（FR-23 / BR-15 ④）的结构证据。</p>
 *
 * <p><b>未填充标记</b>：PRD §8.6 把「标记写法与模板载体归技术设计」交下来，此处定为
 * 字段值 {@value #UNFILLED_MARKER}（比对忽略大小写，小写 {@code unfilled} 手误同样不生效）。
 * 判定是<b>整棵条目树</b>的扫描：任一字段的值仍是标记 ⇒
 * 该条整条不对外声明（未填完即不生效），不会出现「半截条目被当成可部署版本」的假象
 * （N-12 / BR-15 ①）。</p>
 *
 * <p><b>不猜</b>：出现模板未定义的字段名、或取值形状不对（布尔位写成别词、
 * {@code position} 落在 host/container 之外、字符串字段被 YAML 解析成数字或布尔）一律抛异常，
 * 让主应用把目录归 {@code ABSENT} 并记 warn（design.md §16.3），而不是静默忽略该字段、
 * 也不是隐式转写成另一个值——两者都会得到一份「Owner 以为声明了、平台读到的却不是」的目录。
 * YAML 重复键的后写覆盖同理由 {@code allowDuplicateKeys = false} 关掉。</p>
 *
 * <p><b>空目录的三种写法同形</b>：空文件、{@code versions:} 整段留空、{@code versions: []}
 * 都是 0 条目，主应用据此判 {@code EMPTY}（不是 {@code INVALID}）。</p>
 *
 * <p><b>校验不在本类</b>：§8.1～§8.3 的必填 / 唯一 / 字符集 / N1…N5 由主应用唯一读者执行
 * （design.md §16.3），本类只做形状搬运；把校验搬进插件会让主应用的「不合法即整目录」判定
 * 与声明侧的形状检查各判一半。</p>
 */
final class VersionCatalogTemplate {

    /** 模板载体：JAR 内单一 YAML 资源（打进插件 JAR，不是运行时可配，design.md §16.2「声明来源」）。 */
    static final String RESOURCE = "/version-catalog.yml";

    /** 未填充标记（PRD §8.6 各待填字段的标记写法）。比对忽略大小写，{@code unfilled} 手误同样不生效。 */
    static final String UNFILLED_MARKER = "UNFILLED";

    private static final Logger LOG = LoggerFactory.getLogger(VersionCatalogTemplate.class);

    private static final Set<String> DOCUMENT_KEYS = Set.of("versions");
    private static final Set<String> ENTRY_KEYS = Set.of(
            "versionId", "displayName", "imageTag", "default", "patches", "scripts");
    private static final Set<String> PATCH_KEYS = Set.of(
            "label", "url", "targetPath", "sha256", "includePattern", "format", "fatal");
    private static final Set<String> SCRIPT_KEYS = Set.of(
            "label", "content", "url", "sha256", "position", "fatal", "timeoutMs");

    private VersionCatalogTemplate() {
    }

    /**
     * 解析器：{@code allowDuplicateKeys = false}。
     *
     * <p>默认行为是后写覆盖，{@code versionId} 写两遍时静默取最后一个 ⇒ Owner 以为声明了甲条、
     * 平台读到的却是乙条。重复键必须抛，与「未定义字段不静默忽略」是同一条规约。</p>
     */
    private static Yaml newYaml() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        return new Yaml(options);
    }

    /**
     * 读取插件 JAR 内的版本目录模板，返回对外声明的条目（按声明序）。
     *
     * @return 已填充条目的声明列表；模板全部未填充时为空列表，<b>不</b>返回 null、<b>不</b>抛异常
     */
    static List<DeployVersionDeclaration> read() {
        try (InputStream in = VersionCatalogTemplate.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("插件 JAR 内缺少版本目录模板 " + RESOURCE);
            }
            return declare(newYaml().load(in), RESOURCE);
        } catch (IOException e) {
            throw new IllegalStateException("版本目录模板读取失败 " + RESOURCE, e);
        }
    }

    /**
     * 同一读取路径的入参入口：{@link #read()} 去掉「从哪里取字节」之后就是本方法。
     *
     * <p>存在的意义是让「替换目录内容 ⇒ 条目即对外生效」可以在<b>不改动 JAR 内模板</b>的前提下
     * 被核对到（{@code VersionCatalogTemplateTest}），且核对时走的仍是同一段解析 / 过滤 / 搬运代码。</p>
     */
    static List<DeployVersionDeclaration> parse(String templateText) {
        return declare(newYaml().load(templateText), "入参模板");
    }

    private static List<DeployVersionDeclaration> declare(Object document, String origin) {
        if (document == null) {
            // 空文件 = 0 条目，与「文件里只有一条未填充骨架」是同一个可观察结果（都是空目录）
            return List.of();
        }
        Map<String, Object> root = requireMap(document, origin);
        requireKnownKeys(root, DOCUMENT_KEYS, origin);
        Object versionsNode = root.get("versions");
        // `versions:` 整段留空与「空文件」是同一个可观察结果（0 条目 ⇒ 主应用判 EMPTY），
        // Owner 把条目删干净后留一个空键不该得到 INVALID。
        List<Object> entries = versionsNode == null ? List.of()
                : requireList(versionsNode, origin + ".versions");

        List<DeployVersionDeclaration> declared = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            String at = origin + ".versions[" + i + "]";
            Map<String, Object> entry = requireMap(entries.get(i), at);
            requireKnownKeys(entry, ENTRY_KEYS, at);

            List<String> unfilled = collectUnfilledPaths(entry, at, new ArrayList<>());
            if (!unfilled.isEmpty()) {
                LOG.info("版本目录模板条目 {} 仍带未填充标记 {}（字段：{}），按「未填完即不对外声明」跳过",
                        at, UNFILLED_MARKER, String.join("、", unfilled));
                continue;
            }
            declared.add(toDeclaration(entry, at));
        }
        return List.copyOf(declared);
    }

    private static DeployVersionDeclaration toDeclaration(Map<String, Object> entry, String at) {
        return new DeployVersionDeclaration(
                text(entry, "versionId", at),
                text(entry, "displayName", at),
                text(entry, "imageTag", at),
                flag(entry, "default", false, at),
                toSteps(entry.get("patches"), at + ".patches", PATCH_KEYS, (step, stepAt) -> new PatchStepDeclaration(
                        text(step, "label", stepAt),
                        text(step, "url", stepAt),
                        text(step, "targetPath", stepAt),
                        text(step, "sha256", stepAt),
                        text(step, "includePattern", stepAt),
                        text(step, "format", stepAt),
                        flag(step, "fatal", true, stepAt))),
                toSteps(entry.get("scripts"), at + ".scripts", SCRIPT_KEYS, (step, stepAt) -> new ScriptStepDeclaration(
                        text(step, "label", stepAt),
                        text(step, "content", stepAt),
                        text(step, "url", stepAt),
                        text(step, "sha256", stepAt),
                        position(step, stepAt),
                        flag(step, "fatal", true, stepAt),
                        number(step, "timeoutMs", stepAt))));
    }

    /** stepAt 一并交给 mapper：出错时要指名到「哪一条目的第几个步骤的哪个字段」，不能只报到条目层。 */
    private static <T> List<T> toSteps(Object raw, String at, Set<String> knownKeys,
                                       BiFunction<Map<String, Object>, String, T> mapper) {
        if (raw == null) {
            return List.of();
        }
        List<Object> steps = requireList(raw, at);
        List<T> mapped = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            String stepAt = at + "[" + i + "]";
            Map<String, Object> step = requireMap(steps.get(i), stepAt);
            requireKnownKeys(step, knownKeys, stepAt);
            mapped.add(mapper.apply(step, stepAt));
        }
        return List.copyOf(mapped);
    }

    /** §8.3 缺省即 {@code host}；{@code container} 原样搬运，由主应用判声明不合法（design.md §14.5）。 */
    private static ScriptPosition position(Map<String, Object> step, String at) {
        Object raw = step.get("position");
        if (raw == null) {
            return ScriptPosition.HOST;
        }
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "host" -> ScriptPosition.HOST;
            case "container" -> ScriptPosition.CONTAINER;
            default -> throw new IllegalStateException(
                    at + ".position=" + raw + " 不是 host / container 之一，模板侧不做猜测");
        };
    }

    /**
     * 字符串字段取值：非字符串一律抛，不做 {@code String.valueOf} 的隐式转写。
     *
     * <p>YAML 1.1 会把真实世界里的版号解析成别的类型——{@code 1.20} 是 Double（读回来 {@code 1.2}，
     * 尾零没了）、{@code 0012} 是 Integer（前导零没了）、{@code yes} 是 Boolean、
     * {@code 1:20} 是 sexagesimal 整数 80。静默转写会得到一条「Owner 写的是 1.20、平台声明的是 1.2」
     * 的目录条目，而 {@code versionId} 是部署载荷与镜像 tag 拼装的键，差一个字符就是另一个版本。</p>
     */
    private static String text(Map<String, Object> node, String key, String at) {
        Object raw = node.get(key);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String value)) {
            throw new IllegalStateException(at + "." + key + "=" + raw
                    + " 不是字符串（解析为 " + describe(raw) + "），模板侧不做隐式转写——"
                    + "版号这类字符串字段一律加引号");
        }
        return value.trim().isEmpty() ? null : value.trim();
    }

    private static boolean flag(Map<String, Object> node, String key, boolean defaultValue, String at) {
        Object raw = node.get(key);
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Boolean booleanValue) {
            return booleanValue;
        }
        String value = String.valueOf(raw).trim();
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalStateException(at + "." + key + "=" + raw + " 不是布尔值");
    }

    private static Long number(Map<String, Object> node, String key, String at) {
        Object raw = node.get(key);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(at + "." + key + "=" + raw + " 不是整数", e);
        }
    }

    /** 扫遍条目树，收集值仍为未填充标记的字段路径。 */
    private static List<String> collectUnfilledPaths(Object node, String path, List<String> out) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                collectUnfilledPaths(e.getValue(), path + "." + e.getKey(), out);
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                collectUnfilledPaths(list.get(i), path + "[" + i + "]", out);
            }
        } else if (node instanceof String text && UNFILLED_MARKER.equalsIgnoreCase(text.trim())) {
            out.add(path);
        }
        return out;
    }

    private static Map<String, Object> requireMap(Object raw, String at) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalStateException(at + " 不是字段映射（" + describe(raw) + "）");
        }
        Map<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            converted.put(String.valueOf(e.getKey()), e.getValue());
        }
        return converted;
    }

    private static List<Object> requireList(Object raw, String at) {
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException(at + " 不是列表（" + describe(raw) + "）");
        }
        return List.copyOf(list);
    }

    private static void requireKnownKeys(Map<String, Object> node, Set<String> knownKeys, String at) {
        List<String> unknown = node.keySet().stream().filter(k -> !knownKeys.contains(k)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(at + " 含版本目录未定义的字段 " + unknown
                    + "（模板侧不静默忽略，避免「Owner 以为声明了、平台读到的不是那份」）");
        }
    }

    private static String describe(Object raw) {
        return raw == null ? "null" : raw.getClass().getSimpleName();
    }
}

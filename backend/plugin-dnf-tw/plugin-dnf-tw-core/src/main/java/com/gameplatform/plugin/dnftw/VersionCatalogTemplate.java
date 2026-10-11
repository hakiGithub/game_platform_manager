package com.gameplatform.plugin.dnftw;

import com.gameplatform.plugin.extension.deploy.DeployVersionDeclaration;
import com.gameplatform.plugin.extension.deploy.PatchStepDeclaration;
import com.gameplatform.plugin.extension.deploy.ScriptPosition;
import com.gameplatform.plugin.extension.deploy.ScriptStepDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * dnf-tw 版本目录模板的唯一读取路径（design.md §7.3 B-15；PRD §8.6 / FR-23）。
 *
 * <p><b>形状</b>：解析模板 → 按「条目内是否仍带未填充标记」逐条过滤 → 把留下的条目逐字段搬运成
 * SDK 声明类型。三步对任何模板内容都走同一段代码，<b>没有</b>「模板未填充 ⇒ 直接返回空列表」
 * 这类按内容分支的支路：本期 {@code version-catalog.yml} 读为空是过滤的结果，不是特判的结果。
 * 这正是「真实资料到位后只替换目录内容即生效」（FR-23 / BR-15 ④）的结构证据。</p>
 *
 * <p><b>未填充标记</b>：PRD §8.6 把「标记写法与模板载体归技术设计」交下来，此处定为
 * 字段值 {@value #UNFILLED_MARKER}。判定是<b>整棵条目树</b>的扫描：任一字段的值仍是标记 ⇒
 * 该条整条不对外声明（未填完即不生效），不会出现「半截条目被当成可部署版本」的假象
 * （N-12 / BR-15 ①）。</p>
 *
 * <p><b>不猜</b>：出现模板未定义的字段名、或取值形状不对（布尔位写成别词、
 * {@code position} 落在 host/container 之外）一律抛异常，让主应用把目录归 {@code ABSENT}
 * 并记 warn（design.md §16.3），而不是静默忽略该字段——静默忽略会得到一份「Owner 以为声明了、
 * 平台读到的却不是」的目录。</p>
 *
 * <p><b>校验不在本类</b>：§8.1～§8.3 的必填 / 唯一 / 字符集 / N1…N5 由主应用唯一读者执行
 * （design.md §16.3），本类只做形状搬运；把校验搬进插件会让主应用的「不合法即整目录」判定
 * 与声明侧的形状检查各判一半。</p>
 */
final class VersionCatalogTemplate {

    /** 模板载体：JAR 内单一 YAML 资源（打进插件 JAR，不是运行时可配，design.md §16.2「声明来源」）。 */
    static final String RESOURCE = "/version-catalog.yml";

    /** 未填充标记（PRD §8.6 各待填字段的标记写法）。 */
    static final String UNFILLED_MARKER = "UNFILLED";

    /** 模板必须显式携带的标注（AC-24 ⑤：模板文件与模块元数据两处都可见）。 */
    static final String PLACEHOLDER_NOTICE = "占位模板，不可上线";

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
     * 读取插件 JAR 内的版本目录模板，返回对外声明的条目（按声明序）。
     *
     * @return 已填充条目的声明列表；模板全部未填充时为空列表，<b>不</b>返回 null、<b>不</b>抛异常
     */
    static List<DeployVersionDeclaration> read() {
        try (InputStream in = VersionCatalogTemplate.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("插件 JAR 内缺少版本目录模板 " + RESOURCE);
            }
            return declare(new Yaml().load(in), RESOURCE);
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
        return declare(new Yaml().load(templateText), "入参模板");
    }

    private static List<DeployVersionDeclaration> declare(Object document, String origin) {
        if (document == null) {
            // 空文件 = 0 条目，与「文件里只有一条未填充骨架」是同一个可观察结果（都是空目录）
            return List.of();
        }
        Map<String, Object> root = requireMap(document, origin);
        requireKnownKeys(root, DOCUMENT_KEYS, origin);
        List<Object> entries = requireList(root.get("versions"), origin + ".versions");

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
                text(entry, "versionId"),
                text(entry, "displayName"),
                text(entry, "imageTag"),
                flag(entry, "default", false, at),
                toSteps(entry.get("patches"), at + ".patches", PATCH_KEYS, step -> new PatchStepDeclaration(
                        text(step, "label"),
                        text(step, "url"),
                        text(step, "targetPath"),
                        text(step, "sha256"),
                        text(step, "includePattern"),
                        text(step, "format"),
                        flag(step, "fatal", true, at))),
                toSteps(entry.get("scripts"), at + ".scripts", SCRIPT_KEYS, step -> new ScriptStepDeclaration(
                        text(step, "label"),
                        text(step, "content"),
                        text(step, "url"),
                        text(step, "sha256"),
                        position(step, at),
                        flag(step, "fatal", true, at),
                        number(step, "timeoutMs", at))));
    }

    private static <T> List<T> toSteps(Object raw, String at, Set<String> knownKeys,
                                       Function<Map<String, Object>, T> mapper) {
        if (raw == null) {
            return List.of();
        }
        List<Object> steps = requireList(raw, at);
        List<T> mapped = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            String stepAt = at + "[" + i + "]";
            Map<String, Object> step = requireMap(steps.get(i), stepAt);
            requireKnownKeys(step, knownKeys, stepAt);
            mapped.add(mapper.apply(step));
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

    private static String text(Map<String, Object> node, String key) {
        Object raw = node.get(key);
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
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
        } else if (node instanceof String text && UNFILLED_MARKER.equals(text.trim())) {
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

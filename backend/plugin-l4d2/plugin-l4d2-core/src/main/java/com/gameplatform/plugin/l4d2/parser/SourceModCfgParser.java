package com.gameplatform.plugin.l4d2.parser;

import com.gameplatform.plugin.l4d2.vo.config.ConfigItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SourceMod cfg 文件解析器。
 *
 * <p>解析形如 {@code "key" "value" // comment} 的行，支持从注释提取元数据：
 * <ul>
 *   <li>{@code // Default: xxx} 默认值</li>
 *   <li>{@code // Min: xxx} / {@code // Minimum: xxx} 最小值</li>
 *   <li>{@code // Max: xxx} / {@code // Maximum: xxx} 最大值</li>
 *   <li>其他注释作为描述</li>
 * </ul>
 *
 * <p>控制台命令黑名单：以 {@code sm} / {@code exec} / {@code meta} / {@code rcon} 开头的行
 * （独立单词，非前缀）不视为 CVAR，避免把 {@code sm plugins load xxx} 误识别为配置项。
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@Component
public class SourceModCfgParser {

    // 键支持带引号与不带引号两种形式：SourceMod 自动生成的 cfg（sm cfgbuilder）
    // 键不加引号（如 l4d2_health_Head_Boomer "2"），手工编辑的配置常见双引号键
    private static final Pattern KV_PATTERN =
            Pattern.compile("\"?([^\"\\s]+)\"?\\s+\"([^\"]*)\"\\s*(?://\\s*(.*))?");

    private static final Pattern DEFAULT_PATTERN = Pattern.compile(
            "Default:\\s*(?:\"([^\"]*)\"|'([^']*)'|(\\S+))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RANGE_PATTERN = Pattern.compile(
            "\\b(Min(?:imum)?|Max(?:imum)?):\\s*(?:\"([^\"]*)\"|'([^']*)'|(\\S+))",
            Pattern.CASE_INSENSITIVE);

    /**
     * 控制台命令黑名单：以这些关键字开头的行（独立单词，非前缀）不视为 CVAR。
     *
     * <p>对齐 l4d2-server-next config_parser.go consoleCmdNames。
     * 注意：sm_dp、sm_cvar 等 CVAR 不会被误识别（它们以引号开头，首个 token 是 "sm_dp"）。
     */
    private static final Set<String> CONSOLE_CMD_BLACKLIST = Set.of(
            "sm",       // SourceMod 命令前缀：sm plugins load/unload/list
            "exec",     // exec server.cfg
            "meta",     // Metamod 命令前缀
            "rcon"      // rcon password
    );

    public List<ConfigItem> parse(String content) {
        List<ConfigItem> items = new ArrayList<>();
        if (content == null) return items;
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("//")) continue;
            if (isConsoleCommand(line)) continue;
            Matcher m = KV_PATTERN.matcher(line);
            if (!m.matches()) continue;
            ConfigItem item = new ConfigItem();
            item.setKey(m.group(1));
            item.setValue(m.group(2));
            item.setLineNumber(i + 1);
            String comment = m.group(3);
            if (comment != null) parseMetadata(comment, item);
            // SourceMod 自动生成格式：Default/Min/Max 与描述在 KV 行上方的 "// " 注释块中，
            // 向上扫描提取（缺省字段才回填，同行注释优先；空行/非注释行终止扫描）
            int j = i - 1;
            StringBuilder blockDesc = new StringBuilder();
            while (j >= 0) {
                String prev = lines[j].trim();
                if (!prev.startsWith("//")) break;
                String meta = prev.substring(2).trim();
                if (meta.isEmpty() || meta.equals("-")) break;
                parseDefaultMetadata(meta, item);
                parseRangeMetadata(meta, item);
                blockDesc.insert(0, meta + "\n");
                j--;
            }
            if (item.getDescription() == null && blockDesc.length() > 0) {
                String desc = cleanDescription(blockDesc.toString());
                if (!desc.isEmpty()) item.setDescription(desc);
            }
            items.add(item);
        }
        return items;
    }

    /**
     * 检查行首是否为控制台命令（按空白分隔后的第一个 token 命中黑名单）。
     *
     * <p>CVAR 行如 {@code "sm_dp" "2.5"} 的首个 token 是 {@code "sm_dp"}（含引号），
     * 剥离前导引号后为 {@code sm_dp"}，不会命中 {@code sm} 黑名单。
     *
     * @param lineTrimmed 已去首尾空白的行
     * @return true 表示该行是控制台命令，应跳过
     */
    private boolean isConsoleCommand(String lineTrimmed) {
        if (lineTrimmed.isEmpty() || lineTrimmed.startsWith("//")) {
            return false;
        }
        String firstToken;
        int spaceIdx = lineTrimmed.indexOf(' ');
        if (spaceIdx > 0) {
            firstToken = lineTrimmed.substring(0, spaceIdx);
        } else {
            firstToken = lineTrimmed;
        }
        // 剥离前导引号（CVAR 行的 key 总是以引号开头）
        if (firstToken.startsWith("\"")) {
            firstToken = firstToken.substring(1);
        }
        return CONSOLE_CMD_BLACKLIST.contains(firstToken);
    }

    public String serialize(List<ConfigItem> items, String originalContent) {
        if (originalContent == null) originalContent = "";
        String[] lines = originalContent.split("\n", -1);
        for (ConfigItem item : items) {
            int idx = item.getLineNumber() - 1;
            if (idx < 0 || idx >= lines.length) continue;
            String line = lines[idx];
            Matcher m = KV_PATTERN.matcher(line.trim());
            if (m.matches()) {
                // 保留原行的键引号风格：带引号键写回 \"key\" \"value\"，
                // 不带引号键（SourceMod 自动生成格式）写回 key \"value\"——
                // 若统一补引号会把键拼进前缀造成键值重复（回归：保存后行损坏）
                String trimmed = line.trim();
                String indent = line.substring(0, line.length() - line.stripLeading().length());
                boolean quotedKey = trimmed.startsWith("\"");
                String keyPart = quotedKey ? "\"" + item.getKey() + "\"" : item.getKey();
                String comment = m.group(3) != null ? " // " + m.group(3) : "";
                lines[idx] = indent + keyPart + " \"" + item.getValue() + "\"" + comment;
            }
        }
        return String.join("\n", lines);
    }

    private void parseMetadata(String comment, ConfigItem item) {
        parseDefaultMetadata(comment, item);
        parseRangeMetadata(comment, item);
        String desc = cleanDescription(comment);
        if (!desc.isEmpty()) item.setDescription(desc);
    }

    private void parseDefaultMetadata(String comment, ConfigItem item) {
        if (item.getDefaultValue() != null) return;
        Matcher matcher = DEFAULT_PATTERN.matcher(comment);
        if (matcher.find()) {
            item.setDefaultValue(firstNonNull(matcher.group(1), matcher.group(2), matcher.group(3)));
        }
    }

    private void parseRangeMetadata(String comment, ConfigItem item) {
        Matcher matcher = RANGE_PATTERN.matcher(comment);
        while (matcher.find()) {
            String rawValue = firstNonNull(matcher.group(2), matcher.group(3), matcher.group(4));
            Double value;
            try {
                value = Double.parseDouble(rawValue);
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (matcher.group(1).toLowerCase(Locale.ROOT).startsWith("min")) {
                if (item.getMin() == null) item.setMin(value);
            } else if (item.getMax() == null) {
                item.setMax(value);
            }
        }
    }

    private String cleanDescription(String comment) {
        String withoutDefault = DEFAULT_PATTERN.matcher(comment).replaceAll("");
        String withoutRange = RANGE_PATTERN.matcher(withoutDefault).replaceAll("");
        return withoutRange
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *(\\n+) *", "$1")
                .trim();
    }

    private String firstNonNull(String first, String second, String third) {
        if (first != null) return first;
        if (second != null) return second;
        return third;
    }
}
